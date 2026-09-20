package com.dayforge.ui.screens.dashboard

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import app.cash.turbine.test
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import com.dayforge.domain.model.FilterMode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@RunWith(AndroidJUnit4::class)
class DashboardTimeWindowTickerTest {
    @Test
    fun nonTimeWindowMode_emitsOnceWithoutReadingHabits() = runTest {
        val repository = mockk<HabitRepository>()
        val preferencesManager = mockk<PreferencesManager> {
            every { filterMode } returns flowOf(FilterMode.ALL.value)
        }

        DashboardTimeWindowTicker(repository, preferencesManager).observe().first()

        verify(exactly = 0) { repository.allHabits }
    }

    @Test
    fun switchingToTimeWindowMode_emitsImmediatelyAndReadsLatestHabits() = runTest {
        val filterModeFlow = MutableStateFlow(FilterMode.ALL.value)
        val repository = mockk<HabitRepository> {
            every { allHabits } returns flowOf(emptyList())
        }
        val preferencesManager = mockk<PreferencesManager> {
            every { filterMode } returns filterModeFlow
        }
        val ticker = DashboardTimeWindowTicker(repository, preferencesManager)

        ticker.observe().test {
            awaitItem()

            filterModeFlow.value = FilterMode.TIME_WINDOW.value
            awaitItem()

            verify(exactly = 1) { repository.allHabits }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun refreshDelay_usesNearestFutureBoundaryAndAccountsForSeconds() {
        val currentTime = time(hour = 9, minute = 0, second = 30)
        val habit = habit(id = 1, bestTime = 10 * 60L)

        val delay = DashboardTimeWindowRefreshCalculator.calculateDelayMillis(
            listOf(habit),
            currentTime
        )

        assertEquals(2_670_000L, delay)
    }

    @Test
    fun refreshDelay_clampsVeryNearBoundaryToMinimum() {
        val currentTime = time(hour = 8, minute = 44, second = 55)
        val countingHabit = habit(
            id = 1,
            type = HabitType.COUNTING,
            targetValue = 2,
            bestTime = 9 * 60L
        )

        val delay = DashboardTimeWindowRefreshCalculator.calculateDelayMillis(
            listOf(countingHabit),
            currentTime
        )

        assertEquals(10_000L, delay)
    }

    @Test
    fun refreshDelay_clampsDistantBoundaryToHourlyRecheck() {
        val currentTime = time(hour = 8)
        val habit = habit(id = 1, bestTime = 13 * 60L)

        val delay = DashboardTimeWindowRefreshCalculator.calculateDelayMillis(
            listOf(habit),
            currentTime
        )

        assertEquals(3_600_000L, delay)
    }

    @Test
    fun refreshDelay_ignoresInactiveGoalAndUnscheduledHabits() {
        val habits = listOf(
            habit(id = 1, isActive = false, bestTime = 10 * 60L),
            habit(id = 2, type = HabitType.GOAL, bestTime = 10 * 60L),
            habit(id = 3, bestTime = null)
        )

        val delay = DashboardTimeWindowRefreshCalculator.calculateDelayMillis(
            habits,
            time(hour = 9)
        )

        assertEquals(3_600_000L, delay)
    }

    private fun habit(
        id: Long,
        type: HabitType = HabitType.CHECK_IN,
        isActive: Boolean = true,
        targetValue: Int = 1,
        bestTime: Long?
    ) = HabitEntity(
        id = id,
        name = "Habit $id",
        habitType = type,
        iconResId = 1,
        colorHex = "#2196F3",
        schedule = HabitSchedule.Daily,
        isActive = isActive,
        targetValue = targetValue,
        bestTime = bestTime
    )

    private fun time(
        hour: Int,
        minute: Int = 0,
        second: Int = 0
    ) = ZonedDateTime.of(
        2026,
        1,
        1,
        hour,
        minute,
        second,
        0,
        ZoneId.of("Asia/Shanghai")
    )
}
