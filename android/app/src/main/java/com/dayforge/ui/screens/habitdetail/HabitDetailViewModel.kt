package com.dayforge.ui.screens.habitdetail

import kotlinx.coroutines.flow.stateIn

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import com.dayforge.data.local.businessDate
import com.dayforge.data.local.toDisplayMillis
import androidx.lifecycle.viewModelScope
import com.dayforge.R
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.model.HabitType
import com.dayforge.data.model.StreakStats
import com.dayforge.data.repository.HabitRepository
import com.dayforge.ui.retryChangedHabitSnapshot
import com.dayforge.domain.service.StreakCalculator
import com.dayforge.util.DateTimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import javax.inject.Inject

/**
 * Display information for a metric linked to a GOAL habit.
 * Aggregates data from the goal habit and its children.
 */
data class MetricDisplayInfo(
    val metric: MetricEntity,
    val currentValue: String,      // Formatted value or "--" if no records
    val currentRawValue: Double?,  // Raw numeric value for distance calculation
    val trackedDays: Int,          // Days with at least one log
    val targetDays: Int?,          // GOAL habit's targetCycles (for day progress)
    val achievementRate: Int?,     // Day progress percentage
    val distanceToTarget: String?  // Distance to metric target, null if no target
)

data class HabitDetailUiState(
    val habitId: Long = 0,
    val habit: HabitEntity? = null,
    val streakStats: StreakStats? = null,
    val completions: List<CompletionEntity> = emptyList(),
    val timeLogs: List<TimeLogEntity> = emptyList(),
    val lastCompletionId: Long? = null,
    val oneTimeStatus: com.dayforge.domain.model.OneTimeStatus? = null,
    val writeAuthority: com.dayforge.data.repository.ObjectEditAuthority? = null,
    val countHistory: com.dayforge.domain.model.CountHistory? = null,
    val countActionIdentity: com.dayforge.domain.model.CountActionIdentity? = null,
    val checkActionIdentity: com.dayforge.domain.model.CheckActionIdentity? = null,
    val activityRate: Int = 100,
    val targetProgress: Int = 0,  // Distinct days completed for habits with targetCycles
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val readError: Boolean = false,
    // Per TARGET-02: Reactivation confirmation dialog state
    val showReactivationDialog: Boolean = false,
    val reactivationHabitName: String = "",
    val reactivationPreservesHistory: Boolean = false,
    val reactivationInput: com.dayforge.data.repository.HabitReactivationRequest? = null,
    // Metrics for GOAL habits: aggregated from self and children
    val metrics: List<MetricDisplayInfo> = emptyList(),
    // Per NOTIFY-04: Notification enabled state for this habit
    val notificationEnabled: Boolean = true,
    val preferenceClaim: com.dayforge.data.repository.HabitPreferenceClaim? = null,
    val sourceSelection: com.dayforge.data.local.DatabaseObjectSelection? = null
)

