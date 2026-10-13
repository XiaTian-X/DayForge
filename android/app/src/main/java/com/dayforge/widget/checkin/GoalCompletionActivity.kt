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

    @Inject
    lateinit var factReader: WidgetFactReader

    @Inject
    lateinit var habitRepository: HabitRepository
    @Inject lateinit var legacyWidgets: LegacyWidgetFactWorkflow
    @Inject lateinit var timerWriter: NextTimerWriter
    private var actionPending = false
    private data class DialogDisplay(val name: String, val progress: Int, val target: Int,
        val legacyIdentity: LegacyWidgetRestartClaim? = null,
        val timerCompletion: TimerCompletionFollowUpClaim? = null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val habitId = intent.getLongExtra(EXTRA_HABIT_ID, -1L)
        val claim = try { WidgetFactClaim.read(intent) } catch (error: Exception) { finish(); return }
        val legacy = try { LegacyWidgetIdentityIntent.read(intent) } catch (error: Exception) { finish(); return }
        val timer = try { TimerCompletionFollowUpClaim.read(intent) } catch (error: Exception) { finish(); return }
        if (listOfNotNull(claim, legacy, timer).size != 1 || (legacy != null && legacy.habitId != habitId) ||
            (timer != null && timer.habitId != habitId)) { finish(); return }
        if (claim != null && claim.habitId != habitId) { finish(); return }

        if (habitId == -1L) {
            finish()
            return
        }
        if (claim != null) lifecycleScope.launch {
            factReader.accessChanges.catch { finish() }.collect { access ->
                if (access == null || access.session != claim.session() || access.capturedDeviceId != claim.deviceId) finish()
            }
        }
        if (legacy != null) lifecycleScope.launch {
            legacyWidgets.accessChanges.catch { finish() }.collect {
                try { legacyWidgets.requireCurrent(legacy) }
                catch (error: Exception) { if (error is CancellationException) throw error; finish() }
            }
        }
        if (timer != null) lifecycleScope.launch {
            timerWriter.accessChanges.catch { finish() }.collect { access ->
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
                        onConfirm = { submitGoal(claim, value.legacyIdentity, value.timerCompletion, true) },
                        onDismiss = { submitGoal(claim, value.legacyIdentity, value.timerCompletion, false) }
                    )
                }
            }
        }
        // Activity owns the read, not Compose's frame/effect continuation. In particular,
        // test frame interceptors must not redirect Room's transaction thread. Cancellation
        // remains structured; publish one complete display on Main after source validation.
        lifecycleScope.launch {
            try {
                val value = withContext(Dispatchers.IO) {
                    if (timer != null) {
                        val view = timerWriter.goalDisplay(timer)
                        DialogDisplay(view.habit.name, view.progress, requireNotNull(view.habit.targetCycles), timerCompletion = timer)
                    } else if (legacy != null) {
                        val view = legacyWidgets.goalDisplay(legacy)
                        DialogDisplay(view.habit.name, view.progress, requireNotNull(view.habit.targetCycles), view.identity)
                    } else {
                        val view = factReader.goalDisplay(requireNotNull(claim))
                        DialogDisplay(view.habit.name, view.progress, requireNotNull(view.habit.targetCycles))
                    }
                }
                if (value.timerCompletion != null) timerWriter.publishGoal(value.timerCompletion) { display.value = value }
                else if (value.legacyIdentity != null) legacyWidgets.publish(value.legacyIdentity) { display.value = value }
                else display.value = value
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w("GoalCompletion", "Goal display source unavailable", error)
                Toast.makeText(applicationContext, getString(R.string.goal_completion_update_error), Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun submitGoal(claim: WidgetFactClaim?, legacy: LegacyWidgetRestartClaim?, timer: TimerCompletionFollowUpClaim?, complete: Boolean) {
        if (actionPending) return
        actionPending = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (timer != null) habitRepository.applyTimerGoal(applicationContext, timer, complete)
                    else if (claim != null) habitRepository.applyWidgetGoal(applicationContext, claim, complete)
                    else legacyWidgets.applyGoal(applicationContext, requireNotNull(legacy), complete)
                }
                if (complete) Toast.makeText(this@GoalCompletionActivity, getString(R.string.goal_completion_toast), Toast.LENGTH_SHORT).show()
                followUp(claim, legacy, timer)
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

    private suspend fun followUp(claim: WidgetFactClaim?, legacy: LegacyWidgetRestartClaim?, timer: TimerCompletionFollowUpClaim?) {
        try {
            if (timer != null) {
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
            timerCompletion: TimerCompletionFollowUpClaim? = null
        ): Intent {
            return Intent(context, GoalCompletionActivity::class.java).apply {
                putExtra(EXTRA_HABIT_ID, habitId)
                putExtra(EXTRA_HABIT_NAME, habitName)
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_TARGET, target)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                claim?.attach(this)
                legacyIdentity?.let { LegacyWidgetIdentityIntent.attach(this, it) }
                timerCompletion?.attach(this)
            }
        }
    }
}
