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
import kotlinx.coroutines.flow.update
import com.dayforge.data.local.DatabaseRuntimeHost
import com.dayforge.data.local.DatabaseObjectSelection
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
    val appearance: com.dayforge.domain.model.ObjectAppearance? = null,
    val editAuthority: com.dayforge.data.repository.ObjectEditAuthority? = null,
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
    val errorMessage: String? = null,
    val sourceSelection: DatabaseObjectSelection? = null,
    val sourceExpired: Boolean = false
)

/**
 * ViewModel for editing existing metrics.
 *
 * Loads metric data from database, allows editing all fields,
 * and saves changes back to the database.
 */
@HiltViewModel
class EditMetricViewModel internal constructor(
    @param:ApplicationContext private val context: Context,
    metricDao: MetricDao?, metricRepository: MetricRepository?, savedStateHandle: SavedStateHandle,
    private val databases: DatabaseRuntimeHost? = null
) : ViewModel() {
    @Inject internal constructor(@ApplicationContext context: Context, databases: DatabaseRuntimeHost,
        savedStateHandle: SavedStateHandle) : this(context, null, null, savedStateHandle, databases)
    private val fixedReads = if (databases == null)
        com.dayforge.data.repository.MetricDetailSourceReader(requireNotNull(metricDao)) else null
    private val reads get() = databases?.boundGraph()?.metricDetailSourceReader ?: requireNotNull(fixedReads)
    private val fixedRepository = metricRepository
    private val metricRepository get() = databases?.boundGraph()?.metricRepository ?: requireNotNull(fixedRepository)
    internal val usesSourceEpoch: Boolean get() = databases != null
    private val metricId: Long = savedStateHandle["metricId"] ?: 0L
    private val _uiState = MutableStateFlow(EditMetricUiState(sourceExpired = databases != null))
    val uiState: StateFlow<EditMetricUiState> = _uiState.asStateFlow()
    @Volatile private var boundSelection: DatabaseObjectSelection? = null
    @Volatile private var loadGeneration: Any = Any()
    private var loadJob: kotlinx.coroutines.Job? = null
    @Volatile private var isSavingInProgress = false
    private val memoryRegistration = databases?.observeMemory(object : DatabaseRuntimeHost.MemoryListener {
        override fun transition(blocked: Boolean) {
            if (blocked || boundSelection?.publication?.isCurrentInMemory() != true) expireSource()
        }
    })
    init { if (databases == null) loadMetric() }

    internal fun isTerminalCurrent(frame: EditMetricUiState): Boolean = databases == null ||
        !frame.sourceExpired && frame.sourceSelection != null && frame.sourceSelection === boundSelection &&
            frame.sourceSelection.publication.isCurrentInMemory()

    private fun expireSource() {
        loadGeneration = Any()
        // An edited draft is still personal data. Until a separate recovery/display
        // contract exists, every revoked source clears it rather than exempting typed text.
        _uiState.update { old -> EditMetricUiState(sourceSelection = boundSelection,
            sourceExpired = true, isLoaded = true, isSaving = old.isSaving,
            errorMessage = context.getString(R.string.database_view_expired)) }
    }
    private fun reportInput(error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        android.widget.Toast.makeText(context, com.dayforge.ui.actionFailureMessage(context, error), android.widget.Toast.LENGTH_LONG).show()
    }
    private fun publish(source: DatabaseObjectSelection?, update: (EditMetricUiState) -> EditMetricUiState): Boolean {
        if (databases == null) { _uiState.update(update); return true }
        return source?.publication?.publish { if (boundSelection === source) _uiState.update(update) } == true
    }
    private suspend fun <T> withSource(source: DatabaseObjectSelection?, block: suspend () -> T): T {
        val host = databases ?: return block()
        return host.withSelection(source, DatabaseObjectSelection.Kind.METRIC) { graph ->
            val original = requireNotNull(source)
            check(original.id == metricId) { "DATABASE_EPOCH_SELECTION_CHANGED" }
            // Absence keeps the original reader/not-found or delete-noop contract. A
            // present replacement must never be adopted under the old numeric route.
            graph.metricDetailSourceReader.visibleMetric(metricId)?.let(original::requireMetric)
            block()
        }
    }
    fun adoptSource(sourceSelection: DatabaseObjectSelection?) {
        if (databases == null) return
        try {
            val source = requireNotNull(sourceSelection) { "DATABASE_EPOCH_INPUT_REQUIRED" }
            check(source.kind == DatabaseObjectSelection.Kind.METRIC && source.id == metricId &&
                (boundSelection == null || boundSelection === source) && source.publication.isCurrentInMemory()) {
                "DATABASE_EPOCH_INPUT_EXPIRED"
            }
            if (boundSelection === source && (loadJob?.isActive == true || !_uiState.value.sourceExpired)) return
            boundSelection = source
            publish(source) { it.copy(isLoaded = false, errorMessage = null) }
            loadMetric(source)
        } catch (error: Exception) { expireSource(); reportInput(error) }
    }

    /**
     * Load the existing metric from the database.
     */
    private fun loadMetric(source: DatabaseObjectSelection? = null) {
        val generation = Any().also { loadGeneration = it }
        loadJob = viewModelScope.launch {
            try {
                val snapshot = withSource(source) { metricRepository.getMetricForEditing(metricId) }
                val metric = snapshot.value
                if (metric == null) {
                    publish(source) { it.copy(isLoaded = true, errorMessage = context.getString(R.string.toast_metric_not_found)) }
                    return@launch
                }
                source?.requireMetric(metric)
                val isCustom = !metric.unit.isNullOrEmpty() && !com.dayforge.ui.components.MetricUnits.isPreset(metric.unit)
                if (loadGeneration !== generation) return@launch
                publish(source) { EditMetricUiState(metricId = metric.id, name = metric.name,
                    description = metric.description, unit = metric.unit, isUnitCustom = isCustom,
                    customUnit = if (isCustom) metric.unit else "", decimalPlaces = metric.decimalPlaces,
                    aggregationType = metric.aggregationType, targetDirection = metric.targetDirection,
                    targetValueInput = formatDoubleToString(metric.targetValue, metric.decimalPlaces),
                    targetValueUpperInput = formatDoubleToString(metric.targetValueUpper, metric.decimalPlaces),
                    iconResId = metric.iconResId, appearance = metric.appearance, editAuthority = snapshot.authority,
                    colorHex = metric.appearance?.accentColor ?: metric.colorHex, isActive = metric.isActive,
                    isLoaded = true, isValid = validateState(metric.name, metric.unit),
                    sourceSelection = source, sourceExpired = false) }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) {
                if (loadGeneration === generation && !publish(source) { it.copy(isLoaded = true,
                        errorMessage = context.getString(R.string.toast_save_failed, error.message)) }) {
                    expireSource(); reportInput(error)
                }
            }
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

    private fun editDraft(update: (EditMetricUiState) -> EditMetricUiState) {
        if (databases == null) { _uiState.update(update); return }
        val source = boundSelection ?: return
        source.publication.publish {
            if (boundSelection === source) _uiState.update { current ->
                if (current.sourceExpired || current.sourceSelection !== source) current else update(current)
            }
        }
    }

    fun updateName(name: String) {
        editDraft { it.copy(name = name, errorMessage = null,
            isValid = !it.sourceExpired && validateState(name, it.unit)) }
    }

    fun updateDescription(description: String) {
        editDraft { it.copy(description = description) }
    }

    fun updateUnit(unit: String) {
        val custom = unit.isNotEmpty() && !com.dayforge.ui.components.MetricUnits.isPreset(unit)
        editDraft { it.copy(unit = unit, isUnitCustom = custom, customUnit = if (custom) unit else "",
            showUnitPicker = false, errorMessage = null, isValid = !it.sourceExpired && validateState(it.name, unit)) }
    }

    fun updateDecimalPlaces(places: Int) { editDraft { it.copy(decimalPlaces = places) } }

    fun updateAggregationType(type: String) { editDraft { it.copy(aggregationType = type) } }

    fun updateTargetDirection(direction: String?) {
        editDraft { it.copy(targetDirection = direction,
            targetValueInput = if (direction == null) "" else it.targetValueInput,
            targetValueUpperInput = if (direction != "range") "" else it.targetValueUpperInput) }
    }

    fun updateTargetValueInput(value: String) {
        editDraft { it.copy(targetValueInput = NumericInputUtils.filterNumericInput(value, it.decimalPlaces)) }
    }

    fun updateTargetValueUpperInput(value: String) {
        editDraft { it.copy(targetValueUpperInput = NumericInputUtils.filterNumericInput(value, it.decimalPlaces)) }
    }

    fun updateIcon(iconResId: Int) {
        editDraft { if (it.appearance != null) it else it.copy(iconResId = iconResId, showIconPicker = false) }
    }

    fun updateColor(colorHex: String) {
        editDraft { it.copy(colorHex = colorHex, appearance = it.appearance?.copy(accentColor = colorHex), showColorPicker = false) }
    }

    fun updateAppearance(appearance: com.dayforge.domain.model.ObjectAppearance) {
        editDraft { if (it.appearance == null || it.sourceExpired) it
            else it.copy(appearance = appearance, colorHex = appearance.accentColor, showIconPicker = false) }
    }

    fun toggleActive() { editDraft { it.copy(isActive = !it.isActive) } }

    // ========== Dialog Toggles ==========

    fun toggleIconPicker() { editDraft { it.copy(showIconPicker = !it.showIconPicker) } }

    fun toggleColorPicker() { editDraft { it.copy(showColorPicker = !it.showColorPicker) } }

    fun toggleUnitPicker() { editDraft { it.copy(showUnitPicker = !it.showUnitPicker) } }

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
    fun saveMetric(frame: EditMetricUiState? = null) {
        if (isSavingInProgress || _uiState.value.isSaved) return
        val currentState = _uiState.value
        if (databases != null) try {
            val rendered = requireNotNull(frame) { "DATABASE_EPOCH_INPUT_REQUIRED" }
            check(rendered.sourceSelection != null && rendered.sourceSelection === boundSelection &&
                currentState.sourceSelection === rendered.sourceSelection && !rendered.sourceExpired &&
                !currentState.sourceExpired && rendered.sourceSelection.publication.isCurrentInMemory()) {
                "DATABASE_EPOCH_INPUT_EXPIRED"
            }
        } catch (error: Exception) { reportInput(error); return }
        if (!currentState.isValid) return
        val source = currentState.sourceSelection
        isSavingInProgress = true
        if (!publish(source) { it.copy(isSaving = true) }) { isSavingInProgress = false; return }
        viewModelScope.launch {
            try {
                withSource(source) save@ {
                    val existingMetric = reads.visibleMetric(metricId)
                    if (existingMetric == null) {
                        publish(source) { it.copy(isSaving = false, errorMessage = context.getString(R.string.toast_metric_not_found)) }
                        return@save
                    }
                    source?.requireMetric(existingMetric)
                    val trimmedName = currentState.name.trim()
                    if (trimmedName != existingMetric.name) {
                        val duplicateMetric = reads.metricByName(trimmedName)
                        if (duplicateMetric != null) {
                            publish(source) { it.copy(isSaving = false, showDuplicateDialog = true) }
                            return@save
                        }
                    }
                    val updatedMetric = existingMetric.copy(name = trimmedName,
                        description = currentState.description.trim(), unit = currentState.unit,
                        decimalPlaces = currentState.decimalPlaces, aggregationType = currentState.aggregationType,
                        targetDirection = currentState.targetDirection, targetValue = currentState.targetValueInput.toDoubleOrNull(),
                        targetValueUpper = currentState.targetValueUpperInput.toDoubleOrNull(), iconResId = currentState.iconResId,
                        appearance = currentState.appearance, colorHex = currentState.colorHex, isActive = currentState.isActive,
                        updatedAt = System.currentTimeMillis())
                    metricRepository.updateMetric(updatedMetric, currentState.editAuthority)
                    publish(source) { it.copy(isSaving = false, isSaved = true,
                        showIconPicker = false, showColorPicker = false, showUnitPicker = false) }
                }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: DuplicateMetricNameException) {
                publish(source) { it.copy(isSaving = false, showDuplicateDialog = true) }
            } catch (error: Exception) {
                if (!publish(source) { it.copy(isSaving = false, errorMessage = context.getString(R.string.toast_save_failed, error.message)) })
                    reportInput(error)
            } finally { isSavingInProgress = false; _uiState.update { it.copy(isSaving = false) } }
        }
    }

    fun dismissDuplicateDialog() { editDraft { it.copy(showDuplicateDialog = false) } }

    // ========== Utility ==========

    fun clearError() { editDraft { it.copy(errorMessage = null) } }
}
