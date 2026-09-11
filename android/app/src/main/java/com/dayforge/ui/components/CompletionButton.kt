package com.dayforge.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dayforge.R
import com.dayforge.data.model.HabitType
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Timer state for displaying timer controls.
 */
enum class TimerState {
    NOT_RUNNING,    // No timer active for this habit
    RUNNING,        // Timer is running
    PAUSED          // Timer is paused
}

/**
 * CompletionButton component for habit check-in.
 *
 * Supports distinct UI for habit types:
 * - CHECK_IN: Shows "打卡" button, tap to complete. When completed, shows clickable
 *   "已完成" row - tap to undo.
 * - COUNTING: Shows [-] X/Y [+] layout with increment/decrement buttons.
 *   Minus button disabled when count is 0. Completed badge shown when count >= target.
 * - TIMER: Shows timer controls with states:
 *   - RUNNING: Pause button, "MM:SS / X分钟", Stop button
 *   - PAUSED: Resume button, "MM:SS / X分钟 (已暂停)", Stop button
 *   - NOT_RUNNING: Start button, "X/Y 分钟" progress, manual input on click
 *   CheckCircle icon shown when accumulated minutes >= target.
 *
 * Animation specs:
 * - Transition: fadeIn/fadeOut 150ms
 * - Minimum touch target 48dp height
 *
 * @param completed Whether the habit is completed today
 * @param undoAvailable Whether undo is available (same-session undo)
 * @param habitType The type of habit (CHECK_IN, COUNTING, or TIMER)
 * @param targetValue The target value for counting habits (count) or timer habits (minutes)
 * @param currentCount Current count for counting habits, or seconds for timer habits
 * @param timerState State of the timer for TIMER habits (RUNNING, PAUSED, NOT_RUNNING)
 * @param isCountdown Whether this is a countdown timer (true) or countup timer (false)
 * @param onCheckIn Callback when user taps check-in button (with value for counting)
 * @param onUndo Callback when user taps undo (clicking completed area for check-in)
 * @param onIncrement Callback for + button in counting habits
 * @param onDecrement Callback for - button in counting habits
 * @param onTimerStart Callback to start the timer
 * @param onTimerPause Callback to pause the timer
 * @param onTimerResume Callback to resume the timer
 * @param onTimerStop Callback to stop the timer
 * @param showMetricPrompt Whether to show metric record button (for completed timer habits)
 * @param onRecordMetrics Callback to record metrics
 * @param isCheckInAllowed Whether today is a valid check-in day (for Weekly/Monthly/Custom schedules)
 * @param nextCheckInDate Next valid check-in date (shown when !isCheckInAllowed)
 * @param hasFailed Whether the target-based habit has failed (STRICT mode missed check-in)
 * @param isGoalCompleted Whether the goal has been completed (reached targetCycles and deactivated)
 * @param onReactivation Callback when user taps on "已失败" or "目标已完成" to reactivate the habit
 * @param textColor Color for text and icons on the card background
 * @param modifier Modifier for custom styling
 */
