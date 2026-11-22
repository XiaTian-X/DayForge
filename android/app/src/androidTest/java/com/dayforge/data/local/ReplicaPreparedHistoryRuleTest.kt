package com.dayforge.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.repository.NextCoreRequestFixture
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.internal.runners.statements.RunAfters
import org.junit.internal.runners.statements.RunBefores
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.MultipleFailureException
import org.junit.runners.model.Statement
import org.junit.runners.model.TestClass

/** Real inherited setup/cleanup, Room, DataStore and original Jobs. Negative fences are
 * private and never reset. Only proven joins permit explicit outer disposal of a failed
 * fixture; a failed disposal poisons the original process fence and retains its files. */
@RunWith(AndroidJUnit4::class)
class ReplicaPreparedHistoryRuleTest {
    @Test(timeout = 150_000) fun interruptedInheritedSetupWithoutPreparationJobBlocksAllInheritedCleanup() {
        interruptedOwner(Mode.SETUP_INTERRUPT)
    }

    @Test(timeout = 150_000) fun interruptedNonCancellablePreparationCannotEnterBusinessOrDeleteItsFiles() {
        interruptedOwner(Mode.PREPARE_INTERRUPT)
    }

    private fun interruptedOwner(mode: Mode) {
        val fixture = Fixture(mode, PhysicalDatabaseRule.CleanupFence())
        val evaluation = Evaluation(fixture)
        var primary: Throwable? = null
        try {
            evaluation.start()
            assertTrue(fixture.held.await(30, TimeUnit.SECONDS))
            requireNotNull(fixture.heldThread.get()).interrupt()
            evaluation.awaitReturned()
            val actual = requireNotNull(fixture.heldOwner.get())
            assertFalse(actual.isCompleted)
            val chain = requireNotNull(fixture.chainFailure.get())
            assertTrue(chain is MultipleFailureException)
            val inheritedFailures = (chain as MultipleFailureException).failures
            assertEquals(3, inheritedFailures.size)
            val originalInterrupt = inheritedFailures[0]
            assertTrue(originalInterrupt is InterruptedException)
            val inheritedRefusal = inheritedFailures[2]
            assertEquals("PHYSICAL_DATABASE_CLEANUP_BLOCKED", inheritedRefusal.message)
            assertTrue(inheritedRefusal.stackTrace.any {
                it.className.startsWith(NextCoreRequestFixture::class.java.name + "\$cleanup")
            })
            // JUnit 4.13.2 ExternalResource preserves BOTH base and after failures.
            // Observe this boundary separately: the outer Stages watchdog can instead
            // deliver its already-recorded original preparation InterruptedException.
            val physicalTree = requireNotNull(evaluation.physicalFailure.get())
            assertTrue(physicalTree is MultipleFailureException)
            val physicalFailures = (physicalTree as MultipleFailureException).failures
            assertEquals(2, physicalFailures.size)
            assertSame(chain, physicalFailures[0])
            val physicalRefusal = physicalFailures.single { error ->
                error.message == "PHYSICAL_DATABASE_CLEANUP_BLOCKED" && error.stackTrace.any {
                    it.className.startsWith(PhysicalDatabaseRule::class.java.name + "\$after")
                }
            }
            assertSame(physicalFailures[1], physicalRefusal)
            if (mode == Mode.SETUP_INTERRUPT) {
                assertEquals("REPLICA_SETUP_COMPLETION_UNPROVEN", inheritedFailures[1].message)
                assertSame(inheritedFailures[1], inheritedRefusal.cause)
                assertSame(physicalTree, evaluation.failure.get())
            } else {
                assertSame(originalInterrupt, inheritedFailures[1])
                assertSame(originalInterrupt, inheritedRefusal.cause)
                assertSame(originalInterrupt, evaluation.failure.get())
            }
            assertEquals(1, fixture.afterCalls.get())
            assertEquals(0, fixture.businessCalls.get())
            assertFalse(fixture.storage.usesRecoveryCleanup)
            if (mode == Mode.SETUP_INTERRUPT) assertNull(fixture.history.actualPreparationOwner)
            else assertSame(actual, fixture.history.actualPreparationOwner)
            fixture.assertRetained()
            val blocked = runCatching { fixture.fence.requireClear() }.exceptionOrNull()
            assertEquals("PHYSICAL_DATABASE_CLEANUP_BLOCKED", blocked?.message)
            assertNotNull(runCatching { fixture.history.business { fixture.businessCalls.incrementAndGet() } }.exceptionOrNull())
            assertEquals(0, fixture.businessCalls.get())
            val replacement = PhysicalDatabaseRule(fixture.fence)
            assertEquals("PHYSICAL_DATABASE_CLEANUP_BLOCKED", runCatching {
                replacement.apply(object : Statement() { override fun evaluate() { error("MUST_NOT_ENTER") } }, description()).evaluate()
            }.exceptionOrNull()?.message)
            fixture.assertRetained()
            fixture.certifyRefusalBeforeClose(physicalRefusal)
        } catch (error: Throwable) { primary = error; throw error }
        finally { dispose(primary, fixture, evaluation) }
    }

