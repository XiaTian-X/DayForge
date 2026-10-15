package com.dayforge.domain.service

import android.content.Context
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.model.CheckInResult
import com.dayforge.data.repository.HabitRepository
import com.dayforge.data.repository.CompletionActionSnapshot
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val timeLogDao: TimeLogDao,
    private val countActions: CountActionGate? = null
) {
    // Shared across screen coordinators. No global account lock is held across repository calls.
    private val actionLocks = Array(64) { Mutex() }
    private fun actionLock(id: Long) = actionLocks[(id.hashCode() and Int.MAX_VALUE) % actionLocks.size]

    private suspend fun withHabitAction(id: Long, includeHistory: Boolean = false,
        block: suspend (CompletionActionSnapshot) -> CheckInResult): CheckInResult {
        val lock = actionLock(id)
        // No suspension before tryLock: an uncontended operation can capture editor + history
        // together inside the same action lock. A queued operation retains its ORIGINAL ticket.
        if (lock.tryLock()) {
            return try {
                val captured = CompletionActionTiming.measure(CompletionActionTiming.Stage.PREPARE) {
                    habitRepository.prepareCompletion(id, includeHistory)
                }
                if (captured.edit.value == null) CheckInResult.Error("Habit not found") else block(captured)
            } finally { lock.unlock() }
        }
        val snapshot = CompletionActionTiming.measure(CompletionActionTiming.Stage.PREPARE) {
            habitRepository.getHabitForEditing(id)
        }
        if (snapshot.value == null) return CheckInResult.Error("Habit not found")
        return lock.withLock { block(CompletionActionSnapshot(snapshot, null)) }
    }

    /** Typed widget operations reuse the normal repository and target-progress rules. */
    suspend fun widgetInteraction(context: Context, claim: com.dayforge.data.repository.WidgetFactClaim,
        action: String): CheckInResult {
        if (claim.countPolicy == null) return widgetAction(context, claim, action)
        val gate = requireNotNull(countActions)
        val lease = gate.tryAcquire(claim.session(), claim.habitUuid, claim.habitId) ?: return CheckInResult.Ignored
        return try { widgetAction(context, claim, action) } finally { gate.release(lease) }
    }

    /** Command API; interaction admission is owned by the screen or widget entry. */
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
                if (habit.appearance != null) {
                    return habitRepository.getTimerHistory(habit).qualifiedDates.size
                }
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
                    return habitRepository.getCheckHistory(habit).qualifiedDates.size
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
        oneTimeAuthority: com.dayforge.domain.model.OneTimeActionAuthority? = null,
        identity: com.dayforge.domain.model.CheckActionIdentity? = null): CheckInResult =
        withHabitAction(habitId, includeHistory = true) { toggleCheckInLocked(context, habitId, expectedHabitUuid, oneTimeAuthority, it, identity) }

    private suspend fun toggleCheckInLocked(context: Context, habitId: Long, expectedHabitUuid: String?,
        oneTimeAuthority: com.dayforge.domain.model.OneTimeActionAuthority?, captured: CompletionActionSnapshot,
        identity: com.dayforge.domain.model.CheckActionIdentity?): CheckInResult {
        val habit = captured.evidence?.habit ?: currentHabit(requireNotNull(captured.edit.value))
        if (expectedHabitUuid != null && habit.uuid != expectedHabitUuid) return CheckInResult.Error("Habit changed")
        identity?.let {
            check(habit.id == it.habitId && habit.uuid == it.habitUuid &&
                habit.habitType == com.dayforge.data.model.HabitType.CHECK_IN) { "CHECK_ACTION_EXPIRED" }
            it.requireAuthority(requireNotNull(captured.edit.authority))
        }

        if (habit.completionPolicy == "one_and_done") {
            return CheckInResult.Success(habitRepository.toggleOneTime(context, habitId, habit.uuid, oneTimeAuthority), 0, false)
        }
        check(oneTimeAuthority == null) { "ONE_TIME_ACTION_EXPIRED" }

        if (habit.appearance != null && habit.habitType == com.dayforge.data.model.HabitType.CHECK_IN) {
            val before = captured.evidence?.checkHistory ?: readCurrentHabitSnapshot { habitRepository.getCheckHistory(habit) }
            val last = before.todayCompletions.maxByOrNull { it.id }
            val recordedId = CompletionActionTiming.measure(CompletionActionTiming.Stage.WRITE_CALL) {
                if (last != null) {
                    habitRepository.undoCompletion(context, last.id, authority = captured.edit.authority, checkIdentity = identity)
                    null
                }
                else habitRepository.logCompletion(context, habitId, 1, captured.edit.authority, habit.uuid,
                    checkIdentity = identity)
            }
            // Undo may legitimately become a no-op if an independent entry already removed it.
            if (last == null) CompletionActionTiming.mark(CompletionActionTiming.Stage.COMMIT_OBSERVED)
            return afterCommittedAction { readCurrentHabitSnapshot {
                val view = CompletionActionTiming.measure(CompletionActionTiming.Stage.AFTER_READ) {
                    habitRepository.readCompletionAfter(captured, allowActivation = last == null,
                        recordedCompletionId = recordedId)
                }
                val current = view.habit
                val after = requireNotNull(view.checkHistory)
                val progress = after.qualifiedDates.size
                CheckInResult.Success(after.todayCompletions.isNotEmpty(), progress,
                    last == null && current.targetCycles != null && progress >= current.targetCycles, followUp = view.followUp)
            } }
        }

        val todayCount = readCurrentHabitSnapshot { habitRepository.getTodayCompletionCount(habitId) }
        val completed: Boolean
        val isUndo: Boolean

        if (todayCount > 0) {
            // Undo: delete most recent completion
            val completionId = readCurrentHabitSnapshot { habitRepository.getTodayCompletionId(habitId) }
            if (completionId != null) {
                habitRepository.undoCompletion(context, completionId, authority = captured.edit.authority)
                completed = afterCommittedAction { readCurrentHabitSnapshot {
                    habitRepository.getTodayCompletionCount(habitId) > 0
                } }
                isUndo = true
            } else {
                completed = true // Edge case: no completion ID found
                isUndo = false
            }
        } else {
            // Check in: create completion
            habitRepository.logCompletion(context, habitId, 1, captured.edit.authority, habit.uuid)
            completed = true
            isUndo = false
        }

        // Calculate progress and goal detection
        return afterCommittedAction { readCurrentHabitSnapshot {
            val current = currentHabit(habit)
            val progress = getDistinctDayCountForTarget(current)
            // Undo never triggers goalReached; do not combine a new history with an old target.
            CheckInResult.Success(completed, progress, !isUndo && current.targetCycles != null && progress >= current.targetCycles)
        } }
    }

    /**
     * Increments count for a COUNTING habit.
     * @param context Context for scheduling widget refresh
     * @param habitId The ID of the habit
     * @return CheckInResult.Success with completed (today count status), progress, goalReached
     */
    suspend fun incrementCount(context: Context, habitId: Long,
        identity: com.dayforge.domain.model.CountActionIdentity? = null): CheckInResult =
        withHabitAction(habitId) { incrementCountLocked(context, habitId, it, identity) }

    private suspend fun incrementCountLocked(context: Context, habitId: Long, captured: CompletionActionSnapshot,
        identity: com.dayforge.domain.model.CountActionIdentity?): CheckInResult {
        val habit = currentHabit(requireNotNull(captured.edit.value))

        val id = CompletionActionTiming.measure(CompletionActionTiming.Stage.WRITE_CALL) {
            habitRepository.logCompletion(context, habitId, 1, captured.edit.authority, habit.uuid, countIdentity = identity)
        }
        if (id == 0L) return countResult(captured, allowGoal = false, identity).copy(recorded = false)
        CompletionActionTiming.mark(CompletionActionTiming.Stage.COMMIT_OBSERVED)
        return afterCommittedAction { countResult(captured, allowGoal = true, identity, allowActivation = true,
            recordedCompletionId = id) }
    }

    /**
     * Decrements count for a COUNTING habit.
     * Minimum value is 0.
     * Per TARGET-03: Decrement does NOT trigger goalReached (undo operation).
     * @param context Context for scheduling widget refresh
     * @param habitId The ID of the habit
     * @return CheckInResult.Success with completed (today count status), progress, goalReached=false
     */
    suspend fun decrementCount(context: Context, habitId: Long,
        identity: com.dayforge.domain.model.CountActionIdentity? = null): CheckInResult =
        withHabitAction(habitId, includeHistory = true) { decrementCountLocked(context, habitId, it, identity) }

    private suspend fun decrementCountLocked(context: Context, habitId: Long, captured: CompletionActionSnapshot,
        identity: com.dayforge.domain.model.CountActionIdentity?): CheckInResult {
        val habit = captured.evidence?.habit ?: currentHabit(requireNotNull(captured.edit.value))

        val before = if (habit.appearance != null) captured.evidence?.countHistory
            ?: readCurrentHabitSnapshot { habitRepository.getCountHistory(habit) } else null
        identity?.let { requireCountScope(it, before?.actionIdentity ?: habitRepository.legacyCountIdentity(habit)) }
        val hasCounts = before?.let { it.todayQuantity > 0 } ?: (habitRepository.getTodayCompletionCount(habitId) > 0)

        var committed = false
        if (hasCounts) {
            // Delete the most recent completion
            val completionId = if (before != null) before.completions
                .filter { it.recordedLocalDate == before.today.toString() }.maxByOrNull { it.id }?.id
                else readCurrentHabitSnapshot { habitRepository.getTodayCompletionId(habitId) }
            if (completionId != null) {
                CompletionActionTiming.measure(CompletionActionTiming.Stage.WRITE_CALL) {
                    habitRepository.undoCompletion(context, completionId, authority = captured.edit.authority, countIdentity = identity)
                }
                committed = true
            }
        }

        return if (committed) afterCommittedAction { countResult(captured, allowGoal = false, identity) }
            else countResult(captured, allowGoal = false, identity).copy(recorded = false)
    }

    private suspend fun countResult(captured: CompletionActionSnapshot, allowGoal: Boolean,
        identity: com.dayforge.domain.model.CountActionIdentity? = null, allowActivation: Boolean = false,
        recordedCompletionId: Long? = null): CheckInResult.Success =
        readCurrentHabitSnapshot {
            val expected = requireNotNull(captured.edit.value)
            val view = if (expected.appearance != null) CompletionActionTiming.measure(CompletionActionTiming.Stage.AFTER_READ) {
                habitRepository.readCompletionAfter(captured, allowActivation, recordedCompletionId)
            } else null
            val current = view?.habit ?: currentHabit(expected)
            val history = view?.countHistory
            if (current.appearance != null) requireNotNull(history) { "COUNT_READ_REQUIRED" }
            identity?.let { requireCountScope(it, history?.actionIdentity ?: habitRepository.legacyCountIdentity(current)) }
            val progress = history?.qualifiedDates?.size ?: getDistinctDayCountForTarget(current)
            val completed = history?.completedToday ?: (habitRepository.getTodayCompletionCount(current.id) >= current.targetValue)
            CheckInResult.Success(completed, progress,
                allowGoal && current.targetCycles != null && progress >= current.targetCycles, followUp = view?.followUp)
        }

    private suspend fun currentHabit(expected: com.dayforge.data.local.entity.HabitEntity): com.dayforge.data.local.entity.HabitEntity {
        val current = requireNotNull(habitRepository.getHabitById(expected.id)) { "HABIT_NOT_FOUND" }
        check(current.uuid == expected.uuid && current.habitType == expected.habitType &&
            current.completionPolicy == expected.completionPolicy) { "HABIT_IDENTITY_CHANGED" }
        return current
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

    private fun requireCountScope(expected: com.dayforge.domain.model.CountActionIdentity,
        current: com.dayforge.domain.model.CountActionIdentity?) {
        // The write can legitimately reactivate a plan/update its rate. Never adopt another
        // account, device or round while constructing the post-commit outcome.
        check(current != null && expected.session == current.session && expected.deviceId == current.deviceId &&
            expected.habitId == current.habitId && expected.habitUuid == current.habitUuid &&
            expected.roundHead == current.roundHead) { "COUNT_ACTION_EXPIRED" }
    }
}
