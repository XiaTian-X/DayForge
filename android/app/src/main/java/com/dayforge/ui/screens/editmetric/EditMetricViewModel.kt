package com.dayforge.ui.screens.editmetric

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.R
import com.dayforge.data.local.dao.MetricDao
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
import java.util.Locale

/**
 * UI state for the metric edit screen.
 *
 * Contains all editable fields of a metric plus form state.
 */
data class EditMetricUiState(
    val metricId: Long = 0,

    // Basic info
    val name: String = "",
    val description: String = "",
    val unit: String = "",
    val isUnitCustom: Boolean = false,
    val customUnit: String = "",
    val decimalPlaces: Int = 0,
    val aggregationType: String = "average",  // D-08: default is average
    val targetDirection: String? = null,  // "increase" | "decrease" | "range" | null
    val targetValueInput: String = "",    // String for UI input
    val targetValueUpperInput: String = "", // String for UI input (range upper bound)

    // Appearance
    val iconResId: Int = 1,
    val colorHex: String = "#2196F3",
    val isActive: Boolean = true,

    // Form state
    val isValid: Boolean = false,
    val isSaving: Boolean = false,
    val isLoaded: Boolean = false,
    val isSaved: Boolean = false,

    // Dialog visibility
    val showIconPicker: Boolean = false,
    val showColorPicker: Boolean = false,
    val showUnitPicker: Boolean = false,
    val showDuplicateDialog: Boolean = false,

    // Error handling
    val errorMessage: String? = null
)

/**
 * ViewModel for editing existing metrics.
 *
 * Loads metric data from database, allows editing all fields,
 * and saves changes back to the database.
 */
@HiltViewModel
class EditMetricViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metricDao: MetricDao,
    private val metricRepository: MetricRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val metricId: Long = savedStateHandle["metricId"] ?: 0L

    private val _uiState = MutableStateFlow(EditMetricUiState())
    val uiState: StateFlow<EditMetricUiState> = _uiState.asStateFlow()

    // Double-click prevention flag
    @Volatile
    private var isSavingInProgress = false

    init {
        loadMetric()
    }

    /**
     * Load the existing metric from the database.
     */
    private fun loadMetric() {
        viewModelScope.launch {
            val metric = metricDao.getMetricById(metricId)
            if (metric == null) {
                _uiState.value = _uiState.value.copy(
                    isLoaded = true,
                    errorMessage = context.getString(R.string.toast_metric_not_found)
                )
                return@launch
            }

            val isCustom = !metric.unit.isNullOrEmpty() &&
                !com.dayforge.ui.components.MetricUnits.isPreset(metric.unit)

            _uiState.value = EditMetricUiState(
                metricId = metric.id,
                name = metric.name,
                description = metric.description,
                unit = metric.unit,
                isUnitCustom = isCustom,
                customUnit = if (isCustom) metric.unit else "",
                decimalPlaces = metric.decimalPlaces,
                aggregationType = metric.aggregationType,
                targetDirection = metric.targetDirection,
                targetValueInput = formatDoubleToString(metric.targetValue, metric.decimalPlaces),
                targetValueUpperInput = formatDoubleToString(metric.targetValueUpper, metric.decimalPlaces),
                iconResId = metric.iconResId,
                colorHex = metric.colorHex,
                isActive = metric.isActive,
                isLoaded = true,
                isValid = validateState(
                    name = metric.name,
                    unit = metric.unit
                )
            )
        }
    }

    /**
     * Format Double to String based on decimal places.
     * Returns empty string if null, otherwise formats without trailing .0 for integers.
     */
    private fun formatDoubleToString(value: Double?, decimalPlaces: Int): String {
        if (value == null) return ""
        return if (decimalPlaces == 0) {
            value.toInt().toString()
        } else {
            String.format(Locale.getDefault(), "%.${decimalPlaces}f", value).trimEnd('0').trimEnd('.')
        }
    }

    // ========== Field Updates ==========

    fun updateName(name: String) {
        val newState = _uiState.value.copy(
            name = name,
            errorMessage = null
        )
        _uiState.value = newState.copy(
            isValid = validateState(newState.name, newState.unit)
        )
    }

    fun updateDescription(description: String) {
        _uiState.value = _uiState.value.copy(description = description)
    }

    fun updateUnit(unit: String) {
        val isCustom = !unit.isNullOrEmpty() &&
            !com.dayforge.ui.components.MetricUnits.isPreset(unit)
        val newState = _uiState.value.copy(
            unit = unit,
            isUnitCustom = isCustom,
            customUnit = if (isCustom) unit else "",
            showUnitPicker = false,
            errorMessage = null
        )
        _uiState.value = newState.copy(
            isValid = validateState(newState.name, newState.unit)
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
        _uiState.value = _uiState.value.copy(
            iconResId = iconResId,
            showIconPicker = false
        )
    }

    fun updateColor(colorHex: String) {
        _uiState.value = _uiState.value.copy(
            colorHex = colorHex,
            showColorPicker = false
        )
    }

    fun toggleActive() {
        _uiState.value = _uiState.value.copy(
            isActive = !_uiState.value.isActive
        )
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

    private fun validateState(name: String, unit: String): Boolean {
        val nameValid = name.isNotBlank() && name.length <= 50
        val unitValid = unit.isNotBlank()
        return nameValid && unitValid
    }

    // ========== Save ==========

    /**
     * Save changes to the metric.
     * Updates the existing metric in the database.
     */
    fun saveMetric() {
        // Prevent double-click
        if (isSavingInProgress) return

        val currentState = _uiState.value
        if (!currentState.isValid) return

        isSavingInProgress = true
        _uiState.value = currentState.copy(isSaving = true)

        viewModelScope.launch {
            try {
                val existingMetric = metricDao.getMetricById(metricId)
                if (existingMetric == null) {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        errorMessage = context.getString(R.string.toast_metric_not_found)
                    )
                    return@launch
                }

                val trimmedName = currentState.name.trim()

                // Check for duplicate name if name changed
                if (trimmedName != existingMetric.name) {
                    val duplicateMetric = metricDao.getMetricByName(trimmedName)
                    if (duplicateMetric != null) {
                        _uiState.value = _uiState.value.copy(
                            isSaving = false,
                            showDuplicateDialog = true
                        )
                        isSavingInProgress = false
                        return@launch
                    }
                }

                val updatedMetric = existingMetric.copy(
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
                    isActive = currentState.isActive,
                    updatedAt = System.currentTimeMillis()
                )

                metricRepository.updateMetric(updatedMetric)

                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    isSaved = true,
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

    fun dismissDuplicateDialog() {
        _uiState.value = _uiState.value.copy(showDuplicateDialog = false)
    }

    // ========== Utility ==========

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}
