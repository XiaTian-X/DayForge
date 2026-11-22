package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.businessDate
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.model.CheckInResult
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.domain.model.CountHistory
import com.dayforge.domain.service.AccountIconController
import com.dayforge.domain.service.CheckInService
import com.dayforge.util.DateTimeUtils
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production builder, native journal, real original producer and loopback HTTP. The independent
 * oracle always executes RecurringCompletionEvidenceReader, never the persistent count kernel. */
@RunWith(AndroidJUnit4::class)
class CountTrustKernelSafetyTest : NextCoreRequestFixture() {
    @get:Rule(order=-2) val widgets = com.dayforge.widget.IsolatedWidgetRefreshRule()
    private val completeProofReads = AtomicInteger()
    private val cancelIssuance = AtomicBoolean()
    private val cancellationReached = AtomicBoolean()
    private val cancelTarget = AtomicReference<Job?>()
    private val mutateQualificationCapture = AtomicBoolean()
    private val qualificationMutationReached = AtomicBoolean()
    private val qualificationMutationTarget = AtomicReference<String?>()
    private val qualificationMutationValue = AtomicInteger()
    private val probeObserverCapabilities = AtomicBoolean()
    private val observerCapabilityDenials = AtomicReference<Map<String, Throwable?>?>()
    private val icons = mutableListOf<AccountIconController>()
    private val callback = object : RoomDatabase.QueryCallback {
        override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
            val text = sqlQuery.trim().lowercase().replace(Regex("\\s+"), " ")
            if (text.startsWith("select value,habituuid,onetimeaction,recordedlocaldate,")) completeProofReads.incrementAndGet()
            if (text.startsWith("insert") && "into count_trust_nodes" in text && probeObserverCapabilities.compareAndSet(true, false)) {
                val probes: Map<String, () -> Unit> = linkedMapOf(
                    "control" to { sql.execSQL("UPDATE count_trust_control SET generation=0,dirty=0 WHERE id=1") },
                    "proof" to { sql.execSQL("DELETE FROM count_trust_root") },
                    "command" to { sql.execSQL("INSERT INTO count_command_results VALUES('observer','input','fact',NULL,'result','committed')") },
                    "reacquire" to { runBlocking { runtime.maintenance(db) { sql.execSQL("DELETE FROM count_trust_root") } } },
                    "retire" to { runtime.retireFastPathForRecovery() },
                    "begin" to { sql.beginTransaction() },
                    "success" to { sql.setTransactionSuccessful() },
                    "end" to { sql.endTransaction() },
                    "close" to { sql.close() }
                )
                observerCapabilityDenials.set(probes.mapValues { (_, probe) -> runCatching(probe).exceptionOrNull() })
            }
            if (text.startsWith("insert") && "into count_trust_nodes" in text && cancelIssuance.compareAndSet(true, false)) {
                cancellationReached.set(true)
                requireNotNull(cancelTarget.get()).cancel(CancellationException("COUNT_TRUST_TEST_CANCEL_ISSUANCE"))
            }
        }
    }
    init { storage.isolatedBuilder = { context -> buildDiagnosticFaultDatabase(context, QualificationFaultFactory(), callback) } }
    /** Fixed native fault below the controlled wrapper; never returns its raw handle. */
    private inner class QualificationFaultFactory : androidx.sqlite.db.SupportSQLiteOpenHelper.Factory {
        private var original: androidx.sqlite.db.SupportSQLiteDatabase? = null
        private var wrapped: androidx.sqlite.db.SupportSQLiteDatabase? = null
        private fun wrap(raw: androidx.sqlite.db.SupportSQLiteDatabase): androidx.sqlite.db.SupportSQLiteDatabase {
            if (original !== raw) {
                original = raw
                wrapped = object : androidx.sqlite.db.SupportSQLiteDatabase by raw {

                    private fun beforeQuery(text: String) {
                        val normalized = text.trim().lowercase().replace(Regex("\\s+"), " ")
                        if (normalized != "select * from `completions` where 1 order by `id`" ||
                            !mutateQualificationCapture.compareAndSet(true, false)) return
                        check(completeProofReads.get() > 0 && db.inTransaction() && raw.inTransaction())
                        val uuid = requireNotNull(qualificationMutationTarget.get())
                        raw.compileStatement("UPDATE completions SET value=value+1 WHERE uuid=?").use {
                            it.bindString(1, uuid); check(it.executeUpdateDelete() == 1)
                        }
                        raw.query("SELECT value FROM completions WHERE uuid=?", arrayOf(uuid)).use {
                            check(it.moveToFirst()); qualificationMutationValue.set(it.getInt(0))
                        }
                        qualificationMutationReached.set(true)
                    }
                    override fun query(query: String): android.database.Cursor {
                        beforeQuery(query); return raw.query(query)
                    }
                    override fun query(query: String, bindArgs: Array<out Any?>): android.database.Cursor {
                        beforeQuery(query); return raw.query(query, bindArgs)
                    }
                    override fun query(query: androidx.sqlite.db.SupportSQLiteQuery): android.database.Cursor {
                        beforeQuery(query.sql); return raw.query(query)
                    }
                    override fun query(query: androidx.sqlite.db.SupportSQLiteQuery,
                        cancellationSignal: android.os.CancellationSignal?): android.database.Cursor {
                        beforeQuery(query.sql); return raw.query(query, cancellationSignal)
                    }

                }
            }
            return requireNotNull(wrapped)
        }
        override fun create(configuration: androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration): androidx.sqlite.db.SupportSQLiteOpenHelper {
            val parent = configuration.callback
            val callbacks = object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(parent.version) {
                override fun onConfigure(db: androidx.sqlite.db.SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = parent.onCreate(wrap(db))
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    parent.onUpgrade(wrap(db), oldVersion, newVersion)
                override fun onDowngrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    parent.onDowngrade(wrap(db), oldVersion, newVersion)
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) = parent.onOpen(wrap(db))
                override fun onCorruption(db: androidx.sqlite.db.SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
            }
            val native = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                    .name(configuration.name).callback(callbacks).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : androidx.sqlite.db.SupportSQLiteOpenHelper by native {
                override val writableDatabase get() = wrap(native.writableDatabase)
                override val readableDatabase get() = wrap(native.readableDatabase)
            }
        }
    }
    private val sql get() = db.openHelper.writableDatabase
    private val runtime get() = requireNotNull(CountTrustRuntime.forDatabase(db))
    private fun reader() = CountHistoryReader(db, tokens, sessions)
    private fun root() = requireNotNull(CountTrustSeal.read(sql))
    private fun dirty() = sql.query("SELECT dirty FROM count_trust_control WHERE id=1").use {
        assertTrue(it.moveToFirst()); it.getLong(0)
    }
    private suspend fun current() = requireNotNull(db.habitDao().getHabitById(habit.id))
    private suspend fun qualify(date: LocalDate = DateTimeUtils.today()): CountHistory {
        assertNotNull(CountTrustRuntime.forDatabase(db))
        val result = reader().read(current(), date)
        assertFalse(runtime.isRetired)
        assertEquals("The real kernel must qualify, not silently pass through the old path: " + CountTrustKernel.lastQualificationFailure, 0L, dirty())
        assertEquals(1, count("count_trust_root"))
        assertEquals(result, oracle(date))
        return result
    }
    private suspend fun oracle(date: LocalDate = DateTimeUtils.today()): CountHistory {
        val habit = current()
        val original = RecurringCompletionEvidenceReader(db, tokens, sessions).read(habit, date)
        val quantities = original.completions.groupBy { it.businessDate }.mapValues { (_, rows) ->
            rows.fold(0L) { total, row -> Math.addExact(total, row.value.toLong()) }
        }
        return CountHistory(original.today, original.policies, quantities,
            original.policies[original.today] ?: if (original.today in original.unknownDates) null
                else CountDayPolicy(habit.targetValue, habit.isCountdown),
            original.unknownDates, original.completions, original.roundHead, original.countIdentity)
    }
    private suspend fun append(index: Int, date: LocalDate = DateTimeUtils.today(), value: Int = 1,
        afterWrite: suspend () -> Unit = {}): SyncOutboxEntity {
        val current = current()
        val zone = ZoneId.of("Asia/Shanghai")
        val stamp = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val fact = CompletionEntity(habitId=current.id, habitUuid=current.uuid, uuid=id(10_000 + index),
            value=value, date=date.atStartOfDay(zone).toInstant().toEpochMilli(), actualCompletedAt=stamp,
            recordedTimezone=zone.id, recordedLocalDate=date.toString(), createdAt=stamp)
        producer().write(local()) {
            NextCountDayStore(db).capture(current, fact); db.completionDao().insertForSync(fact); afterWrite()
        }
        return db.syncOutboxDao().getAll().single { it.entityUuid == fact.uuid }
    }
    private fun productionActions(): CheckInService {
        val icon = AccountIconController({ error("COUNT_TRUST_TEST_UNEXPECTED_ICON_ACCESS") }, tokens)
        icons += icon
        val editor = NextObjectEditor(db, tokens, sessions, icon)
        val counts = reader()
        val checks = CheckHistoryReader(db, tokens, sessions)
        val repository = HabitRepository(db.habitDao(), db.completionDao(), db.timeLogDao(), db,
            nextObjectEditor=editor, countHistoryReader=counts, checkHistoryReader=checks,
            recurringHabitReader=RecurringHabitReader(db,tokens,sessions,editor,counts,checks),
            widgetFactReader=WidgetFactReader(db,tokens,sessions,preferences,counts,checks),
            countActions=CountActionReader(db,tokens,sessions))
        return CheckInService(repository, db.completionDao(), db.timeLogDao())
    }
    @After fun closeIcons() { icons.forEach { it.close() } }
    private fun editClosed(block: (SQLiteDatabase) -> Unit) {
        db.close()
        SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
        storage.reopen().openHelper.writableDatabase
        assertNotNull(CountTrustRuntime.forDatabase(db))
    }
    private fun proofRows(): List<List<Any?>> = sql.query("SELECT * FROM count_trust_root").use { c ->
        buildList { while (c.moveToNext()) add((0 until c.columnCount).map {
            if (c.getType(it) == android.database.Cursor.FIELD_TYPE_BLOB) c.getBlob(it).toList() else c.getString(it)
        }) }
    }

    // Same typed MAIN-image witness as CountCommandParentReplayTest; private test-only
    // SELECTs also read the cold refused file. No native handle or production grant escapes.
    private data class SchemaFailureCell(val type: Int, val value: Any?)
    private data class SchemaFailureTable(val name: String, val columns: List<String>,
        val rows: List<List<SchemaFailureCell>>)
    private fun schemaFailureCells(cursor: Cursor): List<List<SchemaFailureCell>> = buildList {
        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column ->
            val type = cursor.getType(column)
            SchemaFailureCell(type, when (type) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column).toRawBits()
                Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList()
                else -> error("Unexpected native SQLite type")
            })
        })
    }
    private fun schemaFailureImage(query: (String) -> Cursor): List<SchemaFailureTable> {
        val schema = query("SELECT type,name,tbl_name,rootpage,sql FROM main.sqlite_master ORDER BY type,name").use {
            SchemaFailureTable("@schema", it.columnNames.toList(), schemaFailureCells(it))
        }
        val names = query("SELECT name FROM main.sqlite_master WHERE type='table' ORDER BY name").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return listOf(schema) + names.map { name ->
            val escaped = name.replace("`", "``")
            query("SELECT rowid,* FROM main.`$escaped` ORDER BY rowid").use {
                SchemaFailureTable(name, it.columnNames.toList(), schemaFailureCells(it))
            }
        }
    }

    @Test fun originalBootstrapAndIndependentCompleteValidatorAgreeThenWarmReadReusesRoot() = runBlocking<Unit> {
        register()
        append(1, DateTimeUtils.today().minusDays(45), 2)
        append(2, DateTimeUtils.today(), 3)
        assertEquals(1L, dirty())
        qualify()
        assertTrue(completeProofReads.get() > 0)
        val before = root()
        completeProofReads.set(0)
        val history = reader().read(current())
        val summary = reader().readSummary(current())
        assertEquals(history.summary, summary)
        assertEquals(0, completeProofReads.get())
        assertEquals(before, root()); assertEquals(0L, dirty())
        assertEquals(5L, summary.totalQuantity); assertEquals(3L, summary.todayQuantity)
        assertEquals(oracle(), history)
    }

    @Test fun realCountActionOutboxSendAckAndSharedSummaryAdvanceTheSameTrustedRoot() = runBlocking<Unit> {
        register(); qualify()
        val initial = root()
        val result = productionActions().incrementCount(app, habit.id)
        assertTrue(result is CheckInResult.Success)
        assertNotNull((result as CheckInResult.Success).followUp)
        assertEquals(0L, dirty()); val written = root(); assertTrue(written.revision > initial.revision)
        val request = db.syncOutboxDao().getAll().single()
        assertEquals("completion", request.recordType)
        assertNotNull(db.nextRequestDao().origin(NEXT_OPERATION, request.operationId))
        val (http, _) = channel { successReply(it) }
        val requests = sender(http)
        val delivery = requireNotNull(requests.sendOperation(access(), request.operationId))
        assertEquals(0L, dirty()); val sent = root(); assertTrue(sent.revision > written.revision)
        assertEquals(NextOperationAcceptance.COMMITTED, requests.acceptOperation(delivery))
        assertEquals(0L, dirty()); val accepted = root(); assertTrue(accepted.revision > sent.revision)
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, request.operationId))
        val beforeReplay = proofRows()
        assertEquals(NextOperationAcceptance.REPLAYED, requests.acceptOperation(delivery))
        assertEquals(beforeReplay, proofRows()); assertEquals(0L, dirty())
        completeProofReads.set(0)
        val history = reader().read(current()); val summary = reader().readSummary(current())
        assertEquals(history.summary, summary); assertEquals(1L, summary.todayQuantity)
        assertEquals(0, completeProofReads.get()); assertEquals(accepted, root())
        assertEquals(oracle(), history)
    }

    @Test fun unsupportedWriterLeavesDirtyAndOnlyOriginalRequalificationCanClearIt() = runBlocking<Unit> {
        register(); append(1); qualify()
        val initial = root()
        editMetric("unrelated metric definition")
        assertEquals(1L, dirty()); assertEquals(initial, root())
        completeProofReads.set(0)
        val read = reader().read(current())
        assertTrue(completeProofReads.get() > 0)
        assertEquals(0L, dirty()); assertTrue(root().revision > initial.revision)
        assertEquals(oracle(), read)
    }

    @Test fun closedReopenReusesAuthenticRootWithoutReplayingTheOriginalProof() = runBlocking<Unit> {
        register(); append(1, DateTimeUtils.today().minusDays(400)); append(2); qualify()
        val before = root(); val original = oracle()
        storage.reopen().openHelper.writableDatabase
        assertFalse(runtime.isRetired); completeProofReads.set(0)
        val summary = reader().readSummary(current()); val details = reader().read(current())
        assertEquals(0, completeProofReads.get()); assertEquals(before, root())
        assertEquals(0L, dirty()); assertEquals(original, details); assertEquals(original.summary, summary)
    }

    @Test fun coldForgedRootMacFailsBeforePublicationAndDoesNotRepairProof() = runBlocking<Unit> {
        register(); append(1); qualify()
        editClosed { it.execSQL("UPDATE count_trust_root SET mac=? WHERE id=1", arrayOf(ByteArray(32))) }
        val before = proofRows()
        var published = false
        val error = runCatching { reader().readSummary(current()); published = true }.exceptionOrNull()
        assertNotNull(error); assertFalse(published)
        assertEquals(before, proofRows()); assertEquals(0L, dirty())
    }

    @Test fun coldSelectedRootNodeCorruptionFailsInsteadOfTrustingItsPriorCalculation() = runBlocking<Unit> {
        register(); append(1); qualify()
        val digest = root().root.digest
        editClosed { raw ->
            val bytes = raw.rawQuery("SELECT body FROM count_trust_nodes WHERE digest=?", arrayOf(digest)).use {
                assertTrue(it.moveToFirst()); it.getBlob(0)
            }
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            raw.execSQL("UPDATE count_trust_nodes SET body=? WHERE digest=?", arrayOf(bytes, digest))
        }
        val before = proofRows()
        assertNotNull(runCatching { reader().readSummary(current()) }.exceptionOrNull())
        assertEquals(before, proofRows()); assertEquals(0L, dirty())
    }

    @Test fun coldTrackedSourceMutationCannotBeRequalifiedByAValidOldRoot() = runBlocking<Unit> {
        register(); val request = append(1); qualify()
        editClosed { it.execSQL("UPDATE next_request_origins SET sourceHash='broken' WHERE requestId=?", arrayOf(request.operationId)) }
        val before = proofRows(); assertEquals(1L, dirty())
        assertNotNull(runCatching { reader().read(current()) }.exceptionOrNull())
        assertEquals(before, proofRows()); assertEquals(1L, dirty())
        assertNotNull(runCatching { oracle() }.exceptionOrNull())
    }

    @Test fun unknownExtraTriggerRetiresFastPathAndCannotBeSignedAfterReopen() = runBlocking<Unit> {
        register(); qualify(); val before = root()
        val originalOwner = runtime
        val primaryBefore = CountCommandNativeRows(sql) {}.root()
        val triggerSql = "CREATE TRIGGER count_boundary_extra AFTER UPDATE ON metrics BEGIN UPDATE sync_control SET suppressOutbox=suppressOutbox WHERE id=1; END"
        sql.execSQL(triggerSql)
        assertTrue(runtime.isRetired); assertEquals(1L, dirty())
        val damaged = db.withTransaction { schemaFailureImage { sql.query(it) } }
        completeProofReads.set(0)
        var published = false
        val warmFailure = try { reader().read(current()); published = true; null }
            catch (error: CancellationException) { throw error }
            catch (error: IllegalArgumentException) { error }
            catch (error: IllegalStateException) { error }
        assertTrue(warmFailure is CountCommandSchemaBridge.Invalid)
        val expired = requireNotNull(warmFailure).cause
        assertTrue(expired is IllegalStateException)
        assertEquals("COMMAND_PRIMARY_READ_EXPIRED", expired?.message)
        assertTrue(requireNotNull(expired).stackTrace.any { frame ->
            frame.className == "com.dayforge.data.local.CountTrustOpenHelperFactory" &&
                frame.methodName.startsWith("capturePrimaryReadToken")
        })
        assertFalse(published); assertEquals(0, completeProofReads.get())
        assertSame(originalOwner, runtime); assertTrue(originalOwner.isRetired)
        assertEquals(before, root()); assertEquals(1L, dirty())
        assertTrue(CountCommandNativeRows.sameRoot(primaryBefore, CountCommandNativeRows(sql) {}.root()))
        assertEquals(damaged, db.withTransaction { schemaFailureImage { sql.query(it) } })

        // Room20 requires the persisted MAIN schema to match its primary root even when
        // the optional COUNT owner is unavailable. Reopen must not repair or re-sign it.
        storage.reopen()
        val coldFailure: Throwable? = try { db.openHelper.writableDatabase; null }
            catch (error: CancellationException) { throw error }
            catch (error: IllegalArgumentException) { error }
            catch (error: IllegalStateException) { error }
        assertTrue(generateSequence(coldFailure) { it.cause }.any { error ->
            error is IllegalArgumentException && error.message == "COMMAND_PRIMARY_BINDING_CHANGED" &&
                error.stackTrace.any { frame -> frame.className.startsWith(CountCommandSeal::class.java.name) && frame.methodName.substringBefore('$') == "verify" }
        })
        db.close()
        SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(20, raw.version)
            assertEquals(damaged, schemaFailureImage { raw.rawQuery(it, null) })
            raw.rawQuery("SELECT dirty FROM main.count_trust_control WHERE id=1", null).use {
                assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0)); assertFalse(it.moveToNext())
            }
            raw.rawQuery("SELECT sql FROM main.sqlite_master WHERE type='trigger' AND name='count_boundary_extra'", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(triggerSql, it.getString(0))
                assertFalse(it.moveToNext())
            }
        }
        assertTrue(originalOwner.isRetired)
    }

    @Test fun diagnosticQueryObserverCannotBorrowOrReacquireKernelMaintenanceAuthority() = runBlocking<Unit> {
        register(); append(1)
        val beforeFacts = db.completionDao().getByHabitOnce(habit.id)
        val beforeOrigins = count("next_request_origins")
        probeObserverCapabilities.set(true)
        qualify()
        val attempts = requireNotNull(observerCapabilityDenials.get()) { "Observer must run during proof issuance" }
        assertEquals(setOf("control", "proof", "command", "reacquire", "retire", "begin", "success", "end", "close"), attempts.keys)
        for ((entry, failure) in attempts) assertTrue("$entry must fail at the controlled boundary: $failure",
            failure is IllegalStateException && failure.message.orEmpty().startsWith("COUNT_TRUST_"))
        assertEquals(beforeFacts, db.completionDao().getByHabitOnce(habit.id))
        assertEquals(beforeOrigins, count("next_request_origins"))
        assertEquals(0, count("count_command_results")); assertEquals(0L, dirty()); assertEquals(1, count("count_trust_root"))
        assertFalse(runtime.isRetired)
        assertEquals(oracle(), reader().read(current()))
    }

    @Test fun recoveryCacheDiscardKeepsOriginalReadsAndWritesButCannotReissueUntilReopen() = runBlocking<Unit> {
        register(); append(1); val before = qualify()
        val original = runtime
        val epoch = original.generation
        val preparation = CountTrustRecoveryPreparation(db, tokens, sessions)
        assertTrue(preparation.discardCache(access()))
        assertTrue(original.isRetired); assertEquals(epoch, original.generation)
        assertEquals(1L, dirty())
        for (table in listOf("count_trust_root", "count_trust_nodes", "count_trust_routes")) assertEquals(table, 0, count(table))

        completeProofReads.set(0)
        assertEquals(before, reader().read(current()))
        assertTrue("A retired fast owner must use the unchanged original reader", completeProofReads.get() > 0)
        assertEquals(0, count("count_trust_root")); assertTrue(original.isRetired)
        append(2)
        assertEquals(2L, reader().read(current()).todayQuantity)
        assertEquals(oracle(), reader().read(current()))
        assertEquals(0, count("count_trust_root")); assertEquals(1L, dirty())
        assertFalse(preparation.discardCache(access()))
        assertTrue(original.isRetired); assertEquals(epoch, original.generation)

        storage.reopen().openHelper.writableDatabase
        assertTrue(original.isRetired); assertFalse(runtime.isRetired)
        assertEquals(2L, qualify().todayQuantity)
        assertEquals(1, count("count_trust_root")); assertEquals(0L, dirty())
    }

    @Test fun mutationBetweenOriginalValidationAndRawQualificationCaptureRollsBackWithoutIssuing() = runBlocking<Unit> {
        register(); val request = append(1)
        assertEquals(0, count("count_trust_root")); assertEquals(0, count("count_trust_nodes"))
        val beforeFacts = db.completionDao().getByHabitOnce(habit.id)
        val beforeQueue = db.syncOutboxDao().getAll()
        val beforeOrigin = originalIntent(request)
        val beforeProof = proofRows()
        fun control() = sql.query("SELECT storageId,generation,dirty FROM count_trust_control WHERE id=1").use {
            check(it.moveToFirst()); Triple(it.getString(0), it.getLong(1), it.getLong(2))
        }
        val beforeControl = control()
        completeProofReads.set(0)
        qualificationMutationTarget.set(request.entityUuid)
        mutateQualificationCapture.set(true)
        var published = false
        val failure = runCatching { reader().readSummary(current()); published = true }.exceptionOrNull()
        assertTrue("Injection must run after complete original evidence and before raw capture", qualificationMutationReached.get())
        assertEquals(beforeFacts.single().value + 1, qualificationMutationValue.get())
        assertNotNull(failure); assertFalse(published)
        assertEquals(beforeFacts, db.completionDao().getByHabitOnce(habit.id))
        assertEquals(beforeQueue, db.syncOutboxDao().getAll())
        assertEquals(beforeOrigin, originalIntent(request)); assertEquals(1, count("next_request_origins"))
        assertEquals(beforeControl, control()); assertEquals(beforeProof, proofRows())
        for (table in listOf("count_trust_nodes", "count_trust_routes", "count_command_results")) assertEquals(table, 0, count(table))
        assertEquals(0, count("count_trust_root"))
        assertEquals(beforeFacts, oracle().completions)
        qualify() // A rolled-back failed qualification does not make the database unusable.
    }

    @Test fun cancellationDuringFirstIssuanceNeverCommitsOrPublishesACertificate() = runBlocking<Unit> {
        register(); append(1)
        val published = AtomicBoolean()
        supervisorScope {
            val job = async(Dispatchers.IO, start=CoroutineStart.LAZY) {
                reader().readSummary(current()); published.set(true)
            }
            cancelTarget.set(job); cancelIssuance.set(true)
            val failure = runCatching { job.await() }.exceptionOrNull()
            assertNotNull(failure)
        }
        assertTrue("Fault must reach actual proof SQL", cancellationReached.get())
        assertFalse(published.get()); assertEquals(0, count("count_trust_root"))
        assertEquals(0, count("count_trust_nodes")); assertEquals(1L, dirty())
        cancelIssuance.set(false); cancelTarget.set(null)
        qualify()
    }

    @Test fun inFlightFrozenTransmissionSurvivesReopenAndAckIsIdempotent() = runBlocking<Unit> {
        register(); qualify(); val request = append(1)
        val (http, _) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), request.operationId))
        val frozen = transmission(NEXT_OPERATION, request.operationId).wireBytes.copyOf()
        val sent = root(); assertEquals(0L, dirty())
        storage.reopen().openHelper.writableDatabase
        assertArrayEquals(frozen, transmission(NEXT_OPERATION, request.operationId).wireBytes)
        assertEquals(sent, root()); assertEquals(0L, dirty())
        val reopened = sender(http)
        assertEquals(NextOperationAcceptance.COMMITTED, reopened.acceptOperation(delivery))
        assertEquals(0L, dirty()); val accepted = root(); assertTrue(accepted.revision > sent.revision)
        assertEquals(NextOperationAcceptance.REPLAYED, reopened.acceptOperation(delivery))
        assertEquals(accepted, root()); assertEquals(1, count("completions")); assertEquals(0, count("sync_outbox"))
        assertEquals(oracle(), reader().read(current()))
    }

    @Test fun unrelatedHistoricalDayRewriteInLocalCallbackNeverAdvancesTheRoot() = runBlocking<Unit> {
        register()
        val oldDate = DateTimeUtils.today().minusDays(45)
        append(1, oldDate); qualify(); val before = root()
        append(2) {
            sql.execSQL("UPDATE count_days SET targetValue=targetValue+1 WHERE habitId=? AND localDate=?",
                arrayOf<Any>(habit.id, oldDate.toString()))
        }
        assertEquals(1L, dirty()); assertEquals(before, root())
        assertEquals(2, count("completions"))
        assertNotNull(runCatching { reader().read(current()) }.exceptionOrNull())
        assertNotNull(runCatching { oracle() }.exceptionOrNull())
        assertEquals(before, root()); assertEquals(1L, dirty())
    }

    @Test fun duplicateQuantityOriginWithoutFactMutationCannotAdvanceTheRoot() = runBlocking<Unit> {
        register(); val originalRequest = append(1)
        val (http, _) = channel { successReply(it) }
        assertEquals(NextOperationAcceptance.COMMITTED,
            sender(http).sendAndAcceptOperation(access(), originalRequest.operationId))
        qualify(); val before = root()
        val facts = db.completionDao().getByHabitOnce(habit.id)
        // A genuine original producer creates a second valid envelope/source hash; the fact
        // itself is unchanged. Incremental validation must follow the newly added dependency.
        producer().write(local()) {
            db.syncOutboxDao().insert(SyncOutboxEntity(operationId=id(20_000), recordType="completion",
                entityUuid=originalRequest.entityUuid, wireEntityUuid=originalRequest.entityUuid,
                action="upsert", referenceUuid=habit.uuid))
        }
        assertEquals(facts, db.completionDao().getByHabitOnce(habit.id))
        assertEquals(2, count("next_request_origins")); assertEquals(1, count("sync_outbox"))
        assertEquals(before, root()); assertEquals(1L, dirty())
        assertNotNull(runCatching { reader().read(current()) }.exceptionOrNull())
        assertNotNull(runCatching { oracle() }.exceptionOrNull())
        assertEquals(before, root()); assertEquals(1L, dirty())
    }

    @Test fun extraAckShadowTriggerCannotAcquireIncrementalQualification() = runBlocking<Unit> {
        register(); qualify(); val request = append(1)
        val (http, _) = channel { successReply(it) }
        val core = sender(http)
        val delivery = requireNotNull(core.sendOperation(access(), request.operationId))
        val before = root()
        sql.execSQL("CREATE TRIGGER count_boundary_ack_extra AFTER INSERT ON next_acceptances BEGIN " +
            "INSERT INTO sync_entity_state(entityType,entityUuid,revision,deleted,payloadJson,payloadHash,updatedAt) " +
            "VALUES('activity_event','${id(19_999)}',1,0,'{}','broken',1); END")
        assertTrue(runtime.isRetired); assertEquals(1L, dirty())
        val failure = runCatching { core.acceptOperation(delivery) }.exceptionOrNull()
        assertNotNull("Original ACK checks must reject an extra invalid shadow", failure)
        assertEquals(before, root()); assertEquals(1L, dirty())
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, request.operationId))
        assertNotNull(db.syncOutboxDao().getById(request.id))
    }

    @Test fun rootNeverSubstitutesForNewAccountAuthentication() = runBlocking<Unit> {
        register(); append(1); qualify(); val original = root()
        tokens.saveLoginSession("other-session", "other-refresh", "member", id(99), false)
        assertNotNull(runCatching { reader().read(current()) }.exceptionOrNull())
        assertEquals(original, root())
        assertNotNull(runCatching { oracle() }.exceptionOrNull())
    }
}
