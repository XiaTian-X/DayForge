package com.dayforge.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dayforge.data.local.HabitDatabase
import com.dayforge.domain.service.ActivityRateCalculator
import com.dayforge.widget.checkin.CheckInWidget
import com.dayforge.widget.focus.FocusWidget
import com.dayforge.widget.motivation.MotivationWidget
import com.dayforge.widget.progress.ProgressWidget
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that updates all widgets at midnight.
 * Ensures widgets show fresh "today" state after day boundary.
 * Also refreshes activity rates for all habits.
 *
 * Scheduled in Application.onCreate() - survives reboots via WorkManager.
 */
class WidgetUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val appContext = applicationContext

        // Refresh activity rates for all habits (handles day boundary)
        refreshActivityRates()

        // Update all widgets at midnight
        CheckInWidget().updateAll(appContext)
        ProgressWidget().updateAll(appContext)
        MotivationWidget().updateAll(appContext)
        // FocusWidget - recalculate priorities for new day (WIDGET-09)
        FocusWidget().updateAll(appContext)

        return Result.success()
    }

    /**
     * Refreshes activity rates for all habits.
     * Called at midnight to update rates based on sliding window.
     */
    private suspend fun refreshActivityRates() {
        val database = HabitDatabase.getInstance(applicationContext)
        val habitDao = database.habitDao()
        val completionDao = database.completionDao()
        val habits = habitDao.getAllHabitsOnce()

        for (habit in habits) {
            val completions = completionDao.getCompletionsByHabit(habit.id).first()
            val newRate = ActivityRateCalculator.calculate(
                schedule = habit.schedule,
                createdAt = habit.createdAt,
                completions = completions.map { it.date }
            )
            habitDao.updateActivityRate(habit.id, newRate)
        }
    }

    companion object {
        private const val WORK_NAME = "widget_midnight_update"

        /**
         * Schedules daily midnight widget updates.
         * Call this from Application.onCreate().
         */
        fun schedule(context: Context) {
            val now = Calendar.getInstance()
            val midnight = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_MONTH, 1)
            }

            val delay = midnight.timeInMillis - now.timeInMillis

            val request = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
                24, TimeUnit.HOURS
            )
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                request
            )
        }
    }
}