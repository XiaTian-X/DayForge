package com.dayforge.ui.metrics

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import android.content.Context
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.repository.HabitRepository
import com.dayforge.data.repository.MetricRepository
import com.dayforge.data.repository.NextTimerWriter
import com.dayforge.domain.model.TimerActionAuthority
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertNull

@RunWith(AndroidJUnit4::class)
class LinkedMetricCoordinatorTest {
    private lateinit var habitRepository: HabitRepository
    private lateinit var metricRepository: MetricRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var coordinator: LinkedMetricCoordinator

    @Before
    fun setUp() {
        habitRepository = mockk()
        metricRepository = mockk()
        preferencesManager = mockk()
        every { preferencesManager.pendingMetricHabits } returns flowOf(emptySet())
        coordinator = LinkedMetricCoordinator(
            context = mockk<Context>(relaxed = true),
            preferencesManager = preferencesManager,
            metricRepository = metricRepository,
            habitRepository = habitRepository
        )
    }

    @Test
    fun timer_stop_prompt_reloads_habit_after_service_persistence_delay() = runTest {
        val habit = mockk<HabitEntity> {
            every { name } returns "Timer"
            every { completionPolicy } returns null
        }
        coEvery { habitRepository.getHabitById(7L) } returns habit
        every { preferencesManager.getNeverAskAgain(7L) } returns flowOf(false)
        coEvery { metricRepository.getLinkedMetricSnapshots(7L) } returns emptyList()

        coordinator.showPromptAfterTimerStop(7L)

        coVerify(exactly = 1) { habitRepository.getHabitById(7L) }
        coVerify(exactly = 1) { metricRepository.getLinkedMetricSnapshots(7L) }
    }

    @Test
    fun timer_stop_prompt_ignores_unaccepted_stop() = runTest {
        coordinator.showPromptAfterTimerStop(null)

        coVerify(exactly = 0) { habitRepository.getHabitById(any()) }
        coVerify(exactly = 0) { metricRepository.getLinkedMetricSnapshots(any()) }
    }

    @Test
    fun typed_timer_prompt_does_not_read_metrics_before_confirmed_owned_completion() = runTest {
        val writer = mockk<NextTimerWriter>()
        val ticket = TimerActionAuthority("aa310000-0000-4000-8000-000000000001",
            "aa310000-0000-4000-8000-000000000002", null, null, null,
            "aa310000-0000-4000-8000-000000000003", "aa310000-0000-4000-8000-000000000004", 2, null)
        coordinator = LinkedMetricCoordinator(mockk<Context>(relaxed = true), preferencesManager,
            metricRepository, habitRepository, timerWriter = writer)
        coEvery { writer.afterCompletion(7L, ticket, any()) } returns Unit
        coordinator.showPromptAfterTimerStop(7L, ticket)
        coVerify(exactly = 1) { writer.afterCompletion(7L, ticket, any()) }
        coVerify(exactly = 0) { habitRepository.getHabitById(any()) }
        coVerify(exactly = 0) { metricRepository.getLinkedMetricSnapshots(any()) }
        assertNull(coordinator.postCheckInState.value)
    }
}
