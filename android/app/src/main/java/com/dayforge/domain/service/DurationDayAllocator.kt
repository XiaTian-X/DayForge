package com.dayforge.domain.service

import com.dayforge.data.local.entity.TimeLogDayAllocationEntity
import com.dayforge.data.local.entity.TimerSegmentEntity
import java.time.Instant
import java.time.ZoneId

/** Splits running intervals at real local-midnight boundaries, including DST days. */
object DurationDayAllocator {
    fun allocate(
        sessionUuid: String,
        habitId: Long,
        timezone: String,
        segments: List<TimerSegmentEntity>,
        maximumDurationMillis: Long = Long.MAX_VALUE
    ): List<TimeLogDayAllocationEntity> {
        val zone = ZoneId.of(timezone)
        val totals = linkedMapOf<String, Pair<Long, Long>>()
        var remaining = maximumDurationMillis.coerceAtLeast(0)
        segments.forEach { segment ->
            if (remaining <= 0) return@forEach
            val endedAt = segment.endedAt ?: return@forEach
            var cursor = Instant.ofEpochMilli(segment.startedAt)
            val naturalEnd = Instant.ofEpochMilli(endedAt)
            val end = if (remaining == Long.MAX_VALUE) {
                naturalEnd
            } else {
                minOf(naturalEnd, cursor.plusMillis(remaining))
            }
            while (cursor < end) {
                val localDate = cursor.atZone(zone).toLocalDate()
                val midnight = localDate.atStartOfDay(zone).toInstant().toEpochMilli()
                val nextMidnight = localDate.plusDays(1).atStartOfDay(zone).toInstant()
                val chunkEnd = minOf(end, nextMidnight)
                val duration = chunkEnd.toEpochMilli() - cursor.toEpochMilli()
                if (duration > 0) {
                    val key = localDate.toString()
                    val existing = totals[key]
                    totals[key] = midnight to ((existing?.second ?: 0L) + duration)
                    remaining -= duration
                }
                cursor = chunkEnd
            }
        }
        return totals.map { (date, value) ->
            TimeLogDayAllocationEntity(
                sessionUuid = sessionUuid,
                habitId = habitId,
                localDate = date,
                localDateEpoch = value.first,
                timezone = timezone,
                durationMillis = value.second
            )
        }
    }
}
