package com.dayforge.domain.service

import com.dayforge.data.model.HabitType
import java.time.ZonedDateTime

/**
 * Represents the time window match result for a habit.
 * Used by HabitPriorityCalculator to sort habits by time relevance.
 */
sealed class TimeMatchResult {
    /**
     * Habit is within its optimal execution window.
     * @param score Match score from 0.0 (edge) to 1.0 (perfect at bestTime)
     * @param windowStart Start of the time window in minutes since midnight
     * @param windowEnd End of the time window in minutes since midnight
     * @param currentTimeMinutes Current time in minutes since midnight
     * @param bestTimeMinutes Best execution time in minutes since midnight
     */
    data class InWindow(
        val score: Float,
        val windowStart: Int,
        val windowEnd: Int,
        val currentTimeMinutes: Int,
        val bestTimeMinutes: Int
    ) : TimeMatchResult()

    /**
     * Current time is before the habit's window.
     * @param minutesUntilWindow Minutes until window starts
     * @param windowStart Start of the time window in minutes since midnight
     */
    data class BeforeWindow(
        val minutesUntilWindow: Int,
        val windowStart: Int
    ) : TimeMatchResult()

    /**
     * Current time is after the habit's window.
     * @param minutesSinceWindowEnd Minutes since window ended
     * @param windowEnd End of the time window in minutes since midnight
     */
    data class AfterWindow(
        val minutesSinceWindowEnd: Int,
        val windowEnd: Int
    ) : TimeMatchResult()

    /**
     * Habit has no bestTime set - no time preference.
     */
    object NoBestTime : TimeMatchResult()
}

/**
 * Calculates time window matches for habits.
 *
 * Window widths per SORT-01:
 * - CHECK_IN: ±15 minutes (total width 30 minutes)
 * - COUNTING: ±15 minutes (total width 30 minutes) per slot
 * - TIMER: ±targetValue minutes (total width 2 * targetValue minutes)
 * - GOAL: No window (never matches)
 */
object TimeWindowMatcher {
    private const val DEFAULT_WINDOW_HALF_WIDTH = 15 // minutes

    /**
     * Calculates the time window width in minutes based on habit type.
     *
     * @param habitType Type of habit
     * @param targetValue Target value for TIMER habits (determines window width)
     * @return Total window width in minutes (0 for GOAL)
     */
    fun calculateWindowWidth(habitType: HabitType, targetValue: Int): Int {
        return when (habitType) {
            HabitType.CHECK_IN -> DEFAULT_WINDOW_HALF_WIDTH * 2
            HabitType.COUNTING -> DEFAULT_WINDOW_HALF_WIDTH * 2
            HabitType.TIMER -> targetValue * 2 // ±targetValue minutes
            HabitType.GOAL -> 0 // GOAL habits don't participate in time matching
        }
    }

    /**
     * Calculates the time window match for a habit at the current time.
     * Uses ZonedDateTime for DST-safe calculations (SORT-06).
     *
     * @param bestTimeMinutes Best execution time in minutes since midnight (nullable)
     * @param habitType Type of habit (determines window width)
     * @param targetValue Target value for TIMER habits (determines window width)
     * @param currentTime Current ZonedDateTime
     * @return TimeMatchResult indicating match status and score
     */
    fun calculateMatch(
        bestTimeMinutes: Long?,
        habitType: HabitType,
        targetValue: Int,
        currentTime: ZonedDateTime
    ): TimeMatchResult {
        // No bestTime set means no time preference
        if (bestTimeMinutes == null) {
            return TimeMatchResult.NoBestTime
        }

        // GOAL habits don't participate in time matching
        if (habitType == HabitType.GOAL) {
            return TimeMatchResult.NoBestTime
        }

        val bestTime = bestTimeMinutes.toInt()
        val windowWidth = calculateWindowWidth(habitType, targetValue)
        val halfWidth = windowWidth / 2

        // Get current time in minutes since midnight
        val currentMinutes = currentTime.hour * 60 + currentTime.minute

        val windowStart = bestTime - halfWidth
        val windowEnd = bestTime + halfWidth

        // Calculate match result
        return when {
            currentMinutes < windowStart -> {
                TimeMatchResult.BeforeWindow(
                    minutesUntilWindow = windowStart - currentMinutes,
                    windowStart = windowStart
                )
            }
            currentMinutes > windowEnd -> {
                TimeMatchResult.AfterWindow(
                    minutesSinceWindowEnd = currentMinutes - windowEnd,
                    windowEnd = windowEnd
                )
            }
            else -> {
                // In window: calculate score based on proximity to bestTime
                val distanceFromBest = kotlin.math.abs(currentMinutes - bestTime)
                val score = 1.0f - (distanceFromBest.toFloat() / halfWidth.toFloat())
                TimeMatchResult.InWindow(
                    score = score.coerceIn(0.0f, 1.0f),
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    currentTimeMinutes = currentMinutes,
                    bestTimeMinutes = bestTime
                )
            }
        }
    }
}