package com.dayforge.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.model.HabitType
import com.dayforge.domain.service.HabitPriorityCalculator
import com.dayforge.domain.service.TimeMatchResult
import com.dayforge.domain.service.CountingSlotCalculator
import java.time.ZonedDateTime

/**
 * Schedules AlarmManager alarms for FocusWidget refresh at time window boundaries.
 *
 * Per SYS-01: Schedules refresh at window boundaries (start or end).
 * Per SYS-03: Checks SCHEDULE_EXACT_ALARM permission.
 * Per SYS-04: Falls back to setWindow when permission denied.
 *
 * 改进版本：处理所有窗口状态，包括BeforeWindow和AfterWindow。
 * 计算所有习惯的窗口边界，调度到最近的边界时间。
 */
object FocusWidgetAlarmScheduler {

    private const val TAG = "FocusWidgetAlarmScheduler"
    private const val WINDOW_TOLERANCE_MS = 60_000L // 1 minute tolerance for setWindow fallback
    private const val MIN_DELAY_MS = 10_000L // 最小10秒延迟
    private const val COUNTDOWN_THRESHOLD_MINUTES = 15 // 15分钟内开始倒计时刷新
    private const val COUNTDOWN_INTERVAL_MS = 60_000L // 倒计时期间每分钟刷新

    /**
     * Schedule next FocusWidget refresh based on nearest window boundary.
     * Called after FocusWidget data is refreshed.
     *
     * 改进逻辑：
     * 1. 计算所有活跃习惯的窗口边界（开始和结束）
     * 2. COUNTING习惯考虑所有slot窗口
     * 3. 找到最近的边界时间
     * 4. 如果距离窗口开始 <= 15分钟：每分钟刷新（动态倒计时）
     * 5. 否则：调度到窗口边界或下一个检查点
     */
    suspend fun scheduleNextRefresh(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Get all active habits with bestTime
        val database = HabitDatabaseProvider.getInstance(appContext)
        val currentTime = ZonedDateTime.now()
        val currentMinutes = currentTime.hour * 60 + currentTime.minute
        val activeHabits = database.habitDao().getAllHabitsOnce().filter {
            it.isActive && it.bestTime != null && it.habitType != HabitType.GOAL
        }

        if (activeHabits.isEmpty()) {
            Log.d(TAG, "No habits with bestTime - skip scheduling")
            return
        }

        // 收集所有窗口边界时间（分钟）
        val windowBoundaries = mutableListOf<Int>()

        for (habit in activeHabits) {
            val bestTime = habit.bestTime!!

            if (habit.habitType == HabitType.COUNTING) {
                // COUNTING习惯：计算所有slot窗口边界
                val slots = CountingSlotCalculator.calculateSlots(bestTime, habit.targetValue, currentTime)
                for (slot in slots) {
                    if (!slot.isPast) {
                        windowBoundaries.add(slot.windowStart)
                        windowBoundaries.add(slot.windowEnd)
                    }
                }
            } else {
                // CHECK_IN/TIMER习惯：单个窗口
                val halfWidth = when (habit.habitType) {
                    HabitType.TIMER -> habit.targetValue
                    else -> 15
                }
                val windowStart = bestTime.toInt() - halfWidth
                val windowEnd = bestTime.toInt() + halfWidth

                // 只收集未来的边界
                if (windowEnd > currentMinutes) {
                    windowBoundaries.add(windowStart)
                    windowBoundaries.add(windowEnd)
                }
            }
        }

        // 找到最近的窗口开始时间（用于判断是否需要倒计时刷新）
        val nearestWindowStart = windowBoundaries
            .filter { it > currentMinutes }
            .minByOrNull { it - currentMinutes }

        if (nearestWindowStart == null) {
            Log.d(TAG, "No future window boundaries - schedule for tomorrow reset")
            // 调度到明天7:00（VALID_PERIOD_START）
            val tomorrowMorning = currentTime.plusDays(1).withHour(7).withMinute(0).withSecond(0).withNano(0)
            scheduleAlarmAt(alarmManager, appContext, tomorrowMorning)
            return
        }

        val minutesUntilStart = nearestWindowStart - currentMinutes

        // 关键改进：如果距离窗口开始 <= 15分钟，每分钟刷新以显示动态倒计时
        if (minutesUntilStart <= COUNTDOWN_THRESHOLD_MINUTES) {
            // 倒计时模式：下一分钟刷新
            val nextMinuteRefresh = currentTime
                .plusMinutes(1)
                .withSecond(0)
                .withNano(0)

            Log.d(TAG, "Countdown mode: $minutesUntilStart min until window start, scheduling refresh in ~1 min")
            scheduleAlarmAt(alarmManager, appContext, nextMinuteRefresh)
            return
        }

        // 超过15分钟：找最近的边界时间（开始或结束）
        val nearestBoundary = windowBoundaries
            .filter { it > currentMinutes }
            .minByOrNull { it - currentMinutes }

        // 如果最近的边界距离 > 15分钟，先调度一个15分钟后的检查点
        // 这样可以在进入倒计时范围时及时切换到倒计时模式
        val targetBoundary = if (nearestBoundary != null && (nearestBoundary - currentMinutes) > COUNTDOWN_THRESHOLD_MINUTES) {
            // 先调度到距离窗口开始15分钟的位置，然后再进入倒计时模式
            nearestWindowStart - COUNTDOWN_THRESHOLD_MINUTES
        } else {
            nearestBoundary ?: nearestWindowStart
        }

        val minutesUntilBoundary = targetBoundary - currentMinutes
        val secondsIntoCurrentMinute = currentTime.second

        // 计算精确的毫秒延迟
        val delayMs = (minutesUntilBoundary * 60L - secondsIntoCurrentMinute) * 1000L
        val actualDelayMs = delayMs.coerceAtLeast(MIN_DELAY_MS)

        val nextRefreshTime = currentTime
            .plusSeconds(actualDelayMs / 1000L)
            .withSecond(0)
            .withNano(0)

        Log.d(TAG, "Scheduling FocusWidget refresh at $nextRefreshTime (target=$targetBoundary min, delay=$actualDelayMs ms)")

        scheduleAlarmAt(alarmManager, appContext, nextRefreshTime)
    }

