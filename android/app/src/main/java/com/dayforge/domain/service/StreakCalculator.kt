package com.dayforge.domain.service

import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.util.DateTimeUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object StreakCalculator {

    /**
     * Calculates the current streak from completion records.
     * Uses local timezone for day boundaries.
     *
     * Streak can start from today or yesterday:
     * - If completed today: streak includes today
     * - If completed yesterday but not today: streak ends yesterday
     * - If not completed today or yesterday: streak is 0
     *
     * @param completions List of completion entities, ordered by date (any order)
     * @param timeZone Time zone for date calculations (defaults to UTC, unused in this implementation)
     * @return Number of consecutive days ending today or yesterday
     */
    @Suppress("UNUSED_PARAMETER")
    fun calculateCurrentStreak(
        completions: List<CompletionEntity>,
        timeZone: TimeZone = TimeZone.UTC
    ): Int {
        if (completions.isEmpty()) return 0
        return calculateCurrentStreakFromDates(completions.map { it.date })
    }

    /**
     * Calculates the current streak from a list of dates.
     * Uses local timezone for day boundaries.
     *
     * @param dates List of timestamps (millis since epoch)
     * @return Number of consecutive days ending today or yesterday
     */
    fun calculateCurrentStreakFromDates(dates: List<Long>, today: LocalDate = DateTimeUtils.today()): Int {
        if (dates.isEmpty()) return 0

        // Get distinct dates (normalized to day boundaries using local timezone) sorted descending
        val completedDays = dates
            .map { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
            .distinct()
            .sortedDescending()

        if (completedDays.isEmpty()) return 0

        val yesterday = today.minusDays(1)

        // Determine the starting day for streak calculation
        // Streak can start from today or yesterday
        val streakStartDay = when (completedDays.first()) {
            today -> today
            yesterday -> yesterday
            else -> return 0 // No completion today or yesterday, streak is broken
        }

        var streak = 0
        var expectedDay = streakStartDay

        for (completedDay in completedDays) {
            when {
                completedDay == expectedDay -> {
                    streak++
                    expectedDay = expectedDay.minusDays(1)
                }
                completedDay < expectedDay -> {
                    // Gap found, streak ends
                    break
                }
                // completedDay > expectedDay: skip (shouldn't happen with sorted data)
            }
        }

        return streak
    }

    /**
     * Calculates the best (longest) streak from completion records.
     * Uses local timezone for day boundaries.
     *
     * @param completions List of completion entities, ordered by date (any order)
     * @param timeZone Time zone for date calculations (defaults to UTC, unused in this implementation)
     * @return Longest consecutive sequence in entire history
     */
    @Suppress("UNUSED_PARAMETER")
    fun calculateBestStreak(
        completions: List<CompletionEntity>,
        timeZone: TimeZone = TimeZone.UTC
    ): Int {
        if (completions.isEmpty()) return 0
        return calculateBestStreakFromDates(completions.map { it.date })
    }

    /**
     * Calculates the best (longest) streak from a list of dates.
     * Uses local timezone for day boundaries.
     *
     * @param dates List of timestamps (millis since epoch)
     * @return Longest consecutive sequence in entire history
     */
    fun calculateBestStreakFromDates(dates: List<Long>): Int {
        if (dates.isEmpty()) return 0

        // Get distinct dates (normalized to day boundaries using local timezone) sorted ascending
        val days = dates
            .map { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
            .distinct()
            .sorted()

        var bestStreak = 1
        var currentStreak = 1

        for (i in 1 until days.size) {
            if (days[i] == days[i - 1].plusDays(1)) {
                currentStreak++
                bestStreak = maxOf(bestStreak, currentStreak)
            } else {
                currentStreak = 1
            }
        }

        return bestStreak
    }

    /**
     * Calculates the current streak for habits with a target value (e.g., COUNTING habits).
     * Only days where the sum of values meets or exceeds the target count toward the streak.
     *
     * @param completions List of completion entities with values
     * @param targetValue The minimum sum required for a day to count as "completed"
     * @param timeZone Time zone for date calculations (defaults to UTC, unused in this implementation)
     * @return Number of consecutive days ending today or yesterday where target was met
     */
    @Suppress("UNUSED_PARAMETER")
    fun calculateCurrentStreakWithTarget(
        completions: List<CompletionEntity>,
        targetValue: Int,
        timeZone: TimeZone = TimeZone.UTC
    ): Int {
        if (completions.isEmpty()) return 0

        // Group completions by day and filter for days meeting the target
        val targetMetDates = completions
            .groupBy { DateTimeUtils.normalizeToDay(it.date) }
            .filter { (_, dayCompletions) -> dayCompletions.sumOf { it.value } >= targetValue }
            .keys
            .toList()

        return calculateCurrentStreakFromDates(targetMetDates)
    }

    /**
     * Calculates the best (longest) streak for habits with a target value (e.g., COUNTING habits).
     * Only days where the sum of values meets or exceeds the target count toward the streak.
     *
     * @param completions List of completion entities with values
     * @param targetValue The minimum sum required for a day to count as "completed"
     * @param timeZone Time zone for date calculations (defaults to UTC, unused in this implementation)
     * @return Longest consecutive sequence in entire history where target was met
     */
    @Suppress("UNUSED_PARAMETER")
    fun calculateBestStreakWithTarget(
        completions: List<CompletionEntity>,
        targetValue: Int,
        timeZone: TimeZone = TimeZone.UTC
    ): Int {
        if (completions.isEmpty()) return 0

        // Group completions by day and filter for days meeting the target
        val targetMetDates = completions
            .groupBy { DateTimeUtils.normalizeToDay(it.date) }
            .filter { (_, dayCompletions) -> dayCompletions.sumOf { it.value } >= targetValue }
            .keys
            .toList()

        return calculateBestStreakFromDates(targetMetDates)
    }
}

/**
 * Simple TimeZone wrapper for API compatibility.
 * In a full implementation, this would use kotlinx.datetime.TimeZone.
 */
sealed class TimeZone {
    object UTC : TimeZone()

    companion object {
        fun currentSystemDefault(): TimeZone = UTC
    }
}
