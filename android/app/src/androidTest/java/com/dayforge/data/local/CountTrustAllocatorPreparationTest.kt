package com.dayforge.data.local

import android.database.Cursor
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.repository.NextEvidenceReadSession
import com.dayforge.data.repository.NextReplicaStorageProof
import com.dayforge.data.repository.NextRequestException
import com.dayforge.data.repository.NextRequestSql
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room and fixed private native faults; never an application SQL escape hatch. */
@RunWith(AndroidJUnit4::class)
class CountTrustAllocatorPreparationTest {
    private val fault = NativeFault()
    private lateinit var factory: CountTrustOpenHelperFactory
    @get:Rule val storage = PhysicalDatabaseRule().apply {
        isolatedBuilder = { context ->
            factory = CountTrustOpenHelperFactory(context, fault)
            Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
                .openHelperFactory(factory).addCallback(SyncSchemaCallback).build()
                .also { CountTrustRuntime.attach(it, factory) }
        }
    }
    private val db get() = storage.database
    private val sql get() = db.openHelper.writableDatabase
    private val owner get() = requireNotNull(CountTrustRuntime.forDatabase(db))
    private fun owned(block: suspend () -> Unit) = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job])); block()
    }
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private fun changes() = sql.query("SELECT total_changes()").use { assertTrue(it.moveToFirst()); it.getLong(0) }
    private fun sequence() = sql.query("SELECT rowid,name,seq FROM main.sqlite_sequence ORDER BY rowid").use { c ->
        buildList { while (c.moveToNext()) {
            assertEquals(Cursor.FIELD_TYPE_INTEGER, c.getType(0)); assertEquals(Cursor.FIELD_TYPE_STRING, c.getType(1))
            assertEquals(Cursor.FIELD_TYPE_INTEGER, c.getType(2)); add(Triple(c.getLong(0), c.getString(1), c.getLong(2)))
        } }
    }
    private suspend fun failure(message: String, block: suspend () -> Unit): Throwable {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected $message, got $error", error is IllegalStateException)
        assertEquals(message, error?.message); return requireNotNull(error)
    }

    @Test(timeout = 150_000) fun emptyPreparationHasExactlyTwoRowsAndNoOtherNativeChangeAcrossColdReopen() = owned {
        val before = image()
        db.withTransaction {
            assertTrue(sequence().isEmpty())
            val baseline = changes()
            owner.prepareOutboxAllocatorsForWrite(db)
            assertEquals(2L, changes() - baseline)
            assertEquals(listOf(Triple(1L, "sync_outbox", 0L), Triple(2L, "timer_command_outbox", 0L)), sequence())
            val stable = changes(); owner.prepareOutboxAllocatorsForWrite(db); assertEquals(stable, changes())
            for (table in listOf("sync_outbox", "timer_command_outbox")) {
                assertEquals(0L, NextRequestSql.watermark(sql, table)); assertTrue(NextRequestSql.sources(sql, table).isEmpty())
            }
        }
        val after = image()
        assertEquals(before - "tables", after - "tables")
        assertEquals(before.getValue("tables").jsonObject - "sqlite_sequence", after.getValue("tables").jsonObject - "sqlite_sequence")
        storage.reopen(); assertEquals(after, image()); assertFalse(owner.isRetired)
    }

    @Test(timeout = 150_000) fun anyExistingSequenceIsUntouchedAndOriginalInvalidWatermarksStillReject() = owned {
        for (queue in NativeFault.Queue.entries) for (shape in NativeFault.Shape.entries) db.withTransaction {
            fault.install(shape, queue)
            val before = NextReplicaStorageProof(db).readInTransaction().contents
            val baseline = changes(); owner.prepareOutboxAllocatorsForWrite(db)
            assertEquals(baseline, changes()); assertEquals(before, NextReplicaStorageProof(db).readInTransaction().contents)
            if (shape == NativeFault.Shape.OTHER_ONLY) {
                assertEquals(listOf(Triple(7L, "habits", 9L)), sequence())
                assertEquals(0L, NextRequestSql.watermark(sql, "sync_outbox"))
            } else {
                val error = runCatching { NextRequestSql.watermark(sql, queue.table) }.exceptionOrNull()
                assertTrue(error is NextRequestException)
                assertEquals(NextRequestException.Reason.INVALID_LOCAL_STATE, (error as NextRequestException).reason)
            }
        }
    }

    @Test(timeout = 150_000) fun readPhasesObserverAndActiveCommandCannotAcquirePreparation() = owned {
        val before = image()
        failure("COUNT_TRUST_MAINTENANCE_TRANSACTION_REQUIRED") { owner.prepareOutboxAllocatorsForWrite(db) }
        db.withTransaction {
            NextEvidenceReadSession.read(db) {
                failure("EVIDENCE_READ_WRITE_FORBIDDEN") { owner.prepareOutboxAllocatorsForWrite(db) }
            }
            owner.maintenance(db) {
                withContext(Dispatchers.Default) {
                    failure("COUNT_TRUST_MAINTENANCE_TRANSACTION_REQUIRED") { owner.prepareOutboxAllocatorsForWrite(db) }
                }
            }
            owner.withReadSnapshot(db) {
                failure("COUNT_TRUST_READ_SNAPSHOT_WRITE_DENIED") { owner.prepareOutboxAllocatorsForWrite(db) }
            }
            factory.observeWithoutCapabilities {
                runBlocking { failure("COUNT_TRUST_OBSERVER_CONTROL_DENIED") { owner.prepareOutboxAllocatorsForWrite(db) } }
            }
        }
        val rollback = IllegalStateException("ALLOCATOR_TEST_COMMAND_ROLLBACK")
        assertSame(rollback, runCatching { db.withTransaction {
            owner.maintenance(db) {
                owner.captureCommandBoundary()
                failure("COUNT_TRUST_ALLOCATOR_WRITE_CONTEXT") { owner.prepareOutboxAllocatorsForWrite(db) }
                throw rollback
            }
        } }.exceptionOrNull())
        assertEquals(before, image()); assertFalse(owner.isRetired)
    }

    @Test(timeout = 150_000) fun caughtPostWriteFailureCannotCommitAndOriginalRollbackRemainsComplete() = owned {
        val before = image()
        for (extraWrite in listOf(false, true)) {
            var caught: Throwable? = null
            val ended = fault.ends
            val outer = runCatching { db.withTransaction {
                fault.postWriteFailure = !extraWrite; fault.extraWrite = extraWrite
                caught = runCatching { owner.prepareOutboxAllocatorsForWrite(db) }.exceptionOrNull()
                assertNotNull(caught)
                assertEquals(if (extraWrite) "COUNT_TRUST_ALLOCATOR_EXTRA_WRITE" else "ALLOCATOR_TEST_AFTER_NATIVE_WRITE", caught?.message)
            } }.exceptionOrNull()
            assertSame(caught, outer); assertEquals(ended + 1, fault.ends)
            assertEquals(before, image()); assertFalse(owner.hasUnknownGraphBoundary())
        }
        val rollback = IllegalStateException("ALLOCATOR_TEST_ORIGINAL_ROLLBACK")
        val error = runCatching { db.withTransaction { owner.prepareOutboxAllocatorsForWrite(db); throw rollback } }.exceptionOrNull()
        assertSame(rollback, error); assertEquals(before, image()); assertFalse(owner.isRetired)
    }

    private class NativeFault : SupportSQLiteOpenHelper.Factory {
        enum class Shape { OTHER_ONLY, NULL_VALUE, REAL_VALUE, TEXT_VALUE, BLOB_VALUE, NEGATIVE, DUPLICATE }
        enum class Queue(val table: String) { SYNC("sync_outbox"), TIMER("timer_command_outbox") }
        private var native: SupportSQLiteDatabase? = null
        var postWriteFailure = false
        var extraWrite = false
        // One marker belongs to the native transaction executing the fixed INSERT on
        // this thread. Room's unrelated invalidation transactions are not target ENDs.
        private val writeTransaction = ThreadLocal<SupportSQLiteDatabase?>()
        private val completedWrites = java.util.concurrent.atomic.AtomicInteger()
        val ends: Int get() = completedWrites.get()
        fun install(shape: Shape, queue: Queue) {
            val raw = requireNotNull(native); check(raw.inTransaction())
            raw.execSQL("DELETE FROM main.sqlite_sequence")
            when (shape) {
                Shape.OTHER_ONLY -> raw.execSQL("INSERT INTO main.sqlite_sequence(rowid,name,seq) VALUES(7,'habits',9)")
                Shape.NULL_VALUE -> raw.execSQL("INSERT INTO main.sqlite_sequence(name,seq) VALUES(?,NULL)", arrayOf(queue.table))
                Shape.REAL_VALUE -> raw.execSQL("INSERT INTO main.sqlite_sequence(name,seq) VALUES(?,1.5)", arrayOf(queue.table))
                Shape.BLOB_VALUE -> raw.execSQL("INSERT INTO main.sqlite_sequence(name,seq) VALUES(?,X'0102')", arrayOf(queue.table))
                Shape.TEXT_VALUE -> raw.execSQL("INSERT INTO main.sqlite_sequence(name,seq) VALUES(?,'invalid')", arrayOf(queue.table))
                Shape.NEGATIVE -> raw.execSQL("INSERT INTO main.sqlite_sequence(name,seq) VALUES(?,-1)", arrayOf(queue.table))
                Shape.DUPLICATE -> raw.execSQL("INSERT INTO main.sqlite_sequence(name,seq) VALUES(?,0),(?,0)", arrayOf(queue.table, queue.table))
            }
        }
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            var original: SupportSQLiteDatabase? = null
            var adapted: SupportSQLiteDatabase? = null
            fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase {
                if (original !== raw) {
                    original = raw; native = raw
                    adapted = object : SupportSQLiteDatabase by raw {
                        override fun execSQL(sql: String) {
                            val target = sql == "INSERT INTO main.sqlite_sequence(rowid,name,seq) VALUES(1,'sync_outbox',0),(2,'timer_command_outbox',0)"
                            if (target) {
                                check(raw.inTransaction() && writeTransaction.get() == null) { "ALLOCATOR_TEST_WRITE_OWNER" }
                                writeTransaction.set(raw)
                            }
                            raw.execSQL(sql)
                            if (target) {
                                if (extraWrite) { extraWrite = false; raw.execSQL("UPDATE main.count_trust_control SET dirty=dirty WHERE id=1") }
                                if (postWriteFailure) { postWriteFailure = false; error("ALLOCATOR_TEST_AFTER_NATIVE_WRITE") }
                            }
                        }
                        override fun endTransaction() {
                            val target = writeTransaction.get()
                            raw.endTransaction() // An exception leaves the target pending, never counted as closed.
                            if (target === raw && !raw.inTransaction()) {
                                check(writeTransaction.get() === raw) { "ALLOCATOR_TEST_END_OWNER" }
                                writeTransaction.remove()
                                completedWrites.incrementAndGet()
                            }
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
            val delegate = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : SupportSQLiteOpenHelper by delegate {
                override val writableDatabase get() = wrap(delegate.writableDatabase)
                override val readableDatabase get() = wrap(delegate.readableDatabase)
            }
        }
    }
}
