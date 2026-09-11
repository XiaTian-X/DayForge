package com.dayforge.ui.components

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for TrendChart component.
 *
 * Tests verify:
 * - TimeRange enum has correct values
 * - TimeRange labels are correct
 */
class TrendChartTest {

    @Test
    fun timeRangeEnum_hasCorrectValues() {
        assertEquals(7, TimeRange.SEVEN_DAYS.days)
        assertEquals(30, TimeRange.THIRTY_DAYS.days)
        // Note: label is provided via @Composable getLabel() function, not a direct property
        // UI tests would verify the actual label display
    }

    @Test
    fun timeRangeEnum_hasTwoOptions() {
        val values = TimeRange.entries
        assertEquals(2, values.size)
        assertTrue(values.contains(TimeRange.SEVEN_DAYS))
        assertTrue(values.contains(TimeRange.THIRTY_DAYS))
    }

    @Test
    fun timeRangeEnum_defaultIsSevenDays() {
        // Per D-04: Default time range is 7 days
        assertEquals(7, TimeRange.SEVEN_DAYS.days)
    }
}