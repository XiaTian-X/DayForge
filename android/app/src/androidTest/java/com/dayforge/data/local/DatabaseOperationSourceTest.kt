package com.dayforge.data.local

import android.database.sqlite.SQLiteTransactionListener
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.repository.NextReplicaStorageProof
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Real native BEGIN/listener/END and main control identity. This component fixture does not
 * supply business authority or stand in for the separate whole-Hilt/Host publication gates.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseOperationSourceTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val fault = NativeFault()
    @get:Rule(order = 0) val timeout = Timeout.seconds(150)
    @get:Rule(order = 1) val threads = object : ExternalResource() {
        override fun after() { dispatcher.close(); executor.shutdown(); check(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }
    @get:Rule(order = 2) val storage = PhysicalDatabaseRule().apply {
        isolatedBuilder = { context ->
            val owner = CountTrustOpenHelperFactory(context, fault)
            Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
                .openHelperFactory(owner).addCallback(SyncSchemaCallback).build()
                .also { CountTrustRuntime.attach(it, owner) }
        }
    }
    @get:Rule(order = 3) val nativeCleanup = object : ExternalResource() {
        override fun after() { executor.submit { fault.cleanup() }.get(5, TimeUnit.SECONDS) }
    }
    private val db get() = storage.database
    private val sql get() = db.openHelper.writableDatabase
    private val owner get() = requireNotNull(CountTrustRuntime.forDatabase(db))
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction() }
    private fun scalar(text: String): Long = sql.query(text).use { check(it.moveToFirst()); it.getLong(0) }
    private fun capture(): Any {
        sql.beginTransactionNonExclusive()
        return try { owner.captureGraphSource(db).also { sql.setTransactionSuccessful() } }
        finally { sql.endTransaction() }
    }
    private suspend fun rotate() {
        sql.beginTransactionNonExclusive()
        try { owner.maintenance(db) { CountTrustSchema.resetLocalInTransaction(sql) }; sql.setTransactionSuccessful() }
        finally { sql.endTransaction() }
    }
    private fun listener(begin: () -> Unit = {}, commit: () -> Unit = {}, rollback: () -> Unit = {}) =
        object : SQLiteTransactionListener {
            override fun onBegin() = begin()
            override fun onCommit() = commit()
            override fun onRollback() = rollback()
        }
    private fun begin(mode: Int, observer: SQLiteTransactionListener) {
        when (mode) {
            0 -> sql.beginTransaction()
            1 -> sql.beginTransactionNonExclusive()
            2 -> sql.beginTransactionReadOnly()
            3 -> sql.beginTransactionWithListener(observer)
            4 -> sql.beginTransactionWithListenerNonExclusive(observer)
            5 -> sql.beginTransactionWithListenerReadOnly(observer)
            else -> error("mode")
        }
    }

    @Test fun allSixRealBeginModesKeepOriginalIdentityAndActualEnd() = runBlocking<Unit> { withContext(dispatcher) {
        val identity = capture()
        for (mode in 0..5) {
            var began = 0; var committed = 0
            DatabaseOperationSource.run(identity, { true }) {
                begin(mode, listener(begin = { began++ }, commit = { committed++ }))
                try { assertEquals(1L, scalar("SELECT id FROM sync_control")); sql.setTransactionSuccessful() }
                finally { sql.endTransaction() }
            }
            assertEquals(if (mode < 3) 0 else 1, began)
            assertEquals(if (mode < 3) 0 else 1, committed)
            assertFalse(sql.inTransaction()); assertFalse(owner.isRetired)
        }
    } }

    @Test fun allSixMismatchedBeginsRollbackBeforeOriginalListenerAndLeaveFullMainUnchanged() = runBlocking<Unit> { withContext(dispatcher) {
        val old = capture(); rotate(); val before = image()
        for (mode in 0..5) {
            var callbacks = 0
            val error = runCatching { DatabaseOperationSource.run(old, { true }) {
                begin(mode, listener(begin = { callbacks++; sql.execSQL("UPDATE sync_control SET suppressOutbox=1") },
                    rollback = { callbacks++ }, commit = { callbacks++ }))
            } }.exceptionOrNull()
            assertEquals("DATABASE_EPOCH_REPLICA_CHANGED", error?.message)
            assertEquals(0, callbacks); assertFalse(sql.inTransaction()); assertFalse(owner.isRetired)
            val after = image(); assertEquals(before.contents, after.contents); assertEquals(before.hash, after.hash)
        }
    } }

    @Test fun originalOnBeginFailureRollsBackItsActualWriteWithoutCallingUserRollback() = runBlocking<Unit> { withContext(dispatcher) {
        val identity = capture(); val before = image(); val expected = IllegalStateException("SOURCE_TEST_ORIGINAL_BEGIN")
        var rollback = 0; var commit = 0; var writes = 0
        val error = runCatching { DatabaseOperationSource.run(identity, { true }) {
            sql.beginTransactionWithListenerNonExclusive(listener(begin = {
                sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1"); writes++; throw expected
            }, rollback = { rollback++ }, commit = { commit++ }))
        } }.exceptionOrNull()
        assertSame(expected, error); assertEquals(1, writes); assertEquals(0, rollback); assertEquals(0, commit)
        assertFalse(owner.isRetired); assertFalse(sql.inTransaction()); assertEquals(before.contents, image().contents)
    } }

    @Test fun nestedListenerFailureRetainsOriginalCatchThenCommitContract() = runBlocking<Unit> { withContext(dispatcher) {
        val identity = capture(); val expected = IllegalStateException("SOURCE_TEST_NESTED_BEGIN")
        var rollback = 0
        DatabaseOperationSource.run(identity, { true }) {
            sql.beginTransactionNonExclusive()
            try {
                val error = runCatching { sql.beginTransactionWithListenerNonExclusive(listener(begin = {
                    sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1"); throw expected
                }, rollback = { rollback++ })) }.exceptionOrNull()
                assertSame(expected, error); assertEquals(0, rollback)
                assertTrue(sql.inTransaction()); sql.setTransactionSuccessful()
            } finally { sql.endTransaction() }
        }
        assertEquals(1L, scalar("SELECT suppressOutbox FROM sync_control")); assertFalse(owner.isRetired)
    } }

    @Test fun sourceEnteringExistingOuterAfterResetRejectsBeforeItsCallbackAndOuterCanRollback() = runBlocking<Unit> { withContext(dispatcher) {
        val identity = capture(); val before = image(); var reached = false
        sql.beginTransactionNonExclusive()
        try {
            owner.maintenance(db) { CountTrustSchema.resetLocalInTransaction(sql) }
            val error = runCatching { DatabaseOperationSource.run(identity, { true }) {
                sql.beginTransactionWithListenerNonExclusive(listener(begin = { reached = true }))
            } }.exceptionOrNull()
            assertEquals("DATABASE_EPOCH_REPLICA_CHANGED", error?.message); assertFalse(reached)
        } finally { sql.endTransaction() }
        assertEquals(before.contents, image().contents); assertFalse(owner.isRetired)
    } }

    @Test fun triggerSideEffectFromOrdinaryBusinessSqlCannotCommitChangedStorage() = runBlocking<Unit> { withContext(dispatcher) {
        sql.query("SELECT 1").close()
        fault.nativeSql("CREATE TRIGGER source_storage_fault AFTER UPDATE ON sync_control BEGIN " +
            "UPDATE count_trust_control SET storageId='aa310000-0000-4000-8000-000000009999' WHERE id=1; END")
        val identity = capture(); val before = image(); var statementReached = false
        val error = runCatching { DatabaseOperationSource.run(identity, { true }) {
            sql.beginTransactionNonExclusive()
            try {
                sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1"); statementReached = true
                sql.setTransactionSuccessful()
            } finally { sql.endTransaction() }
        } }.exceptionOrNull()
        assertTrue(statementReached); assertEquals("DATABASE_EPOCH_REPLICA_CHANGED", error?.message)
        assertEquals(before.contents, image().contents); assertFalse(owner.isRetired)
    } }

    @Test fun userOnCommitSideEffectCannotBypassFinalSourceCheck() = runBlocking<Unit> { withContext(dispatcher) {
        sql.query("SELECT 1").close()
        fault.nativeSql("CREATE TRIGGER source_commit_fault AFTER UPDATE ON sync_control BEGIN " +
            "UPDATE count_trust_control SET storageId='aa310000-0000-4000-8000-000000009998' WHERE id=1; END")
        val identity = capture(); val before = image(); var actualCommitCallback = 0
        val error = runCatching { DatabaseOperationSource.run(identity, { true }) {
            sql.beginTransactionWithListenerNonExclusive(listener(commit = {
                actualCommitCallback++; sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            }))
            sql.setTransactionSuccessful(); sql.endTransaction()
        } }.exceptionOrNull()
        assertEquals(1, actualCommitCallback); assertEquals("DATABASE_EPOCH_REPLICA_CHANGED", error?.message)
        assertTrue(owner.isRetired); assertTrue(CountTrustRetirementReason.UNKNOWN_END in owner.retirementReasons)
        assertEquals(before.contents, image().contents)
    } }

    @Test fun rollbackOutcomeLossNeverBecomesOriginalOrKnownCommit() = runBlocking<Unit> { withContext(dispatcher) {
        val identity = capture(); rotate(); val before = image(); var listenerReached = false
        fault.armEndLoss()
        val error = runCatching { DatabaseOperationSource.run(identity, { true }) {
            sql.beginTransactionWithListenerNonExclusive(listener(begin = { listenerReached = true }))
        } }.exceptionOrNull()
        assertEquals("DATABASE_EPOCH_REPLICA_CHANGED", error?.message)
        assertEquals(listOf("SOURCE_TEST_END_LOST"), error?.suppressed?.map { it.message })
        assertFalse(listenerReached); assertTrue(owner.isRetired)
        assertTrue(CountTrustRetirementReason.UNKNOWN_END in owner.retirementReasons)
        assertEquals(CountTrustOpenHelperFactory.GraphDrain.Unknown, owner.captureGraphDrain(db))
        assertEquals(before.contents, image().contents)
    } }

    @Test fun cascadedBusinessDeleteAndItsTriggerCannotChangeSourceStorage() = runBlocking<Unit> { withContext(dispatcher) {
        sql.query("SELECT 1").close()
        fault.nativeSql("CREATE TABLE source_cascade_child(id INTEGER PRIMARY KEY,parent INTEGER REFERENCES sync_control(id) ON DELETE CASCADE)")
        fault.nativeSql("INSERT INTO source_cascade_child VALUES(1,1)")
        fault.nativeSql("CREATE TRIGGER source_cascade_fault AFTER DELETE ON source_cascade_child BEGIN " +
            "UPDATE count_trust_control SET storageId='aa310000-0000-4000-8000-000000009997' WHERE id=1; END")
        val identity = capture(); val before = image(); var actualDelete = false
        val error = runCatching { DatabaseOperationSource.run(identity, { true }) {
            sql.beginTransactionNonExclusive()
            try {
                assertEquals(1L, scalar("PRAGMA foreign_keys")) // Observe the actual DELETE writer connection; never set it.
                sql.execSQL("DELETE FROM sync_control WHERE id=1"); actualDelete = true
                assertEquals(0L, scalar("SELECT COUNT(*) FROM source_cascade_child")); sql.setTransactionSuccessful()
            } finally { sql.endTransaction() }
        } }.exceptionOrNull()
        assertTrue(actualDelete); assertEquals("DATABASE_EPOCH_REPLICA_CHANGED", error?.message)
        assertEquals(before.contents, image().contents); assertFalse(owner.isRetired)
    } }

    @Test fun writeAfterSetSuccessfulStillRollsBackBeforePhysicalCommit() = runBlocking<Unit> { withContext(dispatcher) {
        sql.query("SELECT 1").close()
        fault.nativeSql("CREATE TRIGGER source_post_success_fault AFTER UPDATE ON sync_control BEGIN " +
            "UPDATE count_trust_control SET storageId='aa310000-0000-4000-8000-000000009996' WHERE id=1; END")
        val identity = capture(); val before = image(); var reached = false
        val error = runCatching { DatabaseOperationSource.run(identity, { true }) {
            sql.beginTransactionNonExclusive(); sql.setTransactionSuccessful()
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1"); reached = true
            sql.endTransaction()
        } }.exceptionOrNull()
        assertTrue(reached); assertEquals("DATABASE_EPOCH_REPLICA_CHANGED", error?.message)
        assertTrue(owner.isRetired); assertEquals(before.contents, image().contents)
    } }

    @Test fun cancellationThrownInsideBeginIsPreservedAfterActualRollback() = runBlocking<Unit> { withContext(dispatcher) {
        val identity = capture(); val before = image()
        val expected = kotlinx.coroutines.CancellationException("SOURCE_TEST_CANCELLED")
        var rollback = 0
        val error = runCatching { DatabaseOperationSource.run(identity, { true }) {
            sql.beginTransactionWithListenerNonExclusive(listener(begin = {
                sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1"); throw expected
            }, rollback = { rollback++ }))
        } }.exceptionOrNull()
        assertSame(expected, error); assertEquals(0, rollback); assertFalse(owner.isRetired)
        assertFalse(sql.inTransaction()); assertEquals(before.contents, image().contents)
    } }

    @Test fun delegateFailureAfterRealBeginNeverInfersEndFromALaterThread() = runBlocking<Unit> { withContext(dispatcher) {
        val identity = capture(); rotate(); val before = image()
        fault.armBeginLoss()
        val error = runCatching { DatabaseOperationSource.run(identity, { true }) {
            sql.beginTransactionNonExclusive()
        } }.exceptionOrNull()
        assertEquals("SOURCE_TEST_BEGIN_LOST", error?.message)
        assertEquals(listOf("DATABASE_EPOCH_REPLICA_CHANGED"), error?.suppressed?.map { it.message })
        assertTrue(owner.isRetired); assertTrue(CountTrustRetirementReason.UNKNOWN_BEGIN in owner.retirementReasons)
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            assertFalse(sql.inTransaction()) // Logical state on a different thread proves nothing about the original BEGIN.
            assertEquals(CountTrustOpenHelperFactory.GraphDrain.Unknown, owner.captureGraphDrain(db))
        }
        fault.cleanup() // Original-thread test teardown only; does not certify the failed operation.
        assertEquals(CountTrustOpenHelperFactory.GraphDrain.Unknown, owner.captureGraphDrain(db))
        assertEquals(before.contents, image().contents)
    } }

    @Test fun oversizedControlValuesStayBehindScalarPreflightAndDoNotBlockUnsourcedRollback() = runBlocking<Unit> { withContext(dispatcher) {
        val identity = capture(); val before = image()
        val original = sql.query("SELECT storageId,generation,dirty FROM count_trust_control").use { c ->
            check(c.moveToFirst()); listOf(c.getString(0), c.getLong(1), c.getLong(2))
        }
        for ((column, value) in listOf("storageId" to "zeroblob(3000000)",
            "storageId" to "hex(zeroblob(1500000))", "generation" to "zeroblob(3000000)", "dirty" to "zeroblob(3000000)")) {
            try {
                fault.nativeSql("UPDATE count_trust_control SET $column=$value WHERE id=1")
                // Invalid is observation only for ordinary cleanup. It must not expose the BLOB
                // in a CursorWindow, synthesize a valid source, or require a new health gate.
                sql.beginTransactionNonExclusive()
                try { assertEquals(1L, scalar("SELECT 1")) } finally { sql.endTransaction() }
                val error = runCatching { DatabaseOperationSource.run(identity, { true }) { sql.beginTransaction() } }.exceptionOrNull()
                assertEquals("DATABASE_EPOCH_REPLICA_CHANGED", error?.message)
                assertFalse(owner.isRetired); assertFalse(sql.inTransaction())
                // The original explicit maintenance repair is still available in an unsourced
                // actual transaction; new observation is not a global valid-control prerequisite.
                sql.beginTransactionNonExclusive()
                try {
                    owner.maintenance(db) {
                        sql.execSQL("UPDATE count_trust_control SET storageId=?,generation=?,dirty=? WHERE id=1", original.toTypedArray())
                    }
                    sql.setTransactionSuccessful()
                } finally { sql.endTransaction() }
                assertEquals(before.contents, image().contents)
            } finally {
                fault.nativeSql("UPDATE count_trust_control SET storageId=?,generation=?,dirty=? WHERE id=1", original.toTypedArray())
            }
        }
        assertEquals(before.contents, image().contents)
    } }

    @Test fun oversizedCatalogSqlIsNeverMaterializedBeforeOriginalSchemaBound() = runBlocking<Unit> { withContext(dispatcher) {
        val identity = capture(); val before = image()
        val original = sql.query("SELECT sql FROM main.sqlite_master WHERE name='count_trust_control'").use {
            check(it.moveToFirst()); it.getString(0)
        }
        try {
            fault.nativeSql("PRAGMA writable_schema=ON")
            fault.nativeSql("UPDATE sqlite_master SET sql=sql || hex(zeroblob(20000)) WHERE name='count_trust_control'")
            val error = runCatching { DatabaseOperationSource.run(identity, { true }) { sql.beginTransaction() } }.exceptionOrNull()
            assertEquals("DATABASE_EPOCH_REPLICA_CHANGED", error?.message)
            assertFalse(owner.isRetired); assertFalse(sql.inTransaction())
        } finally {
            fault.nativeSql("UPDATE sqlite_master SET sql=? WHERE name='count_trust_control'", arrayOf(original))
            fault.nativeSql("PRAGMA writable_schema=OFF")
        }
        assertEquals(before.contents, image().contents)
    } }

    private class NativeFault : SupportSQLiteOpenHelper.Factory {
        private var raw: SupportSQLiteDatabase? = null
        private var selected: Thread? = null
        private var loseEnd = false
        private var loseBegin = false
        fun nativeSql(sql: String, arguments: Array<out Any?> = emptyArray()) { requireNotNull(raw).execSQL(sql, arguments) }
        fun armBeginLoss() { check(!loseBegin); selected = Thread.currentThread(); loseBegin = true }
        fun armEndLoss() { check(!loseEnd); selected = Thread.currentThread(); loseEnd = true }
        fun cleanup() { if (selected === Thread.currentThread() && raw?.inTransaction() == true) raw!!.endTransaction() }
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            var original: SupportSQLiteDatabase? = null; var adapted: SupportSQLiteDatabase? = null
            fun wrap(native: SupportSQLiteDatabase): SupportSQLiteDatabase {
                if (original === native) return requireNotNull(adapted)
                original = native; raw = native
                return object : SupportSQLiteDatabase by native {
                    override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) {
                        native.beginTransactionWithListenerNonExclusive(transactionListener)
                        if (loseBegin && selected === Thread.currentThread()) { loseBegin = false; error("SOURCE_TEST_BEGIN_LOST") }
                    }
                    override fun endTransaction() {
                        native.endTransaction()
                        if (loseEnd && selected === Thread.currentThread()) { loseEnd = false; error("SOURCE_TEST_END_LOST") }
                    }
                }.also { adapted = it }
            }
            val callback = configuration.callback
            val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(object : SupportSQLiteOpenHelper.Callback(callback.version) {
                    override fun onConfigure(db: SupportSQLiteDatabase) = callback.onConfigure(wrap(db))
                    override fun onCreate(db: SupportSQLiteDatabase) = callback.onCreate(wrap(db))
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = callback.onUpgrade(wrap(db), oldVersion, newVersion)
                    override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = callback.onDowngrade(wrap(db), oldVersion, newVersion)
                    override fun onOpen(db: SupportSQLiteDatabase) = callback.onOpen(wrap(db))
                    override fun onCorruption(db: SupportSQLiteDatabase) = callback.onCorruption(wrap(db))
                }).build())
            return object : SupportSQLiteOpenHelper by helper {
                override val readableDatabase get() = wrap(helper.readableDatabase)
                override val writableDatabase get() = wrap(helper.writableDatabase)
            }
        }
    }
}
