package com.dayforge.widget.checkin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.dayforge.widget.base.WidgetActivityTheme
import androidx.compose.runtime.*
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import com.dayforge.ui.components.PostCheckInDialog
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.dayforge.data.local.DatabaseRuntimeHost
import com.dayforge.ui.producerEpochFrameState
import kotlinx.coroutines.flow.SharingStarted

/**
 * Transparent activity to show metric prompt dialog after widget check-in.
 *
 * Shows when user checks in a habit from widget that has linked metrics
 * with promptOnComplete=true.
 */
@AndroidEntryPoint
class MetricPromptActivity : ComponentActivity() {

    @Inject internal lateinit var host: DatabaseRuntimeHost

    @Inject
    lateinit var metricCoordinator: LinkedMetricCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val habitId = intent.getLongExtra(EXTRA_HABIT_ID, -1L)
        val timerAuthority = try { com.dayforge.domain.model.TimerActionAuthority.read(intent) }
            catch (error: Exception) { finish(); return }
        val factClaim = try { com.dayforge.data.repository.WidgetFactClaim.read(intent) }
            catch (error: Exception) { finish(); return }
        val legacy = try { com.dayforge.data.repository.LegacyWidgetIdentityIntent.read(intent) }
            catch (error: Exception) { finish(); return }
        val countFollowUp = try { com.dayforge.data.repository.WidgetCountFollowUp.read(intent) }
            catch (error: Exception) { finish(); return }
        val afterTimerGoal = intent.getBooleanExtra(EXTRA_AFTER_TIMER_GOAL, false)
        val restoreCount = intent.getBooleanExtra(EXTRA_RESTORE_COUNT, false)
        if (listOfNotNull(timerAuthority, factClaim, legacy).size != 1 ||
            (legacy != null && legacy.habitId != habitId) ||
            (afterTimerGoal && timerAuthority?.sessionUuid == null) ||
            (countFollowUp != null && (timerAuthority != null || countFollowUp.completionSource != null))) { finish(); return }

        if (habitId == -1L) {
            finish()
            return
        }

