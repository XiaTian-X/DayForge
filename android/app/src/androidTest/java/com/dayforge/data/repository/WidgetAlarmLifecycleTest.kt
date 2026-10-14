package com.dayforge.data.repository

import android.app.AlarmManager
import android.content.Context
import android.content.ContextWrapper
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.TokenManager
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.di.WidgetEntryPoint
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.widget.FocusWidgetAlarmScheduler
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.*
import java.io.IOException
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real account/Room/repository preparation. Only AlarmManager submission is replaced. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class WidgetAlarmLifecycleTest {
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val storage = PhysicalDatabaseRule()
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @Inject lateinit var habits: HabitRepository
    @Inject lateinit var creator: NextObjectCreator
    @Inject lateinit var tokens: TokenManager
    @Inject lateinit var sessions: AccountSessionCoordinator
    @Inject lateinit var publisher: WidgetDisplayPublisher
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = storage.database
    private val alarms = mockk<AlarmManager>(relaxed = true)
    private lateinit var context: Context
    private fun id(n: Int) = "ab330000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    @Before fun setup() = runBlocking<Unit> {
        hilt.inject()
        sessions.exclusive { tokens.clearTokens(); tokens.saveLoginSession("synthetic-first", "synthetic-refresh", "member", id(1), false) }
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? = if (name == ALARM_SERVICE) alarms else super.getSystemService(name)
        }
        val entry = WidgetEntryPoint.from(app)
        mockkObject(WidgetEntryPoint.Companion)
        every { WidgetEntryPoint.from(context) } returns entry
        every { alarms.canScheduleExactAlarms() } returns false
    }
    @After fun cleanup() = runBlocking<Unit> {
        unmockkObject(WidgetEntryPoint.Companion)
        if (::tokens.isInitialized) sessions.exclusive { tokens.clearTokens() }
    }

    @Test fun oldActiveTimerAlarmCannotUndoAccountCleanupAndFreshAlarmRetainsPermissionFallback() = runBlocking<Unit> {
        val original = requireNotNull(publisher.capturePublication())
        sessions.exclusive {
            FocusWidgetAlarmScheduler.cancelScheduledRefresh(context)
            tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(50), false)
        }
        FocusWidgetAlarmScheduler.scheduleNextRefreshForActiveTimer(context, original::invoke)
        verify(exactly = 1) { alarms.cancel(any<android.app.PendingIntent>()) }
        verify(exactly = 0) { alarms.setWindow(any(), any(), any(), any()) }
        verify(exactly = 0) { alarms.setAlarmClock(any(), any()) }
        val before = ZonedDateTime.now().plusMinutes(1).withSecond(0).withNano(0).toInstant().toEpochMilli()
        val trigger = slot<Long>()
        every { alarms.setWindow(AlarmManager.RTC_WAKEUP, capture(trigger), 60_000L, any()) } just Runs
        FocusWidgetAlarmScheduler.scheduleNextRefreshForActiveTimer(context, requireNotNull(publisher.capturePublication())::invoke)
        val after = ZonedDateTime.now().plusMinutes(1).withSecond(0).withNano(0).toInstant().toEpochMilli()
        assertTrue(trigger.captured in before..after)
        every { alarms.canScheduleExactAlarms() } returns true
        FocusWidgetAlarmScheduler.scheduleNextRefreshForActiveTimer(context, requireNotNull(publisher.capturePublication())::invoke)
        verify(exactly = 1) { alarms.setAlarmClock(any(), any()) }
        verify(exactly = 1) { alarms.setWindow(any(), any(), any(), any()) }
    }

    @Test fun actualCountPreparationDoesNotReenterLockAndOldAlarmIsDiscardedAfterSwitch() = runBlocking<Unit> {
        val now = ZonedDateTime.now()
        val habitId = habits.createHabit("Alarm count", "", HabitType.COUNTING, 0, "#123456", HabitSchedule.Daily,
            targetValue = 2, bestTime = (now.hour * 60 + now.minute).toLong(), completionPolicy = "recurring",
            appearance = ObjectAppearance(IconReference.Role("habit.custom"), "#123456", "theme"), creationAuthority = creator.capture())
        val row = requireNotNull(habits.getHabitById(habitId))
        val before = db.withTransaction { nextRestartDatabaseProof(db) }
        val real = habits
        val gated = spyk(real)
        val entry = mockk<WidgetEntryPoint>()
        every { entry.displayPublisher() } returns publisher
        every { entry.habitRepository() } returns gated
        every { WidgetEntryPoint.from(context) } returns entry
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        coEvery { gated.getCountHistory(row) } coAnswers {
            val read = real.getCountHistory(row)
            entered.complete(Unit); release.await(); read
        }
        val scheduling = async(Dispatchers.IO) { FocusWidgetAlarmScheduler.scheduleNextRefresh(context) }
        try {
            withTimeout(5000) { entered.await() }
            sessions.exclusive { tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(50), false) }
            release.complete(Unit); withTimeout(5000) { scheduling.await() }
            verify(exactly = 0) { alarms.setWindow(any(), any(), any(), any()) }
            verify(exactly = 0) { alarms.setAlarmClock(any(), any()) }
            verify(exactly = 0) { alarms.cancel(any<android.app.PendingIntent>()) }
            assertEquals(before, db.withTransaction { nextRestartDatabaseProof(db) })
        } finally { release.complete(Unit); scheduling.cancelAndJoin() }
    }

    @Test fun emptyCurrentSourcesCancelOldAlarmAndPlatformFailurePropagatesWithoutLockLeak() = runBlocking<Unit> {
        withTimeout(5000) { FocusWidgetAlarmScheduler.scheduleNextRefresh(context) }
        verify(exactly = 1) { alarms.cancel(any<android.app.PendingIntent>()) }
        val failure = IOException("alarm platform unavailable")
        every { alarms.setWindow(any(), any(), any(), any()) } throws failure
        var caught: Exception? = null
        try { FocusWidgetAlarmScheduler.scheduleNextRefreshForActiveTimer(context, requireNotNull(publisher.capturePublication())::invoke) }
        catch (error: Exception) { caught = error }
        assertSame(failure, caught)
        withTimeout(5000) { sessions.exclusive { assertEquals(id(1), tokens.authenticationSnapshot()!!.session.userId) } }
    }

    @Test fun noWindowStillKeepsRealActiveTimerMinuteRefreshAndCorruptOriginDoesNotSchedule() = runBlocking<Unit> {
        val now = ZonedDateTime.now()
        val habitId = habits.createHabit("No window timer", "", HabitType.TIMER, 0, "#123456", HabitSchedule.Daily,
            targetValue = 1, completionPolicy = "recurring", appearance = ObjectAppearance(IconReference.Role("habit.custom"),
                "#123456", "theme"), creationAuthority = creator.capture())
        val row = requireNotNull(habits.getHabitById(habitId))
        val at = System.currentTimeMillis() - 1000
        NextCoreLocalIntentStore(db, tokens, sessions).write(requireNotNull(tokens.localCoreWriteAccess()).session) {
            db.timeLogDao().insertSyncedTimer(
                com.dayforge.data.local.entity.TimeLogEntity(habitId = habitId, uuid = id(80), startTime = at,
                    date = com.dayforge.util.DateTimeUtils.startOfDayMillis(), endTime = null, durationSeconds = 0,
                    timerNextCommandSequence = 2, timerControlGeneration = 1, timerLastCommandAt = at, timerTimezone = now.zone.id),
                com.dayforge.data.local.entity.TimerCommandEntity(commandId = id(81), sessionUuid = id(80), sequence = 1,
                    commandType = "start", occurredAt = at, expectedControlGeneration = 0, activityUuid = row.uuid, timezone = now.zone.id),
                com.dayforge.data.local.entity.TimerSegmentEntity(sessionUuid = id(80), sequence = 1, startedAt = at))
        }
        val before = db.withTransaction { nextRestartDatabaseProof(db) }
        withTimeout(5000) { FocusWidgetAlarmScheduler.scheduleNextRefresh(context) }
        verify(exactly = 1) { alarms.setWindow(any(), any(), 60_000L, any()) }
        verify(exactly = 0) { alarms.cancel(any<android.app.PendingIntent>()) }
        assertEquals(before, db.withTransaction { nextRestartDatabaseProof(db) })
        db.openHelper.writableDatabase.execSQL("UPDATE timer_command_outbox SET occurredAt=occurredAt+1 WHERE commandId=?", arrayOf(id(81)))
        val damaged = db.withTransaction { nextRestartDatabaseProof(db) }
        var rejected = false
        try { FocusWidgetAlarmScheduler.scheduleNextRefresh(context) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        verify(exactly = 1) { alarms.setWindow(any(), any(), any(), any()) }
        assertEquals(damaged, db.withTransaction { nextRestartDatabaseProof(db) })
    }
}
