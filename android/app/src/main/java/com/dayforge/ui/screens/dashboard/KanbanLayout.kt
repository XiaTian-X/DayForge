package com.dayforge.ui.screens.dashboard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dayforge.R
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.data.model.HabitWithStats
import com.dayforge.domain.model.ActiveTimerState
import com.dayforge.domain.model.CardColorStyle
import com.dayforge.ui.components.LinkedMetricInfo

/**
 * Four-column Kanban layout for tablet (>=600dp).
 * KANBAN-01, KANBAN-02, KANBAN-10: Row + horizontalScroll + 4 fixed-width columns.
 *
 * Column filtering logic (per KANBAN-03~06):
 * - 待完成 (pending): CHECKABLE semantics - !completedToday && isCheckInAllowed && !hasFailed && !isGoalCompleted && habitType != GOAL && isActive
 * - 已完成 (completed): completedToday == true
 * - 已结束 (terminated): hasFailed || isGoalCompleted
 * - 全部 (all): all habits including inactive
 */
@Composable
fun KanbanLayout(
    habitsWithStats: List<HabitWithStats>,
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
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    // KANBAN-03: 待完成 column - CHECKABLE semantics
    val pendingHabits = habitsWithStats.filter { hws ->
        !hws.completedToday &&
        hws.isCheckInAllowed &&
        hws.habit.isActive &&
        !hws.hasFailed &&
        !hws.isGoalCompleted &&
        hws.habit.habitType != HabitType.GOAL
    }

    // KANBAN-04: 已完成 column - completedToday true
    val completedHabits = habitsWithStats.filter { hws ->
        hws.completedToday
    }

    // KANBAN-05: 已结束 column - failed or goal completed
    val terminatedHabits = habitsWithStats.filter { hws ->
        hws.hasFailed || hws.isGoalCompleted
    }

    // KANBAN-06: 全部 column - all habits
    val allHabits = habitsWithStats

    Row(
        modifier = modifier
            .fillMaxSize()
            .horizontalScroll(scrollState),  // KANBAN-10: Horizontal scroll
        horizontalArrangement = Arrangement.spacedBy(8.dp)  // CONTEXT.md: 8dp gap
    ) {
        // 待完成 column
        KanbanColumn(
            title = stringResource(R.string.kanban_column_pending),
            habits = pendingHabits,
            activeTimer = activeTimer,
            cardColorStyle = cardColorStyle,
            linkedMetricsByHabit = linkedMetricsByHabit,
            pendingMetricHabits = pendingMetricHabits,
            onHabitClick = onHabitClick,
            onDelete = onDelete,
            onEdit = onEdit,
            onCheckIn = onCheckIn,
            onUndo = onUndo,
            onIncrement = onIncrement,
            onDecrement = onDecrement,
            onTimerStart = onTimerStart,
            onTimerPause = onTimerPause,
            onTimerResume = onTimerResume,
            onTimerStop = onTimerStop,
            onRecordMetrics = onRecordMetrics,
            onMetricClick = onMetricClick,
            onReactivation = onReactivation,
            emptyTitleResId = R.string.dashboard_empty_checkable,
            emptyHintResId = R.string.dashboard_empty_checkable_hint
        )

        // 已完成 column
        KanbanColumn(
            title = stringResource(R.string.kanban_column_completed),
            habits = completedHabits,
            activeTimer = activeTimer,
            cardColorStyle = cardColorStyle,
            linkedMetricsByHabit = linkedMetricsByHabit,
            pendingMetricHabits = pendingMetricHabits,
            onHabitClick = onHabitClick,
            onDelete = onDelete,
            onEdit = onEdit,
            onCheckIn = onCheckIn,
            onUndo = onUndo,
            onIncrement = onIncrement,
            onDecrement = onDecrement,
            onTimerStart = onTimerStart,
            onTimerPause = onTimerPause,
            onTimerResume = onTimerResume,
            onTimerStop = onTimerStop,
            onRecordMetrics = onRecordMetrics,
            onMetricClick = onMetricClick,
            onReactivation = onReactivation,
            emptyTitleResId = R.string.dashboard_empty_completed,
            emptyHintResId = R.string.dashboard_empty_completed_hint
        )

        // 已结束 column (reuse filter_mode_terminated string)
        KanbanColumn(
            title = stringResource(R.string.filter_mode_terminated),
            habits = terminatedHabits,
            activeTimer = activeTimer,
            cardColorStyle = cardColorStyle,
            linkedMetricsByHabit = linkedMetricsByHabit,
            pendingMetricHabits = pendingMetricHabits,
            onHabitClick = onHabitClick,
            onDelete = onDelete,
            onEdit = onEdit,
            onCheckIn = onCheckIn,
            onUndo = onUndo,
            onIncrement = onIncrement,
            onDecrement = onDecrement,
            onTimerStart = onTimerStart,
            onTimerPause = onTimerPause,
            onTimerResume = onTimerResume,
            onTimerStop = onTimerStop,
            onRecordMetrics = onRecordMetrics,
            onMetricClick = onMetricClick,
            onReactivation = onReactivation,
            emptyTitleResId = R.string.dashboard_empty_terminated,
            emptyHintResId = R.string.dashboard_empty_terminated_hint
        )

        // 全部 column (reuse filter_mode_all string)
        KanbanColumn(
            title = stringResource(R.string.filter_mode_all),
            habits = allHabits,
            activeTimer = activeTimer,
            cardColorStyle = cardColorStyle,
            linkedMetricsByHabit = linkedMetricsByHabit,
            pendingMetricHabits = pendingMetricHabits,
            onHabitClick = onHabitClick,
            onDelete = onDelete,
            onEdit = onEdit,
            onCheckIn = onCheckIn,
            onUndo = onUndo,
            onIncrement = onIncrement,
            onDecrement = onDecrement,
            onTimerStart = onTimerStart,
            onTimerPause = onTimerPause,
            onTimerResume = onTimerResume,
            onTimerStop = onTimerStop,
            onRecordMetrics = onRecordMetrics,
            onMetricClick = onMetricClick,
            onReactivation = onReactivation,
            emptyTitleResId = R.string.dashboard_empty_all,
            emptyHintResId = R.string.dashboard_empty_all_hint
        )
    }
}
