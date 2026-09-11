package com.dayforge.ui.screens.dashboard

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
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.*
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.dayforge.R
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CardColorStyle
import com.dayforge.domain.model.FilterMode
import com.dayforge.ui.components.GoalCompletionDialog
import com.dayforge.ui.components.HabitCard
import com.dayforge.ui.components.LinkedMetricInfo
import com.dayforge.ui.components.PostCheckInDialog
import com.dayforge.ui.components.SpeedDialFAB
import com.dayforge.ui.components.SpeedDialItem
import com.dayforge.ui.screens.dashboard.KanbanLayout
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel(),
    windowSizeClass: WindowSizeClass,
    onHabitClick: (Long) -> Unit,
    onCreateHabitClick: () -> Unit,
    onCreateTempTaskClick: () -> Unit,
    onEditHabitClick: (Long) -> Unit,
    onMetricClick: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val habitsWithStats by viewModel.habitsWithStats.collectAsState()
    val isInitialized by viewModel.isInitialized.collectAsState()
    val activeTimer by viewModel.activeTimerState.collectAsState()
    val linkedMetricsByHabit by viewModel.linkedMetricsByHabit.collectAsState()
    val postCheckInState by viewModel.postCheckInState.collectAsState()
    val pendingMetricHabits by viewModel.pendingMetricHabits.collectAsState()
    // Card color style for reactive card rendering (CARD-09)
    val cardColorStyle by viewModel.cardColorStyle.collectAsState()
    // Filter mode state
    val currentFilterMode by viewModel.filterMode.collectAsState()
    // Goal completion dialog states (TARGET-08)
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

    // Filter menu state
    var showFilterMenu by remember { mutableStateOf(false) }
    // FAB menu state
    var isFabExpanded by remember { mutableStateOf(false) }

    // Battery guidance and confirmation dialog states
    var showBatteryGuidanceDialog by remember { mutableStateOf(false) }
    var showTimerConfirmation by remember { mutableStateOf(false) }
    var pendingTimerHabitId by remember { mutableStateOf<Long?>(null) }
    var pendingTimerTarget by remember { mutableStateOf(0) }

    // Notification permission launcher (Android 13+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Permission result is handled - we proceed either way
        // If denied, notifications won't show but timer still works
        if (!isGranted) {
            Toast.makeText(context, context.getString(R.string.dialog_notification_permission_denied), Toast.LENGTH_SHORT).show()
        }
        // Start the pending timer after permission request
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
            // Android 12 and below - permission granted at install time
            viewModel.startTimer(habitId, targetMinutes)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dashboard_tab_habits)) },
                actions = {
                    // Filter menu
                    Box {
                        IconButton(
                            onClick = { showFilterMenu = true }
                        ) {
                            Icon(
                                imageVector = Icons.Default.FilterList,
                                contentDescription = stringResource(R.string.dashboard_filter_content_desc),
                                tint = if (currentFilterMode != FilterMode.ALL.value) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }

                        DropdownMenu(
                            expanded = showFilterMenu,
                            onDismissRequest = { showFilterMenu = false }
                        ) {
                            FilterMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(getFilterModeLabelResId(mode))) },
                                    onClick = {
                                        showFilterMenu = false
                                        viewModel.setFilterMode(mode.value)
                                    },
                                    leadingIcon = {
                                        if (currentFilterMode == mode.value) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            SpeedDialFAB(
                isExpanded = isFabExpanded,
                onToggle = { isFabExpanded = !isFabExpanded },
                items = listOf(
                    SpeedDialItem(
                        label = stringResource(R.string.fab_menu_habit),
                        icon = Icons.Default.Add,
                        onClick = {
                            isFabExpanded = false
                            onCreateHabitClick()
                        }
                    ),
                    SpeedDialItem(
                        label = stringResource(R.string.fab_menu_temp_task),
                        icon = Icons.Rounded.TaskAlt,
                        onClick = {
                            isFabExpanded = false
                            onCreateTempTaskClick()
                        }
                    )
                )
            )
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
        } else if (habitsWithStats.isEmpty()) {
            EmptyHabitList(
                modifier = Modifier.padding(padding),
                filterMode = FilterMode.fromValue(currentFilterMode)
            )
        } else {
            // KANBAN-01: Check if window is larger than phone (>=600dp) for adaptive layout
            val isExpandedWindow = windowSizeClass.widthSizeClass > WindowWidthSizeClass.Compact

            if (isExpandedWindow) {
                // KANBAN-01: Tablet layout - Kanban four-column view
                KanbanLayout(
                    habitsWithStats = habitsWithStats,
                    activeTimer = activeTimer,
                    cardColorStyle = cardColorStyle,
                    linkedMetricsByHabit = linkedMetricsByHabit,
                    pendingMetricHabits = pendingMetricHabits,
                    onHabitClick = { habitId -> onHabitClick(habitId) },
                    onDelete = { habit -> viewModel.deleteHabit(habit) },
                    onEdit = { habitId -> onEditHabitClick(habitId) },
                    onCheckIn = { habitId ->
                        scope.launch {
                            viewModel.checkIn(habitId)
                        }
                    },
                    onUndo = { habitId, completionId ->
                        completionId?.let { id ->
                            scope.launch {
                                viewModel.undoCompletion(id)
                            }
                        }
                    },
                    onIncrement = { habitId -> viewModel.incrementCount(habitId) },
                    onDecrement = { habitId ->
                        // Find habit for countdown vs countup logic
                        val habit = habitsWithStats.find { it.habit.id == habitId }?.habit
                        if (habit?.isCountdown == true) {
                            viewModel.incrementCount(habitId)  // Countdown: - button = increment (reduce remaining)
                        } else {
                            viewModel.decrementCount(habitId)  // Countup: - button = decrement
                        }
                    },
                    onTimerStart = { habitId, targetMinutes ->
                        val currentTimer = activeTimer
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
                    },
                    onTimerPause = viewModel::pauseTimer,
                    onTimerResume = viewModel::resumeTimer,
                    onTimerStop = viewModel::stopTimer,
                    onRecordMetrics = { habitId ->
                        scope.launch {
                            val habit = habitsWithStats.find { it.habit.id == habitId }?.habit
                            habit?.let { viewModel.checkAndShowPostCheckInDialog(it.id, it.name) }
                        }
                    },
                    onMetricClick = { metricId -> onMetricClick(metricId) },
                    onReactivation = { habitId -> viewModel.showReactivationDialog(habitId) },
                    modifier = Modifier.padding(padding)
                )
            } else {
                // KANBAN-01: Phone layout - existing LazyColumn preserved
                LazyColumn(
                    modifier = modifier,
                    contentPadding = padding
                ) {
                    // Habits section - existing HabitCard rendering
                    items(habitsWithStats.size) { index ->
                        val habitWithStats = habitsWithStats[index]
                        val habit = habitWithStats.habit

                        HabitCard(
                            habit = habit,
                            cardColorStyle = cardColorStyle,
                            onClick = { onHabitClick(habit.id) },
                            onDelete = { viewModel.deleteHabit(habit) },
                            onEdit = { onEditHabitClick(habit.id) },
                            currentStreak = habitWithStats.currentStreak,
                            bestStreak = habitWithStats.bestStreak,
                            activityRate = habitWithStats.activityRate,
                            completed = habitWithStats.completedToday,
                            undoAvailable = habitWithStats.lastCompletionId != null,
                            todayCount = habitWithStats.todayCount,
                            onCheckIn = { value ->
                                scope.launch {
                                    // Per TARGET-15: Use checkIn method for goal detection
                                    viewModel.checkIn(habit.id)
                                }
                            },
                            onUndo = {
                                habitWithStats.lastCompletionId?.let { completionId ->
                                    scope.launch {
                                        viewModel.undoCompletion(completionId)
                                    }
                                }
                            },
                            onIncrement = { viewModel.incrementCount(habit.id) },
                            onDecrement = {
                                // Countdown mode: - button = increment count (reduce remaining)
                                // Countup mode: - button = decrement count
                                if (habit.isCountdown) {
                                    viewModel.incrementCount(habit.id)
                                } else {
                                    viewModel.decrementCount(habit.id)
                                }
                            },
                            // Timer state and callbacks
                            activeTimer = activeTimer,
                            onTimerStart = {
                                // Check if there's an active timer for a different habit
                                val currentTimer = activeTimer
                                if (currentTimer != null && currentTimer.habitId != habit.id) {
                                    // Show confirmation dialog
                                    pendingTimerHabitId = habit.id
                                    pendingTimerTarget = habit.targetValue
                                    showTimerConfirmation = true
                                } else if (currentTimer?.habitId == habit.id) {
                                    // Timer already running for this habit, ignore
                                } else {
                                    // No active timer, check battery guidance
                                    scope.launch {
                                        if (viewModel.shouldShowBatteryGuidance()) {
                                            pendingTimerHabitId = habit.id
                                            pendingTimerTarget = habit.targetValue
                                            showBatteryGuidanceDialog = true
                                        } else {
                                            checkNotificationPermissionAndStartTimer(habit.id, habit.targetValue)
                                        }
                                    }
                                }
                            },
                            onTimerPause = viewModel::pauseTimer,
                            onTimerResume = viewModel::resumeTimer,
                            onTimerStop = viewModel::stopTimer,
                            // Metric prompt for completed timer habits
                            showMetricPrompt = pendingMetricHabits.contains(habit.id),
                            onRecordMetrics = {
                                scope.launch {
                                    viewModel.checkAndShowPostCheckInDialog(habit.id, habit.name)
                                }
                            },
                            // Linked metrics
                            linkedMetrics = linkedMetricsByHabit[habit.id] ?: emptyList(),
                            onMetricClick = { metricId -> onMetricClick(metricId) },
                            // Check-in day validation
                            isCheckInAllowed = habitWithStats.isCheckInAllowed,
                            nextCheckInDate = habitWithStats.nextCheckInDate,
                            // Target progress for habits with targetCycles
                            targetProgress = habitWithStats.targetProgress,
                            // Failure status
                            hasFailed = habitWithStats.hasFailed,
                            // Goal completed status
                            isGoalCompleted = habitWithStats.isGoalCompleted,
                            // Reactivation for failed/completed habits
                            onReactivation = { viewModel.showReactivationDialog(habit.id) },
                            // Slot progress for focus mode (APP-04)
                            slotProgress = habitWithStats.slotProgress
                        )
                    }
                }
            }
        }
    }

    // Battery optimization guidance dialog (D-10, D-12)
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
                        // Open battery optimization settings
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // Fallback to app settings if battery optimization settings unavailable
                            try {
                                val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(fallbackIntent)
                            } catch (e2: Exception) {
                                Toast.makeText(context, context.getString(R.string.dialog_settings_unavailable), Toast.LENGTH_SHORT).show()
                            }
                        }
                        // Start the pending timer with notification permission check
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
                        // Start the pending timer with notification permission check
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

    // Multi-timer confirmation dialog (D-08)
    if (showTimerConfirmation) {
        AlertDialog(
            onDismissRequest = { showTimerConfirmation = false },
            title = { Text(stringResource(R.string.dialog_multi_timer_title)) },
            text = { Text(stringResource(R.string.dialog_multi_timer_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showTimerConfirmation = false
                        // Just start new timer - TimerService.handleStart will stop the old one
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
                        if (state.isTempTask) {
                            viewModel.deleteTempTask(state.habitId)
                        }
                    }
                },
                onSkip = { neverAskAgain ->
                    scope.launch {
                        if (neverAskAgain) {
                            viewModel.setNeverAskAgain(state.habitId, true)
                        }
                        viewModel.dismissPostCheckInDialog()
                        if (state.isTempTask) {
                            viewModel.deleteTempTask(state.habitId)
                        }
                    }
                },
                onDismiss = {
                    viewModel.dismissPostCheckInDialog()
                    // 临时任务在对话框关闭后删除
                    if (state.isTempTask) {
                        viewModel.deleteTempTask(state.habitId)
                    }
                }
            )
        }
    }

    // Goal completion dialog (TARGET-08)
    if (showGoalDialog && goalHabitId != null) {
        val goalHabit = habitsWithStats.find { it.habit.id == goalHabitId }
        if (goalHabit != null) {
            GoalCompletionDialog(
                habitName = goalHabit.habit.name,
                progress = goalProgress,
                target = goalTarget,
                onConfirm = { viewModel.confirmGoalCompletion() },
                onDismiss = { viewModel.dismissGoalDialog() }
            )
        }
    }

    // Reactivation dialog for failed habits
    if (showReactivationDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissReactivationDialog() },
            title = { Text(stringResource(R.string.dialog_reactivate_title)) },
            text = {
                Text(stringResource(R.string.dialog_reactivate_habit_message, reactivationHabitName))
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmReactivation() }) {
                    Text(stringResource(R.string.dialog_reactivate_confirm), color = MaterialTheme.colorScheme.error)
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

@Composable
fun EmptyHabitList(
    modifier: Modifier = Modifier,
    filterMode: FilterMode = FilterMode.ALL
) {
    // Get title and hint based on filter mode
    val (titleResId, hintResId) = when (filterMode) {
        FilterMode.ALL -> R.string.dashboard_empty_all to R.string.dashboard_empty_all_hint
        FilterMode.TIME_WINDOW -> R.string.dashboard_empty_time_window to R.string.dashboard_empty_time_window_hint
        FilterMode.CHECKABLE -> R.string.dashboard_empty_checkable to R.string.dashboard_empty_checkable_hint
        FilterMode.TERMINATED -> R.string.dashboard_empty_terminated to R.string.dashboard_empty_terminated_hint
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(titleResId),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(hintResId),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Get string resource ID for filter mode label.
 */
@Composable
private fun getFilterModeLabelResId(mode: FilterMode): Int {
    return when (mode) {
        FilterMode.ALL -> R.string.filter_mode_all
        FilterMode.TIME_WINDOW -> R.string.filter_mode_time_window
        FilterMode.CHECKABLE -> R.string.filter_mode_checkable
        FilterMode.TERMINATED -> R.string.filter_mode_terminated
    }
}
