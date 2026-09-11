package com.dayforge.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.service.CountingSlotCalculator
import com.dayforge.domain.service.ScheduleValidator
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Schedules AlarmManager alarms for habit reminder notifications.
 *
 * Per NOTIFY-01: Schedules notification at habit's bestTime (if set).
 * Per T-86-01: Uses PendingIntent.FLAG_IMMUTABLE for security.
 * Per T-86-02: Validates bestTime is within valid range (0-1439 minutes).
 * Per T-86-03: Checks SCHEDULE_EXACT_ALARM permission before scheduling.
 *
 * Time calculation uses ZonedDateTime for DST-safe handling.
 */
object HabitReminderScheduler {

    private const val TAG = "HabitReminderScheduler"
    private const val WINDOW_TOLERANCE_MS = 60_000L // 1 minute tolerance for setWindow fallback
    private const val SLOT_OFFSET_MINUTES = 15 // 提醒在窗口开始时触发（slotTime - 15分钟）
    const val SLOT_COUNT_MAX = 100 // 最大 slot 数量，用于 requestCode 计算

    /**
     * Schedule a reminder notification for a specific habit at its bestTime.
     * For COUNTING habits, schedules multiple reminders at each slot window start.
     *
     * @param context Application context
     * @param habitId The habit's unique ID (used as PendingIntent request code for uniqueness)
     * @param bestTime Minutes since midnight (0-1439) for the preferred execution time
     * @param habitType The habit type (determines single vs multi-slot scheduling)
     * @param targetValue Target value for COUNTING habits (number of slots)
     */
    fun scheduleReminder(
        context: Context,
        habitId: Long,
        bestTime: Long,
        habitType: HabitType = HabitType.CHECK_IN,
        targetValue: Int = 1
    ) {
        // Per T-86-02: Validate bestTime is within valid range
        if (bestTime < 0 || bestTime > 1439) {
            Log.w(TAG, "Invalid bestTime=$bestTime for habitId=$habitId (must be 0-1439), skipping")
            return
        }

        val appContext = context.applicationContext

        // COUNTING habits: schedule multiple slot reminders
        if (habitType == HabitType.COUNTING && targetValue > 1) {
            scheduleCountingSlots(appContext, habitId, bestTime, targetValue)
            return
        }

        // CHECK_IN/TIMER/GOAL: single reminder at bestTime
        scheduleSingleReminder(appContext, habitId, bestTime)
    }

    /**
     * Schedule single reminder for CHECK_IN/TIMER habits at bestTime.
     */
    private fun scheduleSingleReminder(context: Context, habitId: Long, bestTime: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val currentTime = ZonedDateTime.now()

        // Convert bestTime (minutes since midnight) to ZonedDateTime at today's time
        val bestTimeMinutes = bestTime.toInt()
        val reminderTimeToday = currentTime
            .withHour(bestTimeMinutes / 60)
            .withMinute(bestTimeMinutes % 60)
            .withSecond(0)
            .withNano(0)

        // If time has passed today, schedule for tomorrow
        val reminderTime = if (reminderTimeToday.isBefore(currentTime) || reminderTimeToday.isEqual(currentTime)) {
            reminderTimeToday.plusDays(1)
        } else {
            reminderTimeToday
        }

        val triggerAtMillis = reminderTime.toInstant().toEpochMilli()

        Log.d(TAG, "Scheduling single reminder for habitId=$habitId at $reminderTime (bestTime=$bestTime minutes)")

        // Create PendingIntent with habitId as request code (unique per habit)
        val intent = Intent(HabitReminderReceiver.ACTION_HABIT_REMINDER).apply {
            setClass(context, HabitReminderReceiver::class.java)
            putExtra(HabitReminderReceiver.EXTRA_HABIT_ID, habitId)
            // slotIndex defaults to -1 for single-reminder habits
        }

        // Per T-86-01: Use FLAG_IMMUTABLE for security
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            habitId.toInt(),  // Unique request code per habit
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Schedule alarm with permission handling (T-86-03)
        scheduleAlarmWithPermission(alarmManager, triggerAtMillis, pendingIntent, context, "single reminder habitId=$habitId")
    }

