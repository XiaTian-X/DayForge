package com.dayforge.data.repository

import android.database.sqlite.SQLiteTransactionListener
import android.util.Log
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.domain.model.*
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual validator, authentication, private Room and original native END. The delegate
 * delays an early close request until END is released; it never closes a held connection.
 * The final ordering assertion is the red oracle, not any timeout or connection damage. */
@RunWith(AndroidJUnit4::class)
class NextReplicaCandidateLifecycleTest : NextCoreRequestFixture() {
    private fun canonical(kind: String, identity: String, body: JsonObject) = SyncV2Change(0, kind,
        identity, "upsert", 1, JsonObject(body + mapOf("public_id" to JsonPrimitive(identity),
            "revision" to JsonPrimitive(1), "created_at" to JsonPrimitive(time),
            "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)

    private suspend fun response(): ReplicaRecoveryPreviewResponse {
        val snapshot = RoundSyncBootstrapResponse(listOf(
            canonical("plan_node", habit.uuid, NextStructureMapper.writePlan(habit)),
            canonical("plan_node", timerHabit.uuid, NextStructureMapper.writePlan(timerHabit)),
            canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric))), 20, time,
            emptyList(), 1, listOf(habit.uuid, timerHabit.uuid).map { activity ->
                val head = initialChallengeRoundHead(activity)
                ChallengeCheckpoint(head, listOf(ChallengeRoundRecord(head, null, null, null)))
            }, emptyList())
        return ReplicaRecoveryPreviewResponse(1, 5, id(1), id(2), id(99),
            RecoveryDeviceProof(id(4), tokens.getOrCreateInstallationId(), "android", "interactive",
                null, null, false, false, 1, listOf("sync.read", "facts.append"), time), snapshot, null)
    }

    @Test(timeout = 150_000)
    fun cancelledValidatorEndsItsOriginalTransactionBeforeRequestingPrivateRoomClose() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        register()
        val value = response(); val authority = access(); val activeDatabase = db
        val before = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().let { it.contents to it.hash } }
        val preferencesBefore = dataStore.data.first()
        val trace = EndGateFactory()
        val published = AtomicReference<ValidatedReplicaCandidate?>()
        var work: Deferred<Unit>? = null
        var roomJobs: List<Job> = emptyList()
        var primary: Throwable? = null
        try {
            supervisorScope {
                val running = async(Dispatchers.IO + trace.jobContext) {
                    published.set(NextReplicaCandidateValidator(app, tokens, sessions, preferences, trace)
                        .validate(authority, value.device.installationId, value))
                }
                work = running
                withTimeout(15_000) {
                    select<Unit> {
                        trace.endHeld.onAwait { }
                        running.onAwait { error("CANDIDATE_TEST_RETURNED_WITHOUT_HELD_END") }
                    }
                }
                check(trace.actualStateInsert.get() == 1)
                check(trace.endThread.get() != null)
                // Only the original native thread checks transaction state at the gate.
                fun descendants(job: Job): List<Job> = job.children.toList().flatMap { listOf(it) + descendants(it) }
                roomJobs = descendants(running)
                check(roomJobs.any { it === trace.roomOwner.get() })
                running.cancel(CancellationException("CANDIDATE_TEST_CALLER_CANCELLED"))
                assertTrue(running.isCancelled)
                try {
                    // A positive CLOSE_ENTER before release proves the old ordering. Absence
                    // within this bounded window is not a red result; the full tail decides.
                    withTimeoutOrNull(5_000) { trace.closeEntered.await() }
                } finally {
                    withContext(NonCancellable) {
                        trace.releaseEnd.countDown()
                        trace.awaitOwnedEnd()
                        trace.releaseClose.countDown()
                        withTimeout(5_000) { running.join() }
                    }
                }
                assertTrue(running.isCompleted && running.isCancelled)
            }
            assertTrue(roomJobs.all { it.isCompleted })
            assertTrue(requireNotNull(trace.roomOwner.get()).isCompleted)
            assertTrue(trace.roomOwnerCompletionOrder.get() > trace.endReturnOrder.get())
            assertNull("Cancellation cannot deliver a trusted candidate", published.get())
            assertEquals(1, trace.actualStateInsert.get())
            assertEquals(1, trace.heldEnds.get())
            assertEquals(1, trace.actualEnds.get())
            assertTrue(trace.endHeldOrder.get() > 0 && trace.endReturnOrder.get() > trace.endHeldOrder.get())
            assertEquals(1, trace.rawCloseCalls.get()); assertEquals(1, trace.helperCloseCalls.get())
            assertTrue(trace.rawCloseReturned.get() > trace.roomOwnerCompletionOrder.get())
            assertTrue(trace.helperCloseReturned.get() > trace.rawCloseReturned.get())
            assertFalse(requireNotNull(trace.original.get()).isOpen)
            assertSame(activeDatabase, db); assertSame(activeDatabase, HabitDatabaseProvider.getInstance(app))
            assertEquals(preferencesBefore, dataStore.data.first()); assertEquals(authority, access())
            val after = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().let { it.contents to it.hash } }
            assertEquals(before, after)
            trace.event("OWNER_COMPLETED")
            assertTrue("Private Room close was requested before its original native END returned",
                trace.firstCloseOrder.get() > trace.endReturnOrder.get())
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            withContext(NonCancellable) {
                try {
                    trace.releaseEnd.countDown()
                    if (trace.endHeld.isCompleted) trace.awaitOwnedEnd()
                    trace.releaseClose.countDown()
                    work?.let { withTimeout(5_000) { it.cancelAndJoin() }; check(it.isCompleted) }
                    check(roomJobs.all { it.isCompleted })
                    if (trace.original.get() != null) check(!requireNotNull(trace.original.get()).isOpen)
                } catch (cleanup: Throwable) {
                    PhysicalDatabaseRule.preserveProcessCleanupFailure(cleanup)
                    if (primary == null) throw cleanup else if (primary !== cleanup) primary!!.addSuppressed(cleanup)
                }
            }
        }
    }

    /** Observe the original Job installed on the actual Room thread; never replace it. */
    private class ObservedJob : ThreadContextElement<Job?>, AbstractCoroutineContextElement(Key) {
        val current = ThreadLocal<Job?>()
        override fun updateThreadContext(context: CoroutineContext): Job? = current.get().also {
            current.set(context[Job])
        }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Job?) {
            if (oldState == null) current.remove() else current.set(oldState)
        }
        companion object Key : CoroutineContext.Key<ObservedJob>
    }

    private class EndGateFactory : SupportSQLiteOpenHelper.Factory {
        private val sequence = AtomicInteger()
        val jobContext = ObservedJob()
        val roomOwner = AtomicReference<Job?>()
        val roomOwnerCompletionOrder = AtomicInteger()
        val roomOwnerCompleted = CompletableDeferred<Unit>()
        suspend fun awaitOwnedEnd() = withTimeout(5_000) {
            endReturned.await()
            requireNotNull(roomOwner.get()).join()
            roomOwnerCompleted.await()
            check(requireNotNull(roomOwner.get()).isCompleted)
        }
        val original = AtomicReference<SupportSQLiteDatabase?>()
        val endThread = AtomicReference<Thread?>()
        val actualStateInsert = AtomicInteger()
        val heldEnds = AtomicInteger(); val actualEnds = AtomicInteger()
        val endHeld = CompletableDeferred<Unit>(); val endReturned = CompletableDeferred<Unit>()
        val closeEntered = CompletableDeferred<Unit>()
        val releaseEnd = CountDownLatch(1); val releaseClose = CountDownLatch(1)
        val endHeldOrder = AtomicInteger(); val endReturnOrder = AtomicInteger()
        val firstCloseOrder = AtomicInteger(); val rawCloseCalls = AtomicInteger(); val helperCloseCalls = AtomicInteger()
        val rawCloseReturned = AtomicInteger(); val helperCloseReturned = AtomicInteger()
        private val selected = ThreadLocal<Boolean>()
        private val depth = ThreadLocal.withInitial { 0 }
        private fun currentDepth(): Int = requireNotNull(depth.get())
        private fun begin(block: () -> Unit) {
            block()
            depth.set(Math.addExact(currentDepth(), 1))
        }
        fun event(name: String): Int = sequence.incrementAndGet().also {
            Log.i("ReplicaCandidateLifecycle", "event=$name order=$it")
        }
        private fun closeRequested(kind: String) {
            val order = event(kind + "_CLOSE_ENTER")
            firstCloseOrder.compareAndSet(0, order)
            closeEntered.complete(Unit)
            // No held transaction means an original success/error path must close normally.
            // Once held, wait for the exact Room child as well as native END. Native END's
            // signal precedes CountTrust's bookkeeping; it alone cannot release close.
            if (endHeldOrder.get() > 0)
                check(releaseClose.await(15, TimeUnit.SECONDS)) { "CANDIDATE_TEST_CLOSE_GATE_NOT_RELEASED" }
        }
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            check(configuration.name == null)
            var adapted: SupportSQLiteDatabase? = null
            val monitor = Any()
            fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
                val previous = original.get()
                check(previous == null || previous === raw)
                if (previous === raw) return@synchronized requireNotNull(adapted)
                original.set(raw)
                object : SupportSQLiteDatabase by raw {
                    override fun beginTransaction() = begin { raw.beginTransaction() }
                    override fun beginTransactionNonExclusive() = begin { raw.beginTransactionNonExclusive() }
                    override fun beginTransactionReadOnly() = begin { raw.beginTransactionReadOnly() }
                    override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) =
                        begin { raw.beginTransactionWithListener(transactionListener) }
                    override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) =
                        begin { raw.beginTransactionWithListenerNonExclusive(transactionListener) }
                    override fun beginTransactionWithListenerReadOnly(transactionListener: SQLiteTransactionListener) =
                        begin { raw.beginTransactionWithListenerReadOnly(transactionListener) }
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val delegate = raw.compileStatement(sql)
                        val target = sql.replace("`", "").trim().uppercase(Locale.ROOT)
                            .startsWith("INSERT OR ABORT INTO NEXT_SYNC_STATE ")
                        if (!target) return delegate
                        fun afterSuccessfulInsert() {
                            check(raw.inTransaction() && currentDepth() > 0) { "CANDIDATE_TEST_UNTRACKED_BUSINESS_TRANSACTION" }
                            check(actualStateInsert.incrementAndGet() == 1)
                            check(selected.get() != true); selected.set(true)
                        }
                        return object : SupportSQLiteStatement by delegate {
                            // Room 2.8.5 EntityInsertAdapter uses SQLiteStatement.step(), whose
                            // framework delegate calls execute(); retain the old native API too.
                            override fun execute() { delegate.execute(); afterSuccessfulInsert() }
                            override fun executeInsert(): Long = delegate.executeInsert().also { afterSuccessfulInsert() }
                        }
                    }
                    override fun endTransaction() {
                        val enteredDepth = currentDepth()
                        val armed = selected.get() == true
                        check(!armed || enteredDepth > 0) { "CANDIDATE_TEST_UNTRACKED_ARMED_END" }
                        val target = armed && enteredDepth == 1
                        if (target) {
                            check(raw.inTransaction()); check(heldEnds.incrementAndGet() == 1)
                            check(endThread.compareAndSet(null, Thread.currentThread()))
                            val originalJob = requireNotNull(jobContext.current.get())
                            check(roomOwner.compareAndSet(null, originalJob))
                            originalJob.invokeOnCompletion {
                                roomOwnerCompletionOrder.set(event("ROOM_OWNER_COMPLETED"))
                                roomOwnerCompleted.complete(Unit)
                            }
                            endHeldOrder.set(event("END_HELD")); endHeld.complete(Unit)
                            check(releaseEnd.await(15, TimeUnit.SECONDS)) { "CANDIDATE_TEST_END_GATE_NOT_RELEASED" }
                        }
                        raw.endTransaction()
                        // Room's DAO transaction may be nested inside the validator transaction.
                        // Only tracked BEGINs are decremented; schema callbacks can predate them.
                        if (enteredDepth > 0) depth.set(enteredDepth - 1)
                        if (target) {
                            check(Thread.currentThread() === endThread.get() && !raw.inTransaction())
                            selected.remove(); check(actualEnds.incrementAndGet() == 1)
                            endReturnOrder.set(event("END_RETURN")); endReturned.complete(Unit)
                        }
                    }
                    override fun close() {
                        rawCloseCalls.incrementAndGet(); closeRequested("NATIVE")
                        raw.close(); rawCloseReturned.set(event("NATIVE_CLOSE_RETURN"))
                    }
                }.also { adapted = it }
            }
            val parent = configuration.callback
            val callback = object : SupportSQLiteOpenHelper.Callback(parent.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
                override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    parent.onUpgrade(wrap(db), oldVersion, newVersion)
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    parent.onDowngrade(wrap(db), oldVersion, newVersion)
                override fun onOpen(db: SupportSQLiteDatabase) = parent.onOpen(wrap(db))
                override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
            }
            val delegate = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : SupportSQLiteOpenHelper by delegate {
                override val readableDatabase get() = wrap(delegate.readableDatabase)
                override val writableDatabase get() = wrap(delegate.writableDatabase)
                override fun close() {
                    helperCloseCalls.incrementAndGet(); closeRequested("HELPER")
                    delegate.close(); helperCloseReturned.set(event("HELPER_CLOSE_RETURN"))
                }
            }
        }
    }
}
