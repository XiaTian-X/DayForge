package com.dayforge.domain.service

import android.content.ContextWrapper
import android.content.Intent
import android.content.ComponentName
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
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimerManagerTest {
    private lateinit var context: RecordingContext
    private lateinit var habitDao: HabitDao
    private lateinit var timeLogDao: TimeLogDao
    private lateinit var manager: TimerManager

    @Before
    fun setup() {
        context = RecordingContext(ApplicationProvider.getApplicationContext())
        habitDao = mockk()
        timeLogDao = mockk()
        manager = TimerManager(context, habitDao, timeLogDao)
    }

    @Test
    fun stopTimer_derives_countdown_mode_from_habit() = runTest {
        coEvery { habitDao.getHabitById(7L) } returns timerHabit(id = 7L, isCountdown = true)
        coEvery { timeLogDao.getActiveTimeLog() } coAnswers {
            activeLog(habitId = 7L, startTime = System.currentTimeMillis() - 10_000)
        }

        val result = manager.stopTimer(habitId = 7L, targetMinutes = 1)

        assertEquals(null, result)
        val intent = requireNotNull(context.activityIntent)
        assertEquals(CountdownDiscardActivity::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra(CountdownDiscardActivity.EXTRA_IS_COUNTDOWN, false))
        assertTrue(intent.getIntExtra(CountdownDiscardActivity.EXTRA_SECONDS, 0) in 49..50)
    }

    @Test
    fun stopTimer_returns_habit_details_when_stop_command_is_accepted() = runTest {
        val habit = timerHabit(id = 7L, isCountdown = false)
        coEvery { habitDao.getHabitById(7L) } returns habit
        coEvery { timeLogDao.getActiveTimeLog() } returns null

        val result = manager.stopTimer(habitId = 7L, targetMinutes = 1)

        assertEquals(7L, result)
        val intent = requireNotNull(context.serviceIntent)
        assertEquals(TimerService.ACTION_STOP, intent.action)
        assertEquals(7L, intent.getLongExtra(TimerService.EXTRA_HABIT_ID, -1L))
    }

    @Test
    fun recoverRunningTimer_restarts_persisted_running_timer() = runTest {
        val activeLog = activeLog(habitId = 7L, startTime = System.currentTimeMillis())
        coEvery { timeLogDao.getActiveTimeLog() } returns activeLog
        coEvery { habitDao.getHabitById(7L) } returns timerHabit(id = 7L, isCountdown = false)

        manager.recoverRunningTimer()

        val intent = requireNotNull(context.serviceIntent)
        assertEquals(TimerService.ACTION_START, intent.action)
        assertEquals(7L, intent.getLongExtra(TimerService.EXTRA_HABIT_ID, -1L))
        assertEquals(1, intent.getIntExtra(TimerService.EXTRA_TARGET_MINUTES, -1))
    }

    @Test
    fun recoverRunningTimer_restores_persisted_paused_timer_notification() = runTest {
        coEvery { timeLogDao.getActiveTimeLog() } returns
            activeLog(habitId = 7L, startTime = System.currentTimeMillis()).copy(isPaused = true)
        coEvery { habitDao.getHabitById(7L) } returns timerHabit(id = 7L, isCountdown = true)

        manager.recoverRunningTimer()

        val intent = requireNotNull(context.serviceIntent)
        assertEquals(TimerService.ACTION_START, intent.action)
        assertEquals(7L, intent.getLongExtra(TimerService.EXTRA_HABIT_ID, -1L))
        assertTrue(intent.getBooleanExtra(TimerService.EXTRA_IS_COUNTDOWN, false))
    }

    @Test
    fun elapsed_calculation_does_not_overflow_after_multiple_days() {
        val thirtyDaysMillis = 30L * 24 * 60 * 60 * 1000
        val elapsed = manager.calculateElapsedSeconds(
            activeLog(habitId = 7L, startTime = System.currentTimeMillis() - thirtyDaysMillis)
        )

        assertTrue(elapsed in 2_592_000..2_592_001)
    }

    @Test
    fun elapsed_calculation_excludes_persisted_pauses_after_process_recovery() {
        val now = System.currentTimeMillis()
        val elapsed = manager.calculateElapsedSeconds(
            activeLog(habitId = 7L, startTime = now - 120_000).copy(
                accumulatedPauseMillis = 60_000
            )
        )

        assertTrue(elapsed in 59..60)
    }

    // Dispatch-boundary fixture; real service persistence is covered by TimerServicePersistenceTest.
    private class RecordingContext(base: Context) : ContextWrapper(base) {
        var serviceIntent: Intent? = null
        var activityIntent: Intent? = null
        override fun startService(service: Intent): ComponentName? {
            serviceIntent = Intent(service)
            return service.component
        }
        override fun startForegroundService(service: Intent): ComponentName? = startService(service)
        override fun startActivity(intent: Intent) { activityIntent = Intent(intent) }
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
