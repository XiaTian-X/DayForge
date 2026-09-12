package com.dayforge.domain.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.dayforge.MainActivity
import com.dayforge.R

/** Builds all timer notifications without owning timer state or service lifecycle. */
internal class TimerNotificationFactory(
    private val context: Context
) {
    fun createChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            TimerService.CHANNEL_ID,
            "Timer",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background timer notifications"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun createTimerNotification(
        elapsedSeconds: Int,
        isPaused: Boolean,
        targetMinutes: Int,
        isCountdown: Boolean,
        habitId: Long
    ): Notification {
        val contentTitle = if (isPaused) {
            context.getString(R.string.timer_notification_title_paused)
        } else {
            context.getString(R.string.timer_notification_title_running)
        }

        return NotificationCompat.Builder(context, TimerService.CHANNEL_ID)
            .setContentTitle(contentTitle)
            .setContentText(formatTimeText(elapsedSeconds, targetMinutes, isCountdown))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(createContentIntent())
            .addAction(createPauseResumeAction(isPaused, habitId, targetMinutes))
            .addAction(createStopAction(habitId, targetMinutes))
            .build()
    }

    fun createTargetReachedNotification(): Notification = alertNotification(
        R.string.timer_notification_target_reached_title,
        R.string.timer_notification_target_reached_text
    )

    fun createThresholdReachedNotification(): Notification = alertNotification(
        R.string.timer_notification_threshold_reached_title,
        R.string.timer_notification_threshold_reached_text
    )

    fun createCountdownCompleteNotification(): Notification = alertNotification(
        R.string.timer_notification_countdown_complete_title,
        R.string.timer_notification_countdown_complete_text
    )

    internal fun formatTimeText(
        elapsedSeconds: Int,
        targetMinutes: Int,
        isCountdown: Boolean
    ): String {
        val targetSeconds = targetMinutes * 60
        return if (isCountdown) {
            val remainingSeconds = (targetSeconds - elapsedSeconds).coerceAtLeast(0)
            context.getString(
                R.string.timer_notification_remaining_format,
                remainingSeconds / 60,
                remainingSeconds % 60,
                targetMinutes
            )
        } else {
            context.getString(
                R.string.timer_notification_elapsed_format,
                elapsedSeconds / 60,
                elapsedSeconds % 60,
                targetMinutes
            )
        }
    }

    private fun alertNotification(titleRes: Int, textRes: Int): Notification =
        NotificationCompat.Builder(context, TimerService.CHANNEL_ID)
            .setContentTitle(context.getString(titleRes))
            .setContentText(context.getString(textRes))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .build()

    private fun createContentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createPauseResumeAction(
        isPaused: Boolean,
        habitId: Long,
        targetMinutes: Int
    ): NotificationCompat.Action {
        val action = if (isPaused) TimerService.ACTION_RESUME else TimerService.ACTION_PAUSE
        val title = if (isPaused) {
            context.getString(R.string.timer_btn_resume)
        } else {
            context.getString(R.string.timer_btn_pause)
        }
        val intent = Intent(context, TimerService::class.java).apply {
            this.action = action
            putExtra(TimerService.EXTRA_HABIT_ID, habitId)
            putExtra(TimerService.EXTRA_TARGET_MINUTES, targetMinutes)
        }
        val pendingIntent = PendingIntent.getService(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_pause,
            title,
            pendingIntent
        ).build()
    }

    private fun createStopAction(habitId: Long, targetMinutes: Int): NotificationCompat.Action {
        val intent = Intent(context, TimerService::class.java).apply {
            action = TimerService.ACTION_STOP
            putExtra(TimerService.EXTRA_HABIT_ID, habitId)
            putExtra(TimerService.EXTRA_TARGET_MINUTES, targetMinutes)
        }
        val pendingIntent = PendingIntent.getService(
            context,
            TimerService.ACTION_STOP.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            context.getString(R.string.timer_btn_stop),
            pendingIntent
        ).build()
    }
}
