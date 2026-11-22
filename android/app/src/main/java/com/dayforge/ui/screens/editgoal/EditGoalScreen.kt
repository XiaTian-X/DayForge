package com.dayforge.ui.screens.editgoal

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.dayforge.data.local.DatabaseObjectSelection
import com.dayforge.ui.navigateRenderedDatabaseInput
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
import com.dayforge.ui.components.ObjectAppearancePicker
import com.dayforge.ui.components.ObjectIcon

/**
 * Screen for editing a goal (parent habit) and managing its key results (child habits).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditGoalScreen(
    goalId: Long,
    viewModel: EditGoalViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onCreateChildHabit: (parentUuid: String) -> Unit,
    onEditChildHabit: (habitId: Long) -> Unit,
    sourceSelection: DatabaseObjectSelection? = null,
    onEditChildSelection: ((DatabaseObjectSelection) -> Unit)? = null,
    onCreateChildSelection: ((DatabaseObjectSelection) -> Unit)? = null
) {
    val observed by viewModel.uiState.collectAsState(context = kotlinx.coroutines.Dispatchers.Main)
    val uiState = observed
    val context = LocalContext.current

    // Load goal on first composition
    LaunchedEffect(goalId, sourceSelection) {
        viewModel.loadGoal(goalId, sourceSelection)
    }

    // Refresh children when returning from child habit creation/edit
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, viewModel, uiState.sourceSelection, uiState.goalUuid, uiState.sourceExpired) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && uiState.goalUuid != null && !uiState.sourceExpired) {
                viewModel.refreshChildren(uiState)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Navigate back after save or delete
    LaunchedEffect(uiState.saved, uiState.terminalRequest) {
        if (uiState.saved && viewModel.terminalIsCurrent(uiState)) {
            onNavigateBack()
            viewModel.clearSaved(uiState)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_goal_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(enabled = !uiState.sourceExpired && uiState.goalId != null && !uiState.isDeleting && !uiState.isSaving, onClick = { viewModel.toggleDeleteDialog(uiState) }) {
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
        if (uiState.sourceExpired) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.database_view_expired))
            }
        } else if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
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
                        onValueChange = { viewModel.updateName(it, uiState) },
                        label = { Text(stringResource(R.string.edit_habit_name_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                // Description field
                item {
                    OutlinedTextField(
                        value = uiState.description,
                        onValueChange = { viewModel.updateDescription(it, uiState) },
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
                            .clickable { viewModel.toggleIconPicker(uiState) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.edit_habit_icon_label), style = MaterialTheme.typography.bodyLarge)
                            uiState.appearance?.let { ObjectIcon("edit-goal:${uiState.goalId}", it,
                                false, 0, Color.Unspecified, 24.dp) }
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                        }
                    }
                }

                // Color picker button
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.toggleColorPicker(uiState) },
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

                // Target cycles
                item {
                    OutlinedTextField(
                        value = uiState.targetCycles?.toString() ?: "",
                        onValueChange = { value ->
                            viewModel.updateTargetCycles(value.toIntOrNull(), uiState)
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
                            onClick = { viewModel.updateFailMode(FailMode.STRICT, uiState) },
                            label = { Text(stringResource(R.string.edit_habit_strict_mode)) },
                            leadingIcon = if (uiState.failMode == FailMode.STRICT) {
                                { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                            } else null
                        )
                        FilterChip(
                            selected = uiState.failMode == FailMode.LOOSE,
                            onClick = { viewModel.updateFailMode(FailMode.LOOSE, uiState) },
                            label = { Text(stringResource(R.string.edit_habit_relaxed_mode)) },
                            leadingIcon = if (uiState.failMode == FailMode.LOOSE) {
                                { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                            } else null
                        )
                    }
                }

                // Status chip
                if (!uiState.isActive) {
                    item {
                        AssistChip(
                            onClick = {},
                            label = { Text(stringResource(R.string.habit_card_status_inactive)) },
                            modifier = Modifier.height(28.dp),
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }

                // Divider
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                // Key results section
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
                        onClick = {
                            uiState.goalUuid?.let { parentUuid ->
                                navigateRenderedDatabaseInput(context, viewModel.fixedGraphConstruction,
                                    goalId, viewModel.parentSelection(uiState),
                                    { onCreateChildHabit(parentUuid) }, onCreateChildSelection)
                            }
                        },
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
                        ChildHabitCard(
                            habit = child,
                            onEdit = { navigateRenderedDatabaseInput(context, viewModel.fixedGraphConstruction,
                                child.id, viewModel.childSelection(uiState, child),
                                onEditChildHabit, onEditChildSelection) },
                            modifier = Modifier.fillMaxWidth()
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
                        onClick = { viewModel.saveGoal(uiState) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = uiState.goalId != null && !uiState.sourceExpired && !uiState.isSaving && !uiState.isDeleting
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text(stringResource(R.string.action_save))
                        }
                    }
                }
            }
        }
    }

    // Icon picker dialog
    if (!uiState.sourceExpired && uiState.showIconPicker) {
        val appearance = uiState.appearance
        if (appearance != null) ObjectAppearancePicker(appearance, false, uiState.editAuthority,
            onSelected = { viewModel.updateAppearance(it, uiState) }, onDismiss = { viewModel.toggleIconPicker(uiState) })
        else IconPicker(
            selectedIconId = uiState.iconResId,
            onIconSelected = { viewModel.updateIcon(it, uiState) },
            onDismiss = { viewModel.toggleIconPicker(uiState) }
        )
    }

    // Color picker dialog
    if (!uiState.sourceExpired && uiState.showColorPicker) {
        ColorPicker(
            selectedColor = uiState.colorHex,
            onColorSelected = { viewModel.updateColor(it, uiState) },
            onDismiss = { viewModel.toggleColorPicker(uiState) }
        )
    }

    // Delete confirmation dialog
    if (!uiState.sourceExpired && uiState.showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.toggleDeleteDialog(uiState) },
            title = { Text(stringResource(R.string.goal_delete_confirm_title)) },
            text = { Text(stringResource(R.string.goal_delete_confirm_message, uiState.name)) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteGoal(uiState) },
                    enabled = !uiState.isDeleting
                ) {
                    if (uiState.isDeleting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    } else {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(enabled = !uiState.isDeleting, onClick = { viewModel.toggleDeleteDialog(uiState) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Children deletion confirmation dialog
    if (!uiState.sourceExpired && uiState.showDeleteChildrenDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteChildrenDialog(uiState) },
            title = { Text(stringResource(R.string.dialog_delete_habit_with_children_title)) },
            text = {
                Text(stringResource(R.string.dialog_delete_habit_with_children_message, uiState.name, uiState.pendingDeleteChildrenCount))
            },
            confirmButton = {
                TextButton(
                    enabled = !uiState.isDeleting,
                    onClick = { viewModel.deleteGoalWithChildren(uiState) }
                ) {
                    Text(stringResource(R.string.dialog_delete_habit_with_children), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(enabled = !uiState.isDeleting, onClick = { viewModel.deleteGoalKeepChildren(uiState) }) {
                    Text(stringResource(R.string.dialog_delete_habit_keep_children))
                }
            }
        )
    }

    // Error dialog
    uiState.errorMessage?.takeIf { !uiState.sourceExpired }?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError(uiState) },
            title = { Text(stringResource(R.string.create_goal_error_title)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError(uiState) }) {
                    Text(stringResource(R.string.action_confirm))
                }
            }
        )
    }
}

/**
 * Card displaying a child habit (key result).
 */
@Composable
private fun ChildHabitCard(
    habit: com.dayforge.data.local.entity.HabitEntity,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable { onEdit() },
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
                    text = habit.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                when (habit.habitType) {
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
                    if (!habit.isActive) {
                        AssistChip(
                            onClick = {},
                            label = { Text(stringResource(R.string.habit_card_status_inactive), fontSize = 12.sp) },
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.child_habit_edit),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}
