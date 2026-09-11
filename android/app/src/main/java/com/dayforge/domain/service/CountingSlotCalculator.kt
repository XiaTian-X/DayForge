package com.dayforge.domain.service

import java.time.ZonedDateTime

/**
 * Represents a single time slot for a COUNTING habit.
 * COUNTING habits are split into multiple slots based on targetValue.
 */
data class CountingSlot(
    val index: Int, // Slot number (0-based)
    val slotTime: Int, // Slot start time in minutes since midnight
    val windowStart: Int, // Window start (slotTime - 15)
    val windowEnd: Int, // Window end (slotTime + 15, capped at 23:00 boundary)
    val isCurrent: Boolean, // Whether current time falls within this slot's window
    val isPast: Boolean // Whether this slot's window has already passed
)

/**
 * Calculates time slots for COUNTING habits.
 *
 * Per SORT-03:
 * - Valid period: 07:00-23:00 (16 hours)
 * - Interval = 16 hours / targetValue (in minutes)
 * - Slots: bestTime + interval * n for n in 0..targetValue-1
 *
 * Per SORT-05:
 * - Last slot capped at 23:00 (1380 minutes)
 * - Window end for any slot capped at 23:00
 *
 * Per SORT-06:
 * - All calculations use ZonedDateTime for DST-safe handling
 */
object CountingSlotCalculator {
    private const val VALID_PERIOD_START = 7 * 60 // 07:00 = 420 minutes
    private const val VALID_PERIOD_END = 23 * 60 // 23:00 = 1380 minutes
    private const val VALID_PERIOD_DURATION = VALID_PERIOD_END - VALID_PERIOD_START // 16 hours = 960 minutes
    private const val SLOT_WINDOW_HALF_WIDTH = 15 // ±15 minutes per slot

    /**
     * Calculates time slots for a COUNTING habit.
     *
     * @param bestTimeMinutes Best execution time in minutes since midnight
     * @param targetValue Number of target completions (determines slot count)
     * @param currentTime Current ZonedDateTime for determining current/past status
     * @return List of CountingSlot ordered by time
     */
    fun calculateSlots(
        bestTimeMinutes: Long,
        targetValue: Int,
        currentTime: ZonedDateTime
    ): List<CountingSlot> {
        if (targetValue <= 0) return emptyList()

        val bestTime = bestTimeMinutes.toInt()
        val currentMinutes = currentTime.hour * 60 + currentTime.minute

        // Calculate interval between slots
        // If bestTime is outside valid period, adjust it
        val adjustedBestTime = bestTime.coerceIn(VALID_PERIOD_START, VALID_PERIOD_END)

        // Interval in minutes between each slot
        // For targetValue=8, interval = 960/8 = 120 minutes (2 hours)
        val intervalMinutes = VALID_PERIOD_DURATION / targetValue

        return (0 until targetValue).map { slotIndex ->
            // Calculate slot time, capping at 23:00 boundary
            val rawSlotTime = adjustedBestTime + intervalMinutes * slotIndex
            val slotTime = rawSlotTime.coerceAtMost(VALID_PERIOD_END)

            // Calculate window boundaries (±15 minutes)
            val rawWindowStart = slotTime - SLOT_WINDOW_HALF_WIDTH
            val rawWindowEnd = slotTime + SLOT_WINDOW_HALF_WIDTH

            // Cap window end at 23:00 for midnight boundary handling
            val windowStart = rawWindowStart.coerceAtLeast(VALID_PERIOD_START)
            val windowEnd = rawWindowEnd.coerceAtMost(VALID_PERIOD_END)

            // Determine slot status
            val isPast = currentMinutes > windowEnd
            val isCurrent = currentMinutes in windowStart..windowEnd

            CountingSlot(
                index = slotIndex,
                slotTime = slotTime,
                windowStart = windowStart,
                windowEnd = windowEnd,
                isCurrent = isCurrent,
                isPast = isPast
            )
        }
    }

    /**
     * Finds the current slot for a COUNTING habit at the given time.
     * Returns the slot whose window contains the current time, or null if not in any slot window.
     *
     * @param bestTimeMinutes Best execution time in minutes since midnight
     * @param targetValue Number of target completions
     * @param currentTime Current ZonedDateTime
     * @return Current CountingSlot or null if not within any slot window
     */
    fun getCurrentSlot(
        bestTimeMinutes: Long,
        targetValue: Int,
        currentTime: ZonedDateTime
    ): CountingSlot? {
        val slots = calculateSlots(bestTimeMinutes, targetValue, currentTime)
        return slots.find { it.isCurrent }
    }

    /**
     * Gets the slot progress info for display.
     * Format: "第 X 个/共 Y 个" where X is current slot index + 1, Y is total slots.
     *
     * @param bestTimeMinutes Best execution time in minutes since midnight
     * @param targetValue Number of target completions
     * @param currentTime Current ZonedDateTime
     * @return Pair of (currentSlotIndex, totalSlots) or null if not in any slot
     */
    fun getSlotProgress(
        bestTimeMinutes: Long,
        targetValue: Int,
        currentTime: ZonedDateTime
    ): Pair<Int, Int>? {
        val currentSlot = getCurrentSlot(bestTimeMinutes, targetValue, currentTime)
        return if (currentSlot != null) {
            Pair(currentSlot.index + 1, targetValue) // +1 for human-friendly "第X个"
        } else null
    }

    /**
     * Gets the next uncompleted slot for a COUNTING habit.
     * Used when current slot is completed, to show "waiting for next slot".
     *
     * @param bestTimeMinutes Best execution time in minutes since midnight
     * @param targetValue Number of target completions
     * @param completedToday Number of times completed today (0-based comparison: completedToday > slotIndex means slot done)
     * @param currentTime Current ZonedDateTime
     * @return Next uncompleted CountingSlot or null if all slots completed
     */
    fun getNextUncompletedSlot(
        bestTimeMinutes: Long,
        targetValue: Int,
        completedToday: Int,
        currentTime: ZonedDateTime
    ): CountingSlot? {
        val slots = calculateSlots(bestTimeMinutes, targetValue, currentTime)
        // 找到第一个未完成的slot：slotIndex >= completedToday 且不是过去的
        // 注意：completedToday是已完成次数（1,2,3...），slotIndex是0-based（0,1,2...）
        // 所以 slot已完成 = completedToday > slotIndex
        return slots
            .filter { slot -> slot.index >= completedToday && !slot.isPast }
            .minByOrNull { it.index }
    }

    /**
     * Checks if current slot is completed based on completed count.
     *
     * @param currentSlotIndex Current slot index (0-based)
     * @param completedToday Number of times completed today
     * @return true if current slot is completed (completedToday > currentSlotIndex)
     */
    fun isSlotCompleted(currentSlotIndex: Int, completedToday: Int): Boolean {
        return completedToday > currentSlotIndex
    }
}