package com.dayforge.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.dayforge.R
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CardColorStyle
import com.dayforge.domain.service.CardColorResolver
import com.dayforge.domain.model.ActiveTimerState
import com.dayforge.ui.screens.nested.ChildHabitWithStats
import java.time.LocalDate

/**
 * Get icon for a given icon resource ID.
 * Uses shared getIconForResId function for consistent mapping.
 */
@Composable
private fun getIconForId(iconResId: Int): ImageVector {
    return getIconForResId(iconResId)
}

/**
 * ChildHabitRow component for displaying child habits in nested view.
 *
 * Per NEST-04: Nested view supports check-in for child habits.
 * Uses existing CompletionButton component for all habit types:
 * - CHECK_IN: "打卡" button, completed shows "已完成"
 * - COUNTING: Progress display
 * - TIMER: Start/Pause/Stop controls
 *
 * Layout structure (compact, 2 rows):
 * - Row 1: Icon + Name + Type labels + Menu button (fixed right)
 * - Row 2: Check-in button (left aligned)
 * - Progress bar at bottom edge (handled by ParentHabitCard)
 *
 * @param childHabit Child habit with stats
 * @param onClick Callback when habit is clicked (for detail screen)
 * @param onCheckIn Callback for check-in (value) - used for CHECK_IN habits
 * @param onUndo Callback for undo
 * @param onIncrement Callback for increment (counting habits) - increases count
 * @param onDecrement Callback for decrement (counting habits) - decreases count
 * @param onTimerStart Callback to start timer
 * @param onTimerPause Callback to pause timer
 * @param onTimerResume Callback to resume timer
 * @param onTimerStop Callback to stop timer
 * @param activeTimer Current active timer state (for timer habits)
 * @param showMetricPrompt Whether to show metric record button (for completed timer habits)
 * @param onRecordMetrics Callback to record metrics
 * @param linkedMetrics List of linked metrics for this habit
 * @param onMetricClick Callback when a metric is clicked (navigates to metric detail)
 * @param onEdit Callback when edit is requested
 * @param onDelete Callback when delete is confirmed
 * @param modifier Modifier for custom styling
 */
