package com.dayforge.widget.checkin

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.model.CheckInResult
import com.dayforge.data.repository.HabitRepository
import com.dayforge.domain.service.CheckInService
import com.dayforge.di.WidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors
import com.dayforge.util.DateTimeUtils
import com.dayforge.widget.WidgetUpdateReceiver
import com.dayforge.widget.base.MetricPromptHelper

/**
 * Action callback for check-in button tap.
 * Routes actions through CheckInService for unified logic.
 *
 * Supported actions:
 * - "toggle": Check-in or undo (for CHECK_IN habits)
 * - "increment": Add 1 to count (for COUNTING habits)
 * - "decrement": Subtract 1 from count, min 0 (for COUNTING habits)
 */
class CheckInActionCallback : ActionCallback {

    companion object {
        private const val TAG = "CheckInActionCallback"
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val habitId = parameters[ActionParameters.Key<Long>("habitId")] ?: return
        val action = parameters[ActionParameters.Key<String>("action")] ?: "toggle"

        Log.d(TAG, "onAction: habitId=$habitId, action=$action")

        // Create CheckInService manually (widgets don't use Hilt)
        val database = HabitDatabaseProvider.getInstance(context)
        val completionDao = database.completionDao()
        val habitDao = database.habitDao()
        val timeLogDao = database.timeLogDao()
        val repository = runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetEntryPoint::class.java
            ).habitRepository()
        }.getOrElse {
            // Plain Robolectric/unit environments do not own a Hilt component.
            HabitRepository(habitDao, completionDao, timeLogDao, database)
        }
        val service = CheckInService(repository, completionDao, timeLogDao)

        // Track if this was a check-in (not undo)
        var wasCheckIn = false

        // Route action to appropriate service method
        when (action) {
            "toggle" -> {
                val result = service.toggleCheckIn(context, habitId)
                if (result is CheckInResult.Success) {
                    wasCheckIn = result.completed
                    Log.d(TAG, "toggle result: wasCheckIn=$wasCheckIn, progress=${result.progress}, goalReached=${result.goalReached}")
                    // Goal completion dialog handling (TARGET-08)
                    if (result.goalReached) {
                        showGoalCompletionDialog(context, habitId, result.progress, database)
                    }
                } else {
                    Log.d(TAG, "toggle result: Error - ${(result as CheckInResult.Error).message}")
                }
            }
            "increment" -> {
                val result = service.incrementCount(context, habitId)
                wasCheckIn = true // Increment is always a check-in action
                Log.d(TAG, "increment: wasCheckIn=true, goalReached=${(result as? CheckInResult.Success)?.goalReached}")
                // Goal completion dialog handling (TARGET-08)
                if (result is CheckInResult.Success && result.goalReached) {
                    showGoalCompletionDialog(context, habitId, result.progress, database)
                }
            }
            "decrement" -> {
                // For countdown mode: - button means "record one done" (increment completedToday)
                // For countup mode: - button means "subtract one" (decrement completedToday)
                val habit = database.habitDao().getHabitById(habitId)
                if (habit?.isCountdown == true) {
                    // Countdown mode: check if remaining is already 0
                    val startOfDay = DateTimeUtils.startOfDayMillis()
                    val todayCount = database.completionDao()
                        .getCompletionsInRange(habitId, startOfDay, startOfDay + DateTimeUtils.MILLIS_PER_DAY)
                        .sumOf { it.value }
                    val remaining = habit.targetValue - todayCount
                    if (remaining <= 0) {
                        Log.d(TAG, "decrement: countdown already at 0, ignoring")
                        return // Don't go below 0 remaining for countdown mode
                    }
                    // Countdown mode: - button = increment count (reduce remaining)
                    val result = service.incrementCount(context, habitId)
                    wasCheckIn = true
                    Log.d(TAG, "decrement (countdown): incrementCount called, wasCheckIn=true, goalReached=${(result as? CheckInResult.Success)?.goalReached}")
                    // Goal completion dialog handling (TARGET-08)
                    if (result is CheckInResult.Success && result.goalReached) {
                        showGoalCompletionDialog(context, habitId, result.progress, database)
                    }
                } else {
                    // Countup mode: - button = decrement count
                    val result = service.decrementCount(context, habitId)
                    Log.d(TAG, "decrement (countup): decrementCount called, goalReached=${(result as? CheckInResult.Success)?.goalReached}")
                    // Goal completion dialog handling (TARGET-08)
                    // Note: decrementCount can trigger goalReached if distinct day count >= targetCycles
                    if (result is CheckInResult.Success && result.goalReached) {
                        showGoalCompletionDialog(context, habitId, result.progress, database)
                    }
                }
            }
        }

        // Immediately update widget UI for instant feedback
        try {
            CheckInWidget.refreshWidgetData(context, glanceId, habitId)
            CheckInWidget().update(context, glanceId)

            // Also refresh FocusWidget (SYS-05) via LocalBroadcast
            val updateIntent = Intent(WidgetUpdateReceiver.ACTION_DATA_CHANGED)
            LocalBroadcastManager.getInstance(context).sendBroadcast(updateIntent)

            Log.d(TAG, "Widget updated immediately after action")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update widget immediately", e)
        }

        // Show metric prompt if this was a check-in (not undo/decrement)
        if (wasCheckIn) {
            MetricPromptHelper.checkAndShowMetricPrompt(context, habitId)
        } else {
            Log.d(TAG, "Not showing prompt: wasCheckIn=false (undo action)")
        }
    }

    /**
     * Show goal completion dialog when target cycles is reached from widget check-in.
     * Per TARGET-08: Widget path shows goal dialog when target reached.
     *
     * @param context Context for starting activity
     * @param habitId The ID of the habit that reached its goal
     * @param progress The current progress (distinct days count)
     * @param database HabitDatabase for getting habit info
     */
    private suspend fun showGoalCompletionDialog(
        context: Context,
        habitId: Long,
        progress: Int,
        database: HabitDatabase
    ) {
        Log.d(TAG, "showGoalCompletionDialog: habitId=$habitId, progress=$progress")

        // Get habit info for dialog
        val habit = database.habitDao().getHabitById(habitId)
        if (habit == null) {
            Log.d(TAG, "Habit not found for id=$habitId")
            return
        }

        // Only show dialog if habit has targetCycles set
        val target = habit.targetCycles
        if (target == null) {
            Log.d(TAG, "Habit has no targetCycles, skipping goal dialog")
            return
        }

        Log.d(TAG, "Starting GoalCompletionActivity for habit=${habit.name}, progress=$progress, target=$target")

        // Launch GoalCompletionActivity
        val intent = GoalCompletionActivity.createIntent(
            context,
            habitId,
            habit.name,
            progress,
            target
        )
        context.startActivity(intent)
    }

}
