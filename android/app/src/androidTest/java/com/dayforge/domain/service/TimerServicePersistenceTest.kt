package com.dayforge.domain.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.SyncSchemaCallback
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android service lifecycle, foreground notifications, Room and outbox transactions. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TimerServicePersistenceTest {
    @get:Rule val hilt = HiltAndroidRule(this)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var database: HabitDatabase
    private lateinit var activity: ActivityScenario<ComponentActivity>
    private val databaseName = "timer-test-${UUID.randomUUID()}.db"
    private val commandInsertAttempts = AtomicInteger()
    private var habitId = 0L

    @Before fun setup() = runBlocking {
        check(context.packageName == "com.dayforge.testbed")
        database = Room.databaseBuilder(context, HabitDatabase::class.java, databaseName)
            .addCallback(SyncSchemaCallback)
            .setQueryCallback({ sql, _ ->
                if (sql.startsWith("INSERT", ignoreCase = true) && sql.contains("timer_command_outbox")) {
                    commandInsertAttempts.incrementAndGet()
                }
            }, { it.run() }).build()
        HabitDatabaseProvider.setInstanceForTesting(database)
        hilt.inject()
        activity = ActivityScenario.launch(ComponentActivity::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        }
        habitId = database.habitDao().insert(HabitEntity(name = "Focus", habitType = HabitType.TIMER,
            iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily, targetValue = 1))
    }

    @After fun cleanup() = runBlocking {
        stopService()
        if (::activity.isInitialized) activity.close()
        if (::database.isInitialized) database.close()
        HabitDatabaseProvider.clearInstanceForTesting()
        context.deleteDatabase(databaseName)
        Unit
    }

    private fun send(action: String?, id: Long = habitId) {
        val intent = Intent(context, TimerService::class.java).apply {
            this.action = action
            putExtra(TimerService.EXTRA_HABIT_ID, id)
        }
        if (action == TimerService.ACTION_START) context.startForegroundService(intent)
        else context.startService(intent)
    }

    private suspend fun awaitState(predicate: suspend () -> Boolean) = withTimeout(15_000) {
        while (!predicate()) delay(50)
    }

    private suspend fun awaitCommands(count: Int) = awaitState {
        database.timeLogDao().getPendingTimerCommands().size == count
    }

    private suspend fun stopService() {
        context.stopService(Intent(context, TimerService::class.java))
        awaitServiceStopped()
    }

    private suspend fun awaitServiceStopped() {
        awaitState {
            val descriptor = instrumentation.uiAutomation.executeShellCommand(
                "dumpsys activity services ${context.packageName}/com.dayforge.domain.service.TimerService"
            )
            val dump = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            !dump.contains("ServiceRecord{")
        }
        instrumentation.waitForIdleSync()
    }

    @Test fun startPauseRecreateResumeStopPersistsOneCompleteSession() = runBlocking {
        send(TimerService.ACTION_START)
        awaitCommands(1)
        val started = requireNotNull(database.timeLogDao().getActiveTimeLog())
        assertEquals(habitId, started.habitId)
        assertEquals(java.time.ZoneId.systemDefault().id, started.timerTimezone)
        // Real elapsed time: no system clock changes or fabricated completed timer rows.
        delay(61_000)
        send(TimerService.ACTION_PAUSE)
        awaitCommands(2)
        assertTrue(database.timeLogDao().getById(started.id)!!.isPaused)
        stopService()
        send(null)
        val pausedTitle = context.getString(R.string.timer_notification_title_paused)
        awaitState {
            context.getSystemService(NotificationManager::class.java).activeNotifications.any {
                it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == pausedTitle
            }
        }
        send(TimerService.ACTION_RESUME)
        awaitCommands(3)
        assertFalse(database.timeLogDao().getById(started.id)!!.isPaused)
        send(TimerService.ACTION_STOP)
        awaitCommands(4)
        awaitState { database.timeLogDao().getActiveTimeLog() == null }
        awaitServiceStopped()
        val completed = database.timeLogDao().getById(started.id)!!
        assertNotNull(completed.endTime)
        assertTrue(completed.durationSeconds >= 60)
        val commands = database.timeLogDao().getPendingTimerCommands()
        assertEquals(listOf("start", "pause", "resume", "stop"), commands.map { it.commandType })
        assertEquals(listOf(1, 2, 3, 4), commands.map { it.sequence })
        assertEquals(setOf(started.uuid), commands.map { it.sessionUuid }.toSet())
        val segments = database.timeLogDao().getTimerSegments(started.uuid)
        assertEquals(2, segments.size)
        assertTrue(segments.all { it.endedAt != null })
        Room.databaseBuilder(context, HabitDatabase::class.java, databaseName).build().let { reopened ->
            try { assertEquals(completed, reopened.timeLogDao().getById(started.id)) }
            finally { reopened.close() }
        }
        send(TimerService.ACTION_STOP)
        instrumentation.waitForIdleSync()
        awaitServiceStopped()
        assertEquals(commands, database.timeLogDao().getPendingTimerCommands())
    }

    @Test fun runningRecoveryAndStaleCommandsCannotCreateASecondSession() = runBlocking {
        send(TimerService.ACTION_START)
        awaitCommands(1)
        val original = database.timeLogDao().getActiveTimeLog()!!
        stopService()
        send(TimerService.ACTION_PAUSE, habitId + 100)
        send(TimerService.ACTION_START)
        send(TimerService.ACTION_PAUSE)
        awaitCommands(2)
        assertEquals(original.uuid, database.timeLogDao().getActiveTimeLog()!!.uuid)
        assertEquals(listOf("start", "pause"), database.timeLogDao().getPendingTimerCommands().map { it.commandType })
        send(TimerService.ACTION_DISCARD)
        awaitCommands(3)
        assertNull(database.timeLogDao().getById(original.id))
        assertEquals("cancel", database.timeLogDao().getPendingTimerCommands().last().commandType)
        awaitServiceStopped()
    }

    @Test fun outboxFailureRollsBackTimerAndAllowsRetry() = runBlocking {
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_timer_command BEFORE INSERT ON timer_command_outbox BEGIN SELECT RAISE(ABORT, 'test command failure'); END")
        send(TimerService.ACTION_START)
        awaitState { commandInsertAttempts.get() > 0 }
        awaitServiceStopped()
        assertNull(database.timeLogDao().getActiveTimeLog())
        assertTrue(database.timeLogDao().getPendingTimerCommands().isEmpty())
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM timer_segments").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_timer_command")
        send(TimerService.ACTION_START)
        awaitCommands(1)
        assertNotNull(database.timeLogDao().getActiveTimeLog())
    }
}
