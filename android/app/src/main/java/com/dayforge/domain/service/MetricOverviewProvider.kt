package com.dayforge.domain.service

import com.dayforge.data.repository.MetricRepository
import com.dayforge.domain.model.MetricWithLatestValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import javax.inject.Inject

/** Builds the shared active-metric overview consumed by Dashboard and Metrics screens. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MetricOverviewProvider @Inject constructor(
    private val metricRepository: MetricRepository
) {
    fun observe(): Flow<List<MetricWithLatestValue>> = combine(
        metricRepository.observeActiveMetrics(),
        metricRepository.observeLatestMetricLog()
    ) { metrics, _ -> metrics }.mapLatest { metrics ->
        val endExclusive = System.currentTimeMillis()
        val startInclusive = endExclusive - RECENT_LOG_WINDOW_MILLIS
        metrics.map { metric ->
            val latestLog = metricRepository.getLatestLog(metric.id)
            MetricWithLatestValue(
                metric = metric,
                latestValue = latestLog?.value,
                latestLogDate = latestLog?.date,
                logs = metricRepository.getLogsInRange(
                    metricId = metric.id,
                    startInclusive = startInclusive,
                    endExclusive = endExclusive
                )
            )
        }
    }

    private companion object {
        const val RECENT_LOG_WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1_000
    }
}