    /**
     * Schedule multiple slot reminders for COUNTING habits.
     * Each reminder triggers at slot window start (slotTime - 15 minutes).
     *
     * @param targetValue Number of target completions (determines slot count)
     */
    private fun scheduleCountingSlots(
        context: Context,
        habitId: Long,
        bestTime: Long,
        targetValue: Int
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val currentTime = ZonedDateTime.now()

        // Calculate all slots for today
        val slots = CountingSlotCalculator.calculateSlots(bestTime, targetValue, currentTime)

        Log.d(TAG, "Scheduling ${slots.size} slot reminders for COUNTING habitId=$habitId")

        for (slot in slots) {
            // Calculate reminder time: window start (slotTime - 15 minutes)
            // windowStart already accounts for -15 offset
            val reminderMinutes = slot.windowStart

            // Convert to ZonedDateTime
            val reminderTimeToday = currentTime
                .withHour(reminderMinutes / 60)
                .withMinute(reminderMinutes % 60)
                .withSecond(0)
                .withNano(0)

            // If window has passed today, schedule for tomorrow
            val reminderTime = if (reminderTimeToday.isBefore(currentTime) || reminderTimeToday.isEqual(currentTime)) {
                reminderTimeToday.plusDays(1)
            } else {
                reminderTimeToday
            }

            val triggerAtMillis = reminderTime.toInstant().toEpochMilli()

            Log.d(TAG, "Scheduling slot ${slot.index} reminder for habitId=$habitId at $reminderTime (windowStart=$reminderMinutes)")

            // Create PendingIntent with unique request code per slot
            val intent = Intent(HabitReminderReceiver.ACTION_HABIT_REMINDER).apply {
                setClass(context, HabitReminderReceiver::class.java)
                putExtra(HabitReminderReceiver.EXTRA_HABIT_ID, habitId)
                putExtra(HabitReminderReceiver.EXTRA_SLOT_INDEX, slot.index)
                putExtra(HabitReminderReceiver.EXTRA_TARGET_VALUE, targetValue)
            }

            val requestCode = calculateSlotRequestCode(habitId, slot.index)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Schedule alarm
            scheduleAlarmWithPermission(alarmManager, triggerAtMillis, pendingIntent, context, "slot ${slot.index} habitId=$habitId")
        }
    }

    /**
     * Calculate unique request code for a slot reminder.
     * Formula: habitId * SLOT_COUNT_MAX + slotIndex
     * This ensures slot reminders don't conflict with single reminders (habitId < SLOT_COUNT_MAX).
     */
    fun calculateSlotRequestCode(habitId: Long, slotIndex: Int): Int {
        return habitId.toInt() * SLOT_COUNT_MAX + slotIndex
    }

