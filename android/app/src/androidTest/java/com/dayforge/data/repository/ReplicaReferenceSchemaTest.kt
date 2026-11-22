package com.dayforge.data.repository

import com.dayforge.data.local.ReplicaSqlSchema
import com.dayforge.data.local.ReplicaReferenceSchema
import com.dayforge.data.local.ReplicaReferenceSchemaCache

import android.database.Cursor
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.CountTrustOpenHelperFactory
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.SyncSchemaCallback
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Local cache instances only. Lifecycle failures use real Room/reference SQLite, never a
 * global fault switch or an already-approved source proof. Ordinary 150-second runner budget.
 */
@RunWith(AndroidJUnit4::class)
class ReplicaReferenceSchemaTest {
    private enum class Fault { NONE, READ_AND_CLOSE, AFTER_END, CLOSE, CANCEL_AFTER_END, CANCEL_AFTER_CLOSE }
    private class HeldReadEnd {
        val events = CopyOnWriteArrayList<String>()
        val selected = AtomicBoolean()
        val endHeld = CompletableDeferred<Unit>()
        val endReturned = CompletableDeferred<Unit>()
        val closeEntered = CompletableDeferred<Unit>()
        val closeReturned = CompletableDeferred<Unit>()
        val releaseEnd = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val native = AtomicReference<SupportSQLiteDatabase?>()
        val nativeThread = AtomicReference<Thread?>()
        val roomOwner = AtomicReference<Job?>()

        fun holdEnd(raw: SupportSQLiteDatabase) {
            check(raw.isOpen && raw.inTransaction())
            check(native.compareAndSet(null, raw))
            check(nativeThread.compareAndSet(null, Thread.currentThread()))
            events += "END_HELD"
            endHeld.complete(Unit)
            check(releaseEnd.await(10, TimeUnit.SECONDS)) { "REFERENCE_TEST_END_GATE_NOT_RELEASED" }
        }

        fun ended(raw: SupportSQLiteDatabase) {
            check(native.get() === raw && nativeThread.get() === Thread.currentThread())
            check(!raw.inTransaction())
            events += "END_RETURN"
            endReturned.complete(Unit)
        }

        fun holdCloseDelegate() {
            // Observe the cache's actual Reference.close call, before Room/CountTrust
            // can retire or physically close a connection whose END is still held.
            events += "CLOSE_ENTER"
            closeEntered.complete(Unit)
            check(releaseClose.await(10, TimeUnit.SECONDS)) { "REFERENCE_TEST_CLOSE_GATE_NOT_RELEASED" }
        }
    }

    private class Trace(val fault: Fault = Fault.NONE, val heldReadEnd: HeldReadEnd? = null) {
        val analyzed = AtomicBoolean()
        val successful = AtomicBoolean()
        val actualReadEndReturned = AtomicBoolean()
        val readCommittedReturned = AtomicBoolean()
        val nativeCloseReturned = AtomicBoolean()
        val referenceCloseReturned = AtomicBoolean()
        val cancellationReached = AtomicBoolean()
        val cancellationTarget = AtomicReference<Job?>()
        val readFailure = IllegalStateException("REFERENCE_TEST_READ_FAILURE")
        val endFailure = IllegalStateException("REFERENCE_TEST_AFTER_NATIVE_END")
        val closeFailure = IllegalStateException("REFERENCE_TEST_AFTER_NATIVE_CLOSE")
    }

