package com.dayforge.ui.screens.metrics

import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dayforge.R
import com.dayforge.domain.model.CardColorStyle
import com.dayforge.ui.components.CompactMetricCard
import com.dayforge.ui.components.MetricCard
import com.dayforge.ui.screens.dashboard.MetricWithLatestValue

/**
 * MetricsScreen displays all metrics in a dedicated screen.
 *
 * Per HTI-01: Metrics tab as third bottom navigation item.
 * Per HTI-02: Metrics screen shows MetricCard list with create FAB.
 *
 * @param viewModel The MetricsViewModel for data and operations
 * @param onCreateMetricClick Callback when FAB is clicked (for create metric screen)
 * @param onMetricClick Callback when a metric is clicked (for detail screen)
 * @param onEditMetricClick Callback when edit is requested (for edit metric screen)
 * @param modifier Modifier for custom styling
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricsScreen(
    viewModel: MetricsViewModel = hiltViewModel(),
    windowSizeClass: WindowSizeClass,
    onCreateMetricClick: () -> Unit,
    onMetricClick: (Long) -> Unit,
    onEditMetricClick: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val metricsWithLatest by viewModel.metricsWithLatest.collectAsState()
    val isInitialized by viewModel.isInitialized.collectAsState()
    // Card color style for reactive card rendering (CARD-09)
    val cardColorStyle by viewModel.cardColorStyle.collectAsState()

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateMetricClick,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                    contentDescription = stringResource(R.string.metrics_fab_content_description)
                )
            }
        }
    ) { padding ->
        // Show loading indicator while data is being loaded
        if (!isInitialized) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.common_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (metricsWithLatest.isEmpty()) {
            // Empty state
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.metrics_empty_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.metrics_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // METRIC-01: Check if window is larger than phone (>=600dp) for adaptive layout
            val isExpandedWindow = windowSizeClass.widthSizeClass > WindowWidthSizeClass.Compact

            if (isExpandedWindow) {
                // METRIC-01: Tablet layout - Grid layout for metrics
                // METRIC-02: GridCells.Adaptive for flexible column count based on tablet width
                // Increased minSize to 280dp for comfortable card display (previously 160dp was too small)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 280.dp),
                    modifier = modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = metricsWithLatest,
                        key = { it.metric.id }
                    ) { metricWithLatest ->
                        CompactMetricCard(
                            metric = metricWithLatest.metric,
                            latestValue = metricWithLatest.latestValue,
                            onClick = { onMetricClick(metricWithLatest.metric.id) },
                            cardColorStyle = cardColorStyle,
                            modifier = Modifier.padding(4.dp)
                        )
                    }
                }
            } else {
                // METRIC-03: Phone layout - existing LazyColumn preserved
                LazyColumn(
                    modifier = modifier.padding(padding),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(
                        items = metricsWithLatest,
                        key = { it.metric.id }
                    ) { metricWithLatest ->
                        MetricCard(
                            metric = metricWithLatest.metric,
                            cardColorStyle = cardColorStyle,
                            latestValue = metricWithLatest.latestValue,
                            latestLogDate = metricWithLatest.latestLogDate,
                            logs = metricWithLatest.logs,
                            onClick = { onMetricClick(metricWithLatest.metric.id) },
                            onAggregationTypeChange = { aggregationType ->
                                viewModel.updateAggregationType(metricWithLatest.metric.id, aggregationType)
                            },
                            modifier = Modifier.padding(horizontal = 0.dp, vertical = 0.dp)
                        )
                    }
                }
            }
        }
    }
}