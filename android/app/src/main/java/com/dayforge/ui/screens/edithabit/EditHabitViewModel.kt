package com.dayforge.ui.screens.edithabit

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.util.Log
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
import com.dayforge.data.local.entity.HabitMetricLinkEntity
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
    val selectedMetricIds: Set<Long> = emptySet()
)

@HiltViewModel
class EditHabitViewModel @Inject constructor(
    private val habitRepository: HabitRepository,
    private val habitDao: HabitDao,
    private val timeLogDao: TimeLogDao,
    private val completionDao: CompletionDao,
    private val metricDao: MetricDao,
    private val habitMetricLinkDao: HabitMetricLinkDao,
    private val preferencesManager: PreferencesManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditHabitUiState())
    val uiState: StateFlow<EditHabitUiState> = _uiState.asStateFlow()

    private var originalHabit: HabitEntity? = null

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

        viewModelScope.launch {
            habitDao.getTopLevelHabits().collect { habits ->
                _uiState.value = _uiState.value.copy(topLevelHabits = habits)
                // Update parent name if we have a parent UUID and habit is loaded
                updateParentNameIfExists(_uiState.value.parentHabitUuid, habits)
            }
        }
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

    private fun updateParentNameIfExists(parentUuid: String?, habits: List<HabitEntity>) {
        if (parentUuid != null) {
            val parent = habits.find { it.uuid == parentUuid }
            if (parent != null) {
                _uiState.value = _uiState.value.copy(parentHabitName = parent.name)
            }
        }
    }

    fun loadHabit(id: Long) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val habit = habitRepository.getHabit(id)
                habit.collect { entity ->
                    if (entity != null) {
                        originalHabit = entity
                        // Find parent name if parent exists
                        val parentName = if (entity.parentHabitId != null) {
                            habitDao.getHabitByUuid(entity.parentHabitId)?.name
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
                            timeLogDao.hasTimeLogs(entity.id)
                        } else {
                            completionDao.hasCompletions(entity.id)
                        }
                        val originalScheduleDays = getScheduleDays(entity.schedule)

                        // Load existing metric links
                        val existingLinks = habitMetricLinkDao.getAllLinksForHabit(entity.id)
                        val linkedMetricIds = existingLinks.map { it.metricId }.toSet()

                        _uiState.value = EditHabitUiState(
                            habitId = entity.id,
                            name = entity.name,
                            description = entity.description,
                            habitType = entity.habitType,
                            iconResId = entity.iconResId,
                            colorHex = entity.colorHex,
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
                            selectedMetricIds = linkedMetricIds
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            habitNotFound = true
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    habitNotFound = true
                )
            }
        }
    }

    fun updateName(name: String) {
        _uiState.value = _uiState.value.copy(
            name = name,
            hasChanges = true,
            isValid = validateForm(name),
            errorMessage = null,
            showDuplicateDialog = false
        )
    }

    fun updateDescription(description: String) {
        _uiState.value = _uiState.value.copy(
            description = description,
            hasChanges = true
        )
    }

    fun updateIcon(iconResId: Int) {
        _uiState.value = _uiState.value.copy(
            iconResId = iconResId,
            hasChanges = true
        )
    }

    fun updateColor(colorHex: String) {
        _uiState.value = _uiState.value.copy(
            colorHex = colorHex,
            hasChanges = true
        )
    }

    fun updateSchedule(schedule: HabitSchedule) {
        // Initialize input values when switching schedule type
        val monthlyInput = if (schedule is HabitSchedule.Monthly) "1" else _uiState.value.monthlyInputValue
        val customInput = if (schedule is HabitSchedule.Custom) "1" else _uiState.value.customInputValue
        _uiState.value = _uiState.value.copy(
            schedule = schedule,
            monthlyInputValue = monthlyInput,
            customInputValue = customInput,
            hasChanges = true
        )
    }

    fun updateMonthlyInput(value: String) {
        // Filter to only allow digits
        val filtered = value.filter { it.isDigit() }
        // Clamp input value to valid dayOfMonth range (1-31)
        val clamped = filtered.toIntOrNull()?.coerceIn(1, 31)?.toString() ?: filtered
        _uiState.value = _uiState.value.copy(
            monthlyInputValue = clamped,
            hasChanges = true
        )
    }

    fun updateCustomInput(value: String) {
        // Filter to only allow digits
        val filtered = value.filter { it.isDigit() }
        // Clamp input value to max of originalScheduleDays (edit restriction)
        val maxDays = _uiState.value.originalScheduleDays
        val clamped = filtered.toIntOrNull()?.coerceAtMost(maxDays)?.toString() ?: filtered
        _uiState.value = _uiState.value.copy(
            customInputValue = clamped,
            hasChanges = true
        )
    }

    fun updateTargetValue(value: Int?) {
        _uiState.value = _uiState.value.copy(
            targetValue = value,
            hasChanges = true
        )
    }

    fun updateTargetCycles(value: Int?) {
        _uiState.value = _uiState.value.copy(
            targetCycles = value,
            hasChanges = true
        )
    }

    fun updateFailMode(mode: FailMode) {
        _uiState.value = _uiState.value.copy(
            failMode = mode,
            hasChanges = true
        )
    }

    fun updateBestTime(time: Long?) {
        _uiState.value = _uiState.value.copy(bestTime = time, hasChanges = true)
    }

    fun toggleTimePicker() {
        _uiState.value = _uiState.value.copy(showTimePicker = !_uiState.value.showTimePicker)
    }

    fun clearBestTime() {
        _uiState.value = _uiState.value.copy(bestTime = null, hasChanges = true)
    }

    fun toggleMetricSelection(metricId: Long) {
        val currentSelection = _uiState.value.selectedMetricIds
        val newSelection = if (metricId in currentSelection) {
            currentSelection - metricId
        } else {
            currentSelection + metricId
        }
        _uiState.value = _uiState.value.copy(
            selectedMetricIds = newSelection,
            hasChanges = true
        )
    }

    fun updateIsCountdown(value: Boolean) {
        viewModelScope.launch {
            // Check if timer is active for this habit
            val activeTimer = timeLogDao.getActiveTimeLogForHabit(_uiState.value.habitId)
            if (activeTimer != null) {
                // Timer is active, show dialog instead of updating
                _uiState.value = _uiState.value.copy(showActiveTimerDialog = true)
            } else {
                _uiState.value = _uiState.value.copy(
                    isCountdown = value,
                    hasChanges = true
                )
            }
        }
    }

    fun dismissActiveTimerDialog() {
        _uiState.value = _uiState.value.copy(showActiveTimerDialog = false)
    }

    fun toggleDeleteDialog() {
        _uiState.value = _uiState.value.copy(
            showDeleteDialog = !_uiState.value.showDeleteDialog
        )
    }

    fun deleteHabit(onDeleted: () -> Unit) {
        viewModelScope.launch {
            val habitId = _uiState.value.habitId
            try {
                val habit = habitRepository.getHabit(habitId).first()
                if (habit != null) {
                    val children = habitRepository.getHabitChildren(habit.uuid)
                    if (children.isNotEmpty()) {
                        _uiState.value = _uiState.value.copy(
                            showDeleteDialog = false,
                            showDeleteChildrenDialog = true,
                            pendingDeleteChildrenCount = children.size
                        )
                    } else {
                        habitRepository.deleteHabit(habit, context)
                        _uiState.value = _uiState.value.copy(
                            showDeleteDialog = false,
                            habitId = 0L
                        )
                        onDeleted()
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(showDeleteDialog = false)
                onDeleted()
            }
        }
    }

    fun deleteHabitWithChildren(onDeleted: () -> Unit) {
        viewModelScope.launch {
            val habitId = _uiState.value.habitId
            try {
                val habit = habitRepository.getHabit(habitId).first()
                if (habit != null) {
                    habitRepository.deleteHabitWithChildren(habit, context)
                }
                _uiState.value = _uiState.value.copy(
                    showDeleteChildrenDialog = false,
                    habitId = 0L
                )
                onDeleted()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(showDeleteChildrenDialog = false)
                onDeleted()
            }
        }
    }

    fun deleteHabitKeepChildren(onDeleted: () -> Unit) {
        viewModelScope.launch {
            val habitId = _uiState.value.habitId
            try {
                val habit = habitRepository.getHabit(habitId).first()
                if (habit != null) {
                    habitRepository.deleteHabitOrphanChildren(habit, context)
                }
                _uiState.value = _uiState.value.copy(
                    showDeleteChildrenDialog = false,
                    habitId = 0L
                )
                onDeleted()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(showDeleteChildrenDialog = false)
                onDeleted()
            }
        }
    }

    fun dismissDeleteChildrenDialog() {
        _uiState.value = _uiState.value.copy(
            showDeleteChildrenDialog = false
        )
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

    private fun validateForm(name: String): Boolean {
        return name.isNotBlank() && name.length <= 50
    }

    fun saveChanges(onSaved: () -> Unit = {}) {
        if (!_uiState.value.hasChanges || !_uiState.value.isValid) return

        val currentState = _uiState.value

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

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, saved = false)
            try {
                val updatedHabit = originalHabit?.copy(
                    name = _uiState.value.name,
                    description = _uiState.value.description,
                    habitType = _uiState.value.habitType,
                    iconResId = _uiState.value.iconResId,
                    colorHex = _uiState.value.colorHex,
                    schedule = finalSchedule,
                    targetValue = _uiState.value.targetValue ?: 1,
                    isCountdown = _uiState.value.isCountdown,
                    parentHabitId = currentState.selectedParentUuid,
                    targetCycles = _uiState.value.targetCycles,
                    failMode = _uiState.value.failMode,
                    bestTime = _uiState.value.bestTime
                )
                if (updatedHabit != null) {
                    habitRepository.updateHabit(updatedHabit, context)
                    originalHabit = updatedHabit.copy(parentHabitId = currentState.selectedParentUuid)

                    // Handle metric link delta AFTER successful habit update (separate try-catch)
                    try {
                        val selectedIds = currentState.selectedMetricIds
                        val originalIds = currentState.originalLinkedMetricIds

                        // Delete links for metrics no longer selected
                        for (metricId in originalIds) {
                            if (metricId !in selectedIds) {
                                val link = habitMetricLinkDao.getLink(currentState.habitId, metricId)
                                if (link != null) {
                                    habitMetricLinkDao.delete(link)
                                }
                            }
                        }

                        // Insert new links for metrics added
                        for (metricId in selectedIds) {
                            if (metricId !in originalIds) {
                                val metric = metricDao.getMetricById(metricId)
                                if (metric != null) {
                                    val link = HabitMetricLinkEntity(
                                        habitId = currentState.habitId,
                                        habitUuid = updatedHabit.uuid,
                                        metricId = metricId,
                                        metricUuid = metric.uuid,
                                        coefficient = 1.0,
                                        showInHabitDetail = true,
                                        promptOnComplete = true
                                    )
                                    habitMetricLinkDao.insertOrIgnore(link)
                                }
                            }
                        }

                        // Update originalLinkedMetricIds to reflect saved state
                        _uiState.value = _uiState.value.copy(originalLinkedMetricIds = selectedIds)
                    } catch (e: Exception) {
                        Log.w("EditHabitViewModel", "Failed to update metric links", e)
                    }

                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        hasChanges = false,
                        saved = true,
                        parentHabitUuid = currentState.selectedParentUuid
                    )
                    onSaved()
                } else {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        saved = false
                    )
                }
            } catch (e: SQLiteConstraintException) {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        showDuplicateDialog = true,
                        errorMessage = context.getString(R.string.dialog_duplicate_habit_message)
                    )
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(isSaving = false, saved = false)
                }
        }
    }

    fun dismissDefaultTargetDialog() {
        _uiState.value = _uiState.value.copy(showDefaultTargetDialog = false)
    }

    fun confirmDefaultTarget(onSaved: () -> Unit = {}) {
        _uiState.value = _uiState.value.copy(
            targetValue = 1,
            showDefaultTargetDialog = false,
            hasChanges = true
        )
        // Continue saving
        saveChanges(onSaved)
    }

    fun dismissDefaultScheduleDialog() {
        _uiState.value = _uiState.value.copy(showDefaultScheduleDialog = false)
    }

    fun confirmDefaultSchedule(onSaved: () -> Unit = {}) {
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
        _uiState.value = updatedState
        // Continue saving
        saveChanges(onSaved)
    }

    fun dismissDuplicateDialog() {
        _uiState.value = _uiState.value.copy(
            showDuplicateDialog = false,
            errorMessage = null
        )
    }

    // Parent habit management
    fun updateParentHabit(habit: HabitEntity?) {
        val newParentUuid = habit?.uuid
        val newParentName = habit?.name
        val hasParentChange = newParentUuid != originalHabit?.parentHabitId
        _uiState.value = _uiState.value.copy(
            selectedParentUuid = newParentUuid,
            parentHabitName = newParentName,
            hasChanges = hasParentChange || hasOtherChanges(),
            showParentSelector = false
        )
    }

    fun clearParentHabit() {
        val hasParentChange = null != originalHabit?.parentHabitId
        _uiState.value = _uiState.value.copy(
            selectedParentUuid = null,
            parentHabitName = null,
            hasChanges = hasParentChange || hasOtherChanges(),
            showParentSelector = false
        )
    }

    fun toggleParentSelector() {
        _uiState.value = _uiState.value.copy(
            showParentSelector = !_uiState.value.showParentSelector
        )
    }

    private fun hasOtherChanges(): Boolean {
        val current = _uiState.value
        val original = originalHabit ?: return false
        return current.name != original.name ||
                current.description != original.description ||
                current.iconResId != original.iconResId ||
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
            is HabitSchedule.Daily -> 1
            is HabitSchedule.Weekly -> 7
            is HabitSchedule.Monthly -> 30
            is HabitSchedule.Custom -> schedule.frequencyDays
        }
    }
}
