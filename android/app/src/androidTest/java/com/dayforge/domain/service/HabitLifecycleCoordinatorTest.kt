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
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
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
    fun goal_request_exposes_habit_progress_and_target() {
        coordinator.showGoalCompletion(habit(targetCycles = 3), progress = 2)

        assertTrue(coordinator.showGoalDialog.value)
        assertEquals(7L, coordinator.goalHabitId.value)
        assertEquals(2, coordinator.goalProgress.value)
        assertEquals(3, coordinator.goalTarget.value)
    }

    @Test
    fun goal_visibility_is_published_after_its_payload() = runTest {
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
    fun goal_request_ignores_habits_without_a_cycle_target() {
        coordinator.showGoalCompletion(habit(targetCycles = null), progress = 2)

        assertFalse(coordinator.showGoalDialog.value)
        assertNull(coordinator.goalHabitId.value)
    }

    @Test
    fun confirm_goal_deactivates_habit_and_closes_dialog() = runTest {
        coordinator.showGoalCompletion(habit(targetCycles = 1), progress = 1)
        coJustRun { habitRepository.updateIsActive(7L, false, context) }

        val confirmed = coordinator.confirmGoalCompletion()

        assertTrue(confirmed)
        assertFalse(coordinator.showGoalDialog.value)
        coVerify(exactly = 1) { habitRepository.updateIsActive(7L, false, context) }
    }

    @Test
    fun dismiss_strict_goal_switches_to_loose_and_closes_dialog() = runTest {
        val habit = habit(targetCycles = 1, failMode = FailMode.STRICT)
        coordinator.showGoalCompletion(habit, progress = 1)
        coJustRun { habitRepository.updateFailMode(7L, FailMode.LOOSE, context) }

        coordinator.dismissGoalDialog(habit)

        assertFalse(coordinator.showGoalDialog.value)
        coVerify(exactly = 1) { habitRepository.updateFailMode(7L, FailMode.LOOSE, context) }
    }

    @Test
    fun reactivation_success_clears_dialog_state() = runTest {
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
    fun reactivation_visibility_is_published_after_its_payload() = runTest {
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
    fun reactivation_failure_is_reported_and_clears_dialog_state() = runTest {
        val habit = habit(targetCycles = 1)
        coordinator.showReactivationDialog(habit)
        coEvery { habitRepository.clearHabitHistory(habit, context) } throws
            IllegalStateException("failed")

        val result = coordinator.confirmReactivation(habit)

        assertEquals(ReactivationResult.FAILURE, result)
        assertFalse(coordinator.showReactivationDialog.value)
        assertNull(coordinator.reactivationHabitId.value)
    }

    @Test
    fun failedGoalCommitKeepsDialogVisibleForRetry() = runTest {
        coordinator.showGoalCompletion(habit(targetCycles = 3), progress = 3)
        val failure = java.io.IOException("goal update failed")
        coEvery { habitRepository.updateIsActive(7L, false, context) } throws failure
        assertEquals(failure, runCatching { coordinator.confirmGoalCompletion() }.exceptionOrNull())
        assertTrue(coordinator.showGoalDialog.value)
        assertEquals(7L, coordinator.goalHabitId.value)
        coJustRun { habitRepository.updateIsActive(7L, false, context) }
        assertTrue(coordinator.confirmGoalCompletion())
        assertFalse(coordinator.showGoalDialog.value)
    }

    @Test
    fun reactivationCancellationPropagatesAndPreservesPendingRequest() = runTest {
        val habit = habit(targetCycles = 1)
        coordinator.showReactivationDialog(habit)
        val cancellation = kotlinx.coroutines.CancellationException("cancelled")
        coEvery { habitRepository.clearHabitHistory(habit, context) } throws cancellation
        assertEquals(cancellation, runCatching { coordinator.confirmReactivation(habit) }.exceptionOrNull())
        assertTrue(coordinator.showReactivationDialog.value)
        assertEquals(7L, coordinator.reactivationHabitId.value)
        assertEquals("Target habit", coordinator.reactivationHabitName.value)
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
