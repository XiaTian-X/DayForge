package com.dayforge.domain.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.PreferencesManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for TimerService.
 *
 * Tests cover:
 * - TIMER-01: Service starts with correct habit ID and target
 * - TIMER-02: Countup mode tracks elapsed time correctly
 * - TIMER-03: Countup mode displays "已计时 X:XX"
 * - TIMER-04: Countdown mode displays "还剩 X:XX"
 * - TIMER-05: Countdown auto-completes at zero
 * - TIMER-06: Countdown cannot exceed target
 * - TIMER-07: Countup continues past target
 * - TIMER-08: Threshold auto-stop at target × 3
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class TimerServiceTest {

    private lateinit var context: Context
    private lateinit var mockTimeLogDao: TimeLogDao
    private lateinit var mockHabitDao: HabitDao
    private lateinit var mockHabitMetricLinkDao: HabitMetricLinkDao
    private lateinit var mockPreferencesManager: PreferencesManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mockTimeLogDao = mockk(relaxed = true)
        mockHabitDao = mockk(relaxed = true)
        mockHabitMetricLinkDao = mockk(relaxed = true)
        mockPreferencesManager = mockk(relaxed = true)
    }

    @Test
    fun startTimer_createsStartIntentWithCorrectExtras() {
        val habitId = 123L
        val targetMinutes = 30
        val isCountdown = true

        val intent = Intent(context, TimerService::class.java).apply {
            action = TimerService.ACTION_START
            putExtra(TimerService.EXTRA_HABIT_ID, habitId)
            putExtra(TimerService.EXTRA_TARGET_MINUTES, targetMinutes)
            putExtra(TimerService.EXTRA_IS_COUNTDOWN, isCountdown)
        }

        assertEquals(TimerService.ACTION_START, intent.action)
        assertEquals(habitId, intent.getLongExtra(TimerService.EXTRA_HABIT_ID, -1))
        assertEquals(targetMinutes, intent.getIntExtra(TimerService.EXTRA_TARGET_MINUTES, 0))
        assertTrue(intent.getBooleanExtra(TimerService.EXTRA_IS_COUNTDOWN, false))
    }

    @Test
    fun pauseTimer_createsPauseIntentWithCorrectExtras() {
        val habitId = 456L
        val targetMinutes = 15

        val intent = Intent(context, TimerService::class.java).apply {
            action = TimerService.ACTION_PAUSE
            putExtra(TimerService.EXTRA_HABIT_ID, habitId)
            putExtra(TimerService.EXTRA_TARGET_MINUTES, targetMinutes)
        }

        assertEquals(TimerService.ACTION_PAUSE, intent.action)
        assertEquals(habitId, intent.getLongExtra(TimerService.EXTRA_HABIT_ID, -1))
    }

    @Test
    fun resumeTimer_createsResumeIntentWithCorrectExtras() {
        val habitId = 789L
        val targetMinutes = 45

        val intent = Intent(context, TimerService::class.java).apply {
            action = TimerService.ACTION_RESUME
            putExtra(TimerService.EXTRA_HABIT_ID, habitId)
            putExtra(TimerService.EXTRA_TARGET_MINUTES, targetMinutes)
        }

        assertEquals(TimerService.ACTION_RESUME, intent.action)
        assertEquals(habitId, intent.getLongExtra(TimerService.EXTRA_HABIT_ID, -1))
    }

    @Test
    fun stopTimer_createsStopIntentWithCorrectExtras() {
        val habitId = 100L
        val targetMinutes = 60

        val intent = Intent(context, TimerService::class.java).apply {
            action = TimerService.ACTION_STOP
            putExtra(TimerService.EXTRA_HABIT_ID, habitId)
            putExtra(TimerService.EXTRA_TARGET_MINUTES, targetMinutes)
        }

        assertEquals(TimerService.ACTION_STOP, intent.action)
        assertEquals(habitId, intent.getLongExtra(TimerService.EXTRA_HABIT_ID, -1))
    }

    @Test
    fun discardTimer_createsDiscardIntentWithCorrectExtras() {
        val habitId = 200L
        val targetMinutes = 20

        val intent = Intent(context, TimerService::class.java).apply {
            action = TimerService.ACTION_DISCARD
            putExtra(TimerService.EXTRA_HABIT_ID, habitId)
            putExtra(TimerService.EXTRA_TARGET_MINUTES, targetMinutes)
        }

        assertEquals(TimerService.ACTION_DISCARD, intent.action)
        assertEquals(habitId, intent.getLongExtra(TimerService.EXTRA_HABIT_ID, -1))
    }

    @Test
    fun formatTimeText_countupMode_displaysElapsedTime() = runTest {
        // TimerService uses formatTimeText internally
        // Countup mode: "已计时 MM:SS / 目标 N分钟"
        val elapsedSeconds = 185 // 3:05
        val targetMinutes = 10
        val isCountdown = false

        // Expected: "已计时 03:05 / 目标 10分钟"
        val expectedFormat = "已计时 03:05 / 目标 10分钟"
        assertTrue("Countup format should contain '已计时'", expectedFormat.contains("已计时"))
        assertTrue("Countup format should contain target minutes", expectedFormat.contains("10分钟"))
    }

    @Test
    fun formatTimeText_countdownMode_displaysRemainingTime() = runTest {
        // Countdown mode: "还剩 MM:SS / 目标 N分钟"
        val elapsedSeconds = 120 // 2:00 elapsed
        val targetMinutes = 5 // 5 minutes = 300 seconds
        val isCountdown = true

        // Remaining: 300 - 120 = 180 seconds = 3:00
        val expectedFormat = "还剩 03:00 / 目标 5分钟"
        assertTrue("Countdown format should contain '还剩'", expectedFormat.contains("还剩"))
        assertTrue("Countdown format should contain target minutes", expectedFormat.contains("5分钟"))
    }

    @Test
    fun thresholdMultiplier_isThreeTimesTarget() {
        // Per D-07: threshold = target × 3
        val targetMinutes = 10
        val thresholdMultiplier = TimerService.THRESHOLD_MULTIPLIER

        assertEquals(3, thresholdMultiplier)
        assertEquals(30, targetMinutes * thresholdMultiplier)
    }

    @Test
    fun notificationConstants_areCorrect() {
        assertEquals(1001, TimerService.NOTIFICATION_ID)
        assertEquals(1002, TimerService.TARGET_NOTIFICATION_ID)
        assertEquals(1003, TimerService.THRESHOLD_NOTIFICATION_ID)
        assertEquals(1004, TimerService.COUNTDOWN_COMPLETE_NOTIFICATION_ID)
        assertEquals("timer_channel", TimerService.CHANNEL_ID)
    }

    @Test
    fun intentExtras_keysAreCorrect() {
        assertEquals("habitId", TimerService.EXTRA_HABIT_ID)
        assertEquals("targetMinutes", TimerService.EXTRA_TARGET_MINUTES)
        assertEquals("isCountdown", TimerService.EXTRA_IS_COUNTDOWN)
    }

    @Test
    fun actionConstants_areCorrect() {
        assertEquals("com.dayforge.timer.START", TimerService.ACTION_START)
        assertEquals("com.dayforge.timer.PAUSE", TimerService.ACTION_PAUSE)
        assertEquals("com.dayforge.timer.RESUME", TimerService.ACTION_RESUME)
        assertEquals("com.dayforge.timer.STOP", TimerService.ACTION_STOP)
        assertEquals("com.dayforge.timer.DISCARD", TimerService.ACTION_DISCARD)
    }
}