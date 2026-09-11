package com.dayforge.ui.screens.metrics

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.domain.model.CardColorStyle
import com.dayforge.domain.service.StructuralEditGuard
import com.dayforge.ui.screens.dashboard.MetricWithLatestValue
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class MetricsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metricDao: MetricDao,
    private val metricLogDao: MetricLogDao,
    private val preferencesManager: PreferencesManager,
    private val structuralEditGuard: StructuralEditGuard
) : ViewModel() {

    // Track if data has been loaded at least once
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    /**
     * Metrics with their latest values for Metrics screen display.
     * Reuses MetricWithLatestValue data class from DashboardViewModel.
     *
     * Combines metrics with latest log changes to ensure UI refreshes when new logs are recorded.
     */
    val metricsWithLatest: StateFlow<List<MetricWithLatestValue>> = combine(
        metricDao.getAllActiveMetrics(),
        metricLogDao.getLatestLogFlow()
    ) { metrics, _ ->
        metrics.map { metric ->
            val latestLog = withContext(Dispatchers.IO) { metricLogDao.getLatestLog(metric.id) }
            // Load last 30 days of logs for trend chart
            val logs = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val thirtyDaysAgo = now - (30 * 24 * 60 * 60 * 1000L)
                metricLogDao.getLogsInRange(metric.id, thirtyDaysAgo, now)
            }
            MetricWithLatestValue(
                metric = metric,
                latestValue = latestLog?.value,
                latestLogDate = latestLog?.date,
                logs = logs
            )
        }
    }
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
                structuralEditGuard.requireAllowed()
                metricDao.updateAggregationType(metricId, aggregationType)
            }.onFailure {
                Toast.makeText(context, it.message ?: "当前设备不能修改指标配置", Toast.LENGTH_LONG).show()
            }
        }
    }
}