    @Test(timeout = 150_000) fun normalBusinessFailurePreservesOriginalExceptionAndDrainsRealResources() {
        val fixture = Fixture(Mode.BUSINESS_FAILURE, PhysicalDatabaseRule.CleanupFence())
        val evaluation = Evaluation(fixture)
        var primary: Throwable? = null
        try {
            evaluation.start(); evaluation.awaitReturned()
            assertSame(fixture.expected, evaluation.failure.get())
            assertSame(fixture.expected, fixture.chainFailure.get())
            assertEquals(1, fixture.businessCalls.get()); assertEquals(1, fixture.afterCalls.get())
            assertTrue(requireNotNull(fixture.businessOwner.get()).isCompleted)
            assertTrue(requireNotNull(fixture.history.actualPreparationOwner).isCompleted)
            assertNull(fixture.history.fallbackOwner)
            assertTrue(fixture.storage.usesRecoveryCleanup)
            fixture.fence.requireClear()
            fixture.assertNormallyDisposed()
        } catch (error: Throwable) { primary = error; throw error }
        finally { dispose(primary, fixture, evaluation) }
    }

    @Test(timeout = 150_000) fun completedPreparationFailureRegistersThatJobAndJoinsActualDataStoreBeforeDeletion() {
        val fixture = Fixture(Mode.PREPARE_FAILURE, PhysicalDatabaseRule.CleanupFence())
        val evaluation = Evaluation(fixture)
        var primary: Throwable? = null
        try {
            evaluation.start()
            assertTrue(fixture.actorCancelling.await(30, TimeUnit.SECONDS))
            val actual = requireNotNull(fixture.history.actualPreparationOwner)
            assertTrue(actual.isCompleted); assertSame(actual, fixture.history.fallbackOwner)
            assertEquals(0, fixture.businessCalls.get()); assertNull(fixture.businessOwner.get())
            assertTrue(fixture.storage.usesRecoveryCleanup)
            assertFalse(requireNotNull(fixture.scope.coroutineContext[Job]).isCompleted)
            assertEquals(1L, evaluation.returned.count)
            fixture.assertRetained()
            // Release the actual child of the real DataStore SupervisorJob, inside the
            // original NextCoreRequestFixture withTimeout(5000) cancelAndJoin path.
            fixture.releaseActor.complete(Unit)
            evaluation.awaitReturned()
            assertSame(fixture.expected, evaluation.failure.get())
            assertSame(fixture.expected, fixture.chainFailure.get())
            assertEquals(1, fixture.afterCalls.get()); fixture.fence.requireClear()
            fixture.assertNormallyDisposed()
        } catch (error: Throwable) { primary = error; throw error }
        finally { dispose(primary, fixture, evaluation) }
    }

    internal enum class Mode { SETUP_INTERRUPT, PREPARE_INTERRUPT, BUSINESS_FAILURE, PREPARE_FAILURE }

