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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
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
    private val metricOverviewProvider: MetricOverviewProvider,
    private val dashboardReadRepository: com.dayforge.data.repository.DashboardReadRepository? = null,
    private val countActions: com.dayforge.domain.service.CountActionGate? = null
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
            started = com.dayforge.ui.VisibleScreenSharing,
            initialValue = emptySet()
        )

    // Combine habits, completions, time logs, date change trigger, and filter mode.
    // For TIMER habits, todayCount and streaks come from TimeLogEntity, not CompletionEntity
    // Use getActiveTimeLogFlow() as trigger - it changes when timer starts (INSERT) or stops (UPDATE sets endTime)
    // Use dateChangeTrigger to refresh when date changes (user opens app on new day)
    // Use filterMode to switch between different display modes (all, time_window, checkable, terminated)
    private val readMonitor = com.dayforge.ui.DataReadMonitor(TAG)
    val readError = readMonitor.error
    fun retryRead() = readMonitor.retry()
    private val baseCombineFlow = combine(
        habitRepository.allHabits,
        allCompletions,
        timeLogDao.getActiveTimeLogFlow(),  // Triggers when timer state changes (start/stop)
        preferencesManager.dateChangeTrigger,
        preferencesManager.filterMode  // Triggers when filter mode changes
    ) { habits, completions, _, _, filterModeValue ->
        DashboardHabitListInput(
            habits = habits,
            completions = completions,
            filterMode = FilterMode.fromValue(filterModeValue)
        )
    }

    // Hilt supplies the authenticated batch reader. Unauthenticated legacy constructor fixtures
    // retain their existing reader path; there is no runtime fallback after a batch read fails.
    private val presentation = dashboardReadRepository?.let {
        DashboardListPresentation(viewModelScope, context, it, dashboardHabitListBuilder,
            preferencesManager, pendingMetricHabits, readMonitor) { _isInitialized.value = true }
    }

    val habitsWithStats: StateFlow<List<HabitWithStats>> = presentation?.habits ?: readMonitor.recover(emptyList()) { combine(
        combine(baseCombineFlow, habitRepository.oneTimeChanges, habitRepository.countChanges) { data, _, _ -> data },
        timeWindowTickFlow,
        pendingMetricHabits
    ) { input, _, pendingMetricHabitIds -> input to pendingMetricHabitIds }
        .mapLatest { (input, pendingMetricHabitIds) ->
            Log.d(
                TAG,
                "habitsWithStats combine triggered: habits=${input.habits.size}, " +
                    "completions=${input.completions.size}, filterMode=${input.filterMode.value}"
            )
            readMonitor.read(emptyList()) { dashboardHabitListBuilder.build(
                habits = input.habits,
                completions = input.completions,
                filterMode = input.filterMode,
                currentTime = ZonedDateTime.now(ZoneId.systemDefault()),
                pendingMetricHabitIds = pendingMetricHabitIds
            ) }
        } }
        .onEach { _isInitialized.value = true }
        .stateIn(
            scope = viewModelScope,
            started = com.dayforge.ui.VisibleScreenSharing,
            initialValue = emptyList()
        )

    // Today's progress: Pair of (completed count, total count)
    // Derives from habitsWithStats to ensure TIMER habits use TimeLogEntity data
    // Per D-10: Only counts habits, NOT metrics
    // Filters out non-check-in days, failed habits, and goal-completed habits
    val todayProgress: StateFlow<Pair<Int, Int>> = presentation?.todayProgress ?: habitsWithStats.map { habits ->
        val eligibleHabits = habits.filter { it.shouldCountToday }
        val completed = eligibleHabits.count { it.completedToday }
        Pair(completed, eligibleHabits.size)
    }.stateIn(
        scope = viewModelScope,
        started = com.dayforge.ui.VisibleScreenSharing,
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
            started = com.dayforge.ui.VisibleScreenSharing,
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
        started = com.dayforge.ui.VisibleScreenSharing,
        initialValue = emptyMap()
    )



    /**
     * Filter mode state for reactive UI updates.
     * Controls which habits are displayed and how they are sorted.
     */
    val filterMode: StateFlow<String> = preferencesManager.filterMode
        .stateIn(
            scope = viewModelScope,
            started = com.dayforge.ui.VisibleScreenSharing,
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
        val authority = habitsWithStats.value.find { it.habit.id == habitId }?.timerAuthority
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
     * Stop the currently running timer and save the duration.
     * Triggers post-check-in dialog for linked metrics.
     * Delegates to HabitTimerCoordinator for shared implementation.
     */
    fun stopTimer() {
        val currentState = activeTimerState.value ?: return
        launchCompletion(currentState.habitId) {
            try {
                linkedMetricCoordinator.showPromptAfterTimerStop(
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
    val reactivationPreservesHistory: StateFlow<Boolean> = lifecycleCoordinator.reactivationPreservesHistory

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
            h.completionPolicy == null && h.targetCycles == 1 && h.failMode == com.dayforge.data.model.FailMode.LOOSE && h.habitType == HabitType.CHECK_IN && h.iconResId == 53
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
        values: List<MetricValueInput>,
        expectedEventUuid: String? = null,
        expectedTimerPrompt: com.dayforge.ui.metrics.TimerMetricPrompt? = null,
        expectedPreferenceClaim: com.dayforge.data.repository.HabitPreferenceClaim? = null,
        expectedFactPrompt: com.dayforge.ui.metrics.FactMetricPrompt? = null
    ): Boolean = linkedMetricCoordinator.recordMetricValues(habitId, values, expectedEventUuid, expectedTimerPrompt,
        expectedFactPrompt = expectedFactPrompt, expectedPreferenceClaim = expectedPreferenceClaim)

    /**
     * Set "never ask again" preference for a habit's metric prompt.
     * Per D-18: User can suppress future prompts for this habit.
     *
     * @param habitId The ID of the habit
     * @param value True to suppress future prompts
     */
    suspend fun setNeverAskAgain(habitId: Long, value: Boolean, expectedEventUuid: String? = null,
        expectedTimerPrompt: com.dayforge.ui.metrics.TimerMetricPrompt? = null,
        expectedPreferenceClaim: com.dayforge.data.repository.HabitPreferenceClaim? = null,
        expectedFactPrompt: com.dayforge.ui.metrics.FactMetricPrompt? = null) {
        linkedMetricCoordinator.setNeverAskAgain(habitId, value, expectedEventUuid, expectedTimerPrompt,
            expectedFactPrompt = expectedFactPrompt, expectedPreferenceClaim = expectedPreferenceClaim)
    }

    /**
     * Dismiss the post-check-in dialog.
     */
    suspend fun dismissPostCheckInDialog(expected: LinkedMetricPromptState? = null) = linkedMetricCoordinator.closePrompt(expected)

    suspend fun skipPostCheckInDialog(habitId: Long, expectedEventUuid: String? = null,
        expectedTimerPrompt: com.dayforge.ui.metrics.TimerMetricPrompt? = null,
        expectedPreferenceClaim: com.dayforge.data.repository.HabitPreferenceClaim? = null,
        expectedFactPrompt: com.dayforge.ui.metrics.FactMetricPrompt? = null) =
        linkedMetricCoordinator.skipPrompt(habitId, expectedEventUuid, expectedTimerPrompt,
            expectedFactPrompt = expectedFactPrompt, expectedPreferenceClaim = expectedPreferenceClaim)

    suspend fun refreshPostCheckInMetadata() {
        run {
            try { linkedMetricCoordinator.refreshPrompt() }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                val message = if (error is com.dayforge.domain.service.CommittedActionRefreshFailed)
                    context.getString(com.dayforge.R.string.record_saved_refresh_failed)
                else context.getString(com.dayforge.R.string.metric_error_record_failed, error.message.orEmpty())
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    suspend fun savePostCheckInDraft(eventUuid: String, inputs: List<com.dayforge.ui.components.MetricInputState>) =
        linkedMetricCoordinator.savePromptDraft(eventUuid, inputs)

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
                isCurrent = { linkedMetricCoordinator.isCurrentCompletionPrompt(prompt) }) { next, isCurrent ->
                if (outcome.metricPromptHabit != null) linkedMetricCoordinator.showCompletionPrompt(next, prompt, isCurrent)
            } else if (outcome.metricPromptHabit != null) linkedMetricCoordinator.showCompletionPrompt(snapshot, prompt)
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
        val habit = habitsWithStats.value.find { it.habit.id == habitId }?.habit
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
        val habit = habitsWithStats.value.find { it.habit.id == habitId }?.habit
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

    // ========== Habit Operations ==========

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
        val displayed = habitsWithStats.value.find { it.habit.id == habitId }
        launchCompletion(habitId, timing = com.dayforge.domain.service.CompletionActionTiming.Operation.CHECK_IN) {
            val prompt = linkedMetricCoordinator.captureCompletionPrompt()
            val outcome = completionCoordinator.checkIn(
                habitId = habitId,
                finalizeTemporaryTasks = true,
                displayedHabit = { displayed?.habit },
                oneTimeAuthority = displayed?.oneTimeStatus?.authority,
                checkIdentity = displayed?.checkActionIdentity
            )
            if (publishCompletionFollowUp(outcome, displayed?.habit, prompt)) return@launchCompletion
            outcome.goalProgress?.let { progress -> onGoalReached(displayed?.habit, progress) }
            if (outcome.shouldDeleteTemporaryTask) {
                deleteTempTask(habitId)
            }
            outcome.metricPromptHabit?.let { habit ->
                com.dayforge.domain.service.afterCommittedAction { checkAndShowPostCheckInDialog(habitId, habit.name) }
            }
        }
    }

    fun logCompletion(habitId: Long, value: Int = 1) {
        val displayed = habitsWithStats.value.find { it.habit.id == habitId } ?: return
        launchCompletion(habitId, displayed.countActionIdentity, displayed.habit.habitType == HabitType.COUNTING) {
            completionCoordinator.recordCompletion(habitId, value, displayed.countActionIdentity)
        }
    }

    fun undoCompletion(completionId: Long) {
        val displayed = habitsWithStats.value.find { it.lastCompletionId == completionId } ?: return
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
                if (timing == null) block() else {
                    val measured: suspend () -> Unit = {
                        com.dayforge.domain.service.CompletionActionTiming.withOperation(timing, block)
                    }
                    if (dashboardReadRepository == null) measured()
                    else dashboardReadRepository.withInteractiveReadPriority(measured)
                }
            }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.e(TAG, "Completion operation failed", error)
                android.widget.Toast.makeText(context, com.dayforge.ui.actionFailureMessage(context, error),
                    android.widget.Toast.LENGTH_LONG).show()
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
        val displayed = habitsWithStats.value.find { it.habit.id == habitId } ?: return
        launchCompletion(habitId, displayed.countActionIdentity, isCount = true,
            timing = com.dayforge.domain.service.CompletionActionTiming.Operation.COUNT_INCREMENT) {
            val prompt = linkedMetricCoordinator.captureCompletionPrompt()
            val outcome = completionCoordinator.incrementCount(habitId, displayed.countActionIdentity) {
                displayed.habit
            }
            if (countActions != null && displayed.countActionIdentity?.let(countActions::isCurrent) != true) return@launchCompletion
            if (publishCompletionFollowUp(outcome, displayed.habit, prompt)) return@launchCompletion
            outcome.goalProgress?.let { progress -> onGoalReached(displayed.habit, progress) }
            outcome.metricPromptHabit?.let { habit ->
                com.dayforge.domain.service.afterCommittedAction {
                    if (displayed.countActionIdentity != null) linkedMetricCoordinator.showCountPrompt(displayed.countActionIdentity, habit.name)
                    else checkAndShowPostCheckInDialog(habitId, habit.name)
                }
            }
        }
    }

    /**
     * Decrement count for a COUNTING habit.
     * Uses the shared completion coordinator for consistent behavior across habit screens.
     * Triggers goal completion dialog if targetCycles reached.
     */
    fun decrementCount(habitId: Long) {
        val displayed = habitsWithStats.value.find { it.habit.id == habitId } ?: return
        launchCompletion(habitId, displayed.countActionIdentity, isCount = true,
            timing = com.dayforge.domain.service.CompletionActionTiming.Operation.COUNT_DECREMENT) {
            completionCoordinator.decrementCount(habitId, displayed.countActionIdentity).goalProgress
                ?.let { progress -> onGoalReached(displayed.habit, progress) }
        }
    }

    /**
     * Update the aggregation type for a metric.
     * Called when user changes aggregation type in TrendChart.
     */
    private val aggregationActions = com.dayforge.ui.InFlightActions()

    fun updateAggregationType(metricId: Long, aggregationType: String) {
        aggregationActions.launch(viewModelScope, metricId) {
            try {
                metricRepository.updateAggregationType(metricId, aggregationType)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Toast.makeText(context, error.message ?: "当前设备不能修改指标配置", Toast.LENGTH_LONG).show()
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
