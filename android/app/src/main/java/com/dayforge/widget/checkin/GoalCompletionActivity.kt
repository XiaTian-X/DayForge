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
import com.dayforge.R
import com.dayforge.data.repository.HabitRepository
import com.dayforge.data.repository.WidgetFactClaim
import com.dayforge.data.repository.WidgetFactReader
import com.dayforge.data.repository.LegacyWidgetFactWorkflow
import com.dayforge.data.repository.LegacyWidgetIdentityIntent
import com.dayforge.data.repository.LegacyWidgetRestartClaim
import com.dayforge.data.repository.NextTimerWriter
import com.dayforge.data.repository.TimerCompletionFollowUpClaim
import com.dayforge.ui.components.GoalCompletionDialog
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.dayforge.data.local.DatabaseRuntimeHost
import com.dayforge.data.local.TokenManager
import javax.inject.Inject

/**
 * Transparent activity to show goal completion dialog after widget check-in.
 *
 * Per TARGET-08: Shows when user checks in from widget and reaches targetCycles.
 * Allows user to:
 * - Confirm completion (sets habit isActive = false)
 * - Continue tracking (dismisses dialog, habit stays active)
 *
 * Following MetricPromptActivity pattern for widget-to-Compose dialog bridge.
 */
@AndroidEntryPoint
class GoalCompletionActivity : ComponentActivity() {

