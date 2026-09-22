package com.dayforge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.core.graphics.toColorInt
import androidx.compose.material3.CircularProgressIndicator
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.decoration.HorizontalLine
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.core.common.shape.Shape
import com.patrykandpatrick.vico.core.common.Fill
import com.dayforge.R
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import java.util.Calendar
import java.text.SimpleDateFormat
import java.text.DecimalFormat
import java.util.Date
import java.util.Locale

import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.fill

/**
 * Time range options for trend chart display.
 * Per D-04: Default is 7 days
 * Per D-05: Supports 7-day and 30-day switching
 */
enum class TimeRange(val days: Int) {
    SEVEN_DAYS(7),
    THIRTY_DAYS(30);

    /**
     * Get display label for this time range.
     */
    @Composable
    fun getLabel(): String = when (this) {
        SEVEN_DAYS -> stringResource(R.string.chart_7_days)
        THIRTY_DAYS -> stringResource(R.string.chart_30_days)
    }
}

/**
 * Aggregation type options for chart data display.
 * - AVERAGE: Calculate mean of all records per day
 * - SUM: Sum all records per day
 * - BY_TIME: Show all records at exact timestamps
 */
enum class AggregationType(val value: String) {
    AVERAGE("average"),
    SUM("sum"),
    BY_TIME("by_time");

    /**
     * Get display label for this aggregation type.
     */
    @Composable
    fun getLabel(): String = when (this) {
        AVERAGE -> stringResource(R.string.chart_daily_average)
        SUM -> stringResource(R.string.chart_daily_total)
        BY_TIME -> stringResource(R.string.chart_all_records)
    }

    companion object {
        fun fromValue(value: String): AggregationType {
            return entries.find { it.value == value } ?: AVERAGE
        }
    }
}

/**
 * Reusable trend chart for metric visualization.
 * Per D-03: Used in both MetricDetailScreen and MetricCard expanded state.
 *
 * @param metric The metric entity with configuration
 * @param logs List of metric logs to display
 * @param modifier Modifier for sizing
 * @param defaultTimeRange Initial time range (default 7 days per D-04)
 * @param lineColor Override color for the chart line (useful for contrast on colored backgrounds)
 * @param onAggregationTypeChange Callback when aggregation type changes (for persistence/sync)
 */
