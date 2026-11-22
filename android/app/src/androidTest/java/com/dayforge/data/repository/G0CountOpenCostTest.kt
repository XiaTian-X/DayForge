package com.dayforge.data.repository

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Four pairs per entry timing/size: exploratory medians/max, NOT a tail-latency acceptance.
 * No synthetic delay or barrier. Both pages really load current content; candidate alone fills
 * pure blocks during that load. One real predecessor ACK races the actual click. */
@RunWith(AndroidJUnit4::class)
class G0CountOpenCostTest:G0CountJourneyScreenFixture() {
    override fun domainOpeningCallback()=G0SchemaOpeningCallback(true)
    @Test(timeout=900_000) fun eightAcceptedFactsBothEntryTimings()=runBlocking<Unit> { measure(8) }
    @Test(timeout=900_000) fun eightyAcceptedFactsBothEntryTimings()=runBlocking<Unit> { measure(80) }
    private fun now()=android.os.SystemClock.elapsedRealtimeNanos()
    private fun hold(trace:G0PhaseTrace):Long {
        val spans=synchronized(trace.spans) {trace.spans.toList()}
        val a=spans.filter {it.phase=="tx_begin_outer"};val b=spans.filter {it.phase=="tx_end_outer"}
        assertEquals(a.size,b.size)
        return maxOf(a.zip(b).maxOfOrNull {(x,y)->y.end-x.start} ?: 0L,
            spans.filter {it.phase=="schema_callback_total"}.maxOfOrNull {it.end-it.start} ?: 0L)
    }
    private suspend fun measure(history:Int)=coroutineScope {
        configureAction(target=1_000_000);val (http,_)=channel {successReply(it)};val seed=actions()
        repeat(history) {
            increment(seed);val request=sender(http)
            val delivery=requireNotNull(request.sendOperation(access(),db.syncOutboxDao().getAll().single().operationId))
            assertEquals(NextOperationAcceptance.COMMITTED,request.acceptOperation(delivery))
        }
        increment(seed)
        val predecessor=requireNotNull(sender(http).sendOperation(access(),db.syncOutboxDao().getAll().single().operationId))
        val image=storage.snapshotClosedDatabase();val failures=mutableListOf<String>();var completed=0
        for(afterContent in listOf(false,true)) repeat(4) { pair ->
            for(candidate in if(pair%2==0) listOf(false,true) else listOf(true,false)) {
                restoreForJourney(image);val requests=sender(http)
                val flow=journey(afterResult=true,loadOnOpen=true,shareInitialPure=candidate)
                val initialTrace=G0PhaseTrace(driverTiming=false);val commandTrace=G0PhaseTrace(driverTiming=false);val ackTrace=G0PhaseTrace(driverTiming=false)
                flow.initialTraceForTest=initialTrace;flow.traceForTest=commandTrace
                val seen=G0ScreenObservation();val entry=now();install(flow,seen)
                val controls=seen.awaitAny {listOf(0L)+flow.events.filter {it.kind=="state_READY"}.map {it.version}}
                var contentSeen:Long?=null
                if(afterContent) {
                    val ready=withTimeout(20_000) {flow.state.first {it.phase==G0CountJourney.Phase.READY && it.view!=null}}
                    contentSeen=seen.await(ready.version).observed
                    assertEquals((history+1).toLong(),ready.view!!.countHistory!!.todayQuantity)
                }
                val started=CompletableDeferred<Long>()
                val ack=async(Dispatchers.IO) {ackTrace.run {
                    started.complete(now());assertEquals(NextOperationAcceptance.COMMITTED,requests.acceptOperation(predecessor));now()
                }}
                val ackStart=started.await();val click=seen.tap("increment")
                fun versions()=flow.events.filter {it.kind in setOf("state_SAVING","state_SAVED_REFRESHING","state_RESULT")}.map {it.version}
                val feedback=seen.awaitAny {versions()}
                val known=seen.awaitAny {flow.events.filter {it.kind in setOf("state_SAVED_REFRESHING","state_RESULT")}.map {it.version}}
                val result=phase(flow,G0CountJourney.Phase.RESULT);val rendered=seen.await(result.version);flow.awaitSettled()
                assertNull(flow.lastError);assertEquals((history+2).toLong(),result.view!!.countHistory!!.todayQuantity)
                assertEquals(db.completionDao().getByHabitOnce(habit.id).single {it.id==flow.recordedId}.uuid,result.view.followUp.claim.completionUuid)
                val ackEnd=ack.await()
                fun event(name:String)=flow.events.single {it.kind==name}.at
                val committed=event("committed");val resultState=result.stateStartedAt
                val resultDraw=seen.draws.single {it.version==result.version}.at
                val feedbackDraw=seen.draws.filter {it.version in versions()}.minOf {it.at}
                val initialPublished=flow.events.singleOrNull {it.kind=="initial_published"}
                val identity=flow.events.any {it.kind=="count_identity_present"}
                val shared=flow.events.any {it.kind=="prepare_shared"}
                assertEquals(candidate && identity,shared)
                assertEquals(if(identity) 7 else 6,commandTrace.count("decrypt_access"));assertEquals(0,commandTrace.count("decrypt_refresh"))
                assertEquals(3,commandTrace.count("tx_end_outer"))
                Log.i("G0CountOpenBoundaries",buildJsonObject {
                    put("history",history);put("pair",pair);put("candidate",candidate);put("after_content",afterContent)
                    put("commit",committed);put("known_observed",known.observed);put("state_start",resultState)
                    put("state_event",event("state_RESULT"));put("draw_start",resultDraw);put("observed",rendered.observed)
                }.toString())
                assertTrue("drawStart=$resultDraw stateStart=$resultState",resultDraw>=resultState)
                assertTrue("observed=${rendered.observed} drawStart=$resultDraw",rendered.observed>=resultDraw)
                assertTrue("known=${known.observed} committed=$committed",known.observed>=committed)
                assertTrue(initialPublished==null || initialPublished.at<=event("state_SAVING"))
                assertEquals(history+2,count("completions"));assertEquals(history+1,count("next_acceptances"));assertEquals(1,count("sync_outbox"))
                val beforeReplay=pageNativeState();assertEquals(NextOperationAcceptance.REPLAYED,requests.acceptOperation(predecessor));assertEquals(beforeReplay,pageNativeState())
                val sendStart=now();val delivery=requireNotNull(requests.sendOperation(access(),db.syncOutboxDao().getAll().single().operationId));val acceptStart=now()
                assertEquals(NextOperationAcceptance.COMMITTED,requests.acceptOperation(delivery))
                assertEquals(NextOperationAcceptance.REPLAYED,requests.acceptOperation(delivery));val terminal=now()
                assertEquals(history+2,count("next_request_origins"));assertEquals(history+2,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
                val initialHold=hold(initialTrace);val commandHold=hold(commandTrace);val ackHold=hold(ackTrace)
                val overlap=maxOf(0L,minOf(ackEnd,rendered.observed)-maxOf(ackStart,click))
                Log.i("G0CountOpenCost",buildJsonObject {
                    put("history",history);put("pair",pair);put("candidate",candidate);put("after_content",afterContent)
                    put("entry_to_controls_ns",controls.observed-entry);put("entry_to_initial_observed_ns",contentSeen?.minus(entry) ?: -1L)
                    put("entry_to_initial_state_ns",initialPublished?.at?.minus(entry) ?: -1L)
                    put("entry_to_commit_ns",committed-entry);put("entry_to_result_observed_ns",rendered.observed-entry)
                    put("click_to_commit_ns",committed-click);put("click_to_known_saved_observed_ns",known.observed-click)
                    put("click_to_feedback_draw_ns",feedbackDraw-click);put("click_to_feedback_observed_ns",feedback.observed-click)
                    put("click_to_result_state_ns",resultState-click);put("click_to_result_draw_ns",resultDraw-click)
                    put("click_to_result_observed_ns",rendered.observed-click);put("result_observation_attempts",rendered.attempts)
                    put("result_capture_ns",rendered.observed-rendered.captureStarted)
                    put("initial_handoff_ns",event("initial_handoff_finished")-event("initial_handoff_started"))
                    put("initial_transaction_max_ns",initialHold);put("command_transaction_max_ns",commandHold);put("ack_transaction_max_ns",ackHold)
                    put("initial_access_decryptions",initialTrace.count("decrypt_access"));put("command_access_decryptions",commandTrace.count("decrypt_access"))
                    put("initial_published",initialPublished!=null);put("count_identity_present",identity);put("shared_preparation",shared)
                    put("ack_ns",ackEnd-ackStart);put("ack_overlap_ns",overlap);put("send_ns",acceptStart-sendStart)
                    put("final_ack_replay_ns",terminal-acceptStart);put("entry_to_terminal_ns",terminal-entry)
                    put("facts",history+2);put("acceptances",history+2);put("outbox",0);put("sealed_fact_owner",false)
                }.toString())
                completed++
                val key="candidate=$candidate afterContent=$afterContent pair=$pair"
                if(committed-click>500_000_000L) failures+="$key save >500ms"
                if(rendered.observed-click>700_000_000L) failures+="$key result >700ms"
                if(feedbackDraw-click>100_000_000L) failures+="$key feedback >100ms"
                if(maxOf(initialHold,commandHold,ackHold)>150_000_000L) failures+="$key resource >150ms"
                if(ackEnd-ackStart>500_000_000L) failures+="$key ACK >500ms"
                if(overlap==0L) failures+="$key no real foreground/ACK overlap"
                flow.close();flow.awaitSettled()
            }
        }
        assertEquals(16,completed)
        assertTrue("Exploratory per-operation budgets, not P95 acceptance: "+failures.joinToString(";"),failures.isEmpty())
    }
}