    /** The wrapper forwards actual native lifecycle first, then injects a local failure.
     * Initial Room creation transactions are not the measured reference-read transaction.
     */
    private class LifecycleFactory(private val trace: Trace) : SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            var original: SupportSQLiteDatabase? = null
            var adapted: SupportSQLiteDatabase? = null
            val watchingRead = AtomicBoolean()
            fun afterSql(statement: String) {
                if (statement.trim().equals("ANALYZE", ignoreCase = true) && trace.analyzed.compareAndSet(false, true)) {
                    watchingRead.set(true)
                    if (trace.fault == Fault.READ_AND_CLOSE) throw trace.readFailure
                }
            }
            fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase {
                if (original !== raw) {
                    original = raw
                    adapted = object : SupportSQLiteDatabase by raw {
                        override fun execSQL(sql: String) { raw.execSQL(sql); afterSql(sql) }
                        override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
                            raw.execSQL(sql, bindArgs); afterSql(sql)
                        }
                        override fun setTransactionSuccessful() {
                            raw.setTransactionSuccessful()
                            if (watchingRead.get()) trace.successful.set(true)
                        }
                        override fun endTransaction() {
                            val held = trace.heldReadEnd?.takeIf {
                                watchingRead.get() && it.selected.compareAndSet(false, true)
                            }
                            held?.holdEnd(raw)
                            raw.endTransaction()
                            held?.ended(raw)
                            if (!raw.inTransaction() && watchingRead.compareAndSet(true, false)) {
                                trace.actualReadEndReturned.set(true)
                                when (trace.fault) {
                                    Fault.AFTER_END -> throw trace.endFailure
                                    Fault.CANCEL_AFTER_END -> {
                                        trace.cancellationReached.set(true)
                                        requireNotNull(trace.cancellationTarget.get()).cancel(
                                            CancellationException("REFERENCE_TEST_CANCEL_AFTER_NATIVE_END"))
                                    }
                                    else -> Unit
                                }
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
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    parent.onUpgrade(wrap(db), oldVersion, newVersion)
                override fun onOpen(db: SupportSQLiteDatabase) = parent.onOpen(wrap(db))
            }
            val delegate = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : SupportSQLiteOpenHelper by delegate {
                override val writableDatabase get() = wrap(delegate.writableDatabase)
                override val readableDatabase get() = wrap(delegate.readableDatabase)
                override fun close() {
                    delegate.close()
                    trace.nativeCloseReturned.set(true)
                    if (trace.fault == Fault.CANCEL_AFTER_CLOSE) {
                        trace.cancellationReached.set(true)
                        requireNotNull(trace.cancellationTarget.get()).cancel(
                            CancellationException("REFERENCE_TEST_CANCEL_AFTER_NATIVE_CLOSE"))
                    }
                    if (trace.fault in setOf(Fault.CLOSE, Fault.READ_AND_CLOSE)) throw trace.closeFailure
                }
            }
        }
    }

    private fun reference(trace: Trace, beforeRead: suspend () -> Unit = {}): ReplicaReferenceSchemaCache.Reference {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        check(app.packageName == "com.dayforge.testbed")
        val factory = CountTrustOpenHelperFactory(app, LifecycleFactory(trace))
        val database = Room.inMemoryDatabaseBuilder(app, HabitDatabase::class.java)
            .openHelperFactory(factory).addCallback(SyncSchemaCallback).build()
        CountTrustRuntime.attach(database, factory)
        return object : ReplicaReferenceSchemaCache.Reference {
            override suspend fun readCommitted(): ReplicaReferenceSchema {
                beforeRead()
                val result = database.withTransaction {
                    trace.heldReadEnd?.let {
                        val owner = requireNotNull(currentCoroutineContext()[Job])
                        check(it.roomOwner.compareAndSet(null, owner))
                        owner.invokeOnCompletion { _ -> it.events += "ROOM_OWNER_COMPLETED" }
                    }
                    val sql = database.openHelper.writableDatabase
                    sql.execSQL("ANALYZE")
                    val objects = ReplicaSqlSchema.read(sql)
                    val identity = sql.query("SELECT id,identity_hash FROM room_master_table ORDER BY id").use {
                        check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 42L &&
                            it.getType(1) == Cursor.FIELD_TYPE_STRING)
                        it.getString(1).also { _ -> check(!it.moveToNext()) }
                    }
                    ReplicaReferenceSchema(objects, sql.version, identity)
                }
                trace.readCommittedReturned.set(true)
                return result
            }
            override fun close() {
                trace.heldReadEnd?.holdCloseDelegate()
                database.close()
                trace.referenceCloseReturned.set(true)
                trace.heldReadEnd?.let { it.events += "CLOSE_RETURN"; it.closeReturned.complete(Unit) }
            }
        }
    }

