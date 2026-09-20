package com.dayforge.domain.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.util.DateTimeUtils
import org.junit.Assert.*
import org.junit.Test

@RunWith(AndroidJUnit4::class)
class StreakCalculatorTest {

    @Test
    fun calculateCurrentStreak_emptyCompletions_returnsZero() {
        val completions = emptyList<CompletionEntity>()

        val streak = StreakCalculator.calculateCurrentStreak(completions)

        assertEquals("Empty completions should return 0", 0, streak)
    }

    @Test
    fun calculateCurrentStreak_todayOnly_returnsOne() {
        val today = getStartOfDayMillis()
        val completions = listOf(
            createCompletion(date = today)
        )

        val streak = StreakCalculator.calculateCurrentStreak(completions)

        assertEquals("Today only should return 1", 1, streak)
    }

    @Test
    fun calculateCurrentStreak_threeConsecutiveDaysIncludingToday_returnsThree() {
        val today = getStartOfDayMillis()
        val yesterday = today - DateTimeUtils.MILLIS_PER_DAY
        val twoDaysAgo = yesterday - DateTimeUtils.MILLIS_PER_DAY

        val completions = listOf(
            createCompletion(date = today),
            createCompletion(date = yesterday),
            createCompletion(date = twoDaysAgo)
        )

        val streak = StreakCalculator.calculateCurrentStreak(completions)

        assertEquals("Three consecutive days should return 3", 3, streak)
    }

    @Test
    fun calculateCurrentStreak_gapBeforeToday_returnsOne() {
        val today = getStartOfDayMillis()
        val twoDaysAgo = today - (2 * DateTimeUtils.MILLIS_PER_DAY)

        // Today is completed, yesterday is not - streak is 1 (just today)
        val completions = listOf(
            createCompletion(date = today),
            createCompletion(date = twoDaysAgo)
        )

        val streak = StreakCalculator.calculateCurrentStreak(completions)

        assertEquals("Gap before today means streak is just today = 1", 1, streak)
    }

    @Test
    fun calculateCurrentStreak_completedYesterdayNotToday_returnsOne() {
        val today = getStartOfDayMillis()
        val yesterday = today - DateTimeUtils.MILLIS_PER_DAY

        // Only yesterday is completed, not today - streak is 1 (just yesterday)
        // Original behavior: streak can start from today or yesterday
        val completions = listOf(
            createCompletion(date = yesterday)
        )

        val streak = StreakCalculator.calculateCurrentStreak(completions)

        assertEquals("Completed yesterday but not today means current streak is 1", 1, streak)
    }

    @Test
    fun calculateCurrentStreak_completedYesterdayAndDayBeforeNotToday_returnsTwo() {
        val today = getStartOfDayMillis()
        val yesterday = today - DateTimeUtils.MILLIS_PER_DAY
        val twoDaysAgo = yesterday - DateTimeUtils.MILLIS_PER_DAY

        // Yesterday and 2 days ago completed, but not today - streak is 2
        val completions = listOf(
            createCompletion(date = yesterday),
            createCompletion(date = twoDaysAgo)
        )

        val streak = StreakCalculator.calculateCurrentStreak(completions)

        assertEquals("Consecutive days ending yesterday should return 2", 2, streak)
    }

    @Test
    fun calculateCurrentStreak_notCompletedTodayOrYesterday_returnsZero() {
        val today = getStartOfDayMillis()
        val twoDaysAgo = today - (2 * DateTimeUtils.MILLIS_PER_DAY)
        val threeDaysAgo = today - (3 * DateTimeUtils.MILLIS_PER_DAY)

        // Last completion was 2 days ago - streak is 0 (broken)
        val completions = listOf(
            createCompletion(date = twoDaysAgo),
            createCompletion(date = threeDaysAgo)
        )

        val streak = StreakCalculator.calculateCurrentStreak(completions)

        assertEquals("Not completed today or yesterday means streak is broken", 0, streak)
    }

    @Test
    fun calculateBestStreak_emptyCompletions_returnsZero() {
        val completions = emptyList<CompletionEntity>()

        val streak = StreakCalculator.calculateBestStreak(completions)

        assertEquals("Empty completions should return 0", 0, streak)
    }

