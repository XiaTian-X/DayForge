package com.dayforge.domain.service

import android.content.Context
import android.os.SystemClock
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.repository.HabitRepository
import com.dayforge.domain.model.ActiveTimerState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Shared active timer state provider extracted from DashboardViewModel and NestedViewModel.
 * Provides a StateFlow that updates every second for running timers.
 */
class ActiveTimerStateProvider @Inject constructor(
    private val timeLogDao: TimeLogDao,
    private val habitRepository: HabitRepository,
    @param:ApplicationContext private val context: Context
) {
    /**
     * Active timer state for real-time UI updates.
     * Uses a ticker flow to emit updates every second for running timers.
     * Combines active TimeLogEntity with habit data to provide target minutes.
     */
    fun observe(scope: CoroutineScope): StateFlow<ActiveTimerState?> = combine(
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
        scope = scope,
        started = SharingStarted.Lazily,  // Changed from WhileSubscribed to ensure flow stays active
        initialValue = null
    )
}
