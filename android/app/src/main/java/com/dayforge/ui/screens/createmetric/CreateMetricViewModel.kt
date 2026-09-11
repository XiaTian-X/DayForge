package com.dayforge.ui.screens.createmetric

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.R
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.repository.DuplicateMetricNameException
import com.dayforge.data.repository.MetricRepository
import com.dayforge.util.NumericInputUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the metric creation screen.
 *
 * Per D-04: Two-step creation process
 * Per D-05: Step 1 includes name, description, unit, decimalPlaces, targetDirection, targetValue
 * Per D-06: Step 2 includes icon, color (habit selection in 26-02)
 * Per D-07: Both steps can be navigated back
 */
data class CreateMetricUiState(
    // Step tracking
    val currentStep: Int = 1,

    // Basic info (Step 1)
    val name: String = "",
    val description: String = "",
    val unit: String = "",
    val isUnitCustom: Boolean = false,
    val customUnit: String = "",
    val decimalPlaces: Int = 0,
    val aggregationType: String = "average",  // "average" | "sum" | "by_time"
    val targetDirection: String? = null,  // "increase" | "decrease" | "range" | null
    val targetValueInput: String = "",    // String for UI input
    val targetValueUpperInput: String = "", // String for UI input (range upper bound)

    // Appearance (Step 2)
    val iconResId: Int = 1,  // Default to first icon (water)
    val colorHex: String = "#2196F3",

    // Habit links (for 26-02)
    val availableHabits: List<HabitEntity> = emptyList(),
    val selectedHabitIds: Set<Long> = emptySet(),

    // Form state
    val isStepValid: Boolean = false,
    val isSaving: Boolean = false,
    val savedMetricId: Long? = null,

    // Dialog visibility
    val showIconPicker: Boolean = false,
    val showColorPicker: Boolean = false,
    val showUnitPicker: Boolean = false,
    val showDuplicateDialog: Boolean = false,

    // Error handling
    val errorMessage: String? = null
)

/**
 * ViewModel for creating metrics.
 *
 * Manages two-step creation flow with validation and persistence.
 */