    @Test
    fun calculateBestStreak_singleCompletion_returnsOne() {
        val completions = listOf(
            createCompletion(date = getStartOfDayMillis())
        )

        val streak = StreakCalculator.calculateBestStreak(completions)

        assertEquals("Single completion should return 1", 1, streak)
    }

    @Test
    fun calculateBestStreak_multipleSequences_returnsLongest() {
        val today = getStartOfDayMillis()
        val yesterday = today - DateTimeUtils.MILLIS_PER_DAY
        val twoDaysAgo = yesterday - DateTimeUtils.MILLIS_PER_DAY
        val fourDaysAgo = today - (4 * DateTimeUtils.MILLIS_PER_DAY)
        val fiveDaysAgo = today - (5 * DateTimeUtils.MILLIS_PER_DAY)
        val sixDaysAgo = today - (6 * DateTimeUtils.MILLIS_PER_DAY)

        // 3-day streak (today, yesterday, 2 days ago)
        // 3-day streak (4, 5, 6 days ago)
        val completions = listOf(
            createCompletion(date = today),
            createCompletion(date = yesterday),
            createCompletion(date = twoDaysAgo),
            createCompletion(date = fourDaysAgo),
            createCompletion(date = fiveDaysAgo),
            createCompletion(date = sixDaysAgo)
        )

        val streak = StreakCalculator.calculateBestStreak(completions)

        assertEquals("Best of two 3-day streaks should return 3", 3, streak)
    }

    @Test
    fun calculateBestStreak_longerSequenceLater_returnsLongest() {
        val today = getStartOfDayMillis()
        val yesterday = today - DateTimeUtils.MILLIS_PER_DAY
        val twoDaysAgo = yesterday - DateTimeUtils.MILLIS_PER_DAY
        val threeDaysAgo = twoDaysAgo - DateTimeUtils.MILLIS_PER_DAY
        val fourDaysAgo = threeDaysAgo - DateTimeUtils.MILLIS_PER_DAY
        val fiveDaysAgo = today - (5 * DateTimeUtils.MILLIS_PER_DAY)

        // 6-day consecutive streak (today through 5 days ago)
        val completions = listOf(
            createCompletion(date = today),
            createCompletion(date = yesterday),
            createCompletion(date = twoDaysAgo),
            createCompletion(date = threeDaysAgo),
            createCompletion(date = fourDaysAgo),
            createCompletion(date = fiveDaysAgo)
        )

        val streak = StreakCalculator.calculateBestStreak(completions)

        assertEquals("6 consecutive days should return 6", 6, streak)
    }

    @Test
    fun calculateCurrentStreak_multipleCompletionsSameDay_countedOnce() {
        val today = getStartOfDayMillis()

        // Multiple completions on the same day
        val completions = listOf(
            createCompletion(date = today, value = 1),
            createCompletion(date = today, value = 2),
            createCompletion(date = today, value = 3)
        )

        val streak = StreakCalculator.calculateCurrentStreak(completions)

        assertEquals("Multiple completions same day should count as 1", 1, streak)
    }

    @Test
    fun calculateBestStreak_multipleCompletionsSameDay_countedOnce() {
        val today = getStartOfDayMillis()
        val yesterday = today - DateTimeUtils.MILLIS_PER_DAY

        // Multiple completions on the same day
        val completions = listOf(
            createCompletion(date = today, value = 1),
            createCompletion(date = today, value = 2),
            createCompletion(date = yesterday, value = 1),
            createCompletion(date = yesterday, value = 2)
        )

        val streak = StreakCalculator.calculateBestStreak(completions)

        assertEquals("Multiple completions same day should count as 2-day streak", 2, streak)
    }

    // ========== Target-based streak tests for COUNTING habits ==========

    @Test
    fun calculateCurrentStreakWithTarget_emptyCompletions_returnsZero() {
        val completions = emptyList<CompletionEntity>()
        val targetValue = 5

        val streak = StreakCalculator.calculateCurrentStreakWithTarget(completions, targetValue)

        assertEquals("Empty completions should return 0", 0, streak)
    }

    @Test
    fun calculateCurrentStreakWithTarget_targetMetToday_returnsOne() {
        val today = getStartOfDayMillis()
        val targetValue = 5

        // Single completion meeting target
        val completions = listOf(
            createCompletion(date = today, value = 5)
        )

        val streak = StreakCalculator.calculateCurrentStreakWithTarget(completions, targetValue)

        assertEquals("Target met today should return 1", 1, streak)
    }