@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HabitDetailViewModel internal constructor(
    @param:ApplicationContext private val context: Context,
    habitRepository: HabitRepository?,
    private val preferencesManager: PreferencesManager,
    timeLogDao: TimeLogDao?,
    completionDao: CompletionDao?,
    habitDao: HabitDao?,
    habitMetricLinkDao: HabitMetricLinkDao?,
    metricDao: MetricDao?,
    metricLogDao: MetricLogDao?,
    habitPreferences: com.dayforge.data.repository.HabitPreferenceRepository? = null,
    private val countActions: com.dayforge.domain.service.CountActionGate? = null,
    private val databaseHost: com.dayforge.data.local.DatabaseRuntimeHost? = null,
    private val linkedMetrics: com.dayforge.ui.metrics.LinkedMetricCoordinator? = null
) : ViewModel() {
    private val fixedHabitRepository = habitRepository
    private val habitRepository: HabitRepository get() = databaseHost?.boundGraph()?.habitRepository ?: requireNotNull(fixedHabitRepository)
    private val fixedHabitPreferences = habitPreferences
    private val habitPreferences: com.dayforge.data.repository.HabitPreferenceRepository? get() = databaseHost?.boundGraph()?.habitPreferenceRepository ?: fixedHabitPreferences

    private val fixedTargetReads = if (databaseHost == null) com.dayforge.data.repository.HabitTargetReadRepository(
        requireNotNull(habitDao), requireNotNull(timeLogDao), requireNotNull(completionDao),
        requireNotNull(metricDao), requireNotNull(habitMetricLinkDao), requireNotNull(metricLogDao)) else null
    private val targetReads: com.dayforge.data.repository.HabitTargetReadRepository
        get() = databaseHost?.boundGraph()?.habitTargetReadRepository ?: requireNotNull(fixedTargetReads)

    @Inject internal constructor(@ApplicationContext context: Context,
        databaseHost: com.dayforge.data.local.DatabaseRuntimeHost,
        preferencesManager: PreferencesManager,
        countActions: com.dayforge.domain.service.CountActionGate,
        linkedMetrics: com.dayforge.ui.metrics.LinkedMetricCoordinator
    ) : this(context, null, preferencesManager, null, null, null, null, null, null, null,
        countActions, databaseHost, linkedMetrics)

    internal val fixedGraphConstruction: Boolean get() = databaseHost == null

    val postCheckInFrame: StateFlow<com.dayforge.data.local.DatabaseEpochValue<com.dayforge.ui.metrics.LinkedMetricPromptState?>?> =
        if (databaseHost != null && linkedMetrics != null) com.dayforge.ui.producerEpochFrameState(viewModelScope,
            databaseHost, com.dayforge.ui.VisibleScreenSharing, linkedMetrics.postCheckInFrames, linkedMetrics::publishPromptFrame)
        else MutableStateFlow(null)
    val postCheckInState: StateFlow<com.dayforge.ui.metrics.LinkedMetricPromptState?> =
        if (databaseHost == null && linkedMetrics != null) linkedMetrics.postCheckInState else MutableStateFlow(null)

    private suspend fun <T> withMetricPrompt(frame: com.dayforge.data.local.DatabaseEpochValue<com.dayforge.ui.metrics.LinkedMetricPromptState?>?,
        block: suspend (com.dayforge.ui.metrics.LinkedMetricCoordinator) -> T): T {
        val owner = requireNotNull(linkedMetrics) { "COUNT_PROMPT_CONSUMER_REQUIRED" }
        return if (databaseHost == null) block(owner) else databaseHost.withSourceGraph(frame?.publication) { block(owner) }
    }
    suspend fun recordCountMetrics(state: com.dayforge.ui.metrics.LinkedMetricPromptState,
        values: List<com.dayforge.ui.components.MetricValueInput>, neverAsk: Boolean,
        frame: com.dayforge.data.local.DatabaseEpochValue<com.dayforge.ui.metrics.LinkedMetricPromptState?>?) = withMetricPrompt(frame) { owner ->
        if (!owner.recordMetricValues(state.habitId, values, expectedFactPrompt = state.factPrompt,
            expectedPreferenceClaim = state.preferenceClaim, frame = frame, neverAskAgain = neverAsk)) return@withMetricPrompt false
        com.dayforge.domain.service.afterCommittedAction {
            if (neverAsk) owner.setNeverAskAgain(state.habitId, true, expectedFactPrompt = state.factPrompt,
                expectedPreferenceClaim = state.preferenceClaim, frame = frame)
            owner.closePrompt(state, frame)
        }
        true
    }
    suspend fun continueSavedCountMetrics(state: com.dayforge.ui.metrics.LinkedMetricPromptState,
        frame: com.dayforge.data.local.DatabaseEpochValue<com.dayforge.ui.metrics.LinkedMetricPromptState?>?) = withMetricPrompt(frame) { owner ->
        val prompt = requireNotNull(state.factPrompt)
        val saved = requireNotNull(prompt.savedSubmissionForDisplay())
        if (!owner.continueSavedMetricDelivery(state.habitId, prompt, frame)) return@withMetricPrompt false
        com.dayforge.domain.service.afterCommittedAction {
            if (saved.neverAskAgain) owner.setNeverAskAgain(state.habitId, true, expectedFactPrompt = prompt,
                expectedPreferenceClaim = state.preferenceClaim, frame = frame)
            owner.closePrompt(state, frame)
        }
        true
    }
    suspend fun skipCountMetrics(state: com.dayforge.ui.metrics.LinkedMetricPromptState, neverAsk: Boolean,
        frame: com.dayforge.data.local.DatabaseEpochValue<com.dayforge.ui.metrics.LinkedMetricPromptState?>?) = withMetricPrompt(frame) { owner ->
        if (neverAsk) owner.setNeverAskAgain(state.habitId, true, expectedFactPrompt = state.factPrompt,
            expectedPreferenceClaim = state.preferenceClaim, frame = frame)
        owner.skipPrompt(state.habitId, expectedFactPrompt = state.factPrompt, expectedPreferenceClaim = state.preferenceClaim, frame = frame)
    }
    suspend fun dismissCountMetrics(state: com.dayforge.ui.metrics.LinkedMetricPromptState,
        frame: com.dayforge.data.local.DatabaseEpochValue<com.dayforge.ui.metrics.LinkedMetricPromptState?>?) =
        withMetricPrompt(frame) { it.closePrompt(state, frame) }


    private val _uiState = MutableStateFlow(HabitDetailUiState())
    val uiState: StateFlow<HabitDetailUiState> = _uiState.asStateFlow()

    private val expiredSourceMessage = context.getString(R.string.database_view_expired)
    private val presentationMonitor = Any()
    @Volatile private var routeSelection: com.dayforge.data.local.DatabaseObjectSelection? = null
    private val sourceListener = object : com.dayforge.data.local.DatabaseRuntimeHost.MemoryListener {
        override fun transition(blocked: Boolean) {
            val before = synchronized(presentationMonitor) { _uiState.value }
            if (blocked || before.sourceSelection?.publication?.isCurrentInMemory() != true) {
                // A synchronous memory fence only clears presentation. Host owns asynchronous
                // cancellation/draining; no Room work or coroutine continuation runs here.
                synchronized(presentationMonitor) { if (_uiState.value.sourceSelection === before.sourceSelection)
                    _uiState.value = HabitDetailUiState(habitId = before.habitId, isLoading = false,
                        readError = true, errorMessage = expiredSourceMessage)
                }
            }
        }
    }
    private val sourceRegistration = databaseHost?.observeMemory(sourceListener)

    private fun publishState(source: com.dayforge.data.local.DatabaseObjectSelection?,
        next: () -> HabitDetailUiState): Boolean = if (databaseHost == null) {
        synchronized(presentationMonitor) { _uiState.value = next() }; true
    } else {
        var assigned = false
        source?.publication?.publish {
            synchronized(presentationMonitor) { if (routeSelection === source) {
                _uiState.value = next().copy(sourceSelection = source)
                assigned = true
            } }
        }
        assigned
    }

    private suspend fun <T> inSource(source: com.dayforge.data.local.DatabaseObjectSelection?,
        work: com.dayforge.data.local.DatabaseRuntimeHost.Work = com.dayforge.data.local.DatabaseRuntimeHost.Work.COMMAND,
        block: suspend () -> T): T {
        val host = databaseHost ?: return block()
        check(source != null && source.kind == com.dayforge.data.local.DatabaseObjectSelection.Kind.HABIT) {
            "DATABASE_EPOCH_INPUT_REQUIRED"
        }
        return host.withSourceGraph(source.publication, work) { graph ->
            source.requireHabit(requireNotNull(graph.habitTargetReadRepository.habit(source.id)))
            block()
        }
    }

    private var currentHabitId: Long? = null
    // Undo feedback is not an ID-only ticket that may be rebound by a later account/round read.
    private var lastCountCompletionIdentity: com.dayforge.domain.model.CountActionIdentity? = null
    private data class CheckCompletionSource(
        val completionId: Long,
        val original: com.dayforge.domain.model.CheckActionIdentity,
        val successor: com.dayforge.domain.model.CheckActionIdentity,
        val successorDisplayed: Boolean
    ) {
        fun accepts(displayed: com.dayforge.domain.model.CheckActionIdentity?) =
            displayed == successor || (!successorDisplayed && displayed == original)
    }
    private var lastCheckCompletionSource: CheckCompletionSource? = null
    private var habitLoadJob: Job? = null
    private var readsEnabled = true
    fun pauseReads() {
        readsEnabled = false
        habitLoadJob?.cancel()
        habitLoadJob = null
        clearReactivationDialog()
        // Preserve display/undo state, but recapture write claims before another typed action.
        synchronized(presentationMonitor) {
            _uiState.value = _uiState.value.copy(writeAuthority = null, oneTimeStatus = null, preferenceClaim = null,
                countActionIdentity = null, checkActionIdentity = null)
        }
    }

    fun resumeReads() {
        if (readsEnabled) return
        readsEnabled = true
        val id = currentHabitId ?: return
        currentHabitId = null
        loadHabit(id, routeSelection)
    }
    private var reactivationRequest: com.dayforge.data.repository.HabitReactivationRequest? = null
    private var reactivationGeneration = 0
    fun retryRead(rendered: HabitDetailUiState? = null) {
        if (databaseHost != null) {
            val frame = actionFrame(rendered) ?: return
            // An old error's retry must not close a later dialog or restart a newer page.
            if (frame !== _uiState.value || !frame.readError) return
        }
        val id = currentHabitId ?: return
        clearReactivationDialog()
        currentHabitId = null
        loadHabit(id, routeSelection) // Restart DAO/preferences subscriptions as well as the projection.
    }

    fun loadHabit(habitId: Long, sourceSelection: com.dayforge.data.local.DatabaseObjectSelection? = null) {
        if (databaseHost != null && (sourceSelection == null || sourceSelection.id != habitId ||
                sourceSelection.kind != com.dayforge.data.local.DatabaseObjectSelection.Kind.HABIT ||
                !sourceSelection.publication.isCurrentInMemory())) {
            habitLoadJob?.cancel()
            _uiState.value = HabitDetailUiState(habitId = habitId, isLoading = false, readError = true,
                errorMessage = context.getString(R.string.database_view_expired))
            return
        }
        val sameRoute = routeSelection === sourceSelection
        // Reset if different habit to ensure fresh data load
        if (currentHabitId != null && (currentHabitId != habitId || !sameRoute)) {
            lastCountCompletionIdentity = null
            lastCheckCompletionSource = null
            reactivationGeneration++
            reactivationRequest = null
            _uiState.value = HabitDetailUiState(habitId = habitId, isLoading = true)
        }
        if (currentHabitId == habitId && sameRoute) return
        habitLoadJob?.cancel()
        currentHabitId = habitId
        routeSelection = sourceSelection

        if (!readsEnabled) return

        habitLoadJob = viewModelScope.launch {
            try { inSource(sourceSelection, com.dayforge.data.local.DatabaseRuntimeHost.Work.OBSERVATION) {
            publishState(sourceSelection) { _uiState.value.copy(isLoading = true, habitId = habitId) }

            // Combine habit info, completions, and notification setting for reactive updates
            // Per NOTIFY-04: Include notification preference to avoid nested collect leak
            combine(
                habitRepository.getHabit(habitId),
                combine(habitRepository.getAllCompletions(), habitRepository.oneTimeChanges,
                    habitRepository.countChanges, preferencesManager.dateChangeTrigger) { rows, _, _, _ -> rows },
                habitPreferences?.changes ?: kotlinx.coroutines.flow.flowOf(Unit)
            ) { habit, allCompletions, _ -> habit to allCompletions }
                .mapLatest { (habit, allCompletions) ->
                val notificationEnabled = true
                try {
                if (databaseHost != null && habit != null) requireNotNull(sourceSelection).requireHabit(habit)
                val habitCompletions = allCompletions.filter { it.habitId == habitId }
                val recurringSnapshot = habit?.takeIf { it.appearance != null && it.completionPolicy == "recurring" &&
                    it.habitType in setOf(HabitType.COUNTING, HabitType.CHECK_IN, HabitType.TIMER) }
                    ?.let { habitRepository.getRecurringSnapshot(it) }
                val countHistory = recurringSnapshot?.countHistory
                val checkHistory = recurringSnapshot?.checkHistory
                val timerHistory = recurringSnapshot?.timerHistory

                // Calculate targetProgress for habits with targetCycles
                // Per TARGET-06: TIMER habits use timelogs, other types use completions
                val targetProgress = if (habit?.targetCycles != null) {
                    if (countHistory != null) countHistory.qualifiedDates.size
                    else if (checkHistory != null) checkHistory.qualifiedDates.size
                    else if (timerHistory != null) timerHistory.qualifiedDates.size
                    else if (habit.habitType == HabitType.TIMER) {
                        targetReads.timeDayCount(habitId)
                    } else {
                        targetReads.completionDayCount(habitId)
                    }
                } else {
                    0
                }

                // For TIMER habits, use TimeLogEntity for streaks and history
                if (habit?.completionPolicy == "one_and_done") {
                    LoadResult(habit, null, habitCompletions, emptyList(), 0, emptyList(), notificationEnabled,
                        habitRepository.getOneTimeStatus(habitId, habit.uuid))
                } else if (countHistory != null) {
                    val qualified = countHistory.qualifiedDates
                    LoadResult(habit, StreakStats(StreakCalculator.currentFromBusinessDates(qualified, countHistory.today),
                        StreakCalculator.bestFromBusinessDates(qualified), qualified.maxOrNull()?.toDisplayMillis()),
                        countHistory.completions, emptyList(), targetProgress, emptyList(), notificationEnabled,
                        countHistory = countHistory, recurringSnapshot = recurringSnapshot)
                } else if (checkHistory != null) {
                    val qualified = checkHistory.qualifiedDates
                    LoadResult(habit, StreakStats(StreakCalculator.currentFromBusinessDates(qualified, checkHistory.today),
                        StreakCalculator.bestFromBusinessDates(qualified), qualified.maxOrNull()?.toDisplayMillis()),
                        checkHistory.completions, emptyList(), targetProgress, emptyList(), notificationEnabled,
                        recurringSnapshot = recurringSnapshot)
                } else if (timerHistory != null) {
                    val qualified = timerHistory.qualifiedDates
                    // Date-only display rows, never persisted or exposed as writable timer results.
                    val display = qualified.map { date -> CompletionEntity(habitId = habitId, date = date.toDisplayMillis(),
                        value = 1, recordedLocalDate = date.toString()) }
                    LoadResult(habit, StreakStats(StreakCalculator.currentFromBusinessDates(qualified, timerHistory.today),
                        StreakCalculator.bestFromBusinessDates(qualified), qualified.maxOrNull()?.toDisplayMillis()),
                        display, timerHistory.logs, targetProgress, emptyList(), notificationEnabled,
                        recurringSnapshot = recurringSnapshot)
                } else if (habit?.habitType == HabitType.TIMER) {
                    val timeLogs = targetReads.timeHistory(habitId)
                    val targetSeconds = habit.targetValue * 60

                    // Get dates where target was met
                    val completedDates = timeLogs
                        .groupBy { it.date }
                        .filter { (_, logs) -> logs.sumOf { it.durationSeconds } >= targetSeconds }
                        .keys
                        .toList()

                    val currentStreak = StreakCalculator.calculateCurrentStreakFromDates(completedDates)
                    val bestStreak = StreakCalculator.calculateBestStreakFromDates(completedDates)
                    val lastCompletionDate = completedDates.maxOrNull()
                    val streakStats = StreakStats(currentStreak, bestStreak, lastCompletionDate)

                    // Create pseudo-completions from time logs for history display
                    val completions = timeLogs
                        .groupBy { it.date }
                        .filter { (_, logs) -> logs.sumOf { it.durationSeconds } >= targetSeconds }
                        .map { (date, _) ->
                            CompletionEntity(habitId = habitId, date = date, value = 1)
                        }

                    LoadResult(habit, streakStats, completions, timeLogs, targetProgress, emptyList(), notificationEnabled)
                } else if (habit?.habitType == HabitType.GOAL) {
                    // GOAL habit: aggregate metrics from self and children
                    val metrics = loadGoalHabitMetrics(habit)
                    LoadResult(habit, null, emptyList(), emptyList(), targetProgress, metrics, notificationEnabled)
                } else {
                    val currentStreak = StreakCalculator.calculateCurrentStreak(habitCompletions)
                    val bestStreak = StreakCalculator.calculateBestStreak(habitCompletions)
                    val lastCompletionDate = habitCompletions.maxOfOrNull { it.businessDate }?.toDisplayMillis()
                    val streakStats = StreakStats(currentStreak, bestStreak, lastCompletionDate)

                    LoadResult(habit, streakStats, habitCompletions, emptyList(), targetProgress, emptyList(), notificationEnabled)
                }
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    if (error is com.dayforge.data.repository.HabitReadSnapshotChanged) throw error
                    android.util.Log.e("HabitDetail", "Cannot read personal data; retry remains available", error)
                    LoadResult(null, null, emptyList(), emptyList(), 0, emptyList(), notificationEnabled,
                        errorMessage = error.message ?: "HABIT_READ_UNAVAILABLE")
                }
            }.retryChangedHabitSnapshot().catch { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                android.util.Log.e("HabitDetail", "Read source failed; explicit retry restarts the subscription", error)
                emit(LoadResult(null, null, emptyList(), emptyList(), 0, emptyList(), true,
                    errorMessage = error.message ?: "HABIT_READ_UNAVAILABLE"))
            }.collect { result ->
                val captured = try {
                    result.recurringSnapshot?.let { com.dayforge.data.repository.ObjectEditSnapshot(it.habit, it.authority) }
                        ?: result.habit?.takeIf { it.appearance != null && it.completionPolicy == "recurring" }
                        ?.let { habitRepository.getHabitForEditing(it.id) }
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    publishState(sourceSelection) { HabitDetailUiState(habitId = habitId, isLoading = false, readError = true,
                        errorMessage = error.message) }
                    return@collect
                }
                if (captured != null && captured.value != result.habit) return@collect
                val countIdentity = try {
                    result.countHistory?.actionIdentity ?: result.habit
                        ?.takeIf { it.appearance == null && it.habitType == HabitType.COUNTING }
                        ?.let { habitRepository.legacyCountIdentity(it) }
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    publishState(sourceSelection) { HabitDetailUiState(habitId = habitId, isLoading = false, readError = true,
                        errorMessage = error.message) }
                    return@collect
                }
                val preference = try { result.habit?.let { habitPreferences?.read(it) } }
                catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    publishState(sourceSelection) { HabitDetailUiState(habitId = habitId, isLoading = false, readError = true,
                        errorMessage = error.message) }
                    return@collect
                }
                val staleCountUndo = countIdentity != null && lastCountCompletionIdentity?.let {
                    !sameCountScope(it, countIdentity)
                } == true
                if (staleCountUndo) lastCountCompletionIdentity = null
                val checkIdentity = result.recurringSnapshot?.checkHistory?.actionIdentity
                val staleCheckUndo = lastCheckCompletionSource?.let { !it.accepts(checkIdentity) } == true
                if (staleCheckUndo) lastCheckCompletionSource = null
                else lastCheckCompletionSource?.takeIf { it.successor == checkIdentity }?.let {
                    lastCheckCompletionSource = it.copy(successorDisplayed = true)
                }
                publishState(sourceSelection) { _uiState.value.copy(
                    habit = result.habit,
                    writeAuthority = captured?.authority,
                    streakStats = result.streakStats,
                    completions = result.completions,
                    timeLogs = result.timeLogs,
                    targetProgress = result.targetProgress,
                    metrics = result.metrics,
                    notificationEnabled = preference?.notificationEnabled ?: result.notificationEnabled,
                    preferenceClaim = preference?.claim,
                    oneTimeStatus = result.oneTimeStatus,
                    countHistory = result.countHistory,
                    countActionIdentity = countIdentity,
                    checkActionIdentity = checkIdentity,
                    activityRate = result.recurringSnapshot?.activityRate ?: result.habit?.activityRate ?: 100,
                    errorMessage = result.errorMessage,
                    readError = result.errorMessage != null,
                    lastCompletionId = if (result.oneTimeStatus != null) result.oneTimeStatus.completionId
                        else if (staleCountUndo || staleCheckUndo) null
                        else _uiState.value.lastCompletionId,
                    isLoading = false
                ) }
            }
            } } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                publishState(sourceSelection) { HabitDetailUiState(habitId = habitId, isLoading = false,
                    readError = true, errorMessage = error.message) }
            }
        }
    }

    /**
     * Toggles notification enabled state for the current habit.
     * Per NOTIFY-04: User can disable notifications per-habit.
     *
     * @param habitId The ID of the habit
     * @param enabled True to enable notifications for this habit, false to disable
     */
    fun toggleNotificationEnabled(claim: com.dayforge.data.repository.HabitPreferenceClaim, enabled: Boolean,
        rendered: HabitDetailUiState? = null) {
        val frame = actionFrame(rendered) ?: return
        if (databaseHost != null && frame.preferenceClaim !== claim) return
        viewModelScope.launch {
            try { inSource(frame.sourceSelection) {
                requireNotNull(habitPreferences).setNotification(claim, enabled)
                if (_uiState.value.preferenceClaim === claim)
                    publishState(frame.sourceSelection) { _uiState.value.copy(notificationEnabled = enabled) }
            } } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                publishState(frame.sourceSelection) { _uiState.value.copy(errorMessage = error.message) }
            }
        }
    }

    /**
     * Loads and aggregates metrics for a GOAL habit and its children.
     * Deduplicates metrics by metricId.
     * Uses Dispatchers.IO to ensure database operations run off the main thread.
     */
    private suspend fun loadGoalHabitMetrics(habit: HabitEntity): List<MetricDisplayInfo> = withContext(Dispatchers.IO) {
        val todayStart = DateTimeUtils.startOfDayMillis()
        val todayEnd = DateTimeUtils.startOfNextDayMillis(todayStart)

        // Get metric links for the goal habit itself
        val ownLinks = targetReads.visibleLinks(habit.id)

        // Get children and their metric links
        val children = habit.uuid.let { parentUuid ->
            targetReads.children(parentUuid).first()
        }
        val childLinks = children.flatMap { child ->
            targetReads.visibleLinks(child.id)
        }

        // Aggregate all links, deduplicate by metricId
        val allLinks = (ownLinks + childLinks)
            .groupBy { it.metricId }
            .map { it.value.first() }

        // Build MetricDisplayInfo for each unique metric
        allLinks.mapNotNull { link ->
            val metric = targetReads.visibleMetric(link.metricId)
            if (metric == null) return@mapNotNull null

            // Get today's logs
            val todayLogs = targetReads.metricRange(link.metricId, todayStart, todayEnd)

            // Calculate current value (raw and formatted)
            val (formattedValue, rawValue) = calculateCurrentValueWithRaw(metric, todayLogs)

            // Get all logs to count tracked days
            val allLogs = targetReads.metricHistory(link.metricId)
            val trackedDays = allLogs.map { DateTimeUtils.startOfDayMillis(it.date) }.distinct().size

            // Calculate targetDays and achievementRate based on GOAL habit's targetCycles
            val targetDays = habit.targetCycles
            val achievementRate = if (targetDays != null && trackedDays > 0) {
                ((trackedDays.toDouble() / targetDays.toDouble()) * 100).toInt().coerceIn(0, 100)
            } else null

            // Calculate distance to metric target
            val distanceToTarget = calculateDistanceToTarget(metric, rawValue)

            MetricDisplayInfo(
                metric = metric,
                currentValue = formattedValue,
                currentRawValue = rawValue,
                trackedDays = trackedDays,
                targetDays = targetDays,
                achievementRate = achievementRate,
                distanceToTarget = distanceToTarget
            )
        }.sortedByDescending { it.trackedDays }
    }

    /**
     * Calculates current value based on metric's aggregation type.
     * Returns pair of (formatted string, raw double value).
     * Returns ("--", null) if no logs.
     */
    private fun calculateCurrentValueWithRaw(metric: MetricEntity, logs: List<MetricLogEntity>): Pair<String, Double?> {
        if (logs.isEmpty()) return Pair("--", null)

        val value = when (metric.aggregationType) {
            "average" -> logs.sumOf { it.value } / logs.size
            "sum" -> logs.sumOf { it.value }
            "by_time" -> logs.maxByOrNull { it.date }?.value ?: return Pair("--", null)
            else -> logs.sumOf { it.value } / logs.size // Default to average
        }

        // Format based on decimalPlaces - use integer format when decimalPlaces is 0
        val pattern = if (metric.decimalPlaces == 0) "#" else "#.${"#".repeat(metric.decimalPlaces)}"
        return Pair(DecimalFormat(pattern).format(value), value)
    }

    /**
     * Calculates distance to metric target.
     * Handles three target directions: increase, decrease, range.
     * Returns null if metric has no target or no current value.
     */
    private fun calculateDistanceToTarget(metric: MetricEntity, currentValue: Double?): String? {
        if (currentValue == null || metric.targetDirection == null) return null

        val unit = metric.unit
        val decimalPlaces = metric.decimalPlaces
        val pattern = if (decimalPlaces == 0) "#" else "#.${"#".repeat(decimalPlaces)}"
        val formatter = DecimalFormat(pattern)

        return when (metric.targetDirection) {
            "increase" -> {
                val target = metric.targetValue ?: return null
                if (currentValue >= target) {
                    // Already reached target
                    null
                } else {
                    val distance = target - currentValue
                    "距离目标还差${formatter.format(distance)}$unit"
                }
            }
            "decrease" -> {
                val target = metric.targetValue ?: return null
                if (currentValue <= target) {
                    // Already reached target
                    null
                } else {
                    val distance = currentValue - target
                    "距离目标还差${formatter.format(distance)}$unit"
                }
            }
            "range" -> {
                val lower = metric.targetValue ?: return null
                val upper = metric.targetValueUpper ?: return null
                if (currentValue >= lower && currentValue <= upper) {
                    // Within range
                    null
                } else if (currentValue < lower) {
                    val distance = lower - currentValue
                    "距离下限还差${formatter.format(distance)}$unit"
                } else {
                    val distance = currentValue - upper
                    "距离上限还差${formatter.format(distance)}$unit"
                }
            }
            else -> null
        }
    }

    private data class LoadResult(
        val habit: HabitEntity?,
        val streakStats: StreakStats?,
        val completions: List<CompletionEntity>,
        val timeLogs: List<TimeLogEntity>,
        val targetProgress: Int,
        val metrics: List<MetricDisplayInfo>,
        val notificationEnabled: Boolean,  // Per NOTIFY-04: Included to avoid nested collect
        val oneTimeStatus: com.dayforge.domain.model.OneTimeStatus? = null,
        val countHistory: com.dayforge.domain.model.CountHistory? = null,
        val errorMessage: String? = null,
        val recurringSnapshot: com.dayforge.data.repository.RecurringHabitSnapshot? = null
    )

    private fun actionFrame(rendered: HabitDetailUiState?): HabitDetailUiState? {
        val frame = if (databaseHost == null) rendered ?: _uiState.value else rendered
        if (databaseHost != null && (frame?.sourceSelection == null ||
                frame.sourceSelection !== routeSelection || frame.habitId != currentHabitId ||
                !frame.sourceSelection.publication.isCurrentInMemory())) {
            Toast.makeText(context, R.string.database_view_expired, Toast.LENGTH_LONG).show()
            return null
        }
        return frame
    }

    fun logCompletion(value: Int = 1, rendered: HabitDetailUiState? = null) {
        val frame = actionFrame(rendered) ?: return
        val habitId = frame.habitId
        val displayed = frame.habit ?: return
        if (frame.readError) return
        val authority = frame.writeAuthority
        val identity = frame.countActionIdentity
        val checkIdentity = frame.checkActionIdentity
        launchCompletion(identity, displayed.habitType == HabitType.COUNTING, frame.sourceSelection) {
            val promptRequest = if (displayed.habitType == HabitType.COUNTING) linkedMetrics?.captureCompletionPrompt() else null
            if (displayed.appearance != null && displayed.completionPolicy == "recurring" && displayed.habitType == HabitType.CHECK_IN)
                requireNotNull(checkIdentity) { "CHECK_ACTION_REQUIRED" }
            val checkSuccessor = checkIdentity?.completionSuccessor(displayed)
            val mutation = if (displayed.appearance != null && displayed.completionPolicy == "recurring")
                habitRepository.logCompletionWithReceipt(context, habitId, value, requireNotNull(authority), displayed.uuid,
                    countIdentity = identity, checkIdentity = checkIdentity)
            else habitRepository.logCompletionWithReceipt(context, habitId, value, expectedHabitUuid = displayed.uuid, countIdentity = identity)
            val completionId = mutation.value
            // A precomputed successor is never evidence that a cancelled/unknown write committed.
            val checkSource = checkIdentity?.let {
                CheckCompletionSource(completionId, it, requireNotNull(checkSuccessor),
                    successorDisplayed = _uiState.value.checkActionIdentity == checkSuccessor)
            }
            if (currentHabitId == habitId && completionId != 0L && isCurrentCountDisplay(identity) &&
                (checkSource == null || checkSource.accepts(_uiState.value.checkActionIdentity))) {
                lastCountCompletionIdentity = identity
                lastCheckCompletionSource = checkSource
                val delivered = publishState(frame.sourceSelection) { _uiState.value.copy(lastCompletionId = completionId) }
                if (delivered) mutation.command?.let { receipt ->
                    com.dayforge.domain.service.afterCommittedAction {
                        if (!receipt.requiresMetricFollowUp) habitRepository.acknowledgeCountResultDelivery(receipt)
                        else if (linkedMetrics != null) {
                            if (displayed.appearance != null) {
                                val source = habitRepository.readCompletionAfterProjected(
                                    com.dayforge.data.repository.CompletionActionSnapshot(
                                        com.dayforge.data.repository.ObjectEditSnapshot(displayed, requireNotNull(authority))),
                                    allowActivation = true, recordedCompletionId = completionId).followUp
                                check(source.claim.completionUuid == receipt.factUuid) { "COMMAND_FOLLOWUP_SOURCE_CHANGED" }
                                linkedMetrics.showCompletionPrompt(source, requireNotNull(promptRequest),
                                    isCurrent = { currentHabitId == habitId && routeSelection === frame.sourceSelection && isCurrentCountDisplay(identity) },
                                    command = receipt)
                            } else linkedMetrics.showCountPrompt(requireNotNull(identity), displayed.name, receipt)
                        }
                    }
                }
            }
        }
    }

    fun undoCompletion(rendered: HabitDetailUiState? = null) {
        val frame = actionFrame(rendered) ?: return
        val habitId = frame.habitId
        val displayed = frame.habit ?: return
        if (frame.readError) return
        val completionId = frame.lastCompletionId ?: return
        val authority = frame.oneTimeStatus?.authority
        val recurringAuthority = frame.writeAuthority
        val identity = frame.countActionIdentity
        val isTypedCheck = displayed.habitType == HabitType.CHECK_IN && displayed.appearance != null &&
            displayed.completionPolicy == "recurring"
        val checkSource = if (isTypedCheck) lastCheckCompletionSource?.takeIf {
            it.completionId == completionId && it.accepts(_uiState.value.checkActionIdentity)
        } ?: return else null
        // A fresh authority may validate the saved successor, but cannot define a new undo identity.
        val checkIdentity = checkSource?.successor
        val undoAuthority = if (checkSource != null && _uiState.value.checkActionIdentity != checkIdentity)
            null else recurringAuthority
        if (displayed.habitType == HabitType.COUNTING && countActions != null &&
            (identity == null || lastCountCompletionIdentity?.let { sameCountScope(it, identity) } != true)) return
        launchCompletion(identity, displayed.habitType == HabitType.COUNTING, frame.sourceSelection) {
            if (displayed.appearance != null && displayed.completionPolicy == "recurring" && displayed.habitType == HabitType.CHECK_IN)
                requireNotNull(checkIdentity) { "CHECK_ACTION_REQUIRED" }
            val mutation = habitRepository.undoCompletionWithReceipt(context, completionId, authority, undoAuthority,
                countIdentity = identity, checkIdentity = checkIdentity)
            if (currentHabitId == habitId && _uiState.value.lastCompletionId == completionId && isCurrentCountDisplay(identity) &&
                (checkSource == null || checkSource.accepts(_uiState.value.checkActionIdentity))) {
                lastCountCompletionIdentity = null
                lastCheckCompletionSource = null
                val delivered = publishState(frame.sourceSelection) { _uiState.value.copy(lastCompletionId = null) }
                if (delivered) mutation.command?.let { receipt ->
                    com.dayforge.domain.service.afterCommittedAction { habitRepository.acknowledgeCountResultDelivery(receipt) }
                }
            }
        }
    }

    private val completionActions = com.dayforge.ui.InFlightActions()
    val busyHabitIds = countActions?.let { gate ->
        combine(completionActions.active, gate.busyHabitIds) { local, shared -> local + shared }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptySet())
    } ?: completionActions.active

    private fun isCurrentCountDisplay(identity: com.dayforge.domain.model.CountActionIdentity?): Boolean =
        identity == null || _uiState.value.countActionIdentity?.let {
            (countActions == null || countActions.isCurrent(identity)) && sameCountScope(it, identity)
        } == true

    private fun sameCountScope(first: com.dayforge.domain.model.CountActionIdentity,
        second: com.dayforge.domain.model.CountActionIdentity) = first.session == second.session &&
        first.deviceId == second.deviceId && first.habitId == second.habitId &&
        first.habitUuid == second.habitUuid && first.roundHead == second.roundHead

    private fun launchCompletion(identity: com.dayforge.domain.model.CountActionIdentity? = null,
        isCount: Boolean = false, source: com.dayforge.data.local.DatabaseObjectSelection? = null,
        block: suspend () -> Unit) {
        val id = currentHabitId ?: return
        val operation: suspend () -> Unit = {
            try { inSource(source) { block() } }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                publishState(source) { _uiState.value.copy(errorMessage = error.message) }
            }
        }
        if (isCount && countActions != null) {
            if (identity != null) countActions.launch(viewModelScope, identity, operation)
        } else completionActions.launch(viewModelScope, id, block = operation)
    }

    /**
     * Checks if the active status can be toggled for the given habit.
     * Blocks deactivation if the habit has a target and progress > 0.
     * Per TARGET-06: TIMER habits use timelogs table for progress calculation.
     *
     * @param habit The habit to check
     * @return true if toggle is allowed, false otherwise
     */
    private suspend fun canToggleActive(habit: HabitEntity): Boolean {
        // Reactivation is always allowed (will be handled by TARGET-02 for history clearing)
        if (!habit.isActive) return true

        // No target cycles means unlimited tracking - allow deactivation
        if (habit.targetCycles == null) return true

        // Check progress - Per TARGET-06: TIMER uses timelogs, others use completions
        val progress = if (habit.habitType == HabitType.COUNTING && habit.appearance != null) {
            habitRepository.getCountHistory(habit).qualifiedDates.size
        } else if (habit.habitType == HabitType.CHECK_IN && habit.appearance != null && habit.completionPolicy == "recurring") {
            habitRepository.getCheckHistory(habit).qualifiedDates.size
        } else if (habit.habitType == HabitType.TIMER && habit.appearance != null) {
            habitRepository.getTimerHistory(habit).qualifiedDates.size
        } else if (habit.habitType == HabitType.TIMER) {
            targetReads.timeDayCount(habit.id)
        } else {
            targetReads.completionDayCount(habit.id)
        }

        // Block deactivation if there's progress
        return progress == 0
    }

    /**
     * Toggles the active status of the current habit.
     * Validates that deactivation is allowed before proceeding.
     * Per TARGET-02: For reactivation of target-based habits, shows confirmation dialog first.
     * Syncs to server if user is logged in.
     */
    fun toggleActiveStatus(rendered: HabitDetailUiState? = null) {
        val frame = actionFrame(rendered) ?: return
        launchCompletion(source = frame.sourceSelection) {
            frame.habit?.let { habit ->
                val habitId = habit.id
                val newIsActive = !habit.isActive

                // If trying to deactivate, validate first
                if (!newIsActive) {
                    val canToggle = canToggleActive(habit)
                    if (!canToggle) {
                        // Show error message
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_goal_habit_cannot_deactivate),
                            Toast.LENGTH_SHORT
                        ).show()
                        return@let
                    }
                }

                // Per TARGET-02: If reactivating a target-based habit, show confirmation dialog first
                if (!habit.isActive && newIsActive && habit.targetCycles != null) {
                    val generation = ++reactivationGeneration
                    val request = habitRepository.prepareReactivation(habit)
                    if (currentHabitId != habitId || generation != reactivationGeneration) return@let
                    reactivationRequest = request
                    publishState(frame.sourceSelection) { _uiState.value.copy(
                        showReactivationDialog = true,
                        reactivationHabitName = habit.name,
                        reactivationPreservesHistory = request.preservesHistory,
                        reactivationInput = request
                    ) }
                    return@let
                }

                // Direct toggle for non-target habits or deactivation
                performToggleActive(habit, newIsActive)
            }
        }
    }

    /**
     * Confirms the captured restart; challenge profiles retain all previous facts.
     * Per TARGET-02: Called when user confirms the reactivation dialog.
     */
    fun confirmReactivation(rendered: HabitDetailUiState? = null) {
        val frame = actionFrame(rendered) ?: return
        val request = if (databaseHost == null) reactivationRequest else frame.reactivationInput
        if (request == null || request !== reactivationRequest) return
        launchCompletion(source = frame.sourceSelection) {
            if (currentHabitId == request.habit.id) {
                habitRepository.reactivate(request, context)
                Toast.makeText(
                    context,
                    context.getString(if (request.preservesHistory) R.string.restart_completed else R.string.toast_habit_reactivated),
                    Toast.LENGTH_SHORT
                ).show()
            }
            if (reactivationRequest === request) dismissReactivationDialog(frame)
        }
    }

    /**
     * Dismisses the reactivation confirmation dialog without making changes.
     * Per TARGET-02: Called when user cancels the reactivation dialog.
     */
    fun dismissReactivationDialog(rendered: HabitDetailUiState? = null) {
        val frame = actionFrame(rendered) ?: return
        if (rendered != null && (frame.reactivationInput !== reactivationRequest ||
                frame.sourceSelection !== _uiState.value.sourceSelection)) return
        clearReactivationDialog()
    }
    private fun clearReactivationDialog() {
        reactivationGeneration++
        reactivationRequest = null
        synchronized(presentationMonitor) { _uiState.value = _uiState.value.copy(
            showReactivationDialog = false,
            reactivationHabitName = "",
            reactivationPreservesHistory = false,
            reactivationInput = null
        ) }
    }

    /**
     * Performs the actual toggle active operation.
     * @param habitId The ID of the habit
     * @param newIsActive The new active status
     */
    private suspend fun performToggleActive(habit: HabitEntity, newIsActive: Boolean) {
        habitRepository.updateIsActive(
            habitId = habit.id,
            isActive = newIsActive,
            context = context,
            expectedHabitUuid = habit.uuid
        )
    }

    fun getStreakStats(): StreakStats? {
        return _uiState.value.streakStats
    }
}
