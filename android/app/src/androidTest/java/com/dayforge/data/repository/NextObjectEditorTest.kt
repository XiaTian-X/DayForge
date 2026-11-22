package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.dayforge.data.local.CountTrustRuntime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import androidx.lifecycle.SavedStateHandle
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
import com.dayforge.ui.screens.editgoal.EditGoalViewModel
import com.dayforge.ui.screens.edithabit.EditHabitViewModel
import com.dayforge.ui.screens.editmetric.EditMetricViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NextObjectEditorTest : NextObjectEditorFixture() {
    private val originFault = EditorOriginAbortFault()
    init { storage.isolatedBuilder = { buildDiagnosticFaultDatabase(it, originFault, storage.queryCallback) } }
    private fun appearance(role: String) = ObjectAppearance(IconReference.Role(role), "#123456", "theme")
    private suspend fun metricTicket() = editingMetrics().getMetricForEditing(metric.id)
    private suspend fun habitTicket() = editingHabits().getHabitForEditing(habit.id)

    @Test fun batchMetricTicketsPreserveOrderAndRejectChangedMemberOrOldAccountAtomically() = runBlocking<Unit> {
        val second = metric.copy(id = 0, uuid = id(81), name = "Second metric", unit = "hour")
        val secondId = db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.metricDao().insert(second).also {
                db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            }
        }
        val repo = editingMetrics()
        val snapshots = repo.getMetricsForEditing(listOf(secondId, metric.id, secondId, Long.MAX_VALUE))
        assertEquals(listOf(secondId, metric.id, secondId, null), snapshots.map { it.value?.id })
        assertSame(snapshots[0].authority, snapshots[2].authority)
        assertNull(snapshots[3].authority)
        val selected = snapshots.take(2)
        val values = selected.map { MetricValueDraft(it.value!!.id, 3.5) }
        val originalRows = selected.map { it.value!! }
        val ticket = selected.first().authority!!
        val stamp = 1_786_000_000_123L
        repo.recordValues(values, stamp, ticket, expectedMetrics = originalRows)
        repo.recordValues(values, stamp, ticket, expectedMetrics = originalRows) // New explicit operation, same value/time.
        assertEquals(4, count("metric_logs")); assertEquals(4, count("next_request_origins"))
        assertEquals(listOf("hour", "hour"), db.metricLogDao().getAllLogsForMetric(secondId).map { it.unit })
        assertTrue(db.metricLogDao().getAllLogsForMetric(secondId).all { it.date == stamp })
        val before = db.syncOutboxDao().getAll()
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.metricDao().update(metric.copy(unit = "changed"))
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        rejected { repo.recordValues(values, stamp, ticket, expectedMetrics = originalRows) }
        assertEquals(before, db.syncOutboxDao().getAll()); assertEquals(4, count("metric_logs"))
        val fresh = repo.getMetricsForEditing(listOf(secondId, metric.id))
        tokens.saveLoginSession("synthetic-second", "synthetic-refresh", "other", id(91), false)
        rejected { repo.recordValues(values, stamp, fresh.first().authority, expectedMetrics = fresh.map { it.value!! }) }
        assertEquals(before, db.syncOutboxDao().getAll()); assertEquals(4, count("metric_logs"))
    }

    @Test fun typedMetricEditorSavesFrozenIntentAndRealHttpAcceptanceThenColdReopens() = runBlocking<Unit> {
        register()
        val model = withContext(Dispatchers.Main) { own(EditMetricViewModel(app, db.metricDao(), editingMetrics(),
            SavedStateHandle(mapOf("metricId" to metric.id)))) }
        withTimeout(5000) { model.uiState.first { it.isLoaded } }
        assertEquals(metric.appearance, model.uiState.value.appearance)
        assertEquals(metric.appearance!!.accentColor, model.uiState.value.colorHex)
        assertNotNull(model.uiState.value.editAuthority)
        withContext(Dispatchers.Main) {
            model.updateName("Native edited metric")
            model.updateAppearance(appearance("metric.changed"))
            repeat(20) { model.saveMetric() }
        }
        val saved = withTimeout(5000) { model.uiState.first { it.isSaved || it.errorMessage != null } }
        assertNull(saved.errorMessage); assertTrue(saved.isSaved)
        withContext(Dispatchers.Main) { repeat(20) { model.saveMetric() } }
        assertFalse(model.uiState.value.isSaving)
        assertNull(model.uiState.value.errorMessage)
        val row = db.syncOutboxDao().getAll().single()
        val origin = originalIntent(row)
        assertEquals(5, origin.protocol); assertEquals(id(1), origin.accountId)
        assertEquals("metric.changed", Json.parseToJsonElement(origin.intentJson).jsonObject
            .getValue("payload").jsonObject.getValue("appearance").jsonObject.getValue("icon").jsonObject
            .getValue("role").jsonPrimitive.content)
        val (http, server) = channel { successReply(it) }
        assertNotNull(sender(http).sendAndAcceptOperation(access(), row.operationId))
        assertEquals(1, server.requests.count { it.path.endsWith("/push") })
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        storage.reopen()
        val reopened = requireNotNull(db.metricDao().getMetricById(metric.id))
        assertEquals("Native edited metric", reopened.name); assertEquals(appearance("metric.changed"), reopened.appearance)
        assertEquals(origin, db.nextRequestDao().origin(NEXT_OPERATION, row.operationId))
    }

    @Test fun typedHabitEditUpdatesPreferredTimeButPreservesUnseenMetadataAndDerivedState() = runBlocking<Unit> {
        val baseline = habit.copy(planMetadata = habit.planMetadata!!.copy(sortOrder = 37, startDate = "2026-10-01",
            targetUnit = "pages", preferredLocalTime = "08:05:12+08:00"), bestTime = 485)
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitDao().update(baseline)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val snapshot = habitTicket()
        val modified = requireNotNull(snapshot.value).copy(name = "Edited recurring", bestTime = 570,
            appearance = appearance("habit.exercise"), activityRate = 0,
            planMetadata = baseline.planMetadata!!.copy(sortOrder = 0, startDate = null, targetUnit = null))
        editingHabits().updateHabit(modified, editAuthority = snapshot.authority)
        val saved = requireNotNull(db.habitDao().getHabitById(habit.id))
        assertEquals(baseline.activityRate, saved.activityRate)
        assertEquals(baseline.activityRateUpdatedAt, saved.activityRateUpdatedAt)
        assertEquals(baseline.planMetadata!!.copy(preferredLocalTime = "09:30"), saved.planMetadata)
        assertEquals("09:30", Json.parseToJsonElement(originalIntent(db.syncOutboxDao().getAll().single()).intentJson)
            .jsonObject.getValue("payload").jsonObject.getValue("activity").jsonObject
            .getValue("preferred_local_time").jsonPrimitive.content)
    }

    @Test fun editBeforeFirstDeviceRegistrationRetainsNullableReplicaAndOriginalTime() = runBlocking<Unit> {
        val snapshot = habitTicket()
        editingHabits().updateHabit(habit.copy(description = "offline", appearance = appearance("habit.changed")),
            editAuthority = snapshot.authority)
        val origin = originalIntent(db.syncOutboxDao().getAll().single())
        assertNull(origin.serverInstanceId); assertNull(origin.syncEpoch)
        assertEquals(habit.createdAt, db.habitDao().getHabitById(habit.id)!!.createdAt)
        assertEquals(habit.planMetadata, db.habitDao().getHabitById(habit.id)!!.planMetadata)
    }

    @Test fun staleAuthenticationAndRemoteStructureChangesDoNotWriteOrFreezeWork() = runBlocking<Unit> {
        val first = metricTicket()
        sessions.exclusive { tokens.saveLoginSession("synthetic-new", "synthetic-refresh", "member", id(1), false) }
        rejected { editingMetrics().updateMetric(metric.copy(name = "Stale login"), first.authority) }
        assertEquals(metric, db.metricDao().getMetricById(metric.id)); assertEquals(0, count("next_request_origins"))
        val second = metricTicket()
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.metricDao().update(metric.copy(description = "Remote edit"))
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        assertEquals("OBJECT_EDIT_CHANGED_RELOAD_REQUIRED", rejected {
            editingMetrics().updateMetric(metric.copy(name = "Stale structure"), second.authority)
        }.message)
        assertEquals("Remote edit", db.metricDao().getMetricById(metric.id)!!.description)
        assertTrue(db.syncOutboxDao().getAll().isEmpty()); assertEquals(0, count("next_request_origins"))
    }

    @Test fun newFixedAssetRequiresOwnershipAndCorrectPurposeButExistingMissingReferenceIsRetained() = runBlocking<Unit> {
        register()
        val context = iconMetadata.capture()
        val blob = IconBlob("1".repeat(64), 1, "image/svg+xml", 1, 1)
        val task = IconAsset(id(60), "Task only", "task", "template", blob, null)
        val general = task.copy(assetId = id(61), name = "General", purpose = "general")
        iconMetadata.reserveAsset(context, task); iconMetadata.reserveAsset(context, general)
        val snapshot = metricTicket()
        for (asset in listOf(id(60), id(62))) {
            rejected { editingMetrics().updateMetric(metric.copy(appearance = ObjectAppearance(IconReference.Asset(asset),
                "#123456", "object")), snapshot.authority) }
            assertEquals(metric, db.metricDao().getMetricById(metric.id)); assertEquals(0, count("sync_outbox"))
        }
        editingMetrics().updateMetric(metric.copy(appearance = ObjectAppearance(IconReference.Asset(id(61)),
            "#123456", "object")), snapshot.authority)
        assertEquals(IconReference.Asset(id(61)), db.metricDao().getMetricById(metric.id)!!.appearance!!.icon)

        val missing = habit.copy(appearance = ObjectAppearance(IconReference.Asset(id(63)), "#123456", "theme"))
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitDao().update(missing)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val retained = habitTicket()
        editingHabits().updateHabit(missing.copy(description = "Keep missing icon"), editAuthority = retained.authority)
        assertEquals(missing.appearance, db.habitDao().getHabitById(habit.id)!!.appearance)
    }

    @Test fun knownReadOnlyPermissionAndOriginFailureRollBackBusinessAndOutbox() = runBlocking<Unit> {
        register(permissions = setOf("sync.read"))
        val readonly = metricTicket()
        rejected { editingMetrics().updateMetric(metric.copy(name = "Forbidden"), readonly.authority) }
        assertEquals(metric, db.metricDao().getMetricById(metric.id)); assertEquals(0, count("sync_outbox"))
        register()
        val snapshot = metricTicket()
        val originalDatabase = db
        val originalRuntime = requireNotNull(CountTrustRuntime.forDatabase(db))
        originFault.arm(metric.id, metric.uuid, requireNotNull(snapshot.authority).session.authentication.userId)
        val failure = rejected { editingMetrics().updateMetric(metric.copy(name = "Must rollback"), snapshot.authority) }
        originFault.requireTriggeredAndRolledBack(failure)
        assertSame(originalDatabase, db); assertSame(originalRuntime, CountTrustRuntime.forDatabase(db))
        assertEquals(metric, db.metricDao().getMetricById(metric.id))
        assertEquals(0, count("sync_outbox")); assertEquals(0, count("next_request_origins"))
        editingMetrics().updateMetric(metric.copy(name = "Retry"), snapshot.authority)
        originFault.requireWarmRetryCommitted()
        assertSame(originalDatabase, db); assertSame(originalRuntime, CountTrustRuntime.forDatabase(db))
        assertEquals("Retry", db.metricDao().getMetricById(metric.id)!!.name)
        assertNotNull(originalIntent(db.syncOutboxDao().getAll().single()))
    }

    private suspend fun insertPlan(row: HabitEntity): HabitEntity = db.withTransaction {
        db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        val inserted = row.copy(id = db.habitDao().insert(row))
        db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        inserted
    }

    @Test fun actualGoalEditorKeepsDatesAndSortOrderAndFreezesTypedAppearance() = runBlocking<Unit> {
        val goal = insertPlan(habit.copy(id = 0, uuid = id(70), name = "Goal", habitType = HabitType.GOAL,
            targetValue = 1, completionPolicy = null, appearance = appearance("goal.project"),
            planMetadata = habit.planMetadata!!.copy(timezone = null, sortOrder = 81,
                startDate = "2026-10-01", goalDueDate = "2026-12-31")))
        val model = withContext(Dispatchers.Main) {
            own(EditGoalViewModel(app, db.habitDao(), editingHabits(), preferences)).also { it.loadGoal(goal.id) }
        }
        withTimeout(5000) { model.uiState.first { !it.isLoading } }
        assertEquals(goal.appearance!!.accentColor, model.uiState.value.colorHex)
        withContext(Dispatchers.Main) { model.updateAppearance(appearance("goal.changed")); model.saveGoal() }
        val saved = withTimeout(5000) { model.uiState.first { it.saved || it.errorMessage != null } }
        assertNull(saved.errorMessage); assertTrue(saved.saved)
        val row = requireNotNull(db.habitDao().getHabitById(goal.id))
        assertEquals(goal.planMetadata, row.planMetadata); assertNull(row.completionPolicy)
        assertEquals(appearance("goal.changed"), row.appearance)
        assertNotNull(originalIntent(db.syncOutboxDao().getAll().single()))
        assertEquals(1, widgetRefresh.requestCount)
    }

    @Test fun actualOnceEditorDoesNotOfferRecurringCycleOrDailyReminderAndKeepsProjection() = runBlocking<Unit> {
        val once = insertPlan(habit.copy(id = 0, uuid = id(71), name = "Once", habitType = HabitType.CHECK_IN,
            schedule = HabitSchedule.Once("2026-10-10"), targetValue = 1, targetCycles = null,
            completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0,
            appearance = appearance("task.shopping")))
        val model = withContext(Dispatchers.Main) { own(EditHabitViewModel(editingHabits(), db.habitDao(),
            db.timeLogDao(), db.completionDao(), db.metricDao(), db.habitMetricLinkDao(), preferences, app))
            .also { it.loadHabit(once.id) } }
        val loaded = withTimeout(5000) { model.uiState.first { !it.isLoading } }
        assertFalse(loaded.habitNotFound); assertEquals("one_and_done", loaded.completionPolicy)
        assertEquals(once.appearance!!.accentColor, loaded.colorHex)
        assertEquals(0, loaded.originalScheduleDays)
        withContext(Dispatchers.Main) {
            model.updateSchedule(HabitSchedule.Daily); model.updateTargetCycles(1); model.updateBestTime(600)
            model.updateAppearance(appearance("task.changed")); model.saveChanges()
        }
        val saved = withTimeout(5000) { model.uiState.first { it.saved || it.errorMessage != null } }
        assertNull(saved.errorMessage); assertTrue(saved.saved)
        val row = requireNotNull(db.habitDao().getHabitById(once.id))
        assertEquals(once.schedule, row.schedule); assertNull(row.targetCycles); assertNull(row.bestTime)
        assertEquals(0, row.oneTimeConfirmedVersion); assertEquals(appearance("task.changed"), row.appearance)
        assertNotNull(originalIntent(db.syncOutboxDao().getAll().single()))
        assertEquals(1, widgetRefresh.requestCount)
    }

    @Test fun habitBackgroundRefreshCannotEraseUnsavedFieldsOrSilentlyRebindTheirEditTicket() = runBlocking<Unit> {
        val model = withContext(Dispatchers.Main) { own(EditHabitViewModel(editingHabits(), db.habitDao(),
            db.timeLogDao(), db.completionDao(), db.metricDao(), db.habitMetricLinkDao(), preferences, app))
            .also { it.loadHabit(habit.id) } }
        withTimeout(5000) { model.uiState.first { !it.isLoading } }
        val originalAuthority = model.uiState.value.editAuthority
        withContext(Dispatchers.Main) { model.updateName("Unsaved local name") }
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitDao().update(habit.copy(description = "Remote edit"))
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        withContext(Dispatchers.Main) { model.saveChanges() }
        val stopped = withTimeout(5000) { model.uiState.first { it.errorMessage != null } }
        assertFalse(stopped.saved); assertEquals("Unsaved local name", stopped.name)
        assertSame(originalAuthority, stopped.editAuthority)
        assertEquals("Remote edit", db.habitDao().getHabitById(habit.id)!!.description)
        assertEquals(habit.name, db.habitDao().getHabitById(habit.id)!!.name)
        assertEquals(0, count("next_request_origins")); assertEquals(0, count("sync_outbox"))
        assertEquals(0, widgetRefresh.requestCount)
    }
}


