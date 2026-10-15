package com.dayforge.domain.service

import android.content.Context
import app.cash.turbine.test
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import com.dayforge.data.repository.HabitReactivationRequest
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
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
    private lateinit var accountListener: com.dayforge.data.local.PersonalDataDisplayMemory.Listener

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        habitRepository = mockk()
        io.mockk.every { habitRepository.registerCompletionConsumer(any()) } answers {
            accountListener = firstArg(); Unit
        }
        coEvery { habitRepository.prepareReactivation(any()) } answers {
            HabitReactivationRequest(firstArg(), null)
        }
        coordinator = HabitLifecycleCoordinator(context, habitRepository)
    }

    private fun typedSnapshot(active: Boolean = true) = mockk<com.dayforge.data.repository.WidgetFactSnapshot> {
        io.mockk.every { habit } returns this@HabitLifecycleCoordinatorTest.habit(targetCycles = 1).copy(isActive = active,
            completionPolicy = "recurring", appearance = com.dayforge.domain.model.ObjectAppearance(
                com.dayforge.domain.model.IconReference.Role("habit.custom"), "#123456", "object"))
        io.mockk.every { targetProgress } returns 1
    }

    @Test fun typedGoalUsesKnownSuccessorForBothChoicesWithoutFreshNumericIdMutation() = runTest {
        coEvery { habitRepository.publishCompletion(any(), any(), any(), any()) } coAnswers { arg<suspend () -> Unit>(3).invoke() }
        for (complete in listOf(true, false)) {
            val original = typedSnapshot()
            val next = typedSnapshot(active = !complete)
            coEvery { habitRepository.applyCompletionGoal(context, original, complete) } returns next
            var followed: com.dayforge.data.repository.WidgetFactSnapshot? = null
            coordinator.showGoalCompletion(original) { source, current ->
                assertTrue(current()); followed = source
            }
            val action = coordinator.captureGoalCompletion(complete)
            assertTrue(action())
            assertTrue(followed === next)
            assertFalse(coordinator.showGoalDialog.value)
            assertFalse(action())
            coVerify(exactly = 1) { habitRepository.applyCompletionGoal(context, original, complete) }
        }
        coVerify(exactly = 0) { habitRepository.updateIsActive(any(), any(), any()) }
        coVerify(exactly = 0) { habitRepository.updateFailMode(any(), any(), any()) }
    }

    @Test fun capturedGoalButtonCannotAdoptNewDialogAndLateResultCannotClearIt() = runTest {
        coEvery { habitRepository.publishCompletion(any(), any(), any(), any()) } coAnswers { arg<suspend () -> Unit>(3).invoke() }
        val original = typedSnapshot()
        val replacement = typedSnapshot()
        coordinator.showGoalCompletion(original) { _, _ -> error("Old continuation cannot run") }
        val old = coordinator.captureGoalCompletion(true)
        coordinator.showGoalCompletion(replacement) { _, _ -> }
        assertFalse(old())
        coVerify(exactly = 0) { habitRepository.applyCompletionGoal(any(), any(), any()) }
        val current = coordinator.captureGoalCompletion(true)
        coEvery { habitRepository.applyCompletionGoal(context, replacement, true) } coAnswers {
            coordinator.showGoalCompletion(original) { _, _ -> }
            typedSnapshot(active = false)
        }
        assertFalse(current())
        assertTrue(coordinator.showGoalDialog.value)
    }

    @Test fun accountTransitionClearsAllGoalTextAndRejectsLatePublication() = runTest {
        coEvery { habitRepository.publishCompletion(any(), any(), any(), any()) } coAnswers { arg<suspend () -> Unit>(3).invoke() }
        val original = typedSnapshot()
        coordinator.showGoalCompletion(original) { _, _ -> }
        val pending = coordinator.captureGoalCompletion(true)
        accountListener.transition(true)
        accountListener.transition(false)
        assertFalse(coordinator.showGoalDialog.value)
        assertNull(coordinator.goalHabitId.value)
        assertEquals(0, coordinator.goalTarget.value)
        assertEquals(0, coordinator.goalProgress.value)
        assertFalse(pending())
        coEvery { habitRepository.publishCompletion(any(), any(), any(), any()) } coAnswers {
            accountListener.transition(true); accountListener.transition(false)
            arg<suspend () -> Unit>(3).invoke()
        }
        coordinator.showGoalCompletion(original) { _, _ -> }
        assertFalse(coordinator.showGoalDialog.value)
        assertNull(coordinator.goalHabitId.value)
    }

    @Test fun typedGoalCannotUseLegacyLongOnlyEntry() {
        val result = runCatching { coordinator.showGoalCompletion(typedSnapshot().habit, 1) }
        assertEquals("COMPLETION_FOLLOW_UP_REQUIRED", result.exceptionOrNull()?.message)
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
        coJustRun { habitRepository.reactivate(any(), context) }

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
        coEvery { habitRepository.reactivate(any(), context) } throws
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
        coEvery { habitRepository.reactivate(any(), context) } throws cancellation
        assertEquals(cancellation, runCatching { coordinator.confirmReactivation(habit) }.exceptionOrNull())
        assertTrue(coordinator.showReactivationDialog.value)
        assertEquals(7L, coordinator.reactivationHabitId.value)
        assertEquals("Target habit", coordinator.reactivationHabitName.value)
    }

    @Test
    fun confirmation_uses_the_request_captured_before_the_dialog_not_a_fresh_read() = runTest {
        val habit = habit(targetCycles = 1)
        val request = HabitReactivationRequest(habit, null)
        coEvery { habitRepository.prepareReactivation(habit) } returns request
        coJustRun { habitRepository.reactivate(request, context) }
        coordinator.showReactivationDialog(habit)
        assertEquals(ReactivationResult.SUCCESS, coordinator.confirmReactivation(habit))
        assertEquals(ReactivationResult.NO_HABIT, coordinator.confirmReactivation(habit))
        coVerify(exactly = 1) { habitRepository.prepareReactivation(habit) }
        coVerify(exactly = 1) { habitRepository.reactivate(request, context) }
        coVerify(exactly = 0) { habitRepository.clearHabitHistory(any(), any()) }
    }

    @Test
    fun a_reused_numeric_id_with_a_different_uuid_cannot_confirm_the_old_dialog() = runTest {
        val habit = habit(targetCycles = 1)
        coordinator.showReactivationDialog(habit)
        assertEquals(ReactivationResult.NO_HABIT,
            coordinator.confirmReactivation(habit.copy(uuid = java.util.UUID.randomUUID().toString())))
        coVerify(exactly = 0) { habitRepository.reactivate(any(), any()) }
        assertFalse(coordinator.showReactivationDialog.value)
    }

    @Test
    fun dismiss_during_preparation_cannot_reopen_a_late_dialog() = runTest {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { habitRepository.prepareReactivation(any()) } coAnswers {
            started.complete(Unit)
            release.await()
            HabitReactivationRequest(firstArg(), null)
        }
        val job = launch { coordinator.showReactivationDialog(habit(1)) }
        started.await()
        coordinator.dismissReactivationDialog()
        release.complete(Unit)
        job.join()
        assertFalse(coordinator.showReactivationDialog.value)
        assertNull(coordinator.reactivationHabitId.value)
    }

    @Test
    fun delayed_confirmation_cannot_retarget_or_close_a_new_dialog() = runTest {
        val first = habit(1)
        val second = first.copy(id = 8, uuid = java.util.UUID.randomUUID().toString(), name = "Other habit")
        coJustRun { habitRepository.reactivate(any(), context) }
        coordinator.showReactivationDialog(first)
        val confirmed = coordinator.captureReactivation(first)
        coordinator.showReactivationDialog(second)
        assertEquals(ReactivationResult.SUCCESS, confirmed())
        coVerify(exactly = 1) { habitRepository.reactivate(match { it.habit == first }, context) }
        coVerify(exactly = 0) { habitRepository.reactivate(match { it.habit == second }, context) }
        assertTrue(coordinator.showReactivationDialog.value)
        assertEquals(second.id, coordinator.reactivationHabitId.value)
        assertEquals(second.name, coordinator.reactivationHabitName.value)
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
