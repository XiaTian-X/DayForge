package com.dayforge.data.repository

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Functional composition of the existing feedback and RECREATE prompt. This is
 * not another acceleration A/B. A finite B/B burst precedes the modal A prompt;
 * no touch is sent through that modal to manufacture a page BUSY outcome.
 * Observation/trace-quality failures are retained after raw records are emitted. */
@RunWith(AndroidJUnit4::class)
class G0JointPromptCostTest:G0CompletionPromptScreenFixture() {
    private data class Input(val index:Int,val objectIndex:Int,val planned:Long,val start:Long,val end:Long)
    private data class Frame(val kind:String,val version:Long,val start:Long,val end:Long,
        val requestId:String?,val commandVersion:Long?,val first:Long?=null,val last:Long?=null)
    private fun log(history:Int,kind:String,body:JsonObjectBuilder.()->Unit)=
        Log.i("G0JointPromptMeasure",buildJsonObject {put("history",history);put("kind",kind);body()}.toString())
    private fun spanRows(trace:G0PhaseTrace)=synchronized(trace.spans) {trace.spans.toList()}

    @Test(timeout=1_200_000) fun eightHistoryFeedbackAndRecreatedPrompt()=runBlocking<Unit> {journey(8)}
    @Test(timeout=1_200_000) fun annualHistoryFeedbackAndRecreatedPrompt()=runBlocking<Unit> {journey(730)}