    // Public JVM class/methods let the genuine JUnit FrameworkMethod invoke inherited
    // Before/After. This class has no tests and is never independently discovered.
    class Fixture internal constructor(
        private val mode: Mode,
        internal val fence: PhysicalDatabaseRule.CleanupFence
    ) : NextCoreRequestFixture(PhysicalDatabaseRule(fence)) {
        internal val history = ReplicaPreparedHistoryRule(storage, fence::preserve)
        internal val expected = AssertionError("original-owned-failure")
        internal val chainFailure = AtomicReference<Throwable?>()
        internal val businessOwner = AtomicReference<Job?>()
        internal val heldOwner = AtomicReference<Job?>()
        internal val heldThread = AtomicReference<Thread?>()
        internal val held = CountDownLatch(1)
        internal val release = CompletableDeferred<Unit>()
        internal val actorCancelling = CountDownLatch(1)
        internal val releaseActor = CompletableDeferred<Unit>()
        internal val afterCalls = AtomicInteger()
        internal val businessCalls = AtomicInteger()
        private val firstPersistence = AtomicBoolean(true)
        private var resourcesObserved = false
        private var firstExplicitDisposalAllowed = false
        private lateinit var originalNative: SupportSQLiteDatabase
        private lateinit var preferenceImage: ByteArray
        private val databaseFile get() = app.getDatabasePath("habit_database")

        override fun isolatedDataStore(data: DataStore<Preferences>): DataStore<Preferences> =
            object : DataStore<Preferences> by data {
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    val saved = data.updateData(transform)
                    if (mode == Mode.SETUP_INTERRUPT && firstPersistence.compareAndSet(true, false)) {
                        observeResources()
                        // The actual inherited setup() is awaiting this original token
                        // persistence call. TokenManager uses withContext(NonCancellable),
                        // so this operation can survive interruption of setup's thread.
                        // Observe that real operation Job; never invent a completed setup.
                        holdOriginalOwner()
                    }
                    return saved
                }
            }

        private fun observeResources() {
            originalNative = db.openHelper.writableDatabase
            preferenceImage = File(directory, "auth.preferences_pb").readBytes()
            check(preferenceImage.isNotEmpty())
            resourcesObserved = true
        }

        private suspend fun holdOriginalOwner(): Nothing {
            heldOwner.set(requireNotNull(currentCoroutineContext()[Job]))
            heldThread.set(Thread.currentThread())
            CoroutineScope(currentCoroutineContext()).launch(Dispatchers.Default) {
                withContext(NonCancellable) { held.countDown(); release.await() }
            }
            awaitCancellation()
        }

        @Before fun prepareHistory() {
            observeResources()
            history.prepare {
                when (mode) {
                    Mode.SETUP_INTERRUPT -> error("INTERRUPTED_SETUP_MUST_NOT_RETURN")
                    Mode.PREPARE_INTERRUPT -> holdOriginalOwner()
                    Mode.PREPARE_FAILURE -> {
                        val started = CompletableDeferred<Unit>()
                        scope.launch {
                            started.complete(Unit)
                            try { awaitCancellation() }
                            finally { withContext(NonCancellable) { actorCancelling.countDown(); releaseActor.await() } }
                        }
                        started.await()
                        throw expected
                    }
                    Mode.BUSINESS_FAILURE -> Unit
                }
            }
        }

        internal fun business() = history.business {
            runBlocking<Unit> {
                val actual = requireNotNull(currentCoroutineContext()[Job])
                businessOwner.set(actual); storage.ownRecoveryMethod(actual)
                businessCalls.incrementAndGet()
                throw expected
            }
        }

        @After fun endBeforeInheritedCleanup() {
            try { afterCalls.incrementAndGet() }
            finally { history.beforeInheritedCleanup() }
        }

        internal fun assertRetained() {
            check(resourcesObserved)
            assertTrue(originalNative.isOpen); assertTrue(databaseFile.isFile)
            assertTrue(directory.isDirectory)
            assertArrayEquals(preferenceImage, File(directory, "auth.preferences_pb").readBytes())
            assertSame(db, HabitDatabaseProvider.getInstance(app))
        }

        internal fun assertNormallyDisposed() {
            assertTrue(resourcesObserved)
            assertTrue(requireNotNull(scope.coroutineContext[Job]).isCompleted)
            assertFalse(originalNative.isOpen); assertFalse(databaseFile.exists()); assertFalse(directory.exists())
        }

        internal fun certifyRefusalBeforeClose(physicalFailure: Throwable) {
            check(mode == Mode.SETUP_INTERRUPT || mode == Mode.PREPARE_INTERRUPT)
            check(!requireNotNull(heldOwner.get()).isCompleted)
            check(businessCalls.get() == 0 && afterCalls.get() == 1 && !storage.usesRecoveryCleanup)
            val inheritedRefusal = errors(requireNotNull(chainFailure.get())).first { error ->
                error.message == "PHYSICAL_DATABASE_CLEANUP_BLOCKED" && error.stackTrace.any {
                    it.className.startsWith(NextCoreRequestFixture::class.java.name + "\$cleanup")
                }
            }
            val fenceRefusal = runCatching { fence.requireClear() }.exceptionOrNull()
            check(physicalFailure.message == "PHYSICAL_DATABASE_CLEANUP_BLOCKED")
            check(physicalFailure.cause != null && physicalFailure.cause === inheritedRefusal.cause &&
                physicalFailure.cause === fenceRefusal?.cause)
            check(physicalFailure.stackTrace.any { it.className.startsWith(PhysicalDatabaseRule::class.java.name + "\$after") })
            // The SAME private fence already rejected inherited cleanup before Physical's
            // after() entered recoveryCleanup. Its first guard therefore refused before
            // any Host/native close, not an unknown result of closeRecoveryDatabase().
            assertRetained()
            firstExplicitDisposalAllowed = true
        }