@Composable
fun CompletionButton(
    completed: Boolean,
    undoAvailable: Boolean,
    habitType: HabitType = HabitType.CHECK_IN,
    targetValue: Int = 1,
    currentCount: Int = 0,
    timerState: TimerState = TimerState.NOT_RUNNING,
    isCountdown: Boolean = false,
    onCheckIn: (Int) -> Unit,
    onUndo: () -> Unit,
    onIncrement: () -> Unit = {},
    onDecrement: () -> Unit = {},
    onTimerStart: () -> Unit = {},
    onTimerPause: () -> Unit = {},
    onTimerResume: () -> Unit = {},
    onTimerStop: () -> Unit = {},
    showMetricPrompt: Boolean = false,
    onRecordMetrics: () -> Unit = {},
    isCheckInAllowed: Boolean = true,
    nextCheckInDate: LocalDate? = null,
    hasFailed: Boolean = false,
    isGoalCompleted: Boolean = false,
    onReactivation: () -> Unit = {},
    textColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier
) {
    // Resolve text color: use provided color or fallback to theme default
    val resolvedTextColor = if (textColor != Color.Unspecified) {
        textColor
    } else {
        MaterialTheme.colorScheme.onPrimary
    }

    AnimatedContent(
        targetState = when {
            // 1. TIMER 运行中/暂停中 - 最高优先级，不中断计时
            habitType == HabitType.TIMER && timerState == TimerState.RUNNING -> "timer_running"
            habitType == HabitType.TIMER && timerState == TimerState.PAUSED -> "timer_paused"

            // 2. 非打卡日 - 禁止新打卡（但不中断运行中的计时）
            !isCheckInAllowed -> "disabled_checkin"

            // 3. 目标习惯已失败 - 禁止打卡
            hasFailed -> "failed"

            // 4. TIMER 已完成且有 pending metrics（优先于 goal_completed，显示记录按钮）
            habitType == HabitType.TIMER && completed && showMetricPrompt -> "timer_completed"

            // 5. 目标习惯已完成（达成且停用）- 禁止打卡，点击触发重新激活
            isGoalCompleted -> "goal_completed"

            // 6. TIMER 已完成（无 pending metrics）
            habitType == HabitType.TIMER && completed -> "timer_completed"

            // 7. TIMER 未开始
            habitType == HabitType.TIMER -> "timer_static"

            // 8. COUNTING
            habitType == HabitType.COUNTING -> "counting"

            // 9. 已完成/可撤销
            completed || undoAvailable -> "completed"

            // 10. 默认打卡
            else -> "checkin"
        },
        transitionSpec = {
            fadeIn(animationSpec = tween(150)) togetherWith
            fadeOut(animationSpec = tween(150))
        },
        modifier = modifier.animateContentSize()
    ) { state ->
        when (state) {
            "timer_running" -> {
                // Running timer: [Pause] MM:SS / X分钟 [Stop]
                // For countdown mode: [Pause] 还剩 MM:SS / X分钟 [Stop]
                val targetSeconds = targetValue * 60
                val displayText = if (isCountdown) {
                    // Countdown mode: display remaining time
                    val remainingSeconds = (targetSeconds - currentCount).coerceAtLeast(0)
                    val minutes = remainingSeconds / 60
                    val seconds = remainingSeconds % 60
                    stringResource(R.string.timer_remaining_format, minutes, seconds, targetValue)
                } else {
                    // Countup mode: display elapsed time
                    val minutes = currentCount / 60
                    val seconds = currentCount % 60
                    stringResource(R.string.timer_elapsed_format, minutes, seconds, targetValue)
                }
                val isCompleted = if (isCountdown) {
                    // Countdown: completed when remaining <= 0
                    (targetSeconds - currentCount) <= 0
                } else {
                    // Countup: completed when elapsed >= target
                    currentCount >= targetSeconds
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(48.dp)
                ) {
                    // Pause button
                    IconButton(onClick = onTimerPause) {
                        Icon(
                            imageVector = Icons.Default.Pause,
                            contentDescription = stringResource(R.string.action_pause),
                            tint = resolvedTextColor
                        )
                    }

                    // Progress display
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.labelLarge,
                        color = resolvedTextColor
                    )

                    // Stop button
                    IconButton(onClick = onTimerStop) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = stringResource(R.string.action_stop),
                            tint = resolvedTextColor
                        )
                    }

                    // Completed badge when target reached
                    if (isCompleted) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = stringResource(R.string.habit_card_status_completed),
                            tint = resolvedTextColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            "timer_paused" -> {
                // Paused timer: [Resume] MM:SS / X分钟 (已暂停) [Stop]
                // For countdown mode: [Resume] 还剩 MM:SS / X分钟 (已暂停) [Stop]
                val targetSeconds = targetValue * 60
                val displayText = if (isCountdown) {
                    // Countdown mode: display remaining time
                    val remainingSeconds = (targetSeconds - currentCount).coerceAtLeast(0)
                    val minutes = remainingSeconds / 60
                    val seconds = remainingSeconds % 60
                    stringResource(R.string.timer_remaining_paused_format, minutes, seconds, targetValue)
                } else {
                    // Countup mode: display elapsed time
                    val minutes = currentCount / 60
                    val seconds = currentCount % 60
                    stringResource(R.string.timer_elapsed_paused_format, minutes, seconds, targetValue)
                }
                val isCompleted = if (isCountdown) {
                    // Countdown: completed when remaining <= 0
                    (targetSeconds - currentCount) <= 0
                } else {
                    // Countup: completed when elapsed >= target
                    currentCount >= targetSeconds
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(48.dp)
                ) {
                    // Resume button
                    IconButton(onClick = onTimerResume) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.action_resume),
                            tint = resolvedTextColor
                        )
                    }

                    // Progress display with paused indicator
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.labelLarge,
                        color = resolvedTextColor
                    )

                    // Stop button
                    IconButton(onClick = onTimerStop) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = stringResource(R.string.action_stop),
                            tint = resolvedTextColor
                        )
                    }

                    // Completed badge when target reached
                    if (isCompleted) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = stringResource(R.string.habit_card_status_completed),
                            tint = resolvedTextColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            "timer_static" -> {
                // No active timer: Start button + target only
                val progressText = stringResource(R.string.timer_target_format, targetValue)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(48.dp)
                ) {
                    // Start button
                    IconButton(onClick = onTimerStart) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.action_start_timer),
                            tint = resolvedTextColor
                        )
                    }

                    // Progress display
                    Text(
                        text = progressText,
                        style = MaterialTheme.typography.labelLarge,
                        color = resolvedTextColor
                    )
                }
            }
            "timer_completed" -> {
                // Timer already completed today - show completed badge, no start button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.habit_card_status_completed),
                        tint = resolvedTextColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.habit_card_status_completed),
                        style = MaterialTheme.typography.labelLarge,
                        color = resolvedTextColor
                    )

                    // Record metrics button for timer habits with pending metrics
                    if (showMetricPrompt) {
                        Spacer(modifier = Modifier.width(12.dp))
                        TextButton(
                            onClick = onRecordMetrics,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = resolvedTextColor
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.action_record_metrics))
                        }
                    }
                }
            }
            "counting" -> {
                // 统一使用打卡按钮样式
                val remaining = if (isCountdown) {
                    (targetValue - currentCount).coerceAtLeast(0)
                } else {
                    0
                }

                val isCompleted = if (isCountdown) {
                    remaining <= 0
                } else {
                    currentCount >= targetValue
                }

                // 倒计数完成后不能再打卡，正计数可以继续打卡
                val canCheckIn = if (isCountdown) {
                    remaining > 0
                } else {
                    true  // 正计数始终可以打卡
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(48.dp)
                ) {
                    if (isCountdown && isCompleted) {
                        // 倒计数完成：只显示 "✓ 已完成"
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = resolvedTextColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.habit_card_status_completed),
                            style = MaterialTheme.typography.labelLarge,
                            color = resolvedTextColor
                        )
                    } else {
                        // 正计数或倒计数未完成：显示打卡按钮 + 进度
                        // 完成标记（正计数达标后显示）
                        if (isCompleted) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = resolvedTextColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        // 打卡按钮
                        Button(
                            onClick = { if (isCountdown) onDecrement() else onIncrement() },
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.action_check_in),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))

                        // 进度显示
                        val progressText = if (isCountdown) {
                            stringResource(R.string.timer_countdown_remaining, remaining)
                        } else {
                            stringResource(R.string.timer_countup_progress, currentCount, targetValue)
                        }
                        Text(
                            text = progressText,
                            style = MaterialTheme.typography.labelLarge,
                            color = resolvedTextColor
                        )
                    }
                }
            }
            "completed" -> {
                // Tappable row for undo (CHECK_IN habits)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .height(48.dp)
                        .clickable { onUndo() }
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = resolvedTextColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.habit_card_status_completed),
                        style = MaterialTheme.typography.labelLarge,
                        color = resolvedTextColor.copy(alpha = 0.9f)
                    )
                }
            }
            "disabled_checkin" -> {
                // Non-check-in day: Show disabled state with next check-in date hint
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = null,
                        tint = resolvedTextColor.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.habit_card_status_non_checkin_day),
                        style = MaterialTheme.typography.labelLarge,
                        color = resolvedTextColor.copy(alpha = 0.5f)
                    )
                    if (nextCheckInDate != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        val daysUntil = ChronoUnit.DAYS.between(LocalDate.now(), nextCheckInDate)
                        val hint = when {
                            daysUntil == 1L -> stringResource(R.string.next_checkin_tomorrow)
                            daysUntil <= 7L -> stringResource(R.string.next_checkin_days, daysUntil)
                            else -> stringResource(R.string.next_checkin_date, nextCheckInDate.monthValue, nextCheckInDate.dayOfMonth)
                        }
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = resolvedTextColor.copy(alpha = 0.6f)
                        )
                    }
                }
            }
            "failed" -> {
                // Target-based habit has failed (STRICT mode missed check-in)
                // Clickable to trigger reactivation dialog
                Surface(
                    modifier = Modifier.clickable { onReactivation() },
                    color = Color.White.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cancel,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.status_already_failed),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            "goal_completed" -> {
                // Target-based habit has completed goal (reached targetCycles and deactivated)
                // Clickable to trigger reactivation dialog
                Surface(
                    modifier = Modifier.clickable { onReactivation() },
                    color = Color.White.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,  // Use theme primary color
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.status_goal_completed),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary  // Use theme primary color
                        )
                    }
                }
            }
            "checkin" -> {
                Button(
                    onClick = { onCheckIn(1) },
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(
                        text = stringResource(R.string.action_check_in),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}