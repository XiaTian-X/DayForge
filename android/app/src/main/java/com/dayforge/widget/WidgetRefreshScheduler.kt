package com.dayforge.widget

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Low-frequency data invalidations. Timer ticks and platform broadcasts are separate. */
object WidgetRefreshScheduler {
    internal const val WORK_NAME = "dayforge-widget-refresh"

    fun request(context: Context): Operation? = try {
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        // KEEP can lose a change arriving during a render; REPLACE cancels that render.
        // Each successor reads current data, never a queued account/habit snapshot.
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request
        ).also { operation ->
            operation.result.addListener({
                try {
                    operation.result.get()
                } catch (error: Exception) {
                    Log.e(TAG, "Widget refresh enqueue failed", error)
                }
            }, Executor { it.run() })
        }
    } catch (error: RuntimeException) {
        // Rendering must not turn an already committed check-in into a business failure.
        // A process-start refresh and subsequent changes provide recovery opportunities.
        Log.e(TAG, "Widget refresh scheduling unavailable", error)
        null
    }

    private const val TAG = "WidgetRefreshScheduler"
}
