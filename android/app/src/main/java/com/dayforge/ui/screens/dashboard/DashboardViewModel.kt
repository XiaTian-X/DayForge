package com.dayforge.ui.screens.dashboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dayforge.data.local.DatabaseEpochValue
import com.dayforge.data.local.DatabaseEpochPublication
import com.dayforge.data.local.DatabaseObjectSelection
import com.dayforge.domain.service.HabitGoalDialogState
import com.dayforge.domain.service.HabitReactivationDialogState
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
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel internal constructor(
    @param:ApplicationContext private val context: Context,
    habitRepository: HabitRepository?,
    private val completionCoordinator: HabitCompletionCoordinator,
    private val deletionCoordinator: HabitDeletionCoordinator,
    dashboardHabitListBuilder: DashboardHabitListBuilder?,
    private val dashboardTimeWindowTicker: DashboardTimeWindowTicker,
    private val lifecycleCoordinator: HabitLifecycleCoordinator,
    private val timerCoordinator: HabitTimerCoordinator,
    timeLogDao: TimeLogDao?,
    habitDao: com.dayforge.data.local.dao.HabitDao?,
    private val preferencesManager: PreferencesManager,
    completionDao: CompletionDao?,
    metricRepository: MetricRepository?,
    private val linkedMetricCoordinator: LinkedMetricCoordinator,
    metricOverviewProvider: MetricOverviewProvider?,
    dashboardReadRepository: com.dayforge.data.repository.DashboardReadRepository? = null,
    private val countActions: com.dayforge.domain.service.CountActionGate? = null,
    private val databaseHost: com.dayforge.data.local.DatabaseRuntimeHost? = null
) : ViewModel() {
    private val fixedHabitRepository = habitRepository
    private val habitRepository: HabitRepository get() = databaseHost?.boundGraph()?.habitRepository ?: requireNotNull(fixedHabitRepository)
    private val fixedDashboardHabitListBuilder = dashboardHabitListBuilder
    private val dashboardHabitListBuilder: DashboardHabitListBuilder get() = databaseHost?.boundGraph()?.dashboardHabitListBuilder ?: requireNotNull(fixedDashboardHabitListBuilder)
    private val fixedOverviewReads by lazy {
        com.dayforge.data.repository.OverviewReadRepository(requireNotNull(timeLogDao), habitDao)
    }
    private val overviewReads get() = databaseHost?.boundGraph()?.overviewReadRepository ?: fixedOverviewReads
    private val fixedCompletionDao = completionDao
    private val completionDao: CompletionDao get() = databaseHost?.boundGraph()?.completionDao ?: requireNotNull(fixedCompletionDao)
    private val fixedMetricRepository = metricRepository
    private val metricRepository: MetricRepository get() = databaseHost?.boundGraph()?.metricRepository ?: requireNotNull(fixedMetricRepository)
    private val fixedMetricOverviewProvider = metricOverviewProvider
    private val metricOverviewProvider: MetricOverviewProvider get() = databaseHost?.boundGraph()?.metricOverviewProvider ?: requireNotNull(fixedMetricOverviewProvider)
    private val fixedDashboardReadRepository = dashboardReadRepository
    private val dashboardReadRepository: com.dayforge.data.repository.DashboardReadRepository? get() = databaseHost?.boundGraph()?.dashboardReadRepository ?: fixedDashboardReadRepository

    /** Production Hilt captures no Room/DAO/repository from one retiring graph. The primary
     * constructor remains the explicitly fixed-graph manual fixture path. */
    @Inject internal constructor(@ApplicationContext context: Context,
        databaseHost: com.dayforge.data.local.DatabaseRuntimeHost,
        completionCoordinator: HabitCompletionCoordinator, deletionCoordinator: HabitDeletionCoordinator,
        dashboardTimeWindowTicker: DashboardTimeWindowTicker, lifecycleCoordinator: HabitLifecycleCoordinator,
        timerCoordinator: HabitTimerCoordinator, preferencesManager: PreferencesManager,
        linkedMetricCoordinator: LinkedMetricCoordinator, countActions: com.dayforge.domain.service.CountActionGate
    ) : this(context, null, completionCoordinator, deletionCoordinator, null, dashboardTimeWindowTicker,
        lifecycleCoordinator, timerCoordinator, null, null, preferencesManager, null, null,
        linkedMetricCoordinator, null, null, countActions, databaseHost)


    internal val fixedGraphConstruction: Boolean get() = databaseHost == null

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
    private val allCompletions by lazy { this@DashboardViewModel.habitRepository.getAllCompletions() }

    /**
     * Habits that need metric recording (completed timer habits with unrecorded prompt metrics).
     * Shows a "Record" button on the habit card.
     *
     * This is now read from DataStore where TimerService stores the pending habits.
     * This approach is more reliable than Flow calculation which could miss updates
     * when the app is in the background or when multiple habits share the same metric.
     */
    val pendingMetricHabits: StateFlow<Set<Long>> = databaseHost?.let { host ->
        com.dayforge.ui.epochPublishedState(viewModelScope, host, com.dayforge.ui.VisibleScreenSharing,
            emptySet(), linkedMetricCoordinator.pendingMetricHabitFrames)
    } ?: linkedMetricCoordinator.pendingMetricHabits
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
    private val baseCombineFlow by lazy { combine(
        this@DashboardViewModel.habitRepository.allHabits,
        allCompletions,
        this@DashboardViewModel.overviewReads.activeTimerChanges(),  // Triggers when timer state changes (start/stop)
        preferencesManager.dateChangeTrigger,
        preferencesManager.filterMode  // Triggers when filter mode changes
    ) { habits, completions, _, _, filterModeValue ->
        DashboardHabitListInput(
            habits = habits,
            completions = completions,
            filterMode = FilterMode.fromValue(filterModeValue)
        )
    } }
    // Hilt supplies the authenticated batch reader. Unauthenticated legacy constructor fixtures
    // retain their existing reader path; there is no runtime fallback after a batch read fails.
    private val presentation = if (databaseHost != null) null else dashboardReadRepository?.let {
        DashboardListPresentation(viewModelScope, context, it, this@DashboardViewModel.dashboardHabitListBuilder,
            preferencesManager, pendingMetricHabits, readMonitor) { _isInitialized.value = true }
    }

    private val epochHabits = databaseHost?.let { host ->
        host.observe { graph -> kotlinx.coroutines.flow.flow {
            kotlinx.coroutines.coroutineScope {
                val source = host.currentPublication()
                val holder = DashboardListPresentation(this, context, graph.dashboardReadRepository,
                    graph.dashboardHabitListBuilder, preferencesManager, pendingMetricHabits, readMonitor,
                    onRead = { source.publish { _isInitialized.value = true } })
                holder.habits.collect { rows -> emit(rows.map { it.copy(sourceEpoch = source) }) }
            }
        } }.shareIn(viewModelScope, com.dayforge.ui.VisibleScreenSharing, replay = 1)
    }

    val habitsWithStats: StateFlow<List<HabitWithStats>> = databaseHost?.let { host ->
        com.dayforge.ui.epochPublishedState(viewModelScope, host, com.dayforge.ui.VisibleScreenSharing,
            emptyList(), requireNotNull(epochHabits), initialized = { if (!it) _isInitialized.value = false })
    } ?: presentation?.habits ?: readMonitor.recover(emptyList()) { combine(
        combine(baseCombineFlow, this@DashboardViewModel.habitRepository.oneTimeChanges, this@DashboardViewModel.habitRepository.countChanges) { data, _, _ -> data },
        timeWindowTickFlow,
        pendingMetricHabits
    ) { input, _, pendingMetricHabitIds -> input to pendingMetricHabitIds }
        .mapLatest { (input, pendingMetricHabitIds) ->
            Log.d(
                TAG,
                "habitsWithStats combine triggered: habits=${input.habits.size}, " +
                    "completions=${input.completions.size}, filterMode=${input.filterMode.value}"
            )
            readMonitor.read(emptyList()) { this@DashboardViewModel.dashboardHabitListBuilder.build(
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
    val todayProgress: StateFlow<Pair<Int, Int>> = databaseHost?.let { host ->
        com.dayforge.ui.epochPublishedState(viewModelScope, host, com.dayforge.ui.VisibleScreenSharing,
            0 to 0, requireNotNull(epochHabits).map { emission ->
                val eligible = emission.value.filter { it.shouldCountToday }
                com.dayforge.data.local.DatabaseEpochValue(eligible.count { it.completedToday } to eligible.size, emission.publication)
            })
    } ?: presentation?.todayProgress ?: habitsWithStats.map { habits ->
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
    val metricsWithLatest: StateFlow<List<MetricWithLatestValue>> = databaseHost?.let { host ->
        com.dayforge.ui.epochPublishedState(viewModelScope, host, com.dayforge.ui.VisibleScreenSharing,
            emptyList(), host.observe { graph -> graph.metricOverviewProvider.observe().map { rows ->
                val source = host.currentPublication(); rows.map { it.copy(sourceEpoch = source) }
            } })
    } ?: this@DashboardViewModel.metricOverviewProvider.observe()
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
    val linkedMetricFrame: StateFlow<DatabaseEpochValue<Map<Long, List<LinkedMetricInfo>>>?> = databaseHost?.let { host ->
        val values = host.observe { _ -> linkedMetricCoordinator.observeLinkedMetrics(
            habitIds = habitsWithStats.map { rows -> rows.filter { it.sourceEpoch?.isCurrentInMemory() == true }.map { it.habit.id }.toSet() },
            onlyShownInHabitDetail = true
        ) }
        com.dayforge.ui.epochPublishedState(viewModelScope, host, com.dayforge.ui.VisibleScreenSharing,
            null, values.map { frame -> DatabaseEpochValue<DatabaseEpochValue<Map<Long, List<LinkedMetricInfo>>>?>(frame, frame.publication) })
    } ?: MutableStateFlow(null)

    val linkedMetricsByHabit: StateFlow<Map<Long, List<LinkedMetricInfo>>> = if (databaseHost != null) MutableStateFlow(emptyMap()) else
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
    val activeTimerFrame: StateFlow<DatabaseEpochValue<ActiveTimerState?>?> = databaseHost?.let { host ->
        com.dayforge.ui.epochPublishedState(viewModelScope, host, com.dayforge.ui.VisibleScreenSharing,
            null, timerCoordinator.observeActiveTimerFrames().map { frame ->
                DatabaseEpochValue<DatabaseEpochValue<ActiveTimerState?>?>(frame, frame.publication)
            })
    } ?: MutableStateFlow(null)
    // Fixed-graph compatibility only. Production UI consumes the atomic frame above.
    val activeTimerState: StateFlow<ActiveTimerState?> = if (databaseHost != null) MutableStateFlow(null)
        else timerCoordinator.observeActiveTimer(viewModelScope)

    /**
     * Start a timer for the given habit.
     * Delegates to HabitTimerCoordinator for shared implementation.
     *
     * @param habitId The ID of the habit
     * @param targetMinutes Target duration in minutes
     */
    fun startTimer(habitId: Long, targetMinutes: Int, selection: com.dayforge.data.local.DatabaseObjectSelection? = null) {
        if (!acceptsRenderedInput(habitId, selection)) return
        val authority = habitsWithStats.value.find { it.habit.id == habitId }?.timerAuthority
        launchCompletion(habitId, selection = selection) { verifiedHabit ->
            try { timerCoordinator.startTimer(habitId, targetMinutes, authority, expectedHabit = verifiedHabit) }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Toast.makeText(context, com.dayforge.ui.actionFailureMessage(context, error), Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Pause the currently running timer.
     * Delegates to HabitTimerCoordinator for shared implementation.
     */
    fun pauseTimer(frame: DatabaseEpochValue<ActiveTimerState?>? = null) {
        if (databaseHost == null) { timerCoordinator.pauseTimer(activeTimerState.value); return }
        val value = captureInput {
            requireNotNull(frame) { "DATABASE_EPOCH_INPUT_REQUIRED" }.value
        } ?: return
        viewModelScope.launch {
            try {
                databaseHost.withSourceGraph(requireNotNull(frame).publication) { timerCoordinator.pauseTimer(value) }
            } catch (error: Exception) { reportInputFailure(error) }
        }
    }

    /**
     * Resume a paused timer.
     * Delegates to HabitTimerCoordinator for shared implementation.
     */
    fun resumeTimer(frame: DatabaseEpochValue<ActiveTimerState?>? = null) {
        if (databaseHost == null) { timerCoordinator.resumeTimer(activeTimerState.value); return }
        val value = captureInput {
            requireNotNull(frame) { "DATABASE_EPOCH_INPUT_REQUIRED" }.value
        } ?: return
        viewModelScope.launch {
            try {
                databaseHost.withSourceGraph(requireNotNull(frame).publication) { timerCoordinator.resumeTimer(value) }
            } catch (error: Exception) { reportInputFailure(error) }
        }
    }

    /**
     * Stop the currently running timer and save the duration.
     * Triggers post-check-in dialog for linked metrics.
     * Delegates to HabitTimerCoordinator for shared implementation.
     */
    fun stopTimer(frame: DatabaseEpochValue<ActiveTimerState?>? = null) {
        val currentState = captureInput {
            if (databaseHost == null) activeTimerState.value
            else requireNotNull(frame) { "DATABASE_EPOCH_INPUT_REQUIRED" }.value
        } ?: return
        launchCompletion(currentState.habitId, sourceEpoch = frame?.publication) {
            try {
                linkedMetricCoordinator.showPromptAfterTimerStop(timerCoordinator.stopTimer(currentState), currentState.authority)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Toast.makeText(context, com.dayforge.ui.actionFailureMessage(context, error), Toast.LENGTH_LONG).show()
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
        return timerCoordinator.isHabitTimerActive((if (databaseHost == null) activeTimerState.value else activeTimerFrame.value?.value), habitId)
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
    suspend fun markBatteryGuidanceShown(selection: DatabaseObjectSelection? = null) {
        try {
            if (databaseHost == null) preferencesManager.setBatteryGuidanceShown()
            else databaseHost.withSelection(selection, DatabaseObjectSelection.Kind.HABIT) { preferencesManager.setBatteryGuidanceShown() }
        } catch (error: Exception) { reportInputFailure(error) }
    }

    // ========== Post-Check-In Dialog State ==========

    /**
     * State for the post-check-in dialog that prompts users to record linked metrics.
     * Per METRIC-09: Users should be prompted to record linked metrics after checking in.
     * Per D-15 to D-19: Dialog shows linked metrics, supports inline recording, and "never ask again".
     */
    val postCheckInFrame: StateFlow<DatabaseEpochValue<LinkedMetricPromptState?>?> = databaseHost?.let { host ->
        com.dayforge.ui.producerEpochFrameState(viewModelScope, host, com.dayforge.ui.VisibleScreenSharing,
            linkedMetricCoordinator.postCheckInFrames, linkedMetricCoordinator::publishPromptFrame)
    } ?: MutableStateFlow(null)
    val postCheckInState: StateFlow<LinkedMetricPromptState?> = if (databaseHost != null) MutableStateFlow(null)
        else linkedMetricCoordinator.postCheckInState

    // ========== Goal Completion Dialog State ==========

    /**
     * State for the goal completion dialog shown when targetCycles is reached.
     * Per TARGET-08: Dialog appears when check-in reaches target cycles.
     */
    val goalDialogFrame: StateFlow<DatabaseEpochValue<HabitGoalDialogState>?> = databaseHost?.let { host ->
        com.dayforge.ui.producerEpochFrameState(viewModelScope, host, com.dayforge.ui.VisibleScreenSharing,
            lifecycleCoordinator.goalFrames, lifecycleCoordinator::publishGoalDisplay)
    } ?: MutableStateFlow(null)
    val reactivationDialogFrame: StateFlow<DatabaseEpochValue<HabitReactivationDialogState>?> = databaseHost?.let { host ->
        com.dayforge.ui.producerEpochFrameState(viewModelScope, host, com.dayforge.ui.VisibleScreenSharing,
            lifecycleCoordinator.reactivationFrames, lifecycleCoordinator::publishReactivationDisplay)
    } ?: MutableStateFlow(null)
    val deletionDialogFrame: StateFlow<DatabaseEpochValue<com.dayforge.domain.service.PendingHabitDeletion?>?> = databaseHost?.let { host ->
        com.dayforge.ui.producerEpochFrameState(viewModelScope, host, com.dayforge.ui.VisibleScreenSharing,
            deletionCoordinator.pendingDeletionFrames, deletionCoordinator::publishPendingDeletion)
    } ?: MutableStateFlow(null)

    val showGoalDialog: StateFlow<Boolean> = if (databaseHost != null) MutableStateFlow(false) else lifecycleCoordinator.showGoalDialog
    val goalHabitId: StateFlow<Long?> = if (databaseHost != null) MutableStateFlow(null) else lifecycleCoordinator.goalHabitId
    val goalProgress: StateFlow<Int> = if (databaseHost != null) MutableStateFlow(0) else lifecycleCoordinator.goalProgress
    val goalTarget: StateFlow<Int> = if (databaseHost != null) MutableStateFlow(0) else lifecycleCoordinator.goalTarget

    // ========== Reactivation Dialog State ==========

    val showReactivationDialog: StateFlow<Boolean> = if (databaseHost != null) MutableStateFlow(false) else lifecycleCoordinator.showReactivationDialog
    val reactivationHabitId: StateFlow<Long?> = if (databaseHost != null) MutableStateFlow(null) else lifecycleCoordinator.reactivationHabitId
    val reactivationHabitName: StateFlow<String> = if (databaseHost != null) MutableStateFlow("") else lifecycleCoordinator.reactivationHabitName
    val reactivationPreservesHistory: StateFlow<Boolean> = if (databaseHost != null) MutableStateFlow(false) else lifecycleCoordinator.reactivationPreservesHistory

    val showChildrenDialog = if (databaseHost != null) MutableStateFlow<com.dayforge.domain.service.PendingHabitDeletion?>(null) else deletionCoordinator.pendingDeletion

    /**
     * Called after a habit check-in to potentially show the metric recording dialog.
     * Per D-15: Shows dialog if habit has linked metrics with promptOnComplete=true
     * Per D-18: Respects "never ask again" preference
     *
     * @param habitId The ID of the habit that was checked in
     * @param habitName The name of the habit (for display in dialog)
     */
    suspend fun checkAndShowPostCheckInDialog(habitId: Long, habitName: String,
        selection: com.dayforge.data.local.DatabaseObjectSelection? = null) {
        try { withRenderedInput(habitId, selection) { showBoundPostCheckInDialog(habitId, habitName) } }
        catch (error: Exception) { reportInputFailure(error) }
    }

    private suspend fun showBoundPostCheckInDialog(habitId: Long, habitName: String) {
        val habit = overviewReads.habit(habitId)
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
        expectedFactPrompt: com.dayforge.ui.metrics.FactMetricPrompt? = null,
        frame: DatabaseEpochValue<LinkedMetricPromptState?>? = null,
        neverAskAgain: Boolean = false
    ): Boolean {
        // Establish diagnostics before acquiring the Host source lease or any command ticket.
        // Existing/recovered tickets are still acquired and consumed inside the original call.
        val record: suspend () -> Boolean = { withPromptInput(frame) {
            linkedMetricCoordinator.recordMetricValues(habitId, values, expectedEventUuid, expectedTimerPrompt,
                expectedFactPrompt = expectedFactPrompt, expectedPreferenceClaim = expectedPreferenceClaim, frame = frame,
                neverAskAgain = neverAskAgain)
        } }
        return if (expectedFactPrompt?.command == null) record()
        else com.dayforge.domain.service.CompletionActionTiming.withOperation(
            com.dayforge.domain.service.CompletionActionTiming.Operation.LINKED_METRICS, record)
    }

    suspend fun continueSavedMetricDelivery(habitId: Long, prompt: com.dayforge.ui.metrics.FactMetricPrompt,
        frame: DatabaseEpochValue<LinkedMetricPromptState?>? = null): Boolean =
        com.dayforge.domain.service.CompletionActionTiming.withOperation(
            com.dayforge.domain.service.CompletionActionTiming.Operation.LINKED_METRICS) {
            withPromptInput(frame) { linkedMetricCoordinator.continueSavedMetricDelivery(habitId, prompt, frame) }
        }


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
        expectedFactPrompt: com.dayforge.ui.metrics.FactMetricPrompt? = null,
        frame: DatabaseEpochValue<LinkedMetricPromptState?>? = null) = withPromptInput(frame) {
        linkedMetricCoordinator.setNeverAskAgain(habitId, value, expectedEventUuid, expectedTimerPrompt,
            expectedFactPrompt = expectedFactPrompt, expectedPreferenceClaim = expectedPreferenceClaim, frame = frame)
    }

    /**
     * Dismiss the post-check-in dialog.
     */
    suspend fun dismissPostCheckInDialog(expected: LinkedMetricPromptState? = null,
        frame: DatabaseEpochValue<LinkedMetricPromptState?>? = null) = withPromptInput(frame) {
        linkedMetricCoordinator.closePrompt(expected, frame)
    }

    suspend fun skipPostCheckInDialog(habitId: Long, expectedEventUuid: String? = null,
        expectedTimerPrompt: com.dayforge.ui.metrics.TimerMetricPrompt? = null,
        expectedPreferenceClaim: com.dayforge.data.repository.HabitPreferenceClaim? = null,
        expectedFactPrompt: com.dayforge.ui.metrics.FactMetricPrompt? = null,
        frame: DatabaseEpochValue<LinkedMetricPromptState?>? = null) = withPromptInput(frame) {
        linkedMetricCoordinator.skipPrompt(habitId, expectedEventUuid, expectedTimerPrompt,
            expectedFactPrompt = expectedFactPrompt, expectedPreferenceClaim = expectedPreferenceClaim, frame = frame)
    }

    suspend fun refreshPostCheckInMetadata(frame: DatabaseEpochValue<LinkedMetricPromptState?>? = null) {
        run {
            try { withPromptInput(frame) { linkedMetricCoordinator.refreshPrompt(frame) } }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                val message = if (error is com.dayforge.domain.service.CommittedActionRefreshFailed)
                    context.getString(com.dayforge.R.string.record_saved_refresh_failed)
                else context.getString(com.dayforge.R.string.metric_error_record_failed, error.message.orEmpty())
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    suspend fun savePostCheckInDraft(eventUuid: String, inputs: List<com.dayforge.ui.components.MetricInputState>,
        frame: DatabaseEpochValue<LinkedMetricPromptState?>? = null) = withPromptInput(frame) {
        linkedMetricCoordinator.savePromptDraft(eventUuid, inputs, frame)
    }

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
                if (outcome.metricPromptHabit != null) linkedMetricCoordinator.showCompletionPrompt(next, prompt, isCurrent, outcome.command)
            } else if (outcome.metricPromptHabit != null) linkedMetricCoordinator.showCompletionPrompt(snapshot, prompt, command = outcome.command)
        }
        return true
    }

    /**
     * Confirms goal completion, setting habit isActive = false.
     * Per TARGET-08: User can confirm to mark habit as complete.
     */
    fun confirmGoalCompletion(frame: DatabaseEpochValue<HabitGoalDialogState>? = null) {
        val captured = captureInput {
            if (databaseHost == null) {
                val id = lifecycleCoordinator.goalHabitId.value ?: return@captureInput null
                Triple(id, lifecycleCoordinator.captureGoalCompletion(complete = true), null)
            } else {
                val original = requireNotNull(frame) { "DATABASE_EPOCH_INPUT_REQUIRED" }
                Triple(requireNotNull(original.value.habitId), lifecycleCoordinator.captureGoalCompletion(true, original), original.publication)
            }
        } ?: return
        val (habitId, action, source) = captured
        launchCompletion(habitId, sourceEpoch = source) {
            if (action()) Toast.makeText(context, context.getString(R.string.toast_habit_completed), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Dismisses the goal completion dialog without completing the habit.
     * Per TARGET-08: User can continue tracking if they dismiss.
     * Switches to LOOSE mode to prevent failure detection for the continued cycle.
     * Dialog will reappear on next goal-reaching check-in.
     */
    fun dismissGoalDialog(frame: DatabaseEpochValue<HabitGoalDialogState>? = null) {
        val captured = captureInput {
            if (databaseHost == null) {
                val id = lifecycleCoordinator.goalHabitId.value ?: return@captureInput null
                Triple(id, lifecycleCoordinator.captureGoalCompletion(complete = false), null)
            } else {
                val original = requireNotNull(frame) { "DATABASE_EPOCH_INPUT_REQUIRED" }
                Triple(requireNotNull(original.value.habitId), lifecycleCoordinator.captureGoalCompletion(false, original), original.publication)
            }
        } ?: return
        val (habitId, action, source) = captured
        launchCompletion(habitId, sourceEpoch = source) {
            action()
        }
    }

    // ========== Reactivation Dialog Methods ==========

    /**
     * Shows reactivation dialog for a failed habit.
     * Called when user taps on "已失败" status.
     */
    fun showReactivationDialog(habitId: Long, selection: com.dayforge.data.local.DatabaseObjectSelection? = null) {
        if (!acceptsRenderedInput(habitId, selection)) return
        val habit = habitsWithStats.value.find { it.habit.id == habitId }?.habit
        viewModelScope.launch {
            try { withRenderedInput(habitId, selection) { lifecycleCoordinator.showReactivationDialog(habit) } }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Toast.makeText(context, context.getString(R.string.restart_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Confirms the captured restart; challenge profiles preserve history.
     */
    fun confirmReactivation(frame: DatabaseEpochValue<HabitReactivationDialogState>? = null) {
        val captured = captureInput {
            val habitId = if (databaseHost == null) lifecycleCoordinator.reactivationHabitId.value
                else requireNotNull(frame) { "DATABASE_EPOCH_INPUT_REQUIRED" }.value.habitId
            if (habitId == null) return@captureInput null
            val habit = habitsWithStats.value.find { it.habit.id == habitId }?.habit
            val preserve = if (databaseHost == null) lifecycleCoordinator.reactivationPreservesHistory.value else requireNotNull(frame).value.preservesHistory
            val action = if (databaseHost == null) lifecycleCoordinator.captureReactivation(habit)
                else lifecycleCoordinator.captureReactivation(habit, requireNotNull(frame))
            Triple(habitId, preserve, action)
        } ?: return
        val (habitId, preservesHistory, confirmation) = captured
        launchCompletion(habitId, sourceEpoch = frame?.publication) {
            when (confirmation()) {
                ReactivationResult.SUCCESS -> Toast.makeText(context, context.getString(if (preservesHistory)
                    R.string.restart_completed else R.string.toast_habit_reactivated), Toast.LENGTH_SHORT).show()
                ReactivationResult.FAILURE -> Toast.makeText(context, context.getString(R.string.restart_failed), Toast.LENGTH_SHORT).show()
                ReactivationResult.NO_HABIT -> Unit
            }
        }
    }

    /**
     * Dismisses the reactivation dialog.
     */
    fun dismissReactivationDialog(frame: DatabaseEpochValue<HabitReactivationDialogState>? = null) {
        captureInput {
            val id = if (databaseHost == null) lifecycleCoordinator.reactivationHabitId.value
                else requireNotNull(frame) { "DATABASE_EPOCH_INPUT_REQUIRED" }.value.habitId
            if (id in busyHabitIds.value) return@captureInput
            if (databaseHost == null) lifecycleCoordinator.dismissReactivationDialog()
            else lifecycleCoordinator.dismissReactivationDialog(requireNotNull(frame))
        }
    }

    // ========== Habit Operations ==========

    fun deleteHabit(habit: HabitEntity, selection: com.dayforge.data.local.DatabaseObjectSelection? = null) {
        if (!acceptsRenderedInput(habit.id, selection)) return
        runDeletion(habit.id, selection) {
            deletionCoordinator.requestDeletion(habit)
        }
    }

    fun deleteHabitWithChildren(frame: DatabaseEpochValue<com.dayforge.domain.service.PendingHabitDeletion?>? = null) {
        val captured = captureInput {
            if (databaseHost == null) {
                val pending = deletionCoordinator.pendingDeletion.value ?: return@captureInput null
                pending.habit.id to suspend { deletionCoordinator.deleteWithChildren() }
            } else {
                val original = requireNotNull(frame) { "DATABASE_EPOCH_INPUT_REQUIRED" }
                val pending = requireNotNull(original.value) { "DATABASE_EPOCH_SELECTION_CHANGED" }
                pending.habit.id to deletionCoordinator.captureDeletion(original, withChildren = true)
            }
        } ?: return
        runDeletion(captured.first, sourceEpoch = frame?.publication) { captured.second() }
    }

    fun deleteHabitKeepChildren(frame: DatabaseEpochValue<com.dayforge.domain.service.PendingHabitDeletion?>? = null) {
        val captured = captureInput {
            if (databaseHost == null) {
                val pending = deletionCoordinator.pendingDeletion.value ?: return@captureInput null
                pending.habit.id to suspend { deletionCoordinator.deleteKeepingChildren() }
            } else {
                val original = requireNotNull(frame) { "DATABASE_EPOCH_INPUT_REQUIRED" }
                val pending = requireNotNull(original.value) { "DATABASE_EPOCH_SELECTION_CHANGED" }
                pending.habit.id to deletionCoordinator.captureDeletion(original, withChildren = false)
            }
        } ?: return
        runDeletion(captured.first, sourceEpoch = frame?.publication) { captured.second() }
    }

    fun dismissChildrenDialog(frame: DatabaseEpochValue<com.dayforge.domain.service.PendingHabitDeletion?>? = null) {
        captureInput {
            val pending = if (databaseHost == null) deletionCoordinator.pendingDeletion.value
                else requireNotNull(frame) { "DATABASE_EPOCH_INPUT_REQUIRED" }.value
            if (pending?.habit?.id in busyHabitIds.value) return@captureInput
            if (databaseHost == null) deletionCoordinator.dismissDeletion()
            else deletionCoordinator.dismissDeletion(requireNotNull(frame))
        }
    }

    private fun runDeletion(habitId: Long, selection: com.dayforge.data.local.DatabaseObjectSelection? = null,
        sourceEpoch: DatabaseEpochPublication? = selection?.publication, action: suspend () -> Unit) {
        launchCompletion(habitId, selection = selection, sourceEpoch = sourceEpoch) {
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
    fun deleteTempTask(habitId: Long, frame: DatabaseEpochValue<LinkedMetricPromptState?>? = null) {
        val selection = captureInput {
            if (databaseHost == null) null else {
                val original = requireNotNull(frame) { "DATABASE_EPOCH_INPUT_REQUIRED" }
                val state = requireNotNull(original.value) { "DATABASE_EPOCH_SELECTION_CHANGED" }
                check(state.habitId == habitId && state.isTempTask) { "DATABASE_EPOCH_SELECTION_CHANGED" }
                DatabaseObjectSelection.habit(habitId, requireNotNull(state.habitUuid), original.publication)
            }
        }
        if (databaseHost != null && selection == null) return
        viewModelScope.launch {
            if (databaseHost == null) finalizeTempTaskInGraph(habitId)
            else try { withRenderedInput(habitId, selection) { finalizeTempTaskInGraph(habitId, requireNotNull(selection).uuid) } }
                catch (error: Exception) { reportInputFailure(error) }
        }
    }

    private suspend fun finalizeTempTaskInGraph(habitId: Long, expectedUuid: String? = null) {
        val habit = habitRepository.getHabitById(habitId)
        if (habit != null) {
            check(expectedUuid == null || habit.uuid == expectedUuid) { "DATABASE_EPOCH_SELECTION_CHANGED" }
            habitRepository.deleteHabit(habit, context, factDerivedTaskFinalization = true)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, context.getString(R.string.toast_temp_task_completed), Toast.LENGTH_SHORT).show()
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
    fun checkIn(habitId: Long, selection: com.dayforge.data.local.DatabaseObjectSelection? = null) {
        if (!acceptsRenderedInput(habitId, selection)) return
        val displayed = habitsWithStats.value.find { it.habit.id == habitId }
        launchCompletion(habitId, timing = com.dayforge.domain.service.CompletionActionTiming.Operation.CHECK_IN, selection = selection) {
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
                if (databaseHost == null) deleteTempTask(habitId)
                else com.dayforge.domain.service.afterCommittedAction {
                    finalizeTempTaskInGraph(habitId, requireNotNull(selection).uuid)
                }
            }
            outcome.metricPromptHabit?.let { habit ->
                com.dayforge.domain.service.afterCommittedAction { showBoundPostCheckInDialog(habitId, habit.name) }
            }
        }
    }

    fun logCompletion(habitId: Long, value: Int = 1, selection: com.dayforge.data.local.DatabaseObjectSelection? = null) {
        if (!acceptsRenderedInput(habitId, selection)) return
        val displayed = habitsWithStats.value.find { it.habit.id == habitId } ?: return
        launchCompletion(habitId, displayed.countActionIdentity, displayed.habit.habitType == HabitType.COUNTING, selection = selection) {
            val receipt = completionCoordinator.recordCompletion(habitId, value, displayed.countActionIdentity)
            acceptCountButtonResult(displayed.habit.uuid, receipt, selection?.publication)
        }
    }

    fun undoCompletion(completionId: Long, selection: com.dayforge.data.local.DatabaseObjectSelection? = null) {
        if (databaseHost != null && !acceptsRenderedInput(selection?.id ?: -1L, selection)) return
        val displayed = habitsWithStats.value.find { it.lastCompletionId == completionId } ?: return
        val authority = displayed.oneTimeStatus?.authority
        val checkIdentity = displayed.checkActionIdentity
        launchCompletion(displayed.habit.id, displayed.countActionIdentity, displayed.habit.habitType == HabitType.COUNTING, selection = selection) {
            check(displayed.habit.appearance == null || displayed.habit.completionPolicy != "recurring" ||
                displayed.habit.habitType != HabitType.CHECK_IN || checkIdentity != null) { "CHECK_ACTION_REQUIRED" }
            val receipt = completionCoordinator.undoCompletion(completionId, authority, displayed.countActionIdentity, checkIdentity = checkIdentity)
            acceptCountButtonResult(displayed.habit.uuid, receipt, selection?.publication)
        }
    }

    private fun reportInputFailure(error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        Log.w(TAG, "Screen input rejected", error)
        Toast.makeText(context, com.dayforge.ui.actionFailureMessage(context, error), Toast.LENGTH_LONG).show()
    }
    private fun <T> captureInput(block: () -> T): T? = try { block() }
        catch (error: Exception) { reportInputFailure(error); null }

    private suspend fun <T> withPromptInput(frame: DatabaseEpochValue<LinkedMetricPromptState?>?,
        block: suspend () -> T): T {
        val host = databaseHost ?: return block()
        return host.withSourceGraph(frame?.publication) { block() }
    }

    private fun acceptsRenderedInput(id: Long, selection: DatabaseObjectSelection?,
        kind: DatabaseObjectSelection.Kind = DatabaseObjectSelection.Kind.HABIT): Boolean {
        val host = databaseHost ?: return true
        return captureInput {
            check(selection != null && selection.kind == kind && selection.id == id && selection.publication.belongsTo(host)) {
                "DATABASE_EPOCH_INPUT_REQUIRED"
            }
            check(selection.publication.isCurrentInMemory()) { "DATABASE_EPOCH_INPUT_EXPIRED" }
            true
        } == true
    }

    private suspend fun <T> withRenderedInput(habitId: Long,
        selection: com.dayforge.data.local.DatabaseObjectSelection?,
        source: com.dayforge.data.local.DatabaseEpochPublication? = selection?.publication,
        block: suspend (HabitEntity?) -> T): T {
        val host = databaseHost ?: return block(null)
        return host.withSourceGraph(source) { graph ->
            val verifiedHabit = if (selection != null) {
                check(selection.kind == com.dayforge.data.local.DatabaseObjectSelection.Kind.HABIT && selection.id == habitId) {
                    "DATABASE_EPOCH_SELECTION_CHANGED"
                }
                requireNotNull(graph.overviewReadRepository.visibleHabit(habitId)) { "DATABASE_EPOCH_SELECTION_CHANGED" }
                    .also(selection::requireHabit)
            } else null
            // Carry this exact checked row to delayed adapters; a later numeric-ID lookup
            // must not replace the original UUID before its own claim is frozen.
            // Original fresh access, claim/round and actual END/publication remain required.
            block(verifiedHabit)
        }
    }

    private val countButtonModels = com.dayforge.ui.CountButtonModels(viewModelScope, databaseHost)
    val countButtonFrame = countButtonModels.frames
    val fixedCountButtonModels = countButtonModels.fixedState

    private suspend fun acceptCountButtonResult(habitUuid: String,
        receipt: com.dayforge.data.repository.CountCommandReceipt?,
        original: com.dayforge.data.local.DatabaseEpochPublication?): Boolean {
        if (receipt == null) return true
        if (!countButtonModels.accept(habitUuid, com.dayforge.ui.CountButtonModel.committed(receipt), original)) return false
        if (!receipt.requiresMetricFollowUp) com.dayforge.domain.service.afterCommittedAction {
            completionCoordinator.acknowledgeCountResultDelivery(receipt)
        }
        return true
    }

    private val completionActions = com.dayforge.ui.InFlightActions()
    val busyHabitIds = countActions?.let { gate ->
        combine(completionActions.active, gate.busyHabitIds) { local, shared -> local + shared }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptySet())
    } ?: completionActions.active

    private fun launchCompletion(habitId: Long, identity: com.dayforge.domain.model.CountActionIdentity? = null,
        isCount: Boolean = false, timing: com.dayforge.domain.service.CompletionActionTiming.Operation? = null,
        selection: com.dayforge.data.local.DatabaseObjectSelection? = null,
        sourceEpoch: com.dayforge.data.local.DatabaseEpochPublication? = selection?.publication,
        block: suspend (HabitEntity?) -> Unit) {
        val operation: suspend () -> Unit = {
            try {
                if (isCount) (identity?.habitUuid ?: selection?.uuid)?.let { countButtonModels.begin(it, sourceEpoch) }
                val admitted: suspend () -> Unit = {
                    withRenderedInput(habitId, selection, sourceEpoch) { verifiedHabit ->
                        val reader = dashboardReadRepository
                        if (timing == null || reader == null) block(verifiedHabit)
                        else reader.withInteractiveReadPriority { block(verifiedHabit) }
                    }
                }
                // Include source admission and its UUID check in the original operation trace.
                if (timing == null) admitted()
                else com.dayforge.domain.service.CompletionActionTiming.withOperation(timing, admitted)
            }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.e(TAG, "Completion operation failed", error)
                android.widget.Toast.makeText(context, com.dayforge.ui.actionFailureMessage(context, error),
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
        if (isCount && countActions != null) {
            if (identity != null && countActions.launch(viewModelScope, identity, operation) == null) {
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    if (databaseHost == null || sourceEpoch?.isCurrentInMemory() == true)
                        Toast.makeText(context, R.string.widget_count_busy_not_recorded, Toast.LENGTH_SHORT).show()
                }
            }
        } else completionActions.launch(viewModelScope, habitId, block = operation)
    }

    /**
     * Increment count for a COUNTING habit.
     * Uses the shared completion coordinator for consistent behavior across habit screens.
     * Triggers goal completion dialog if targetCycles reached.
     * Triggers post-check-in dialog for linked metrics.
     */
    fun incrementCount(habitId: Long, selection: com.dayforge.data.local.DatabaseObjectSelection? = null) {
        if (!acceptsRenderedInput(habitId, selection)) return
        val displayed = habitsWithStats.value.find { it.habit.id == habitId } ?: return
        launchCompletion(habitId, displayed.countActionIdentity, isCount = true,
            timing = com.dayforge.domain.service.CompletionActionTiming.Operation.COUNT_INCREMENT, selection = selection) {
            val prompt = linkedMetricCoordinator.captureCompletionPrompt()
            val outcome = completionCoordinator.incrementCount(habitId, displayed.countActionIdentity) {
                displayed.habit
            }
            if (countActions != null && displayed.countActionIdentity?.let(countActions::isCurrent) != true) return@launchCompletion
            if (!acceptCountButtonResult(displayed.habit.uuid, outcome.command, selection?.publication)) return@launchCompletion
            if (publishCompletionFollowUp(outcome, displayed.habit, prompt)) return@launchCompletion
            outcome.goalProgress?.let { progress -> onGoalReached(displayed.habit, progress) }
            outcome.metricPromptHabit?.let { habit ->
                com.dayforge.domain.service.afterCommittedAction {
                    if (displayed.countActionIdentity != null) linkedMetricCoordinator.showCountPrompt(displayed.countActionIdentity, habit.name, outcome.command)
                    else showBoundPostCheckInDialog(habitId, habit.name)
                }
            }
        }
    }

    /**
     * Decrement count for a COUNTING habit.
     * Uses the shared completion coordinator for consistent behavior across habit screens.
     * Triggers goal completion dialog if targetCycles reached.
     */
    fun decrementCount(habitId: Long, selection: com.dayforge.data.local.DatabaseObjectSelection? = null) {
        if (!acceptsRenderedInput(habitId, selection)) return
        val displayed = habitsWithStats.value.find { it.habit.id == habitId } ?: return
        launchCompletion(habitId, displayed.countActionIdentity, isCount = true,
            timing = com.dayforge.domain.service.CompletionActionTiming.Operation.COUNT_DECREMENT, selection = selection) {
            val outcome = completionCoordinator.decrementCount(habitId, displayed.countActionIdentity)
            if (countActions != null && displayed.countActionIdentity?.let(countActions::isCurrent) != true) return@launchCompletion
            if (!acceptCountButtonResult(displayed.habit.uuid, outcome.command, selection?.publication)) return@launchCompletion
            outcome.goalProgress?.let { progress -> onGoalReached(displayed.habit, progress) }
        }
    }

    /**
     * Update the aggregation type for a metric.
     * Called when user changes aggregation type in TrendChart.
     */
    private val aggregationActions = com.dayforge.ui.InFlightActions()

    fun updateAggregationType(metricId: Long, aggregationType: String, selection: DatabaseObjectSelection? = null) {
        if (!acceptsRenderedInput(metricId, selection, DatabaseObjectSelection.Kind.METRIC)) return
        aggregationActions.launch(viewModelScope, metricId) {
            try {
                if (databaseHost == null) metricRepository.updateAggregationType(metricId, aggregationType)
                else databaseHost.withSelection(selection, DatabaseObjectSelection.Kind.METRIC) { graph ->
                    check(requireNotNull(selection).id == metricId) { "DATABASE_EPOCH_SELECTION_CHANGED" }
                    selection.requireMetric(requireNotNull(graph.overviewReadRepository.metric(metricId)) { "DATABASE_EPOCH_SELECTION_CHANGED" })
                    graph.metricRepository.updateAggregationType(metricId, aggregationType, expectedMetricUuid = selection.uuid)
                }
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
