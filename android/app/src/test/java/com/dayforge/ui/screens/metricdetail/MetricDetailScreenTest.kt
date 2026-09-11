package com.dayforge.ui.screens.metricdetail

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for MetricDetailScreen.
 * Tests the UI state structure and helper functions.
 */
class MetricDetailScreenTest {
    @Test
    fun uiState_hasRequiredFields() {
        // Verify UiState structure - logs field is required for TrendChart
        val uiState = MetricDetailUiState(
            metric = null,
            logs = emptyList(),
            isLoading = true
        )

        assertNotNull(uiState)
        assertTrue(uiState.logs.isEmpty())
        assertNull(uiState.metric)
        assertTrue(uiState.isLoading)
    }

    @Test
    fun uiState_defaultValues() {
        // Verify default values match expected behavior
        val uiState = MetricDetailUiState()

        assertNull(uiState.metric)
        assertNull(uiState.latestValue)
        assertTrue(uiState.logs.isEmpty())
        assertTrue(uiState.links.isEmpty())
        assertTrue(uiState.isLoading)
        assertFalse(uiState.isDeleted)
    }

    @Test
    fun habitMetricLinkWithHabit_containsRequiredData() {
        // Verify link with habit data structure
        val link = com.dayforge.data.local.entity.HabitMetricLinkEntity(
            habitId = 1L,
            habitUuid = "test-uuid",
            metricId = 1L,
            metricUuid = "metric-uuid"
        )
        val linkWithHabit = HabitMetricLinkWithHabit(
            link = link,
            habitName = "Test Habit"
        )

        assertEquals(1L, linkWithHabit.link.habitId)
        assertEquals("Test Habit", linkWithHabit.habitName)
    }

    @Test
    fun habitForLinking_tracksSelectionState() {
        // Verify habit for linking structure
        val habit = HabitForLinking(
            id = 1L,
            name = "Morning Run",
            isAlreadyLinked = false
        )

        assertEquals(1L, habit.id)
        assertEquals("Morning Run", habit.name)
        assertFalse(habit.isAlreadyLinked)
    }
}