    @Test
    fun calculateCurrentStreakWithTarget_targetNotMetToday_returnsZero() {
        val today = getStartOfDayMillis()
        val targetValue = 5

        // Completion exists but doesn't meet target
        val completions = listOf(
            createCompletion(date = today, value = 3)
        )

        val streak = StreakCalculator.calculateCurrentStreakWithTarget(completions, targetValue)

        assertEquals("Target not met should return 0", 0, streak)
    }

    @Test
    fun calculateCurrentStreakWithTarget_partialCompletionsSumToTarget_returnsStreak() {
        val today = getStartOfDayMillis()
        val yesterday = today - DateTimeUtils.MILLIS_PER_DAY
        val targetValue = 5

        // Multiple completions on same day that sum to target
        val completions = listOf(
            createCompletion(date = today, value = 2),
            createCompletion(date = today, value = 3),
            createCompletion(date = yesterday, value = 1),
            createCompletion(date = yesterday, value = 4)
        )

        val streak = StreakCalculator.calculateCurrentStreakWithTarget(completions, targetValue)

        assertEquals("Partial completions summing to target should return 2", 2, streak)
    }

    @Test
    fun calculateCurrentStreakWithTarget_gapWhenTargetNotMet_breaksStreak() {
        val today = getStartOfDayMillis()
        val yesterday = today - DateTimeUtils.MILLIS_PER_DAY
        val twoDaysAgo = yesterday - DateTimeUtils.MILLIS_PER_DAY
        val targetValue = 5

        // Target met today and 2 days ago, but not yesterday (breaks streak)
        val completions = listOf(
            createCompletion(date = today, value = 5),
            createCompletion(date = yesterday, value = 3),  // Not met
            createCompletion(date = twoDaysAgo, value = 5)
        )

        val streak = StreakCalculator.calculateCurrentStreakWithTarget(completions, targetValue)

        assertEquals("Gap when target not met should break streak, returning 1", 1, streak)
    }

    @Test
    fun calculateCurrentStreakWithTarget_threeConsecutiveDaysAllMet_returnsThree() {
        val today = getStartOfDayMillis()
        val yesterday = today - DateTimeUtils.MILLIS_PER_DAY
        val twoDaysAgo = yesterday - DateTimeUtils.MILLIS_PER_DAY
        val targetValue = 5

        val completions = listOf(
            createCompletion(date = today, value = 5),
            createCompletion(date = yesterday, value = 6),
            createCompletion(date = twoDaysAgo, value = 7)
        )

        val streak = StreakCalculator.calculateCurrentStreakWithTarget(completions, targetValue)

        assertEquals("Three consecutive days all meeting target should return 3", 3, streak)
    }

    @Test
    fun calculateCurrentStreakWithTarget_countdownMode_targetReached() {
        val today = getStartOfDayMillis()
        val yesterday = today - DateTimeUtils.MILLIS_PER_DAY
        val targetValue = 10  // Countdown: need 10 presses to reach 0

        // Countdown mode: currentCount reaches targetValue when complete
        // Same logic as countup: sum >= target means complete
        val completions = listOf(
            createCompletion(date = today, value = 10),
            createCompletion(date = yesterday, value = 10)
        )

        val streak = StreakCalculator.calculateCurrentStreakWithTarget(completions, targetValue)

        assertEquals("Countdown mode reaching target should return 2", 2, streak)
    }

    @Test
    fun calculateCurrentStreakWithTarget_countdownMode_partialNotCounted() {
        val today = getStartOfDayMillis()
        val yesterday = today - DateTimeUtils.MILLIS_PER_DAY
        val targetValue = 10

        // Partial countdown (8/10) doesn't count toward streak
        val completions = listOf(
            createCompletion(date = today, value = 8),
            createCompletion(date = yesterday, value = 10)
        )

        val streak = StreakCalculator.calculateCurrentStreakWithTarget(completions, targetValue)

        assertEquals("Partial countdown should not count, streak from yesterday only = 1", 1, streak)
    }

