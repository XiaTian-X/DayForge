package com.dayforge.ui.screens.dashboard

import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.model.HabitWithStats
import com.dayforge.domain.model.FilterMode
import com.dayforge.domain.service.HabitStatusCalculator
import io.mockk.mockk
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardHabitListBuilderTest {
    private val builder = DashboardHabitListBuilder(mockk<HabitStatusCalculator>())
    private val currentTime = ZonedDateTime.of(
        2026,
        1,
        1,
        9,
        0,
        0,
        0,
        ZoneId.of("Asia/Shanghai")
    )

    @Test
    fun allMode_prioritizesActiveAllowedIncompleteHabitsAndKeepsStableOrder() {
        val completed = stats(habit(id = 1), completedToday = true)
        val inactive = stats(habit(id = 2, isActive = false))
        val available = stats(habit(id = 3))

        val result = arrange(
            listOf(completed, inactive, available),
            FilterMode.ALL
        )

        assertEquals(listOf(3L, 1L, 2L), result.ids())
    }

    @Test
    fun timeWindowMode_filtersTerminatedAndNoBestTimeThenPlacesNonCheckInDaysLast() {
        val available = stats(habit(id = 1, bestTime = 540))
        val completed = stats(habit(id = 2, bestTime = 540), completedToday = true, todayCount = 1)
        val nonCheckInDay = stats(habit(id = 3, bestTime = 540), isCheckInAllowed = false)
        val noBestTime = stats(habit(id = 4))
        val failed = stats(habit(id = 5, bestTime = 540), hasFailed = true)
        val counting = stats(
            habit(id = 6, type = HabitType.COUNTING, targetValue = 3, bestTime = 540)
        )

        val result = arrange(
            listOf(completed, nonCheckInDay, noBestTime, failed, available, counting),
            FilterMode.TIME_WINDOW
        )

        assertEquals(listOf(1L, 6L, 2L, 3L), result.ids())
        assertEquals("第 1 个/共 3 个", result.first { it.habit.id == 6L }.slotProgress)
    }

    @Test
    fun checkableMode_preservesSpecialCountingAndPendingTimerRules() {
        val incompleteCheckIn = stats(habit(id = 1))
        val completedCheckIn = stats(habit(id = 2), completedToday = true)
        val positiveCounting = stats(
            habit(id = 3, type = HabitType.COUNTING),
            completedToday = true
        )
        val completedPendingTimer = stats(
            habit(id = 4, type = HabitType.TIMER),
            completedToday = true
        )
        val completedTimer = stats(
            habit(id = 5, type = HabitType.TIMER),
            completedToday = true
        )
        val goal = stats(habit(id = 6, type = HabitType.GOAL))
        val failed = stats(habit(id = 7), hasFailed = true)

        val result = arrange(
            listOf(
                incompleteCheckIn,
                completedCheckIn,
                positiveCounting,
                completedPendingTimer,
                completedTimer,
                goal,
                failed
            ),
            FilterMode.CHECKABLE,
            pendingMetricHabitIds = setOf(4L)
        )

        assertEquals(listOf(1L, 3L, 4L), result.ids())
    }

    @Test
    fun terminatedMode_onlyShowsFailedOrCompletedGoalHabits() {
        val active = stats(habit(id = 1))
        val failed = stats(habit(id = 2), hasFailed = true)
        val completedGoal = stats(
            habit(id = 3, isActive = false, targetCycles = 2),
            targetProgress = 2
        )
        val merelyInactive = stats(habit(id = 4, isActive = false))

        val result = arrange(
            listOf(active, failed, completedGoal, merelyInactive),
            FilterMode.TERMINATED
        )

        assertEquals(listOf(2L, 3L), result.ids())
    }

    private fun arrange(
        habits: List<HabitWithStats>,
        filterMode: FilterMode,
        pendingMetricHabitIds: Set<Long> = emptySet()
    ) = builder.arrange(habits, filterMode, currentTime, pendingMetricHabitIds)

    private fun habit(
        id: Long,
        type: HabitType = HabitType.CHECK_IN,
        isActive: Boolean = true,
        targetValue: Int = 1,
        targetCycles: Int? = null,
        bestTime: Long? = null
    ) = HabitEntity(
        id = id,
        name = "Habit $id",
        habitType = type,
        iconResId = 1,
        colorHex = "#2196F3",
        schedule = HabitSchedule.Daily,
        isActive = isActive,
        targetValue = targetValue,
        targetCycles = targetCycles,
        bestTime = bestTime,
        createdAt = id
    )

    private fun stats(
        habit: HabitEntity,
        completedToday: Boolean = false,
        todayCount: Int = 0,
        isCheckInAllowed: Boolean = true,
        targetProgress: Int = 0,
        hasFailed: Boolean = false
    ) = HabitWithStats(
        habit = habit,
        completedToday = completedToday,
        todayCount = todayCount,
        lastCompletionId = null,
        currentStreak = 0,
        bestStreak = 0,
        isCheckInAllowed = isCheckInAllowed,
        targetProgress = targetProgress,
        hasFailed = hasFailed
    )

    private fun List<HabitWithStats>.ids(): List<Long> = map { it.habit.id }
}
