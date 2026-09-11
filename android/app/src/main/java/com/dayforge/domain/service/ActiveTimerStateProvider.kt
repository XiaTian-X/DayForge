package com.dayforge.domain.service

import android.content.Context
import android.os.SystemClock
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.repository.HabitRepository
import com.dayforge.ui.screens.dashboard.ActiveTimerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * Shared active timer state provider extracted from DashboardViewModel and NestedViewModel.
 * Provides a StateFlow that updates every second for running timers.
 */
class ActiveTimerStateProvider(
    private val timeLogDao: TimeLogDao,
    private val habitRepository: HabitRepository,
    private val viewModelScope: kotlinx.coroutines.CoroutineScope,
    private val context: Context
) {
    /**
     * Active timer state for real-time UI updates.
     * Uses a ticker flow to emit updates every second for running timers.
     * Combines active TimeLogEntity with habit data to provide target minutes.
     */
    val activeTimerState: StateFlow<ActiveTimerState?> = combine(
        timeLogDao.getActiveTimeLogFlow(),
        habitRepository.allHabits,
        // Ticker flow to trigger updates every second for running timers
        flow {
            while (true) {
                emit(SystemClock.elapsedRealtime())
                delay(1000)
            }
        }
    ) { activeLog, habits, _ ->
        if (activeLog == null) {
            null
        } else {
            // Find the habit to get targetMinutes
            val habit = habits.find { it.id == activeLog.habitId }
            val targetMinutes = habit?.targetValue ?: 0

            // Calculate elapsed seconds based on pause state
            // startTime is stored as System.currentTimeMillis() (epoch millis)
            val elapsedSeconds = TimerElapsedCalculator.elapsedSeconds(activeLog, context)

            ActiveTimerState(
                habitId = activeLog.habitId,
                elapsedSeconds = elapsedSeconds,
                isPaused = activeLog.isPaused,
                targetMinutes = targetMinutes
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,  // Changed from WhileSubscribed to ensure flow stays active
        initialValue = null
    )
}
