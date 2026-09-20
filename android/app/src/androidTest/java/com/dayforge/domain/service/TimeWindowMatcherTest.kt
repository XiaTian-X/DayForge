package com.dayforge.domain.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

import com.dayforge.data.model.HabitType
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class TimeWindowMatcherTest {

    private val zoneId = ZoneId.systemDefault()

    // ========== calculateWindowWidth tests ==========

    @Test
    fun calculateWindowWidth_checkIn_returns30Minutes() {
        val width = TimeWindowMatcher.calculateWindowWidth(HabitType.CHECK_IN, 1)
        assertEquals("CHECK_IN should have 30 minute window", 30, width)
    }

    @Test
    fun calculateWindowWidth_counting_returns30Minutes() {
        val width = TimeWindowMatcher.calculateWindowWidth(HabitType.COUNTING, 5)
        assertEquals("COUNTING should have 30 minute window", 30, width)
    }

    @Test
    fun calculateWindowWidth_timer_target5_returns10Minutes() {
        val width = TimeWindowMatcher.calculateWindowWidth(HabitType.TIMER, 5)
        assertEquals("TIMER with target 5 should have 10 minute window", 10, width)
    }

    @Test
    fun calculateWindowWidth_timer_target30_returns60Minutes() {
        val width = TimeWindowMatcher.calculateWindowWidth(HabitType.TIMER, 30)
        assertEquals("TIMER with target 30 should have 60 minute window", 60, width)
    }

    @Test
    fun calculateWindowWidth_goal_returns0() {
        val width = TimeWindowMatcher.calculateWindowWidth(HabitType.GOAL, 1)
        assertEquals("GOAL should have 0 window width", 0, width)
    }

    // ========== calculateMatch - NoBestTime tests ==========

    @Test
    fun calculateMatch_nullBestTime_returnsNoBestTime() {
        val currentTime = ZonedDateTime.of(2026, 4, 11, 9, 0, 0, 0, zoneId)
        val result = TimeWindowMatcher.calculateMatch(null, HabitType.CHECK_IN, 1, currentTime)

        assertTrue("Null bestTime should return NoBestTime", result is TimeMatchResult.NoBestTime)
    }

    @Test
    fun calculateMatch_goalType_returnsNoBestTime() {
        val currentTime = ZonedDateTime.of(2026, 4, 11, 9, 0, 0, 0, zoneId)
        val result = TimeWindowMatcher.calculateMatch(540L, HabitType.GOAL, 1, currentTime)

        assertTrue("GOAL type should return NoBestTime", result is TimeMatchResult.NoBestTime)
    }

    // ========== calculateMatch - InWindow tests ==========

    @Test
    fun calculateMatch_exactBestTime_returnsScore1() {
        val currentTime = ZonedDateTime.of(2026, 4, 11, 9, 0, 0, 0, zoneId)
        val result = TimeWindowMatcher.calculateMatch(540L, HabitType.CHECK_IN, 1, currentTime)

        assertTrue("At bestTime should be InWindow", result is TimeMatchResult.InWindow)
        val inWindow = result as TimeMatchResult.InWindow
        assertEquals("Score at bestTime should be 1.0", 1.0f, inWindow.score, 0.01f)
    }

    @Test
    fun calculateMatch_atWindowEdge_returnsScore0() {
        // bestTime = 540 (9:00), window = ±15, so edge at 8:45 (525) and 9:15 (555)
        val currentTime = ZonedDateTime.of(2026, 4, 11, 8, 45, 0, 0, zoneId)
        val result = TimeWindowMatcher.calculateMatch(540L, HabitType.CHECK_IN, 1, currentTime)

        assertTrue("At window edge should be InWindow", result is TimeMatchResult.InWindow)
        val inWindow = result as TimeMatchResult.InWindow
        assertEquals("Score at edge should be ~0.0", 0.0f, inWindow.score, 0.1f)
    }

    @Test
    fun calculateMatch_midWindow_returnsCorrectScore() {
        // bestTime = 540 (9:00), window = ±15, mid at 8:52 (532)
        val currentTime = ZonedDateTime.of(2026, 4, 11, 8, 52, 0, 0, zoneId)
        val result = TimeWindowMatcher.calculateMatch(540L, HabitType.CHECK_IN, 1, currentTime)

        assertTrue("In window should be InWindow", result is TimeMatchResult.InWindow)
        val inWindow = result as TimeMatchResult.InWindow
        // Distance from best = 8 minutes, halfWidth = 15, score = 1 - 8/15 = ~0.47
        assertTrue("Score should be between 0 and 1", inWindow.score > 0.0f && inWindow.score < 1.0f)
    }

    @Test
    fun calculateMatch_timerInWindow_calculatesCorrectly() {
        // TIMER with target 30 minutes: window ±30, bestTime at 10:00 (600)
        val currentTime = ZonedDateTime.of(2026, 4, 11, 10, 15, 0, 0, zoneId)
        val result = TimeWindowMatcher.calculateMatch(600L, HabitType.TIMER, 30, currentTime)

        assertTrue("In window should be InWindow", result is TimeMatchResult.InWindow)
        val inWindow = result as TimeMatchResult.InWindow
        // Distance = 15 minutes, halfWidth = 30, score = 1 - 15/30 = 0.5
        assertEquals("Score at mid-window should be 0.5", 0.5f, inWindow.score, 0.01f)
    }

    // ========== calculateMatch - BeforeWindow tests ==========

    @Test
    fun calculateMatch_beforeWindow_returnsBeforeWindow() {
        // bestTime = 540 (9:00), window starts at 8:45 (525)
        val currentTime = ZonedDateTime.of(2026, 4, 11, 8, 30, 0, 0, zoneId)
        val result = TimeWindowMatcher.calculateMatch(540L, HabitType.CHECK_IN, 1, currentTime)

        assertTrue("Before window should return BeforeWindow", result is TimeMatchResult.BeforeWindow)
        val before = result as TimeMatchResult.BeforeWindow
        assertEquals("Window start should be 525", 525, before.windowStart)
        assertEquals("Minutes until should be 15", 15, before.minutesUntilWindow)
    }

    // ========== calculateMatch - AfterWindow tests ==========

    @Test
    fun calculateMatch_afterWindow_returnsAfterWindow() {
        // bestTime = 540 (9:00), window ends at 9:15 (555)
        val currentTime = ZonedDateTime.of(2026, 4, 11, 9, 30, 0, 0, zoneId)
        val result = TimeWindowMatcher.calculateMatch(540L, HabitType.CHECK_IN, 1, currentTime)

        assertTrue("After window should return AfterWindow", result is TimeMatchResult.AfterWindow)
        val after = result as TimeMatchResult.AfterWindow
        assertEquals("Window end should be 555", 555, after.windowEnd)
        assertEquals("Minutes since should be 15", 15, after.minutesSinceWindowEnd)
    }
}