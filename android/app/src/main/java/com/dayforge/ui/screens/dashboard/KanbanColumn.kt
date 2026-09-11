package com.dayforge.ui.screens.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dayforge.R
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitWithStats
import com.dayforge.domain.model.ActiveTimerState
import com.dayforge.domain.model.CardColorStyle
import com.dayforge.ui.components.HabitCard
import com.dayforge.ui.components.LinkedMetricInfo

/**
 * Single Kanban column with header, habit items, and empty state.
 * KANBAN-02, KANBAN-09, KANBAN-10: Fixed 420dp width for comfortable card display.
 */
@Composable
fun KanbanColumn(
    title: String,
    habits: List<HabitWithStats>,
    width: Dp = 420.dp,  // KANBAN-10: Fixed width (increased for better card display)
    activeTimer: ActiveTimerState?,
    cardColorStyle: CardColorStyle,
    linkedMetricsByHabit: Map<Long, List<LinkedMetricInfo>>,
    pendingMetricHabits: Set<Long>,
    onHabitClick: (Long) -> Unit,
    onDelete: (HabitEntity) -> Unit,
    onEdit: (Long) -> Unit,
    onCheckIn: (Long) -> Unit,
    onUndo: (Long, Long?) -> Unit,
    onIncrement: (Long) -> Unit,
    onDecrement: (Long) -> Unit,
    onTimerStart: (Long, Int) -> Unit,
    onTimerPause: () -> Unit,
    onTimerResume: () -> Unit,
    onTimerStop: () -> Unit,
    onRecordMetrics: (Long) -> Unit,
    onMetricClick: (Long) -> Unit,
    onReactivation: (Long) -> Unit,
    emptyTitleResId: Int,
    emptyHintResId: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.width(width),  // KANBAN-10: Fixed width per column
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            // Column header with subtitle style (CONTEXT.md decision)
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)  // KANBAN-10: 16dp internal padding
            )

            if (habits.isEmpty()) {
                // Empty state per UI-SPEC.md
                KanbanEmptyState(
                    titleResId = emptyTitleResId,
                    hintResId = emptyHintResId
                )
            } else {
                // Habit items - LazyColumn fills remaining height
                LazyColumn(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(habits) { habitWithStats ->
                        val habit = habitWithStats.habit

                        // KANBAN-09: HabitCard unchanged, all existing parameters passed
                        HabitCard(
                            habit = habit,
                            cardColorStyle = cardColorStyle,
                            onClick = { onHabitClick(habit.id) },
                            onDelete = { onDelete(habit) },
                            onEdit = { onEdit(habit.id) },
                            currentStreak = habitWithStats.currentStreak,
                            bestStreak = habitWithStats.bestStreak,
                            activityRate = habitWithStats.activityRate,
                            completed = habitWithStats.completedToday,
                            undoAvailable = habitWithStats.lastCompletionId != null,
                            todayCount = habitWithStats.todayCount,
                            onCheckIn = { onCheckIn(habit.id) },
                            onUndo = { onUndo(habit.id, habitWithStats.lastCompletionId) },
                            onIncrement = { onIncrement(habit.id) },
                            onDecrement = { onDecrement(habit.id) },
                            // Timer state - KANBAN-08: stays in original column
                            activeTimer = activeTimer,
                            onTimerStart = { onTimerStart(habit.id, habit.targetValue) },
                            onTimerPause = onTimerPause,
                            onTimerResume = onTimerResume,
                            onTimerStop = onTimerStop,
                            // Metric prompt
                            showMetricPrompt = pendingMetricHabits.contains(habit.id),
                            onRecordMetrics = { onRecordMetrics(habit.id) },
                            // Linked metrics
                            linkedMetrics = linkedMetricsByHabit[habit.id] ?: emptyList(),
                            onMetricClick = onMetricClick,
                            // Check-in validation
                            isCheckInAllowed = habitWithStats.isCheckInAllowed,
                            nextCheckInDate = habitWithStats.nextCheckInDate,
                            // Target progress
                            targetProgress = habitWithStats.targetProgress,
                            // Failure/goal status
                            hasFailed = habitWithStats.hasFailed,
                            isGoalCompleted = habitWithStats.isGoalCompleted,
                            // Reactivation
                            onReactivation = { onReactivation(habit.id) },
                            // Slot progress
                            slotProgress = habitWithStats.slotProgress,
                            // Reduced padding for Kanban column cards
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Empty state for Kanban column.
 * Shows localized message with subtle icon per CONTEXT.md.
 */
@Composable
fun KanbanEmptyState(
    titleResId: Int,
    hintResId: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),  // UI-SPEC.md: xl spacing
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Rounded.Inbox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(titleResId),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(hintResId),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}
