package com.dayforge.reminder

import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.service.CountingSlotCalculator
import com.dayforge.domain.service.CountingReminderWindow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** null minute is a silent date refresh, never a reminder generated from yesterday's rule. */
internal data class HabitReminderWake(val date: LocalDate, val minute: Int?, val trigger: Instant,
    val zone: ZoneId, val firstSlot: Int = -1, val lastSlot: Int = -1)

internal object HabitReminderPlan {
    fun next(habit: HabitEntity, target: Int?, quantity: Long, now: ZonedDateTime): HabitReminderWake? {
        val bestTime = habit.bestTime ?: return null
        if (!habit.isActive || habit.habitType == HabitType.GOAL || bestTime !in 0L..1439L) return null
        val today = now.toLocalDate()
        if (nextEligible(habit, today, now.zone) == today) {
            if (habit.habitType == HabitType.COUNTING) {
                if (target != null) for (window in windows(bestTime, target, now)) {
                    val trigger = today.atTime(window.minute / 60, window.minute % 60).atZone(now.zone).toInstant()
                    if (trigger > now.toInstant() && quantity <= window.lastIndex.toLong())
                        return HabitReminderWake(today, window.minute, trigger, now.zone, window.firstIndex, window.lastIndex)
                }
            } else {
                val minute = bestTime.toInt()
                val trigger = today.atTime(minute / 60, minute % 60).atZone(now.zone).toInstant()
                if (trigger > now.toInstant()) return HabitReminderWake(today, minute, trigger, now.zone)
            }
        }
        val nextDate = nextEligible(habit, today.plusDays(1), now.zone)
        // COUNTING must re-read the new day's own rule, even after an undo or westward travel.
        if (habit.habitType == HabitType.COUNTING)
            return HabitReminderWake(nextDate, null, nextDate.atStartOfDay(now.zone).toInstant(), now.zone)
        val minute = bestTime.toInt()
        return HabitReminderWake(nextDate, minute,
            nextDate.atTime(minute / 60, minute % 60).atZone(now.zone).toInstant(), now.zone)
    }

    fun window(habit: HabitEntity, target: Int?, minute: Int, now: ZonedDateTime): Pair<Int, Int>? {
        if (habit.habitType != HabitType.COUNTING || target == null) return null
        return windows(requireNotNull(habit.bestTime), target, now)
            .firstOrNull { it.minute == minute }?.let { it.firstIndex to it.lastIndex }
    }

    private fun windows(bestTime: Long, target: Int, now: ZonedDateTime): List<CountingReminderWindow> =
        // Target one previously used the single-reminder path at bestTime, not windowStart.
        if (target == 1) listOf(CountingReminderWindow(bestTime.toInt(), 0, 0))
        else CountingSlotCalculator.reminderWindows(bestTime, target, now)

    fun nextEligible(habit: HabitEntity, from: LocalDate, zone: ZoneId): LocalDate = when (val schedule = habit.schedule) {
        is HabitSchedule.Daily, is HabitSchedule.Once -> from
        is HabitSchedule.Weekly -> if (schedule.daysOfWeek.isNotEmpty()) {
            require(schedule.daysOfWeek.all { it in 1..7 })
            (0L..6L).map { from.plusDays(it) }.first { it.dayOfWeek.value in schedule.daysOfWeek }
        } else periodic(habit, from, zone, 7)
        is HabitSchedule.Custom -> periodic(habit, from, zone, schedule.frequencyDays)
        is HabitSchedule.Monthly -> {
            val day = schedule.dayOfMonth.coerceIn(1, 31)
            val thisMonth = from.withDayOfMonth(minOf(day, from.lengthOfMonth()))
            if (thisMonth >= from) thisMonth else from.plusMonths(1).let { it.withDayOfMonth(minOf(day, it.lengthOfMonth())) }
        }
    }

    private fun periodic(habit: HabitEntity, from: LocalDate, zone: ZoneId, days: Int): LocalDate {
        require(days > 0)
        val created = Instant.ofEpochMilli(habit.createdAt).atZone(zone).toLocalDate()
        val elapsed = ChronoUnit.DAYS.between(created, from)
        return from.plusDays(Math.floorMod(-elapsed, days.toLong()))
    }
}
