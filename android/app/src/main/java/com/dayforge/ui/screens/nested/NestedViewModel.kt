package com.dayforge.ui.screens.nested

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.R
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import com.dayforge.domain.model.ActiveTimerState
import com.dayforge.domain.service.HabitCompletionCoordinator
import com.dayforge.domain.service.HabitDeletionCoordinator
import com.dayforge.domain.service.HabitLifecycleCoordinator
import com.dayforge.domain.service.HabitTimerCoordinator
import com.dayforge.domain.service.ReactivationResult
import com.dayforge.ui.components.LinkedMetricInfo
import com.dayforge.ui.components.MetricValueInput
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import com.dayforge.ui.metrics.LinkedMetricPromptState
import com.dayforge.util.DateTimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * Data class representing a child habit with its completion stats.
 * Used for displaying child habits in ParentHabitCard.
 *
 * Per NEST-04: Child habits have check-in support.
 */
data class ChildHabitWithStats(
    val habit: HabitEntity,
    val completedToday: Boolean,
    val todayCount: Int,
    val lastCompletionId: Long?,
    val currentStreak: Int,
    val bestStreak: Int,
    val activityRate: Int = 100,  // 活跃度 0-100
    val isCheckInAllowed: Boolean = true,
    val nextCheckInDate: LocalDate? = null,
    val targetProgress: Int = 0,  // Distinct days completed for habits with targetCycles
    val hasFailed: Boolean = false,  // Failure status for target-based habits
    val oneTimeStatus: com.dayforge.domain.model.OneTimeStatus? = null,
    val timerAuthority: com.dayforge.domain.model.TimerActionAuthority? = null,
    val countHistory: com.dayforge.domain.model.CountHistory? = null,
    val countActionIdentity: com.dayforge.domain.model.CountActionIdentity? = countHistory?.actionIdentity,
    val checkActionIdentity: com.dayforge.domain.model.CheckActionIdentity? = null
) {
    val actualTodayCount: Long get() = countHistory?.todayQuantity ?: todayCount.toLong()
    val displayTargetValue: Int get() = countHistory?.todayPolicy?.targetValue ?: habit.targetValue
    val displayIsCountdown: Boolean get() = countHistory?.todayPolicy?.isCountdown ?: habit.isCountdown
    val completedForDisplay: Boolean get() = oneTimeStatus?.completed ?: completedToday
    /**
     * Whether the goal has been completed (reached targetCycles and deactivated).
     * Used for CompletionButton to show "目标已完成" state.
     */
    val isGoalCompleted: Boolean
        get() = habit.targetCycles != null && targetProgress >= habit.targetCycles && !habit.isActive
}

/**
 * Data class representing a parent habit with its children and completion stats.
 * Used for displaying top-level habits in NestedScreen.
 *
 * Per NEST-02: Parent habits displayed with child lists.
 * Per NEST-05: Aggregated completion progress.
 */
data class ParentHabitWithChildren(
    val habit: HabitEntity,
    val children: List<ChildHabitWithStats>,
    val completedChildren: Int,
    val totalChildren: Int,
    val totalChildrenIncludingNonCheckInDays: Int,  // All children count (for UI display)
    val isCheckInAllowed: Boolean = true,   // True for Daily schedule, calculated for Weekly/Monthly/Custom
    val nextCheckInDate: LocalDate? = null, // Null if check-in allowed today, otherwise next valid check-in date
    val dayProgress: Int = 0  // Days since creation (for GOAL type display "第 X 天 / 共 Y 天")
)

/**
 * Result of attempting to start a timer.
 * Used to communicate to UI whether to show confirmation dialogs.
 */
sealed class StartTimerResult {
    object Started : StartTimerResult()
    object AlreadyCompleted : StartTimerResult()
    data class HasActiveTimer(val currentHabitId: Long) : StartTimerResult()
}

