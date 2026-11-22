package com.dayforge.data.local

import android.database.Cursor
import android.database.CursorWrapper
import android.os.CancellationSignal
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.withTransaction
import androidx.sqlite.db.*
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Actual first Hilt Host initialization. Native and dispatcher probes only delay/cancel the
 * original operation; they do not replace a graph, mutate Host state or issue permissions. */
@HiltAndroidTest @RunWith(AndroidJUnit4::class)
class DatabaseColdInitializationCancellationTest {
    private val native = NativeInitializationProbe()
    @get:Rule(order = 0) val timeout = Timeout.seconds(150)
    @get:Rule(order = 1) val epochs = PhysicalDatabaseEpochFixture().apply { nativeFactory = native }
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    private val tasks = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val releases = mutableListOf<CompletableDeferred<Unit>>()
    private val dispatchers = mutableListOf<ReturnGate>()
    @Before fun start() = runBlocking<Unit> {
        hilt.inject(); epochs.connect()
        // Unlike login(), do not call fixture.current(): the test must own the FIRST open.
        epochs.sessions.exclusive {
            epochs.tokens.clearTokens()
            epochs.tokens.saveLoginSession("synthetic-cold-access", "synthetic-cold-refresh", "member",
                "ac490000-0000-4000-8000-000000000001", false)
        }
        assertEquals(0, epochs.realRoomCreates)
    }
    @After fun finish() = runBlocking<Unit> {
        releases.forEach { it.complete(Unit) }; dispatchers.forEach { it.release() }
        withTimeout(5_000) { tasks.coroutineContext[Job]!!.cancelAndJoin() }
    }
    private fun release() = CompletableDeferred<Unit>().also { releases += it }
    private fun dispatcher() = ReturnGate().also { dispatchers += it }
    private suspend fun waitFor(predicate: () -> Boolean) = withTimeout(5_000) { while (!predicate()) delay(10) }
    private suspend fun cancelled(task: Deferred<*>) {
        val failure = try { task.await(); null } catch (error: CancellationException) { error }
        assertNotNull(failure); assertTrue(task.isCancelled)
    }
    private suspend fun unavailable() {
        val failure = try { epochs.host.withGraph { fail("Failed candidate admitted a business callback") }; null }
        catch (error: DatabaseRuntimeHost.Unavailable) { error }
        assertNotNull(failure)
    }

    @Test fun cancelledBeforeFirstNativeOpenClosesOnlyItsUnopenedCandidateAndNextCallerStartsFresh() = runBlocking<Unit> {
        val held = CompletableDeferred<Unit>(); val unblock = release()
        val account = tasks.async { epochs.sessions.exclusive { held.complete(Unit); unblock.await() } }
        held.await()
        val entered = AtomicBoolean()
        val first = tasks.async { epochs.host.withGraph { entered.set(true) } }
        try {
            waitFor { epochs.realRoomCreates == 1 }
            assertEquals(0, native.opens.get())
            first.cancel(); withTimeout(5_000) { cancelled(first) }
            assertFalse(entered.get()); assertEquals(0, native.opens.get())
            assertFalse(epochs.createdRoomInspection().single().isOpen)
        } finally { unblock.complete(Unit); withTimeout(5_000) { account.await() } }
        val fresh = withTimeout(30_000) { epochs.host.withGraph { it.database } }
        assertEquals(2, epochs.realRoomCreates); assertTrue(fresh.isOpen)
        assertNotSame(epochs.createdRoomInspection().first(), fresh)
    }

