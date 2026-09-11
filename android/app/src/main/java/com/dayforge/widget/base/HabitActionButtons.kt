package com.dayforge.widget.base

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.dayforge.R
import com.dayforge.widget.checkin.CheckInActionCallback
import com.dayforge.widget.focus.TimerStartCallback

/**
 * Shared action button composables for habit widgets.
 * Provides consistent button styling and behavior across:
 * - CheckInWidget
 * - CountingWidget
 * - TimerWidget
 * - FocusWidget
 *
 * Per WIDGET-REUSE-01: Extract common button UI to reduce duplication.
 *
 * Button styling standards (matching standalone widgets exactly):
 * - CHECK_IN button: height=40dp (matches CheckInWidget)
 * - TIMER buttons: height=36dp (matches TimerWidget)
 */
object HabitActionButtons {

    /**
     * Check-in button for CHECK_IN habit type.
     * Shows "打卡" or "已完成" based on completion status.
     * Matches CheckInWidget styling exactly (height=40dp).
     *
     * @param habitId The habit ID for the action callback
     * @param isCompleted Whether the habit is completed today
     * @param modifier Optional GlanceModifier for customization
     */
    @Composable
    fun CheckInButton(
        habitId: Long,
        isCompleted: Boolean,
        modifier: GlanceModifier = GlanceModifier.height(40.dp)
    ) {
        val context = LocalContext.current
        Button(
            text = if (isCompleted) {
                context.getString(R.string.widget_status_completed)
            } else {
                context.getString(R.string.action_check_in)
            },
            onClick = actionRunCallback<CheckInActionCallback>(
                actionParametersOf(
                    ActionParameters.Key<Long>("habitId") to habitId,
                    ActionParameters.Key<String>("action") to "toggle"
                )
            ),
            modifier = modifier
        )
    }

    /**
     * Timer start button for TIMER habit type.
     * Shows "启动计时" when not running, or "正在计时..." (disabled) when active.
     * Matches TimerWidget styling exactly (height=36dp).
     *
     * @param habitId The habit ID for the action callback
     * @param targetMinutes Target duration in minutes
     * @param isTimerActive Whether the timer is currently running
     * @param modifier Optional GlanceModifier for customization
     */
    @Composable
    fun TimerStartButton(
        habitId: Long,
        targetMinutes: Int,
        isTimerActive: Boolean = false,
        modifier: GlanceModifier = GlanceModifier.height(36.dp)
    ) {
        val context = LocalContext.current

        if (isTimerActive) {
            // Timer running: show disabled "正在计时..." button
            Button(
                text = "正在计时...",
                onClick = actionRunCallback<TimerStartCallback>(
                    actionParametersOf(
                        ActionParameters.Key<Long>("habitId") to habitId,
                        ActionParameters.Key<Int>("targetMinutes") to targetMinutes
                    )
                ),
                modifier = modifier,
                enabled = false,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = ColorProvider(Color.Gray.copy(alpha = 0.3f)),
                    contentColor = ColorProvider(Color.White)
                )
            )
        } else {
            // Timer not running: show "启动计时" button
            Button(
                text = context.getString(R.string.widget_timer_start),
                onClick = actionRunCallback<TimerStartCallback>(
                    actionParametersOf(
                        ActionParameters.Key<Long>("habitId") to habitId,
                        ActionParameters.Key<Int>("targetMinutes") to targetMinutes
                    )
                ),
                modifier = modifier
            )
        }
    }

    /**
     * Waiting button for BeforeWindow state.
     * Shows disabled "等待中" button.
     * Matches styling of other disabled buttons (height=36dp).
     *
     * @param habitId The habit ID (for potential action)
     * @param modifier Optional GlanceModifier for customization
     */
    @Composable
    fun WaitingButton(
        habitId: Long,
        modifier: GlanceModifier = GlanceModifier.height(36.dp)
    ) {
        val context = LocalContext.current
        Button(
            text = context.getString(R.string.widget_focus_waiting),
            onClick = actionRunCallback<CheckInActionCallback>(
                actionParametersOf(
                    ActionParameters.Key<Long>("habitId") to habitId,
                    ActionParameters.Key<String>("action") to "none"
                )
            ),
            modifier = modifier,
            enabled = false,
            colors = ButtonDefaults.buttonColors(
                backgroundColor = ColorProvider(Color.Gray.copy(alpha = 0.3f)),
                contentColor = ColorProvider(Color.Gray)
            )
        )
    }

    /**
     * Completed indicator for COUNTING habits when target reached.
     * Shows "✓ 已完成" with appropriate styling.
     *
     * @param textColor Text color
     * @param modifier Optional GlanceModifier
     */
    @Composable
    fun CompletedIndicator(
        textColor: Color,
        modifier: GlanceModifier = GlanceModifier
    ) {
        val context = LocalContext.current
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "✓",
                style = TextStyle(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = ColorProvider(textColor)
                )
            )
            Spacer(modifier = GlanceModifier.width(6.dp))
            Text(
                text = context.getString(R.string.widget_status_completed),
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = ColorProvider(textColor)
                )
            )
        }
    }
}