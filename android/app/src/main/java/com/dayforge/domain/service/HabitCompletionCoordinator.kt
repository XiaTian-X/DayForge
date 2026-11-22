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
class HabitCompletionCoordinator private constructor(
    private val context: Context,
    private val fixedCheckInService: CheckInService?,
    private val fixedHabitRepository: HabitRepository?,
    private val fixedMetricRepository: MetricRepository?,
    private val databases: com.dayforge.data.local.DatabaseRuntimeHost?
) {
    constructor(context: Context, checkInService: CheckInService, habitRepository: HabitRepository,
        metricRepository: MetricRepository) : this(context, checkInService, habitRepository, metricRepository, null)
    @Inject internal constructor(@ApplicationContext context: Context,
        databases: com.dayforge.data.local.DatabaseRuntimeHost) : this(context, null, null, null, databases)
    // Public production operations are composed inside the caller's original source-bound
    // Host lease. There is no method-level lookup of the latest database/DAO.
    private val checkInService get() = fixedCheckInService ?: requireNotNull(databases).boundGraph().checkInService
    private val habitRepository get() = fixedHabitRepository ?: requireNotNull(databases).boundGraph().habitRepository
    private val metricRepository get() = fixedMetricRepository ?: requireNotNull(databases).boundGraph().metricRepository

    suspend fun checkIn(
        habitId: Long,
        finalizeTemporaryTasks: Boolean,
        oneTimeAuthority: com.dayforge.domain.model.OneTimeActionAuthority? = null,
        checkIdentity: com.dayforge.domain.model.CheckActionIdentity? = null,
        displayedHabit: () -> HabitEntity?
    ): HabitCompletionOutcome {
        val displayed = displayedHabit() ?: return HabitCompletionOutcome.NONE
        check(displayed.id == habitId) { "CHECK_ACTION_EXPIRED" }
        val result = if (displayed.completionPolicy == "one_and_done")
            checkInService.toggleCheckIn(context, habitId, displayed.uuid, oneTimeAuthority)
        else if (displayed.appearance != null && displayed.completionPolicy == "recurring") {
            requireNotNull(checkIdentity) { "CHECK_ACTION_REQUIRED" }
            checkInService.toggleCheckIn(context, habitId, displayed.uuid, identity = checkIdentity)
        }
        else checkInService.toggleCheckIn(context, habitId)
        val success = result as? CheckInResult.Success ?: return HabitCompletionOutcome.NONE
        return afterCommittedAction {
        val habit = success.followUp?.habit ?: displayed
        if (habit.appearance != null && habit.completionPolicy == "recurring")
            requireNotNull(success.followUp) { "COMPLETION_FOLLOW_UP_REQUIRED" }
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
        val promptHabit = habit.takeIf {
            (success.followUp?.completed ?: success.completed) &&
                (!isTemporaryTask || temporaryTaskHasPromptMetrics ?: hasPromptMetrics(habitId))
        }

        HabitCompletionOutcome(
            goalProgress = goalProgress,
            metricPromptHabit = promptHabit,
            shouldDeleteTemporaryTask = shouldDeleteTemporaryTask,
            followUp = success.followUp, command = success.command
        )
        }
    }

    suspend fun recordCompletion(habitId: Long, value: Int,
        identity: com.dayforge.domain.model.CountActionIdentity? = null): com.dayforge.data.repository.CountCommandReceipt? =
        habitRepository.logCompletionWithReceipt(context, habitId, value, countIdentity = identity).command

    suspend fun undoCompletion(completionId: Long, oneTimeAuthority: com.dayforge.domain.model.OneTimeActionAuthority? = null,
        identity: com.dayforge.domain.model.CountActionIdentity? = null,
        checkIdentity: com.dayforge.domain.model.CheckActionIdentity? = null): com.dayforge.data.repository.CountCommandReceipt? =
        habitRepository.undoCompletionWithReceipt(context, completionId, oneTimeAuthority, countIdentity = identity, checkIdentity = checkIdentity).command

    internal suspend fun acknowledgeCountResultDelivery(receipt: com.dayforge.data.repository.CountCommandReceipt) =
        habitRepository.acknowledgeCountResultDelivery(receipt)

    suspend fun incrementCount(
        habitId: Long,
        identity: com.dayforge.domain.model.CountActionIdentity? = null,
        displayedHabit: () -> HabitEntity?
    ): HabitCompletionOutcome {
        // The visible projection can expire while the accepted write is in flight.
        val habit = displayedHabit()
        val result = checkInService.incrementCount(context, habitId, identity)
        // Rejected actions must not open metric forms or announce reached goals.
        // Exceptions/cancellation still propagate to the caller without retrying the write.
        val success = result as? CheckInResult.Success ?: return HabitCompletionOutcome.NONE
        if (!success.recorded) return HabitCompletionOutcome(command = success.command)
        val currentHabit = success.followUp?.habit ?: habit
        if (currentHabit?.appearance != null && currentHabit.completionPolicy == "recurring")
            requireNotNull(success.followUp) { "COMPLETION_FOLLOW_UP_REQUIRED" }
        val goalProgress = success
            .takeIf(CheckInResult.Success::goalReached)
            ?.progress
        return HabitCompletionOutcome(
            goalProgress = goalProgress,
            metricPromptHabit = currentHabit,
            followUp = success.followUp, command = success.command
        )
    }

    suspend fun decrementCount(habitId: Long, identity: com.dayforge.domain.model.CountActionIdentity? = null): HabitCompletionOutcome {
        val result = checkInService.decrementCount(context, habitId, identity)
        val goalProgress = (result as? CheckInResult.Success)
            ?.takeIf(CheckInResult.Success::goalReached)
            ?.progress
        return HabitCompletionOutcome(goalProgress = goalProgress, followUp = (result as? CheckInResult.Success)?.followUp, command = (result as? CheckInResult.Success)?.command)
    }

    private suspend fun hasPromptMetrics(habitId: Long): Boolean =
        metricRepository.getLinkedMetricSnapshots(habitId).any { it.promptOnComplete }

    private fun HabitEntity?.isTemporaryTask(): Boolean =
        this != null &&
            completionPolicy == null &&
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
    val shouldDeleteTemporaryTask: Boolean = false,
    val followUp: com.dayforge.data.repository.WidgetFactSnapshot? = null,
    val command: com.dayforge.data.repository.CountCommandReceipt? = null
) {
    companion object {
        val NONE = HabitCompletionOutcome()
    }
}
