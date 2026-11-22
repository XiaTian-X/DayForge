package com.dayforge.ui.screens.editgoal

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.R
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.repository.HabitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import com.dayforge.data.local.DatabaseObjectSelection
import com.dayforge.data.local.DatabaseRuntimeHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI State for EditGoalScreen.
 */
data class EditGoalUiState(
    val goalId: Long? = null,
    val goalUuid: String? = null,
    val name: String = "",
    val description: String = "",
    val iconResId: Int = 0,
    val appearance: com.dayforge.domain.model.ObjectAppearance? = null,
    val editAuthority: com.dayforge.data.repository.ObjectEditAuthority? = null,
    val colorHex: String = "#2196F3",
    // GOAL type is always Daily schedule, no need to configure
    val targetCycles: Int? = null,
    val failMode: FailMode = FailMode.STRICT,
    val isActive: Boolean = true,

    // Child habits (key results)
    val children: List<HabitEntity> = emptyList(),

    // UI state
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isDeleting: Boolean = false,
    val showDeleteChildrenDialog: Boolean = false,
    val pendingDeleteChildrenCount: Int = 0,
    val saved: Boolean = false,
    val showIconPicker: Boolean = false,
    val showColorPicker: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val errorMessage: String? = null,
    val sourceSelection: DatabaseObjectSelection? = null,
    val sourceExpired: Boolean = false,
    val deleteRequest: Any? = null,
    val iconRequest: Any? = null,
    val colorRequest: Any? = null,
    val terminalRequest: Any? = null,
    val errorRequest: Any? = null
)

