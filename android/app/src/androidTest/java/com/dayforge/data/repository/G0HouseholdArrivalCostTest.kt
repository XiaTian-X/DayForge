package com.dayforge.data.repository

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.setContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fixed arrival times, independent of earlier results, screenshots and ACKs. This
 * is a finite window with a short burst, not a steady-state throughput benchmark.
 * Separate read-only DB and screen observers add observation overhead; neither drives
 * the input loop or authorizes any application operation. */
@RunWith(AndroidJUnit4::class)
class G0HouseholdArrivalCostTest:G0HouseholdHistoryFixture() {
    private fun now()=SystemClock.elapsedRealtimeNanos()
    private data class Arrival(val index:Int,val habit:String,val planned:Long,val start:Long,val end:Long)
    private data class Frame(val kind:String,val version:Long,val start:Long,val end:Long,
        val commandVersion:Long?=null,val firstAttempt:Long?=null,val lastAttempt:Long?=null)
    private data class Pending(val id:String,val createdAt:Long)
    private data class QueueSample(val start:Long,val end:Long,val wall:Long,val pending:List<Pending>,val known:Int)
    private data class Span(val start:Long,val end:Long)
    private fun spans(trace:G0PhaseTrace)=synchronized(trace.spans) {trace.spans.toList()}
    private fun transactions(trace:G0PhaseTrace):List<Span> {
        val rows=spans(trace);val begins=rows.filter {it.phase=="tx_begin_outer"};val ends=rows.filter {it.phase=="tx_end_outer"}
        assertEquals(begins.size,ends.size)
        return begins.zip(ends).map {(a,b)->assertTrue(a.ok && b.ok && b.end>a.start);Span(a.start,b.end)}
    }
    private fun windows(values:List<Span>,origin:Long)=JsonArray(values.map {buildJsonObject {
        put("start_ns",it.start-origin);put("end_ns",it.end-origin)
    }})
    private var strategy="baseline"
    private fun log(body:JsonObjectBuilder.()->Unit)=Log.i("G0HouseholdArrivalMeasure",buildJsonObject {
        put("strategy",strategy);body()
    }.toString())
    private fun pending(raw:SQLiteDatabase):List<Pending> = raw.rawQuery(
        "SELECT operationId,createdAt FROM sync_outbox ORDER BY id",null).use {c->
        buildList {while(c.moveToNext())add(Pending(c.getString(0),c.getLong(1)))}
    }
    private fun snapshot(runtime:G0HouseholdCountRuntime):QueueSample = SQLiteDatabase.openDatabase(
        app.getDatabasePath("habit_database").path,null,SQLiteDatabase.OPEN_READONLY).use {raw->
        val start=now();val rows=pending(raw);QueueSample(start,now(),System.currentTimeMillis(),rows,runtime.pendingCount)
    }

    @Test(timeout=1_200_000) fun eightHistoryFixedArrivalsAndBurst()=runBlocking<Unit> {journey(8,false)}
    @Test(timeout=1_200_000) fun annualHistoryFixedArrivalsAndBurst()=runBlocking<Unit> {journey(730,false)}

    @Test(timeout=1_200_000) fun eightHistoryExplicitCommandFeedback()=runBlocking<Unit> {journey(8,true)}
    @Test(timeout=1_200_000) fun annualHistoryExplicitCommandFeedback()=runBlocking<Unit> {journey(730,true)}

