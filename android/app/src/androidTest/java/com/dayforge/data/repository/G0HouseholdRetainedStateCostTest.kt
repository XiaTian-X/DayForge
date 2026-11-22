package com.dayforge.data.repository

import android.database.sqlite.SQLiteDatabase
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

/** One complete bootstrap, then real incremental use of the SAME runtime/store/connection.
 * Two page reconstructions do not reopen SQLite. The final local reopen DOES rebuild memory
 * from the unchanged, actually used file: it is neither a process kill nor a persisted-proof hit.
 * Historical ACK rows are constructed and independently audited; all six NEW requests use HTTP.
 * No ACK hooks, artificial dwell, arrival alignment, retrying taps, or fastest-sample selection.
 */
@RunWith(AndroidJUnit4::class)
class G0HouseholdRetainedStateCostTest:G0HouseholdHistoryFixture() {
    private data class Window(val start:Long,val end:Long)
    private data class Operation(val index:Int,val requestId:String,val input:Long,val io:Long,
        val commit:Long,val result:Long,val trace:G0PhaseTrace)
    private data class Reentry(val index:Int,val input:Long,val result:Long,val trace:G0PhaseTrace)
    private fun now()=SystemClock.elapsedRealtimeNanos()
    private fun spans(trace:G0PhaseTrace)=synchronized(trace.spans) {trace.spans.toList()}
    private fun count(trace:G0PhaseTrace,phase:String)=spans(trace).count {it.phase==phase}
    private fun transactions(trace:G0PhaseTrace):List<Window> {
        val rows=spans(trace)
        val begin=rows.filter {it.phase=="tx_begin_outer"};val end=rows.filter {it.phase=="tx_end_outer"}
        assertEquals("Every observed BEGIN needs its actual END",begin.size,end.size)
        return begin.zip(end).map {(a,b)->assertTrue(a.ok && b.ok && b.end>a.start);Window(a.start,b.end)}
    }
    private fun maximum(windows:List<Window>)=windows.maxOfOrNull {it.end-it.start} ?: 0L
    private fun overlap(a:Window,b:Window)=maxOf(0L,minOf(a.end,b.end)-maxOf(a.start,b.start))
    private fun windows(values:List<Window>,origin:Long)=JsonArray(values.map {buildJsonObject {
        put("start_ns",it.start-origin);put("end_ns",it.end-origin)
    }})
    private fun log(body:JsonObjectBuilder.()->Unit)=Log.i("G0RetainedStateMeasure",buildJsonObject(body).toString())
    private fun requireIncremental(trace:G0PhaseTrace) {
        assertEquals("Warm work must not repeat full history validation",0,count(trace,"count_complete_history"))
        assertEquals(0,count(trace,"cold_reference"));assertEquals(0,count(trace,"cold_frozen_capture"))
        assertTrue("Warm work must not rebuild a reference database",spans(trace).none {it.phase.startsWith("reference_")})
    }
    // A narrow independent outbox sample, never a proof or a full-table audit between inputs.
    private fun durablePending():List<String> = SQLiteDatabase.openDatabase(
        app.getDatabasePath("habit_database").path,null,SQLiteDatabase.OPEN_READONLY
    ).use {raw->raw.rawQuery("SELECT operationId FROM sync_outbox ORDER BY id",null).use {cursor->
        buildList {while(cursor.moveToNext())add(cursor.getString(0))}
    }}

    @Test(timeout=1_200_000) fun eightFactsRetainedUseAndSameFileReopen()=runBlocking<Unit> {journey(8)}
    @Test(timeout=1_200_000) fun annualHistoryRetainedUseAndSameFileReopen()=runBlocking<Unit> {journey(730)}

