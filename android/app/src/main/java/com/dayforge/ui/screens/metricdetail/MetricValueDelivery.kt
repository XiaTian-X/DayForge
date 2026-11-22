package com.dayforge.ui.screens.metricdetail

import com.dayforge.data.local.DatabaseObjectSelection
import com.dayforge.data.local.DatabaseRuntimeHost
import com.dayforge.data.repository.IndependentMetricSaved

/** Result delivery owns its original Host lease only while the repository acknowledges.
 * Returning is not a save grant; the ViewModel must still match the exact rendered result.
 * The repository remains the only T2 producer and performs its complete fresh checks. */
internal fun interface MetricValueDelivery {
    suspend fun deliver(host: DatabaseRuntimeHost, selection: DatabaseObjectSelection,
        saved: IndependentMetricSaved, isConsumerCurrent: () -> Boolean)
}

internal object RepositoryMetricValueDelivery : MetricValueDelivery {
    override suspend fun deliver(host: DatabaseRuntimeHost, selection: DatabaseObjectSelection,
        saved: IndependentMetricSaved, isConsumerCurrent: () -> Boolean) {
        host.withSelection(selection, DatabaseObjectSelection.Kind.METRIC) { graph ->
            graph.metricDetailSourceReader.visibleMetric(selection.id)?.let(selection::requireMetric)
            graph.metricRepository.acknowledgeIndependentValue(saved, isConsumerCurrent)
        }
    }
}
