package com.dayforge.data.repository

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Two counterbalanced sessions per strategy, each with one first command then ten warm
 * commands on the SAME page/reader. Each session restores the same seed image. Network send
 * for the preceding command happens before the next click; its real ACK competes with it.
 * Send time remains explicit, and this is not a sealed fact writer or whole Dashboard test. */
@RunWith(AndroidJUnit4::class)
open class G0CountWarmContentionCostTest:G0CountJourneyScreenFixture() {
    override fun domainOpeningCallback()=G0SchemaOpeningCallback(true)
    internal open fun candidateJourney()=journey(prepareBlocks=true)
    internal open val candidateSharesFirstPreparation:Boolean get()=true
    @Test(timeout=900_000) fun eightSeedFactsContinuousPageWithAckCompetition()=runBlocking<Unit> { measure(8) }
    @Test(timeout=900_000) fun eightySeedFactsContinuousPageWithAckCompetition()=runBlocking<Unit> { measure(80) }
    private fun now()=android.os.SystemClock.elapsedRealtimeNanos()
    private fun p95(values:List<Long>)=values.sorted()[kotlin.math.ceil(values.size*.95).toInt()-1]
    private fun hold(trace:G0PhaseTrace,foreground:Boolean):Long {
        val all=synchronized(trace.spans) { trace.spans.toList() }
        val starts=all.filter { it.phase=="tx_begin_outer" };val ends=all.filter { it.phase=="tx_end_outer" }
        assertEquals(starts.size,ends.size);assertTrue(starts.isNotEmpty());if(foreground) assertEquals(3,starts.size)
        val regular=starts.zip(ends).maxOf { (a,b)->b.end-a.start }
        val opening=all.filter { it.phase=="schema_callback_total" }.maxOfOrNull { it.end-it.start } ?: 0L
        return maxOf(regular,opening)
    }
    private suspend fun measure(history:Int)=coroutineScope {
        configureAction(target=1_000_000);val (http,_)=channel { successReply(it) };val seed=actions()
        repeat(history) {
            increment(seed);val requests=sender(http)
            val delivery=requireNotNull(requests.sendOperation(access(),db.syncOutboxDao().getAll().single().operationId))
            assertEquals(NextOperationAcceptance.COMMITTED,requests.acceptOperation(delivery))
        }
        val image=storage.snapshotClosedDatabase();val samples=mutableListOf<Pair<Boolean,List<Long>>>()
        repeat(2) { window ->
            for(candidate in if(window==0) listOf(false,true) else listOf(true,false)) {
                restoreForJourney(image);val flow=if(candidate) candidateJourney() else journey(prepareBlocks=false);val seen=G0ScreenObservation()
                install(flow,seen);seen.await(0)
                val requests=sender(http);var lastClick=0L
                // The first command establishes the page's actual cache; it is logged, never
                // silently removed from a cold-start claim. No background ACK exists yet.
                suspend fun command(step:Int,ackStarted:Long=0L,ackWork:Deferred<Pair<Long,Long>>?=null,
                    previousSend:Long=0L) {
                    val previousClick=lastClick;val offset=flow.events.size;val previousVersion=flow.state.value.version;val hits=flow.factBlockHits
                    val trace=G0PhaseTrace(driverTiming=false);flow.traceForTest=trace
                    val start=seen.tap("increment")
                    fun events()=flow.events.drop(offset)
                    fun versions(kinds:Set<String>)=events().filter { it.kind in kinds }.map { it.version }
                    val feedback=seen.awaitAny { versions(setOf("state_SAVING","state_SAVED_REFRESHING","state_RESULT")) }
                    val known=seen.awaitAny { versions(setOf("state_SAVED_REFRESHING","state_RESULT")) }
                    val result=withTimeout(20_000) { flow.state.first { it.phase==G0CountJourney.Phase.RESULT && it.version>previousVersion } }
                    val rendered=seen.await(result.version);flow.awaitSettled();assertNull(flow.lastError)
                    val quantity=history+step+1;assertEquals(quantity.toLong(),result.view!!.countHistory!!.todayQuantity)
                    assertEquals(db.completionDao().getByHabitOnce(habit.id).single { it.id==flow.recordedId }.uuid,result.view.followUp.claim.completionUuid)
                    fun event(name:String)=events().single { it.kind==name }.at
                    val committed=event("committed")
                    val shared=events().any { it.kind=="prepare_shared" }
                    assertEquals(candidate && (step>0 || candidateSharesFirstPreparation),shared)
                    assertEquals(1,events().count { it.kind in setOf("prepare_shared","prepare_original") })
                    val drawn=seen.draws.filter { it.version in versions(setOf("state_SAVING","state_SAVED_REFRESHING","state_RESULT")) }.minOf { it.at }
                    assertTrue(drawn>=start);assertTrue(known.observed>=committed);assertTrue(rendered.observed>=known.observed)
                    val resource=hold(trace,true);assertEquals(if(step==0) 6 else 7,trace.count("decrypt_access"));assertEquals(0,trace.count("decrypt_refresh"))
                    fun decryptions(from:Long,to:Long)=trace.spans.count { it.phase=="decrypt_access" && it.start>=from && it.end<=to }
                    assertEquals(2,decryptions(event("prepare_started"),event("prepared")))
                    assertEquals(if(step==0) 2 else 3,decryptions(event("write_started"),event("write_returned")))
                    assertEquals(2,decryptions(committed,rendered.observed))
                    val ack=ackWork?.await();val ackDuration=ack?.let { it.first-ackStarted } ?: 0L
                    val overlap=ack?.let { maxOf(0L,minOf(it.first,rendered.observed)-maxOf(ackStarted,start)) } ?: 0L
                    assertEquals(quantity,count("completions"));assertEquals(quantity,count("next_request_origins"))
                    assertEquals(quantity-1,count("next_acceptances"));assertEquals(1,count("sync_outbox"))
                    if(step>0) samples+=candidate to listOf(committed-start,rendered.observed-start,drawn-start,resource,ackDuration,overlap,ack!!.second)
                    Log.i("G0CountWarmCost",buildJsonObject {
                        put("history",history);put("window",window);put("step",step);put("candidate",candidate);put("first_command",step==0);put("shared_preparation",shared)
                        put("prepare_ns",event("prepared")-event("prepare_started"));put("write_ns",event("write_returned")-event("write_started"))
                        put("commit_to_result_ns",rendered.observed-committed);put("fact_block_hits",flow.factBlockHits-hits)
                        put("click_to_commit_ns",committed-start);put("click_to_feedback_draw_ns",drawn-start)
                        put("click_to_feedback_observed_ns",feedback.observed-start);put("click_to_known_saved_observed_ns",known.observed-start)
                        put("click_to_result_observed_ns",rendered.observed-start);put("transaction_max_ns",resource)
                        put("access_decryptions",trace.count("decrypt_access"));put("ack_ns",ackDuration);put("ack_transaction_max_ns",ack?.second ?: 0L)
                        put("previous_send_ns",previousSend);put("ack_overlap_ns",overlap)
                        put("previous_click_to_ack_ns",ack?.let { it.first-previousClick } ?: 0L)
                        put("facts",quantity);put("acceptances",quantity-1);put("outbox",1)
                        put("background_competition",step>0);put("exact_first_presentation",false);put("sealed_fact_owner",false)
                    }.toString())
                    lastClick=start
                }
                command(0)
                repeat(10) { index ->
                    val row=db.syncOutboxDao().getAll().single();val sendAt=now()
                    val delivery=requireNotNull(requests.sendOperation(access(),row.operationId));val sendNs=now()-sendAt
                    val started=CompletableDeferred<Long>();val ackTrace=G0PhaseTrace(driverTiming=false)
                    val ack=async(Dispatchers.IO) {
                        val ended=ackTrace.run {
                            started.complete(now());assertEquals(NextOperationAcceptance.COMMITTED,requests.acceptOperation(delivery));now()
                        }
                        ended to hold(ackTrace,false)
                    }
                    val ackStarted=started.await();command(index+1,ackStarted,ack,sendNs)
                    val before=pageNativeState();assertEquals(NextOperationAcceptance.REPLAYED,requests.acceptOperation(delivery));assertEquals(before,pageNativeState())
                }
                val sendAt=now();val delivery=requireNotNull(requests.sendOperation(access(),db.syncOutboxDao().getAll().single().operationId));val ackAt=now()
                assertEquals(NextOperationAcceptance.COMMITTED,requests.acceptOperation(delivery))
                assertEquals(NextOperationAcceptance.REPLAYED,requests.acceptOperation(delivery));val terminal=now()
                assertEquals(history+11,count("completions"));assertEquals(history+11,count("next_request_origins"))
                assertEquals(history+11,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
                Log.i("G0CountWarmTerminal",buildJsonObject {
                    put("history",history);put("window",window);put("candidate",candidate);put("facts",history+11)
                    put("acceptances",history+11);put("outbox",0);put("send_ns",ackAt-sendAt);put("ack_replay_ns",terminal-ackAt)
                    put("last_click_to_ack_replay_ns",terminal-lastClick)
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
                if(group.maxOf { maxOf(it[3],it[6]) }>150_000_000L) add("candidate=$candidate transaction max >150ms")
                if(p95(group.map { it[4] })>500_000_000L) add("candidate=$candidate ACK P95 >500ms")
                if(group.any { it[5]==0L }) add("candidate=$candidate a scheduled ACK did not overlap the foreground")
            }
        }
        assertTrue(failures.joinToString(";"),failures.isEmpty())
    }
}