/** Fixed native origin ABORT below the unchanged controlled factory. No SQL, callback or
 * native handle escapes; the same open connection must support the original warm retry. */
private class EditorOriginAbortFault : SupportSQLiteOpenHelper.Factory {
    private data class Cell(val type: Int, val value: Any?)
    private data class Rows(val columns: List<String>, val rows: List<List<Cell>>)
    private data class MainImage(val version: Int, val schemaVersion: Long, val schema: Rows, val tables: Map<String, Rows>)
    private class Attempt(val metricId: Long, val metricUuid: String, val accountId: String,
        val native: SupportSQLiteDatabase, val before: MainImage) {
        val armed = AtomicBoolean(true)
        val originActors = AtomicInteger()
        val abort = AtomicReference<SQLiteConstraintException?>()
        val marks = AtomicInteger()
        val ends = AtomicInteger()
        val retryActors = AtomicInteger()
        val retryReturned = AtomicInteger()
        val retryMarks = AtomicInteger()
        val retryEnds = AtomicInteger()
        val endFailure = AtomicReference<Throwable?>()
        @Volatile var originalEffects = false
        @Volatile var rollbackEqual = false
        @Volatile var gateRolledBack = false
    }
    private data class Frame(val attempt: Attempt, val raw: SupportSQLiteDatabase, val retry: Boolean)
    private val installNext = AtomicBoolean(true)
    private val installations = AtomicInteger()
    private val selected = AtomicReference<Attempt?>()
    private val writer = ThreadLocal<Frame?>()
    @Volatile private var opened: SupportSQLiteDatabase? = null

