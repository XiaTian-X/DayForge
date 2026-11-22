package com.dayforge.ui.screens.createtemptask

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.R
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CreateTempTaskViewModel private constructor(
    private val fixedHabitRepository: HabitRepository?,
    private val fixedReads: com.dayforge.data.repository.CreationReadRepository?,
    @param:ApplicationContext private val context: Context,
    private val databases: com.dayforge.data.local.DatabaseRuntimeHost?
) : ViewModel() {
    constructor(habitRepository: HabitRepository, habitDao: HabitDao, metricDao: MetricDao, context: Context) :
        this(habitRepository, com.dayforge.data.repository.CreationReadRepository(habitDao, metricDao), context, null)
    @Inject internal constructor(databases: com.dayforge.data.local.DatabaseRuntimeHost,
        @ApplicationContext context: Context) : this(null, null, context, databases)
    private val habitRepository get() = databases?.boundGraph()?.habitRepository ?: requireNotNull(fixedHabitRepository)
    private val reads get() = databases?.boundGraph()?.creationReadRepository ?: requireNotNull(fixedReads)

    private val expiredMessage = context.getString(R.string.database_view_expired)
    private val _uiState = com.dayforge.ui.CreationState(databases, CreateTempTaskUiState(),
        { CreateTempTaskUiState(errorMessage = expiredMessage) })
    val uiState: StateFlow<CreateTempTaskUiState> = _uiState.asStateFlow()

    internal fun beginSource(input: com.dayforge.data.local.DatabaseCreationInput) {
        _uiState.bind(input)
        input.authority?.let(::beginCreation)
    }

    fun beginCreation(authority: com.dayforge.data.repository.ObjectCreationAuthority) {
        val current = _uiState.value
        if (current.creationAuthority === authority) return
        check(current.creationAuthority == null && !current.isSaving && current.savedHabitId == null)
        _uiState.value = current.copy(creationAuthority = authority,
            appearance = com.dayforge.domain.model.ObjectAppearance(com.dayforge.domain.model.IconReference.Role("task.default"),
                "#2196F3", "theme"))
    }

    fun updateAppearance(value: com.dayforge.domain.model.ObjectAppearance) {
        if (_uiState.value.creationAuthority == null) return
        _uiState.value = _uiState.value.copy(appearance = value, showIconPicker = false)
    }

    fun toggleIconPicker() { _uiState.value = _uiState.value.copy(showIconPicker = !_uiState.value.showIconPicker) }

    @Volatile
    private var isSavingInProgress = false

    // Lazy load flags - prevent repeated loading
    @Volatile
    private var hasLoadedTopLevelHabits = false
    @Volatile
    private var hasLoadedMetrics = false

    /**
     * Load top-level habits when parent selector is expanded.
     * Lazy loading to avoid database query on ViewModel init.
     */
    fun loadTopLevelHabitsIfNeeded() {
        if (!_uiState.isCurrent() || hasLoadedTopLevelHabits) return
        hasLoadedTopLevelHabits = true

        viewModelScope.launch {
            _uiState.withGraph(com.dayforge.data.local.DatabaseRuntimeHost.Work.OBSERVATION) {
                reads.topLevelHabits().collect { habits ->
                    _uiState.value = _uiState.value.copy(topLevelHabits = habits)
                }
            }
        }
    }

    /**
     * Load available metrics when metric linking section becomes visible.
     * Lazy loading to avoid database query on ViewModel init.
     */
    fun loadMetricsIfNeeded() {
        if (!_uiState.isCurrent() || hasLoadedMetrics) return
        hasLoadedMetrics = true

        viewModelScope.launch {
            _uiState.withGraph(com.dayforge.data.local.DatabaseRuntimeHost.Work.OBSERVATION) {
                reads.activeMetrics().collect { metrics ->
                    _uiState.value = _uiState.value.copy(availableMetrics = metrics)
                }
            }
        }
    }

    fun updateName(name: String) {
        _uiState.value = _uiState.value.copy(
            name = name,
            isValid = validateForm(name),
            showDuplicateDialog = false
        )
    }

    fun updateParentHabit(habit: com.dayforge.data.local.entity.HabitEntity?) {
        _uiState.value = _uiState.value.copy(parentHabitUuid = habit?.uuid)
    }

    /**
     * Set parent habit UUID directly (used when navigating with parentUuid parameter).
     */
    fun setParentUuid(uuid: String?) {
        _uiState.value = _uiState.value.copy(parentHabitUuid = uuid)
    }

    fun toggleMetricSelection(metricId: Long, expectedUuid: String? = null) {
        if (!_uiState.isCurrent()) return
        if (databases != null) require(expectedUuid != null) { "DATABASE_EPOCH_INPUT_REQUIRED" }
        val currentSelection = _uiState.value.selectedMetricIds
        val newSelection = if (metricId in currentSelection) {
            currentSelection - metricId
        } else {
            currentSelection + metricId
        }
        val identities = if (metricId in newSelection && expectedUuid != null)
            _uiState.value.selectedMetricUuids + (metricId to expectedUuid)
            else _uiState.value.selectedMetricUuids - metricId
        _uiState.value = _uiState.value.copy(selectedMetricIds = newSelection, selectedMetricUuids = identities)
    }

    private fun validateForm(name: String): Boolean {
        return name.isNotBlank() && name.length <= 50
    }

    /**
     * Save temporary task with auto-configured values per D-09 to D-14.
     *
     * Auto-configured (hardcoded):
     * - habitType = CHECK_IN (D-09)
     * - targetCycles = 1 (D-10)
     * - failMode = LENIENT (D-11)
     * - iconResId = 53 (task icon, D-12)
     * - schedule = Daily (D-13)
     * - colorHex = "#2196F3" (D-14)
     * - description = "" (D-08)
     * - bestTime = null
     */
    fun saveTempTask() {
        if (!_uiState.isCurrent()) return
        if (isSavingInProgress || _uiState.value.savedHabitId != null) return

        val currentState = _uiState.value
        if (!currentState.isValid) return

        isSavingInProgress = true
        _uiState.value = currentState.copy(isSaving = true)

        viewModelScope.launch {
            try {
                _uiState.withGraph {
                // Create habit with auto-configured values for temporary tasks
                val habitId = habitRepository.createHabit(
                    name = currentState.name,
                    description = "", // D-08: no description for temp tasks
                    habitType = HabitType.CHECK_IN, // D-09: hardcoded
                    iconResId = if (currentState.creationAuthority != null) 0 else 53,
                    colorHex = currentState.appearance?.accentColor ?: "#2196F3",
                    schedule = if (currentState.creationAuthority != null) HabitSchedule.Once(null) else HabitSchedule.Daily,
                    targetValue = 1,
                    isCountdown = false,
                    parentHabitId = currentState.parentHabitUuid,
                    targetCycles = if (currentState.creationAuthority != null) null else 1,
                    failMode = FailMode.LOOSE, // D-11: hardcoded (loose mode for temp tasks)
                    bestTime = null,
                    context = context,
                    selectedMetricIds = currentState.selectedMetricIds,
                    appearance = currentState.appearance,
                    completionPolicy = if (currentState.creationAuthority != null) "one_and_done" else null,
                    creationAuthority = currentState.creationAuthority,
                    expectedMetricUuids = currentState.selectedMetricUuids.takeIf { databases != null }
                )

                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    savedHabitId = habitId
                )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _uiState.value = _uiState.value.copy(isSaving = false)
                throw e
            } catch (e: SQLiteConstraintException) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    showDuplicateDialog = true
                )
            } catch (e: Exception) {
                Log.e("CreateTempTaskViewModel", "Failed to save temp task", e)
                _uiState.value = _uiState.value.copy(isSaving = false,
                    errorMessage = context.getString(R.string.toast_save_failed, e.message))
            } finally {
                isSavingInProgress = false
            }
        }
    }

    fun dismissDuplicateDialog() {
        _uiState.value = _uiState.value.copy(
            showDuplicateDialog = false
        )
    }

    fun resetState() {
        isSavingInProgress = false
        _uiState.value = CreateTempTaskUiState()
    }

    fun clearSavedHabit() {
        _uiState.value = _uiState.value.copy(savedHabitId = null)
    }
}
