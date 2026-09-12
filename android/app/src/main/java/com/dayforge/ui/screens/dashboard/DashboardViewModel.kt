package com.dayforge.ui.screens.dashboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.R
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.model.HabitType
import com.dayforge.data.model.HabitWithStats
import com.dayforge.data.repository.HabitRepository
import com.dayforge.data.repository.MetricRepository
import com.dayforge.domain.model.CardColorStyle
import com.dayforge.domain.model.FilterMode
import com.dayforge.domain.model.MetricWithLatestValue
import com.dayforge.domain.model.ActiveTimerState
import com.dayforge.domain.service.HabitCompletionCoordinator
import com.dayforge.domain.service.HabitDeletionCoordinator
import com.dayforge.domain.service.HabitLifecycleCoordinator
import com.dayforge.domain.service.HabitTimerCoordinator
import com.dayforge.domain.service.MetricOverviewProvider
import com.dayforge.domain.service.ReactivationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.ZoneId
import com.dayforge.ui.components.LinkedMetricInfo
import com.dayforge.ui.components.MetricValueInput
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import com.dayforge.ui.metrics.LinkedMetricPromptState
import com.dayforge.util.DateTimeUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val habitRepository: HabitRepository,
    private val completionCoordinator: HabitCompletionCoordinator,
    private val deletionCoordinator: HabitDeletionCoordinator,
    private val dashboardHabitListBuilder: DashboardHabitListBuilder,
    private val dashboardTimeWindowTicker: DashboardTimeWindowTicker,
    private val lifecycleCoordinator: HabitLifecycleCoordinator,
    private val timerCoordinator: HabitTimerCoordinator,
    private val timeLogDao: TimeLogDao,
    private val habitDao: com.dayforge.data.local.dao.HabitDao,
    private val preferencesManager: PreferencesManager,
    private val completionDao: CompletionDao,
    private val metricRepository: MetricRepository,
    private val linkedMetricCoordinator: LinkedMetricCoordinator,
    private val metricOverviewProvider: MetricOverviewProvider
) : ViewModel() {

    companion object {
        private const val TAG = "DashboardViewModel"
    }

    // Track if data has been loaded at least once
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val timeWindowTickFlow = dashboardTimeWindowTicker.observe()

    init {
        viewModelScope.launch {
            // Per D-05: recovery is automatic without user confirmation.
            timerCoordinator.recoverRunningTimer()
        }
    }

    // Listen to all completions to trigger UI updates when打卡 happens
    private val allCompletions = habitRepository.getAllCompletions()

    /**
     * Habits that need metric recording (completed timer habits with unrecorded prompt metrics).
     * Shows a "Record" button on the habit card.
     *
     * This is now read from DataStore where TimerService stores the pending habits.
     * This approach is more reliable than Flow calculation which could miss updates
     * when the app is in the background or when multiple habits share the same metric.
     */
    val pendingMetricHabits: StateFlow<Set<Long>> = linkedMetricCoordinator.pendingMetricHabits
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = emptySet()
        )

    // Combine habits, completions, time logs, date change trigger, and filter mode.
    // For TIMER habits, todayCount and streaks come from TimeLogEntity, not CompletionEntity
    // Use getActiveTimeLogFlow() as trigger - it changes when timer starts (INSERT) or stops (UPDATE sets endTime)
    // Use dateChangeTrigger to refresh when date changes (user opens app on new day)
    // Use filterMode to switch between different display modes (all, time_window, checkable, terminated)
    private val baseCombineFlow = combine(
        habitRepository.allHabits,
        allCompletions,
        timeLogDao.getActiveTimeLogFlow(),  // Triggers when timer state changes (start/stop)
        preferencesManager.dateChangeTrigger,  // Triggers when date changes
        preferencesManager.filterMode  // Triggers when filter mode changes
    ) { habits, completions, _, _, filterModeValue ->
        DashboardHabitListInput(
            habits = habits,
            completions = completions,
            filterMode = FilterMode.fromValue(filterModeValue)
        )
    }

    val habitsWithStats: StateFlow<List<HabitWithStats>> = combine(
        baseCombineFlow,
        timeWindowTickFlow,
        pendingMetricHabits
    ) { input, _, pendingMetricHabitIds ->
        Log.d(
            TAG,
            "habitsWithStats combine triggered: habits=${input.habits.size}, " +
                "completions=${input.completions.size}, filterMode=${input.filterMode.value}"
        )
        dashboardHabitListBuilder.build(
            habits = input.habits,
            completions = input.completions,
            filterMode = input.filterMode,
            currentTime = ZonedDateTime.now(ZoneId.systemDefault()),
            pendingMetricHabitIds = pendingMetricHabitIds
        )
    }
        .onEach { _isInitialized.value = true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,  // Changed from WhileSubscribed to ensure flow stays active
            initialValue = emptyList()
        )

    // Today's progress: Pair of (completed count, total count)
    // Derives from habitsWithStats to ensure TIMER habits use TimeLogEntity data
    // Per D-10: Only counts habits, NOT metrics
    // Filters out non-check-in days, failed habits, and goal-completed habits
    val todayProgress: StateFlow<Pair<Int, Int>> = habitsWithStats.map { habits ->
        val eligibleHabits = habits.filter { it.shouldCountToday }
        val completed = eligibleHabits.count { it.completedToday }
        Pair(completed, eligibleHabits.size)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,  // Changed from WhileSubscribed to ensure flow stays active
        initialValue = Pair(0, 0)
    )

    /**
     * Metrics with their latest values for main screen display.
     * Per METRIC-07: Shows active metrics independently on main screen.
     * Per D-09: Appears below habits section with title separator.
     *
     * Combines metrics with latest log changes to ensure UI refreshes when new logs are recorded.
     */
    val metricsWithLatest: StateFlow<List<MetricWithLatestValue>> = metricOverviewProvider.observe()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,  // Changed from WhileSubscribed to ensure flow stays active
            initialValue = emptyList()
        )

    /**
     * Linked metrics for all habits, keyed by habit ID.
     * Per METRIC-08: Users can see linked metrics under habit cards.
     *
     * Uses habitsWithStats, metric logs, and link changes as triggers to recalculate
     * when habits, logs, or links change. This ensures the UI refreshes when
     * habit-metric links are added/updated during sync.
     */
    val linkedMetricsByHabit: StateFlow<Map<Long, List<LinkedMetricInfo>>> =
        linkedMetricCoordinator.observeLinkedMetrics(
            habitIds = habitsWithStats.map { habits -> habits.map { it.habit.id }.toSet() },
            onlyShownInHabitDetail = true
        ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,  // Changed from WhileSubscribed to ensure flow stays active
        initialValue = emptyMap()
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
     * Filter mode state for reactive UI updates.
     * Controls which habits are displayed and how they are sorted.
     */
    val filterMode: StateFlow<String> = preferencesManager.filterMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = FilterMode.ALL.value
        )

    /**
     * Set filter mode for habit list display.
     * @param mode The filter mode value ("all", "time_window", "checkable", "terminated")
     */
    fun setFilterMode(mode: String) {
        viewModelScope.launch {
            preferencesManager.setFilterMode(mode)
        }
    }

    /**
     * Active timer state for real-time UI updates.
     * Delegates to HabitTimerCoordinator for shared implementation.
     */
    val activeTimerState: StateFlow<ActiveTimerState?> =
        timerCoordinator.observeActiveTimer(viewModelScope)

    /**
     * Start a timer for the given habit.
     * Delegates to HabitTimerCoordinator for shared implementation.
     *
     * @param habitId The ID of the habit
     * @param targetMinutes Target duration in minutes
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
     * Stop the currently running timer and save the duration.
     * Triggers post-check-in dialog for linked metrics.
     * Delegates to HabitTimerCoordinator for shared implementation.
     */
    fun stopTimer() {
        val currentState = activeTimerState.value ?: return
        viewModelScope.launch {
            linkedMetricCoordinator.showPromptAfterTimerStop(
                timerCoordinator.stopTimer(currentState)
            )
        }
    }

    /**
     * Check if a specific habit has the active timer.
     *
     * @param habitId The habit ID to check
     * @return true if this habit has the active timer
     */
    fun isHabitTimerActive(habitId: Long): Boolean {
        return timerCoordinator.isHabitTimerActive(activeTimerState.value, habitId)
    }

    /**
     * Check if battery optimization guidance should be shown.
     * Returns true if guidance has never been shown before.
     */
    suspend fun shouldShowBatteryGuidance(): Boolean {
        return !preferencesManager.hasShownBatteryGuidance.first()
    }

    /**
     * Mark battery optimization guidance as shown.
     * Called after the guidance dialog is dismissed.
     */
    suspend fun markBatteryGuidanceShown() {
        preferencesManager.setBatteryGuidanceShown()
    }

    // ========== Post-Check-In Dialog State ==========

    /**
     * State for the post-check-in dialog that prompts users to record linked metrics.
     * Per METRIC-09: Users should be prompted to record linked metrics after checking in.
     * Per D-15 to D-19: Dialog shows linked metrics, supports inline recording, and "never ask again".
     */
    val postCheckInState: StateFlow<LinkedMetricPromptState?> =
        linkedMetricCoordinator.postCheckInState

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

    /**
     * Called after a habit check-in to potentially show the metric recording dialog.
     * Per D-15: Shows dialog if habit has linked metrics with promptOnComplete=true
     * Per D-18: Respects "never ask again" preference
     *
     * @param habitId The ID of the habit that was checked in
     * @param habitName The name of the habit (for display in dialog)
     */
    suspend fun checkAndShowPostCheckInDialog(habitId: Long, habitName: String) {
        val habit = habitDao.getHabitById(habitId)
        val isTempTask = habit?.let { h ->
            h.targetCycles == 1 && h.failMode == com.dayforge.data.model.FailMode.LOOSE && h.habitType == HabitType.CHECK_IN && h.iconResId == 53
        } ?: false
        linkedMetricCoordinator.showPromptIfNeeded(habitId, habitName, isTempTask)
    }

    /**
     * Record a metric value from the post-check-in dialog.
     *
     * @param metricId The ID of the metric
     * @param value The value to record
     * @param note Optional note for this record
     */
    suspend fun recordMetricValues(
        habitId: Long,
        values: List<MetricValueInput>
    ): Boolean = linkedMetricCoordinator.recordMetricValues(habitId, values)

    /**
     * Set "never ask again" preference for a habit's metric prompt.
     * Per D-18: User can suppress future prompts for this habit.
     *
     * @param habitId The ID of the habit
     * @param value True to suppress future prompts
     */
    suspend fun setNeverAskAgain(habitId: Long, value: Boolean) {
        linkedMetricCoordinator.setNeverAskAgain(habitId, value)
    }

    /**
     * Dismiss the post-check-in dialog.
     */
    fun dismissPostCheckInDialog() {
        linkedMetricCoordinator.dismissPrompt()
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
        val habit = habitsWithStats.value.find { it.habit.id == habitId }?.habit
        lifecycleCoordinator.showGoalCompletion(habit, progress)
    }

    /**
     * Confirms goal completion, setting habit isActive = false.
     * Per TARGET-08: User can confirm to mark habit as complete.
     */
    fun confirmGoalCompletion() {
        viewModelScope.launch {
            if (lifecycleCoordinator.confirmGoalCompletion()) {
                Toast.makeText(context, "习惯已完成", Toast.LENGTH_SHORT).show()
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
            val habit = habitsWithStats.value.find { it.habit.id == habitId }?.habit
            lifecycleCoordinator.dismissGoalDialog(habit)
        }
    }

    // ========== Reactivation Dialog Methods ==========

    /**
     * Shows reactivation dialog for a failed habit.
     * Called when user taps on "已失败" status.
     */
    fun showReactivationDialog(habitId: Long) {
        val habit = habitsWithStats.value.find { it.habit.id == habitId }?.habit
        lifecycleCoordinator.showReactivationDialog(habit)
    }

    /**
     * Confirms reactivation: clears history and reactivates the habit.
     */
    fun confirmReactivation() {
        viewModelScope.launch {
            val habitId = lifecycleCoordinator.reactivationHabitId.value ?: return@launch
            val habit = habitsWithStats.value.find { it.habit.id == habitId }?.habit
            when (lifecycleCoordinator.confirmReactivation(habit)) {
                ReactivationResult.SUCCESS ->
                    Toast.makeText(context, "习惯已重新激活，历史记录已清空", Toast.LENGTH_SHORT).show()
                ReactivationResult.FAILURE ->
                    Toast.makeText(context, "清空历史失败，请检查网络连接", Toast.LENGTH_SHORT).show()
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

    // ========== Habit Operations ==========

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

    /**
     * Delete a temporary task after completion.
     * Used when temp task (targetCycles=1 + failMode=LOOSE + CHECK_IN) is checked in.
     *
     * @param habitId The ID of the temporary task to delete
     */
    fun deleteTempTask(habitId: Long) {
        viewModelScope.launch {
            val habit = habitRepository.getHabitById(habitId)
            if (habit != null) {
                habitRepository.deleteHabit(
                    habit,
                    context,
                    factDerivedTaskFinalization = true
                )
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_temp_task_completed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * Check-in for a CHECK_IN habit.
     * Uses the shared completion coordinator for consistent behavior across habit screens.
     * Per TARGET-15: Triggers goal completion dialog if targetCycles reached.
     * Triggers post-check-in dialog for linked metrics.
     * For temporary tasks: deletes after completion if no linked metrics.
     */
    fun checkIn(habitId: Long) {
        viewModelScope.launch {
            val outcome = completionCoordinator.checkIn(
                habitId = habitId,
                finalizeTemporaryTasks = true,
                displayedHabit = { habitsWithStats.value.find { it.habit.id == habitId }?.habit }
            )
            outcome.goalProgress?.let { progress -> onGoalReached(habitId, progress) }
            if (outcome.shouldDeleteTemporaryTask) {
                deleteTempTask(habitId)
            }
            outcome.metricPromptHabit?.let { habit ->
                checkAndShowPostCheckInDialog(habitId, habit.name)
            }
        }
    }

    fun logCompletion(habitId: Long, value: Int = 1) {
        viewModelScope.launch {
            completionCoordinator.recordCompletion(habitId, value)
        }
    }

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
                habitsWithStats.value.find { it.habit.id == habitId }?.habit
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
     * Triggers goal completion dialog if targetCycles reached.
     */
    fun decrementCount(habitId: Long) {
        viewModelScope.launch {
            completionCoordinator.decrementCount(habitId).goalProgress
                ?.let { progress -> onGoalReached(habitId, progress) }
        }
    }

    /**
     * Update the aggregation type for a metric.
     * Called when user changes aggregation type in TrendChart.
     */
    fun updateAggregationType(metricId: Long, aggregationType: String) {
        viewModelScope.launch {
            runCatching {
                metricRepository.updateAggregationType(metricId, aggregationType)
            }.onFailure {
                Toast.makeText(context, it.message ?: "当前设备不能修改指标配置", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isToday(dateMillis: Long): Boolean {
        val today = DateTimeUtils.startOfDayMillis()
        return dateMillis == today
    }

}

private data class DashboardHabitListInput(
    val habits: List<HabitEntity>,
    val completions: List<CompletionEntity>,
    val filterMode: FilterMode
)
