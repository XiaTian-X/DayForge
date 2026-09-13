package com.dayforge.widget

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.businessDate
import com.dayforge.domain.service.ActivityRateCalculator
import androidx.work.await
import kotlinx.coroutines.CancellationException
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

    override suspend fun doWork(): Result = try {
        val appContext = applicationContext

        // Refresh activity rates for all habits (handles day boundary)
        refreshActivityRates()

        // Use the same serial queue as data changes, including counting and timer widgets.
        val operation = WidgetRefreshScheduler.request(appContext)
            ?: throw IllegalStateException("Widget refresh was not enqueued")
        operation.await()

        Result.success()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.e("WidgetUpdateWorker", "Midnight refresh failed", error)
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }

    /**
     * Refreshes activity rates for all habits.
     * Called at midnight to update rates based on sliding window.
     */
    private suspend fun refreshActivityRates() {
        val database = HabitDatabaseProvider.getInstance(applicationContext)
        val habitDao = database.habitDao()
        val completionDao = database.completionDao()
        val habits = habitDao.getAllHabitsOnce()

        for (habit in habits) {
            val completions = completionDao.getCompletionsByHabit(habit.id).first()
            val newRate = ActivityRateCalculator.calculate(
                schedule = habit.schedule,
                createdAt = habit.createdAt,
                completions = completions.map { it.businessDate }
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
