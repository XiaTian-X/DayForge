package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Four pairs/size, exploratory median/max. Actual original Dashboard invalidations,
 * publication authentication and full reads compete with the original count writer/ACK. */
@RunWith(AndroidJUnit4::class)
open class G0CountDashboardCostTest:G0CountDashboardFixture() {
    protected open val compareAdmission:Boolean get()=false
    @Test(timeout=900_000) fun eightFactsOriginalHomeAndActualCount()=runBlocking<Unit> {measure(8)}
    @Test(timeout=900_000) fun eightyFactsOriginalHomeAndActualCount()=runBlocking<Unit> {measure(80)}
    private fun now()=SystemClock.elapsedRealtimeNanos()
    private fun hold(trace:G0PhaseTrace):Long {
        val spans=synchronized(trace.spans) {trace.spans.toList()}
        val begin=spans.filter {it.phase=="tx_begin_outer"};val end=spans.filter {it.phase=="tx_end_outer"}
        assertEquals(begin.size,end.size)
        return maxOf(begin.zip(end).maxOfOrNull {(a,b)->b.end-a.start} ?: 0L,
            spans.filter {it.phase=="schema_callback_total"}.maxOfOrNull {it.end-it.start} ?: 0L)
    }
    private suspend fun measure(history:Int)=coroutineScope {
        configureAction(target=1_000_000);val (http,_)=channel {successReply(it)};val seed=actions()
        repeat(history) {
            increment(seed);val request=sender(http)
            val sent=requireNotNull(request.sendOperation(access(),db.syncOutboxDao().getAll().single().operationId))
            assertEquals(NextOperationAcceptance.COMMITTED,request.acceptOperation(sent))
        }
        increment(seed);val predecessor=requireNotNull(sender(http).sendOperation(access(),db.syncOutboxDao().getAll().single().operationId))
        val image=storage.snapshotClosedDatabase();val failures=mutableListOf<String>();var completed=0
        repeat(4) {pair -> for(candidate in if(pair%2==0) listOf(false,true) else listOf(true,false)) {
            restoreForJourney(image);val requests=sender(http)
            val flow=journey(afterResult=true,loadOnOpen=true,shareInitialPure=compareAdmission || candidate,prioritizeDashboard=true)
            val home=dashboard(coalesce=compareAdmission && candidate)
            val initial=G0PhaseTrace(driverTiming=false);val command=G0PhaseTrace(driverTiming=false)
            val homeTrace=G0PhaseTrace(driverTiming=false);val ackTrace=G0PhaseTrace(driverTiming=false)
            flow.initialTraceForTest=initial;flow.traceForTest=command;home.trace=homeTrace
            val a=G0ScreenObservation();val b=G0ScreenObservation();val entry=now();installBoth(flow,home,a,b)
            val controls=a.awaitAny {listOf(0L)+flow.events.filter {it.kind=="state_READY"}.map {it.version}}
            val initialFrame=both(flow,home,a,b);flow.awaitInitial()
            assertEquals((history+1).toLong(),flow.state.value.view!!.countHistory!!.todayQuantity)
            val started=CompletableDeferred<Long>()
            val ack=async(Dispatchers.IO) {ackTrace.run {
                started.complete(now());assertEquals(NextOperationAcceptance.COMMITTED,requests.acceptOperation(predecessor));now()
            }}
            val ackStart=started.await();requireCommandBounds(a);val click=a.tap("increment")
            fun feedbackVersions()=flow.events.filter {it.kind in setOf("state_SAVING","state_SAVED_REFRESHING","state_RESULT")}.map {it.version}
            val feedback=a.awaitAny {feedbackVersions()}
            val saved=a.awaitAny {flow.events.filter {it.kind in setOf("state_SAVED_REFRESHING","state_RESULT")}.map {it.version}}
            val result=phase(flow,G0CountJourney.Phase.RESULT);val resultFrame=a.await(result.version)
            flow.awaitSettled();assertNull(flow.lastError)
            val ackEnd=ack.await();val joint=both(flow,home,a,b)
            assertNull(home.state.value.error);assertTrue(G0CountDashboard.compatible(result,home.state.value))
            assertEquals((history+2).toLong(),result.view!!.countHistory!!.todayQuantity)
            assertEquals(db.completionDao().getByHabitOnce(habit.id).single {it.id==flow.recordedId}.uuid,result.view.followUp.claim.completionUuid)
            assertEquals(compareAdmission || candidate,flow.events.any {it.kind=="prepare_shared"})
            assertEquals(7,command.count("decrypt_access"));assertEquals(0,command.count("decrypt_refresh"))
            assertEquals(3,command.count("tx_end_outer"))
            val committed=flow.events.single {it.kind=="committed"}.at
            val draw=a.draws.filter {it.version in feedbackVersions()}.minOf {it.at}
            val resultDraw=a.draws.single {it.version==result.version}.at
            assertTrue(resultDraw>=result.stateStartedAt);assertTrue(resultFrame.observed>=resultDraw);assertTrue(saved.observed>=committed)
            assertEquals(history+2,count("completions"));assertEquals(history+1,count("next_acceptances"));assertEquals(1,count("sync_outbox"))
            val before=pageNativeState();assertEquals(NextOperationAcceptance.REPLAYED,requests.acceptOperation(predecessor));assertEquals(before,pageNativeState())
            val delivery=requireNotNull(requests.sendOperation(access(),db.syncOutboxDao().getAll().single().operationId))
            assertEquals(NextOperationAcceptance.COMMITTED,requests.acceptOperation(delivery));assertEquals(NextOperationAcceptance.REPLAYED,requests.acceptOperation(delivery))
            val terminal=now();home.shutdown();flow.close();flow.awaitSettled()
            assertEquals(history+2,count("next_request_origins"));assertEquals(history+2,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
            val initialHold=hold(initial);val commandHold=hold(command);val homeHold=hold(homeTrace);val ackHold=hold(ackTrace)
            val overlap=maxOf(0L,minOf(ackEnd,joint.observed)-maxOf(ackStart,click))
            Log.i(if(compareAdmission) "G0CountAdmissionCost" else "G0CountDashboardCost",buildJsonObject {
                put("history",history);put("pair",pair);put("candidate",candidate)
                put("comparison",if(compareAdmission) "admission" else "pure")
                put("entry_to_controls_ns",controls.observed-entry);put("entry_to_joint_initial_ns",initialFrame.observed-entry)
                put("entry_to_joint_result_ns",joint.observed-entry);put("entry_to_terminal_ns",terminal-entry)
                put("click_to_feedback_draw_ns",draw-click);put("click_to_feedback_observed_ns",feedback.observed-click)
                put("click_to_commit_ns",committed-click);put("click_to_saved_observed_ns",saved.observed-click)
                put("click_to_result_observed_ns",resultFrame.observed-click);put("click_to_joint_observed_ns",joint.observed-click)
                put("result_to_joint_ns",joint.observed-resultFrame.observed);put("joint_observation_attempts",joint.attempts)
                put("initial_transaction_max_ns",initialHold);put("command_transaction_max_ns",commandHold)
                put("home_transaction_max_ns",homeHold);put("ack_transaction_max_ns",ackHold)
                put("command_access",command.count("decrypt_access"));put("home_access",homeTrace.count("decrypt_access"))
                put("home_reads",home.reads.get());put("home_publications",home.publications.get());put("home_invalidations",home.invalidations.get())
                put("home_discards",home.events.count {it.kind=="discarded"})
                put("home_events",buildJsonArray {home.events.forEach {e ->add(buildJsonObject {
                    put("kind",e.kind);put("at_ns",e.at-entry);put("expected",e.expected);put("current",e.current)
                })}})
                put("ack_ns",ackEnd-ackStart);put("ack_overlap_ns",overlap)
                put("facts",history+2);put("acceptances",history+2);put("outbox",0);put("sealed_fact_owner",false)
            }.toString());completed++
            val key="pair=$pair candidate=$candidate"
            if(committed-click>500_000_000L) failures+="$key save >500ms"
            if(joint.observed-click>700_000_000L) failures+="$key joint result >700ms"
            if(draw-click>100_000_000L) failures+="$key feedback >100ms"
            if(maxOf(initialHold,commandHold,homeHold,ackHold)>150_000_000L) failures+="$key resource >150ms"
            if(ackEnd-ackStart>500_000_000L) failures+="$key ACK >500ms"
            if(overlap==0L) failures+="$key no actual overlap"
        } }
        assertEquals(8,completed)
        assertTrue("Exploratory per-operation budgets, not P95: "+failures.joinToString(";"),failures.isEmpty())
    }
}
