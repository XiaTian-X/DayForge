package com.dayforge.ui.screens.creategoal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.hilt.navigation.compose.hiltViewModel
import com.dayforge.R
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitType
import com.dayforge.ui.components.ColorPicker
import com.dayforge.ui.components.IconPicker

/**
 * Screen for creating a goal (parent habit) with key results (child habits).
 * The parent UUID is pre-generated so child habits can be created first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGoalScreen(
    viewModel: CreateGoalViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onCreateChildHabit: (parentUuid: String) -> Unit  // Navigate to CreateHabitScreen with parentUuid
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.create_goal_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Goal (parent habit) section
            item {
                Text(
                    text = stringResource(R.string.create_goal_section_info),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Name field
            item {
                OutlinedTextField(
                    value = uiState.name,
                    onValueChange = viewModel::updateName,
                    label = { Text(stringResource(R.string.edit_habit_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = uiState.errorMessage != null
                )
            }

            // Description field
            item {
                OutlinedTextField(
                    value = uiState.description,
                    onValueChange = viewModel::updateDescription,
                    label = { Text(stringResource(R.string.edit_habit_description_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            }

            // Icon picker button
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleIconPicker() },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.edit_habit_icon_label), style = MaterialTheme.typography.bodyLarge)
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                    }
                }
            }

            // Color picker button
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleColorPicker() },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.edit_habit_color_label), style = MaterialTheme.typography.bodyLarge)
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(
                                    color = Color(uiState.colorHex.toColorInt()),
                                    shape = MaterialTheme.shapes.small
                                )
                        )
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                    }
                }
            }

            // Target cycles (optional)
            item {
                OutlinedTextField(
                    value = uiState.targetCycles?.toString() ?: "",
                    onValueChange = { value ->
                        viewModel.updateTargetCycles(value.toIntOrNull())
                    },
                    label = { Text(stringResource(R.string.create_goal_target_cycles_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text(stringResource(R.string.create_goal_target_cycles_hint)) }
                )
            }

            // Fail mode selection
            item {
                Text(stringResource(R.string.edit_habit_failure_mode), style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = uiState.failMode == FailMode.STRICT,
                        onClick = { viewModel.updateFailMode(FailMode.STRICT) },
                        label = { Text(stringResource(R.string.edit_habit_strict_mode)) },
                        leadingIcon = if (uiState.failMode == FailMode.STRICT) {
                            { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                        } else null
                    )
                    FilterChip(
                        selected = uiState.failMode == FailMode.LOOSE,
                        onClick = { viewModel.updateFailMode(FailMode.LOOSE) },
                        label = { Text(stringResource(R.string.edit_habit_relaxed_mode)) },
                        leadingIcon = if (uiState.failMode == FailMode.LOOSE) {
                            { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                        } else null
                    )
                }
            }

            // Divider
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            // Key results (child habits) section
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.create_goal_key_results_section),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.create_goal_key_results_count, uiState.children.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Add key result button
            item {
                Button(
                    onClick = { onCreateChildHabit(uiState.parentUuid) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.edit_habit_add_key_result))
                }
            }

            // Child habits list
            if (uiState.children.isNotEmpty()) {
                items(uiState.children, key = { it.id }) { child ->
                    ChildHabitDraftCard(
                        draft = child,
                        onDelete = { viewModel.removeChildHabit(child.id) }
                    )
                }
            } else {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.create_goal_no_key_results),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.create_goal_add_key_results_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Save button
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.saveGoal() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.isValid && !uiState.isSaving
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(stringResource(R.string.create_goal_btn_create))
                    }
                }
            }
        }
    }

    // Navigate back after goal is saved
    LaunchedEffect(uiState.savedGoalId) {
        if (uiState.savedGoalId != null) {
            onNavigateBack()
            viewModel.clearSavedGoal()
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

    // Error dialog
    uiState.errorMessage?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text(stringResource(R.string.create_goal_error_title)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) {
                    Text(stringResource(R.string.action_confirm))
                }
            }
        )
    }
}

/**
 * Card displaying a draft child habit (key result).
 */
@Composable
private fun ChildHabitDraftCard(
    draft: com.dayforge.data.model.HabitDraft,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = draft.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                when (draft.habitType) {
                                    HabitType.CHECK_IN -> stringResource(R.string.edit_habit_type_checkin)
                                    HabitType.COUNTING -> stringResource(R.string.edit_habit_type_counting)
                                    HabitType.TIMER -> stringResource(R.string.edit_habit_type_timer)
                                    HabitType.GOAL -> stringResource(R.string.goal_type_label)
                                },
                                fontSize = 12.sp
                            )
                        },
                        modifier = Modifier.height(24.dp)
                    )
                    if (draft.habitType != HabitType.CHECK_IN) {
                        AssistChip(
                            onClick = {},
                            label = { Text(stringResource(R.string.edit_goal_target_format, draft.targetValue), fontSize = 12.sp) },
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