    @Test
    fun calculateBestStreakWithTarget_emptyCompletions_returnsZero() {
        val completions = emptyList<CompletionEntity>()
        val targetValue = 5

        val streak = StreakCalculator.calculateBestStreakWithTarget(completions, targetValue)

        assertEquals("Empty completions should return 0", 0, streak)
    }

    @Test
    fun calculateBestStreakWithTarget_singleTargetMetDay_returnsOne() {
        val today = getStartOfDayMillis()
        val targetValue = 5

        val completions = listOf(
            createCompletion(date = today, value = 5)
        )

        val streak = StreakCalculator.calculateBestStreakWithTarget(completions, targetValue)

        assertEquals("Single target-met day should return 1", 1, streak)
    }

    @Test
    fun calculateBestStreakWithTarget_multipleSequences_returnsLongestTargetMetSequence() {
        val today = getStartOfDayMillis()
        val yesterday = today - DateTimeUtils.MILLIS_PER_DAY
        val twoDaysAgo = yesterday - DateTimeUtils.MILLIS_PER_DAY
        val fourDaysAgo = today - (4 * DateTimeUtils.MILLIS_PER_DAY)
        val fiveDaysAgo = today - (5 * DateTimeUtils.MILLIS_PER_DAY)
        val sixDaysAgo = today - (6 * DateTimeUtils.MILLIS_PER_DAY)
        val targetValue = 5

        // 3-day target-met streak (today, yesterday, 2 days ago)
        // 3-day target-met streak (4, 5, 6 days ago)
        // Day 3 ago has value 3 (not met, creates gap)
        val completions = listOf(
            createCompletion(date = today, value = 5),
            createCompletion(date = yesterday, value = 5),
            createCompletion(date = twoDaysAgo, value = 5),
            createCompletion(date = today - (3 * DateTimeUtils.MILLIS_PER_DAY), value = 3),  // Not met
            createCompletion(date = fourDaysAgo, value = 5),
            createCompletion(date = fiveDaysAgo, value = 5),
            createCompletion(date = sixDaysAgo, value = 5)
        )

        val streak = StreakCalculator.calculateBestStreakWithTarget(completions, targetValue)

        assertEquals("Best of two 3-day target-met streaks should return 3", 3, streak)
    }

    @Test
    fun calculateBestStreakWithTarget_partialDaysNotIncluded() {
        val today = getStartOfDayMillis()
        val yesterday = today - DateTimeUtils.MILLIS_PER_DAY
        val twoDaysAgo = yesterday - DateTimeUtils.MILLIS_PER_DAY
        val targetValue = 5

        // Only yesterday met target, today and 2 days ago are partial
        val completions = listOf(
            createCompletion(date = today, value = 3),
            createCompletion(date = yesterday, value = 5),
            createCompletion(date = twoDaysAgo, value = 2)
        )

        val streak = StreakCalculator.calculateBestStreakWithTarget(completions, targetValue)

        assertEquals("Only one day met target, best streak = 1", 1, streak)
    }

    private fun createCompletion(date: Long, value: Int = 1): CompletionEntity {
        return CompletionEntity(
            id = 0,
            habitId = 1,
            date = date,
            value = value
        )
    }

    private fun getStartOfDayMillis(): Long {
        return DateTimeUtils.startOfDayMillis()
    }
    @Test
    fun captured_calendar_dates_override_shared_legacy_dates_and_do_not_let_future_history_hide_the_streak() {
        val today = java.time.LocalDate.parse("2026-03-10")
        val completions = listOf("2026-03-08", "2026-03-09", "2026-03-10", "2026-03-12").flatMap { date ->
            listOf(1, 2).map { value -> CompletionEntity(habitId = 1, date = 0, value = value,
                actualCompletedAt = java.time.Instant.parse("${date}T16:00:00Z").toEpochMilli(),
                recordedTimezone = "America/New_York", recordedLocalDate = date) }
        }
        assertEquals(3, StreakCalculator.calculateCurrentStreak(completions, today))
        assertEquals(3, StreakCalculator.calculateBestStreak(completions))
        assertEquals(3, StreakCalculator.calculateCurrentStreakWithTarget(completions, 3, today))
        assertEquals(3, StreakCalculator.calculateBestStreakWithTarget(completions, 3))
        assertEquals(0, StreakCalculator.calculateCurrentStreakWithTarget(completions, 4, today))
    }

}