@HiltViewModel
class CreateMetricViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metricRepository: MetricRepository,
    private val metricDao: MetricDao,
    private val habitDao: HabitDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateMetricUiState())
    val uiState: StateFlow<CreateMetricUiState> = _uiState.asStateFlow()

    // Double-click prevention flag
    @Volatile
    private var isSavingInProgress = false

    // Lazy load flag for habits
    @Volatile
    private var hasLoadedHabits = false

    /**
     * Load available habits when user enters step 2 (habit linking step).
     * Lazy loading to avoid database query on ViewModel init.
     */
    fun loadHabitsIfNeeded() {
        if (hasLoadedHabits) return
        hasLoadedHabits = true

        viewModelScope.launch {
            habitDao.getAllHabits().collect { habits ->
                _uiState.value = _uiState.value.copy(availableHabits = habits)
            }
        }
    }

    // ========== Step Navigation ==========

    fun goToStep(step: Int) {
        val currentState = _uiState.value
        if (step == 1) {
            _uiState.value = currentState.copy(currentStep = step)
        } else if (step == 2 && validateStep1(currentState)) {
            // Check for duplicate name before proceeding to step 2
            viewModelScope.launch {
                val trimmedName = currentState.name.trim()
                val existingMetric = metricDao.getMetricByName(trimmedName)
                if (existingMetric != null) {
                    _uiState.value = _uiState.value.copy(showDuplicateDialog = true)
                } else {
                    _uiState.value = currentState.copy(currentStep = step)
                    // Lazy load habits when entering step 2
                    loadHabitsIfNeeded()
                }
            }
        }
    }

    fun dismissDuplicateDialog() {
        _uiState.value = _uiState.value.copy(showDuplicateDialog = false)
    }

    // ========== Field Updates ==========

    fun updateName(name: String) {
        val newState = _uiState.value.copy(
            name = name,
            errorMessage = null
        )
        _uiState.value = newState.copy(
            isStepValid = validateCurrentStep(newState)
        )
    }

    fun updateDescription(description: String) {
        _uiState.value = _uiState.value.copy(description = description)
    }

    fun updateUnit(unit: String) {
        val isCustom = !unit.isNullOrEmpty() && !com.dayforge.ui.components.MetricUnits.isPreset(unit)
        val newState = _uiState.value.copy(
            unit = unit,
            isUnitCustom = isCustom,
            customUnit = if (isCustom) unit else "",
            showUnitPicker = false,
            errorMessage = null
        )
        _uiState.value = newState.copy(
            isStepValid = validateCurrentStep(newState)
        )
    }

    fun updateDecimalPlaces(places: Int) {
        _uiState.value = _uiState.value.copy(decimalPlaces = places)
    }

    fun updateAggregationType(type: String) {
        _uiState.value = _uiState.value.copy(aggregationType = type)
    }

    fun updateTargetDirection(direction: String?) {
        val newState = _uiState.value.copy(
            targetDirection = direction,
            // Clear target values if no direction
            targetValueInput = if (direction == null) "" else _uiState.value.targetValueInput,
            targetValueUpperInput = if (direction != "range") "" else _uiState.value.targetValueUpperInput
        )
        _uiState.value = newState
    }

    fun updateTargetValueInput(value: String) {
        val filtered = NumericInputUtils.filterNumericInput(value, _uiState.value.decimalPlaces)
        _uiState.value = _uiState.value.copy(targetValueInput = filtered)
    }

    fun updateTargetValueUpperInput(value: String) {
        val filtered = NumericInputUtils.filterNumericInput(value, _uiState.value.decimalPlaces)
        _uiState.value = _uiState.value.copy(targetValueUpperInput = filtered)
    }

    fun updateIcon(iconResId: Int) {
        val newState = _uiState.value.copy(
            iconResId = iconResId,
            showIconPicker = false
        )
        _uiState.value = newState.copy(
            isStepValid = validateCurrentStep(newState)
        )
    }

    fun updateColor(colorHex: String) {
        val newState = _uiState.value.copy(
            colorHex = colorHex,
            showColorPicker = false
        )
        _uiState.value = newState.copy(
            isStepValid = validateCurrentStep(newState)
        )
    }

    // ========== Habit Selection ==========

    /**
     * Toggle habit selection for metric linking.
     * Per D-09: 0 selections is valid (metric is independent)
     */
    fun toggleHabitSelection(habitId: Long) {
        val currentSelection = _uiState.value.selectedHabitIds
        val newSelection = if (habitId in currentSelection) {
            currentSelection - habitId
        } else {
            currentSelection + habitId
        }
        _uiState.value = _uiState.value.copy(selectedHabitIds = newSelection)
    }

    // ========== Dialog Toggles ==========

    fun toggleIconPicker() {
        _uiState.value = _uiState.value.copy(
            showIconPicker = !_uiState.value.showIconPicker
        )
    }

    fun toggleColorPicker() {
        _uiState.value = _uiState.value.copy(
            showColorPicker = !_uiState.value.showColorPicker
        )
    }

    fun toggleUnitPicker() {
        _uiState.value = _uiState.value.copy(
            showUnitPicker = !_uiState.value.showUnitPicker
        )
    }

    // ========== Validation ==========

    private fun validateCurrentStep(state: CreateMetricUiState): Boolean {
        return when (state.currentStep) {
            1 -> validateStep1(state)
            2 -> validateStep2(state)
            else -> false
        }
    }

    /**
     * Validate Step 1: name and unit are required.
     */
    private fun validateStep1(state: CreateMetricUiState): Boolean {
        val nameValid = state.name.isNotBlank() && state.name.length <= 50
        val unitValid = state.unit.isNotBlank()
        return nameValid && unitValid
    }

    /**
     * Validate Step 2: icon and color are required (have defaults, so always valid).
     */
    private fun validateStep2(state: CreateMetricUiState): Boolean {
        // Icon and color have default values, so step 2 is always valid
        return true
    }

    // ========== Save ==========

    fun saveMetric() {
        // Prevent double-click
        if (isSavingInProgress) return

        val currentState = _uiState.value
        if (!validateStep2(currentState)) return

        isSavingInProgress = true
        _uiState.value = currentState.copy(isSaving = true)

        viewModelScope.launch {
            try {
                // Check for duplicate name
                val trimmedName = currentState.name.trim()
                val existingMetric = metricDao.getMetricByName(trimmedName)
                if (existingMetric != null) {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        showDuplicateDialog = true
                    )
                    isSavingInProgress = false
                    return@launch
                }

                // Pre-generate UUID for sync (D-08)
                val metricUuid = java.util.UUID.randomUUID().toString()

                val metric = MetricEntity(
                    name = trimmedName,
                    description = currentState.description.trim(),
                    unit = currentState.unit,
                    decimalPlaces = currentState.decimalPlaces,
                    aggregationType = currentState.aggregationType,
                    targetDirection = currentState.targetDirection,
                    targetValue = currentState.targetValueInput.toDoubleOrNull(),
                    targetValueUpper = currentState.targetValueUpperInput.toDoubleOrNull(),
                    iconResId = currentState.iconResId,
                    colorHex = currentState.colorHex,
                    isActive = true,
                    uuid = metricUuid
                )

                val metricId = metricRepository.createMetric(metric, currentState.selectedHabitIds)

                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    savedMetricId = metricId,
                    showIconPicker = false,
                    showColorPicker = false,
                    showUnitPicker = false
                )
            } catch (_: DuplicateMetricNameException) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    showDuplicateDialog = true
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = context.getString(R.string.toast_save_failed, e.message)
                )
            } finally {
                isSavingInProgress = false
            }
        }
    }

    // ========== Utility ==========

    fun clearSavedMetric() {
        _uiState.value = _uiState.value.copy(savedMetricId = null)
    }

    fun resetState() {
        isSavingInProgress = false
        _uiState.value = CreateMetricUiState()
    }
}
