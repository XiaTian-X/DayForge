package com.dayforge.domain.service

import android.content.Context
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.repository.HabitRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared goal-completion and reactivation state for habit list screens. */
class HabitLifecycleCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val habitRepository: HabitRepository
) {
    private val _showGoalDialog = MutableStateFlow(false)
    val showGoalDialog: StateFlow<Boolean> = _showGoalDialog.asStateFlow()

    private val _goalHabitId = MutableStateFlow<Long?>(null)
    val goalHabitId: StateFlow<Long?> = _goalHabitId.asStateFlow()

    private val _goalProgress = MutableStateFlow(0)
    val goalProgress: StateFlow<Int> = _goalProgress.asStateFlow()

    private val _goalTarget = MutableStateFlow(0)
    val goalTarget: StateFlow<Int> = _goalTarget.asStateFlow()

    private val _showReactivationDialog = MutableStateFlow(false)
    val showReactivationDialog: StateFlow<Boolean> = _showReactivationDialog.asStateFlow()

    private val _reactivationHabitId = MutableStateFlow<Long?>(null)
    val reactivationHabitId: StateFlow<Long?> = _reactivationHabitId.asStateFlow()

    private val _reactivationHabitName = MutableStateFlow("")
    val reactivationHabitName: StateFlow<String> = _reactivationHabitName.asStateFlow()

    fun showGoalCompletion(habit: HabitEntity?, progress: Int) {
        val target = habit?.targetCycles ?: return
        _goalHabitId.value = habit.id
        _goalProgress.value = progress
        _goalTarget.value = target
        // Visibility is the commit signal consumed by the UI. Publish it only
        // after the dialog payload is complete.
        _showGoalDialog.value = true
    }

    suspend fun confirmGoalCompletion(): Boolean {
        val habitId = _goalHabitId.value ?: return false
        habitRepository.updateIsActive(habitId, false, context)
        _showGoalDialog.value = false
        return true
    }

    suspend fun dismissGoalDialog(habit: HabitEntity?) {
        val habitId = _goalHabitId.value ?: return
        if (habit?.failMode == FailMode.STRICT) {
            habitRepository.updateFailMode(habitId, FailMode.LOOSE, context)
        }
        _showGoalDialog.value = false
    }

    fun showReactivationDialog(habit: HabitEntity?) {
        habit ?: return
        _reactivationHabitId.value = habit.id
        _reactivationHabitName.value = habit.name
        // Keep the visible state from exposing an empty/stale payload.
        _showReactivationDialog.value = true
    }

    suspend fun confirmReactivation(habit: HabitEntity?): ReactivationResult {
        if (_reactivationHabitId.value == null) return ReactivationResult.NO_HABIT
        val result = if (habit == null) {
            ReactivationResult.NO_HABIT
        } else {
            try {
                habitRepository.clearHabitHistory(habit, context)
                ReactivationResult.SUCCESS
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                ReactivationResult.FAILURE
            }
        }
        clearReactivationDialog()
        return result
    }

    fun dismissReactivationDialog() {
        clearReactivationDialog()
    }

    private fun clearReactivationDialog() {
        _showReactivationDialog.value = false
        _reactivationHabitId.value = null
        _reactivationHabitName.value = ""
    }
}

enum class ReactivationResult {
    SUCCESS,
    FAILURE,
    NO_HABIT
}
