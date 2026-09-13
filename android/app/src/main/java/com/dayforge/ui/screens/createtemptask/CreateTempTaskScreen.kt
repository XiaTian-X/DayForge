package com.dayforge.ui.screens.createtemptask

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dayforge.R
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTempTaskScreen(
    viewModel: CreateTempTaskViewModel = hiltViewModel(),
    parentUuid: String? = null,
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
                title = { Text(stringResource(R.string.create_temp_task_title)) },
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
            // Name field (required)
            OutlinedTextField(
                value = uiState.name,
                onValueChange = viewModel::updateName,
                label = { Text(stringResource(R.string.edit_habit_name_required)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

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

            // Parent habit selector (optional - GOAL type only)
            var parentMenuExpanded by remember { mutableStateOf(false) }
            val goalHabits = uiState.topLevelHabits.filter { it.habitType == HabitType.GOAL }
            val selectedParentName = uiState.topLevelHabits.find { it.uuid == uiState.parentHabitUuid }?.name

            // Lazy load top-level habits when menu expands
            LaunchedEffect(parentMenuExpanded) {
                if (parentMenuExpanded) {
                    viewModel.loadTopLevelHabitsIfNeeded()
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ExposedDropdownMenuBox(
                        expanded = parentMenuExpanded,
                        onExpandedChange = { parentMenuExpanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedParentName ?: stringResource(R.string.edit_habit_parent_none_independent),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.edit_habit_parent_habit_optional)) },
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
                            // "None" option
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.edit_habit_parent_none_independent)) },
                                onClick = {
                                    viewModel.updateParentHabit(null)
                                    parentMenuExpanded = false
                                }
                            )
                            // List of GOAL type habits (only GOAL can be parent)
                            if (goalHabits.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.edit_habit_no_parent_available)) },
                                    onClick = { parentMenuExpanded = false },
                                    enabled = false
                                )
                            } else {
                                goalHabits.forEach { habit ->
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
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Save button
            Button(
                onClick = { viewModel.saveTempTask() },
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.isValid && !uiState.isSaving
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(stringResource(R.string.create_temp_task_save))
                }
            }
        }

        // Navigate back after temp task is saved
        LaunchedEffect(uiState.savedHabitId) {
            if (uiState.savedHabitId != null) {
                onNavigateBack()
                viewModel.clearSavedHabit()
            }
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
    }
}
