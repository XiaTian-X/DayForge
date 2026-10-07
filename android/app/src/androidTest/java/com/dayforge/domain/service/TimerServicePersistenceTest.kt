package com.dayforge.domain.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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
import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.repository.SyncV2Merger
import kotlinx.serialization.json.Json
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.UUID
import javax.inject.Inject
import com.dayforge.data.local.TokenManager
import com.dayforge.data.repository.HabitRepository
import com.dayforge.data.repository.NextObjectCreator
import com.dayforge.data.repository.NextTimerWriter
import com.dayforge.data.repository.decodeNextTimerIntent
import com.dayforge.data.repository.NEXT_TIMER
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.model.TimerActionAuthority
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
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
    @get:Rule(order = 0) val widgetRefresh = com.dayforge.widget.IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val hilt = HiltAndroidRule(this)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var database: HabitDatabase
    private lateinit var activity: ActivityScenario<ComponentActivity>
    private val databaseName = "timer-test-${UUID.randomUUID()}.db"
    private val commandInsertAttempts = AtomicInteger()
    private var habitId = 0L
    @Inject lateinit var tokens: TokenManager
    @Inject lateinit var sessions: AccountSessionCoordinator
    @Inject lateinit var habits: HabitRepository
    @Inject lateinit var creator: NextObjectCreator
    @Inject lateinit var timerWriter: NextTimerWriter

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
        sessions.exclusive { tokens.clearTokens() }
        activity = ActivityScenario.launch(ComponentActivity::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        }
        habitId = database.habitDao().insert(HabitEntity(name = "Focus", habitType = HabitType.TIMER,
            iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily, targetValue = 1))
    }

    @After fun cleanup() = runBlocking {
        stopService()
        if (::tokens.isInitialized) sessions.exclusive { tokens.clearTokens() }
        if (::activity.isInitialized) activity.close()
        if (::database.isInitialized) database.close()
        HabitDatabaseProvider.clearInstanceForTesting()
        context.deleteDatabase(databaseName)
        Unit
    }

    private fun send(action: String?, id: Long = habitId, authority: TimerActionAuthority? = null) {
        val intent = Intent(context, TimerService::class.java).apply {
            this.action = action
            putExtra(TimerService.EXTRA_HABIT_ID, id)
            authority?.attach(this)
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
        val startBefore = SystemClock.elapsedRealtime()
        send(TimerService.ACTION_START)
        awaitCommands(1)
        val startAfter = SystemClock.elapsedRealtime()
        val started = requireNotNull(database.timeLogDao().getActiveTimeLog())
        assertEquals(habitId, started.habitId)
        assertEquals(java.time.ZoneId.systemDefault().id, started.timerTimezone)
        // Real elapsed time: no system clock changes or fabricated completed timer rows.
        delay(61_000)
        val pauseBefore = SystemClock.elapsedRealtime()
        send(TimerService.ACTION_PAUSE)
        awaitCommands(2)
        val pauseAfter = SystemClock.elapsedRealtime()
        val paused = requireNotNull(database.timeLogDao().getById(started.id))
        assertTrue(paused.isPaused)
        assertTrue(paused.timerActiveElapsedMillis in (pauseBefore - startAfter)..(pauseAfter - startBefore))
        stopService()
        send(null)
        val pausedTitle = context.getString(R.string.timer_notification_title_paused)
        awaitState {
            context.getSystemService(NotificationManager::class.java).activeNotifications.any {
                it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == pausedTitle
            }
        }
        // A real pause makes accidental inclusion of paused/recreation time observable.
        delay(5_000)
        val restored = requireNotNull(database.timeLogDao().getById(started.id))
        assertTrue(restored.isPaused)
        assertEquals(paused.timerActiveElapsedMillis, restored.timerActiveElapsedMillis)
        val resumeBefore = SystemClock.elapsedRealtime()
        send(TimerService.ACTION_RESUME)
        awaitCommands(3)
        val resumeAfter = SystemClock.elapsedRealtime()
        assertFalse(database.timeLogDao().getById(started.id)!!.isPaused)
        delay(1_100)
        val stopBefore = SystemClock.elapsedRealtime()
        send(TimerService.ACTION_STOP)
        awaitCommands(4)
        val stopAfter = SystemClock.elapsedRealtime()
        awaitState { database.timeLogDao().getActiveTimeLog() == null }
        awaitServiceStopped()
        val completed = database.timeLogDao().getById(started.id)!!
        assertNotNull(completed.endTime)
        assertTrue(completed.durationSeconds >= 60)
        // Independent monotonic observations bound each active interval. Do not derive the
        // only duration oracle from the service's own stored duration, segments or commands.
        val minimumActiveMillis = pauseBefore - startAfter + stopBefore - resumeAfter
        val maximumActiveMillis = pauseAfter - startBefore + stopAfter - resumeBefore
        assertTrue("Active milliseconds must exclude the observed pause: $completed",
            completed.timerActiveElapsedMillis in minimumActiveMillis..maximumActiveMillis)
        assertTrue("Saved seconds must match independently observed active time: $completed",
            completed.durationSeconds.toLong() in (minimumActiveMillis / 1_000)..(maximumActiveMillis / 1_000))
        assertEquals(completed.timerActiveElapsedMillis / 1_000, completed.durationSeconds.toLong())
        val commands = database.timeLogDao().getPendingTimerCommands()
        assertEquals(listOf("start", "pause", "resume", "stop"), commands.map { it.commandType })
        assertEquals(listOf(1, 2, 3, 4), commands.map { it.sequence })
        assertEquals(setOf(started.uuid), commands.map { it.sessionUuid }.toSet())
        assertEquals(paused.timerActiveElapsedMillis, commands[1].activeElapsedMillis)
        assertEquals(completed.timerActiveElapsedMillis, commands.last().activeElapsedMillis)
        assertEquals(completed.endTime, commands.last().occurredAt)
        val segments = database.timeLogDao().getTimerSegments(started.uuid)
        assertEquals(2, segments.size)
        assertTrue(segments.all { it.endedAt != null })
        assertEquals(completed.timerActiveElapsedMillis, segments.sumOf { requireNotNull(it.endedAt) - it.startedAt })
        val allocations = database.timeLogDao().getDayAllocations(started.uuid)
        assertFalse(allocations.isEmpty())
        assertEquals(completed.timerActiveElapsedMillis, allocations.sumOf { it.durationMillis })
        assertTrue(allocations.all { it.sessionUuid == started.uuid && it.habitId == habitId && it.timezone == started.timerTimezone })
        Room.databaseBuilder(context, HabitDatabase::class.java, databaseName).build().let { reopened ->
            try {
                assertEquals(completed, reopened.timeLogDao().getById(started.id))
                assertEquals(commands, reopened.timeLogDao().getPendingTimerCommands())
                assertEquals(segments, reopened.timeLogDao().getTimerSegments(started.uuid))
                assertEquals(allocations, reopened.timeLogDao().getDayAllocations(started.uuid))
            }
            finally { reopened.close() }
        }
        send(TimerService.ACTION_STOP)
        instrumentation.waitForIdleSync()
        awaitServiceStopped()
        assertEquals(commands, database.timeLogDao().getPendingTimerCommands())
        assertEquals(1, widgetRefresh.requestCount)
    }

    @Test fun countdownSurvivesParentSyncAndAutomaticallyQueuesOneStop() = runBlocking {
        val habit = requireNotNull(database.habitDao().getHabitById(habitId)).copy(isCountdown = true)
        database.habitDao().update(habit)
        val beforeStart = SystemClock.elapsedRealtime()
        send(TimerService.ACTION_START)
        awaitCommands(1)
        val started = requireNotNull(database.timeLogDao().getActiveTimeLog())
        val merger = SyncV2Merger(database, database.habitDao(), database.completionDao(),
            database.timeLogDao(), database.metricDao(), database.metricLogDao(),
            database.habitMetricLinkDao(), database.syncOutboxDao(), database.syncConflictDao())
        val change = Json.decodeFromString<SyncV2Change>("""{"sequence":1,"entity_type":"plan_node","entity_uuid":"${habit.uuid}","operation":"upsert","revision":1,"payload":{"node_kind":"activity","title":"Focus synced","status":"active","activity":{"tracking_mode":"duration","target_value":60,"is_countdown":true}},"changed_at":"2026-09-21T00:00:00Z"}""")
        repeat(2) { merger.apply(listOf(change)) }
        assertEquals(started, database.timeLogDao().getActiveTimeLog())
        assertEquals(1, database.timeLogDao().getTimerSegments(started.uuid).size)
        // Observe the real one-minute automatic completion; never fabricate a stop/fact.
        withTimeout(75_000) {
            while (database.timeLogDao().getPendingTimerCommands().size < 2) delay(100)
        }
        assertTrue(SystemClock.elapsedRealtime() - beforeStart >= 60_000)
        awaitServiceStopped()
        val completed = requireNotNull(database.timeLogDao().getById(started.id))
        assertEquals(60, completed.durationSeconds)
        assertEquals(60_000L, completed.timerActiveElapsedMillis)
        assertEquals(1, widgetRefresh.requestCount)
        assertNotNull(completed.endTime)
        assertNull(database.timeLogDao().getActiveTimeLog())
        val commands = database.timeLogDao().getPendingTimerCommands()
        assertEquals(listOf("start", "stop"), commands.map { it.commandType })
        assertEquals(listOf(1, 2), commands.map { it.sequence })
        assertEquals(setOf(started.uuid), commands.map { it.sessionUuid }.toSet())
        assertEquals(60_000L, commands.last().activeElapsedMillis)
        val segments = database.timeLogDao().getTimerSegments(started.uuid)
        val allocations = database.timeLogDao().getDayAllocations(started.uuid)
        assertEquals(60_000L, segments.sumOf { requireNotNull(it.endedAt) - it.startedAt })
        assertEquals(60_000L, allocations.sumOf { it.durationMillis })
        merger.apply(listOf(change))
        assertEquals(completed, database.timeLogDao().getById(started.id))
        assertEquals(commands, database.timeLogDao().getPendingTimerCommands())
        assertEquals(segments, database.timeLogDao().getTimerSegments(started.uuid))
        assertEquals(allocations, database.timeLogDao().getDayAllocations(started.uuid))
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

    private suspend fun typed(countdown: Boolean) {
        sessions.exclusive {
            tokens.saveLoginSession("synthetic-timer", "synthetic-refresh", "member",
                "ac350000-0000-4000-8000-000000000001", false)
        }
        habitId = habits.createHabit("Typed focus", "", HabitType.TIMER, 0, "#123456", HabitSchedule.Daily,
            targetValue = 1, isCountdown = countdown, completionPolicy = "recurring",
            appearance = ObjectAppearance(IconReference.Role("habit.custom"), "#123456", "theme"),
            creationAuthority = creator.capture())
    }

    @Test fun typedCountupKeepsBirthPolicyAfterConfigEditPauseServiceRecoveryAndOldNotification() = runBlocking {
        typed(false)
        send(TimerService.ACTION_START, authority = timerWriter.capture(habitId))
        awaitCommands(1)
        val started = requireNotNull(database.timeLogDao().getActiveTimeLog())
        val startCommand = database.timeLogDao().getPendingTimerCommands().single()
        val birth = requireNotNull(database.nextRequestDao().origin(NEXT_TIMER, startCommand.commandId))
        val policy = requireNotNull(decodeNextTimerIntent(birth.intentJson).command.startPolicy)
        assertEquals(60, policy.targetSeconds); assertFalse(policy.isCountdown); assertEquals(180, policy.maxDurationSeconds)
        val oldAuthority = requireNotNull(timerWriter.capture(habitId))
        val expectedPause = TimerNotificationFactory(context).createTimerNotification(0, false, 1, false, habitId, oldAuthority).actions[0].actionIntent
        awaitState { context.getSystemService(NotificationManager::class.java).activeNotifications.any {
            it.id == TimerService.NOTIFICATION_ID && it.notification.actions[0].actionIntent == expectedPause
        } }
        val oldPause = context.getSystemService(NotificationManager::class.java).activeNotifications.single {
            it.id == TimerService.NOTIFICATION_ID
        }.notification.actions[0].actionIntent
        val edit = habits.getHabitForEditing(habitId)
        habits.updateHabit(requireNotNull(edit.value).copy(targetValue = 5, isCountdown = true), editAuthority = edit.authority)
        delay(61_000)
        send(TimerService.ACTION_PAUSE, authority = oldAuthority)
        awaitCommands(2)
        val paused = requireNotNull(database.timeLogDao().getActiveTimeLog())
        assertTrue(paused.isPaused)
        stopService()
        send(null)
        awaitState { context.getSystemService(NotificationManager::class.java).activeNotifications.any {
            it.id == TimerService.NOTIFICATION_ID && it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == context.getString(R.string.timer_notification_title_paused)
        } }
        val restoredNotification = context.getSystemService(NotificationManager::class.java).activeNotifications.single { it.id == TimerService.NOTIFICATION_ID }.notification
        assertEquals(TimerNotificationFactory(context).formatTimeText(TimerElapsedCalculator.elapsedSeconds(paused, context), 1, false),
            restoredNotification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        send(TimerService.ACTION_RESUME, authority = timerWriter.capture(habitId))
        awaitCommands(3)
        assertTrue(oldPause != context.getSystemService(NotificationManager::class.java).activeNotifications.single { it.id == TimerService.NOTIFICATION_ID }.notification.actions[0].actionIntent)
        oldPause.send()
        // A fresh valid stop is serialized after the rejected old transition; the old pause cannot consume sequence 4.
        val stopAuthority = requireNotNull(timerWriter.capture(habitId))
        var followUpCount = 0
        val followUp = async(start = CoroutineStart.UNDISPATCHED) {
            timerWriter.afterCompletion(habitId, stopAuthority) {
                val committed = requireNotNull(database.timeLogDao().getById(started.id))
                assertNotNull(committed.endTime)
                assertEquals(committed.timerActiveElapsedMillis,
                    database.timeLogDao().getDayAllocations(started.uuid).sumOf { it.durationMillis })
                followUpCount++
            }
        }
        delay(50)
        assertFalse(followUp.isCompleted)
        send(TimerService.ACTION_STOP, authority = stopAuthority)
        awaitCommands(4)
        awaitServiceStopped()
        val completed = requireNotNull(database.timeLogDao().getById(started.id))
        assertNotNull(completed.endTime); assertTrue(completed.durationSeconds in 60..180)
        assertEquals(listOf("start", "pause", "resume", "stop"), database.timeLogDao().getPendingTimerCommands().map { it.commandType })
        assertEquals(birth, database.nextRequestDao().origin(NEXT_TIMER, startCommand.commandId))
        assertEquals(completed.timerActiveElapsedMillis, database.timeLogDao().getDayAllocations(started.uuid).sumOf { it.durationMillis })
        followUp.await()
        assertEquals(1, followUpCount)
    }

    @Test fun typedCountdownAutoCompletesOriginalMinuteAfterTargetAndModeChange() = runBlocking {
        typed(true)
        val before = SystemClock.elapsedRealtime()
        send(TimerService.ACTION_START, authority = timerWriter.capture(habitId))
        awaitCommands(1)
        val started = requireNotNull(database.timeLogDao().getActiveTimeLog())
        val edit = habits.getHabitForEditing(habitId)
        habits.updateHabit(requireNotNull(edit.value).copy(targetValue = 5, isCountdown = false), editAuthority = edit.authority)
        withTimeout(75_000) { while (database.timeLogDao().getPendingTimerCommands().size < 2) delay(100) }
        awaitServiceStopped()
        assertTrue(SystemClock.elapsedRealtime() - before >= 60_000)
        val completed = requireNotNull(database.timeLogDao().getById(started.id))
        assertEquals(60, completed.durationSeconds); assertEquals(60_000L, completed.timerActiveElapsedMillis)
        assertEquals(listOf("start", "stop"), database.timeLogDao().getPendingTimerCommands().map { it.commandType })
        assertEquals(60_000L, database.timeLogDao().getDayAllocations(started.uuid).sumOf { it.durationMillis })
        assertEquals(5, habits.getHabitById(habitId)!!.targetValue); assertFalse(habits.getHabitById(habitId)!!.isCountdown)
    }
}
