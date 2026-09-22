package com.dayforge.ui.screens.edithabit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.hilt.navigation.compose.hiltViewModel
import com.dayforge.R
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.ui.components.ColorPicker
import com.dayforge.ui.components.DeleteConfirmationDialog
import com.dayforge.ui.components.IconPicker
import com.dayforge.ui.components.ParentHabitSelector
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditHabitScreen(
    habitId: Long,
    viewModel: EditHabitViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onHabitDeleted: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    // Load habit on first composition - only if not already loaded
    LaunchedEffect(habitId) {
        if (uiState.habitId != habitId && !uiState.habitNotFound) {
            viewModel.loadHabit(habitId)
        }
    }

    if (uiState.habitNotFound) {
        HabitNotFoundScreen(modifier = Modifier.fillMaxSize())
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_habit_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.content_description_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::toggleDeleteDialog) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Name field
                OutlinedTextField(
                    value = uiState.name,
                    onValueChange = viewModel::updateName,
                    label = { Text(stringResource(R.string.edit_habit_name_required)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = uiState.errorMessage != null
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Description field
                OutlinedTextField(
                    value = uiState.description,
                    onValueChange = viewModel::updateDescription,
                    label = { Text(stringResource(R.string.edit_habit_description_optional)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Parent habit selector (not available for GOAL type - GOAL habits cannot be children)
                if (uiState.habitType != HabitType.GOAL) {
                    // Lazy load top-level habits when selector is expanded
                    var parentSelectorExpanded by remember { mutableStateOf(false) }
                    LaunchedEffect(parentSelectorExpanded) {
                        if (parentSelectorExpanded) {
                            viewModel.loadTopLevelHabitsIfNeeded()
                        }
                    }

                    ParentHabitSelector(
                        parentHabitName = uiState.parentHabitName,
                        topLevelHabits = uiState.topLevelHabits,
                        currentHabitId = uiState.habitId,
                        showSelector = uiState.showParentSelector,
                        onExpand = { parentSelectorExpanded = true },
                        onToggleSelector = viewModel::toggleParentSelector,
                        onSelectParent = viewModel::updateParentHabit,
                        onClearParent = viewModel::clearParentHabit
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Mode selection (only for TIMER and COUNTING types)
                if (uiState.habitType == HabitType.TIMER || uiState.habitType == HabitType.COUNTING) {
                    val modeLabel = if (uiState.habitType == HabitType.TIMER) stringResource(R.string.edit_habit_mode_timer) else stringResource(R.string.edit_habit_mode_counting)
                    Text(modeLabel, style = MaterialTheme.typography.titleMedium)
                    // Use different labels for TIMER vs COUNTING habits
                    val countupLabel = if (uiState.habitType == HabitType.TIMER) {
                        stringResource(R.string.edit_habit_countup)
                    } else {
                        stringResource(R.string.edit_habit_countup_counting)
                    }
                    val countdownLabel = if (uiState.habitType == HabitType.TIMER) {
                        stringResource(R.string.edit_habit_countdown)
                    } else {
                        stringResource(R.string.edit_habit_countdown_counting)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !uiState.isCountdown,
                            onClick = { viewModel.updateIsCountdown(false) },
                            label = { Text(countupLabel) },
                            leadingIcon = if (!uiState.isCountdown) {
                                { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                            } else null
                        )
                        FilterChip(
                            selected = uiState.isCountdown,
                            onClick = { viewModel.updateIsCountdown(true) },
                            label = { Text(countdownLabel) },
                            leadingIcon = if (uiState.isCountdown) {
                                { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                            } else null
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Schedule Type selection (not for GOAL type - always Daily)
                if (uiState.habitType != HabitType.GOAL) {
                    Text(stringResource(R.string.edit_habit_schedule_type), style = MaterialTheme.typography.titleMedium)

                    // Helper function to determine if a schedule option is allowed
                    // Rule: Only allow options with days <= original schedule days (upgrade path)
                    val isScheduleOptionAllowed = { optionDays: Int ->
                        optionDays <= uiState.originalScheduleDays
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = uiState.schedule is HabitSchedule.Daily,
                            onClick = { viewModel.updateSchedule(HabitSchedule.Daily) },
                            label = { Text(stringResource(R.string.frequency_daily)) },
                            enabled = isScheduleOptionAllowed(1),  // Daily = 1 day
                            leadingIcon = if (uiState.schedule is HabitSchedule.Daily) {
                                { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                            } else null
                        )
                        FilterChip(
                            selected = uiState.schedule is HabitSchedule.Weekly,
                            onClick = { viewModel.updateSchedule(HabitSchedule.Weekly(emptyList())) },
                            label = { Text(stringResource(R.string.frequency_weekly)) },
                            enabled = isScheduleOptionAllowed(7),  // Weekly = 7 days
                            leadingIcon = if (uiState.schedule is HabitSchedule.Weekly) {
                                { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                            } else null
                        )
                        FilterChip(
                            selected = uiState.schedule is HabitSchedule.Monthly,
                            onClick = { viewModel.updateSchedule(HabitSchedule.Monthly(1)) },
                            label = { Text(stringResource(R.string.frequency_monthly)) },
                            enabled = isScheduleOptionAllowed(30),  // Monthly = 30 days
                            leadingIcon = if (uiState.schedule is HabitSchedule.Monthly) {
                                { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                            } else null
                        )
                        FilterChip(
                            selected = uiState.schedule is HabitSchedule.Custom,
                            onClick = { viewModel.updateSchedule(HabitSchedule.Custom(1)) },
                            label = { Text(stringResource(R.string.frequency_custom)) },
                            enabled = true,  // Custom is always selectable (per context D-03)
                            leadingIcon = if (uiState.schedule is HabitSchedule.Custom) {
                                { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                            } else null
                        )
                    }

                    // Weekly day display (disabled - cannot edit after creation)
                    if (uiState.schedule is HabitSchedule.Weekly) {
                        val weeklySchedule = uiState.schedule as HabitSchedule.Weekly
                        if (weeklySchedule.daysOfWeek.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.edit_habit_checkin_days),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val dayLabels = listOf(
                                    stringResource(R.string.edit_habit_day_mon),
                                    stringResource(R.string.edit_habit_day_tue),
                                    stringResource(R.string.edit_habit_day_wed),
                                    stringResource(R.string.edit_habit_day_thu),
                                    stringResource(R.string.edit_habit_day_fri),
                                    stringResource(R.string.edit_habit_day_sat),
                                    stringResource(R.string.edit_habit_day_sun)
                                )
                                weeklySchedule.daysOfWeek.forEach { dayValue ->
                                    val label = dayLabels[dayValue - 1]  // 1=Monday -> index 0
                                    FilterChip(
                                        selected = true,
                                        onClick = { },  // No action - disabled
                                        label = { Text(label) },
                                        enabled = false,  // Per CONTEXT.md D-04: disabled display
                                        leadingIcon = {
                                            Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Custom frequency input (only when Custom schedule selected)
                    if (uiState.schedule is HabitSchedule.Custom) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = uiState.customInputValue,
                            onValueChange = { viewModel.updateCustomInput(it) },
                            label = { Text(stringResource(R.string.edit_habit_frequency_days)) },
                            supportingText = { Text(stringResource(R.string.edit_habit_frequency_max, uiState.originalScheduleDays)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Monthly day input (only when Monthly schedule selected)
                    if (uiState.schedule is HabitSchedule.Monthly) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = uiState.monthlyInputValue,
                            onValueChange = { viewModel.updateMonthlyInput(it) },
                            label = { Text(stringResource(R.string.edit_habit_day_of_month)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Target value (only for counting and timer habits)
                if (uiState.habitType == HabitType.COUNTING || uiState.habitType == HabitType.TIMER) {
                    OutlinedTextField(
                        value = uiState.targetValue?.toString() ?: "",
                        onValueChange = {
                            viewModel.updateTargetValue(it.toIntOrNull())
                        },
                        label = { Text(if (uiState.habitType == HabitType.TIMER) stringResource(R.string.edit_habit_daily_target_minutes) else stringResource(R.string.edit_habit_daily_target)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Target cycles (optional for all habit types)
                OutlinedTextField(
                    value = uiState.targetCycles?.toString() ?: "",
                    onValueChange = {
                        val filtered = it.filter { c -> c.isDigit() }
                        viewModel.updateTargetCycles(filtered.toIntOrNull()?.coerceAtLeast(1))
                    },
                    label = { Text(stringResource(R.string.edit_habit_target_optional)) },
                    supportingText = {
                        if (uiState.hasCompletions) {
                            Text(stringResource(R.string.edit_habit_target_no_modify))
                        } else {
                            Text(stringResource(R.string.edit_habit_target_unlimited))
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !uiState.hasCompletions,  // Disabled if habit has completions
                    modifier = Modifier.fillMaxWidth()
                )

                // Failure mode selection (only shown when targetCycles is set)
                if (uiState.targetCycles != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.edit_habit_failure_mode),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = uiState.failMode == FailMode.STRICT,
                            onClick = {
                                if (!uiState.hasCompletions) {
                                    viewModel.updateFailMode(FailMode.STRICT)
                                }
                            },
                            label = { Text(stringResource(R.string.edit_habit_strict_mode)) },
                            modifier = Modifier.weight(1f),
                            enabled = !uiState.hasCompletions
                        )
                        FilterChip(
                            selected = uiState.failMode == FailMode.LOOSE,
                            onClick = {
                                if (!uiState.hasCompletions) {
                                    viewModel.updateFailMode(FailMode.LOOSE)
                                }
                            },
                            label = { Text(stringResource(R.string.edit_habit_relaxed_mode)) },
                            modifier = Modifier.weight(1f),
                            enabled = !uiState.hasCompletions
                        )
                    }
                    Text(
                        text = if (uiState.hasCompletions) {
                            stringResource(R.string.edit_habit_target_no_modify)
                        } else if (uiState.failMode == FailMode.STRICT) {
                            stringResource(R.string.edit_habit_strict_mode_desc)
                        } else {
                            stringResource(R.string.edit_habit_relaxed_mode_desc)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Best time picker button
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleTimePicker() },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                stringResource(R.string.edit_habit_best_time_label),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                stringResource(R.string.edit_habit_best_time_optional),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = uiState.bestTime?.let {
                                    String.format(Locale.getDefault(), "%02d:%02d", it / 60, it % 60)
                                } ?: stringResource(R.string.edit_habit_best_time_none),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (uiState.bestTime != null)
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Icon picker button
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleIconPicker() },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.edit_habit_icon_label), style = MaterialTheme.typography.bodyLarge)
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Color picker button
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleColorPicker() },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.edit_habit_color_label), style = MaterialTheme.typography.bodyLarge)
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(
                                    color = ComposeColor(uiState.colorHex.toColorInt()),
                                    shape = MaterialTheme.shapes.small
                                )
                        )
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Metric linking section (optional) - expandable card
                var metricSectionExpanded by remember { mutableStateOf(false) }

                // Lazy load metrics when section expands
                LaunchedEffect(metricSectionExpanded) {
                    if (metricSectionExpanded) {
                        viewModel.loadMetricsIfNeeded()
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { metricSectionExpanded = !metricSectionExpanded },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = stringResource(R.string.edit_habit_linked_metric_optional),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                val selectedCount = uiState.selectedMetricIds.size
                                if (selectedCount > 0) {
                                    Text(
                                        text = stringResource(R.string.edit_habit_selected_metrics_count, selectedCount),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Icon(
                                if (metricSectionExpanded) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = stringResource(R.string.content_description_expand),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Expanded content with metric list
                        if (metricSectionExpanded) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.edit_habit_linked_metric_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            if (uiState.availableMetrics.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.edit_habit_no_metrics_available),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                uiState.availableMetrics.forEach { metric ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.toggleMetricSelection(metric.id) }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = metric.id in uiState.selectedMetricIds,
                                            onCheckedChange = { viewModel.toggleMetricSelection(metric.id) }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "${metric.name} (${metric.unit})",
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Save button
                Button(
                    onClick = {
                        viewModel.saveChanges { onNavigateBack() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.hasChanges && uiState.isValid && !uiState.isSaving
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(stringResource(R.string.edit_habit_save_changes))
                    }
                }
            }
        }
    }

    // Icon picker dialog
    if (uiState.showIconPicker) {
        IconPicker(
            selectedIconId = uiState.iconResId,
            onIconSelected = viewModel::updateIcon,
            onDismiss = viewModel::toggleIconPicker
        )
    }

    // Color picker dialog
    if (uiState.showColorPicker) {
        ColorPicker(
            selectedColor = uiState.colorHex,
            onColorSelected = viewModel::updateColor,
            onDismiss = viewModel::toggleColorPicker
        )
    }

    // Delete confirmation dialog
    if (uiState.showDeleteDialog) {
        DeleteConfirmationDialog(
            habitName = uiState.name,
            onConfirm = {
                viewModel.deleteHabit(onHabitDeleted)
            },
            onDismiss = viewModel::toggleDeleteDialog
        )
    }

    // Children deletion confirmation dialog
    if (uiState.showDeleteChildrenDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteChildrenDialog() },
            title = { Text(stringResource(R.string.dialog_delete_habit_with_children_title)) },
            text = {
                Text(stringResource(R.string.dialog_delete_habit_with_children_message, uiState.name, uiState.pendingDeleteChildrenCount))
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteHabitWithChildren(onHabitDeleted) }
                ) {
                    Text(stringResource(R.string.dialog_delete_habit_with_children), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.deleteHabitKeepChildren(onHabitDeleted) }) {
                    Text(stringResource(R.string.dialog_delete_habit_keep_children))
                }
            }
        )
    }

    // Duplicate name error dialog
    if (uiState.showDuplicateDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDuplicateDialog() },
            title = { Text(stringResource(R.string.dialog_cannot_save)) },
            text = { Text(stringResource(R.string.dialog_duplicate_habit_name)) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissDuplicateDialog() }) {
                    Text(stringResource(R.string.action_confirm))
                }
            }
        )
    }

    // Active timer blocking dialog
    if (uiState.showActiveTimerDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissActiveTimerDialog() },
            title = { Text(stringResource(R.string.dialog_cannot_change_mode)) },
            text = { Text(stringResource(R.string.dialog_timer_running_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissActiveTimerDialog() }) {
                    Text(stringResource(R.string.action_confirm))
                }
            }
        )
    }

    // Default target value dialog
    if (uiState.showDefaultTargetDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDefaultTargetDialog() },
            title = { Text(stringResource(R.string.dialog_target_empty_title)) },
            text = { Text(stringResource(R.string.dialog_target_empty_message)) },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDefaultTargetDialog() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDefaultTarget { onNavigateBack() } }) {
                    Text(stringResource(R.string.action_confirm))
                }
            }
        )
    }

    // Default schedule value dialog
    if (uiState.showDefaultScheduleDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDefaultScheduleDialog() },
            title = { Text(stringResource(R.string.dialog_value_empty_title)) },
            text = { Text(stringResource(R.string.dialog_value_empty_message)) },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDefaultScheduleDialog() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDefaultSchedule { onNavigateBack() } }) {
                    Text(stringResource(R.string.action_confirm))
                }
            }
        )
    }

    // Time picker dialog
    if (uiState.showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = uiState.bestTime?.let { (it / 60).toInt() } ?: 8,
            initialMinute = uiState.bestTime?.let { (it % 60).toInt() } ?: 0,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { viewModel.toggleTimePicker() },
            title = { Text(stringResource(R.string.edit_habit_best_time_label)) },
            text = {
                Column {
                    TimePicker(state = timePickerState)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.edit_habit_best_time_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.clearBestTime()
                    viewModel.toggleTimePicker()
                }) {
                    Text(stringResource(R.string.edit_habit_best_time_none))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val minutes = timePickerState.hour * 60L + timePickerState.minute
                    viewModel.updateBestTime(minutes)
                    viewModel.toggleTimePicker()
                }) {
                    Text(stringResource(R.string.action_confirm))
                }
            }
        )
    }
}

@Composable
fun HabitNotFoundScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.edit_habit_not_found),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.edit_habit_not_found_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentHabitSelector(
    parentHabitName: String?,
    topLevelHabits: List<com.dayforge.data.local.entity.HabitEntity>,
    currentHabitId: Long,
    showSelector: Boolean,
    onExpand: () -> Unit = {},
    onToggleSelector: () -> Unit,
    onSelectParent: (com.dayforge.data.local.entity.HabitEntity) -> Unit,
    onClearParent: () -> Unit
) {
    var expanded by remember { mutableStateOf(showSelector) }

    // Notify parent when expanded to trigger lazy load
    LaunchedEffect(expanded) {
        if (expanded) {
            onExpand()
        }
    }

    Column {
        Text(stringResource(R.string.edit_habit_parent_habit_label), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        // Current parent display
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = parentHabitName ?: stringResource(R.string.edit_habit_none_top_level),
                    style = MaterialTheme.typography.bodyLarge
                )
                Icon(Icons.Default.ExpandMore, contentDescription = stringResource(R.string.content_description_options))
            }
        }

        // Detach button if has parent
        if (parentHabitName != null) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = onClearParent,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.edit_habit_detach_parent))
            }
        }

        // Dropdown menu
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            // None option
            DropdownMenuItem(
                text = { Text(stringResource(R.string.edit_habit_none_top_level)) },
                onClick = {
                    onClearParent()
                    expanded = false
                }
            )
            HorizontalDivider()
            // Top-level habits (exclude current habit and only show GOAL type - only GOAL can be parent)
            topLevelHabits
                .filter { it.id != currentHabitId && it.habitType == HabitType.GOAL }
                .forEach { habit ->
                    DropdownMenuItem(
                        text = { Text(habit.name) },
                        onClick = {
                            onSelectParent(habit)
                            expanded = false
                        }
                    )
                }
        }
    }
}
