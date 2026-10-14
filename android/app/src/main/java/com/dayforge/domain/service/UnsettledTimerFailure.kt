package com.dayforge.domain.service

import com.dayforge.data.local.dao.TimerFailureDaySnapshot
import java.time.LocalDate
import java.time.ZoneId

/** Read-only upper bound, NOT elapsed proof, completion, allocation or write authorization. */
internal object UnsettledTimerFailure {
    fun maySettle(snapshot: TimerFailureDaySnapshot, date: LocalDate, targetSeconds: Long): Boolean {
        val log = snapshot.activeLog ?: return false
        // Old sessions with no captured intervals cannot be reconstructed from their start time.
        if (snapshot.segments.isEmpty() || log.timerTimezone == null) return false
        val zone = ZoneId.of(log.timerTimezone)
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val segments = snapshot.segments
        check(segments.all { it.sessionUuid == log.uuid && it.sequence > 0 && it.startedAt >= log.startTime &&
            (it.endedAt == null || it.endedAt >= it.startedAt) }) { "TIMER_FAILURE_INVALID_SEGMENTS" }
        check(segments.zipWithNext().all { (a, b) -> a.sequence < b.sequence &&
            a.endedAt != null && a.endedAt <= b.startedAt }) { "TIMER_FAILURE_INVALID_SEGMENTS" }
        check(segments.count { it.endedAt == null } == if (log.isPaused) 0 else 1) {
            "TIMER_FAILURE_INVALID_SEGMENTS"
        }
        // A running interval can still contribute up to the captured-zone boundary. A pause
        // contributes nothing. Use the existing absolute ceiling only as an upper bound;
        // the actual start policy and clock-safe stop may accept less or cancel everything.
        // In particular, this never extends the service's shorter start-policy limit.
        val bounded = segments.map { segment ->
            if (segment.endedAt == null) segment.copy(endedAt = maxOf(segment.startedAt, dayEnd)) else segment
        }
        val upper = DurationDayAllocator.allocate(log.uuid, log.habitId, log.timerTimezone, bounded,
            maximumDurationMillis = 86_400_000L).firstOrNull { it.localDate == date.toString() }?.durationMillis ?: 0L
        // Add before truncating: 59,999 ms completed plus 1 ms unsettled can settle a 60 s day.
        return Math.addExact(snapshot.completedMillis, upper) / 1_000L >= targetSeconds
    }
}
