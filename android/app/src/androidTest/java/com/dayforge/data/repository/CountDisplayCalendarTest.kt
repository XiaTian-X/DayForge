package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.service.ScheduleValidator
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountDisplayCalendarTest {
    private val zone = ZoneId.of("Etc/UTC")
    private val created = LocalDate.of(2024, 3, 3)
    private fun habit(schedule: HabitSchedule) = HabitEntity(name = "calendar", habitType = HabitType.COUNTING,
        iconResId = 0, colorHex = "#FFFFFF", schedule = schedule,
        createdAt = created.atStartOfDay(zone).toInstant().toEpochMilli())
    private fun original(habit: HabitEntity, first: LocalDate, length: Int): LocalDate? =
        (0 until length).map { first.plusDays(it.toLong()) }.firstOrNull {
            ScheduleValidator.isCheckInAllowedOnDate(habit.schedule, habit.createdAt, it, zone)
        }
    @Test fun beforeCreationAndEveryOriginalScheduleMatchTheOriginalPredicate() {
        val schedules = listOf(HabitSchedule.Once(), HabitSchedule.Daily, HabitSchedule.Weekly(emptyList()),
            HabitSchedule.Weekly(listOf(7, 1, 1, 4)), HabitSchedule.Monthly(31), HabitSchedule.Monthly(-4),
            HabitSchedule.Custom(3), HabitSchedule.Custom(400), HabitSchedule.Custom(-11))
        for (schedule in schedules) for (offset in listOf(-1000L, -401L, -7L, -3L, -1L, 0L, 1L, 399L, 1000L)) {
            val value = habit(schedule); val first = created.plusDays(offset)
            for (size in listOf(1, 7, 40, 420)) assertEquals(original(value, first, size),
                CountDisplayCalendar.firstDue(value, zone, first, first.plusDays(size.toLong())))
        }
    }
    @Test fun signedIntWrapUsesActualOriginalRemaindersRatherThanAnUnboundedDayModulo() {
        val schedules = listOf(HabitSchedule.Weekly(emptyList()), HabitSchedule.Custom(11), HabitSchedule.Custom(400),
            HabitSchedule.Custom(Int.MAX_VALUE), HabitSchedule.Custom(Int.MIN_VALUE))
        val offsets = listOf(Int.MAX_VALUE.toLong() - 8L, Int.MAX_VALUE.toLong() - 1L, Int.MAX_VALUE.toLong(),
            Int.MAX_VALUE.toLong() + 1L, Int.MIN_VALUE.toLong() - 8L, Int.MIN_VALUE.toLong(),
            2L * (Int.MAX_VALUE.toLong() + 1L) - 8L)
        for (schedule in schedules) for (offset in offsets) {
            val value = habit(schedule); val first = created.plusDays(offset)
            assertEquals(original(value, first, 820), CountDisplayCalendar.firstDue(value, zone, first, first.plusDays(820)))
        }
    }
    @Test fun hugeValidPeriodsDoNotScanAbsentYearsAndTheEndBoundIsExclusive() {
        val value = habit(HabitSchedule.Custom(Int.MAX_VALUE))
        val first = created.plusDays(1)
        val due = created.plusDays(Int.MAX_VALUE.toLong())
        assertNull(CountDisplayCalendar.firstDue(value, zone, first, due))
        assertEquals(due, CountDisplayCalendar.firstDue(value, zone, first, due.plusDays(1)))
        assertTrue(ScheduleValidator.isCheckInAllowedOnDate(value.schedule, value.createdAt, due, zone))
        val monthly = habit(HabitSchedule.Monthly(31))
        val leap = LocalDate.of(2024, 2, 29)
        assertEquals(leap, CountDisplayCalendar.firstDue(monthly, zone, LocalDate.of(2024, 2, 1), leap.plusDays(1)))
        assertNull(CountDisplayCalendar.firstDue(monthly, zone, LocalDate.of(2024, 2, 1), leap))
    }
    @Test fun dateBoundsEmptyIntervalsAndMalformedZeroRetainOriginalOutcomes() {
        val month = habit(HabitSchedule.Monthly(1))
        assertNull(CountDisplayCalendar.firstDue(month, zone, LocalDate.MAX.minusDays(1), LocalDate.MAX))
        assertNull(CountDisplayCalendar.firstDue(month, zone, created, created))
        val invalid = habit(HabitSchedule.Custom(0))
        assertThrows(ArithmeticException::class.java) { original(invalid, created, 1) }
        assertThrows(ArithmeticException::class.java) { CountDisplayCalendar.firstDue(invalid, zone, created, created.plusDays(1)) }
        assertNull(CountDisplayCalendar.firstDue(invalid, zone, created, created))
    }
}
