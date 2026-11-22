package com.dayforge.data.repository

import android.appwidget.AppWidgetManager
import android.content.Intent
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.*
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.service.DurationDayAllocator
import com.dayforge.widget.IsolatedWidgetRefreshRule
import com.dayforge.widget.clearWidgetSelection
import com.dayforge.widget.timer.TimerWidget
import com.dayforge.widget.timer.TimerWidgetReceiver
import com.dayforge.widget.timer.WidgetTimerAction
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual controlled Host/Glance storage consumer, not a synthetic snapshot or launcher test.
 * Original service state-machine/notification/stop-source suites remain separate gates. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TimerProjectionWidgetConsumerTest {
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    private val watched = AtomicLong(-1)
    private val details = AtomicInteger()
    private var owner: Job? = null
    @get:Rule(order = 1) val epochs = PhysicalDatabaseEpochFixture().also { fixture ->
        fixture.beforeCleanup = { check(owner == null || owner!!.isCompleted) { "Timer consumer original Job has not exited" } }
        fixture.queryCallback = object : RoomDatabase.QueryCallback {
            override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                val sql = sqlQuery.replace("`", "").lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ").trim()
                if (Regex("^select \\* from timelogs where habitid\\s*=\\s*\\? order by starttime desc$").matches(sql) &&
                    (bindArgs.firstOrNull() as? Number)?.toLong() == watched.get()) details.incrementAndGet()
            }
        }
    }
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val ids = mutableListOf<GlanceId>()
    @Before fun setup() = runBlocking<Unit> { hilt.inject(); epochs.connect(); epochs.login() }
    @After fun finish() = runBlocking<Unit> {
        owner?.let { withTimeout(5_000) { it.cancelAndJoin() } }
        ids.forEach { key -> clearWidgetSelection(app, key); updateAppWidgetState(app, key) { it.clear() } }
    }
    private fun glanceId(): GlanceId = requireNotNull(GlanceAppWidgetManager(app).getGlanceIdBy(
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, (System.nanoTime() and 0x1fffffff).toInt() + 1))).also { ids += it }
    private suspend fun state(key: GlanceId) = TimerWidget().getAppWidgetState<Preferences>(app, key)
    private suspend fun bind(key: GlanceId, habitId: Long) = epochs.current { graph ->
        val habit = requireNotNull(graph.habitRepository.getHabitById(habitId))
        val spec = WidgetConfigurationSpec(HabitType.TIMER, TimerWidget.PREFS_NAME,
            TimerWidget.PREF_HABIT_ID_PREFIX, TimerWidgetReceiver::class.java)
        val widgetId = GlanceAppWidgetManager(app).getAppWidgetId(key)
        val repository = graph.widgetConfigurationRepository
        val snapshot = repository.load(spec, widgetId)
        assertTrue(snapshot.habits.any { it.id == habit.id && it.uuid == habit.uuid })
        repository.configure(snapshot, habit) { publication -> check(publication { }) }
        val bound = requireNotNull(repository.bound(spec, widgetId))
        assertEquals(habit.id, bound.habit?.id); assertEquals(habit.uuid, bound.habit?.uuid)
        assertTrue(bound.publication { })
    }

    @Test(timeout = 150_000)
    fun actualTimerWidgetUsesFiniteMainRefreshAndKeepsOriginalActionAndLightTick() = runBlocking<Unit> {
        owner = requireNotNull(coroutineContext[Job])
        val key = glanceId()
        val habitId = epochs.current { graph -> graph.habitRepository.createHabit("Finite timer widget", "", HabitType.TIMER,
            0, "#123456", HabitSchedule.Daily, targetValue = 1, isCountdown = false, targetCycles = 3, completionPolicy = "recurring",
            appearance = ObjectAppearance(IconReference.Role("habit.custom"), "#123456", "theme"),
            creationAuthority = graph.nextObjectCreator.capture()) }
        watched.set(habitId); bind(key, habitId)
        val completedUuid = UUID.randomUUID().toString()
        val completedStartId = UUID.randomUUID().toString(); val completedStopId = UUID.randomUUID().toString()
        val sessionUuid = UUID.randomUUID().toString(); val commandId = UUID.randomUUID().toString()
        epochs.current { graph ->
            val writer = graph.nextTimerWriter
            val zone = java.time.ZoneId.systemDefault().id
            val habit = requireNotNull(graph.habitRepository.getHabitById(habitId))
            // Fixed logical history under the original writer/DAO transitions, not a timed Service journey.
            // This reaches the original 60s policy's 180s cap, then remains a completed day after editing to 180s.
            val completedEnd = System.currentTimeMillis() - 1_000L
            val completedStart = completedEnd - 180_000L
            writer.write(habitId, writer.capture(habitId)) {
                graph.timeLogDao.insertSyncedTimer(TimeLogEntity(habitId = habitId, uuid = completedUuid,
                    startTime = completedStart, endTime = null, durationSeconds = 0, date = completedStart,
                    timerNextCommandSequence = 2, timerControlGeneration = 1,
                    timerLastCommandAt = completedStart, timerTimezone = zone),
                    TimerCommandEntity(commandId = completedStartId, sessionUuid = completedUuid, sequence = 1,
                        commandType = "start", occurredAt = completedStart, expectedControlGeneration = 0,
                        activityUuid = habit.uuid, timezone = zone),
                    TimerSegmentEntity(sessionUuid = completedUuid, sequence = 1, startedAt = completedStart))
            }
            val completing = requireNotNull(writer.capture(habitId))
            val completedLog = requireNotNull(graph.timeLogDao.getActiveTimeLogForHabit(habitId))
            writer.write(habitId, completing) {
                graph.timeLogDao.finishTimerAndQueue(completedLog.id, completedEnd, 180, 0, 3, 180_000L,
                    TimerCommandEntity(commandId = completedStopId, sessionUuid = completedUuid, sequence = 2,
                        commandType = "stop", occurredAt = completedEnd, expectedControlGeneration = 1,
                        activeElapsedMillis = 180_000L), false)
                graph.timeLogDao.replaceDayAllocations(completedUuid, DurationDayAllocator.allocate(completedUuid,
                    habitId, zone, graph.timeLogDao.getTimerSegments(completedUuid), 180_000L))
            }
            assertEquals(completedEnd, graph.timeLogDao.getTimeLogByUuid(completedUuid)!!.endTime)
            assertEquals(180_000L, graph.timeLogDao.getDayAllocations(completedUuid).sumOf { it.durationMillis })
            val began = System.currentTimeMillis()
            writer.write(habitId, writer.capture(habitId)) {
                graph.timeLogDao.insertSyncedTimer(TimeLogEntity(habitId = habitId, uuid = sessionUuid,
                    startTime = began, endTime = null, durationSeconds = 0, date = began,
                    timerNextCommandSequence = 2, timerControlGeneration = 1, timerLastCommandAt = began, timerTimezone = zone),
                    TimerCommandEntity(commandId = commandId, sessionUuid = sessionUuid, sequence = 1, commandType = "start",
                        occurredAt = began, expectedControlGeneration = 0, activityUuid = habit.uuid, timezone = zone),
                    TimerSegmentEntity(sessionUuid = sessionUuid, sequence = 1, startedAt = began))
            }
            val edit = graph.habitRepository.getHabitForEditing(habitId)
            graph.habitRepository.updateHabit(requireNotNull(edit.value).copy(targetValue = 3, isCountdown = true), editAuthority = edit.authority)
        }
        val original = epochs.current { graph ->
            val habit = requireNotNull(graph.habitRepository.getHabitById(habitId))
            details.set(0)
            val full = requireNotNull(graph.nextTimerWriter.widgetSnapshot(habit))
            assertTrue("Calibrate the real full-history DAO first", details.get() > 0)
            assertEquals(60, requireNotNull(full.policy).targetSeconds); assertFalse(full.policy!!.isCountdown)
            assertEquals(sessionUuid, full.authority.sessionUuid)
            assertEquals(180L, full.history.todaySeconds)
            assertEquals(1, full.history.qualifiedDates.size)
            assertEquals(setOf(completedUuid, sessionUuid), full.history.logs.map { it.uuid }.toSet())
            assertTrue(graph.nextTimerWriter.renderWidgetDisplaySnapshot(habit) { finite ->
                assertFalse(graph.database.inTransaction())
                assertEquals(full.habit, finite.habit); assertEquals(full.authority, finite.authority)
                assertEquals(full.policy, finite.policy); assertEquals(full.startGuard, finite.startGuard)
                assertEquals(full.activeLog, finite.activeLog); assertEquals(full.activeLogHash, finite.activeLogHash)
                assertEquals(full.history.todaySeconds, finite.projection.todaySeconds)
                assertEquals(full.history.qualifiedDates.size, finite.projection.qualifiedDayCount)
            })
            full
        }
        val before = epochs.exactMain(); details.set(0)
        TimerWidget.refreshWidgetData(app, key, habitId)
        val main = state(key)
        assertEquals(0, details.get())
        assertEquals(true, main[TimerWidget.DATA_LOADED_KEY]); assertEquals(false, main[TimerWidget.READ_FAILED_KEY])
        assertEquals(false, main[TimerWidget.IS_DELETED_KEY]); assertEquals("RUNNING", main[TimerWidget.TIMER_STATE_KEY])
        assertEquals(1, main[TimerWidget.TARGET_MINUTES_KEY]); assertEquals(false, main[TimerWidget.IS_COUNTDOWN_KEY])
        val elapsed = requireNotNull(main[TimerWidget.ELAPSED_SECONDS_KEY])
        assertTrue(elapsed in 0..180)
        assertEquals(180 + elapsed, main[TimerWidget.ACCUMULATED_SECONDS_KEY])
        assertEquals(true, main[TimerWidget.IS_COMPLETED_KEY])
        assertEquals(1, main[TimerWidget.TARGET_PROGRESS_KEY]); assertEquals(false, main[TimerWidget.HAS_FAILED_KEY])
        assertEquals(WidgetTimerAction.from(original), WidgetTimerAction.decode(requireNotNull(main[TimerWidget.ACTION_PROOF_KEY])))
        assertEquals(original.activeLogHash, main[TimerWidget.TICK_LOG_HASH_KEY])
        assertEquals(original.history.today.toString(), main[TimerWidget.TICK_DATE_KEY])
        assertEquals(java.time.ZoneId.systemDefault().id, main[TimerWidget.TICK_ZONE_KEY])
        assertEquals(180, main[TimerWidget.TICK_COMPLETED_SECONDS_KEY])
        assertEquals(before, epochs.exactMain())
        details.set(0)
        assertTrue(TimerWidget.refreshElapsedWidgetData(app, key, habitId))
        val tick = state(key)
        assertEquals(0, details.get())
        assertEquals(main[TimerWidget.ACTION_PROOF_KEY], tick[TimerWidget.ACTION_PROOF_KEY])
        assertEquals(main[TimerWidget.TICK_PLAN_KEY], tick[TimerWidget.TICK_PLAN_KEY])
        assertEquals(main[TimerWidget.TICK_LOG_HASH_KEY], tick[TimerWidget.TICK_LOG_HASH_KEY])
        assertEquals(main[TimerWidget.TICK_COMPLETED_SECONDS_KEY], tick[TimerWidget.TICK_COMPLETED_SECONDS_KEY])
        assertEquals(before, epochs.exactMain())
        epochs.current { graph ->
            assertEquals(listOf(completedStartId, completedStopId, commandId),
                graph.timeLogDao.getPendingTimerCommands().map { it.commandId })
            assertEquals(sessionUuid, graph.timeLogDao.getActiveTimeLogForHabit(habitId)?.uuid)
            assertEquals(0, graph.metricLogDao.countAll())
        }
    }
}
