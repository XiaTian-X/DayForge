package com.dayforge.data.repository

import android.content.ContentValues
import android.database.Cursor
import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** The primitive closes boot SQL for one owned read scope; it does not certify business rows. */
@RunWith(AndroidJUnit4::class)
class G0ColdNativeReadBoundaryTest : G0SealedDomainFixture() {
    // Only this legacy primitive re-enters coldRead repeatedly on its setup database.
    // Keep Room's real TEMP invalidation work queued behind the owned outer transaction;
    // this does not grant another thread any cold SQL/transaction capability.
    private val roomExecutor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(256), { task -> Thread(task, "g0-cold-native-room") },
        ThreadPoolExecutor.AbortPolicy())
    init {
        storage.isolatedBuilder = { context ->
            Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
                .addCallback(domainOpeningCallback()).openHelperFactory(domainGate.factory)
                .setQueryExecutor(roomExecutor).setTransactionExecutor(roomExecutor).build()
        }
    }
    @After fun closeRoomBeforeRetiringItsExecutor() {
        var failure: Throwable? = null
        try { db.close() } catch (error: Throwable) { failure = error }
        roomExecutor.shutdown() // Drain queued work; never silently discard or swallow it.
        try {
            check(roomExecutor.awaitTermination(5, TimeUnit.SECONDS)) { "G0_COLD_ROOM_EXECUTOR_NOT_RETIRED" }
        } catch (cleanup: Throwable) {
            if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
        }
        failure?.let { throw it }
    }

    @Test fun realRoomInvalidationWaitsForColdSnapshotEndAndStillNotifies() = runBlocking<Unit> {
        // FIFO barriers await actual executor work, not elapsed time or best-effort sleeps.
        roomExecutor.submit {}.get(5, TimeUnit.SECONDS)
        val before = requireNotNull(db.metricDao().getMetricById(metric.id))
        val trace = G0PhaseTrace(driverTiming = false)
        val notified = CompletableDeferred<Pair<Set<String>, Boolean>>()
        val notifications = AtomicInteger()
        val observer = object : InvalidationTracker.Observer("metrics") {
            override fun onInvalidated(tables: Set<String>) {
                notifications.incrementAndGet()
                val ended = synchronized(trace.spans) {
                    trace.spans.any { it.phase == "tx_end_outer" && it.ok }
                }
                notified.complete(tables.toSet() to ended)
            }
        }
        db.invalidationTracker.addObserver(observer)
        try {
            roomExecutor.submit {}.get(5, TimeUnit.SECONDS)
            trace.run {
                db.withTransaction {
                    // Same business values: original outbox trigger remains quiet, but Room's
                    // real UPDATE tracking trigger must mark the TEMP invalidation row.
                    sql().execSQL("UPDATE metrics SET name=?,description=?,updatedAt=? WHERE id=?",
                        arrayOf<Any>(before.name, before.description, before.updatedAt, before.id))
                    sql().query("SELECT * FROM room_table_modification_log WHERE invalidated = 1").use {
                        assertTrue("actual Room trigger marked a table", it.moveToFirst())
                    }
                    domainGate.coldRead(db) {
                        assertEquals(0, notifications.get())
                        db.invalidationTracker.refreshAsync()
                        assertTrue("real Room refresh is queued behind this transaction", roomExecutor.queue.isNotEmpty())
                        assertFalse(notified.isCompleted)
                        assertEquals(before, db.metricDao().getMetricById(metric.id))
                    }
                }
            }
            val (tables, actualEndObserved) = withTimeout(5_000) { notified.await() }
            assertTrue("metrics" in tables)
            assertTrue("refresh observer ran only after successful actual outer END", actualEndObserved)
            roomExecutor.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(1, notifications.get())
            assertEquals(before, db.metricDao().getMetricById(metric.id))
            assertOriginal()
        } finally {
            db.invalidationTracker.removeObserver(observer)
        }
    }
    private fun sql(): SupportSQLiteDatabase = db.openHelper.writableDatabase
    private fun denied(body: () -> Unit): Throwable =
        requireNotNull(runCatching(body).exceptionOrNull()).also {
            assertTrue("Expected SQL denial, got $it", it is G0DomainSqlGate.Denied)
        }
    private suspend fun tainted(body: suspend () -> Unit): Throwable {
        var accepted = false
        val failure = rejected {
            db.withTransaction {
                domainGate.coldRead(db) {
                    body()
                    accepted = true
                }
                fail("A caught violation must not yield a qualified cold scope")
            }
        }
        assertTrue("The caller caught the inner failure before scope exit", accepted)
        return failure
    }
    private fun assertOriginal() {
        sql().query("SELECT * FROM metrics WHERE id = ? LIMIT 1", arrayOf(metric.id)).use {
            assertTrue(it.moveToFirst())
            assertEquals(metric.name, it.getString(it.getColumnIndexOrThrow("name")))
        }
        sql().query("SELECT count(*) FROM sync_outbox").use {
            assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0))
        }
    }

    @Test fun ownedRoomSnapshotAllowsOnlyNativeReadsAndDoesNotSealOrEndTheTransaction() = runBlocking<Unit> {
        val handle = sql()
        denied { domainGate.requireColdRead(db) }
        db.withTransaction {
            val count = domainGate.coldRead(db) {
                domainGate.requireColdRead(db)
                assertSame(handle, sql())
                assertEquals(metric.name, db.metricDao().getMetricById(metric.id)!!.name)
                handle.query("SELECT * FROM main.`metrics`").use { cursor ->
                    assertEquals(G0DomainSqlCatalog.columns.getValue("metrics"), cursor.columnNames.toList())
                    assertTrue(cursor.moveToFirst())
                    assertEquals(Cursor.FIELD_TYPE_INTEGER, cursor.getType(cursor.getColumnIndexOrThrow("id")))
                    assertEquals(Cursor.FIELD_TYPE_STRING, cursor.getType(cursor.getColumnIndexOrThrow("name")))
                    assertEquals(metric.name, cursor.getString(cursor.getColumnIndexOrThrow("name")))
                    cursor.count
                }
            }
            assertEquals(1, count)
            assertTrue(db.inTransaction())
            denied { domainGate.requireColdRead(db) }
            // The scope does not seize the trusted boot owner's remaining initialization.
            handle.execSQL("CREATE TEMP TABLE g0_cold_owner_after_scope(value INTEGER)")
            handle.execSQL("DROP TABLE g0_cold_owner_after_scope")
        }
        assertOriginal()
        sealDomain()
        domainGate.transaction(db, G0DomainSqlGate.Phase.READ) {
            assertEquals(metric.name, db.metricDao().getMetricById(metric.id)!!.name)
        }
    }

    @Test fun admissionNeedsExistingRoomTransactionAndCannotRunAfterSeal() = runBlocking<Unit> {
        sql()
        assertTrue(rejected { domainGate.coldRead(db) { fail("No outer transaction") } } is G0DomainSqlGate.Denied)
        db.withTransaction {
            val unbound = G0DomainSqlGate()
            assertTrue(rejected { unbound.coldRead(db) { fail("Different gate") } } is G0DomainSqlGate.Denied)
        }
        sealDomain()
        assertTrue(rejected {
            domainGate.transaction(db, G0DomainSqlGate.Phase.READ) {
                domainGate.coldRead(db) { fail("No reopening boot authority") }
            }
        } is G0DomainSqlGate.Denied)
    }

    @Test fun caughtNativeAndPreparedWritesAlwaysTaintAndNeverMutateTheBaseline() = runBlocking<Unit> {
        val handle = sql()
        val update = "UPDATE metrics SET name=?,description=?,updatedAt=? WHERE id=?"
        handle.compileStatement(update).use { prepared ->
            prepared.bindString(1, "Forbidden"); prepared.bindString(2, "")
            prepared.bindLong(3, millis); prepared.bindLong(4, metric.id)
            val operations: List<() -> Unit> = listOf(
                { handle.execSQL(update, arrayOf<Any>("Forbidden", "", millis, metric.id)) },
                { domainGate.sqlClient().execSQL(update, arrayOf<Any>("Forbidden", "", millis, metric.id)) },
                { prepared.executeUpdateDelete() },
                { handle.compileStatement(update).use { it.execute() } },
                { handle.query("DELETE FROM metrics").close() },
                { handle.execSQL("CREATE TEMP TABLE g0_cold_bypass(value INTEGER)") },
                { handle.execSQL("PRAGMA recursive_triggers=OFF") },
                { handle.update("metrics", 0, ContentValues().apply { put("name", "Forbidden") }, "id=?", arrayOf(metric.id)) },
                { handle.delete("metrics", "id=?", arrayOf(metric.id)) },
                { handle.insert("metrics", 0, ContentValues()) }
            )
            operations.forEach { operation ->
                assertTrue(tainted { denied(operation) } is G0DomainSqlGate.Denied)
                assertOriginal()
            }
        }
    }

    @Test fun transactionConfigurationLifecycleAndImplicitSqlGettersCannotBypassTheScope() = runBlocking<Unit> {
        val handle = sql()
        val operations: List<() -> Unit> = listOf(
            { handle.beginTransaction() },
            { handle.setTransactionSuccessful() },
            { handle.endTransaction() },
            { handle.yieldIfContendedSafely() },
            { handle.setForeignKeyConstraintsEnabled(false) },
            { handle.version = 999 },
            { handle.version },
            { handle.pageSize },
            { handle.maximumSize },
            { handle.attachedDbs },
            { handle.isDatabaseIntegrityOk },
            { handle.needUpgrade(999) },
            { handle.execPerConnectionSQL("PRAGMA recursive_triggers=OFF", null) },
            { handle.close() },
            { db.openHelper.close() },
            { domainGate.seal() }
        )
        operations.forEach { operation ->
            assertTrue(tainted { denied(operation) } is G0DomainSqlGate.Denied)
            assertOriginal()
        }
    }

    @Test fun leakedColdCursorAndStatementNeverBecomeBootCapabilities() = runBlocking<Unit> {
        lateinit var cursor: Cursor
        lateinit var prepared: SupportSQLiteStatement
        db.withTransaction {
            domainGate.coldRead(db) {
                cursor = sql().query("SELECT count(*) FROM sync_outbox")
                assertTrue(cursor.moveToFirst()); assertEquals(0L, cursor.getLong(0))
                prepared = sql().compileStatement("SELECT count(*) FROM sync_outbox")
                assertEquals(0L, prepared.simpleQueryForLong())
            }
            denied { cursor.getLong(0) }
            denied { cursor.position }
            denied { cursor.columnNames }
            denied { cursor.getColumnIndex("count(*)") }
            denied { cursor.extras }
            denied { prepared.simpleQueryForLong() }
        }
        cursor.close(); prepared.close()
        assertOriginal()
    }

    @Test fun preexistingCursorAndNestedScopeCannotSupplyAcceptedEvidence() = runBlocking<Unit> {
        val retained = sql().query("SELECT count(*) FROM sync_outbox")
        try {
            assertTrue(tainted { denied { retained.moveToFirst() } } is G0DomainSqlGate.Denied)
            assertTrue(tainted {
                assertTrue(rejected { domainGate.coldRead(db) { fail("Nested cold scope") } } is G0DomainSqlGate.Denied)
            } is G0DomainSqlGate.Denied)
        } finally { retained.close() }
        assertOriginal()
    }

    @Test fun otherThreadWithOrWithoutInheritedContextCannotReadOrWrite() = runBlocking<Unit> {
        val handle = sql()
        assertTrue(tainted {
            withContext(Dispatchers.Default) {
                denied { handle.query("SELECT count(*) FROM sync_outbox").close() }
            }
        } is G0DomainSqlGate.Denied)
        assertTrue(tainted {
            val failure = AtomicReference<Throwable?>()
            val thread = Thread {
                failure.set(runCatching { handle.execSQL("DELETE FROM metrics") }.exceptionOrNull())
            }
            thread.start(); thread.join(5_000)
            assertFalse(thread.isAlive)
            assertTrue(failure.get() is G0DomainSqlGate.Denied)
        } is G0DomainSqlGate.Denied)
        assertTrue(tainted {
            handle.query("SELECT count(*) FROM sync_outbox").use { cursor ->
                assertTrue(cursor.moveToFirst())
                withContext(Dispatchers.Default) { denied { cursor.getLong(0) } }
            }
        } is G0DomainSqlGate.Denied)
        assertOriginal()
    }

    @Test fun swallowedQueryCursorAndCaptureFailuresCannotProduceAQualifiedResult() = runBlocking<Unit> {
        val bindingFailure = IllegalStateException("G0_COLD_BINDING_FAILURE")
        val query = object : SupportSQLiteQuery {
            override val sql = "SELECT count(*) FROM sync_outbox"
            override val argCount = 0
            override fun bindTo(statement: SupportSQLiteProgram) { throw bindingFailure }
        }
        assertSame(bindingFailure, tainted {
            assertSame(bindingFailure, runCatching { sql().query(query) }.exceptionOrNull())
        })
        lateinit var cursorFailure: Throwable
        val observedCursorFailure = tainted {
            sql().query("SELECT count(*) FROM sync_outbox").use { cursor ->
                cursorFailure = requireNotNull(runCatching { cursor.getColumnIndexOrThrow("missing") }.exceptionOrNull())
            }
        }
        assertSame(cursorFailure, observedCursorFailure)
        val captureFailure = IllegalStateException("G0_COLD_CAPTURE_FAILURE")
        assertSame(captureFailure, tainted {
            assertSame(captureFailure, runCatching { domainGate.failColdRead(captureFailure) }.exceptionOrNull())
        })
        assertOriginal()
    }

    @Test fun exactReadTemplatesAndNativeBindingTypesRemainMandatory() = runBlocking<Unit> {
        val handle = sql()
        val absence = "SELECT 1 FROM next_abandoned_requests WHERE kind=? AND requestId=? LIMIT 1"
        val queue = "SELECT * FROM sync_outbox WHERE operationId = ?"
        val completeReads = listOf(
            "SELECT * FROM metrics ORDER BY createdAt DESC" to emptyArray<Any>(),
            "SELECT * FROM habits ORDER BY createdAt DESC" to emptyArray<Any>(),
            "SELECT * FROM completions ORDER BY recordedLocalDate DESC, COALESCE(actualCompletedAt, date) DESC, id DESC" to emptyArray<Any>(),
            "SELECT * FROM sync_outbox WHERE deadLetteredAt IS NULL ORDER BY id" to emptyArray<Any>(),
            "SELECT * FROM sync_outbox WHERE deadLetteredAt IS NOT NULL ORDER BY deadLetteredAt, id" to emptyArray<Any>(),
            "SELECT * FROM sync_entity_state WHERE entityType = ?" to arrayOf<Any>("activity_event")
        )
        val operations: List<() -> Unit> = listOf(
            { handle.query("SELECT * FROM metrics WHERE id = ? LIMIT 1", arrayOf(metric.id.toString())).close() },
            { handle.query("SELECT * FROM metrics WHERE id = ? LIMIT 1").close() },
            { handle.query("SELECT * FROM metrics WHERE id = ? LIMIT 2", arrayOf(metric.id)).close() },
            { handle.query("PRAGMA query_only").close() },
            { handle.query("SELECT * FROM count_days WHERE habitId=? ORDER BY localDate", arrayOf(habit.id)).close() },
            { handle.query(absence, arrayOf(NEXT_OPERATION, id(811))).close() },
            { handle.query(queue, arrayOf(id(811))).close() }
        )
        operations.forEach { assertTrue(tainted { denied(it) } is G0DomainSqlGate.Denied) }
        completeReads.forEach { (text, args) ->
            assertTrue(tainted { denied { handle.query(text,args).close() } } is G0DomainSqlGate.Denied)
        }
        freshGateForColdOpen(recurringReads = true)
        storage.reopen()
        db.withTransaction {
            domainGate.coldRead(db) {
                sql().query("SELECT * FROM count_days WHERE habitId=? ORDER BY localDate", arrayOf(habit.id)).use {
                    assertFalse(it.moveToFirst())
                }
                sql().query(absence, arrayOf(NEXT_OPERATION, id(811))).use { assertFalse(it.moveToFirst()) }
                sql().query(queue, arrayOf(id(811))).use { assertFalse(it.moveToFirst()) }
                completeReads.forEach { (text, args) -> sql().query(text,args).use { assertTrue(it.count>=0) } }
            }
        }
        assertTrue(tainted { denied { sql().query(queue, arrayOf(811L)).close() } } is G0DomainSqlGate.Denied)
        assertTrue(tainted { denied { sql().query("$queue LIMIT 1", arrayOf(id(811))).close() } } is G0DomainSqlGate.Denied)
        sealDomain()
        domainGate.transaction(db, G0DomainSqlGate.Phase.READ) {
            denied { sql().query(absence, arrayOf(NEXT_OPERATION, id(811))).close() }
            denied { sql().query(queue, arrayOf(id(811))).close() }
            completeReads.forEach { (text,args) -> denied { sql().query(text,args).close() } }
        }
    }

    @Test fun cancellationExpiresTheReadCapabilityAndLeavesNoSuccessfulScope() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        lateinit var cursor: Cursor
        var accepted = false
        val task = async {
            db.withTransaction {
                domainGate.coldRead(db) {
                    cursor = sql().query("SELECT count(*) FROM sync_outbox")
                    assertTrue(cursor.moveToFirst())
                    entered.complete(Unit)
                    release.await()
                }
            }
            accepted = true
        }
        withTimeout(5_000) { entered.await() }
        task.cancelAndJoin()
        assertFalse(accepted)
        denied { cursor.getLong(0) }
        cursor.close()
        assertOriginal()
    }
}
