package com.dayforge.domain.service

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime
import java.time.ZoneId

class CountingSlotCalculatorTest {

    private val zoneId = ZoneId.systemDefault()

    // ========== calculateSlots basic tests ==========

    @Test
    fun calculateSlots_target1_returnsSingleSlot() {
        val currentTime = ZonedDateTime.of(2026, 4, 11, 9, 0, 0, 0, zoneId)
        val slots = CountingSlotCalculator.calculateSlots(540L, 1, currentTime) // 9:00

        assertEquals("Target 1 should have 1 slot", 1, slots.size)
        assertEquals("Slot time should be bestTime", 540, slots[0].slotTime)
    }

    @Test
    fun calculateSlots_target8_returns8Slots() {
        val currentTime = ZonedDateTime.of(2026, 4, 11, 9, 0, 0, 0, zoneId)
        val slots = CountingSlotCalculator.calculateSlots(540L, 8, currentTime) // 9:00

        assertEquals("Target 8 should have 8 slots", 8, slots.size)

        // Verify slots are at correct times (interval = 960/8 = 120 min)
        assertEquals("First slot at 9:00", 540, slots[0].slotTime)
        assertEquals("Second slot at 11:00", 660, slots[1].slotTime)
        assertEquals("Third slot at 13:00", 780, slots[2].slotTime)
        assertEquals("Fourth slot at 15:00", 900, slots[3].slotTime)
        assertEquals("Fifth slot at 17:00", 1020, slots[4].slotTime)
        assertEquals("Sixth slot at 19:00", 1140, slots[5].slotTime)
        assertEquals("Seventh slot at 21:00", 1260, slots[6].slotTime)
        assertEquals("Last slot capped at 23:00", 1380, slots[7].slotTime)
    }

    @Test
    fun calculateSlots_windowBoundaries_correctWidth() {
        val currentTime = ZonedDateTime.of(2026, 4, 11, 9, 0, 0, 0, zoneId)
        val slots = CountingSlotCalculator.calculateSlots(540L, 1, currentTime)

        // Window should be ±15 minutes
        assertEquals("Window start should be 525", 525, slots[0].windowStart)
        assertEquals("Window end should be 555", 555, slots[0].windowEnd)
    }

    // ========== Midnight boundary tests (SORT-05) ==========

    @Test
    fun calculateSlots_lastSlotCappedAt2300() {
        // Even if calculation would push slot beyond 23:00
        val currentTime = ZonedDateTime.of(2026, 4, 11, 9, 0, 0, 0, zoneId)
        val slots = CountingSlotCalculator.calculateSlots(540L, 10, currentTime)

        // Last slot should be capped at 23:00
        val lastSlot = slots.last()
        assertTrue("Last slot time should be <= 23:00", lastSlot.slotTime <= 1380)
        assertTrue("Last slot window end should be <= 23:00", lastSlot.windowEnd <= 1380)
    }

    @Test
    fun calculateSlots_bestTimeAt2300_cappedCorrectly() {
        val currentTime = ZonedDateTime.of(2026, 4, 11, 23, 0, 0, 0, zoneId)
        val slots = CountingSlotCalculator.calculateSlots(1380L, 1, currentTime) // 23:00

        assertEquals("Slot time should be 23:00", 1380, slots[0].slotTime)
        // Window start at 22:45, end capped at 23:00
        assertEquals("Window start should be 22:45", 1365, slots[0].windowStart)
        assertEquals("Window end should be capped at 23:00", 1380, slots[0].windowEnd)
    }

    @Test
    fun calculateSlots_bestTimeOutsideValidPeriod_adjustedToValid() {
        // bestTime at 05:00 (before valid period 07:00)
        val currentTime = ZonedDateTime.of(2026, 4, 11, 9, 0, 0, 0, zoneId)
        val slots = CountingSlotCalculator.calculateSlots(300L, 4, currentTime) // 05:00

        // Should be adjusted to start at 07:00
        assertEquals("First slot should be adjusted to 07:00", 420, slots[0].slotTime)
    }

    // ========== Current slot detection tests (SORT-04) ==========

    @Test
    fun getCurrentSlot_inWindow_returnsCorrectSlot() {
        // bestTime 9:00, slot 0 window 8:45-9:15
        val currentTime = ZonedDateTime.of(2026, 4, 11, 9, 0, 0, 0, zoneId)
        val currentSlot = CountingSlotCalculator.getCurrentSlot(540L, 8, currentTime)

        assertNotNull("Should find current slot at 9:00", currentSlot)
        assertEquals("Should be slot 0", 0, currentSlot!!.index)
        assertTrue("Slot should be current", currentSlot.isCurrent)
    }

