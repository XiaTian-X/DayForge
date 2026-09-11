package com.dayforge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.dayforge.domain.service.TimerService

/**
 * BroadcastReceiver for handling notification action button presses.
 *
 * Forwards pause/resume/stop actions from the notification to TimerService.
 * Uses startForegroundService for ACTION_START and startService for other actions.
 */
class TimerActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // Create a new Intent targeting TimerService
        val serviceIntent = Intent(context, TimerService::class.java).apply {
            this.action = action

            // Copy extras (habitId, targetMinutes) from the received intent
            if (intent.hasExtra(TimerService.EXTRA_HABIT_ID)) {
                putExtra(TimerService.EXTRA_HABIT_ID, intent.getLongExtra(TimerService.EXTRA_HABIT_ID, 0L))
            }
            if (intent.hasExtra(TimerService.EXTRA_TARGET_MINUTES)) {
                putExtra(TimerService.EXTRA_TARGET_MINUTES, intent.getIntExtra(TimerService.EXTRA_TARGET_MINUTES, 0))
            }
        }

        // Start the service with the appropriate method based on action
        when (action) {
            TimerService.ACTION_START -> {
                // ACTION_START needs to start as foreground service
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
            else -> {
                // PAUSE, RESUME, STOP - service is already running, just send command
                context.startService(serviceIntent)
            }
        }
    }
}