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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    val errorMessage: String? = null
)

@HiltViewModel
class EditGoalViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val habitDao: HabitDao,
    private val habitRepository: HabitRepository,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditGoalUiState())
    val uiState: StateFlow<EditGoalUiState> = _uiState.asStateFlow()

    /**
     * Load the goal (parent habit) and its children.
     */
    fun loadGoal(goalId: Long) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            val goal = habitDao.getHabitById(goalId)
            if (goal == null) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = context.getString(R.string.goal_not_found)
                )
                return@launch
            }

            val children = goal.uuid?.let { uuid ->
                habitDao.getChildrenByParentUuid(uuid).first()
            } ?: emptyList()

            _uiState.value = _uiState.value.copy(
                goalId = goal.id,
                goalUuid = goal.uuid,
                name = goal.name,
                description = goal.description,
                iconResId = goal.iconResId,
                colorHex = goal.colorHex,
                targetCycles = goal.targetCycles,
                failMode = goal.failMode,
                isActive = goal.isActive,
                children = children,
                isLoading = false
            )
        }
    }

    fun updateName(name: String) {
        _uiState.value = _uiState.value.copy(name = name)
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

    fun toggleDeleteDialog() {
        _uiState.value = _uiState.value.copy(showDeleteDialog = !_uiState.value.showDeleteDialog)
    }

    /**
     * Refresh children list after a child habit is added/edited/deleted.
     */
    fun refreshChildren() {
        val goalUuid = _uiState.value.goalUuid ?: return
        viewModelScope.launch {
            val children = habitDao.getChildrenByParentUuid(goalUuid).first()
            _uiState.value = _uiState.value.copy(children = children)
        }
    }

    /**
     * Save the goal (parent habit) changes.
     */
    fun saveGoal() {
        val currentState = _uiState.value
        val goalId = currentState.goalId ?: return

        _uiState.value = currentState.copy(isSaving = true)

        viewModelScope.launch {
            try {
                val existingGoal = habitDao.getHabitById(goalId) ?: return@launch
                val updatedGoal = existingGoal.copy(
                    name = currentState.name,
                    description = currentState.description,
                    iconResId = currentState.iconResId,
                    colorHex = currentState.colorHex,
                    targetCycles = currentState.targetCycles,
                    failMode = currentState.failMode,
                    updatedAt = System.currentTimeMillis()
                )
                habitRepository.updateHabit(updatedGoal, context)

                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    saved = true,
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

    /**
     * Delete the goal (parent habit).
     * Note: This will also affect child habits (they will become top-level habits).
     */
    fun deleteGoal() {
        val goalId = _uiState.value.goalId ?: return

        _uiState.value = _uiState.value.copy(isDeleting = true)

        viewModelScope.launch {
            try {
                val goal = habitDao.getHabitById(goalId) ?: return@launch
                val children = habitRepository.getHabitChildren(goal.uuid)
                if (children.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isDeleting = false,
                        showDeleteChildrenDialog = true,
                        pendingDeleteChildrenCount = children.size
                    )
                } else {
                    habitRepository.deleteHabit(goal, context)
                    _uiState.value = _uiState.value.copy(
                        isDeleting = false,
                        showDeleteDialog = false,
                        saved = true  // Navigate back after deletion
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isDeleting = false,
                    errorMessage = context.getString(R.string.goal_delete_failed, e.message)
                )
            }
        }
    }

    fun deleteGoalWithChildren() {
        val goalId = _uiState.value.goalId ?: return

        viewModelScope.launch {
            try {
                val goal = habitDao.getHabitById(goalId) ?: return@launch
                habitRepository.deleteHabitWithChildren(goal, context)
                _uiState.value = _uiState.value.copy(
                    showDeleteChildrenDialog = false,
                    saved = true
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    showDeleteChildrenDialog = false,
                    errorMessage = context.getString(R.string.goal_delete_failed, e.message)
                )
            }
        }
    }

    fun deleteGoalKeepChildren() {
        val goalId = _uiState.value.goalId ?: return

        viewModelScope.launch {
            try {
                val goal = habitDao.getHabitById(goalId) ?: return@launch
                habitRepository.deleteHabitOrphanChildren(goal, context)
                _uiState.value = _uiState.value.copy(
                    showDeleteChildrenDialog = false,
                    saved = true
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    showDeleteChildrenDialog = false,
                    errorMessage = context.getString(R.string.goal_delete_failed, e.message)
                )
            }
        }
    }

    fun dismissDeleteChildrenDialog() {
        _uiState.value = _uiState.value.copy(showDeleteChildrenDialog = false)
    }

    fun clearSaved() {
        _uiState.value = _uiState.value.copy(saved = false)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}
