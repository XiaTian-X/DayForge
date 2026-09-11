package com.dayforge.domain.model

/**
 * Filter mode for habit list display.
 * Controls which habits are shown and how they are sorted.
 */
enum class FilterMode(val value: String) {
    /**
     * All mode: Show all active habits.
     * Sorted by active status and check-in allowed status.
     */
    ALL("all"),

    /**
     * Time window mode: Sort habits by time priority.
     * Uses HabitPriorityCalculator for time-based sorting.
     * Uncompleted habits appear first.
     */
    TIME_WINDOW("time_window"),

    /**
     * Checkable mode: Show only habits that can be checked in and are not completed.
     * Rules:
     * - Normal: completedToday=false, isCheckInAllowed=true, isActive=true, not failed, not goal completed
     * - Positive counting (isCountdown=false): Always show (can continue checking in after reaching target)
     * - TIMER with pending metric: Show (has "Record" button to process)
     */
    CHECKABLE("checkable"),

    /**
     * Terminated mode: Show only habits that have ended.
     * - hasFailed=true: Target-based habit that failed (STRICT mode missed check-in)
     * - isGoalCompleted=true: Reached targetCycles and deactivated
     */
    TERMINATED("terminated");

    companion object {
        /**
         * Get FilterMode from string value.
         * Returns ALL as default for unknown values.
         */
        fun fromValue(value: String): FilterMode {
            return entries.find { it.value == value } ?: ALL
        }
    }
}