    @Test fun cancellationAfterRealControlReadAndActualEndDoesNotPoisonTheNextColdCaller() = runBlocking<Unit> {
        val entered = AtomicBoolean(); lateinit var first: Deferred<Unit>
        native.afterInitialRead.set { first.cancel() }
        first = tasks.async(start = CoroutineStart.LAZY) { epochs.host.withGraph { entered.set(true) } }
        first.start(); withTimeout(5_000) { cancelled(first) }
        assertTrue(native.initialStepped.get()); assertEquals(1, native.initialEnds.get()); assertFalse(entered.get())
        val old = epochs.createdRoomInspection().single()
        // The cancelled caller has ended, but Room may still own a registered internal
        // transaction. The NEXT caller must strictly drain/close before creating anything.
        val fresh = withTimeout(30_000) { epochs.host.withGraph { it.database } }
        assertFalse(old.isOpen); assertEquals(1, native.closes.get())
        assertNotSame(old, fresh); assertEquals(2, epochs.realRoomCreates); assertTrue(fresh.isOpen)
    }

    @Test fun cancellationAtPublishedIoReturnKeepsTheActiveGraphAndAnotherAcceptedLease() = runBlocking<Unit> {
        val gate = dispatcher(); native.afterInitialEnd.set(gate::arm)
        val originalCallback = AtomicBoolean()
        val first = tasks.async(gate) { epochs.host.withGraph { originalCallback.set(true) } }
        withTimeout(5_000) { gate.held.await() }
        val current = epochs.createdRoomInspection().single()
        val entered = CompletableDeferred<Unit>(); val unblock = release()
        val other = tasks.async { epochs.host.withGraph {
            assertSame(current, it.database); entered.complete(Unit); unblock.await(); assertTrue(it.database.isOpen)
        } }
        try {
            withTimeout(5_000) { entered.await() }
            first.cancel(); gate.release(); withTimeout(5_000) { cancelled(first) }
            assertFalse(originalCallback.get()); assertTrue(other.isActive); assertTrue(current.isOpen)
            assertEquals(0, native.closes.get()); assertEquals(1, epochs.realRoomCreates)
        } finally { gate.release(); unblock.complete(Unit); withTimeout(5_000) { other.await() } }
        epochs.host.withGraph { assertSame(current, it.database) }
    }

