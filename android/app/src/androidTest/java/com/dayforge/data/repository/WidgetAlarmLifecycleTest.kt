package com.dayforge.data.repository

import android.appwidget.AppWidgetManager
import android.content.ComponentName
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

/** Real account/Room/repository preparation. Explicit installed-consumer inventory keeps
 * all original COUNT/timer/error branches live; no fake ID is submitted to a widget host. */
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
    private val installed = mockk<AppWidgetManager>()
    private lateinit var context: Context
    private fun id(n: Int) = "ab330000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    @Before fun setup() = runBlocking<Unit> {
        hilt.inject()
        sessions.exclusive { tokens.clearTokens(); tokens.saveLoginSession("synthetic-first", "synthetic-refresh", "member", id(1), false) }
        every { installed.getAppWidgetIds(any<ComponentName>()) } returns intArrayOf(701)
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? = when (name) {
                ALARM_SERVICE -> alarms
                APPWIDGET_SERVICE -> installed
                else -> super.getSystemService(name)
            }
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
        fun mark(stage: String) {
            android.util.Log.d("WidgetAlarmGateTiming", "stage=$stage at_ns=${android.os.SystemClock.elapsedRealtimeNanos()}")
        }
        val now = ZonedDateTime.now()
        val habitId = habits.createHabit("Alarm count", "", HabitType.COUNTING, 0, "#123456", HabitSchedule.Daily,
            targetValue = 2, bestTime = (now.hour * 60 + now.minute).toLong(), completionPolicy = "recurring",
            appearance = ObjectAppearance(IconReference.Role("habit.custom"), "#123456", "theme"), creationAuthority = creator.capture())
        val row = requireNotNull(habits.getHabitById(habitId))
        val before = db.withTransaction { nextRestartDatabaseProof(db) }
        val qualification = CountReadQualificationFixtureAssertions(db, tokens, sessions)
        val beforeRead = qualification.beginBeforeFirstRead()
        val originalAuthority = requireNotNull(tokens.localCoreWriteAccess())
        val real = habits
        val gated = spyk(real)
        val actualEntry = WidgetEntryPoint.from(app)
        val entry = mockk<com.dayforge.di.WidgetGraphAccess>()
        coEvery { entry.withGraph(any<suspend () -> Any?>()) } coAnswers {
            actualEntry.withGraph(firstArg<suspend () -> Any?>())
        }
        every { entry.bound() } answers { actualEntry.bound() }
        every { entry.displayPublisher() } returns publisher
        every { entry.habitRepository() } returns gated
        every { WidgetEntryPoint.from(context) } returns entry
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        coEvery { gated.getCountHistory(row) } coAnswers {
            mark("real_count_begin")
            val read = real.getCountHistory(row)
            mark("real_count_return")
            entered.complete(Unit); mark("entered_complete"); release.await(); mark("read_release_return"); read
        }
        val scheduling = async(Dispatchers.IO) {
            mark("scheduling_begin")
            FocusWidgetAlarmScheduler.scheduleNextRefresh(context)
            mark("scheduling_return")
        }
        try {
            mark("entered_await_begin")
            try {
                withTimeout(5000) { entered.await() }
            } catch (error: Throwable) {
                mark("entered_await_failed")
                throw error
            }
            mark("entered_await_return")
            // The REAL original getCountHistory has completed and is held before platform
            // publication. Inspect that first read now, while the original account is current.
            assertEquals(originalAuthority, tokens.localCoreWriteAccess())
            val prepared = qualification.verifyFirstRetry(beforeRead)
            val preparedHash = db.withTransaction { nextRestartDatabaseProof(db) }
            if (prepared == beforeRead) assertEquals(before, preparedHash)
            mark("account_switch_begin")
            sessions.exclusive { tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(50), false) }
            mark("account_switch_return")
            mark("normal_release")
            release.complete(Unit)
            mark("scheduling_await_begin")
            try {
                withTimeout(5000) { scheduling.await() }
            } catch (error: Throwable) {
                mark("scheduling_await_failed")
                throw error
            }
            mark("scheduling_await_return")
            verify(exactly = 0) { alarms.setWindow(any(), any(), any(), any()) }
            verify(exactly = 0) { alarms.setAlarmClock(any(), any()) }
            verify(exactly = 0) { alarms.cancel(any<android.app.PendingIntent>()) }
            // Rejected old-account publication has NO write allowance, even for COUNT cache.
            qualification.assertUnchanged(prepared)
            assertEquals(preparedHash, db.withTransaction { nextRestartDatabaseProof(db) })
        } finally {
            mark("cleanup_release")
            release.complete(Unit)
            mark("cleanup_cancel_join_begin")
            scheduling.cancelAndJoin()
            mark("cleanup_cancel_join_return")
        }
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
