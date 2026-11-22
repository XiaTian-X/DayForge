package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    private val completionFault = CompletionInsertNativeFault()
    init { storage.isolatedBuilder = { buildDiagnosticFaultDatabase(it, completionFault) } }
    @Inject lateinit var workflow: LegacyWidgetFactWorkflow
    @Inject lateinit var habits: HabitRepository
    @Inject lateinit var tokens: TokenManager
    @Inject lateinit var sessions: AccountSessionCoordinator
    @Inject lateinit var coordinator: LinkedMetricCoordinator
    @Inject lateinit var preferences: HabitPreferenceRepository
    @Inject internal lateinit var host: com.dayforge.data.local.DatabaseRuntimeHost
    @Inject internal lateinit var graphServices: com.dayforge.data.local.DatabaseEpochServices
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = storage.database
    private fun id(n: Int) = "ab420000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"

    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        hilt.inject()
        sessions.exclusive { tokens.clearTokens(); tokens.saveLoginSession("synthetic-first", "synthetic-refresh", "member", id(1), false) }
    }
    @After fun cleanup() = runBlocking<Unit> {
        if (::coordinator.isInitialized) {
            // Teardown closes only this exact already-published in-memory prompt. It does
            // not acquire a new graph/source or consume its durable pending preference.
            coordinator.postCheckInFrames.value?.let { original -> coordinator.dismissPrompt(original) }
            assertNull(coordinator.postCheckInState.value)
            assertNull(coordinator.postCheckInFrames.value)
        }
        if (::tokens.isInitialized) sessions.exclusive { tokens.clearTokens() }
    }
    private suspend fun create(type: HabitType = HabitType.CHECK_IN, countdown: Boolean = false, target: Int = 1): Long =
        db.habitDao().insert(HabitEntity(name = "Legacy $type $countdown", habitType = type, iconResId = 0,
            colorHex = "#123456", schedule = HabitSchedule.Daily, targetValue = target, isCountdown = countdown))
    private suspend fun identity(habitId: Long) = sessions.exclusive { db.withTransaction {
        LegacyWidgetRestartClaim.displayed(requireNotNull(db.habitDao().getVisibleHabitById(habitId)),
            requireNotNull(tokens.localCoreWriteAccess()), com.dayforge.data.local.LocalReplicaIdentity.readInTransaction(db))
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
        val fullBefore = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
        completionFault.arm(habitId, original.identity.habitUuid)
        try {
            val failure = try { workflow.perform(app, original); null }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) { error }
            completionFault.requireRejected(failure)
            assertFalse(db.inTransaction())
            assertEquals(before, proof())
            assertEquals(fullBefore, db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents })
            assertEquals(0, db.completionDao().countAll())
            assertNull(db.completionDao().getCompletionByUuid(requireNotNull(completionFault.insertedUuid)))
            assertTrue(db.syncOutboxDao().getAll().none { it.entityUuid == completionFault.insertedUuid })
        } finally { completionFault.disarm() }
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
        // Hilt's stable coordinator owns the real Host graph. Prove it opens the same
        // physical dataset and account collaborators as this original fixed DAO fixture.
        val fixtureStorage = db.withTransaction { com.dayforge.data.local.LocalReplicaIdentity.readInTransaction(db) }
        assertNotNull(fixtureStorage)
        suspend fun show(identity: LegacyWidgetRestartClaim) = host.withGraph { graph ->
            assertSame(tokens, graphServices.tokenManager)
            assertSame(sessions, graphServices.accountSessionCoordinator)
            assertEquals(java.io.File(requireNotNull(db.openHelper.writableDatabase.path)).canonicalPath,
                java.io.File(requireNotNull(graph.database.openHelper.writableDatabase.path)).canonicalPath)
            assertEquals(fixtureStorage, graph.database.withTransaction {
                com.dayforge.data.local.LocalReplicaIdentity.readInTransaction(graph.database)
            })
            assertEquals(db.habitDao().getVisibleHabitById(habitId), graph.habitDao.getVisibleHabitById(habitId))
            coordinator.showLegacyWidgetPrompt(identity)
            requireNotNull(coordinator.postCheckInFrames.value).also { frame ->
                assertSame(coordinator.postCheckInState.value, frame.value)
            }
        }
        val originalFrame = show(original.identity)
        val source = requireNotNull(originalFrame.value?.preferenceClaim)
        assertEquals(original.identity, source.widgetIdentity)
        suspend fun submit() = withContext(Dispatchers.Main) {
            host.withSourceGraph(originalFrame.publication) {
                coordinator.recordMetricValues(habitId, listOf(MetricValueInput(metricId, 4.0)),
                    expectedPreferenceClaim = source, frame = originalFrame)
            }
        }
        assertTrue(submit()); assertTrue(submit())
        assertEquals(1, db.metricLogDao().countAll())
        val nextFrame = show(original.identity)
        val next = requireNotNull(nextFrame.value?.preferenceClaim)
        tokens.saveSyncDeviceId(id(4))
        val before = proof()
        withContext(Dispatchers.Main) {
            try {
                host.withSourceGraph(nextFrame.publication) { fail("Expired original prompt source was admitted") }
                fail("Expected original source rejection")
            } catch (expired: IllegalStateException) {
                assertEquals("DATABASE_EPOCH_INPUT_EXPIRED", expired.message)
            }
            // Also retain the original coordinator-level false-result contract. Binding a
            // graph does not re-sign the old frame: its own first guard must still reject it.
            host.withGraph {
                assertFalse(coordinator.recordMetricValues(habitId, listOf(MetricValueInput(metricId, 5.0)),
                    expectedPreferenceClaim = next, frame = nextFrame))
            }
        }
        assertEquals(before, proof())
        rejected { show(original.identity) }
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

    /** Same production-equivalent native seam as Settings203, under the real controlled
     * factory. Only this exact generated completion insert can arm a fixed engine failure.
     * Always wrap cached statements; no generic SQL callback or raw handle escapes.
     */
    private class CompletionInsertNativeFault : SupportSQLiteOpenHelper.Factory {
        private val armed = AtomicBoolean()
        private val writes = AtomicInteger()
        private val rejections = AtomicInteger()
        private val completedEnds = AtomicInteger()
        private var expectedHabitId = -1L
        private var expectedHabitUuid = ""
        @Volatile var insertedUuid: String? = null; private set
        @Volatile private var rejectedByEngine: SQLiteConstraintException? = null
        @Volatile private var faultThread: Thread? = null
        @Volatile private var faultNative: SupportSQLiteDatabase? = null
        fun arm(habitId: Long, habitUuid: String) {
            check(writes.get() == 0 && rejections.get() == 0 && completedEnds.get() == 0)
            expectedHabitId = habitId; expectedHabitUuid = habitUuid
            check(armed.compareAndSet(false, true))
        }
        fun disarm() { armed.set(false) }
        fun requireRejected(failure: Throwable?) {
            assertEquals("Actual generated completion INSERT must execute exactly once", 1, writes.get())
            assertEquals("Fixed duplicate must be refused by SQLite itself", 1, rejections.get())
            assertEquals("Fault transaction must reach native outer END on its original thread", 1, completedEnds.get())
            assertFalse(armed.get())
            val actual = requireNotNull(rejectedByEngine)
            assertTrue("The same native constraint must escape the original writer",
                generateSequence(failure) { it.cause }.any { it === actual })
        }
        private fun matches(sql: String) = sql.startsWith("INSERT OR REPLACE INTO `completions` (`id`,")
        private fun <T> executed(sql: String, raw: SupportSQLiteDatabase, action: () -> T): T {
            val result = action()
            if (matches(sql) && armed.compareAndSet(true, false)) {
                check(raw.inTransaction()) { "WIDGET_TEST_NATIVE_INSERT_OUTSIDE_TRANSACTION" }
                raw.query("SELECT changes()").use { check(it.moveToFirst() && it.getLong(0) == 1L && !it.moveToNext()) }
                val inserted = raw.query("SELECT last_insert_rowid()").use {
                    check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER)
                    it.getLong(0).also { value -> check(value > 0L && !it.moveToNext()) }
                }
                val uuid = raw.query("SELECT habitId,habitUuid,uuid,value FROM main.completions WHERE id=? LIMIT 2",
                    arrayOf(inserted)).use { c ->
                    check(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_INTEGER && c.getLong(0) == expectedHabitId &&
                        c.getType(1) == Cursor.FIELD_TYPE_STRING && c.getString(1) == expectedHabitUuid &&
                        c.getType(2) == Cursor.FIELD_TYPE_STRING && c.getType(3) == Cursor.FIELD_TYPE_INTEGER && c.getLong(3) == 1L)
                    c.getString(2).also { value -> check(com.dayforge.domain.model.isContractUuid(value) && !c.moveToNext()) }
                }
                // The original INSERT and trigger-produced source both exist BEFORE failure.
                raw.query("SELECT count(*) FROM main.sync_outbox WHERE recordType='completion' AND action='upsert' " +
                    "AND entityUuid=? AND wireEntityUuid=? AND referenceUuid=?", arrayOf(uuid, uuid, expectedHabitUuid)).use {
                    check(it.moveToFirst() && it.getLong(0) == 1L && !it.moveToNext()) { "WIDGET_TEST_NATIVE_SOURCE_NOT_WRITTEN" }
                }
                insertedUuid = uuid; writes.incrementAndGet(); faultNative = raw; faultThread = Thread.currentThread()
                try {
                    raw.execSQL("INSERT INTO completions SELECT * FROM completions WHERE id=?", arrayOf(inserted))
                } catch (failure: SQLiteConstraintException) {
                    check(failure.message?.contains("UNIQUE constraint failed: completions.id") == true) {
                        "WIDGET_TEST_WRONG_NATIVE_CONSTRAINT"
                    }
                    rejectedByEngine = failure; rejections.incrementAndGet(); throw failure
                }
                error("WIDGET_TEST_NATIVE_DUPLICATE_WAS_ACCEPTED")
            }
            return result
        }
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            var rawOwner: SupportSQLiteDatabase? = null
            var wrapper: SupportSQLiteDatabase? = null
            val monitor = Any()
            fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
                if (rawOwner !== raw) {
                    rawOwner = raw
                    wrapper = object : SupportSQLiteDatabase by raw {
                        override fun compileStatement(sql: String): SupportSQLiteStatement {
                            val actual = raw.compileStatement(sql)
                            return object : SupportSQLiteStatement by actual {
                                override fun execute() = executed(sql, raw) { actual.execute() }
                                override fun executeInsert() = executed(sql, raw) { actual.executeInsert() }
                                override fun executeUpdateDelete() = executed(sql, raw) { actual.executeUpdateDelete() }
                            }
                        }
                        override fun endTransaction() {
                            raw.endTransaction()
                            if (faultNative === raw && faultThread === Thread.currentThread() && rejectedByEngine != null && !raw.inTransaction()) {
                                faultThread = null; faultNative = null; completedEnds.incrementAndGet()
                            }
                        }
                    }
                }
                requireNotNull(wrapper)
            }
            val parent = configuration.callback
            val callback = object : SupportSQLiteOpenHelper.Callback(parent.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
                override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(wrap(db), oldVersion, newVersion)
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onDowngrade(wrap(db), oldVersion, newVersion)
                override fun onOpen(db: SupportSQLiteDatabase) = parent.onOpen(wrap(db))
                override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
            }
            val actual = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : SupportSQLiteOpenHelper by actual {
                override val readableDatabase get() = wrap(actual.readableDatabase)
                override val writableDatabase get() = wrap(actual.writableDatabase)
            }
        }
    }

}
