package com.dayforge.reminder

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.dayforge.MainActivity
import com.dayforge.R
import java.util.Locale

/**
 * Builder for habit reminder notifications.
 * Per NOTIFY-02: Creates notification with habit name and scheduled time.
 * Per T-86-02: Uses FLAG_IMMUTABLE for security.
 */
object ReminderNotificationBuilder {

    const val EXTRA_HABIT_ID = "habitId"
    const val EXTRA_NAVIGATION_DESTINATION = "navigation_destination"

    /**
     * Builds a notification for habit reminder.
     *
     * @param context Application context
     * @param habitId The habit ID for notification ID and PendingIntent request code
     * @param habitName The habit name to display as notification title
     * @param bestTime The scheduled time in minutes since midnight (nullable)
     * @param slotIndex Slot index for COUNTING habits (-1 for single reminder)
     * @param targetValue Target value for COUNTING habits
     * @param completedToday Number of completions today
     * @return Notification ready to be shown
     */
    fun buildReminderNotification(
        context: Context,
        habitId: Long,
        habitName: String,
        bestTime: Long?,
        slotIndex: Int = -1,
        targetValue: Int = 1,
        completedToday: Int = 0
    ): android.app.Notification {
        val contentText = if (slotIndex >= 0 && targetValue > 1) {
            // COUNTING habit: show slot progress
            formatSlotProgressText(context, slotIndex, targetValue, completedToday)
        } else {
            // CHECK_IN/TIMER: show scheduled time
            formatTimeText(context, bestTime)
        }

        return NotificationCompat.Builder(context, HabitReminderReceiver.CHANNEL_ID)
            .setContentTitle(habitName)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)  // Dismiss on tap
            .setContentIntent(createHabitDetailPendingIntent(context, habitId))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    /**
     * Creates PendingIntent to open habit detail screen when notification is tapped.
     * Per T-86-02: Uses FLAG_IMMUTABLE for security, unique request code per habit.
     *
     * @param context Application context
     * @param habitId The habit ID for navigation target
     * @return PendingIntent targeting MainActivity with habitId extra
     */
    fun createHabitDetailPendingIntent(context: Context, habitId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_HABIT_ID, habitId)
            putExtra(EXTRA_NAVIGATION_DESTINATION, "habit_detail")
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        return PendingIntent.getActivity(
            context,
            habitId.toInt(),  // Unique request code per habit
            intent,
            flags
        )
    }

    /**
     * Formats the time text for notification content.
     * Converts bestTime (minutes since midnight) to HH:mm format.
     *
     * @param context Application context for localized string
     * @param bestTime Minutes since midnight (nullable)
     * @return Formatted time string, e.g., "Scheduled at 8:00"
     */
    private fun formatTimeText(context: Context, bestTime: Long?): String {
        if (bestTime == null) {
            return ""
        }

        // Convert minutes since midnight to HH:mm format
        val hours = (bestTime / 60).toInt()
        val minutes = (bestTime % 60).toInt()
        val formattedTime = String.format(Locale.getDefault(), "%02d:%02d", hours, minutes)

        return context.getString(R.string.notification_scheduled_time, formattedTime)
    }

    /**
     * Formats the slot progress text for COUNTING habit notifications.
     *
     * @param context Application context for localized string
     * @param slotIndex Current slot index (0-based)
     * @param targetValue Total target completions
     * @param completedToday Number of completions today
     * @return Formatted progress string, e.g., "第1次/共3次 (已完成0次)"
     */
    private fun formatSlotProgressText(
        context: Context,
        slotIndex: Int,
        targetValue: Int,
        completedToday: Int
    ): String {
        // slotIndex is 0-based, display as 1-based (第1次, 第2次, etc.)
        return context.getString(
            R.string.notification_slot_progress,
            slotIndex + 1,
            targetValue,
            completedToday
        )
    }
}
