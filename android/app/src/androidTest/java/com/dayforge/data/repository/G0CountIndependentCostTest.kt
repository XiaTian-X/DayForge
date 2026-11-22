package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Independent bounded sync worker. Frontend awaits no send/ACK, DAO lookup or home
 * between its commands. Two paired windows, three commands/window: exploratory, not P95. */
@RunWith(AndroidJUnit4::class)
class G0CountIndependentCostTest:G0CountIndependentFixture() {
    private fun now()=SystemClock.elapsedRealtimeNanos()
    @Test(timeout=900_000) fun earliestNextClicksWithIndependentOriginalSendAndAck()=runBlocking<Unit> {
        configureAction(target=1000);val history=80;val (http,_)=channel {successReply(it)}
        repeat(history) {val fact=seedFact();val row=db.syncOutboxDao().getEntityIntents("completion",fact.uuid).single()
            val request=sender(http);val sent=requireNotNull(request.sendOperation(access(),row.operationId))
            assertEquals(NextOperationAcceptance.COMMITTED,request.acceptOperation(sent))}
        val prior=seedFact();val image=storage.snapshotClosedDatabase();val failures=mutableListOf<String>();var total=0
        repeat(2) {pair->for(candidate in if(pair==0) listOf(false,true) else listOf(true,false)) {
            restoreForJourney(image);val sync=worker(http)
            val flow=journey(afterResult=true,loadOnOpen=true,shareInitialPure=true,prioritizeDashboard=true)
            val home=dashboard(coalesce=candidate);val homeTrace=G0PhaseTrace(driverTiming=false);home.trace=homeTrace
            val a=G0ScreenObservation();val b=G0ScreenObservation();val entry=now();installBoth(flow,home,a,b)
            val controls=a.awaitAny {listOf(0L)+flow.events.filter {it.kind=="state_READY"}.map {it.version}}
            val initial=both(flow,home,a,b);flow.awaitInitial();requireCommandBounds(a)
            assertTrue(sync.offer(prior));sync.start()
            val intervals=mutableListOf<Pair<Long,Long>>();var lastResult=0L;var unfinished=0
            repeat(3) {step->
                val ready=G0CountDashboard.compatible(flow.state.value,home.state.value)
                if(step>0 && !ready) unfinished++
                val previous=flow.state.value.version;val eventStart=flow.events.size
                val trace=G0PhaseTrace(driverTiming=false);flow.traceForTest=trace
                val click=a.tap("increment")
                fun events()=flow.events.drop(eventStart)
                val feedback=a.awaitAny {events().filter {it.kind in setOf("state_SAVING","state_SAVED_REFRESHING","state_RESULT")}.map {it.version}}
                val saved=a.awaitAny {events().filter {it.kind in setOf("state_SAVED_REFRESHING","state_RESULT")}.map {it.version}}
                nextResult(flow,previous);assertEquals((history+2+step).toLong(),flow.state.value.view!!.countHistory!!.todayQuantity)
                // Pure memory admission of this exact known commit; no database or worker await.
                assertTrue(sync.offer(knownResult(flow)))
                val result=flow.state.value;val observed=a.await(result.version)
                intervals+=click to observed.observed
                assertEquals(7,trace.count("decrypt_access"));assertEquals(0,trace.count("decrypt_refresh"));assertEquals(3,trace.count("tx_end_outer"))
                val ev=events();fun at(kind:String)=ev.single {it.kind==kind}.at
                val callback=a.clicks.last {it.key=="increment"}.at
                val draw=a.draws.filter {d->ev.any {it.version==d.version && it.kind in setOf("state_SAVING","state_SAVED_REFRESHING","state_RESULT")}}.minOf {it.at}
                assertTrue(callback>=click);assertTrue(draw>=at("state_SAVING"));assertTrue(saved.observed>=at("committed"))
                Log.i("G0CountIndependentStep",buildJsonObject {
                    put("pair",pair);put("candidate",candidate);put("step",step);put("click_ns",click-entry);put("result_ns",observed.observed-entry)
                    put("home_ready_before_click",ready);put("previous_result_to_click_ns",if(step==0) 0L else click-lastResult)
                    put("tap_to_callback_ns",callback-click);put("callback_to_saving_ns",at("state_SAVING")-callback)
                    put("saving_to_work_ns",at("initial_handoff_started")-at("state_SAVING"));put("saving_to_feedback_draw_ns",draw-at("state_SAVING"))
                    put("click_to_feedback_draw_ns",draw-click);put("click_to_feedback_observed_ns",feedback.observed-click)
                    put("prepare_ns",at("prepared")-at("prepare_started"));put("write_ns",at("write_returned")-at("write_started"))
                    put("after_write_to_result_ns",at("state_RESULT")-at("write_returned"));put("result_to_observed_ns",observed.observed-at("state_RESULT"))
                    put("click_to_commit_ns",at("committed")-click);put("click_to_saved_observed_ns",saved.observed-click)
                    put("click_to_result_observed_ns",observed.observed-click);put("command_transaction_max_ns",transactionHold(trace))
                    put("decrypt_ns",trace.nanos("decrypt_access"));put("command_access",trace.count("decrypt_access"))
                    put("sync_accepted_so_far",sync.attempts.count {it.accepted!=null});put("home_reads_so_far",home.reads.get())
                }.toString())
                lastResult=observed.observed;total++
                val key="pair=$pair candidate=$candidate step=$step"
                if(at("committed")-click>500_000_000L) failures+="$key save >500ms"
                if(observed.observed-click>700_000_000L) failures+="$key command result >700ms"
                if(draw-click>100_000_000L) failures+="$key feedback >100ms"
                if(transactionHold(trace)>150_000_000L) failures+="$key resource >150ms"
            }
            val joint=try {both(flow,home,a,b)} catch(e:Exception) {diagnoseContinuousPage(flow,home);throw e}
            sync.finish();assertNull(sync.failure);assertEquals(4,sync.attempts.size)
            val drained=now();val finalFrame=both(flow,home,a,b)
            assertEquals((history+4).toLong(),home.state.value.batch!!.counts.getValue(habit.id).todayQuantity)
            var overlaps=0
            sync.attempts.forEachIndexed {index,attempt->
                assertEquals(NextOperationAcceptance.COMMITTED,attempt.accepted)
                val overlap=intervals.sumOf {(start,end)->maxOf(0L,minOf(end,attempt.ackEnded)-maxOf(start,attempt.ackStarted))}
                if(overlap>0) overlaps++
                Log.i("G0CountIndependentSync",buildJsonObject {
                    put("pair",pair);put("candidate",candidate);put("index",index)
                    put("enqueue_ns",attempt.enqueued-entry);put("send_start_ns",attempt.sendStarted-entry);put("send_end_ns",attempt.sendEnded-entry)
                    put("ack_start_ns",attempt.ackStarted-entry);put("ack_end_ns",attempt.ackEnded-entry)
                    put("queue_wait_ns",attempt.sendStarted-attempt.enqueued);put("send_ns",attempt.sendEnded-attempt.sendStarted)
                    put("ack_ns",attempt.ackEnded-attempt.ackStarted);put("ack_overlap_ns",overlap)
                    put("send_transaction_max_ns",transactionHold(attempt.send));put("ack_transaction_max_ns",transactionHold(attempt.ack))
                    put("send_access",attempt.send.count("decrypt_access"));put("ack_access",attempt.ack.count("decrypt_access"))
                }.toString())
                val before=pageNativeState();assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(requireNotNull(attempt.delivery)));assertEquals(before,pageNativeState())
                if(attempt.ackEnded-attempt.ackStarted>500_000_000L) failures+="pair=$pair candidate=$candidate ACK $index >500ms"
                if(maxOf(transactionHold(attempt.send),transactionHold(attempt.ack))>150_000_000L) failures+="pair=$pair candidate=$candidate sync $index resource >150ms"
            }
            val terminal=now();home.shutdown();flow.close();flow.awaitSettled()
            assertEquals(history+4,count("completions"));assertEquals(history+4,count("next_request_origins"));assertEquals(history+4,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
            assertTrue("At least one actual ACK overlaps a foreground command",overlaps>0)
            Log.i("G0CountIndependentWindow",buildJsonObject {
                put("pair",pair);put("candidate",candidate);put("history",history);put("commands",3)
                put("entry_to_controls_ns",controls.observed-entry);put("entry_to_initial_ns",initial.observed-entry)
                put("entry_to_joint_ns",joint.observed-entry);put("last_click_to_joint_ns",joint.observed-intervals.last().first)
                put("last_result_to_joint_ns",joint.observed-lastResult);put("entry_to_drained_ns",drained-entry)
                put("entry_to_final_frame_ns",finalFrame.observed-entry);put("entry_to_terminal_ns",terminal-entry)
                put("unfinished_home_next_clicks",unfinished);put("ack_overlaps",overlaps)
                put("home_reads",home.reads.get());put("home_publications",home.publications.get());put("home_discards",home.events.count {it.kind=="discarded"})
                put("home_transaction_max_ns",transactionHold(homeTrace));put("facts",history+4);put("acceptances",history+4);put("outbox",0)
            }.toString())
            if(joint.observed-intervals.last().first>700_000_000L) failures+="pair=$pair candidate=$candidate joint >700ms"
            if(transactionHold(homeTrace)>150_000_000L) failures+="pair=$pair candidate=$candidate home resource >150ms"
        }}
        assertEquals(12,total)
        assertTrue("Independent sync exploratory budgets, not P95: "+failures.joinToString(";"),failures.isEmpty())
    }
}
