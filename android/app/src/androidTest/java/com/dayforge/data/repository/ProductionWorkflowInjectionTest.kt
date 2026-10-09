package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.*
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production Hilt graph, not manually constructed repositories with optional collaborators. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ProductionWorkflowInjectionTest {
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val storage = PhysicalDatabaseRule()
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @Inject lateinit var habits: HabitRepository
    @Inject lateinit var metrics: MetricRepository
    @Inject lateinit var creator: NextObjectCreator
    @Inject lateinit var once: OneTimeRepository
    @Inject lateinit var tokens: TokenManager
    @Inject lateinit var sessions: AccountSessionCoordinator
    @Inject lateinit var reminders: com.dayforge.reminder.HabitReminderController
    @Inject lateinit var preferences: PreferencesManager
    @Inject lateinit var calendar: com.dayforge.domain.service.DeviceCalendar
    @Inject lateinit var timerWriter: NextTimerWriter
    private val db get() = storage.database
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun id(n: Int) = "ac310000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private fun appearance(role: String) = ObjectAppearance(IconReference.Role(role), "#123456", "theme")

    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        hilt.inject()
        assertSame(reminders, com.dayforge.di.ReminderEntryPoint.from(app))
        assertSame(calendar, preferences.calendar)
        sessions.exclusive {
            tokens.clearTokens()
            tokens.saveLoginSession("synthetic-graph-access", "synthetic-graph-refresh", "member", id(1), false)
        }
    }

    @After fun cleanup() = runBlocking<Unit> {
        if (::tokens.isInitialized) sessions.exclusive { tokens.clearTokens() }
    }

    private suspend fun assertBornTogether() {
        val queues = db.syncOutboxDao().getAll()
        assertTrue(queues.isNotEmpty())
        for (row in queues) {
            val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, row.operationId))
            assertEquals(row.id, origin.queueId)
            assertEquals(id(1), origin.accountId)
            assertEquals(5, origin.protocol)
            assertNull(origin.serverInstanceId); assertNull(origin.syncEpoch)
        }
    }

    @Test fun accountCleanupWithTheRealSharedLockDoesNotReenterReminderScheduling() = runBlocking<Unit> {
        val rowId = habits.createHabit("Hilt reminder cleanup", "", HabitType.COUNTING, 0, "#123456", HabitSchedule.Daily,
            targetValue = 10, bestTime = 540, completionPolicy = "recurring", appearance = appearance("habit.exercise"),
            context = app, creationAuthority = creator.capture())
        try {
            habits.logCompletion(app, rowId, 1)
            assertTrue(db.countDayDao().forHabit(rowId).isNotEmpty())
            fun existing() = android.app.PendingIntent.getBroadcast(app, 0,
                com.dayforge.reminder.AndroidReminderAlarms.alarmIntent(app, rowId),
                android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE)
            assertNotNull(existing())
            val session = tokens.authenticationSnapshot()!!.session
            kotlinx.coroutines.withTimeout(5000) { sessions.exclusive { habits.clearAllData(app) } }
            assertTrue(db.habitDao().getAllHabitsOnce().isEmpty()); assertTrue(db.syncOutboxDao().getAll().isEmpty())
            assertTrue(db.countDayDao().forHabit(rowId).isEmpty())
            assertNull(existing())
            assertEquals(session, tokens.authenticationSnapshot()!!.session)
        } finally { reminders.cancelAllNow() }
    }

    @Test fun injectedOnceAndMetricRepositoriesCreateCompletePromptUndoAndStageDeleteOffline() = runBlocking<Unit> {
        val metricId = metrics.createMetric(MetricEntity(name = "Hilt metric", unit = "kg", decimalPlaces = 3,
            iconResId = 0, colorHex = "#123456", appearance = appearance("metric.weight")), creationAuthority = creator.capture())
        val taskId = habits.createHabit("Hilt retained once", "", HabitType.CHECK_IN, 0, "#123456", HabitSchedule.Once(),
            failMode = FailMode.LOOSE, selectedMetricIds = setOf(metricId), completionPolicy = "one_and_done",
            appearance = appearance("task.shopping"), creationAuthority = creator.capture())
        val row = requireNotNull(habits.getHabitById(taskId))
        assertNotNull(habits.getHabitForEditing(taskId).authority)
        habits.logCompletion(app, taskId)
        assertTrue(habits.getOneTimeStatus(taskId).completed)
        val prompt = requireNotNull(once.prompt(taskId))
        once.submit(once.saveDraft(prompt, mapOf(metricId to ("12.250" to "production graph"))))
        assertEquals(12.25, db.metricLogDao().getLogsByMetric(metricId).first().single().value, 0.0)
        habits.undoCompletion(app, requireNotNull(habits.getOneTimeStatus(taskId).completionId))
        assertFalse(habits.getOneTimeStatus(taskId).completed)
        assertEquals(2, db.completionDao().getByHabitOnce(taskId).size)
        habits.deleteHabit(row, app, authority = habits.getHabitForEditing(taskId).authority)
        assertNull(habits.getHabitById(taskId)); assertNotNull(db.habitDao().getHabitById(taskId))
        assertBornTogether()
    }

    @Test fun injectedGoalAndHabitRepositoriesCreateEditAndDetachWithoutLegacySyncAdmission() = runBlocking<Unit> {
        val goal = HabitDraft(id = id(20), name = "Hilt goal", habitType = HabitType.GOAL,
            appearance = appearance("goal.default"))
        val child = HabitDraft(id = id(21), name = "Hilt child", habitType = HabitType.COUNTING,
            completionPolicy = "recurring", appearance = appearance("habit.exercise"))
        habits.createGoal(goal, listOf(child), creationAuthority = creator.capture())
        val snapshot = habits.getHabitForEditing(requireNotNull(db.habitDao().getHabitByUuid(child.id)).id)
        val row = requireNotNull(snapshot.value)
        habits.updateHabit(row.copy(name = "Hilt edited child"), editAuthority = snapshot.authority)
        assertEquals("Hilt edited child", habits.getHabitById(row.id)!!.name)
        val current = requireNotNull(habits.getHabitById(row.id))
        val beforeRead = db.syncOutboxDao().getAll()
        val recurring = habits.getRecurringSnapshot(current)
        assertEquals(current, recurring.habit)
        assertEquals(NextStructureMapper.writePlan(current), recurring.authority.original)
        assertTrue(requireNotNull(recurring.countHistory).completions.isEmpty())
        assertEquals(beforeRead, db.syncOutboxDao().getAll())
        val beforeMidnight = db.habitDao().getAllHabitsOnce()
        com.dayforge.di.WidgetEntryPoint.from(app).activityRateRefresher().refresh()
        assertEquals(beforeMidnight, db.habitDao().getAllHabitsOnce()) // typed plans are not cache targets
        assertEquals(beforeRead, db.syncOutboxDao().getAll())
        val root = requireNotNull(db.habitDao().getHabitByUuid(goal.id))
        habits.deleteHabitOrphanChildren(root, app, habits.getHabitForEditing(root.id).authority)
        assertNull(habits.getHabitById(root.id)); assertNull(habits.getHabitById(row.id)!!.parentHabitId)
        assertBornTogether()
    }

    @Test fun injectedTypedWritersRejectKnownReadOnlyAndStaleAuthenticationWithoutPartialWrites() = runBlocking<Unit> {
        val rowId = habits.createHabit("Hilt bound writer", "", HabitType.COUNTING, 0, "#123456", HabitSchedule.Daily,
            completionPolicy = "recurring", appearance = appearance("habit.exercise"), creationAuthority = creator.capture())
        val snapshot = habits.getHabitForEditing(rowId)
        val original = requireNotNull(snapshot.value)
        val queues = db.syncOutboxDao().getAll()
        sessions.exclusive {
            tokens.saveServerIdentity(id(2), id(3))
            tokens.saveDeviceRegistration(id(4), setOf("sync.read", "facts.append"), false, 1)
        }
        val denied = habits.getHabitForEditing(rowId)
        assertTrue(runCatching { habits.updateHabit(original.copy(name = "Denied"), editAuthority = denied.authority) }.isFailure)
        assertEquals(original, habits.getHabitById(rowId)); assertEquals(queues, db.syncOutboxDao().getAll())
        val current = habits.getHabitForEditing(rowId)
        sessions.exclusive { tokens.saveLoginSession("synthetic-next", "synthetic-refresh", "member", id(1), false) }
        assertTrue(runCatching { habits.deleteHabit(original, app, authority = current.authority) }.isFailure)
        assertEquals(original, habits.getHabitById(rowId)); assertEquals(queues, db.syncOutboxDao().getAll())
    }

    @Test fun injectedRepositoryKeepsLegacyReadsAndOrdinaryCompletionOnTheirOriginalPath() = runBlocking<Unit> {
        val row = HabitEntity(name = "Hilt legacy", habitType = HabitType.COUNTING, iconResId = 1,
            colorHex = "#123456", schedule = HabitSchedule.Daily, targetValue = 5)
        val saved = row.copy(id = db.habitDao().insert(row))
        assertNull(habits.getHabitForEditing(saved.id).authority)
        habits.logCompletion(app, saved.id, 2)
        assertEquals(2, db.completionDao().getByHabitOnce(saved.id).single().value)
        assertTrue(db.syncOutboxDao().getAll().all { db.nextRequestDao().origin(NEXT_OPERATION, it.operationId) == null })
    }

    @Test fun realTimerAndFocusWidgetsReadOriginalPolicyAndWithholdCorruptDisplay() = runBlocking<Unit> {
        assertSame(timerWriter, com.dayforge.di.WidgetEntryPoint.from(app).timerWriter())
        val now = java.time.ZonedDateTime.now()
        val rowId = habits.createHabit("Hilt frozen widget timer", "", HabitType.TIMER, 0, "#123456", HabitSchedule.Daily,
            targetValue = 1, isCountdown = true, bestTime = (now.hour * 60 + now.minute).toLong(),
            completionPolicy = "recurring", appearance = appearance("habit.exercise"), creationAuthority = creator.capture())
        val before = requireNotNull(habits.getHabitById(rowId))
        val startAt = System.currentTimeMillis() - 10_000L
        val producer = NextCoreLocalIntentStore(db, tokens, sessions)
        producer.write(requireNotNull(tokens.localCoreWriteAccess()).session) {
            db.timeLogDao().insertSyncedTimer(
                com.dayforge.data.local.entity.TimeLogEntity(habitId = rowId, uuid = id(80), startTime = startAt,
                    date = com.dayforge.util.DateTimeUtils.startOfDayMillis(), endTime = null, durationSeconds = 0,
                    timerNextCommandSequence = 2, timerControlGeneration = 1, timerLastCommandAt = startAt,
                    timerTimezone = now.zone.id),
                com.dayforge.data.local.entity.TimerCommandEntity(commandId = id(81), sessionUuid = id(80),
                    sequence = 1, commandType = "start", occurredAt = startAt, expectedControlGeneration = 0,
                    activityUuid = before.uuid, timezone = now.zone.id),
                com.dayforge.data.local.entity.TimerSegmentEntity(sessionUuid = id(80), sequence = 1, startedAt = startAt))
        }
        producer.write(requireNotNull(tokens.localCoreWriteAccess()).session) {
            db.habitDao().update(before.copy(targetValue = 2, isCountdown = false))
        }
        val manager = androidx.glance.appwidget.GlanceAppWidgetManager(app)
        fun glanceId(widgetId: Int) = requireNotNull(manager.getGlanceIdBy(android.content.Intent().putExtra(
            android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)))
        val timerId = glanceId((System.nanoTime() and 0x1fffffff).toInt() + 1)
        val focusId = glanceId((System.nanoTime() and 0x1fffffff).toInt() + 0x20000000)
        suspend fun refresh() {
            com.dayforge.widget.timer.TimerWidget.refreshWidgetData(app, timerId, rowId)
            com.dayforge.widget.focus.FocusWidget.refreshWidgetData(app, focusId)
        }
        suspend fun assertHealthy() {
            val timer = com.dayforge.widget.timer.TimerWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, timerId)
            val focus = com.dayforge.widget.focus.FocusWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, focusId)
            assertEquals(1, timer[com.dayforge.widget.timer.TimerWidget.TARGET_MINUTES_KEY])
            assertEquals(true, timer[com.dayforge.widget.timer.TimerWidget.IS_COUNTDOWN_KEY])
            assertEquals("RUNNING", timer[com.dayforge.widget.timer.TimerWidget.TIMER_STATE_KEY])
            assertEquals(false, timer[com.dayforge.widget.timer.TimerWidget.READ_FAILED_KEY])
            assertEquals(rowId, focus[com.dayforge.widget.focus.FocusWidget.PRIMARY_HABIT_ID_KEY])
            assertEquals(1, focus[com.dayforge.widget.focus.FocusWidget.PRIMARY_TARGET_VALUE_KEY])
            assertEquals(true, focus[com.dayforge.widget.focus.FocusWidget.PRIMARY_IS_COUNTDOWN_KEY])
            assertEquals(false, focus[com.dayforge.widget.focus.FocusWidget.READ_FAILED_KEY])
            val timerAction = com.dayforge.widget.timer.WidgetTimerAction.decode(requireNotNull(timer[com.dayforge.widget.timer.TimerWidget.ACTION_PROOF_KEY]))
            val focusAction = com.dayforge.widget.timer.WidgetTimerAction.decode(requireNotNull(focus[com.dayforge.widget.focus.FocusWidget.TIMER_ACTION_PROOF_KEY]))
            assertEquals(timerAction, focusAction)
            assertEquals(id(80), timerAction.authority.sessionUuid)
            assertNull(timerAction.startGuard)
        }
        try {
            refresh()
            assertHealthy()
            val queued = db.timeLogDao().getPendingTimerCommands()
            val active = db.timeLogDao().getActiveTimeLog()
            val beforeTick = com.dayforge.widget.timer.TimerWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, timerId)
            val tickBusiness = db.withTransaction { nextRestartDatabaseProof(db) }
            assertTrue(com.dayforge.widget.timer.TimerWidget.refreshElapsedWidgetData(app, timerId, rowId))
            val afterTick = com.dayforge.widget.timer.TimerWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, timerId)
            val elapsedKeys = listOf(com.dayforge.widget.timer.TimerWidget.ELAPSED_SECONDS_KEY,
                com.dayforge.widget.timer.TimerWidget.ACCUMULATED_SECONDS_KEY,
                com.dayforge.widget.timer.TimerWidget.REMAINING_SECONDS_KEY,
                com.dayforge.widget.timer.TimerWidget.IS_COMPLETED_KEY)
            fun withoutElapsed(values: androidx.datastore.preferences.core.Preferences) = values.asMap().filterKeys { it !in elapsedKeys }
            assertEquals(withoutElapsed(beforeTick), withoutElapsed(afterTick))
            assertEquals(tickBusiness, db.withTransaction { nextRestartDatabaseProof(db) })
            val capturedDate = requireNotNull(afterTick[com.dayforge.widget.timer.TimerWidget.TICK_DATE_KEY])
            androidx.glance.appwidget.state.updateAppWidgetState(app, timerId) { it[com.dayforge.widget.timer.TimerWidget.TICK_DATE_KEY] = "2000-01-01" }
            val oldDate = com.dayforge.widget.timer.TimerWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, timerId)
            assertFalse(com.dayforge.widget.timer.TimerWidget.refreshElapsedWidgetData(app, timerId, rowId))
            assertEquals(oldDate, com.dayforge.widget.timer.TimerWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, timerId))
            androidx.glance.appwidget.state.updateAppWidgetState(app, timerId) { it[com.dayforge.widget.timer.TimerWidget.TICK_DATE_KEY] = capturedDate }
            val capturedZone = requireNotNull(afterTick[com.dayforge.widget.timer.TimerWidget.TICK_ZONE_KEY])
            androidx.glance.appwidget.state.updateAppWidgetState(app, timerId) { it[com.dayforge.widget.timer.TimerWidget.TICK_ZONE_KEY] = "Invalid/cached-zone" }
            assertFalse(com.dayforge.widget.timer.TimerWidget.refreshElapsedWidgetData(app, timerId, rowId))
            androidx.glance.appwidget.state.updateAppWidgetState(app, timerId) { it[com.dayforge.widget.timer.TimerWidget.TICK_ZONE_KEY] = capturedZone }
            db.openHelper.writableDatabase.execSQL("UPDATE timer_command_outbox SET occurredAt=occurredAt+1 WHERE commandId=?", arrayOf(id(81)))
            assertFalse(com.dayforge.widget.timer.TimerWidget.refreshElapsedWidgetData(app, timerId, rowId))
            refresh()
            val timer = com.dayforge.widget.timer.TimerWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, timerId)
            val focus = com.dayforge.widget.focus.FocusWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, focusId)
            assertEquals(true, timer[com.dayforge.widget.timer.TimerWidget.READ_FAILED_KEY])
            assertEquals(false, timer[com.dayforge.widget.timer.TimerWidget.DATA_LOADED_KEY])
            assertNull(timer[com.dayforge.widget.timer.TimerWidget.ACTION_PROOF_KEY])
            assertEquals(true, focus[com.dayforge.widget.focus.FocusWidget.READ_FAILED_KEY])
            assertEquals(false, focus[com.dayforge.widget.focus.FocusWidget.DATA_LOADED_KEY])
            assertEquals(active, db.timeLogDao().getActiveTimeLog())
            db.openHelper.writableDatabase.execSQL("UPDATE timer_command_outbox SET occurredAt=? WHERE commandId=?", arrayOf<Any>(startAt, id(81)))
            refresh()
            assertHealthy()
            assertEquals(queued, db.timeLogDao().getPendingTimerCommands())

            // Exercise the real consumer's presentation failure, not just the writer callback.
            // Restore only this exact testbed preference and wait for the shared controller.
            val display = com.dayforge.widget.timer.TimerWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, timerId)
            val business = db.withTransaction { nextRestartDatabaseProof(db) }
            val store = com.dayforge.data.local.DataStoreProvider.get(app)
            val selectionKey = androidx.datastore.preferences.core.stringPreferencesKey("appearance_theme_selection_v1")
            val selection = requireNotNull(store.data.first()[selectionKey])
            val themes = com.dayforge.di.DeviceThemeControllerEntryPoint.from(app).themeController()
            val originalTheme = themes.current()
            try {
                store.edit { it[selectionKey] = "damaged timer publication theme" }
                kotlinx.coroutines.withTimeout(5000) {
                    themes.state.first { it is com.dayforge.data.appearance.DeviceThemeLoadState.Failed }
                }
                val failure = runCatching {
                    com.dayforge.widget.timer.TimerWidget.refreshWidgetData(app, timerId, rowId)
                }.exceptionOrNull()
                assertTrue("Presentation failure must propagate for bounded refresh retry", failure is com.dayforge.data.appearance.ThemeSelectionException)
                assertEquals(display, com.dayforge.widget.timer.TimerWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, timerId))
                assertEquals(business, db.withTransaction { nextRestartDatabaseProof(db) })
                kotlinx.coroutines.withTimeout(5000) { sessions.exclusive { assertEquals(id(1), tokens.authenticationSnapshot()!!.session.userId) } }
            } finally {
                store.edit { it[selectionKey] = selection }
                themes.retry()
                kotlinx.coroutines.withTimeout(5000) {
                    themes.state.first { it is com.dayforge.data.appearance.DeviceThemeLoadState.Ready && it.theme.saved == originalTheme.saved }
                }
            }
            refresh()
            assertHealthy()
            assertEquals(business, db.withTransaction { nextRestartDatabaseProof(db) })
        } finally { com.dayforge.widget.FocusWidgetAlarmScheduler.cancelScheduledRefresh(app) }
    }
}
