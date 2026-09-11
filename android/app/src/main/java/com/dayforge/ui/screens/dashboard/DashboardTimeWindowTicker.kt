package com.dayforge.ui.screens.dashboard

import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import com.dayforge.domain.model.FilterMode
import com.dayforge.domain.service.CountingSlotCalculator
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/** Emits refresh ticks only while the dashboard uses time-window filtering. */
class DashboardTimeWindowTicker @Inject constructor(
    private val habitRepository: HabitRepository,
    private val preferencesManager: PreferencesManager
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<Unit> = preferencesManager.filterMode
        .flatMapLatest { filterMode ->
            if (filterMode != FilterMode.TIME_WINDOW.value) {
                flowOf(Unit)
            } else {
                timeWindowTicks()
            }
        }

    private fun timeWindowTicks(): Flow<Unit> = flow {
        while (true) {
            val habits = habitRepository.allHabits.first()
            val currentTime = ZonedDateTime.now(ZoneId.systemDefault())
            val refreshDelay = DashboardTimeWindowRefreshCalculator.calculateDelayMillis(
                habits,
                currentTime
            )

            emit(Unit)
            delay(refreshDelay)
        }
    }
}

internal object DashboardTimeWindowRefreshCalculator {
    const val MIN_REFRESH_DELAY_MS = 10_000L
    const val MAX_REFRESH_DELAY_MS = 3_600_000L

    fun calculateDelayMillis(
        habits: List<HabitEntity>,
        currentTime: ZonedDateTime
    ): Long {
        val currentMinutes = currentTime.hour * 60 + currentTime.minute
        val nearestBoundary = habits
            .asSequence()
            .filter { habit ->
                habit.isActive && habit.bestTime != null && habit.habitType != HabitType.GOAL
            }
            .flatMap { habit -> boundaries(habit, currentTime).asSequence() }
            .filter { boundary -> boundary > currentMinutes }
            .minOrNull()
            ?: return MAX_REFRESH_DELAY_MS

        val minutesUntilBoundary = nearestBoundary - currentMinutes
        val secondsUntilBoundary = minutesUntilBoundary * 60 - currentTime.second
        return (secondsUntilBoundary * 1_000L)
            .coerceIn(MIN_REFRESH_DELAY_MS, MAX_REFRESH_DELAY_MS)
    }

    private fun boundaries(habit: HabitEntity, currentTime: ZonedDateTime): List<Int> {
        if (habit.habitType == HabitType.COUNTING) {
            return CountingSlotCalculator.calculateSlots(
                requireNotNull(habit.bestTime),
                habit.targetValue,
                currentTime
            ).asSequence()
                .filterNot { slot -> slot.isPast }
                .flatMap { slot -> sequenceOf(slot.windowStart, slot.windowEnd) }
                .toList()
        }

        val bestTime = requireNotNull(habit.bestTime).toInt()
        val halfWidth = if (habit.habitType == HabitType.TIMER) habit.targetValue else 15
        val windowEnd = bestTime + halfWidth
        return if (windowEnd > currentTime.hour * 60 + currentTime.minute) {
            listOf(bestTime - halfWidth, windowEnd)
        } else {
            emptyList()
        }
    }
}
