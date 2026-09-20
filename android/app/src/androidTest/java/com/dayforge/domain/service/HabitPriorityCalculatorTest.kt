package com.dayforge.domain.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

import com.dayforge.data.model.HabitType
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class HabitPriorityCalculatorTest {

    private val zoneId = ZoneId.systemDefault()

    // ========== getTypePriority tests ==========

    @Test
    fun getTypePriority_checkIn_returns05() {
        val priority = HabitPriorityCalculator.getTypePriority(HabitType.CHECK_IN)
        assertEquals("CHECK_IN should have highest priority", 0.5f, priority, 0.01f)
    }

    @Test
    fun getTypePriority_counting_returns025() {
        val priority = HabitPriorityCalculator.getTypePriority(HabitType.COUNTING)
        assertEquals("COUNTING should have medium priority", 0.25f, priority, 0.01f)
    }

    @Test
    fun getTypePriority_timer_returns0125() {
        val priority = HabitPriorityCalculator.getTypePriority(HabitType.TIMER)
        assertEquals("TIMER should have lowest priority", 0.125f, priority, 0.01f)
    }

    @Test
    fun getTypePriority_goal_returns0() {
        val priority = HabitPriorityCalculator.getTypePriority(HabitType.GOAL)
        assertEquals("GOAL should have zero priority", 0.0f, priority, 0.01f)
    }

    // ========== calculatePriorities sorting tests ==========

    @Test
    fun calculatePriorities_emptyList_returnsEmpty() {
        val currentTime = ZonedDateTime.of(2026, 4, 11, 9, 0, 0, 0, zoneId)
        val result = HabitPriorityCalculator.calculatePriorities(emptyList(), currentTime)

        assertTrue("Empty input should return empty list", result.isEmpty())
    }

    @Test
    fun calculatePriorities_singleHabit_returnsSingle() {
        val currentTime = ZonedDateTime.of(2026, 4, 11, 9, 0, 0, 0, zoneId)
        val habit = createTestHabit(
            id = 1,
            habitType = HabitType.CHECK_IN,
            bestTime = 540L // 9:00
        )

        val result = HabitPriorityCalculator.calculatePriorities(listOf(habit), currentTime)

        assertEquals("Single habit should return single result", 1, result.size)
        assertEquals("First result should be the input habit", habit.id, result[0].habit.id)
    }

    @Test
    fun calculatePriorities_inWindowHabit_sortsHigherThanOutWindow() {
        val currentTime = ZonedDateTime.of(2026, 4, 11, 9, 0, 0, 0, zoneId)

        val inWindowHabit = createTestHabit(
            id = 1,
            habitType = HabitType.CHECK_IN,
            bestTime = 540L, // 9:00 - in window at 9:00
            createdAt = 100L
        )

        val outWindowHabit = createTestHabit(
            id = 2,
            habitType = HabitType.CHECK_IN,
            bestTime = 600L, // 10:00 - before window at 9:00
            createdAt = 200L
        )

        val result = HabitPriorityCalculator.calculatePriorities(
            listOf(outWindowHabit, inWindowHabit), // Input order reversed
            currentTime
        )

        assertEquals("Should have 2 results", 2, result.size)
        assertEquals("In-window habit should be first (higher distance score + in-window bonus)",
            inWindowHabit.id, result[0].habit.id)
        assertEquals("Out-window habit should be second", outWindowHabit.id, result[1].habit.id)
    }

    @Test
    fun calculatePriorities_sameTimeScore_typePriorityDeterminesOrder() {
        val currentTime = ZonedDateTime.of(2026, 4, 11, 9, 0, 0, 0, zoneId)

        // Both in window at 9:00, but different types
        val checkInHabit = createTestHabit(
            id = 1,
            habitType = HabitType.CHECK_IN,
            bestTime = 540L,
            createdAt = 200L
        )

        val countingHabit = createTestHabit(
            id = 2,
            habitType = HabitType.COUNTING,
            bestTime = 540L,
            createdAt = 100L
        )

        val result = HabitPriorityCalculator.calculatePriorities(
            listOf(countingHabit, checkInHabit),
            currentTime
        )

        assertEquals("Should have 2 results", 2, result.size)
        assertEquals("CHECK_IN should sort higher than COUNTING (typePriority: 0.5 > 0.25)",
            checkInHabit.id, result[0].habit.id)
        assertEquals("COUNTING should be second",
            countingHabit.id, result[1].habit.id)
    }

    @Test
    fun calculatePriorities_sameScoreAndType_creationOrderDetermines() {
        val currentTime = ZonedDateTime.of(2026, 4, 11, 9, 0, 0, 0, zoneId)

        // Same type, same bestTime, different createdAt
        val earlierHabit = createTestHabit(
            id = 1,
            habitType = HabitType.CHECK_IN,
            bestTime = 540L,
            createdAt = 100L
        )

        val laterHabit = createTestHabit(
            id = 2,
            habitType = HabitType.CHECK_IN,
            bestTime = 540L,
            createdAt = 200L
        )

        val result = HabitPriorityCalculator.calculatePriorities(
            listOf(laterHabit, earlierHabit),
            currentTime
        )

        assertEquals("Should have 2 results", 2, result.size)
        assertEquals("Earlier created habit should sort first",
            earlierHabit.id, result[0].habit.id)
        assertEquals("Later created habit should be second",
            laterHabit.id, result[1].habit.id)
    }

    @Test
    fun calculatePriorities_noBestTime_sortsByTypePriorityOnly() {
        val currentTime = ZonedDateTime.of(2026, 4, 11, 9, 0, 0, 0, zoneId)

        val checkInHabit = createTestHabit(
            id = 1,
            habitType = HabitType.CHECK_IN,
            bestTime = null // No best time
        )

        val timerHabit = createTestHabit(
            id = 2,
            habitType = HabitType.TIMER,
            bestTime = null
        )

        val result = HabitPriorityCalculator.calculatePriorities(
            listOf(timerHabit, checkInHabit),
            currentTime
        )

        assertEquals("CHECK_IN should still sort higher (typePriority + distanceScore=0.5)",
            checkInHabit.id, result[0].habit.id)
    }

    @Test
    fun calculatePriorities_multipleHabitTypes_complexSorting() {
        val currentTime = ZonedDateTime.of(2026, 4, 11, 9, 5, 0, 0, zoneId)

        // Timer in window (bestTime 9:00, target 30, window ±30)
        val timerInWindow = createTestHabit(
            id = 1,
            habitType = HabitType.TIMER,
            bestTime = 540L,
            targetValue = 30,
            createdAt = 100L
        )

        // CHECK_IN in window (bestTime 9:00, score 1.0)
        val checkInInWindow = createTestHabit(
            id = 2,
            habitType = HabitType.CHECK_IN,
            bestTime = 540L,
            createdAt = 200L
        )

        // COUNTING after window (bestTime 8:00)
        val countingAfterWindow = createTestHabit(
            id = 3,
            habitType = HabitType.COUNTING,
            bestTime = 480L, // 8:00, window ended at 8:15
            createdAt = 300L
        )

        val result = HabitPriorityCalculator.calculatePriorities(
            listOf(countingAfterWindow, timerInWindow, checkInInWindow),
            currentTime
        )

        // Expected: CHECK_IN (distance~1.3 + type 0.5) > TIMER (distance~1.28 + type 0.125) > COUNTING (distance~0.08 + type 0.25)
        assertEquals("CHECK_IN in window should be first",
            checkInInWindow.id, result[0].habit.id)
        assertEquals("TIMER in window should be second",
            timerInWindow.id, result[1].habit.id)
        assertEquals("COUNTING after window should be third",
            countingAfterWindow.id, result[2].habit.id)
    }

    // ========== New tests for completion and distance ==========

    @Test
    fun calculatePriorities_completedHabit_sortsLowerThanUncompleted() {
        val currentTime = ZonedDateTime.of(2026, 4, 11, 9, 0, 0, 0, zoneId)

        val completedHabit = createTestHabit(
            id = 1,
            habitType = HabitType.CHECK_IN,
            bestTime = 540L, // 9:00 - in window
            createdAt = 100L
        )

        val uncompletedHabit = createTestHabit(
            id = 2,
            habitType = HabitType.CHECK_IN,
            bestTime = 540L, // 9:00 - in window
            createdAt = 200L
        )

        val completedMap = mapOf(
            1L to 1,  // completedHabit is completed (1 completion today)
            2L to 0   // uncompletedHabit is not completed (0 completions today)
        )

        val result = HabitPriorityCalculator.calculatePriorities(
            listOf(completedHabit, uncompletedHabit),
            currentTime,
            completedMap
        )

        assertEquals("Uncompleted habit should sort higher (completionScore: 2.0 > 0.0)",
            uncompletedHabit.id, result[0].habit.id)
        assertEquals("Completed habit should be second",
            completedHabit.id, result[1].habit.id)
    }

    @Test
    fun calculatePriorities_nearbyHabit_sortsHigherThanFarHabit() {
        // Current time: 13:00 (780 minutes)
        val currentTime = ZonedDateTime.of(2026, 4, 11, 13, 0, 0, 0, zoneId)

        // Habit at 12:00 (720 min) - AfterWindow, distance = 60 min
        val nearbyHabit = createTestHabit(
            id = 1,
            habitType = HabitType.CHECK_IN,
            bestTime = 720L, // 12:00
            createdAt = 100L
        )

        // Habit at 22:00 (1320 min) - BeforeWindow, distance = 540 min
        val farHabit = createTestHabit(
            id = 2,
            habitType = HabitType.CHECK_IN,
            bestTime = 1320L, // 22:00
            createdAt = 200L
        )

        val result = HabitPriorityCalculator.calculatePriorities(
            listOf(farHabit, nearbyHabit),
            currentTime
        )

        // Nearby: distanceScore = 1 * (1 - 60/720) ≈ 0.92
        // Far: distanceScore = 1 * (1 - 540/720) = 0.25
        assertEquals("Nearby habit (12:00, 60 min away) should sort higher",
            nearbyHabit.id, result[0].habit.id)
        assertEquals("Far habit (22:00, 540 min away) should be second",
            farHabit.id, result[1].habit.id)
    }

    @Test
    fun calculatePriorities_afterWindowNearby_sortsHigherThanBeforeWindowFar() {
        // Current time: 21:00 (1260 minutes)
        val currentTime = ZonedDateTime.of(2026, 4, 11, 21, 0, 0, 0, zoneId)

        // Habit at 12:00 (720 min) - AfterWindow, distance = 1260 - 735 = 525 min
        val afterWindowNearby = createTestHabit(
            id = 1,
            habitType = HabitType.CHECK_IN,
            bestTime = 720L, // 12:00, window ends at 12:15 (735 min)
            createdAt = 100L
        )

        // Habit at 22:00 (1320 min) - BeforeWindow, distance = 1305 - 1260 = 45 min
        val beforeWindowNearby = createTestHabit(
            id = 2,
            habitType = HabitType.CHECK_IN,
            bestTime = 1320L, // 22:00, window starts at 21:45 (1305 min)
            createdAt = 200L
        )

        val result = HabitPriorityCalculator.calculatePriorities(
            listOf(afterWindowNearby, beforeWindowNearby),
            currentTime
        )

        // AfterWindow: 525 min away → score ≈ 0.27
        // BeforeWindow: 45 min away → score ≈ 0.94
        assertEquals("BeforeWindow nearby (22:00, 45 min until window) should sort higher",
            beforeWindowNearby.id, result[0].habit.id)
        assertEquals("AfterWindow far (12:00, 525 min since window) should be second",
            afterWindowNearby.id, result[1].habit.id)
    }

    @Test
    fun calculatePriorities_completedAlwaysLower_thanUncompletedRegardlessOfDistance() {
        // Current time: 13:00
        val currentTime = ZonedDateTime.of(2026, 4, 11, 13, 0, 0, 0, zoneId)

        // Completed habit nearby (12:00, 60 min away)
        val completedNearby = createTestHabit(
            id = 1,
            habitType = HabitType.CHECK_IN,
            bestTime = 720L,
            createdAt = 100L
        )

        // Uncompleted habit far (22:00, 540 min away)
        val uncompletedFar = createTestHabit(
            id = 2,
            habitType = HabitType.CHECK_IN,
            bestTime = 1320L,
            createdAt = 200L
        )

        val completedMap = mapOf(
            1L to 1,   // completed (1 completion today)
            2L to 0    // uncompleted (0 completions today)
        )

        val result = HabitPriorityCalculator.calculatePriorities(
            listOf(completedNearby, uncompletedFar),
            currentTime,
            completedMap
        )

        // UncompletedFar: completionScore 2.0 + distanceScore 0.25 + typePriority 0.5 = 2.75
        // CompletedNearby: completionScore 0.0 + distanceScore 0.92 + typePriority 0.5 = 1.42
        assertEquals("Uncompleted (even far) should always sort higher than completed",
            uncompletedFar.id, result[0].habit.id)
        assertEquals("Completed (even nearby) should be second",
            completedNearby.id, result[1].habit.id)
    }

    // ========== Pending metric tests ==========

    @Test
    fun calculatePriorities_pendingMetricHabit_sortsBetweenUncompletedAndCompleted() {
        val currentTime = ZonedDateTime.of(2026, 4, 11, 9, 0, 0, 0, zoneId)

        val uncompletedHabit = createTestHabit(
            id = 1,
            habitType = HabitType.CHECK_IN,
            bestTime = 540L,
            createdAt = 100L
        )

        val completedWithPendingMetric = createTestHabit(
            id = 2,
            habitType = HabitType.CHECK_IN,
            bestTime = 540L,
            createdAt = 200L
        )

        val completedWithoutPendingMetric = createTestHabit(
            id = 3,
            habitType = HabitType.CHECK_IN,
            bestTime = 540L,
            createdAt = 300L
        )

        val completedMap = mapOf(
            1L to 0,  // uncompleted (0 completions today)
            2L to 1,  // completed (1 completion today)
            3L to 1   // completed (1 completion today)
        )

        val pendingMetrics = setOf(2L)  // Only habit 2 has pending metric

        val result = HabitPriorityCalculator.calculatePriorities(
            listOf(completedWithoutPendingMetric, completedWithPendingMetric, uncompletedHabit),
            currentTime,
            completedMap,
            pendingMetrics
        )

        // Expected order:
        // - Uncompleted: score 2.0 + 1.3 + 0.5 = 3.8
        // - Completed with pending: score 1.5 + 1.3 + 0.5 = 3.3
        // - Completed without pending: score 0.0 + 1.3 + 0.5 = 1.8
        assertEquals("Uncompleted should be first", uncompletedHabit.id, result[0].habit.id)
        assertEquals("Completed with pending metric should be second",
            completedWithPendingMetric.id, result[1].habit.id)
        assertEquals("Completed without pending metric should be third",
            completedWithoutPendingMetric.id, result[2].habit.id)
    }

    @Test
    fun calculatePriorities_pendingMetricHabit_sortsHigherThanOtherCompleted() {
        val currentTime = ZonedDateTime.of(2026, 4, 11, 13, 0, 0, 0, zoneId)

        // Completed habit far away but with pending metric
        val completedWithPendingMetricFar = createTestHabit(
            id = 1,
            habitType = HabitType.CHECK_IN,
            bestTime = 720L, // 12:00 - 60 min away (AfterWindow)
            createdAt = 100L
        )

        // Completed habit nearby without pending metric
        val completedWithoutPendingMetricNear = createTestHabit(
            id = 2,
            habitType = HabitType.CHECK_IN,
            bestTime = 780L, // 13:00 - in window
            createdAt = 200L
        )

        val completedMap = mapOf(
            1L to 1,
            2L to 1
        )

        val pendingMetrics = setOf(1L)  // Habit 1 has pending metric

        val result = HabitPriorityCalculator.calculatePriorities(
            listOf(completedWithoutPendingMetricNear, completedWithPendingMetricFar),
            currentTime,
            completedMap,
            pendingMetrics
        )

        // Completed with pending metric (far): score 1.5 + ~0.92 + 0.5 = ~2.92
        // Completed without pending metric (near, in window): score 0.0 + 1.3 + 0.5 = 1.8
        assertEquals("Completed with pending metric should sort higher than completed without",
            completedWithPendingMetricFar.id, result[0].habit.id)
        assertEquals("Completed without pending metric should be second",
            completedWithoutPendingMetricNear.id, result[1].habit.id)
    }

    // ========== Helper functions ==========

    private fun createTestHabit(
        id: Long,
        habitType: HabitType,
        bestTime: Long?,
        targetValue: Int = 1,
        createdAt: Long = System.currentTimeMillis()
    ): HabitEntity {
        return HabitEntity(
            id = id,
            name = "Test Habit $id",
            habitType = habitType,
            iconResId = 0,
            colorHex = "#FFFFFF",
            schedule = HabitSchedule.Daily,
            targetValue = targetValue,
            bestTime = bestTime,
            createdAt = createdAt
        )
    }
}