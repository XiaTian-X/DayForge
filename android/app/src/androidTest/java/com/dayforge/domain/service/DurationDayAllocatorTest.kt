package com.dayforge.domain.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

import com.dayforge.data.local.entity.TimerSegmentEntity
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

@RunWith(AndroidJUnit4::class)
class DurationDayAllocatorTest {
    @Test
    fun cross_midnight_duration_is_split_at_captured_timezone_midnight() {
        val zone = ZoneId.of("Asia/Shanghai")
        val start = ZonedDateTime.of(2026, 8, 14, 23, 30, 0, 0, zone).toInstant().toEpochMilli()
        val end = ZonedDateTime.of(2026, 8, 15, 0, 30, 0, 0, zone).toInstant().toEpochMilli()

        val result = DurationDayAllocator.allocate(
            "session", 1, zone.id,
            listOf(TimerSegmentEntity(sessionUuid = "session", sequence = 1, startedAt = start, endedAt = end))
        )

        assertEquals(listOf("2026-08-14", "2026-08-15"), result.map { it.localDate })
        assertEquals(listOf(1_800_000L, 1_800_000L), result.map { it.durationMillis })
    }

    @Test
    fun paused_interval_spanning_midnight_is_excluded() {
        val zone = ZoneId.of("Asia/Shanghai")
        fun at(day: Int, hour: Int, minute: Int) =
            ZonedDateTime.of(2026, 8, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

        val result = DurationDayAllocator.allocate(
            "session", 1, zone.id,
            listOf(
                TimerSegmentEntity(sessionUuid = "session", sequence = 1, startedAt = at(14, 23, 30), endedAt = at(14, 23, 50)),
                TimerSegmentEntity(sessionUuid = "session", sequence = 3, startedAt = at(15, 0, 10), endedAt = at(15, 0, 40))
            )
        )

        assertEquals(listOf(1_200_000L, 1_800_000L), result.map { it.durationMillis })
    }

    @Test
    fun dst_spring_day_uses_real_23_hour_boundary() {
        val zone = ZoneId.of("America/New_York")
        val day = LocalDate.of(2026, 3, 8)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val result = DurationDayAllocator.allocate(
            "session", 1, zone.id,
            listOf(TimerSegmentEntity(sessionUuid = "session", sequence = 1, startedAt = start, endedAt = end))
        )

        assertEquals(23L * 60 * 60 * 1_000, result.single().durationMillis)
    }

    @Test
    fun dst_fall_day_uses_real_25_hour_boundary() {
        val zone = ZoneId.of("America/New_York")
        val day = LocalDate.of(2026, 11, 1)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val result = DurationDayAllocator.allocate(
            "session", 1, zone.id,
            listOf(
                TimerSegmentEntity(
                    sessionUuid = "session",
                    sequence = 1,
                    startedAt = start,
                    endedAt = end
                )
            )
        )

        assertEquals(25L * 60 * 60 * 1_000, result.single().durationMillis)
    }

    @Test
    fun maximum_duration_clamps_across_ordered_running_segments() {
        val zone = ZoneId.of("UTC")
        fun at(day: Int, hour: Int) =
            ZonedDateTime.of(2026, 8, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

        val result = DurationDayAllocator.allocate(
            "session", 1, zone.id,
            listOf(
                TimerSegmentEntity(
                    sessionUuid = "session",
                    sequence = 1,
                    startedAt = at(14, 23),
                    endedAt = at(15, 0)
                ),
                TimerSegmentEntity(
                    sessionUuid = "session",
                    sequence = 3,
                    startedAt = at(15, 1),
                    endedAt = at(15, 2)
                )
            ),
            maximumDurationMillis = 90L * 60 * 1_000
        )

        assertEquals(listOf("2026-08-14", "2026-08-15"), result.map { it.localDate })
        assertEquals(listOf(60L * 60 * 1_000, 30L * 60 * 1_000), result.map { it.durationMillis })
    }
    @Test
    fun literal_utc_interval_crosses_a_short_dst_day_without_losing_milliseconds() {
        // 2026-03-08 00:00 New York through 2026-03-09 01:00, plus 123 ms.
        val result = DurationDayAllocator.allocate("fixed-session", 7, "America/New_York", listOf(
            TimerSegmentEntity(sessionUuid = "fixed-session", sequence = 1,
                startedAt = 1772946000000L, endedAt = 1773032400123L)
        ))
        assertEquals(listOf("2026-03-08", "2026-03-09"), result.map { it.localDate })
        assertEquals(listOf(82800000L, 3600123L), result.map { it.durationMillis })
        assertEquals(listOf(1772946000000L, 1773028800000L), result.map { it.localDateEpoch })
        assertEquals(86400123L, result.sumOf { it.durationMillis })
        assertEquals(listOf("fixed-session", "fixed-session"), result.map { it.sessionUuid })
        assertEquals(listOf(7L, 7L), result.map { it.habitId })
        assertEquals(listOf("America/New_York", "America/New_York"), result.map { it.timezone })
    }

}
