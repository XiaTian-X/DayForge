package com.dayforge.data.model

import com.dayforge.data.local.entity.HabitEntity
import java.time.LocalDate

/**
 * Habit with calculated stats for UI display.
 * Combines raw habit data with computed status fields.
 *
 * Used by:
 * - DashboardViewModel for habit list display
 * - Widgets for progress calculation
 * - ProfileViewModel for today's progress
 */
data class HabitWithStats(
    val habit: HabitEntity,
    val completedToday: Boolean,
    val todayCount: Int,        // Current count for counting habits
    val lastCompletionId: Long?,
    val currentStreak: Int,
    val bestStreak: Int,
    val activityRate: Int = 100,  // 活跃度 0-100
    val isCheckInAllowed: Boolean = true,  // True for Daily schedule, calculated for Weekly/Monthly/Custom
    val nextCheckInDate: LocalDate? = null,  // Null if check-in allowed today, otherwise next valid check-in date
    val targetProgress: Int = 0,  // Distinct days completed for habits with targetCycles
    val hasFailed: Boolean = false,  // Failure status for target-based habits
    val slotProgress: String? = null  // Slot progress for COUNTING habits in focus mode: "第 X 个/共 Y 个"
) {
    /**
     * Whether the goal has been reached (targetCycles achieved).
     * Used for displaying "完成" badge in HabitCard.
     * User can still continue tracking after goal reached (until they confirm completion).
     */
    val isGoalReached: Boolean
        get() = habit.targetCycles != null && targetProgress >= habit.targetCycles

    /**
     * Whether the goal has been completed (reached and deactivated).
     * Used for CompletionButton to show "目标已完成" state.
     * This means user has confirmed goal completion and habit is now inactive.
     */
    val isGoalCompleted: Boolean
        get() = isGoalReached && !habit.isActive

    /**
     * Whether this habit should count towards today's progress.
     * Excludes:
     * - GOAL type (container for child habits, no check-ins)
     * - Non-check-in days (based on schedule)
     * - Failed habits (target-based habits that failed)
     * - Goal-completed habits (reached targetCycles and deactivated)
     */
    val shouldCountToday: Boolean
        get() {
            // GOAL type doesn't count towards progress
            if (habit.habitType == HabitType.GOAL) return false

            // Not a check-in day
            if (!isCheckInAllowed) return false

            // Failed habit
            if (hasFailed) return false

            // Goal completed (reached target and deactivated)
            if (isGoalCompleted) return false

            return true
        }
}