@Composable
fun ChildHabitRow(
    childHabit: ChildHabitWithStats,
    onClick: () -> Unit = {},
    onCheckIn: (Int) -> Unit,
    onUndo: () -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onTimerStart: () -> Unit,
    onTimerPause: () -> Unit,
    onTimerResume: () -> Unit,
    onTimerStop: () -> Unit,
    activeTimer: ActiveTimerState?,
    showMetricPrompt: Boolean = false,
    onRecordMetrics: () -> Unit = {},
    linkedMetrics: List<LinkedMetricInfo> = emptyList(),
    onMetricClick: (Long) -> Unit = {},
    // Check-in day validation
    isCheckInAllowed: Boolean = true,
    nextCheckInDate: LocalDate? = null,
    // Failure status
    hasFailed: Boolean = false,
    // Goal completed status (reached targetCycles and deactivated)
    isGoalCompleted: Boolean = false,
    // Reactivation callback for failed/completed habits
    onReactivation: () -> Unit = {},
    // Edit and delete callbacks
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    cardColorStyle: CardColorStyle = CardColorStyle.DEFAULT,
    modifier: Modifier = Modifier
) {
    val habit = childHabit.habit

    // Resolve card colors based on style preference
    val colorScheme = MaterialTheme.colorScheme
    val resolvedColors = CardColorResolver.resolveCardColors(
        style = cardColorStyle,
        userColorHex = habit.colorHex,
        primaryContainer = colorScheme.primaryContainer.toArgb(),
        onPrimaryContainer = colorScheme.onPrimaryContainer.toArgb(),
        onPrimary = colorScheme.onPrimary.toArgb()
    )

    // Menu state
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Calculate timer state for this child habit
    val timerState = when {
        activeTimer?.habitId == habit.id && !activeTimer.isPaused -> TimerState.RUNNING
        activeTimer?.habitId == habit.id && activeTimer.isPaused -> TimerState.PAUSED
        else -> TimerState.NOT_RUNNING
    }

    // For active timer, use elapsedSeconds; otherwise use todayCount (accumulated)
    val displayCount = if (activeTimer?.habitId == habit.id) {
        activeTimer.elapsedSeconds
    } else {
        childHabit.todayCount
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .graphicsLayer { alpha = if (childHabit.habit.isActive && isCheckInAllowed) 1f else 0.5f }
    ) {
        // Row 1: Icon + Name + Type labels + Menu button (menu fixed right)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon + Name + Type labels (takes remaining space)
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Child habit icon (16dp)
                Icon(
                    modifier = Modifier.size(16.dp),
                    tint = resolvedColors.iconColor,
                    contentDescription = null,
                    imageVector = getIconForId(habit.iconResId)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Habit name
                Text(
                    text = habit.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = resolvedColors.textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp
                )

                // 倒计时标签（仅计时型习惯且为倒计时模式时显示）
                if (habit.habitType == HabitType.TIMER && habit.isCountdown) {
                    Spacer(modifier = Modifier.width(6.dp))
                    AssistChip(
                        onClick = { },
                        label = { Text(stringResource(R.string.habit_card_status_countdown), fontSize = 9.sp) },
                        modifier = Modifier.height(18.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = resolvedColors.textColor.copy(alpha = 0.2f),
                            labelColor = resolvedColors.textColor
                        )
                    )
                }

                // 倒计数标签（仅计数型习惯且为倒计数模式时显示）
                if (habit.habitType == HabitType.COUNTING && habit.isCountdown) {
                    Spacer(modifier = Modifier.width(6.dp))
                    AssistChip(
                        onClick = { },
                        label = { Text(stringResource(R.string.habit_card_status_countdown_counting), fontSize = 9.sp) },
                        modifier = Modifier.height(18.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = resolvedColors.textColor.copy(alpha = 0.2f),
                            labelColor = resolvedColors.textColor
                        )
                    )
                }

                // 目标标签（仅目标型习惯显示）
                if (habit.habitType == HabitType.GOAL) {
                    Spacer(modifier = Modifier.width(6.dp))
                    AssistChip(
                        onClick = { },
                        label = { Text(stringResource(R.string.habit_card_status_goal), fontSize = 9.sp) },
                        modifier = Modifier.height(18.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = resolvedColors.textColor.copy(alpha = 0.2f),
                            labelColor = resolvedColors.textColor
                        )
                    )
                }

                // 非打卡日标签（第一行，名称后面）
                if (!isCheckInAllowed) {
                    Spacer(modifier = Modifier.width(6.dp))
                    AssistChip(
                        onClick = { },
                        label = { Text(stringResource(R.string.habit_card_no_checkin_today_chip), fontSize = 9.sp) },
                        modifier = Modifier.height(18.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = resolvedColors.textColor.copy(alpha = 0.2f),
                            labelColor = resolvedColors.textColor
                        )
                    )
                }
            }

            // Menu button for edit/delete
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.child_habit_more_options),
                        tint = resolvedColors.iconColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.child_habit_edit)) },
                        onClick = {
                            showMenu = false
                            onEdit()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.child_habit_delete), color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            showDeleteDialog = true
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    )
                }
            }
        }

        // Row 3: Check-in button (left aligned, compact)
        // GOAL 类型不需要打卡按钮
        if (habit.habitType != HabitType.GOAL) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // CompletionButton handles all states including failed/completed/non-checkin-day
                CompletionButton(
                    completed = childHabit.completedToday,
                    undoAvailable = childHabit.completedToday && childHabit.lastCompletionId != null,
                    habitType = habit.habitType,
                    targetValue = habit.targetValue,
                    currentCount = displayCount,
                    timerState = timerState,
                    isCountdown = habit.isCountdown,
                    onCheckIn = onCheckIn,
                    onUndo = onUndo,
                    onIncrement = onIncrement,
                    onDecrement = onDecrement,
                    onTimerStart = onTimerStart,
                    onTimerPause = onTimerPause,
                    onTimerResume = onTimerResume,
                    onTimerStop = onTimerStop,
                    showMetricPrompt = showMetricPrompt,
                    onRecordMetrics = onRecordMetrics,
                    isCheckInAllowed = isCheckInAllowed,
                    nextCheckInDate = nextCheckInDate,
                    hasFailed = hasFailed,
                    isGoalCompleted = isGoalCompleted,
                    onReactivation = onReactivation,
                    textColor = resolvedColors.textColor,
                    modifier = Modifier
                )
            }
        }

        // Linked metrics section (if any)
        if (linkedMetrics.isNotEmpty()) {
            LinkedMetricsSection(
                linkedMetrics = linkedMetrics,
                onMetricClick = onMetricClick,
                textColor = resolvedColors.textColor,
                textSize = 10.sp
            )
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        DeleteConfirmationDialog(
            habitName = habit.name,
            onConfirm = {
                showDeleteDialog = false
                onDelete()
            },
            onDismiss = { showDeleteDialog = false }
        )
    }
}
