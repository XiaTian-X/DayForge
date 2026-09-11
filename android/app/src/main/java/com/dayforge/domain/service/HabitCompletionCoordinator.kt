package com.dayforge.domain.service

import android.content.Context
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.CheckInResult
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import com.dayforge.data.repository.MetricRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Coordinates completion mutations and returns the follow-up actions required by habit screens. */
class HabitCompletionCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val checkInService: CheckInService,
    private val habitRepository: HabitRepository,
    private val metricRepository: MetricRepository
) {
    suspend fun checkIn(
        habitId: Long,
        finalizeTemporaryTasks: Boolean,
        displayedHabit: () -> HabitEntity?
    ): HabitCompletionOutcome {
        val result = checkInService.toggleCheckIn(context, habitId)
        val habit = displayedHabit()
        val success = result as? CheckInResult.Success ?: return HabitCompletionOutcome.NONE
        val isTemporaryTask = finalizeTemporaryTasks && habit.isTemporaryTask()

        val temporaryTaskHasPromptMetrics = if (success.goalReached && isTemporaryTask) {
            hasPromptMetrics(habitId)
        } else {
            null
        }
        val shouldDeleteTemporaryTask = success.goalReached &&
            isTemporaryTask &&
            temporaryTaskHasPromptMetrics == false

        val goalProgress = success.progress.takeIf { success.goalReached && !isTemporaryTask }
        val promptHabit = habit?.takeIf {
            success.completed &&
                (!isTemporaryTask || temporaryTaskHasPromptMetrics ?: hasPromptMetrics(habitId))
        }

        return HabitCompletionOutcome(
            goalProgress = goalProgress,
            metricPromptHabit = promptHabit,
            shouldDeleteTemporaryTask = shouldDeleteTemporaryTask
        )
    }

    suspend fun recordCompletion(habitId: Long, value: Int) {
        habitRepository.logCompletion(context, habitId, value)
    }

    suspend fun undoCompletion(completionId: Long) {
        habitRepository.undoCompletion(context, completionId)
    }

    suspend fun incrementCount(
        habitId: Long,
        displayedHabit: () -> HabitEntity?
    ): HabitCompletionOutcome {
        val result = checkInService.incrementCount(context, habitId)
        val goalProgress = (result as? CheckInResult.Success)
            ?.takeIf(CheckInResult.Success::goalReached)
            ?.progress
        return HabitCompletionOutcome(
            goalProgress = goalProgress,
            metricPromptHabit = displayedHabit()
        )
    }

    suspend fun decrementCount(habitId: Long): HabitCompletionOutcome {
        val result = checkInService.decrementCount(context, habitId)
        val goalProgress = (result as? CheckInResult.Success)
            ?.takeIf(CheckInResult.Success::goalReached)
            ?.progress
        return HabitCompletionOutcome(goalProgress = goalProgress)
    }

    private suspend fun hasPromptMetrics(habitId: Long): Boolean =
        metricRepository.getLinkedMetricSnapshots(habitId).any { it.promptOnComplete }

    private fun HabitEntity?.isTemporaryTask(): Boolean =
        this != null &&
            targetCycles == 1 &&
            failMode == FailMode.LOOSE &&
            habitType == HabitType.CHECK_IN &&
            iconResId == TEMPORARY_TASK_ICON_ID

    private companion object {
        const val TEMPORARY_TASK_ICON_ID = 53
    }
}

data class HabitCompletionOutcome(
    val goalProgress: Int? = null,
    val metricPromptHabit: HabitEntity? = null,
    val shouldDeleteTemporaryTask: Boolean = false
) {
    companion object {
        val NONE = HabitCompletionOutcome()
    }
}