    private suspend fun journey(history:Int)=coroutineScope {
        val fixtureStarted=now()
        val seed=seedPendingHouseholdHistory(history)
        val (http,server)=channel {successReply(it)}
        constructAcceptedHouseholdHistory(seed,http)
        verifyAcceptedHouseholdHistory(seed,http)
        assertEquals(0,server.requests.count {it.path.endsWith("/push")})
        val profile=if(history==730)G0HouseholdCountStore.CapacityProfile.ANNUAL_730_PLUS_6
            else G0HouseholdCountStore.CapacityProfile.DEFAULT_128
        log {put("kind","fixture");put("history",history);put("elapsed_ns",now()-fixtureStarted)
            put("historical_http_pushes",0);put("history_constructed",true);put("past_span_days",history/2)}

        val factories=AtomicInteger();val closes=AtomicInteger()
        val bootstrap=G0PhaseTrace(driverTiming=false);val reopened=G0PhaseTrace(driverTiming=false)
        val stores=mutableListOf<G0HouseholdCountStore>()
        val connection=G0CountConnectionOwner {db.close();closes.incrementAndGet()}
        fun runtime(trace:G0PhaseTrace)=G0HouseholdCountRuntime(this,connection,authority,tokens,
            {domainGate.changeSequence},http) {
            factories.incrementAndGet()
            trace.run {coldHousehold(http,capacityProfile=profile)}.also {stores+=it}
        }
        val firstRuntime=runtime(bootstrap)
        var secondRuntime:G0HouseholdCountRuntime?=null
        val seen=G0ScreenObservation();val reopenedSeen=G0ScreenObservation()
        val pages=ConcurrentHashMap<G0HouseholdCountPage,G0ScreenObservation>()
        val feedbackPages=ConcurrentHashMap<G0HouseholdCountPage,G0HouseholdFeedbackObservation>()
        val refreshes=ConcurrentHashMap<G0HouseholdCountPage,G0PhaseTrace>()
        fun observe(page:G0HouseholdCountPage)=pages.computeIfAbsent(page) {G0ScreenObservation()}
        fun feedback(page:G0HouseholdCountPage)=feedbackPages.computeIfAbsent(page) {G0HouseholdFeedbackObservation()}
        fun watch(page:G0HouseholdCountPage) {
            page.refreshTrace=refreshes.computeIfAbsent(page) {G0PhaseTrace(driverTiming=false)}
        }
        fun install(value:G0HouseholdCountRuntime,screen:G0ScreenObservation) {
            activity.scenario.onActivity {it.setContent {
                G0HouseholdCountRuntimeScreen(value,screen,::observe,::feedback)
            }}
        }
        val failures=mutableListOf<String>();val operations=mutableListOf<Operation>()
        val reentries=mutableListOf<Reentry>();val savedAt=linkedMapOf<String,Long>()
        val totals=seed.habits.associate {it.uuid to 0L}.toMutableMap()
        var origin=0L;var stage="install";var primary:Throwable?=null
        var largestPending=0
        fun budget(label:String,value:Long,limit:Long) {if(value>limit)failures+="$label ${value/1_000_000.0}ms >${limit/1_000_000}ms"}
        suspend fun openCold(value:G0HouseholdCountRuntime,screen:G0ScreenObservation,
            trace:G0PhaseTrace,label:String):G0HouseholdCountPage {
            stage="$label input"
            screen.await(value.state.value.version)
            val input=screen.tap("open")
            if(origin==0L)origin=input
            log {put("kind","input");put("history",history);put("path",label);put("input_ns",input-origin)}
            stage="$label feedback"
            val feedbackPixels=screen.awaitAny {value.events.filter {it.phase in setOf(
                G0HouseholdCountRuntime.Phase.LOADING,G0HouseholdCountRuntime.Phase.OPEN)}.map {it.version}}
            stage="$label ready"
            val page=withTimeout(180_000) {requireNotNull(value.state.first {
                value.error?.let {problem->throw problem};it.phase==G0HouseholdCountRuntime.Phase.OPEN}.page)}
            watch(page)
            val pixels=householdPixels(value,page,observe(page))
            val shown=requireNotNull(page.events.single {it.version==pixels.version}.view)
            assertEquals(totals,shown.cards.mapValues {it.value.history.todayQuantity})
            val draw=screen.draws.first {row->value.events.any {it.version==row.version &&
                it.phase in setOf(G0HouseholdCountRuntime.Phase.LOADING,G0HouseholdCountRuntime.Phase.OPEN)}}
            assertEquals(2,count(trace,"count_complete_history"))
            assertTrue(count(trace,"decrypt_access")>=2)
            assertEquals(1,value.coldStarts)
            log {put("kind","cold_open");put("history",history);put("path",label)
                put("input_ns",input-origin);put("feedback_draw_ns",draw.at-input)
                put("feedback_observed_ns",feedbackPixels.observed-input);put("ready_observed_ns",pixels.observed-input)
                put("factory_elapsed_ns",trace.nanos("operation"));put("resource_ns",maximum(transactions(trace)))
                put("complete_history_calls",count(trace,"count_complete_history"));put("access",count(trace,"decrypt_access"))
                put("factory_calls",factories.get());put("cold_starts",value.coldStarts)
                put("same_disk_after_real_use",label=="LOCAL_REOPEN_AFTER_REAL_USE")
                put("process_killed",false);put("persistent_certificate_hit",false)}
            budget("$label feedback",draw.at-input,100_000_000L)
            budget("$label cold ready",pixels.observed-input,2_000_000_000L)
            return page
        }
        try {
            install(firstRuntime,seen)
            var page=openCold(firstRuntime,seen,bootstrap,"BOOTSTRAP")
            // Record identity only AFTER lazy Room initialization has actually completed.
            val retainedDb=db;val retainedGate=domainGate;val retainedStore=stores.single()
            val retainedHandle=db.openHelper.writableDatabase
            val originalColdSpans=spans(bootstrap)
            fun requireRetained() {
                assertEquals(1,factories.get());assertEquals(1,firstRuntime.coldStarts);assertEquals(0,closes.get())
                assertSame(retainedDb,db);assertSame(retainedGate,domainGate);assertSame(retainedStore,stores.single())
                assertSame(retainedHandle,db.openHelper.writableDatabase)
                assertEquals(originalColdSpans,spans(bootstrap))
            }
            suspend fun reenter(index:Int) {
                stage="leave:$index"
                val prior=page;val beforeVersion=firstRuntime.state.value.version
                val leave=seen.tap("leave")
                val left=seen.awaitAny {firstRuntime.events.filter {it.version>beforeVersion && it.phase in setOf(
                    G0HouseholdCountRuntime.Phase.LEAVING,G0HouseholdCountRuntime.Phase.AWAY)}.map {it.version}}
                val leaveDraw=seen.draws.first {row->firstRuntime.events.any {it.version==row.version &&
                    it.version>beforeVersion && it.phase in setOf(G0HouseholdCountRuntime.Phase.LEAVING,G0HouseholdCountRuntime.Phase.AWAY)}}
                withTimeout(20_000) {firstRuntime.state.first {
                    firstRuntime.error?.let {problem->throw problem};it.phase==G0HouseholdCountRuntime.Phase.AWAY}}
                assertFalse(prior.live());assertEquals(0,closes.get())
                val priorOpen=firstRuntime.openTraces.size;val priorEvent=firstRuntime.state.value.version
                stage="warm reopen:$index"
                val input=seen.tap("reopen")
                log {put("kind","input");put("history",history);put("path","WARM_PAGE_REENTRY")
                    put("index",index);put("input_ns",input-origin)}
                val feedbackPixels=seen.awaitAny {firstRuntime.events.filter {it.version>priorEvent && it.phase in setOf(
                    G0HouseholdCountRuntime.Phase.LOADING,G0HouseholdCountRuntime.Phase.OPEN)}.map {it.version}}
                page=householdPage(firstRuntime);assertNotSame(prior,page);watch(page)
                val pixels=householdPixels(firstRuntime,page,observe(page))
                val shown=requireNotNull(page.events.single {it.version==pixels.version}.view)
                assertEquals(totals,shown.cards.mapValues {it.value.history.todayQuantity})
                assertEquals(priorOpen+1,firstRuntime.openTraces.size)
                val trace=firstRuntime.openTraces.last();requireIncremental(trace)
                assertTrue(count(trace,"decrypt_access")>=2);requireRetained()
                val tx=transactions(trace);assertTrue(tx.isNotEmpty())
                val draw=seen.draws.first {row->firstRuntime.events.any {it.version==row.version &&
                    it.version>priorEvent && it.phase in setOf(G0HouseholdCountRuntime.Phase.LOADING,G0HouseholdCountRuntime.Phase.OPEN)}}
                reentries+=Reentry(index,input,pixels.observed,trace)
                log {put("kind","warm_open");put("history",history);put("index",index)
                    put("leave_input_ns",leave-origin);put("leave_draw_ns",leaveDraw.at-leave)
                    put("leave_observed_ns",left.observed-leave);put("input_ns",input-origin)
                    put("feedback_draw_ns",draw.at-input);put("feedback_observed_ns",feedbackPixels.observed-input)
                    put("ready_observed_ns",pixels.observed-input);put("resource_ns",maximum(tx))
                    put("transactions",windows(tx,origin));put("access",count(trace,"decrypt_access"))
                    put("complete_history_calls",count(trace,"count_complete_history"));put("factory_calls",factories.get())
                    put("runtime_retained",true);put("store_retained",true);put("database_retained",true)
                    put("page_recreated",true);put("known_pending",firstRuntime.pendingCount)}
                budget("leave $index feedback",leaveDraw.at-leave,100_000_000L)
                budget("warm $index feedback",draw.at-input,100_000_000L)
                // Reuse the correct-result target; older warm-open experiments also reported 2s.
                budget("warm $index correct page",pixels.observed-input,700_000_000L)
                budget("warm $index resource",maximum(tx),150_000_000L)
            }
            repeat(6) {index->
                val row=seed.habits[index%2];val screen=observe(page)
                val trace=G0PhaseTrace(driverTiming=false);page.trace=trace
                val previous=page.commandTiming?.firstStateVersion ?: 0L
                stage="command input:$index"
                val input=screen.tap("increment:${row.uuid}")
                log {put("kind","input");put("history",history);put("path","COUNT")
                    put("index",index);put("input_ns",input-origin);put("known_pending",firstRuntime.pendingCount)}
                // One real tap, no retries. Missing handlers or refusal terminate this journey.
                val timing=withTimeout(20_000) {
                    while(page.commandTiming?.firstStateVersion?.let {it>previous}!=true) {
                        firstRuntime.error?.let {throw it};page.error?.let {throw it};delay(1)
                    }
                    requireNotNull(page.commandTiming)
                }
                assertEquals(row.uuid,timing.command.habitUuid)
                stage="command feedback:$index"
                val feedbackPixels=screen.awaitAny {page.events.filter {it.version>=timing.firstStateVersion && it.phase in setOf(
                    G0HouseholdCountPage.Phase.VERIFYING,G0HouseholdCountPage.Phase.SAVING,
                    G0HouseholdCountPage.Phase.SAVED,G0HouseholdCountPage.Phase.RESULT)}.map {it.version}}
                stage="command result:$index"
                withTimeout(20_000) {page.state.first {
                    firstRuntime.error?.let {problem->throw problem};page.error?.let {problem->throw problem}
                    it.version>=timing.firstStateVersion && it.phase==G0HouseholdCountPage.Phase.RESULT}}
                val pixels=householdPixels(firstRuntime,page,screen)
                val shown=requireNotNull(page.events.single {it.version==pixels.version}.view)
                val saved=requireNotNull(page.saved);val current=requireNotNull(page.commandTiming)
                assertSame(timing.command,current.command);assertEquals(row.uuid,saved.habitUuid)
                totals[row.uuid]=totals.getValue(row.uuid)+1
                assertEquals(totals,shown.cards.mapValues {it.value.history.todayQuantity})
                assertEquals(saved.factUuid,shown.cards.getValue(row.uuid).followUp.claim.completionUuid)
                val commit=page.committedAt;assertEquals(commit,current.knownCommitAt)
                assertTrue(commit>=input)
                assertTrue(page.events.filter {it.version>=timing.firstStateVersion && it.phase in setOf(
                    G0HouseholdCountPage.Phase.SAVED,G0HouseholdCountPage.Phase.RESULT)}.all {it.publishedAt>=commit})
                savedAt[saved.requestId]=commit
                operations+=Operation(index,saved.requestId,input,current.ioStartedAt,commit,pixels.observed,trace)
                val draw=screen.draws.filter {it.version>=timing.firstStateVersion}.minBy {it.at}
                val anonymous=feedback(page).draws.filter {it.commandVersion==timing.firstStateVersion}.minByOrNull {it.at}
                val firstFeedback=minOf(draw.at,anonymous?.at ?: Long.MAX_VALUE)
                val tx=transactions(trace);assertTrue(tx.isNotEmpty());requireIncremental(trace)
                assertTrue(count(trace,"decrypt_access")>=2);requireRetained()
                val pending=durablePending();assertTrue(pending.all {it in savedAt})
                largestPending=maxOf(largestPending,pending.size)
                val sampled=now();val oldest=pending.minOfOrNull {savedAt.getValue(it)}
                log {put("kind","operation");put("history",history);put("index",index)
                    put("path",if(index==0)"ESTABLISH_REAL_INCREMENTAL_STATE" else "RETAINED_INCREMENTAL_USE")
                    put("input_ns",input-origin);put("io_started_ns",current.ioStartedAt-origin)
                    put("commit_at_ns",commit-origin);put("result_at_ns",pixels.observed-origin)
                    put("commit_ns",commit-input);put("result_ns",pixels.observed-input)
                    put("feedback_draw_ns",firstFeedback-input);put("page_feedback_draw_ns",draw.at-input)
                    put("feedback_observed_ns",feedbackPixels.observed-input);put("anonymous_busy_draw_present",anonymous!=null)
                    put("handler_observed",screen.clicks.any {it.key=="increment:${row.uuid}" && it.at>=input})
                    put("complete_history_calls",count(trace,"count_complete_history"));put("factory_calls",factories.get())
                    put("access",count(trace,"decrypt_access"));put("refresh",count(trace,"decrypt_refresh"))
                    put("resource_ns",maximum(tx));put("transactions",windows(tx,origin))
                    put("account_wait_windows",windows(spans(trace).filter {it.phase=="count_account_wait"}.map {Window(it.start,it.end)},origin))
                    put("sampled_outbox",pending.size);put("oldest_sampled_age_ns",oldest?.let {sampled-it} ?: 0L)
                    put("quantities",JsonArray(seed.habits.map {JsonPrimitive(totals.getValue(it.uuid))}))}
                budget("command $index feedback",firstFeedback-input,100_000_000L)
                budget("command $index commit",commit-input,500_000_000L)
                budget("command $index result",pixels.observed-input,700_000_000L)
                budget("command $index resource",maximum(tx),150_000_000L)
                if(index==0) {
                    stage="first actual ACK establishes retained state"
                    val wait=now();withTimeout(60_000) {firstRuntime.awaitSynced()}
                    householdPixels(firstRuntime,page,screen)
                    val attempt=firstRuntime.workerAttempts.single()
                    assertEquals(saved.requestId,attempt.requestId);assertEquals(NextOperationAcceptance.COMMITTED,attempt.accepted)
                    assertEquals(1,server.requests.count {it.path.endsWith("/push")})
                    assertTrue(durablePending().isEmpty())
                    log {put("kind","conditioning");put("history",history);put("wait_started_ns",wait-origin)
                        put("ready_ns",now()-origin);put("wait_ns",now()-wait);put("real_saves",1);put("real_acks",1)
                        put("included_as_operation_index",0);put("same_runtime",true)}
                    reenter(1)
                } else if(index==3)reenter(2)
            }
            stage="all actual ACKs drain"
            withTimeout(60_000) {firstRuntime.awaitSynced()}
            val drained=now();val terminalPixels=householdPixels(firstRuntime,page,observe(page))
            val terminalView=requireNotNull(page.events.single {it.version==terminalPixels.version}.view)
            assertEquals(totals,terminalView.cards.mapValues {it.value.history.todayQuantity})
            requireVisiblePageText(seed.habits.map {"${it.name}：3 / ${it.targetValue}"}.toSet()+
                ("首页："+page.state.value.members.joinToString("，") {id->"${terminalView.cards.getValue(id).habit.name}=3"}))
            requireRetained();assertEquals(3,firstRuntime.openTraces.size)
            assertEquals(2,reentries.size);assertEquals(3,pages.size)
            val attempts=firstRuntime.workerAttempts
            assertEquals(operations.map {it.requestId},attempts.map {it.requestId})
            assertEquals(6,server.requests.count {it.path.endsWith("/push")})
            assertTrue(durablePending().isEmpty());assertEquals(0,firstRuntime.pendingCount)
            var ackOverlap=0L
            attempts.forEach {attempt->
                assertEquals(NextOperationAcceptance.COMMITTED,attempt.accepted)
                requireIncremental(attempt.send);requireIncremental(attempt.ack)
                assertTrue(count(attempt.ack,"decrypt_access")>=2)
                val send=transactions(attempt.send);val ack=transactions(attempt.ack)
                assertTrue(send.isNotEmpty() && ack.isNotEmpty())
                val competing=operations.filter {it.requestId!=attempt.requestId}
                val commandOverlap=competing.sumOf {overlap(Window(attempt.ackStarted,attempt.ackEnded),Window(it.io,it.result))}
                val openOverlap=reentries.sumOf {overlap(Window(attempt.ackStarted,attempt.ackEnded),Window(it.input,it.result))}
                val waiting=competing.flatMap {spans(it.trace).filter {span->span.phase=="count_account_wait"}.map {span->Window(span.start,span.end)}}+
                    reentries.flatMap {spans(it.trace).filter {span->span.phase=="count_account_wait"}.map {span->Window(span.start,span.end)}}
                val waitOverlap=ack.sumOf {transaction->waiting.sumOf {overlap(transaction,it)}}
                ackOverlap+=commandOverlap+openOverlap
                val operation=operations.single {it.requestId==attempt.requestId}
                log {put("kind","sync");put("history",history);put("index",operation.index)
                    put("enqueued_ns",attempt.enqueued-origin);put("send_started_ns",attempt.sendStarted-origin)
                    put("send_ended_ns",attempt.sendEnded-origin);put("ack_started_ns",attempt.ackStarted-origin)
                    put("ack_ended_ns",attempt.ackEnded-origin);put("ack_ns",attempt.ackEnded-attempt.ackStarted)
                    put("commit_to_ack_ns",attempt.ackEnded-operation.commit)
                    put("send_transactions",windows(send,origin));put("ack_transactions",windows(ack,origin))
                    put("other_command_ack_overlap_ns",commandOverlap);put("warm_open_ack_overlap_ns",openOverlap)
                    put("ack_transaction_account_wait_overlap_ns",waitOverlap)
                    put("access",count(attempt.ack,"decrypt_access"));put("complete_history_calls",count(attempt.ack,"count_complete_history"))}
                budget("ack ${operation.index}",attempt.ackEnded-attempt.ackStarted,500_000_000L)
                budget("sync ${operation.index} resource",maximum(send+ack),150_000_000L)
            }
            firstRuntime.openTraces.forEach {requireIncremental(it);assertTrue(count(it,"decrypt_access")>=2)}
            refreshes.values.forEach(::requireIncremental)
            assertEquals(history+6,pageRowCount("completions"));assertEquals(history+6,pageRowCount("next_acceptances"))
            assertEquals(history+2,pageRowCount("count_days"))
            val terminal=pageNativeState()
            stage="close retained runtime"
            assertTrue(firstRuntime.shutdown());assertEquals(1,closes.get());assertFalse(connection.busy())
            // Same actual disk after six real uses: no seed, image restore, account or key mutation.
            val next=runtime(reopened);secondRuntime=next;install(next,reopenedSeen)
            val reopenedPage=openCold(next,reopenedSeen,reopened,"LOCAL_REOPEN_AFTER_REAL_USE")
            assertEquals(2,factories.get());assertEquals(1,closes.get());assertEquals(1,next.coldStarts)
            assertNotSame(retainedDb,db);assertNotSame(retainedGate,domainGate)
            assertNotSame(retainedStore,stores.last());assertNotSame(page,reopenedPage)
            val reopenedPixels=householdPixels(next,reopenedPage,observe(reopenedPage))
            val reopenedView=requireNotNull(reopenedPage.events.single {it.version==reopenedPixels.version}.view)
            // Page-specific completion claims may differ on reopening; facts and displayed totals must not.
            assertEquals(terminalView.cards.mapValues {it.value.history},reopenedView.cards.mapValues {it.value.history})
            assertEquals(terminal,pageNativeState());assertTrue(next.workerAttempts.isEmpty())
            assertEquals(6,server.requests.count {it.path.endsWith("/push")})
            assertEquals(4,pages.size)
            stage="reference and replay"
            assertTrue(next.shutdown());assertEquals(2,closes.get());assertFalse(connection.busy())
            // Both owners are now drained: include every observer refresh, including reads
            // following ACKs and page departure, without truncating an in-flight trace.
            refreshes.entries.forEachIndexed {index,(_,trace)->
                requireIncremental(trace)
                val tx=transactions(trace)
                log {put("kind","warm_refresh");put("history",history);put("observer",index)
                    put("resource_ns",maximum(tx));put("transactions",windows(tx,origin))
                    put("access",count(trace,"decrypt_access"));put("complete_history_calls",count(trace,"count_complete_history"))
                    put("completed_operations",spans(trace).count {it.phase=="operation" && it.ok})
                    put("cancelled_or_failed_operations",spans(trace).count {it.phase=="operation" && !it.ok})}
                budget("refresh $index resource",maximum(tx),150_000_000L)
            }
            reopenReference();sameHouseholdAsOriginal(reopenedView)
            attempts.forEach {assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(requireNotNull(it.delivery)))}
            assertEquals(terminal,pageNativeState())
            log {put("kind","terminal");put("history",history);put("profile",profile.name)
                put("operations",operations.size);put("actual_acks",attempts.size);put("warm_page_reentries",reentries.size)
                put("runtime_instances",2);put("factories",factories.get());put("close_callbacks",closes.get())
                put("facts",history+6);put("outbox",0);put("sampled_peak_outbox",largestPending)
                put("last_result_to_drain_ns",drained-operations.last().result)
                put("natural_other_work_ack_overlap_ns",ackOverlap);put("natural_ack_overlap_observed",ackOverlap>0L)
                put("pacing","one conditioning ACK, then closed-loop pixels, no injected contention or ACK waits")
                put("network_synthetic",true);put("history_constructed",true);put("actual_new_http_pushes",6)
                put("same_file_reopen",true);put("process_killed",false);put("persistent_certificate_hit",false)
                put("reference_and_replay",true);put("full_journey_complete",true)
                put("budget_failures",JsonArray(failures.map(::JsonPrimitive)))}
            stage="original budgets"
            assertTrue("Retained-state feedback/commit/result/ACK/resource and separately recorded cold budgets: $failures",failures.isEmpty())
        } catch(failure:Throwable) {
            primary=failure
            Log.e("G0RetainedStateFailure",buildJsonObject {
                put("history",history);put("stage",stage);put("error",failure.javaClass.simpleName)
                put("factories",factories.get());put("closes",closes.get());put("completed_operations",operations.size)
                put("runtime_phase",firstRuntime.state.value.phase.name)
                put("reopened_phase",secondRuntime?.state?.value?.phase?.name ?: "not_created")
                putJsonArray("page_inputs") {pages.forEach {(page,screen)->add(buildJsonObject {
                    put("page_phase",page.state.value.phase.name);put("inputs",screen.inputs.size);put("handlers",screen.clicks.size)
                    put("error",page.error?.javaClass?.simpleName ?: "none")
                })}}
                put("budget_failures_so_far",JsonArray(failures.map(::JsonPrimitive)))
            }.toString())
            throw failure
        } finally {
            val cleanup=runCatching {withContext(NonCancellable) {
                secondRuntime?.shutdown();firstRuntime.shutdown()
            }}.exceptionOrNull()
            if(cleanup!=null) {primary?.addSuppressed(cleanup) ?: throw cleanup}
        }
    }
}
