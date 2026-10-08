package com.dayforge.widget.timer

import com.dayforge.data.api.dto.TimerStartPolicy
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.repository.timerPolicy

/** Display projection only. It cannot create a completion, a command or a replacement policy. */
internal data class WidgetTimerPolicy(
    val targetSeconds: Int,
    val isCountdown: Boolean,
    val maxDurationSeconds: Int
) {
    val targetMinutes: Int get() = targetSeconds / 60

    fun elapsed(rawSeconds: Int): Int = rawSeconds.coerceIn(0, maxDurationSeconds)

    /** The running display is this session, not completed sessions from earlier today. */
    fun remaining(activeLog: TimeLogEntity?, completedSeconds: Int, elapsedSeconds: Int): Int =
        if (isCountdown) (targetSeconds - if (activeLog != null) elapsedSeconds else completedSeconds).coerceAtLeast(0)
        else 0

    companion object {
        fun read(habit: HabitEntity, activeLog: TimeLogEntity?, original: TimerStartPolicy?): WidgetTimerPolicy {
            if (habit.appearance != null && activeLog != null) requireNotNull(original) { "TIMER_WIDGET_POLICY_REQUIRED" }
            if (original != null) check(activeLog != null)
            val declared = original ?: if (habit.appearance != null) timerPolicy(habit) else null
            if (declared != null) {
                declared.validate()
                return WidgetTimerPolicy(declared.targetSeconds, declared.isCountdown, declared.maxDurationSeconds)
            }
            // The inactive plan and the still-supported v4 consumer retain their existing plan semantics.
            val target = Math.multiplyExact(habit.targetValue, 60)
            val max = if (habit.isCountdown) target else if (target > 0) Math.multiplyExact(target, 3) else 86_400
            require(target >= 0 && max >= 0)
            return WidgetTimerPolicy(target, habit.isCountdown, max)
        }
    }
}
