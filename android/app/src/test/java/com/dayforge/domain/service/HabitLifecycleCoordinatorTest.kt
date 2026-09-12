package com.dayforge.domain.service

import android.content.Context
import app.cash.turbine.test
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.combine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HabitLifecycleCoordinatorTest {
    private lateinit var context: Context
    private lateinit var habitRepository: HabitRepository
    private lateinit var coordinator: HabitLifecycleCoordinator

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        habitRepository = mockk()
        coordinator = HabitLifecycleCoordinator(context, habitRepository)
    }

    @Test
    fun `goal request exposes habit progress and target`() {
        coordinator.showGoalCompletion(habit(targetCycles = 3), progress = 2)

        assertTrue(coordinator.showGoalDialog.value)
        assertEquals(7L, coordinator.goalHabitId.value)
        assertEquals(2, coordinator.goalProgress.value)
        assertEquals(3, coordinator.goalTarget.value)
    }

    @Test
    fun `goal visibility is published after its payload`() = runTest {
        combine(
            coordinator.showGoalDialog,
            coordinator.goalHabitId,
            coordinator.goalProgress,
            coordinator.goalTarget
        ) { visible, habitId, progress, target ->
            GoalDialogSnapshot(visible, habitId, progress, target)
        }.test {
            assertFalse(awaitItem().visible)

            coordinator.showGoalCompletion(habit(targetCycles = 3), progress = 2)

            val visible = awaitVisibleGoalSnapshot()
            assertEquals(7L, visible.habitId)
            assertEquals(2, visible.progress)
            assertEquals(3, visible.target)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `goal request ignores habits without a cycle target`() {
        coordinator.showGoalCompletion(habit(targetCycles = null), progress = 2)

        assertFalse(coordinator.showGoalDialog.value)
        assertNull(coordinator.goalHabitId.value)
    }

    @Test
    fun `confirm goal deactivates habit and closes dialog`() = runTest {
        coordinator.showGoalCompletion(habit(targetCycles = 1), progress = 1)
        coJustRun { habitRepository.updateIsActive(7L, false, context) }

        val confirmed = coordinator.confirmGoalCompletion()

        assertTrue(confirmed)
        assertFalse(coordinator.showGoalDialog.value)
        coVerify(exactly = 1) { habitRepository.updateIsActive(7L, false, context) }
    }

    @Test
    fun `dismiss strict goal switches to loose and closes dialog`() = runTest {
        val habit = habit(targetCycles = 1, failMode = FailMode.STRICT)
        coordinator.showGoalCompletion(habit, progress = 1)
        coJustRun { habitRepository.updateFailMode(7L, FailMode.LOOSE, context) }

        coordinator.dismissGoalDialog(habit)

        assertFalse(coordinator.showGoalDialog.value)
        coVerify(exactly = 1) { habitRepository.updateFailMode(7L, FailMode.LOOSE, context) }
    }

    @Test
    fun `reactivation success clears dialog state`() = runTest {
        val habit = habit(targetCycles = 1)
        coordinator.showReactivationDialog(habit)
        coJustRun { habitRepository.clearHabitHistory(habit, context) }

        val result = coordinator.confirmReactivation(habit)

        assertEquals(ReactivationResult.SUCCESS, result)
        assertFalse(coordinator.showReactivationDialog.value)
        assertNull(coordinator.reactivationHabitId.value)
        assertEquals("", coordinator.reactivationHabitName.value)
    }

    @Test
    fun `reactivation visibility is published after its payload`() = runTest {
        combine(
            coordinator.showReactivationDialog,
            coordinator.reactivationHabitId,
            coordinator.reactivationHabitName
        ) { visible, habitId, habitName ->
            ReactivationDialogSnapshot(visible, habitId, habitName)
        }.test {
            assertFalse(awaitItem().visible)

            coordinator.showReactivationDialog(habit(targetCycles = 1))

            val visible = awaitVisibleReactivationSnapshot()
            assertEquals(7L, visible.habitId)
            assertEquals("Target habit", visible.habitName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reactivation failure is reported and clears dialog state`() = runTest {
        val habit = habit(targetCycles = 1)
        coordinator.showReactivationDialog(habit)
        coEvery { habitRepository.clearHabitHistory(habit, context) } throws
            IllegalStateException("failed")

        val result = coordinator.confirmReactivation(habit)

        assertEquals(ReactivationResult.FAILURE, result)
        assertFalse(coordinator.showReactivationDialog.value)
        assertNull(coordinator.reactivationHabitId.value)
    }

    private fun habit(targetCycles: Int?, failMode: FailMode = FailMode.STRICT) = HabitEntity(
        id = 7L,
        name = "Target habit",
        habitType = HabitType.CHECK_IN,
        iconResId = 1,
        colorHex = "#2196F3",
        schedule = HabitSchedule.Daily,
        targetValue = 1,
        targetCycles = targetCycles,
        failMode = failMode
    )

    private suspend fun app.cash.turbine.ReceiveTurbine<GoalDialogSnapshot>.awaitVisibleGoalSnapshot():
        GoalDialogSnapshot {
        while (true) {
            val snapshot = awaitItem()
            if (snapshot.visible) return snapshot
        }
    }

    private suspend fun app.cash.turbine.ReceiveTurbine<ReactivationDialogSnapshot>
        .awaitVisibleReactivationSnapshot(): ReactivationDialogSnapshot {
        while (true) {
            val snapshot = awaitItem()
            if (snapshot.visible) return snapshot
        }
    }

    private data class GoalDialogSnapshot(
        val visible: Boolean,
        val habitId: Long?,
        val progress: Int,
        val target: Int
    )

    private data class ReactivationDialogSnapshot(
        val visible: Boolean,
        val habitId: Long?,
        val habitName: String
    )
}
