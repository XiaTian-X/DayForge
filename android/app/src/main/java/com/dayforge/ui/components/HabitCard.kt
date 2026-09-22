package com.dayforge.ui.components

import android.graphics.Color.parseColor
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dayforge.R
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CardColorStyle
import com.dayforge.domain.service.CardColorResolver
import com.dayforge.domain.model.ActiveTimerState
import java.time.LocalDate

/**
 * Get icon for a given icon resource ID.
 * Uses shared getIconForResId function for consistent mapping.
 */
@Composable
private fun getIconForId(iconResId: Int): ImageVector {
    return getIconForResId(iconResId)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitCard(
    habit: HabitEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    cardColorStyle: CardColorStyle = CardColorStyle.DEFAULT,
    currentStreak: Int = 0,
    bestStreak: Int = 0,
    activityRate: Int = 100,  // 活跃度 0-100
    completed: Boolean = false,
    undoAvailable: Boolean = false,
    todayCount: Int = 0,
    onCheckIn: (Int) -> Unit = {},
    onUndo: () -> Unit = {},
    onIncrement: () -> Unit = {},
    onDecrement: () -> Unit = {},
    // Timer state and callbacks
    activeTimer: ActiveTimerState? = null,
    onTimerStart: () -> Unit = {},
    onTimerPause: () -> Unit = {},
    onTimerResume: () -> Unit = {},
    onTimerStop: () -> Unit = {},
    // Metric prompt for completed timer habits
    showMetricPrompt: Boolean = false,
    onRecordMetrics: () -> Unit = {},
    // Linked metrics
    linkedMetrics: List<LinkedMetricInfo> = emptyList(),
    onMetricClick: (Long) -> Unit = {},
    // Check-in day validation
    isCheckInAllowed: Boolean = true,
    nextCheckInDate: LocalDate? = null,
    // Target progress for habits with targetCycles
    targetProgress: Int = 0,
    // Failure status
    hasFailed: Boolean = false,
    // Goal completed status (reached targetCycles and deactivated)
    isGoalCompleted: Boolean = false,
    // Reactivation callback for failed/completed habits
    onReactivation: () -> Unit = {},
    // Slot progress for focus mode (COUNTING habit in time window)
    slotProgress: String? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Resolve card colors based on style preference
    val colorScheme = MaterialTheme.colorScheme
    val resolvedColors = CardColorResolver.resolveCardColors(
        style = cardColorStyle,
        userColorHex = habit.colorHex,
        primaryContainer = colorScheme.primaryContainer.toArgb(),
        onPrimaryContainer = colorScheme.onPrimaryContainer.toArgb(),
        onPrimary = colorScheme.onPrimary.toArgb()
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .graphicsLayer { alpha = if (habit.isActive && isCheckInAllowed) 1f else 0.5f },
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = resolvedColors.backgroundColor
        )
    ) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        modifier = Modifier.size(40.dp),
                        tint = resolvedColors.iconColor,
                        contentDescription = null,
                        imageVector = getIconForId(habit.iconResId)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = habit.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = resolvedColors.textColor,
                                modifier = Modifier.weight(1f, fill = false),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            // 失败标记（错误色）- 优先级最高，白底确保可见性
                            if (hasFailed) {
                                Spacer(modifier = Modifier.width(8.dp))
                                AssistChip(
                                    onClick = { },
                                    label = { Text(stringResource(R.string.habit_card_status_failed), fontSize = 10.sp) },
                                    modifier = Modifier.height(20.dp),
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = Color.White.copy(alpha = 0.85f),
                                        labelColor = MaterialTheme.colorScheme.error
                                    )
                                )
                            }
                            // 完成标记（主题色）- 目标已达成，白底确保可见性
                            else if (habit.targetCycles != null && targetProgress >= habit.targetCycles) {
                                Spacer(modifier = Modifier.width(8.dp))
                                AssistChip(
                                    onClick = { },
                                    label = { Text(stringResource(R.string.habit_card_status_completed), fontSize = 10.sp) },
                                    modifier = Modifier.height(20.dp),
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = Color.White.copy(alpha = 0.85f),
                                        labelColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                            }
                            // 倒计时标签（仅计时型习惯且为倒计时模式时显示）- 白底主题色字
                            if (habit.habitType == HabitType.TIMER && habit.isCountdown) {
                                Spacer(modifier = Modifier.width(8.dp))
                                AssistChip(
                                    onClick = { },
                                    label = { Text(stringResource(R.string.habit_card_status_countdown), fontSize = 10.sp) },
                                    modifier = Modifier.height(20.dp),
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = Color.White.copy(alpha = 0.85f),
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                            // 倒计数标签（仅计数型习惯且为倒计数模式时显示）- 白底主题色字
                            if (habit.habitType == HabitType.COUNTING && habit.isCountdown) {
                                Spacer(modifier = Modifier.width(8.dp))
                                AssistChip(
                                    onClick = { },
                                    label = { Text(stringResource(R.string.habit_card_status_countdown_counting), fontSize = 10.sp) },
                                    modifier = Modifier.height(20.dp),
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = Color.White.copy(alpha = 0.85f),
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                            // 目标标签（仅目标型习惯显示）- 白底主题色字
                            if (habit.habitType == HabitType.GOAL) {
                                Spacer(modifier = Modifier.width(8.dp))
                                AssistChip(
                                    onClick = { },
                                    label = { Text(stringResource(R.string.habit_card_status_goal), fontSize = 10.sp) },
                                    modifier = Modifier.height(20.dp),
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = Color.White.copy(alpha = 0.85f),
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                            // 非打卡日标签 - 白底主题色字
                            if (!isCheckInAllowed) {
                                Spacer(modifier = Modifier.width(8.dp))
                                AssistChip(
                                    onClick = { },
                                    label = { Text(stringResource(R.string.habit_card_status_non_checkin_day), fontSize = 10.sp) },
                                    modifier = Modifier.height(20.dp),
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = Color.White.copy(alpha = 0.85f),
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                            // 非活跃状态标签 - 白底主题色字
                            if (!habit.isActive) {
                                Spacer(modifier = Modifier.width(8.dp))
                                AssistChip(
                                    onClick = { },
                                    label = { Text(stringResource(R.string.habit_card_status_inactive), fontSize = 10.sp) },
                                    modifier = Modifier.height(20.dp),
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = Color.White.copy(alpha = 0.85f),
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                            // 槽进度标签（聚焦模式下COUNTING习惯在时间窗口内）
                            if (slotProgress != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                AssistChip(
                                    onClick = { },
                                    label = { Text(slotProgress, fontSize = 10.sp) },
                                    modifier = Modifier.height(20.dp),
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = Color.White.copy(alpha = 0.85f),
                                        labelColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                            }
                        }
                        Text(
                            text = habit.description.takeIf { it.isNotBlank() } ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = resolvedColors.secondaryTextColor
                        )
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.content_description_options),
                                tint = resolvedColors.iconColor
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.content_description_edit)) },
                                onClick = {
                                    showMenu = false
                                    onEdit()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMenu = false
                                    showDeleteDialog = true
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            )
                        }
                    }
                }

                val timerState = when {
                    activeTimer?.habitId == habit.id && !activeTimer.isPaused -> TimerState.RUNNING
                    activeTimer?.habitId == habit.id && activeTimer.isPaused -> TimerState.PAUSED
                    else -> TimerState.NOT_RUNNING
                }
                val isTimerActive = timerState == TimerState.RUNNING || timerState == TimerState.PAUSED

                // Progress indicator and Completion button in a single row
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // LEFT: Completion Button (if not GOAL)
                    Box(
                        modifier = if (isTimerActive) Modifier.fillMaxWidth() else Modifier.wrapContentWidth(), 
                        contentAlignment = if (isTimerActive) Alignment.Center else Alignment.CenterStart
                    ) {
                        if (habit.habitType != HabitType.GOAL) {
                            val displayCount = if (activeTimer?.habitId == habit.id) {
                                activeTimer.elapsedSeconds
                            } else {
                                todayCount
                            }
                            CompletionButton(
                                completed = completed,
                                undoAvailable = undoAvailable,
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
                    
                    if (!isTimerActive) {
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        // RIGHT: Progress Indicator
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            if (habit.targetCycles != null) {
                                TargetProgressIndicator(
                                    progress = targetProgress,
                                    target = habit.targetCycles,
                                    textColor = resolvedColors.textColor,
                                    modifier = Modifier
                                )
                            } else {
                                StreakIndicator(
                                    activityRate = activityRate,
                                    textColor = resolvedColors.textColor,
                                    modifier = Modifier
                                )
                            }
                        }
                    }
                }
                // Linked metrics section (D-12 to D-14)
                if (linkedMetrics.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinkedMetricsSection(
                        linkedMetrics = linkedMetrics,
                        onMetricClick = onMetricClick,
                        textColor = resolvedColors.textColor
                    )
                }
            }
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
