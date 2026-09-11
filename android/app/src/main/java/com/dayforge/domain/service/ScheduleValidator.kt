package com.dayforge.domain.service

import com.dayforge.data.model.HabitSchedule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object ScheduleValidator {

    /**
     * Checks if today is a valid check-in day for the given schedule.
     *
     * @param schedule The habit's schedule configuration
     * @param createdAt The habit's creation timestamp (millis since epoch)
     * @return true if check-in is allowed today, false otherwise
     */
    fun isCheckInAllowedToday(schedule: HabitSchedule, createdAt: Long): Boolean {
        return when (schedule) {
            is HabitSchedule.Daily -> true // Daily habits allow check-in every day
            is HabitSchedule.Weekly -> isWeeklyCheckInDay(schedule, createdAt)
            is HabitSchedule.Monthly -> isMonthlyCheckInDay(schedule)
            is HabitSchedule.Custom -> isCustomCheckInDay(schedule, createdAt)
        }
    }

    /**
     * Weekly schedule: Dual-mode check-in logic.
     * - daysOfWeek empty: original every-7-days logic (creation day + intervals)
     * - daysOfWeek has values: check if today's weekday is in the list (1=Monday, 7=Sunday)
     */
    private fun isWeeklyCheckInDay(schedule: HabitSchedule.Weekly, createdAt: Long): Boolean {
        if (schedule.daysOfWeek.isEmpty()) {
            // Original logic: check-in every 7 days from creation
            val today = LocalDate.now()
            val creationDate = Instant.ofEpochMilli(createdAt)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            val daysSinceCreation = ChronoUnit.DAYS.between(creationDate, today).toInt()
            return daysSinceCreation % 7 == 0
        } else {
            // New logic: check if today's weekday (1=Mon, 7=Sun) is in daysOfWeek
            val today = LocalDate.now()
            val dayOfWeek = today.dayOfWeek.value  // 1=Monday, 7=Sunday
            return dayOfWeek in schedule.daysOfWeek
        }
    }

    /**
     * Monthly schedule: Check-in allowed on the same day of each month.
     * Handles month-end edge cases (e.g., day 31 in February).
     */
    private fun isMonthlyCheckInDay(schedule: HabitSchedule.Monthly): Boolean {
        val today = LocalDate.now()
        val targetDay = schedule.dayOfMonth.coerceIn(1, 31)

        // Handle months with fewer days than target
        val lastDayOfMonth = today.lengthOfMonth()
        val effectiveDay = targetDay.coerceAtMost(lastDayOfMonth)

        return today.dayOfMonth == effectiveDay
    }

    /**
     * Custom schedule: Check-in allowed every N days from creation date.
     */
    private fun isCustomCheckInDay(schedule: HabitSchedule.Custom, createdAt: Long): Boolean {
        val today = LocalDate.now()
        val creationDate = Instant.ofEpochMilli(createdAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()

        val daysSinceCreation = ChronoUnit.DAYS.between(creationDate, today).toInt()
        return daysSinceCreation % schedule.frequencyDays == 0
    }

    /**
     * Gets the next check-in date for the given schedule.
     *
     * @param schedule The habit's schedule configuration
     * @param createdAt The habit's creation timestamp
     * @return LocalDate of the next check-in day, or today if check-in is allowed
     */
    fun getNextCheckInDate(schedule: HabitSchedule, createdAt: Long): LocalDate {
        val today = LocalDate.now()

        if (isCheckInAllowedToday(schedule, createdAt)) {
            return today
        }

        return when (schedule) {
            is HabitSchedule.Daily -> today.plusDays(1)
            is HabitSchedule.Weekly -> {
                if (schedule.daysOfWeek.isEmpty()) {
                    // Original logic
                    val creationDate = Instant.ofEpochMilli(createdAt)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                    val daysSinceCreation = ChronoUnit.DAYS.between(creationDate, today).toInt()
                    val daysUntilNext = 7 - (daysSinceCreation % 7)
                    today.plusDays(daysUntilNext.toLong())
                } else {
                    // New logic: find next weekday in daysOfWeek
                    val currentDayOfWeek = today.dayOfWeek.value  // 1=Monday, 7=Sunday
                    val sortedDays = schedule.daysOfWeek.sorted()
                    val nextDay = sortedDays.firstOrNull { it > currentDayOfWeek }
                        ?: sortedDays.first()  // wrap to next week
                    val daysToAdd = if (nextDay > currentDayOfWeek) {
                        nextDay - currentDayOfWeek
                    } else {
                        7 - currentDayOfWeek + nextDay  // wrap around week
                    }
                    today.plusDays(daysToAdd.toLong())
                }
            }
            is HabitSchedule.Monthly -> {
                val targetDay = schedule.dayOfMonth.coerceIn(1, 31)
                val lastDayOfCurrentMonth = today.lengthOfMonth()
                val effectiveDay = targetDay.coerceAtMost(lastDayOfCurrentMonth)

                if (today.dayOfMonth < effectiveDay) {
                    today.withDayOfMonth(effectiveDay)
                } else {
                    // Move to next month
                    val nextMonth = today.plusMonths(1)
                    val lastDayOfNextMonth = nextMonth.lengthOfMonth()
                    nextMonth.withDayOfMonth(targetDay.coerceAtMost(lastDayOfNextMonth))
                }
            }
            is HabitSchedule.Custom -> {
                val creationDate = Instant.ofEpochMilli(createdAt)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                val daysSinceCreation = ChronoUnit.DAYS.between(creationDate, today).toInt()
                val daysUntilNext = schedule.frequencyDays - (daysSinceCreation % schedule.frequencyDays)
                today.plusDays(daysUntilNext.toLong())
            }
        }
    }

    /**
     * Checks if a specific date is a valid check-in day.
     * Used by FailureChecker and widgets to iterate through past dates.
     *
     * @param schedule The habit's schedule configuration
     * @param createdAt The habit's creation timestamp (millis since epoch)
     * @param date The date to check
     * @return true if check-in is allowed on the given date, false otherwise
     */
    fun isCheckInAllowedOnDate(schedule: HabitSchedule, createdAt: Long, date: LocalDate): Boolean {
        return when (schedule) {
            is HabitSchedule.Daily -> true
            is HabitSchedule.Weekly -> {
                if (schedule.daysOfWeek.isEmpty()) {
                    // Original logic
                    val creationDate = Instant.ofEpochMilli(createdAt)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                    val daysSinceCreation = ChronoUnit.DAYS.between(creationDate, date).toInt()
                    daysSinceCreation % 7 == 0
                } else {
                    // New logic: check weekday
                    val dayOfWeek = date.dayOfWeek.value  // 1=Monday, 7=Sunday
                    dayOfWeek in schedule.daysOfWeek
                }
            }
            is HabitSchedule.Monthly -> {
                val targetDay = schedule.dayOfMonth.coerceIn(1, 31)
                date.dayOfMonth == targetDay.coerceAtMost(date.lengthOfMonth())
            }
            is HabitSchedule.Custom -> {
                val creationDate = Instant.ofEpochMilli(createdAt)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                val daysSinceCreation = ChronoUnit.DAYS.between(creationDate, date).toInt()
                daysSinceCreation % schedule.frequencyDays == 0
            }
        }
    }
}