    @Inject internal lateinit var host: DatabaseRuntimeHost
    @Inject lateinit var tokens: TokenManager
    private val factReader get() = host.boundGraph().widgetFactReader
    private val habitRepository get() = host.boundGraph().habitRepository
    private val legacyWidgets get() = host.boundGraph().legacyWidgetFactWorkflow
    private val timerWriter get() = host.boundGraph().nextTimerWriter
    private var actionPending = false
    private data class DialogDisplay(val name: String, val progress: Int, val target: Int,
        val legacyIdentity: LegacyWidgetRestartClaim? = null,
        val timerCompletion: TimerCompletionFollowUpClaim? = null,
        val publication: com.dayforge.data.local.DatabaseEpochPublication,
        val command: com.dayforge.data.repository.CountCommandReceipt? = null,
        val completionSource: WidgetFactClaim? = null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val habitId = intent.getLongExtra(EXTRA_HABIT_ID, -1L)
        val claim = try { WidgetFactClaim.read(intent) } catch (error: Exception) { finish(); return }
        val legacy = try { LegacyWidgetIdentityIntent.read(intent) } catch (error: Exception) { finish(); return }
        val timer = try { TimerCompletionFollowUpClaim.read(intent) } catch (error: Exception) { finish(); return }
        val countFollowUp = try { com.dayforge.data.repository.WidgetCountFollowUp.read(intent) }
            catch (error: Exception) { finish(); return }
        if (listOfNotNull(claim, legacy, timer).size != 1 || (legacy != null && legacy.habitId != habitId) ||
            (timer != null && timer.habitId != habitId) ||
            (countFollowUp != null && (timer != null || legacy != null && countFollowUp.completionSource != null))) { finish(); return }
        if (claim != null && claim.habitId != habitId) { finish(); return }

        if (habitId == -1L) {
            finish()
            return
        }
        if (claim != null) lifecycleScope.launch {
            tokens.coreAccessChanges.catch { finish() }.collect { access ->
                if (access == null || access.session != claim.session() || access.capturedDeviceId != claim.deviceId) finish()
            }
        }
        if (legacy != null) lifecycleScope.launch {
            tokens.factAccessChanges.catch { finish() }.collect {
                try { host.withGraph { legacyWidgets.requireCurrent(legacy) } }
                catch (error: Exception) { if (error is CancellationException) throw error; finish() }
            }
        }
        if (timer != null) lifecycleScope.launch {
            tokens.coreAccessChanges.catch { finish() }.collect { access ->
                if (access == null || access.session != timer.source.session() || access.capturedDeviceId != timer.source.deviceId) finish()
            }
        }

        val display = mutableStateOf<DialogDisplay?>(null)
        setContent {
            WidgetActivityTheme(this) {
                display.value?.let { value ->
                    GoalCompletionDialog(
                        habitName = value.name,
                        progress = value.progress,
                        target = value.target,
                        onConfirm = { submitGoal(claim, value.legacyIdentity, value.timerCompletion, value.publication, value.command, value.completionSource, true) },
                        onDismiss = { submitGoal(claim, value.legacyIdentity, value.timerCompletion, value.publication, value.command, value.completionSource, false) }
                    )
                }
            }
        }
        // Activity owns the read, not Compose's frame/effect continuation. In particular,
        // test frame interceptors must not redirect Room's transaction thread. Cancellation
        // remains structured; publish one complete display on Main after source validation.
        lifecycleScope.launch {
            try {
                host.withGraph {
                val publication = host.currentPublication()
                val command = countFollowUp?.let { captured -> habitRepository.historicalCountResult(captured.command,
                    requireNotNull(claim?.habitUuid ?: legacy?.habitUuid)).also { receipt ->
                    if (claim != null && receipt.requiresMetricFollowUp) {
                        val source = requireNotNull(captured.completionSource) { "COMMAND_FOLLOWUP_SOURCE_CHANGED" }
                        check(receipt.factUuid == source.completionUuid && source.habitUuid == claim.habitUuid) { "COMMAND_FOLLOWUP_SOURCE_CHANGED" }
                    }
                } }
                val value = withContext(Dispatchers.IO) {
                    if (timer != null) {
                        val view = timerWriter.goalDisplay(timer)
                        DialogDisplay(view.habit.name, view.progress, requireNotNull(view.habit.targetCycles), timerCompletion = timer, publication = publication)
                    } else if (legacy != null) {
                        val view = if (command == null) legacyWidgets.goalDisplay(legacy) else legacyWidgets.goalDisplayForCommand(legacy, command)
                        DialogDisplay(view.habit.name, view.progress, requireNotNull(view.habit.targetCycles), view.identity, publication = publication)
                    } else {
                        val view = if (command == null) factReader.goalDisplay(requireNotNull(claim)) else factReader.goalDisplayForCommand(requireNotNull(claim), command)
                        DialogDisplay(view.habit.name, view.progress, requireNotNull(view.habit.targetCycles), publication = publication)
                    }
                }.copy(command = command, completionSource = countFollowUp?.completionSource)
                var delivered = false
                if (value.timerCompletion != null) timerWriter.publishGoal(value.timerCompletion) {
                    delivered = publication.publish { display.value = value }
                } else if (value.legacyIdentity != null) {
                    if (command == null) legacyWidgets.publish(value.legacyIdentity) { delivered = publication.publish { display.value = value } }
                    else legacyWidgets.publishGoalForCommand(value.legacyIdentity, command) { delivered = publication.publish { display.value = value } }
                } else if (command != null) factReader.publishGoalForCommand(requireNotNull(claim), command) {
                    delivered = publication.publish { display.value = value }
                } else delivered = publication.publish { display.value = value }
                if (delivered && command != null && !command.requiresMetricFollowUp)
                    com.dayforge.domain.service.afterCommittedAction { habitRepository.acknowledgeCountResultDelivery(command) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w("GoalCompletion", "Goal display source unavailable", error)
                val message = if (error is com.dayforge.domain.service.CommittedActionRefreshFailed)
                    com.dayforge.ui.actionFailureMessage(this@GoalCompletionActivity, error)
                    else getString(R.string.goal_completion_update_error)
                Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun submitGoal(claim: WidgetFactClaim?, legacy: LegacyWidgetRestartClaim?, timer: TimerCompletionFollowUpClaim?,
        publication: com.dayforge.data.local.DatabaseEpochPublication,
        command: com.dayforge.data.repository.CountCommandReceipt?, completionSource: WidgetFactClaim?, complete: Boolean) {
        if (actionPending) return
        actionPending = true
        lifecycleScope.launch {
            try {
                host.withSourceGraph(publication) {
                withContext(Dispatchers.IO) {
                    if (timer != null) habitRepository.applyTimerGoal(applicationContext, timer, complete)
                    else if (claim != null) {
                        if (command == null) habitRepository.applyWidgetGoal(applicationContext, claim, complete)
                        else habitRepository.applyWidgetGoalWithCommand(applicationContext, claim, complete, command)
                    } else {
                        if (command == null) legacyWidgets.applyGoal(applicationContext, requireNotNull(legacy), complete)
                        else legacyWidgets.applyGoalWithCommand(applicationContext, requireNotNull(legacy), complete, command)
                    }
                }
                if (complete) Toast.makeText(this@GoalCompletionActivity, getString(R.string.goal_completion_toast), Toast.LENGTH_SHORT).show()
                followUp(claim, legacy, timer, command, completionSource)
                }
                finish()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Toast.makeText(this@GoalCompletionActivity, error.message ?: getString(R.string.goal_completion_update_error), Toast.LENGTH_LONG).show()
                // A stale confirmation cannot become writable again by dismissing the same dialog.
                // Close it so Back/Continue cannot trap the user on an expired, non-writable screen.
                com.dayforge.widget.WidgetRefreshScheduler.request(this@GoalCompletionActivity)
                finish()
            } finally { actionPending = false }
        }
    }

    private suspend fun followUp(claim: WidgetFactClaim?, legacy: LegacyWidgetRestartClaim?, timer: TimerCompletionFollowUpClaim?,
        command: com.dayforge.data.repository.CountCommandReceipt?, completionSource: WidgetFactClaim?) {
        try {
            if (command != null) {
                if (!command.requiresMetricFollowUp) return
                if (claim != null) {
                    val source = requireNotNull(factReader.afterCountCommand(requireNotNull(completionSource), command.factUuid).completionSource)
                    startActivity(MetricPromptActivity.createIntent(this, source.habit.id, source.habit.name,
                        factClaim = source.claim, countCommand = command.reference, restoreCount = intent.getBooleanExtra(EXTRA_RESTORE_COUNT, false)))
                } else {
                    legacyWidgets.publish(requireNotNull(legacy)) { habit ->
                        startActivity(MetricPromptActivity.createIntent(this, habit.id, habit.name,
                            legacyIdentity = legacy, countCommand = command.reference, restoreCount = intent.getBooleanExtra(EXTRA_RESTORE_COUNT, false)))
                    }
                }
            } else if (timer != null) {
                startActivity(MetricPromptActivity.createIntent(this, timer.habitId, "", timerAuthority = timer.source,
                    afterTimerGoal = true))
            } else if (claim != null) {
                val current = factReader.afterWrite(claim)
                startActivity(MetricPromptActivity.createIntent(this, current.habit.id, current.habit.name, factClaim = current.claim))
            } else com.dayforge.widget.base.MetricPromptHelper.checkAndShowMetricPrompt(this,
                requireNotNull(legacy).habitId, legacy)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Toast.makeText(this, getString(R.string.metric_error_record_failed, error.message.orEmpty()), Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_HABIT_ID = "habitId"
        const val EXTRA_HABIT_NAME = "habitName"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_TARGET = "target"
        private const val EXTRA_RESTORE_COUNT = "com.dayforge.count.restore_form"

        /**
         * Create intent to start this activity.
         *
         * @param context Context for starting activity
         * @param habitId The ID of the habit that reached its goal
         * @param habitName The name of the habit (for dialog display)
         * @param progress The current progress (distinct days count)
         * @param target The target cycles (habit.targetCycles)
         * @return Intent to start GoalCompletionActivity
         */
        fun createIntent(
            context: Context,
            habitId: Long,
            habitName: String,
            progress: Int,
            target: Int,
            claim: WidgetFactClaim? = null,
            legacyIdentity: LegacyWidgetRestartClaim? = null,
            timerCompletion: TimerCompletionFollowUpClaim? = null,
            countCommand: com.dayforge.data.repository.CountCommandReference? = null,
            countCompletionSource: WidgetFactClaim? = null,
            restoreCount: Boolean = false
        ): Intent {
            return Intent(context, GoalCompletionActivity::class.java).apply {
                putExtra(EXTRA_HABIT_ID, habitId)
                putExtra(EXTRA_HABIT_NAME, habitName)
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_TARGET, target)
                putExtra(EXTRA_RESTORE_COUNT, restoreCount)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                claim?.attach(this)
                legacyIdentity?.let { LegacyWidgetIdentityIntent.attach(this, it) }
                timerCompletion?.attach(this)
                require(countCommand != null || countCompletionSource == null)
                countCommand?.let { com.dayforge.data.repository.WidgetCountFollowUp(it, countCompletionSource).attach(this) }
            }
        }
    }
}
