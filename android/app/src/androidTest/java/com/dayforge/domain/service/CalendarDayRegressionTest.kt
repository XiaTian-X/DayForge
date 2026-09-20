package com.dayforge.domain.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

import com.dayforge.util.DateTimeUtils
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

@RunWith(AndroidJUnit4::class)
class CalendarDayRegressionTest {
    private val originalZone = TimeZone.getDefault()

    @After
    fun restoreZone() = TimeZone.setDefault(originalZone)

    @Test
    fun day_end_follows_next_midnight_across_DST_and_ordinary_dates() {
        for ((zone, date, hours) in listOf(
            Triple("America/New_York", "2026-03-08", 23L),
            Triple("America/New_York", "2026-11-01", 25L),
            Triple("Europe/Berlin", "2026-03-29", 23L),
            Triple("Europe/Berlin", "2026-10-25", 25L),
            Triple("Asia/Shanghai", "2026-03-08", 24L),
            Triple("UTC", "2026-12-31", 24L)
        )) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            val day = LocalDate.parse(date)
            val start = millis(day)
            val end = DateTimeUtils.startOfNextDayMillis(start)
            assertEquals(zone, millis(day.plusDays(1)), end)
            assertEquals(zone, hours * 60 * 60 * 1000, end - start)
            assertEquals(start, DateTimeUtils.normalizeToDay(end - 1))
            assertEquals(end, DateTimeUtils.normalizeToDay(end))
        }
    }

    @Test
    fun current_and_best_streak_continue_across_both_DST_transitions() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        for (transition in listOf("2026-03-08", "2026-11-01")) {
            val today = LocalDate.parse(transition).plusDays(1)
            val days = (0L..3L).map { millis(today.minusDays(it)) }
            assertEquals(4, StreakCalculator.calculateCurrentStreakFromDates(days, today))
            assertEquals(4, StreakCalculator.calculateBestStreakFromDates(days))
            assertEquals(3, StreakCalculator.calculateCurrentStreakFromDates(days.drop(1), today))
            assertEquals(1, StreakCalculator.calculateCurrentStreakFromDates(listOf(days[0], days[2]), today))
            assertEquals(4, StreakCalculator.calculateBestStreakFromDates(days + days))
        }
    }

    private fun millis(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
