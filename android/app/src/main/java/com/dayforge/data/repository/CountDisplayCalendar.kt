package com.dayforge.data.repository

import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Earliest original scheduled date in an authenticated EMPTY calendar interval. This does
 * not prove data absence. It preserves ScheduleValidator's signed Int day truncation and
 * remainder-zero semantics, including dates before creation and an Int wrap boundary. */
internal object CountDisplayCalendar {
    fun firstDue(habit: HabitEntity, zone: ZoneId, first: LocalDate, after: LocalDate): LocalDate? {
        val length = ChronoUnit.DAYS.between(first, after)
        if (length <= 0L) return null
        return when (val schedule = habit.schedule) {
            is HabitSchedule.Once -> null
            is HabitSchedule.Daily -> first
            is HabitSchedule.Weekly -> if (schedule.daysOfWeek.isEmpty())
                periodic(habit, zone, first, length, 7) else {
                // Day-of-week repeats after seven actual calendar days, independent of origin.
                (0L until minOf(7L, length)).firstOrNull { offset ->
                    first.plusDays(offset).dayOfWeek.value in schedule.daysOfWeek
                }?.let(first::plusDays)
            }
            is HabitSchedule.Monthly -> {
                val requested = schedule.dayOfMonth.coerceIn(1, 31)
                var candidate = first.withDayOfMonth(minOf(requested, first.lengthOfMonth()))
                if (candidate < first) {
                    if (first.year == LocalDate.MAX.year && first.monthValue == 12) return null
                    val next = first.plusMonths(1)
                    candidate = next.withDayOfMonth(minOf(requested, next.lengthOfMonth()))
                }
                candidate.takeIf { it < after }
            }
            is HabitSchedule.Custom -> periodic(habit, zone, first, length, schedule.frequencyDays)
        }
    }
    private fun periodic(habit: HabitEntity, zone: ZoneId, first: LocalDate,
        length: Long, divisor: Int): LocalDate? {
        // Formal schedules reject zero earlier. Preserve the old predicate's exception for
        // this pure helper's malformed input rather than silently inventing no due date.
        if (divisor == 0) throw ArithmeticException("/ by zero")
        val period = kotlin.math.abs(divisor.toLong())
        val created = Instant.ofEpochMilli(habit.createdAt).atZone(zone).toLocalDate()
        val start = ChronoUnit.DAYS.between(created, first).toInt().toLong()
        fun distance(value: Long): Long = Math.floorMod(value, period).let { remainder ->
            if (remainder == 0L) 0L else period - remainder
        }
        val ordinary = distance(start)
        val untilWrap = Int.MAX_VALUE.toLong() - start + 1L
        // At most one wrap is needed: after wrap the entire signed Int range is available,
        // and |divisor| <= 2^31. Do not use old getNextCheckInDate's negative-remainder formula.
        val offset = if (ordinary < untilWrap) ordinary else untilWrap + distance(Int.MIN_VALUE.toLong())
        return if (offset < length) first.plusDays(offset) else null
    }
}
