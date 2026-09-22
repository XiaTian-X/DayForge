package com.dayforge.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.graphics.toColorInt
import androidx.compose.ui.unit.dp
import com.dayforge.R
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.domain.model.CardColorStyle
import com.dayforge.domain.service.CardColorResolver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Returns the icon for the given icon resource ID.
 * Uses shared getIconForResId function for consistent mapping.
 */
@Composable
private fun getIconForId(iconResId: Int): ImageVector {
    return getIconForResId(iconResId)
}

/**
 * Returns the icon for the target direction.
 * Per D-03: Shows direction arrow in value row.
 */
@Composable
private fun getDirectionIcon(direction: String?): ImageVector {
    return when (direction) {
        "increase" -> Icons.Default.ArrowUpward
        "decrease" -> Icons.Default.ArrowDownward
        "range" -> Icons.Rounded.Straighten
        else -> Icons.AutoMirrored.Filled.HelpOutline
    }
}

/**
 * Formats a metric value according to its decimal places setting.
 * Copied from MetricDetailScreen for consistency.
 */
private fun formatMetricValue(value: Double, decimalPlaces: Int): String {
    return if (decimalPlaces == 0) {
        value.toLong().toString()
    } else {
        String.format(Locale.getDefault(), "%.${decimalPlaces}f", value)
    }
}

/**
 * Formats a timestamp to a human-readable date string.
 * Used for "Last recorded" display in expanded mode.
 */
private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

/**
 * Calculate a contrasting color for the chart line.
 * Returns white for dark backgrounds, dark gray for light backgrounds.
 */
private fun getContrastingColor(colorHex: String): Color {
    return try {
        val color = colorHex.toColorInt()
        // Calculate luminance
        val r = android.graphics.Color.red(color) / 255.0
        val g = android.graphics.Color.green(color) / 255.0
        val b = android.graphics.Color.blue(color) / 255.0
        val luminance = 0.299 * r + 0.587 * g + 0.114 * b

        // Return surface color for contrast (adapts to theme)
        // For light backgrounds, use onSurface; for dark, use surface
        if (luminance > 0.5) {
            Color(0xFF1A1A1A) // Dark for light backgrounds (user's card color)
        } else {
            Color.White // White for dark backgrounds
        }
    } catch (e: Exception) {
        Color.White // Default to white
    }
}

/**
 * MetricCard displays a metric with its current value and target direction.
 *
 * Per METRIC-07: Users can see metrics in a separate section on main screen.
 * Per D-01 to D-04: Simple mode (collapsed) and expanded mode with last recorded date.
 * Per D-03: Shows icon + name + value + unit + direction arrow in simple mode.
 * Per D-11: Whole card is clickable to navigate to detail.
 *
 * @param metric The metric entity to display
 * @param latestValue The most recent recorded value, null if never recorded
 * @param latestLogDate The date of the most recent log, null if never recorded
 * @param logs List of metric logs for the expanded trend chart
 * @param onClick Callback when the card is clicked (navigate to detail)
 * @param modifier Optional modifier
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricCard(
    metric: MetricEntity,
    latestValue: Double?,
    latestLogDate: Long?,
    logs: List<MetricLogEntity> = emptyList(),
    onClick: () -> Unit,
    onAggregationTypeChange: ((String) -> Unit)? = null,
    cardColorStyle: CardColorStyle = CardColorStyle.DEFAULT,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }

    // Resolve card colors based on style preference
    val colorScheme = MaterialTheme.colorScheme
    val resolvedColors = CardColorResolver.resolveCardColors(
        style = cardColorStyle,
        userColorHex = metric.colorHex,
        primaryContainer = colorScheme.primaryContainer.toArgb(),
        onPrimaryContainer = colorScheme.onPrimaryContainer.toArgb(),
        onPrimary = colorScheme.onPrimary.toArgb()
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = resolvedColors.backgroundColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Main row: Icon + Name + Value + Unit + Direction Arrow
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon
                Icon(
                    modifier = Modifier.size(40.dp),
                    tint = resolvedColors.iconColor,
                    contentDescription = null,
                    imageVector = getIconForId(metric.iconResId)
                )

                Spacer(modifier = Modifier.width(16.dp))

                // Name and Last Recorded Date
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = metric.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = resolvedColors.textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    if (latestLogDate != null) {
                        Text(
                            text = stringResource(R.string.metric_card_last_recorded, formatDate(latestLogDate)),
                            style = MaterialTheme.typography.labelSmall,
                            color = resolvedColors.secondaryTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.metric_card_never_recorded),
                            style = MaterialTheme.typography.labelSmall,
                            color = resolvedColors.secondaryTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Value + Unit
                if (latestValue != null) {
                    Text(
                        text = "${formatMetricValue(latestValue, metric.decimalPlaces)} ${metric.unit}",
                        style = MaterialTheme.typography.titleLarge,
                        color = resolvedColors.textColor
                    )
                } else {
                    Text(
                        text = "--",
                        style = MaterialTheme.typography.titleLarge,
                        color = resolvedColors.secondaryTextColor
                    )
                }

                // Expand/collapse button
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = { isExpanded = !isExpanded },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) stringResource(R.string.content_description_collapse) else stringResource(R.string.content_description_expand),
                        tint = resolvedColors.secondaryTextColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessLow)),
                exit = fadeOut(animationSpec = spring(stiffness = Spring.StiffnessLow))
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))

                    // Trend Chart (D-02) - use contrasting color for visibility on colored background
                    TrendChart(
                        metric = metric,
                        logs = logs,
                        modifier = Modifier.fillMaxWidth(),
                        lineColor = getContrastingColor(metric.colorHex),
                        onAggregationTypeChange = onAggregationTypeChange?.let { callback ->
                            { type -> callback(type.value) }
                        }
                    )

                    
                }
            }
        }
    }
}
