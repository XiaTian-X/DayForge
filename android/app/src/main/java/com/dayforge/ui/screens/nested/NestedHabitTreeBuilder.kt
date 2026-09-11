package com.dayforge.ui.screens.nested

import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.service.FailureChecker
import com.dayforge.domain.service.ScheduleValidator
import com.dayforge.domain.service.StreakCalculator
import com.dayforge.util.DateTimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * Builds the nested-screen read model from persisted habits and completion facts.
 *
 * Keeping this projection outside the ViewModel makes its database reads and business-stat
 * calculations one replaceable boundary while preserving the existing observable UI model.
 */
class NestedHabitTreeBuilder @Inject constructor(
    private val habitDao: HabitDao,
    private val completionDao: CompletionDao,
    private val timeLogDao: TimeLogDao,
    private val failureChecker: FailureChecker
) {

    suspend fun build(
        topLevelHabits: List<HabitEntity>,
        completions: List<CompletionEntity>
    ): List<ParentHabitWithChildren> = topLevelHabits.map { parentHabit ->
        val children = withContext(Dispatchers.IO) {
            habitDao.getChildrenByParentUuid(parentHabit.uuid).first()
        }
        val childrenWithStats = children.map { child ->
            calculateChildStats(child, completions)
        }
        val sortedChildren = childrenWithStats.sortedByDescending {
            it.habit.isActive && it.isCheckInAllowed
        }
        val checkInAllowedChildren = sortedChildren.filter {
            it.isCheckInAllowed && !it.isGoalCompleted && !it.hasFailed
        }
        val parentIsCheckInAllowed = ScheduleValidator.isCheckInAllowedToday(
            parentHabit.schedule,
            parentHabit.createdAt
        )
        val parentNextCheckInDate = if (parentIsCheckInAllowed) {
            null
        } else {
            ScheduleValidator.getNextCheckInDate(parentHabit.schedule, parentHabit.createdAt)
        }
        val dayProgress = if (parentHabit.habitType == HabitType.GOAL) {
            val creationDate = Instant.ofEpochMilli(parentHabit.createdAt)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            ChronoUnit.DAYS.between(creationDate, LocalDate.now()).toInt() + 1
        } else {
            0
        }

        ParentHabitWithChildren(
            habit = parentHabit,
            children = sortedChildren,
            completedChildren = checkInAllowedChildren.count { it.completedToday },
            totalChildren = checkInAllowedChildren.size,
            totalChildrenIncludingNonCheckInDays = sortedChildren.size,
            isCheckInAllowed = parentIsCheckInAllowed,
            nextCheckInDate = parentNextCheckInDate,
            dayProgress = dayProgress
        )
    }
        .filter { it.habit.habitType == HabitType.GOAL || it.totalChildren > 0 }
        .sortedByDescending { it.habit.isActive && it.isCheckInAllowed }

    private suspend fun calculateChildStats(
        child: HabitEntity,
        completions: List<CompletionEntity>
    ): ChildHabitWithStats {
        val habitCompletions = completions.filter { it.habitId == child.id }
        val todayCompletions = habitCompletions.filter { isToday(it.date) }

        val (todayCount, currentStreak, bestStreak) = if (child.habitType == HabitType.TIMER) {
            val allTimeLogs = withContext(Dispatchers.IO) {
                timeLogDao.getAllTimeLogsForHabit(child.id)
            }
            val todayStart = DateTimeUtils.startOfDayMillis()
            val todayEnd = todayStart + DateTimeUtils.MILLIS_PER_DAY
            val todayLogs = allTimeLogs.filter { it.date in todayStart until todayEnd }
            val targetSeconds = child.targetValue * 60
            val completedDates = allTimeLogs
                .groupBy { it.date }
                .filter { (_, logs) -> logs.sumOf { it.durationSeconds } >= targetSeconds }
                .keys
                .toList()

            Triple(
                todayLogs.sumOf { it.durationSeconds },
                StreakCalculator.calculateCurrentStreakFromDates(completedDates),
                StreakCalculator.calculateBestStreakFromDates(completedDates)
            )
        } else {
            Triple(
                todayCompletions.sumOf { it.value },
                StreakCalculator.calculateCurrentStreak(habitCompletions),
                StreakCalculator.calculateBestStreak(habitCompletions)
            )
        }

        val isCheckInAllowed = ScheduleValidator.isCheckInAllowedToday(
            child.schedule,
            child.createdAt
        )
        val targetProgress = if (child.targetCycles == null) {
            0
        } else {
            withContext(Dispatchers.IO) {
                if (child.habitType == HabitType.TIMER) {
                    timeLogDao.getDistinctDayCount(child.id)
                } else {
                    completionDao.getDistinctDayCount(child.id)
                }
            }
        }
        val hasFailed = if (child.targetCycles == null) {
            false
        } else {
            withContext(Dispatchers.IO) {
                val firstCompletionDateMillis = if (child.habitType == HabitType.TIMER) {
                    timeLogDao.getFirstTimeLogDate(child.id)
                } else {
                    completionDao.getFirstCompletionDate(child.id)
                }
                val firstCompletionDate = firstCompletionDateMillis?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                }
                failureChecker.hasFailed(child, firstCompletionDate)
            }
        }

        return ChildHabitWithStats(
            habit = child,
            completedToday = when (child.habitType) {
                HabitType.CHECK_IN -> todayCount > 0
                HabitType.COUNTING -> todayCount >= child.targetValue
                HabitType.TIMER -> todayCount >= child.targetValue * 60
                HabitType.GOAL -> false
            },
            todayCount = todayCount,
            lastCompletionId = todayCompletions.maxByOrNull { it.id }?.id,
            currentStreak = currentStreak,
            bestStreak = bestStreak,
            activityRate = child.activityRate,
            isCheckInAllowed = isCheckInAllowed,
            nextCheckInDate = if (isCheckInAllowed) {
                null
            } else {
                ScheduleValidator.getNextCheckInDate(child.schedule, child.createdAt)
            },
            targetProgress = targetProgress,
            hasFailed = hasFailed
        )
    }

    private fun isToday(dateMillis: Long): Boolean =
        dateMillis == DateTimeUtils.startOfDayMillis()
}