    private fun completed(trace: Trace) {
        assertTrue(trace.analyzed.get()); assertTrue(trace.successful.get())
        assertTrue("Actual native END must precede publication", trace.actualReadEndReturned.get())
        assertTrue(trace.readCommittedReturned.get())
        assertTrue("Actual native close must precede publication", trace.nativeCloseReturned.get())
        assertTrue(trace.referenceCloseReturned.get())
    }

    @Test fun publishedMetadataOwnsEveryCollectionAndWarmReadsCannotInvokeAnotherLoader() = runBlocking<Unit> {
        val clause = mutableListOf("word:example", "word:integer")
        val clauses = mutableListOf<List<String>>(clause)
        val source = linkedMapOf("table.example" to ReplicaSqlSchema.SchemaObject("table", "example", "example", clauses))
        val cache = ReplicaReferenceSchemaCache()
        var closed = false
        val frozen = cache.get { object : ReplicaReferenceSchemaCache.Reference {
            override suspend fun readCommitted() = ReplicaReferenceSchema(source, 19, "fixed-room-identity")
            override fun close() { closed = true }
        } }
        assertTrue(closed)
        clause[0] = "changed"; clauses.clear(); source.clear()
        val value = frozen.objects.getValue("table.example")
        assertEquals(listOf(listOf("word:example", "word:integer")), value.definition)
        assertTrue(runCatching { (frozen.objects as MutableMap<String, ReplicaSqlSchema.SchemaObject>).clear() }.isFailure)
        assertTrue(runCatching { (value.definition as MutableList<List<String>>).clear() }.isFailure)
        assertTrue(runCatching { (value.definition.single() as MutableList<String>)[0] = "changed" }.isFailure)
        assertEquals(setOf("table.example"), frozen.objects.keys)
        assertEquals(listOf(listOf("word:example", "word:integer")), value.definition)
        assertSame(frozen, cache.get { error("A warm cache must not retain or invoke another loader") })
    }

    @Test fun concurrentCallersBuildOneRealRoomReferenceAndObserveOnlyEndedClosedMetadata() = runBlocking<Unit> {
        val cache = ReplicaReferenceSchemaCache(); val trace = Trace()
        val builds = AtomicInteger(); val arrivals = AtomicInteger()
        val readStarted = CompletableDeferred<Unit>(); val allArrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coroutineScope {
            val calls = List(8) { async(Dispatchers.IO) {
                if (arrivals.incrementAndGet() == 8) allArrived.complete(Unit)
                cache.get {
                    builds.incrementAndGet()
                    reference(trace) { readStarted.complete(Unit); release.await() }
                }.also { completed(trace) }
            } }
            try {
                withTimeout(10_000) { readStarted.await(); allArrived.await() }
                assertEquals(1, builds.get())
                assertTrue(calls.none { it.isCompleted })
            } finally { release.complete(Unit) }
            val values = calls.awaitAll()
            values.forEach { assertSame(values.first(), it) }
            assertEquals(1, builds.get()); assertEquals(20, values.first().version)
            assertEquals(NextReplicaV19StorageView.CURRENT_ROOM_IDENTITY, values.first().roomIdentity)
            assertTrue(values.first().objects.containsKey("table.count_trust_control"))
        }
    }

