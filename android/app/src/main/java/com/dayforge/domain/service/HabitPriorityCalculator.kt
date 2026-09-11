package com.dayforge.domain.service

import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import java.time.ZonedDateTime

/**
 * Represents a habit with its calculated priority for time-based sorting.
 * Used by FocusWidget and App focus mode to display relevant habits.
 */
data class HabitPriority(
    val habit: HabitEntity,
    val matchResult: TimeMatchResult,
    val sortScore: Float, // Combined score for sorting (0.0-3.5 range with new algorithm)
    val slotIndex: Int? = null, // For COUNTING: which slot this represents
    val totalSlots: Int? = null, // For COUNTING: total number of slots
    val completedToday: Boolean = false, // Whether habit is fully completed today
    val hasPendingMetric: Boolean = false, // Whether habit has pending metric to record
    val nextSlotTime: Int? = null // For COUNTING: next uncompleted slot time (minutes since midnight)
)

/**
 * Calculates habit priorities based on time window matching and completion status.
 *
 * Five-layer sorting per improved SORT-02:
 * 1. Completion status (descending) - uncompleted/pending-metric habits first
 * 2. Pending metric (descending) - completed but pending metric sorts between uncompleted/completed
 * 3. Time distance (descending) - closer to current time first
 * 4. Window status (descending) - InWindow > Before/AfterWindow > NoBestTime
 * 5. Type priority (descending) - CHECK_IN > COUNTING > TIMER > GOAL
 *
 * Time distance formula:
 * - distanceScore = 1.0 * (1 - distanceMinutes / 720)
 * - Max distance: 12 hours (720 minutes), beyond that score = 0
 *
 * Pending metric handling:
 * - Completed habits with pending metric (needs to record linked metric) get 1.5 score
 * - This keeps them visible so user can record the metric before habit drops in priority
 *
 * COUNTING habits special handling (方案I：Slot级别完成判定):
 * - Each slot has its own time window (±15 minutes around slot time)
 * - 当前slot已完成(todayCount > slotIndex)时：改为BeforeWindow，等待下一slot
 * - 当前slot未完成时：保持InWindow，高优先级
 * - 实现效果：本窗口期打卡一次后脱离本窗口期，下一窗口期重新加入
 */
object HabitPriorityCalculator {
    private const val CHECK_IN_PRIORITY = 0.5f
    private const val COUNTING_PRIORITY = 0.25f
    private const val TIMER_PRIORITY = 0.125f
    private const val GOAL_PRIORITY = 0.0f

    // Completion score constants
    private const val COMPLETED_SCORE = 0.0f
    private const val PENDING_SCORE = 1.0f  // COUNTING当前slot已完成，等待下一slot
    private const val PENDING_METRIC_SCORE = 1.5f  // Completed with pending metric
    private const val UNCOMPLETED_SCORE = 2.0f

    // Distance score constants
    private const val MAX_DISTANCE_MINUTES = 720 // 12 hours
    private const val IN_WINDOW_BONUS = 0.3f // Extra score for being in window
    private const val VALID_PERIOD_END = 23 * 60 // 23:00 = 1380 minutes

    /**
     * Calculates the type priority score for a habit type.
     * Higher values indicate higher priority (but lower weight than completion/distance).
     *
     * Priority order per improved SORT-02:
     * - CHECK_IN: 0.5 (highest - instant completion)
     * - COUNTING: 0.25 (medium - multiple clicks)
     * - TIMER: 0.125 (lowest - needs extended time)
     * - GOAL: 0.0 (never participates in time matching)
     */
    fun getTypePriority(habitType: HabitType): Float {
        return when (habitType) {
            HabitType.CHECK_IN -> CHECK_IN_PRIORITY
            HabitType.COUNTING -> COUNTING_PRIORITY
            HabitType.TIMER -> TIMER_PRIORITY
            HabitType.GOAL -> GOAL_PRIORITY
        }
    }

