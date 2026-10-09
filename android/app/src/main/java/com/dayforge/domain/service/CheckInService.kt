package com.dayforge.domain.service

import android.content.Context
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.model.CheckInResult
import com.dayforge.data.repository.HabitRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Domain service for check-in operations.
 * Single source of truth for both App (Compose) and Widget (Glance) consumers.
 *
 * Supports three habit types:
 * - CHECK_IN: Tap to complete, tap again to undo
 * - COUNTING: Increment/decrement with target-based completion
 * - TIMER: Progress queries for completed timer sessions
 */
@Singleton
class CheckInService @Inject constructor(
    private val habitRepository: HabitRepository,
    private val completionDao: CompletionDao,
    private val timeLogDao: TimeLogDao
) {

    /** Typed widget operations reuse the normal repository and target-progress rules. */
    suspend fun widgetAction(context: Context, claim: com.dayforge.data.repository.WidgetFactClaim,
        action: String): CheckInResult.Success {
        val committed = habitRepository.performWidgetFact(context, claim, action)
        val habit = committed.habit
        if (habit.completionPolicy == "one_and_done") return CheckInResult.Success(committed.completed, 0, false)
        val progress = committed.targetProgress
        val added = action == "increment" || (action == "toggle" && claim.completionUuid == null)
        return CheckInResult.Success(committed.completed, progress, added && habit.targetCycles != null && progress >= habit.targetCycles)
    }

    /**
     * Gets the distinct day count for target progress calculation.
     * TIMER habits use timelogs table, other types use completions table.
     * Per TARGET-06: Progress is calculated from distinct days with completions.
     *
     * For COUNTING habits, only counts days where target was met (sum >= targetValue).
     * For TIMER habits, only counts days where duration target was met.
     * For CHECK_IN habits, any completion counts.
     *
     * @param habit The habit entity
     * @return Number of distinct days meeting the target criteria
     */
    private suspend fun getDistinctDayCountForTarget(habit: com.dayforge.data.local.entity.HabitEntity): Int {
        return when (habit.habitType) {
            com.dayforge.data.model.HabitType.TIMER -> {
                // TIMER: count days where duration >= targetSeconds
                val targetSeconds = habit.targetValue * 60
                timeLogDao.getTargetMetDayCount(habit.id, targetSeconds)
            }
            com.dayforge.data.model.HabitType.COUNTING -> {
                if (habit.appearance != null) return habitRepository.getCountHistory(habit).qualifiedDates.size
                // COUNTING: count days where sum >= targetValue
                completionDao.getTargetMetDayCount(habit.id, habit.targetValue)
            }
            else -> {
                // CHECK_IN: any completion counts
                if (habit.habitType == com.dayforge.data.model.HabitType.CHECK_IN && habit.appearance != null) {
                    // The write may reactivate or update activityRate. Never reuse its pre-write row.
                    val current = requireNotNull(habitRepository.getHabitById(habit.id)) { "CHECK_NOT_FOUND" }
                    check(current.uuid == habit.uuid) { "CHECK_ACTIVITY_CHANGED" }
                    return habitRepository.getCheckHistory(current).qualifiedDates.size
                }
                completionDao.getDistinctDayCount(habit.id)
            }
        }
    }

    /**
     * Checks if check-in is allowed today for the given habit.
     * Uses ScheduleValidator to validate against habit's schedule type.
     *
     * @param habitId The ID of the habit
     * @return true if check-in is allowed today, false otherwise
     */
    suspend fun isCheckInAllowedToday(habitId: Long): Boolean {
        val habit = habitRepository.getHabitById(habitId) ?: return false
        if (habit.completionPolicy == "one_and_done") return habitRepository.getOneTimeStatus(habitId).canChange
        return ScheduleValidator.isCheckInAllowedToday(habit.schedule, habit.createdAt)
    }

    /**
     * Toggles check-in status for a CHECK_IN habit.
     * Per TARGET-03/08: Undo operations do NOT trigger goalReached.
     * @param context Context for scheduling widget refresh
     * @param habitId The ID of the habit
     * @return CheckInResult.Success with completed, progress, goalReached; or CheckInResult.Error
     */
    suspend fun toggleCheckIn(context: Context, habitId: Long, expectedHabitUuid: String? = null,
        oneTimeAuthority: com.dayforge.domain.model.OneTimeActionAuthority? = null): CheckInResult {
        val habit = habitRepository.getHabitById(habitId)
            ?: return CheckInResult.Error("Habit not found")
        if (expectedHabitUuid != null && habit.uuid != expectedHabitUuid) return CheckInResult.Error("Habit changed")

        if (habit.completionPolicy == "one_and_done") {
            return CheckInResult.Success(habitRepository.toggleOneTime(context, habitId, habit.uuid, oneTimeAuthority), 0, false)
        }
        check(oneTimeAuthority == null) { "ONE_TIME_ACTION_EXPIRED" }

        val todayCount = habitRepository.getTodayCompletionCount(habitId)
        val completed: Boolean
        val isUndo: Boolean

        if (todayCount > 0) {
            // Undo: delete most recent completion
            val completionId = habitRepository.getTodayCompletionId(habitId)
            if (completionId != null) {
                habitRepository.undoCompletion(context, completionId)
                completed = habitRepository.getTodayCompletionCount(habitId) > 0
                isUndo = true
            } else {
                completed = true // Edge case: no completion ID found
                isUndo = false
            }
        } else {
            // Check in: create completion
            habitRepository.logCompletion(context, habitId, 1)
            completed = true
            isUndo = false
        }

        // Calculate progress and goal detection
        val progress = getDistinctDayCountForTarget(habit)
        // Per TARGET-03/08: Undo operations should not trigger goalReached
        val goalReached = !isUndo && habit.targetCycles != null && progress >= habit.targetCycles

        return CheckInResult.Success(completed, progress, goalReached)
    }

    /**
     * Increments count for a COUNTING habit.
     * @param context Context for scheduling widget refresh
     * @param habitId The ID of the habit
     * @return CheckInResult.Success with completed (today count status), progress, goalReached
     */
    suspend fun incrementCount(context: Context, habitId: Long): CheckInResult {
        val habit = habitRepository.getHabitById(habitId)
            ?: return CheckInResult.Error("Habit not found")

        habitRepository.logCompletion(context, habitId, 1)
        val history = countHistoryAfterAction(habit)

        // Calculate progress and goal detection
        val progress = history?.qualifiedDates?.size ?: getDistinctDayCountForTarget(habit)
        val goalReached = habit.targetCycles != null && progress >= habit.targetCycles

        // For COUNTING: completed means todayCount >= targetValue
        val completed = history?.completedToday ?: (habitRepository.getTodayCompletionCount(habitId) >= habit.targetValue)

        return CheckInResult.Success(completed, progress, goalReached)
    }

    /**
     * Decrements count for a COUNTING habit.
     * Minimum value is 0.
     * Per TARGET-03: Decrement does NOT trigger goalReached (undo operation).
     * @param context Context for scheduling widget refresh
     * @param habitId The ID of the habit
     * @return CheckInResult.Success with completed (today count status), progress, goalReached=false
     */
    suspend fun decrementCount(context: Context, habitId: Long): CheckInResult {
        val habit = habitRepository.getHabitById(habitId)
            ?: return CheckInResult.Error("Habit not found")

        val hasCounts = if (habit.appearance != null) habitRepository.getCountHistory(habit).todayQuantity > 0
            else habitRepository.getTodayCompletionCount(habitId) > 0

        if (hasCounts) {
            // Delete the most recent completion
            val completionId = habitRepository.getTodayCompletionId(habitId)
            if (completionId != null) {
                habitRepository.undoCompletion(context, completionId)
            }
        }

        val history = countHistoryAfterAction(habit)

        // Calculate progress for UI display
        val progress = history?.qualifiedDates?.size ?: getDistinctDayCountForTarget(habit)

        // Per TARGET-03: goalReached is always false for decrement operations
        // Undo operations should not trigger goal completion dialogs
        val goalReached = false

        // For COUNTING: completed means newTodayCount >= targetValue
        val completed = history?.completedToday ?: (habitRepository.getTodayCompletionCount(habitId) >= habit.targetValue)

        return CheckInResult.Success(completed, progress, goalReached)
    }

    /**
     * Computes completion state for any habit type.
     * @param habitId The ID of the habit
     * @param targetValue The target value to reach
     * @return true if count >= target
     */
    suspend fun isCompleted(habitId: Long, targetValue: Int): Boolean {
        val habit = habitRepository.getHabitById(habitId)
        if (habit?.completionPolicy == "one_and_done") {
            return habitRepository.getOneTimeStatus(habitId).completed
        }
        if (habit?.habitType == com.dayforge.data.model.HabitType.COUNTING && habit.appearance != null)
            return habitRepository.getCountHistory(habit).completedToday
        val todayCount = habitRepository.getTodayCompletionCount(habitId)
        return todayCount >= targetValue
    }

    private suspend fun countHistoryAfterAction(expected: com.dayforge.data.local.entity.HabitEntity): com.dayforge.domain.model.CountHistory? {
        if (expected.appearance == null) return null
        // Recording/undo updates activityRate and can reactivate the habit. Read the committed
        // entity rather than using the stale pre-write display value as read authority.
        val current = requireNotNull(habitRepository.getHabitById(expected.id)) { "COUNT_NOT_FOUND" }
        check(current.uuid == expected.uuid) { "COUNT_ACTIVITY_CHANGED" }
        return habitRepository.getCountHistory(current)
    }
}