    fun arm(metricId: Long, metricUuid: String, accountId: String) {
        val raw = requireNotNull(opened)
        check(installations.get() == 1 && !installNext.get() && !raw.inTransaction())
        require(metricId > 0 && isContractUuid(metricUuid) && isContractUuid(accountId))
        check(selected.compareAndSet(null, Attempt(metricId, metricUuid, accountId, raw, image(raw))))
    }
    fun requireTriggeredAndRolledBack(failure: Throwable) {
        val a = requireNotNull(selected.get())
        assertFalse(a.armed.get()); assertEquals("Exact original origin actor", 1, a.originActors.get())
        val original = requireNotNull(a.abort.get())
        assertTrue(original.message.orEmpty().contains(MESSAGE))
        assertTrue("The original SQLite ABORT is in the propagated cause chain",
            generateSequence(failure) { it.cause }.any { it === original })
        assertTrue("Metric UPDATE and its real outbox row preceded origin INSERT", a.originalEffects)
        assertEquals("The failed writer never marks success", 0, a.marks.get())
        assertEquals("Actual outer rollback END returned", 1, a.ends.get())
        assertNull("An END or observation failure is not the requested ABORT", a.endFailure.get())
        assertTrue("Every MAIN typed cell, schema, cookie and rowid restored", a.rollbackEqual)
        assertSame(a.native, opened); assertFalse(a.native.inTransaction())
    }
    fun requireWarmRetryCommitted() {
        val a = requireNotNull(selected.get())
        assertSame(a.native, opened); assertEquals(1, installations.get())
        assertTrue("The TEMP enabling row rolled back before the warm origin", a.gateRolledBack)
        assertEquals(1, a.retryActors.get()); assertEquals(1, a.retryReturned.get())
        assertTrue(a.retryMarks.get() > 0); assertEquals(1, a.retryEnds.get())
        assertNull(a.endFailure.get()); assertFalse(a.native.inTransaction())
    }
    private fun scalar(raw: SupportSQLiteDatabase, sql: String, args: Array<out Any?> = emptyArray()): Long =
        raw.query(sql, args).use { c -> check(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_INTEGER)
            c.getLong(0).also { check(!c.moveToNext()) } }
    private fun rows(raw: SupportSQLiteDatabase, query: String): Rows = raw.query(query).use { c ->
        val columns = c.columnNames.toList()
        Rows(columns, buildList { while (c.moveToNext()) add(columns.indices.map { i ->
            val type = c.getType(i)
            Cell(type, when (type) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i).toRawBits()
                Cursor.FIELD_TYPE_STRING -> c.getString(i)
                Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).toList()
                else -> error("Unknown SQLite cell type")
            })
        }) })
    }
    private fun image(raw: SupportSQLiteDatabase): MainImage {
        val schema = rows(raw, "SELECT type,name,tbl_name,rootpage,sql FROM main.sqlite_master ORDER BY type,name")
        val tables = raw.query("SELECT name FROM main.sqlite_master WHERE type='table' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) { check(c.getType(0) == Cursor.FIELD_TYPE_STRING); add(c.getString(0)) } }
        }
        return MainImage(raw.version, scalar(raw, "PRAGMA main.schema_version"), schema, tables.associateWith { table ->
            rows(raw, "SELECT rowid AS __editor_fault_rowid,* FROM main.\"${table.replace("\"", "\"\"")}\" ORDER BY rowid")
        })
    }
    private fun install(raw: SupportSQLiteDatabase) {
        check(!raw.inTransaction()); val before = image(raw)
        raw.execSQL("CREATE TEMP TABLE editor_origin_fault_gate(id INTEGER PRIMARY KEY,enabled INTEGER NOT NULL,requestId TEXT)")
        raw.execSQL("INSERT INTO temp.editor_origin_fault_gate VALUES(1,0,NULL)")
        raw.execSQL("CREATE TEMP TRIGGER editor_origin_fault_abort BEFORE INSERT ON main.next_request_origins " +
            "WHEN NEW.kind='$NEXT_OPERATION' AND (SELECT enabled FROM editor_origin_fault_gate WHERE id=1)=1 " +
            "AND NEW.requestId=(SELECT requestId FROM editor_origin_fault_gate WHERE id=1) " +
            "BEGIN SELECT RAISE(ABORT,'$MESSAGE'); END")
        check(before == image(raw)) { "Fixed TEMP origin fault changed MAIN" }
        check(installations.incrementAndGet() == 1)
    }
    private fun <T> executeOrigin(raw: SupportSQLiteDatabase, keys: Map<Int, Any?>, action: () -> T): T {
        val a = selected.get() ?: return action()
        check(raw === a.native && raw.inTransaction() && writer.get() == null)
        val requestId = keys[2] as? String ?: error("Original request ID missing")
        val queueId = keys[3] as? Long ?: error("Original queue ID missing")
        check(keys[1] == NEXT_OPERATION && isContractUuid(requestId) && queueId > 0 &&
            keys[4] == 5L && keys[5] == a.accountId)
        val retry = !a.armed.get()
        val name = if (retry) "Retry" else "Must rollback"
        check(scalar(raw, "SELECT count(*) FROM main.metrics WHERE id=? AND uuid=? AND name=?",
            arrayOf<Any?>(a.metricId, a.metricUuid, name)) == 1L)
        check(scalar(raw, "SELECT count(*) FROM main.sync_outbox WHERE id=? AND operationId=? " +
            "AND recordType='metric' AND entityUuid=? AND wireEntityUuid=? AND action='upsert'",
            arrayOf<Any?>(queueId, requestId, a.metricUuid, a.metricUuid)) == 1L)
        check(scalar(raw, "SELECT count(*) FROM main.next_request_origins WHERE kind=? AND requestId=?",
            arrayOf(NEXT_OPERATION, requestId)) == 0L)
        check(scalar(raw, "SELECT enabled FROM temp.editor_origin_fault_gate WHERE id=1") == 0L)
        if (retry) {
            check(a.ends.get() == 1 && a.rollbackEqual && a.abort.get() != null)
            check(a.retryActors.incrementAndGet() == 1); a.gateRolledBack = true
            writer.set(Frame(a, raw, true))
            return action().also { check(a.retryReturned.incrementAndGet() == 1) }
        }
        check(a.armed.compareAndSet(true, false)); a.originalEffects = true
        raw.execSQL("UPDATE temp.editor_origin_fault_gate SET enabled=1,requestId=? WHERE id=1", arrayOf(requestId))
        check(scalar(raw, "SELECT changes()") == 1L)
        check(a.originActors.incrementAndGet() == 1); writer.set(Frame(a, raw, false))
        try { action(); error("Expected original native origin ABORT") }
        catch (failure: SQLiteConstraintException) {
            check(failure.message.orEmpty().contains(MESSAGE))
            check(scalar(raw, "SELECT count(*) FROM main.next_request_origins WHERE kind=? AND requestId=?",
                arrayOf(NEXT_OPERATION, requestId)) == 0L)
            check(a.abort.compareAndSet(null, failure)); throw failure
        }
    }
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        check(configuration.context.packageName == "com.dayforge.testbed")
        var native: SupportSQLiteDatabase? = null; var adapted: SupportSQLiteDatabase? = null
        val monitor = Any()
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
            if (native !== raw) {
                native = raw; opened = raw
                adapted = object : SupportSQLiteDatabase by raw {
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val statement = raw.compileStatement(sql)
                        if (sql != ORIGIN) return statement
                        return object : SupportSQLiteStatement by statement {
                            private val keys = hashMapOf<Int, Any?>()
                            override fun clearBindings() { keys.clear(); statement.clearBindings() }
                            override fun bindNull(index: Int) { keys[index] = null; statement.bindNull(index) }
                            override fun bindLong(index: Int, value: Long) { keys[index] = value; statement.bindLong(index, value) }
                            override fun bindDouble(index: Int, value: Double) { keys[index] = value; statement.bindDouble(index, value) }
                            override fun bindString(index: Int, value: String) { keys[index] = value; statement.bindString(index, value) }
                            override fun bindBlob(index: Int, value: ByteArray) { keys[index] = value.copyOf(); statement.bindBlob(index, value) }
                            override fun execute() = executeOrigin(raw, keys.toMap()) { statement.execute() }
                            override fun executeInsert() = executeOrigin(raw, keys.toMap()) { statement.executeInsert() }
                            override fun executeUpdateDelete() = executeOrigin(raw, keys.toMap()) { statement.executeUpdateDelete() }
                        }
                    }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
                        if (sql != ORIGIN) raw.execSQL(sql, bindArgs)
                        else executeOrigin(raw, bindArgs.mapIndexed { index, value -> index + 1 to value }.toMap()) { raw.execSQL(sql, bindArgs) }
                    }
                    override fun setTransactionSuccessful() {
                        raw.setTransactionSuccessful()
                        writer.get()?.takeIf { it.raw === raw }?.let {
                            (if (it.retry) it.attempt.retryMarks else it.attempt.marks).incrementAndGet()
                        }
                    }
                    override fun endTransaction() {
                        val frame = writer.get()?.takeIf { it.raw === raw }
                        if (frame == null) { raw.endTransaction(); return }
                        var returned = false
                        try { raw.endTransaction(); returned = true }
                        catch (failure: Throwable) { frame.attempt.endFailure.compareAndSet(null, failure); throw failure }
                        finally {
                            try { if (!raw.inTransaction()) {
                                try {
                                    check(returned); val a = frame.attempt
                                    if (frame.retry) a.retryEnds.incrementAndGet()
                                    else { a.ends.incrementAndGet(); a.rollbackEqual = image(raw) == a.before }
                                } finally { writer.remove() }
                            } } catch (observation: Throwable) { frame.attempt.endFailure.compareAndSet(null, observation) }
                        }
                    }
                }
            }
            requireNotNull(adapted)
        }
        val parent = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(parent.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
            override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(wrap(db), oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onDowngrade(wrap(db), oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) { parent.onOpen(wrap(db)); if (installNext.compareAndSet(true, false)) install(db) }
            override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase get() = wrap(helper.writableDatabase)
            override val readableDatabase get() = wrap(helper.readableDatabase)
        }
    }
    private companion object {
        const val MESSAGE = "editor origin unavailable"
        const val ORIGIN = "INSERT OR ABORT INTO `next_request_origins` (`kind`,`requestId`,`queueId`,`protocol`,`accountId`,`serverInstanceId`,`syncEpoch`,`sourceHash`,`intentJson`) VALUES (?,?,?,?,?,?,?,?,?)"
    }
}
