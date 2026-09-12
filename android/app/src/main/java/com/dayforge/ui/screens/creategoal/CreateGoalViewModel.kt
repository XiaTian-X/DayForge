package com.dayforge.ui.screens.creategoal

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.R
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.entity.HabitEntity
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * UI State for CreateGoalScreen.
 */
data class CreateGoalUiState(
    // Parent habit (goal) fields
    val name: String = "",
    val description: String = "",
    val iconResId: Int = 0,
    val colorHex: String = "#2196F3",
    // GOAL type is always Daily schedule, no need to configure
    val targetCycles: Int? = null,
    val failMode: FailMode = FailMode.STRICT,

    // Pre-generated UUID for parent habit
    val parentUuid: String = UUID.randomUUID().toString(),

    // Child habits (key results) - stored as temporary data
    val children: List<ChildHabitDraft> = emptyList(),

    // UI state
    val isValid: Boolean = false,
    val isSaving: Boolean = false,
    val savedGoalId: Long? = null,
    val showIconPicker: Boolean = false,
    val showColorPicker: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Draft data for a child habit (key result) being created.
 */
data class ChildHabitDraft(
    val id: String = UUID.randomUUID().toString(),  // Temporary ID for UI list key
    val name: String,
    val habitType: HabitType,
    val targetValue: Int = 1,
    val isCountdown: Boolean = false
)

@HiltViewModel
class CreateGoalViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val habitDao: HabitDao,
    private val habitRepository: HabitRepository,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateGoalUiState())
    val uiState: StateFlow<CreateGoalUiState> = _uiState.asStateFlow()

    /**
     * Refresh children list from database.
     * Called when returning from CreateHabitScreen to show newly created child habits.
     */
    fun refreshChildren() {
        val parentUuid = _uiState.value.parentUuid
        viewModelScope.launch {
            val children = habitDao.getChildrenByParentUuid(parentUuid).first()
            _uiState.value = _uiState.value.copy(children = children.map { habit ->
                ChildHabitDraft(
                    id = habit.uuid,
                    name = habit.name,
                    habitType = habit.habitType,
                    targetValue = habit.targetValue,
                    isCountdown = habit.isCountdown
                )
            })
        }
    }

    fun updateName(name: String) {
        _uiState.value = _uiState.value.copy(name = name)
        validateForm()
    }

    fun updateDescription(description: String) {
        _uiState.value = _uiState.value.copy(description = description)
    }

    fun updateIcon(iconResId: Int) {
        _uiState.value = _uiState.value.copy(iconResId = iconResId)
    }

    fun updateColor(colorHex: String) {
        _uiState.value = _uiState.value.copy(colorHex = colorHex)
    }

    fun updateTargetCycles(value: Int?) {
        _uiState.value = _uiState.value.copy(targetCycles = value)
    }

    fun updateFailMode(mode: FailMode) {
        _uiState.value = _uiState.value.copy(failMode = mode)
    }

    fun toggleIconPicker() {
        _uiState.value = _uiState.value.copy(showIconPicker = !_uiState.value.showIconPicker)
    }

    fun toggleColorPicker() {
        _uiState.value = _uiState.value.copy(showColorPicker = !_uiState.value.showColorPicker)
    }

    /**
     * Add a child habit (key result) to the draft list.
     */
    fun addChildHabit(draft: ChildHabitDraft) {
        val currentChildren = _uiState.value.children
        _uiState.value = _uiState.value.copy(children = currentChildren + draft)
    }

    /**
     * Remove a child habit from the draft list.
     */
    fun removeChildHabit(draftId: String) {
        val currentChildren = _uiState.value.children
        _uiState.value = _uiState.value.copy(children = currentChildren.filter { it.id != draftId })
    }

    /**
     * Clear a child habit after it's been saved.
     */
    fun clearChildHabit(draftId: String) {
        val currentChildren = _uiState.value.children
        _uiState.value = _uiState.value.copy(children = currentChildren.filter { it.id != draftId })
    }

    /**
     * Save the goal (parent habit) and all key results (child habits).
     */
    fun saveGoal() {
        if (!_uiState.value.isValid || _uiState.value.isSaving) return

        val currentState = _uiState.value
        _uiState.value = currentState.copy(isSaving = true)

        viewModelScope.launch {
            try {
                // Save parent habit with pre-generated UUID
                val parentHabitId = habitRepository.createHabit(
                    name = currentState.name,
                    description = currentState.description,
                    habitType = HabitType.GOAL,  // Goals are always GOAL type
                    iconResId = currentState.iconResId,
                    colorHex = currentState.colorHex,
                    schedule = HabitSchedule.Daily,  // GOAL type is always Daily
                    targetCycles = currentState.targetCycles,
                    failMode = currentState.failMode,
                    predefinedUuid = currentState.parentUuid,
                    context = context
                )

                // Child habits are already saved via CreateHabitScreen with parentUuid
                // They were created with parentHabitId = currentState.parentUuid

                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    savedGoalId = parentHabitId,
                    showIconPicker = false,
                    showColorPicker = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = context.getString(R.string.toast_save_failed, e.message)
                )
            }
        }
    }

    fun clearSavedGoal() {
        _uiState.value = _uiState.value.copy(savedGoalId = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private fun validateForm() {
        val state = _uiState.value
        val isValid = state.name.isNotBlank() && state.name.length <= 50
        _uiState.value = state.copy(isValid = isValid)
    }
}