@Composable
fun TrendChart(
    metric: MetricEntity,
    logs: List<MetricLogEntity>,
    modifier: Modifier = Modifier,
    defaultTimeRange: TimeRange = TimeRange.SEVEN_DAYS,
    lineColor: Color? = null,
    onAggregationTypeChange: ((AggregationType) -> Unit)? = null
) {
    var selectedTimeRange by remember { mutableStateOf(defaultTimeRange) }
    var selectedAggregation by remember(metric.aggregationType) {
        mutableStateOf(AggregationType.fromValue(metric.aggregationType))
    }
    var showAggregationMenu by remember { mutableStateOf(false) }

    // Delay chart rendering to improve initial load performance
    var isChartReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Wait for first frame to complete, then render chart
        isChartReady = true
    }

    Column(modifier = modifier) {
        // Time range selector and aggregation dropdown (D-05)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Time range chips
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TimeRange.entries.forEach { range ->
                    FilterChip(
                        selected = selectedTimeRange == range,
                        onClick = { selectedTimeRange = range },
                        label = { Text(range.getLabel()) }
                    )
                }
            }

            // Aggregation type dropdown
            Box {
                TextButton(
                    onClick = { showAggregationMenu = true }
                ) {
                    Text(
                        text = selectedAggregation.getLabel(),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("▼", style = MaterialTheme.typography.bodySmall)
                }

                DropdownMenu(
                    expanded = showAggregationMenu,
                    onDismissRequest = { showAggregationMenu = false }
                ) {
                    AggregationType.entries.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.getLabel()) },
                            onClick = {
                                selectedAggregation = type
                                showAggregationMenu = false
                                onAggregationTypeChange?.invoke(type)
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Show placeholder while chart is loading
        if (!isChartReady) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = lineColor ?: MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp
                )
            }
            return@Column
        }

        // Chart
        val modelProducer = remember { CartesianChartModelProducer() }
        val metricColor = rememberColor(metric.colorHex)
        // Use override color if provided, otherwise use metric color
        val chartColor = lineColor ?: metricColor

        // Filter and aggregate logs
        val chartData by remember(logs, selectedTimeRange, selectedAggregation) {
            derivedStateOf {
                val now = System.currentTimeMillis()
                val startTime = now - (selectedTimeRange.days * 24 * 60 * 60 * 1000L)
                val filteredLogs = logs.filter { it.date >= startTime }

                aggregateLogs(filteredLogs, selectedAggregation.value)
            }
        }

        LaunchedEffect(chartData) {
            modelProducer.runTransaction {
                lineSeries {
                    series(chartData.map { it.x }, chartData.map { it.y })
                }
            }
        }

        if (chartData.isNotEmpty()) {
            val decimalFormat = remember(metric.decimalPlaces) {
                val pattern = if (metric.decimalPlaces > 0) {
                    "#." + "#".repeat(metric.decimalPlaces)
                } else {
                    "#"
                }
                DecimalFormat(pattern)
            }

            val startAxisFormatter = remember(decimalFormat) {
                CartesianValueFormatter { _, value, _ ->
                    decimalFormat.format(value)
                }
            }

            val bottomAxisFormatter = remember(chartData) {
                CartesianValueFormatter { _, value, _ ->
                    val index = value.toInt()
                    if (index >= 0 && index < chartData.size) {
                        val date = Date(chartData[index].timestamp)
                        SimpleDateFormat("MM/dd", Locale.getDefault()).format(date)
                    } else {
                        // Vico 2.0 doesn't allow empty strings - return placeholder
                        "-"
                    }
                }
            }

            // Build target line decorations if metric has target
            // Use bright contrasting color for target lines (same as line color but brighter)
            val targetLineColor = chartColor.copy(alpha = 1f)
            val decorations = buildTargetLineDecorations(metric, targetLineColor)

            // Calculate Y axis range that includes target values
            val dataMinY = chartData.minOf { it.y }
            val dataMaxY = chartData.maxOf { it.y }

            // Include target values in Y range
            val targetValues = mutableListOf<Double>()
            metric.targetValue?.let { targetValues.add(it) }
            metric.targetValueUpper?.let { targetValues.add(it) }

            val effectiveMinY = if (targetValues.isNotEmpty()) minOf(dataMinY, targetValues.min()) else dataMinY
            val effectiveMaxY = if (targetValues.isNotEmpty()) maxOf(dataMaxY, targetValues.max()) else dataMaxY

            // Add 10% padding
            val range = effectiveMaxY - effectiveMinY
            val padding = if (range > 0) range * 0.1 else 1.0
            val axisMinY = effectiveMinY - padding
            val axisMaxY = effectiveMaxY + padding

            // Create range provider to include target values
            val rangeProvider = remember(axisMinY, axisMaxY) {
                CartesianLayerRangeProvider.fixed(
                    minY = axisMinY,
                    maxY = axisMaxY
                )
            }

            val pointComponent = rememberShapeComponent(shape = Shape.Rectangle, fill = fill(chartColor))
            val point = remember(pointComponent) { LineCartesianLayer.Point(pointComponent, 8f) }
            val line = LineCartesianLayer.rememberLine(
                fill = LineCartesianLayer.LineFill.single(fill(chartColor)),
                pointProvider = LineCartesianLayer.PointProvider.single(point)
            )

            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberLineCartesianLayer(
                        lineProvider = LineCartesianLayer.LineProvider.series(line),
                        rangeProvider = rangeProvider
                    ),
                    startAxis = VerticalAxis.rememberStart(valueFormatter = startAxisFormatter),
                    bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = bottomAxisFormatter),
                    decorations = decorations
                ),
                modelProducer = modelProducer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            )
        } else {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.chart_no_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Remember the metric color, with fallback to primary color.
 */
@Composable
private fun rememberColor(colorHex: String): Color {
    return remember {
        try {
            Color(colorHex.toColorInt())
        } catch (e: Exception) {
            Color.Unspecified
        }
    }
}

/**
 * Data point for chart rendering.
 */
private data class ChartPoint(val x: Double, val y: Double, val timestamp: Long)

/**
 * Aggregate logs based on aggregation type (D-06).
 * - "average": Calculate mean of all records per day
 * - "sum": Sum all records per day
 * - "by_time": Show all records at exact timestamps
 */
private fun aggregateLogs(
    logs: List<MetricLogEntity>,
    aggregationType: String
): List<ChartPoint> {
    val aggregated = when (aggregationType) {
        "sum" -> logs.groupBy { getDayKey(it.date) }
            .map { (timestamp, dayLogs) ->
                Pair(timestamp, dayLogs.sumOf { it.value })
            }
        "by_time" -> logs.map { Pair(it.date, it.value) }
        else -> logs.groupBy { getDayKey(it.date) }
            .map { (timestamp, dayLogs) ->
                Pair(timestamp, dayLogs.map { it.value }.average())
            }
    }.sortedBy { it.first }

    return aggregated.mapIndexed { index, pair ->
        ChartPoint(index.toDouble(), pair.second, pair.first)
    }
}

/**
 * Build target line decorations based on metric configuration.
 * Per D-11: Target line color close to metric color
 * Per D-12: Dashed line style
 * Per D-13: Range shows two lines with different shades
 * Per D-14: Single target shows one line
 */
@Composable
private fun buildTargetLineDecorations(
    metric: MetricEntity,
    lineColor: Color
): List<HorizontalLine> {
    val decorations = mutableListOf<HorizontalLine>()

    // Use bright version of the line color for target lines
    val targetColor = lineColor

    // Get localized labels
    val targetLabel = stringResource(R.string.chart_target_line)
    val lowerLabel = stringResource(R.string.chart_range_lower)
    val upperLabel = stringResource(R.string.chart_range_upper)

    metric.targetValue?.let { target ->
        when (metric.targetDirection) {
            "increase", "decrease" -> {
                // D-14: Single target line - more prominent
                decorations.add(
                    HorizontalLine(
                        y = { target },
                        line = rememberLineComponent(
                            fill = Fill(targetColor.toArgb()),
                            thickness = 3.dp,
                            shape = Shape.Rectangle
                        ),
                        labelComponent = rememberTextComponent(color = targetColor),
                        label = { targetLabel }
                    )
                )
            }
            "range" -> {
                // D-13: Range with two lines - more prominent
                // Lower bound line
                decorations.add(
                    HorizontalLine(
                        y = { target },
                        line = rememberLineComponent(
                            fill = Fill(targetColor.toArgb()),
                            thickness = 3.dp,
                            shape = Shape.Rectangle
                        ),
                        labelComponent = rememberTextComponent(color = targetColor),
                        label = { lowerLabel }
                    )
                )

                // Upper bound line
                metric.targetValueUpper?.let { upper ->
                    decorations.add(
                        HorizontalLine(
                            y = { upper },
                            line = rememberLineComponent(
                                fill = Fill(targetColor.toArgb()),
                                thickness = 3.dp,
                                shape = Shape.Rectangle
                            ),
                            labelComponent = rememberTextComponent(color = targetColor),
                            label = { upperLabel }
                        )
                    )
                }
            }
        }
    }

    return decorations
}

/**
 * Get day key for grouping logs by day.
 */
private fun getDayKey(timestamp: Long): Long {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = timestamp
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}
