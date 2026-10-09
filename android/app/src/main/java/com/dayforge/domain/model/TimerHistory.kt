package com.dayforge.domain.model

import com.dayforge.data.local.dao.TimerFailureDaySnapshot
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.local.entity.TimerSegmentEntity
import java.time.LocalDate

/** Completed milliseconds only. Unsettled intervals are never published as completion. */
data class TimerHistory(
    val today: LocalDate,
    val targetSeconds: Long,
    val completedMillis: Map<LocalDate, Long>,
    val logs: List<TimeLogEntity>,
    val activeLog: TimeLogEntity?,
    val activeSegments: List<TimerSegmentEntity>,
    val roundHead: ChallengeRoundHead?
) {
    val qualifiedDates: Set<LocalDate> get() = completedMillis.filterValues { it / 1_000 >= targetSeconds }.keys
    val todaySeconds: Long get() = (completedMillis[today] ?: 0L) / 1_000
    val completedToday: Boolean get() = todaySeconds >= targetSeconds
    // Preserve the challenge's existing first completed-day start rule. An active session alone
    // does not start a completed-day challenge, but may settle an already-started past date.
    val firstDate: LocalDate? get() = completedMillis.keys.minOrNull()
    fun failureDay(date: LocalDate) = TimerFailureDaySnapshot(completedMillis[date] ?: 0L, activeLog, activeSegments)
}
