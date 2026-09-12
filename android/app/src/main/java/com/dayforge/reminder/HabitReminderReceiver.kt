package com.dayforge.reminder

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.dayforge.data.local.DataStoreProvider
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.model.HabitType
import com.dayforge.domain.service.ScheduleValidator
import com.dayforge.util.DateTimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for habit reminder notifications.
 * Receives ACTION_HABIT_REMINDER broadcast from AlarmManager and creates notification.
 *
 * Per NOTIFY-01: Triggered at habit's bestTime (if set).
 * Per T-86-01: Validates habitId from intent before processing.
 */
class HabitReminderReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HabitReminderReceiver"
        const val ACTION_HABIT_REMINDER = "com.dayforge.HABIT_REMINDER"
        const val EXTRA_HABIT_ID = "habitId"
        const val EXTRA_SLOT_INDEX = "slotIndex"  // Slot index for COUNTING habits (-1 for single reminder)
        const val EXTRA_TARGET_VALUE = "targetValue"  // Target value for COUNTING habits
        const val CHANNEL_ID = "habit_reminders"  // Created in DayForgeApplication.onCreate()

        // Preference keys for notification settings (NOTIFY-04)
        private val GLOBAL_NOTIFICATIONS_ENABLED_KEY = booleanPreferencesKey("global_notifications_enabled")
        private fun habitNotificationKey(habitId: Long) = booleanPreferencesKey("habit_notification_$habitId")
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_HABIT_REMINDER) {
            // Per T-86-01: Validate habitId before processing
            val habitId = intent.getLongExtra(EXTRA_HABIT_ID, 0L)
            if (habitId == 0L) {
                Log.w(TAG, "Received reminder with invalid habitId=0, ignoring")
                return
            }

            // Extract slot info for COUNTING habits
            val slotIndex = intent.getIntExtra(EXTRA_SLOT_INDEX, -1)
            val targetValue = intent.getIntExtra(EXTRA_TARGET_VALUE, 1)

            Log.d(TAG, "Received HABIT_REMINDER broadcast for habitId=$habitId, slotIndex=$slotIndex, targetValue=$targetValue")

            val pendingResult = goAsync()
            val appContext = context.applicationContext

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Query habit from database
                    val database = HabitDatabaseProvider.getInstance(appContext)
                    val habit = database.habitDao().getHabitByIdSync(habitId)

                    if (habit == null) {
                        Log.w(TAG, "Habit not found: habitId=$habitId")
                    } else if (!habit.isActive) {
                        Log.d(TAG, "Habit is inactive, skipping reminder: habitId=$habitId")
                    } else if (habit.habitType == HabitType.GOAL) {
                        Log.d(TAG, "GOAL habit doesn't have reminders: habitId=$habitId")
                    } else {
                        // Per NOTIFY-04: Check global and per-habit notification settings
                        val dataStore = DataStoreProvider.get(appContext)
                        val globalEnabled = dataStore.data.map { prefs ->
                            prefs[GLOBAL_NOTIFICATIONS_ENABLED_KEY] ?: true  // Default: enabled
                        }.first()
                        val habitEnabled = dataStore.data.map { prefs ->
                            prefs[habitNotificationKey(habitId)] ?: true  // Default: enabled
                        }.first()

                        if (!globalEnabled) {
                            Log.d(TAG, "Global notifications disabled, skipping reminder: habitId=$habitId")
                        } else if (!habitEnabled) {
                            Log.d(TAG, "Habit notifications disabled, skipping reminder: habitId=$habitId")
                        } else {
                            // Check if today is a valid check-in day for the habit's schedule
                            val isCheckInAllowedToday = ScheduleValidator.isCheckInAllowedToday(habit.schedule, habit.createdAt)

                            if (!isCheckInAllowedToday) {
                                Log.d(TAG, "Today is not a check-in day (schedule=${habit.schedule}), skipping reminder for habitId=$habitId")
                                // Reschedule to the next valid check-in day
                                rescheduleForNextCheckInDay(appContext, habit, slotIndex)
                                return@launch
                            }

                            // COUNTING habits with slot info: check if slot already completed
                            if (habit.habitType == HabitType.COUNTING && slotIndex >= 0 && targetValue > 1) {
                                val todayStart = DateTimeUtils.startOfDayMillis()
                                val todayEnd = DateTimeUtils.startOfNextDayMillis(todayStart)
                                val todayCompletions = database.completionDao().getCompletionsInRangeSync(habitId, todayStart, todayEnd)
                                val completedToday = todayCompletions.sumOf { it.value }

                                // Skip if this slot is already completed (completedToday > slotIndex)
                                if (completedToday > slotIndex) {
                                    Log.d(TAG, "Slot $slotIndex already completed (completedToday=$completedToday), skipping reminder for habitId=$habitId")
                                    // Still reschedule for tomorrow
                                    rescheduleSlotReminder(appContext, habit, slotIndex)
                                    return@launch
                                }

                                // Show notification with slot progress
                                showNotificationWithSlot(appContext, habit, slotIndex, targetValue, completedToday)
                            } else {
                                // CHECK_IN/TIMER: single reminder
                                showNotification(appContext, habit)
                            }

                            // Reschedule for tomorrow
                            rescheduleReminder(appContext, habit, slotIndex)
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process habit reminder for habitId=$habitId", e)
                } finally {
                    try {
                        pendingResult.finish()
                    } catch (e: Exception) {
                        Log.d(TAG, "pendingResult.finish() threw exception (safe to ignore)")
                    }
                }
            }
        }
    }

    private fun showNotification(context: Context, habit: com.dayforge.data.local.entity.HabitEntity) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = ReminderNotificationBuilder.buildReminderNotification(
            context = context,
            habitId = habit.id,
            habitName = habit.name,
            bestTime = habit.bestTime
        )
        notificationManager.notify(habit.id.toInt(), notification)
        Log.i(TAG, "Notification shown for habit: ${habit.name} (habitId=${habit.id})")
    }

    private fun showNotificationWithSlot(
        context: Context,
        habit: com.dayforge.data.local.entity.HabitEntity,
        slotIndex: Int,
        targetValue: Int,
        completedToday: Int
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = ReminderNotificationBuilder.buildReminderNotification(
            context = context,
            habitId = habit.id,
            habitName = habit.name,
            bestTime = habit.bestTime,
            slotIndex = slotIndex,
            targetValue = targetValue,
            completedToday = completedToday
        )
        // Use unique notification ID for each slot to allow multiple pending notifications
        val notificationId = habit.id.toInt() * 100 + slotIndex
        notificationManager.notify(notificationId, notification)
        Log.i(TAG, "Slot notification shown for habit: ${habit.name} slot $slotIndex/$targetValue (habitId=${habit.id}, completedToday=$completedToday)")
    }

    private fun rescheduleReminder(context: Context, habit: com.dayforge.data.local.entity.HabitEntity, slotIndex: Int) {
        if (habit.bestTime != null) {
            HabitReminderScheduler.scheduleReminder(
                context,
                habit.id,
                habit.bestTime,
                habit.habitType,
                habit.targetValue
            )
            Log.d(TAG, "Rescheduled reminder for habitId=${habit.id} slotIndex=$slotIndex at bestTime=${habit.bestTime}")
        }
    }

    private fun rescheduleSlotReminder(context: Context, habit: com.dayforge.data.local.entity.HabitEntity, slotIndex: Int) {
        if (habit.bestTime != null && habit.habitType == HabitType.COUNTING) {
            // Only reschedule this specific slot for tomorrow
            HabitReminderScheduler.scheduleReminder(
                context,
                habit.id,
                habit.bestTime,
                habit.habitType,
                habit.targetValue
            )
            Log.d(TAG, "Rescheduled slot $slotIndex reminder for tomorrow for habitId=${habit.id}")
        }
    }

    /**
     * Reschedule reminder for the next valid check-in day.
     * Used when today is not a check-in day (Weekly/Monthly/Custom schedules).
     */
    private fun rescheduleForNextCheckInDay(context: Context, habit: com.dayforge.data.local.entity.HabitEntity, slotIndex: Int) {
        if (habit.bestTime != null) {
            HabitReminderScheduler.scheduleReminderForNextCheckInDay(
                context,
                habit.id,
                habit.bestTime,
                habit.habitType,
                habit.targetValue,
                habit.schedule,
                habit.createdAt
            )
            Log.d(TAG, "Rescheduled reminder for next check-in day for habitId=${habit.id} slotIndex=$slotIndex")
        }
    }
}