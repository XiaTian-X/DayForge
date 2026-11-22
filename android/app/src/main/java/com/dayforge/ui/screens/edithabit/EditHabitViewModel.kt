package com.dayforge.ui.screens.edithabit

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.R
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EditHabitUiState(
    val habitId: Long = 0,
    val name: String = "",
    val description: String = "",
    val habitType: HabitType = HabitType.CHECK_IN,
    val iconResId: Int = 0,
    val appearance: com.dayforge.domain.model.ObjectAppearance? = null,
    val completionPolicy: String? = null,
    val editAuthority: com.dayforge.data.repository.ObjectEditAuthority? = null,
    val colorHex: String = "#2196F3",
    val schedule: HabitSchedule = HabitSchedule.Daily,
    val originalScheduleDays: Int = 1,  // Days count of original schedule, used for edit restriction
    val monthlyInputValue: String = "",  // Display value for Monthly schedule, can be cleared
    val customInputValue: String = "",   // Display value for Custom schedule, can be cleared
    val targetValue: Int? = 1,
    val targetCycles: Int? = null,  // Target completion count
    val failMode: FailMode = FailMode.STRICT,  // Failure mode for target-based habits
    val bestTime: Long? = null,  // Best execution time loaded from habit
    val showTimePicker: Boolean = false,  // Time picker dialog state
    val hasCompletions: Boolean = false,  // Whether habit has any completions (blocks targetCycles edit)
    val isCountdown: Boolean = false,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isDeleting: Boolean = false,
    val hasChanges: Boolean = false,
    val isValid: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val showDeleteChildrenDialog: Boolean = false,
    val pendingDeleteChildrenCount: Int = 0,
    val showIconPicker: Boolean = false,
    val showColorPicker: Boolean = false,
    val habitNotFound: Boolean = false,
    val saved: Boolean = false,
    val errorMessage: String? = null,
    val showDuplicateDialog: Boolean = false,
    val showActiveTimerDialog: Boolean = false,
    val showDefaultTargetDialog: Boolean = false,
    val showDefaultScheduleDialog: Boolean = false,
    // Parent habit fields
    val parentHabitUuid: String? = null,  // Current parent UUID (from loaded habit)
    val parentHabitName: String? = null,  // Display name for current parent
    val topLevelHabits: List<HabitEntity> = emptyList(),  // Available parents (top-level habits)
    val selectedParentUuid: String? = null,  // Pending selection (null = detach)
    val showParentSelector: Boolean = false,
    // Metric linking (optional)
    val availableMetrics: List<MetricEntity> = emptyList(),
    val originalLinkedMetricIds: Set<Long> = emptySet(),
    val selectedMetricIds: Set<Long> = emptySet(),
    val selectedMetricUuids: Map<Long, String> = emptyMap(),
    val sourceSelection: com.dayforge.data.local.DatabaseObjectSelection? = null,
    val sourceExpired: Boolean = false,
    val saveConfirmationToken: Any? = null,
    val deleteConfirmationToken: Any? = null
)

