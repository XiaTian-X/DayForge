package com.dayforge.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dayforge.R
import java.util.Locale

/**
 * Represents a linked metric with its current value for display in habit cards.
 *
 * Per METRIC-08: Users can see linked metrics under habit cards.
 * Per D-12 to D-14: Shows metric name and current value in expandable section.
 *
 * @param metricName The display name of the metric
 * @param metricId The ID of the metric (for navigation)
 * @param latestValue The most recent recorded value, null if never recorded
 * @param unit The unit of measurement
 * @param decimalPlaces Number of decimal places for formatting
 */
data class LinkedMetricInfo(
    val metricName: String,
    val metricId: Long,
    val latestValue: Double?,
    val unit: String,
    val decimalPlaces: Int
)

/**
 * Formats a metric value according to its decimal places setting.
 */
private fun formatMetricValue(value: Double, decimalPlaces: Int): String {
    return if (decimalPlaces == 0) {
        value.toLong().toString()
    } else {
        String.format(Locale.getDefault(), "%.${decimalPlaces}f", value)
    }
}

/**
 * Expandable section for displaying linked metrics under a habit card.
 *
 * Per METRIC-08: Users can see linked metrics under habit cards.
 * Per D-12: Section shows "关联指标" label with expand/collapse icon.
 * Per D-13: Section is collapsed by default.
 * Per D-14: Expanded content shows metric name and current value.
 *
 * @param linkedMetrics List of linked metrics to display
 * @param onMetricClick Callback when a metric is clicked (navigate to detail)
 * @param textColor Optional text color override
 * @param textSize Optional text size override
 * @param modifier Optional modifier
 */
@Composable
fun LinkedMetricsSection(
    linkedMetrics: List<LinkedMetricInfo>,
    onMetricClick: (Long) -> Unit,
    textColor: Color? = null,
    textSize: androidx.compose.ui.unit.TextUnit? = null,
    modifier: Modifier = Modifier
) {
    val resolvedColor = textColor ?: MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
    val resolvedStyle = if (textSize != null) {
        MaterialTheme.typography.labelSmall.copy(fontSize = textSize)
    } else {
        MaterialTheme.typography.labelSmall
    }

    var isExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        // Header row with label and expand/collapse icon
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.linked_metrics_title),
                style = resolvedStyle,
                color = resolvedColor
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = resolvedColor,
                modifier = Modifier.size(16.dp)
            )
        }

        // Expanded content with metric items
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column {
                linkedMetrics.forEach { info ->
                    LinkedMetricItem(
                        info = info,
                        onClick = { onMetricClick(info.metricId) },
                        textColor = textColor,
                        textSize = textSize
                    )
                }
            }
        }
    }
}

/**
 * Single linked metric item in the expanded section.
 *
 * @param info The linked metric info to display
 * @param onClick Callback when clicked (navigate to metric detail)
 * @param textColor Optional text color override
 * @param textSize Optional text size override
 */
@Composable
private fun LinkedMetricItem(
    info: LinkedMetricInfo,
    onClick: () -> Unit,
    textColor: Color? = null,
    textSize: androidx.compose.ui.unit.TextUnit? = null
) {
    val resolvedColor = textColor ?: MaterialTheme.colorScheme.onPrimary
    val resolvedStyle = if (textSize != null) {
        MaterialTheme.typography.bodyMedium.copy(fontSize = textSize)
    } else {
        MaterialTheme.typography.bodyMedium
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Metric name
        Text(
            text = info.metricName,
            style = resolvedStyle,
            color = resolvedColor,
            modifier = Modifier.weight(1f)
        )

        // Value + unit
        if (info.latestValue != null) {
            Text(
                text = "${formatMetricValue(info.latestValue, info.decimalPlaces)} ${info.unit}",
                style = resolvedStyle,
                color = resolvedColor.copy(alpha = 0.8f)
            )
        } else {
            Text(
                text = "-- ${info.unit}",
                style = resolvedStyle,
                color = resolvedColor.copy(alpha = 0.5f)
            )
        }
    }
}