@HiltViewModel
class EditGoalViewModel internal constructor(
    @param:ApplicationContext private val context: Context,
    habitDao: HabitDao?, habitRepository: HabitRepository?,
    private val preferencesManager: PreferencesManager,
    private val databases: DatabaseRuntimeHost? = null
) : ViewModel() {
    @Inject internal constructor(@ApplicationContext context: Context, databases: DatabaseRuntimeHost,
        preferencesManager: PreferencesManager) : this(context, null, null, preferencesManager, databases)

    private val fixedReads = if (databases == null)
        com.dayforge.data.repository.GoalTargetReadRepository(requireNotNull(habitDao)) else null
    private val reads get() = databases?.boundGraph()?.goalTargetReadRepository ?: requireNotNull(fixedReads)
    private val fixedRepository = habitRepository
    private val habitRepository get() = databases?.boundGraph()?.habitRepository ?: requireNotNull(fixedRepository)
    internal val fixedGraphConstruction: Boolean get() = databases == null
    private val _uiState = MutableStateFlow(EditGoalUiState())
    val uiState: StateFlow<EditGoalUiState> = _uiState.asStateFlow()
    @Volatile private var boundSelection: DatabaseObjectSelection? = null
    private val presentationMonitor = Any()
    private var loadJob: Job? = null
    private var childrenJob: Job? = null
    @Volatile private var loadGeneration: Any = Any()
    @Volatile private var childrenGeneration: Any = Any()
    private enum class DeleteStage { INITIAL, CHILDREN }
    private data class DeleteRequest(val token: Any, val stage: DeleteStage, val id: Long,
        val uuid: String, val source: DatabaseObjectSelection?,
        val authority: com.dayforge.data.repository.ObjectEditAuthority?)
    private var pendingDelete: DeleteRequest? = null
    private val expiredMessage = context.getString(R.string.database_view_expired)
    private val registration = databases?.observeMemory(object : DatabaseRuntimeHost.MemoryListener {
        override fun transition(blocked: Boolean) {
            val source = boundSelection
            if (blocked || source?.publication?.isCurrentInMemory() != true) expireSource()
        }
    })

    private fun expireSource() {
        loadGeneration = Any(); childrenGeneration = Any()
        synchronized(presentationMonitor) {
            pendingDelete = null
            _uiState.value = EditGoalUiState(isLoading = false, sourceSelection = boundSelection,
                sourceExpired = true, isSaving = _uiState.value.isSaving, isDeleting = _uiState.value.isDeleting,
                errorMessage = expiredMessage)
        }
    }
    private fun inputExpired() = android.widget.Toast.makeText(context, R.string.database_view_expired,
        android.widget.Toast.LENGTH_LONG).show()

    private fun frame(rendered: EditGoalUiState?, requireGoal: Boolean = true): EditGoalUiState? {
        if (databases == null) return rendered ?: _uiState.value
        val selected = rendered?.sourceSelection
        if (rendered == null || selected == null || selected !== boundSelection || rendered.sourceExpired ||
            (requireGoal && (rendered.goalId != selected.id || rendered.goalUuid != selected.uuid)) ||
            _uiState.value.sourceSelection !== selected || _uiState.value.sourceExpired ||
            !selected.publication.isCurrentInMemory()) {
            inputExpired(); return null
        }
        return rendered
    }
    private fun publish(source: DatabaseObjectSelection?, update: (EditGoalUiState) -> EditGoalUiState): Boolean {
        if (databases == null) { synchronized(presentationMonitor) { _uiState.value = update(_uiState.value) }; return true }
        var assigned = false
        source?.publication?.publish {
            synchronized(presentationMonitor) { if (boundSelection === source) {
                _uiState.value = update(_uiState.value).copy(sourceSelection = source); assigned = true
            } }
        }
        return assigned
    }
    private fun edit(rendered: EditGoalUiState?, update: (EditGoalUiState) -> EditGoalUiState) {
        val selected = frame(rendered) ?: return
        publish(selected.sourceSelection, update)
    }
    private suspend fun <T> inSource(source: DatabaseObjectSelection?, observation: Boolean = false,
        block: suspend () -> T): T {
        val host = databases ?: return block()
        check(source != null && source === boundSelection && source.kind == DatabaseObjectSelection.Kind.HABIT) {
            "DATABASE_EPOCH_INPUT_REQUIRED"
        }
        return host.withSourceGraph(source.publication,
            if (observation) DatabaseRuntimeHost.Work.OBSERVATION else DatabaseRuntimeHost.Work.COMMAND) { block() }
    }
    /** Same original point read; missing-row/no-op behavior stays with the caller. */
    private suspend fun currentGoal(id: Long, source: DatabaseObjectSelection?): HabitEntity? =
        reads.habit(id).also { row -> if (databases != null && row != null) requireNotNull(source).requireHabit(row) }

    internal fun terminalIsCurrent(rendered: EditGoalUiState): Boolean = databases == null ||
        rendered.saved && rendered.terminalRequest != null && rendered.terminalRequest === _uiState.value.terminalRequest &&
            rendered.sourceSelection === boundSelection && !rendered.sourceExpired &&
            rendered.sourceSelection?.publication?.isCurrentInMemory() == true

    internal fun parentSelection(rendered: EditGoalUiState): DatabaseObjectSelection? = frame(rendered)?.sourceSelection
    internal fun childSelection(rendered: EditGoalUiState, child: HabitEntity): DatabaseObjectSelection? {
        val original = frame(rendered) ?: return null
        val source = original.sourceSelection ?: return null
        if (original.children.none { it.id == child.id && it.uuid == child.uuid }) { inputExpired(); return null }
        return DatabaseObjectSelection.habit(child, source.publication)
    }

    /** A target binds once to the original opaque route. No current epoch is looked up here. */
    fun loadGoal(goalId: Long, sourceSelection: DatabaseObjectSelection? = null) {
        if (databases != null) {
            if (sourceSelection == null || sourceSelection.kind != DatabaseObjectSelection.Kind.HABIT ||
                sourceSelection.id != goalId || (boundSelection != null && boundSelection !== sourceSelection) ||
                !sourceSelection.publication.isCurrentInMemory()) {
                expireSource(); inputExpired(); return
            }
            if (boundSelection === sourceSelection && (loadJob?.isActive == true ||
                    _uiState.value.goalId == goalId && !_uiState.value.sourceExpired)) return
            boundSelection = sourceSelection
        }
        loadJob?.cancel()
        val generation = Any().also { loadGeneration = it }
        loadJob = viewModelScope.launch {
            publish(sourceSelection) { it.copy(isLoading = true, sourceExpired = false, errorMessage = null, errorRequest = null) }
            try { inSource(sourceSelection, observation = true) {
                val snapshot = habitRepository.getHabitForEditing(goalId)
                val goal = snapshot.value
                if (goal == null) {
                    publish(sourceSelection) { it.copy(isLoading = false,
                        errorMessage = context.getString(R.string.goal_not_found), errorRequest = Any()) }
                    return@inSource
                }
                if (databases != null) requireNotNull(sourceSelection).requireHabit(goal)
                val children = reads.children(goal.uuid).first()
                if (loadGeneration !== generation) return@inSource
                publish(sourceSelection) { it.copy(goalId = goal.id, goalUuid = goal.uuid,
                    name = goal.name, description = goal.description, iconResId = goal.iconResId,
                    appearance = goal.appearance, editAuthority = snapshot.authority,
                    colorHex = goal.appearance?.accentColor ?: goal.colorHex, targetCycles = goal.targetCycles,
                    failMode = goal.failMode, isActive = goal.isActive,
                    children = java.util.Collections.unmodifiableList(children.toList()), isLoading = false,
                    sourceExpired = false, errorMessage = null, errorRequest = null) }
            } } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { if (loadGeneration === generation) publish(sourceSelection) {
                it.copy(isLoading = false, errorMessage = context.getString(R.string.toast_save_failed, error.message), errorRequest = Any())
            } }
        }
    }

    fun updateName(name: String, rendered: EditGoalUiState? = null) = edit(rendered) { it.copy(name = name) }
    fun updateDescription(description: String, rendered: EditGoalUiState? = null) = edit(rendered) { it.copy(description = description) }
    fun updateIcon(iconResId: Int, rendered: EditGoalUiState? = null) {
        val original = frame(rendered) ?: return
        if (databases != null && (original.iconRequest == null || original.iconRequest !== _uiState.value.iconRequest || !_uiState.value.showIconPicker)) return
        publish(original.sourceSelection) { if (it.appearance != null) it else it.copy(iconResId = iconResId) }
    }
    fun updateColor(colorHex: String, rendered: EditGoalUiState? = null) {
        val original = frame(rendered) ?: return
        if (databases != null && (original.colorRequest == null || original.colorRequest !== _uiState.value.colorRequest || !_uiState.value.showColorPicker)) return
        publish(original.sourceSelection) { it.copy(colorHex = colorHex, appearance = it.appearance?.copy(accentColor = colorHex)) }
    }
    fun updateAppearance(appearance: com.dayforge.domain.model.ObjectAppearance, rendered: EditGoalUiState? = null) {
        val original = frame(rendered) ?: return
        if (databases != null && (original.iconRequest == null || original.iconRequest !== _uiState.value.iconRequest || !_uiState.value.showIconPicker)) return
        publish(original.sourceSelection) { if (it.appearance == null) it else it.copy(appearance = appearance,
            colorHex = appearance.accentColor, showIconPicker = false, iconRequest = null) }
    }
    fun updateTargetCycles(value: Int?, rendered: EditGoalUiState? = null) = edit(rendered) { it.copy(targetCycles = value) }
    fun updateFailMode(mode: FailMode, rendered: EditGoalUiState? = null) = edit(rendered) { it.copy(failMode = mode) }
    fun toggleIconPicker(rendered: EditGoalUiState? = null) {
        val original = frame(rendered) ?: return; val current = _uiState.value
        if (databases != null && (original.showIconPicker != current.showIconPicker ||
                current.showIconPicker && original.iconRequest !== current.iconRequest)) return
        publish(original.sourceSelection) { it.copy(showIconPicker = !it.showIconPicker,
            iconRequest = if (it.showIconPicker) null else Any()) }
    }
    fun toggleColorPicker(rendered: EditGoalUiState? = null) {
        val original = frame(rendered) ?: return; val current = _uiState.value
        if (databases != null && (original.showColorPicker != current.showColorPicker ||
                current.showColorPicker && original.colorRequest !== current.colorRequest)) return
        publish(original.sourceSelection) { it.copy(showColorPicker = !it.showColorPicker,
            colorRequest = if (it.showColorPicker) null else Any()) }
    }
    fun toggleDeleteDialog(rendered: EditGoalUiState? = null) {
        val original = frame(rendered) ?: return; val current = _uiState.value
        if (current.isDeleting || current.isSaving) return
        if (current.showDeleteDialog) {
            if (databases != null && original.deleteRequest !== pendingDelete?.token) return
            publish(original.sourceSelection) { pendingDelete = null; it.copy(showDeleteDialog = false, deleteRequest = null) }
        } else {
            if (databases != null && (original.showDeleteDialog || original.deleteRequest != null)) return
            val request = DeleteRequest(Any(), DeleteStage.INITIAL, original.goalId ?: return,
                original.goalUuid ?: return, original.sourceSelection, original.editAuthority)
            publish(original.sourceSelection) { pendingDelete = request; it.copy(showDeleteDialog = true, deleteRequest = request.token) }
        }
    }

    fun refreshChildren(rendered: EditGoalUiState? = null) {
        val original = frame(rendered) ?: return
        val goalUuid = original.goalUuid ?: return
        childrenJob?.cancel(); val generation = Any().also { childrenGeneration = it }
        childrenJob = viewModelScope.launch {
            try { inSource(original.sourceSelection, observation = true) {
                if (databases != null) requireNotNull(currentGoal(requireNotNull(original.goalId), original.sourceSelection)) { "OBJECT_EDIT_NOT_FOUND" }
                val children = reads.children(goalUuid).first()
                if (childrenGeneration === generation) publish(original.sourceSelection) {
                    it.copy(children = java.util.Collections.unmodifiableList(children.toList()))
                }
            } } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { publish(original.sourceSelection) {
                it.copy(errorMessage = context.getString(R.string.toast_save_failed, error.message), errorRequest = Any())
            } }
        }
    }

    private fun sameSaveInput(expected: EditGoalUiState, current: EditGoalUiState): Boolean =
        expected.sourceSelection === current.sourceSelection && expected.goalId == current.goalId &&
            expected.goalUuid == current.goalUuid && expected.name == current.name &&
            expected.description == current.description && expected.iconResId == current.iconResId &&
            expected.appearance == current.appearance && expected.colorHex == current.colorHex &&
            expected.targetCycles == current.targetCycles && expected.failMode == current.failMode &&
            expected.editAuthority === current.editAuthority

    fun saveGoal(rendered: EditGoalUiState? = null) {
        val original = frame(rendered) ?: return
        val current = _uiState.value
        if (databases != null && !sameSaveInput(original, current)) { inputExpired(); return }
        val captured = original; val goalId = captured.goalId ?: return
        if (current.isSaving || current.isDeleting || current.saved) return
        val terminal = Any()
        if (!publish(original.sourceSelection) { it.copy(isSaving = true) }) return
        viewModelScope.launch {
            try { inSource(original.sourceSelection) {
                val existing = requireNotNull(currentGoal(goalId, original.sourceSelection)) { "OBJECT_EDIT_NOT_FOUND" }
                val updated = existing.copy(name = captured.name, description = captured.description,
                    iconResId = captured.iconResId, appearance = captured.appearance, colorHex = captured.colorHex,
                    targetCycles = captured.targetCycles, failMode = captured.failMode, updatedAt = System.currentTimeMillis())
                habitRepository.updateHabit(updated, context, editAuthority = captured.editAuthority)
                publish(original.sourceSelection) { it.copy(isSaving = false, saved = true,
                    showIconPicker = false, showColorPicker = false, iconRequest = null, colorRequest = null,
                    terminalRequest = terminal) }
            } } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { publish(original.sourceSelection) { it.copy(isSaving = false,
                errorMessage = context.getString(R.string.toast_save_failed, error.message), errorRequest = Any()) } }
            finally { _uiState.update { it.copy(isSaving = false) } }
        }
    }

    private fun deletion(original: EditGoalUiState, stage: DeleteStage): DeleteRequest? {
        if (databases == null) return DeleteRequest(Any(), stage, original.goalId ?: return null,
            original.goalUuid.orEmpty(), null, original.editAuthority)
        return pendingDelete?.takeIf { request -> request.stage == stage && request.source === original.sourceSelection &&
            request.token === original.deleteRequest && _uiState.value.deleteRequest === request.token &&
            (if (stage == DeleteStage.INITIAL) _uiState.value.showDeleteDialog else _uiState.value.showDeleteChildrenDialog) }
    }
    fun deleteGoal(rendered: EditGoalUiState? = null) {
        val original = frame(rendered) ?: return; val current = _uiState.value
        if (current.isDeleting || current.isSaving || current.saved) return
        val request = deletion(original, DeleteStage.INITIAL) ?: return
        if (!publish(original.sourceSelection) { it.copy(isDeleting = true) }) return
        viewModelScope.launch {
            try { inSource(original.sourceSelection) {
                val goal = currentGoal(request.id, original.sourceSelection) ?: return@inSource
                if (databases != null) check(goal.uuid == request.uuid) { "DATABASE_OBJECT_SOURCE_CHANGED" }
                val children = habitRepository.getHabitChildren(goal.uuid)
                if (databases != null && pendingDelete !== request) return@inSource
                if (children.isNotEmpty()) {
                    val next = request.copy(token = Any(), stage = DeleteStage.CHILDREN)
                    publish(original.sourceSelection) { pendingDelete = next; it.copy(isDeleting = false,
                        showDeleteDialog = if (databases == null) it.showDeleteDialog else false,
                        showDeleteChildrenDialog = true, pendingDeleteChildrenCount = children.size, deleteRequest = next.token) }
                } else {
                    habitRepository.deleteHabit(goal, context, authority = request.authority)
                    publish(original.sourceSelection) { pendingDelete = null; it.copy(isDeleting = false,
                        showDeleteDialog = false, deleteRequest = null, saved = true, terminalRequest = request.token) }
                }
            } } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { publish(original.sourceSelection) { it.copy(isDeleting = false,
                errorMessage = context.getString(R.string.goal_delete_failed, error.message), errorRequest = Any()) } }
            finally { _uiState.update { it.copy(isDeleting = false) } }
        }
    }
    private fun deleteChildren(rendered: EditGoalUiState?, keepChildren: Boolean) {
        val original = frame(rendered) ?: return; val current = _uiState.value
        if (current.isDeleting || current.isSaving || current.saved) return
        val request = deletion(original, DeleteStage.CHILDREN) ?: return
        if (!publish(original.sourceSelection) { it.copy(isDeleting = true) }) return
        viewModelScope.launch {
            try { inSource(original.sourceSelection) {
                val goal = currentGoal(request.id, original.sourceSelection) ?: return@inSource
                if (databases != null) check(goal.uuid == request.uuid) { "DATABASE_OBJECT_SOURCE_CHANGED" }
                if (keepChildren) habitRepository.deleteHabitOrphanChildren(goal, context, request.authority)
                else habitRepository.deleteHabitWithChildren(goal, context, request.authority)
                publish(original.sourceSelection) { pendingDelete = null; it.copy(showDeleteChildrenDialog = false,
                    deleteRequest = null, saved = true, terminalRequest = request.token) }
            } } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { publish(original.sourceSelection) { it.copy(showDeleteChildrenDialog = false,
                deleteRequest = null, errorMessage = context.getString(R.string.goal_delete_failed, error.message), errorRequest = Any()) } }
            finally { _uiState.update { it.copy(isDeleting = false) } }
        }
    }
    fun deleteGoalWithChildren(rendered: EditGoalUiState? = null) = deleteChildren(rendered, keepChildren = false)
    fun deleteGoalKeepChildren(rendered: EditGoalUiState? = null) = deleteChildren(rendered, keepChildren = true)
    fun dismissDeleteChildrenDialog(rendered: EditGoalUiState? = null) {
        val original = frame(rendered) ?: return
        if (_uiState.value.isDeleting || databases != null && deletion(original, DeleteStage.CHILDREN) == null) return
        publish(original.sourceSelection) { pendingDelete = null; it.copy(showDeleteChildrenDialog = false, deleteRequest = null) }
    }
    fun clearSaved(rendered: EditGoalUiState? = null) {
        val original = frame(rendered) ?: return
        if (databases != null && (original.terminalRequest == null || original.terminalRequest !== _uiState.value.terminalRequest)) return
        publish(original.sourceSelection) { it.copy(saved = false, terminalRequest = null) }
    }
    fun clearError(rendered: EditGoalUiState? = null) {
        val original = frame(rendered, requireGoal = false) ?: return
        if (databases != null && (original.errorRequest == null || original.errorRequest !== _uiState.value.errorRequest)) return
        publish(original.sourceSelection) { it.copy(errorMessage = null, errorRequest = null) }
    }
}
