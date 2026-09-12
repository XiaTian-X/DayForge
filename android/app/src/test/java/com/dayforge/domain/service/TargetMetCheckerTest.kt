package com.dayforge.domain.service

import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.model.HabitType
import com.dayforge.util.DateTimeUtils
import org.junit.Assert.*
import org.junit.Test

class TargetMetCheckerTest {

    // ========== COUNTING/CHECK_IN Tests ==========

    @Test
    fun `getTargetMetDates returns empty list for empty completions`() {
        val result = TargetMetChecker.getTargetMetDates(emptyList(), targetValue = 5)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getTargetMetDates returns empty list for zero target`() {
        val today = DateTimeUtils.startOfDayMillis()
        val completions = listOf(
            CompletionEntity(habitId = 1, date = today, value = 3)
        )
        val result = TargetMetChecker.getTargetMetDates(completions, targetValue = 0)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getTargetMetDates filters days meeting target`() {
        val today = DateTimeUtils.startOfDayMillis()
        val yesterday = today - DateTimeUtils.MILLIS_PER_DAY

        val completions = listOf(
            // Today: 2+3=5 >= target 5 ✓
            CompletionEntity(habitId = 1, date = today, value = 2),
            CompletionEntity(habitId = 1, date = today, value = 3),
            // Yesterday: 3 < target 5 ✗
            CompletionEntity(habitId = 1, date = yesterday, value = 3)
        )

        val result = TargetMetChecker.getTargetMetDates(completions, targetValue = 5)

        assertEquals(1, result.size)
        assertTrue(result.contains(DateTimeUtils.today()))
    }

    @Test
    fun `countTargetMetDays returns correct count`() {
        val today = DateTimeUtils.startOfDayMillis()
        val yesterday = today - DateTimeUtils.MILLIS_PER_DAY
        val twoDaysAgo = today - 2 * DateTimeUtils.MILLIS_PER_DAY

        val completions = listOf(
            // Today: meets target
            CompletionEntity(habitId = 1, date = today, value = 5),
            // Yesterday: meets target
            CompletionEntity(habitId = 1, date = yesterday, value = 6),
            // Two days ago: does not meet target
            CompletionEntity(habitId = 1, date = twoDaysAgo, value = 2)
        )

        val result = TargetMetChecker.countTargetMetDays(completions, targetValue = 5)

        assertEquals(2, result)
    }

    @Test
    fun `isTargetMetOnDate returns true when target met`() {
        val today = DateTimeUtils.startOfDayMillis()
        val completions = listOf(
            CompletionEntity(habitId = 1, date = today, value = 3),
            CompletionEntity(habitId = 1, date = today, value = 2)
        )

        val result = TargetMetChecker.isTargetMetOnDate(completions, DateTimeUtils.today(), targetValue = 5)

        assertTrue(result)
    }

    @Test
    fun `isTargetMetOnDate returns false when target not met`() {
        val today = DateTimeUtils.startOfDayMillis()
        val completions = listOf(
            CompletionEntity(habitId = 1, date = today, value = 3)
        )

        val result = TargetMetChecker.isTargetMetOnDate(completions, DateTimeUtils.today(), targetValue = 5)

        assertFalse(result)
    }

    @Test
    fun `isTargetMetOnDate returns false for wrong date`() {
        val today = DateTimeUtils.startOfDayMillis()
        val yesterday = today - DateTimeUtils.MILLIS_PER_DAY
        val completions = listOf(
            CompletionEntity(habitId = 1, date = today, value = 10)
        )

        val result = TargetMetChecker.isTargetMetOnDate(completions, DateTimeUtils.today().minusDays(1), targetValue = 5)

        assertFalse(result)
    }

    // ========== TIMER Tests ==========

    @Test
    fun `getTimerTargetMetDates filters days meeting target`() {
        val today = DateTimeUtils.startOfDayMillis()
        val yesterday = today - DateTimeUtils.MILLIS_PER_DAY

        val timeLogs = listOf(
            // Today: 180+120=300s >= 300s (5 min target) ✓
            TimeLogEntity(habitId = 1, date = today, startTime = today, endTime = today + 180_000, durationSeconds = 180),
            TimeLogEntity(habitId = 1, date = today, startTime = today, endTime = today + 120_000, durationSeconds = 120),
            // Yesterday: 200s < 300s ✗
            TimeLogEntity(habitId = 1, date = yesterday, startTime = yesterday, endTime = yesterday + 200_000, durationSeconds = 200)
        )

        val result = TargetMetChecker.getTimerTargetMetDates(timeLogs, targetSeconds = 300)

        assertEquals(1, result.size)
        assertTrue(result.contains(today))
    }

    @Test
    fun `countTimerTargetMetDays returns correct count`() {
        val today = DateTimeUtils.startOfDayMillis()
        val yesterday = today - DateTimeUtils.MILLIS_PER_DAY

        val timeLogs = listOf(
            // Today: 400s >= 300s ✓
            TimeLogEntity(habitId = 1, date = today, startTime = today, endTime = today + 400_000, durationSeconds = 400),
            // Yesterday: 200s < 300s ✗
            TimeLogEntity(habitId = 1, date = yesterday, startTime = yesterday, endTime = yesterday + 200_000, durationSeconds = 200)
        )

        val result = TargetMetChecker.countTimerTargetMetDays(timeLogs, targetSeconds = 300)

        assertEquals(1, result)
    }

    @Test
    fun `isTimerTargetMetOnDate returns true when target met`() {
        val today = DateTimeUtils.startOfDayMillis()
        val timeLogs = listOf(
            TimeLogEntity(habitId = 1, date = today, startTime = today, endTime = today + 300_000, durationSeconds = 300)
        )

        val result = TargetMetChecker.isTimerTargetMetOnDate(timeLogs, today, targetSeconds = 300)

        assertTrue(result)
    }

    @Test
    fun `isTimerTargetMetOnDate returns false when target not met`() {
        val today = DateTimeUtils.startOfDayMillis()
        val timeLogs = listOf(
            TimeLogEntity(habitId = 1, date = today, startTime = today, endTime = today + 200_000, durationSeconds = 200)
        )

        val result = TargetMetChecker.isTimerTargetMetOnDate(timeLogs, today, targetSeconds = 300)

        assertFalse(result)
    }

    // ========== Universal Method Tests ==========

    @Test
    fun `isTargetMetOnDateUniversal for CHECK_IN returns true for any completion`() {
        val today = DateTimeUtils.startOfDayMillis()
        val completions = listOf(
            CompletionEntity(habitId = 1, date = today, value = 1)
        )

        val result = TargetMetChecker.isTargetMetOnDateUniversal(
            habitType = HabitType.CHECK_IN,
            completions = completions,
            targetValue = 1  // Not used for CHECK_IN
        )

        assertTrue(result)
    }

    @Test
    fun `isTargetMetOnDateUniversal for CHECK_IN returns false for no completions`() {
        val result = TargetMetChecker.isTargetMetOnDateUniversal(
            habitType = HabitType.CHECK_IN,
            completions = emptyList(),
            targetValue = 1
        )

        assertFalse(result)
    }

    @Test
    fun `isTargetMetOnDateUniversal for COUNTING returns true when target met`() {
        val today = DateTimeUtils.startOfDayMillis()
        val completions = listOf(
            CompletionEntity(habitId = 1, date = today, value = 5)
        )

        val result = TargetMetChecker.isTargetMetOnDateUniversal(
            habitType = HabitType.COUNTING,
            completions = completions,
            targetValue = 5
        )

        assertTrue(result)
    }

    @Test
    fun `isTargetMetOnDateUniversal for COUNTING returns false when target not met`() {
        val today = DateTimeUtils.startOfDayMillis()
        val completions = listOf(
            CompletionEntity(habitId = 1, date = today, value = 3)
        )

        val result = TargetMetChecker.isTargetMetOnDateUniversal(
            habitType = HabitType.COUNTING,
            completions = completions,
            targetValue = 5
        )

        assertFalse(result)
    }

    @Test
    fun `isTargetMetOnDateUniversal for TIMER returns true when target met`() {
        val today = DateTimeUtils.startOfDayMillis()
        val timeLogs = listOf(
            TimeLogEntity(habitId = 1, date = today, startTime = today, endTime = today + 300_000, durationSeconds = 300)
        )

        val result = TargetMetChecker.isTargetMetOnDateUniversal(
            habitType = HabitType.TIMER,
            timeLogs = timeLogs,
            targetValue = 5  // 5 minutes = 300 seconds
        )

        assertTrue(result)
    }

    @Test
    fun `isTargetMetOnDateUniversal for TIMER returns false when target not met`() {
        val today = DateTimeUtils.startOfDayMillis()
        val timeLogs = listOf(
            TimeLogEntity(habitId = 1, date = today, startTime = today, endTime = today + 200_000, durationSeconds = 200)
        )

        val result = TargetMetChecker.isTargetMetOnDateUniversal(
            habitType = HabitType.TIMER,
            timeLogs = timeLogs,
            targetValue = 5  // 5 minutes = 300 seconds
        )

        assertFalse(result)
    }

    @Test
    fun `isTargetMetOnDateUniversal for GOAL returns false`() {
        val result = TargetMetChecker.isTargetMetOnDateUniversal(
            habitType = HabitType.GOAL,
            completions = emptyList(),
            targetValue = 1
        )

        assertFalse(result)
    }
}
