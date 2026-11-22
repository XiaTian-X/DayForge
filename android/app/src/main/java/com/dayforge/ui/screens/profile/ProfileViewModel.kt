package com.dayforge.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.data.local.DatabaseRuntimeHost
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.displayState
import com.dayforge.data.repository.HabitRepository
import com.dayforge.data.repository.OverviewReadRepository
import com.dayforge.domain.service.HabitStatusCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Original profile calculation, with one captured graph for each complete observation lifetime. */
@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProfileViewModel private constructor(
    private val fixed: Fixed?, private val databases: DatabaseRuntimeHost?,
    private val preferencesManager: PreferencesManager
) : ViewModel() {
    private class Fixed(val habits: HabitRepository, timers: TimeLogDao, val status: HabitStatusCalculator) {
        val reads = OverviewReadRepository(timers)
    }
    constructor(habitRepository: HabitRepository, timeLogDao: TimeLogDao,
        habitStatusCalculator: HabitStatusCalculator, preferencesManager: PreferencesManager) :
        this(Fixed(habitRepository, timeLogDao, habitStatusCalculator), null, preferencesManager)
    @Inject internal constructor(databases: DatabaseRuntimeHost, preferencesManager: PreferencesManager) :
        this(null, databases, preferencesManager)
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()
    private val readMonitor = com.dayforge.ui.DataReadMonitor("ProfileViewModel")
    val readError = readMonitor.error
    fun retryRead() = readMonitor.retry()

    private fun progress(habitRepository: HabitRepository, reads: OverviewReadRepository,
        habitStatusCalculator: HabitStatusCalculator) = readMonitor.recover(0 to 0) { combine(
        habitRepository.allHabits,
        combine(habitRepository.getAllCompletions(), habitRepository.countChanges,
            habitRepository.oneTimeChanges,
            preferencesManager.dateChangeTrigger) { rows, _, _, _ -> rows },
        reads.activeTimerChanges()
    ) { habits, completions, _ -> habits to completions }
        .mapLatest { (habits, completions) ->
            val stats = readMonitor.read(emptyList()) { habits.map { habit ->
                habitStatusCalculator.calculate(habit, completions, null)
            } }
            val eligibleStats = stats.filter { it.shouldCountToday }
            Pair(eligibleStats.count { it.completedToday }, eligibleStats.size)
        } }

    val todayProgress: StateFlow<Pair<Int, Int>> = if (databases == null) requireNotNull(fixed).let {
        progress(it.habits, it.reads, it.status).onEach { _isInitialized.value = true }
            .stateIn(viewModelScope, com.dayforge.ui.VisibleScreenSharing, 0 to 0)
    } else databases.displayState(viewModelScope, com.dayforge.ui.VisibleScreenSharing, 0 to 0,
        onInitialized = { _isInitialized.value = it }) { graph ->
        progress(graph.habitRepository, graph.overviewReadRepository, graph.habitStatusCalculator)
    }
}
