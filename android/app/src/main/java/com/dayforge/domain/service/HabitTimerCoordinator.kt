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

    internal fun observeActiveTimerFrames(): kotlinx.coroutines.flow.Flow<
        com.dayforge.data.local.DatabaseEpochValue<ActiveTimerState?>> = activeTimerStateProvider.observeFrames()

    suspend fun recoverRunningTimer() {
        timerManager.recoverRunningTimer()
    }

    suspend fun startTimer(habitId: Long, targetMinutes: Int, authority: com.dayforge.domain.model.TimerActionAuthority? = null,
        expectedHabit: com.dayforge.data.local.entity.HabitEntity? = null) {
        timerManager.startTimer(habitId, targetMinutes, authority, expectedHabit)
    }

    fun pauseTimer(activeTimer: ActiveTimerState?) {
        activeTimer ?: return
        timerManager.pauseTimer(activeTimer.habitId, activeTimer.targetMinutes, activeTimer.authority, activeTimer.legacyClaim)
    }

    fun resumeTimer(activeTimer: ActiveTimerState?) {
        activeTimer ?: return
        timerManager.resumeTimer(activeTimer.habitId, activeTimer.targetMinutes, activeTimer.authority, activeTimer.legacyClaim)
    }

    suspend fun stopTimer(activeTimer: ActiveTimerState?): Long? {
        activeTimer ?: return null
        return timerManager.stopTimer(
            habitId = activeTimer.habitId,
            targetMinutes = activeTimer.targetMinutes,
            authority = activeTimer.authority,
            legacyClaim = activeTimer.legacyClaim
        )
    }

    fun isHabitTimerActive(activeTimer: ActiveTimerState?, habitId: Long): Boolean =
        activeTimer?.habitId == habitId
}
