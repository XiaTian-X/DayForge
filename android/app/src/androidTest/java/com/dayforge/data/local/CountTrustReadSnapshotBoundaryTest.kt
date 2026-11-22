package com.dayforge.data.local

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteTransactionListener
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.repository.NextReplicaStorageProof
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real controlled Room connections: a memo may reuse bytes only while no native mutation
 * can intervene. Rejections must reach this boundary, not be swallowed by QueryCallback.
 */
@RunWith(AndroidJUnit4::class)
class CountTrustReadSnapshotBoundaryTest {
    private val nativeFault = EndFaultFactory()
    private var observe: (() -> Unit)? = null
    @get:Rule val storage = PhysicalDatabaseRule().apply {
        isolatedBuilder = { context ->
            val factory = CountTrustOpenHelperFactory(context, nativeFault)
            Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
                .openHelperFactory(factory).addCallback(SyncSchemaCallback)
                .setQueryCallback(object : androidx.room.RoomDatabase.QueryCallback {
                    override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                        if (sqlQuery == "SELECT 73") factory.observeWithoutCapabilities { observe?.invoke() }
                    }
                }, java.util.concurrent.Executor { it.run() })
                .build().also { CountTrustRuntime.attach(it, factory) }
        }
    }
    private val db get() = storage.database
    private val sql get() = db.openHelper.writableDatabase
    private val runtime get() = requireNotNull(CountTrustRuntime.forDatabase(db))
    private fun scalar(text: String): Long = sql.query(text).use { assertTrue(it.moveToFirst()); it.getLong(0) }
    private fun denied(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("Expected no-write boundary rejection, got $failure",
            failure is IllegalStateException && failure.message.orEmpty().startsWith("COUNT_TRUST_"))
    }
    private suspend fun deniedSuspend(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected scoped boundary rejection, got $failure",
            failure is IllegalStateException && failure.message.orEmpty().startsWith("COUNT_TRUST_"))
    }

    @Test fun softRetiredOwnerReadsTheCompleteTypedSnapshotAndOrdinaryWritesResumeAfterScope() = runBlocking<Unit> {
        db.metricDao().insert(MetricEntity(name="typed", unit="u", iconResId=0, colorHex="#000000", targetValue=1.25))
        db.withTransaction {
            sql.execSQL("INSERT INTO next_config_imports VALUES('import','account','server','epoch','device','archive','identities','replacement','prepared',NULL)")
            sql.execSQL("INSERT INTO next_config_import_payloads VALUES('import','network',0,X'00017FFF')")
            val before = NextReplicaStorageProof(db).readInTransaction()
            runtime.maintenance(db) { runtime.retireFastPathForRecovery() }
            val epoch = runtime.generation
            assertTrue(runtime.isRetired)
            val actual = runtime.withReadSnapshot(db) {
                // Real suspend DAO adapters may change Job on the same Room transaction.
                assertEquals("typed", db.metricDao().getAllMetricsOnce().single().name)
                coroutineScope { assertEquals(17L, async { scalar("SELECT 17") }.await()) }
                NextReplicaStorageProof(db).readInTransaction()
            }
            assertEquals(before.hash, actual.hash); assertEquals(before.contents, actual.contents)
            assertEquals(epoch, runtime.generation); assertTrue(runtime.isRetired)
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        }
        assertEquals(1L, scalar("SELECT suppressOutbox FROM sync_control"))
        db.metricDao().insert(MetricEntity(name="after", unit="u", iconResId=0, colorHex="#000000"))
        assertEquals(2, db.metricDao().getAllMetricsOnce().size)
    }

    @Test fun everyWriteEntrypointIncludingPreviouslyCompiledSqlIsRejectedBeforeNativeExecution() = runBlocking<Unit> {
        val handle = sql
        val previous = handle.compileStatement("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        try { db.withTransaction {
            val before = NextReplicaStorageProof(db).readInTransaction()
            runtime.withReadSnapshot(db) {
                for (text in listOf(
                    "UPDATE sync_control SET suppressOutbox=1 WHERE id=1", "DELETE FROM metrics",
                    "INSERT INTO next_config_import_payloads VALUES('x','x',0,X'01')",
                    "WITH input(v) AS (SELECT 1) UPDATE sync_control SET suppressOutbox=1",
                    "CREATE TABLE snapshot_escape(value)", "CREATE TEMP TABLE snapshot_escape(value)",
                    "DROP INDEX index_metrics_uuid", "ANALYZE", "REINDEX index_metrics_uuid", "VACUUM",
                    "PRAGMA defer_foreign_keys=ON", "PRAGMA main.schema_version(1)", "PRAGMA wal_checkpoint(PASSIVE)",
                    "EXPLAIN PRAGMA writable_schema=ON", "EXPLAIN UPDATE sync_control SET suppressOutbox=1",
                    // Existing exact Room exceptions must not become a no-write escape.
                    "UPDATE room_table_modification_log SET invalidated = 0 WHERE invalidated = 1"
                )) {
                    denied { handle.execSQL(text) }; denied { handle.query(text).close() }
                    handle.compileStatement(text).use {
                        denied { it.execute() }; denied { it.executeInsert() }; denied { it.executeUpdateDelete() }
                        denied { it.simpleQueryForLong() }; denied { it.simpleQueryForString() }
                    }
                }
                denied { previous.executeUpdateDelete() }
                denied { handle.insert("metrics", 0, ContentValues().apply { put("name", "escape") }) }
                denied { handle.update("sync_control", 0, ContentValues().apply { put("suppressOutbox", 1) }, "id=1", null) }
                denied { handle.delete("metrics", null, null) }
                assertEquals(0L, scalar("SELECT suppressOutbox FROM sync_control"))
                assertEquals(before.hash, NextReplicaStorageProof(db).readInTransaction().hash)
            }
            previous.executeUpdateDelete() // Rejection cannot poison a valid old business statement.
        } } finally { previous.close() }
        assertEquals(1L, scalar("SELECT suppressOutbox FROM sync_control"))
    }

    @Test fun nativeControlsAndInternalMaintenanceCannotCrossOrNestTheReadBoundary() = runBlocking<Unit> {
        sql
        deniedSuspend { runtime.withReadSnapshot(db) { error("must not enter without an actual transaction") } }
        sql.beginTransactionReadOnly()
        try {
            deniedSuspend { runtime.withReadSnapshot(db) { error("must not borrow a read-only outer transaction") } }
        } finally { sql.endTransaction() }
        val listener = object : SQLiteTransactionListener {
            override fun onBegin() = Unit
            override fun onCommit() = Unit
            override fun onRollback() = Unit
        }
        db.withTransaction {
            runtime.maintenance(db) {
                deniedSuspend { runtime.withReadSnapshot(db) { error("must not inherit maintenance") } }
            }
            runtime.withReadSnapshot(db) {
                deniedSuspend { runtime.withReadSnapshot(db) { error("must not nest") } }
                deniedSuspend { runtime.maintenance(db) { error("must not mint write authority") } }
                deniedSuspend { runtime.compactAfterClear(db) }
                denied { runtime.retireFastPathForRecovery() }; denied { runtime.captureChangeBoundary() }
                denied { sql.beginTransaction() }; denied { sql.beginTransactionNonExclusive() }
                denied { sql.beginTransactionReadOnly() }
                denied { sql.beginTransactionWithListener(listener) }
                denied { sql.beginTransactionWithListenerNonExclusive(listener) }
                denied { sql.beginTransactionWithListenerReadOnly(listener) }
                denied { sql.setTransactionSuccessful() }; denied { sql.endTransaction() }
                denied { sql.version = 1 }; denied { sql.pageSize = 8192 }
                denied { sql.setForeignKeyConstraintsEnabled(false) }
                denied { sql.execPerConnectionSQL("PRAGMA recursive_triggers=OFF", null) }
                denied { sql.enableWriteAheadLogging() }; denied { sql.disableWriteAheadLogging() }
                denied { db.openHelper.setWriteAheadLoggingEnabled(false) }
                denied { sql.close() }; denied { db.openHelper.close() }
                assertTrue(db.inTransaction()); assertEquals(1L, scalar("SELECT 1"))
            }
            assertFalse(runtime.isRetired)
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        }
        assertEquals(1L, scalar("SELECT suppressOutbox FROM sync_control"))
    }

    @Test fun queryObserversBindersAndThreadsWithoutTheCoroutineElementCannotMutateTheSnapshot() = runBlocking<Unit> {
        val executor = Executors.newSingleThreadExecutor()
        val handle = sql
        val reached = AtomicBoolean()
        var callbackFailure: Throwable? = null
        observe = {
            if (reached.compareAndSet(false, true)) callbackFailure = runCatching {
                assertTrue(handle.inTransaction())
                denied { handle.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1") }
                denied { handle.endTransaction() }
                handle.compileStatement("DELETE FROM metrics").use { denied { it.executeUpdateDelete() } }
                runBlocking { deniedSuspend { runtime.withReadSnapshot(db) {} } }
            }.exceptionOrNull()
        }
        try { db.withTransaction {
            val before = NextReplicaStorageProof(db).readInTransaction().hash
            runtime.withReadSnapshot(db) {
                assertEquals(73L, scalar("SELECT 73"))
                assertTrue(reached.get()); assertNull(callbackFailure)
                val binder = object : SupportSQLiteQuery {
                    override val sql = "SELECT ?"
                    override val argCount = 1
                    override fun bindTo(statement: SupportSQLiteProgram) {
                        denied { handle.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1") }
                        statement.bindLong(1, 29)
                    }
                }
                handle.query(binder).use { assertTrue(it.moveToFirst()); assertEquals(29L, it.getLong(0)) }
                val foreign = executor.submit<Boolean> {
                    // A plain executor does not inherit ReadElement. Global blocking is required.
                    denied { handle.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1") }
                    denied { handle.update("sync_control", 0, ContentValues().apply { put("suppressOutbox", 1) }, null, null) }
                    handle.isOpen
                }
                assertTrue(foreign.get(5, TimeUnit.SECONDS))
                assertEquals(before, NextReplicaStorageProof(db).readInTransaction().hash)
            }
        } } finally { observe = null; executor.shutdownNow() }
    }

    @Test fun inheritedChildrenCannotWriteAndScopedCursorsOrStatementsExpireOnReturn() = runBlocking<Unit> {
        lateinit var cursor: Cursor
        lateinit var statement: SupportSQLiteStatement
        lateinit var write: SupportSQLiteStatement
        db.withTransaction {
            runtime.withReadSnapshot(db) {
                coroutineScope { async {
                    assertEquals(37L, scalar("SELECT 37"))
                    denied { sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1") }
                    deniedSuspend { runtime.maintenance(db) {} }
                }.await() }
                val cancelledWriteChecked = AtomicBoolean()
                val cancelledMaintenanceChecked = AtomicBoolean()
                coroutineScope {
                    val cancelled = async {
                        currentCoroutineContext()[Job]!!.cancel()
                        denied { sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1") }
                        cancelledWriteChecked.set(true)
                        deniedSuspend { runtime.maintenance(db) {} }
                        cancelledMaintenanceChecked.set(true)
                    }
                    assertTrue(runCatching { cancelled.await() }.exceptionOrNull() is CancellationException)
                }
                assertTrue(cancelledWriteChecked.get()); assertTrue(cancelledMaintenanceChecked.get())
                assertEquals(0L, scalar("SELECT suppressOutbox FROM sync_control"))
                withContext(Dispatchers.Default) {
                    denied { sql.query("SELECT 1").close() } // No actual original TX on this thread.
                    denied { sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1") }
                }
                cursor = sql.query("SELECT 41").also { assertTrue(it.moveToFirst()) }
                statement = sql.compileStatement("SELECT 43")
                write = sql.compileStatement("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                assertEquals(43L, statement.simpleQueryForLong())
            }
            denied { cursor.getLong(0) }; denied { statement.simpleQueryForLong() }; denied { write.executeUpdateDelete() }
            runtime.withReadSnapshot(db) {
                denied { cursor.moveToFirst() }; denied { statement.simpleQueryForLong() }; denied { write.executeUpdateDelete() }
            }
        }
        cursor.close(); statement.close(); write.close()
        assertEquals(0L, scalar("SELECT suppressOutbox FROM sync_control"))
    }

    @Test fun failureCancellationAndUnknownActualEndNeverPublishOrLeaveTheReadRestrictionInstalled() = runBlocking<Unit> {
        sql
        val failure = runCatching { db.withTransaction {
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            runtime.withReadSnapshot(db) { assertEquals(1L, scalar("SELECT suppressOutbox FROM sync_control")); error("read failed") }
        } }.exceptionOrNull()
        assertEquals("read failed", failure?.message); assertEquals(0L, scalar("SELECT suppressOutbox FROM sync_control"))
        val returned = AtomicBoolean()
        val cancelled = async {
            db.withTransaction {
                sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                runtime.withReadSnapshot(db) {
                    assertEquals(1L, scalar("SELECT suppressOutbox FROM sync_control"))
                    currentCoroutineContext()[Job]!!.cancel()
                }
                returned.set(true)
            }
        }
        assertTrue(runCatching { cancelled.await() }.exceptionOrNull() is CancellationException)
        assertFalse(returned.get()); assertEquals(0L, scalar("SELECT suppressOutbox FROM sync_control"))
        val unknown = runCatching { runtime.transaction(db) {
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            runtime.withReadSnapshot(db) { assertEquals(1L, scalar("SELECT suppressOutbox FROM sync_control")) }
            nativeFault.afterEnd.set(true)
            Any()
        }.also { returned.set(true) } }.exceptionOrNull()
        assertEquals("COUNT_READ_TEST_UNKNOWN_END", unknown?.message); assertFalse(returned.get())
        assertTrue(runtime.isRetired); assertEquals(1L, scalar("SELECT suppressOutbox FROM sync_control"))
        db.withTransaction {
            runtime.withReadSnapshot(db) { assertEquals(1L, scalar("SELECT suppressOutbox FROM sync_control")) }
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        assertEquals(0L, scalar("SELECT suppressOutbox FROM sync_control"))
    }

    @Test fun asynchronousRoomInvalidationRemainsObservableAfterReadScopeAndNextCommit() = runBlocking<Unit> {
        sql
        repeat(2) {
            val ready = CompletableDeferred<Unit>()
            val begin = EndFaultFactory.BeginWatch()
            val observed = async(Dispatchers.IO) {
                withTimeout(10_000) { db.invalidationTracker.createFlow("sync_control")
                    .onEach { ready.complete(Unit) }.drop(1).first() }
            }
            withTimeout(10_000) { ready.await() }
            try { db.withTransaction {
                sql.execSQL("UPDATE sync_control SET suppressOutbox=suppressOutbox WHERE id=1")
                runtime.withReadSnapshot(db) {
                    nativeFault.beginWatch = begin
                    db.invalidationTracker.refreshAsync()
                    withTimeout(10_000) { begin.entered.await() }
                    assertFalse("Native foreign BEGIN must still wait for the original outer END", begin.returned.get())
                    assertEquals(0L, scalar("SELECT suppressOutbox FROM sync_control"))
                }
                assertFalse(begin.returned.get())
            }
                assertEquals(setOf("sync_control"), observed.await())
                assertTrue("The original Room refresh must actually finish its native BEGIN", begin.returned.get())
            } finally { nativeFault.beginWatch = null }
        }
    }

    private class EndFaultFactory : SupportSQLiteOpenHelper.Factory {
        val afterEnd = AtomicBoolean()
        class BeginWatch {
            val entered = CompletableDeferred<Unit>()
            val returned = AtomicBoolean()
        }
        @Volatile var beginWatch: BeginWatch? = null
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            var original: SupportSQLiteDatabase? = null
            var adapted: SupportSQLiteDatabase? = null
            fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase {
                if (original !== raw) {
                    original = raw
                    adapted = object : SupportSQLiteDatabase by raw {
                        private fun begin(body: () -> Unit) {
                            val watch = beginWatch?.takeIf { !raw.inTransaction() }
                            watch?.entered?.complete(Unit)
                            body()
                            watch?.returned?.set(true)
                        }
                        override fun beginTransaction() = begin { raw.beginTransaction() }
                        override fun beginTransactionNonExclusive() = begin { raw.beginTransactionNonExclusive() }
                        override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) =
                            begin { raw.beginTransactionWithListener(transactionListener) }
                        override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) =
                            begin { raw.beginTransactionWithListenerNonExclusive(transactionListener) }
                        override fun endTransaction() {
                            raw.endTransaction()
                            if (!raw.inTransaction() && afterEnd.compareAndSet(true, false)) error("COUNT_READ_TEST_UNKNOWN_END")
                        }
                    }
                }
                return requireNotNull(adapted)
            }
            val parent = configuration.callback
            val callback = object : SupportSQLiteOpenHelper.Callback(parent.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
                override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(wrap(db), oldVersion, newVersion)
                override fun onOpen(db: SupportSQLiteDatabase) = parent.onOpen(wrap(db))
            }
            val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : SupportSQLiteOpenHelper by helper {
                override val writableDatabase get() = wrap(helper.writableDatabase)
                override val readableDatabase get() = wrap(helper.readableDatabase)
            }
        }
    }
}
