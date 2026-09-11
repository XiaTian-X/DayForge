package com.dayforge.domain.service

import com.dayforge.data.local.entity.TimerSegmentEntity
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DurationDayAllocatorTest {
    @Test
    fun `cross midnight duration is split at captured timezone midnight`() {
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
    fun `paused interval spanning midnight is excluded`() {
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
    fun `dst spring day uses real 23 hour boundary`() {
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
}
