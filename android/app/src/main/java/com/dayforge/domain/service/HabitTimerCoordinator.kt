package com.dayforge.domain.service

import com.dayforge.domain.model.ActiveTimerState
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** Coordinates shared timer controls, process recovery, and observable timer state. */
class HabitTimerCoordinator @Inject constructor(
    private val timerManager: TimerManager,
    private val activeTimerStateProvider: ActiveTimerStateProvider
) {
    fun observeActiveTimer(scope: CoroutineScope): StateFlow<ActiveTimerState?> =
        activeTimerStateProvider.observe(scope)

    suspend fun recoverRunningTimer() {
        timerManager.recoverRunningTimer()
    }

    suspend fun startTimer(habitId: Long, targetMinutes: Int) {
        timerManager.startTimer(habitId, targetMinutes)
    }

    fun pauseTimer(activeTimer: ActiveTimerState?) {
        activeTimer ?: return
        timerManager.pauseTimer(activeTimer.habitId, activeTimer.targetMinutes)
    }

    fun resumeTimer(activeTimer: ActiveTimerState?) {
        activeTimer ?: return
        timerManager.resumeTimer(activeTimer.habitId, activeTimer.targetMinutes)
    }

    suspend fun stopTimer(activeTimer: ActiveTimerState?): Long? {
        activeTimer ?: return null
        return timerManager.stopTimer(
            habitId = activeTimer.habitId,
            targetMinutes = activeTimer.targetMinutes
        )
    }

    fun isHabitTimerActive(activeTimer: ActiveTimerState?, habitId: Long): Boolean =
        activeTimer?.habitId == habitId
}
