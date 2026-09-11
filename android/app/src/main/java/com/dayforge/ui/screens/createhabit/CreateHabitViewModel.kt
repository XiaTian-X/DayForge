package com.dayforge.ui.screens.createhabit

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.R
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.MetricDao
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

data class CreateHabitUiState(
    val name: String = "",
    val description: String = "",
    val habitType: HabitType = HabitType.CHECK_IN,
    val iconResId: Int = R.drawable.ic_launcher_foreground,
    val colorHex: String = "#2196F3",
    val schedule: HabitSchedule = HabitSchedule.Daily,
    val monthlyInputValue: String = "1",  // Display value for Monthly schedule, can be cleared
    val customInputValue: String = "1",   // Display value for Custom schedule, can be cleared
    val targetValue: Int? = 1,
    val isCountdown: Boolean = false,  // false = countup mode, true = countdown mode
    val parentHabitUuid: String? = null,  // UUID of selected parent habit, null = top-level
    val topLevelHabits: List<HabitEntity> = emptyList(),  // Available parent habits
    val targetCycles: Int? = null,  // Nullable: null = infinite tracking (no target)
    val failMode: FailMode = FailMode.STRICT,  // Failure mode for target-based habits
    val bestTime: Long? = null,  // Nullable: best execution time (minutes since midnight)
    val showTimePicker: Boolean = false,  // Time picker dialog state
    val isValid: Boolean = false,
    val isSaving: Boolean = false,
    val savedHabitId: Long? = null,
    val showIconPicker: Boolean = false,
    val showColorPicker: Boolean = false,
    val selectedPresetName: String? = null,
    val showPresetDialog: Boolean = false,
    val errorMessage: String? = null,
    val showDuplicateDialog: Boolean = false,
    val showDefaultTargetDialog: Boolean = false,
    val showDefaultScheduleDialog: Boolean = false,
    // Metric linking (optional)
    val availableMetrics: List<MetricEntity> = emptyList(),
    val selectedMetricIds: Set<Long> = emptySet()
)