@HiltViewModel
class EditHabitViewModel internal constructor(
    habitRepository: HabitRepository?,
    habitDao: HabitDao?,
    timeLogDao: TimeLogDao?,
    completionDao: CompletionDao?,
    metricDao: MetricDao?,
    habitMetricLinkDao: HabitMetricLinkDao?,
    private val preferencesManager: PreferencesManager,
    @param:ApplicationContext private val context: Context,
    private val databaseHost: com.dayforge.data.local.DatabaseRuntimeHost? = null
) : ViewModel() {
    private val fixedHabitRepository = habitRepository
    private val habitRepository: HabitRepository get() = databaseHost?.boundGraph()?.habitRepository ?: requireNotNull(fixedHabitRepository)

    private val fixedTargetReads = if (databaseHost == null) com.dayforge.data.repository.HabitTargetReadRepository(
        requireNotNull(habitDao), requireNotNull(timeLogDao), requireNotNull(completionDao),
        requireNotNull(metricDao), requireNotNull(habitMetricLinkDao)) else null
    private val targetReads: com.dayforge.data.repository.HabitTargetReadRepository
        get() = databaseHost?.boundGraph()?.habitTargetReadRepository ?: requireNotNull(fixedTargetReads)

    @Inject internal constructor(databaseHost: com.dayforge.data.local.DatabaseRuntimeHost,
        preferencesManager: PreferencesManager, @ApplicationContext context: Context
    ) : this(null, null, null, null, null, null, preferencesManager, context, databaseHost)

    internal val fixedGraphConstruction: Boolean get() = databaseHost == null


    private val _uiState = MutableStateFlow(EditHabitUiState())
    val uiState: StateFlow<EditHabitUiState> = _uiState.asStateFlow()

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
                    _uiState.value = EditHabitUiState(habitId = before.habitId, isLoading = false,
                        sourceExpired = true, errorMessage = expiredSourceMessage)
                }
            }
        }
    }
    private val sourceRegistration = databaseHost?.observeMemory(sourceListener)

    private fun publishState(source: com.dayforge.data.local.DatabaseObjectSelection?,
        next: () -> EditHabitUiState): Boolean = if (databaseHost == null) {
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

    private enum class DefaultKind { TARGET, SCHEDULE }
    private data class SaveConfirmation(val token: Any, val kind: DefaultKind,
        val draft: EditHabitUiState, val original: HabitEntity)
    private enum class DeleteStage { INITIAL, CHILDREN }
    private data class DeleteConfirmation(val token: Any, val stage: DeleteStage,
        val habit: HabitEntity, val authority: com.dayforge.data.repository.ObjectEditAuthority?)
    private var saveConfirmation: SaveConfirmation? = null
    private var deleteConfirmation: DeleteConfirmation? = null

    private fun sameDraft(a: EditHabitUiState, b: EditHabitUiState): Boolean =
        a.sourceSelection === b.sourceSelection && a.habitId == b.habitId &&
        a.name == b.name && a.description == b.description && a.habitType == b.habitType &&
        a.iconResId == b.iconResId && a.appearance == b.appearance && a.colorHex == b.colorHex &&
        a.completionPolicy == b.completionPolicy && a.editAuthority == b.editAuthority &&
        a.schedule == b.schedule && a.monthlyInputValue == b.monthlyInputValue &&
        a.customInputValue == b.customInputValue && a.targetValue == b.targetValue &&
        a.targetCycles == b.targetCycles && a.failMode == b.failMode && a.bestTime == b.bestTime &&
        a.isCountdown == b.isCountdown && a.selectedParentUuid == b.selectedParentUuid &&
        a.selectedMetricIds == b.selectedMetricIds && a.selectedMetricUuids == b.selectedMetricUuids

    private fun showDefaultConfirmation(frame: EditHabitUiState, draft: EditHabitUiState,
        original: HabitEntity, kind: DefaultKind) {
        val request = SaveConfirmation(Any(), kind, draft, original)
        publishState(frame.sourceSelection) {
            saveConfirmation = request
            draft.copy(showDefaultTargetDialog = kind == DefaultKind.TARGET,
                showDefaultScheduleDialog = kind == DefaultKind.SCHEDULE, saveConfirmationToken = request.token)
        }
    }
    private fun validDefaultConfirmation(frame: EditHabitUiState, kind: DefaultKind): SaveConfirmation? {
        val request = saveConfirmation ?: return null
        val current = _uiState.value
        if (request.kind != kind || frame.saveConfirmationToken !== request.token ||
            current.saveConfirmationToken !== request.token ||
            !(if (kind == DefaultKind.TARGET) current.showDefaultTargetDialog else current.showDefaultScheduleDialog) ||
            !sameDraft(request.draft, current) || originalHabit !== request.original) return null
        return request
    }
    private fun validDeleteConfirmation(frame: EditHabitUiState, stage: DeleteStage): DeleteConfirmation? {
        if (databaseHost == null) return deleteConfirmation ?: originalHabit?.let {
            DeleteConfirmation(Any(), stage, it, _uiState.value.editAuthority)
        }
        return deleteConfirmation?.takeIf { request ->
            request.stage == stage && frame.deleteConfirmationToken === request.token &&
                _uiState.value.deleteConfirmationToken === request.token &&
                (if (stage == DeleteStage.INITIAL) _uiState.value.showDeleteDialog else _uiState.value.showDeleteChildrenDialog)
        }
    }

    private var originalHabit: HabitEntity? = null
    private var loadJob: kotlinx.coroutines.Job? = null
    private fun actionFrame(rendered: EditHabitUiState?): EditHabitUiState? {
        val frame = if (databaseHost == null) rendered ?: _uiState.value else rendered
        if (databaseHost != null && (frame?.sourceSelection == null || frame.sourceExpired ||
                frame.sourceSelection !== routeSelection ||
                !frame.sourceSelection.publication.isCurrentInMemory())) {
            android.widget.Toast.makeText(context, R.string.database_view_expired, android.widget.Toast.LENGTH_LONG).show()
            return null
        }
        return frame
    }
    private fun mayDeliver(source: com.dayforge.data.local.DatabaseObjectSelection?): Boolean =
        databaseHost == null || source != null && routeSelection === source && source.publication.isCurrentInMemory()

    private fun preserveEdit() = !_uiState.value.isLoading &&
        (_uiState.value.hasChanges || _uiState.value.isSaving || _uiState.value.saved)

    // Lazy load flags - prevent repeated loading
    @Volatile
    private var hasLoadedTopLevelHabits = false
    @Volatile
    private var hasLoadedMetrics = false

    /**
     * Load top-level habits when parent selector is expanded.
     * Lazy loading to avoid database query on ViewModel init.
     */
    private var parentLoadJob: kotlinx.coroutines.Job? = null
    private var metricLoadJob: kotlinx.coroutines.Job? = null

    fun loadTopLevelHabitsIfNeeded() = loadTopLevelHabitsFrom(null)

    fun loadTopLevelHabitsFrom(rendered: EditHabitUiState?) {
        val frame = actionFrame(rendered) ?: return
        if (hasLoadedTopLevelHabits) return
        hasLoadedTopLevelHabits = true
        parentLoadJob = viewModelScope.launch {
            try { inSource(frame.sourceSelection, com.dayforge.data.local.DatabaseRuntimeHost.Work.OBSERVATION) {
                targetReads.topLevelHabits().collect { habits ->
                    publishState(frame.sourceSelection) {
                        val current = _uiState.value
                        current.copy(topLevelHabits = habits,
                            parentHabitName = habits.find { it.uuid == current.parentHabitUuid }?.name ?: current.parentHabitName)
                    }
                }
            } } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                publishState(frame.sourceSelection) { _uiState.value.copy(errorMessage = error.message) }
            }
        }
    }

    fun loadMetricsIfNeeded() = loadMetricsFrom(null)

    fun loadMetricsFrom(rendered: EditHabitUiState?) {
        val frame = actionFrame(rendered) ?: return
        if (hasLoadedMetrics) return
        hasLoadedMetrics = true
        metricLoadJob = viewModelScope.launch {
            try { inSource(frame.sourceSelection, com.dayforge.data.local.DatabaseRuntimeHost.Work.OBSERVATION) {
                targetReads.activeMetrics().collect { metrics ->
                    publishState(frame.sourceSelection) { _uiState.value.copy(availableMetrics = metrics) }
                }
            } } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                publishState(frame.sourceSelection) { _uiState.value.copy(errorMessage = error.message) }
            }
        }
    }

    fun loadHabit(id: Long, sourceSelection: com.dayforge.data.local.DatabaseObjectSelection? = null) {
        if (databaseHost != null && (sourceSelection == null || sourceSelection.id != id ||
                sourceSelection.kind != com.dayforge.data.local.DatabaseObjectSelection.Kind.HABIT ||
                !sourceSelection.publication.isCurrentInMemory())) {
            loadJob?.cancel()
            _uiState.value = EditHabitUiState(habitId = id, isLoading = false, sourceExpired = true,
                errorMessage = context.getString(R.string.database_view_expired))
            return
        }
        loadJob?.cancel()
        if (routeSelection !== sourceSelection || _uiState.value.habitId != id) {
            parentLoadJob?.cancel(); metricLoadJob?.cancel()
            parentLoadJob = null; metricLoadJob = null
            hasLoadedTopLevelHabits = false; hasLoadedMetrics = false
            originalHabit = null; saveConfirmation = null; deleteConfirmation = null
            _uiState.value = EditHabitUiState(habitId = id)
        }
        routeSelection = sourceSelection
        loadJob = viewModelScope.launch {
            publishState(sourceSelection) { _uiState.value.copy(isLoading = true) }
            try { inSource(sourceSelection, com.dayforge.data.local.DatabaseRuntimeHost.Work.OBSERVATION) {
                val habit = habitRepository.getHabit(id)
                habit.collect {
                    // A background refresh must not silently replace a user's in-progress edit or save result.
                    // Typed saves prove the original structure and ask for reload if it changed remotely.
                    if (preserveEdit()) return@collect
                    val snapshot = habitRepository.getHabitForEditing(id)
                    val entity = snapshot.value
                    if (entity != null) {
                        if (databaseHost != null) requireNotNull(sourceSelection).requireHabit(entity)
                        // Find parent name if parent exists
                        val parentName = if (entity.parentHabitId != null) {
                            targetReads.habitByUuid(entity.parentHabitId)?.name
                        } else null
                        // Initialize input values from loaded habit schedule
                        val monthlyInput = if (entity.schedule is HabitSchedule.Monthly) {
                            (entity.schedule as HabitSchedule.Monthly).dayOfMonth.toString()
                        } else ""
                        val customInput = if (entity.schedule is HabitSchedule.Custom) {
                            (entity.schedule as HabitSchedule.Custom).frequencyDays.toString()
                        } else ""
                        // Check if habit has records (blocks targetCycles editing)
                        // TIMER habits use timelogs table, others use completions table
                        val hasCompletions = if (entity.habitType == HabitType.TIMER) {
                            targetReads.hasTimeHistory(entity.id)
                        } else {
                            targetReads.hasCompletions(entity.id)
                        }
                        val originalScheduleDays = if (entity.completionPolicy == "one_and_done") 0 else getScheduleDays(entity.schedule)

                        // Load existing metric links
                        val existingLinks = targetReads.visibleLinks(entity.id)
                        val linkedMetricIds = existingLinks.map { it.metricId }.toSet()
                        // Room reads above suspend; recheck after them before replacing either draft or ticket.
                        if (preserveEdit()) return@collect
                        publishState(sourceSelection) {
                            originalHabit = entity
                            EditHabitUiState(
                            habitId = entity.id,
                            name = entity.name,
                            description = entity.description,
                            habitType = entity.habitType,
                            iconResId = entity.iconResId,
                            appearance = entity.appearance,
                            completionPolicy = entity.completionPolicy,
                            editAuthority = snapshot.authority,
                            colorHex = entity.appearance?.accentColor ?: entity.colorHex,
                            schedule = entity.schedule,
                            originalScheduleDays = originalScheduleDays,
                            monthlyInputValue = monthlyInput,
                            customInputValue = customInput,
                            targetValue = entity.targetValue,
                            targetCycles = entity.targetCycles,
                            failMode = entity.failMode,
                            bestTime = entity.bestTime,
                            hasCompletions = hasCompletions,
                            isCountdown = entity.isCountdown,
                            isLoading = false,
                            isValid = true,
                            parentHabitUuid = entity.parentHabitId,
                            parentHabitName = parentName,
                            selectedParentUuid = entity.parentHabitId,
                            topLevelHabits = _uiState.value.topLevelHabits,
                            availableMetrics = _uiState.value.availableMetrics,
                            originalLinkedMetricIds = linkedMetricIds,
                            selectedMetricIds = linkedMetricIds,
                            selectedMetricUuids = existingLinks.associate { it.metricId to it.metricUuid }
                        ) }
                    } else {
                        publishState(sourceSelection) { _uiState.value.copy(
                            isLoading = false,
                            habitNotFound = true
                        ) }
                    }
                }
            } } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (e: Exception) {
                publishState(sourceSelection) { _uiState.value.copy(
                    isLoading = false,
                    habitNotFound = true
                ) }
            }
        }
    }

    fun updateName(name: String) = updateNameFrom(null, name)

    fun updateNameFrom(rendered: EditHabitUiState?, name: String) {
        val frame = actionFrame(rendered) ?: return
        publishState(frame.sourceSelection) { _uiState.value.copy(
            name = name,
            hasChanges = true,
            isValid = validateForm(name),
            errorMessage = null,
            showDuplicateDialog = false
        ) }
    }

    fun updateDescription(description: String) = updateDescriptionFrom(null, description)

    fun updateDescriptionFrom(rendered: EditHabitUiState?, description: String) {
        val frame = actionFrame(rendered) ?: return
        publishState(frame.sourceSelection) { _uiState.value.copy(
            description = description,
            hasChanges = true
        ) }
    }

    fun updateIcon(iconResId: Int) = updateIconFrom(null, iconResId)

    fun updateIconFrom(rendered: EditHabitUiState?, iconResId: Int) {
        val frame = actionFrame(rendered) ?: return
        if (_uiState.value.appearance != null) return
        publishState(frame.sourceSelection) { _uiState.value.copy(
            iconResId = iconResId,
            hasChanges = true
        ) }
    }

    fun updateColor(colorHex: String) = updateColorFrom(null, colorHex)

    fun updateColorFrom(rendered: EditHabitUiState?, colorHex: String) {
        val frame = actionFrame(rendered) ?: return
        publishState(frame.sourceSelection) { _uiState.value.copy(
            colorHex = colorHex,
            appearance = _uiState.value.appearance?.copy(accentColor = colorHex),
            hasChanges = true
        ) }
    }

    fun updateAppearance(appearance: com.dayforge.domain.model.ObjectAppearance) = updateAppearanceFrom(null, appearance)

    fun updateAppearanceFrom(rendered: EditHabitUiState?, appearance: com.dayforge.domain.model.ObjectAppearance) {
        val frame = actionFrame(rendered) ?: return
        if (_uiState.value.appearance == null) return
        publishState(frame.sourceSelection) { _uiState.value.copy(appearance = appearance, colorHex = appearance.accentColor,
            hasChanges = true, showIconPicker = false) }
    }

    fun updateSchedule(schedule: HabitSchedule) = updateScheduleFrom(null, schedule)

    fun updateScheduleFrom(rendered: EditHabitUiState?, schedule: HabitSchedule) {
        val frame = actionFrame(rendered) ?: return
        if (_uiState.value.completionPolicy == "one_and_done") return
        // Initialize input values when switching schedule type
        val monthlyInput = if (schedule is HabitSchedule.Monthly) "1" else _uiState.value.monthlyInputValue
        val customInput = if (schedule is HabitSchedule.Custom) "1" else _uiState.value.customInputValue
        publishState(frame.sourceSelection) { _uiState.value.copy(
            schedule = schedule,
            monthlyInputValue = monthlyInput,
            customInputValue = customInput,
            hasChanges = true
        ) }
    }

    fun updateMonthlyInput(value: String) = updateMonthlyInputFrom(null, value)

    fun updateMonthlyInputFrom(rendered: EditHabitUiState?, value: String) {
        val frame = actionFrame(rendered) ?: return
        // Filter to only allow digits
        val filtered = value.filter { it.isDigit() }
        // Clamp input value to valid dayOfMonth range (1-31)
        val clamped = filtered.toIntOrNull()?.coerceIn(1, 31)?.toString() ?: filtered
        publishState(frame.sourceSelection) { _uiState.value.copy(
            monthlyInputValue = clamped,
            hasChanges = true
        ) }
    }

    fun updateCustomInput(value: String) = updateCustomInputFrom(null, value)

    fun updateCustomInputFrom(rendered: EditHabitUiState?, value: String) {
        val frame = actionFrame(rendered) ?: return
        // Filter to only allow digits
        val filtered = value.filter { it.isDigit() }
        // Clamp input value to max of originalScheduleDays (edit restriction)
        val maxDays = _uiState.value.originalScheduleDays
        val clamped = filtered.toIntOrNull()?.coerceAtMost(maxDays)?.toString() ?: filtered
        publishState(frame.sourceSelection) { _uiState.value.copy(
            customInputValue = clamped,
            hasChanges = true
        ) }
    }

    fun updateTargetValue(value: Int?) = updateTargetValueFrom(null, value)

    fun updateTargetValueFrom(rendered: EditHabitUiState?, value: Int?) {
        val frame = actionFrame(rendered) ?: return
        publishState(frame.sourceSelection) { _uiState.value.copy(
            targetValue = value,
            hasChanges = true
        ) }
    }

    fun updateTargetCycles(value: Int?) = updateTargetCyclesFrom(null, value)

    fun updateTargetCyclesFrom(rendered: EditHabitUiState?, value: Int?) {
        val frame = actionFrame(rendered) ?: return
        if (_uiState.value.completionPolicy == "one_and_done") return
        publishState(frame.sourceSelection) { _uiState.value.copy(
            targetCycles = value,
            hasChanges = true
        ) }
    }

    fun updateFailMode(mode: FailMode) = updateFailModeFrom(null, mode)

    fun updateFailModeFrom(rendered: EditHabitUiState?, mode: FailMode) {
        val frame = actionFrame(rendered) ?: return
        if (_uiState.value.completionPolicy == "one_and_done") return
        publishState(frame.sourceSelection) { _uiState.value.copy(
            failMode = mode,
            hasChanges = true
        ) }
    }

    fun updateBestTime(time: Long?) = updateBestTimeFrom(null, time)

    fun updateBestTimeFrom(rendered: EditHabitUiState?, time: Long?) {
        val frame = actionFrame(rendered) ?: return
        if (_uiState.value.completionPolicy == "one_and_done") return
        publishState(frame.sourceSelection) { _uiState.value.copy(bestTime = time, hasChanges = true) }
    }

    fun toggleTimePicker() = toggleTimePickerFrom(null)

    fun toggleTimePickerFrom(rendered: EditHabitUiState?) {
        val frame = actionFrame(rendered) ?: return
        if (_uiState.value.completionPolicy == "one_and_done") return
        publishState(frame.sourceSelection) { _uiState.value.copy(showTimePicker = !_uiState.value.showTimePicker) }
    }

    fun clearBestTime() = clearBestTimeFrom(null)

    fun clearBestTimeFrom(rendered: EditHabitUiState?) {
        val frame = actionFrame(rendered) ?: return
        publishState(frame.sourceSelection) { _uiState.value.copy(bestTime = null, hasChanges = true) }
    }

    fun toggleMetricSelection(metricId: Long) = toggleMetricSelectionFrom(null, metricId)

    fun toggleMetricSelectionFrom(rendered: EditHabitUiState?, metricId: Long) {
        val frame = actionFrame(rendered) ?: return
        val currentSelection = _uiState.value.selectedMetricIds
        val adding = metricId !in currentSelection
        val originalMetric = frame.availableMetrics.singleOrNull { it.id == metricId }
        if (databaseHost != null && adding && originalMetric == null) return
        val newSelection = if (adding) currentSelection + metricId else currentSelection - metricId
        val uuids = if (adding && originalMetric != null)
            _uiState.value.selectedMetricUuids + (metricId to originalMetric.uuid)
        else _uiState.value.selectedMetricUuids - metricId
        publishState(frame.sourceSelection) { _uiState.value.copy(
            selectedMetricIds = newSelection, selectedMetricUuids = uuids, hasChanges = true
        ) }
    }

    fun updateIsCountdown(value: Boolean) = updateIsCountdownFrom(null, value)

    fun updateIsCountdownFrom(rendered: EditHabitUiState?, value: Boolean) {
        val frame = actionFrame(rendered) ?: return
        viewModelScope.launch {
            try { inSource(frame.sourceSelection) {
            // Check if timer is active for this habit
            val activeTimer = targetReads.activeTimer(frame.habitId)
            if (activeTimer != null) {
                // Timer is active, show dialog instead of updating
                publishState(frame.sourceSelection) { _uiState.value.copy(showActiveTimerDialog = true) }
            } else {
                publishState(frame.sourceSelection) { _uiState.value.copy(
                    isCountdown = value,
                    hasChanges = true
                ) }
            }
            } } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                publishState(frame.sourceSelection) { _uiState.value.copy(errorMessage = error.message) }
            }
        }
    }

    fun dismissActiveTimerDialog() = dismissActiveTimerDialogFrom(null)

    fun dismissActiveTimerDialogFrom(rendered: EditHabitUiState?) {
        val frame = actionFrame(rendered) ?: return
        publishState(frame.sourceSelection) { _uiState.value.copy(showActiveTimerDialog = false) }
    }

    fun toggleDeleteDialog() = toggleDeleteDialogFrom(null)

    fun toggleDeleteDialogFrom(rendered: EditHabitUiState?) {
        val frame = actionFrame(rendered) ?: return
        if (_uiState.value.isSaving || _uiState.value.isDeleting) return
        if (_uiState.value.showDeleteDialog) {
            if (databaseHost != null && frame.deleteConfirmationToken !== deleteConfirmation?.token) return
            publishState(frame.sourceSelection) {
                deleteConfirmation = null
                _uiState.value.copy(showDeleteDialog = false, deleteConfirmationToken = null)
            }
        } else {
            if (databaseHost != null && (frame.showDeleteDialog || frame.deleteConfirmationToken != null)) return
            val request = DeleteConfirmation(Any(), DeleteStage.INITIAL, originalHabit ?: return, _uiState.value.editAuthority)
            publishState(frame.sourceSelection) {
                deleteConfirmation = request
                _uiState.value.copy(showDeleteDialog = true, deleteConfirmationToken = request.token)
            }
        }
    }

    fun deleteHabit(onDeleted: () -> Unit) = deleteHabitFrom(null, onDeleted)

    fun deleteHabitFrom(rendered: EditHabitUiState?, onDeleted: () -> Unit) {
        val frame = actionFrame(rendered) ?: return
        val captured = _uiState.value
        if (captured.isSaving || captured.isDeleting || captured.habitId == 0L) return
        val request = validDeleteConfirmation(frame, DeleteStage.INITIAL) ?: return
        val habit = request.habit
        publishState(frame.sourceSelection) { captured.copy(isDeleting = true) }
        viewModelScope.launch {
            try { inSource(frame.sourceSelection) {
                val children = habitRepository.getHabitChildren(habit.uuid)
                if (databaseHost != null && deleteConfirmation !== request) return@inSource
                if (children.isNotEmpty()) {
                    val nextRequest = request.copy(token = Any(), stage = DeleteStage.CHILDREN)
                    publishState(frame.sourceSelection) {
                        deleteConfirmation = nextRequest
                        _uiState.value.copy(
                        showDeleteDialog = false,
                        showDeleteChildrenDialog = true,
                        pendingDeleteChildrenCount = children.size, deleteConfirmationToken = nextRequest.token
                    ) }
                } else {
                    habitRepository.deleteHabit(habit, context, authority = request.authority)
                    publishState(frame.sourceSelection) {
                        deleteConfirmation = null
                        _uiState.value.copy(showDeleteDialog = false, habitId = 0L, deleteConfirmationToken = null)
                    }
                    if (mayDeliver(frame.sourceSelection)) onDeleted()
                }
            } } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                publishState(frame.sourceSelection) { _uiState.value.copy(errorMessage = context.getString(R.string.goal_delete_failed, e.message)) }
            } finally { publishState(frame.sourceSelection) { _uiState.value.copy(isDeleting = false) } }
        }
    }

    fun deleteHabitWithChildren(onDeleted: () -> Unit) = deleteHabitWithChildrenFrom(null, onDeleted)

    fun deleteHabitWithChildrenFrom(rendered: EditHabitUiState?, onDeleted: () -> Unit) {
        val frame = actionFrame(rendered) ?: return
        val captured = _uiState.value
        if (captured.isSaving || captured.isDeleting || captured.habitId == 0L) return
        val request = validDeleteConfirmation(frame, DeleteStage.CHILDREN) ?: return
        val habit = request.habit
        publishState(frame.sourceSelection) { captured.copy(isDeleting = true) }
        viewModelScope.launch {
            try { inSource(frame.sourceSelection) {
                habitRepository.deleteHabitWithChildren(habit, context, request.authority)
                publishState(frame.sourceSelection) {
                    deleteConfirmation = null
                    _uiState.value.copy(showDeleteChildrenDialog = false, habitId = 0L, deleteConfirmationToken = null)
                }
                if (mayDeliver(frame.sourceSelection)) onDeleted()
            } } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                publishState(frame.sourceSelection) { _uiState.value.copy(errorMessage = context.getString(R.string.goal_delete_failed, e.message)) }
            } finally { publishState(frame.sourceSelection) { _uiState.value.copy(isDeleting = false) } }
        }
    }

    fun deleteHabitKeepChildren(onDeleted: () -> Unit) = deleteHabitKeepChildrenFrom(null, onDeleted)

    fun deleteHabitKeepChildrenFrom(rendered: EditHabitUiState?, onDeleted: () -> Unit) {
        val frame = actionFrame(rendered) ?: return
        val captured = _uiState.value
        if (captured.isSaving || captured.isDeleting || captured.habitId == 0L) return
        val request = validDeleteConfirmation(frame, DeleteStage.CHILDREN) ?: return
        val habit = request.habit
        publishState(frame.sourceSelection) { captured.copy(isDeleting = true) }
        viewModelScope.launch {
            try { inSource(frame.sourceSelection) {
                habitRepository.deleteHabitOrphanChildren(habit, context, request.authority)
                publishState(frame.sourceSelection) {
                    deleteConfirmation = null
                    _uiState.value.copy(showDeleteChildrenDialog = false, habitId = 0L, deleteConfirmationToken = null)
                }
                if (mayDeliver(frame.sourceSelection)) onDeleted()
            } } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                publishState(frame.sourceSelection) { _uiState.value.copy(errorMessage = context.getString(R.string.goal_delete_failed, e.message)) }
            } finally { publishState(frame.sourceSelection) { _uiState.value.copy(isDeleting = false) } }
        }
    }

    fun dismissDeleteChildrenDialog() = dismissDeleteChildrenDialogFrom(null)

    fun dismissDeleteChildrenDialogFrom(rendered: EditHabitUiState?) {
        val frame = actionFrame(rendered) ?: return
        if (_uiState.value.isDeleting) return
        if (databaseHost != null && validDeleteConfirmation(frame, DeleteStage.CHILDREN) == null) return
        publishState(frame.sourceSelection) {
            deleteConfirmation = null
            _uiState.value.copy(showDeleteChildrenDialog = false, deleteConfirmationToken = null)
        }
    }

    fun toggleIconPicker() = toggleIconPickerFrom(null)

    fun toggleIconPickerFrom(rendered: EditHabitUiState?) {
        val frame = actionFrame(rendered) ?: return
        publishState(frame.sourceSelection) { _uiState.value.copy(
            showIconPicker = !_uiState.value.showIconPicker
        ) }
    }

    fun toggleColorPicker() = toggleColorPickerFrom(null)

    fun toggleColorPickerFrom(rendered: EditHabitUiState?) {
        val frame = actionFrame(rendered) ?: return
        publishState(frame.sourceSelection) { _uiState.value.copy(
            showColorPicker = !_uiState.value.showColorPicker
        ) }
    }

    private fun validateForm(name: String): Boolean {
        return name.isNotBlank() && name.length <= 50
    }

    fun saveChanges(onSaved: () -> Unit = {}) = saveChangesFrom(null, onSaved)

    fun saveChangesFrom(rendered: EditHabitUiState?, onSaved: () -> Unit = {}) {
        val frame = actionFrame(rendered) ?: return
        if (_uiState.value.isDeleting || _uiState.value.saved || _uiState.value.habitId == 0L) return
        if (!_uiState.value.hasChanges || !_uiState.value.isValid || _uiState.value.isSaving) return

        val currentState = _uiState.value
        val original = originalHabit ?: return

        // Check if Monthly/Custom schedule values are empty
        when (currentState.schedule) {
            is HabitSchedule.Monthly -> {
                if (currentState.monthlyInputValue.isEmpty()) {
                    showDefaultConfirmation(frame, currentState, original, DefaultKind.SCHEDULE)
                    return
                }
            }
            is HabitSchedule.Custom -> {
                if (currentState.customInputValue.isEmpty()) {
                    showDefaultConfirmation(frame, currentState, original, DefaultKind.SCHEDULE)
                    return
                }
            }
            else -> {} // Daily, Weekly don't need input validation
        }

        // Check if targetValue is empty for counting/timer habits
        if ((currentState.habitType == HabitType.COUNTING || currentState.habitType == HabitType.TIMER)
            && currentState.targetValue == null) {
            showDefaultConfirmation(frame, currentState, original, DefaultKind.TARGET)
            return
        }

        // Derive schedule from input values
        val finalSchedule = when (currentState.schedule) {
            is HabitSchedule.Monthly -> HabitSchedule.Monthly(
                currentState.monthlyInputValue.toIntOrNull()?.coerceIn(1, 31) ?: 1
            )
            is HabitSchedule.Custom -> HabitSchedule.Custom(
                currentState.customInputValue.toIntOrNull()?.coerceIn(1, 365) ?: 1
            )
            else -> currentState.schedule
        }

        publishState(frame.sourceSelection) { _uiState.value.copy(isSaving = true, saved = false) }
        viewModelScope.launch {
            try { inSource(frame.sourceSelection) {
                val updatedHabit = original.copy(
                    name = currentState.name,
                    description = currentState.description,
                    habitType = currentState.habitType,
                    iconResId = currentState.iconResId,
                    appearance = currentState.appearance,
                    colorHex = currentState.colorHex,
                    schedule = finalSchedule,
                    targetValue = currentState.targetValue ?: 1,
                    isCountdown = currentState.isCountdown,
                    parentHabitId = currentState.selectedParentUuid,
                    targetCycles = currentState.targetCycles,
                    failMode = currentState.failMode,
                    bestTime = currentState.bestTime
                )
                run {
                    habitRepository.updateHabit(
                        updatedHabit,
                        context,
                        selectedMetricIds = currentState.selectedMetricIds,
                        editAuthority = currentState.editAuthority,
                        expectedMetricUuids = if (databaseHost == null) null else currentState.selectedMetricUuids
                    )
                    publishState(frame.sourceSelection) {
                        originalHabit = updatedHabit.copy(parentHabitId = currentState.selectedParentUuid)
                        _uiState.value.copy(
                        isSaving = false,
                        hasChanges = false,
                        saved = true,
                        parentHabitUuid = currentState.selectedParentUuid,
                        originalLinkedMetricIds = currentState.selectedMetricIds
                    ) }
                    if (mayDeliver(frame.sourceSelection)) onSaved()
                }
            } } catch (e: SQLiteConstraintException) {
                    publishState(frame.sourceSelection) { _uiState.value.copy(
                        isSaving = false,
                        showDuplicateDialog = true,
                        errorMessage = context.getString(R.string.dialog_duplicate_habit_message)
                    ) }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    publishState(frame.sourceSelection) { _uiState.value.copy(isSaving = false, saved = false) }
                    throw error
                } catch (e: Exception) {
                    publishState(frame.sourceSelection) { _uiState.value.copy(isSaving = false, saved = false,
                        errorMessage = context.getString(R.string.toast_save_failed, e.message)) }
                }
        }
    }

    fun dismissDefaultTargetDialog() = dismissDefaultTargetDialogFrom(null)

    fun dismissDefaultTargetDialogFrom(rendered: EditHabitUiState?) {
        val frame = actionFrame(rendered) ?: return
        if (databaseHost != null && (frame.saveConfirmationToken !== saveConfirmation?.token ||
                _uiState.value.saveConfirmationToken !== frame.saveConfirmationToken)) return
        publishState(frame.sourceSelection) {
            saveConfirmation = null
            _uiState.value.copy(showDefaultTargetDialog = false, saveConfirmationToken = null)
        }
    }

    fun confirmDefaultTarget(onSaved: () -> Unit = {}) = confirmDefaultTargetFrom(null, onSaved)

    fun confirmDefaultTargetFrom(rendered: EditHabitUiState?, onSaved: () -> Unit = {}) {
        val frame = actionFrame(rendered) ?: return
        if (databaseHost != null && validDefaultConfirmation(frame, DefaultKind.TARGET) == null) return
        saveConfirmation = null
        publishState(frame.sourceSelection) { _uiState.value.copy(
            targetValue = 1,
            showDefaultTargetDialog = false,
            hasChanges = true, saveConfirmationToken = null
        ) }
        // Continue saving
        saveChangesFrom(frame, onSaved)
    }

    fun dismissDefaultScheduleDialog() = dismissDefaultScheduleDialogFrom(null)

    fun dismissDefaultScheduleDialogFrom(rendered: EditHabitUiState?) {
        val frame = actionFrame(rendered) ?: return
        if (databaseHost != null && (frame.saveConfirmationToken !== saveConfirmation?.token ||
                _uiState.value.saveConfirmationToken !== frame.saveConfirmationToken)) return
        publishState(frame.sourceSelection) {
            saveConfirmation = null
            _uiState.value.copy(showDefaultScheduleDialog = false, saveConfirmationToken = null)
        }
    }

    fun confirmDefaultSchedule(onSaved: () -> Unit = {}) = confirmDefaultScheduleFrom(null, onSaved)

    fun confirmDefaultScheduleFrom(rendered: EditHabitUiState?, onSaved: () -> Unit = {}) {
        val frame = actionFrame(rendered) ?: return
        if (databaseHost != null && validDefaultConfirmation(frame, DefaultKind.SCHEDULE) == null) return
        saveConfirmation = null
        val currentState = _uiState.value
        // Set default values (Monthly=1, Custom=1) and continue saving
        val updatedState = when (currentState.schedule) {
            is HabitSchedule.Monthly -> currentState.copy(
                monthlyInputValue = "1",
                showDefaultScheduleDialog = false,
                hasChanges = true
            )
            is HabitSchedule.Custom -> currentState.copy(
                customInputValue = "1",
                showDefaultScheduleDialog = false,
                hasChanges = true
            )
            else -> currentState.copy(showDefaultScheduleDialog = false)
        }
        publishState(frame.sourceSelection) { updatedState.copy(saveConfirmationToken = null) }
        // Continue saving
        saveChangesFrom(frame, onSaved)
    }

    fun dismissDuplicateDialog() = dismissDuplicateDialogFrom(null)

    fun dismissDuplicateDialogFrom(rendered: EditHabitUiState?) {
        val frame = actionFrame(rendered) ?: return
        publishState(frame.sourceSelection) { _uiState.value.copy(
            showDuplicateDialog = false,
            errorMessage = null
        ) }
    }

    // Parent habit management
    fun updateParentHabit(habit: HabitEntity?) = updateParentHabitFrom(null, habit)

    fun updateParentHabitFrom(rendered: EditHabitUiState?, habit: HabitEntity?) {
        val frame = actionFrame(rendered) ?: return
        if (databaseHost != null && habit != null && frame.topLevelHabits.none { it.id == habit.id && it.uuid == habit.uuid }) return
        val newParentUuid = habit?.uuid
        val newParentName = habit?.name
        val hasParentChange = newParentUuid != originalHabit?.parentHabitId
        publishState(frame.sourceSelection) { _uiState.value.copy(
            selectedParentUuid = newParentUuid,
            parentHabitName = newParentName,
            hasChanges = hasParentChange || hasOtherChanges(),
            showParentSelector = false
        ) }
    }

    fun clearParentHabit() = clearParentHabitFrom(null)

    fun clearParentHabitFrom(rendered: EditHabitUiState?) {
        val frame = actionFrame(rendered) ?: return
        val hasParentChange = null != originalHabit?.parentHabitId
        publishState(frame.sourceSelection) { _uiState.value.copy(
            selectedParentUuid = null,
            parentHabitName = null,
            hasChanges = hasParentChange || hasOtherChanges(),
            showParentSelector = false
        ) }
    }

    fun toggleParentSelector() = toggleParentSelectorFrom(null)

    fun toggleParentSelectorFrom(rendered: EditHabitUiState?) {
        val frame = actionFrame(rendered) ?: return
        publishState(frame.sourceSelection) { _uiState.value.copy(
            showParentSelector = !_uiState.value.showParentSelector
        ) }
    }

    private fun hasOtherChanges(): Boolean {
        val current = _uiState.value
        val original = originalHabit ?: return false
        return current.name != original.name ||
                current.description != original.description ||
                current.iconResId != original.iconResId ||
                current.appearance != original.appearance ||
                current.colorHex != original.colorHex ||
                current.schedule != original.schedule ||
                current.targetValue != original.targetValue ||
                current.targetCycles != original.targetCycles ||
                current.isCountdown != original.isCountdown ||
                current.failMode != original.failMode ||
                current.bestTime != original.bestTime
    }

    /**
     * Calculate the number of days represented by a schedule.
     * Used to determine which schedule options should be disabled in edit mode.
     * - Daily = 1 day
     * - Weekly = 7 days
     * - Monthly = 30 days
     * - Custom(N) = N days
     */
    fun getScheduleDays(schedule: HabitSchedule): Int {
        return when (schedule) {
            is HabitSchedule.Once -> error("Once plans have no recurring cycle length")
            is HabitSchedule.Daily -> 1
            is HabitSchedule.Weekly -> 7
            is HabitSchedule.Monthly -> 30
            is HabitSchedule.Custom -> schedule.frequencyDays
        }
    }
}
