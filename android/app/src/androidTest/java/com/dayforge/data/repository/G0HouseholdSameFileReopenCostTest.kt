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

/** Separate, finite question: after ONE actual saved and ACKed annual-history command,
 * what does a normal connection/memory rebuild cost on the SAME file and credentials?
 * It does not replace the retained-state six-command natural-contention failure or fix
 * its revision guard. No process kill, snapshot restore, persisted certificate, or retry.
 */
@RunWith(AndroidJUnit4::class)
class G0HouseholdSameFileReopenCostTest:G0HouseholdHistoryFixture() {
    private data class Window(val start:Long,val end:Long)
    private fun now()=SystemClock.elapsedRealtimeNanos()
    private fun spans(trace:G0PhaseTrace)=synchronized(trace.spans) {trace.spans.toList()}
    private fun count(trace:G0PhaseTrace,phase:String)=spans(trace).count {it.phase==phase}
    private fun transactions(trace:G0PhaseTrace):List<Window> {
        val rows=spans(trace)
        val begin=rows.filter {it.phase=="tx_begin_outer"};val end=rows.filter {it.phase=="tx_end_outer"}
        assertEquals("Every observed BEGIN needs its actual END",begin.size,end.size)
        return begin.zip(end).map {(a,b)->assertTrue(a.ok && b.ok && b.end>a.start);Window(a.start,b.end)}
    }
    private fun maximum(values:List<Window>)=values.maxOfOrNull {it.end-it.start} ?: 0L
    private fun windows(values:List<Window>,origin:Long)=JsonArray(values.map {buildJsonObject {
        put("start_ns",it.start-origin);put("end_ns",it.end-origin)
    }})
    private fun incremental(trace:G0PhaseTrace) {
        assertEquals(0,count(trace,"count_complete_history"));assertEquals(0,count(trace,"cold_reference"))
        assertTrue(spans(trace).none {it.phase.startsWith("reference_")})
    }
    private fun log(body:JsonObjectBuilder.()->Unit)=Log.i("G0RetainedReopenMeasure",buildJsonObject(body).toString())

