package com.dayforge.ui.screens.dashboard

import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.data.model.HabitWithStats
import com.dayforge.domain.model.FilterMode
import com.dayforge.domain.service.CountingSlotCalculator
import com.dayforge.domain.service.HabitPriorityCalculator
import com.dayforge.domain.service.HabitStatusCalculator
import java.time.ZonedDateTime
import javax.inject.Inject

/** Builds the dashboard habit list without owning any Flow or lifecycle state. */
class DashboardHabitListBuilder @Inject constructor(
    private val habitStatusCalculator: HabitStatusCalculator
) {
    internal suspend fun project(batch: com.dayforge.data.repository.DashboardReadBatch): List<HabitWithStats> {
        var oneTimes: Map<Long, com.dayforge.domain.model.OneTimeStatus>? = null
        return batch.habits.map { habit ->
            val count = batch.counts[habit.id]
            val check = batch.checks[habit.id]
            when {
                habit.completionPolicy == "one_and_done" -> {
                    val statuses = oneTimes ?: habitStatusCalculator.readOneTimes(
                        batch.habits.filter { it.completionPolicy == "one_and_done" }).also { oneTimes = it }
                    habitStatusCalculator.projectOneTime(habit, statuses.getValue(habit.id))
                }
                count != null -> habitStatusCalculator.projectCount(habit, count, batch.time)
                check != null -> habitStatusCalculator.projectCheck(habit, check, batch.time)
                else -> habitStatusCalculator.calculate(habit, batch.completions, null).let { stats ->
                    if (habit.habitType == HabitType.COUNTING && habit.appearance == null)
                        stats.copy(countActionIdentity = com.dayforge.data.repository.CountActionReader.legacyIdentity(habit, batch.core))
                    else stats
                }
            }
        }
    }

    suspend fun build(
        habits: List<HabitEntity>,
        completions: List<CompletionEntity>,
        filterMode: FilterMode,
        currentTime: ZonedDateTime,
        pendingMetricHabitIds: Set<Long>
    ): List<HabitWithStats> {
        val habitsWithStats = habits.map { habit ->
            habitStatusCalculator.calculate(habit, completions, null)
        }

        return arrange(habitsWithStats, filterMode, currentTime, pendingMetricHabitIds)
    }

    internal fun arrange(
        habits: List<HabitWithStats>,
        filterMode: FilterMode,
        currentTime: ZonedDateTime,
        pendingMetricHabitIds: Set<Long>
    ): List<HabitWithStats> {
        val habitsWithSlotProgress = habits.map { stats ->
            stats.copy(
                slotProgress = if (stats.countRuleKnown) slotProgress(stats.windowHabit, filterMode, currentTime) else null
            )
        }

        return when (filterMode) {
            FilterMode.ALL -> allHabits(habitsWithSlotProgress)
            FilterMode.TIME_WINDOW -> timeWindowHabits(
                habitsWithSlotProgress,
                currentTime,
                pendingMetricHabitIds
            )
            FilterMode.CHECKABLE -> checkableHabits(
                habitsWithSlotProgress,
                pendingMetricHabitIds
            )
            FilterMode.TERMINATED -> terminatedHabits(habitsWithSlotProgress)
        }
    }

    private fun allHabits(habits: List<HabitWithStats>): List<HabitWithStats> =
        habits.sortedByDescending {
            it.habit.isActive && it.isCheckInAllowed && !it.completedForDisplay
        }

    private fun timeWindowHabits(
        habits: List<HabitWithStats>,
        currentTime: ZonedDateTime,
        pendingMetricHabitIds: Set<Long>
    ): List<HabitWithStats> {
        val eligibleHabits = habits.filter {
            it.habit.bestTime != null && it.countRuleKnown && !it.hasFailed && !it.isGoalCompleted
        }
        val checkInAllowedHabits = eligibleHabits.filter(HabitWithStats::isCheckInAllowed)
        val nonCheckInDayHabits = eligibleHabits.filterNot(HabitWithStats::isCheckInAllowed)

        val completedCountByHabitId = checkInAllowedHabits.associate {
            it.habit.id to it.todayCount
        }
        val allowedPriorities = HabitPriorityCalculator.calculatePriorities(
            checkInAllowedHabits.map(HabitWithStats::windowHabit),
            currentTime,
            completedCountByHabitId,
            pendingMetricHabitIds
        )
        val nonCheckInPriorities = HabitPriorityCalculator.calculatePriorities(
            nonCheckInDayHabits.map(HabitWithStats::windowHabit),
            currentTime,
            emptyMap(),
            emptySet()
        )

        val statsByHabitId = eligibleHabits.associateBy { it.habit.id }
        return (allowedPriorities + nonCheckInPriorities).mapNotNull { priority ->
            statsByHabitId[priority.habit.id]
        }
    }

    private fun checkableHabits(
        habits: List<HabitWithStats>,
        pendingMetricHabitIds: Set<Long>
    ): List<HabitWithStats> = habits.filter { stats ->
        val habit = stats.habit
        val isGoal = habit.habitType == HabitType.GOAL
        val isTerminated = stats.hasFailed || stats.isGoalCompleted
        val isPositiveCounting = habit.habitType == HabitType.COUNTING && !stats.displayIsCountdown && stats.countRuleKnown
        val hasPendingMetric = (habit.habitType == HabitType.TIMER || habit.completionPolicy == "one_and_done") &&
            habit.id in pendingMetricHabitIds
        val isNormallyCheckable = !stats.completedForDisplay &&
            stats.oneTimeStatus?.canChange != false &&
            stats.isCheckInAllowed &&
            habit.isActive

        !isGoal && !isTerminated &&
            (isPositiveCounting || hasPendingMetric || isNormallyCheckable)
    }

    private fun terminatedHabits(habits: List<HabitWithStats>): List<HabitWithStats> =
        habits.filter { it.hasFailed || it.isGoalCompleted }

    private fun slotProgress(
        habit: HabitEntity,
        filterMode: FilterMode,
        currentTime: ZonedDateTime
    ): String? {
        if (
            filterMode != FilterMode.TIME_WINDOW ||
            habit.habitType != HabitType.COUNTING ||
            habit.bestTime == null
        ) {
            return null
        }

        return CountingSlotCalculator.getSlotProgress(
            habit.bestTime,
            habit.targetValue,
            currentTime
        )?.let { (currentSlot, totalSlots) ->
            "第 $currentSlot 个/共 $totalSlots 个"
        }
    }
}
