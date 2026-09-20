package com.dayforge.domain.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dayforge.R
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/**
 * Intent and notification contracts used by [TimerService].
 *
 * Persisted state transitions are covered by TimerTransitionDaoTest and
 * process-recovery dispatch by TimerManagerTest. Android lifecycle/background
 * behavior still requires the device acceptance described in docs/TESTING.md.
 */
@RunWith(AndroidJUnit4::class)
class TimerServiceTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun startTimer_createsStartIntentWithCorrectExtras() {
        val habitId = 123L
        val targetMinutes = 30
        val isCountdown = true

        val intent = TimerServiceController.commandIntent(
            context,
            TimerService.ACTION_START,
            habitId,
            targetMinutes,
            isCountdown
        )

        assertEquals(TimerService.ACTION_START, intent.action)
        assertEquals(habitId, intent.getLongExtra(TimerService.EXTRA_HABIT_ID, -1))
        assertEquals(targetMinutes, intent.getIntExtra(TimerService.EXTRA_TARGET_MINUTES, 0))
        assertTrue(intent.getBooleanExtra(TimerService.EXTRA_IS_COUNTDOWN, false))
    }

    @Test
    fun pauseTimer_createsPauseIntentWithCorrectExtras() {
        val habitId = 456L
        val targetMinutes = 15

        val intent = TimerServiceController.commandIntent(
            context,
            TimerService.ACTION_PAUSE,
            habitId,
            targetMinutes
        )

        assertEquals(TimerService.ACTION_PAUSE, intent.action)
        assertEquals(habitId, intent.getLongExtra(TimerService.EXTRA_HABIT_ID, -1))
    }

    @Test
    fun resumeTimer_createsResumeIntentWithCorrectExtras() {
        val habitId = 789L
        val targetMinutes = 45

        val intent = TimerServiceController.commandIntent(
            context,
            TimerService.ACTION_RESUME,
            habitId,
            targetMinutes
        )

        assertEquals(TimerService.ACTION_RESUME, intent.action)
        assertEquals(habitId, intent.getLongExtra(TimerService.EXTRA_HABIT_ID, -1))
    }

    @Test
    fun stopTimer_createsStopIntentWithCorrectExtras() {
        val habitId = 100L
        val targetMinutes = 60

        val intent = TimerServiceController.commandIntent(
            context,
            TimerService.ACTION_STOP,
            habitId,
            targetMinutes
        )

        assertEquals(TimerService.ACTION_STOP, intent.action)
        assertEquals(habitId, intent.getLongExtra(TimerService.EXTRA_HABIT_ID, -1))
    }

    @Test
    fun discardTimer_createsDiscardIntentWithCorrectExtras() {
        val habitId = 200L
        val targetMinutes = 20

        val intent = TimerServiceController.commandIntent(
            context,
            TimerService.ACTION_DISCARD,
            habitId,
            targetMinutes
        )

        assertEquals(TimerService.ACTION_DISCARD, intent.action)
        assertEquals(habitId, intent.getLongExtra(TimerService.EXTRA_HABIT_ID, -1))
    }

    @Test
    fun formatTimeText_countupMode_displaysElapsedTime() = runTest {
        val factory = TimerNotificationFactory(context)
        val text = factory.formatTimeText(185, 10, isCountdown = false)

        assertEquals(context.getString(R.string.timer_notification_elapsed_format, 3, 5, 10), text)
    }

    @Test
    fun formatTimeText_countdownMode_displaysRemainingTime() = runTest {
        val factory = TimerNotificationFactory(context)
        val text = factory.formatTimeText(120, 5, isCountdown = true)

        assertEquals(context.getString(R.string.timer_notification_remaining_format, 3, 0, 5), text)
    }

    @Test
    fun formatTimeText_countdownClampsAtZero() {
        val factory = TimerNotificationFactory(context)

        assertEquals(
            context.getString(R.string.timer_notification_remaining_format, 0, 0, 1),
            factory.formatTimeText(90, 1, isCountdown = true)
        )
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