@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NestedViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val habitDao: HabitDao,
    private val timeLogDao: TimeLogDao,
    private val habitRepository: HabitRepository,
    private val nestedHabitTreeBuilder: NestedHabitTreeBuilder,
    private val completionCoordinator: HabitCompletionCoordinator,
    private val deletionCoordinator: HabitDeletionCoordinator,
    private val preferencesManager: PreferencesManager,
    private val metricCoordinator: LinkedMetricCoordinator,
    private val lifecycleCoordinator: HabitLifecycleCoordinator,
    private val timerCoordinator: HabitTimerCoordinator,
    private val countActions: com.dayforge.domain.service.CountActionGate? = null,
) : ViewModel() {

    companion object {
        private const val TAG = "NestedViewModel"
    }

    // Track if data has been loaded at least once
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    // Listen to all completions to trigger UI updates
    private val allCompletions = habitRepository.getAllCompletions()

    /**
     * Top-level habits (parentHabitId = null) with their children and stats.
     */
    private val readMonitor = com.dayforge.ui.DataReadMonitor(TAG)
    val readError = readMonitor.error
    fun retryRead() = readMonitor.retry()
    val topLevelHabitsWithChildren: StateFlow<List<ParentHabitWithChildren>> = readMonitor.recover(emptyList()) { combine(
        habitDao.getTopLevelHabits(),
        combine(allCompletions, habitRepository.oneTimeChanges, habitRepository.countChanges) { rows, _, _ -> rows },
        timeLogDao.getActiveTimeLogFlow(),
        preferencesManager.dateChangeTrigger
    ) { topLevelHabits, completions, _, _ -> topLevelHabits to completions }
        .mapLatest { (topLevelHabits, completions) ->
            Log.d(TAG, "topLevelHabitsWithChildren combine triggered: topLevelHabits=${topLevelHabits.size}")
            readMonitor.read(emptyList()) { nestedHabitTreeBuilder.build(topLevelHabits, completions) }
        } }
        .onEach { _isInitialized.value = true }
        .stateIn(
            scope = viewModelScope,
            started = com.dayforge.ui.VisibleScreenSharing,
            initialValue = emptyList()
        )

    /**
     * Active timer state for real-time UI updates.
     * Delegates to HabitTimerCoordinator for shared implementation.
     */
    val activeTimerState: StateFlow<ActiveTimerState?> =
        timerCoordinator.observeActiveTimer(viewModelScope)

    /**
     * Habits that have pending metric recording.
     */
    val pendingMetricHabits: StateFlow<Set<Long>> = metricCoordinator.pendingMetricHabits
        .stateIn(
            scope = viewModelScope,
            started = com.dayforge.ui.VisibleScreenSharing,
            initialValue = emptySet()
        )

    /**
     * Expanded parent habit UUIDs for persisting expand/collapse state.
     */
    val expandedParentUuids: StateFlow<Set<String>> = preferencesManager.expandedParentUuids
        .stateIn(
            scope = viewModelScope,
            started = com.dayforge.ui.VisibleScreenSharing,
            initialValue = emptySet()
        )



    /**
     * Linked metrics by habit ID.
     */
    val linkedMetricsByHabit: StateFlow<Map<Long, List<LinkedMetricInfo>>> =
        metricCoordinator.observeLinkedMetrics(
            habitIds = habitRepository.allHabits.map { habits -> habits.map { it.id }.toSet() },
            onlyShownInHabitDetail = false
        )
        .stateIn(
            scope = viewModelScope,
            started = com.dayforge.ui.VisibleScreenSharing,
            initialValue = emptyMap()
        )

    /**
     * State for the post-check-in dialog.
     */
    val postCheckInState: StateFlow<LinkedMetricPromptState?> = metricCoordinator.postCheckInState

    // ========== Goal Completion Dialog State ==========

    /**
     * State for the goal completion dialog shown when targetCycles is reached.
     * Per TARGET-08: Dialog appears when check-in reaches target cycles.
     */
    val showGoalDialog: StateFlow<Boolean> = lifecycleCoordinator.showGoalDialog
    val goalHabitId: StateFlow<Long?> = lifecycleCoordinator.goalHabitId
    val goalProgress: StateFlow<Int> = lifecycleCoordinator.goalProgress
    val goalTarget: StateFlow<Int> = lifecycleCoordinator.goalTarget

    // ========== Reactivation Dialog State ==========

    val showReactivationDialog: StateFlow<Boolean> = lifecycleCoordinator.showReactivationDialog
    val reactivationHabitId: StateFlow<Long?> = lifecycleCoordinator.reactivationHabitId
    val reactivationHabitName: StateFlow<String> = lifecycleCoordinator.reactivationHabitName
    val reactivationPreservesHistory: StateFlow<Boolean> = lifecycleCoordinator.reactivationPreservesHistory

    val showChildrenDialog = deletionCoordinator.pendingDeletion

    // ========== Check-in Operations ==========

    /**
     * Log a completion for a child habit.
     * Uses the shared completion coordinator for consistent behavior with DashboardViewModel.
     * Per TARGET-15: Triggers goal completion dialog if targetCycles reached.
     * Triggers post-check-in dialog for linked metrics.
     */
    fun logCompletion(habitId: Long, value: Int = 1) {
        val displayed = topLevelHabitsWithChildren.value.flatMap { it.children }.find { it.habit.id == habitId }
        val displayedHabit = displayed?.habit ?: findHabitById(habitId)
        if (displayedHabit?.habitType == HabitType.COUNTING) {
            launchCompletion(habitId, displayed?.countActionIdentity, isCount = true) {
                completionCoordinator.recordCompletion(habitId, value, displayed?.countActionIdentity)
            }
            return
        }
        launchCompletion(habitId, timing = com.dayforge.domain.service.CompletionActionTiming.Operation.CHECK_IN) {
            val prompt = metricCoordinator.captureCompletionPrompt()
            val outcome = completionCoordinator.checkIn(
                habitId = habitId,
                finalizeTemporaryTasks = false,
                displayedHabit = { displayedHabit },
                oneTimeAuthority = displayed?.oneTimeStatus?.authority,
                checkIdentity = displayed?.checkActionIdentity
            )
            if (publishCompletionFollowUp(outcome, displayedHabit, prompt)) return@launchCompletion
            outcome.goalProgress?.let { progress -> onGoalReached(displayedHabit, progress) }
            outcome.metricPromptHabit?.let { habit ->
                com.dayforge.domain.service.afterCommittedAction { checkAndShowPostCheckInDialog(habitId, habit.name) }
            }
        }
    }

    /**
     * Undo a completion for a child habit.
     */
    fun undoCompletion(completionId: Long) {
        val displayed = topLevelHabitsWithChildren.value.flatMap { it.children }
            .find { it.lastCompletionId == completionId } ?: return
        val authority = displayed.oneTimeStatus?.authority
        val checkIdentity = displayed.checkActionIdentity
        launchCompletion(displayed.habit.id, displayed.countActionIdentity, displayed.habit.habitType == HabitType.COUNTING) {
            check(displayed.habit.appearance == null || displayed.habit.completionPolicy != "recurring" ||
                displayed.habit.habitType != HabitType.CHECK_IN || checkIdentity != null) { "CHECK_ACTION_REQUIRED" }
            completionCoordinator.undoCompletion(completionId, authority, displayed.countActionIdentity, checkIdentity = checkIdentity)
        }
    }

    private val completionActions = com.dayforge.ui.InFlightActions()
    val busyHabitIds = countActions?.let { gate ->
        combine(completionActions.active, gate.busyHabitIds) { local, shared -> local + shared }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptySet())
    } ?: completionActions.active

    private fun launchCompletion(habitId: Long, identity: com.dayforge.domain.model.CountActionIdentity? = null,
        isCount: Boolean = false, timing: com.dayforge.domain.service.CompletionActionTiming.Operation? = null,
        block: suspend () -> Unit) {
        val operation: suspend () -> Unit = {
            try {
                if (timing == null) block() else com.dayforge.domain.service.CompletionActionTiming.withOperation(timing, block)
            }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.e(TAG, "Completion operation failed", error)
                val message = com.dayforge.ui.actionFailureMessage(context, error)
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        if (isCount && countActions != null) {
            if (identity != null) countActions.launch(viewModelScope, identity, operation)
        } else completionActions.launch(viewModelScope, habitId, block = operation)
    }

    /**
     * Increment count for a COUNTING habit.
     * Uses the shared completion coordinator for consistent behavior across habit screens.
     * Triggers goal completion dialog if targetCycles reached.
     * Triggers post-check-in dialog for linked metrics.
     */
    fun incrementCount(habitId: Long) {
        val displayed = topLevelHabitsWithChildren.value.flatMap { it.children }.find { it.habit.id == habitId } ?: return
        launchCompletion(habitId, displayed.countActionIdentity, isCount = true,
            timing = com.dayforge.domain.service.CompletionActionTiming.Operation.COUNT_INCREMENT) {
            val prompt = metricCoordinator.captureCompletionPrompt()
            val outcome = completionCoordinator.incrementCount(habitId, displayed.countActionIdentity) {
                displayed.habit
            }
            if (countActions != null && displayed.countActionIdentity?.let(countActions::isCurrent) != true) return@launchCompletion
            if (publishCompletionFollowUp(outcome, displayed.habit, prompt)) return@launchCompletion
            outcome.goalProgress?.let { progress -> onGoalReached(displayed.habit, progress) }
            outcome.metricPromptHabit?.let { habit ->
                com.dayforge.domain.service.afterCommittedAction {
                    if (displayed.countActionIdentity != null) metricCoordinator.showCountPrompt(displayed.countActionIdentity, habit.name)
                    else checkAndShowPostCheckInDialog(habitId, habit.name)
                }
            }
        }
    }

    /**
     * Decrement count for a COUNTING habit.
     * Uses the shared completion coordinator for consistent behavior across habit screens.
     */
    fun decrementCount(habitId: Long) {
        val displayed = topLevelHabitsWithChildren.value.flatMap { it.children }.find { it.habit.id == habitId } ?: return
        launchCompletion(habitId, displayed.countActionIdentity, isCount = true,
            timing = com.dayforge.domain.service.CompletionActionTiming.Operation.COUNT_DECREMENT) {
            completionCoordinator.decrementCount(habitId, displayed.countActionIdentity).goalProgress
                ?.let { progress -> onGoalReached(displayed.habit, progress) }
        }
    }

    // ========== Timer Operations ==========

    /**
     * Check if can start a timer. Returns result for UI to handle.
     *
     * @return StartTimerResult indicating whether to start, already completed, or has active timer
     */
    fun checkStartTimer(habitId: Long, targetMinutes: Int): StartTimerResult {
        val currentTimer = activeTimerState.value

        // Check if there's an active timer for a different habit
        if (currentTimer != null && currentTimer.habitId != habitId) {
            return StartTimerResult.HasActiveTimer(currentTimer.habitId)
        }

        // Check if already completed today
        viewModelScope.launch {
            val todayStart = DateTimeUtils.startOfDayMillis()
            val todayEnd = DateTimeUtils.startOfNextDayMillis(todayStart)
            val completedSeconds = timeLogDao.getCompletedDurationSecondsForDate(
                habitId, java.time.LocalDate.now().toString(), todayStart, todayEnd
            )
            val targetSeconds = targetMinutes * 60

            if (completedSeconds >= targetSeconds) {
                // Already completed - UI should handle this
            }
        }

        return StartTimerResult.Started
    }

    /**
     * Start a timer for a child habit.
     * Delegates to HabitTimerCoordinator for shared implementation.
     */
    fun startTimer(habitId: Long, targetMinutes: Int) {
        val authority = topLevelHabitsWithChildren.value.flatMap { it.children }
            .find { it.habit.id == habitId }?.timerAuthority
        launchCompletion(habitId) {
            try { timerCoordinator.startTimer(habitId, targetMinutes, authority) }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Toast.makeText(context, context.getString(R.string.metric_error_record_failed, error.message.orEmpty()), Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Pause the currently running timer.
     * Delegates to HabitTimerCoordinator for shared implementation.
     */
    fun pauseTimer() {
        timerCoordinator.pauseTimer(activeTimerState.value)
    }

    /**
     * Resume a paused timer.
     * Delegates to HabitTimerCoordinator for shared implementation.
     */
    fun resumeTimer() {
        timerCoordinator.resumeTimer(activeTimerState.value)
    }

    /**
     * Stop the currently running timer.
     * Delegates to HabitTimerCoordinator for shared implementation.
     */
    fun stopTimer() {
        val currentState = activeTimerState.value ?: return
        launchCompletion(currentState.habitId) {
            try {
                metricCoordinator.showPromptAfterTimerStop(
                    timerCoordinator.stopTimer(currentState), currentState.authority
                )
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Toast.makeText(context, context.getString(R.string.metric_error_record_failed, error.message.orEmpty()), Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Check if a specific habit has the active timer.
     */
    fun isHabitTimerActive(habitId: Long): Boolean {
        return timerCoordinator.isHabitTimerActive(activeTimerState.value, habitId)
    }

    // ========== Battery Optimization ==========

    /**
     * Check if battery optimization guidance should be shown.
     */
    suspend fun shouldShowBatteryGuidance(): Boolean {
        return !preferencesManager.hasShownBatteryGuidance.first()
    }

    /**
     * Mark battery optimization guidance as shown.
     */
    suspend fun markBatteryGuidanceShown() {
        preferencesManager.setBatteryGuidanceShown()
    }

    // ========== Post-Check-In Dialog ==========

    /**
     * Called after a habit check-in to potentially show the metric recording dialog.
     */
    suspend fun checkAndShowPostCheckInDialog(habitId: Long, habitName: String) {
        metricCoordinator.showPromptIfNeeded(habitId, habitName)
    }

    /**
     * Record a metric value from the post-check-in dialog.
     */
    suspend fun recordMetricValues(
        habitId: Long,
        values: List<MetricValueInput>,
        expectedEventUuid: String? = null,
        expectedTimerPrompt: com.dayforge.ui.metrics.TimerMetricPrompt? = null,
        expectedPreferenceClaim: com.dayforge.data.repository.HabitPreferenceClaim? = null,
        expectedFactPrompt: com.dayforge.ui.metrics.FactMetricPrompt? = null
    ): Boolean = metricCoordinator.recordMetricValues(habitId, values, expectedEventUuid, expectedTimerPrompt,
        expectedFactPrompt = expectedFactPrompt, expectedPreferenceClaim = expectedPreferenceClaim)

    /**
     * Set "never ask again" preference for a habit's metric prompt.
     */
    suspend fun setNeverAskAgain(habitId: Long, value: Boolean, expectedEventUuid: String? = null,
        expectedTimerPrompt: com.dayforge.ui.metrics.TimerMetricPrompt? = null,
        expectedPreferenceClaim: com.dayforge.data.repository.HabitPreferenceClaim? = null,
        expectedFactPrompt: com.dayforge.ui.metrics.FactMetricPrompt? = null) {
        metricCoordinator.setNeverAskAgain(habitId, value, expectedEventUuid, expectedTimerPrompt,
            expectedFactPrompt = expectedFactPrompt, expectedPreferenceClaim = expectedPreferenceClaim)
    }

    /**
     * Dismiss the post-check-in dialog.
     */
    suspend fun dismissPostCheckInDialog(expected: LinkedMetricPromptState? = null) = metricCoordinator.closePrompt(expected)

    suspend fun skipPostCheckInDialog(habitId: Long, expectedEventUuid: String? = null,
        expectedTimerPrompt: com.dayforge.ui.metrics.TimerMetricPrompt? = null,
        expectedPreferenceClaim: com.dayforge.data.repository.HabitPreferenceClaim? = null,
        expectedFactPrompt: com.dayforge.ui.metrics.FactMetricPrompt? = null) =
        metricCoordinator.skipPrompt(habitId, expectedEventUuid, expectedTimerPrompt,
            expectedFactPrompt = expectedFactPrompt, expectedPreferenceClaim = expectedPreferenceClaim)

    suspend fun refreshPostCheckInMetadata() {
        run {
            try { metricCoordinator.refreshPrompt() }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                android.widget.Toast.makeText(context, context.getString(com.dayforge.R.string.metric_error_record_failed,
                    error.message.orEmpty()), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    suspend fun savePostCheckInDraft(eventUuid: String, inputs: List<com.dayforge.ui.components.MetricInputState>) =
        metricCoordinator.savePromptDraft(eventUuid, inputs)

    // ========== Goal Completion Dialog Methods ==========

    /**
     * Called when a habit reaches its targetCycles.
     * Per TARGET-08: Shows goal completion dialog.
     *
     * @param habitId The ID of the habit that reached its target
     * @param progress The current progress (distinct days count)
     */
    private fun onGoalReached(habit: com.dayforge.data.local.entity.HabitEntity?, progress: Int) {
        lifecycleCoordinator.showGoalCompletion(habit, progress)
    }

    private suspend fun publishCompletionFollowUp(outcome: com.dayforge.domain.service.HabitCompletionOutcome,
        displayed: com.dayforge.data.local.entity.HabitEntity?,
        prompt: com.dayforge.ui.metrics.CompletionPromptRequest): Boolean {
        val snapshot = outcome.followUp
        if (snapshot == null) {
            check(displayed?.appearance == null || displayed.completionPolicy != "recurring" ||
                outcome.goalProgress == null && outcome.metricPromptHabit == null) { "COMPLETION_FOLLOW_UP_REQUIRED" }
            return false
        }
        com.dayforge.domain.service.afterCommittedAction {
            if (outcome.goalProgress != null) lifecycleCoordinator.showGoalCompletion(snapshot,
                isCurrent = { metricCoordinator.isCurrentCompletionPrompt(prompt) }) { next, isCurrent ->
                if (outcome.metricPromptHabit != null) metricCoordinator.showCompletionPrompt(next, prompt, isCurrent)
            } else if (outcome.metricPromptHabit != null) metricCoordinator.showCompletionPrompt(snapshot, prompt)
        }
        return true
    }

    /**
     * Confirms goal completion, setting habit isActive = false.
     * Per TARGET-08: User can confirm to mark habit as complete.
     */
    fun confirmGoalCompletion() {
        val habitId = lifecycleCoordinator.goalHabitId.value ?: return
        val confirm = lifecycleCoordinator.captureGoalCompletion(complete = true)
        launchCompletion(habitId) {
            if (confirm()) {
                Toast.makeText(context, context.getString(R.string.toast_habit_completed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Dismisses the goal completion dialog without completing the habit.
     * Per TARGET-08: User can continue tracking if they dismiss.
     * Switches to LOOSE mode to prevent failure detection for the continued cycle.
     * Dialog will reappear on next goal-reaching check-in.
     */
    fun dismissGoalDialog() {
        val habitId = lifecycleCoordinator.goalHabitId.value ?: return
        val dismiss = lifecycleCoordinator.captureGoalCompletion(complete = false)
        launchCompletion(habitId) {
            dismiss()
        }
    }

    // ========== Reactivation Dialog Methods ==========

    /**
     * Shows reactivation dialog for a failed habit.
     * Called when user taps on "已失败" status.
     */
    fun showReactivationDialog(habitId: Long) {
        val habit = findHabitById(habitId)
        viewModelScope.launch {
            try { lifecycleCoordinator.showReactivationDialog(habit) }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Toast.makeText(context, context.getString(R.string.restart_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Confirms the captured restart; challenge profiles preserve history.
     */
    fun confirmReactivation() {
        val habitId = lifecycleCoordinator.reactivationHabitId.value ?: return
        val habit = findHabitById(habitId)
        val preservesHistory = lifecycleCoordinator.reactivationPreservesHistory.value
        val confirmation = lifecycleCoordinator.captureReactivation(habit)
        launchCompletion(habitId) {
            when (confirmation()) {
                ReactivationResult.SUCCESS ->
                    Toast.makeText(context, context.getString(if (preservesHistory) R.string.restart_completed
                        else R.string.toast_habit_reactivated), Toast.LENGTH_SHORT).show()
                ReactivationResult.FAILURE ->
                    Toast.makeText(context, context.getString(R.string.restart_failed), Toast.LENGTH_SHORT).show()
                ReactivationResult.NO_HABIT -> Unit
            }
        }
    }

    /**
     * Dismisses the reactivation dialog.
     */
    fun dismissReactivationDialog() {
        if (lifecycleCoordinator.reactivationHabitId.value in busyHabitIds.value) return
        lifecycleCoordinator.dismissReactivationDialog()
    }

    // ========== Habit Delete ==========

    /**
     * Delete a child habit.
     */
    fun deleteHabit(habit: HabitEntity) {
        runDeletion(habit.id) {
            deletionCoordinator.requestDeletion(habit)
        }
    }

    fun deleteHabitWithChildren() {
        runDeletion(deletionCoordinator.pendingDeletion.value?.habit?.id ?: return) {
            deletionCoordinator.deleteWithChildren()
        }
    }

    fun deleteHabitKeepChildren() {
        runDeletion(deletionCoordinator.pendingDeletion.value?.habit?.id ?: return) {
            deletionCoordinator.deleteKeepingChildren()
        }
    }

    fun dismissChildrenDialog() {
        if (deletionCoordinator.pendingDeletion.value?.habit?.id in busyHabitIds.value) return
        deletionCoordinator.dismissDeletion()
    }

    private fun runDeletion(habitId: Long, action: suspend () -> Unit) {
        launchCompletion(habitId) {
            try { action() } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Toast.makeText(context, context.getString(R.string.goal_delete_failed, error.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ========== Expand State Management ==========

    /**
     * Toggle expand state for a parent habit.
     * Persists the change to DataStore.
     */
    fun toggleParentExpand(uuid: String) {
        viewModelScope.launch {
            val current = expandedParentUuids.value
            val newSet = if (uuid in current) {
                current - uuid
            } else {
                current + uuid
            }
            preferencesManager.setExpandedParentUuids(newSet)
        }
    }

    // ========== Helper Methods ==========

    private fun findHabitById(habitId: Long): HabitEntity? {
        return topLevelHabitsWithChildren.value
            .flatMap { it.children }
            .find { it.habit.id == habitId }
            ?.habit
    }

}
