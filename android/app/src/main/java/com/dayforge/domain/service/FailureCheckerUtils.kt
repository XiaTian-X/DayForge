package com.dayforge.domain.service

import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitType
import com.dayforge.util.DateTimeUtils
import java.time.LocalDate
import java.time.ZoneId

/**
 * Shared utility functions for failure checking logic.
 * Extracted from FailureChecker and WidgetFailureChecker to reduce duplication.
 */
object FailureCheckerUtils {

    /**
     * Checks if a habit should be evaluated for failure.
     * Common pre-checks shared between FailureChecker and WidgetFailureChecker.
     *
     * @param habit The habit to check
     * @return true if the habit should be evaluated for failure, false to skip
     */
    fun shouldCheckFailure(habit: HabitEntity): Boolean {
        // GOAL type doesn't have failure check
        if (habit.habitType == HabitType.GOAL) return false

        // No target = no failure possible
        if (habit.targetCycles == null) return false

        // Inactive habits don't fail
        if (!habit.isActive) return false

        return true
    }

    /**
     * Checks if STRICT failure mode should be applied.
     *
     * @param habit The habit to check
     * @return true if STRICT mode is active
     */
    fun isStrictMode(habit: HabitEntity): Boolean {
        return habit.failMode == FailMode.STRICT
    }

    /**
     * Checks if the target was met on a specific date.
     *
     * For COUNTING habits: sum of values >= targetValue
     * For CHECK_IN habits: any completion counts
     * For TIMER habits: sum of duration >= targetSeconds
     *
     * @param habit The habit to check
     * @param completionDao DAO for completion data
     * @param timeLogDao DAO for time log data
     * @param date The date to check
     * @return true if target was met, false otherwise
     */
    suspend fun hasTargetMetOnDate(
        habit: HabitEntity,
        completionDao: CompletionDao,
        timeLogDao: TimeLogDao,
        date: LocalDate
    ): Boolean {
        val dateMillis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        return when (habit.habitType) {
            HabitType.TIMER -> {
                val targetSeconds = habit.targetValue * 60
                val completedSeconds = timeLogDao.getCompletedDurationSecondsForDate(
                    habit.id,
                    date.toString(),
                    dateMillis,
                    DateTimeUtils.startOfNextDayMillis(dateMillis)
                )
                completedSeconds >= targetSeconds
            }
            HabitType.COUNTING -> {
                val completions = completionDao.getCompletionsInRange(
                    habit.id,
                    date,
                    date.plusDays(1)
                )
                completions.sumOf { it.value } >= habit.targetValue
            }
            HabitType.CHECK_IN -> {
                completionDao.hasCompletionOnDate(habit.id, date)
            }
            HabitType.GOAL -> false
        }
    }

    /**
     * Checks strict failure for a habit.
     * Iterates through each day from first completion to yesterday.
     *
     * @param habit The habit to check
     * @param firstCompletionDate The date of first completion
     * @param completionDao DAO for completion data
     * @param timeLogDao DAO for time log data
     * @return true if the habit has failed, false otherwise
     */
    suspend fun checkStrictFailure(
        habit: HabitEntity,
        firstCompletionDate: LocalDate,
        completionDao: CompletionDao,
        timeLogDao: TimeLogDao
    ): Boolean {
        val today = LocalDate.now()
        var checkDate = firstCompletionDate

        while (checkDate < today) {
            if (ScheduleValidator.isCheckInAllowedOnDate(habit.schedule, habit.createdAt, checkDate)) {
                if (!hasTargetMetOnDate(habit, completionDao, timeLogDao, checkDate)) {
                    return true
                }
            }
            checkDate = checkDate.plusDays(1)
        }

        return false
    }
}
