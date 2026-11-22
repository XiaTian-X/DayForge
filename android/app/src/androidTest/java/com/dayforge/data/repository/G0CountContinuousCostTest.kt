package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.NextSyncPushResponse
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Two alternating pairs, three real commands/page. No wait for the home between commands.
 * The next delivery is prepared concurrently; its actual acceptance has separate timing.
 * This measures a continuous journey, not the previous one-click sample population. */
@RunWith(AndroidJUnit4::class)
class G0CountContinuousCostTest:G0CountDashboardFixture() {
    private fun now()=SystemClock.elapsedRealtimeNanos()
    private fun hold(trace:G0PhaseTrace):Long {
        val spans=synchronized(trace.spans) {trace.spans.toList()}
        val begins=spans.filter {it.phase=="tx_begin_outer"};val ends=spans.filter {it.phase=="tx_end_outer"}
        assertEquals(begins.size,ends.size)
        return begins.zip(ends).maxOfOrNull {(a,b)->b.end-a.start} ?: 0L
    }
    @Test(timeout=900_000) fun eightyHistoryThreeConsecutiveClicksWithPipelinedOriginalSync()=runBlocking<Unit> {
        configureAction(target=1000);val history=80;val (http,_)=channel {successReply(it)};val seed=actions()
        repeat(history) {
            increment(seed);val r=sender(http)
            val sent=requireNotNull(r.sendOperation(access(),db.syncOutboxDao().getAll().single().operationId))
            assertEquals(NextOperationAcceptance.COMMITTED,r.acceptOperation(sent))
        }
        increment(seed);val prior=requireNotNull(sender(http).sendOperation(access(),db.syncOutboxDao().getAll().single().operationId))
        val image=storage.snapshotClosedDatabase();val failures=mutableListOf<String>();var measured=0
        repeat(2) {pair -> for(candidate in if(pair==0) listOf(false,true) else listOf(true,false)) {
            restoreForJourney(image);val requests=sender(http)
            val flow=journey(afterResult=true,loadOnOpen=true,shareInitialPure=true,prioritizeDashboard=true)
            val home=dashboard(coalesce=candidate);val homeTrace=G0PhaseTrace(driverTiming=false);home.trace=homeTrace
            val a=G0ScreenObservation();val b=G0ScreenObservation();val entry=now();installBoth(flow,home,a,b)
            val controls=a.awaitAny {listOf(0L)+flow.events.filter {it.kind=="state_READY"}.map {it.version}}
            val initial=both(flow,home,a,b);flow.awaitInitial();requireCommandBounds(a)
            var delivery:Deferred<NextCoreDelivery<NextSyncPushResponse>> = CompletableDeferred(prior)
            val accepted=mutableListOf<NextCoreDelivery<NextSyncPushResponse>>()
            var lastClick=0L;var overlapCount=0;var unfinishedHomeAtNextClick=0;var lastResultAt=0L;var lastCommandResult=0L
            repeat(3) {step ->
                val captured=delivery
                val workerStarted=CompletableDeferred<Unit>();var acceptStart=0L;var acceptEnd=0L
                val ackTrace=G0PhaseTrace(driverTiming=false)
                val ack=async(Dispatchers.IO) {
                    workerStarted.complete(Unit);val value=captured.await()
                    ackTrace.run {acceptStart=now();assertEquals(NextOperationAcceptance.COMMITTED,requests.acceptOperation(value));acceptEnd=now()}
                    value
                }
                workerStarted.await()
                val homeReady=G0CountDashboard.compatible(flow.state.value,home.state.value)
                if(step>0 && !homeReady) unfinishedHomeAtNextClick++
                val previous=flow.state.value.version;val eventStart=flow.events.size
                val trace=G0PhaseTrace(driverTiming=false);flow.traceForTest=trace
                val click=a.tap("increment");lastClick=click
                val feedback=a.awaitAny {flow.events.drop(eventStart).filter {it.kind in setOf("state_SAVING","state_SAVED_REFRESHING","state_RESULT")}.map {it.version}}
                val saved=a.awaitAny {flow.events.drop(eventStart).filter {it.kind in setOf("state_SAVED_REFRESHING","state_RESULT")}.map {it.version}}
                withTimeout(20_000) {while(flow.state.value.version<=previous || flow.state.value.phase!=G0CountJourney.Phase.RESULT) delay(1)}
                flow.awaitSettled();assertNull(flow.lastError)
                val result=flow.state.value;val observed=a.await(result.version);lastResultAt=observed.observed
                assertEquals((history+2+step).toLong(),result.view!!.countHistory!!.todayQuantity)
                assertEquals(db.completionDao().getByHabitOnce(habit.id).single {it.id==flow.recordedId}.uuid,result.view.followUp.claim.completionUuid)
                assertEquals(7,trace.count("decrypt_access"));assertEquals(0,trace.count("decrypt_refresh"));assertEquals(3,trace.count("tx_end_outer"))
                accepted+=ack.await()
                val operation=db.syncOutboxDao().getAll().single().operationId
                delivery=async(Dispatchers.IO) {requireNotNull(requests.sendOperation(access(),operation))}
                val events=flow.events.drop(eventStart)
                fun at(kind:String)=events.single {it.kind==kind}.at
                val callback=a.clicks.last {it.key=="increment"}.at
                val draw=a.draws.filter {d->events.any {it.version==d.version && it.kind in setOf("state_SAVING","state_SAVED_REFRESHING","state_RESULT")}}.minOf {it.at}
                val overlap=maxOf(0L,minOf(acceptEnd,observed.observed)-maxOf(acceptStart,click))
                if(overlap>0L) overlapCount++
                assertTrue(callback>=click);assertTrue(at("state_SAVING")>=callback);assertTrue(draw>=at("state_SAVING"))
                assertTrue(saved.observed>=at("committed"));assertTrue(observed.observed>=at("state_RESULT"))
                Log.i("G0CountContinuousStep",buildJsonObject {
                    put("pair",pair);put("candidate",candidate);put("step",step);put("home_ready_before_click",homeReady)
                    put("click_ns",click-entry);put("tap_to_callback_ns",callback-click)
                    put("callback_to_saving_ns",at("state_SAVING")-callback);put("saving_to_work_ns",at("initial_handoff_started")-at("state_SAVING"))
                    put("saving_to_feedback_draw_ns",draw-at("state_SAVING"));put("click_to_feedback_draw_ns",draw-click)
                    put("click_to_feedback_observed_ns",feedback.observed-click);put("prepare_ns",at("prepared")-at("prepare_started"))
                    put("habit_load_ns",at("habit_loaded")-at("prepared"));put("write_ns",at("write_returned")-at("write_started"))
                    put("after_write_to_result_ns",at("state_RESULT")-at("write_returned"));put("result_to_observed_ns",observed.observed-at("state_RESULT"))
                    put("click_to_commit_ns",at("committed")-click);put("click_to_saved_observed_ns",saved.observed-click)
                    put("click_to_result_observed_ns",observed.observed-click);put("previous_result_to_click_ns",if(step==0) 0L else click-lastCommandResult)
                    put("decrypt_ns",trace.nanos("decrypt_access"));put("tx_begin_ns",trace.nanos("tx_begin_outer"));put("tx_end_ns",trace.nanos("tx_end_outer"))
                    put("command_transaction_max_ns",hold(trace));put("ack_transaction_max_ns",hold(ackTrace))
                    put("ack_ns",acceptEnd-acceptStart);put("ack_start_after_click_ns",acceptStart-click);put("ack_overlap_ns",overlap)
                    put("command_access",trace.count("decrypt_access"));put("home_reads_so_far",home.reads.get());put("home_publications_so_far",home.publications.get())
                }.toString())
                lastCommandResult=observed.observed;measured++
                val key="pair=$pair candidate=$candidate step=$step"
                if(at("committed")-click>500_000_000L) failures+="$key save >500ms"
                if(observed.observed-click>700_000_000L) failures+="$key command result >700ms"
                if(draw-click>100_000_000L) failures+="$key feedback >100ms"
                if(maxOf(hold(trace),hold(ackTrace))>150_000_000L) failures+="$key resource >150ms"
                if(acceptEnd-acceptStart>500_000_000L) failures+="$key ACK >500ms"
            }
            val joint=try {both(flow,home,a,b)} catch(failure:Exception) {diagnoseContinuousPage(flow,home);throw failure}
            assertNull(home.state.value.error)
            assertEquals((history+4).toLong(),home.state.value.batch!!.counts.getValue(habit.id).todayQuantity)
            val finalDelivery=delivery.await();assertEquals(NextOperationAcceptance.COMMITTED,requests.acceptOperation(finalDelivery));accepted+=finalDelivery
            for(value in accepted) {
                val before=pageNativeState();assertEquals(NextOperationAcceptance.REPLAYED,requests.acceptOperation(value));assertEquals(before,pageNativeState())
            }
            val terminal=now();home.shutdown();flow.close();flow.awaitSettled()
            assertEquals(history+4,count("completions"));assertEquals(history+4,count("next_request_origins"));assertEquals(history+4,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
            assertTrue("Original ACK must really overlap at least one click in each continuous window",overlapCount>0)
            Log.i("G0CountContinuousWindow",buildJsonObject {
                put("pair",pair);put("candidate",candidate);put("history",history);put("commands",3)
                put("entry_to_controls_ns",controls.observed-entry);put("entry_to_initial_ns",initial.observed-entry)
                put("last_click_to_joint_ns",joint.observed-lastClick);put("last_result_to_joint_ns",joint.observed-lastResultAt)
                put("entry_to_joint_ns",joint.observed-entry);put("entry_to_terminal_ns",terminal-entry)
                put("unfinished_home_next_clicks",unfinishedHomeAtNextClick);put("ack_overlaps",overlapCount)
                put("home_reads",home.reads.get());put("home_publications",home.publications.get());put("home_invalidations",home.invalidations.get())
                put("home_discards",home.events.count {it.kind=="discarded"});put("home_transaction_max_ns",hold(homeTrace))
                put("facts",history+4);put("acceptances",history+4);put("outbox",0);put("sealed_fact_owner",false)
            }.toString())
            if(joint.observed-lastClick>700_000_000L) failures+="pair=$pair candidate=$candidate final joint >700ms"
            if(hold(homeTrace)>150_000_000L) failures+="pair=$pair candidate=$candidate home resource >150ms"
        } }
        assertEquals(12,measured)
        assertTrue("Continuous exploratory budgets, not P95: "+failures.joinToString(";"),failures.isEmpty())
    }
}