@HiltViewModel
class CreateHabitViewModel @Inject constructor(
    private val habitRepository: HabitRepository,
    private val habitDao: HabitDao,
    private val metricDao: MetricDao,
    private val preferencesManager: PreferencesManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateHabitUiState())
    val uiState: StateFlow<CreateHabitUiState> = _uiState.asStateFlow()

    // Immediate debounce flag - checked synchronously before StateFlow updates
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
            errorMessage = null,
            showDuplicateDialog = false
        )
    }

    fun updateDescription(description: String) {
        _uiState.value = _uiState.value.copy(description = description)
    }

    fun updateHabitType(type: HabitType) {
        _uiState.value = _uiState.value.copy(habitType = type)
    }

    fun updateIcon(iconResId: Int) {
        _uiState.value = _uiState.value.copy(iconResId = iconResId)
    }

    fun updateColor(colorHex: String) {
        _uiState.value = _uiState.value.copy(colorHex = colorHex)
    }

    fun updateSchedule(schedule: HabitSchedule) {
        // Initialize input values when switching schedule type
        val monthlyInput = if (schedule is HabitSchedule.Monthly) "1" else _uiState.value.monthlyInputValue
        val customInput = if (schedule is HabitSchedule.Custom) "1" else _uiState.value.customInputValue
        _uiState.value = _uiState.value.copy(
            schedule = schedule,
            monthlyInputValue = monthlyInput,
            customInputValue = customInput
        )
    }

    fun toggleDayOfWeek(dayValue: Int) {
        val currentSchedule = _uiState.value.schedule
        if (currentSchedule is HabitSchedule.Weekly) {
            val currentDays = currentSchedule.daysOfWeek
            val newDays = if (dayValue in currentDays) {
                currentDays - dayValue  // Remove day
            } else {
                (currentDays + dayValue).sorted()  // Add day and keep sorted
            }
            _uiState.value = _uiState.value.copy(
                schedule = HabitSchedule.Weekly(newDays)
            )
        }
    }

    fun updateMonthlyInput(value: String) {
        // Filter to only allow digits
        val filtered = value.filter { it.isDigit() }
        // Clamp input value to valid dayOfMonth range (1-31)
        val clamped = filtered.toIntOrNull()?.coerceIn(1, 31)?.toString() ?: filtered
        _uiState.value = _uiState.value.copy(monthlyInputValue = clamped)
    }

    fun updateCustomInput(value: String) {
        // Filter to only allow digits
        val filtered = value.filter { it.isDigit() }
        _uiState.value = _uiState.value.copy(customInputValue = filtered)
    }

    fun updateTargetValue(value: Int?) {
        _uiState.value = _uiState.value.copy(targetValue = value)
    }

    fun updateIsCountdown(value: Boolean) {
        _uiState.value = _uiState.value.copy(isCountdown = value)
    }

    fun updateParentHabit(habit: HabitEntity?) {
        _uiState.value = _uiState.value.copy(parentHabitUuid = habit?.uuid)
    }

    /**
     * Set parent habit UUID directly (used when navigating from CreateGoalScreen).
     */
    fun setParentUuid(uuid: String?) {
        _uiState.value = _uiState.value.copy(parentHabitUuid = uuid)
    }

    fun updateTargetCycles(value: Int?) {
        _uiState.value = _uiState.value.copy(targetCycles = value)
    }

    fun updateFailMode(mode: FailMode) {
        _uiState.value = _uiState.value.copy(failMode = mode)
    }

    fun updateBestTime(time: Long?) {
        _uiState.value = _uiState.value.copy(bestTime = time)
    }

    fun toggleTimePicker() {
        _uiState.value = _uiState.value.copy(showTimePicker = !_uiState.value.showTimePicker)
    }

    fun clearBestTime() {
        _uiState.value = _uiState.value.copy(bestTime = null)
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

    fun togglePresetDialog() {
        _uiState.value = _uiState.value.copy(
            showPresetDialog = !_uiState.value.showPresetDialog
        )
    }

    fun selectPreset(preset: com.dayforge.data.local.entity.HabitEntity) {
        _uiState.value = _uiState.value.copy(
            name = preset.name,
            description = preset.description,
            habitType = preset.habitType,
            iconResId = preset.iconResId,
            colorHex = preset.colorHex,
            schedule = preset.schedule,
            targetValue = preset.targetValue,
            isCountdown = preset.isCountdown,
            selectedPresetName = preset.name,
            isValid = validateForm(preset.name)
        )
    }

    private fun validateForm(name: String): Boolean {
        return name.isNotBlank() && name.length <= 50
    }

    fun saveHabit(predefinedUuid: String? = null) {
        // Immediate synchronous check - prevents rapid clicks before StateFlow updates
        if (isSavingInProgress) return

        val currentState = _uiState.value
        if (!currentState.isValid) return

        // Check if Monthly/Custom schedule values are empty
        when (currentState.schedule) {
            is HabitSchedule.Monthly -> {
                if (currentState.monthlyInputValue.isEmpty()) {
                    _uiState.value = currentState.copy(showDefaultScheduleDialog = true)
                    return
                }
            }
            is HabitSchedule.Custom -> {
                if (currentState.customInputValue.isEmpty()) {
                    _uiState.value = currentState.copy(showDefaultScheduleDialog = true)
                    return
                }
            }
            else -> {} // Daily, Weekly don't need input validation
        }

        // Check if targetValue is empty for counting/timer habits
        if ((currentState.habitType == HabitType.COUNTING || currentState.habitType == HabitType.TIMER)
            && currentState.targetValue == null) {
            _uiState.value = currentState.copy(showDefaultTargetDialog = true)
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

        // Set flag immediately
        isSavingInProgress = true
        _uiState.value = currentState.copy(isSaving = true)

        viewModelScope.launch {
            try {
                val habitId = habitRepository.createHabit(
                    name = currentState.name,
                    description = currentState.description,
                    habitType = currentState.habitType,
                    iconResId = currentState.iconResId,
                    colorHex = currentState.colorHex,
                    schedule = finalSchedule,
                    targetValue = currentState.targetValue ?: 1,
                    isCountdown = currentState.isCountdown,
                    parentHabitId = currentState.parentHabitUuid,
                    targetCycles = currentState.targetCycles,
                    failMode = currentState.failMode,
                    bestTime = currentState.bestTime,
                    predefinedUuid = predefinedUuid,
                    context = context,
                    selectedMetricIds = currentState.selectedMetricIds
                )

                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    savedHabitId = habitId,
                    showIconPicker = false,
                    showColorPicker = false,
                    showPresetDialog = false
                )
            } catch (e: SQLiteConstraintException) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    showDuplicateDialog = true,
                    errorMessage = context.getString(R.string.toast_duplicate_habit_name)
                )
            } catch (e: Exception) {
                val isDuplicate = generateSequence<Throwable>(e) { it.cause }
                    .any { it is SQLiteConstraintException }
                _uiState.value = if (isDuplicate) {
                    _uiState.value.copy(
                        isSaving = false,
                        showDuplicateDialog = true,
                        errorMessage = context.getString(R.string.toast_duplicate_habit_name)
                    )
                } else {
                    _uiState.value.copy(
                        isSaving = false,
                        errorMessage = context.getString(R.string.toast_save_failed, e.message.orEmpty())
                    )
                }
            } finally {
                isSavingInProgress = false
            }
        }
    }

    fun dismissDefaultTargetDialog() {
        _uiState.value = _uiState.value.copy(showDefaultTargetDialog = false)
    }

    fun confirmDefaultTarget() {
        _uiState.value = _uiState.value.copy(
            targetValue = 1,
            showDefaultTargetDialog = false
        )
        // Continue saving
        saveHabit()
    }

    fun dismissDefaultScheduleDialog() {
        _uiState.value = _uiState.value.copy(showDefaultScheduleDialog = false)
    }

    fun confirmDefaultSchedule() {
        val currentState = _uiState.value
        // Set default values (Monthly=1, Custom=1) and continue saving
        val updatedState = when (currentState.schedule) {
            is HabitSchedule.Monthly -> currentState.copy(
                monthlyInputValue = "1",
                showDefaultScheduleDialog = false
            )
            is HabitSchedule.Custom -> currentState.copy(
                customInputValue = "1",
                showDefaultScheduleDialog = false
            )
            else -> currentState.copy(showDefaultScheduleDialog = false)
        }
        _uiState.value = updatedState
        // Continue saving
        saveHabit()
    }

    fun resetState() {
        isSavingInProgress = false
        _uiState.value = CreateHabitUiState()
    }

    fun clearSavedHabit() {
        _uiState.value = _uiState.value.copy(savedHabitId = null)
    }

    fun dismissDuplicateDialog() {
        _uiState.value = _uiState.value.copy(
            showDuplicateDialog = false,
            errorMessage = null
        )
    }
}
