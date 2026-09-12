package com.dayforge.domain.service

import com.dayforge.data.local.businessDate
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.model.HabitType
import com.dayforge.util.DateTimeUtils
import java.time.LocalDate

/**
 * Utility for checking if a habit has met its target on a given day.
 *
 * This centralizes the "target met" logic for:
 * - COUNTING habits: sum of values >= targetValue
 * - CHECK_IN habits: any completion counts (no target threshold)
 * - TIMER habits: sum of duration >= targetSeconds
 *
 * Used by:
 * - StreakCalculator (already has similar logic, can be refactored to use this)
 * - FailureChecker (to determine if a day was "completed" for STRICT mode)
 * - HabitStatusCalculator (for targetProgress calculation)
 * - Widgets (for progress display)
 */
object TargetMetChecker {

    /**
     * Get the list of dates where the target was met.
     *
     * @param completions List of completion entities
     * @param targetValue The minimum sum required for a day to count as "completed"
     * @return Captured business dates where target was met
     */
    fun getTargetMetDates(completions: List<CompletionEntity>, targetValue: Int): List<LocalDate> {
        if (completions.isEmpty() || targetValue <= 0) return emptyList()

        return completions
            .groupBy { it.businessDate }
            .filter { (_, dayCompletions) -> dayCompletions.sumOf { it.value } >= targetValue }
            .keys
            .toList()
    }

    /**
     * Count the number of days where the target was met.
     *
     * @param completions List of completion entities
     * @param targetValue The minimum sum required for a day to count as "completed"
     * @return Number of distinct days meeting the target
     */
    fun countTargetMetDays(completions: List<CompletionEntity>, targetValue: Int): Int {
        return getTargetMetDates(completions, targetValue).size
    }

    /**
     * Check if the target was met on a specific date.
     *
     * @param completions List of completion entities
     * @param date The business date to check
     * @param targetValue The minimum sum required
     * @return true if sum of values on that date >= targetValue
     */
    fun isTargetMetOnDate(completions: List<CompletionEntity>, date: LocalDate, targetValue: Int): Boolean {
        if (targetValue <= 0) return false

        val dayCompletions = completions.filter { it.businessDate == date }
        return dayCompletions.sumOf { it.value } >= targetValue
    }

    /**
     * Get the list of dates where the timer target was met.
     *
     * @param timeLogs List of time log entities
     * @param targetSeconds The minimum duration in seconds required
     * @return List of normalized day timestamps where target was met
     */
    fun getTimerTargetMetDates(timeLogs: List<TimeLogEntity>, targetSeconds: Int): List<Long> {
        if (timeLogs.isEmpty() || targetSeconds <= 0) return emptyList()

        return timeLogs
            .groupBy { DateTimeUtils.normalizeToDay(it.date) }
            .filter { (_, dayLogs) -> dayLogs.sumOf { it.durationSeconds } >= targetSeconds }
            .keys
            .toList()
    }

    /**
     * Count the number of days where the timer target was met.
     *
     * @param timeLogs List of time log entities
     * @param targetSeconds The minimum duration in seconds required
     * @return Number of distinct days meeting the target
     */
    fun countTimerTargetMetDays(timeLogs: List<TimeLogEntity>, targetSeconds: Int): Int {
        return getTimerTargetMetDates(timeLogs, targetSeconds).size
    }

    /**
     * Check if the timer target was met on a specific date.
     *
     * @param timeLogs List of time log entities
     * @param dateMillis The normalized day timestamp to check
     * @param targetSeconds The minimum duration in seconds required
     * @return true if sum of duration on that date >= targetSeconds
     */
    fun isTimerTargetMetOnDate(timeLogs: List<TimeLogEntity>, dateMillis: Long, targetSeconds: Int): Boolean {
        if (targetSeconds <= 0) return false

        val dayLogs = timeLogs.filter { DateTimeUtils.normalizeToDay(it.date) == dateMillis }
        return dayLogs.sumOf { it.durationSeconds } >= targetSeconds
    }

    /**
     * Universal method: Check if a habit met its target on a specific date.
     *
     * This method handles all habit types uniformly:
     * - CHECK_IN: any completion (value > 0) counts as met
     * - COUNTING: sum >= targetValue
     * - TIMER: sum of duration >= targetSeconds (use timeLogs parameter)
     * - GOAL: always false (GOAL habits don't have check-ins)
     *
     * @param habitType The type of habit
     * @param completions Completions for the date (for CHECK_IN/COUNTING)
     * @param timeLogs Time logs for the date (for TIMER)
     * @param targetValue Target value (count for COUNTING, minutes for TIMER converted to seconds)
     * @return true if the habit met its target on that date
     */
    fun isTargetMetOnDateUniversal(
        habitType: HabitType,
        completions: List<CompletionEntity> = emptyList(),
        timeLogs: List<TimeLogEntity> = emptyList(),
        targetValue: Int = 1
    ): Boolean {
        return when (habitType) {
            HabitType.CHECK_IN -> completions.any { it.value > 0 }
            HabitType.COUNTING -> completions.sumOf { it.value } >= targetValue
            HabitType.TIMER -> {
                val targetSeconds = targetValue * 60
                timeLogs.sumOf { it.durationSeconds } >= targetSeconds
            }
            HabitType.GOAL -> false  // GOAL habits don't have check-ins
        }
    }
}
