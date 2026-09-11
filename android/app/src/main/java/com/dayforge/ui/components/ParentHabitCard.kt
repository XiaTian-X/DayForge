package com.dayforge.ui.components

import android.graphics.Color.parseColor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dayforge.R
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CardColorStyle
import com.dayforge.domain.service.CardColorResolver
import com.dayforge.ui.screens.dashboard.ActiveTimerState
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
 * ParentHabitCard component for displaying parent habits with expandable child lists.
 *
 * Per NEST-02: Parent habits displayed with child lists in tree structure.
 * Per NEST-03: Expand/collapse child lists with smooth animation.
 * Per NEST-05: Aggregated completion progress displayed on parent cards.
 *
 * Layout structure:
 * - Card with colored background (habit.colorHex)
 * - Row: Icon (40dp), Column(Name + "Parent" chip), Expand/Collapse IconButton
 * - Spacer
 * - Row: "X/Y completed" text, LinearProgressIndicator
 * - Spacer
 * - AnimatedVisibility(isExpanded): Column of ChildHabitRow items
 *
 * @param parentHabit The parent habit entity
 * @param children List of child habits with their stats
 * @param completedChildren Count of children completed today
 * @param totalChildren Total count of children
 * @param isExpanded Whether the children list is expanded
 * @param onClick Callback when card is clicked (for editing)
 * @param onExpandToggle Callback to toggle expand/collapse state
 * @param onChildCheckIn Callback when child habit check-in (habitId, value)
 * @param onChildUndo Callback when child habit undo (habitId)
 * @param onChildIncrement Callback when child counting habit increment (habitId)
 * @param onChildDecrement Callback when child counting habit decrement (habitId)
 * @param onChildTimerStart Callback when child timer starts (habitId, targetMinutes)
 * @param onChildTimerPause Callback when child timer pauses
 * @param onChildTimerResume Callback when child timer resumes
 * @param onChildTimerStop Callback when child timer stops
 * @param onChildReactivation Callback when child habit reactivation is requested
 * @param onChildClick Callback when child habit card is clicked (for detail screen)
 * @param onChildEdit Callback when child habit edit is requested from menu
 * @param onChildDelete Callback when child habit delete is confirmed
 * @param activeTimer Current active timer state for timer habits
 * @param pendingMetricHabits Set of habit IDs with pending metric recording
 * @param linkedMetricsByHabit Map of habit ID to linked metrics
 * @param onMetricClick Callback when a metric is clicked
 * @param onRecordMetrics Callback to record metrics for a habit
 * @param modifier Modifier for custom styling
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentHabitCard(
    parentHabit: HabitEntity,
    children: List<ChildHabitWithStats>,
    completedChildren: Int,
    totalChildren: Int,
    totalChildrenIncludingNonCheckInDays: Int = 0,  // All children including non-check-in-day ones
    isCheckInAllowed: Boolean = true,
    nextCheckInDate: LocalDate? = null,
    dayProgress: Int = 0,  // Days since creation (for GOAL type display)
    isExpanded: Boolean,
    onClick: () -> Unit = {},
    onExpandToggle: () -> Unit,
    onChildCheckIn: (Long, Int) -> Unit,
    onChildUndo: (Long) -> Unit,
    onChildIncrement: (Long) -> Unit,
    onChildDecrement: (Long) -> Unit,
    onChildTimerStart: (Long, Int) -> Unit,
    onChildTimerPause: () -> Unit,
    onChildTimerResume: () -> Unit,
    onChildTimerStop: () -> Unit,
    onChildReactivation: (Long) -> Unit = {},
    onChildClick: (Long) -> Unit = {},
    onChildEdit: (Long) -> Unit = {},
    onChildDelete: (Long) -> Unit = {},
    activeTimer: ActiveTimerState?,
    pendingMetricHabits: Set<Long> = emptySet(),
    linkedMetricsByHabit: Map<Long, List<LinkedMetricInfo>> = emptyMap(),
    onMetricClick: (Long) -> Unit = {},
    onRecordMetrics: (Long) -> Unit = {},
    cardColorStyle: CardColorStyle = CardColorStyle.DEFAULT,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Resolve card colors based on style preference
        val colorScheme = MaterialTheme.colorScheme
        val resolvedColors = CardColorResolver.resolveCardColors(
            style = cardColorStyle,
            userColorHex = parentHabit.colorHex,
            primaryContainer = colorScheme.primaryContainer.toArgb(),
            onPrimaryContainer = colorScheme.onPrimaryContainer.toArgb(),
            onPrimary = colorScheme.onPrimary.toArgb()
        )

        // Parent habit card with background color
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = if (parentHabit.isActive && isCheckInAllowed) 1f else 0.5f },
            onClick = onClick,
            colors = CardDefaults.cardColors(
                containerColor = resolvedColors.backgroundColor
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Row: Icon + Name/Description + Expand/Collapse button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Parent habit icon (40dp)
                    Icon(
                        modifier = Modifier.size(40.dp),
                        tint = resolvedColors.iconColor,
                        contentDescription = null,
                        imageVector = getIconForId(parentHabit.iconResId)
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    // Name and description column
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = parentHabit.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = resolvedColors.textColor,
                                modifier = Modifier.weight(1f, fill = false),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            // GOAL type label (目标标识)
                            if (parentHabit.habitType == HabitType.GOAL) {
                                Spacer(modifier = Modifier.width(8.dp))
                                AssistChip(
                                    onClick = { },
                                    label = { Text(stringResource(R.string.habit_card_status_goal), fontSize = 10.sp) },
                                    modifier = Modifier.height(20.dp),
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = resolvedColors.textColor.copy(alpha = 0.2f),
                                        labelColor = resolvedColors.textColor
                                    )
                                )
                            }

                            // "Parent" chip if has children (show for non-GOAL types)
                            if (totalChildren > 0 && parentHabit.habitType != HabitType.GOAL) {
                                Spacer(modifier = Modifier.width(8.dp))
                                AssistChip(
                                    onClick = { },
                                    label = { Text(stringResource(R.string.habit_card_parent), fontSize = 10.sp) },
                                    modifier = Modifier.height(20.dp),
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = resolvedColors.textColor.copy(alpha = 0.2f),
                                        labelColor = resolvedColors.textColor
                                    )
                                )
                            }

                            // Non-active status chip
                            if (!parentHabit.isActive) {
                                Spacer(modifier = Modifier.width(8.dp))
                                AssistChip(
                                    onClick = { },
                                    label = { Text(stringResource(R.string.habit_card_status_inactive), fontSize = 10.sp) },
                                    modifier = Modifier.height(20.dp),
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = resolvedColors.textColor.copy(alpha = 0.2f),
                                        labelColor = resolvedColors.textColor
                                    )
                                )
                            }

                            // GOAL success/failure status chip
                            if (parentHabit.habitType == HabitType.GOAL) {
                                when (parentHabit.goalSuccess) {
                                    true -> {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        AssistChip(
                                            onClick = { },
                                            label = { Text(stringResource(R.string.habit_card_status_success), fontSize = 10.sp) },
                                            modifier = Modifier.height(20.dp),
                                            colors = AssistChipDefaults.assistChipColors(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                labelColor = MaterialTheme.colorScheme.onPrimary
                                            )
                                        )
                                    }
                                    false -> {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        AssistChip(
                                            onClick = { },
                                            label = { Text(stringResource(R.string.habit_card_status_failed), fontSize = 10.sp) },
                                            modifier = Modifier.height(20.dp),
                                            colors = AssistChipDefaults.assistChipColors(
                                                containerColor = MaterialTheme.colorScheme.error,
                                                labelColor = MaterialTheme.colorScheme.onError
                                            )
                                        )
                                    }
                                    null -> {} // No chip for ongoing goals
                                }
                            }
                        }

                        // Description (optional)
                        if (parentHabit.description.isNotBlank()) {
                            Text(
                                text = parentHabit.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = resolvedColors.secondaryTextColor
                            )
                        }
                    }

                    // Expand/Collapse icon button
                    IconButton(onClick = onExpandToggle) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) stringResource(R.string.content_description_collapse) else stringResource(R.string.content_description_expand),
                            tint = resolvedColors.iconColor
                        )
                    }
                }

                // Spacer
                Spacer(modifier = Modifier.height(12.dp))

                // Progress row: For GOAL type show "第 X 天 / 共 Y 天", otherwise show "X/Y completed"
                if (parentHabit.habitType == HabitType.GOAL && parentHabit.targetCycles != null) {
                    // GOAL type: show day progress
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.component_goal_day_progress, dayProgress, parentHabit.targetCycles),
                            style = MaterialTheme.typography.bodyMedium,
                            color = resolvedColors.textColor
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        LinearProgressIndicator(
                            progress = { dayProgress.toFloat() / parentHabit.targetCycles.toFloat() },
                            modifier = Modifier
                                .weight(1f)
                                .height(8.dp),
                            color = resolvedColors.textColor,
                            trackColor = resolvedColors.textColor.copy(alpha = 0.3f),
                        )
                    }
                } else if (totalChildren > 0) {
                    // Non-GOAL type: show child completion progress (only check-in-day habits)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.component_children_completed, completedChildren, totalChildren),
                            style = MaterialTheme.typography.bodyMedium,
                            color = resolvedColors.textColor
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        LinearProgressIndicator(
                            progress = { completedChildren.toFloat() / totalChildren.toFloat() },
                            modifier = Modifier
                                .weight(1f)
                                .height(8.dp),
                            color = resolvedColors.textColor,
                            trackColor = resolvedColors.textColor.copy(alpha = 0.3f),
                        )
                    }
                } else if (totalChildrenIncludingNonCheckInDays > 0) {
                    // Has children but none are check-in-day habits today
                    AssistChip(
                        onClick = { },
                        label = { Text(stringResource(R.string.habit_card_no_checkin_today_chip), fontSize = 12.sp) },
                        modifier = Modifier.height(24.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = resolvedColors.textColor.copy(alpha = 0.2f),
                            labelColor = resolvedColors.textColor
                        )
                    )
                } else {
                    // No children chip
                    AssistChip(
                        onClick = { },
                        label = { Text(stringResource(R.string.habit_card_no_children), fontSize = 12.sp) },
                        modifier = Modifier.height(24.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = resolvedColors.textColor.copy(alpha = 0.2f),
                            labelColor = resolvedColors.textColor
                        )
                    )
                }
            }
        }

        // Children list (outside parent card - transparent background)
        AnimatedVisibility(
            visible = isExpanded && children.isNotEmpty(),
            enter = expandIn() + fadeIn(),
            exit = shrinkOut() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                children.forEachIndexed { index, child ->
                    val isLast = index == children.size - 1
                    val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .padding(vertical = 4.dp)
                            .drawBehind {
                                // Vertical guide line - closer to left edge
                                val lineX = 4.dp.toPx()
                                val lineWidth = 2.dp.toPx()
                                val halfRowHeight = size.height / 2

                                if (isLast) {
                                    // Last child: draw vertical line only to center (horizontal connector)
                                    drawLine(
                                        color = lineColor,
                                        start = androidx.compose.ui.geometry.Offset(lineX, 0f),
                                        end = androidx.compose.ui.geometry.Offset(lineX, halfRowHeight),
                                        strokeWidth = lineWidth
                                    )
                                } else {
                                    // Non-last: draw full vertical line through center
                                    drawLine(
                                        color = lineColor,
                                        start = androidx.compose.ui.geometry.Offset(lineX, 0f),
                                        end = androidx.compose.ui.geometry.Offset(lineX, size.height),
                                        strokeWidth = lineWidth
                                    )
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Horizontal connector column - narrow for wider child card
                        Box(
                            modifier = Modifier
                                .width(12.dp)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            // Horizontal connector line
                            Box(
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .width(16.dp)
                                    .height(2.dp)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                            )
                        }

                        // Child habit card with its own color
                        val childResolvedColors = CardColorResolver.resolveCardColors(
                            style = cardColorStyle,
                            userColorHex = child.habit.colorHex,
                            primaryContainer = colorScheme.primaryContainer.toArgb(),
                            onPrimaryContainer = colorScheme.onPrimaryContainer.toArgb(),
                            onPrimary = colorScheme.onPrimary.toArgb()
                        )

                        Card(
                            modifier = Modifier.weight(1f),
                            colors = CardDefaults.cardColors(
                                containerColor = childResolvedColors.backgroundColor
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                ChildHabitRow(
                                    childHabit = child,
                                    cardColorStyle = cardColorStyle,
                                    onClick = { onChildClick(child.habit.id) },
                                    onCheckIn = { value -> onChildCheckIn(child.habit.id, value) },
                                    onUndo = { onChildUndo(child.habit.id) },
                                    onIncrement = { onChildIncrement(child.habit.id) },
                                    onDecrement = { onChildDecrement(child.habit.id) },
                                    onTimerStart = {
                                        onChildTimerStart(child.habit.id, child.habit.targetValue)
                                    },
                                    onTimerPause = onChildTimerPause,
                                    onTimerResume = onChildTimerResume,
                                    onTimerStop = onChildTimerStop,
                                    activeTimer = activeTimer,
                                    showMetricPrompt = pendingMetricHabits.contains(child.habit.id),
                                    onRecordMetrics = { onRecordMetrics(child.habit.id) },
                                    linkedMetrics = linkedMetricsByHabit[child.habit.id] ?: emptyList(),
                                    onMetricClick = onMetricClick,
                                    isCheckInAllowed = child.isCheckInAllowed,
                                    nextCheckInDate = child.nextCheckInDate,
                                    hasFailed = child.hasFailed,
                                    isGoalCompleted = child.isGoalCompleted,
                                    onReactivation = { onChildReactivation(child.habit.id) },
                                    onEdit = { onChildEdit(child.habit.id) },
                                    onDelete = { onChildDelete(child.habit.id) }
                                )

                                // 底部边缘进度条 (仅目标型习惯且有目标值时显示)
                                if (child.habit.targetCycles != null && child.habit.targetCycles > 0) {
                                    val progressFraction = (child.targetProgress.toFloat() / child.habit.targetCycles.toFloat()).coerceIn(0f, 1f)

                                    Box(
                                        modifier = Modifier
                                            .padding(horizontal = 4.dp)
                                            .fillMaxWidth()
                                            .height(2.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(childResolvedColors.textColor.copy(alpha = 0.2f))
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(fraction = progressFraction)
                                                .height(2.dp)
                                                .background(MaterialTheme.colorScheme.primary)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}