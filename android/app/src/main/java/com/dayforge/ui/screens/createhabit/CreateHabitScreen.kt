package com.dayforge.ui.screens.createhabit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dayforge.R
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.ui.components.ColorPicker
import com.dayforge.ui.components.IconPicker
import com.dayforge.ui.components.PresetHabitSelectorExpanded
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateHabitScreen(
    viewModel: CreateHabitViewModel = hiltViewModel(),
    parentUuid: String? = null,
    onSaveDraft: ((com.dayforge.data.model.HabitDraft) -> Unit)? = null,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    // Set parent UUID if provided from navigation
    LaunchedEffect(parentUuid) {
        if (parentUuid != null) {
            viewModel.setParentUuid(parentUuid)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (parentUuid != null) stringResource(R.string.edit_habit_add_key_result) else stringResource(R.string.create_habit_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
                    }
                }
            )
        }
    ) { padding ->
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

            // Preset selector card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.togglePresetDialog() },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.create_habit_preset_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Icon(
                            Icons.Outlined.ExpandMore,
                            contentDescription = stringResource(R.string.content_description_expand),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    val presetName = uiState.selectedPresetName
                    if (presetName != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.create_habit_selected_preset, presetName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

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

            // Parent habit selector (only GOAL type habits can be parent)
            var parentMenuExpanded by remember { mutableStateOf(false) }
            val selectedParentName = uiState.topLevelHabits.find { it.uuid == uiState.parentHabitUuid }?.name

            // Lazy load top-level habits when menu expands
            LaunchedEffect(parentMenuExpanded) {
                if (parentMenuExpanded) {
                    viewModel.loadTopLevelHabitsIfNeeded()
                }
            }

            ExposedDropdownMenuBox(
                expanded = parentMenuExpanded,
                onExpandedChange = { parentMenuExpanded = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = selectedParentName ?: stringResource(R.string.edit_habit_none_top_level),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.edit_habit_parent_habit_label)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = parentMenuExpanded)
                    },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = parentMenuExpanded,
                    onDismissRequest = { parentMenuExpanded = false }
                ) {
                    // "None" option for top-level habit
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_habit_none_top_level)) },
                        onClick = {
                            viewModel.updateParentHabit(null)
                            parentMenuExpanded = false
                        }
                    )
                    // List of GOAL type habits (only GOAL can be parent)
                    uiState.topLevelHabits
                        .filter { it.habitType == HabitType.GOAL }
                        .forEach { habit ->
                            DropdownMenuItem(
                                text = { Text(habit.name) },
                                onClick = {
                                    viewModel.updateParentHabit(habit)
                                    parentMenuExpanded = false
                                }
                            )
                        }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Habit Type selection
            Text(stringResource(R.string.create_habit_habit_type), style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = uiState.habitType == HabitType.CHECK_IN,
                    onClick = { viewModel.updateHabitType(HabitType.CHECK_IN) },
                    label = { Text(stringResource(R.string.edit_habit_type_checkin)) },
                    leadingIcon = if (uiState.habitType == HabitType.CHECK_IN) {
                        { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                    } else null
                )
                FilterChip(
                    selected = uiState.habitType == HabitType.COUNTING,
                    onClick = { viewModel.updateHabitType(HabitType.COUNTING) },
                    label = { Text(stringResource(R.string.edit_habit_type_counting)) },
                    leadingIcon = if (uiState.habitType == HabitType.COUNTING) {
                        { Icon(Icons.AutoMirrored.Outlined.TrendingUp, null, Modifier.size(18.dp)) }
                    } else null
                )
                FilterChip(
                    selected = uiState.habitType == HabitType.TIMER,
                    onClick = { viewModel.updateHabitType(HabitType.TIMER) },
                    label = { Text(stringResource(R.string.edit_habit_type_timer)) },
                    leadingIcon = if (uiState.habitType == HabitType.TIMER) {
                        { Icon(Icons.Outlined.Timer, null, Modifier.size(18.dp)) }
                    } else null
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Mode selection (only for TIMER and COUNTING types)
            if (uiState.habitType == HabitType.TIMER || uiState.habitType == HabitType.COUNTING) {
                Text(
                    text = if (uiState.habitType == HabitType.TIMER) stringResource(R.string.edit_habit_mode_timer) else stringResource(R.string.edit_habit_mode_counting),
                    style = MaterialTheme.typography.titleMedium
                )
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

            // Schedule Type selection
            Text(stringResource(R.string.edit_habit_schedule_type), style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = uiState.schedule is HabitSchedule.Daily,
                    onClick = { viewModel.updateSchedule(HabitSchedule.Daily) },
                    label = { Text(stringResource(R.string.frequency_daily)) },
                    leadingIcon = if (uiState.schedule is HabitSchedule.Daily) {
                        { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                    } else null
                )
                FilterChip(
                    selected = uiState.schedule is HabitSchedule.Weekly,
                    onClick = { viewModel.updateSchedule(HabitSchedule.Weekly(emptyList())) },
                    label = { Text(stringResource(R.string.frequency_weekly)) },
                    leadingIcon = if (uiState.schedule is HabitSchedule.Weekly) {
                        { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                    } else null
                )
                FilterChip(
                    selected = uiState.schedule is HabitSchedule.Monthly,
                    onClick = { viewModel.updateSchedule(HabitSchedule.Monthly(1)) },
                    label = { Text(stringResource(R.string.frequency_monthly)) },
                    leadingIcon = if (uiState.schedule is HabitSchedule.Monthly) {
                        { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                    } else null
                )
                FilterChip(
                    selected = uiState.schedule is HabitSchedule.Custom,
                    onClick = { viewModel.updateSchedule(HabitSchedule.Custom(1)) },
                    label = { Text(stringResource(R.string.frequency_custom)) },
                    leadingIcon = if (uiState.schedule is HabitSchedule.Custom) {
                        { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                    } else null
                )
            }

            // Weekly day selection (only when Weekly schedule selected)
            if (uiState.schedule is HabitSchedule.Weekly) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.edit_habit_select_days_optional),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val dayLabelResources = listOf(
                        R.string.edit_habit_day_mon,
                        R.string.edit_habit_day_tue,
                        R.string.edit_habit_day_wed,
                        R.string.edit_habit_day_thu,
                        R.string.edit_habit_day_fri,
                        R.string.edit_habit_day_sat,
                        R.string.edit_habit_day_sun
                    )
                    val currentDaysOfWeek = (uiState.schedule as HabitSchedule.Weekly).daysOfWeek

                    // First row: Mon to Thu
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        dayLabelResources.take(4).forEachIndexed { index, resId ->
                            val dayValue = index + 1  // 1=Monday, 4=Thursday
                            FilterChip(
                                selected = dayValue in currentDaysOfWeek,
                                onClick = { viewModel.toggleDayOfWeek(dayValue) },
                                label = { Text(stringResource(resId)) },
                                leadingIcon = if (dayValue in currentDaysOfWeek) {
                                    { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                                } else null
                            )
                        }
                    }

                    // Second row: Fri to Sun
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        dayLabelResources.drop(4).forEachIndexed { index, resId ->
                            val dayValue = index + 5  // 5=Friday, 7=Sunday
                            FilterChip(
                                selected = dayValue in currentDaysOfWeek,
                                onClick = { viewModel.toggleDayOfWeek(dayValue) },
                                label = { Text(stringResource(resId)) },
                                leadingIcon = if (dayValue in currentDaysOfWeek) {
                                    { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                                } else null
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.edit_habit_select_days_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Custom frequency input (only when Custom schedule selected)
            if (uiState.schedule is HabitSchedule.Custom) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.customInputValue,
                    onValueChange = { viewModel.updateCustomInput(it) },
                    label = { Text(stringResource(R.string.edit_habit_frequency_days)) },
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
                supportingText = { Text(stringResource(R.string.edit_habit_target_unlimited)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
                        onClick = { viewModel.updateFailMode(FailMode.STRICT) },
                        label = { Text(stringResource(R.string.edit_habit_strict_mode)) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = uiState.failMode == FailMode.LOOSE,
                        onClick = { viewModel.updateFailMode(FailMode.LOOSE) },
                        label = { Text(stringResource(R.string.edit_habit_relaxed_mode)) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = if (uiState.failMode == FailMode.STRICT) {
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
                                color = Color(android.graphics.Color.parseColor(uiState.colorHex)),
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
                            if (metricSectionExpanded) Icons.Outlined.ExpandMore else Icons.AutoMirrored.Filled.ArrowForward,
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

            Spacer(modifier = Modifier.height(16.dp))

            // Save button
            Button(
                onClick = {
                    viewModel.saveHabit(onSaveDraft = onSaveDraft)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.isValid && !uiState.isSaving
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(if (parentUuid != null) stringResource(R.string.edit_habit_add_key_result) else stringResource(R.string.create_habit_title))
                }
            }
        }

        // Navigate back after habit is saved
        LaunchedEffect(uiState.savedHabitId, uiState.savedDraft) {
            if (uiState.savedHabitId != null || uiState.savedDraft) {
                onNavigateBack()
                viewModel.clearSavedHabit()
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

        // Preset dialog
        if (uiState.showPresetDialog) {
            PresetHabitSelectorExpanded(
                onPresetSelected = viewModel::selectPreset,
                onDismiss = viewModel::togglePresetDialog
            )
        }

        // Duplicate name error dialog
        if (uiState.showDuplicateDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissDuplicateDialog() },
                title = { Text(stringResource(R.string.dialog_cannot_create)) },
                text = { Text(stringResource(R.string.dialog_duplicate_habit_name)) },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissDuplicateDialog() }) {
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
                    TextButton(onClick = { viewModel.confirmDefaultTarget(onSaveDraft) }) {
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
                    TextButton(onClick = { viewModel.confirmDefaultSchedule(onSaveDraft) }) {
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
}
