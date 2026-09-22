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
import com.dayforge.domain.model.CardColorStyle
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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
    val hasFailed: Boolean = false  // Failure status for target-based habits
) {
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
    val topLevelHabitsWithChildren: StateFlow<List<ParentHabitWithChildren>> = combine(
        habitDao.getTopLevelHabits(),
        allCompletions,
        timeLogDao.getActiveTimeLogFlow(),
        preferencesManager.dateChangeTrigger  // Triggers when date changes
    ) { topLevelHabits, completions, activeTimeLog, _ ->
        Log.d(TAG, "topLevelHabitsWithChildren combine triggered: topLevelHabits=${topLevelHabits.size}")
        nestedHabitTreeBuilder.build(topLevelHabits, completions)
    }
        .onEach { _isInitialized.value = true }
        .stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
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
            started = SharingStarted.Lazily,
            initialValue = emptySet()
        )

    /**
     * Expanded parent habit UUIDs for persisting expand/collapse state.
     */
    val expandedParentUuids: StateFlow<Set<String>> = preferencesManager.expandedParentUuids
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = emptySet()
        )

    /**
     * Card color style preference for reactive card rendering.
     * Per CARD-09: Triggers instant recomposition when style changes.
     */
    val cardColorStyle: StateFlow<CardColorStyle> = preferencesManager.cardColorStyle
        .map { CardColorStyle.fromStringOrDefault(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = CardColorStyle.DEFAULT
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
            started = SharingStarted.Lazily,
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

    val showChildrenDialog = deletionCoordinator.pendingDeletion

    // ========== Check-in Operations ==========

    /**
     * Log a completion for a child habit.
     * Uses the shared completion coordinator for consistent behavior with DashboardViewModel.
     * Per TARGET-15: Triggers goal completion dialog if targetCycles reached.
     * Triggers post-check-in dialog for linked metrics.
     */
    fun logCompletion(habitId: Long, value: Int = 1) {
        viewModelScope.launch {
            val outcome = completionCoordinator.checkIn(
                habitId = habitId,
                finalizeTemporaryTasks = false,
                displayedHabit = { findHabitById(habitId) }
            )
            outcome.goalProgress?.let { progress -> onGoalReached(habitId, progress) }
            outcome.metricPromptHabit?.let { habit ->
                checkAndShowPostCheckInDialog(habitId, habit.name)
            }
        }
    }

    /**
     * Undo a completion for a child habit.
     */
    fun undoCompletion(completionId: Long) {
        viewModelScope.launch {
            completionCoordinator.undoCompletion(completionId)
        }
    }

    /**
     * Increment count for a COUNTING habit.
     * Uses the shared completion coordinator for consistent behavior across habit screens.
     * Triggers goal completion dialog if targetCycles reached.
     * Triggers post-check-in dialog for linked metrics.
     */
    fun incrementCount(habitId: Long) {
        viewModelScope.launch {
            val outcome = completionCoordinator.incrementCount(habitId) {
                findHabitById(habitId)
            }
            outcome.goalProgress?.let { progress -> onGoalReached(habitId, progress) }
            outcome.metricPromptHabit?.let { habit ->
                checkAndShowPostCheckInDialog(habitId, habit.name)
            }
        }
    }

    /**
     * Decrement count for a COUNTING habit.
     * Uses the shared completion coordinator for consistent behavior across habit screens.
     */
    fun decrementCount(habitId: Long) {
        viewModelScope.launch {
            completionCoordinator.decrementCount(habitId).goalProgress
                ?.let { progress -> onGoalReached(habitId, progress) }
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
        viewModelScope.launch {
            timerCoordinator.startTimer(habitId, targetMinutes)
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
        viewModelScope.launch {
            metricCoordinator.showPromptAfterTimerStop(
                timerCoordinator.stopTimer(currentState)
            )
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
        values: List<MetricValueInput>
    ): Boolean = metricCoordinator.recordMetricValues(habitId, values)

    /**
     * Set "never ask again" preference for a habit's metric prompt.
     */
    suspend fun setNeverAskAgain(habitId: Long, value: Boolean) {
        metricCoordinator.setNeverAskAgain(habitId, value)
    }

    /**
     * Dismiss the post-check-in dialog.
     */
    fun dismissPostCheckInDialog() {
        metricCoordinator.dismissPrompt()
    }

    // ========== Goal Completion Dialog Methods ==========

    /**
     * Called when a habit reaches its targetCycles.
     * Per TARGET-08: Shows goal completion dialog.
     *
     * @param habitId The ID of the habit that reached its target
     * @param progress The current progress (distinct days count)
     */
    private fun onGoalReached(habitId: Long, progress: Int) {
        val habit = findHabitById(habitId)
        lifecycleCoordinator.showGoalCompletion(habit, progress)
    }

    /**
     * Confirms goal completion, setting habit isActive = false.
     * Per TARGET-08: User can confirm to mark habit as complete.
     */
    fun confirmGoalCompletion() {
        viewModelScope.launch {
            if (lifecycleCoordinator.confirmGoalCompletion()) {
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
        viewModelScope.launch {
            val habitId = lifecycleCoordinator.goalHabitId.value ?: return@launch
            val habit = habitDao.getHabitById(habitId)
            lifecycleCoordinator.dismissGoalDialog(habit)
        }
    }

    // ========== Reactivation Dialog Methods ==========

    /**
     * Shows reactivation dialog for a failed habit.
     * Called when user taps on "已失败" status.
     */
    fun showReactivationDialog(habitId: Long) {
        val habit = findHabitById(habitId)
        lifecycleCoordinator.showReactivationDialog(habit)
    }

    /**
     * Confirms reactivation: clears history and reactivates the habit.
     */
    fun confirmReactivation() {
        viewModelScope.launch {
            val habitId = lifecycleCoordinator.reactivationHabitId.value ?: return@launch
            val habit = findHabitById(habitId)
            when (lifecycleCoordinator.confirmReactivation(habit)) {
                ReactivationResult.SUCCESS ->
                    Toast.makeText(context, context.getString(R.string.toast_habit_reactivated), Toast.LENGTH_SHORT).show()
                ReactivationResult.FAILURE ->
                    Toast.makeText(context, context.getString(R.string.toast_clear_history_failed), Toast.LENGTH_SHORT).show()
                ReactivationResult.NO_HABIT -> Unit
            }
        }
    }

    /**
     * Dismisses the reactivation dialog.
     */
    fun dismissReactivationDialog() {
        lifecycleCoordinator.dismissReactivationDialog()
    }

    // ========== Habit Delete ==========

    /**
     * Delete a child habit.
     */
    fun deleteHabit(habit: HabitEntity) {
        viewModelScope.launch {
            deletionCoordinator.requestDeletion(habit)
        }
    }

    fun deleteHabitWithChildren() {
        viewModelScope.launch {
            deletionCoordinator.deleteWithChildren()
        }
    }

    fun deleteHabitKeepChildren() {
        viewModelScope.launch {
            deletionCoordinator.deleteKeepingChildren()
        }
    }

    fun dismissChildrenDialog() {
        deletionCoordinator.dismissDeletion()
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
