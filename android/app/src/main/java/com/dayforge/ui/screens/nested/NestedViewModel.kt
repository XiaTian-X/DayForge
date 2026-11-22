package com.dayforge.ui.screens.nested

import android.content.Context
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
    val checkActionIdentity: com.dayforge.domain.model.CheckActionIdentity? = null,
    val sourceEpoch: com.dayforge.data.local.DatabaseEpochPublication? = null
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
    val dayProgress: Int = 0,  // Days since creation (for GOAL type display "第 X 天 / 共 Y 天")
    val sourceEpoch: com.dayforge.data.local.DatabaseEpochPublication? = null
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
class NestedViewModel internal constructor(
    @param:ApplicationContext private val context: Context,
    habitDao: HabitDao?,
    timeLogDao: TimeLogDao?,
    habitRepository: HabitRepository?,
    nestedHabitTreeBuilder: NestedHabitTreeBuilder?,
    private val completionCoordinator: HabitCompletionCoordinator,
    private val deletionCoordinator: HabitDeletionCoordinator,
    private val preferencesManager: PreferencesManager,
    private val metricCoordinator: LinkedMetricCoordinator,
    private val lifecycleCoordinator: HabitLifecycleCoordinator,
    private val timerCoordinator: HabitTimerCoordinator,
    private val countActions: com.dayforge.domain.service.CountActionGate? = null,
    private val databaseHost: com.dayforge.data.local.DatabaseRuntimeHost? = null
) : ViewModel() {
    private val fixedOverviewReads by lazy {
        com.dayforge.data.repository.OverviewReadRepository(requireNotNull(timeLogDao), habitDao)
    }
    private val overviewReads get() = databaseHost?.boundGraph()?.overviewReadRepository ?: fixedOverviewReads
    private val fixedHabitRepository = habitRepository
    private val habitRepository: HabitRepository get() = databaseHost?.boundGraph()?.habitRepository ?: requireNotNull(fixedHabitRepository)
    private val fixedNestedHabitTreeBuilder = nestedHabitTreeBuilder
    private val nestedHabitTreeBuilder: NestedHabitTreeBuilder get() = databaseHost?.boundGraph()?.nestedHabitTreeBuilder ?: requireNotNull(fixedNestedHabitTreeBuilder)

    @Inject internal constructor(@ApplicationContext context: Context,
        databaseHost: com.dayforge.data.local.DatabaseRuntimeHost,
        completionCoordinator: HabitCompletionCoordinator, deletionCoordinator: HabitDeletionCoordinator,
        preferencesManager: PreferencesManager, metricCoordinator: LinkedMetricCoordinator,
        lifecycleCoordinator: HabitLifecycleCoordinator, timerCoordinator: HabitTimerCoordinator,
        countActions: com.dayforge.domain.service.CountActionGate
    ) : this(context, null, null, null, null, completionCoordinator, deletionCoordinator,
        preferencesManager, metricCoordinator, lifecycleCoordinator, timerCoordinator, countActions, databaseHost)


    internal val fixedGraphConstruction: Boolean get() = databaseHost == null

    companion object {
        private const val TAG = "NestedViewModel"
    }

    // Track if data has been loaded at least once
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    // Listen to all completions to trigger UI updates
    private val allCompletions by lazy { this@NestedViewModel.habitRepository.getAllCompletions() }

    /**
     * Top-level habits (parentHabitId = null) with their children and stats.
     */
    private val readMonitor = com.dayforge.ui.DataReadMonitor(TAG)
    val readError = readMonitor.error
    fun retryRead() = readMonitor.retry()
    val topLevelHabitsWithChildren: StateFlow<List<ParentHabitWithChildren>> = databaseHost?.let { host ->
        com.dayforge.ui.epochPublishedState(viewModelScope, host, com.dayforge.ui.VisibleScreenSharing,
            emptyList(), host.observe { graph -> readMonitor.recover(emptyList()) { combine(
                graph.overviewReadRepository.topLevelHabits(),
                combine(graph.habitRepository.getAllCompletions(), graph.habitRepository.oneTimeChanges,
                    graph.habitRepository.countChanges) { rows, _, _ -> rows },
                graph.overviewReadRepository.activeTimerChanges(), preferencesManager.dateChangeTrigger
            ) { habits, completions, _, _ -> habits to completions }.mapLatest { (habits, completions) ->
                readMonitor.read(emptyList()) { graph.nestedHabitTreeBuilder.build(habits, completions) }.let { rows ->
                    val source = host.currentPublication()
                    rows.map { it.copy(sourceEpoch = source,
                        children = it.children.map { child -> child.copy(sourceEpoch = source) }) }
                }
            } } }, initialized = { _isInitialized.value = it })
    } ?: readMonitor.recover(emptyList()) { combine(
        this@NestedViewModel.overviewReads.topLevelHabits(),
        combine(allCompletions, this@NestedViewModel.habitRepository.oneTimeChanges, this@NestedViewModel.habitRepository.countChanges) { rows, _, _ -> rows },
        this@NestedViewModel.overviewReads.activeTimerChanges(),
        preferencesManager.dateChangeTrigger
    ) { topLevelHabits, completions, _, _ -> topLevelHabits to completions }
        .mapLatest { (topLevelHabits, completions) ->
            Log.d(TAG, "topLevelHabitsWithChildren combine triggered: topLevelHabits=${topLevelHabits.size}")
            readMonitor.read(emptyList()) { this@NestedViewModel.nestedHabitTreeBuilder.build(topLevelHabits, completions) }
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
     * Habits that have pending metric recording.
     */
    val pendingMetricHabits: StateFlow<Set<Long>> = databaseHost?.let { host ->
        com.dayforge.ui.epochPublishedState(viewModelScope, host, com.dayforge.ui.VisibleScreenSharing,
            emptySet(), metricCoordinator.pendingMetricHabitFrames)
    } ?: metricCoordinator.pendingMetricHabits
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
    val linkedMetricFrame: StateFlow<DatabaseEpochValue<Map<Long, List<LinkedMetricInfo>>>?> = databaseHost?.let { host ->
        val values = host.observe { graph -> metricCoordinator.observeLinkedMetrics(
            habitIds = graph.habitRepository.allHabits.map { rows -> rows.map { it.id }.toSet() },
            onlyShownInHabitDetail = false
        ) }
        com.dayforge.ui.epochPublishedState(viewModelScope, host, com.dayforge.ui.VisibleScreenSharing,
            null, values.map { frame -> DatabaseEpochValue<DatabaseEpochValue<Map<Long, List<LinkedMetricInfo>>>?>(frame, frame.publication) })
    } ?: MutableStateFlow(null)

    val linkedMetricsByHabit: StateFlow<Map<Long, List<LinkedMetricInfo>>> = if (databaseHost != null) MutableStateFlow(emptyMap()) else
        metricCoordinator.observeLinkedMetrics(
            habitIds = this@NestedViewModel.habitRepository.allHabits.map { habits -> habits.map { it.id }.toSet() },
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
    val postCheckInFrame: StateFlow<DatabaseEpochValue<LinkedMetricPromptState?>?> = databaseHost?.let { host ->
        com.dayforge.ui.producerEpochFrameState(viewModelScope, host, com.dayforge.ui.VisibleScreenSharing,
            metricCoordinator.postCheckInFrames, metricCoordinator::publishPromptFrame)
    } ?: MutableStateFlow(null)
    val postCheckInState: StateFlow<LinkedMetricPromptState?> = if (databaseHost != null) MutableStateFlow(null)
        else metricCoordinator.postCheckInState

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

    // ========== Check-in Operations ==========

    /**
     * Log a completion for a child habit.
     * Uses the shared completion coordinator for consistent behavior with DashboardViewModel.
     * Per TARGET-15: Triggers goal completion dialog if targetCycles reached.
     * Triggers post-check-in dialog for linked metrics.
     */
    fun logCompletion(habitId: Long, value: Int = 1, selection: com.dayforge.data.local.DatabaseObjectSelection? = null) {
        if (!acceptsRenderedInput(habitId, selection)) return
        val displayed = topLevelHabitsWithChildren.value.flatMap { it.children }.find { it.habit.id == habitId }
        val displayedHabit = displayed?.habit ?: findHabitById(habitId)
        if (displayedHabit?.habitType == HabitType.COUNTING) {
            launchCompletion(habitId, displayed?.countActionIdentity, isCount = true, selection = selection) {
                val receipt = completionCoordinator.recordCompletion(habitId, value, displayed?.countActionIdentity)
                if (displayed != null) acceptCountButtonResult(displayed.habit.uuid, receipt, selection?.publication)
            }
            return
        }
        launchCompletion(habitId, timing = com.dayforge.domain.service.CompletionActionTiming.Operation.CHECK_IN, selection = selection) {
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
                com.dayforge.domain.service.afterCommittedAction { showBoundPostCheckInDialog(habitId, habit.name) }
            }
        }
    }

    /**
     * Undo a completion for a child habit.
     */
    fun undoCompletion(completionId: Long, selection: com.dayforge.data.local.DatabaseObjectSelection? = null) {
        if (databaseHost != null && !acceptsRenderedInput(selection?.id ?: -1L, selection)) return
        val displayed = topLevelHabitsWithChildren.value.flatMap { it.children }
            .find { it.lastCompletionId == completionId } ?: return
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
                val admitted: suspend () -> Unit = { withRenderedInput(habitId, selection, sourceEpoch, block) }
                if (timing == null) admitted()
                else com.dayforge.domain.service.CompletionActionTiming.withOperation(timing, admitted)
            }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.e(TAG, "Completion operation failed", error)
                val message = com.dayforge.ui.actionFailureMessage(context, error)
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
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
        val displayed = topLevelHabitsWithChildren.value.flatMap { it.children }.find { it.habit.id == habitId } ?: return
        launchCompletion(habitId, displayed.countActionIdentity, isCount = true,
            timing = com.dayforge.domain.service.CompletionActionTiming.Operation.COUNT_INCREMENT, selection = selection) {
            val prompt = metricCoordinator.captureCompletionPrompt()
            val outcome = completionCoordinator.incrementCount(habitId, displayed.countActionIdentity) {
                displayed.habit
            }
            if (countActions != null && displayed.countActionIdentity?.let(countActions::isCurrent) != true) return@launchCompletion
            if (!acceptCountButtonResult(displayed.habit.uuid, outcome.command, selection?.publication)) return@launchCompletion
            if (publishCompletionFollowUp(outcome, displayed.habit, prompt)) return@launchCompletion
            outcome.goalProgress?.let { progress -> onGoalReached(displayed.habit, progress) }
            outcome.metricPromptHabit?.let { habit ->
                com.dayforge.domain.service.afterCommittedAction {
                    if (displayed.countActionIdentity != null) metricCoordinator.showCountPrompt(displayed.countActionIdentity, habit.name, outcome.command)
                    else showBoundPostCheckInDialog(habitId, habit.name)
                }
            }
        }
    }

    /**
     * Decrement count for a COUNTING habit.
     * Uses the shared completion coordinator for consistent behavior across habit screens.
     */
    fun decrementCount(habitId: Long, selection: com.dayforge.data.local.DatabaseObjectSelection? = null) {
        if (!acceptsRenderedInput(habitId, selection)) return
        val displayed = topLevelHabitsWithChildren.value.flatMap { it.children }.find { it.habit.id == habitId } ?: return
        launchCompletion(habitId, displayed.countActionIdentity, isCount = true,
            timing = com.dayforge.domain.service.CompletionActionTiming.Operation.COUNT_DECREMENT, selection = selection) {
            val outcome = completionCoordinator.decrementCount(habitId, displayed.countActionIdentity)
            if (countActions != null && displayed.countActionIdentity?.let(countActions::isCurrent) != true) return@launchCompletion
            if (!acceptCountButtonResult(displayed.habit.uuid, outcome.command, selection?.publication)) return@launchCompletion
            outcome.goalProgress?.let { progress -> onGoalReached(displayed.habit, progress) }
        }
    }

    // ========== Timer Operations ==========

    /**
     * Check if can start a timer. Returns result for UI to handle.
     *
     * @return StartTimerResult indicating whether to start, already completed, or has active timer
     */
    fun checkStartTimer(habitId: Long, targetMinutes: Int,
        selection: DatabaseObjectSelection? = null): StartTimerResult {
        if (databaseHost != null) {
            check(selection != null && selection.kind == DatabaseObjectSelection.Kind.HABIT && selection.id == habitId && selection.publication.belongsTo(databaseHost)) { "DATABASE_EPOCH_INPUT_REQUIRED" }
            check(selection.publication.isCurrentInMemory()) { "DATABASE_EPOCH_INPUT_EXPIRED" }
        }
        val currentTimer = (if (databaseHost == null) activeTimerState.value else activeTimerFrame.value?.value)

        // Check if there's an active timer for a different habit
        if (currentTimer != null && currentTimer.habitId != habitId) {
            return StartTimerResult.HasActiveTimer(currentTimer.habitId)
        }

        // Check if already completed today
        viewModelScope.launch {
            try { withRenderedInput(habitId, selection) {
            val todayStart = DateTimeUtils.startOfDayMillis()
            val todayEnd = DateTimeUtils.startOfNextDayMillis(todayStart)
            val completedSeconds = overviewReads.completedSeconds(
                habitId, java.time.LocalDate.now().toString(), todayStart, todayEnd
            )
            val targetSeconds = targetMinutes * 60

            if (completedSeconds >= targetSeconds) {
                // Already completed - UI should handle this
            }
            } } catch (error: Exception) { reportInputFailure(error) }
        }

        return StartTimerResult.Started
    }

    /**
     * Start a timer for a child habit.
     * Delegates to HabitTimerCoordinator for shared implementation.
     */
    fun startTimer(habitId: Long, targetMinutes: Int, selection: com.dayforge.data.local.DatabaseObjectSelection? = null) {
        if (!acceptsRenderedInput(habitId, selection)) return
        val authority = topLevelHabitsWithChildren.value.flatMap { it.children }
            .find { it.habit.id == habitId }?.timerAuthority
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
     * Stop the currently running timer.
     * Delegates to HabitTimerCoordinator for shared implementation.
     */
    fun stopTimer(frame: DatabaseEpochValue<ActiveTimerState?>? = null) {
        val currentState = captureInput {
            if (databaseHost == null) activeTimerState.value
            else requireNotNull(frame) { "DATABASE_EPOCH_INPUT_REQUIRED" }.value
        } ?: return
        launchCompletion(currentState.habitId, sourceEpoch = frame?.publication) {
            try {
                metricCoordinator.showPromptAfterTimerStop(timerCoordinator.stopTimer(currentState), currentState.authority)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Toast.makeText(context, com.dayforge.ui.actionFailureMessage(context, error), Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Check if a specific habit has the active timer.
     */
    fun isHabitTimerActive(habitId: Long): Boolean {
        return timerCoordinator.isHabitTimerActive((if (databaseHost == null) activeTimerState.value else activeTimerFrame.value?.value), habitId)
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
    suspend fun markBatteryGuidanceShown(selection: DatabaseObjectSelection? = null) {
        try {
            if (databaseHost == null) preferencesManager.setBatteryGuidanceShown()
            else databaseHost.withSelection(selection, DatabaseObjectSelection.Kind.HABIT) { preferencesManager.setBatteryGuidanceShown() }
        } catch (error: Exception) { reportInputFailure(error) }
    }

    // ========== Post-Check-In Dialog ==========

    /**
     * Called after a habit check-in to potentially show the metric recording dialog.
     */
    suspend fun checkAndShowPostCheckInDialog(habitId: Long, habitName: String,
        selection: com.dayforge.data.local.DatabaseObjectSelection? = null) {
        try { withRenderedInput(habitId, selection) { showBoundPostCheckInDialog(habitId, habitName) } }
        catch (error: Exception) { reportInputFailure(error) }
    }

    private suspend fun showBoundPostCheckInDialog(habitId: Long, habitName: String) {
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
        expectedFactPrompt: com.dayforge.ui.metrics.FactMetricPrompt? = null,
        frame: DatabaseEpochValue<LinkedMetricPromptState?>? = null,
        neverAskAgain: Boolean = false
    ): Boolean {
        // Establish diagnostics before acquiring the Host source lease or any command ticket.
        // Existing/recovered tickets are still acquired and consumed inside the original call.
        val record: suspend () -> Boolean = { withPromptInput(frame) {
            metricCoordinator.recordMetricValues(habitId, values, expectedEventUuid, expectedTimerPrompt,
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
            withPromptInput(frame) { metricCoordinator.continueSavedMetricDelivery(habitId, prompt, frame) }
        }


    /**
     * Set "never ask again" preference for a habit's metric prompt.
     */
    suspend fun setNeverAskAgain(habitId: Long, value: Boolean, expectedEventUuid: String? = null,
        expectedTimerPrompt: com.dayforge.ui.metrics.TimerMetricPrompt? = null,
        expectedPreferenceClaim: com.dayforge.data.repository.HabitPreferenceClaim? = null,
        expectedFactPrompt: com.dayforge.ui.metrics.FactMetricPrompt? = null,
        frame: DatabaseEpochValue<LinkedMetricPromptState?>? = null) = withPromptInput(frame) {
        metricCoordinator.setNeverAskAgain(habitId, value, expectedEventUuid, expectedTimerPrompt,
            expectedFactPrompt = expectedFactPrompt, expectedPreferenceClaim = expectedPreferenceClaim, frame = frame)
    }

    /**
     * Dismiss the post-check-in dialog.
     */
    suspend fun dismissPostCheckInDialog(expected: LinkedMetricPromptState? = null,
        frame: DatabaseEpochValue<LinkedMetricPromptState?>? = null) = withPromptInput(frame) {
        metricCoordinator.closePrompt(expected, frame)
    }

    suspend fun skipPostCheckInDialog(habitId: Long, expectedEventUuid: String? = null,
        expectedTimerPrompt: com.dayforge.ui.metrics.TimerMetricPrompt? = null,
        expectedPreferenceClaim: com.dayforge.data.repository.HabitPreferenceClaim? = null,
        expectedFactPrompt: com.dayforge.ui.metrics.FactMetricPrompt? = null,
        frame: DatabaseEpochValue<LinkedMetricPromptState?>? = null) = withPromptInput(frame) {
        metricCoordinator.skipPrompt(habitId, expectedEventUuid, expectedTimerPrompt,
            expectedFactPrompt = expectedFactPrompt, expectedPreferenceClaim = expectedPreferenceClaim, frame = frame)
    }

    suspend fun refreshPostCheckInMetadata(frame: DatabaseEpochValue<LinkedMetricPromptState?>? = null) {
        run {
            try { withPromptInput(frame) { metricCoordinator.refreshPrompt(frame) } }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                android.widget.Toast.makeText(context, context.getString(com.dayforge.R.string.metric_error_record_failed,
                    error.message.orEmpty()), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    suspend fun savePostCheckInDraft(eventUuid: String, inputs: List<com.dayforge.ui.components.MetricInputState>,
        frame: DatabaseEpochValue<LinkedMetricPromptState?>? = null) = withPromptInput(frame) {
        metricCoordinator.savePromptDraft(eventUuid, inputs, frame)
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
                isCurrent = { metricCoordinator.isCurrentCompletionPrompt(prompt) }) { next, isCurrent ->
                if (outcome.metricPromptHabit != null) metricCoordinator.showCompletionPrompt(next, prompt, isCurrent, outcome.command)
            } else if (outcome.metricPromptHabit != null) metricCoordinator.showCompletionPrompt(snapshot, prompt, command = outcome.command)
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
        val habit = findHabitById(habitId)
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
            val habit = findHabitById(habitId)
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

    // ========== Habit Delete ==========

    /**
     * Delete a child habit.
     */
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

    // ========== Expand State Management ==========

    /**
     * Toggle expand state for a parent habit.
     * Persists the change to DataStore.
     */
    fun toggleParentExpand(uuid: String, selection: DatabaseObjectSelection? = null) {
        viewModelScope.launch {
            try {
                val change: suspend () -> Unit = {
                    val current = expandedParentUuids.value
                    preferencesManager.setExpandedParentUuids(if (uuid in current) current - uuid else current + uuid)
                }
                if (databaseHost == null) change()
                else databaseHost.withSelection(selection, DatabaseObjectSelection.Kind.HABIT) {
                    check(requireNotNull(selection).uuid == uuid) { "DATABASE_EPOCH_SELECTION_CHANGED" }
                    change()
                }
            } catch (error: Exception) { reportInputFailure(error) }
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
