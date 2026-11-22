package com.dayforge.data.repository

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Same restored image and batched initialization for both strategies. The only candidate
 * switch shares complete-input pure fact blocks between full preparation and publication.
 * Real count command, real screen, real subsequent ACK; no background competition. */
@RunWith(AndroidJUnit4::class)
class G0CountPreparationCostTest:G0CountJourneyScreenFixture() {
    override fun domainOpeningCallback()=G0SchemaOpeningCallback(true)
    @Test(timeout=900_000) fun eightAcceptedFactsFortyPairedScreenCommands()=runBlocking<Unit> { measure(8) }
    @Test(timeout=900_000) fun eightyAcceptedFactsFortyPairedScreenCommands()=runBlocking<Unit> { measure(80) }
    private fun now()=android.os.SystemClock.elapsedRealtimeNanos()
    private fun p95(values:List<Long>)=values.sorted()[kotlin.math.ceil(values.size*.95).toInt()-1]
    private fun hold(trace:G0PhaseTrace):Long {
        val all=synchronized(trace.spans) { trace.spans.toList() }
        val starts=all.filter { it.phase=="tx_begin_outer" };val ends=all.filter { it.phase=="tx_end_outer" }
        assertEquals(starts.size,ends.size);assertEquals(3,starts.size)
        val regular=starts.zip(ends).maxOf { (a,b)->b.end-a.start }
        val open=all.single { it.phase=="schema_callback_end" }.end-all.single { it.phase=="schema_callback_begin" }.start
        return maxOf(regular,open)
    }
    private suspend fun measure(history:Int) {
        configureAction(target=1_000_000)
        val (http,_)=channel { successReply(it) }
        val seed=actions()
        repeat(history) {
            increment(seed);val row=db.syncOutboxDao().getAll().single();val requests=sender(http)
            val delivery=requireNotNull(requests.sendOperation(access(),row.operationId))
            assertEquals(NextOperationAcceptance.COMMITTED,requests.acceptOperation(delivery))
        }
        val image=storage.snapshotClosedDatabase()
        val samples=mutableListOf<Pair<Boolean,List<Long>>>()
        repeat(20) { pair ->
            for(candidate in if(pair%2==0) listOf(false,true) else listOf(true,false)) {
                restoreForJourney(image)
                val flow=journey(prepareBlocks=candidate);val seen=G0ScreenObservation();val trace=G0PhaseTrace(driverTiming=false)
                flow.traceForTest=trace;install(flow,seen);seen.await(0)
                val start=seen.tap("increment")
                fun versions(kinds:Set<String>)=flow.events.filter { it.kind in kinds }.map { it.version }
                val feedback=seen.awaitAny { versions(setOf("state_SAVING","state_SAVED_REFRESHING","state_RESULT")) }
                val known=seen.awaitAny { versions(setOf("state_SAVED_REFRESHING","state_RESULT")) }
                val result=phase(flow,G0CountJourney.Phase.RESULT);val rendered=seen.await(result.version);flow.awaitSettled()
                assertNull(flow.lastError);assertEquals((history+1).toLong(),result.view!!.countHistory!!.todayQuantity)
                val recorded=requireNotNull(flow.recordedId)
                assertEquals(db.completionDao().getByHabitOnce(habit.id).single { it.id==recorded }.uuid,result.view.followUp.claim.completionUuid)
                val committed=flow.events.single { it.kind=="committed" }.at
                val drawn=seen.draws.filter { it.version in versions(setOf("state_SAVING","state_SAVED_REFRESHING","state_RESULT")) }.minOf { it.at }
                assertTrue(drawn>=start);assertTrue(known.observed>=committed);assertTrue(rendered.observed>=known.observed)
                val resource=hold(trace);assertEquals(0,trace.count("decrypt_refresh"));assertEquals(6,trace.count("decrypt_access"));if(candidate) assertTrue(flow.factBlockHits>0)
                val row=db.syncOutboxDao().getAll().single();val requests=sender(http)
                val sendAt=now();val delivery=requireNotNull(requests.sendOperation(access(),row.operationId));val ackAt=now()
                assertEquals(NextOperationAcceptance.COMMITTED,requests.acceptOperation(delivery));val ack=now()-ackAt
                val replayAt=now();assertEquals(NextOperationAcceptance.REPLAYED,requests.acceptOperation(delivery));val terminal=now()
                assertEquals(history+1,count("completions"));assertEquals(history+1,count("next_request_origins"))
                assertEquals(history+1,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
                samples+=candidate to listOf(committed-start,rendered.observed-start,drawn-start,resource,ack)
                Log.i("G0CountPreparationCost",buildJsonObject {
                    put("history",history);put("pair",pair);put("candidate",candidate)
                    fun event(name:String)=flow.events.single { it.kind==name }.at
                    put("prepare_ns",event("prepared")-event("prepare_started"))
                    put("write_ns",event("write_returned")-event("write_started"))
                    put("commit_to_result_ns",rendered.observed-committed)
                    put("schema_callback_ns",trace.nanos("schema_callback_total"))
                    put("fact_block_hits",flow.factBlockHits)
                    put("click_to_commit_ns",committed-start);put("click_to_feedback_draw_ns",drawn-start)
                    put("click_to_feedback_observed_ns",feedback.observed-start);put("click_to_known_saved_observed_ns",known.observed-start)
                    put("click_to_result_observed_ns",rendered.observed-start);put("transaction_max_ns",resource)
                    put("access_decryptions",trace.count("decrypt_access"));put("ack_ns",ack)
                    put("send_ns",ackAt-sendAt);put("replay_ns",terminal-replayAt);put("click_to_ack_terminal_ns",terminal-start)
                    put("facts",history+1);put("acceptances",history+1);put("outbox",0)
                    put("background_competition",false);put("exact_first_presentation",false);put("sealed_fact_owner",false)
                }.toString())
                flow.close();flow.awaitSettled()
            }
        }
        val failures=buildList {
            for(candidate in listOf(false,true)) {
                val group=samples.filter { it.first==candidate }.map { it.second };assertEquals(20,group.size)
                if(p95(group.map { it[0] })>500_000_000L) add("candidate=$candidate commit P95 >500ms")
                if(p95(group.map { it[1] })>700_000_000L) add("candidate=$candidate result P95 >700ms")
                if(group.maxOf { it[2] }>100_000_000L) add("candidate=$candidate feedback max >100ms")
                if(group.maxOf { it[3] }>150_000_000L) add("candidate=$candidate transaction max >150ms")
                if(p95(group.map { it[4] })>500_000_000L) add("candidate=$candidate ACK P95 >500ms")
            }
        }
        assertTrue(failures.joinToString(";"),failures.isEmpty())
    }
}
