package com.dayforge.ui.screens.habitdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.model.HabitType
import com.dayforge.data.model.StreakStats
import com.dayforge.ui.components.CompletionCalendar
import com.dayforge.ui.components.StreakIndicator
import com.dayforge.ui.components.TargetProgressIndicator
import androidx.compose.ui.res.stringResource
import com.dayforge.R
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.font.FontWeight
import java.util.Locale

/**
 * HabitDetailScreen displays full streak information and 30-day calendar heatmap.
 *
 * Screen layout:
 * - TopAppBar with habit name, back button, and edit button
 * - StreakSummary Card showing current and best streak
 * - CompletionCalendar showing last 35 days (5x7 grid)
 *
 * @param habitId The ID of the habit to display
 * @param onNavigateBack Callback when user taps back button
 * @param onEditClick Callback when user taps edit button
 * @param viewModel HabitDetailViewModel injected via Hilt
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitDetailScreen(
    habitId: Long,
    onNavigateBack: () -> Unit,
    onEditClick: (Long) -> Unit = {},
    onEditGoalClick: (Long) -> Unit = {},
    viewModel: HabitDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(habitId) {
        viewModel.loadHabit(habitId)
    }

    // Per TARGET-02: Reactivation confirmation dialog for target-based habits
    if (state.showReactivationDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissReactivationDialog() },
            title = { Text(stringResource(R.string.dialog_reactivate_title)) },
            text = {
                Text(stringResource(R.string.dialog_reactivate_habit_message, state.reactivationHabitName))
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.habit?.name ?: stringResource(R.string.habit_detail_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.content_description_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (state.habit?.habitType == HabitType.GOAL) {
                            onEditGoalClick(habitId)
                        } else {
                            onEditClick(habitId)
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.content_description_edit)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column {
                // Active Status Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.habit_detail_active_status),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(
                        checked = state.habit?.isActive ?: true,
                        onCheckedChange = { viewModel.toggleActiveStatus() }
                    )
                }

                // Notification Toggle - only show if habit has bestTime set (NOTIFY-04)
                if (state.habit?.bestTime != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.habit_notification_toggle_title),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = stringResource(
                                    R.string.habit_notification_toggle_desc,
                                    formatBestTime(state.habit!!.bestTime!!)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Switch(
                            checked = state.notificationEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.toggleNotificationEnabled(habitId, enabled)
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Streak Summary Card
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        val streakStats: StreakStats? = state.streakStats
                        val habit = state.habit

                        // Current streak and best streak
                        if (streakStats != null) {
                            Text(
                                text = stringResource(R.string.format_streak, streakStats.currentStreak),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.widget_best_streak, streakStats.bestStreak),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        if (habit?.targetCycles != null) {
                            // Target-based habit: show TargetProgressIndicator
                            TargetProgressIndicator(
                                progress = state.targetProgress,
                                target = habit.targetCycles
                            )
                        } else if (streakStats != null) {
                            // Unlimited habit: show StreakIndicator with activity rate
                            StreakIndicator(
                                activityRate = habit?.activityRate ?: 100
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.common_empty),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Completion Calendar - only for non-GOAL habits
                val habit = state.habit
                if (habit?.habitType != HabitType.GOAL) {
                    val completions: List<CompletionEntity> = state.completions
                    CompletionCalendar(completions = completions)
                }

                // Metrics section for GOAL habits
                if (habit?.habitType == HabitType.GOAL && state.metrics.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.habit_detail_metrics_section),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            state.metrics.forEach { metricInfo ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = metricInfo.metric.name,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = stringResource(
                                                R.string.habit_detail_metric_current_value,
                                                metricInfo.currentValue,
                                                metricInfo.metric.unit
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (metricInfo.currentValue == "--") {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }
                                }

                                // Target distance or tracked days
                                if (metricInfo.distanceToTarget != null) {
                                    // Metric has target: show distance to target
                                    Text(
                                        text = metricInfo.distanceToTarget,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    // Metric has no target: show tracked days
                                    Text(
                                        text = stringResource(
                                            R.string.habit_detail_metric_tracked_days,
                                            metricInfo.trackedDays
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Formats bestTime (minutes since midnight) to HH:mm format.
 * Per NOTIFY-04: Display the reminder time in the notification toggle UI.
 *
 * @param bestTime Minutes since midnight (e.g., 480 = 8:00 AM)
 * @return Formatted time string (e.g., "08:00")
 */
private fun formatBestTime(bestTime: Long): String {
    val hours = (bestTime / 60).toInt()
    val minutes = (bestTime % 60).toInt()
    return String.format(Locale.getDefault(), "%02d:%02d", hours, minutes)
}