    /**
     * Calculates distance score based on time distance from current time.
     *
     * Formula: distanceScore = 1.0 * (1 - distanceMinutes / MAX_DISTANCE_MINUTES)
     * - Distance < 720 min: score decreases linearly from 1.0 to 0.0
     * - Distance >= 720 min: score = 0.0
     * - InWindow: gets extra bonus up to +0.3 based on proximity to bestTime
     *
     * @param matchResult Time window match result
     * @param currentMinutes Current time in minutes since midnight
     * @return Distance score (0.0 to 1.3)
     */
    private fun calculateDistanceScore(
        matchResult: TimeMatchResult,
        currentMinutes: Int
    ): Float {
        return when (matchResult) {
            is TimeMatchResult.InWindow -> {
                // In window: base 1.0 + bonus for proximity to bestTime
                val distanceFromBest = kotlin.math.abs(currentMinutes - matchResult.bestTimeMinutes)
                val halfWidth = (matchResult.windowEnd - matchResult.windowStart) / 2
                val bonus = IN_WINDOW_BONUS * (1 - distanceFromBest.toFloat() / halfWidth.toFloat())
                1.0f + bonus.coerceIn(0.0f, IN_WINDOW_BONUS)
            }
            is TimeMatchResult.BeforeWindow -> {
                // Before window: score based on distance to window start
                val distance = matchResult.minutesUntilWindow
                if (distance >= MAX_DISTANCE_MINUTES) 0.0f
                else 1.0f * (1 - distance.toFloat() / MAX_DISTANCE_MINUTES)
            }
            is TimeMatchResult.AfterWindow -> {
                // After window: score based on distance since window end
                val distance = matchResult.minutesSinceWindowEnd
                if (distance >= MAX_DISTANCE_MINUTES) 0.0f
                else 1.0f * (1 - distance.toFloat() / MAX_DISTANCE_MINUTES)
            }
            is TimeMatchResult.NoBestTime -> 0.5f // Neutral score for no preference
        }
    }

    /**
     * Calculates priorities for a list of habits based on completion status and time distance.
     *
     * Five-layer sorting:
     * 1. Completion status - uncompleted (2.0) > pending-metric (1.5) > slot-completed (1.0) > completed (0.0)
     * 2. Time distance (descending) - closer to current time scores higher
     * 3. Window status - InWindow gets bonus, Before/AfterWindow by distance
     * 4. Type priority (descending) - CHECK_IN > COUNTING > TIMER > GOAL
     * 5. Creation order (ascending) - earlier created first when tied
     *
     * COUNTING habits special handling (方案I：Slot级别完成判定):
     * - 当前slot已完成(todayCount > slotIndex)：改为BeforeWindow，等待下一slot
     * - 当前slot未完成：保持InWindow，高优先级
     * - 实现效果：本窗口期打卡一次后脱离本窗口期，下一窗口期重新加入
     *
     * @param habits List of habits to calculate priorities for
     * @param currentTime Current ZonedDateTime for window calculations
     * @param completedTodayCountByHabitId Map of habit ID to today's completion count (actual count, not boolean)
     * @param pendingMetricHabitIds Set of habit IDs with pending metric recording (show "Record" button)
     * @return List of HabitPriority sorted by priority (highest first)
     */
    fun calculatePriorities(
        habits: List<HabitEntity>,
        currentTime: ZonedDateTime,
        completedTodayCountByHabitId: Map<Long, Int> = emptyMap(),
        pendingMetricHabitIds: Set<Long> = emptySet()
    ): List<HabitPriority> {
        val currentMinutes = currentTime.hour * 60 + currentTime.minute

        return habits
            .map { habit ->
                // Get today's completion count
                val todayCount = completedTodayCountByHabitId[habit.id] ?: 0

                // For COUNTING habits, check slot window and completion status (方案I)
                val slotInfo = if (habit.habitType == HabitType.COUNTING && habit.bestTime != null) {
                    val progress = CountingSlotCalculator.getSlotProgress(
                        habit.bestTime,
                        habit.targetValue,
                        currentTime
                    )
                    if (progress != null) {
                        Pair(progress.first - 1, progress.second) // Convert back to 0-based index
                    } else null
                } else null

                // COUNTING habits: check if current slot is completed (方案I)
                val isCurrentSlotCompleted = if (habit.habitType == HabitType.COUNTING && slotInfo != null) {
                    CountingSlotCalculator.isSlotCompleted(slotInfo.first, todayCount)
                } else false

                // Calculate distance score and matchResult (方案I：COUNTING当前slot完成时特殊处理)
                val (distanceScore, matchResult, nextSlotTime) = if (habit.habitType == HabitType.COUNTING && habit.bestTime != null) {
                    calculateCountingScoreAndMatchResult(
                        habit.bestTime,
                        habit.targetValue,
                        currentMinutes,
                        currentTime,
                        slotInfo,
                        isCurrentSlotCompleted,
                        todayCount
                    )
                } else {
                    // Non-COUNTING habits use standard TimeWindowMatcher
                    val result = TimeWindowMatcher.calculateMatch(
                        bestTimeMinutes = habit.bestTime,
                        habitType = habit.habitType,
                        targetValue = habit.targetValue,
                        currentTime = currentTime
                    )
                    Triple(calculateDistanceScore(result, currentMinutes), result, null)
                }

                // Calculate completion status (方案I)
                val hasPendingMetric = pendingMetricHabitIds.contains(habit.id)

                // For non-COUNTING habits, check if fully completed
                val isFullyCompleted = when (habit.habitType) {
                    HabitType.CHECK_IN -> todayCount > 0
                    HabitType.COUNTING -> todayCount >= habit.targetValue  // All slots completed
                    HabitType.TIMER -> todayCount >= habit.targetValue * 60  // Target seconds met
                    HabitType.GOAL -> false  // GOAL habits don't have check-ins
                    else -> todayCount > 0
                }

                // Calculate completion score (方案I：COUNTING当前slot已完成时使用中等得分)
                val completionScore = when {
                    isFullyCompleted && hasPendingMetric -> PENDING_METRIC_SCORE  // 1.5
                    isFullyCompleted -> COMPLETED_SCORE  // 0.0
                    isCurrentSlotCompleted -> PENDING_SCORE  // 1.0 - COUNTING当前slot已完成，等待下一slot
                    else -> UNCOMPLETED_SCORE  // 2.0 - 未完成
                }

                val typePriority = getTypePriority(habit.habitType)

                // Combined score: completion (0/1/1.5/2) + distance (0-1.3) + type (0-0.5)
                val sortScore = completionScore + distanceScore + typePriority

                HabitPriority(
                    habit = habit,
                    matchResult = matchResult,
                    sortScore = sortScore,
                    slotIndex = slotInfo?.first,
                    totalSlots = slotInfo?.second,
                    completedToday = isFullyCompleted,
                    hasPendingMetric = hasPendingMetric,
                    nextSlotTime = nextSlotTime
                )
            }
            .sortedWith(
                compareByDescending<HabitPriority> { it.sortScore }
                    .thenBy { it.habit.createdAt } // Earlier created first when tied
            )
    }

