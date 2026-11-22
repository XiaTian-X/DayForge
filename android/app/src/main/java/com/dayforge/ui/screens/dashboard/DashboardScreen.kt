package com.dayforge.ui.screens.dashboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.dayforge.R
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
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
import com.dayforge.ui.TimerLaunchInput
import com.dayforge.ui.renderedSelection
import com.dayforge.data.local.DatabaseObjectSelection
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
    modifier: Modifier = Modifier,
    onHabitSelection: ((DatabaseObjectSelection) -> Unit)? = null,
    onEditHabitSelection: ((DatabaseObjectSelection) -> Unit)? = null,
    onMetricSelection: ((DatabaseObjectSelection) -> Unit)? = null
) {
    val habitsWithStats by viewModel.habitsWithStats.collectAsStateWithLifecycle()
    val busyHabitIds by viewModel.busyHabitIds.collectAsStateWithLifecycle()
    val countButtonFrame by viewModel.countButtonFrame.collectAsStateWithLifecycle()
    val fixedCountButtons by viewModel.fixedCountButtonModels.collectAsStateWithLifecycle()
    val countButtons = if (viewModel.fixedGraphConstruction) fixedCountButtons else countButtonFrame?.value.orEmpty()
    val isInitialized by viewModel.isInitialized.collectAsStateWithLifecycle()
    val readError by viewModel.readError.collectAsStateWithLifecycle()
    val fixedActiveTimer by viewModel.activeTimerState.collectAsStateWithLifecycle()
    val activeTimerFrameValue by viewModel.activeTimerFrame.collectAsStateWithLifecycle()
    val renderedActiveTimerFrame = activeTimerFrameValue
    val activeTimer = if (viewModel.fixedGraphConstruction) fixedActiveTimer else renderedActiveTimerFrame?.value ?: null
    val fixedLinkedMetricsByHabit by viewModel.linkedMetricsByHabit.collectAsStateWithLifecycle()
    val linkedMetricFrameValue by viewModel.linkedMetricFrame.collectAsStateWithLifecycle()
    val renderedLinkedMetricFrame = linkedMetricFrameValue
    val linkedMetricsByHabit = if (viewModel.fixedGraphConstruction) fixedLinkedMetricsByHabit else renderedLinkedMetricFrame?.value ?: emptyMap()
    val fixedPostCheckInState by viewModel.postCheckInState.collectAsStateWithLifecycle()
    val postCheckInFrameValue by viewModel.postCheckInFrame.collectAsStateWithLifecycle()
    val renderedPostCheckInFrame = postCheckInFrameValue
    val postCheckInState = if (viewModel.fixedGraphConstruction) fixedPostCheckInState else renderedPostCheckInFrame?.value ?: null
    val pendingMetricHabits by viewModel.pendingMetricHabits.collectAsStateWithLifecycle()
    // Card color style for reactive card rendering (CARD-09)
    val cardColorStyle = com.dayforge.ui.theme.LocalDeviceCardStyle.current
    // Filter mode state
    val currentFilterMode by viewModel.filterMode.collectAsStateWithLifecycle()
    // Goal completion dialog states (TARGET-08)
    val goalDialogFrameValue by viewModel.goalDialogFrame.collectAsStateWithLifecycle()
    val renderedGoalDialogFrame = goalDialogFrameValue
    val reactivationDialogFrameValue by viewModel.reactivationDialogFrame.collectAsStateWithLifecycle()
    val renderedReactivationDialogFrame = reactivationDialogFrameValue
    val deletionDialogFrameValue by viewModel.deletionDialogFrame.collectAsStateWithLifecycle()
    val renderedDeletionDialogFrame = deletionDialogFrameValue
    val fixedShowGoalDialog by viewModel.showGoalDialog.collectAsStateWithLifecycle()
    val showGoalDialog = if (viewModel.fixedGraphConstruction) fixedShowGoalDialog else renderedGoalDialogFrame?.value?.show ?: false
    val fixedGoalHabitId by viewModel.goalHabitId.collectAsStateWithLifecycle()
    val goalHabitId = if (viewModel.fixedGraphConstruction) fixedGoalHabitId else renderedGoalDialogFrame?.value?.habitId ?: null
    val fixedGoalProgress by viewModel.goalProgress.collectAsStateWithLifecycle()
    val goalProgress = if (viewModel.fixedGraphConstruction) fixedGoalProgress else renderedGoalDialogFrame?.value?.progress ?: 0
    val fixedGoalTarget by viewModel.goalTarget.collectAsStateWithLifecycle()
    val goalTarget = if (viewModel.fixedGraphConstruction) fixedGoalTarget else renderedGoalDialogFrame?.value?.target ?: 0
    // Reactivation dialog states
    val fixedShowReactivationDialog by viewModel.showReactivationDialog.collectAsStateWithLifecycle()
    val showReactivationDialog = if (viewModel.fixedGraphConstruction) fixedShowReactivationDialog else renderedReactivationDialogFrame?.value?.show ?: false
    val fixedReactivationHabitId by viewModel.reactivationHabitId.collectAsStateWithLifecycle()
    val reactivationHabitId = if (viewModel.fixedGraphConstruction) fixedReactivationHabitId else renderedReactivationDialogFrame?.value?.habitId ?: null
    val fixedReactivationHabitName by viewModel.reactivationHabitName.collectAsStateWithLifecycle()
    val reactivationHabitName = if (viewModel.fixedGraphConstruction) fixedReactivationHabitName else renderedReactivationDialogFrame?.value?.habitName ?: ""
    val fixedReactivationPreservesHistory by viewModel.reactivationPreservesHistory.collectAsStateWithLifecycle()
    val reactivationPreservesHistory = if (viewModel.fixedGraphConstruction) fixedReactivationPreservesHistory else renderedReactivationDialogFrame?.value?.preservesHistory ?: false
    // Children deletion dialog state
    val fixedShowChildrenDialog by viewModel.showChildrenDialog.collectAsStateWithLifecycle()
    val showChildrenDialog = if (viewModel.fixedGraphConstruction) fixedShowChildrenDialog else renderedDeletionDialogFrame?.value ?: null
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Capture actual rendered values now; callbacks must not consult a newer StateFlow by ID.
    val renderedHabits = habitsWithStats
    fun habitSelection(id: Long): DatabaseObjectSelection? = renderedHabits.find { it.habit.id == id }?.renderedSelection()
    fun navigateHabit(id: Long, edit: Boolean = false) = com.dayforge.ui.navigateRenderedDatabaseInput(
        context, viewModel.fixedGraphConstruction, id, habitSelection(id),
        if (edit) onEditHabitClick else onHabitClick, if (edit) onEditHabitSelection else onHabitSelection)


    // Filter menu state
    var showFilterMenu by remember { mutableStateOf(false) }
    // FAB menu state
    var isFabExpanded by remember { mutableStateOf(false) }

    fun metricSelection(id: Long): DatabaseObjectSelection? {
        val frame = renderedLinkedMetricFrame ?: return null
        val metric = frame.value.values.asSequence().flatMap { it.asSequence() }.firstOrNull { it.metricId == id } ?: return null
        val uuid = metric.metricUuid ?: return null
        return DatabaseObjectSelection.metric(id, uuid, frame.publication)
    }
    fun navigateMetric(id: Long) = com.dayforge.ui.navigateRenderedDatabaseInput(context,
        viewModel.fixedGraphConstruction, id, metricSelection(id), onMetricClick, onMetricSelection)

    // Battery guidance and confirmation dialog states
    var showBatteryGuidanceDialog by remember { mutableStateOf(false) }
    var showTimerConfirmation by remember { mutableStateOf(false) }
    var pendingTimer by remember { mutableStateOf<TimerLaunchInput?>(null) }
    var permissionTimer by remember { mutableStateOf<TimerLaunchInput?>(null) }
    val renderedPendingTimer = pendingTimer
    fun timerInput(habitId: Long, target: Int) = TimerLaunchInput(habitId, target, habitSelection(habitId), renderedActiveTimerFrame)
    fun rejectTimerInput() { Toast.makeText(context, R.string.database_view_expired, Toast.LENGTH_LONG).show() }
    fun acceptPendingTimer(): TimerLaunchInput? {
        val original = renderedPendingTimer
        if (original == null || pendingTimer !== original || !original.isCurrentFor(viewModel.fixedGraphConstruction)) {
            rejectTimerInput(); return null
        }
        pendingTimer = null
        return original
    }

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
        val original = permissionTimer
        permissionTimer = null
        if (original != null) {
            if (original.isCurrentFor(viewModel.fixedGraphConstruction))
                viewModel.startTimer(original.habitId, original.targetMinutes, original.selection)
            else rejectTimerInput()
        }
    }

    // Helper function to check and request notification permission
    fun checkNotificationPermissionAndStartTimer(original: TimerLaunchInput) {
        if (!original.isCurrentFor(viewModel.fixedGraphConstruction)) { rejectTimerInput(); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // A second click is not allowed to replace the original in-flight permission input.
            if (permissionTimer != null) { rejectTimerInput(); return }
            permissionTimer = original
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else viewModel.startTimer(original.habitId, original.targetMinutes, original.selection)
    }
    fun continuePendingTimer() {
        val original = acceptPendingTimer() ?: return
        checkNotificationPermissionAndStartTimer(original)
    }
    LaunchedEffect(renderedHabits, pendingTimer) {
        val original = pendingTimer
        if (original != null && !original.isCurrentFor(viewModel.fixedGraphConstruction) && pendingTimer === original) {
            pendingTimer = null; showTimerConfirmation = false; showBatteryGuidanceDialog = false
        }
    }

    CompositionLocalProvider(com.dayforge.ui.LocalInFlightHabitIds provides busyHabitIds,
        com.dayforge.ui.LocalCountButtonModels provides countButtons) {
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
        } else if (readError) {
            com.dayforge.ui.components.DataReadFailure(viewModel::retryRead,
                Modifier.fillMaxSize().padding(padding))
        } else if (renderedHabits.isEmpty()) {
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
                    habitsWithStats = renderedHabits,
                    activeTimer = activeTimer,
                    cardColorStyle = cardColorStyle,
                    linkedMetricsByHabit = linkedMetricsByHabit,
                    pendingMetricHabits = pendingMetricHabits,
                    onHabitClick = { habitId -> navigateHabit(habitId) },
                    onDelete = { habit -> viewModel.deleteHabit(habit, selection = habitSelection(habit.id)) },
                    onEdit = { habitId -> navigateHabit(habitId, edit = true) },
                    onCheckIn = { habitId ->
                        scope.launch {
                            viewModel.checkIn(habitId, selection = habitSelection(habitId))
                        }
                    },
                    onUndo = { habitId, completionId ->
                        completionId?.let { id ->
                            scope.launch {
                                viewModel.undoCompletion(id, selection = habitSelection(habitId))
                            }
                        }
                    },
                    onIncrement = { habitId -> viewModel.incrementCount(habitId, selection = habitSelection(habitId)) },
                    onDecrement = { habitId ->
                        // Find habit for countdown vs countup logic
                        val stats = renderedHabits.find { it.habit.id == habitId }
                        if (stats?.displayIsCountdown == true) {
                            viewModel.incrementCount(habitId, selection = habitSelection(habitId))  // Countdown: - button = increment (reduce remaining)
                        } else {
                            viewModel.decrementCount(habitId, selection = habitSelection(habitId))  // Countup: - button = decrement
                        }
                    },
                    onTimerStart = { habitId, targetMinutes ->
                        val currentTimer = activeTimer
                        if (currentTimer != null && currentTimer.habitId != habitId) {
                            // Show confirmation dialog
                            pendingTimer = timerInput(habitId, targetMinutes)
                            showTimerConfirmation = true
                        } else if (currentTimer?.habitId == habitId) {
                            // Timer already running for this habit, ignore
                        } else {
                            // No active timer, check battery guidance
                            scope.launch {
                                if (viewModel.shouldShowBatteryGuidance()) {
                                    pendingTimer = timerInput(habitId, targetMinutes)
                                    showBatteryGuidanceDialog = true
                                } else {
                                    checkNotificationPermissionAndStartTimer(timerInput(habitId, targetMinutes))
                                }
                            }
                        }
                    },
                    onTimerPause = { viewModel.pauseTimer(renderedActiveTimerFrame) },
                    onTimerResume = { viewModel.resumeTimer(renderedActiveTimerFrame) },
                    onTimerStop = { viewModel.stopTimer(renderedActiveTimerFrame) },
                    onRecordMetrics = { habitId ->
                        scope.launch {
                            val habit = renderedHabits.find { it.habit.id == habitId }?.habit
                            habit?.let { viewModel.checkAndShowPostCheckInDialog(it.id, it.name, habitSelection(it.id)) }
                        }
                    },
                    onMetricClick = { metricId -> navigateMetric(metricId) },
                    onReactivation = { habitId -> viewModel.showReactivationDialog(habitId, selection = habitSelection(habitId)) },
                    modifier = Modifier.padding(padding)
                )
            } else {
                // KANBAN-01: Phone layout - existing LazyColumn preserved
                LazyColumn(
                    modifier = modifier,
                    contentPadding = padding
                ) {
                    // Habits section - existing HabitCard rendering
                    items(renderedHabits.size) { index ->
                        val habitWithStats = renderedHabits[index]
                        val habit = habitWithStats.habit

                        HabitCard(
                            habit = habit,
                            cardColorStyle = cardColorStyle,
                            onClick = { navigateHabit(habit.id) },
                            onDelete = { viewModel.deleteHabit(habit, selection = habitSelection(habit.id)) },
                            onEdit = { navigateHabit(habit.id, edit = true) },
                            currentStreak = habitWithStats.currentStreak,
                            bestStreak = habitWithStats.bestStreak,
                            activityRate = habitWithStats.activityRate,
                            completed = habitWithStats.completedForDisplay,
                            actionsEnabled = habitWithStats.oneTimeStatus?.canChange ?: true,
                            undoAvailable = habitWithStats.lastCompletionId != null,
                            todayCount = habitWithStats.todayCount,
                            countHistory = habitWithStats.countHistory,
                            countSummary = habitWithStats.countSummary,
                            countDisplay = habitWithStats.countDisplay,
                            onCheckIn = { value ->
                                scope.launch {
                                    // Per TARGET-15: Use checkIn method for goal detection
                                    viewModel.checkIn(habit.id, selection = habitSelection(habit.id))
                                }
                            },
                            onUndo = {
                                habitWithStats.lastCompletionId?.let { completionId ->
                                    scope.launch {
                                        viewModel.undoCompletion(completionId, selection = habitSelection(habit.id))
                                    }
                                }
                            },
                            onIncrement = { viewModel.incrementCount(habit.id, selection = habitSelection(habit.id)) },
                            onDecrement = {
                                // Countdown mode: - button = increment count (reduce remaining)
                                // Countup mode: - button = decrement count
                                if (habitWithStats.displayIsCountdown) {
                                    viewModel.incrementCount(habit.id, selection = habitSelection(habit.id))
                                } else {
                                    viewModel.decrementCount(habit.id, selection = habitSelection(habit.id))
                                }
                            },
                            // Timer state and callbacks
                            activeTimer = activeTimer,
                            onTimerStart = {
                                // Check if there's an active timer for a different habit
                                val currentTimer = activeTimer
                                if (currentTimer != null && currentTimer.habitId != habit.id) {
                                    // Show confirmation dialog
                                    pendingTimer = timerInput(habit.id, habit.targetValue)
                                    showTimerConfirmation = true
                                } else if (currentTimer?.habitId == habit.id) {
                                    // Timer already running for this habit, ignore
                                } else {
                                    // No active timer, check battery guidance
                                    scope.launch {
                                        if (viewModel.shouldShowBatteryGuidance()) {
                                            pendingTimer = timerInput(habit.id, habit.targetValue)
                                            showBatteryGuidanceDialog = true
                                        } else {
                                            checkNotificationPermissionAndStartTimer(timerInput(habit.id, habit.targetValue))
                                        }
                                    }
                                }
                            },
                            onTimerPause = { viewModel.pauseTimer(renderedActiveTimerFrame) },
                            onTimerResume = { viewModel.resumeTimer(renderedActiveTimerFrame) },
                            onTimerStop = { viewModel.stopTimer(renderedActiveTimerFrame) },
                            // Metric prompt for completed timer habits
                            showMetricPrompt = pendingMetricHabits.contains(habit.id),
                            onRecordMetrics = {
                                scope.launch {
                                    viewModel.checkAndShowPostCheckInDialog(habit.id, habit.name, habitSelection(habit.id))
                                }
                            },
                            // Linked metrics
                            linkedMetrics = linkedMetricsByHabit[habit.id] ?: emptyList(),
                            onMetricClick = { metricId -> navigateMetric(metricId) },
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
                            onReactivation = { viewModel.showReactivationDialog(habit.id, selection = habitSelection(habit.id)) },
                            // Slot progress for focus mode (APP-04)
                            slotProgress = habitWithStats.slotProgress
                        )
                    }
                }
            }
        }
    }

    }
    // Battery optimization guidance dialog (D-10, D-12)
    if (showBatteryGuidanceDialog) {
        AlertDialog(
            onDismissRequest = dismissBattery@{
                if (pendingTimer !== renderedPendingTimer || renderedPendingTimer?.isCurrentFor(viewModel.fixedGraphConstruction) != true) return@dismissBattery
                showBatteryGuidanceDialog = false
                scope.launch {
                    viewModel.markBatteryGuidanceShown(renderedPendingTimer?.selection)
                }
            },
            title = { Text(stringResource(R.string.dialog_battery_optimization_title)) },
            text = {
                Text(stringResource(R.string.dialog_battery_optimization_message))
            },
            confirmButton = {
                TextButton(
                    onClick = timerAction@{
                        if (pendingTimer !== renderedPendingTimer || renderedPendingTimer?.isCurrentFor(viewModel.fixedGraphConstruction) != true) { rejectTimerInput(); return@timerAction }
                        showBatteryGuidanceDialog = false
                        scope.launch {
                            viewModel.markBatteryGuidanceShown(renderedPendingTimer?.selection)
                        }
                        // Open battery optimization settings
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = "package:${context.packageName}".toUri()
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // Fallback to app settings if battery optimization settings unavailable
                            try {
                                val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = "package:${context.packageName}".toUri()
                                }
                                context.startActivity(fallbackIntent)
                            } catch (e2: Exception) {
                                Toast.makeText(context, context.getString(R.string.dialog_settings_unavailable), Toast.LENGTH_SHORT).show()
                            }
                        }
                        // Start the pending timer with notification permission check
                        continuePendingTimer()
                    }
                ) {
                    Text(stringResource(R.string.dialog_battery_optimization_settings))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = timerAction@{
                        if (pendingTimer !== renderedPendingTimer || renderedPendingTimer?.isCurrentFor(viewModel.fixedGraphConstruction) != true) { rejectTimerInput(); return@timerAction }
                        showBatteryGuidanceDialog = false
                        scope.launch {
                            viewModel.markBatteryGuidanceShown(renderedPendingTimer?.selection)
                        }
                        // Start the pending timer with notification permission check
                        continuePendingTimer()
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
            onDismissRequest = { if (pendingTimer === renderedPendingTimer) { showTimerConfirmation = false; pendingTimer = null } },
            title = { Text(stringResource(R.string.dialog_multi_timer_title)) },
            text = { Text(stringResource(R.string.dialog_multi_timer_message)) },
            confirmButton = {
                TextButton(
                    onClick = timerAction@{
                        if (pendingTimer !== renderedPendingTimer || renderedPendingTimer?.isCurrentFor(viewModel.fixedGraphConstruction) != true) { rejectTimerInput(); return@timerAction }
                        showTimerConfirmation = false
                        // Just start new timer - TimerService.handleStart will stop the old one
                        continuePendingTimer()
                    }
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = timerAction@{
                        if (pendingTimer !== renderedPendingTimer || renderedPendingTimer?.isCurrentFor(viewModel.fixedGraphConstruction) != true) { rejectTimerInput(); return@timerAction }
                        showTimerConfirmation = false
                        if (pendingTimer === renderedPendingTimer) pendingTimer = null
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
            suspend fun finishSubmittedMetrics(neverAskAgain: Boolean) {
                com.dayforge.domain.service.afterCommittedAction {
                    if (neverAskAgain) viewModel.setNeverAskAgain(state.habitId, true, state.oneTimePrompt?.eventUuid,
                        state.timerPrompt, state.preferenceClaim, state.factPrompt, frame = renderedPostCheckInFrame)
                    viewModel.dismissPostCheckInDialog(state, renderedPostCheckInFrame)
                    if (state.isTempTask) viewModel.deleteTempTask(state.habitId, renderedPostCheckInFrame)
                }
            }
            PostCheckInDialog(
                habitName = state.habitName,
                linkedMetrics = state.linkedMetrics,
                submissionProgress = state.factPrompt?.submissionPhase,
                savedSubmission = { state.factPrompt?.savedSubmissionForDisplay() },
                onContinue = if (state.factPrompt?.submissionPhase != null) ({
                    try {
                        val prompt = requireNotNull(state.factPrompt)
                        val saved = requireNotNull(prompt.savedSubmissionForDisplay())
                        if (viewModel.continueSavedMetricDelivery(state.habitId, prompt, renderedPostCheckInFrame))
                            finishSubmittedMetrics(saved.neverAskAgain)
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        Toast.makeText(context, com.dayforge.ui.actionFailureMessage(context, error), Toast.LENGTH_LONG).show()
                    }
                }) else null,
                promptIdentity = state.oneTimePrompt?.eventUuid,
                missingTargets = state.oneTimePrompt?.entries?.filterNot { it.available }?.map { it.name }.orEmpty(),
                onRefreshMetadata = if (state.oneTimePrompt != null) { { viewModel.refreshPostCheckInMetadata(renderedPostCheckInFrame) } } else null,
                initialInputs = state.oneTimePrompt?.entries?.associate { it.metricId to (it.input to it.note) }.orEmpty(),
                onDraftChange = { inputs -> state.oneTimePrompt?.let { viewModel.savePostCheckInDraft(it.eventUuid, inputs, renderedPostCheckInFrame) } },
                onRecord = { values, neverAskAgain ->
                    run record@ {
                        try {
                            if (!viewModel.recordMetricValues(state.habitId, values, state.oneTimePrompt?.eventUuid, state.timerPrompt, state.preferenceClaim, state.factPrompt, frame = renderedPostCheckInFrame, neverAskAgain = neverAskAgain)) return@record
                            finishSubmittedMetrics(neverAskAgain)
                        } catch (error: Exception) {
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            Toast.makeText(context, com.dayforge.ui.actionFailureMessage(context, error), Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onSkip = { neverAskAgain ->
                    run {
                        try {
                            if (neverAskAgain) {
                                viewModel.setNeverAskAgain(state.habitId, true, state.oneTimePrompt?.eventUuid, state.timerPrompt, state.preferenceClaim, state.factPrompt, frame = renderedPostCheckInFrame)
                            }
                            viewModel.skipPostCheckInDialog(state.habitId, state.oneTimePrompt?.eventUuid, state.timerPrompt, state.preferenceClaim, state.factPrompt, frame = renderedPostCheckInFrame)
                            if (state.isTempTask) {
                                viewModel.deleteTempTask(state.habitId, renderedPostCheckInFrame)
                            }
                        } catch (error: Exception) {
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            Toast.makeText(context, com.dayforge.ui.actionFailureMessage(context, error), Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onDismiss = {
                    try {
                        viewModel.dismissPostCheckInDialog(state, renderedPostCheckInFrame)
                        // 临时任务在对话框关闭后删除
                        if (state.isTempTask) viewModel.deleteTempTask(state.habitId, renderedPostCheckInFrame)
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        Toast.makeText(context, com.dayforge.ui.actionFailureMessage(context, error), Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
    }

    // Goal completion dialog (TARGET-08)
    if (showGoalDialog && goalHabitId != null) {
        val goalHabit = renderedHabits.find { it.habit.id == goalHabitId && (viewModel.fixedGraphConstruction || it.habit.uuid == renderedGoalDialogFrame?.value?.habitUuid) }
        if (goalHabit != null) {
            GoalCompletionDialog(
                busy = goalHabitId in busyHabitIds,
                habitName = goalHabit.habit.name,
                progress = goalProgress,
                target = goalTarget,
                onConfirm = { viewModel.confirmGoalCompletion(renderedGoalDialogFrame) },
                onDismiss = { viewModel.dismissGoalDialog(renderedGoalDialogFrame) }
            )
        }
    }

    // Reactivation dialog for failed habits
    if (showReactivationDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissReactivationDialog(renderedReactivationDialogFrame) },
            title = { Text(stringResource(R.string.dialog_reactivate_title)) },
            text = {
                Text(stringResource(if (reactivationPreservesHistory) R.string.restart_message
                    else R.string.dialog_reactivate_habit_message, reactivationHabitName))
            },
            confirmButton = {
                TextButton(enabled = reactivationHabitId !in busyHabitIds, onClick = { viewModel.confirmReactivation(renderedReactivationDialogFrame) }) {
                    Text(stringResource(R.string.dialog_reactivate_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(enabled = reactivationHabitId !in busyHabitIds, onClick = { viewModel.dismissReactivationDialog(renderedReactivationDialogFrame) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Children deletion confirmation dialog
    showChildrenDialog?.let { deleteInfo ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissChildrenDialog(renderedDeletionDialogFrame) },
            title = { Text(stringResource(R.string.dialog_delete_habit_with_children_title)) },
            text = {
                Text(stringResource(R.string.dialog_delete_habit_with_children_message, deleteInfo.habit.name, deleteInfo.childCount))
            },
            confirmButton = {
                TextButton(
                    enabled = deleteInfo.habit.id !in busyHabitIds,
                    onClick = { viewModel.deleteHabitWithChildren(renderedDeletionDialogFrame) }
                ) {
                    Text(stringResource(R.string.dialog_delete_habit_with_children), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(enabled = deleteInfo.habit.id !in busyHabitIds, onClick = { viewModel.deleteHabitKeepChildren(renderedDeletionDialogFrame) }) {
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
