package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Same sealed owner in both arms: worker starts during input or after the burst. This
 * isolates contention, NOT a fresh old-architecture comparison. Six real taps/window,
 * one window/arm/size: exploratory ranges, not P95 or sustained-throughput certification. */
@RunWith(AndroidJUnit4::class)
class G0ShortCountBacklogCostTest:G0ShortCountBacklogCostFixture() {
    @Test(timeout=900_000) fun eightFactsContinuousQueueAndDrain()=runBlocking<Unit> {measure(8)}
    @Test(timeout=900_000) fun eightyFactsContinuousQueueAndDrain()=runBlocking<Unit> {measure(80)}
}

/** Short-history reproduction of the SAME workload, with failure state diagnostics.
 * This class does not replace the two-size cost gate or turn timeout into success. */
@RunWith(AndroidJUnit4::class)
class G0ShortCountBacklogDiagnosticTest:G0ShortCountBacklogCostFixture() {
    @Test(timeout=300_000) fun shortHistoryFailureStateAndQueueProgress()=runBlocking<Unit> {measure(8)}
}

abstract class G0ShortCountBacklogCostFixture:G0ShortCountCalendarFixture() {
    private fun now()=SystemClock.elapsedRealtimeNanos()
    private fun record(value:JsonObject) {Log.i("G0BacklogMeasure",value.toString())}
    protected suspend fun measure(history:Int,daysAgo:List<Long>?=null,
        authorityArms:List<Boolean> = listOf(jointCountAuthority),interResultDelayMs:Long=0,
        concurrentArms:List<Boolean> = if(history==8)listOf(false,true)else listOf(true,false))=coroutineScope {
        require(authorityArms.isNotEmpty() && authorityArms.distinct()==authorityArms)
        require(concurrentArms.isNotEmpty() && concurrentArms.distinct()==concurrentArms)
        require(interResultDelayMs in 0L..5_000L)
        val today=java.time.LocalDate.now()
        val seedQuantities=if(daysAgo==null) {
            seedAccepted(history);mapOf(today to history.toLong())
        } else {
            require(daysAgo.size==history && 0L in daysAgo)
            seedCalendarAccepted(daysAgo).quantities
        }
        val distribution=daysAgo?.let {"calendar_${it.maxOrNull()}"} ?: "same_day"
        val initialQuantities=seedQuantities+(today to (seedQuantities.getValue(today)+1L))
        seedFact();val image=storage.snapshotClosedDatabase();val failures=mutableListOf<String>()
        record(buildJsonObject {
            put("kind","fixture");put("history",history);put("distribution",distribution)
            put("think_ms",interResultDelayMs);put("authority_arms",JsonArray(authorityArms.map(::JsonPrimitive)))
            put("concurrent_arms",JsonArray(concurrentArms.map(::JsonPrimitive)))
            put("seed_fact_rows",history);put("entry_fact_rows",history+1);put("terminal_fact_rows",history+7)
            put("day_count",seedQuantities.size);put("span_days",daysAgo?.maxOrNull() ?: 0L)
            put("days_ago",JsonArray((daysAgo ?: List(history) {0L}).map(::JsonPrimitive)))
            put("initial_today_quantity",initialQuantities.getValue(today))
        })
        var completeWindows=0
        for((pair,concurrent) in concurrentArms.withIndex()) {
        for(joint in if(pair%2==0)authorityArms else authorityArms.reversed()) {
            val windowId="${distribution}_h${history}_t${interResultDelayMs}_p${pair}_j${joint}_c$concurrent"
            fun windowRecord(body:JsonObjectBuilder.()->Unit)=record(buildJsonObject {
                put("window_id",windowId);put("joint",joint);put("pair",pair);put("think_ms",interResultDelayMs)
                body()
            })
            freshGateForColdOpen();restoreForJourney(image)
            val old=db.syncOutboxDao().getAll().single().operationId;val entry=now()
            val store=openCount(jointAuthority=joint);val (http,_)=channel {successReply(it)}
            val worker=G0ShortCountSyncWorker(store,http,this);assertTrue(worker.offer(old))
            val page=G0ShortCountPage(store,tokens,this);val seen=G0ScreenObservation()
            val refreshTrace=G0PhaseTrace(driverTiming=false);page.refreshTrace=refreshTrace
            val intervals=mutableListOf<Pair<Long,Long>>();var lastResult=0L;var foregroundResource=0L
            var stage="initial";var stepIndex=-1
            try {
                page.open();page.watchChanges(domainGate.changeSequence);install(page,seen)
                seen.awaitAny {listOf(page.state.value.version)};val initial=now()
                if(concurrent)worker.start()
                repeat(6) {step->
                    // Closed-loop synthetic thinking time, not a fixed arrival rate.
                    // Existing stress workloads keep zero delay and their overlap gate.
                    if(step>0 && interResultDelayMs>0)delay(interResultDelayMs)
                    stepIndex=step;stage="ready"
                    worker.failure?.let {throw it}
                    withTimeout(20_000) {while(page.state.value.view?.valid()!=true) {page.error?.let {throw it};delay(5)}}
                    val eventStart=page.events.size;val priorSaved=page.saved;val trace=G0PhaseTrace(driverTiming=false);page.trace=trace
                    val inputStart=seen.inputs.size;val clickStart=seen.clicks.size;val priorCommand=page.command
                    stage="tap"
                    val start=seen.tap("increment")
                    val inputPhases=setOf(G0ShortCountPage.Phase.VERIFYING,G0ShortCountPage.Phase.SAVING)
                    fun commandEvents()=page.events.drop(eventStart).dropWhile {it.phase !in inputPhases}
                    stage="feedback_pixels"
                    val feedbackPhases=inputPhases+setOf(G0ShortCountPage.Phase.SAVED,G0ShortCountPage.Phase.RESULT)
                    val feedback=seen.awaitAny {commandEvents().filter {it.phase in feedbackPhases}.map {it.version}}
                    stage="command_state"
                    withTimeout(20_000) {page.state.first {it.phase==G0ShortCountPage.Phase.RECOVERY || page.saved!=priorSaved && it.phase==G0ShortCountPage.Phase.RESULT}}
                    page.settled();assertNull(page.error);val saved=requireNotNull(page.saved)
                    stage="offer";worker.failure?.let {throw it}
                    assertTrue("Known commit rejected; worker failure=${worker.failure}",worker.offer(saved.requestId))
                    stage="result_pixels"
                    val result=seen.awaitAny {commandEvents().filter {it.phase==G0ShortCountPage.Phase.RESULT}.map {it.version}}
                    val view=requireNotNull(page.state.value.view)
                    val expected=initialQuantities+(today to (initialQuantities.getValue(today)+step+1L))
                    assertEquals(expected.getValue(today),view.history.todayQuantity);assertEquals(expected,view.history.quantities)
                    assertEquals(saved.factUuid,view.followUp.claim.completionUuid)
                    val events=commandEvents()
                    val firstDraw=seen.draws.filter {d->events.any {it.version==d.version && it.phase in feedbackPhases}}.minBy {it.at}
                    val drawn=firstDraw.at
                    intervals+=start to result.observed;foregroundResource=maxOf(foregroundResource,transactionHold(trace))
                    windowRecord {
                        put("kind","step");put("history",history);put("distribution",distribution);put("concurrent",concurrent);put("step",step)
                        put("click_ns",start-entry);put("commit_ns",page.committedAt-start);put("feedback_draw_ns",drawn-start)
                        put("feedback_observed_ns",feedback.observed-start);put("result_ns",result.observed-start)
                        put("previous_result_to_click_ns",if(step==0)0L else start-lastResult)
                        put("resource_ns",transactionHold(trace));put("access",trace.count("decrypt_access"));put("refresh",trace.count("decrypt_refresh"))
                        put("admission_read",events.any {it.phase==G0ShortCountPage.Phase.VERIFYING})
                        put("outstanding",worker.depths.last().outstanding);put("accepted",worker.attempts.count {it.accepted!=null})
                    }
                    // Each stage is tied to THIS admitted command and to the version
                    // actually drawn/observed. A RESULT frame cannot borrow SAVING's time.
                    stage="input_timing"
                    val input=seen.inputs.drop(inputStart).single();val click=seen.clicks.drop(clickStart).single()
                    val timing=requireNotNull(page.commandTiming)
                    assertEquals("increment",input.key);assertEquals("increment",click.key);assertEquals(start,input.started)
                    assertNotSame(priorCommand,timing.command);assertSame(page.command,timing.command)
                    assertEquals(saved.requestId,timing.command.requestId);assertEquals(saved.factUuid,timing.command.factUuid)
                    val firstState=events.first();assertTrue(firstState.phase in inputPhases)
                    assertEquals(firstState.version,timing.firstStateVersion)
                    val drawnState=events.single {it.version==firstDraw.version}
                    val observedState=events.single {it.version==feedback.version}
                    val resultState=events.single {it.version==result.version}
                    val firstResult=events.first {it.phase==G0ShortCountPage.Phase.RESULT}
                    assertTrue(drawnState.phase in feedbackPhases);assertTrue(observedState.phase in feedbackPhases)
                    assertEquals(G0ShortCountPage.Phase.RESULT,resultState.phase)
                    assertTrue(input.started<=input.downReturned && input.downReturned<=input.upStarted && input.upStarted<=input.upReturned)
                    // injectInputEvent waits synchronously; onClick may precede UP's return.
                    assertTrue(click.at>=input.upStarted && firstState.publishedAt>=click.at)
                    assertTrue(timing.ioStartedAt>=firstState.publishedAt)
                    assertTrue(timing.knownCommitAt>=timing.ioStartedAt && timing.knownCommitAt==page.committedAt)
                    assertTrue(firstResult.publishedAt>=timing.knownCommitAt)
                    assertTrue(firstDraw.at>=drawnState.publishedAt)
                    assertTrue(feedback.observed>=observedState.publishedAt && feedback.observed>=feedback.captureStarted)
                    assertTrue(result.observed>=resultState.publishedAt && result.observed>=result.captureStarted)
                    windowRecord {
                        put("kind","input_timing");put("history",history);put("distribution",distribution);put("concurrent",concurrent);put("step",step)
                        put("offset_origin","pre_down");put("click_ns",start-entry)
                        put("down_started_ns",input.started-start);put("down_returned_ns",input.downReturned-start)
                        put("up_started_ns",input.upStarted-start);put("up_returned_ns",input.upReturned-start);put("on_click_ns",click.at-start)
                        put("first_state",buildJsonObject {put("version",firstState.version);put("phase",firstState.phase.name);put("published_ns",firstState.publishedAt-start)})
                        put("command_io_started_ns",timing.ioStartedAt-start);put("known_commit_ns",timing.knownCommitAt-start)
                        put("first_result",buildJsonObject {put("version",firstResult.version);put("phase",firstResult.phase.name);put("published_ns",firstResult.publishedAt-start)})
                        put("first_draw",buildJsonObject {put("version",drawnState.version);put("phase",drawnState.phase.name);put("published_ns",drawnState.publishedAt-start);put("drawn_ns",firstDraw.at-start)})
                        put("feedback_observation",buildJsonObject {put("version",observedState.version);put("phase",observedState.phase.name);put("published_ns",observedState.publishedAt-start);put("capture_started_ns",feedback.captureStarted-start);put("observed_ns",feedback.observed-start)})
                        put("result_observation",buildJsonObject {put("version",resultState.version);put("phase",resultState.phase.name);put("published_ns",resultState.publishedAt-start);put("capture_started_ns",result.captureStarted-start);put("observed_ns",result.observed-start)})
                    }
                    if(page.committedAt-start>500_000_000L)failures+="$windowId/$step commit >500ms"
                    if(drawn-start>100_000_000L)failures+="$windowId/$step feedback >100ms"
                    if(result.observed-start>700_000_000L)failures+="$windowId/$step result >700ms"
                    if(transactionHold(trace)>150_000_000L)failures+="$windowId/$step resource >150ms"
                    lastResult=result.observed
                }
                stage="drain"
                withTimeout(60_000) {worker.finish()};val drained=now();assertNull(worker.failure)
                withTimeout(20_000) {while(page.state.value.view?.valid()!=true) {page.error?.let {throw it};delay(5)}}
                stage="terminal_pixels"
                val terminal=seen.awaitAny {listOf(page.state.value.version)};val result=requireNotNull(page.state.value.view)
                val expected=initialQuantities+(today to (initialQuantities.getValue(today)+6L))
                assertEquals(expected.getValue(today),result.history.todayQuantity);assertEquals(expected,result.history.quantities)
                var overlaps=0
                worker.attempts.forEachIndexed {i,a->
                    assertEquals(NextOperationAcceptance.COMMITTED,a.accepted)
                    val overlap=intervals.sumOf {(start,end)->maxOf(0L,minOf(end,a.ackEnded)-maxOf(start,a.ackStarted))}
                    if(overlap>0)overlaps++
                    windowRecord {
                        put("kind","sync");put("history",history);put("distribution",distribution);put("concurrent",concurrent);put("index",i)
                        put("queue_wait_ns",a.sendStarted-a.enqueued);put("send_ns",a.sendEnded-a.sendStarted);put("ack_ns",a.ackEnded-a.ackStarted)
                        put("enqueued_ns",a.enqueued-entry);put("send_started_ns",a.sendStarted-entry);put("send_ended_ns",a.sendEnded-entry)
                        put("ack_started_ns",a.ackStarted-entry);put("ack_ended_ns",a.ackEnded-entry)
                        put("sojourn_ns",a.ackEnded-a.enqueued);put("overlap_ns",overlap)
                        put("send_resource_ns",transactionHold(a.send));put("ack_resource_ns",transactionHold(a.ack))
                        put("send_access",a.send.count("decrypt_access"));put("send_refresh",a.send.count("decrypt_refresh"));put("ack_access",a.ack.count("decrypt_access"))
                    }
                    if(a.ackEnded-a.ackStarted>500_000_000L)failures+="$windowId ACK $i >500ms"
                    if(maxOf(transactionHold(a.send),transactionHold(a.ack))>150_000_000L)failures+="$windowId sync $i resource >150ms"
                }
                assertEquals(7,worker.attempts.size);assertEquals(0,worker.depths.last().outstanding)
                // Only reconstruct pending age AFTER complete drain. Before then the
                // attempts list omits hints that have not started, so it cannot prove 0.
                assertEquals(7,worker.attempts.map {it.requestId}.distinct().size)
                intervals.forEachIndexed {step,(click,cutoff) ->
                    val pending=worker.attempts.filter {it.enqueued<=cutoff && it.ackEnded>cutoff}
                    val age=pending.minOfOrNull {it.enqueued}?.let {cutoff-it} ?: 0L
                    windowRecord {
                        put("kind","queue_at_result");put("step",step);put("at_ns",cutoff-entry)
                        put("unaccepted",pending.size);put("oldest_pending_ns",age)
                        put("released_slots_depth",worker.depths.last {it.at<=cutoff}.outstanding)
                        put("accepted",worker.attempts.count {it.ackEnded<=cutoff})
                        put("ack_before_click",worker.attempts.count {it.ackEnded<=click})
                        put("ack_during_think",if(step==0)0 else worker.attempts.count {
                            it.ackEnded>intervals[step-1].second && it.ackEnded<=click})
                    }
                }
                val state=pageNativeState();page.close();page.settled();reopenReference();sameAsOriginal(result)
                assertEquals(history+7,count("completions"));assertEquals(history+7,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
                for(a in worker.attempts)assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(requireNotNull(a.delivery)))
                assertEquals(state,pageNativeState())
                val pendingAtLast=worker.attempts.filter {it.enqueued<=lastResult && it.ackEnded>lastResult}
                val ackBeforeLast=worker.attempts.count {it.ackEnded<=lastResult}
                val acksDuringInput=worker.attempts.count {it.ackEnded in intervals.first().first..lastResult}
                val sendsDuringInput=worker.attempts.count {it.sendStarted in intervals.first().first..lastResult}
                windowRecord {
                    put("kind","window");put("history",history);put("distribution",distribution);put("concurrent",concurrent);put("commands",6);put("syncs",7)
                    put("initial_ns",initial-entry);put("burst_ns",lastResult-intervals.first().first)
                    put("last_result_to_drain_ns",drained-lastResult);put("last_click_to_terminal_ns",terminal.observed-intervals.last().first)
                    put("entry_to_terminal_ns",terminal.observed-entry)
                    put("outstanding_at_last_result",worker.depths.last {it.at<=lastResult}.outstanding)
                    put("max_outstanding",worker.depths.maxOf {it.outstanding});put("ack_overlaps",overlaps)
                    put("unaccepted_at_last_result",pendingAtLast.size)
                    put("oldest_at_last_result_ns",pendingAtLast.minOfOrNull {it.enqueued}?.let {lastResult-it} ?: 0L)
                    put("max_pending_age_ns",worker.attempts.maxOf {it.ackEnded-it.enqueued})
                    put("ack_before_last_result",ackBeforeLast);put("sends_during_input_window",sendsDuringInput)
                    put("acks_during_input_window",acksDuringInput)
                    put("require_ack_overlap",concurrent && interResultDelayMs==0L)
                    put("refresh_transactions",refreshTrace.count("tx_end_outer"));put("refresh_resource_ns",transactionHold(refreshTrace))
                    put("foreground_resource_ns",foregroundResource);put("outbox",0);put("network_synthetic",true);put("exact_first_presentation",false)
                    put("original_reader_and_replay_verified",true)
                }
                completeWindows++
                if(concurrent && interResultDelayMs==0L && overlaps==0)failures+="$windowId missing actual ACK overlap"
                if(concurrent && interResultDelayMs>0 && (acksDuringInput==0 || sendsDuringInput==0))
                    failures+="$windowId paced background did not advance during input"
                if(transactionHold(refreshTrace)>150_000_000L)failures+="$windowId refresh resource >150ms"
            } catch(error:Exception) {
                windowRecord {
                    put("kind","failure");put("history",history);put("distribution",distribution);put("concurrent",concurrent);put("step",stepIndex);put("stage",stage)
                    put("exception",error.javaClass.simpleName);put("message",error.message)
                    put("page_error",page.error?.toString());put("worker_error",worker.failure?.toString())
                    put("phase",page.state.value.phase.name);put("view_valid",page.state.value.view?.valid())
                    put("inputs",seen.inputs.size);put("click_callbacks",seen.clicks.size);put("attempts",worker.attempts.size)
                    put("accepted",worker.attempts.count {it.accepted!=null});put("outstanding",worker.depths.last().outstanding)
                    put("events",page.events.joinToString {"${it.version}:${it.phase}"})
                };throw error
            } finally {page.close();page.settled();worker.shutdown()}
        }
        }
        assertEquals(authorityArms.size*concurrentArms.size,completeWindows)
        assertTrue("Continuous screen exploratory budgets, NOT P95: "+failures.joinToString(";"),failures.isEmpty())
    }
}
