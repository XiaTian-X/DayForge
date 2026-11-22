package com.dayforge.data.local

import android.database.Cursor
import android.database.sqlite.SQLiteTransactionListener
import android.os.CancellationSignal
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Real connection/END evidence only, not whole-graph/Hilt closure or a known command result. */
@RunWith(AndroidJUnit4::class)
class CountTrustGraphDrainTest {
    private val transactionExecutor = Executors.newSingleThreadExecutor()
    private val fault = OriginalEndFault()
    @get:Rule(order = 0) val time = Timeout.seconds(150)
    @get:Rule(order = 1) val threads = object : ExternalResource() {
        override fun after() {
            transactionExecutor.shutdown(); check(transactionExecutor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
    @get:Rule(order = 2) val storage = PhysicalDatabaseRule().apply {
        isolatedBuilder = { context ->
            val factory = CountTrustOpenHelperFactory(context, fault)
            Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
                .setTransactionExecutor(transactionExecutor).openHelperFactory(factory)
                .addCallback(SyncSchemaCallback).build().also { CountTrustRuntime.attach(it, factory) }
        }
    }
    @get:Rule(order = 3) val nativeCleanup = object : ExternalResource() {
        override fun after() {
            // Before PhysicalDatabaseRule closes the handle, even when an assertion failed.
            // Test-only cleanup never certifies or publishes the failed production operation.
            fault.releaseRefresh()
            transactionExecutor.submit { fault.cleanupOriginalThread() }.get(5, TimeUnit.SECONDS)
        }
    }
    private val db get() = storage.database
    private val owner get() = requireNotNull(CountTrustRuntime.forDatabase(db))
    private val sql get() = db.openHelper.writableDatabase
    private suspend fun open() = withContext(Dispatchers.IO) { sql.query("SELECT 1").use { assertTrue(it.moveToFirst()) } }
    private suspend fun sealedDrain(): CountTrustOpenHelperFactory.GraphDrain.Ready = withTimeout(5_000) {
        var ready: CountTrustOpenHelperFactory.GraphDrain.Ready? = null
        while (ready == null) {
            when (val proof = owner.captureGraphDrain(db)) {
                is CountTrustOpenHelperFactory.GraphDrain.Ready -> {
                    // Production closes admission against this exact snapshot before close.
                    // A queued invalidation transaction may win; only a fresh sealed proof
                    // permits closure, still within the original five-second drain budget.
                    if (owner.trySealGraphClose(db, proof.witness)) ready = proof else delay(10)
                }
                CountTrustOpenHelperFactory.GraphDrain.Busy -> delay(10)
                CountTrustOpenHelperFactory.GraphDrain.Unknown -> error("Unexpected unknown physical boundary")
            }
        }
        ready
    }
    private suspend fun failEnd(mode: OriginalEndFault.Mode): Throwable {
        var completed: CountTrustRuntime.Completed<Any>? = null
        val problem = try {
            completed = owner.transaction(db) {
                sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                fault.arm(mode); Any()
            }
            null
        } catch (failure: Throwable) { failure }
        assertNull("No candidate is returned on any unknown END", completed)
        assertEquals("GRAPH_TEST_END_OUTCOME_UNKNOWN", problem?.message)
        assertTrue(owner.isRetired)
        assertTrue(CountTrustRetirementReason.UNKNOWN_END in owner.retirementReasons)
        return requireNotNull(problem)
    }
    @Test fun committedThenLostEndOnlyPermitsPhysicalCloseAndNeverReissuesItsReceipt() = runBlocking<Unit> {
        open()
        val old = owner
        val candidate = Any()
        val prior = old.transaction(db) { candidate }
        prior.end.requireCandidate(candidate)
        failEnd(OriginalEndFault.Mode.AFTER_END)
        assertEquals("COUNT_TRUST_END_RECEIPT_EXPIRED", runCatching { prior.end.requireCandidate(candidate) }.exceptionOrNull()?.message)
        val proof = sealedDrain(); val closeEpoch = old.generation; val nativeOpen = fault.originalOpenProbe()
        db.close()
        assertFalse("Original native handle remains open after Room.close", nativeOpen())
        assertEquals("Only the original close may retire this sealed epoch", closeEpoch + 1L, old.generation)
        old.requireGraphClosed(db, proof.witness)
        assertEquals(1, fault.nativeCloses.get()); assertEquals(1, fault.helperCloses.get())
        assertTrue(old.isRetired)
        storage.reopen(); open()
        assertNotSame(old, owner); assertFalse(owner.isRetired)
        assertTrue(CountTrustRetirementReason.UNKNOWN_END in old.retirementReasons)
        sql.query("SELECT suppressOutbox FROM sync_control WHERE id=1").use {
            assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0)); assertFalse(it.moveToNext())
        }
        assertEquals("COUNT_TRUST_END_RECEIPT_EXPIRED", runCatching { prior.end.requireCandidate(candidate) }.exceptionOrNull()?.message)
    }
    @Test fun anotherThreadsFalseCannotResolveTheOriginalUnfinishedEnd() = runBlocking<Unit> {
        open(); failEnd(OriginalEndFault.Mode.BEFORE_END)
        assertSame(CountTrustOpenHelperFactory.GraphDrain.Unknown, owner.captureGraphDrain(db))
        assertFalse("SQLite's unrelated thread is not the original transaction's END witness",
            withContext(Dispatchers.IO) { fault.isInTransactionOnCurrentThread() })
        assertSame(CountTrustOpenHelperFactory.GraphDrain.Unknown, owner.captureGraphDrain(db))
        transactionExecutor.submit { fault.cleanupOriginalThread() }.get(5, TimeUnit.SECONDS)
        assertFalse(withContext(Dispatchers.IO) { fault.isInTransactionOnCurrentThread() })
        assertSame("Later cleanup does not retroactively certify the failed END",
            CountTrustOpenHelperFactory.GraphDrain.Unknown, owner.captureGraphDrain(db))
    }
    @Test fun failedInspectionNeverBecomesClosureEvidenceEvenAfterActualEnd() = runBlocking<Unit> {
        open(); val failure = failEnd(OriginalEndFault.Mode.INSPECTION_THROWS)
        assertTrue(failure.suppressed.any { it.message == "GRAPH_TEST_INSPECTION_FAILED" })
        assertFalse(withContext(Dispatchers.IO) { fault.isInTransactionOnCurrentThread() })
        assertSame(CountTrustOpenHelperFactory.GraphDrain.Unknown, owner.captureGraphDrain(db))
    }
    @Test fun activeActualReadTransactionMustEndBeforeAnyClosureWitness() = runBlocking<Unit> {
        open(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val reader = async(Dispatchers.IO) {
            db.withTransaction {
                owner.withReadSnapshot(db) { entered.complete(Unit); release.await() }
            }
        }
        try {
            withTimeout(5_000) { entered.await() }
            assertSame(CountTrustOpenHelperFactory.GraphDrain.Busy, owner.captureGraphDrain(db))
        } finally { release.complete(Unit); withTimeout(5_000) { reader.await() } }
        val proof = sealedDrain(); val selected = owner; val closeEpoch = selected.generation
        val nativeOpen = fault.originalOpenProbe()
        db.close()
        assertFalse("Original native handle remains open after Room.close", nativeOpen())
        assertEquals("Only the original close may retire this sealed epoch", closeEpoch + 1L, selected.generation)
        selected.requireGraphClosed(db, proof.witness)
        assertEquals(1, fault.nativeCloses.get()); assertEquals(1, fault.helperCloses.get())
    }
    @Test fun closingOneOwnerCannotConfirmAnotherOwnersWitnessForTheSameFile() = runBlocking<Unit> {
        open(); val oldDb = db; val old = owner
        val proof = sealedDrain(); val closeEpoch = old.generation; val nativeOpen = fault.originalOpenProbe()
        storage.reopen(); open()
        assertFalse("Original native handle remains open after reopen", nativeOpen())
        assertEquals("Only the original close may retire this sealed epoch", closeEpoch + 1L, old.generation)
        val fresh = owner
        assertNotSame(old, fresh)
        assertEquals("COUNT_TRUST_GRAPH_CLOSE_UNCONFIRMED", runCatching {
            fresh.requireGraphClosed(db, proof.witness)
        }.exceptionOrNull()?.message)
        old.requireGraphClosed(oldDb, proof.witness)
        assertTrue(old.isRetired); assertFalse(fresh.isRetired)
    }

    @Test fun firstNativeCloseFailureNeverBecomesConfirmationAfterTheLaterHelperCloses() = runBlocking<Unit> {
        open(); db.withTransaction { sql.query("SELECT 1").close() }
        val selected = owner; val proof = sealedDrain(); val before = selected.generation
        val nativeOpen = fault.originalOpenProbe(); fault.armClose(OriginalEndFault.CloseMode.NATIVE_AFTER)
        assertEquals("GRAPH_TEST_NATIVE_CLOSE_LOST", runCatching { db.close() }.exceptionOrNull()?.message)
        assertFalse(nativeOpen()); assertEquals(1, fault.nativeCloses.get()); assertEquals(0, fault.helperCloses.get())
        assertFalse(selected.isGraphCloseConfirmed(db))
        assertEquals("COUNT_TRUST_GRAPH_CLOSE_UNCONFIRMED", runCatching { selected.requireGraphClosed(db, proof.witness) }.exceptionOrNull()?.message)
        // An explicit later cleanup closes the original helper but cannot backdate success.
        db.openHelper.close()
        assertEquals(1, fault.helperCloses.get()); assertEquals(before + 2L, selected.generation)
        assertEquals("COUNT_TRUST_GRAPH_CLOSE_UNCONFIRMED", runCatching { selected.requireGraphClosed(db, proof.witness) }.exceptionOrNull()?.message)
    }

    @Test fun failedPhysicalCloseInspectionNeverIssuesACompletedClose() = runBlocking<Unit> {
        open(); db.withTransaction { sql.query("SELECT 1").close() }
        val selected = owner; val proof = sealedDrain(); val before = selected.generation
        val nativeOpen = fault.originalOpenProbe(); fault.armClose(OriginalEndFault.CloseMode.INSPECTION_AFTER)
        assertEquals("GRAPH_TEST_CLOSE_INSPECTION_FAILED", runCatching { db.close() }.exceptionOrNull()?.message)
        assertFalse(nativeOpen()); assertEquals(before + 1L, selected.generation)
        assertFalse(selected.isGraphCloseConfirmed(db))
        assertEquals("COUNT_TRUST_GRAPH_CLOSE_UNCONFIRMED", runCatching { selected.requireGraphClosed(db, proof.witness) }.exceptionOrNull()?.message)
        db.openHelper.close()
        assertEquals(before + 2L, selected.generation)
        assertEquals("COUNT_TRUST_GRAPH_CLOSE_UNCONFIRMED", runCatching { selected.requireGraphClosed(db, proof.witness) }.exceptionOrNull()?.message)
    }

    @Test fun laterHelperFailureRevokesAFormerConfirmedSameNativeClosure() = runBlocking<Unit> {
        open(); db.withTransaction { sql.query("SELECT 1").close() }
        val selected = owner; val proof = sealedDrain(); val before = selected.generation
        db.close(); selected.requireGraphClosed(db, proof.witness)
        assertTrue(selected.isGraphCloseConfirmed(db)); assertEquals(before + 1L, selected.generation)
        assertEquals(1, fault.nativeCloses.get()); assertEquals(1, fault.helperCloses.get())
        fault.armClose(OriginalEndFault.CloseMode.HELPER_AFTER)
        assertEquals("GRAPH_TEST_HELPER_CLOSE_LOST", runCatching { db.openHelper.close() }.exceptionOrNull()?.message)
        assertEquals(2, fault.helperCloses.get()); assertFalse(selected.isGraphCloseConfirmed(db))
        assertEquals("COUNT_TRUST_GRAPH_CLOSE_UNCONFIRMED", runCatching { selected.requireGraphClosed(db, proof.witness) }.exceptionOrNull()?.message)
    }

    @Test fun realCorruptionCallbackBetweenCloseLayersCannotBeDeduplicatedAsConnectionClose() = runBlocking<Unit> {
        open(); db.withTransaction { sql.query("SELECT 1").close() }
        val selected = owner; val proof = sealedDrain(); val before = selected.generation
        fault.armClose(OriginalEndFault.CloseMode.CORRUPTION_AFTER_HELPER)
        assertEquals("COUNT_TRUST_CORRUPTION_PRESERVED", runCatching { db.close() }.exceptionOrNull()?.message)
        assertEquals(1, fault.nativeCloses.get()); assertEquals(1, fault.helperCloses.get())
        assertEquals(before + 2L, selected.generation)
        assertTrue(CountTrustRetirementReason.CORRUPTION in selected.retirementReasons)
        assertFalse(selected.isGraphCloseConfirmed(db))
        assertEquals("COUNT_TRUST_GRAPH_CLOSE_UNCONFIRMED", runCatching { selected.requireGraphClosed(db, proof.witness) }.exceptionOrNull()?.message)
    }

    @Test fun realQueuedRoomRefreshLosingSealRaceCancelsWithoutNativeBeginAndFreshOwnerStillWorks() = runBlocking<Unit> {
        open()
        val before = db.withTransaction {
            com.dayforge.data.repository.NextReplicaStorageProof(db).readInTransaction().let { it.contents to it.hash }
        }
        val selected = owner; val originalDatabase = db; val originalSql = sql
        var closing: kotlinx.coroutines.Deferred<Unit>? = null
        val closeThread = AtomicReference<Thread?>()
        fault.armRefresh()
        try {
            // A prior automatic refresh may have the pending flag. Request the actual public
            // refresh until one reaches its real BEGIN on the original Room coroutine.
            withTimeout(5_000) {
                while (!fault.refreshReached.isCompleted) {
                    db.invalidationTracker.refreshAsync(); delay(10)
                }
            }
            assertEquals(1, fault.refreshPauses.get())
            assertTrue(requireNotNull(fault.refreshStack.get()).any {
                it.className == "com.dayforge.data.local.CountTrustOpenHelperFactory\$Database" && it.methodName.startsWith("begin")
            })
            assertTrue(requireNotNull(fault.refreshStack.get()).any { it.className == "androidx.room.TriggerBasedInvalidationTracker" })
            val proof = sealedDrain(); val closeEpoch = selected.generation
            val begins = fault.nativeBegins.get(); val sqlCalls = fault.nativeSqlCalls.get(); val helperReads = fault.helperReads.get()
            val reasons = selected.retirementReasons
            fun exactCancellation(block: () -> Unit) {
                val failure = runCatching(block).exceptionOrNull()
                assertTrue(failure is CancellationException)
                assertEquals("COUNT_TRUST_CONNECTION_EXPIRED", failure?.message)
                assertEquals("com.dayforge.data.local.CountTrustOpenHelperFactory\$ClosingCancellation", failure?.javaClass?.name)
            }
            var listenerCalls = 0
            val listener = object : SQLiteTransactionListener {
                override fun onBegin() { listenerCalls++ }
                override fun onCommit() { listenerCalls++ }
                override fun onRollback() { listenerCalls++ }
            }
            exactCancellation { originalSql.beginTransaction() }
            exactCancellation { originalSql.beginTransactionNonExclusive() }
            exactCancellation { originalSql.beginTransactionReadOnly() }
            exactCancellation { originalSql.beginTransactionWithListener(listener) }
            exactCancellation { originalSql.beginTransactionWithListenerNonExclusive(listener) }
            exactCancellation { originalSql.beginTransactionWithListenerReadOnly(listener) }
            exactCancellation { originalDatabase.openHelper.writableDatabase }
            exactCancellation { originalDatabase.openHelper.readableDatabase }
            assertEquals(0, listenerCalls); assertEquals(reasons, selected.retirementReasons)
            assertEquals(begins, fault.nativeBegins.get()); assertEquals(sqlCalls, fault.nativeSqlCalls.get())
            assertEquals(helperReads, fault.helperReads.get())
            val nativeCloses = fault.nativeCloses.get(); val helperCloses = fault.helperCloses.get()
            val activeClose = async(Dispatchers.IO) { closeThread.set(Thread.currentThread()); originalDatabase.close() }
            closing = activeClose
            // Room cancels its scope only AFTER this barrier. Observing it waiting proves the
            // selected refresh must meet the second admission gate, not a prior scope cancel.
            withTimeout(5_000) {
                while (closeThread.get()?.stackTrace?.any { it.className == "androidx.room.concurrent.CloseBarrier" &&
                        it.methodName.startsWith("close") } != true) delay(10)
            }
            assertFalse(activeClose.isCompleted)
            assertEquals(nativeCloses, fault.nativeCloses.get()); assertEquals(helperCloses, fault.helperCloses.get())
            fault.releaseRefresh()
            withTimeout(5_000) { activeClose.await() }
            assertFalse(fault.refreshTimedOut.get()); assertEquals(1, fault.refreshPauses.get())
            assertEquals(begins, fault.nativeBegins.get()); assertEquals(sqlCalls, fault.nativeSqlCalls.get())
            assertEquals(helperReads, fault.helperReads.get())
            assertEquals(nativeCloses + 1, fault.nativeCloses.get()); assertEquals(helperCloses + 1, fault.helperCloses.get())
            assertEquals(closeEpoch + 1L, selected.generation)
            selected.requireGraphClosed(originalDatabase, proof.witness)
            exactCancellation { originalSql.beginTransactionNonExclusive() } // Also before expired native isOpen.
            assertEquals(begins, fault.nativeBegins.get())
            storage.reopen(); open()
            assertNotSame(selected, owner); assertFalse(owner.isRetired)
            val after = db.withTransaction {
                com.dayforge.data.repository.NextReplicaStorageProof(db).readInTransaction().let { it.contents to it.hash }
            }
            assertEquals(before, after)
        } finally {
            fault.releaseRefresh()
            closing?.let { withTimeout(5_000) { it.await() } }
        }
    }

    private class OriginalEndFault : SupportSQLiteOpenHelper.Factory {
        enum class Mode { NONE, AFTER_END, BEFORE_END, INSPECTION_THROWS }
        enum class CloseMode { NONE, NATIVE_AFTER, INSPECTION_AFTER, HELPER_AFTER, CORRUPTION_AFTER_HELPER }
        @Volatile private var closeMode = CloseMode.NONE
        @Volatile private var closeInspectionFailure = false
        val nativeCloses = AtomicInteger(); val helperCloses = AtomicInteger()
        val nativeBegins = AtomicInteger(); val nativeSqlCalls = AtomicInteger(); val helperReads = AtomicInteger()
        private val refreshArmed = AtomicBoolean()
        private val refreshRelease = CountDownLatch(1)
        val refreshReached = CompletableDeferred<Unit>(); val refreshTimedOut = AtomicBoolean()
        val refreshPauses = AtomicInteger(); val refreshStack = AtomicReference<List<StackTraceElement>?>()
        fun armRefresh() { check(refreshArmed.compareAndSet(false, true)) }
        fun releaseRefresh() { refreshRelease.countDown() }
        private fun pauseActualRefresh(isOpen: Boolean) {
            if (!isOpen || !refreshArmed.get()) return
            val stack = Thread.currentThread().stackTrace.toList()
            if (stack.none { it.className == "com.dayforge.data.local.CountTrustOpenHelperFactory\$Database" &&
                    it.methodName.startsWith("begin") } ||
                stack.none { it.className == "androidx.room.TriggerBasedInvalidationTracker" } ||
                !refreshArmed.compareAndSet(true, false)) return
            refreshStack.set(stack); refreshPauses.incrementAndGet(); refreshReached.complete(Unit)
            if (!refreshRelease.await(5, TimeUnit.SECONDS)) {
                refreshTimedOut.set(true); error("GRAPH_TEST_REFRESH_RELEASE_TIMEOUT")
            }
        }
        fun armClose(value: CloseMode) { check(closeMode == CloseMode.NONE); closeMode = value }
        @Volatile private var mode = Mode.NONE
        @Volatile private var selectedThread: Thread? = null
        @Volatile private var raw: SupportSQLiteDatabase? = null
        private var inspectionFailure = false
        private var stranded = false
        fun arm(value: Mode) { check(mode == Mode.NONE); selectedThread = Thread.currentThread(); mode = value }
        fun isInTransactionOnCurrentThread() = requireNotNull(raw).inTransaction()
        fun originalOpenProbe(): () -> Boolean {
            val original = requireNotNull(raw)
            return { original.isOpen } // Pure observation of the SAME native handle, even after reopen.
        }
        fun cleanupOriginalThread() {
            if (!stranded) return
            check(Thread.currentThread() === selectedThread)
            requireNotNull(raw).endTransaction(); stranded = false
        }
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            var original: SupportSQLiteDatabase? = null
            var adapted: SupportSQLiteDatabase? = null
            val monitor = Any()
            fun wrap(native: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
                if (original === native) return@synchronized requireNotNull(adapted)
                original = native; raw = native
                object : SupportSQLiteDatabase by native {
                    override fun beginTransaction() { nativeBegins.incrementAndGet(); native.beginTransaction() }
                    override fun beginTransactionNonExclusive() { nativeBegins.incrementAndGet(); native.beginTransactionNonExclusive() }
                    override fun beginTransactionReadOnly() { nativeBegins.incrementAndGet(); native.beginTransactionReadOnly() }
                    override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) {
                        nativeBegins.incrementAndGet(); native.beginTransactionWithListener(transactionListener)
                    }
                    override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) {
                        nativeBegins.incrementAndGet(); native.beginTransactionWithListenerNonExclusive(transactionListener)
                    }
                    override fun beginTransactionWithListenerReadOnly(transactionListener: SQLiteTransactionListener) {
                        nativeBegins.incrementAndGet(); native.beginTransactionWithListenerReadOnly(transactionListener)
                    }
                    override fun query(query: String): Cursor { nativeSqlCalls.incrementAndGet(); return native.query(query) }
                    override fun query(query: String, bindArgs: Array<out Any?>): Cursor {
                        nativeSqlCalls.incrementAndGet(); return native.query(query, bindArgs)
                    }
                    override fun query(query: SupportSQLiteQuery): Cursor { nativeSqlCalls.incrementAndGet(); return native.query(query) }
                    override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor {
                        nativeSqlCalls.incrementAndGet(); return native.query(query, cancellationSignal)
                    }
                    override fun execSQL(sql: String) { nativeSqlCalls.incrementAndGet(); native.execSQL(sql) }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
                        nativeSqlCalls.incrementAndGet(); native.execSQL(sql, bindArgs)
                    }
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        nativeSqlCalls.incrementAndGet(); val actual = native.compileStatement(sql)
                        return object : SupportSQLiteStatement by actual {
                            override fun execute() { nativeSqlCalls.incrementAndGet(); actual.execute() }
                            override fun executeInsert(): Long { nativeSqlCalls.incrementAndGet(); return actual.executeInsert() }
                            override fun executeUpdateDelete(): Int { nativeSqlCalls.incrementAndGet(); return actual.executeUpdateDelete() }
                            override fun simpleQueryForLong(): Long { nativeSqlCalls.incrementAndGet(); return actual.simpleQueryForLong() }
                            override fun simpleQueryForString(): String? { nativeSqlCalls.incrementAndGet(); return actual.simpleQueryForString() }
                        }
                    }
                    override fun close() {
                        nativeCloses.incrementAndGet(); native.close()
                        when (closeMode) {
                            CloseMode.NATIVE_AFTER -> { closeMode = CloseMode.NONE; error("GRAPH_TEST_NATIVE_CLOSE_LOST") }
                            CloseMode.INSPECTION_AFTER -> { closeMode = CloseMode.NONE; closeInspectionFailure = true }
                            else -> Unit
                        }
                    }
                    override val isOpen: Boolean get() {
                        if (closeInspectionFailure) { closeInspectionFailure = false; error("GRAPH_TEST_CLOSE_INSPECTION_FAILED") }
                        return native.isOpen.also { pauseActualRefresh(it) }
                    }
                    override fun endTransaction() {
                        val chosen = if (Thread.currentThread() === selectedThread) mode else Mode.NONE
                        if (chosen == Mode.NONE) { native.endTransaction(); return }
                        mode = Mode.NONE
                        if (chosen == Mode.BEFORE_END) stranded = true else native.endTransaction()
                        if (chosen == Mode.INSPECTION_THROWS) inspectionFailure = true
                        error("GRAPH_TEST_END_OUTCOME_UNKNOWN")
                    }
                    override fun inTransaction(): Boolean {
                        if (Thread.currentThread() === selectedThread && inspectionFailure) {
                            inspectionFailure = false; error("GRAPH_TEST_INSPECTION_FAILED")
                        }
                        return native.inTransaction()
                    }
                }.also { adapted = it }
            }
            val callback = configuration.callback
            val delegate = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(object : SupportSQLiteOpenHelper.Callback(callback.version) {
                    override fun onConfigure(db: SupportSQLiteDatabase) = callback.onConfigure(wrap(db))
                    override fun onCreate(db: SupportSQLiteDatabase) = callback.onCreate(wrap(db))
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = callback.onUpgrade(wrap(db), oldVersion, newVersion)
                    override fun onOpen(db: SupportSQLiteDatabase) = callback.onOpen(wrap(db))
                    override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = callback.onDowngrade(wrap(db), oldVersion, newVersion)
                    override fun onCorruption(db: SupportSQLiteDatabase) = callback.onCorruption(wrap(db))
                }).build())
            return object : SupportSQLiteOpenHelper by delegate {
                override val readableDatabase get() = wrap(delegate.readableDatabase.also { helperReads.incrementAndGet() })
                override val writableDatabase get() = wrap(delegate.writableDatabase.also { helperReads.incrementAndGet() })
                override fun close() {
                    helperCloses.incrementAndGet(); delegate.close()
                    when (closeMode) {
                        CloseMode.HELPER_AFTER -> { closeMode = CloseMode.NONE; error("GRAPH_TEST_HELPER_CLOSE_LOST") }
                        CloseMode.CORRUPTION_AFTER_HELPER -> { closeMode = CloseMode.NONE; callback.onCorruption(requireNotNull(raw)) }
                        else -> Unit
                    }
                }
            }
        }
    }
}
