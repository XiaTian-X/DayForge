package com.dayforge.data.repository

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Finite two-object household journey. One deliberately aligned arrival starts
 * input after the first ACK has entered its real transaction; no SQL owner is
 * paused and no wait is subtracted from an input-to-result measurement. */
@RunWith(AndroidJUnit4::class)
class G0HouseholdCountScreenCostTest:G0HouseholdCountPageFixture() {
    private fun now()=SystemClock.elapsedRealtimeNanos()
    private data class Window(val start:Long,val end:Long)
    private data class Operation(val index:Int,val requestId:String,val input:Long,val io:Long,
        val commit:Long,val result:Long,val trace:G0PhaseTrace)
    private fun spans(trace:G0PhaseTrace)=synchronized(trace.spans) {trace.spans.toList()}
    private fun transactions(trace:G0PhaseTrace):List<Window> {
        val rows=spans(trace);val begin=rows.filter {it.phase=="tx_begin_outer"};val end=rows.filter {it.phase=="tx_end_outer"}
        assertEquals(begin.size,end.size)
        return begin.zip(end).map {(a,b)->assertTrue(a.ok && b.ok && b.end>a.start);Window(a.start,b.end)}
    }
    private fun windows(values:List<Window>,origin:Long)=JsonArray(values.map {buildJsonObject {
        put("start_ns",it.start-origin);put("end_ns",it.end-origin)
    }})
    private fun overlap(a:Window,b:Window)=maxOf(0L,minOf(a.end,b.end)-maxOf(a.start,b.start))
    private fun log(body:JsonObjectBuilder.()->Unit)=Log.i("G0HouseholdMeasure",buildJsonObject(body).toString())
    // Independent measurement only, not an application SQL capability. This narrow
    // snapshot avoids scanning all 39 tables between the user's next two inputs.
    private fun durablePending():List<String> = SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path,
        null,SQLiteDatabase.OPEN_READONLY).use {raw->raw.rawQuery("SELECT operationId FROM sync_outbox ORDER BY id",null).use {c->
        buildList {while(c.moveToNext())add(c.getString(0))}
    }}

    @Test(timeout=1_200_000) fun eightCrossDayFactsAndSixHouseholdInputs()=runBlocking<Unit> {journey(8,false)}
    @Test(timeout=1_200_000) fun eightyCrossDayFactsAndSixHouseholdInputs()=runBlocking<Unit> {journey(80,false)}
    @Test(timeout=1_200_000) fun seventyNineAndOneFactsAndSixHouseholdInputs()=runBlocking<Unit> {journey(80,true)}

    private suspend fun journey(history:Int,skewed:Boolean)=coroutineScope {
        val groups=history/2
        val offsets=List(history) {1L+(it/2L)*364L/(groups-1)}
        val seed=seedHouseholdAccepted(history,skewed,offsets)
        val (http,server)=channel {successReply(it)}
        val factories=AtomicInteger();val closes=AtomicInteger();val coldTrace=G0PhaseTrace(driverTiming=false)
        val connection=G0CountConnectionOwner {closes.incrementAndGet();db.close()}
        val runtime=G0HouseholdCountRuntime(this,connection,authority,tokens,{domainGate.changeSequence},http) {
            factories.incrementAndGet();coldTrace.run {coldHousehold(http)}
        }
        val seen=G0ScreenObservation();val pages=ConcurrentHashMap<G0HouseholdCountPage,G0ScreenObservation>()
        fun observe(page:G0HouseholdCountPage)=pages.computeIfAbsent(page) {G0ScreenObservation()}
        val firstAckReady=CompletableDeferred<Unit>();val firstAckRelease=CompletableDeferred<Unit>();val ackOrdinal=AtomicInteger()
        runtime.beforeAcceptForTest={if(ackOrdinal.getAndIncrement()==0) {firstAckReady.complete(Unit);firstAckRelease.await()}}
        val failures=mutableListOf<String>();val operations=mutableListOf<Operation>()
        val totals=seed.habits.associate {it.uuid to 0L}.toMutableMap()
        val savedAt=linkedMapOf<String,Long>();var origin=0L;var stage="install";var primary:Throwable?=null
        try {
            installHouseholdRuntime(runtime,seen,::observe);seen.await(runtime.state.value.version)
            assertEquals(0,factories.get());origin=seen.tap("open")
            stage="cold feedback"
            val opening=seen.awaitAny {runtime.events.filter {it.phase in setOf(
                G0HouseholdCountRuntime.Phase.LOADING,G0HouseholdCountRuntime.Phase.OPEN)}.map {it.version}}
            stage="cold page"
            val page=householdPage(runtime);val screen=observe(page)
            val ready=householdPixels(runtime,page,screen)
            val first=page.events.single {it.version==ready.version}.view!!
            assertEquals(seed.habits.map {it.uuid}.toSet(),first.cards.keys)
            assertTrue(first.cards.values.all {it.history.todayQuantity==0L && seed.today !in it.history.policies})
            val firstDraw=seen.draws.first {d->runtime.events.any {it.version==d.version && it.phase in setOf(
                G0HouseholdCountRuntime.Phase.LOADING,G0HouseholdCountRuntime.Phase.OPEN)}}.at
            log {
                put("kind","open");put("history",history);put("skewed",skewed)
                put("feedback_draw_ns",firstDraw-origin);put("feedback_observed_ns",opening.observed-origin)
                put("ready_observed_ns",ready.observed-origin);put("cold_resource_ns",transactionHold(coldTrace))
                put("past_span_days",offsets.max());put("today_unstarted",true)
                put("object_fact_counts",JsonArray(seed.habits.map {JsonPrimitive(seed.factCounts.getValue(it.uuid))}))
            }
            if(firstDraw-origin>100_000_000L)failures+="cold feedback >100ms"
            if(ready.observed-origin>2_000_000_000L)failures+="cold ready >2s"

            repeat(6) {index->
                val row=seed.habits[index%2]
                if(index==1) {
                    stage="align first ACK arrival"
                    withTimeout(20_000) {firstAckReady.await()}
                    firstAckRelease.complete(Unit)
                    // Align the arrival BEFORE starting the input clock. There is no
                    // extra suspension of the foreground command or ACK transaction.
                    withTimeout(15_000) {while(runtime.workerAttempts.firstOrNull()?.ack?.let {t->
                        spans(t).any {it.phase=="tx_begin_outer" && it.ok}
                    }!=true) {runtime.error?.let {throw it};delay(1)}}
                }
                val trace=G0PhaseTrace(driverTiming=false);page.trace=trace
                val previous=page.commandTiming?.firstStateVersion ?: 0L
                stage="input:$index"
                val input=screen.tap("increment:${row.uuid}")
                val timing=withTimeout(20_000) {while(true) {
                    runtime.error?.let {throw it};page.error?.let {throw it}
                    val current=page.commandTiming
                    if(current!=null && current.firstStateVersion>previous)break
                    delay(1)
                };requireNotNull(page.commandTiming)}
                assertEquals(row.uuid,timing.command.habitUuid)
                stage="feedback:$index"
                val feedback=screen.awaitAny {page.events.filter {it.version>=timing.firstStateVersion && it.phase in setOf(
                    G0HouseholdCountPage.Phase.VERIFYING,G0HouseholdCountPage.Phase.SAVING,
                    G0HouseholdCountPage.Phase.SAVED,G0HouseholdCountPage.Phase.RESULT)}.map {it.version}}
                val feedbackDraw=screen.draws.filter {it.version>=timing.firstStateVersion}.minOf {it.at}
                stage="result:$index"
                withTimeout(20_000) {page.state.first {runtime.error?.let {throw it};page.error?.let {throw it}
                    it.version>=timing.firstStateVersion && it.phase==G0HouseholdCountPage.Phase.RESULT}}
                val pixels=householdPixels(runtime,page,screen)
                val shown=page.events.single {it.version==pixels.version}.view!!
                val saved=requireNotNull(page.saved);assertEquals(row.uuid,saved.habitUuid)
                totals[row.uuid]=totals.getValue(row.uuid)+1
                assertEquals(totals,shown.cards.mapValues {it.value.history.todayQuantity})
                assertEquals(saved.factUuid,shown.cards.getValue(row.uuid).followUp.claim.completionUuid)
                val commit=page.committedAt;val command=page.commandTiming!!
                assertEquals(timing.command,command.command);assertEquals(commit,command.knownCommitAt)
                assertTrue(page.events.filter {it.version>=timing.firstStateVersion && it.phase in setOf(
                    G0HouseholdCountPage.Phase.SAVED,G0HouseholdCountPage.Phase.RESULT)}.all {it.publishedAt>=commit})
                savedAt[saved.requestId]=commit;operations+=Operation(index,saved.requestId,input,command.ioStartedAt,commit,pixels.observed,trace)
                val tx=transactions(trace);assertTrue(tx.isNotEmpty())
                val waits=spans(trace).filter {it.phase=="count_account_wait"}.map {Window(it.start,it.end)}
                val pending=durablePending();assertTrue(pending.all {it in savedAt})
                val sampled=now();val oldest=pending.minOfOrNull {savedAt.getValue(it)}
                log {
                    put("kind","operation");put("history",history);put("skewed",skewed);put("index",index)
                    put("object",index%2);put("first_day",index<2);put("controlled_ack_arrival",index==1)
                    put("input_ns",input-origin);put("io_started_ns",command.ioStartedAt-origin);put("commit_at_ns",commit-origin)
                    put("result_at_ns",pixels.observed-origin);put("commit_ns",commit-input);put("result_ns",pixels.observed-input)
                    put("feedback_draw_ns",feedbackDraw-input);put("feedback_observed_ns",feedback.observed-input)
                    put("feedback_version",feedback.version);put("command_version",timing.firstStateVersion)
                    put("transaction_windows",windows(tx,origin));put("account_wait_windows",windows(waits,origin))
                    put("access",trace.count("decrypt_access"));put("refresh",trace.count("decrypt_refresh"))
                    put("sampled_outbox",pending.size);put("sampled_known_ids",runtime.pendingCount)
                    put("oldest_sampled_age_ns",oldest?.let {sampled-it} ?: 0L)
                    put("quantities",JsonArray(seed.habits.map {JsonPrimitive(totals.getValue(it.uuid))}))
                }
                if(feedbackDraw-input>100_000_000L)failures+="$index feedback >100ms"
                if(commit-input>500_000_000L)failures+="$index commit >500ms"
                if(pixels.observed-input>700_000_000L)failures+="$index result >700ms"
                if(tx.maxOf {it.end-it.start}>150_000_000L)failures+="$index resource >150ms"
            }
            val lastResult=operations.last().result
            stage="leave and warm reentry"
            val leave=seen.tap("leave")
            withTimeout(20_000) {runtime.state.first {runtime.error?.let {throw it};it.phase==G0HouseholdCountRuntime.Phase.AWAY}}
            seen.await(runtime.state.value.version)
            val warmInput=seen.tap("reopen");val warm=householdPage(runtime)
            val warmPixels=householdPixels(runtime,warm,observe(warm))
            assertEquals(totals,warm.events.single {it.version==warmPixels.version}.view!!.cards.mapValues {it.value.history.todayQuantity})
            stage="background drain";withTimeout(60_000) {runtime.awaitSynced()};val drained=now()
            val terminalPixels=householdPixels(runtime,warm,observe(warm))
            val terminalView=warm.events.single {it.version==terminalPixels.version}.view!!
            assertEquals(totals,terminalView.cards.mapValues {it.value.history.todayQuantity})
            val expectedText=seed.habits.map {row->"${row.name}：3 / ${row.targetValue}"}.toSet()+
                ("首页："+warm.state.value.members.joinToString("，") {id->terminalView.cards.getValue(id).let {"${it.habit.name}=3"}})
            requireVisiblePageText(expectedText)
            assertEquals(1,factories.get());assertEquals(1,runtime.coldStarts);assertEquals(2,pages.size)
            assertTrue(runtime.openTraces.all {it.count("decrypt_access")>=2})
            assertTrue(durablePending().isEmpty());assertEquals(0,runtime.pendingCount)
            val attempts=runtime.workerAttempts;assertEquals(operations.map {it.requestId},attempts.map {it.requestId})
            assertEquals(6,server.requests.count {it.path.endsWith("/push")})
            assertEquals(history+6,pageRowCount("completions"));assertEquals(history+6,pageRowCount("next_acceptances"))
            assertEquals(seed.datesByObject.values.sumOf {it.toSet().size}+2,pageRowCount("count_days"))
            val before=pageNativeState();stage="reference and replay"
            assertTrue(runtime.shutdown());assertEquals(1,closes.get());assertFalse(connection.busy())
            reopenReference();sameHouseholdAsOriginal(terminalView);assertEquals(before,pageNativeState())
            attempts.forEach {a->assertEquals(NextOperationAcceptance.COMMITTED,a.accepted)
                assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(requireNotNull(a.delivery)))}
            assertEquals(before,pageNativeState())
            attempts.forEach {a->
                val index=operations.indexOfFirst {it.requestId==a.requestId};assertTrue(index>=0)
                val send=transactions(a.send);val ack=transactions(a.ack);assertTrue(send.isNotEmpty() && ack.isNotEmpty())
                val b=operations[1];val waits=spans(b.trace).filter {it.phase=="count_account_wait"}.map {Window(it.start,it.end)}
                log {
                    put("kind","sync");put("history",history);put("skewed",skewed);put("index",index)
                    put("enqueued_ns",a.enqueued-origin);put("send_started_ns",a.sendStarted-origin);put("send_ended_ns",a.sendEnded-origin)
                    put("ack_started_ns",a.ackStarted-origin);put("ack_ended_ns",a.ackEnded-origin)
                    put("send_transactions",windows(send,origin));put("ack_transactions",windows(ack,origin))
                    put("b_command_overlap_ns",overlap(Window(a.ackStarted,a.ackEnded),Window(b.io,b.commit)))
                    put("b_wait_ack_transaction_overlap_ns",ack.sumOf {aw->waits.sumOf {overlap(aw,it)}})
                }
                if(a.ackEnded-a.ackStarted>500_000_000L)failures+="ack $index >500ms"
                if((send+ack).maxOf {it.end-it.start}>150_000_000L)failures+="sync $index resource >150ms"
            }
            val warmTrace=runtime.openTraces[1];assertTrue(transactions(warmTrace).isNotEmpty())
            if(warmPixels.observed-warmInput>2_000_000_000L)failures+="warm ready >2s"
            if(transactionHold(warmTrace)>150_000_000L)failures+="warm read resource >150ms"
            log {
                put("kind","terminal");put("history",history);put("skewed",skewed);put("operations",operations.size);put("syncs",attempts.size)
                put("facts",history+6);put("outbox",0);put("cold_starts",runtime.coldStarts);put("same_committed_model",true)
                put("last_result_to_drain_ns",drained-lastResult);put("leave_ns",leave-origin)
                put("warm_ready_ns",warmPixels.observed-warmInput);put("warm_read_resource_ns",transactionHold(warmTrace))
                put("entry_to_terminal_ns",terminalPixels.observed-origin);put("reference_and_replay",true)
                put("network_synthetic",true);put("pacing","closed loop, first ACK aligned, not a steady arrival rate")
            }
            stage="original budgets";assertTrue("Original household feedback/commit/result/resource/ACK budgets: $failures",failures.isEmpty())
        } catch(failure:Throwable) {
            primary=failure
            Log.e("G0HouseholdFailure",buildJsonObject {
                put("history",history);put("skewed",skewed);put("stage",stage);put("error",failure.javaClass.simpleName)
                put("runtime_error",runtime.error?.javaClass?.simpleName ?: "none");put("runtime_phase",runtime.state.value.phase.name)
                put("cold_starts",runtime.coldStarts);put("completed_operations",operations.size);put("known_pending",runtime.pendingCount)
                putJsonArray("pages") {pages.forEach {(page,screen)->add(buildJsonObject {
                    put("phase",page.state.value.phase.name);put("version",page.state.value.version);put("live",page.live())
                    put("valid",page.state.value.view?.valid()==true);put("error",page.error?.javaClass?.simpleName ?: "none")
                    putJsonArray("draws") {screen.draws.takeLast(8).forEach {add(it.version)}}
                    put("click_count",screen.clicks.size)
                })}}
            }.toString());throw failure
        } finally {
            firstAckRelease.complete(Unit)
            val cleanup=runCatching {withContext(NonCancellable) {runtime.shutdown()}}.exceptionOrNull()
            if(cleanup!=null) {primary?.addSuppressed(cleanup) ?: throw cleanup}
        }
    }
}
