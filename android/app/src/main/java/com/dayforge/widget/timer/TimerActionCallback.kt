package com.dayforge.widget.timer

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.domain.service.TimerServiceController
import com.dayforge.domain.service.TimerElapsedCalculator
import com.dayforge.util.DateTimeUtils
import com.dayforge.widget.base.MetricPromptHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import javax.inject.Inject

/**
 * Action callback for timer widget button taps.
 * Routes actions through TimerService for timer control.
 *
 * Supported actions:
 * - "start": Start the timer
 * - "pause": Pause the running timer
 * - "resume": Resume a paused timer
 * - "stop": Stop the timer and save the session
 */
class TimerActionCallback : ActionCallback {

    companion object {
        private const val TAG = "TimerActionCallback"
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val habitId = parameters[ActionParameters.Key<Long>("habitId")] ?: return
        val action = parameters[ActionParameters.Key<String>("action")] ?: return
        val targetMinutes = parameters[ActionParameters.Key<Int>("targetMinutes")] ?: 0

        Log.d(TAG, "onAction: habitId=$habitId, action=$action, targetMinutes=$targetMinutes")

        when (action) {
            "start" -> {
                val database = HabitDatabaseProvider.getInstance(context.applicationContext)

                // Check if already completed today
                val habit = database.habitDao().getHabitById(habitId)
                val todayStart = DateTimeUtils.startOfDayMillis()
                val todayEnd = DateTimeUtils.startOfNextDayMillis(todayStart)
                val completedSeconds = database.timeLogDao().getCompletedDurationSecondsForDate(
                    habitId, java.time.LocalDate.now().toString(), todayStart, todayEnd
                )
                val targetSeconds = (habit?.targetValue ?: 0) * 60
                val alreadyCompleted = completedSeconds >= targetSeconds

                if (alreadyCompleted) {
                    // Already completed today, don't start new timer
                    Log.d(TAG, "start: habit $habitId already completed today")
                    return
                }

                // Check if there's an active timer for a different habit
                val activeLog = database.timeLogDao().getActiveTimeLog()

                if (activeLog != null && activeLog.habitId != habitId) {
                    // Show confirmation dialog
                    showConfirmationDialog(context, habitId, targetMinutes, glanceId)
                } else {
                    // No conflict, start directly
                    TimerServiceController.startTimer(context, habitId, targetMinutes, habit?.isCountdown ?: false)
                    TimerWidget.refreshWidgetData(context, glanceId, habitId)
                    TimerWidget().update(context, glanceId)
                }
            }
            "pause" -> {
                TimerServiceController.pauseTimer(context, habitId, targetMinutes)
                // Immediately update widget UI
                TimerWidget.refreshWidgetData(context, glanceId, habitId)
                TimerWidget().update(context, glanceId)
            }
            "resume" -> {
                TimerServiceController.resumeTimer(context, habitId, targetMinutes)
                // Immediately update widget UI
                TimerWidget.refreshWidgetData(context, glanceId, habitId)
                TimerWidget().update(context, glanceId)
            }
            "stop" -> {
                // Check if this is an incomplete session (countdown or countup)
                val database = HabitDatabaseProvider.getInstance(context.applicationContext)
                val habit = database.habitDao().getHabitById(habitId)
                val activeLog = database.timeLogDao().getActiveTimeLog()

                val targetSeconds = (habit?.targetValue ?: 0) * 60
                val isCountdown = habit?.isCountdown ?: false

                // Calculate safe duration limit (same logic as TimerService)
                // Absolute maximum: 24 hours (prevent runaway values from system date changes)
                val absoluteMaxSeconds = 24 * 60 * 60
                val safeDurationLimit = if (isCountdown) {
                    targetSeconds  // Countdown: cannot exceed target
                } else {
                    if (targetSeconds > 0) targetSeconds * 3 else absoluteMaxSeconds  // Countup: threshold or absolute max
                }

                // Clamp elapsed seconds to safe limit
                val rawElapsedSeconds = if (activeLog != null && activeLog.habitId == habitId) {
                    TimerElapsedCalculator.elapsedSeconds(activeLog, context)
                } else 0
                val elapsedSeconds = rawElapsedSeconds.coerceAtMost(safeDurationLimit)

                // Countdown: incomplete if remaining > 0
                // Countup: incomplete if elapsed < target
                val isIncomplete = if (isCountdown) {
                    (targetSeconds - elapsedSeconds) > 0
                } else {
                    elapsedSeconds < targetSeconds
                }

                if (isIncomplete && activeLog != null && activeLog.habitId == habitId) {
                    // Per TIMER-08: Show discard confirmation dialog for incomplete sessions
                    val remainingOrElapsedSeconds = if (isCountdown) {
                        targetSeconds - elapsedSeconds  // remaining for countdown
                    } else {
                        elapsedSeconds  // elapsed for countup
                    }
                    showDiscardConfirmation(
                        context,
                        habitId,
                        targetMinutes,
                        glanceId,
                        remainingOrElapsedSeconds,
                        isCountdown
                    )
                } else {
                    // Normal stop (completed or not active)
                    TimerServiceController.stopTimer(context, habitId, targetMinutes)
                    // Stop updates database asynchronously, wait and refresh
                    delay(150)
                    TimerWidget.refreshWidgetData(context, glanceId, habitId)
                    TimerWidget().update(context, glanceId)

                    // Check and show metric prompt after stopping timer
                    MetricPromptHelper.checkAndShowMetricPrompt(context, habitId)
                }
            }
        }
    }

    /**
     * Show the discard confirmation dialog for incomplete sessions.
     * Per TIMER-08: Shows dialog asking user to confirm discard or continue timing.
     */
    private suspend fun showDiscardConfirmation(
        context: Context,
        habitId: Long,
        targetMinutes: Int,
        glanceId: GlanceId,
        seconds: Int,
        isCountdown: Boolean
    ) {
        val intent = CountdownDiscardActivity.createIntent(
            context,
            habitId,
            targetMinutes,
            seconds,
            isCountdown
        )
        context.startActivity(intent)
    }

    private suspend fun showConfirmationDialog(
        context: Context,
        habitId: Long,
        targetMinutes: Int,
        glanceId: GlanceId
    ) {
        val appWidgetId = try {
            GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        } catch (e: Exception) {
            AppWidgetManager.INVALID_APPWIDGET_ID
        }

        val intent = Intent(context, TimerConfirmationActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(TimerConfirmationActivity.EXTRA_HABIT_ID, habitId)
            putExtra(TimerConfirmationActivity.EXTRA_TARGET_MINUTES, targetMinutes)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        context.startActivity(intent)
    }
}
