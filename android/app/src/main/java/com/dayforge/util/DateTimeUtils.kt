package com.dayforge.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Utility object for timezone-aware date calculations.
 *
 * Uses java.time API to correctly calculate local timezone midnight,
 * fixing the issue where UTC midnight was used instead of local midnight.
 *
 * Problem: In UTC+8 timezone, between 0:00-8:00 local time, the UTC date
 * is still "yesterday", causing check-ins to be recorded on wrong dates.
 *
 * Solution: Use ZoneId.systemDefault() to get local timezone midnight.
 */
object DateTimeUtils {

    /**
     * Milliseconds per day constant.
     */
    const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L

    /**
     * Returns the start of the current day in milliseconds using local timezone.
     *
     * For example, in UTC+8 timezone at 3:00 AM local time:
     * - This returns midnight of the local date (0:00 local time)
     * - Not UTC midnight which would be 8:00 AM local time
     *
     * @return Epoch milliseconds of local midnight today
     */
    fun startOfDayMillis(): Long {
        return LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Returns the start of the given day in milliseconds using local timezone.
     *
     * @param timestamp Epoch milliseconds to get the day start for
     * @return Epoch milliseconds of local midnight for that day
     */
    fun startOfDayMillis(timestamp: Long): Long {
        return ZonedDateTime
            .ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Normalizes a timestamp to the start of its day (midnight local timezone).
     *
     * This is useful for comparing dates regardless of the time of day.
     *
     * @param millis Epoch milliseconds to normalize
     * @return Epoch milliseconds of local midnight for that day
     */
    fun normalizeToDay(millis: Long): Long {
        return startOfDayMillis(millis)
    }

    /** Exclusive end of the local calendar day; DST days need not be 24 hours long. */
    fun startOfNextDayMillis(timestamp: Long): Long {
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
            .plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /**
     * Returns the current LocalDate in the system default timezone.
     *
     * @return Current LocalDate
     */
    fun today(): LocalDate {
        return LocalDate.now(ZoneId.systemDefault())
    }

    /**
     * Returns the start of today as a LocalDate in UTC epoch milliseconds.
     * Used for TIMER habits that need UTC-based date range queries.
     *
     * @return Epoch milliseconds of UTC midnight today
     */
    fun startOfTodayUtc(): Long {
        return LocalDate.now()
            .atStartOfDay(ZoneId.of("UTC"))
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Returns the start of tomorrow as a LocalDate in UTC epoch milliseconds.
     * Used for TIMER habits that need UTC-based date range queries.
     *
     * @return Epoch milliseconds of UTC midnight tomorrow
     */
    fun startOfTomorrowUtc(): Long {
        return LocalDate.now()
            .plusDays(1)
            .atStartOfDay(ZoneId.of("UTC"))
            .toInstant()
            .toEpochMilli()
    }
}