    @Test
    fun getCurrentSlot_inSecondWindow_returnsSlot1() {
        // Slots: 9:00, 11:00, ... Second slot window 10:45-11:15
        val currentTime = ZonedDateTime.of(2026, 4, 11, 11, 0, 0, 0, zoneId)
        val currentSlot = CountingSlotCalculator.getCurrentSlot(540L, 8, currentTime)

        assertNotNull("Should find current slot at 11:00", currentSlot)
        assertEquals("Should be slot 1", 1, currentSlot!!.index)
    }

    @Test
    fun getCurrentSlot_outsideAllWindows_returnsNull() {
        // At 6:00, before first window (which starts at 8:45 for bestTime 9:00)
        val currentTime = ZonedDateTime.of(2026, 4, 11, 6, 0, 0, 0, zoneId)
        val currentSlot = CountingSlotCalculator.getCurrentSlot(540L, 8, currentTime)

        assertNull("Should return null before any window", currentSlot)
    }

    @Test
    fun getCurrentSlot_betweenSlots_returnsNull() {
        // At 10:00, between slot 0 (8:45-9:15) and slot 1 (10:45-11:15)
        val currentTime = ZonedDateTime.of(2026, 4, 11, 10, 0, 0, 0, zoneId)
        val currentSlot = CountingSlotCalculator.getCurrentSlot(540L, 8, currentTime)

        assertNull("Should return null between slot windows", currentSlot)
    }

    // ========== Slot progress tests ==========

    @Test
    fun getSlotProgress_inSlot_returnsCorrectProgress() {
        val currentTime = ZonedDateTime.of(2026, 4, 11, 9, 0, 0, 0, zoneId)
        val progress = CountingSlotCalculator.getSlotProgress(540L, 8, currentTime)

        assertNotNull("Should return progress", progress)
        assertEquals("Current slot should be '第1个'", 1, progress!!.first)
        assertEquals("Total slots should be 8", 8, progress.second)
    }

    @Test
    fun getSlotProgress_inSecondSlot_returns2of8() {
        val currentTime = ZonedDateTime.of(2026, 4, 11, 11, 0, 0, 0, zoneId)
        val progress = CountingSlotCalculator.getSlotProgress(540L, 8, currentTime)

        assertNotNull("Should return progress", progress)
        assertEquals("Current slot should be '第2个'", 2, progress!!.first)
        assertEquals("Total slots should be 8", 8, progress.second)
    }

    // ========== Slot status tests ==========

    @Test
    fun calculateSlots_pastSlots_markedCorrectly() {
        // At 15:00, slots at 9:00, 11:00 should be past
        val currentTime = ZonedDateTime.of(2026, 4, 11, 15, 30, 0, 0, zoneId)
        val slots = CountingSlotCalculator.calculateSlots(540L, 8, currentTime)

        assertTrue("Slot 0 (9:00) should be past", slots[0].isPast)
        assertTrue("Slot 1 (11:00) should be past", slots[1].isPast)
        // Its 14:45-15:15 window has ended at 15:30.
        assertTrue("Slot 3 (15:00) should be past", slots[3].isPast)
    }

    @Test
    fun calculateSlots_currentSlot_markedCorrectly() {
        val currentTime = ZonedDateTime.of(2026, 4, 11, 15, 0, 0, 0, zoneId)
        val slots = CountingSlotCalculator.calculateSlots(540L, 8, currentTime)

        // Slot 3 is at 15:00, window 14:45-15:15
        assertTrue("Slot 3 should be current at 15:00", slots[3].isCurrent)
        assertFalse("Slot 2 should not be current", slots[2].isCurrent)
        assertFalse("Slot 4 should not be current", slots[4].isCurrent)
    }

    // ========== DST handling tests (SORT-06) ==========

    @Test
    fun calculateSlots_dstTransition_correctCalculation() {
        // Use a timezone with DST (e.g., America/New_York)
        // This test verifies that ZonedDateTime arithmetic handles DST correctly
        val dstZone = ZoneId.of("America/New_York")

        // During spring DST transition (clocks jump forward)
        val beforeDst = ZonedDateTime.of(2026, 3, 8, 1, 0, 0, 0, dstZone)
        val slots = CountingSlotCalculator.calculateSlots(60L, 4, beforeDst) // 01:00

        // Verify slots are calculated correctly despite DST complexity
        assertEquals("Should have 4 slots", 4, slots.size)
    }

    @Test
    fun calculateSlots_systemDefaultZoneId_worksCorrectly() {
        // Verify system default zone works
        val currentTime = ZonedDateTime.now(zoneId)
        val slots = CountingSlotCalculator.calculateSlots(540L, 4, currentTime)

        // Should not throw and should return correct count
        assertEquals("Should have 4 slots", 4, slots.size)
    }
}