    /**
     * Calculate score and matchResult for COUNTING habits (方案I实现).
     *
     * @param bestTimeMinutes Best time in minutes since midnight
     * @param targetValue Number of slots (target completions)
     * @param currentMinutes Current time in minutes since midnight
     * @param currentTime Current ZonedDateTime for slot calculation
     * @param slotInfo Current slot info (index, total) if in a slot window
     * @param isCurrentSlotCompleted Whether current slot is already completed
     * @param todayCount Number of times completed today
     * @return Triple of (distanceScore, matchResult, nextSlotTime)
     */
    private fun calculateCountingScoreAndMatchResult(
        bestTimeMinutes: Long,
        targetValue: Int,
        currentMinutes: Int,
        currentTime: ZonedDateTime,
        slotInfo: Pair<Int, Int>?,
        isCurrentSlotCompleted: Boolean,
        todayCount: Int
    ): Triple<Float, TimeMatchResult, Int?> {
        // 方案I：当前slot已完成时，改为等待下一slot
        if (isCurrentSlotCompleted && slotInfo != null) {
            val nextSlot = CountingSlotCalculator.getNextUncompletedSlot(
                bestTimeMinutes,
                targetValue,
                todayCount,
                currentTime
            )
            if (nextSlot != null) {
                // Before next slot window
                val distance = (nextSlot.windowStart - currentMinutes).coerceAtLeast(0)
                val distanceScore = if (distance >= MAX_DISTANCE_MINUTES) 0.0f
                    else 1.0f * (1 - distance.toFloat() / MAX_DISTANCE_MINUTES)

                val matchResult = TimeMatchResult.BeforeWindow(
                    minutesUntilWindow = distance,
                    windowStart = nextSlot.windowStart
                )

                return Triple(distanceScore, matchResult, nextSlot.slotTime)
            } else {
                // All slots completed - treat as completed
                return Triple(
                    0.0f,
                    TimeMatchResult.AfterWindow(
                        minutesSinceWindowEnd = currentMinutes - VALID_PERIOD_END,
                        windowEnd = VALID_PERIOD_END
                    ),
                    null
                )
            }
        }

        // 当前slot未完成且在窗口内：InWindow
        if (slotInfo != null) {
            val slots = CountingSlotCalculator.calculateSlots(bestTimeMinutes, targetValue, currentTime)
            val currentSlot = slots.find { it.index == slotInfo.first }
            if (currentSlot != null) {
                return Triple(
                    1.0f + IN_WINDOW_BONUS,
                    TimeMatchResult.InWindow(
                        score = 1.0f,
                        windowStart = currentSlot.windowStart,
                        windowEnd = currentSlot.windowEnd,
                        currentTimeMinutes = currentMinutes,
                        bestTimeMinutes = currentSlot.slotTime
                    ),
                    null
                )
            }
        }

        // Not in any slot window: standard calculation
        val distanceScore = calculateCountingDistanceScore(
            bestTimeMinutes = bestTimeMinutes,
            targetValue = targetValue,
            currentMinutes = currentMinutes,
            currentTime = currentTime,
            isInSlotWindow = false
        )

        val matchResult = calculateNearestSlotMatchResult(bestTimeMinutes, targetValue, currentMinutes, currentTime)

        // Get next slot time for display
        val nextSlot = CountingSlotCalculator.getNextUncompletedSlot(bestTimeMinutes, targetValue, todayCount, currentTime)

        return Triple(distanceScore, matchResult, nextSlot?.slotTime)
    }

