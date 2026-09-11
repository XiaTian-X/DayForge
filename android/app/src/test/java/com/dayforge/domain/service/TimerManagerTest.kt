package com.dayforge.domain.service

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.widget.timer.CountdownDiscardActivity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class TimerManagerTest {
    private lateinit var context: Context
    private lateinit var habitDao: HabitDao
    private lateinit var timeLogDao: TimeLogDao
    private lateinit var manager: TimerManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        habitDao = mockk()
        timeLogDao = mockk()
        manager = TimerManager(context, habitDao, timeLogDao)
    }

    @Test
    fun `stopTimer derives countdown mode from habit`() = runTest {
        val now = System.currentTimeMillis()
        coEvery { habitDao.getHabitById(7L) } returns timerHabit(id = 7L, isCountdown = true)
        coEvery { timeLogDao.getActiveTimeLog() } returns activeLog(habitId = 7L, startTime = now - 10_000)

        val result = manager.stopTimer(habitId = 7L, targetMinutes = 1)

        assertEquals(null, result)
        val intent = shadowOf(context as Application).nextStartedActivity
        assertEquals(CountdownDiscardActivity::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra(CountdownDiscardActivity.EXTRA_IS_COUNTDOWN, false))
        assertTrue(intent.getIntExtra(CountdownDiscardActivity.EXTRA_SECONDS, 0) in 49..50)
    }

    @Test
    fun `stopTimer returns habit details when stop command is accepted`() = runTest {
        val habit = timerHabit(id = 7L, isCountdown = false)
        coEvery { habitDao.getHabitById(7L) } returns habit
        coEvery { timeLogDao.getActiveTimeLog() } returns null

        val result = manager.stopTimer(habitId = 7L, targetMinutes = 1)

        assertEquals(7L, result)
        val intent = shadowOf(context as Application).nextStartedService
        assertEquals(TimerService.ACTION_STOP, intent.action)
        assertEquals(7L, intent.getLongExtra(TimerService.EXTRA_HABIT_ID, -1L))
    }

    @Test
    fun `recoverRunningTimer restarts persisted running timer`() = runTest {
        val activeLog = activeLog(habitId = 7L, startTime = System.currentTimeMillis())
        coEvery { timeLogDao.getActiveTimeLog() } returns activeLog
        coEvery { habitDao.getHabitById(7L) } returns timerHabit(id = 7L, isCountdown = false)

        manager.recoverRunningTimer()

        val intent = shadowOf(context as Application).nextStartedService
        assertEquals(TimerService.ACTION_START, intent.action)
        assertEquals(7L, intent.getLongExtra(TimerService.EXTRA_HABIT_ID, -1L))
        assertEquals(1, intent.getIntExtra(TimerService.EXTRA_TARGET_MINUTES, -1))
    }

    @Test
    fun `recoverRunningTimer leaves persisted paused timer stopped`() = runTest {
        coEvery { timeLogDao.getActiveTimeLog() } returns
            activeLog(habitId = 7L, startTime = System.currentTimeMillis()).copy(isPaused = true)

        manager.recoverRunningTimer()

        assertNull(shadowOf(context as Application).nextStartedService)
        coVerify(exactly = 0) { habitDao.getHabitById(any()) }
    }

    @Test
    fun `elapsed calculation does not overflow after multiple days`() {
        val thirtyDaysMillis = 30L * 24 * 60 * 60 * 1000
        val elapsed = manager.calculateElapsedSeconds(
            activeLog(habitId = 7L, startTime = System.currentTimeMillis() - thirtyDaysMillis)
        )

        assertTrue(elapsed in 2_592_000..2_592_001)
    }

    @Test
    fun `elapsed calculation excludes persisted pauses after process recovery`() {
        val now = System.currentTimeMillis()
        val elapsed = manager.calculateElapsedSeconds(
            activeLog(habitId = 7L, startTime = now - 120_000).copy(
                accumulatedPauseMillis = 60_000
            )
        )

        assertTrue(elapsed in 59..60)
    }

    private fun timerHabit(id: Long, isCountdown: Boolean) = HabitEntity(
        id = id,
        name = "Timer",
        habitType = HabitType.TIMER,
        iconResId = 0,
        colorHex = "#2196F3",
        schedule = HabitSchedule.Daily,
        targetValue = 1,
        isCountdown = isCountdown
    )

    private fun activeLog(habitId: Long, startTime: Long) = TimeLogEntity(
        id = 1L,
        habitId = habitId,
        startTime = startTime,
        endTime = null,
        durationSeconds = 0,
        date = startTime
    )
}