    /**
     * Schedule alarm with permission handling for Android 12+.
     */
    private fun scheduleAlarmWithPermission(
        alarmManager: AlarmManager,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent,
        context: Context,
        logTag: String
    ) {
        // Schedule alarm with permission handling (T-86-03)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: check permission
            val canScheduleExact = alarmManager.canScheduleExactAlarms()

            if (canScheduleExact) {
                // Use setAlarmClock for highest priority (exact timing)
                val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerAtMillis, pendingIntent)
                alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
                Log.d(TAG, "Scheduled exact alarm using setAlarmClock for $logTag")
            } else {
                // Permission denied: use setWindow fallback with 1-minute tolerance
                alarmManager.setWindow(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    WINDOW_TOLERANCE_MS,
                    pendingIntent
                )
                Log.d(TAG, "Scheduled inexact alarm using setWindow for $logTag (permission denied)")
            }
        } else {
            // Android 11-: exact alarms allowed without permission
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            Log.d(TAG, "Scheduled exact alarm using setExactAndAllowWhileIdle for $logTag")
        }
    }

    /**
     * Cancel a pending reminder alarm for a specific habit.
     * For COUNTING habits, cancels all slot reminders.
     *
     * @param context Application context
     * @param habitId The habit's unique ID (must match the request code used in scheduleReminder)
     * @param habitType The habit type (determines whether to cancel multiple slots)
     * @param targetValue Target value for COUNTING habits (number of slots to cancel)
     */
    fun cancelReminder(
        context: Context,
        habitId: Long,
        habitType: HabitType = HabitType.CHECK_IN,
        targetValue: Int = 1
    ) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Create base intent for matching
        val intent = Intent(HabitReminderReceiver.ACTION_HABIT_REMINDER).apply {
            setClass(appContext, HabitReminderReceiver::class.java)
        }

        // COUNTING habits: cancel all slot reminders
        if (habitType == HabitType.COUNTING && targetValue > 1) {
            for (slotIndex in 0 until targetValue) {
                val requestCode = calculateSlotRequestCode(habitId, slotIndex)
                val pendingIntent = PendingIntent.getBroadcast(
                    appContext,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(pendingIntent)
            }
            Log.d(TAG, "Cancelled $targetValue slot reminders for COUNTING habitId=$habitId")
            return
        }

        // CHECK_IN/TIMER: cancel single reminder
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            habitId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "Cancelled reminder alarm for habitId=$habitId")
    }

    /**
     * Reschedule all reminders for active habits with bestTime set.
     *
     * Queries the database for all habits where:
     * - bestTime != null (user has set preferred time)
     * - isActive = true (habit is active)
     * - habitType != GOAL (GOAL habits don't have check-ins)
     *
     * @param context Application context
     */
    suspend fun rescheduleAllReminders(context: Context) {
        val appContext = context.applicationContext
        val database = HabitDatabase.getInstance(appContext)

        // Query all habits with bestTime and filter
        val allHabits = database.habitDao().getAllHabitsOnce()
        val habitsWithReminders = allHabits.filter { habit ->
            habit.bestTime != null &&
            habit.isActive &&
            habit.habitType != HabitType.GOAL
        }

        Log.d(TAG, "Rescheduling reminders for ${habitsWithReminders.size} habits")

        for (habit in habitsWithReminders) {
            scheduleReminder(
                appContext,
                habit.id,
                habit.bestTime!!,
                habit.habitType,
                habit.targetValue
            )
        }

        Log.i(TAG, "Rescheduled ${habitsWithReminders.size} habit reminders")
    }

    /**
     * Schedule a reminder for the next valid check-in day.
     * Used when today is not a check-in day (Weekly/Monthly/Custom schedules).
     *
     * This avoids unnecessary daily alarm triggers by scheduling directly to
     * the next valid check-in day.
     *
     * @param context Application context
     * @param habitId The habit's unique ID
     * @param bestTime Minutes since midnight (0-1439) for the preferred execution time
     * @param habitType The habit type
     * @param targetValue Target value for COUNTING habits
     * @param schedule The habit's schedule configuration
     * @param createdAt The habit's creation timestamp
     */
    fun scheduleReminderForNextCheckInDay(
        context: Context,
        habitId: Long,
        bestTime: Long,
        habitType: HabitType,
        targetValue: Int,
        schedule: HabitSchedule,
        createdAt: Long
    ) {
        // Per T-86-02: Validate bestTime is within valid range
        if (bestTime < 0 || bestTime > 1439) {
            Log.w(TAG, "Invalid bestTime=$bestTime for habitId=$habitId, skipping")
            return
        }

        val appContext = context.applicationContext
        val nextCheckInDate = ScheduleValidator.getNextCheckInDate(schedule, createdAt)
        val today = LocalDate.now()
        val daysUntilNext = ChronoUnit.DAYS.between(today, nextCheckInDate).toInt()

        // If nextCheckInDate is today, use normal scheduling
        if (daysUntilNext <= 0) {
            scheduleReminder(appContext, habitId, bestTime, habitType, targetValue)
            Log.d(TAG, "Next check-in is today, using normal scheduling for habitId=$habitId")
            return
        }

        Log.d(TAG, "Scheduling reminder for habitId=$habitId at next check-in day ($nextCheckInDate, $daysUntilNext days away)")

        // COUNTING habits: schedule multiple slot reminders for the next check-in day
        if (habitType == HabitType.COUNTING && targetValue > 1) {
            scheduleCountingSlotsForFutureDate(appContext, habitId, bestTime, targetValue, daysUntilNext)
            return
        }

        // CHECK_IN/TIMER: single reminder for the next check-in day
        scheduleSingleReminderForFutureDate(appContext, habitId, bestTime, daysUntilNext)
    }

    /**
     * Schedule single reminder for a future date (days from now).
     * Used for CHECK_IN/TIMER habits on non-check-in days.
     */
    private fun scheduleSingleReminderForFutureDate(
        context: Context,
        habitId: Long,
        bestTime: Long,
        daysFromNow: Int
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val currentTime = ZonedDateTime.now()

        // Calculate reminder time: today + daysFromNow, at bestTime
        val bestTimeMinutes = bestTime.toInt()
        val reminderTime = currentTime
            .plusDays(daysFromNow.toLong())
            .withHour(bestTimeMinutes / 60)
            .withMinute(bestTimeMinutes % 60)
            .withSecond(0)
            .withNano(0)

        val triggerAtMillis = reminderTime.toInstant().toEpochMilli()

        Log.d(TAG, "Scheduling future reminder for habitId=$habitId at $reminderTime ($daysFromNow days from now)")

        // Create PendingIntent
        val intent = Intent(HabitReminderReceiver.ACTION_HABIT_REMINDER).apply {
            setClass(context, HabitReminderReceiver::class.java)
            putExtra(HabitReminderReceiver.EXTRA_HABIT_ID, habitId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            habitId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleAlarmWithPermission(alarmManager, triggerAtMillis, pendingIntent, context, "future reminder habitId=$habitId")
    }

    /**
     * Schedule multiple slot reminders for a future date (days from now).
     * Used for COUNTING habits on non-check-in days.
     */
    private fun scheduleCountingSlotsForFutureDate(
        context: Context,
        habitId: Long,
        bestTime: Long,
        targetValue: Int,
        daysFromNow: Int
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val currentTime = ZonedDateTime.now()

        // Calculate future date time
        val futureDateTime = currentTime.plusDays(daysFromNow.toLong())

        // Calculate all slots for the future date
        val slots = CountingSlotCalculator.calculateSlots(bestTime, targetValue, futureDateTime)

        Log.d(TAG, "Scheduling ${slots.size} slot reminders for future date ($daysFromNow days away) for COUNTING habitId=$habitId")

        for (slot in slots) {
            val reminderMinutes = slot.windowStart

            // Convert to ZonedDateTime on the future date
            val reminderTime = futureDateTime
                .withHour(reminderMinutes / 60)
                .withMinute(reminderMinutes % 60)
                .withSecond(0)
                .withNano(0)

            val triggerAtMillis = reminderTime.toInstant().toEpochMilli()

            Log.d(TAG, "Scheduling future slot ${slot.index} reminder for habitId=$habitId at $reminderTime")

            // Create PendingIntent
            val intent = Intent(HabitReminderReceiver.ACTION_HABIT_REMINDER).apply {
                setClass(context, HabitReminderReceiver::class.java)
                putExtra(HabitReminderReceiver.EXTRA_HABIT_ID, habitId)
                putExtra(HabitReminderReceiver.EXTRA_SLOT_INDEX, slot.index)
                putExtra(HabitReminderReceiver.EXTRA_TARGET_VALUE, targetValue)
            }

            val requestCode = calculateSlotRequestCode(habitId, slot.index)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            scheduleAlarmWithPermission(alarmManager, triggerAtMillis, pendingIntent, context, "future slot ${slot.index} habitId=$habitId")
        }
    }
}