    private suspend fun journey(history:Int,candidate:Boolean)=coroutineScope {
        strategy=if(candidate)"candidate" else "baseline"
        val seed=seedPendingHouseholdHistory(history);val (http,server)=channel {successReply(it)}
        constructAcceptedHouseholdHistory(seed,http);verifyAcceptedHouseholdHistory(seed,http)
        assertEquals(0,server.requests.count {it.path.endsWith("/push")})
        val profile=if(history==730)G0HouseholdCountStore.CapacityProfile.ANNUAL_730_PLUS_6
            else G0HouseholdCountStore.CapacityProfile.DEFAULT_128
        val cold=G0PhaseTrace(driverTiming=false)
        val connection=G0CountConnectionOwner {db.close()}
        val runtime=G0HouseholdCountRuntime(this,connection,authority,tokens,{domainGate.changeSequence},http) {
            cold.run {coldHousehold(http,capacityProfile=profile)}
        }
        val outer=G0ScreenObservation();val screens=ConcurrentHashMap<G0HouseholdCountPage,G0ScreenObservation>()
        val feedbacks=ConcurrentHashMap<G0HouseholdCountPage,G0HouseholdFeedbackObservation>()
        val diagnostics=ConcurrentHashMap<G0HouseholdCountPage,G0HouseholdArrivalObservation>()
        val commandFeedbacks=ConcurrentHashMap<G0HouseholdCountPage,G0HouseholdCommandFeedbackObservation>()
        fun screen(page:G0HouseholdCountPage)=screens.computeIfAbsent(page) {G0ScreenObservation()}
        fun feedback(page:G0HouseholdCountPage)=feedbacks.computeIfAbsent(page) {G0HouseholdFeedbackObservation()}
        fun diagnostic(page:G0HouseholdCountPage)=diagnostics.computeIfAbsent(page) {G0HouseholdArrivalObservation()}
        fun commandFeedback(page:G0HouseholdCountPage)=commandFeedbacks.computeIfAbsent(page) {G0HouseholdCommandFeedbackObservation()}
        val arrivals=CopyOnWriteArrayList<Arrival>();val frames=CopyOnWriteArrayList<Frame>()
        val samples=CopyOnWriteArrayList<QueueSample>();val failures=mutableListOf<String>()
        var captures=0;var captureNanos=0L;var captureMax=0L
        var pictureJob:Job?=null;var queueJob:Job?=null;var origin=0L;var stage="install";var primary:Throwable?=null
        try {
            activity.scenario.onActivity {it.setContent {
                G0HouseholdCountRuntimeScreen(runtime,outer,::screen,::feedback,::diagnostic,
                    commandFeedback=candidate,commandSeen=::commandFeedback)
            }}
            outer.await(runtime.state.value.version);val opened=outer.tap("open")
            stage="cold page"
            val page=withTimeout(180_000) {requireNotNull(runtime.state.first {
                runtime.error?.let {error->throw error};it.phase==G0HouseholdCountRuntime.Phase.OPEN}.page)}
            val observed=householdPixels(runtime,page,screen(page))
            assertTrue(page.state.value.view!!.cards.values.all {it.history.todayQuantity==0L})
            log {put("kind","open");put("history",history);put("ready_ns",observed.observed-opened)
                put("cold_resource_ns",transactionHold(cold));put("profile",profile.name)}
            if(observed.observed-opened>2_000_000_000L)failures+="cold ready >2s"

            // Independent observers. No input waits for either observer or a business state.
            pictureJob=launch(Dispatchers.IO) {
                val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
                while(isActive) {
                    check(frames.size<4096) {"G0_ARRIVAL_FRAME_OVERFLOW"}
                    val outside=runtime.state.value;val state=page.state.value;val timing=page.commandTiming
                    val start=now();val bitmap=automation.takeScreenshot();val end=now()
                    captures++;captureNanos+=end-start;captureMax=maxOf(captureMax,end-start)
                    if(bitmap!=null)try {
                        val personal=G0ScreenObservation.Target(screen(page),state.version) {
                            runtime.state.value===outside && outside.page===page && page.state.value===state && runtime.renderable()
                        }
                        if(G0ScreenObservation.matchesTogether(bitmap,listOf(personal)))frames+=Frame("personal",state.version,start,end)
                        if(timing!=null) {
                            val busy=G0ScreenObservation.Target(feedback(page).pixels,state.version) {
                                runtime.feedbackRenderable(outside,state,timing)
                            }
                            if(G0ScreenObservation.matchesTogether(bitmap,listOf(busy)))frames+=Frame("busy",state.version,start,end)
                        }
                        if(candidate) {
                            val seen=commandFeedback(page)
                            seen.noticeTarget(runtime,outside,state)?.let {target->
                                if(G0ScreenObservation.matchesTogether(bitmap,listOf(target)))frames+=Frame(
                                    "commit_notice",state.version,start,end,requireNotNull(state.commitNotice).firstStateVersion)
                            }
                            seen.attemptTarget(runtime,outside,state)?.let {target->
                                val receipt=requireNotNull(state.attemptReceipt)
                                if(G0ScreenObservation.matchesTogether(bitmap,listOf(target)))frames+=Frame(
                                    "attempt_receipt",state.version,start,end,receipt.firstStateVersion,receipt.firstSequence,receipt.sequence)
                            }
                        }
                    } finally {bitmap.recycle()}
                    delay(16)
                }
            }
            samples+=snapshot(runtime)
            assertTrue(samples.single().pending.isEmpty())
            queueJob=launch(Dispatchers.IO) {
                SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path,null,SQLiteDatabase.OPEN_READONLY).use {raw->
                    while(isActive) {
                        check(samples.size<2048) {"G0_ARRIVAL_QUEUE_OVERFLOW"}
                        val start=now();val rows=pending(raw);samples+=QueueSample(start,now(),System.currentTimeMillis(),rows,runtime.pendingCount)
                        delay(50)
                    }
                }
            }
            val anchorWall=System.currentTimeMillis();origin=now();stage="fixed arrivals"
            val offsets=listOf(0L,2000L,4000L,6000L,6100L,6200L)
            withContext(Dispatchers.IO) {
                for((index,millis) in offsets.withIndex()) {
                    val due=origin+millis*1_000_000L
                    while(now()<due)delay(maxOf(1L,(due-now()+999_999L)/1_000_000L))
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {page.trace=G0PhaseTrace(driverTiming=false)}
                    val row=seed.habits[index%2];val start=screen(page).tap("increment:${row.uuid}")
                    arrivals+=Arrival(index,row.uuid,due,start,now())
                }
            }
            val inputFinished=now();samples+=snapshot(runtime);stage="settle admitted commands"
            page.settled();runtime.error?.let {throw it};page.error?.let {throw it}
            stage="drain";withTimeout(60_000) {runtime.awaitSynced()};val drained=now()
            val terminalPixels=householdPixels(runtime,page,screen(page))
            val view=page.events.single {it.version==terminalPixels.version}.view!!
            pictureJob.cancelAndJoin();queueJob.cancelAndJoin();samples+=snapshot(runtime)
            check(!page.diagnosticsOverflow && !diagnostic(page).overflow)
            val records=page.commandRecords;val admissions=page.admissions
            assertEquals(6,arrivals.size);assertEquals(6,screen(page).inputs.size)
            val accepted=admissions.filter {it.outcome==G0HouseholdCountPage.AdmissionOutcome.ACCEPTED}
            assertEquals(accepted.map {it.requestId}.toSet(),records.keys)
            assertEquals(accepted.size,records.size)
            val totals=seed.habits.associate {row->row.uuid to records.values.count {it.command.habitUuid==row.uuid}.toLong()}
            assertEquals(totals,view.cards.mapValues {it.value.history.todayQuantity})
            assertTrue(records.isNotEmpty());assertTrue(records.values.all {it.saved!=null && it.knownCommitAt>0 && it.completedAt>0 && it.errorClass==null})
            val seenAdmissions=mutableSetOf<G0HouseholdCountPage.Admission>();var unexplained=0;var visibleBusy=0;var explicitBusy=0
            for(arrival in arrivals) {
                val next=arrivals.getOrNull(arrival.index+1)?.start ?: inputFinished+1_000_000_000L
                val matched=admissions.filter {it.at>=arrival.start && it.at<next && it.requestedHabitUuid==arrival.habit}
                assertTrue(matched.size<=1);seenAdmissions+=matched
                val admission=matched.singleOrNull()
                val record=admission?.takeIf {it.outcome==G0HouseholdCountPage.AdmissionOutcome.ACCEPTED}?.requestId?.let {records.getValue(it)}
                // Conservative explanation requires the same actual busy marker in
                // screenshots bracketing the input, plus a drawn disabled target.
                // A phase value or draw timestamp alone is never called visible busy.
                val before=frames.lastOrNull {it.kind=="busy" && it.end<=arrival.start && arrival.start-it.end<=250_000_000L}
                val after=before?.let {b->frames.firstOrNull {it.kind=="busy" && it.version==b.version &&
                    it.start>=arrival.end && it.start-arrival.end<=250_000_000L}}
                val disabled=before!=null && after!=null && listOf(before,after).all {busyFrame->
                    frames.any {it.kind=="personal" && it.version==busyFrame.version && it.start==busyFrame.start && it.end==busyFrame.end}
                } && diagnostic(page).buttonDraws.any {
                    it.habitUuid==arrival.habit && it.stateVersion==before.version && !it.enabled && it.current && it.at<=arrival.start
                } && diagnostic(page).privateDraws.none {it.at in before.end..after.start &&
                    (!it.allowed || it.stateVersion!=before.version)}
                val receipt=admission?.takeIf {it.outcome==G0HouseholdCountPage.AdmissionOutcome.BUSY}?.attemptSequence?.let {sequence->
                    frames.filter {it.kind=="attempt_receipt" && it.commandVersion==requireNotNull(admission).firstStateVersion &&
                        sequence in requireNotNull(it.firstAttempt)..requireNotNull(it.lastAttempt) && it.end>=arrival.start}.minByOrNull {it.end}
                }
                receipt?.let {frame->
                    val semantic=requireNotNull(page.events.single {it.version==frame.version}.attemptReceipt)
                    val members=admissions.filter {it.outcome==G0HouseholdCountPage.AdmissionOutcome.BUSY &&
                        it.firstStateVersion==semantic.firstStateVersion && it.attemptSequence?.let {sequence->
                            sequence in semantic.firstSequence..semantic.sequence}==true}
                    assertEquals(semantic.count,members.size.toLong())
                    assertEquals((semantic.firstSequence..semantic.sequence).toSet(),members.map {requireNotNull(it.attemptSequence)}.toSet())
                    assertTrue(members.all {it.requestId==semantic.command.requestId})
                    assertEquals(semantic.requestedHabitUuid,members.single {it.attemptSequence==semantic.sequence}.requestedHabitUuid)
                }
                val explanation=when {
                    record!=null->"accepted"
                    receipt!=null->"explicit_busy_not_admitted"
                    disabled && (admission==null || admission.outcome==G0HouseholdCountPage.AdmissionOutcome.BUSY)->"visible_busy_not_admitted"
                    else->"unexplained_not_admitted"
                }
                if(explanation=="visible_busy_not_admitted")visibleBusy++
                if(explanation=="explicit_busy_not_admitted")explicitBusy++
                if(explanation=="unexplained_not_admitted")unexplained++
                log {
                    put("kind","arrival");put("history",history);put("index",arrival.index);put("object",arrival.index%2)
                    put("scheduled_ns",arrival.planned-origin);put("actual_input_ns",arrival.start-origin)
                    put("input_end_ns",arrival.end-origin);put("lateness_ns",arrival.start-arrival.planned)
                    put("handler",admission!=null);put("outcome",admission?.outcome?.name ?: "NO_HANDLER")
                    put("classification",explanation);put("disabled_busy_bracket",disabled)
                    put("explicit_receipt_visible",receipt!=null)
                    admission?.attemptSequence?.let {put("attempt_sequence",it)}
                    receipt?.let {put("receipt_observed_ns",it.end-arrival.start);put("receipt_version",it.version)
                        put("receipt_first_sequence",requireNotNull(it.firstAttempt));put("receipt_last_sequence",requireNotNull(it.lastAttempt))}
                    val raw=screen(page).inputs[arrival.index]
                    put("down_returned_ns",raw.downReturned-origin);put("up_started_ns",raw.upStarted-origin);put("up_returned_ns",raw.upReturned-origin)
                    admission?.let {put("handler_at_ns",it.at-origin);put("decided_at_ns",it.decidedAt-origin)}
                }
                if(receipt!=null && receipt.end-arrival.start>100_000_000L)failures+="${arrival.index} refusal receipt >100ms"
                if(record!=null) {
                    val trace=requireNotNull(record.trace);val tx=transactions(trace)
                    val versions=record.publications.map {it.version}.toSet()
                    val resultFrames=frames.filter {it.kind=="personal" && it.version in record.publications.filter {
                        p->p.phase==G0HouseholdCountPage.Phase.RESULT}.map {p->p.version}.toSet()}
                    val firstResult=resultFrames.minByOrNull {it.end}
                    val prefix=accepted.take(accepted.indexOfFirst {it.requestId==record.command.requestId}+1)
                    val expectedQuantities=seed.habits.associate {row->row.uuid to prefix.count {
                        records.getValue(requireNotNull(it.requestId)).command.habitUuid==row.uuid}.toLong()}
                    for(frame in resultFrames) {
                        val shown=requireNotNull(page.events.single {it.version==frame.version}.view)
                        assertEquals(expectedQuantities,shown.cards.mapValues {it.value.history.todayQuantity})
                        assertEquals(requireNotNull(record.saved).factUuid,
                            shown.cards.getValue(record.command.habitUuid).followUp.claim.completionUuid)
                    }
                    val busy=feedback(page).draws.filter {it.commandVersion==record.firstStateVersion}.minByOrNull {it.at}
                    val personal=screen(page).draws.filter {it.version==record.firstStateVersion || it.version in versions}.minByOrNull {it.at}
                    val semanticDraw=commandFeedback(page).draws.filter {it.kind==G0HouseholdCommandFeedbackObservation.Kind.NOTICE && it.firstStateVersion==record.firstStateVersion}.minByOrNull {it.at}
                    val firstFeedback=listOfNotNull(busy?.at,personal?.at,semanticDraw?.at).minOrNull()
                    val notices=frames.filter {it.kind=="commit_notice" && it.commandVersion==record.firstStateVersion}
                    val firstNotice=notices.minByOrNull {it.end}
                    for(frame in notices) {
                        val notice=requireNotNull(page.events.single {it.version==frame.version}.commitNotice)
                        assertSame(record.command,notice.command);assertEquals(record.saved,notice.saved)
                        assertTrue(frame.start>=record.knownCommitAt)
                    }
                    assertTrue(record.publications.all {it.at>=record.knownCommitAt})
                    log {put("kind","command");put("history",history);put("index",arrival.index)
                        put("command_version",record.firstStateVersion);put("commit_ns",record.knownCommitAt-arrival.start)
                        put("io_started_ns",record.ioStartedAt-origin);put("known_commit_ns",record.knownCommitAt-origin)
                        put("commit_notice_observed",firstNotice!=null)
                        firstNotice?.let {put("commit_notice_ns",it.end-arrival.start);put("commit_notice_version",it.version)}
                        put("result_observed",firstResult!=null);firstResult?.let {put("result_ns",it.end-arrival.start);put("result_version",it.version)}
                        put("feedback_draw_present",firstFeedback!=null);firstFeedback?.let {put("feedback_draw_ns",it-arrival.start)}
                        put("busy_draw_present",busy!=null);busy?.let {put("busy_draw_ns",it.at-arrival.start);put("busy_version",it.version)}
                        put("transactions",windows(tx,origin));put("access",trace.count("decrypt_access"));put("refresh",trace.count("decrypt_refresh"))}
                    if(candidate && (firstNotice==null || firstNotice.end-arrival.start>500_000_000L))failures+="${arrival.index} commit notice absent or >500ms"
                    if(record.knownCommitAt-arrival.start>500_000_000L)failures+="${arrival.index} commit >500ms"
                    if(firstFeedback==null || firstFeedback-arrival.start>100_000_000L)failures+="${arrival.index} feedback absent or >100ms"
                    if(firstResult==null || firstResult.end-arrival.start>700_000_000L)failures+="${arrival.index} result absent or >700ms"
                    if(tx.maxOf {it.end-it.start}>150_000_000L)failures+="${arrival.index} foreground resource >150ms"
                }
            }
            assertEquals(admissions.toSet(),seenAdmissions)
            val attempts=runtime.workerAttempts;assertEquals(records.keys,attempts.map {it.requestId}.toSet())
            assertEquals(records.size,attempts.size);assertEquals(records.size,server.requests.count {it.path.endsWith("/push")})
            val depths=runtime.workerDepths;assertTrue(depths.isNotEmpty())
            assertTrue(depths.all {window->window.isNotEmpty() && window.last().outstanding==0 && window.all {it.outstanding in 0..8}})
            val created=samples.flatMap {it.pending}.groupBy {it.id}.mapValues {(_,rows)->rows.map {it.createdAt}.toSet().single()}
            assertEquals(records.keys,created.keys)
            for(sample in samples)assertTrue(sample.pending.all {it.id in records})
            assertTrue(samples.isNotEmpty());assertTrue(samples.first().pending.isEmpty());assertTrue(samples.last().pending.isEmpty())
            val clockError=kotlin.math.abs((System.currentTimeMillis()-anchorWall)*1_000_000L-(now()-origin))
            if(clockError>100_000_000L)failures+="wall/elapsed anchor changed >100ms"
            attempts.forEach {attempt->
                assertEquals(NextOperationAcceptance.COMMITTED,attempt.accepted)
                val tx=transactions(attempt.send)+transactions(attempt.ack)
                log {put("kind","sync");put("history",history);put("index",accepted.indexOfFirst {it.requestId==attempt.requestId})
                    put("send_started_ns",attempt.sendStarted-origin);put("send_ended_ns",attempt.sendEnded-origin)
                    put("ack_started_ns",attempt.ackStarted-origin);put("ack_ended_ns",attempt.ackEnded-origin)
                    put("durable_created_wall_ms",created.getValue(attempt.requestId));put("transactions",windows(tx,origin))}
                if(attempt.ackEnded-attempt.ackStarted>500_000_000L)failures+="ACK >500ms"
                if(tx.maxOf {it.end-it.start}>150_000_000L)failures+="sync resource >150ms"
            }
            val terminal=pageNativeState();stage="original full reference"
            assertTrue(runtime.shutdown());assertFalse(connection.busy());reopenReference();sameHouseholdAsOriginal(view)
            attempts.forEach {assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(requireNotNull(it.delivery)))}
            assertEquals(terminal,pageNativeState());assertEquals(history+records.size,pageRowCount("completions"))
            assertEquals(history+records.size,pageRowCount("next_acceptances"));assertEquals(0,pageRowCount("sync_outbox"))
            assertEquals(history+totals.count {it.value>0},pageRowCount("count_days"))
            log {put("kind","terminal");put("history",history);put("attempted",arrivals.size);put("accepted",records.size)
                put("visible_busy_not_admitted",visibleBusy);put("explicit_busy_not_admitted",explicitBusy);put("unexplained",unexplained);put("lost_accepted",0);put("outbox",0)
                put("syncs",attempts.size);put("facts",history+records.size);put("last_input_to_drain_ns",drained-inputFinished)
                put("sampled_depth_max",samples.maxOf {it.pending.size});put("sampled_runtime_pending_max",samples.maxOf {it.known})
                put("worker_outstanding_max",depths.flatten().maxOf {it.outstanding});put("worker_windows",depths.size)
                put("oldest_durable_age_ms",samples.maxOf {s->s.pending.maxOfOrNull {s.wall-it.createdAt} ?: 0L})
                put("queue_samples",samples.size);put("queue_interval_max_ns",samples.sortedBy {it.start}.zipWithNext().maxOfOrNull {(a,b)->b.start-a.start} ?: 0L)
                put("clock_anchor_error_ns",clockError);put("window_ack_progress",attempts.count {it.ackEnded in origin..inputFinished})
                put("screenshot_calls",captures);put("screenshot_call_sum_ns",captureNanos);put("screenshot_call_max_ns",captureMax)
                put("queue_read_sum_ns",samples.sumOf {it.end-it.start});put("queue_read_max_ns",samples.maxOf {it.end-it.start})
                put("terminal_reference",true);put("network_synthetic",true);put("history_constructed",true)
                put("arrival_schedule_ms",JsonArray(offsets.map {JsonPrimitive(it)}))}
            if(unexplained!=0)failures+="unexplained arrivals $unexplained"
            stage="original budgets";assertTrue("Original fixed-arrival budgets and input accounting: $failures",failures.isEmpty())
        } catch(error:Throwable) {
            primary=error;Log.e("G0HouseholdArrivalFailure",buildJsonObject {put("history",history);put("strategy",strategy);put("stage",stage)
                put("error",error.javaClass.simpleName);put("attempted",arrivals.size);put("runtime_error",runtime.error?.javaClass?.simpleName ?: "none")}.toString())
            throw error
        } finally {
            val cleanup=runCatching {withContext(NonCancellable) {
                pictureJob?.cancelAndJoin();queueJob?.cancelAndJoin();runtime.shutdown()
            }}.exceptionOrNull()
            if(cleanup!=null)primary?.addSuppressed(cleanup) ?: throw cleanup
        }
    }
}
