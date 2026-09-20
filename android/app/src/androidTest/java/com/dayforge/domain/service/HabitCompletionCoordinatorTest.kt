package com.dayforge.domain.service

import android.content.Context
import com.dayforge.data.local.dao.LinkedMetricSnapshot
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.CheckInResult
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import com.dayforge.data.repository.MetricRepository
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class HabitCompletionCoordinatorTest {
    private lateinit var context: Context
    private lateinit var checkInService: CheckInService
    private lateinit var habitRepository: HabitRepository
    private lateinit var metricRepository: MetricRepository
    private lateinit var coordinator: HabitCompletionCoordinator

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        checkInService = mockk()
        habitRepository = mockk()
        metricRepository = mockk()
        coordinator = HabitCompletionCoordinator(
            context,
            checkInService,
            habitRepository,
            metricRepository
        )
    }

    @Test
    fun normal_successful_check_in_requests_goal_and_metric_follow_ups() = runTest {
        val habit = habit(targetCycles = 2)
        coEvery { checkInService.toggleCheckIn(context, 7L) } returns
            CheckInResult.Success(completed = true, progress = 2, goalReached = true)

        val outcome = coordinator.checkIn(7L, finalizeTemporaryTasks = true) { habit }

        assertEquals(2, outcome.goalProgress)
        assertEquals(habit, outcome.metricPromptHabit)
        assertFalse(outcome.shouldDeleteTemporaryTask)
        coVerify(exactly = 0) { metricRepository.getLinkedMetricSnapshots(any()) }
    }

    @Test
    fun temporary_task_without_prompt_metrics_requests_deletion_only() = runTest {
        val habit = temporaryTask()
        coEvery { checkInService.toggleCheckIn(context, 7L) } returns
            CheckInResult.Success(completed = true, progress = 1, goalReached = true)
        coEvery { metricRepository.getLinkedMetricSnapshots(7L) } returns emptyList()

        val outcome = coordinator.checkIn(7L, finalizeTemporaryTasks = true) { habit }

        assertNull(outcome.goalProgress)
        assertNull(outcome.metricPromptHabit)
        assertTrue(outcome.shouldDeleteTemporaryTask)
        coVerify(exactly = 1) { metricRepository.getLinkedMetricSnapshots(7L) }
    }

    @Test
    fun temporary_task_with_prompt_metric_is_retained_for_prompt() = runTest {
        val habit = temporaryTask()
        coEvery { checkInService.toggleCheckIn(context, 7L) } returns
            CheckInResult.Success(completed = true, progress = 1, goalReached = true)
        coEvery { metricRepository.getLinkedMetricSnapshots(7L) } returns listOf(promptMetric())

        val outcome = coordinator.checkIn(7L, finalizeTemporaryTasks = true) { habit }

        assertNull(outcome.goalProgress)
        assertEquals(habit, outcome.metricPromptHabit)
        assertFalse(outcome.shouldDeleteTemporaryTask)
        coVerify(exactly = 1) { metricRepository.getLinkedMetricSnapshots(7L) }
    }

    @Test
    fun screen_without_temporary_task_finalization_treats_task_as_a_normal_habit() = runTest {
        val habit = temporaryTask()
        coEvery { checkInService.toggleCheckIn(context, 7L) } returns
            CheckInResult.Success(completed = true, progress = 1, goalReached = true)

        val outcome = coordinator.checkIn(7L, finalizeTemporaryTasks = false) { habit }

        assertEquals(1, outcome.goalProgress)
        assertEquals(habit, outcome.metricPromptHabit)
        assertFalse(outcome.shouldDeleteTemporaryTask)
        coVerify(exactly = 0) { metricRepository.getLinkedMetricSnapshots(any()) }
    }

    @Test
    fun check_in_undo_has_no_follow_up_action() = runTest {
        coEvery { checkInService.toggleCheckIn(context, 7L) } returns
            CheckInResult.Success(completed = false, progress = 0, goalReached = false)

        val outcome = coordinator.checkIn(7L, finalizeTemporaryTasks = true) { habit() }

        assertEquals(HabitCompletionOutcome.NONE, outcome)
    }

    @Test
    fun count_increment_preserves_existing_prompt_behavior_when_action_reports_error() = runTest {
        val habit = habit(type = HabitType.COUNTING)
        coEvery { checkInService.incrementCount(context, 7L) } returns
            CheckInResult.Error("failed")

        val outcome = coordinator.incrementCount(7L) { habit }

        assertNull(outcome.goalProgress)
        assertEquals(habit, outcome.metricPromptHabit)
    }

    @Test
    fun record_and_undo_delegate_to_repository() = runTest {
        coEvery { habitRepository.logCompletion(context, 7L, 3) } returns 11L
        coJustRun { habitRepository.undoCompletion(context, 11L) }

        coordinator.recordCompletion(7L, 3)
        coordinator.undoCompletion(11L)

        coVerify(exactly = 1) { habitRepository.logCompletion(context, 7L, 3) }
        coVerify(exactly = 1) { habitRepository.undoCompletion(context, 11L) }
    }

    private fun temporaryTask() = habit(
        targetCycles = 1,
        failMode = FailMode.LOOSE,
        iconResId = 53
    )

    private fun habit(
        type: HabitType = HabitType.CHECK_IN,
        targetCycles: Int? = null,
        failMode: FailMode = FailMode.STRICT,
        iconResId: Int = 1
    ) = HabitEntity(
        id = 7L,
        name = "Habit",
        habitType = type,
        iconResId = iconResId,
        colorHex = "#2196F3",
        schedule = HabitSchedule.Daily,
        targetValue = 1,
        targetCycles = targetCycles,
        failMode = failMode
    )

    private fun promptMetric() = LinkedMetricSnapshot(
        habitId = 7L,
        metricId = 3L,
        metricName = "Weight",
        latestValue = null,
        unit = "kg",
        decimalPlaces = 1,
        showInHabitDetail = true,
        promptOnComplete = true
    )
}
