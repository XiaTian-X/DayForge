package com.dayforge.ui.screens.metrics

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.repository.MetricRepository
import com.dayforge.domain.model.CardColorStyle
import com.dayforge.domain.model.MetricWithLatestValue
import com.dayforge.domain.service.MetricOverviewProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MetricsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val metricRepository: MetricRepository,
    private val metricOverviewProvider: MetricOverviewProvider
) : ViewModel() {

    // Track if data has been loaded at least once
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    /**
     * Metrics with their latest values for Metrics screen display.
     * Shares the same repository-backed projection as the Dashboard.
     */
    val metricsWithLatest: StateFlow<List<MetricWithLatestValue>> = metricOverviewProvider.observe()
        .onEach { _isInitialized.value = true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList()
        )

    /**
     * Card color style preference for reactive card rendering.
     * Per CARD-09: Triggers instant recomposition when style changes.
     */
    val cardColorStyle: StateFlow<CardColorStyle> = preferencesManager.cardColorStyle
        .map { CardColorStyle.fromStringOrDefault(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = CardColorStyle.DEFAULT
        )

    /**
     * Update the aggregation type for a metric.
     * Called when user changes aggregation type in TrendChart.
     */
    fun updateAggregationType(metricId: Long, aggregationType: String) {
        viewModelScope.launch {
            runCatching {
                metricRepository.updateAggregationType(metricId, aggregationType)
            }.onFailure {
                Toast.makeText(context, it.message ?: "当前设备不能修改指标配置", Toast.LENGTH_LONG).show()
            }
        }
    }
}
