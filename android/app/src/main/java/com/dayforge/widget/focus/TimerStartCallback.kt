package com.dayforge.widget.focus

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.dayforge.domain.service.TimerService

/**
 * Action callback for starting timer from FocusWidget.
 * Launches TimerService as foreground service for TIMER habits.
 */
class TimerStartCallback : ActionCallback {

    companion object {
        private const val TAG = "TimerStartCallback"
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val habitId = parameters[ActionParameters.Key<Long>("habitId")] ?: return
        val targetMinutes = parameters[ActionParameters.Key<Int>("targetMinutes")] ?: 0

        Log.d(TAG, "onAction: habitId=$habitId, targetMinutes=$targetMinutes")

        // Start TimerService as foreground service
        val intent = Intent(context, TimerService::class.java).apply {
            action = TimerService.ACTION_START
            putExtra(TimerService.EXTRA_HABIT_ID, habitId)
            putExtra(TimerService.EXTRA_TARGET_MINUTES, targetMinutes)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }

        Log.d(TAG, "Timer started for habitId=$habitId")
    }
}