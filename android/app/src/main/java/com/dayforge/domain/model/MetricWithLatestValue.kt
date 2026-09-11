package com.dayforge.domain.model

import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity

/** Metric metadata together with the latest value and recent logs used by overview cards. */
data class MetricWithLatestValue(
    val metric: MetricEntity,
    val latestValue: Double?,
    val latestLogDate: Long?,
    val logs: List<MetricLogEntity> = emptyList()
)
