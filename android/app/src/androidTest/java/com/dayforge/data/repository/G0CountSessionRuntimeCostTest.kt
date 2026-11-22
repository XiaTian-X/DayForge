package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.setContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A real navigation journey, not a repeated cold/warm microbenchmark. Each visit
 * adds one count then leaves while the owned background consumer can continue. */
@RunWith(AndroidJUnit4::class)
class G0CountSessionRuntimeCostTest:G0CountLoadingFixture() {
    private fun now()=SystemClock.elapsedRealtimeNanos()
    private fun record(body:JsonObjectBuilder.()->Unit)=Log.i("G0SessionMeasure",buildJsonObject(body).toString())
    @Test(timeout=900_000) fun eightFactsThreeVisitsAndBackgroundProgress()=runBlocking<Unit> {journey(8)}
    @Test(timeout=900_000) fun eightyFactsThreeVisitsAndBackgroundProgress()=runBlocking<Unit> {journey(80)}
    private suspend fun journey(history:Int)=coroutineScope {
        seedAccepted(history);val (http,_)=channel {successReply(it)}
        val factories=AtomicInteger();val closes=AtomicInteger();val coldTrace=G0PhaseTrace(driverTiming=false)
        val connection=G0CountConnectionOwner {closes.incrementAndGet();db.close()}
        val runtime=G0CountSessionRuntime(this,connection,authority,tokens,{domainGate.changeSequence},http) {
            factories.incrementAndGet();coldTrace.run {coldCount(http)}
        }
        val seen=G0ScreenObservation();val pages=ConcurrentHashMap<G0ShortCountPage,G0ScreenObservation>()
        fun pageSeen(page:G0ShortCountPage)=pages.computeIfAbsent(page) {G0ScreenObservation()}
        val failures=mutableListOf<String>();val requests=mutableListOf<String>();val opens=mutableListOf<Pair<Long,Long>>()
        val visits=mutableListOf<JsonObject>();var firstInput=0L;var stage="setup"
        try {
            activity.scenario.onActivity {it.setContent {G0CountSessionScreen(runtime,seen,::pageSeen)}}
            seen.await(runtime.state.value.version);assertEquals(0,factories.get())
            repeat(3) {visit->
                stage="visit:$visit:open feedback"
                val eventStart=runtime.events.size
                val start=seen.tap(if(visit==0)"open" else "reopen");if(visit==0)firstInput=start
                val feedback=seen.awaitAny {runtime.events.drop(eventStart).filter {
                    it.phase in setOf(G0CountSessionRuntime.Phase.LOADING,G0CountSessionRuntime.Phase.OPEN)
                }.map {it.version}}
                stage="visit:$visit:open state"
                val opened=withTimeout(30_000) {runtime.state.first {
                    runtime.error?.let {throw it};it.phase==G0CountSessionRuntime.Phase.OPEN
                }}
                val page=requireNotNull(opened.page);val screen=pageSeen(page)
                stage="visit:$visit:valid view"
                withTimeout(20_000) {page.state.first {runtime.error?.let {throw it};it.view?.valid()==true}}
                stage="visit:$visit:content pixels"
                val content=G0ScreenObservation.awaitTogether {
                    val state=page.state.value
                    val view=state.view
                    if(!runtime.renderable() || view==null || !view.valid())null else listOf(
                        G0ScreenObservation.Target(screen,state.version) {
                            runtime.renderable() && page.state.value.version==state.version && view.valid()
                        })
                }
                val presented=page.events.single {it.version==content.version}.view!!
                assertEquals((history+visit).toLong(),presented.history.todayQuantity)
                assertFalse(page.events.any {it.phase==G0ShortCountPage.Phase.SAVED})
                opens+=start to content.observed
                val openStates=runtime.events.drop(eventStart)
                val loadingDraw=seen.draws.filter {d->openStates.any {it.version==d.version && it.phase in
                    setOf(G0CountSessionRuntime.Phase.LOADING,G0CountSessionRuntime.Phase.OPEN)}}.minBy {it.at}
                val writeTrace=G0PhaseTrace(driverTiming=false);page.trace=writeTrace
                val eventOffset=page.events.size
                stage="visit:$visit:count feedback"
                val input=screen.tap("increment")
                val saving=screen.awaitAny {page.events.drop(eventOffset).filter {it.phase in setOf(
                    G0ShortCountPage.Phase.VERIFYING,G0ShortCountPage.Phase.SAVING,G0ShortCountPage.Phase.SAVED,
                    G0ShortCountPage.Phase.RESULT)}.map {it.version}}
                val feedbackDraw=screen.draws.filter {d->page.events.drop(eventOffset).any {it.version==d.version &&
                    it.phase in setOf(G0ShortCountPage.Phase.VERIFYING,G0ShortCountPage.Phase.SAVING,
                        G0ShortCountPage.Phase.SAVED,G0ShortCountPage.Phase.RESULT)}}
                    .minOf {it.at}
                stage="visit:$visit:away state"
                val leave=seen.tap("leave")
                withTimeout(20_000) {runtime.state.first {runtime.error?.let {throw it};it.phase==G0CountSessionRuntime.Phase.AWAY}}
                stage="visit:$visit:away pixels"
                val away=seen.await(runtime.state.value.version)
                page.settled();assertNull(page.error);assertNull(runtime.error)
                val saved=requireNotNull(page.saved);requests+=saved.requestId
                assertFalse(page.live());assertNull(page.state.value.view)
                assertEquals(history+visit+1,pageRowCount("completions"))
                assertEquals(1,factories.get());assertEquals(0,closes.get())
                val trace=runtime.openTraces[visit]
                assertTrue("Every visit must authenticate anew",trace.count("decrypt_access")>=2)
                val row=buildJsonObject {
                    put("kind","visit");put("history",history);put("visit",visit);put("cold",visit==0)
                    put("open_ns",start-firstInput);put("open_feedback_draw_ns",loadingDraw.at-start)
                    put("open_feedback_observed_ns",feedback.observed-start);put("open_ready_observed_ns",content.observed-start)
                    put("count_ns",input-firstInput);put("count_feedback_draw_ns",feedbackDraw-input)
                    put("count_feedback_observed_ns",saving.observed-input);put("count_commit_ns",page.committedAt-input)
                    put("leave_ns",leave-firstInput);put("leave_away_observed_ns",away.observed-leave)
                    put("left_before_known_commit",leave<page.committedAt)
                    put("result_published_before_leave",page.events.any {it.phase==G0ShortCountPage.Phase.RESULT && it.publishedAt<leave})
                    put("read_resource_ns",transactionHold(trace));put("write_resource_ns",transactionHold(writeTrace))
                    put("read_access",trace.count("decrypt_access"));put("read_refresh",trace.count("decrypt_refresh"))
                    put("pending_at_away",runtime.pendingCount);put("factory_calls",factories.get())
                };visits+=row;Log.i("G0SessionMeasure",row.toString())
                if(loadingDraw.at-start>100_000_000L)failures+="$visit open feedback >100ms"
                if(content.observed-start>2_000_000_000L)failures+="$visit ready >2s"
                if(feedbackDraw-input>100_000_000L)failures+="$visit count feedback >100ms"
                if(page.committedAt-input>500_000_000L)failures+="$visit commit >500ms"
                if(transactionHold(trace)>150_000_000L)failures+="$visit read resource >150ms"
                if(transactionHold(writeTrace)>150_000_000L)failures+="$visit write resource >150ms"
            }
            stage="sync drain"
            val lastAway=now();withTimeout(60_000) {runtime.awaitSynced()};val synced=now()
            assertNull(runtime.error);assertEquals(0,runtime.pendingCount)
            val attempts=runtime.workerAttempts
            assertEquals(requests,attempts.map {it.requestId});assertTrue(attempts.all {it.accepted==NextOperationAcceptance.COMMITTED})
            assertEquals(0,pageRowCount("sync_outbox"));assertEquals(history+3,pageRowCount("next_acceptances"))
            // A fourth fresh page observes accepted facts; old-page READY versions must
            // never match its pixels. No extra count is executed on this terminal visit.
            stage="terminal open"
            val terminalOpen=seen.tap("reopen")
            val finalPage=withTimeout(20_000) {runtime.state.first {runtime.error?.let {throw it};it.phase==G0CountSessionRuntime.Phase.OPEN}}.page!!
            withTimeout(20_000) {finalPage.state.first {it.view?.valid()==true}}
            stage="terminal pixels"
            val finalPixels=pageSeen(finalPage).awaitAny {listOf(finalPage.state.value.version)}
            val final=finalPage.state.value.view!!
            requireVisiblePageText(setOf("卡片：${history+3}","首页：${history+3} / 1000000"))
            assertEquals((history+3).toLong(),final.history.todayQuantity)
            assertEquals(4,pages.size);assertEquals(1,factories.get());assertEquals(1,runtime.coldStarts)
            assertEquals(4,runtime.openTraces.size);assertTrue(runtime.openTraces.all {it.count("decrypt_access")>=2})
            stage="shutdown and reference replay"
            val before=pageNativeState();val shutdown=now();assertTrue(runtime.shutdown());val stopped=now()
            assertEquals(1,closes.get());assertFalse(connection.busy())
            reopenReference();sameAsOriginal(final);assertEquals(before,pageNativeState())
            for(a in attempts)assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(requireNotNull(a.delivery)))
            assertEquals(before,pageNativeState())
            attempts.forEachIndexed {i,a->
                val overlap=opens.drop(1).sumOf {(start,end)->maxOf(0L,minOf(end,a.ackEnded)-maxOf(start,a.ackStarted))}
                record {
                    put("kind","sync");put("history",history);put("index",i)
                    put("enqueued_ns",a.enqueued-firstInput);put("send_started_ns",a.sendStarted-firstInput)
                    put("send_ended_ns",a.sendEnded-firstInput);put("ack_started_ns",a.ackStarted-firstInput)
                    put("ack_ended_ns",a.ackEnded-firstInput);put("ack_overlap_with_warm_open_ns",overlap)
                    put("send_resource_ns",transactionHold(a.send));put("ack_resource_ns",transactionHold(a.ack))
                }
                if(a.ackEnded-a.ackStarted>500_000_000L)failures+="sync $i ack >500ms"
                if(maxOf(transactionHold(a.send),transactionHold(a.ack))>150_000_000L)failures+="sync $i resource >150ms"
            }
            record {
                put("kind","terminal");put("history",history);put("visits",4);put("commands",3);put("syncs",attempts.size)
                put("cold_starts",runtime.coldStarts);put("cold_resource_ns",transactionHold(coldTrace))
                put("last_away_to_sync_ns",synced-lastAway);put("terminal_open_ready_ns",finalPixels.observed-terminalOpen)
                put("entry_to_terminal_ns",finalPixels.observed-firstInput);put("shutdown_ns",stopped-shutdown)
                put("facts",history+3);put("outbox",0);put("reference_and_replay",true)
                put("network_synthetic",true);put("exact_first_presentation",false)
            }
            stage="original budgets"
            assertTrue("Original navigation/feedback/commit/resource/ACK budgets; exploratory, not P95: $failures",failures.isEmpty())
        } catch(failure:Throwable) {
            // Diagnostic-only: do not retry, extend timeouts, authorize a stale view,
            // or replace the original failure. Record before shutdown retires the UI.
            Log.e("G0SessionFailure",buildJsonObject {
                put("history",history);put("stage",stage);put("error",failure.javaClass.simpleName)
                put("runtime_error",runtime.error?.javaClass?.simpleName ?: "none")
                put("phase",runtime.state.value.phase.name);put("renderable",runtime.renderable())
                put("cold_starts",runtime.coldStarts);put("known_pending",runtime.pendingCount)
                putJsonArray("runtime_events") {runtime.events.forEach {add("${it.version}:${it.phase}")}}
                putJsonArray("runtime_draws") {seen.draws.forEach {add(it.version)}}
                putJsonArray("pages") {pages.forEach {(page,screen)->add(buildJsonObject {
                    put("current",runtime.state.value.page===page);put("live",page.live())
                    put("version",page.state.value.version);put("phase",page.state.value.phase.name)
                    put("valid",page.state.value.view?.valid()==true)
                    put("error",page.error?.javaClass?.simpleName ?: "none")
                    putJsonArray("events") {page.events.forEach {add("${it.version}:${it.phase}")}}
                    putJsonArray("draws") {screen.draws.forEach {add(it.version)}}
                    putJsonArray("clicks") {screen.clicks.forEach {add(it.key)}}
                })}}
                putJsonArray("sync") {runtime.workerAttempts.forEach {a->add(buildJsonObject {
                    put("send_started",a.sendStarted);put("send_ended",a.sendEnded)
                    put("ack_started",a.ackStarted);put("ack_ended",a.ackEnded)
                    put("accepted",a.accepted?.name ?: "none")
                })}}
            }.toString())
            throw failure
        } finally {runtime.shutdown()}
    }
}
