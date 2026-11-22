package com.dayforge.domain.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.util.DateTimeUtils
import com.dayforge.widget.timer.CountdownDiscardActivity
import com.dayforge.domain.model.TimerActionAuthority
import com.dayforge.data.repository.NextTimerWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Shared timer management logic extracted from DashboardViewModel and NestedViewModel.
 * Handles starting, pausing, resuming, and stopping timers.
 */
class TimerManager private constructor(
    private val context: Context,
    private val fixedHabitDao: HabitDao?,
    private val fixedTimeLogDao: TimeLogDao?,
    private val fixedTimerWriter: NextTimerWriter?,
    private val fixedTimerHistoryReader: com.dayforge.data.repository.TimerHistoryReader?,
    private val databases: com.dayforge.data.local.DatabaseRuntimeHost?
) {
    constructor(context: Context, habitDao: HabitDao, timeLogDao: TimeLogDao,
        timerWriter: NextTimerWriter? = null, timerHistoryReader: com.dayforge.data.repository.TimerHistoryReader? = null) :
        this(context, habitDao, timeLogDao, timerWriter, timerHistoryReader, null)
    @Inject internal constructor(@ApplicationContext context: Context, databases: com.dayforge.data.local.DatabaseRuntimeHost) :
        this(context, null, null, null, null, databases)
    private val habitDao get() = fixedHabitDao ?: requireNotNull(databases).boundGraph().habitDao
    private val timeLogDao get() = fixedTimeLogDao ?: requireNotNull(databases).boundGraph().timeLogDao
    private val timerWriter get() = if (databases == null) fixedTimerWriter else databases.boundGraph().nextTimerWriter
    private val timerHistoryReader get() = if (databases == null) fixedTimerHistoryReader else databases.boundGraph().timerHistoryReader

    /** Restores foreground execution for a persisted running or paused timer. */
    suspend fun recoverRunningTimer() {
        if (databases == null) recoverRunningTimerInGraph()
        else databases.withGraph(com.dayforge.data.local.DatabaseRuntimeHost.Work.CONTROL) { recoverRunningTimerInGraph() }
    }
    private suspend fun recoverRunningTimerInGraph() {
        val activeLog = timeLogDao.getActiveTimeLog() ?: return
        val habit = habitDao.getHabitById(activeLog.habitId) ?: return
        val intent = if (databases != null) {
            // This method is an explicit system read/recovery entry, not an old user action.
            // The existing null-action service path reads and authenticates persisted state;
            // it must never treat an active-session claim as a new START request.
            Intent(context, TimerService::class.java)
        } else Intent(context, TimerService::class.java).apply {
            action = TimerService.ACTION_START
            putExtra(TimerService.EXTRA_HABIT_ID, activeLog.habitId)
            putExtra(TimerService.EXTRA_TARGET_MINUTES, habit.targetValue)
            putExtra(TimerService.EXTRA_IS_COUNTDOWN, habit.isCountdown)
            timerWriter?.capture(habit.id)?.attach(this)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Start a timer for a habit if not already completed today.
     * @param habitId ID of the habit to start timer for
     * @param targetMinutes Target duration in minutes
     */
    suspend fun startTimer(habitId: Long, targetMinutes: Int, authority: TimerActionAuthority? = null,
        expectedHabit: com.dayforge.data.local.entity.HabitEntity? = null) {
        val legacy = if (databases != null && authority == null) {
            val original = requireNotNull(expectedHabit) { "DATABASE_EPOCH_INPUT_REQUIRED" }
            check(original.id == habitId && original.appearance == null) { "DATABASE_EPOCH_SELECTION_CHANGED" }
            requireNotNull(timerWriter).captureLegacyForDisplay(original, timeLogDao.getActiveTimeLogForHabit(habitId))
        } else null
        timerWriter?.requireAction(habitId, authority, legacy)
        val todayStart = DateTimeUtils.startOfDayMillis()
        val todayEnd = DateTimeUtils.startOfNextDayMillis(todayStart)
        val habit = habitDao.getHabitById(habitId) ?: return
        val completedSeconds: Long = if (habit.appearance != null) requireNotNull(timerHistoryReader) { "TIMER_READER_REQUIRED" }
            .readProjection(habit).todaySeconds else timeLogDao.getCompletedDurationSecondsForDate(
            habitId, java.time.LocalDate.now().toString(), todayStart, todayEnd
        ).toLong()
        val targetSeconds = targetMinutes * 60

        if (targetSeconds > 0 && completedSeconds >= targetSeconds) {
            return
        }

        val intent = Intent(context, TimerService::class.java).apply {
            action = TimerService.ACTION_START
            putExtra(TimerService.EXTRA_HABIT_ID, habitId)
            putExtra(TimerService.EXTRA_TARGET_MINUTES, targetMinutes)
            authority?.attach(this); legacy?.attach(this)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Pause the currently running timer.
     * @param habitId Current habit ID
     * @param targetMinutes Current target minutes
     */
    fun pauseTimer(habitId: Long, targetMinutes: Int, authority: TimerActionAuthority? = null,
        legacyClaim: com.dayforge.data.repository.LegacyWidgetTimerClaim? = null) {
        if (databases != null) {
            databases.boundGraph()
            check(authority != null || legacyClaim != null) { "DATABASE_EPOCH_INPUT_REQUIRED" }
        }
        val intent = Intent(context, TimerService::class.java).apply {
            action = TimerService.ACTION_PAUSE
            putExtra(TimerService.EXTRA_HABIT_ID, habitId)
            putExtra(TimerService.EXTRA_TARGET_MINUTES, targetMinutes)
            authority?.attach(this); legacyClaim?.attach(this)
        }
        context.startService(intent)
    }

    /**
     * Resume a paused timer.
     * @param habitId Current habit ID
     * @param targetMinutes Current target minutes
     */
    fun resumeTimer(habitId: Long, targetMinutes: Int, authority: TimerActionAuthority? = null,
        legacyClaim: com.dayforge.data.repository.LegacyWidgetTimerClaim? = null) {
        if (databases != null) {
            databases.boundGraph()
            check(authority != null || legacyClaim != null) { "DATABASE_EPOCH_INPUT_REQUIRED" }
        }
        val intent = Intent(context, TimerService::class.java).apply {
            action = TimerService.ACTION_RESUME
            putExtra(TimerService.EXTRA_HABIT_ID, habitId)
            putExtra(TimerService.EXTRA_TARGET_MINUTES, targetMinutes)
            authority?.attach(this); legacyClaim?.attach(this)
        }
        context.startService(intent)
    }

    /**
     * Stop the currently running timer.
     * Shows confirmation dialog for incomplete sessions.
     * @param habitId Current habit ID
     * @param targetMinutes Current target minutes
     * @return Habit ID if the stop command was accepted, null when confirmation is required
     */
    suspend fun stopTimer(
        habitId: Long,
        targetMinutes: Int,
        authority: TimerActionAuthority? = null,
        legacyClaim: com.dayforge.data.repository.LegacyWidgetTimerClaim? = null
    ): Long? {
        if (databases != null) check(authority != null || legacyClaim != null) { "DATABASE_EPOCH_INPUT_REQUIRED" }
        timerWriter?.requireAction(habitId, authority, legacyClaim)
        val habit = habitDao.getHabitById(habitId)
        val activeLog = timeLogDao.getActiveTimeLog()
        val policy = activeLog?.takeIf { it.habitId == habitId }?.let { timerWriter?.policy(habitId, it.uuid) }
        val isCountdown = policy?.isCountdown ?: (habit?.isCountdown == true)

        val elapsedSeconds = if (activeLog != null && activeLog.habitId == habitId) {
            calculateElapsedSeconds(activeLog)
        } else 0
        val targetSeconds = policy?.targetSeconds ?: ((habit?.targetValue ?: 0) * 60)

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
                policy?.targetSeconds?.div(60) ?: targetMinutes,
                seconds,
                isCountdown,
                authority,
                if (authority == null) legacyClaim ?: requireNotNull(timerWriter).captureLegacyConfirmation(requireNotNull(habit), activeLog) else null
            )
            context.startActivity(intent)
            return null
        } else {
            val intent = Intent(context, TimerService::class.java).apply {
                action = TimerService.ACTION_STOP
                putExtra(TimerService.EXTRA_HABIT_ID, habitId)
                putExtra(TimerService.EXTRA_TARGET_MINUTES, targetMinutes)
                authority?.attach(this); legacyClaim?.attach(this)
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