        val frames = producerEpochFrameState(lifecycleScope, host, SharingStarted.Eagerly,
            metricCoordinator.postCheckInFrames, metricCoordinator::publishPromptFrame)
        setContent {
            val frame by frames.collectAsState()
            val state = frame?.value
            val ownedFrame = frame?.takeIf { original -> original.value?.let {
                it.habitId == habitId && (countFollowUp == null ||
                    (it.factPrompt?.command ?: it.habitPrompt?.command)?.reference == countFollowUp.command) &&
                    (legacy == null || it.preferenceClaim?.widgetIdentity == legacy) &&
                    (timerAuthority == null || it.timerPrompt?.source == timerAuthority) &&
                    (factClaim == null || it.factPrompt?.source == factClaim ||
                        it.oneTimePrompt?.let { prompt -> prompt.eventUuid == factClaim.completionUuid &&
                            prompt.snapshot.session == factClaim.session() } == true)
            } == true }
            var hadPrompt by remember { mutableStateOf(false) }
            LaunchedEffect(state) {
                if (state != null) {
                    hadPrompt = true
                    if (ownedFrame == null) finish()
                } else if (hadPrompt) finish()
            }

            LaunchedEffect(habitId) {
                try {
                    host.withGraph { graph ->
                    if (countFollowUp != null && !graph.countCommandPendingRepository.isPending(countFollowUp.command)) {
                        finish(); return@withGraph
                    }
                    val count = countFollowUp?.let { original -> graph.habitRepository.historicalCountResult(original.command,
                        requireNotNull(factClaim?.habitUuid ?: legacy?.habitUuid)).also {
                        check(it.recorded && it.requiresMetricFollowUp && it.sourceHabitUuid == (factClaim?.habitUuid ?: legacy?.habitUuid)) {
                            "COMMAND_FOLLOWUP_SOURCE_CHANGED"
                        }
                    } }
                    if (factClaim != null) {
                        check(factClaim.habitId == habitId)
                        metricCoordinator.showWidgetFactPrompt(factClaim, count, restorePending = restoreCount)
                    } else if (timerAuthority?.sessionUuid != null) {
                        if (afterTimerGoal) metricCoordinator.showPromptAfterTimerGoal(habitId, timerAuthority)
                        else metricCoordinator.showPromptAfterTimerStop(habitId, timerAuthority)
                    }
                    else if (timerAuthority != null) metricCoordinator.showPendingTimerWidgetPrompt(habitId, timerAuthority)
                    else metricCoordinator.showLegacyWidgetPrompt(requireNotNull(legacy), count, restorePending = restoreCount)
                    if (metricCoordinator.postCheckInState.value == null) finish()
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    report(error)
                    finish()
                }
            }

            // Effects stay outside the palette gate: retrying a theme cannot reopen/reset a prompt.
            WidgetActivityTheme(this) {
                ownedFrame?.let { originalFrame ->
                    val prompt = requireNotNull(originalFrame.value)
                    // PostCheckInDialog is already an AlertDialog, no need to wrap in Dialog
                    PostCheckInDialog(
                        habitName = prompt.habitName,
                        linkedMetrics = prompt.linkedMetrics,
                        submissionProgress = prompt.factPrompt?.submissionPhase,
                        savedSubmission = { prompt.factPrompt?.savedSubmissionForDisplay() },
                        onContinue = if (prompt.factPrompt?.submissionPhase != null) ({
                            try {
                                val fact = requireNotNull(prompt.factPrompt)
                                val saved = requireNotNull(fact.savedSubmissionForDisplay())
                                host.withSourceGraph(originalFrame.publication) {
                                    if (metricCoordinator.continueSavedMetricDelivery(habitId, fact, originalFrame)) {
                                        com.dayforge.domain.service.afterCommittedAction {
                                            if (saved.neverAskAgain) metricCoordinator.setNeverAskAgain(habitId, true,
                                                prompt.oneTimePrompt?.eventUuid, prompt.timerPrompt, prompt.factPrompt,
                                                expectedPreferenceClaim = prompt.preferenceClaim, frame = originalFrame)
                                            finish()
                                        }
                                    }
                                }
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                report(error)
                            }
                        }) else null,
                        promptIdentity = prompt.restoredChildId ?: prompt.oneTimePrompt?.eventUuid,
                        missingTargets = prompt.oneTimePrompt?.entries?.filterNot { it.available }?.map { it.name }.orEmpty(),
                        onRefreshMetadata = if (prompt.oneTimePrompt != null) ({
                            run {
                                try { host.withSourceGraph(originalFrame.publication) { metricCoordinator.refreshPrompt(originalFrame) } }
                                catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    report(error)
                                }
                            }
                        }) else null,
                        initialInputs = if (prompt.restoredChildId != null) prompt.restoredInputs else prompt.oneTimePrompt?.entries?.associate { it.metricId to (it.input to it.note) }.orEmpty(),
                        onDraftChange = { inputs -> prompt.oneTimePrompt?.let { ticket ->
                            host.withSourceGraph(originalFrame.publication) {
                                metricCoordinator.savePromptDraft(ticket.eventUuid, inputs, originalFrame)
                            }
                        } },
                        onRecord = { values, neverAskAgain ->
                            run {
                                try {
                                    host.withSourceGraph(originalFrame.publication) {
                                    if (!metricCoordinator.recordMetricValues(habitId, values, prompt.oneTimePrompt?.eventUuid, prompt.timerPrompt, prompt.factPrompt,
                                            expectedPreferenceClaim = prompt.preferenceClaim, frame = originalFrame,
                                            neverAskAgain = neverAskAgain)) return@withSourceGraph
                                    com.dayforge.domain.service.afterCommittedAction {
                                        if (neverAskAgain) {
                                            metricCoordinator.setNeverAskAgain(habitId, true, prompt.oneTimePrompt?.eventUuid, prompt.timerPrompt, prompt.factPrompt,
                                                expectedPreferenceClaim = prompt.preferenceClaim, frame = originalFrame)
                                        }
                                        finish()
                                    }
                                    }
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    report(error)
                                }
                            }
                        },
                        onSkip = { neverAskAgain ->
                            run {
                                try {
                                    host.withSourceGraph(originalFrame.publication) {
                                    if (neverAskAgain) {
                                        metricCoordinator.setNeverAskAgain(habitId, true, prompt.oneTimePrompt?.eventUuid, prompt.timerPrompt, prompt.factPrompt,
                                            expectedPreferenceClaim = prompt.preferenceClaim, frame = originalFrame)
                                    }
                                    metricCoordinator.skipPrompt(habitId, prompt.oneTimePrompt?.eventUuid, prompt.timerPrompt, prompt.factPrompt,
                                        expectedPreferenceClaim = prompt.preferenceClaim, frame = originalFrame)
                                    finish()
                                    }
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    report(error)
                                }
                            }
                        },
                        onDismiss = {
                            try { host.withSourceGraph(originalFrame.publication) {
                                metricCoordinator.closePrompt(prompt, originalFrame)
                            } } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                report(error)
                            } finally { finish() }
                        }
                    )
                }
            }
        }
    }

    private fun report(error: Exception) {
        Log.e(TAG, "Failed to update linked metric prompt", error)
        Toast.makeText(this, com.dayforge.ui.actionFailureMessage(this, error),
            Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "MetricPromptActivity"
        const val EXTRA_HABIT_ID = "habitId"
        const val EXTRA_HABIT_NAME = "habitName"
        private const val EXTRA_AFTER_TIMER_GOAL = "com.dayforge.timer.after_goal"
        private const val EXTRA_RESTORE_COUNT = "com.dayforge.count.restore_form"

        /**
         * Create intent to start this activity.
         */
        fun createIntent(context: android.content.Context, habitId: Long, habitName: String,
            timerAuthority: com.dayforge.domain.model.TimerActionAuthority? = null,
            factClaim: com.dayforge.data.repository.WidgetFactClaim? = null,
            legacyIdentity: com.dayforge.data.repository.LegacyWidgetRestartClaim? = null,
            afterTimerGoal: Boolean = false,
            countCommand: com.dayforge.data.repository.CountCommandReference? = null,
            restoreCount: Boolean = false): Intent {
            return Intent(context, MetricPromptActivity::class.java).apply {
                putExtra(EXTRA_HABIT_ID, habitId)
                putExtra(EXTRA_HABIT_NAME, habitName)
                putExtra(EXTRA_AFTER_TIMER_GOAL, afterTimerGoal)
                putExtra(EXTRA_RESTORE_COUNT, restoreCount)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                timerAuthority?.attach(this)
                factClaim?.attach(this)
                legacyIdentity?.let { com.dayforge.data.repository.LegacyWidgetIdentityIntent.attach(this, it) }
                countCommand?.let { com.dayforge.data.repository.WidgetCountFollowUp(it).attach(this) }
            }
        }
    }
}
