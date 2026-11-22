package com.dayforge.data.repository

import android.util.Log
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.ui.components.LinkedMetricInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*

/** One finite A/B/A household journey, ordinary original Dialog and real background
 * send/ACK. Sampling limits allow complete evidence; the original operation budgets remain.
 * The 730 history is constructed then originally verified, not 730 historical HTTP ACKs. */
abstract class G0CompletionPromptCostFixture:G0CompletionPromptScreenFixture() {
    internal data class SharedSetup(val imageHash:String,val restoreNs:Long,val order:Int)
    internal data class Sample(val index:Int,val saveNs:Long,val pageNs:Long,val promptNs:Long?,
        val backNs:Long?,val ackNs:Long,val commitToSendNs:Long,val commitToAckNs:Long)
    internal data class Measurement(val failures:List<String>,val samples:List<Sample>,val coldNs:Long,
        val firstInputToLastAckNs:Long,val lastCommitToLastAckNs:Long,val peakKnownUnacked:Int)
    private data class Window(val start:Long,val end:Long)
    private data class Frames(var page:G0ScreenObservation.Seen?=null,var prompt:G0ScreenObservation.Seen?=null,
        var expected:G0CompletionPromptSession.Expected?=null,var feedback:G0ScreenObservation.Seen?=null,
        var feedbackKind:String?=null,var waiting:G0ScreenObservation.Seen?=null,
        var ticket:G0CompletionPromptSession.WindowTicket?=null,var viewIdentity:Int?=null,var windowIdentity:Int?=null)
    private data class Closure(val expected:G0CompletionPromptSession.Callback?=null,
        val cancellation:G0CompletionPromptSession.WindowCancellation?=null)
    private data class Step(val index:Int,val input:Long,val admission:G0HouseholdCountPage.Admission,
        val command:G0HouseholdCountStore.Command,val saved:G0HouseholdCountStore.Saved,val commit:Long,
        val pageSeen:G0ScreenObservation.Seen,val frames:Frames,val pageOrder:String,val back:Long?,
        val closedVersion:Long?,val removal:Long?,val trace:G0PhaseTrace,
        val closure:Closure?,val closedViewIdentity:Int?,val closedWindowIdentity:Int?,val pageText:PageTextSeen)
    private fun spans(t:G0PhaseTrace)=synchronized(t.spans) {t.spans.toList()}
    private fun tx(t:G0PhaseTrace):List<Window> {
        val s=spans(t);val starts=s.filter {it.phase=="tx_begin_outer"}.sortedBy {it.start}
        val ends=s.filter {it.phase=="tx_end_outer"}.sortedBy {it.start}
        check(starts.size==ends.size)
        val windows=starts.zip(ends).map {(a,b)->check(a.ok && b.ok && a.start<=a.end && a.end<=b.start && b.start<b.end);Window(a.start,b.end)}
        check(windows.zipWithNext().all {(a,b)->a.end<=b.start})
        return windows
    }
    private fun maxResource(t:G0PhaseTrace):Long {
        val values=tx(t).map {it.end-it.start}+spans(t).filter {it.phase in setOf("count_account_hold","domain_sql_hold")}.map {
            check(it.ok && it.end>it.start);it.end-it.start
        }
        return values.maxOrNull() ?: 0L
    }
    private fun log(history:Int,kind:String,strategy:String?,body:JsonObjectBuilder.()->Unit) {
        Log.i("G0CompletionPromptMeasure",buildJsonObject {
            put("history",history);put("kind",kind);strategy?.let {put("strategy",it)};body()
        }.toString())
    }
    internal fun failure(history:Int,stage:String,problem:Throwable,strategy:String?=null,
        completedSteps:Int=0,knownSaves:Int=0,workerAttempts:Int=0,failures:List<String> = emptyList()) {
        Log.e("G0CompletionPromptFailure",buildJsonObject {
            put("history",history);put("stage",stage);put("type",problem.javaClass.simpleName)
            strategy?.let {put("strategy",it)}
            put("completed_steps",completedSteps);put("known_saves",knownSaves);put("worker_attempts",workerAttempts)
            put("retained_budget_failures",JsonArray(failures.map(::JsonPrimitive)))
        }.toString())
    }
    internal suspend fun measureOriginal(history:Int) {
        val seedAt=now()
        val seed=try {seedPrompt(history)} catch(problem:Throwable) {failure(history,"seed",problem);throw problem}
        val result=measure(seed,now()-seedAt)
        try {
            assertTrue("Preserved operation budgets and explicitly new prompt observation targets: ${result.failures}",result.failures.isEmpty())
        } catch(problem:Throwable) {
            failure(history,"record",problem,completedSteps=3,knownSaves=3,workerAttempts=3,failures=result.failures)
            throw problem
        }
    }
    /** Both paired arms require the actually closed current Dialog, not a prior
     * proof version's already detached View. This is observation, never authority. */
    private suspend fun removedDialog(frozen:(android.graphics.Bitmap)->Boolean,view:View):Long=withTimeout(10_000) {
        while(true) {
            var attached=true
            InstrumentationRegistry.getInstrumentation().runOnMainSync {attached=view.isAttachedToWindow}
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val gone=try {bitmap!=null && !frozen(bitmap) && !attached &&
                !visibleText("${metric.name} (${metric.unit})")} finally {bitmap?.recycle()}
            if(gone)return@withTimeout now()
            delay(16)
        }
        @Suppress("UNREACHABLE_CODE") error("Dialog has not detached")
    }
    /** One screenshot samples all currently eligible targets. A DTO or hidden/dimmed
     * Activity marker never substitutes for a visible page; no observer blocks ACK. */
    private suspend fun frames(h:Harness,page:G0HouseholdCountPage,command:G0HouseholdCountStore.Command,wantsPrompt:Boolean):Frames=withTimeout(20_000) {
        val result=Frames();val session=h.prompt(page);var captures=0
        while(true) {
            h.runtime.error?.let {throw it};page.error?.let {throw it}
            if(session.state.value.phase==G0CompletionPromptSession.Phase.FAILED)
                throw requireNotNull(session.error)
            val outer=h.runtime.state.value;val state=page.state.value;val view=state.view
            val sameCommand=state.intent?.command===command
            val pageTarget=if(sameCommand && state.phase==G0HouseholdCountPage.Phase.RESULT && view!=null && view.valid())
                G0ScreenObservation.Target(h.seen(page),state.version) {
                    h.runtime.state.value===outer && outer.page===page && h.runtime.renderable() &&
                        page.live() && page.state.value===state && view.valid()
                } else null
            val feedbackTarget=if(sameCommand && state.phase in setOf(G0HouseholdCountPage.Phase.VERIFYING,
                    G0HouseholdCountPage.Phase.SAVING,G0HouseholdCountPage.Phase.SAVED,G0HouseholdCountPage.Phase.RESULT))
                G0ScreenObservation.Target(h.seen(page),state.version) {
                    h.runtime.state.value===outer && outer.page===page && h.runtime.renderable() &&
                        page.live() && page.state.value===state && state.view?.valid()!=false
                } else null
            val timing=page.commandTiming
            val busyTarget=if(timing?.command===command && h.runtime.feedbackRenderable(outer,state,timing))
                G0ScreenObservation.Target(h.feedback(page).pixels,state.version) {h.runtime.feedbackRenderable(outer,state,timing)} else null
            val promptState=session.state.value
            val expected=promptState.expected?.takeIf {session.renderable(it)}
            if(expected!=null)assertEquals(command.factUuid,expected.source.claim.completionUuid)
            val promptTarget=expected?.let {h.observation(page).target(session,it)}
            val waitingTarget=promptState.window?.takeIf {session.waiting(it,promptState)}
                ?.let {h.observation(page).waitingTarget(session,it,promptState)}
            val start=now();val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val end=now();captures++
            if(bitmap!=null)try {
                fun matches(target:G0ScreenObservation.Target?)=target!=null && G0ScreenObservation.matchesTogether(bitmap,listOf(target))
                if(result.feedback==null) {
                    val matched=if(matches(busyTarget))busyTarget else if(matches(feedbackTarget))feedbackTarget else null
                    if(matched!=null) {result.feedback=G0ScreenObservation.Seen(matched.version,start,end,captures)
                        result.feedbackKind=if(matched===busyTarget)"anonymous_busy" else "page"}
                }
                if(result.page==null && matches(pageTarget))result.page=G0ScreenObservation.Seen(requireNotNull(pageTarget).version,start,end,captures)
                if(result.prompt==null && matches(promptTarget)) {
                    val rendered=h.observation(page).draws.single {it.version==requireNotNull(expected).version && it.allowed}
                    result.prompt=G0ScreenObservation.Seen(requireNotNull(promptTarget).version,start,end,captures);result.expected=expected
                    // Bind diagnostics to the matched proof's draw. A later composition
                    // may already have replaced the observer's latest Window/View.
                    result.ticket=promptState.window;result.viewIdentity=rendered.viewIdentity;result.windowIdentity=rendered.windowIdentity
                }
                if(result.waiting==null && matches(waitingTarget))
                    result.waiting=G0ScreenObservation.Seen(requireNotNull(waitingTarget).version,start,end,captures)
            } finally {bitmap.recycle()}
            if(wantsPrompt && result.prompt!=null)return@withTimeout result
            if(!wantsPrompt && result.page!=null && session.state.value.phase==G0CompletionPromptSession.Phase.SUPPRESSED) {
                assertNull(session.state.value.expected);return@withTimeout result
            }
            delay(8)
        }
        @Suppress("UNREACHABLE_CODE") error("No actual frame")
    }
    internal suspend fun measure(seed:Seed,seedNs:Long,
        mode:G0CompletionPromptSession.WindowMode=G0CompletionPromptSession.WindowMode.RECREATE,
        strategy:String?=null,shared:SharedSetup?=null):Measurement {
        val history=seed.history
        check((strategy==null)==(shared==null))
        check(strategy!=null || mode==G0CompletionPromptSession.WindowMode.RECREATE)
        check(strategy==null || strategy==mode.name)
        var stage="open";var retainedHarness:Harness?=null;var retainedPage:G0HouseholdCountPage?=null
        val steps=mutableListOf<Step>();val failures=mutableListOf<String>()
        try {
            val h=harness(seed,mode);retainedHarness=h
            val (origin,page)=h.open();retainedPage=page
            val coldReady=requireNotNull(h.openReady)
            val session=h.prompt(page);val promptTrace=G0PhaseTrace(driverTiming=false)
            val refreshTrace=G0PhaseTrace(driverTiming=false)
            session.traceForTest=promptTrace;page.refreshTrace=refreshTrace
            assertEquals(1,h.runtime.coldStarts);assertTrue(tx(h.cold).isNotEmpty())
            assertEquals(2,h.cold.count("count_complete_history"))
            val counts=currentHousehold.associate {it.uuid to 0L}.toMutableMap()
            var finalSource:WidgetFactSnapshot?=null;var finalMetrics:List<LinkedMetricInfo>?=null
            for(index in 0..2) {
                val uuid=currentHousehold[index%2].uuid
                val trace=G0PhaseTrace(driverTiming=false);page.trace=trace
                val prior=page.admissions.size
                stage="click:$index"
                val input=h.seen(page).tap("increment:$uuid")
                val admission=withTimeout(5_000) {
                    while(page.admissions.size==prior) {page.error?.let {throw it};delay(1)}
                    page.admissions.drop(prior).single()
                }
                assertEquals(G0HouseholdCountPage.AdmissionOutcome.ACCEPTED,admission.outcome)
                assertEquals(uuid,admission.requestedHabitUuid)
                val command=requireNotNull(page.command);assertEquals(command.requestId,admission.requestId)
                stage="pixels:$index"
                val observed=frames(h,page,command,index!=1)
                val record=page.commandRecords.getValue(command.requestId)
                val saved=requireNotNull(record.saved);assertTrue(record.knownCommitAt>=input)
                assertEquals(command.factUuid,saved.factUuid);assertEquals(uuid,saved.habitUuid)
                counts[uuid]=counts.getValue(uuid)+1
                var backAt:Long?=null;var closedVersion:Long?=null;var removedAt:Long?=null
                var closure:Closure?=null;var closedViewIdentity:Int?=null;var closedWindowIdentity:Int?=null
                if(index!=1) {
                    val expected=requireNotNull(observed.expected)
                    assertEquals(saved.factUuid,expected.source.claim.completionUuid)
                    assertTrue(requireNotNull(observed.prompt).observed>=record.knownCommitAt)
                    finalSource=expected.source;finalMetrics=expected.metrics
                    stage="dialog_text:$index";awaitDialogText()
                    assertTrue(visibleText(expected.habitName))
                    // Observe the actual original Back callback. A replaced expected window
                    // is reported separately, never relabelled as the earlier observed frame.
                    val frozen=h.observation(page).pixels.frozenMarker(expected.version)
                    stage="back:$index";val after=back();backAt=after
                    val closed=withTimeout(10_000) {
                        while(true) {
                            h.runtime.error?.let {throw it};session.error?.let {throw it}
                            val callback=session.callbacks.firstOrNull {it.operation=="close" && it.outcome=="accepted" && it.at>=after}
                            if(callback!=null)return@withTimeout Closure(expected=callback)
                            if(mode==G0CompletionPromptSession.WindowMode.RETAIN_REQUEST) {
                                val cancelled=session.windowCancellations.firstOrNull {it.accepted && it.at>=after}
                                if(cancelled!=null)return@withTimeout Closure(cancellation=cancelled)
                            }
                            delay(1)
                        }
                        @Suppress("UNREACHABLE_CODE") error("No close callback")
                    }
                    closure=closed
                    val windowIdentity=if(closed.expected!=null) {
                        val callback=closed.expected
                        closedVersion=callback.version
                        val closedExpected=requireNotNull(session.events.single {it.version==callback.version}.expected)
                        assertSame(expected.request,closedExpected.request)
                        assertEquals(expected.source.claim,closedExpected.source.claim)
                        assertEquals(command.factUuid,closedExpected.source.claim.completionUuid)
                        assertTrue(session.callbacks.any {it.operation=="draft" && it.outcome=="accepted" &&
                            it.version==callback.version && it.at in after..callback.at})
                        if(mode==G0CompletionPromptSession.WindowMode.RECREATE)closedExpected.identity
                        else requireNotNull(observed.ticket).identity
                    } else {
                        val cancelled=requireNotNull(closed.cancellation)
                        val ticket=requireNotNull(observed.ticket)
                        assertEquals(mode,G0CompletionPromptSession.WindowMode.RETAIN_REQUEST)
                        assertEquals(ticket.identity,cancelled.identity);assertEquals(ticket.generation,cancelled.generation)
                        assertSame(expected.request,ticket.request)
                        assertSame(command,requireNotNull(ticket.request.known).command)
                        assertSame(saved,requireNotNull(ticket.request.known).saved)
                        assertEquals(expected.source.claim,requireNotNull(ticket.request.known).source.claim)
                        ticket.identity
                    }
                    stage="removal:$index"
                    removedAt=if(strategy==null)removed(frozen) else {
                        val observation=h.observation(page)
                        val closedView=requireNotNull(observation.actualView)
                        val closedWindow=requireNotNull(observation.actualWindow)
                        val mounted=observation.windows.last {it.identity==windowIdentity && it.mounted}
                        assertEquals(mounted.viewIdentity,System.identityHashCode(closedView))
                        assertEquals(mounted.windowIdentity,System.identityHashCode(closedWindow))
                        closedViewIdentity=mounted.viewIdentity;closedWindowIdentity=mounted.windowIdentity
                        removedDialog(frozen,closedView)
                    }
                }
                stage="page_result:$index"
                val order=if(observed.page!=null) {
                    if(observed.prompt?.observed==observed.page?.observed)"same_screenshot" else "before_dialog"
                } else "after_dialog_close"
                val pageSeen=observed.page ?: householdPixels(h.runtime,page,h.seen(page))
                val shown=requireNotNull(page.events.single {it.version==pageSeen.version}.view)
                assertEquals(counts,shown.cards.mapValues {it.value.history.todayQuantity})
                assertEquals(saved.factUuid,shown.cards.getValue(uuid).followUp.claim.completionUuid)
                // The earlier pixel endpoint remains unchanged. AX is a distinct
                // observation: ACK may invalidate that version before this traversal.
                val pageText=householdResultText(h,page,command,saved,counts)
                assertTrue(tx(trace).isNotEmpty())
                steps+=Step(index,input,admission,command,saved,record.knownCommitAt,pageSeen,observed,order,
                    backAt,closedVersion,removedAt,trace,closure,closedViewIdentity,closedWindowIdentity,pageText)
            }
            stage="drain";h.synced();session.settled()
            val drainedAt=now();val workers=h.runtime.workerAttempts
            assertEquals(3,workers.size);assertEquals(steps.map {it.saved.requestId}.toSet(),workers.map {it.requestId}.toSet())
            workers.forEach {assertEquals(NextOperationAcceptance.COMMITTED,it.accepted)}
            assertFalse(session.diagnosticsOverflow);assertFalse(h.observation(page).overflow);assertFalse(page.diagnosticsOverflow)
            val generations=session.attempts.map {it.generation}.distinct().sorted()
            assertEquals(3,generations.size)
            assertEquals(1,session.attempts.count {it.generation==generations[1] && it.outcome=="suppressed"})
            stage="original_terminal"
            val originalShowNs=terminal(h,page,history+3,requireNotNull(finalSource),requireNotNull(finalMetrics))
            if(strategy!=null) {
                assertTrue(h.runtime.drained);assertFalse(h.connection.busy());assertTrue(h.job.isCompleted)
            }
            stage="record"
            fun budget(label:String,actual:Long,limit:Long) {if(actual>limit)failures+="$label:$actual>$limit"}
            budget("cold_ready",coldReady.observed-origin,2_000_000_000)
            log(history,"open",strategy) {
                put("seed_ns",seedNs);put("seed_strategy",if(history==730)"constructed_originally_verified" else "original_http_accepted")
                put("object_count",2);put("past_days",history/2);put("open_at_ns",origin)
                put("cold_ready_observed_ns",coldReady.observed-origin);put("cold_ready_version",coldReady.version)
                put("household_cold_count_reader_calls",h.cold.count("count_complete_history"));put("cold_starts",h.runtime.coldStarts)
                shared?.let {
                    put("window_mode",session.windowMode.name)
                    put("seed_cost_shared",true);put("seed_builds_for_pair",1);put("image_hash",it.imageHash)
                    put("restore_ns",it.restoreNs);put("order_index",it.order)
                    put("restore_includes_image_native_and_preferences_verification",true)
                }
            }
            for(step in steps) {
                val versions=page.events.filter {it.intent?.command===step.command && it.phase in setOf(
                    G0HouseholdCountPage.Phase.VERIFYING,G0HouseholdCountPage.Phase.SAVING,G0HouseholdCountPage.Phase.SAVED,
                    G0HouseholdCountPage.Phase.RESULT)}.map {it.version}.toSet()
                val privateDraw=h.seen(page).draws.filter {it.version in versions}.minOfOrNull {it.at}
                val busyDraw=h.feedback(page).draws.filter {it.commandVersion==step.admission.firstStateVersion}.minOfOrNull {it.at}
                val firstDraw=listOfNotNull(privateDraw,busyDraw).minOrNull()
                firstDraw?.let {assertTrue(it>=step.input)}
                if(firstDraw==null)failures+="${step.index}:feedback_missing" else budget("${step.index}:feedback",firstDraw-step.input,100_000_000)
                budget("${step.index}:save",step.commit-step.input,500_000_000)
                budget("${step.index}:result",step.pageSeen.observed-step.input,700_000_000)
                budget("${step.index}:foreground_resource",maxResource(step.trace),150_000_000)
                step.frames.prompt?.let {budget("${step.index}:new_prompt_observation_target",it.observed-step.input,700_000_000)}
                if(step.back!=null && step.removal!=null)budget("${step.index}:new_back_removal_target",step.removal-step.back,100_000_000)
                log(history,"operation",strategy) {
                    put("index",step.index);put("object",step.index%2);put("request_id",step.saved.requestId)
                    put("command_version",requireNotNull(step.admission.firstStateVersion));put("generation",generations[step.index])
                    put("input_ns",step.input-origin);put("admission_ns",step.admission.decidedAt-origin)
                    put("admission","accepted");put("known_commit_ns",step.commit-origin);put("save_ns",step.commit-step.input)
                    put("page_version",step.pageSeen.version);put("page_observed_ns",step.pageSeen.observed-origin)
                    put("result_ns",step.pageSeen.observed-step.input);put("page_observation_order",step.pageOrder)
                    put("page_ax_version",step.pageText.state.version);put("page_ax_runtime_version",step.pageText.runtimeVersion)
                    put("page_ax_start_ns",step.pageText.started-origin);put("page_ax_observed_ns",step.pageText.observed-origin)
                    put("page_ax_wait_ns",step.pageText.observed-step.pageText.started)
                    put("page_pixel_to_ax_ns",step.pageText.observed-step.pageSeen.observed)
                    put("page_ax_observations",step.pageText.passes)
                    put("page_ax_invalidations",step.pageText.invalidViews+step.pageText.changedDuringRead)
                    put("page_ax_invalid_views",step.pageText.invalidViews);put("page_ax_changed_during_read",step.pageText.changedDuringRead)
                    put("page_ax_incomplete_texts",step.pageText.incompleteTexts)
                    put("page_ax_scope","current_Command_Saved_State_View_and_same_authority_lease_before_after_one_root")
                    put("feedback_draw_present",firstDraw!=null);firstDraw?.let {put("feedback_draw_ns",it-step.input)}
                    put("feedback_observed",step.frames.feedback!=null)
                    step.frames.feedback?.let {put("feedback_observed_ns",it.observed-step.input);put("feedback_version",it.version)}
                    step.frames.feedbackKind?.let {put("feedback_observation_kind",it)}
                    put("prompt_expected",step.index!=1);put("prompt_observed",step.frames.prompt!=null)
                    step.frames.prompt?.let {put("prompt_observed_ns",it.observed-step.input);put("prompt_version",it.version)
                        put("prompt_capture_start_ns",it.captureStarted-origin)}
                    step.back?.let {put("back_ns",it-origin)};step.closedVersion?.let {
                        put("closed_version",it);put("close_version_changed_since_observation",it!=step.frames.prompt?.version)
                    }
                    step.removal?.let {put("removed_ns",it-origin);put("back_removal_ns",it-requireNotNull(step.back))}
                    put("exact_first_presentation",false)
                    if(strategy!=null) {
                        put("back_removal_scope","personal_marker_and_AX_absence_plus_current_Dialog_View_detached")
                        step.closure?.let {closed->
                            put("back_route",if(closed.expected!=null)"expected_close" else "window_cancel")
                            closed.cancellation?.let {cancel->put("cancelled_window",cancel.identity)
                                put("cancelled_generation",cancel.generation);put("cancelled_version",cancel.version)}
                        }
                        step.closedViewIdentity?.let {put("closed_view_identity",it)}
                        step.closedWindowIdentity?.let {put("closed_window_identity",it)}
                        step.frames.viewIdentity?.let {put("prompt_view_identity",it)}
                        step.frames.windowIdentity?.let {put("prompt_window_identity",it)}
                        step.frames.ticket?.let {put("prompt_window_ticket",it.identity)}
                        put("waiting_screenshot_observed",step.frames.waiting!=null)
                        step.frames.waiting?.let {
                            put("waiting_version",it.version);put("waiting_capture_start_ns",it.captureStarted-origin)
                            put("waiting_observed_ns",it.observed-step.input)
                        }
                    }
                }
            }
            session.events.forEach {event->log(history,"prompt_state",strategy) {
                put("version",event.version);put("phase",event.phase.name);put("at_ns",event.at-origin)
                event.expected?.let {put("generation",it.request.generation);put("identity",it.identity)}
                if(strategy!=null)event.window?.let {put("window_ticket",it.identity);put("window_generation",it.generation)}
            }}
            session.attempts.forEach {attempt->log(history,"prompt_attempt",strategy) {
                put("generation",attempt.generation);put("number",attempt.number);put("hint",attempt.hint)
                put("start_ns",attempt.started-origin);put("end_ns",attempt.ended-origin);put("outcome",attempt.outcome)
                attempt.publishedVersion?.let {version->put("version",version)
                    put("drawn",h.observation(page).draws.any {it.version==version && it.allowed})
                    put("screenshot_observed",steps.any {it.frames.prompt?.version==version})}
                attempt.errorClass?.let {put("error_class",it)}
            }}
            h.observation(page).draws.forEach {draw->log(history,"dialog_draw",strategy) {
                put("version",draw.version);put("generation",draw.generation);put("allowed",draw.allowed);put("at_ns",draw.at-origin)
                draw.screenRect?.let {r->put("screen_rect",buildJsonArray {add(r.left);add(r.top);add(r.right);add(r.bottom)})}
                if(strategy!=null) {put("view_identity",draw.viewIdentity);put("window_identity",draw.windowIdentity)}
            }}
            session.callbacks.forEach {callback->log(history,"callback",strategy) {
                put("operation",callback.operation);put("version",callback.version);put("outcome",callback.outcome);put("at_ns",callback.at-origin)
            }}
            if(strategy!=null) {
                h.observation(page).windows.forEachIndexed {index,event->log(history,"window_event",strategy) {
                    put("index",index);put("identity",event.identity);put("view_identity",event.viewIdentity)
                    put("window_identity",event.windowIdentity);put("mounted",event.mounted);put("at_ns",event.at-origin)
                    put("lifecycle_scope","Compose_effect_mount_dispose_not_WindowManager_attach")
                }}
                h.observation(page).waitingDraws.forEachIndexed {index,event->log(history,"waiting_draw",strategy) {
                    put("index",index);put("version",event.version);put("identity",event.identity);put("allowed",event.allowed)
                    put("view_identity",event.viewIdentity);put("window_identity",event.windowIdentity);put("at_ns",event.at-origin)
                }}
                session.windowCancellations.forEachIndexed {index,event->log(history,"window_cancel",strategy) {
                    put("index",index);put("identity",event.identity);put("generation",event.generation)
                    put("version",event.version);put("accepted",event.accepted);put("at_ns",event.at-origin)
                }}
            }
            val traces=mutableListOf("cold" to h.cold,"page_refresh" to refreshTrace,"prompt" to promptTrace)
            h.runtime.openTraces.forEachIndexed {index,t->traces+="page_open_$index" to t}
            steps.forEach {traces+="foreground_${it.index}" to it.trace}
            workers.forEach {worker->
                val index=steps.single {it.saved.requestId==worker.requestId}.index
                assertTrue(tx(worker.send).isNotEmpty());assertTrue(tx(worker.ack).isNotEmpty())
                traces+="send_$index" to worker.send;traces+="ack_$index" to worker.ack
                budget("$index:send_resource",maxResource(worker.send),150_000_000)
                budget("$index:ack_resource",maxResource(worker.ack),150_000_000)
                budget("$index:ack",worker.ackEnded-worker.ackStarted,500_000_000)
                log(history,"sync",strategy) {
                    put("index",index);put("request_id",worker.requestId);put("enqueue_ns",worker.enqueued-origin)
                    put("send_start_ns",worker.sendStarted-origin);put("send_end_ns",worker.sendEnded-origin)
                    put("ack_start_ns",worker.ackStarted-origin);put("ack_end_ns",worker.ackEnded-origin);put("accepted",true)
                    if(strategy!=null) {
                        val step=steps.single {it.saved.requestId==worker.requestId}
                        put("known_commit_to_offer_ns",worker.enqueued-step.commit)
                        put("known_commit_to_send_ns",worker.sendStarted-step.commit)
                        put("known_commit_to_ack_ns",worker.ackEnded-step.commit)
                    }
                }
            }
            assertTrue(tx(promptTrace).isNotEmpty());assertTrue(spans(promptTrace).count {it.phase=="operation"}==session.attempts.size)
            budget("prompt_resource",maxResource(promptTrace),150_000_000)
            budget("page_refresh_resource",maxResource(refreshTrace),150_000_000)
            for((name,trace) in traces) {
                val all=spans(trace);val windows=tx(trace)
                log(history,"trace",strategy) {
                    put("trace",name);put("transactions",JsonArray(windows.map {w->buildJsonObject {put("start_ns",w.start-origin);put("end_ns",w.end-origin)}}))
                    put("account_windows",JsonArray(all.filter {it.phase in setOf("count_account_wait","count_account_hold","domain_sql_wait","domain_sql_hold")}.map {s->buildJsonObject {
                        put("phase",s.phase);put("start_ns",s.start-origin);put("end_ns",s.end-origin);put("ok",s.ok)
                    }}))
                    put("access_decrypt",trace.count("decrypt_access"));put("refresh_decrypt",trace.count("decrypt_refresh"))
                    put("unclassified_decrypt",trace.count("decrypt_unclassified"))
                    put("instrumented_count_history_calls",trace.count("count_complete_history"))
                    put("count_history_counter_scope","household_store_cold_callsite_only_not_global")
                    put("max_resource_ns",maxResource(trace));put("operation_count",trace.count("operation"))
                }
            }
            val lastAck=workers.maxOf {it.ackEnded}
            val depthEvents=steps.map {it.commit to 1}+workers.map {it.ackEnded to -1}
            var depth=0;var peakKnownUnacked=0
            for((_,delta) in depthEvents.sortedWith(compareBy<Pair<Long,Int>> {it.first}.thenBy {it.second})) {
                depth+=delta;assertTrue(depth>=0);peakKnownUnacked=maxOf(peakKnownUnacked,depth)
            }
            assertEquals(0,depth)
            val measured=steps.map {step->
                val worker=workers.single {it.requestId==step.saved.requestId}
                Sample(step.index,step.commit-step.input,step.pageSeen.observed-step.input,
                    step.frames.prompt?.let {it.observed-step.input},
                    step.removal?.let {it-requireNotNull(step.back)},worker.ackEnded-worker.ackStarted,
                    worker.sendStarted-step.commit,worker.ackEnded-step.commit)
            }
            log(history,"terminal",strategy) {
                put("actual_inputs",3);put("accepted",3);put("actual_syncs",3);put("facts",history+3)
                put("outbox",0);put("original_verified",true);put("replayed",true);put("native_and_preferences_unchanged_by_oracle",true)
                put("original_coordinator_first_show_ns",originalShowNs);put("original_full_history_calls",JsonNull)
                put("original_full_history_counter","uninstrumented");put("component_baseline_only",true);put("same_image_ui_ab",false)
                put("drained_ns",drainedAt-origin);put("last_page_to_drained_ns",drainedAt-steps.last().pageSeen.observed)
                put("last_ack_end_ns",workers.maxOf {it.ackEnded}-origin)
                put("drain_observation_lag_ns",drainedAt-workers.maxOf {it.ackEnded})
                put("new_prompt_observation_target_ns",700_000_000);put("new_back_removal_target_ns",100_000_000)
                put("new_targets_are_existing_contract",false);put("failures",JsonArray(failures.map(::JsonPrimitive)))
                if(strategy!=null) {
                    put("first_input_to_last_ack_ns",lastAck-steps.first().input)
                    put("last_known_commit_to_last_ack_ns",lastAck-steps.last().commit)
                    put("peak_known_committed_unacked",peakKnownUnacked)
                    put("queue_scope","known_return_to_ACK_end_not_persistent_outbox_creation_age")
                    put("original_reference_scope","component_reference_not_UI_AB")
                    put("paired_strategy_observation",true)
                }
            }
            return Measurement(failures.toList(),measured,coldReady.observed-origin,
                lastAck-steps.first().input,lastAck-steps.last().commit,peakKnownUnacked)
        } catch(problem:Throwable) {
            failure(history,stage,problem,strategy,steps.size,
                retainedPage?.commandRecords?.values?.count {it.saved!=null} ?: 0,
                retainedHarness?.runtime?.workerAttempts?.size ?: 0,failures)
            throw problem
        }
    }
}
