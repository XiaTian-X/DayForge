package com.dayforge.widget

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException

class WidgetRefreshWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val refresh: suspend () -> Int
) : CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(
        context, params, { WidgetRefresher(context.applicationContext).refresh() }
    )

    override suspend fun doWork(): Result {
        val failures = try {
            refresh()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Widget refresh failed", error)
            1
        }
        if (failures > 0 && runAttemptCount < MAX_RETRIES) return Result.retry()
        if (failures > 0) {
            Log.e(TAG, "Widget refresh exhausted retries; failed steps: $failures")
        }
        // Complete the queue item even after bounded failure, otherwise its already queued
        // successors inherit failure. Output distinguishes a partial refresh from success.
        return Result.success(workDataOf(FAILED_STEPS to failures))
    }

    internal companion object {
        const val FAILED_STEPS = "failed_steps"
        const val MAX_RETRIES = 2
        private const val TAG = "WidgetRefreshWorker"
    }
}
