package com.dayforge.data.repository

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.first
import org.junit.Rule
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** P1 one 8-history journey per layout. Screenshot upper bounds, NOT P95 or a release benchmark. */
@RunWith(AndroidJUnit4::class)
class G0InteractionScreenTest : G0InteractionFixture() {
    // No Compose test clock or idle synchronization on the measured path.
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private var stableFields=false
    override fun installScreen() { activity.scenario.onActivity { it.setContent { G0InteractionScreen(interaction,observation,stableFields) } } }
    private fun screenNode(text:String):AccessibilityNodeInfo? {
        val root=InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return null
        fun find(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
            if(node.text?.toString()==text && node.isVisibleToUser) return node
            for(i in 0 until node.childCount) node.getChild(i)?.let { child -> find(child)?.let { return it } }
            return null
        }
        return find(root)
    }
    private suspend fun screenText(text:String)=withTimeout(5_000) {
        while(screenNode(text)==null) delay(16)
    }
    private suspend fun replaceText(original:String,value:String) {
        screenText(original)
        val node=requireNotNull(screenNode(original));check(node.isEditable)
        check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)
        }))
        withTimeout(5_000) { interaction.state.first { it.name==value } }
        observation.await(interaction.state.value.version)
        screenText(value)
    }
    @Test fun coldEditSaveAndAckHaveVersionMatchedScreenObservations()=runBlocking<Unit> { journey(false) }
    @Test fun stableEmptyControlsPreserveVersionMatchedScreenObservations()=runBlocking<Unit> { journey(true) }
    private suspend fun journey(stable:Boolean) {
        stableFields=stable
        register()
        val (http,_)=channel { input -> successReply(input,revision=(if(input.path.endsWith("/identity")) 0L else
            wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0L)+1) }
        repeat(8) { i ->
            val row=editMetric("History $i")
            val sent=requireNotNull(sender(http).sendOperation(access(),row.operationId))
            assertEquals(NextOperationAcceptance.COMMITTED,sender(http).acceptOperation(sent))
        }
        val setupAt=android.os.SystemClock.elapsedRealtimeNanos()
        setupInteraction()
        val initialSeen=observation.await(interaction.state.value.version)
        val openAt=observation.tap("open")
        val loading=phase(G0InteractionSession.Phase.LOADING)
        val loadingSeen=observation.await(loading.version)
        val editable=phase(G0InteractionSession.Phase.EDITING)
        val editableSeen=observation.await(editable.version)
        interaction.awaitSettled()
        replaceText("History 7","P1 visible change")
        val saveAt=observation.tap("save")
        val savingSeen=observation.awaitAny {
            interaction.events.filter { it.kind in setOf("state_SAVING","state_SAVED_REFRESHING","state_SAVED") }.map { it.version }
        }
        val firstFeedbackKind=interaction.events.single { it.version==savingSeen.version && it.kind.startsWith("state_") }.kind
        val knownSavedSeen=if(firstFeedbackKind!="state_SAVING") savingSeen else observation.awaitAny {
            interaction.events.filter { it.kind in setOf("state_SAVED_REFRESHING","state_SAVED") }.map { it.version }
        }
        val saved=phase(G0InteractionSession.Phase.SAVED)
        val savedSeen=observation.await(saved.version)
        interaction.awaitSettled()
        screenText("已保存，待同步")
        assertEquals("P1 visible change",saved.publication!!.value.name)
        assertEquals(1,saved.publication.value.pending)
        val committed=interaction.events.single { it.kind=="committed" }.at
        val syncAt=observation.tap("sync")
        val syncing=phase(G0InteractionSession.Phase.SYNCING)
        val syncingSeen=observation.await(syncing.version)
        val synced=phase(G0InteractionSession.Phase.SYNCED)
        val syncedSeen=observation.await(synced.version)
        interaction.awaitSettled()
        screenText("已同步")
        assertEquals(0,synced.publication!!.value.pending)
        val observations=listOf("loading" to loadingSeen,"editable" to editableSeen,"saving" to savingSeen,
            "saved" to savedSeen,"syncing" to syncingSeen,"synced" to syncedSeen)
        for((label,seen) in observations) {
            val draw=observation.draws.single { it.version==seen.version }
            val published=interaction.events.single { it.version==seen.version && it.kind.startsWith("state_") }.at
            assertTrue(draw.at>=published)
            assertTrue(seen.observed>=draw.at);assertTrue(seen.observed>=seen.captureStarted)
            Log.i("G0InteractionFrame",buildJsonObject {
                put("stable_fields",stableFields);put("stage",label);put("version",seen.version);put("draw_ns",draw.at-openAt);put("published_ns",published-openAt);put("state_to_draw_ns",draw.at-published)
                put("capture_start_ns",seen.captureStarted-openAt);put("observed_ns",seen.observed-openAt)
                put("capture_ns",seen.observed-seen.captureStarted);put("attempts",seen.attempts)
                put("screen_pixels_matched",true);put("exact_first_presentation",false)
            }.toString())
        }
        // Keep the original pre-DOWN start. Callback timestamps diagnose delivery, never replace it.
        for((key,seen) in listOf("open" to loadingSeen,"save" to savingSeen,"sync" to syncingSeen)) {
            val input=observation.inputs.single { it.key==key }
            val click=observation.clicks.single { it.key==key }
            val publication=interaction.events.single { it.version==seen.version && it.kind.startsWith("state_") }
            val draw=observation.draws.single { it.version==seen.version }
            assertTrue(input.started<=input.downReturned && input.downReturned<=input.upStarted)
            assertTrue(input.upStarted<=input.upReturned)
            assertTrue(click.at>=input.started && publication.at>=click.at)
            assertTrue(draw.at>=publication.at && seen.observed>=draw.at)
            Log.i("G0InteractionInput",buildJsonObject {
                put("stable_fields",stableFields);put("action",key);put("version",seen.version)
                put("down_injection_ns",input.downReturned-input.started)
                put("up_injection_ns",input.upReturned-input.upStarted)
                put("input_to_callback_ns",click.at-input.started)
                put("callback_to_state_ns",publication.at-click.at)
                put("state_to_draw_ns",draw.at-publication.at)
                put("input_to_draw_ns",draw.at-input.started)
                put("input_to_observed_ns",seen.observed-input.started)
                put("callback_before_injection_return",click.at<=input.upReturned)
                put("exact_first_presentation",false)
            }.toString())
        }
        assertEquals(listOf("initialize","read","edit","read","sync_plan","send","ack","read"),adapter.spans.map { it.stage })
        for(span in adapter.spans) {
            assertTrue(span.completed);assertTrue(span.ended>=span.started)
            Log.i("G0InteractionWork",buildJsonObject {
                put("stable_fields",stableFields);put("stage",span.stage);put("started_ns",span.started-openAt)
                put("ended_ns",span.ended-openAt);put("duration_ns",span.ended-span.started)
            }.toString())
        }
        val knownSavedEvent=interaction.events.single { it.version==knownSavedSeen.version && it.kind.startsWith("state_") }
        val knownSavedDraw=observation.draws.single { it.version==knownSavedSeen.version }
        val receipt=interaction.events.single { it.kind=="state_SAVED_REFRESHING" }
        assertTrue(receipt.at>=committed)
        assertTrue(knownSavedDraw.at>=committed && knownSavedSeen.observed>=knownSavedDraw.at)
        assertTrue(committed>=saveAt && savedSeen.observed>=committed)
        Log.i("G0InteractionJourney",buildJsonObject {
            put("page_setup_to_initial_draw_ns",observation.draws.single { it.version==initialSeen.version }.at-setupAt)
            put("page_setup_to_initial_observed_ns",initialSeen.observed-setupAt)
            put("page_setup_to_editable_observed_ns",editableSeen.observed-setupAt)
            put("initial_observed_to_open_ns",openAt-initialSeen.observed)
            put("stable_fields",stableFields);put("history",8);put("builds",builds);put("open_feedback_observed_ns",loadingSeen.observed-openAt)
            put("open_editable_observed_ns",editableSeen.observed-openAt)
            put("save_feedback_observed_phase",firstFeedbackKind)
            put("known_saved_observed_phase",knownSavedEvent.kind)
            put("known_saved_draw_ns",knownSavedDraw.at-saveAt)
            put("known_saved_observed_ns",knownSavedSeen.observed-saveAt)
            put("receipt_state_ns",receipt.at-saveAt)
            put("receipt_was_drawn",observation.draws.any { it.version==receipt.version })
            put("save_feedback_observed_ns",savingSeen.observed-saveAt);put("save_commit_ns",committed-saveAt)
            put("save_result_observed_ns",savedSeen.observed-saveAt);put("sync_result_observed_ns",syncedSeen.observed-syncAt)
            put("open_result_observed_ns",savedSeen.observed-openAt)
            put("feedback_observation_over_100ms",savingSeen.observed-saveAt>100_000_000)
            put("exact_first_presentation",false);put("formal_latency_gate",false)
        }.toString())
        originalDatabase();assertEquals(9,count("next_request_origins"));assertEquals(9,count("next_acceptances"))
        assertEquals(0,count("sync_outbox"));assertEquals("P1 visible change",db.metricDao().getMetricById(metric.id)!!.name)
    }
}
