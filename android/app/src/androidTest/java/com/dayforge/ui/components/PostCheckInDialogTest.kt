package com.dayforge.ui.components

import android.app.Application
import android.view.KeyEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class PostCheckInDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val metrics = listOf(LinkedMetricInfo("Weight", 7, null, "kg", 1),
        LinkedMetricInfo("Steps", 9, null, "steps", 0))

    @Test fun slowSubmissionLocksInputsAndSkipAndFlushesExactLatestDraftOnce() {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        var flushed = ""
        compose.setContent { MaterialTheme { PostCheckInDialog("Once", metrics,
            { values, _ ->
                assertEquals("12.5", flushed)
                assertEquals(12.5, values.single().value, 0.0)
                calls++; release.await()
            }, { fail("Skip cannot race submission") }, {}, promptIdentity = "event",
            onDraftChange = { flushed = it.single { input -> input.metricId == 7L }.inputValue }) } }
        compose.onNodeWithText("Weight (kg)").performTextInput("12.5")
        compose.onNodeWithText(compose.activity.getString(R.string.post_checkin_record)).performClick()
        compose.waitUntil { calls == 1 }
        compose.onNodeWithText("Weight (kg)").assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.post_checkin_skip)).assertIsNotEnabled()
        compose.onNode(isToggleable()).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, calls); release.complete(Unit) }
        compose.waitForIdle()
        compose.onNodeWithText("Weight (kg)").assertIsEnabled()
    }

    @Test fun record_is_disabled_for_empty_input_and_submits_only_entered_values_with_preference() {
        var result: Pair<List<MetricValueInput>, Boolean>? = null
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize()) {
            PostCheckInDialog("Walk", metrics, { values, never -> result = values to never }, {}, {})
        } } }
        val record = compose.onNodeWithText(compose.activity.getString(R.string.post_checkin_record))
        record.assertIsNotEnabled()
        compose.onNodeWithText("Weight (kg)").performTextInput("68.5")
        record.assertIsEnabled()
        compose.onNode(isToggleable()).performClick()
        record.performClick()
        compose.runOnIdle { assertEquals(listOf(MetricValueInput(7, 68.5)) to true, result) }
    }

    @Test fun skip_preserves_checkbox_choice_and_never_records_typed_values() {
        var recorded = false
        var skipped: Boolean? = null
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize()) {
            PostCheckInDialog("Walk", metrics, { _, _ -> recorded = true }, { skipped = it }, {})
        } } }
        compose.onNodeWithText("Weight (kg)").performTextInput("68.5")
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.post_checkin_skip)).performClick()
        compose.runOnIdle { assertEquals(true, skipped); assertFalse(recorded) }
    }

    @Test fun durable_prompt_restores_raw_input_publishes_edits_and_explicit_metadata_refresh() {
        var drafts = emptyList<MetricInputState>()
        var refreshes = 0
        compose.setContent { MaterialTheme { PostCheckInDialog("Once", metrics, { _, _ -> }, {}, {},
            initialInputs = mapOf(7L to ("12.5" to "retained")), promptIdentity = "stable-event",
            onDraftChange = { drafts = it }, onRefreshMetadata = { refreshes++ }) } }
        compose.onNodeWithText("Weight (kg)").assertTextContains("12.5")
        compose.onNodeWithText("Weight (kg)").performTextReplacement("13.2")
        compose.onNodeWithText(compose.activity.getString(R.string.once_refresh_metrics)).performClick()
        compose.runOnIdle {
            assertEquals("13.2", drafts.single { it.metricId == 7L }.inputValue)
            assertEquals("retained", drafts.single { it.metricId == 7L }.note)
            assertEquals(1, refreshes)
        }
    }

    @Test fun candidateKeepsItsRealWindowWhileWaitingRemovesPersonalSemanticsAndBackOnlyCancels() {
        var identity by mutableStateOf("proof-one")
        var showContent by mutableStateOf(true)
        val windows = ConcurrentHashMap<String, Window>()
        val cancelled = AtomicInteger()
        val drafts = AtomicInteger()
        val dismissed = AtomicInteger()
        val waiting = "Checking current information"
        compose.setContent {
            val currentIdentity = identity
            val currentContent = showContent
            MaterialTheme {
                PostCheckInDialog(
                    "Private habit", metrics,
                    { _, _ -> fail("Waiting cannot record") }, { fail("Waiting cannot skip") },
                    { dismissed.incrementAndGet() },
                    modifier = Modifier.composed {
                        val window = (LocalView.current.parent as DialogWindowProvider).window
                        SideEffect { windows["$currentIdentity:$currentContent"] = window }
                        this
                    },
                    initialInputs = mapOf(7L to ("12.5" to "")),
                    promptIdentity = currentIdentity,
                    onDraftChange = { drafts.incrementAndGet() },
                    presentation = PostCheckInDialogPresentation(currentContent, waiting) {
                        cancelled.incrementAndGet()
                    }
                )
            }
        }
        compose.onNodeWithText("Private habit", substring = true).assertExists()
        compose.onNodeWithText("Weight (kg)").assertTextContains("12.5")
        compose.waitUntil(10_000) { windows.containsKey("proof-one:true") }
        val originalWindow = requireNotNull(windows["proof-one:true"])
        compose.runOnIdle { showContent = false }
        compose.onNodeWithText(waiting).assertExists()
        compose.onAllNodes(hasText("Private habit", substring = true), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(hasText("Weight", substring = true), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(hasText("Steps", substring = true), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(isToggleable(), useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText(compose.activity.getString(R.string.post_checkin_record)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.post_checkin_skip)).assertDoesNotExist()
        // Query a fresh platform accessibility root; a missing root cannot pass.
        compose.waitUntil(10_000) {
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            if (root == null) false else {
                var anonymous = false
                var personal = false
                fun visit(node: android.view.accessibility.AccessibilityNodeInfo) {
                    val text = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString()
                    if (text.contains(waiting)) anonymous = true
                    if (node.isEditable || listOf("Private habit", "Weight", "Steps", "12.5").any(text::contains)) personal = true
                    for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
                }
                visit(root)
                anonymous && !personal
            }
        }
        compose.waitUntil(10_000) { windows.containsKey("proof-one:false") }
        assertSame(originalWindow, windows["proof-one:false"])
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { cancelled.get() == 1 }
        compose.onNodeWithText(compose.activity.getString(android.R.string.cancel)).performClick()
        compose.runOnIdle {
            assertEquals(2, cancelled.get())
            assertEquals(0, drafts.get())
            assertEquals(0, dismissed.get())
            identity = "proof-two"
            showContent = true
        }
        compose.onNodeWithText("Weight (kg)").assertTextContains("12.5")
        compose.onNodeWithText(waiting).assertDoesNotExist()
        compose.waitUntil(10_000) { windows.containsKey("proof-two:true") }
        assertSame(originalWindow, windows["proof-two:true"])
    }

    @Test fun candidateRetiresOldDraftAndSubmissionScopesWithoutChangingNewInputOrBusyState() {
        var identity by mutableStateOf("draft")
        var showContent by mutableStateOf(true)
        val draftRelease = CompletableDeferred<Unit>()
        val oldSubmitRelease = CompletableDeferred<Unit>()
        val oldFinallyRelease = CompletableDeferred<Unit>()
        val newSubmitRelease = CompletableDeferred<Unit>()
        val draftEntered = AtomicBoolean()
        val draftCancelled = AtomicBoolean()
        val oldSubmitEntered = AtomicBoolean()
        val oldSubmitCancelled = AtomicBoolean()
        val oldFinallyFinished = AtomicBoolean()
        val newSubmitEntered = AtomicBoolean()
        val newSubmitFinished = AtomicBoolean()
        val records = AtomicInteger()
        compose.setContent {
            val currentIdentity = identity
            MaterialTheme {
                PostCheckInDialog(
                    "Private habit", metrics,
                    { _, _ ->
                        records.incrementAndGet()
                        when (currentIdentity) {
                            "old-submit" -> {
                                oldSubmitEntered.set(true)
                                try {
                                    oldSubmitRelease.await()
                                } finally {
                                    oldSubmitCancelled.set(!currentCoroutineContext().isActive)
                                    withContext(NonCancellable) { oldFinallyRelease.await() }
                                    oldFinallyFinished.set(true)
                                }
                            }
                            "new-submit" -> {
                                newSubmitEntered.set(true)
                                newSubmitRelease.await()
                                newSubmitFinished.set(true)
                            }
                            else -> fail("No submission belongs to the old draft")
                        }
                    },
                    { fail("No skip in this scenario") }, {},
                    promptIdentity = currentIdentity,
                    onDraftChange = {
                        if (currentIdentity == "draft") {
                            draftEntered.set(true)
                            try {
                                draftRelease.await()
                            } finally {
                                draftCancelled.set(!currentCoroutineContext().isActive)
                            }
                        }
                    },
                    presentation = PostCheckInDialogPresentation(showContent, "Checking current information") {}
                )
            }
        }
        fun assertFreshInput() {
            compose.onNodeWithText("Weight (kg)").assertIsEnabled()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            compose.onNode(isToggleable()).assertIsOff()
        }
        try {
            compose.onNodeWithText("Weight (kg)").performTextInput("12.5")
            compose.waitUntil(10_000) { draftEntered.get() }
            compose.runOnIdle { showContent = false }
            compose.waitUntil(10_000) { draftCancelled.get() }
            compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(0)
            compose.runOnIdle { identity = "old-submit"; showContent = true }
            assertFreshInput()
            compose.onNodeWithText("Weight (kg)").performTextInput("9")
            compose.onNode(isToggleable()).performClick()
            compose.onNodeWithText(compose.activity.getString(R.string.post_checkin_record)).performClick()
            compose.waitUntil(10_000) { oldSubmitEntered.get() }
            compose.onNodeWithText("Weight (kg)").assertIsNotEnabled()
            // A fresh proof retires the candidate's old scope even without a held frame.
            compose.runOnIdle { identity = "new-submit" }
            compose.waitUntil(10_000) { oldSubmitCancelled.get() }
            assertFreshInput()
            compose.onNodeWithText("Weight (kg)").performTextInput("7")
            compose.onNodeWithText(compose.activity.getString(R.string.post_checkin_record)).performClick()
            compose.waitUntil(10_000) { newSubmitEntered.get() }
            compose.onNodeWithText("Weight (kg)").assertIsNotEnabled()
            oldFinallyRelease.complete(Unit)
            compose.waitUntil(10_000) { oldFinallyFinished.get() }
            compose.waitForIdle()
            // The old submit's finally must only clear its retired busy state.
            compose.onNodeWithText("Weight (kg)").assertIsNotEnabled()
            compose.onNodeWithText(compose.activity.getString(R.string.post_checkin_skip)).assertIsNotEnabled()
            compose.onNode(isToggleable()).assertIsNotEnabled()
            newSubmitRelease.complete(Unit)
            compose.waitUntil(10_000) { newSubmitFinished.get() }
            compose.waitForIdle()
            compose.onNodeWithText("Weight (kg)").assertIsEnabled()
            compose.runOnIdle { assertEquals(2, records.get()) }
        } finally {
            draftRelease.complete(Unit)
            oldSubmitRelease.complete(Unit)
            oldFinallyRelease.complete(Unit)
            newSubmitRelease.complete(Unit)
        }
    }

    @Test fun savedRetryUsesOriginalValuesAndChoiceAfterRecreationWithoutRecordOrDraft() {
        val phases = kotlinx.coroutines.flow.MutableStateFlow<MetricSubmissionPhase?>(MetricSubmissionPhase.SAVED_PENDING)
        val saved = com.dayforge.ui.metrics.SavedMetricSubmission(listOf(MetricValueInput(7, 12.5, "original")), true)
        var incarnation by mutableStateOf(0)
        var continued = 0
        var dismissed = 0
        val release = CompletableDeferred<Unit>()
        compose.setContent { MaterialTheme { androidx.compose.runtime.key(incarnation) {
            PostCheckInDialog("Count", metrics,
                onRecord = { _, _ -> fail("Saved values cannot be recorded again") },
                onSkip = { fail("Later cannot skip/acknowledge the original pending command") },
                onDismiss = { dismissed++ },
                initialInputs = mapOf(7L to ("999" to "new draft")),
                onDraftChange = { fail("Continuation/close cannot flush a new draft") },
                submissionProgress = phases, savedSubmission = { saved },
                onContinue = { continued++; release.await() })
        } } }
        compose.runOnIdle { incarnation++ }
        compose.onNodeWithText("Weight (kg)").assertTextContains("12.5").assertIsNotEnabled()
        compose.onNode(isToggleable()).assertIsOn().assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.metric_submission_continue)).performClick()
        compose.waitUntil { continued == 1 }
        compose.onNodeWithText(compose.activity.getString(R.string.metric_submission_later)).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, dismissed); release.complete(Unit) }
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.metric_submission_later)).performClick()
        compose.runOnIdle { assertEquals(1, continued); assertEquals(1, dismissed) }
    }

    @Test fun rebuiltInFlightAndMissingSavedInputCannotEnableAnUnqualifiedAction() {
        val phases = kotlinx.coroutines.flow.MutableStateFlow<MetricSubmissionPhase?>(MetricSubmissionPhase.SAVED_DELIVERING)
        val saved = com.dayforge.ui.metrics.SavedMetricSubmission(listOf(MetricValueInput(7, 12.5)), false)
        var available by mutableStateOf(true)
        var incarnation by mutableStateOf(0)
        var dismissed = 0
        compose.setContent { MaterialTheme { androidx.compose.runtime.key(incarnation) {
            PostCheckInDialog("Count", metrics,
                onRecord = { _, _ -> fail("Existing operation still owns the prompt") },
                onSkip = { fail("The saved prompt cannot be skipped") }, onDismiss = { dismissed++ },
                submissionProgress = phases, savedSubmission = { if (available) saved else null },
                onContinue = { fail("Neither in-flight nor missing input may continue") })
        } } }
        for (active in listOf(MetricSubmissionPhase.SAVING, MetricSubmissionPhase.SAVED_DELIVERING)) {
            compose.runOnIdle { phases.value = active; incarnation++ }
            compose.onNodeWithText("Weight (kg)").assertIsNotEnabled()
            val closeLabel = if (active == MetricSubmissionPhase.SAVING) R.string.post_checkin_skip else R.string.metric_submission_later
            compose.onNodeWithText(compose.activity.getString(closeLabel)).assertIsNotEnabled()
            compose.onNodeWithText(compose.activity.getString(R.string.metric_submission_processing)).assertIsNotEnabled()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.runOnIdle { assertEquals(0, dismissed) }
        }
        compose.runOnIdle { phases.value = MetricSubmissionPhase.SAVED_PENDING; available = false }
        compose.onNodeWithText(compose.activity.getString(R.string.metric_submission_saved_input_missing)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.metric_submission_continue)).assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText(compose.activity.getString(R.string.metric_submission_later)).performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

}