    @Test fun failedReadKeepsItsPrimaryErrorClosesTheActualReferenceAndRetriesWithoutPublication() = runBlocking<Unit> {
        val cache = ReplicaReferenceSchemaCache(); val failed = Trace(Fault.READ_AND_CLOSE)
        val attempts = AtomicInteger()
        val error = runCatching { cache.get { attempts.incrementAndGet(); reference(failed) } }.exceptionOrNull()
        assertSame(failed.readFailure, error)
        assertTrue(requireNotNull(error).suppressed.any { it === failed.closeFailure })
        assertTrue(failed.analyzed.get()); assertFalse(failed.successful.get())
        assertTrue(failed.actualReadEndReturned.get()); assertTrue(failed.nativeCloseReturned.get())
        assertFalse(failed.readCommittedReturned.get()); assertFalse(failed.referenceCloseReturned.get())
        val retry = Trace()
        val result = cache.get { attempts.incrementAndGet(); reference(retry) }
        completed(retry); assertEquals(2, attempts.get())
        assertSame(result, cache.get { error("Successful retry must become the only published value") })
    }

    @Test fun actualEndFailureCannotPublishEvenWhenNativeCommitAlreadyReturned() = runBlocking<Unit> {
        val cache = ReplicaReferenceSchemaCache(); val failed = Trace(Fault.AFTER_END)
        val attempts = AtomicInteger()
        val error = runCatching { cache.get { attempts.incrementAndGet(); reference(failed) } }.exceptionOrNull()
        assertSame(failed.endFailure, error)
        assertTrue(failed.successful.get()); assertTrue(failed.actualReadEndReturned.get())
        assertFalse(failed.readCommittedReturned.get()); assertTrue(failed.nativeCloseReturned.get())
        assertTrue(failed.referenceCloseReturned.get())
        val retry = Trace()
        cache.get { attempts.incrementAndGet(); reference(retry) }
        completed(retry); assertEquals(2, attempts.get())
    }

    @Test fun actualCloseFailureCannotPublishAnOtherwiseCommittedReference() = runBlocking<Unit> {
        val cache = ReplicaReferenceSchemaCache(); val failed = Trace(Fault.CLOSE)
        val attempts = AtomicInteger()
        val error = runCatching { cache.get { attempts.incrementAndGet(); reference(failed) } }.exceptionOrNull()
        assertSame(failed.closeFailure, error)
        assertTrue(failed.successful.get()); assertTrue(failed.actualReadEndReturned.get())
        assertTrue(failed.readCommittedReturned.get()); assertTrue(failed.nativeCloseReturned.get())
        assertFalse(failed.referenceCloseReturned.get())
        val retry = Trace()
        cache.get { attempts.incrementAndGet(); reference(retry) }
        completed(retry); assertEquals(2, attempts.get())
    }

    @Test fun cancellationAfterActualEndOrCloseCannotPublishAndWarmCancelledCallersCannotReceiveMetadata() = runBlocking<Unit> {
        for (point in listOf(Fault.CANCEL_AFTER_END, Fault.CANCEL_AFTER_CLOSE)) {
            val cache = ReplicaReferenceSchemaCache(); val cancelled = Trace(point)
            val attempts = AtomicInteger(); val published = AtomicBoolean()
            supervisorScope {
                val work = async(Dispatchers.IO) {
                    cache.get {
                        attempts.incrementAndGet(); cancelled.cancellationTarget.set(currentCoroutineContext()[Job])
                        reference(cancelled)
                    }
                    published.set(true)
                }
                assertTrue(runCatching { work.await() }.exceptionOrNull() is CancellationException)
            }
            assertTrue(cancelled.cancellationReached.get()); assertTrue(cancelled.successful.get())
            assertTrue(cancelled.actualReadEndReturned.get()); assertTrue(cancelled.nativeCloseReturned.get())
            assertTrue(cancelled.referenceCloseReturned.get()); assertFalse(published.get())
            if (point == Fault.CANCEL_AFTER_CLOSE)
                assertTrue("Read and actual END must return before this cancellation", cancelled.readCommittedReturned.get())
            val retry = Trace()
            val ready = cache.get { attempts.incrementAndGet(); reference(retry) }
            completed(retry); assertEquals(2, attempts.get())
            val warmPublished = AtomicBoolean()
            supervisorScope {
                val work = async(Dispatchers.IO) {
                    currentCoroutineContext().cancel(CancellationException("REFERENCE_TEST_CANCEL_WARM_CALLER"))
                    cache.get { error("Warm cancelled caller must not rebuild") }
                    warmPublished.set(true)
                }
                assertTrue(runCatching { work.await() }.exceptionOrNull() is CancellationException)
            }
            assertFalse(warmPublished.get())
            assertSame(ready, cache.get { error("A cancelled caller must not destroy valid fixed metadata") })
        }
    }

