package com.dayforge.ui.screens.nested

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.dayforge.R
import com.dayforge.domain.model.CardColorStyle
import com.dayforge.ui.components.GoalCompletionDialog
import com.dayforge.ui.components.ParentHabitCard
import com.dayforge.ui.components.PostCheckInDialog
import kotlinx.coroutines.launch

/**
 * NestedScreen displays habits in hierarchical tree structure.
 *
 * Per NEST-02: Tree display of parent-child hierarchy.
 * Per NEST-03: Expand/collapse functionality.
 * Per NEST-04: Check-in operations for child habits.
 * Per NEST-05: Aggregated completion progress displayed on parent cards.
 *
 * @param viewModel The NestedViewModel for data and operations
 * @param onHabitClick Callback when a child habit card is clicked (for detail screen)
 * @param onEditHabitClick Callback when edit is requested from menu (for edit screen)
 * @param onEditGoalClick Callback when a goal (parent habit) is clicked for detail view
 * @param onMetricClick Callback when a metric is clicked
 * @param modifier Modifier for custom styling
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NestedScreen(
    viewModel: NestedViewModel = hiltViewModel(),
    windowSizeClass: WindowSizeClass,
    onHabitClick: (Long) -> Unit,
    onEditHabitClick: (Long) -> Unit = {},
    onEditGoalClick: (Long) -> Unit = {},
    onCreateGoalClick: () -> Unit = {},
    onMetricClick: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val topLevelHabitsWithChildren by viewModel.topLevelHabitsWithChildren.collectAsState()
    val isInitialized by viewModel.isInitialized.collectAsState()
    val activeTimer by viewModel.activeTimerState.collectAsState()
    val pendingMetricHabits by viewModel.pendingMetricHabits.collectAsState()
    val linkedMetricsByHabit by viewModel.linkedMetricsByHabit.collectAsState()
    val postCheckInState by viewModel.postCheckInState.collectAsState()
    val expandedParentUuids by viewModel.expandedParentUuids.collectAsState()
    // Card color style for reactive card rendering (CARD-09)
    val cardColorStyle by viewModel.cardColorStyle.collectAsState()
    val showGoalDialog by viewModel.showGoalDialog.collectAsState()
    val goalHabitId by viewModel.goalHabitId.collectAsState()
    val goalProgress by viewModel.goalProgress.collectAsState()
    val goalTarget by viewModel.goalTarget.collectAsState()
    // Reactivation dialog states
    val showReactivationDialog by viewModel.showReactivationDialog.collectAsState()
    val reactivationHabitName by viewModel.reactivationHabitName.collectAsState()
    // Children deletion dialog state
    val showChildrenDialog by viewModel.showChildrenDialog.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Get goal habit name for dialog
    val goalHabitName = remember(goalHabitId, topLevelHabitsWithChildren) {
        goalHabitId?.let { id ->
            topLevelHabitsWithChildren
                .flatMap { it.children }
                .find { it.habit.id == id }
                ?.habit
                ?.name
        } ?: ""
    }

    // Battery guidance and confirmation dialog states
    var showBatteryGuidanceDialog by remember { mutableStateOf(false) }
    var showTimerConfirmation by remember { mutableStateOf(false) }
    var pendingTimerHabitId by remember { mutableStateOf<Long?>(null) }
    var pendingTimerTarget by remember { mutableStateOf(0) }

    // Notification permission launcher (Android 13+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, context.getString(R.string.toast_notification_permission_denied), Toast.LENGTH_SHORT).show()
        }
        pendingTimerHabitId?.let { habitId ->
            viewModel.startTimer(habitId, pendingTimerTarget)
        }
        pendingTimerHabitId = null
    }

    // Helper function to check and request notification permission
    fun checkNotificationPermissionAndStartTimer(habitId: Long, targetMinutes: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionStatus = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            )
            if (permissionStatus == PackageManager.PERMISSION_GRANTED) {
                viewModel.startTimer(habitId, targetMinutes)
            } else {
                pendingTimerHabitId = habitId
                pendingTimerTarget = targetMinutes
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            viewModel.startTimer(habitId, targetMinutes)
        }
    }

    // Helper function to handle timer start with all checks
    fun handleTimerStart(habitId: Long, targetMinutes: Int) {
        val currentTimer = activeTimer

        // Check if there's an active timer for a different habit
        if (currentTimer != null && currentTimer.habitId != habitId) {
            // Show confirmation dialog
            pendingTimerHabitId = habitId
            pendingTimerTarget = targetMinutes
            showTimerConfirmation = true
        } else if (currentTimer?.habitId == habitId) {
            // Timer already running for this habit, ignore
        } else {
            // No active timer, check battery guidance
            scope.launch {
                if (viewModel.shouldShowBatteryGuidance()) {
                    pendingTimerHabitId = habitId
                    pendingTimerTarget = targetMinutes
                    showBatteryGuidanceDialog = true
                } else {
                    checkNotificationPermissionAndStartTimer(habitId, targetMinutes)
                }
            }
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateGoalClick,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.fab_create_goal_content_description))
            }
        }
    ) { padding ->
        // Show loading indicator while data is being loaded
        if (!isInitialized) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.common_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (topLevelHabitsWithChildren.isEmpty()) {
            // Empty state
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.nested_no_goal_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.nested_goal_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // Check if window is larger than phone (>=600dp) for adaptive layout
            val isExpandedWindow = windowSizeClass.widthSizeClass > WindowWidthSizeClass.Compact

            // Phase 92: Staggered Grid (瀑布流) for tablets (>=600dp), LazyColumn for phones (<600dp)
            if (isExpandedWindow) {
                // Tablet: Staggered Grid (瀑布流) - each item has independent height
                // Perfect solution: expanded item doesn't push other columns down
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),  // 2列瀑布流
                    modifier = modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalItemSpacing = 8.dp  // 瀑布流垂直间距
                ) {
                    items(
                        items = topLevelHabitsWithChildren,
                        key = { it.habit.uuid }
                    ) { parentWithChildren ->
                        val isExpanded = expandedParentUuids.contains(parentWithChildren.habit.uuid)

                        // Use ParentHabitCard directly (staggered grid supports dynamic height)
                        ParentHabitCard(
                            parentHabit = parentWithChildren.habit,
                            cardColorStyle = cardColorStyle,
                            children = parentWithChildren.children,
                            completedChildren = parentWithChildren.completedChildren,
                            totalChildren = parentWithChildren.totalChildren,
                            totalChildrenIncludingNonCheckInDays = parentWithChildren.totalChildrenIncludingNonCheckInDays,
                            isCheckInAllowed = parentWithChildren.isCheckInAllowed,
                            nextCheckInDate = parentWithChildren.nextCheckInDate,
                            dayProgress = parentWithChildren.dayProgress,
                            isExpanded = isExpanded,  // Use existing expanded state
                            onClick = { onEditGoalClick(parentWithChildren.habit.id) },
                            onExpandToggle = {
                                viewModel.toggleParentExpand(parentWithChildren.habit.uuid)  // Use existing viewModel
                            },
                            onChildCheckIn = { habitId, value ->
                                viewModel.logCompletion(habitId, value)
                            },
                            onChildUndo = { habitId ->
                                val child = parentWithChildren.children.find { it.habit.id == habitId }
                                if (child != null && child.lastCompletionId != null) {
                                    viewModel.undoCompletion(child.lastCompletionId)
                                }
                            },
                            onChildIncrement = { habitId ->
                                viewModel.incrementCount(habitId)
                            },
                            onChildDecrement = { habitId ->
                                val child = parentWithChildren.children.find { it.habit.id == habitId }
                                if (child != null && child.habit.isCountdown) {
                                    viewModel.incrementCount(habitId)
                                } else {
                                    viewModel.decrementCount(habitId)
                                }
                            },
                            onChildTimerStart = { habitId, targetMinutes ->
                                handleTimerStart(habitId, targetMinutes)
                            },
                            onChildTimerPause = { viewModel.pauseTimer() },
                            onChildTimerResume = { viewModel.resumeTimer() },
                            onChildTimerStop = { viewModel.stopTimer() },
                            activeTimer = activeTimer,
                            pendingMetricHabits = pendingMetricHabits,
                            linkedMetricsByHabit = linkedMetricsByHabit,
                            onMetricClick = onMetricClick,
                            onRecordMetrics = { habitId ->
                                scope.launch {
                                    val habit = parentWithChildren.children.find { it.habit.id == habitId }?.habit
                                    if (habit != null) {
                                        viewModel.checkAndShowPostCheckInDialog(habitId, habit.name)
                                    }
                                }
                            },
                            onChildReactivation = { habitId ->
                                viewModel.showReactivationDialog(habitId)
                            },
                            onChildClick = { habitId -> onHabitClick(habitId) },
                            onChildEdit = { habitId -> onEditHabitClick(habitId) },
                            onChildDelete = { habitId ->
                                val child = parentWithChildren.children.find { it.habit.id == habitId }
                                if (child != null) {
                                    viewModel.deleteHabit(child.habit)
                                }
                            },
                            modifier = Modifier.width(420.dp) // Fixed width per cell (increased for better display)
                        )
                    }
                }
            } else {
                // Phone: LazyColumn with expand/collapse functionality
                LazyColumn(
                    modifier = modifier.padding(padding),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(
                        items = topLevelHabitsWithChildren,
                        key = { it.habit.uuid }
                    ) { parentWithChildren ->
                        val isExpanded = expandedParentUuids.contains(parentWithChildren.habit.uuid)

                        ParentHabitCard(
                            parentHabit = parentWithChildren.habit,
                            cardColorStyle = cardColorStyle,
                            children = parentWithChildren.children,
                            completedChildren = parentWithChildren.completedChildren,
                            totalChildren = parentWithChildren.totalChildren,
                            totalChildrenIncludingNonCheckInDays = parentWithChildren.totalChildrenIncludingNonCheckInDays,
                            isCheckInAllowed = parentWithChildren.isCheckInAllowed,
                            nextCheckInDate = parentWithChildren.nextCheckInDate,
                            dayProgress = parentWithChildren.dayProgress,
                            isExpanded = isExpanded,
                            onClick = { onEditGoalClick(parentWithChildren.habit.id) },
                            onExpandToggle = {
                                viewModel.toggleParentExpand(parentWithChildren.habit.uuid)
                            },
                            onChildCheckIn = { habitId, value ->
                                viewModel.logCompletion(habitId, value)
                            },
                            onChildUndo = { habitId ->
                                val child = parentWithChildren.children.find { it.habit.id == habitId }
                                if (child != null && child.lastCompletionId != null) {
                                    viewModel.undoCompletion(child.lastCompletionId)
                                }
                            },
                            onChildIncrement = { habitId ->
                                viewModel.incrementCount(habitId)
                            },
                            onChildDecrement = { habitId ->
                                val child = parentWithChildren.children.find { it.habit.id == habitId }
                                if (child != null && child.habit.isCountdown) {
                                    viewModel.incrementCount(habitId)
                                } else {
                                    viewModel.decrementCount(habitId)
                                }
                            },
                            onChildTimerStart = { habitId, targetMinutes ->
                                handleTimerStart(habitId, targetMinutes)
                            },
                            onChildTimerPause = {
                                viewModel.pauseTimer()
                            },
                            onChildTimerResume = {
                                viewModel.resumeTimer()
                            },
                            onChildTimerStop = {
                                viewModel.stopTimer()
                            },
                            activeTimer = activeTimer,
                            pendingMetricHabits = pendingMetricHabits,
                            linkedMetricsByHabit = linkedMetricsByHabit,
                            onMetricClick = onMetricClick,
                            onRecordMetrics = { habitId ->
                                scope.launch {
                                    val habit = parentWithChildren.children.find { it.habit.id == habitId }?.habit
                                    if (habit != null) {
                                        viewModel.checkAndShowPostCheckInDialog(habitId, habit.name)
                                    }
                                }
                            },
                            onChildReactivation = { habitId ->
                                viewModel.showReactivationDialog(habitId)
                            },
                            onChildClick = { habitId ->
                                onHabitClick(habitId)
                            },
                            onChildEdit = { habitId ->
                                onEditHabitClick(habitId)
                            },
                            onChildDelete = { habitId ->
                                val child = parentWithChildren.children.find { it.habit.id == habitId }
                                if (child != null) {
                                    viewModel.deleteHabit(child.habit)
                                }
                            },
                            modifier = Modifier
                        )
                    }
                }
            }
        }
    }

    // Battery optimization guidance dialog
    if (showBatteryGuidanceDialog) {
        AlertDialog(
            onDismissRequest = {
                showBatteryGuidanceDialog = false
                scope.launch {
                    viewModel.markBatteryGuidanceShown()
                }
            },
            title = { Text(stringResource(R.string.dialog_battery_optimization_title)) },
            text = {
                Text(stringResource(R.string.dialog_battery_optimization_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBatteryGuidanceDialog = false
                        scope.launch {
                            viewModel.markBatteryGuidanceShown()
                        }
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(fallbackIntent)
                            } catch (e2: Exception) {
                                Toast.makeText(context, context.getString(R.string.toast_cannot_open_settings), Toast.LENGTH_SHORT).show()
                            }
                        }
                        pendingTimerHabitId?.let { habitId ->
                            checkNotificationPermissionAndStartTimer(habitId, pendingTimerTarget)
                        }
                        pendingTimerHabitId = null
                    }
                ) {
                    Text(stringResource(R.string.dialog_battery_optimization_settings))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showBatteryGuidanceDialog = false
                        scope.launch {
                            viewModel.markBatteryGuidanceShown()
                        }
                        pendingTimerHabitId?.let { habitId ->
                            checkNotificationPermissionAndStartTimer(habitId, pendingTimerTarget)
                        }
                        pendingTimerHabitId = null
                    }
                ) {
                    Text(stringResource(R.string.dialog_battery_optimization_later))
                }
            }
        )
    }

    // Multi-timer confirmation dialog
    if (showTimerConfirmation) {
        AlertDialog(
            onDismissRequest = { showTimerConfirmation = false },
            title = { Text(stringResource(R.string.dialog_timer_switch_title)) },
            text = { Text(stringResource(R.string.dialog_timer_switch_fallback)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showTimerConfirmation = false
                        pendingTimerHabitId?.let { habitId ->
                            checkNotificationPermissionAndStartTimer(habitId, pendingTimerTarget)
                        }
                        pendingTimerHabitId = null
                    }
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showTimerConfirmation = false
                        pendingTimerHabitId = null
                    }
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Post-check-in dialog for recording linked metrics
    postCheckInState?.let { state ->
        if (state.show && state.linkedMetrics.isNotEmpty()) {
            PostCheckInDialog(
                habitName = state.habitName,
                linkedMetrics = state.linkedMetrics,
                onRecord = { values, neverAskAgain ->
                    scope.launch {
                        viewModel.recordMetricValues(state.habitId, values)
                        if (neverAskAgain) {
                            viewModel.setNeverAskAgain(state.habitId, true)
                        }
                        viewModel.dismissPostCheckInDialog()
                    }
                },
                onSkip = { neverAskAgain ->
                    scope.launch {
                        if (neverAskAgain) {
                            viewModel.setNeverAskAgain(state.habitId, true)
                        }
                        viewModel.dismissPostCheckInDialog()
                    }
                },
                onDismiss = { viewModel.dismissPostCheckInDialog() }
            )
        }
    }

    // Goal completion dialog for habits that reached targetCycles
    if (showGoalDialog) {
        GoalCompletionDialog(
            habitName = goalHabitName,
            progress = goalProgress,
            target = goalTarget,
            onConfirm = { viewModel.confirmGoalCompletion() },
            onDismiss = { viewModel.dismissGoalDialog() }
        )
    }

    // Reactivation dialog for failed habits
    if (showReactivationDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissReactivationDialog() },
            title = { Text(stringResource(R.string.dialog_reactivate_confirm)) },
            text = {
                Text(stringResource(R.string.nested_reactivation_message, reactivationHabitName))
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmReactivation() }) {
                    Text(stringResource(R.string.action_confirm_activate), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissReactivationDialog() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Children deletion confirmation dialog
    showChildrenDialog?.let { deleteInfo ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissChildrenDialog() },
            title = { Text(stringResource(R.string.dialog_delete_habit_with_children_title)) },
            text = {
                Text(stringResource(R.string.dialog_delete_habit_with_children_message, deleteInfo.habit.name, deleteInfo.childCount))
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteHabitWithChildren() }
                ) {
                    Text(stringResource(R.string.dialog_delete_habit_with_children), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.deleteHabitKeepChildren() }) {
                    Text(stringResource(R.string.dialog_delete_habit_keep_children))
                }
            }
        )
    }

}
