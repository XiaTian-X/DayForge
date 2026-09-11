package com.dayforge.domain.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.util.DateTimeUtils
import com.dayforge.widget.timer.CountdownDiscardActivity

/**
 * Shared timer management logic extracted from DashboardViewModel and NestedViewModel.
 * Handles starting, pausing, resuming, and stopping timers.
 */
class TimerManager(
    private val context: Context,
    private val habitDao: HabitDao,
    private val timeLogDao: TimeLogDao
) {
    /**
     * Start a timer for a habit if not already completed today.
     * @param habitId ID of the habit to start timer for
     * @param targetMinutes Target duration in minutes
     */
    suspend fun startTimer(habitId: Long, targetMinutes: Int) {
        val todayStart = DateTimeUtils.startOfDayMillis()
        val todayEnd = todayStart + DateTimeUtils.MILLIS_PER_DAY
        val completedSeconds = timeLogDao.getCompletedDurationSecondsForDate(
            habitId, java.time.LocalDate.now().toString(), todayStart, todayEnd
        )
        val targetSeconds = targetMinutes * 60

        if (completedSeconds >= targetSeconds) {
            return
        }

        val intent = Intent(context, TimerService::class.java).apply {
            action = TimerService.ACTION_START
            putExtra(TimerService.EXTRA_HABIT_ID, habitId)
            putExtra(TimerService.EXTRA_TARGET_MINUTES, targetMinutes)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Pause the currently running timer.
     * @param habitId Current habit ID
     * @param targetMinutes Current target minutes
     */
    fun pauseTimer(habitId: Long, targetMinutes: Int) {
        val intent = Intent(context, TimerService::class.java).apply {
            action = TimerService.ACTION_PAUSE
            putExtra(TimerService.EXTRA_HABIT_ID, habitId)
            putExtra(TimerService.EXTRA_TARGET_MINUTES, targetMinutes)
        }
        context.startService(intent)
    }

    /**
     * Resume a paused timer.
     * @param habitId Current habit ID
     * @param targetMinutes Current target minutes
     */
    fun resumeTimer(habitId: Long, targetMinutes: Int) {
        val intent = Intent(context, TimerService::class.java).apply {
            action = TimerService.ACTION_RESUME
            putExtra(TimerService.EXTRA_HABIT_ID, habitId)
            putExtra(TimerService.EXTRA_TARGET_MINUTES, targetMinutes)
        }
        context.startService(intent)
    }

    /**
     * Stop the currently running timer.
     * Shows confirmation dialog for incomplete sessions.
     * @param habitId Current habit ID
     * @param targetMinutes Current target minutes
     * @return Habit ID if timer was stopped and metric dialog should be shown, null otherwise
     */
    suspend fun stopTimer(
        habitId: Long,
        targetMinutes: Int
    ): Long? {
        val habit = habitDao.getHabitById(habitId)
        val activeLog = timeLogDao.getActiveTimeLog()
        val isCountdown = habit?.isCountdown == true

        val elapsedSeconds = if (activeLog != null && activeLog.habitId == habitId) {
            calculateElapsedSeconds(activeLog)
        } else 0
        val targetSeconds = (habit?.targetValue ?: 0) * 60

        val isIncomplete = if (isCountdown) {
            (targetSeconds - elapsedSeconds) > 0
        } else {
            elapsedSeconds < targetSeconds
        }

        if (isIncomplete && activeLog != null && activeLog.habitId == habitId) {
            val seconds = if (isCountdown) {
                targetSeconds - elapsedSeconds
            } else {
                elapsedSeconds
            }
            val intent = CountdownDiscardActivity.createIntent(
                context,
                habitId,
                targetMinutes,
                seconds,
                isCountdown
            )
            context.startActivity(intent)
            return null
        } else {
            val intent = Intent(context, TimerService::class.java).apply {
                action = TimerService.ACTION_STOP
                putExtra(TimerService.EXTRA_HABIT_ID, habitId)
                putExtra(TimerService.EXTRA_TARGET_MINUTES, targetMinutes)
            }
            context.startService(intent)
            return habitId
        }
    }

    /**
     * Calculate elapsed seconds for an active timer session.
     */
    fun calculateElapsedSeconds(activeLog: TimeLogEntity): Int {
        return TimerElapsedCalculator.elapsedSeconds(activeLog, context)
    }
}