    /**
     * 在指定时间调度Alarm。
     */
    private fun scheduleAlarmAt(
        alarmManager: AlarmManager,
        context: Context,
        triggerTime: ZonedDateTime
    ) {
        val triggerAtMillis = triggerTime.toInstant().toEpochMilli()

        // Create pending intent for FocusWindowReceiver
        val intent = Intent(FocusWindowReceiver.ACTION_FOCUS_WINDOW_UPDATE).apply {
            setClass(context, FocusWindowReceiver::class.java)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Schedule alarm (SYS-03, SYS-04: permission handling)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: check permission
            val canScheduleExact = alarmManager.canScheduleExactAlarms()

            if (canScheduleExact) {
                // Use setAlarmClock for highest priority (SYS-01)
                val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerAtMillis, pendingIntent)
                alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
                Log.d(TAG, "Scheduled exact alarm using setAlarmClock")
            } else {
                // Permission denied: use setWindow fallback (SYS-04)
                alarmManager.setWindow(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    WINDOW_TOLERANCE_MS,
                    pendingIntent
                )
                Log.d(TAG, "Scheduled inexact alarm using setWindow (permission denied)")
            }
        } else {
            // Android 11-: exact alarms allowed
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            Log.d(TAG, "Scheduled exact alarm using setExactAndAllowWhileIdle")
        }
    }

    /**
     * Cancel any pending FocusWidget refresh alarm.
     */
    fun cancelScheduledRefresh(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(FocusWindowReceiver.ACTION_FOCUS_WINDOW_UPDATE).apply {
            setClass(appContext, FocusWindowReceiver::class.java)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "Cancelled pending FocusWidget refresh alarm")
    }

    /**
     * Schedule next refresh when there's an active timer running.
     * Refresh every minute to keep timer progress synchronized in widget.
     */
    fun scheduleNextRefreshForActiveTimer(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val currentTime = ZonedDateTime.now()

        // Schedule refresh at next minute boundary
        val nextMinuteRefresh = currentTime
            .plusMinutes(1)
            .withSecond(0)
            .withNano(0)

        Log.d(TAG, "Active timer mode: scheduling refresh at next minute ($nextMinuteRefresh)")
        scheduleAlarmAt(alarmManager, appContext, nextMinuteRefresh)
    }
}