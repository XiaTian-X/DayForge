package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.ui.components.MetricValueInput
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real v4 Room/outbox and authenticated Hilt graph; only launcher refresh is isolated. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LegacyWidgetFactWorkflowTest {
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val storage = PhysicalDatabaseRule()
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @Inject lateinit var workflow: LegacyWidgetFactWorkflow
    @Inject lateinit var habits: HabitRepository
    @Inject lateinit var tokens: TokenManager
    @Inject lateinit var sessions: AccountSessionCoordinator
    @Inject lateinit var coordinator: LinkedMetricCoordinator
    @Inject lateinit var preferences: HabitPreferenceRepository
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = storage.database
    private fun id(n: Int) = "ab420000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"

    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        hilt.inject()
        sessions.exclusive { tokens.clearTokens(); tokens.saveLoginSession("synthetic-first", "synthetic-refresh", "member", id(1), false) }
    }
    @After fun cleanup() = runBlocking<Unit> {
        if (::coordinator.isInitialized) coordinator.closePrompt()
        if (::tokens.isInitialized) sessions.exclusive { tokens.clearTokens() }
    }
    private suspend fun create(type: HabitType = HabitType.CHECK_IN, countdown: Boolean = false, target: Int = 1): Long =
        db.habitDao().insert(HabitEntity(name = "Legacy $type $countdown", habitType = type, iconResId = 0,
            colorHex = "#123456", schedule = HabitSchedule.Daily, targetValue = target, isCountdown = countdown))
    private suspend fun identity(habitId: Long) = sessions.exclusive { db.withTransaction {
        LegacyWidgetRestartClaim.displayed(requireNotNull(db.habitDao().getVisibleHabitById(habitId)),
            requireNotNull(tokens.localCoreWriteAccess()))
    } }
    private suspend fun claim(habitId: Long, action: String) = workflow.capture(identity(habitId)).forAction(action)
    private suspend fun proof() = db.withTransaction { nextRestartDatabaseProof(db) }
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Expected stale legacy operation to fail") }
        catch (error: Exception) { assertTrue(error.message.orEmpty().isNotBlank()) }
    }

    @Test fun authenticatedOfflineCountingPreservesContinuousIncrementUndoAndCountdownFloor() = runBlocking<Unit> {
        val count = create(HabitType.COUNTING, target = 2)
        val snapshot = workflow.capture(identity(count))
        assertEquals(0L, snapshot.todayQuantity)
        assertEquals(snapshot, LegacyWidgetFactClaim.decode(snapshot.encode()))
        val increment = snapshot.forAction("increment")
        workflow.perform(app, increment); workflow.perform(app, increment); workflow.perform(app, increment)
        assertEquals(3, habits.getTodayCompletionCount(count))
        val undo = claim(count, "decrement")
        workflow.perform(app, undo)
        assertEquals(2, habits.getTodayCompletionCount(count))
        val unchanged = proof()
        rejected { workflow.perform(app, undo) }
        assertEquals(unchanged, proof())
        val down = create(HabitType.COUNTING, countdown = true, target = 2)
        val decrement = claim(down, "decrement")
        assertFalse(workflow.perform(app, decrement).result.completed)
        assertTrue(workflow.perform(app, decrement).result.completed)
        val atZero = proof()
        assertFalse(workflow.perform(app, decrement).added)
        assertEquals(atZero, proof())
        assertEquals(2, habits.getTodayCompletionCount(down))
        assertEquals(4, db.completionDao().countAll())
        assertTrue(db.syncOutboxDao().getAll().any { it.recordType == "completion" })
        val reopened = storage.reopen()
        assertEquals(4, reopened.completionDao().countAll())
        assertEquals(atZero, reopened.withTransaction { nextRestartDatabaseProof(reopened) })
    }

    @Test fun toggleUsesRenderedCompletionInsteadOfTogglingNewerState() = runBlocking<Unit> {
        val habit = create()
        val original = claim(habit, "toggle")
        assertTrue(workflow.perform(app, original).result.completed)
        val after = proof()
        rejected { workflow.perform(app, original) }
        assertEquals(after, proof())
        assertFalse(workflow.perform(app, claim(habit, "toggle")).result.completed)
        assertEquals(0, db.completionDao().countAll())
    }

    @Test fun expiredDayActionPlanAndReusedUuidCannotMutateBusinessOrOutbox() = runBlocking<Unit> {
        val habitId = create()
        val original = claim(habitId, "toggle")
        var before = proof()
        rejected { workflow.perform(app, original.copy(date = java.time.LocalDate.parse(original.date).minusDays(1).toString())) }
        rejected { workflow.perform(app, original.copy(action = "increment")) }
        assertEquals(before, proof())
        val habit = requireNotNull(db.habitDao().getVisibleHabitById(habitId))
        db.habitDao().update(habit.copy(targetValue = 2))
        before = proof()
        rejected { workflow.perform(app, original) }
        assertEquals(before, proof())
        db.habitDao().update(habit.copy(uuid = id(9)))
        before = proof()
        rejected { workflow.perform(app, original) }
        assertEquals(before, proof())
    }

    @Test fun reauthenticationReplicaAndDeviceChangesRejectOldScopeWithoutAdoptingCurrentAccount() = runBlocking<Unit> {
        val habitId = create()
        val original = claim(habitId, "toggle")
        val before = proof()
        sessions.exclusive { tokens.saveLoginSession("renewed", "renewed-refresh", "member", id(1), false) }
        rejected { workflow.perform(app, original) }
        val current = claim(habitId, "toggle")
        tokens.saveServerIdentity(id(2), id(3))
        rejected { workflow.perform(app, current) }
        val known = claim(habitId, "toggle")
        tokens.saveSyncDeviceId(id(4))
        rejected { workflow.perform(app, known) }
        assertEquals(before, proof())
    }

    @Test fun actualCompletionFailureRollsBackFactActivityRateAndOutboxTogether() = runBlocking<Unit> {
        val habitId = create()
        val original = claim(habitId, "toggle")
        val before = proof()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER legacy_widget_abort AFTER INSERT ON completions BEGIN SELECT RAISE(ABORT, 'injected widget write failure'); END")
        try {
            rejected { workflow.perform(app, original) }
            assertEquals(before, proof())
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER legacy_widget_abort") }
        assertTrue(workflow.perform(app, original).result.completed)
        assertEquals(1, db.completionDao().countAll())
    }

    @Test fun metricFollowUpRetainsDeviceAndOriginalScopeThroughItsActualWriteTransaction() = runBlocking<Unit> {
        val habitId = create()
        val original = claim(habitId, "toggle")
        workflow.perform(app, original)
        val metric = MetricEntity(name = "Bound metric", unit = "kg", iconResId = 0, colorHex = "#123456")
        val metricId = db.metricDao().insert(metric)
        db.habitMetricLinkDao().insert(HabitMetricLinkEntity(habitId = habitId, habitUuid = original.identity.habitUuid,
            metricId = metricId, metricUuid = metric.uuid, coefficient = 1.0, showInHabitDetail = true, promptOnComplete = true))
        coordinator.showLegacyWidgetPrompt(original.identity)
        val source = requireNotNull(coordinator.postCheckInState.value?.preferenceClaim)
        assertEquals(original.identity, source.widgetIdentity)
        suspend fun submit() = withContext(Dispatchers.Main) {
            coordinator.recordMetricValues(habitId, listOf(MetricValueInput(metricId, 4.0)), expectedPreferenceClaim = source)
        }
        assertTrue(submit()); assertTrue(submit())
        assertEquals(1, db.metricLogDao().countAll())
        coordinator.showLegacyWidgetPrompt(original.identity)
        val next = requireNotNull(coordinator.postCheckInState.value?.preferenceClaim)
        tokens.saveSyncDeviceId(id(4))
        val before = proof()
        withContext(Dispatchers.Main) {
            assertFalse(coordinator.recordMetricValues(habitId, listOf(MetricValueInput(metricId, 5.0)), expectedPreferenceClaim = next))
        }
        assertEquals(before, proof())
        rejected { coordinator.showLegacyWidgetPrompt(original.identity) }
        assertNull(coordinator.postCheckInState.value)

        // The network authenticator does not take the account mutex. Invalidate the real
        // credentials after the actual DAO insert, before the outer Room transaction commits.
        val currentIdentity = identity(habitId)
        val currentHabit = requireNotNull(db.habitDao().getVisibleHabitById(habitId))
        val currentSource = preferences.read(currentHabit, currentIdentity.session(), currentIdentity).claim
        val authentication = requireNotNull(tokens.authenticationSnapshot())
        var insertedBeforeInvalidation = false
        val invalidatingLogs = object : MetricLogDao by db.metricLogDao() {
            override suspend fun insertAll(logs: List<MetricLogEntity>): List<Long> {
                val ids = db.metricLogDao().insertAll(logs)
                insertedBeforeInvalidation = ids.isNotEmpty() && db.metricLogDao().countAll() == 2
                tokens.clearRejectedRefresh(authentication)
                return ids
            }
        }
        val writingMetrics = MetricRepository(db, db.metricDao(), invalidatingLogs, db.habitDao(), db.habitMetricLinkDao())
        val beforeInvalidation = proof()
        rejected {
            writingMetrics.recordLegacyPromptValues(listOf(MetricValueDraft(metricId, 6.0)), System.currentTimeMillis(),
                currentSource, preferences, currentHabit, listOf(requireNotNull(db.metricDao().getVisibleMetricById(metricId))))
        }
        assertTrue(insertedBeforeInvalidation)
        assertNull(tokens.authenticationSnapshot())
        assertEquals(beforeInvalidation, proof())
        assertEquals(1, db.metricLogDao().countAll())
    }
}
