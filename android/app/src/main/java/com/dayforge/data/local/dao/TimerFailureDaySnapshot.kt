package com.dayforge.data.local.dao

import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.local.entity.TimerSegmentEntity

/** Completed data and unsettled intervals from one Room read transaction. */
data class TimerFailureDaySnapshot(
    val completedMillis: Long,
    val activeLog: TimeLogEntity?,
    val segments: List<TimerSegmentEntity>
) {
    val completedSeconds: Long get() = completedMillis / 1_000L
}
