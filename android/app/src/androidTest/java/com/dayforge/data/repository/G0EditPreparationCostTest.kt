package com.dayforge.data.repository

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Three actual editing conditions per identical restored image; not a draft-cache A/B or P95.
 * All budgets are checked after all three complete original-oracle/replay terminals. */
@RunWith(AndroidJUnit4::class)
class G0EditPreparationCostTest : G0EditPreparationFixture() {
    @Test(timeout=900_000) fun eightAcceptedHistoryThreeLegalEditConditions()=runBlocking<Unit> { measure(8) }
    @Test(timeout=900_000) fun eightyAcceptedHistoryThreeLegalEditConditions()=runBlocking<Unit> { measure(80) }
    private val conditions=listOf("earliest","dwell_300ms","ack_invalidation")
    private fun emit(history:Int,condition:String,kind:String,body:JsonObjectBuilder.()->Unit) {
        Log.i("G0EditPreparationMeasure",buildJsonObject {
            put("history",history);put("condition",condition);put("kind",kind);body()
        }.toString())
    }
    private data class Attempt(val input:G0ScreenObservation.Input,val decision:Decision?,val beforeVersion:Long,
        val beforePhase:G0InteractionSession.Phase,val left:Float,val top:Float,val right:Float,val bottom:Float)
    private suspend fun attempt(h:Harness):Attempt {
        val before=h.session.state.value;val prior=h.decisions.size
        val rect=requireNotNull(h.observation.bounds["save"])
        h.observation.tap("save")
        // Native injectInputEvent(waitForFinish=true), then a real main-dispatch boundary. No
        // draft screenshot, Compose idle wait or hidden preparation completion precedes the tap.
        val decisions=withContext(Dispatchers.Main) { h.decisions.drop(prior) }
        check(decisions.size<=1)
        return Attempt(h.observation.inputs.last(),decisions.singleOrNull(),before.version,before.phase,
            rect.left,rect.top,rect.right,rect.bottom)
    }
    private fun spans(trace:G0PhaseTrace)=synchronized(trace.spans) { trace.spans.toList() }
    private fun resources(trace:G0PhaseTrace):List<Pair<String,Pair<Long,Long>>> {
        val all=spans(trace)
        val starts=all.filter { it.phase=="tx_begin_outer" }.sortedBy { it.start }
        val ends=all.filter { it.phase=="tx_end_outer" }.sortedBy { it.start }
        check(starts.size==ends.size)
        val transactions=starts.zip(ends).map { (a,b) ->
            check(a.ok && b.ok && a.end>=a.start && b.start>=a.end && b.end>b.start)
            "transaction" to (a.start to b.end)
        }
        check(transactions.zipWithNext().all { (a,b) -> a.second.second<=b.second.first })
        return transactions+all.filter { it.phase in setOf("short_account_hold","domain_sql_hold") }.map {
            check(it.ok && it.end>it.start);it.phase to (it.start to it.end)
        }
    }
    private suspend fun measure(history:Int) {
        val failures=mutableListOf<String>()
        val seed=seedEdits(history)
        for((order,condition) in conditions.withIndex()) {
            var phase="restore"
            try {
                val setupAt=now();val h=restoredHarness(seed);val setupEnd=now()
                phase="open"
                val (openAt,editableSeen)=h.openEditable()
                val initial=h.session.state.value
                assertTrue(initial.renderable());assertEquals(1,initial.publication!!.value.pending)
                assertEquals(1,h.builds.get())
                phase="input"
                val inputAt=h.typeName()
                val draft=h.session.state.value
                assertEquals(editedName,draft.name);assertEquals(baseDescription,draft.description)
                assertEquals(0,h.trace.count("warm_edit_compute"));assertTrue(h.port.calls.isEmpty())
                var ackStart:Long?=null;var ackEnd:Long?=null
                var dwellDeadline:Long?=null
                if(condition=="dwell_300ms") {
                    dwellDeadline=inputAt+300_000_000L
                    val remaining=dwellDeadline-now()
                    if(remaining>0) delay((remaining+999_999)/1_000_000)
                } else if(condition=="ack_invalidation") {
                    phase="old_ack"
                    val ack=h.acceptOld();ackStart=ack.first;ackEnd=ack.second
                    assertFalse("Old held publication must remain invalid",draft.renderable())
                }
                phase="first_confirm"
                val attempts=mutableListOf(attempt(h))
                if(attempts.last().decision?.accepted!=true) {
                    // This is an explicit SECOND real touch, never a queued retry. A disabled
                    // first touch remains NO_HANDLER; a false callback remains a distinct refusal.
                    phase="fresh_republication"
                    val fresh=withTimeout(20_000) { h.session.state.first {
                        it.phase==G0InteractionSession.Phase.EDITING && it.renderable() &&
                            (condition!="ack_invalidation" || it.publication?.value?.pending==0)
                    } }
                    assertEquals(draft.name,fresh.name);assertEquals(draft.description,fresh.description)
                    assertEquals(draft.inputVersion,fresh.inputVersion)
                    h.pixels(G0InteractionSession.Phase.EDITING)
                    assertTrue(h.port.calls.isEmpty())
                    attempts+=attempt(h)
                }
                assertEquals("One explicitly admitted edit",1,attempts.count { it.decision?.accepted==true })
                assertEquals(1,h.decisions.count { it.accepted })
                val accepted=attempts.single { it.decision?.accepted==true }
                val confirmAt=accepted.input.started
                phase="feedback"
                val feedback=h.observation.awaitAny { h.session.events.filter {
                    it.kind in setOf("state_SAVING","state_SAVED_REFRESHING","state_SAVED")
                }.map { it.version } }
                val feedbackEvent=h.session.events.single { it.version==feedback.version && it.kind.startsWith("state_") }
                val feedbackDraw=h.observation.draws.single { it.version==feedback.version }
                phase="result"
                val resultSeen=h.pixels(G0InteractionSession.Phase.SAVED)
                h.session.awaitSettled()
                val result=h.session.state.value
                val call=h.port.calls.single()
                assertTrue(call.completed);assertEquals(editedName,result.publication!!.value.name)
                val committed=h.session.events.single { it.kind=="committed" }.at
                assertTrue(committed>=confirmAt);assertTrue(resultSeen.observed>=committed)
                assertTrue(feedbackDraw.at>=feedbackEvent.at && feedback.observed>=feedbackDraw.at)
                if(feedbackEvent.kind!="state_SAVING") assertTrue(feedbackEvent.at>=committed)
                val legalFeedbackVersions=h.session.events.filter {
                    it.kind in setOf("state_SAVING","state_SAVED_REFRESHING","state_SAVED") && it.at>=confirmAt
                }.map { it.version }.toSet()
                val earliestFeedback=h.observation.draws.filter { it.version in legalFeedbackVersions }.minBy { it.at }
                val earliestFeedbackEvent=h.session.events.single { it.version==earliestFeedback.version && it.kind.startsWith("state_") }
                assertTrue(earliestFeedback.at>=confirmAt)
                assertTrue(earliestFeedback.at>=earliestFeedbackEvent.at)
                if(earliestFeedbackEvent.kind!="state_SAVING") assertTrue(earliestFeedbackEvent.at>=committed)
                val operationSpans=spans(h.trace).filter { it.start<=resultSeen.observed }
                val compute=operationSpans.filter { it.phase=="warm_edit_compute" }
                assertTrue(compute.isNotEmpty())
                assertTrue("Original cold transactions are really measured",h.coldTrace.count("tx_begin_outer")>0)
                assertEquals(history+1,h.coldTrace.count("cold_origin_proof"))
                phase="sync"
                val syncAt=h.observation.tap("sync")
                val syncSeen=h.pixels(G0InteractionSession.Phase.SYNCED);h.session.awaitSettled()
                assertEquals(0,h.session.state.value.publication!!.value.pending)
                phase="original_oracle"
                val terminal=verifyTerminal(h,condition=="ack_invalidation")
                phase="record"
                val warmResources=resources(h.trace)+resources(h.ackTrace)
                assertTrue(resources(h.trace).any { it.first=="transaction" })
                assertTrue(resources(h.coldTrace).any { it.first=="transaction" })
                if(condition=="ack_invalidation") assertTrue(resources(h.ackTrace).any { it.first=="transaction" })
                val maxWarm=warmResources.maxOf { it.second.second-it.second.first }
                val driver=h.inputDriverEvents.single()
                assertEquals(true,driver.accepted);assertEquals(inputAt,requireNotNull(driver.callbackObservedAt))
                emit(history,condition,"journey") {
                    put("order_index",order);put("image_hash",seed.imageHash);put("accepted_history",history)
                    put("initial_pending",1);put("cold_origin_count",h.coldTrace.count("cold_origin_proof"));put("builds",h.builds.get())
                    put("setup_ns",setupEnd-setupAt);put("open_at_ns",openAt)
                    put("editable_observed_ns",editableSeen.observed-openAt);put("editable_version",editableSeen.version)
                    put("draft_input_complete_ns",inputAt-openAt);put("input_version",draft.inputVersion)
                    put("input_driver",buildJsonObject {
                        put("lookup_started_ns",driver.lookupStarted-openAt)
                        put("qualified_ns",requireNotNull(driver.qualifiedAt)-openAt)
                        put("dispatch_started_ns",requireNotNull(driver.dispatchStarted)-openAt)
                        put("dispatch_returned_ns",requireNotNull(driver.dispatchReturned)-openAt)
                        put("callback_observed_ns",requireNotNull(driver.callbackObservedAt)-openAt)
                        put("lookup_passes",driver.lookupPasses);put("rejected_candidates",driver.rejectedCandidates)
                        put("refresh_checked",driver.refreshChecked);put("refreshed",driver.refreshed)
                        put("set_text_advertised",driver.setTextAdvertised);put("enabled",driver.enabled)
                        put("editable",driver.editable);put("visible",driver.visible);put("accepted",driver.accepted==true)
                        fun snapshot(s:InputState)=buildJsonObject {
                            put("session",s.session);put("version",s.version);put("input_version",s.inputVersion)
                            put("phase",s.phase);put("renderable",s.renderable)
                        }
                        put("before",snapshot(driver.before));put("after",snapshot(driver.after))
                    })
                    put("first_confirm_after_input_ns",attempts.first().input.started-inputAt)
                    put("planned_dwell_ns",if(condition=="dwell_300ms") 300_000_000L else 0L)
                    dwellDeadline?.let { put("dwell_lateness_ns",attempts.first().input.started-it) }
                    ackStart?.let { put("old_ack_start_ns",it-openAt) };ackEnd?.let { put("old_ack_end_ns",it-openAt) }
                    put("attempt_count",attempts.size);put("first_attempt_outcome",when(attempts.first().decision?.accepted) {
                        true->"accepted";false->"handler_refused";null->"no_handler"
                    })
                    put("accepted_confirm_ns",confirmAt-openAt);put("save_commit_ns",committed-confirmAt)
                    put("feedback_phase",feedbackEvent.kind.removePrefix("state_"));put("feedback_version",feedback.version)
                    put("feedback_draw_ns",earliestFeedback.at-confirmAt)
                    put("first_feedback_draw_version",earliestFeedback.version)
                    put("first_feedback_draw_phase",earliestFeedbackEvent.kind.removePrefix("state_"))
                    put("matched_feedback_draw_ns",feedbackDraw.at-confirmAt);put("feedback_observed_ns",feedback.observed-confirmAt)
                    put("result_version",resultSeen.version);put("result_observed_ns",resultSeen.observed-confirmAt)
                    put("open_result_observed_ns",resultSeen.observed-openAt)
                    put("sync_result_observed_ns",syncSeen.observed-syncAt);put("open_synced_observed_ns",syncSeen.observed-openAt)
                    put("warm_edit_compute_count",compute.size);put("warm_edit_compute_ns",compute.sumOf { it.end-it.start })
                    put("preparation_retry_count",operationSpans.count { it.phase=="preparation_retry" })
                    put("preparation_stale_count",operationSpans.count { it.phase=="preparation_stale" })
                    put("refreshing_state_count",h.session.events.count { it.kind=="state_REFRESHING" })
                    val beforeConfirmReads=h.adapter.spans.filter { it.stage=="read" && it.started>=inputAt && it.ended<=confirmAt }
                    put("post_input_pre_confirm_read_count",beforeConfirmReads.size)
                    put("post_input_pre_confirm_read_ns",beforeConfirmReads.sumOf { it.ended-it.started })
                    put("max_warm_resource_ns",maxWarm);put("exact_first_presentation",false)
                    put("draft_work_started_before_confirm",false);put("same_work_algorithm_ab",false)
                }
                attempts.forEachIndexed { index,a -> emit(history,condition,"confirm_attempt") {
                    put("index",index);put("input_start_ns",a.input.started-openAt)
                    put("down_return_ns",a.input.downReturned-openAt);put("up_start_ns",a.input.upStarted-openAt)
                    put("up_return_ns",a.input.upReturned-openAt);put("before_version",a.beforeVersion)
                    put("before_phase",a.beforePhase.name);put("handler_observed",a.decision!=null)
                    a.decision?.let { put("accepted",it.accepted);put("decision_ns",it.at-openAt);put("decision_version",it.version) }
                    put("bounds",buildJsonArray { add(a.left);add(a.top);add(a.right);add(a.bottom) })
                } }
                h.adapter.spans.forEachIndexed { index,s -> emit(history,condition,"adapter_span") {
                    put("index",index);put("stage",s.stage);put("start_ns",s.started-openAt);put("end_ns",s.ended-openAt)
                    put("duration_ns",s.ended-s.started);put("completed",s.completed)
                } }
                for((label,trace) in listOf("cold" to h.coldTrace,"interaction" to h.trace,"old_ack" to h.ackTrace)) {
                    spans(trace).groupBy { it.phase }.toSortedMap().forEach { (name,group) -> emit(history,condition,"trace_summary") {
                        put("trace",label);put("phase",name);put("count",group.size);put("total_ns",group.sumOf { it.end-it.start })
                        put("max_ns",group.maxOf { it.end-it.start });put("failed",group.count { !it.ok })
                    } }
                    resources(trace).forEachIndexed { index,(name,bounds) -> emit(history,condition,"resource_span") {
                        put("trace",label);put("index",index);put("phase",name)
                        put("start_ns",bounds.first-openAt);put("end_ns",bounds.second-openAt);put("duration_ns",bounds.second-bounds.first)
                    } }
                }
                emit(history,condition,"terminal") {
                    put("command_id",terminal.command);put("actual_request_id",terminal.actualId)
                    put("origins",terminal.origins);put("receipts",terminal.receipts);put("supersessions",terminal.supersessions)
                    put("outbox",0);put("original_verified",true);put("original_id_replayed",true)
                    put("native_unchanged_by_oracle",true);put("ui",true);put("attempts",attempts.size)
                }
                fun budget(name:String,value:Long,limit:Long) { if(value>limit) failures+="$condition:$name:$value>$limit" }
                budget("cold_editable",editableSeen.observed-openAt,2_000_000_000L)
                budget("feedback",earliestFeedback.at-confirmAt,100_000_000L)
                budget("save",committed-confirmAt,500_000_000L)
                budget("result",resultSeen.observed-confirmAt,700_000_000L)
                budget("warm_resource",maxWarm,150_000_000L)
                if(condition!="ack_invalidation" && attempts.size!=1) failures+="$condition:earliest_legal_touch_not_admitted"
            } catch(error:Throwable) {
                Log.e("G0EditPreparationFailure",buildJsonObject {
                    put("history",history);put("condition",condition);put("kind","exception");put("phase",phase)
                    put("type",error.javaClass.simpleName)
                }.toString());throw error
            }
        }
        if(failures.isNotEmpty()) Log.e("G0EditPreparationFailure",buildJsonObject {
            put("history",history);put("condition","all");put("kind","budgets")
            put("failures",JsonArray(failures.map(::JsonPrimitive)))
        }.toString())
        assertTrue("Original budgets, complete conditions: $failures",failures.isEmpty())
    }
}
