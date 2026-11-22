package com.dayforge.ui.screens.metrics

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.data.local.DatabaseEpochValue
import com.dayforge.data.local.DatabaseObjectSelection
import com.dayforge.data.local.DatabaseRuntimeHost
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.repository.MetricRepository
import com.dayforge.domain.model.MetricWithLatestValue
import com.dayforge.domain.service.MetricOverviewProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MetricsViewModel internal constructor(
    @param:ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val metricRepository: MetricRepository?,
    private val metricOverviewProvider: MetricOverviewProvider?,
    private val databases: DatabaseRuntimeHost? = null
) : ViewModel() {
    @Inject internal constructor(@ApplicationContext context: Context, preferencesManager: PreferencesManager,
        databases: DatabaseRuntimeHost) : this(context, preferencesManager, null, null, databases)

    internal val usesSourceEpoch: Boolean get() = databases != null
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    /** Tag the actual overview emission before buffering; never sign old rows on click. */
    val metricsWithLatest: StateFlow<List<MetricWithLatestValue>> = databases?.let { host ->
        val source = host.observe { graph -> graph.metricOverviewProvider.observe() }.map { frame ->
            DatabaseEpochValue(frame.value.map { it.copy(sourceEpoch = frame.publication) }, frame.publication)
        }
        com.dayforge.ui.epochPublishedState(viewModelScope, host, com.dayforge.ui.VisibleScreenSharing,
            emptyList(), source) { _isInitialized.value = it }
    } ?: requireNotNull(metricOverviewProvider).observe()
        .onEach { _isInitialized.value = true }
        .stateIn(viewModelScope, com.dayforge.ui.VisibleScreenSharing, emptyList())

    private val aggregationActions = com.dayforge.ui.InFlightActions()
    fun updateAggregationType(metricId: Long, aggregationType: String,
        selection: DatabaseObjectSelection? = null) {
        aggregationActions.launch(viewModelScope, metricId) {
            try {
                val host = databases
                if (host == null) requireNotNull(metricRepository).updateAggregationType(metricId, aggregationType)
                else host.withSelection(selection, DatabaseObjectSelection.Kind.METRIC) { graph ->
                    val original = requireNotNull(selection)
                    check(original.id == metricId) { "DATABASE_EPOCH_SELECTION_CHANGED" }
                    original.requireMetric(requireNotNull(graph.metricDetailSourceReader.visibleMetric(metricId)) {
                        "DATABASE_EPOCH_SELECTION_CHANGED"
                    })
                    // The repository repeats UUID matching in its original actual write Tx.
                    graph.metricRepository.updateAggregationType(metricId, aggregationType, expectedMetricUuid = original.uuid)
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Toast.makeText(context, com.dayforge.ui.actionFailureMessage(context, error), Toast.LENGTH_LONG).show()
            }
        }
    }
}