    private suspend fun journey(history:Int)=coroutineScope {
        var stage="seed";var retained:Harness?=null;var page:G0HouseholdCountPage?=null
        var sampler:Job?=null;var origin=0L
        val inputs=mutableListOf<Input>();val frames=CopyOnWriteArrayList<Frame>()
        val failures=mutableListOf<String>();val quality=mutableListOf<String>()
        var captures=0;var captureNs=0L
        try {
            val seedAt=now();val seed=seedPrompt(history);val seedNs=now()-seedAt
            val h=harness(seed,commandFeedback=true);retained=h
            stage="open";val opened=h.open();origin=opened.first;val current=opened.second;page=current
            val session=h.prompt(current);assertEquals(G0CompletionPromptSession.WindowMode.RECREATE,session.windowMode)
            val promptTrace=G0PhaseTrace(driverTiming=false);val refreshTrace=G0PhaseTrace(driverTiming=false)
            session.traceForTest=promptTrace;current.refreshTrace=refreshTrace
            fun checkErrors() {h.runtime.error?.let {throw it};current.error?.let {throw it};session.error?.let {throw it}}
            fun budget(label:String,value:Long,limit:Long) {if(value>limit)failures+="$label:$value>$limit"}
            val cold=requireNotNull(h.openReady).observed-origin
            budget("cold_ready",cold,2_000_000_000)
            log(history,"open") {
                put("seed_ns",seedNs);put("ready_ns",cold);put("cold_starts",h.runtime.coldStarts)
                put("objects",2);put("past_days",history/2);put("command_feedback",true)
                put("window_mode",session.windowMode.name);put("acceleration_comparison",false)
            }
            sampler=launch(Dispatchers.IO) {
                val observed=hashSetOf<String>()
                while(isActive) {
                    checkErrors();check(frames.size<4096) {"G0_JOINT_FRAME_OVERFLOW"}
                    val outer=h.runtime.state.value;val state=current.state.value;val timing=current.commandTiming
                    val personal=G0ScreenObservation.Target(h.seen(current),state.version) {
                        h.runtime.state.value===outer && current.state.value===state && h.runtime.renderable()
                    }
                    val busy=timing?.let {G0ScreenObservation.Target(h.feedback(current).pixels,state.version) {
                        h.runtime.feedbackRenderable(outer,state,it)
                    }}
                    val commands=h.commandFeedback(current)
                    val notice=commands.noticeTarget(h.runtime,outer,state)
                    val attempt=commands.attemptTarget(h.runtime,outer,state)
                    val expected=session.state.value.expected?.takeIf(session::renderable)
                    val prompt=expected?.let {h.observation(current).target(session,it)}
                    val start=now();val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    val end=now();captures++;captureNs+=end-start
                    if(bitmap!=null)try {
                        fun record(kind:String,target:G0ScreenObservation.Target?,request:String?,commandVersion:Long?,
                            first:Long?=null,last:Long?=null) {
                            if(target!=null && G0ScreenObservation.matchesTogether(bitmap,listOf(target)) &&
                                observed.add("$kind:${target.version}:$request"))
                                frames+=Frame(kind,target.version,start,end,request,commandVersion,first,last)
                        }
                        record("page",personal,state.intent?.command?.requestId,state.intent?.firstStateVersion)
                        record("busy",busy,timing?.command?.requestId,timing?.firstStateVersion)
                        record("notice",notice,state.commitNotice?.command?.requestId,state.commitNotice?.firstStateVersion)
                        val receipt=state.attemptReceipt
                        record("attempt",attempt,receipt?.command?.requestId,receipt?.firstStateVersion,
                            receipt?.firstSequence,receipt?.sequence)
                        record("prompt",prompt,expected?.request?.known?.command?.requestId,null)
                    } finally {bitmap.recycle()}
                    delay(16)
                }
            }
            suspend fun inject(objectIndex:Int,planned:Long=now()) {
                checkErrors();assertNull("No page touch while a modal is current",session.state.value.expected)
                InstrumentationRegistry.getInstrumentation().runOnMainSync {current.trace=G0PhaseTrace(driverTiming=false)}
                val start=h.seen(current).tap("increment:${currentHousehold[objectIndex].uuid}")
                val input=Input(inputs.size,objectIndex,planned,start,now());inputs+=input
                log(history,"input") {
                    put("index",input.index);put("object",objectIndex);put("planned_ns",planned-origin)
                    put("start_ns",start-origin);put("end_ns",input.end-origin);put("lateness_ns",start-planned)
                }
            }
            suspend fun completedPage() {
                current.settled();checkErrors()
                withTimeout(20_000) {current.state.first {
                    checkErrors();it.phase==G0HouseholdCountPage.Phase.RESULT && it.view?.valid()==true
                }}
                householdPixels(h.runtime,current,h.seen(current))
            }
            stage="burst"
            inject(1)
            val secondDue=inputs.first().start+100_000_000
            while(now()<secondDue)delay(maxOf(1L,(secondDue-now()+999_999)/1_000_000))
            inject(1,secondDue)
            completedPage();session.settled();checkErrors()
            assertEquals(G0CompletionPromptSession.Phase.SUPPRESSED,session.state.value.phase)
            assertTrue(current.commandRecords.isNotEmpty())
            stage="linked_input";inject(0)
            val linked=current.admissions.lastOrNull {it.at>=inputs.last().start &&
                it.requestedHabitUuid==currentHousehold[0].uuid}
            assertEquals("Linked physical input must be admitted",G0HouseholdCountPage.AdmissionOutcome.ACCEPTED,linked?.outcome)
            val linkedId=requireNotNull(linked?.requestId)
            stage="prompt"
            withTimeout(20_000) {while(frames.none {it.kind=="prompt" && it.requestId==linkedId}) {checkErrors();delay(16)}}
            // The independent sampler retains the first actual prompt frame even if
            // a newer proof replaces it before the real Back reaches the current Dialog.
            awaitDialogText()
            val firstPrompt=frames.first {it.kind=="prompt" && it.requestId==linkedId}
            val expected=requireNotNull(session.events.single {it.version==firstPrompt.version}.expected)
            val source=expected.source;val metrics=expected.metrics
            assertEquals(linkedId,expected.request.known?.command?.requestId)
            val after=back()
            val close=withTimeout(10_000) {
                var found:G0CompletionPromptSession.Callback?=null
                while(found==null) {checkErrors();found=session.callbacks.firstOrNull {
                    it.operation=="close" && it.outcome=="accepted" && it.at>=after
                };if(found==null)delay(16)}
                requireNotNull(found)
            }
            val closed=requireNotNull(session.events.single {it.version==close.version}.expected)
            assertSame(expected.request,closed.request);assertEquals(source.claim,closed.source.claim)
            assertTrue(session.callbacks.any {it.operation=="draft" && it.outcome=="accepted" &&
                it.version==close.version && it.at in after..close.at})
            val actualView=requireNotNull(h.observation(current).actualView)
            val windowEvent=h.observation(current).windows.last {it.identity==closed.identity && it.mounted}
            assertEquals(windowEvent.viewIdentity,System.identityHashCode(actualView))
            val frozen=h.observation(current).pixels.frozenMarker(close.version)
            var removedAt=0L
            withTimeout(10_000) {
                while(removedAt==0L) {
                    var attached=true
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {attached=actualView.isAttachedToWindow}
                    val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    val gone=try {image!=null && !frozen(image) && !attached &&
                        !visibleText("${metric.name} (${metric.unit})")} finally {image?.recycle()}
                    if(gone)removedAt=now() else delay(16)
                }
            }
            budget("prompt",firstPrompt.end-inputs[2].start,700_000_000)
            budget("back",removedAt-after,100_000_000)
            log(history,"prompt") {
                put("input_index",2);put("request_id",linkedId);put("version",firstPrompt.version)
                put("observed_ns",firstPrompt.end-inputs[2].start);put("closed_version",close.version)
                put("back_ns",after-origin);put("removed_ns",removedAt-origin);put("back_latency_ns",removedAt-after)
                put("window_identity",windowEvent.windowIdentity);put("actual_view_detached",true)
            }
            completedPage()
            stage="last_input";inject(1);completedPage();session.settled()
            stage="drain";h.synced();current.settled();session.settled();checkErrors()
            val drained=now();val finalFrame=householdPixels(h.runtime,current,h.seen(current))
            requireNotNull(sampler).cancelAndJoin();sampler=null
            val records=current.commandRecords;val admissions=current.admissions
            val accepted=admissions.filter {it.outcome==G0HouseholdCountPage.AdmissionOutcome.ACCEPTED}
            assertEquals(4,inputs.size);assertEquals(4,h.seen(current).inputs.size)
            assertEquals(accepted.map {it.requestId}.toSet(),records.keys)
            assertTrue(records.size in 3..4)
            assertTrue(records.values.all {it.saved!=null && it.knownCommitAt>0 && it.completedAt>0 && it.errorClass==null})
            val quantities=currentHousehold.associate {row->row.uuid to records.values.count {it.command.habitUuid==row.uuid}.toLong()}
            val finalView=requireNotNull(current.events.single {it.version==finalFrame.version}.view)
            assertEquals(quantities,finalView.cards.mapValues {it.value.history.todayQuantity})
            val workers=h.runtime.workerAttempts
            assertEquals(records.keys,workers.map {it.requestId}.toSet());assertEquals(records.size,workers.size)
            assertTrue(workers.all {it.accepted==NextOperationAcceptance.COMMITTED})
            assertFalse(current.diagnosticsOverflow);assertFalse(session.diagnosticsOverflow);assertFalse(h.observation(current).overflow)
            stage="original_terminal"
            val originalPromptNs=terminal(h,current,history+records.size,source,metrics)
            assertTrue(h.runtime.drained);assertFalse(h.connection.busy());assertTrue(h.job.isCompleted)
            stage="records"
            val covered=mutableSetOf<G0HouseholdCountPage.Admission>()
            for(input in inputs) {
                val end=inputs.getOrNull(input.index+1)?.start ?: drained
                val matches=admissions.filter {it.at in input.start until end &&
                    it.requestedHabitUuid==currentHousehold[input.objectIndex].uuid}
                assertTrue(matches.size<=1);covered+=matches
                val admission=matches.singleOrNull();val acceptedHere=admission?.outcome==G0HouseholdCountPage.AdmissionOutcome.ACCEPTED
                val receipt=admission?.attemptSequence?.let {sequence->frames.firstOrNull {
                    it.kind=="attempt" && it.requestId==admission.requestId && it.commandVersion==admission.firstStateVersion &&
                        it.first!=null && it.last!=null && sequence in it.first..it.last && it.end>=input.start
                }}
                if(receipt!=null) {
                    val state=current.events.single {it.version==receipt.version};val actual=requireNotNull(state.attemptReceipt)
                    val members=admissions.filter {it.outcome==G0HouseholdCountPage.AdmissionOutcome.BUSY &&
                        it.firstStateVersion==actual.firstStateVersion && it.attemptSequence?.let {n->n in actual.firstSequence..actual.sequence}==true}
                    assertEquals(actual.count,members.size.toLong())
                    assertEquals((actual.firstSequence..actual.sequence).toSet(),members.map {requireNotNull(it.attemptSequence)}.toSet())
                    assertTrue(members.all {it.requestId==actual.command.requestId})
                    budget("${input.index}:receipt",receipt.end-input.start,100_000_000)
                }
                val classification=if(acceptedHere)"accepted" else if(receipt!=null)"explicit_busy" else "unexplained"
                if(classification=="unexplained")failures+="${input.index}:unexplained_input"
                log(history,"admission") {
                    put("index",input.index);put("outcome",admission?.outcome?.name ?: "NO_HANDLER")
                    put("classification",classification);put("receipt_observed",receipt!=null)
                    admission?.let {put("request_id",it.requestId);put("decided_ns",it.decidedAt-origin)
                        put("command_version",it.firstStateVersion)
                        it.attemptSequence?.let {n->put("attempt_sequence",n)}}
                    receipt?.let {put("receipt_latency_ns",it.end-input.start);put("receipt_version",it.version)}
                }
                if(acceptedHere) {
                    val record=records.getValue(requireNotNull(admission?.requestId))
                    val id=record.command.requestId
                    val resultFrames=frames.filter {it.kind=="page" && it.requestId==id &&
                        current.events.single {s->s.version==it.version}.phase==G0HouseholdCountPage.Phase.RESULT}
                    val prefixCounts=currentHousehold.associate {row->row.uuid to records.values.count {
                        it.command.habitUuid==row.uuid && it.knownCommitAt<=record.knownCommitAt
                    }.toLong()}
                    for(frame in resultFrames) {
                        val state=current.events.single {it.version==frame.version}
                        val view=requireNotNull(state.view);val saved=requireNotNull(record.saved)
                        assertSame(record.command,state.intent?.command)
                        assertSame(record.command,state.commitNotice?.command);assertSame(saved,state.commitNotice?.saved)
                        assertEquals(prefixCounts,view.cards.mapValues {it.value.history.todayQuantity})
                        val sourceAtFrame=view.cards.getValue(saved.habitUuid).followUp
                        assertEquals(saved.habitUuid,sourceAtFrame.claim.habitUuid)
                        assertEquals(saved.factUuid,sourceAtFrame.claim.completionUuid)
                    }
                    val pageFrame=resultFrames.firstOrNull()
                    val notices=frames.filter {it.kind=="notice" && it.requestId==id}
                    for(frame in notices) {
                        val state=current.events.single {it.version==frame.version}
                        assertSame(record.command,state.intent?.command)
                        assertSame(record.command,state.commitNotice?.command)
                        assertSame(record.saved,state.commitNotice?.saved)
                        assertEquals(record.firstStateVersion,state.commitNotice?.firstStateVersion)
                        assertTrue(frame.end>=record.knownCommitAt)
                    }
                    val notice=notices.firstOrNull()
                    val versions=current.events.filter {it.intent?.command===record.command}.map {it.version}.toSet()
                    val feedback=listOfNotNull(
                        h.seen(current).draws.filter {it.version in versions}.minOfOrNull {it.at},
                        h.feedback(current).draws.filter {it.commandVersion==record.firstStateVersion}.minOfOrNull {it.at},
                        h.commandFeedback(current).draws.filter {it.requestId==id &&
                            it.kind==G0HouseholdCommandFeedbackObservation.Kind.NOTICE}.minOfOrNull {it.at}).minOrNull()
                    feedback?.let {assertTrue(it>=input.start)}
                    pageFrame?.let {assertTrue(it.end>=record.knownCommitAt)}
                    budget("${input.index}:save",record.knownCommitAt-input.start,500_000_000)
                    if(feedback==null)failures+="${input.index}:feedback_missing" else budget("${input.index}:feedback",feedback-input.start,100_000_000)
                    if(pageFrame==null)failures+="${input.index}:page_missing" else budget("${input.index}:page",pageFrame.end-input.start,700_000_000)
                    if(notice==null)failures+="${input.index}:notice_missing" else {
                        assertTrue(notice.end>=record.knownCommitAt);budget("${input.index}:notice",notice.end-input.start,500_000_000)
                    }
                    val worker=workers.single {it.requestId==id}
                    budget("${input.index}:ack",worker.ackEnded-worker.ackStarted,500_000_000)
                    log(history,"operation") {
                        put("index",input.index);put("request_id",id);put("input_ns",input.start-origin)
                        put("command_version",record.firstStateVersion)
                        put("known_commit_ns",record.knownCommitAt-origin);put("save_ns",record.knownCommitAt-input.start)
                        put("completed_ns",record.completedAt-origin);put("page_observed",pageFrame!=null)
                        pageFrame?.let {put("page_ns",it.end-input.start);put("page_version",it.version)}
                        put("notice_observed",notice!=null);notice?.let {put("notice_ns",it.end-input.start)}
                        feedback?.let {put("feedback_draw_ns",it-input.start)}
                        put("send_start_ns",worker.sendStarted-origin);put("ack_start_ns",worker.ackStarted-origin)
                        put("ack_end_ns",worker.ackEnded-origin);put("ack_ns",worker.ackEnded-worker.ackStarted)
                        put("known_commit_to_send_ns",worker.sendStarted-record.knownCommitAt)
                        put("known_commit_to_ack_ns",worker.ackEnded-record.knownCommitAt)
                    }
                }
            }
            assertEquals(admissions.toSet(),covered)
            for(frame in frames)log(history,"frame") {
                put("frame_kind",frame.kind);put("version",frame.version);put("capture_start_ns",frame.start-origin)
                put("observed_ns",frame.end-origin);frame.requestId?.let {put("request_id",it)}
                frame.commandVersion?.let {put("command_version",it)}
                frame.first?.let {put("first_sequence",it)};frame.last?.let {put("last_sequence",it)}
            }
            val traces=mutableListOf("cold" to h.cold,"prompt" to promptTrace,"page_refresh" to refreshTrace)
            h.runtime.openTraces.forEachIndexed {index,trace->traces+="page_open_$index" to trace}
            records.values.forEach {record->traces+="foreground_${record.command.requestId}" to requireNotNull(record.trace)}
            workers.forEach {worker->traces+="send_${worker.requestId}" to worker.send;traces+="ack_${worker.requestId}" to worker.ack}
            val phases=setOf("tx_begin_outer","tx_end_outer","count_account_wait","count_account_hold","domain_sql_wait","domain_sql_hold")
            for((name,trace) in traces) {
                val rows=spanRows(trace).filter {it.phase in phases}
                rows.forEachIndexed {index,row->log(history,"span") {
                    put("trace",name);put("index",index);put("phase",row.phase)
                    put("start_ns",row.start-origin);put("end_ns",row.end-origin);put("ok",row.ok)
                }}
                val starts=rows.filter {it.phase=="tx_begin_outer"}.sortedBy {it.start}
                val ends=rows.filter {it.phase=="tx_end_outer"}.sortedBy {it.start}
                val bad=mutableListOf<String>()
                if(rows.any {!it.ok || it.end<=it.start})bad+="invalid_or_non_ok_span"
                if(starts.size!=ends.size)bad+="unpaired_transaction"
                val windows=starts.zip(ends)
                if(windows.any {(a,b)->a.start>a.end || a.end>b.start || b.start>=b.end})bad+="invalid_transaction_order"
                if(windows.zipWithNext().any {(a,b)->a.second.end>b.first.start})bad+="overlapping_transactions"
                val durations=rows.filter {it.phase in setOf("count_account_hold","domain_sql_hold")}.map {it.end-it.start}+
                    windows.map {(a,b)->b.end-a.start}
                val maximum=durations.maxOrNull() ?: 0L
                if(name!="page_refresh" && starts.isEmpty())bad+="missing_transaction"
                bad.forEach {quality+="$name:$it"}
                if(name!="cold" && !name.startsWith("page_open_"))budget("$name:resource",maximum,150_000_000)
                log(history,"trace") {
                    put("trace",name);put("span_count",rows.size);put("transactions",starts.size)
                    put("max_recorded_resource_ns",maximum);put("quality_valid",bad.isEmpty())
                    put("quality_failures",JsonArray(bad.map(::JsonPrimitive)))
                    put("access_decrypt",trace.count("decrypt_access"));put("refresh_decrypt",trace.count("decrypt_refresh"))
                    put("unclassified_decrypt",trace.count("decrypt_unclassified"))
                    put("instrumented_history_calls",trace.count("count_complete_history"))
                    put("history_counter_scope","household_store_cold_callsite_not_global")
                }
            }
            var depth=0;var peak=0
            val changes=records.values.map {it.knownCommitAt to 1}+workers.map {it.ackEnded to -1}
            for((_,delta) in changes.sortedWith(compareBy<Pair<Long,Int>> {it.first}.thenBy {it.second})) {
                depth+=delta;assertTrue(depth>=0);peak=maxOf(peak,depth)
            }
            assertEquals(0,depth)
            log(history,"terminal") {
                put("inputs",inputs.size);put("accepted",records.size);put("actual_acks",workers.size)
                put("facts",history+records.size);put("outbox",0);put("original_verified",true);put("replayed",true)
                put("owner_drained",true);put("captures",captures);put("capture_ns",captureNs)
                put("drained_ns",drained-origin);put("last_ack_ns",workers.maxOf {it.ackEnded}-origin)
                put("drain_observation_lag_ns",drained-workers.maxOf {it.ackEnded})
                put("peak_known_committed_unacked",peak);put("original_prompt_reference_ns",originalPromptNs)
                put("quality_failures",JsonArray(quality.map(::JsonPrimitive)))
                put("budget_failures",JsonArray(failures.map(::JsonPrimitive)))
                put("scope","finite_joint_chain; sequential_after_initial_burst; no_fixed_rate_or_acceleration_claim")
            }
            stage="final_checks"
            assertTrue("Joint delivery budgets or trace quality failed; all records retained: quality=$quality budgets=$failures",
                quality.isEmpty() && failures.isEmpty())
        } catch(problem:Throwable) {
            Log.e("G0JointPromptFailure",buildJsonObject {
                put("history",history);put("stage",stage);put("type",problem.javaClass.simpleName)
                put("inputs",inputs.size);put("known_saves",page?.commandRecords?.values?.count {it.saved!=null} ?: 0)
                put("worker_attempts",retained?.runtime?.workerAttempts?.size ?: 0)
                put("quality_failures",JsonArray(quality.map(::JsonPrimitive)))
                put("budget_failures",JsonArray(failures.map(::JsonPrimitive)))
            }.toString())
            throw problem
        } finally {
            withContext(NonCancellable) {sampler?.cancelAndJoin();retained?.close()}
        }
    }
}