    @Test(timeout = 150_000)
    fun cancellationWhileOriginalRoomEndIsHeldCannotCloseTheReferenceBeforeThatEnd() = runBlocking<Unit> {
        val cache = ReplicaReferenceSchemaCache()
        val held = HeldReadEnd(); val trace = Trace(heldReadEnd = held)
        val attempts = AtomicInteger(); val published = AtomicBoolean()
        var closeObservedBeforeRelease = false
        supervisorScope {
            val work = async(Dispatchers.IO) {
                cache.get { attempts.incrementAndGet(); reference(trace) }
                published.set(true)
            }
            work.invokeOnCompletion { held.events += "OWNER_COMPLETED" }
            var primary: Throwable? = null
            try {
                withTimeout(10_000) { held.endHeld.await() }
                assertTrue(trace.analyzed.get()); assertTrue(trace.successful.get())
                assertFalse(trace.actualReadEndReturned.get())
                assertNotSame(work, requireNotNull(held.roomOwner.get()))
                held.events += "CANCEL_REQUEST"
                work.cancel(CancellationException("REFERENCE_TEST_CANCEL_WHILE_NATIVE_END_HELD"))
                // A positive close entry is the red evidence. A timeout here is only
                // the bounded observation window; the final complete order is decisive.
                closeObservedBeforeRelease = withTimeoutOrNull(5_000) {
                    held.closeEntered.await(); true
                } ?: false
            } catch (failure: Throwable) {
                primary = failure
                throw failure
            } finally {
                try {
                    withContext(NonCancellable) {
                        held.releaseEnd.countDown()
                        withTimeout(5_000) {
                            held.endReturned.await()
                            requireNotNull(held.roomOwner.get()).join()
                        }
                        // An unknown END must never release the physical-close delegate.
                        held.releaseClose.countDown()
                        withTimeout(5_000) { work.join(); held.closeReturned.await() }
                        check(requireNotNull(held.roomOwner.get()).isCompleted && work.isCompleted)
                        check(trace.actualReadEndReturned.get() && trace.nativeCloseReturned.get() &&
                            trace.referenceCloseReturned.get() && !requireNotNull(held.native.get()).isOpen)
                    }
                } catch (cleanup: Throwable) {
                    // Never let an unresolved negative fixture proceed into another case.
                    PhysicalDatabaseRule.preserveProcessCleanupFailure(cleanup)
                    if (primary == null) throw cleanup else if (primary !== cleanup) primary.addSuppressed(cleanup)
                }
            }
            assertTrue(runCatching { work.await() }.exceptionOrNull() is CancellationException)
        }
        assertFalse(published.get()); assertFalse(trace.readCommittedReturned.get())
        assertEquals(1, attempts.get())
        val retry = Trace()
        val ready = cache.get { attempts.incrementAndGet(); reference(retry) }
        completed(retry); assertEquals(2, attempts.get())
        assertSame(ready, cache.get { error("Only the fresh ended/closed reference may be published") })
        val events = held.events.toList()
        val ended = events.indexOf("END_RETURN"); val closing = events.indexOf("CLOSE_ENTER")
        check(ended >= 0 && closing >= 0 && events.count { it == "END_HELD" } == 1 &&
            events.count { it == "END_RETURN" } == 1 && events.count { it == "CLOSE_ENTER" } == 1 &&
            events.count { it == "CLOSE_RETURN" } == 1)
        // Both the original reference and the successful retry are genuinely closed
        // before this sole ordering oracle fails on the original cache implementation.
        assertTrue("Reference.close entered before original native END; " +
            "closeObservedBeforeRelease=$closeObservedBeforeRelease events=$events", ended < closing)
    }
}
