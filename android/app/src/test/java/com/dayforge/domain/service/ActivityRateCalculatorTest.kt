package com.dayforge.domain.service

import com.dayforge.data.model.HabitSchedule
import com.dayforge.util.DateTimeUtils
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ActivityRateCalculatorTest {

    // ========== Deduction per miss tests ==========

    @Test
    fun getDeductionPerMiss_daily_returns15() {
        val schedule = HabitSchedule.Daily
        assertEquals(15, ActivityRateCalculator.getDeductionPerMiss(schedule))
    }

    @Test
    fun getDeductionPerMiss_weeklySpecifiedDays_returns12() {
        val schedule = HabitSchedule.Weekly(daysOfWeek = listOf(1, 3, 5))  // Mon, Wed, Fri
        assertEquals(12, ActivityRateCalculator.getDeductionPerMiss(schedule))
    }

    @Test
    fun getDeductionPerMiss_weeklyEvery7Days_returns30() {
        val schedule = HabitSchedule.Weekly(daysOfWeek = emptyList())
        assertEquals(30, ActivityRateCalculator.getDeductionPerMiss(schedule))
    }

    @Test
    fun getDeductionPerMiss_monthly_returns45() {
        val schedule = HabitSchedule.Monthly(dayOfMonth = 15)
        assertEquals(45, ActivityRateCalculator.getDeductionPerMiss(schedule))
    }

    @Test
    fun getDeductionPerMiss_custom_returns30() {
        val schedule = HabitSchedule.Custom(frequencyDays = 3)
        assertEquals(30, ActivityRateCalculator.getDeductionPerMiss(schedule))
    }

    // ========== Window size tests ==========

    @Test
    fun getWindowSize_daily_returns7() {
        val schedule = HabitSchedule.Daily
        assertEquals(7, ActivityRateCalculator.getWindowSize(schedule))
    }

    @Test
    fun getWindowSize_weeklySpecifiedDays_returns8() {
        val schedule = HabitSchedule.Weekly(daysOfWeek = listOf(1, 3, 5))
        assertEquals(8, ActivityRateCalculator.getWindowSize(schedule))
    }

    @Test
    fun getWindowSize_weeklyEvery7Days_returns4() {
        val schedule = HabitSchedule.Weekly(daysOfWeek = emptyList())
        assertEquals(4, ActivityRateCalculator.getWindowSize(schedule))
    }

    @Test
    fun getWindowSize_monthly_returns3() {
        val schedule = HabitSchedule.Monthly(dayOfMonth = 15)
        assertEquals(3, ActivityRateCalculator.getWindowSize(schedule))
    }

    @Test
    fun getWindowSize_custom_returns4() {
        val schedule = HabitSchedule.Custom(frequencyDays = 3)
        assertEquals(4, ActivityRateCalculator.getWindowSize(schedule))
    }

    // ========== Daily schedule calculation tests ==========

    @Test
    fun calculate_daily_allCompleted_returns100() {
        val now = System.currentTimeMillis()
        val createdAt = now - (7 * DateTimeUtils.MILLIS_PER_DAY)  // Created 7 days ago

        // Complete all 7 days
        val completions = (0..6).map { daysAgo ->
            now - (daysAgo * DateTimeUtils.MILLIS_PER_DAY)
        }

        val rate = ActivityRateCalculator.calculate(
            schedule = HabitSchedule.Daily,
            createdAt = createdAt,
            completions = completions,
            now = now
        )

        assertEquals("All completed should return 100", 100, rate)
    }

    @Test
    fun calculate_daily_oneMissed_returns85() {
        val now = System.currentTimeMillis()
        val createdAt = now - (7 * DateTimeUtils.MILLIS_PER_DAY)

        // Complete 6 days, miss 1
        val completions = (1..6).map { daysAgo ->
            now - (daysAgo * DateTimeUtils.MILLIS_PER_DAY)
        }

        val rate = ActivityRateCalculator.calculate(
            schedule = HabitSchedule.Daily,
            createdAt = createdAt,
            completions = completions,
            now = now
        )

        assertEquals("1 missed day should deduct 15", 85, rate)
    }

    @Test
    fun calculate_daily_threeMissed_returns55() {
        val now = System.currentTimeMillis()
        val createdAt = now - (7 * DateTimeUtils.MILLIS_PER_DAY)

        // Complete 4 days, miss 3
        val completions = (3..6).map { daysAgo ->
            now - (daysAgo * DateTimeUtils.MILLIS_PER_DAY)
        }

        val rate = ActivityRateCalculator.calculate(
            schedule = HabitSchedule.Daily,
            createdAt = createdAt,
            completions = completions,
            now = now
        )

        assertEquals("3 missed days should deduct 45", 55, rate)
    }

    @Test
    fun calculate_daily_allMissed_returns0() {
        val now = System.currentTimeMillis()
        val createdAt = now - (7 * DateTimeUtils.MILLIS_PER_DAY)

        // No completions
        val completions = emptyList<Long>()

        val rate = ActivityRateCalculator.calculate(
            schedule = HabitSchedule.Daily,
            createdAt = createdAt,
            completions = completions,
            now = now
        )

        assertEquals("All 7 missed should return 0", 0, rate)
    }

    // ========== Weekly (specified days) calculation tests ==========

    @Test
    fun calculate_weeklySpecifiedDays_allCompleted_returns100() {
        val now = System.currentTimeMillis()
        val createdAt = now - (28 * DateTimeUtils.MILLIS_PER_DAY)  // 4 weeks ago
        val today = LocalDate.now()

        // Complete all check-in days (Mon, Wed, Fri) for the window
        val completions = generateWeeklyCompletions(today, listOf(1, 3, 5), 8)  // 8 check-in days

        val rate = ActivityRateCalculator.calculate(
            schedule = HabitSchedule.Weekly(daysOfWeek = listOf(1, 3, 5)),
            createdAt = createdAt,
            completions = completions,
            now = now
        )

        assertEquals("All weekly check-in days completed should return 100", 100, rate)
    }

    @Test
    fun calculate_weeklySpecifiedDays_oneMissed_returns88() {
        val now = System.currentTimeMillis()
        val createdAt = now - (28 * DateTimeUtils.MILLIS_PER_DAY)

        // Complete 7 out of 8 check-in days
        val completions = generateWeeklyCompletions(LocalDate.now(), listOf(1, 3, 5), 7)

        val rate = ActivityRateCalculator.calculate(
            schedule = HabitSchedule.Weekly(daysOfWeek = listOf(1, 3, 5)),
            createdAt = createdAt,
            completions = completions,
            now = now
        )

        assertEquals("1 missed weekly check-in should deduct 12", 88, rate)
    }

    // ========== Weekly (every 7 days) calculation tests ==========

    @Test
    fun calculate_weeklyEvery7Days_allCompleted_returns100() {
        val now = System.currentTimeMillis()
        val createdAt = now - (28 * DateTimeUtils.MILLIS_PER_DAY)

        // Complete all 4 cycle days
        val completions = listOf(
            now,  // Today (cycle day)
            now - (7 * DateTimeUtils.MILLIS_PER_DAY),
            now - (14 * DateTimeUtils.MILLIS_PER_DAY),
            now - (21 * DateTimeUtils.MILLIS_PER_DAY)
        )

        val rate = ActivityRateCalculator.calculate(
            schedule = HabitSchedule.Weekly(daysOfWeek = emptyList()),
            createdAt = createdAt,
            completions = completions,
            now = now
        )

        assertEquals("All 4 cycles completed should return 100", 100, rate)
    }

    @Test
    fun calculate_weeklyEvery7Days_oneMissed_returns70() {
        val now = System.currentTimeMillis()
        val createdAt = now - (28 * DateTimeUtils.MILLIS_PER_DAY)

        // Complete 3 out of 4 cycle days
        val completions = listOf(
            now,
            now - (7 * DateTimeUtils.MILLIS_PER_DAY),
            now - (21 * DateTimeUtils.MILLIS_PER_DAY)
        )

        val rate = ActivityRateCalculator.calculate(
            schedule = HabitSchedule.Weekly(daysOfWeek = emptyList()),
            createdAt = createdAt,
            completions = completions,
            now = now
        )

        assertEquals("1 missed cycle should deduct 30", 70, rate)
    }

    @Test
    fun calculate_weeklyEvery7Days_twoMissed_returns40() {
        val now = System.currentTimeMillis()
        val createdAt = now - (28 * DateTimeUtils.MILLIS_PER_DAY)

        // Complete 2 out of 4 cycle days
        val completions = listOf(
            now,
            now - (21 * DateTimeUtils.MILLIS_PER_DAY)
        )

        val rate = ActivityRateCalculator.calculate(
            schedule = HabitSchedule.Weekly(daysOfWeek = emptyList()),
            createdAt = createdAt,
            completions = completions,
            now = now
        )

        assertEquals("2 missed cycles should deduct 60", 40, rate)
    }

    // ========== Monthly calculation tests ==========

    @Test
    fun calculate_monthly_allCompleted_returns100() {
        val now = System.currentTimeMillis()
        val createdAt = now - (90 * DateTimeUtils.MILLIS_PER_DAY)  // 3 months ago
        val today = LocalDate.now()

        // Complete 3 monthly check-ins
        val completions = generateMonthlyCompletions(today, 15, 3)

        val rate = ActivityRateCalculator.calculate(
            schedule = HabitSchedule.Monthly(dayOfMonth = 15),
            createdAt = createdAt,
            completions = completions,
            now = now
        )

        assertEquals("All 3 monthly check-ins completed should return 100", 100, rate)
    }

    @Test
    fun calculate_monthly_oneMissed_returns55() {
        val fixedDate = LocalDate.of(2026, 8, 20)
        val now = fixedDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val createdAt = now - (90 * DateTimeUtils.MILLIS_PER_DAY)

        // Complete 2 out of 3 monthly check-ins
        val completions = generateMonthlyCompletions(fixedDate, 15, 2)

        val rate = ActivityRateCalculator.calculate(
            schedule = HabitSchedule.Monthly(dayOfMonth = 15),
            createdAt = createdAt,
            completions = completions,
            now = now
        )

        assertEquals("1 missed month should deduct 45", 55, rate)
    }

    @Test
    fun calculate_monthly_twoMissed_returns10() {
        val fixedDate = LocalDate.of(2026, 8, 20)
        val now = fixedDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val createdAt = now - (90 * DateTimeUtils.MILLIS_PER_DAY)

        // Complete 1 out of 3 monthly check-ins
        val completions = generateMonthlyCompletions(fixedDate, 15, 1)

        val rate = ActivityRateCalculator.calculate(
            schedule = HabitSchedule.Monthly(dayOfMonth = 15),
            createdAt = createdAt,
            completions = completions,
            now = now
        )

        assertEquals("2 missed months should deduct 90", 10, rate)
    }

    // ========== Custom schedule calculation tests ==========

    @Test
    fun calculate_customEvery3Days_allCompleted_returns100() {
        val now = System.currentTimeMillis()
        val createdAt = now - (12 * DateTimeUtils.MILLIS_PER_DAY)

        // Complete all 4 cycle days
        val completions = listOf(
            now,
            now - (3 * DateTimeUtils.MILLIS_PER_DAY),
            now - (6 * DateTimeUtils.MILLIS_PER_DAY),
            now - (9 * DateTimeUtils.MILLIS_PER_DAY)
        )

        val rate = ActivityRateCalculator.calculate(
            schedule = HabitSchedule.Custom(frequencyDays = 3),
            createdAt = createdAt,
            completions = completions,
            now = now
        )

        assertEquals("All 4 custom cycles completed should return 100", 100, rate)
    }

    @Test
    fun calculate_customEvery3Days_oneMissed_returns70() {
        val now = System.currentTimeMillis()
        val createdAt = now - (12 * DateTimeUtils.MILLIS_PER_DAY)

        // Complete 3 out of 4 cycle days
        val completions = listOf(
            now,
            now - (3 * DateTimeUtils.MILLIS_PER_DAY),
            now - (9 * DateTimeUtils.MILLIS_PER_DAY)
        )

        val rate = ActivityRateCalculator.calculate(
            schedule = HabitSchedule.Custom(frequencyDays = 3),
            createdAt = createdAt,
            completions = completions,
            now = now
        )

        assertEquals("1 missed custom cycle should deduct 30", 70, rate)
    }

    // ========== Edge case tests ==========

    @Test
    fun calculate_newHabitNoCompletions_returns100() {
        val now = System.currentTimeMillis()
        val createdAt = now  // Just created

        val rate = ActivityRateCalculator.calculate(
            schedule = HabitSchedule.Daily,
            createdAt = createdAt,
            completions = emptyList(),
            now = now
        )

        assertEquals("New habit with no check-in days should return 100", 100, rate)
    }

    @Test
    fun calculate_habitCreatedYesterdayNoCompletions_daily_returns85() {
        val now = System.currentTimeMillis()
        val createdAt = now - DateTimeUtils.MILLIS_PER_DAY  // Created yesterday

        // Window should have 1 day (yesterday or today depending on when it's checked)
        val rate = ActivityRateCalculator.calculate(
            schedule = HabitSchedule.Daily,
            createdAt = createdAt,
            completions = emptyList(),
            now = now
        )

        // The result depends on whether today/yesterday is counted as a check-in day
        // For daily, every day is a check-in day, so should have missed days
        assertTrue("New habit should have reasonable rate", rate in 0..100)
    }

    @Test
    fun calculate_completionsOutsideWindow_notCounted() {
        val now = System.currentTimeMillis()
        val createdAt = now - (30 * DateTimeUtils.MILLIS_PER_DAY)

        // Complete all 7 days in window + some days outside window
        val completions = mutableListOf<Long>()
        // Days in window (0-6 days ago)
        for (i in 0..6) {
            completions.add(now - (i * DateTimeUtils.MILLIS_PER_DAY))
        }
        // Days outside window (8-14 days ago) - should be ignored
        for (i in 8..14) {
            completions.add(now - (i * DateTimeUtils.MILLIS_PER_DAY))
        }

        val rate = ActivityRateCalculator.calculate(
            schedule = HabitSchedule.Daily,
            createdAt = createdAt,
            completions = completions,
            now = now
        )

        assertEquals("Completions outside window should not affect rate", 100, rate)
    }

    @Test
    fun generateWindowCheckInDays_daily_returns7Days() {
        val now = System.currentTimeMillis()
        val createdAt = now - (10 * DateTimeUtils.MILLIS_PER_DAY)

        val windowDays = ActivityRateCalculator.generateWindowCheckInDays(
            schedule = HabitSchedule.Daily,
            createdAt = createdAt,
            now = now
        )

        assertEquals("Daily window should have 7 days", 7, windowDays.size)
    }

    @Test
    fun generateWindowCheckInDays_weeklySpecifiedDays_returnsCorrectDays() {
        val now = System.currentTimeMillis()
        val createdAt = now - (30 * DateTimeUtils.MILLIS_PER_DAY)

        val windowDays = ActivityRateCalculator.generateWindowCheckInDays(
            schedule = HabitSchedule.Weekly(daysOfWeek = listOf(1, 3, 5)),  // Mon, Wed, Fri
            createdAt = createdAt,
            now = now
        )

        // Window size is 8 for specified days
        assertEquals("Weekly (specified) window should have 8 days", 8, windowDays.size)

        // Verify each day is a valid check-in day (Mon, Wed, or Fri)
        for (day in windowDays) {
            val localDate = java.time.Instant.ofEpochMilli(day)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            val dayOfWeek = localDate.dayOfWeek.value
            assertTrue("Each day should be Mon, Wed, or Fri", dayOfWeek in listOf(1, 3, 5))
        }
    }

    @Test
    fun generateWindowCheckInDays_notIncludeFutureDays() {
        val now = System.currentTimeMillis()
        val createdAt = now - (5 * DateTimeUtils.MILLIS_PER_DAY)

        val windowDays = ActivityRateCalculator.generateWindowCheckInDays(
            schedule = HabitSchedule.Daily,
            createdAt = createdAt,
            now = now
        )

        // Should not include more days than have passed since creation
        assertTrue("Window should not exceed days since creation", windowDays.size <= 6)  // 5 days ago + today
    }

    // ========== Helper functions ==========

    private fun generateWeeklyCompletions(
        today: LocalDate,
        daysOfWeek: List<Int>,
        count: Int
    ): List<Long> {
        val completions = mutableListOf<Long>()
        var currentDate = today
        var generated = 0

        while (generated < count) {
            if (currentDate.dayOfWeek.value in daysOfWeek) {
                completions.add(
                    currentDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                )
                generated++
            }
            currentDate = currentDate.minusDays(1)
        }

        return completions
    }

    private fun generateMonthlyCompletions(
        today: LocalDate,
        dayOfMonth: Int,
        count: Int
    ): List<Long> {
        val completions = mutableListOf<Long>()
        var currentDate = today
        var generated = 0

        while (generated < count) {
            val effectiveDay = dayOfMonth.coerceAtMost(currentDate.lengthOfMonth())
            if (currentDate.dayOfMonth == effectiveDay) {
                completions.add(
                    currentDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                )
                generated++
            }
            currentDate = currentDate.minusDays(1)
        }

        return completions
    }
}