        internal fun finishAfterEvaluation(evaluationFailure: Throwable?) {
            if (mode == Mode.BUSINESS_FAILURE || mode == Mode.PREPARE_FAILURE) {
                // Only the original cleanup may close these modes. A failed first close
                // or any unknown inherited cleanup is retained: never retry raw close,
                // clear the Provider or delete files in a finally to make it look ended.
                check(evaluationFailure === expected && chainFailure.get() === expected)
                fence.requireClear()
                check(requireNotNull(history.actualPreparationOwner).isCompleted)
                businessOwner.get()?.let { check(it.isCompleted) }
                assertNormallyDisposed()
                check(client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
                return
            }
            check(firstExplicitDisposalAllowed && resourcesObserved) { "PREPARED_FIXTURE_RESOURCE_OUTCOME_UNKNOWN" }
            runBlocking { withTimeout(5_000) {
                heldOwner.get()?.let { it.cancelAndJoin(); check(it.isCompleted) }
                history.actualPreparationOwner?.let { it.join(); check(it.isCompleted) }
                businessOwner.get()?.let { it.join(); check(it.isCompleted) }
                requireNotNull(scope.coroutineContext[Job]).cancelAndJoin()
            } }
            servers.forEach { it.close() }
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            check(client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
            db.close(); check(!originalNative.isOpen) // First explicit close only; no fallback helper close.
            HabitDatabaseProvider.clearInstanceForTesting()
            check(app.deleteDatabase("habit_database") || !databaseFile.exists())
            check(directory.deleteRecursively() || !directory.exists())
        }
    }

    private class Evaluation(private val fixture: Fixture) {
        val returned = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val physicalFailure = AtomicReference<Throwable?>()
        private val thread = Thread({
            try {
                val klass = TestClass(fixture.javaClass)
                val body = object : Statement() { override fun evaluate() { fixture.business() } }
                val before = RunBefores(body, klass.getAnnotatedMethods(Before::class.java), fixture)
                val after = RunAfters(before, klass.getAnnotatedMethods(After::class.java), fixture)
                val recorded = object : Statement() {
                    override fun evaluate() {
                        try { after.evaluate() } catch (error: Throwable) { fixture.chainFailure.set(error); throw error }
                    }
                }
                val physical = fixture.storage.apply(recorded, description())
                val recordedPhysical = object : Statement() {
                    override fun evaluate() {
                        try { physical.evaluate() } catch (error: Throwable) { physicalFailure.set(error); throw error }
                    }
                }
                fixture.history.apply(recordedPhysical, description()).evaluate()
            } catch (error: Throwable) { failure.set(error) }
            finally { returned.countDown() }
        }, "replica-prepared-seam-supervisor")
        fun start() = thread.start()
        fun awaitReturned() { check(returned.await(30, TimeUnit.SECONDS)); thread.join(5_000); check(!thread.isAlive) }
        fun finish() {
            if (thread.isAlive) thread.interrupt()
            thread.join(5_000); check(!thread.isAlive) { "PREPARED_SEAM_SUPERVISOR_NOT_JOINED" }
        }
    }

    companion object {
        private fun description() = Description.createTestDescription(ReplicaPreparedHistoryRuleTest::class.java,
            "ownedHistoricalInput", RetainedPreparedJourney())
        private fun errors(error: Throwable): List<Throwable> = listOf(error) +
            (if (error is MultipleFailureException) error.failures.flatMap(::errors) else emptyList()) +
            error.suppressed.flatMap(::errors) + (error.cause?.let(::errors) ?: emptyList())
        private fun dispose(primary: Throwable?, fixture: Fixture, evaluation: Evaluation) {
            try {
                fixture.release.complete(Unit); fixture.releaseActor.complete(Unit)
                evaluation.finish()
                fixture.finishAfterEvaluation(evaluation.failure.get())
            } catch (error: Throwable) {
                PhysicalDatabaseRule.preserveProcessCleanupFailure(error)
                if (primary == null) throw error
                if (primary !== error) primary.addSuppressed(error)
            }
        }
    }
}
