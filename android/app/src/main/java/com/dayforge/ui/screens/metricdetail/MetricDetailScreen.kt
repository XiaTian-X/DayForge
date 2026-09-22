package com.dayforge.ui.screens.metricdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.hilt.navigation.compose.hiltViewModel
import com.dayforge.R
import com.dayforge.ui.components.MetricValueInput
import com.dayforge.ui.components.TrendChart
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * MetricDetailScreen displays metric information, history, linked habits,
 * and allows recording values, editing, and deletion.
 *
 * Screen layout:
 * - TopAppBar with metric name, back button, and edit button
 * - Metric info card showing unit, decimal places, target info
 * - History section showing recorded values with dates and notes
 * - Linked habits section with unlink buttons
 * - Record Value button
 * - Delete Metric button
 *
 * Per D-15: Metric detail page allows viewing metric info, history, and managing links
 * Per D-16: Delete with confirmation, cascade to logs and links
 *
 * @param metricId The ID of the metric to display (extracted from SavedStateHandle in ViewModel)
 * @param onNavigateBack Callback when user taps back button
 * @param onEditClick Callback when user taps edit button
 * @param onDeleted Callback when metric is deleted
 * @param viewModel MetricDetailViewModel injected via Hilt
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricDetailScreen(
    metricId: Long,
    onNavigateBack: () -> Unit,
    onEditClick: (Long) -> Unit = {},
    onDeleted: () -> Unit = {},
    viewModel: MetricDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Navigate back after delete
    LaunchedEffect(uiState.isDeleted) {
        if (uiState.isDeleted) {
            onDeleted()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.metric?.name ?: stringResource(R.string.metric_detail_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.content_description_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onEditClick(metricId) }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.content_description_edit)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        if (uiState.isLoading) {
            // Loading state
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                color = MaterialTheme.colorScheme.background
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        } else if (uiState.metric == null) {
            // Error state
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = uiState.errorMessage ?: stringResource(R.string.metric_not_found),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            // Content
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState()),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    uiState.metric?.let { metric ->

                    // Metric Header Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            // Metric name with color indicator
                            val metricColor = try {
                                Color(metric.colorHex.toColorInt())
                            } catch (e: Exception) {
                                MaterialTheme.colorScheme.primary
                            }

                            Text(
                                text = metric.name,
                                style = MaterialTheme.typography.headlineMedium,
                                color = metricColor
                            )

                            // Latest value display
                            uiState.latestValue?.let { value ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "${formatMetricValue(value, metric.decimalPlaces)} ${metric.unit}",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } ?: run {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.metric_no_records_yet),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Metric Info Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.metric_details_section),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Unit
                            MetricInfoRow(
                                label = stringResource(R.string.metric_unit_label),
                                value = metric.unit
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Decimal places
                            MetricInfoRow(
                                label = stringResource(R.string.metric_decimal_places_label),
                                value = metric.decimalPlaces.toString()
                            )

                            // Target info
                            metric.targetValue?.let { target ->
                                Spacer(modifier = Modifier.height(8.dp))
                                val directionLabel = when (metric.targetDirection) {
                                    "increase" -> stringResource(R.string.metric_target_increase)
                                    "decrease" -> stringResource(R.string.metric_target_decrease)
                                    "range" -> stringResource(R.string.metric_target_range)
                                    else -> stringResource(R.string.metric_target_label)
                                }

                                val targetDisplay = if (metric.targetDirection == "range" && metric.targetValueUpper != null) {
                                    "${formatMetricValue(target, metric.decimalPlaces)} - ${formatMetricValue(metric.targetValueUpper, metric.decimalPlaces)} ${metric.unit}"
                                } else {
                                    "${formatMetricValue(target, metric.decimalPlaces)} ${metric.unit}"
                                }

                                MetricInfoRow(
                                    label = directionLabel,
                                    value = targetDisplay
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Record Value Button
                    Button(
                        onClick = { viewModel.toggleValueInput() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.metric_record_value))
                    }

                    // Trend Chart Section (D-01)
                    Spacer(modifier = Modifier.height(24.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.metric_trend_section),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TrendChart(
                                metric = metric,
                                logs = uiState.logs,
                                modifier = Modifier.fillMaxWidth(),
                                onAggregationTypeChange = { aggregationType ->
                                    viewModel.updateAggregationType(aggregationType.value)
                                }
                            )
                        }
                    }

                    // History Section
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = stringResource(R.string.metric_history_section),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (uiState.logs.isEmpty()) {
                        Text(
                            text = stringResource(R.string.metric_no_records_yet),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        uiState.logs.take(10).forEach { log ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "${formatMetricValue(log.value, metric.decimalPlaces)} ${log.unit}",
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        if (log.note.isNotBlank()) {
                                            Text(
                                                text = log.note,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Text(
                                        text = formatDateTime(log.date),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (uiState.logs.size > 10) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.metric_more_records, uiState.logs.size - 10),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Linked Habits Section
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = stringResource(R.string.metric_linked_habits_section),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (uiState.links.isEmpty()) {
                        Text(
                            text = stringResource(R.string.metric_no_linked_habits),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        uiState.links.forEach { linkWithHabit ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = linkWithHabit.habitName,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    IconButton(onClick = { viewModel.showUnlinkConfirm(linkWithHabit.link.id) }) {
                                        Icon(
                                            imageVector = Icons.Default.LinkOff,
                                            contentDescription = stringResource(R.string.metric_unlink),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Link Habit Button
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.showLinkHabitDialog() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.metric_link_habit))
                    }

                    // Delete Section
                    Spacer(modifier = Modifier.height(24.dp))
                    OutlinedButton(
                        onClick = { viewModel.toggleDeleteConfirm() },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.metric_delete_metric))
                    }

                    // Error message display
                    uiState.errorMessage?.let { error ->
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    } // closes metric?.let block
                }
            }
        }

        // Value Input Dialog
        if (uiState.showValueInput) {
            uiState.metric?.let { metric ->
                MetricValueInput(
                    metricName = metric.name,
                    unit = metric.unit,
                    decimalPlaces = metric.decimalPlaces,
                    currentValue = uiState.latestValue,
                    targetValue = metric.targetValue,
                    targetDirection = metric.targetDirection,
                    onConfirm = { value, note ->
                        viewModel.recordValue(value, note)
                    },
                    onDismiss = { viewModel.toggleValueInput() }
                )
            }
        }

        // Delete Confirmation Dialog
        if (uiState.showDeleteConfirm) {
            uiState.metric?.let { metric ->
                AlertDialog(
                    onDismissRequest = { viewModel.toggleDeleteConfirm() },
                    title = { Text(text = stringResource(R.string.metric_delete_confirm_title)) },
                    text = {
                        Text(
                            text = stringResource(R.string.metric_delete_confirm_message, metric.name)
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = { viewModel.deleteMetric() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(text = stringResource(R.string.action_delete))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.toggleDeleteConfirm() }) {
                            Text(text = stringResource(R.string.action_cancel))
                        }
                    }
                )
            }
        }

        // Unlink Confirmation Dialog
        uiState.showUnlinkConfirm?.let { linkId ->
            val linkWithHabit = uiState.links.find { it.link.id == linkId }
            if (linkWithHabit != null) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissUnlinkConfirm() },
                    title = { Text(text = stringResource(R.string.metric_unlink_confirm_title)) },
                    text = {
                        Text(
                            text = stringResource(
                                R.string.metric_unlink_confirm_message,
                                uiState.metric?.name ?: stringResource(R.string.metric_not_found),
                                linkWithHabit.habitName
                            )
                        )
                    },
                    confirmButton = {
                        Button(onClick = { viewModel.unlinkHabit() }) {
                            Text(text = stringResource(R.string.metric_unlink))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissUnlinkConfirm() }) {
                            Text(text = stringResource(R.string.action_cancel))
                        }
                    }
                )
            }
        }

        // Link Habit Dialog
        if (uiState.showLinkHabit) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissLinkHabitDialog() },
                title = { Text(text = stringResource(R.string.metric_link_habits_title)) },
                text = {
                    Column {
                        if (uiState.availableHabits.isEmpty()) {
                            Text(
                                text = stringResource(R.string.metric_no_habits_to_link),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.metric_select_habits_to_link),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            LazyColumn(
                                modifier = Modifier.heightIn(max = 300.dp)
                            ) {
                                items(uiState.availableHabits.size) { index ->
                                    val habit = uiState.availableHabits[index]
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !habit.isAlreadyLinked) {
                                                viewModel.toggleHabitSelection(habit.id)
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = habit.id in uiState.selectedHabitIds || habit.isAlreadyLinked,
                                            onCheckedChange = if (!habit.isAlreadyLinked) {
                                                { viewModel.toggleHabitSelection(habit.id) }
                                            } else null,
                                            enabled = !habit.isAlreadyLinked
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = habit.name,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = if (habit.isAlreadyLinked) {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                }
                                            )
                                            if (habit.isAlreadyLinked) {
                                                Text(
                                                    text = stringResource(R.string.metric_already_linked),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.linkSelectedHabits() },
                        enabled = uiState.selectedHabitIds.isNotEmpty()
                    ) {
                        Text(text = stringResource(R.string.metric_link_habit))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissLinkHabitDialog() }) {
                        Text(text = stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }
}

/**
 * Row component for displaying metric info label-value pairs.
 */
@Composable
private fun MetricInfoRow(
    label: String,
    value: String
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
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
 * Formats a timestamp to a human-readable date/time string.
 * Format: "Mar 23, 2026 10:30 AM"
 * Uses device's local timezone explicitly for consistent display.
 */
private fun formatDateTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault())
    sdf.timeZone = TimeZone.getDefault() // Explicitly use device timezone
    return sdf.format(Date(timestamp))
}
