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

        // Preserve every logical slot, without allocating targetValue objects. Consumers which
        // iterate time boundaries use distinctSlots; quantities and slot indices are not capped.
        return object : AbstractList<CountingSlot>() {
            override val size = targetValue
            override fun get(index: Int): CountingSlot {
                if (index !in 0 until size) throw IndexOutOfBoundsException("Slot $index outside target $size")
                return requireNotNull(slotAt(bestTimeMinutes, targetValue, index, currentTime))
            }
        }
    }

    fun slotAt(bestTimeMinutes: Long, targetValue: Int, index: Int, currentTime: ZonedDateTime): CountingSlot? {
        if (targetValue <= 0 || index !in 0 until targetValue) return null
        val bestTime = bestTimeMinutes.coerceIn(VALID_PERIOD_START.toLong(), VALID_PERIOD_END.toLong()).toInt()
        val slotTime = (bestTime.toLong() + (VALID_PERIOD_DURATION / targetValue).toLong() * index)
            .coerceAtMost(VALID_PERIOD_END.toLong()).toInt()
        val start = (slotTime - SLOT_WINDOW_HALF_WIDTH).coerceAtLeast(VALID_PERIOD_START)
        val end = (slotTime + SLOT_WINDOW_HALF_WIDTH).coerceAtMost(VALID_PERIOD_END)
        val minutes = currentTime.hour * 60 + currentTime.minute
        return CountingSlot(index, slotTime, start, end, minutes in start..end, minutes > end)
    }

    /** At most 961 distinct minute windows, retaining the first original index for each. */
    fun distinctSlots(bestTimeMinutes: Long, targetValue: Int, currentTime: ZonedDateTime): List<CountingSlot> {
        if (targetValue <= 0) return emptyList()
        val result = ArrayList<CountingSlot>()
        var index = 0
        while (index < targetValue) {
            val slot = requireNotNull(slotAt(bestTimeMinutes, targetValue, index, currentTime))
            result += slot
            index = lowerBound(targetValue) {
                requireNotNull(slotAt(bestTimeMinutes, targetValue, it, currentTime)).slotTime > slot.slotTime
            }
        }
        return result
    }

    /** Merge equal reminder starts, including the 07:00 clamp; keep all original indices. */
    fun reminderWindows(bestTimeMinutes: Long, targetValue: Int, currentTime: ZonedDateTime): List<CountingReminderWindow> {
        if (targetValue <= 0) return emptyList()
        return distinctSlots(bestTimeMinutes, targetValue, currentTime).distinctBy { it.windowStart }.map { slot ->
            val after = lowerBound(targetValue) {
                requireNotNull(slotAt(bestTimeMinutes, targetValue, it, currentTime)).windowStart > slot.windowStart
            }
            CountingReminderWindow(slot.windowStart, slot.index, after - 1)
        }
    }

    private fun lowerBound(size: Int, matches: (Int) -> Boolean): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = low + (high - low) / 2
            if (matches(mid)) high = mid else low = mid + 1
        }
        return low
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
        if (targetValue <= 0) return null
        val minutes = currentTime.hour * 60 + currentTime.minute
        val index = lowerBound(targetValue) {
            requireNotNull(slotAt(bestTimeMinutes, targetValue, it, currentTime)).windowEnd >= minutes
        }
        return slotAt(bestTimeMinutes, targetValue, index, currentTime)?.takeIf { it.isCurrent }
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
        if (targetValue <= 0) return null
        val minutes = currentTime.hour * 60 + currentTime.minute
        val firstNotPast = lowerBound(targetValue) {
            requireNotNull(slotAt(bestTimeMinutes, targetValue, it, currentTime)).windowEnd >= minutes
        }
        return slotAt(bestTimeMinutes, targetValue, maxOf(firstNotPast, completedToday.coerceAtLeast(0)), currentTime)
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

data class CountingReminderWindow(val minute: Int, val firstIndex: Int, val lastIndex: Int)
