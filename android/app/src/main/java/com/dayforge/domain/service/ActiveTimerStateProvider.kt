package com.dayforge.domain.service

import android.content.Context
import com.dayforge.data.local.DatabaseEpochValue
import com.dayforge.data.local.DatabaseRuntimeHost
import kotlinx.coroutines.flow.Flow
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * Shared active timer state provider extracted from DashboardViewModel and NestedViewModel.
 * Provides a StateFlow that updates every second for running timers.
 */
class ActiveTimerStateProvider private constructor(
    private val fixedTimeLogDao: TimeLogDao?,
    private val fixedHabitRepository: HabitRepository?,
    private val context: Context,
    private val timerWriter: com.dayforge.data.repository.NextTimerWriter?,
    private val databases: DatabaseRuntimeHost?
) {
    constructor(timeLogDao: TimeLogDao, habitRepository: HabitRepository, context: Context,
        timerWriter: com.dayforge.data.repository.NextTimerWriter? = null) :
        this(timeLogDao, habitRepository, context, timerWriter, null)
    @Inject internal constructor(@ApplicationContext context: Context, databases: DatabaseRuntimeHost) :
        this(null, null, context, null, databases)

    internal fun observeFrames(): Flow<DatabaseEpochValue<ActiveTimerState?>> = requireNotNull(databases).observe { graph ->
        // The source is owned by this actual graph. No old stateIn replay is tagged afresh,
        // and no external ViewModel-scope sharing job outlives the Host observation lease.
        graph.activeTimerStateProvider.source()
    }

    /**
     * Active timer state for real-time UI updates.
     * Uses a ticker flow to emit updates every second for running timers.
     * Combines active TimeLogEntity with habit data to provide target minutes.
     */
    fun observe(scope: CoroutineScope): StateFlow<ActiveTimerState?> {
        check(databases == null) { "DATABASE_EPOCH_INPUT_REQUIRED" }
        return source().stateIn(scope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 1_000, replayExpirationMillis = 0), null)
    }

    private fun source(): Flow<ActiveTimerState?> {
        val timeLogDao = requireNotNull(fixedTimeLogDao)
        val habitRepository = requireNotNull(fixedHabitRepository)
        var lastBinding: Triple<com.dayforge.data.local.entity.TimeLogEntity?, String?, com.dayforge.data.local.LocalCoreWriteAccess?>? = null
        var authority: com.dayforge.domain.model.TimerActionAuthority? = null
        var legacyClaim: com.dayforge.data.repository.LegacyWidgetTimerClaim? = null
        var policy: com.dayforge.data.api.dto.TimerStartPolicy? = null
        return combine(
        timeLogDao.getActiveTimeLogFlow(),
        habitRepository.allHabits,
        timerWriter?.accessChanges ?: flowOf(null),
        // Ticker flow to trigger updates every second for running timers
        flow {
            while (true) {
                emit(SystemClock.elapsedRealtime())
                delay(1000)
            }
        }
    ) { activeLog, habits, access, _ ->
        if (activeLog == null) {
            null
        } else {
            // Find the habit to get targetMinutes
            val habit = habits.find { it.id == activeLog.habitId }
            if (habit == null || habit.appearance != null && access == null) return@combine null
            val binding = Triple(activeLog, habit?.uuid, access)
            if (binding != lastBinding) {
                lastBinding = binding
                authority = null
                legacyClaim = null
                policy = null
                try {
                    authority = timerWriter?.capture(activeLog.habitId)
                    if (habit.appearance == null) legacyClaim = timerWriter?.captureLegacyForDisplay(habit, activeLog)
                    if (habit.appearance == null || authority?.sessionUuid == activeLog.uuid && authority?.session() == access?.session)
                        policy = timerWriter?.policy(activeLog.habitId, activeLog.uuid)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    // Retain persisted work, withhold controls, and retry on a fresh binding.
                    // Never terminate stateIn or fall back to the newly edited target.
                    Log.w("ActiveTimerState", "Timer display proof unavailable", error)
                    authority = null
                    legacyClaim = null
                    policy = null
                }
            }
            if (habit.appearance != null && (authority?.sessionUuid != activeLog.uuid ||
                authority?.session() != access?.session || policy == null)) return@combine null
            val targetMinutes = policy?.targetSeconds?.div(60) ?: (habit?.targetValue ?: 0)

            // Calculate elapsed seconds based on pause state
            // startTime is stored as System.currentTimeMillis() (epoch millis)
            val elapsedSeconds = TimerElapsedCalculator.elapsedSeconds(activeLog, context)

            ActiveTimerState(
                habitId = activeLog.habitId,
                elapsedSeconds = elapsedSeconds,
                isPaused = activeLog.isPaused,
                targetMinutes = targetMinutes,
                isCountdown = policy?.isCountdown ?: habit?.isCountdown,
                authority = authority,
                legacyClaim = legacyClaim
            )
        }
    }.onStart {
        // Re-entering a page must capture a fresh proof, not reuse a hidden page's authority.
        lastBinding = null
        authority = null
        legacyClaim = null
        policy = null
    }
    }
}
