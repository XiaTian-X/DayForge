package com.dayforge.domain.service

import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.model.HabitType
import com.dayforge.data.model.HabitWithStats
import com.dayforge.util.DateTimeUtils
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for calculating habit status.
 * Centralizes all status calculation logic to ensure consistency across:
 * - DashboardViewModel
 * - ProfileViewModel
 * - Widgets (ProgressWidget, MotivationWidget)
 *
 * Reuses existing services:
 * - ScheduleValidator for check-in day validation
 * - StreakCalculator for streak calculation
 * - FailureChecker for failure status
 */
@Singleton
class HabitStatusCalculator @Inject constructor(
    private val failureChecker: FailureChecker,
    private val completionDao: CompletionDao,
    private val timeLogDao: TimeLogDao
) {
    /**
     * Calculate complete status for a habit.
     *
     * @param habit The habit entity
     * @param completions Optional pre-fetched completions (for batch operations). If null, fetches from DAO.
     * @param timeLogs Optional pre-fetched time logs (for TIMER habits). If null, fetches from DAO.
     * @return HabitWithStats with all calculated fields
     */
    suspend fun calculate(
        habit: HabitEntity,
        completions: List<CompletionEntity>? = null,
        timeLogs: List<TimeLogEntity>? = null
    ): HabitWithStats {
        // Fetch data if not provided
        val habitCompletions = completions?.filter { it.habitId == habit.id }
            ?: completionDao.getCompletionsByHabit(habit.id).first()
        val habitTimeLogs = timeLogs?.filter { it.habitId == habit.id }
            ?: timeLogDao.getAllTimeLogsForHabit(habit.id)

        // Calculate based on habit type
        return when (habit.habitType) {
            HabitType.TIMER -> calculateTimerHabit(habit, habitTimeLogs)
            HabitType.CHECK_IN,
            HabitType.COUNTING -> calculateCompletionHabit(habit, habitCompletions)
            HabitType.GOAL -> calculateGoalHabit(habit)
        }
    }

    /**
     * Calculate status for GOAL habits (container for child habits).
     * GOAL habits don't have check-ins, only track day progress towards targetCycles.
     */
    private fun calculateGoalHabit(habit: HabitEntity): HabitWithStats {
        // Calculate days since creation
        val creationDate = Instant.ofEpochMilli(habit.createdAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        val today = LocalDate.now()
        val daysSinceCreation = ChronoUnit.DAYS.between(creationDate, today).toInt() + 1  // +1 to include today

        // Target progress is days since creation (for display "第 X 天 / 共 Y 天")
        val targetProgress = daysSinceCreation

        // GOAL habits are always "active" for display purposes until completed
        val isCheckInAllowed = true

        return HabitWithStats(
            habit = habit,
            completedToday = false,  // GOAL habits don't have check-ins
            todayCount = 0,
            lastCompletionId = null,
            currentStreak = 0,  // GOAL habits don't have streaks
            bestStreak = 0,
            activityRate = habit.activityRate,
            isCheckInAllowed = isCheckInAllowed,
            nextCheckInDate = null,
            targetProgress = targetProgress,
            hasFailed = false  // GOAL habits use goalSuccess field instead
        )
    }

    /**
     * Calculate status for TIMER habits (uses TimeLogEntity).
     */
    private suspend fun calculateTimerHabit(
        habit: HabitEntity,
        timeLogs: List<TimeLogEntity>
    ): HabitWithStats {
        val todayStart = DateTimeUtils.startOfDayMillis()
        val todayEnd = todayStart + DateTimeUtils.MILLIS_PER_DAY

        // Today's time logs
        val todayLogs = timeLogs.filter { it.date in todayStart until todayEnd }
        val todayCount = todayLogs.sumOf { it.durationSeconds }

        // Streaks - only count days where target was met
        val targetSeconds = habit.targetValue * 60
        val completedDates = timeLogs
            .groupBy { it.date }
            .filter { (_, logs) -> logs.sumOf { it.durationSeconds } >= targetSeconds }
            .keys
            .toList()

        val currentStreak = StreakCalculator.calculateCurrentStreakFromDates(completedDates)
        val bestStreak = StreakCalculator.calculateBestStreakFromDates(completedDates)

        // Completed today?
        val completedToday = todayCount >= targetSeconds

        // Check-in day validation
        val isCheckInAllowed = ScheduleValidator.isCheckInAllowedToday(habit.schedule, habit.createdAt)
        val nextCheckInDate = if (!isCheckInAllowed) {
            ScheduleValidator.getNextCheckInDate(habit.schedule, habit.createdAt)
        } else null

        // Target progress - count days where target was met
        // For COUNTING habits, use target-based filtering; for TIMER, already filtered above
        val targetProgress = if (habit.targetCycles != null) {
            // Use TargetMetChecker to count days meeting the target
            TargetMetChecker.countTimerTargetMetDays(timeLogs, targetSeconds)
        } else 0

        // Failure status
        val hasFailed = if (habit.targetCycles != null) {
            val firstDate = timeLogDao.getFirstTimeLogDate(habit.id)
            val firstLocalDate = firstDate?.let { millisToLocalDate(it) }
            failureChecker.hasFailed(habit, firstLocalDate)
        } else false

        return HabitWithStats(
            habit = habit,
            completedToday = completedToday,
            todayCount = todayCount,
            lastCompletionId = todayLogs.maxByOrNull { it.id }?.id,
            currentStreak = currentStreak,
            bestStreak = bestStreak,
            activityRate = habit.activityRate,
            isCheckInAllowed = isCheckInAllowed,
            nextCheckInDate = nextCheckInDate,
            targetProgress = targetProgress,
            hasFailed = hasFailed
        )
    }

    /**
     * Calculate status for CHECK_IN and COUNTING habits (uses CompletionEntity).
     */
    private suspend fun calculateCompletionHabit(
        habit: HabitEntity,
        completions: List<CompletionEntity>
    ): HabitWithStats {
        val todayStart = DateTimeUtils.startOfDayMillis()

        // Today's completions
        val todayCompletions = completions.filter { it.date == todayStart }
        val todayCount = todayCompletions.sumOf { it.value }

        // Streaks - for COUNTING habits, only count days where target was met
        val (currentStreak, bestStreak) = when (habit.habitType) {
            HabitType.COUNTING -> {
                // Only days with sum >= targetValue count toward streak
                val targetValue = habit.targetValue
                Pair(
                    StreakCalculator.calculateCurrentStreakWithTarget(completions, targetValue),
                    StreakCalculator.calculateBestStreakWithTarget(completions, targetValue)
                )
            }
            else -> {
                // CHECK_IN: any completion counts
                Pair(
                    StreakCalculator.calculateCurrentStreak(completions),
                    StreakCalculator.calculateBestStreak(completions)
                )
            }
        }

        // Completed today?
        val completedToday = when (habit.habitType) {
            HabitType.CHECK_IN -> todayCount > 0
            HabitType.COUNTING -> todayCount >= habit.targetValue
            else -> false
        }

        // Check-in day validation
        val isCheckInAllowed = ScheduleValidator.isCheckInAllowedToday(habit.schedule, habit.createdAt)
        val nextCheckInDate = if (!isCheckInAllowed) {
            ScheduleValidator.getNextCheckInDate(habit.schedule, habit.createdAt)
        } else null

        // Target progress - count days where target was met
        val targetProgress = when {
            habit.targetCycles == null -> 0
            habit.habitType == HabitType.COUNTING -> {
                // For COUNTING habits, only count days where sum >= targetValue
                TargetMetChecker.countTargetMetDays(completions, habit.targetValue)
            }
            else -> {
                // For CHECK_IN habits, any completion counts
                completionDao.getDistinctDayCount(habit.id)
            }
        }

        // Failure status
        val hasFailed = if (habit.targetCycles != null) {
            val firstDate = completionDao.getFirstCompletionDate(habit.id)
            val firstLocalDate = firstDate?.let { millisToLocalDate(it) }
            failureChecker.hasFailed(habit, firstLocalDate)
        } else false

        return HabitWithStats(
            habit = habit,
            completedToday = completedToday,
            todayCount = todayCount,
            lastCompletionId = todayCompletions.maxByOrNull { it.id }?.id,
            currentStreak = currentStreak,
            bestStreak = bestStreak,
            activityRate = habit.activityRate,
            isCheckInAllowed = isCheckInAllowed,
            nextCheckInDate = nextCheckInDate,
            targetProgress = targetProgress,
            hasFailed = hasFailed
        )
    }

    /**
     * Convert millis to LocalDate.
     */
    private fun millisToLocalDate(millis: Long): LocalDate {
        return Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
    }
}