package com.dayforge.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.repository.HabitRepository
import com.dayforge.domain.service.HabitStatusCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * ViewModel for ProfileScreen.
 * Provides today's habit completion progress.
 *
 * Per PROFILE-01: Shows progress display moved from Dashboard TopAppBar.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val habitRepository: HabitRepository,
    private val timeLogDao: TimeLogDao,
    private val habitStatusCalculator: HabitStatusCalculator
) : ViewModel() {

    // Track if data has been loaded at least once
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    /**
     * Today's progress: Pair of (completed count, total count).
     * Uses HabitStatusCalculator for consistent status calculation.
     * Filters out non-check-in days, failed habits, and goal-completed habits.
     */
    val todayProgress: StateFlow<Pair<Int, Int>> = combine(
        habitRepository.allHabits,
        habitRepository.getAllCompletions(),
        timeLogDao.getActiveTimeLogFlow()
    ) { habits, completions, _ ->
        // Calculate status for each habit
        val stats = habits.map { habit ->
            habitStatusCalculator.calculate(habit, completions, null)
        }

        // Filter eligible habits (check-in day + not failed + not goal-completed)
        val eligibleStats = stats.filter { it.shouldCountToday }

        // Count completed
        val completed = eligibleStats.count { it.completedToday }
        Pair(completed, eligibleStats.size)
    }
        .onEach { _isInitialized.value = true }
        .stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = Pair(0, 0)
    )
}