    private suspend fun busyCandidate(unknown: Boolean) {
        // Exact historical missing key, not an explicit malformed generation. The normal
        // encrypted access/owner and original native cancellation choreography stay intact.
        val legacyStore = DataStoreProvider.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val legacyKey = stringPreferencesKey("auth_session_generation")
        if (unknown) legacyStore.edit { it.remove(legacyKey) }
        val gate = dispatcher(); lateinit var first: Deferred<Unit>
        native.afterInitialEnd.set { gate.arm(); first.cancel() }
        first = tasks.async(gate, start = CoroutineStart.LAZY) { epochs.host.withGraph { fail("Cancelled initializer callback") } }
        first.start(); withTimeout(5_000) { gate.held.await() }
        val original = epochs.createdRoomInspection().single()
        val readerEntered = CompletableDeferred<Unit>(); val unblock = release()
        val reader = tasks.async { original.withTransaction {
            original.openHelper.writableDatabase.query("SELECT 'cold-cancel-held-reader'").use { assertTrue(it.moveToFirst()) }
            readerEntered.complete(Unit); unblock.await()
        } }
        try {
            withTimeout(5_000) { readerEntered.await() }
            gate.release(); withTimeout(5_000) { cancelled(first) }
            assertTrue(original.isOpen); assertEquals(0, native.closes.get())
            val owner = requireNotNull(CountTrustRuntime.forDatabase(original))
            assertFalse(owner.hasUnknownGraphBoundary())
            assertSame(CountTrustOpenHelperFactory.GraphDrain.Busy, owner.captureGraphDrain(original))
            // Observe the actual retry cleanup's IO return while the real reader is still
            // held. Neither the original callback nor a replacement graph may be admitted.
            val expiryGate = dispatcher().apply { arm() }
            val retryCallback = AtomicBoolean()
            val retry = tasks.async(expiryGate, start = CoroutineStart.UNDISPATCHED) {
                epochs.host.withGraph { retryCallback.set(true) }
            }
            try {
                withTimeout(5_000) { expiryGate.held.await() }
                assertTrue(retry.isActive); assertFalse(retryCallback.get())
                assertEquals(1, epochs.realRoomCreates); assertTrue(original.isOpen)
                expiryGate.release()
                val failure = try { withTimeout(5_000) { retry.await() }; null }
                catch (error: DatabaseRuntimeHost.Unavailable) { error }
                assertNotNull("The bounded Busy window must expire explicitly", failure)
                assertFalse(retryCallback.get()); assertEquals(1, epochs.realRoomCreates)
                assertTrue(original.isOpen); assertEquals(0, native.closes.get())
            } finally { expiryGate.release(); withTimeout(5_000) { retry.cancelAndJoin() } }

            // A second caller can cancel its own wait. It must not cancel the original
            // reader, close its native handle, erase Failed, or execute the user's callback.
            val cancelGate = dispatcher().apply { arm() }
            val cancelCallback = AtomicBoolean()
            val waiting = tasks.async(cancelGate, start = CoroutineStart.UNDISPATCHED) {
                epochs.host.withGraph { cancelCallback.set(true) }
            }
            try {
                withTimeout(5_000) { cancelGate.held.await() }
                assertTrue(waiting.isActive); assertFalse(cancelCallback.get())
                waiting.cancel(); cancelGate.release()
                withTimeout(5_000) { cancelled(waiting) }
                assertTrue(reader.isActive); assertTrue(original.isOpen)
                assertFalse(cancelCallback.get()); assertEquals(1, epochs.realRoomCreates)
                assertEquals(0, native.closes.get())
            } finally { cancelGate.release(); withTimeout(5_000) { waiting.cancelAndJoin() } }
            native.loseHeldEnd.set(unknown)
        } finally { gate.release(); unblock.complete(Unit) }
        val result = try { withTimeout(5_000) { reader.await() }; null } catch (error: IllegalStateException) { error }
        if (unknown) {
            assertEquals("COLD_TEST_ACTUAL_END_RESPONSE_LOST", result?.message)
            assertEquals(1, native.lostEnds.get())
            assertTrue(CountTrustRetirementReason.UNKNOWN_END in requireNotNull(CountTrustRuntime.forDatabase(original)).retirementReasons)
            unavailable(); unavailable()
            assertFalse("Unknown native END must never issue a local generation",
                legacyStore.data.first().asMap().keys.any { it.name == legacyKey.name })
            assertTrue(original.isOpen); assertEquals(1, epochs.realRoomCreates); assertEquals(0, native.closes.get())
        } else {
            assertNull(result)
            val fresh = withTimeout(30_000) { epochs.host.withGraph { it.database } }
            assertNotSame(original, fresh); assertFalse(original.isOpen); assertEquals(2, epochs.realRoomCreates)
            assertEquals(1, native.closes.get())
        }
    }
    @Test fun onlyBusyCancelledInitializationCanCloseAfterItsActualReaderEndAndRetry() = runBlocking<Unit> { busyCandidate(false) }
    @Test fun busyCancelledInitializationBecomingUnknownEndNeverCreatesANewGraph() = runBlocking<Unit> { busyCandidate(true) }

    @Test fun sameNativeConfigurationIsUsedAgainAfterActualClearCloseAndNewOwner() = runBlocking<Unit> {
        epochs.host.withGraph { assertTrue(it.database.isOpen) }
        assertEquals(1, native.opens.get())
        val before = native.controlSteps.get()
        val (old, fresh) = epochs.clearAndAwaitReplacement()
        assertFalse(old.isOpen); assertTrue(fresh.isOpen)
        assertEquals(2, native.opens.get()); assertEquals(2, epochs.realRoomCreates)
        assertTrue("Second actual native connection still carries the configured control-read observer", native.controlSteps.get() > before)
        assertEquals(1, native.closes.get())
    }