    // Bounded runner allowance for two complete annual proofs plus independent validation.
    // The original per-phase latency budgets below remain unchanged and decide the outcome.
    @Test(timeout=900_000) fun annualHistoryOneActualAckThenNormalSameFileReopen()=runBlocking<Unit> {
        val fixtureStarted=now()
        val seed=seedPendingHouseholdHistory(730)
        val (http,server)=channel {successReply(it)}
        constructAcceptedHouseholdHistory(seed,http)
        verifyAcceptedHouseholdHistory(seed,http)
        assertEquals(0,server.requests.count {it.path.endsWith("/push")})
        log {put("kind","fixture");put("history",730);put("elapsed_ns",now()-fixtureStarted)
            put("history_constructed",true);put("historical_http_pushes",0);put("past_span_days",365)}

        val factories=AtomicInteger();val closes=AtomicInteger()
        val stores=mutableListOf<G0HouseholdCountStore>()
        val connection=G0CountConnectionOwner {db.close();closes.incrementAndGet()}
        fun runtime(trace:G0PhaseTrace)=G0HouseholdCountRuntime(this,connection,authority,tokens,
            {domainGate.changeSequence},http) {
            factories.incrementAndGet()
            trace.run {coldHousehold(http,capacityProfile=G0HouseholdCountStore.CapacityProfile.ANNUAL_730_PLUS_6)}
                .also {stores+=it}
        }
        val bootstrap=G0PhaseTrace(driverTiming=false);val reopen=G0PhaseTrace(driverTiming=false)
        val first=runtime(bootstrap);var second:G0HouseholdCountRuntime?=null
        val firstScreen=G0ScreenObservation();val secondScreen=G0ScreenObservation()
        val pages=ConcurrentHashMap<G0HouseholdCountPage,G0ScreenObservation>()
        val feedbacks=ConcurrentHashMap<G0HouseholdCountPage,G0HouseholdFeedbackObservation>()
        val refreshes=ConcurrentHashMap<G0HouseholdCountPage,G0PhaseTrace>()
        fun observe(page:G0HouseholdCountPage)=pages.computeIfAbsent(page) {G0ScreenObservation()}
        fun feedback(page:G0HouseholdCountPage)=feedbacks.computeIfAbsent(page) {G0HouseholdFeedbackObservation()}
        fun install(value:G0HouseholdCountRuntime,screen:G0ScreenObservation) {
            activity.scenario.onActivity {it.setContent {
                G0HouseholdCountRuntimeScreen(value,screen,::observe,::feedback)
            }}
        }
        val quantities=seed.habits.associate {it.uuid to 0L}.toMutableMap()
        val failures=mutableListOf<String>();var origin=0L;var stage="install";var primary:Throwable?=null
        fun budget(label:String,value:Long,limit:Long) {
            if(value>limit)failures+="$label ${value/1_000_000.0}ms >${limit/1_000_000}ms"
        }
        suspend fun open(value:G0HouseholdCountRuntime,screen:G0ScreenObservation,
            trace:G0PhaseTrace,path:String):G0HouseholdCountPage {
            stage="$path input"
            screen.await(value.state.value.version)
            val input=screen.tap("open");if(origin==0L)origin=input
            log {put("kind","input");put("history",730);put("path",path);put("input_ns",input-origin)}
            val feedbackPixels=screen.awaitAny {value.events.filter {it.phase in setOf(
                G0HouseholdCountRuntime.Phase.LOADING,G0HouseholdCountRuntime.Phase.OPEN)}.map {it.version}}
            stage="$path ready"
            val page=withTimeout(180_000) {requireNotNull(value.state.first {
                value.error?.let {problem->throw problem};it.phase==G0HouseholdCountRuntime.Phase.OPEN}.page)}
            // Existing runtime has no pre-observer trace injection; this covers subsequent
            // refreshes. Actual input-to-qualified-pixel timing includes the whole open.
            page.refreshTrace=refreshes.computeIfAbsent(page) {G0PhaseTrace(driverTiming=false)}
            val pixels=householdPixels(value,page,observe(page))
            val shown=requireNotNull(page.events.single {it.version==pixels.version}.view)
            assertEquals(quantities,shown.cards.mapValues {it.value.history.todayQuantity})
            val draw=screen.draws.first {row->value.events.any {it.version==row.version &&
                it.phase in setOf(G0HouseholdCountRuntime.Phase.LOADING,G0HouseholdCountRuntime.Phase.OPEN)}}
            assertEquals(2,count(trace,"count_complete_history"));assertTrue(count(trace,"decrypt_access")>=2)
            assertEquals(1,value.coldStarts)
            log {put("kind","cold_open");put("history",730);put("path",path)
                put("input_ns",input-origin);put("feedback_draw_ns",draw.at-input)
                put("feedback_observed_ns",feedbackPixels.observed-input);put("ready_observed_ns",pixels.observed-input)
                put("factory_elapsed_ns",trace.nanos("operation"));put("resource_ns",maximum(transactions(trace)))
                put("complete_history_calls",count(trace,"count_complete_history"));put("access",count(trace,"decrypt_access"))
                put("factory_calls",factories.get());put("same_disk_after_real_use",path=="LOCAL_REOPEN_AFTER_REAL_USE")
                put("process_killed",false);put("persistent_certificate_hit",false)}
            budget("$path feedback",draw.at-input,100_000_000L)
            budget("$path cold ready",pixels.observed-input,2_000_000_000L)
            return page
        }
        try {
            install(first,firstScreen)
            val page=open(first,firstScreen,bootstrap,"BOOTSTRAP")
            val originalDb=db;val originalGate=domainGate;val originalStore=stores.single()
            val coldSpans=spans(bootstrap)
            val screen=observe(page);val trace=G0PhaseTrace(driverTiming=false);page.trace=trace
            val row=seed.habits.first();stage="one actual command input"
            val input=screen.tap("increment:${row.uuid}")
            log {put("kind","input");put("history",730);put("path","ONE_REAL_COMMAND");put("input_ns",input-origin)}
            val command=withTimeout(20_000) {
                while(page.commandTiming==null) {first.error?.let {throw it};page.error?.let {throw it};delay(1)}
                requireNotNull(page.commandTiming)
            }
            stage="one actual command feedback"
            val feedbackPixels=screen.awaitAny {page.events.filter {it.version>=command.firstStateVersion && it.phase in setOf(
                G0HouseholdCountPage.Phase.VERIFYING,G0HouseholdCountPage.Phase.SAVING,
                G0HouseholdCountPage.Phase.SAVED,G0HouseholdCountPage.Phase.RESULT)}.map {it.version}}
            stage="one actual command result"
            withTimeout(20_000) {page.state.first {
                first.error?.let {problem->throw problem};page.error?.let {problem->throw problem}
                it.version>=command.firstStateVersion && it.phase==G0HouseholdCountPage.Phase.RESULT}}
            val pixels=householdPixels(first,page,screen)
            val saved=requireNotNull(page.saved);val timing=requireNotNull(page.commandTiming)
            assertSame(command.command,timing.command);assertEquals(row.uuid,saved.habitUuid)
            quantities[row.uuid]=1L
            val shown=requireNotNull(page.events.single {it.version==pixels.version}.view)
            assertEquals(quantities,shown.cards.mapValues {it.value.history.todayQuantity})
            assertEquals(saved.factUuid,shown.cards.getValue(row.uuid).followUp.claim.completionUuid)
            val commit=page.committedAt;assertEquals(commit,timing.knownCommitAt);assertTrue(commit>=input)
            assertTrue(page.events.filter {it.version>=command.firstStateVersion && it.phase in setOf(
                G0HouseholdCountPage.Phase.SAVED,G0HouseholdCountPage.Phase.RESULT)}.all {it.publishedAt>=commit})
            val draw=screen.draws.filter {it.version>=command.firstStateVersion}.minBy {it.at}
            val anonymous=feedback(page).draws.filter {it.commandVersion==command.firstStateVersion}.minByOrNull {it.at}
            val firstFeedback=minOf(draw.at,anonymous?.at ?: Long.MAX_VALUE)
            val tx=transactions(trace);assertTrue(tx.isNotEmpty());incremental(trace)
            assertTrue(count(trace,"decrypt_access")>=2)
            log {put("kind","operation");put("history",730);put("input_ns",input-origin)
                put("commit_at_ns",commit-origin);put("result_at_ns",pixels.observed-origin)
                put("commit_ns",commit-input);put("result_ns",pixels.observed-input)
                put("feedback_draw_ns",firstFeedback-input);put("page_feedback_draw_ns",draw.at-input)
                put("feedback_observed_ns",feedbackPixels.observed-input);put("anonymous_busy_draw_present",anonymous!=null)
                put("handler_observed",screen.clicks.any {it.key=="increment:${row.uuid}" && it.at>=input})
                put("resource_ns",maximum(tx));put("transactions",windows(tx,origin))
                put("access",count(trace,"decrypt_access"));put("complete_history_calls",count(trace,"count_complete_history"))}
            budget("command feedback",firstFeedback-input,100_000_000L)
            budget("command commit",commit-input,500_000_000L)
            budget("command result",pixels.observed-input,700_000_000L)
            budget("command resource",maximum(tx),150_000_000L)

            stage="condition on actual ACK drain"
            val waitStarted=now();withTimeout(60_000) {first.awaitSynced()}
            val drained=now();val attempt=first.workerAttempts.single()
            assertEquals(saved.requestId,attempt.requestId);assertEquals(NextOperationAcceptance.COMMITTED,attempt.accepted)
            assertEquals(1,server.requests.count {it.path.endsWith("/push")})
            val terminalPixels=householdPixels(first,page,screen)
            val terminalView=requireNotNull(page.events.single {it.version==terminalPixels.version}.view)
            assertEquals(quantities,terminalView.cards.mapValues {it.value.history.todayQuantity})
            requireVisiblePageText(seed.habits.map {"${it.name}：${quantities.getValue(it.uuid)} / ${it.targetValue}"}.toSet()+
                ("首页："+page.state.value.members.joinToString("，") {id->"${terminalView.cards.getValue(id).habit.name}=${quantities.getValue(id)}"}))
            assertEquals(1,factories.get());assertEquals(0,closes.get());assertEquals(coldSpans,spans(bootstrap))
            assertSame(originalDb,db);assertSame(originalGate,domainGate);assertSame(originalStore,stores.single())
            incremental(attempt.send);incremental(attempt.ack);assertTrue(count(attempt.ack,"decrypt_access")>=2)
            val send=transactions(attempt.send);val ack=transactions(attempt.ack)
            assertTrue(send.isNotEmpty() && ack.isNotEmpty())
            log {put("kind","conditioning_and_sync");put("history",730);put("real_saves",1);put("real_acks",1)
                put("wait_started_ns",waitStarted-origin);put("drained_ns",drained-origin);put("wait_ns",drained-waitStarted)
                put("send_started_ns",attempt.sendStarted-origin);put("send_ended_ns",attempt.sendEnded-origin)
                put("ack_started_ns",attempt.ackStarted-origin);put("ack_ended_ns",attempt.ackEnded-origin)
                put("ack_ns",attempt.ackEnded-attempt.ackStarted);put("commit_to_ack_ns",attempt.ackEnded-commit)
                put("send_transactions",windows(send,origin));put("ack_transactions",windows(ack,origin))
                put("access",count(attempt.ack,"decrypt_access"));put("complete_history_calls",count(attempt.ack,"count_complete_history"))}
            budget("ACK",attempt.ackEnded-attempt.ackStarted,500_000_000L)
            budget("send/ACK resource",maximum(send+ack),150_000_000L)
            // Only after the actual user journey and sync drain, never between inputs.
            val terminal=pageNativeState()
            for(table in listOf("completions","next_request_origins","next_transmissions","next_acceptances","sync_entity_state"))
                assertEquals(731,terminal.getValue(table).size)
            assertEquals(731,terminal.getValue("count_days").size);assertTrue(terminal.getValue("sync_outbox").isEmpty())
            assertEquals(0,first.pendingCount)

            stage="normal first runtime shutdown"
            assertTrue(first.shutdown());assertEquals(1,closes.get());assertFalse(connection.busy())
            // Reuse the actual resulting SQLite file. No image restore, seed, credential write,
            // shortcut validator or retry occurs between this close and the next UI open.
            val next=runtime(reopen);second=next;install(next,secondScreen)
            val nextPage=open(next,secondScreen,reopen,"LOCAL_REOPEN_AFTER_REAL_USE")
            assertEquals(2,factories.get());assertEquals(1,closes.get())
            assertNotSame(originalDb,db);assertNotSame(originalGate,domainGate);assertNotSame(originalStore,stores.last())
            assertNotSame(page,nextPage)
            val nextPixels=householdPixels(next,nextPage,observe(nextPage))
            val nextView=requireNotNull(nextPage.events.single {it.version==nextPixels.version}.view)
            assertEquals(terminalView.cards.mapValues {it.value.history},nextView.cards.mapValues {it.value.history})
            assertEquals(terminal,pageNativeState());assertTrue(next.workerAttempts.isEmpty())
            assertEquals(1,server.requests.count {it.path.endsWith("/push")})
            assertEquals(2,pages.size)

            stage="actual close and independent reference/replay"
            assertTrue(next.shutdown());assertEquals(2,closes.get());assertFalse(connection.busy())
            refreshes.entries.forEachIndexed {index,(_,refresh)->
                incremental(refresh);val refreshTx=transactions(refresh)
                log {put("kind","refresh");put("history",730);put("observer",index)
                    put("resource_ns",maximum(refreshTx));put("transactions",windows(refreshTx,origin))
                    put("access",count(refresh,"decrypt_access"));put("complete_history_calls",count(refresh,"count_complete_history"))
                    put("cancelled_or_failed_operations",spans(refresh).count {it.phase=="operation" && !it.ok})}
                budget("refresh $index resource",maximum(refreshTx),150_000_000L)
            }
            reopenReference();sameHouseholdAsOriginal(nextView)
            assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(requireNotNull(attempt.delivery)))
            assertEquals(terminal,pageNativeState())
            log {put("kind","terminal");put("history",730);put("facts",731);put("outbox",0)
                put("actual_new_http_pushes",1);put("actual_acks",1);put("runtime_instances",2)
                put("factory_calls",factories.get());put("close_callbacks",closes.get());put("reference_and_replay",true)
                put("same_file_reopen",true);put("process_killed",false);put("persistent_certificate_hit",false)
                put("history_constructed",true);put("network_synthetic",true);put("full_journey_complete",true)
                put("condition","one actual command followed by ACK drain; not six-command natural contention")
                put("prior_contention_failure_superseded",false);put("budget_failures",JsonArray(failures.map(::JsonPrimitive)))}
            stage="original budgets"
            assertTrue("Same-file post-use reopen: original cold/feedback/commit/result/ACK/resource budgets: $failures",failures.isEmpty())
        } catch(failure:Throwable) {
            primary=failure
            Log.e("G0RetainedReopenFailure",buildJsonObject {
                put("history",730);put("stage",stage);put("error",failure.javaClass.simpleName)
                put("runtime_phase",first.state.value.phase.name);put("reopened_phase",second?.state?.value?.phase?.name ?: "not_created")
                put("factories",factories.get());put("closes",closes.get())
                putJsonArray("page_inputs") {pages.forEach {(page,screen)->add(buildJsonObject {
                    put("phase",page.state.value.phase.name);put("inputs",screen.inputs.size);put("handlers",screen.clicks.size)
                    put("error",page.error?.javaClass?.simpleName ?: "none")
                })}}
                put("budget_failures_so_far",JsonArray(failures.map(::JsonPrimitive)))
            }.toString())
            throw failure
        } finally {
            val cleanup=runCatching {withContext(NonCancellable) {second?.shutdown();first.shutdown()}}.exceptionOrNull()
            if(cleanup!=null) {primary?.addSuppressed(cleanup) ?: throw cleanup}
        }
    }
}
