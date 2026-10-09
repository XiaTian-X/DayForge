package com.dayforge.widget

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import kotlinx.coroutines.CancellationException
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that updates all widgets at midnight.
 * Ensures widgets show fresh "today" state after day boundary.
 * Refreshes only the legacy activity cache; typed consumers read current-round derived rates.
 *
 * Scheduled in Application.onCreate() - survives reboots via WorkManager.
 */
class WidgetUpdateWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val refreshActivityRates: suspend () -> Unit
) : CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params, {
        com.dayforge.di.WidgetEntryPoint.from(context.applicationContext).activityRateRefresher().refresh()
    })

    override suspend fun doWork(): Result = try {
        val appContext = applicationContext

        // Commit the account-coordinated legacy cache before enqueueing fresh widget reads.
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
