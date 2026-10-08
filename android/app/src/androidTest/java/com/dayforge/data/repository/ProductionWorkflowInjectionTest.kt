package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
}
