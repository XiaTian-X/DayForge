package com.dayforge.ui.screens.nested

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import com.dayforge.R
import com.dayforge.domain.model.CardColorStyle
import com.dayforge.ui.components.GoalCompletionDialog
import com.dayforge.ui.components.ParentHabitCard
import com.dayforge.ui.components.PostCheckInDialog
import com.dayforge.ui.TimerLaunchInput
import com.dayforge.ui.renderedSelection
import com.dayforge.data.local.DatabaseObjectSelection
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
    modifier: Modifier = Modifier,
    onHabitSelection: ((DatabaseObjectSelection) -> Unit)? = null,
    onEditHabitSelection: ((DatabaseObjectSelection) -> Unit)? = null,
    onMetricSelection: ((DatabaseObjectSelection) -> Unit)? = null,
    onEditGoalSelection: ((DatabaseObjectSelection) -> Unit)? = null
) {
    val topLevelHabitsWithChildren by viewModel.topLevelHabitsWithChildren.collectAsStateWithLifecycle()
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
    val pendingMetricHabits by viewModel.pendingMetricHabits.collectAsStateWithLifecycle()
    val fixedLinkedMetricsByHabit by viewModel.linkedMetricsByHabit.collectAsStateWithLifecycle()
    val linkedMetricFrameValue by viewModel.linkedMetricFrame.collectAsStateWithLifecycle()
    val renderedLinkedMetricFrame = linkedMetricFrameValue
    val linkedMetricsByHabit = if (viewModel.fixedGraphConstruction) fixedLinkedMetricsByHabit else renderedLinkedMetricFrame?.value ?: emptyMap()
    val fixedPostCheckInState by viewModel.postCheckInState.collectAsStateWithLifecycle()
    val postCheckInFrameValue by viewModel.postCheckInFrame.collectAsStateWithLifecycle()
    val renderedPostCheckInFrame = postCheckInFrameValue
    val postCheckInState = if (viewModel.fixedGraphConstruction) fixedPostCheckInState else renderedPostCheckInFrame?.value ?: null
    val expandedParentUuids by viewModel.expandedParentUuids.collectAsStateWithLifecycle()
    // Card color style for reactive card rendering (CARD-09)
    val cardColorStyle = com.dayforge.ui.theme.LocalDeviceCardStyle.current
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

    val renderedTree = topLevelHabitsWithChildren
    fun habitSelection(id: Long): DatabaseObjectSelection? =
        renderedTree.firstOrNull { it.habit.id == id }?.renderedSelection()
            ?: renderedTree.asSequence().flatMap { it.children.asSequence() }.firstOrNull { it.habit.id == id }?.renderedSelection()
    fun navigateHabit(id: Long, edit: Boolean = false, goal: Boolean = false) = com.dayforge.ui.navigateRenderedDatabaseInput(
        context, viewModel.fixedGraphConstruction, id, habitSelection(id),
        if (goal) onEditGoalClick else if (edit) onEditHabitClick else onHabitClick,
        if (goal) onEditGoalSelection else if (edit) onEditHabitSelection else onHabitSelection)


    fun metricSelection(id: Long): DatabaseObjectSelection? {
        val frame = renderedLinkedMetricFrame ?: return null
        val metric = frame.value.values.asSequence().flatMap { it.asSequence() }.firstOrNull { it.metricId == id } ?: return null
        val uuid = metric.metricUuid ?: return null
        return DatabaseObjectSelection.metric(id, uuid, frame.publication)
    }
    fun navigateMetric(id: Long) = com.dayforge.ui.navigateRenderedDatabaseInput(context,
        viewModel.fixedGraphConstruction, id, metricSelection(id), onMetricClick, onMetricSelection)

    // Get goal habit name for dialog
    val goalHabitName = remember(goalHabitId, renderedTree, renderedGoalDialogFrame) {
        goalHabitId?.let { id ->
            renderedTree
                .flatMap { it.children }
                .find { it.habit.id == id && (viewModel.fixedGraphConstruction || it.habit.uuid == renderedGoalDialogFrame?.value?.habitUuid) }
                ?.habit
                ?.name
        } ?: ""
    }

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
        if (!isGranted) {
            Toast.makeText(context, context.getString(R.string.toast_notification_permission_denied), Toast.LENGTH_SHORT).show()
        }
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
    LaunchedEffect(renderedTree, pendingTimer) {
        val original = pendingTimer
        if (original != null && !original.isCurrentFor(viewModel.fixedGraphConstruction) && pendingTimer === original) {
            pendingTimer = null; showTimerConfirmation = false; showBatteryGuidanceDialog = false
        }
    }

    // Helper function to handle timer start with all checks
    fun handleTimerStart(habitId: Long, targetMinutes: Int) {
        val currentTimer = activeTimer

        // Check if there's an active timer for a different habit
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
    }

    CompositionLocalProvider(com.dayforge.ui.LocalInFlightHabitIds provides busyHabitIds,
        com.dayforge.ui.LocalCountButtonModels provides countButtons) {
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
        } else if (readError) {
            com.dayforge.ui.components.DataReadFailure(viewModel::retryRead,
                Modifier.fillMaxSize().padding(padding))
        } else if (renderedTree.isEmpty()) {
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
                        items = renderedTree,
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
                            onClick = { navigateHabit(parentWithChildren.habit.id, goal = true) },
                            onExpandToggle = {
                                viewModel.toggleParentExpand(parentWithChildren.habit.uuid, parentWithChildren.renderedSelection())  // Use existing viewModel
                            },
                            onChildCheckIn = { habitId, value ->
                                viewModel.logCompletion(habitId, value, selection = habitSelection(habitId))
                            },
                            onChildUndo = { habitId ->
                                val child = parentWithChildren.children.find { it.habit.id == habitId }
                                if (child != null && child.lastCompletionId != null) {
                                    viewModel.undoCompletion(child.lastCompletionId, selection = child.renderedSelection())
                                }
                            },
                            onChildIncrement = { habitId ->
                                viewModel.incrementCount(habitId, selection = habitSelection(habitId))
                            },
                            onChildDecrement = { habitId ->
                                val child = parentWithChildren.children.find { it.habit.id == habitId }
                                if (child != null && child.displayIsCountdown) {
                                    viewModel.incrementCount(habitId, selection = habitSelection(habitId))
                                } else {
                                    viewModel.decrementCount(habitId, selection = habitSelection(habitId))
                                }
                            },
                            onChildTimerStart = { habitId, targetMinutes ->
                                handleTimerStart(habitId, targetMinutes)
                            },
                            onChildTimerPause = { viewModel.pauseTimer(renderedActiveTimerFrame) },
                            onChildTimerResume = { viewModel.resumeTimer(renderedActiveTimerFrame) },
                            onChildTimerStop = { viewModel.stopTimer(renderedActiveTimerFrame) },
                            activeTimer = activeTimer,
                            pendingMetricHabits = pendingMetricHabits,
                            linkedMetricsByHabit = linkedMetricsByHabit,
                            onMetricClick = { metricId -> navigateMetric(metricId) },
                            onRecordMetrics = { habitId ->
                                scope.launch {
                                    val habit = parentWithChildren.children.find { it.habit.id == habitId }?.habit
                                    if (habit != null) {
                                        viewModel.checkAndShowPostCheckInDialog(habitId, habit.name, habitSelection(habitId))
                                    }
                                }
                            },
                            onChildReactivation = { habitId ->
                                viewModel.showReactivationDialog(habitId, selection = habitSelection(habitId))
                            },
                            onChildClick = { habitId -> navigateHabit(habitId) },
                            onChildEdit = { habitId -> navigateHabit(habitId, edit = true) },
                            onChildDelete = { habitId ->
                                val child = parentWithChildren.children.find { it.habit.id == habitId }
                                if (child != null) {
                                    viewModel.deleteHabit(child.habit, selection = child.renderedSelection())
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
                        items = renderedTree,
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
                            onClick = { navigateHabit(parentWithChildren.habit.id, goal = true) },
                            onExpandToggle = {
                                viewModel.toggleParentExpand(parentWithChildren.habit.uuid, parentWithChildren.renderedSelection())
                            },
                            onChildCheckIn = { habitId, value ->
                                viewModel.logCompletion(habitId, value, selection = habitSelection(habitId))
                            },
                            onChildUndo = { habitId ->
                                val child = parentWithChildren.children.find { it.habit.id == habitId }
                                if (child != null && child.lastCompletionId != null) {
                                    viewModel.undoCompletion(child.lastCompletionId, selection = child.renderedSelection())
                                }
                            },
                            onChildIncrement = { habitId ->
                                viewModel.incrementCount(habitId, selection = habitSelection(habitId))
                            },
                            onChildDecrement = { habitId ->
                                val child = parentWithChildren.children.find { it.habit.id == habitId }
                                if (child != null && child.displayIsCountdown) {
                                    viewModel.incrementCount(habitId, selection = habitSelection(habitId))
                                } else {
                                    viewModel.decrementCount(habitId, selection = habitSelection(habitId))
                                }
                            },
                            onChildTimerStart = { habitId, targetMinutes ->
                                handleTimerStart(habitId, targetMinutes)
                            },
                            onChildTimerPause = {
                                viewModel.pauseTimer(renderedActiveTimerFrame)
                            },
                            onChildTimerResume = {
                                viewModel.resumeTimer(renderedActiveTimerFrame)
                            },
                            onChildTimerStop = {
                                viewModel.stopTimer(renderedActiveTimerFrame)
                            },
                            activeTimer = activeTimer,
                            pendingMetricHabits = pendingMetricHabits,
                            linkedMetricsByHabit = linkedMetricsByHabit,
                            onMetricClick = { metricId -> navigateMetric(metricId) },
                            onRecordMetrics = { habitId ->
                                scope.launch {
                                    val habit = parentWithChildren.children.find { it.habit.id == habitId }?.habit
                                    if (habit != null) {
                                        viewModel.checkAndShowPostCheckInDialog(habitId, habit.name, habitSelection(habitId))
                                    }
                                }
                            },
                            onChildReactivation = { habitId ->
                                viewModel.showReactivationDialog(habitId, selection = habitSelection(habitId))
                            },
                            onChildClick = { habitId ->
                                navigateHabit(habitId)
                            },
                            onChildEdit = { habitId ->
                                navigateHabit(habitId, edit = true)
                            },
                            onChildDelete = { habitId ->
                                val child = parentWithChildren.children.find { it.habit.id == habitId }
                                if (child != null) {
                                    viewModel.deleteHabit(child.habit, selection = child.renderedSelection())
                                }
                            },
                            modifier = Modifier
                        )
                    }
                }
            }
        }
    }

    }
    // Battery optimization guidance dialog
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
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = "package:${context.packageName}".toUri()
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = "package:${context.packageName}".toUri()
                                }
                                context.startActivity(fallbackIntent)
                            } catch (e2: Exception) {
                                Toast.makeText(context, context.getString(R.string.toast_cannot_open_settings), Toast.LENGTH_SHORT).show()
                            }
                        }
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
                        continuePendingTimer()
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
            onDismissRequest = { if (pendingTimer === renderedPendingTimer) { showTimerConfirmation = false; pendingTimer = null } },
            title = { Text(stringResource(R.string.dialog_timer_switch_title)) },
            text = { Text(stringResource(R.string.dialog_timer_switch_fallback)) },
            confirmButton = {
                TextButton(
                    onClick = timerAction@{
                        if (pendingTimer !== renderedPendingTimer || renderedPendingTimer?.isCurrentFor(viewModel.fixedGraphConstruction) != true) { rejectTimerInput(); return@timerAction }
                        showTimerConfirmation = false
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
                        } catch (error: Exception) {
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            Toast.makeText(context, com.dayforge.ui.actionFailureMessage(context, error), Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onDismiss = {
                    try { viewModel.dismissPostCheckInDialog(state, renderedPostCheckInFrame) }
                    catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        Toast.makeText(context, com.dayforge.ui.actionFailureMessage(context, error), Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
    }

    // Goal completion dialog for habits that reached targetCycles
    if (showGoalDialog) {
        GoalCompletionDialog(
            busy = goalHabitId in busyHabitIds,
            habitName = goalHabitName,
            progress = goalProgress,
            target = goalTarget,
            onConfirm = { viewModel.confirmGoalCompletion(renderedGoalDialogFrame) },
            onDismiss = { viewModel.dismissGoalDialog(renderedGoalDialogFrame) }
        )
    }

    // Reactivation dialog for failed habits
    if (showReactivationDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissReactivationDialog(renderedReactivationDialogFrame) },
            title = { Text(stringResource(R.string.dialog_reactivate_confirm)) },
            text = {
                Text(stringResource(if (reactivationPreservesHistory) R.string.restart_message
                    else R.string.nested_reactivation_message, reactivationHabitName))
            },
            confirmButton = {
                TextButton(enabled = reactivationHabitId !in busyHabitIds, onClick = { viewModel.confirmReactivation(renderedReactivationDialogFrame) }) {
                    Text(stringResource(R.string.action_confirm_activate), color = MaterialTheme.colorScheme.error)
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