    /** Holds only the initializer's dispatcher RETURN, never SQLite or the account lock. */
    private class ReturnGate : CoroutineDispatcher() {
        private val armed = AtomicBoolean()
        private val opened = AtomicBoolean()
        private val queue = ConcurrentLinkedQueue<Pair<CoroutineContext, Runnable>>()
        val held = CompletableDeferred<Unit>()
        fun arm() { armed.set(true) }
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (armed.get() && !opened.get()) {
                queue.add(context to block); held.complete(Unit)
                if (opened.get()) drain()
            } else Dispatchers.Default.dispatch(context, block)
        }
        private fun drain() { while (true) { val next = queue.poll() ?: return; Dispatchers.Default.dispatch(next.first, next.second) } }
        fun release() { opened.set(true); drain() }
    }
    private class NativeInitializationProbe : SupportSQLiteOpenHelper.Factory {
        val opens = AtomicInteger(); val closes = AtomicInteger(); val controlSteps = AtomicInteger()
        val initialStepped = AtomicBoolean(); val initialEnds = AtomicInteger(); val lostEnds = AtomicInteger()
        val afterInitialRead = AtomicReference<(() -> Unit)?>(); val afterInitialEnd = AtomicReference<(() -> Unit)?>()
        val loseHeldEnd = AtomicBoolean()
        private val initialThread = AtomicReference<Thread?>(); private val heldThread = AtomicReference<Thread?>()
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            var rawOwner: SupportSQLiteDatabase? = null; var wrapper: SupportSQLiteDatabase? = null
            val opened = AtomicBoolean(); val monitor = Any()
            fun cursor(sql: String, raw: SupportSQLiteDatabase, value: Cursor): Cursor {
                if (sql == "SELECT 'cold-cancel-held-reader'") heldThread.set(Thread.currentThread())
                if (!opened.get() || !raw.inTransaction() || !Regex("(?i)\\bFROM\\s+(?:main\\.)?count_trust_control\\b").containsMatchIn(sql)) return value
                val stepped = AtomicBoolean()
                return object : CursorWrapper(value) {
                    private fun observed(found: Boolean): Boolean {
                        if (stepped.compareAndSet(false, true)) {
                            assertTrue("Actual bounded control SELECT must have its row", found); controlSteps.incrementAndGet()
                            if (initialStepped.compareAndSet(false, true)) {
                                initialThread.set(Thread.currentThread()); afterInitialRead.getAndSet(null)?.invoke()
                            }
                        }
                        return found
                    }
                    override fun moveToFirst() = observed(value.moveToFirst())
                    override fun moveToNext() = observed(value.moveToNext())
                    override fun moveToPosition(position: Int) = observed(value.moveToPosition(position))
                }
            }
            fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
                if (rawOwner !== raw) {
                    rawOwner = raw
                    wrapper = object : SupportSQLiteDatabase by raw {
                        override fun query(query: String) = cursor(query, raw, raw.query(query))
                        override fun query(query: String, bindArgs: Array<out Any?>) = cursor(query, raw, raw.query(query, bindArgs))
                        override fun query(query: SupportSQLiteQuery) = cursor(query.sql, raw, raw.query(query))
                        override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?) = cursor(query.sql, raw, raw.query(query, cancellationSignal))
                        override fun endTransaction() {
                            raw.endTransaction()
                            if (initialThread.compareAndSet(Thread.currentThread(), null)) {
                                initialEnds.incrementAndGet(); afterInitialEnd.getAndSet(null)?.invoke()
                            }
                            if (heldThread.compareAndSet(Thread.currentThread(), null) && loseHeldEnd.getAndSet(false)) {
                                lostEnds.incrementAndGet(); error("COLD_TEST_ACTUAL_END_RESPONSE_LOST")
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
                override fun onOpen(db: SupportSQLiteDatabase) { parent.onOpen(wrap(db)); opened.set(true); opens.incrementAndGet() }
                override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
            }
            val actual = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : SupportSQLiteOpenHelper by actual {
                override val readableDatabase get() = wrap(actual.readableDatabase)
                override val writableDatabase get() = wrap(actual.writableDatabase)
                override fun close() { actual.close(); closes.incrementAndGet() }
            }
        }
    }
}
