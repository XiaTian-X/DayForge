package com.dayforge.domain.service

import android.content.Context
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.repository.HabitRepository
import com.dayforge.data.repository.WidgetFactSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared goal-completion and reactivation state for habit list screens. */
class HabitLifecycleCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val habitRepository: HabitRepository
) {
    private val _showGoalDialog = MutableStateFlow(false)
    val showGoalDialog: StateFlow<Boolean> = _showGoalDialog.asStateFlow()

    private val _goalHabitId = MutableStateFlow<Long?>(null)
    val goalHabitId: StateFlow<Long?> = _goalHabitId.asStateFlow()

    private val _goalProgress = MutableStateFlow(0)
    val goalProgress: StateFlow<Int> = _goalProgress.asStateFlow()

    private val _goalTarget = MutableStateFlow(0)
    val goalTarget: StateFlow<Int> = _goalTarget.asStateFlow()
    private class GoalRequest(val habit: HabitEntity, val snapshot: WidgetFactSnapshot? = null,
        val followUp: (suspend (WidgetFactSnapshot, () -> Boolean) -> Unit)? = null)
    private val presentationMonitor = Any()
    private val goalGeneration = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var blocked = false
    @Volatile private var goalRequest: GoalRequest? = null
    private val invalidator = object : com.dayforge.data.local.PersonalDataDisplayMemory.Listener {
        override fun transition(blocked: Boolean) {
            synchronized(presentationMonitor) {
                this@HabitLifecycleCoordinator.blocked = blocked
                clearGoalDialog()
                clearReactivationDialog()
            }
        }
    }

    private val _showReactivationDialog = MutableStateFlow(false)
    val showReactivationDialog: StateFlow<Boolean> = _showReactivationDialog.asStateFlow()

    private val _reactivationHabitId = MutableStateFlow<Long?>(null)
    val reactivationHabitId: StateFlow<Long?> = _reactivationHabitId.asStateFlow()

    private val _reactivationHabitName = MutableStateFlow("")
    val reactivationHabitName: StateFlow<String> = _reactivationHabitName.asStateFlow()
    @Volatile private var reactivationRequest: com.dayforge.data.repository.HabitReactivationRequest? = null
    private var reactivationGeneration = 0
    private val _reactivationPreservesHistory = MutableStateFlow(false)
    val reactivationPreservesHistory: StateFlow<Boolean> = _reactivationPreservesHistory.asStateFlow()

    init { habitRepository.registerCompletionConsumer(invalidator) }

    fun showGoalCompletion(habit: HabitEntity?, progress: Int) {
        check(habit?.appearance == null) { "COMPLETION_FOLLOW_UP_REQUIRED" }
        val target = habit?.targetCycles ?: return
        synchronized(presentationMonitor) {
            if (blocked) return
            goalGeneration.incrementAndGet()
            goalRequest = GoalRequest(habit)
            publishGoal(habit, progress, target)
        }
    }

    suspend fun showGoalCompletion(snapshot: WidgetFactSnapshot,
        isCurrent: () -> Boolean = { true },
        followUp: suspend (WidgetFactSnapshot, () -> Boolean) -> Unit) {
        val target = requireNotNull(snapshot.habit.targetCycles)
        check(snapshot.targetProgress >= target) { "COMPLETION_GOAL_CHANGED" }
        val generation = goalGeneration.incrementAndGet()
        val request = GoalRequest(snapshot.habit, snapshot, followUp)
        habitRepository.publishCompletion(snapshot, requireUnchangedProgress = true) {
            synchronized(presentationMonitor) {
                if (!blocked && generation == goalGeneration.get() && isCurrent()) {
                    goalRequest = request
                    publishGoal(snapshot.habit, snapshot.targetProgress, target)
                }
            }
        }
    }

    private fun publishGoal(habit: HabitEntity, progress: Int, target: Int) {
        _goalHabitId.value = habit.id
        _goalProgress.value = progress
        _goalTarget.value = target
        // Visibility is the commit signal consumed by the UI. Publish it only
        // after the dialog payload is complete.
        _showGoalDialog.value = true
    }

    /** Capture before coroutine dispatch; an older button cannot adopt a newer dialog. */
    fun captureGoalCompletion(complete: Boolean): suspend () -> Boolean {
        val request = synchronized(presentationMonitor) { goalRequest.takeUnless { blocked } }
        return { applyGoal(request, complete) }
    }

    suspend fun confirmGoalCompletion(): Boolean = applyGoal(goalRequest, true)

    suspend fun dismissGoalDialog(habit: HabitEntity?) {
        val request = goalRequest ?: return
        if (habit != null) check(habit.id == request.habit.id && habit.uuid == request.habit.uuid) {
            "COMPLETION_GOAL_CHANGED"
        }
        applyGoal(request, false)
    }

    private suspend fun applyGoal(request: GoalRequest?, complete: Boolean): Boolean {
        if (request == null || blocked || goalRequest !== request) return false
        val next = request.snapshot?.let { habitRepository.applyCompletionGoal(context, it, complete) }
        if (request.snapshot == null) {
            if (complete) habitRepository.updateIsActive(request.habit.id, false, context)
            else if (request.habit.failMode == FailMode.STRICT)
                habitRepository.updateFailMode(request.habit.id, FailMode.LOOSE, context)
        }
        return afterCommittedAction {
            val current = synchronized(presentationMonitor) {
                if (blocked || goalRequest !== request) false else { _showGoalDialog.value = false; true }
            }
            if (!current) return@afterCommittedAction false
            if (next != null) request.followUp?.invoke(next) { !blocked && goalRequest === request }
            synchronized(presentationMonitor) { if (goalRequest === request) clearGoalDialog() }
            true
        }
    }

    private fun clearGoalDialog() {
        goalGeneration.incrementAndGet()
        goalRequest = null
        _showGoalDialog.value = false
        _goalHabitId.value = null
        _goalProgress.value = 0
        _goalTarget.value = 0
    }

    suspend fun showReactivationDialog(habit: HabitEntity?) {
        habit ?: return
        val generation = synchronized(presentationMonitor) { ++reactivationGeneration }
        val request = habitRepository.prepareReactivation(habit)
        synchronized(presentationMonitor) {
            if (blocked || generation != reactivationGeneration) return
            reactivationRequest = request
            _reactivationPreservesHistory.value = request.preservesHistory
            _reactivationHabitId.value = habit.id
            _reactivationHabitName.value = habit.name
            // Keep the visible state from exposing an empty/stale payload.
            _showReactivationDialog.value = true
        }
    }

    /** Capture at the button click, not later when its coroutine is dispatched. */
    fun captureReactivation(habit: HabitEntity?): suspend () -> ReactivationResult {
        val request = reactivationRequest
        return { confirmReactivation(habit, request) }
    }

    suspend fun confirmReactivation(habit: HabitEntity?): ReactivationResult =
        confirmReactivation(habit, reactivationRequest)

    private suspend fun confirmReactivation(habit: HabitEntity?,
        captured: com.dayforge.data.repository.HabitReactivationRequest?): ReactivationResult {
        val request = captured ?: return ReactivationResult.NO_HABIT
        val result = if (habit == null || habit.id != request.habit.id || habit.uuid != request.habit.uuid) {
            ReactivationResult.NO_HABIT
        } else {
            try {
                habitRepository.reactivate(request, context)
                ReactivationResult.SUCCESS
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                ReactivationResult.FAILURE
            }
        }
        synchronized(presentationMonitor) { if (reactivationRequest === request) clearReactivationDialog() }
        return result
    }

    fun dismissReactivationDialog() {
        synchronized(presentationMonitor) { clearReactivationDialog() }
    }

    private fun clearReactivationDialog() {
        reactivationGeneration++
        reactivationRequest = null
        _showReactivationDialog.value = false
        _reactivationHabitId.value = null
        _reactivationHabitName.value = ""
        _reactivationPreservesHistory.value = false
    }
}

enum class ReactivationResult {
    SUCCESS,
    FAILURE,
    NO_HABIT
}
