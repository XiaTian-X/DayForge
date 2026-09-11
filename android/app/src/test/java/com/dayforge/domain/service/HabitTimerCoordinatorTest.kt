package com.dayforge.domain.service

import com.dayforge.domain.model.ActiveTimerState
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HabitTimerCoordinatorTest {
    private lateinit var timerManager: TimerManager
    private lateinit var stateProvider: ActiveTimerStateProvider
    private lateinit var coordinator: HabitTimerCoordinator

    @Before
    fun setUp() {
        timerManager = mockk()
        stateProvider = mockk()
        coordinator = HabitTimerCoordinator(timerManager, stateProvider)
    }

    @Test
    fun `start and recovery delegate to timer manager`() = runTest {
        coJustRun { timerManager.startTimer(7L, 1) }
        coJustRun { timerManager.recoverRunningTimer() }

        coordinator.startTimer(7L, 1)
        coordinator.recoverRunningTimer()

        coVerify(exactly = 1) { timerManager.startTimer(7L, 1) }
        coVerify(exactly = 1) { timerManager.recoverRunningTimer() }
    }

    @Test
    fun `pause and resume ignore missing active timer`() {
        coordinator.pauseTimer(null)
        coordinator.resumeTimer(null)

        verify(exactly = 0) { timerManager.pauseTimer(any(), any()) }
        verify(exactly = 0) { timerManager.resumeTimer(any(), any()) }
    }

    @Test
    fun `pause and resume forward active timer details`() {
        val activeTimer = activeTimer()
        justRun { timerManager.pauseTimer(7L, 1) }
        justRun { timerManager.resumeTimer(7L, 1) }

        coordinator.pauseTimer(activeTimer)
        coordinator.resumeTimer(activeTimer)

        verify(exactly = 1) { timerManager.pauseTimer(7L, 1) }
        verify(exactly = 1) { timerManager.resumeTimer(7L, 1) }
    }

    @Test
    fun `stop returns accepted habit identity`() = runTest {
        coEvery { timerManager.stopTimer(7L, 1) } returns 7L

        val result = coordinator.stopTimer(activeTimer())

        assertEquals(7L, result)
        coVerify(exactly = 1) { timerManager.stopTimer(7L, 1) }
    }

    @Test
    fun `stop ignores missing active timer`() = runTest {
        val result = coordinator.stopTimer(null)

        assertNull(result)
        coVerify(exactly = 0) { timerManager.stopTimer(any(), any()) }
    }

    @Test
    fun `active timer match uses habit identity`() {
        assertTrue(coordinator.isHabitTimerActive(activeTimer(), 7L))
        assertFalse(coordinator.isHabitTimerActive(activeTimer(), 8L))
        assertFalse(coordinator.isHabitTimerActive(null, 7L))
    }

    private fun activeTimer() = ActiveTimerState(
        habitId = 7L,
        elapsedSeconds = 10,
        isPaused = false,
        targetMinutes = 1
    )
}
