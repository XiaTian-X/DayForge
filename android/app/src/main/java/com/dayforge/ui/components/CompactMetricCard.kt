package com.dayforge.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dayforge.R
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.domain.model.CardColorStyle
import com.dayforge.domain.service.CardColorResolver
import java.util.Locale

/**
 * Returns the icon for the target direction.
 * Per UI-SPEC: Shows direction arrow in compact card.
 */
@Composable
private fun getDirectionIcon(direction: String?): ImageVector {
    return when (direction) {
        "increase" -> Icons.Default.ArrowUpward
        "decrease" -> Icons.Default.ArrowDownward
        "range" -> Icons.Rounded.Straighten
        else -> Icons.Default.HelpOutline
    }
}

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
 * CompactMetricCard — Grid-adapted metric card for tablet layout.
 *
 * Compact mode: Shows summary only (icon, name, value, trend arrow).
 * Click navigates directly to MetricDetailScreen (no expansion).
 * Used in MetricsScreen Grid layout (Phase 93) when windowSize >= 600dp.
 *
 * Per UI-SPEC Card Presentation Contract:
 * - Icon: 40.dp size, left-aligned
 * - Name: titleMedium, maxLines=1, ellipsis
 * - Value + Unit: titleLarge, right-aligned
 * - Direction arrow: 20.dp icon, right of value
 * - Expand button: Hidden
 * - TrendChart: Hidden (navigate to detail instead)
 * - Last recorded: Hidden
 * - Tap hint: Hidden
 *
 * @param metric The metric entity to display
 * @param latestValue The most recent recorded value, null if never recorded
 * @param onClick Callback when card is clicked (navigate to MetricDetailScreen)
 * @param cardColorStyle Card color style preference
 * @param modifier Optional modifier
 */
@Composable
fun CompactMetricCard(
    metric: MetricEntity,
    latestValue: Double?,
    onClick: () -> Unit,
    cardColorStyle: CardColorStyle = CardColorStyle.DEFAULT,
    modifier: Modifier = Modifier
) {
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
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = resolvedColors.backgroundColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp) // Per UI-SPEC: 16.dp internal card padding
        ) {
            // Icon row
            Icon(
                modifier = Modifier.size(40.dp),
                tint = resolvedColors.iconColor,
                contentDescription = null,
                imageVector = getIconForResId(metric.iconResId)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Name row (single line with ellipsis)
            Text(
                text = metric.name,
                style = MaterialTheme.typography.titleMedium,
                color = resolvedColors.textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Value + Unit + Direction row
            if (latestValue != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${formatMetricValue(latestValue, metric.decimalPlaces)} ${metric.unit}",
                        style = MaterialTheme.typography.titleLarge,
                        color = resolvedColors.textColor,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1
                    )

                    // Direction arrow
                    metric.targetDirection?.let { direction ->
                        Spacer(modifier = Modifier.width(4.dp))
                        val directionLabel = when (direction) {
                            "increase" -> stringResource(R.string.edit_metric_target_increase)
                            "decrease" -> stringResource(R.string.edit_metric_target_decrease)
                            "range" -> stringResource(R.string.edit_metric_target_range)
                            else -> stringResource(R.string.metric_target_label)
                        }
                        Icon(
                            imageVector = getDirectionIcon(direction),
                            contentDescription = stringResource(R.string.metric_card_target_direction, directionLabel),
                            tint = resolvedColors.textColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            } else {
                // No records placeholder
                Text(
                    text = stringResource(R.string.metric_card_no_records),
                    style = MaterialTheme.typography.bodySmall,
                    color = resolvedColors.secondaryTextColor,
                    maxLines = 1
                )
            }
        }
    }
}
