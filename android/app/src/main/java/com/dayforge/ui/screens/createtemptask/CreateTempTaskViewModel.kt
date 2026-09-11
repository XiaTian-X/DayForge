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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CreateTempTaskViewModel @Inject constructor(
    private val habitRepository: HabitRepository,
    private val habitDao: HabitDao,
    private val metricDao: MetricDao,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateTempTaskUiState())
    val uiState: StateFlow<CreateTempTaskUiState> = _uiState.asStateFlow()

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
        if (hasLoadedTopLevelHabits) return
        hasLoadedTopLevelHabits = true

        habitDao.getTopLevelHabits()
            .onEach { habits ->
                _uiState.value = _uiState.value.copy(topLevelHabits = habits)
            }
            .launchIn(viewModelScope)
    }

    /**
     * Load available metrics when metric linking section becomes visible.
     * Lazy loading to avoid database query on ViewModel init.
     */
    fun loadMetricsIfNeeded() {
        if (hasLoadedMetrics) return
        hasLoadedMetrics = true

        metricDao.getAllActiveMetrics()
            .onEach { metrics ->
                _uiState.value = _uiState.value.copy(availableMetrics = metrics)
            }
            .launchIn(viewModelScope)
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

    fun toggleMetricSelection(metricId: Long) {
        val currentSelection = _uiState.value.selectedMetricIds
        val newSelection = if (metricId in currentSelection) {
            currentSelection - metricId
        } else {
            currentSelection + metricId
        }
        _uiState.value = _uiState.value.copy(selectedMetricIds = newSelection)
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
        if (isSavingInProgress) return

        val currentState = _uiState.value
        if (!currentState.isValid) return

        isSavingInProgress = true
        _uiState.value = currentState.copy(isSaving = true)

        viewModelScope.launch {
            try {
                // Create habit with auto-configured values for temporary tasks
                val habitId = habitRepository.createHabit(
                    name = currentState.name,
                    description = "", // D-08: no description for temp tasks
                    habitType = HabitType.CHECK_IN, // D-09: hardcoded
                    iconResId = 53, // D-12: task icon from Phase 88
                    colorHex = "#2196F3", // D-14: first preset color
                    schedule = HabitSchedule.Daily, // D-13: default
                    targetValue = 1,
                    isCountdown = false,
                    parentHabitId = currentState.parentHabitUuid,
                    targetCycles = 1, // D-10: hardcoded
                    failMode = FailMode.LOOSE, // D-11: hardcoded (loose mode for temp tasks)
                    bestTime = null,
                    context = context,
                    selectedMetricIds = currentState.selectedMetricIds
                )

                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    savedHabitId = habitId
                )
            } catch (e: SQLiteConstraintException) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    showDuplicateDialog = true
                )
            } catch (e: Exception) {
                Log.e("CreateTempTaskViewModel", "Failed to save temp task", e)
                _uiState.value = _uiState.value.copy(isSaving = false)
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