    /**
     * Calculate distance score for COUNTING habits considering all slot windows.
     *
     * @param bestTimeMinutes Best time in minutes since midnight
     * @param targetValue Number of slots (target completions)
     * @param currentMinutes Current time in minutes since midnight
     * @param currentTime Current ZonedDateTime for slot calculation
     * @param isInSlotWindow Whether currently in a slot window
     * @return Distance score (0.0 to 1.3)
     */
    private fun calculateCountingDistanceScore(
        bestTimeMinutes: Long,
        targetValue: Int,
        currentMinutes: Int,
        currentTime: ZonedDateTime,
        isInSlotWindow: Boolean
    ): Float {
        if (isInSlotWindow) {
            // In a slot window: same score as InWindow
            return 1.0f + IN_WINDOW_BONUS
        }

        // Not in any slot window: calculate distance to nearest slot
        val slots = CountingSlotCalculator.calculateSlots(bestTimeMinutes, targetValue, currentTime)

        // Find nearest upcoming slot (BeforeWindow logic)
        val nearestFutureSlot = slots.filter { !it.isPast && !it.isCurrent }.minByOrNull {
            it.windowStart - currentMinutes
        }

        // Find nearest past slot (AfterWindow logic)
        val nearestPastSlot = slots.filter { it.isPast }.maxByOrNull {
            currentMinutes - it.windowEnd
        }

        // Use nearest slot for distance calculation
        val distance = when {
            nearestFutureSlot != null -> {
                // Before nearest slot window
                (nearestFutureSlot.windowStart - currentMinutes).coerceAtLeast(0)
            }
            nearestPastSlot != null -> {
                // After all slot windows (past the last one)
                (currentMinutes - nearestPastSlot.windowEnd).coerceAtLeast(0)
            }
            else -> MAX_DISTANCE_MINUTES // No slots found
        }

        return if (distance >= MAX_DISTANCE_MINUTES) 0.0f
        else 1.0f * (1 - distance.toFloat() / MAX_DISTANCE_MINUTES)
    }

    /**
     * Create TimeMatchResult based on nearest slot for COUNTING habits not in any slot window.
     */
    private fun calculateNearestSlotMatchResult(
        bestTimeMinutes: Long,
        targetValue: Int,
        currentMinutes: Int,
        currentTime: ZonedDateTime
    ): TimeMatchResult {
        val slots = CountingSlotCalculator.calculateSlots(bestTimeMinutes, targetValue, currentTime)

        // Find nearest upcoming slot
        val nearestFutureSlot = slots.filter { !it.isPast && !it.isCurrent }.minByOrNull {
            it.windowStart - currentMinutes
        }

        // Find nearest past slot
        val nearestPastSlot = slots.filter { it.isPast }.maxByOrNull {
            currentMinutes - it.windowEnd
        }

        return when {
            nearestFutureSlot != null -> {
                TimeMatchResult.BeforeWindow(
                    minutesUntilWindow = nearestFutureSlot.windowStart - currentMinutes,
                    windowStart = nearestFutureSlot.windowStart
                )
            }
            nearestPastSlot != null -> {
                TimeMatchResult.AfterWindow(
                    minutesSinceWindowEnd = currentMinutes - nearestPastSlot.windowEnd,
                    windowEnd = nearestPastSlot.windowEnd
                )
            }
            else -> TimeMatchResult.NoBestTime
        }
    }
}