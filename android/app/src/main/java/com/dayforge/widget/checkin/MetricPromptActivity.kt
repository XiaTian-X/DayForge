package com.dayforge.widget.checkin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import com.dayforge.data.local.PreferencesManager
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import com.dayforge.ui.components.PostCheckInDialog
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Transparent activity to show metric prompt dialog after widget check-in.
 *
 * Shows when user checks in a habit from widget that has linked metrics
 * with promptOnComplete=true.
 */
@AndroidEntryPoint
class MetricPromptActivity : ComponentActivity() {

    @Inject
    lateinit var metricCoordinator: LinkedMetricCoordinator

    @Inject
    lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var habits: com.dayforge.data.repository.HabitRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val habitId = intent.getLongExtra(EXTRA_HABIT_ID, -1L)
        val habitName = intent.getStringExtra(EXTRA_HABIT_NAME) ?: "Habit"
        val timerAuthority = try { com.dayforge.domain.model.TimerActionAuthority.read(intent) }
            catch (error: Exception) { finish(); return }
        val factClaim = try { com.dayforge.data.repository.WidgetFactClaim.read(intent) }
            catch (error: Exception) { finish(); return }
        if (timerAuthority != null && factClaim != null) { finish(); return }

        if (habitId == -1L) {
            finish()
            return
        }

        setContent {
            MaterialTheme {
                val state by metricCoordinator.postCheckInState.collectAsState()
                var hadPrompt by remember { mutableStateOf(false) }
                LaunchedEffect(state) {
                    if (state != null) {
                        hadPrompt = true
                        if (state?.habitId != habitId) finish()
                    } else if (hadPrompt) finish()
                }

                LaunchedEffect(habitId) {
                    try {
                        if (factClaim != null) {
                            check(factClaim.habitId == habitId)
                            metricCoordinator.showWidgetFactPrompt(factClaim)
                        } else if (timerAuthority?.sessionUuid != null) metricCoordinator.showPromptAfterTimerStop(habitId, timerAuthority)
                        else if (timerAuthority != null) metricCoordinator.showPendingTimerWidgetPrompt(habitId, timerAuthority)
                        else {
                            check(habits.getHabitById(habitId)?.appearance == null) { "FACT_WIDGET_CLAIM_REQUIRED" }
                            metricCoordinator.showPromptIfNeeded(habitId, habitName)
                        }
                        if (metricCoordinator.postCheckInState.value == null) finish()
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        report(error)
                        finish()
                    }
                }

                state?.takeIf { it.habitId == habitId }?.let { prompt ->
                    // PostCheckInDialog is already an AlertDialog, no need to wrap in Dialog
                    PostCheckInDialog(
                        habitName = prompt.habitName,
                        linkedMetrics = prompt.linkedMetrics,
                        promptIdentity = prompt.oneTimePrompt?.eventUuid,
                        missingTargets = prompt.oneTimePrompt?.entries?.filterNot { it.available }?.map { it.name }.orEmpty(),
                        onRefreshMetadata = if (prompt.oneTimePrompt != null) ({
                            lifecycleScope.launch {
                                try { metricCoordinator.refreshPrompt() }
                                catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    report(error)
                                }
                            }
                        }) else null,
                        initialInputs = prompt.oneTimePrompt?.entries?.associate { it.metricId to (it.input to it.note) }.orEmpty(),
                        onDraftChange = { inputs -> prompt.oneTimePrompt?.let { ticket ->
                            lifecycleScope.launch {
                                try { metricCoordinator.savePromptDraft(ticket.eventUuid, inputs) }
                                catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    report(error)
                                }
                            }
                        } },
                        onRecord = { values, neverAskAgain ->
                            lifecycleScope.launch {
                                try {
                                    if (!metricCoordinator.recordMetricValues(habitId, values, prompt.oneTimePrompt?.eventUuid, prompt.timerPrompt, prompt.factPrompt)) return@launch
                                    if (neverAskAgain) {
                                        metricCoordinator.setNeverAskAgain(habitId, true, prompt.oneTimePrompt?.eventUuid, prompt.timerPrompt, prompt.factPrompt)
                                    }
                                    finish()
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    report(error)
                                }
                            }
                        },
                        onSkip = { neverAskAgain ->
                            lifecycleScope.launch {
                                try {
                                    if (neverAskAgain) {
                                        metricCoordinator.setNeverAskAgain(habitId, true, prompt.oneTimePrompt?.eventUuid, prompt.timerPrompt, prompt.factPrompt)
                                    }
                                    metricCoordinator.skipPrompt(habitId, prompt.oneTimePrompt?.eventUuid, prompt.timerPrompt, prompt.factPrompt)
                                    if (prompt.timerPrompt == null && prompt.factPrompt == null && prompt.oneTimePrompt == null)
                                        preferencesManager.removePendingMetricHabit(habitId)
                                    finish()
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    report(error)
                                }
                            }
                        },
                        onDismiss = { lifecycleScope.launch { metricCoordinator.closePrompt(); finish() } }
                    )
                }
            }
        }
    }

    private fun report(error: Exception) {
        Log.e(TAG, "Failed to update linked metric prompt", error)
        Toast.makeText(this, getString(com.dayforge.R.string.metric_error_record_failed, error.message.orEmpty()),
            Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "MetricPromptActivity"
        const val EXTRA_HABIT_ID = "habitId"
        const val EXTRA_HABIT_NAME = "habitName"

        /**
         * Create intent to start this activity.
         */
        fun createIntent(context: android.content.Context, habitId: Long, habitName: String,
            timerAuthority: com.dayforge.domain.model.TimerActionAuthority? = null,
            factClaim: com.dayforge.data.repository.WidgetFactClaim? = null): Intent {
            return Intent(context, MetricPromptActivity::class.java).apply {
                putExtra(EXTRA_HABIT_ID, habitId)
                putExtra(EXTRA_HABIT_NAME, habitName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                timerAuthority?.attach(this)
                factClaim?.attach(this)
            }
        }
    }
}
