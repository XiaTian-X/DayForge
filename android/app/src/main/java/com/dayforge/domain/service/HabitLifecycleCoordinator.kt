package com.dayforge.domain.service

import android.content.Context
import com.dayforge.data.local.DatabaseEpochPublication
import com.dayforge.data.local.DatabaseEpochValue
import com.dayforge.data.local.DatabaseRuntimeHost
import com.dayforge.data.local.TokenManager
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
class HabitLifecycleCoordinator private constructor(
    private val context: Context,
    private val fixedRepository: HabitRepository?,
    private val databases: DatabaseRuntimeHost?,
    private val stableTokens: TokenManager?
) {
    constructor(context: Context, habitRepository: HabitRepository) : this(context, habitRepository, null, null)
    @Inject internal constructor(@ApplicationContext context: Context, databases: DatabaseRuntimeHost,
        tokens: TokenManager) : this(context, null, databases, tokens)
    private val habitRepository get() = fixedRepository ?: requireNotNull(databases).boundGraph().habitRepository
    private val _goalFrames = MutableStateFlow<DatabaseEpochValue<HabitGoalDialogState>?>(null)
    internal val goalFrames = _goalFrames.asStateFlow()
    private val _reactivationFrames = MutableStateFlow<DatabaseEpochValue<HabitReactivationDialogState>?>(null)
    internal val reactivationFrames = _reactivationFrames.asStateFlow()

    private val _showGoalDialog = MutableStateFlow(false)
    val showGoalDialog: StateFlow<Boolean> = _showGoalDialog.asStateFlow()

    private val _goalHabitId = MutableStateFlow<Long?>(null)
    val goalHabitId: StateFlow<Long?> = _goalHabitId.asStateFlow()

    private val _goalProgress = MutableStateFlow(0)
    val goalProgress: StateFlow<Int> = _goalProgress.asStateFlow()

    private val _goalTarget = MutableStateFlow(0)
    val goalTarget: StateFlow<Int> = _goalTarget.asStateFlow()
    private class GoalRequest(val habit: HabitEntity, val snapshot: WidgetFactSnapshot? = null,
        val followUp: (suspend (WidgetFactSnapshot, () -> Boolean) -> Unit)? = null,
        val publication: DatabaseEpochPublication? = null)
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
    private var reactivationPublication: DatabaseEpochPublication? = null
    private val _reactivationPreservesHistory = MutableStateFlow(false)
    val reactivationPreservesHistory: StateFlow<Boolean> = _reactivationPreservesHistory.asStateFlow()

    init {
        if (databases == null) habitRepository.registerCompletionConsumer(invalidator)
        else requireNotNull(stableTokens).registerPersonalDisplay(invalidator)
    }
    private val graphRegistration = databases?.observeMemory(object : DatabaseRuntimeHost.MemoryListener {
        override fun transition(blocked: Boolean) {
            if (blocked) synchronized(presentationMonitor) {
                clearGoalDialog(); clearReactivationDialog()
                _goalFrames.value = null; _reactivationFrames.value = null
            }
        }
    })

    /** Original TokenManager source fence -> presentation monitor -> Host memory gate. */
    internal fun publishGoalDisplay(frame: DatabaseEpochValue<HabitGoalDialogState>?, assignment: () -> Unit): Boolean {
        fun assign() = synchronized(presentationMonitor) {
            if (_goalFrames.value !== frame || frame?.value?.show == true && blocked) false
            else if (frame == null) { assignment(); true } else frame.publication.publish(assignment)
        }
        return if (frame == null) assign() else frame.publication.withSourceFence(::assign)
    }
    internal fun publishReactivationDisplay(frame: DatabaseEpochValue<HabitReactivationDialogState>?, assignment: () -> Unit): Boolean {
        fun assign() = synchronized(presentationMonitor) {
            if (_reactivationFrames.value !== frame || frame?.value?.show == true && blocked) false
            else if (frame == null) { assignment(); true } else frame.publication.publish(assignment)
        }
        return if (frame == null) assign() else frame.publication.withSourceFence(::assign)
    }

    private fun publishGoalFrame(publication: DatabaseEpochPublication?) {
        if (databases == null) return
        if (publication == null || !_showGoalDialog.value) { _goalFrames.value = null; return }
        val value = HabitGoalDialogState(true, _goalHabitId.value, _goalProgress.value, _goalTarget.value,
            goalRequest?.habit?.uuid)
        _goalFrames.value = DatabaseEpochValue(value, publication)
    }
    private fun publishReactivationFrame(publication: DatabaseEpochPublication?) {
        if (databases == null) return
        if (publication == null || !_showReactivationDialog.value) { _reactivationFrames.value = null; return }
        val value = HabitReactivationDialogState(true, _reactivationHabitId.value,
            _reactivationHabitName.value, _reactivationPreservesHistory.value, reactivationRequest?.habit?.uuid)
        _reactivationFrames.value = DatabaseEpochValue(value, publication)
    }

    fun showGoalCompletion(habit: HabitEntity?, progress: Int) {
        check(habit?.appearance == null) { "COMPLETION_FOLLOW_UP_REQUIRED" }
        val target = habit?.targetCycles ?: return
        synchronized(presentationMonitor) {
            if (blocked) return
            goalGeneration.incrementAndGet()
            goalRequest = GoalRequest(habit, publication = databases?.currentPublication())
            publishGoal(habit, progress, target)
        }
    }

    suspend fun showGoalCompletion(snapshot: WidgetFactSnapshot,
        isCurrent: () -> Boolean = { true },
        followUp: suspend (WidgetFactSnapshot, () -> Boolean) -> Unit) {
        val target = requireNotNull(snapshot.habit.targetCycles)
        check(snapshot.targetProgress >= target) { "COMPLETION_GOAL_CHANGED" }
        val generation = goalGeneration.incrementAndGet()
        val request = GoalRequest(snapshot.habit, snapshot, followUp, databases?.currentPublication())
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
        publishGoalFrame(goalRequest?.publication)
    }

    /** Capture before coroutine dispatch; an older button cannot adopt a newer dialog. */
    fun captureGoalCompletion(complete: Boolean): suspend () -> Boolean {
        check(databases == null) { "DATABASE_EPOCH_INPUT_REQUIRED" }
        val request = synchronized(presentationMonitor) { goalRequest.takeUnless { blocked } }
        return { applyGoal(request, complete) }
    }

    internal fun captureGoalCompletion(complete: Boolean,
        frame: DatabaseEpochValue<HabitGoalDialogState>): suspend () -> Boolean {
        val request = synchronized(presentationMonitor) {
            check(_goalFrames.value === frame && frame.value.show) { "DATABASE_EPOCH_SELECTION_CHANGED" }
            goalRequest.takeUnless { blocked }
        }
        return { applyGoal(request, complete) }
    }

    suspend fun confirmGoalCompletion(): Boolean {
        check(databases == null) { "DATABASE_EPOCH_INPUT_REQUIRED" }
        return applyGoal(goalRequest, true)
    }

    suspend fun dismissGoalDialog(habit: HabitEntity?) {
        check(databases == null) { "DATABASE_EPOCH_INPUT_REQUIRED" }
        val request = goalRequest ?: return
        if (habit != null) check(habit.id == request.habit.id && habit.uuid == request.habit.uuid) {
            "COMPLETION_GOAL_CHANGED"
        }
        applyGoal(request, false)
    }

    private suspend fun applyGoal(request: GoalRequest?, complete: Boolean): Boolean {
        if (request == null) return false
        return if (databases == null) applyGoalInGraph(request, complete)
            else databases.withSourceGraph(request.publication) { applyGoalInGraph(request, complete) }
    }

    private suspend fun applyGoalInGraph(request: GoalRequest?, complete: Boolean): Boolean {
        if (request == null || blocked || goalRequest !== request) return false
        val next = request.snapshot?.let { habitRepository.applyCompletionGoal(context, it, complete) }
        if (request.snapshot == null) {
            if (complete) habitRepository.updateIsActive(request.habit.id, false, context, expectedHabitUuid = request.habit.uuid)
            else if (request.habit.failMode == FailMode.STRICT)
                habitRepository.updateFailMode(request.habit.id, FailMode.LOOSE, context, expectedHabitUuid = request.habit.uuid)
        }
        return afterCommittedAction {
            val current = synchronized(presentationMonitor) {
                if (blocked || goalRequest !== request) false else { _showGoalDialog.value = false; publishGoalFrame(request.publication); true }
            }
            if (!current) return@afterCommittedAction false
            if (next != null) request.followUp?.invoke(next) { !blocked && goalRequest === request }
            synchronized(presentationMonitor) { if (goalRequest === request) clearGoalDialog() }
            true
        }
    }

    private fun clearGoalDialog() {
        val publication = goalRequest?.publication
        goalGeneration.incrementAndGet()
        goalRequest = null
        _showGoalDialog.value = false
        _goalHabitId.value = null
        _goalProgress.value = 0
        _goalTarget.value = 0
        publishGoalFrame(publication)
    }

    suspend fun showReactivationDialog(habit: HabitEntity?) {
        habit ?: return
        val publication = databases?.currentPublication()
        val generation = synchronized(presentationMonitor) { ++reactivationGeneration }
        val request = habitRepository.prepareReactivation(habit)
        synchronized(presentationMonitor) {
            if (blocked || generation != reactivationGeneration) return
            reactivationRequest = request
            reactivationPublication = publication
            _reactivationPreservesHistory.value = request.preservesHistory
            _reactivationHabitId.value = habit.id
            _reactivationHabitName.value = habit.name
            // Keep the visible state from exposing an empty/stale payload.
            _showReactivationDialog.value = true
            publishReactivationFrame(publication)
        }
    }

    /** Capture at the button click, not later when its coroutine is dispatched. */
    fun captureReactivation(habit: HabitEntity?): suspend () -> ReactivationResult {
        check(databases == null) { "DATABASE_EPOCH_INPUT_REQUIRED" }
        val request = reactivationRequest
        return { confirmReactivation(habit, request) }
    }

    internal fun captureReactivation(habit: HabitEntity?,
        frame: DatabaseEpochValue<HabitReactivationDialogState>): suspend () -> ReactivationResult {
        val request = synchronized(presentationMonitor) {
            check(_reactivationFrames.value === frame && frame.value.show) { "DATABASE_EPOCH_SELECTION_CHANGED" }
            reactivationRequest
        }
        return { confirmReactivation(habit, request, frame.publication) }
    }

    suspend fun confirmReactivation(habit: HabitEntity?): ReactivationResult {
        check(databases == null) { "DATABASE_EPOCH_INPUT_REQUIRED" }
        return confirmReactivation(habit, reactivationRequest)
    }

    private suspend fun confirmReactivation(habit: HabitEntity?,
        captured: com.dayforge.data.repository.HabitReactivationRequest?,
        publication: DatabaseEpochPublication? = null): ReactivationResult {
        val request = captured ?: return ReactivationResult.NO_HABIT
        val result = if (habit == null || habit.id != request.habit.id || habit.uuid != request.habit.uuid) {
            ReactivationResult.NO_HABIT
        } else {
            try {
                if (databases == null) habitRepository.reactivate(request, context)
                else databases.withSourceGraph(publication) { habitRepository.reactivate(request, context) }
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
        check(databases == null) { "DATABASE_EPOCH_INPUT_REQUIRED" }
        synchronized(presentationMonitor) { clearReactivationDialog() }
    }

    internal fun dismissReactivationDialog(frame: DatabaseEpochValue<HabitReactivationDialogState>) {
        synchronized(presentationMonitor) {
            if (_reactivationFrames.value === frame) clearReactivationDialog()
        }
    }

    private fun clearReactivationDialog() {
        val publication = reactivationPublication
        reactivationPublication = null
        reactivationGeneration++
        reactivationRequest = null
        _showReactivationDialog.value = false
        _reactivationHabitId.value = null
        _reactivationHabitName.value = ""
        _reactivationPreservesHistory.value = false
        publishReactivationFrame(publication)
    }
}

data class HabitGoalDialogState(val show: Boolean, val habitId: Long?, val progress: Int, val target: Int,
    val habitUuid: String? = null)
data class HabitReactivationDialogState(val show: Boolean, val habitId: Long?, val habitName: String,
    val preservesHistory: Boolean, val habitUuid: String? = null)

enum class ReactivationResult {
    SUCCESS,
    FAILURE,
    NO_HABIT
}
