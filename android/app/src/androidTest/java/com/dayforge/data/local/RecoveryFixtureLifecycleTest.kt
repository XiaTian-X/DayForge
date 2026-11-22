package com.dayforge.data.local

import android.content.Context
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import android.database.Cursor
import android.os.CancellationSignal
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.repository.NextObjectEditorFixture
import com.dayforge.data.repository.NextCoreRequestFixture
import com.dayforge.data.repository.NextReplicaDestinationFixture
import com.dayforge.data.repository.ReplicaPresentationRepository
import com.dayforge.domain.service.ReplicaPresentationController
import com.dayforge.domain.service.ReplicaPresentationState
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.internal.runners.statements.RunAfters
import org.junit.internal.runners.statements.FailOnTimeout
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.MultipleFailureException
import org.junit.runners.model.Statement
import org.junit.runners.model.TestClass
import org.junit.runners.model.TestTimedOutException

/** Real Room/native END, original method Jobs and actual files. Each negative case has its
 * own refusal fence; the production-default process fence is never reset by these tests.
 * Explicit outer disposal follows release + exact Job/END and cannot certify failed cleanup.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryFixtureLifecycleTest {
    @Test fun completedRootStillJoinsTheRegisteredRealControllerBeforeClosingOrDeleting() {
        val fixture = Fixture()
        val destination = object : NextReplicaDestinationFixture(fixture.rule) {}
        var controller: ReplicaPresentationController? = null
        var method: MethodThread? = null
        var cleaning: Thread? = null
        val cleaned = CountDownLatch(1)
        val cleanupFailure = AtomicReference<Throwable?>()
        var setup = false
        var primary: Throwable? = null
        try {
            fixture.evaluate(outer = destination.widgetRefresh) {
                destination.setup(); destination.appearanceSetup(); setup = true
                method = MethodThread(fixture.rule, register = true) {
                    controller = ReplicaPresentationController(destination.tokens,
                        ReplicaPresentationRepository(destination.db, destination.tokens, destination.sessions))
                    destination.ownRecoveryController(requireNotNull(controller))
                    withTimeout(5_000) { requireNotNull(controller).state.first { it is ReplicaPresentationState.Open } }
                }
                requireNotNull(method).joinMethodAndOwner(); assertNull(requireNotNull(method).failure.get())
                assertTrue(requireNotNull(method!!.owner.get()).isCompleted)
                val actualController = requireNotNull(controller)
                val permit = (actualController.state.value as ReplicaPresentationState.Open).permit
                fixture.native.armControllerRead()
                actualController.retry()
                assertTrue(fixture.native.controllerEntered.await(5, TimeUnit.SECONDS))
                val originalIcon = destination.iconDb.openHelper.writableDatabase
                cleaning = Thread({
                    try { destination.appearanceCleanup() } catch (error: Throwable) { cleanupFailure.set(error) }
                    finally { cleaned.countDown() }
                }, "recovery-controller-cleanup").also { it.start() }
                // close() revoked the real permit, while its independent actual Room read is
                // still inside the native query. The completed method Job cannot join this owner.
                runBlocking { withTimeout(5_000) {
                    actualController.state.first { it is ReplicaPresentationState.Checking }
                } }
                runBlocking { awaitActualControllerJoin(requireNotNull(cleaning), cleaned) }
                assertFalse(permit.canRender()); assertEquals(1L, cleaned.count)
                assertTrue(originalIcon.isOpen); assertTrue(destination.directory.isDirectory)
                assertEquals(0, fixture.native.nativeCloses.get()); assertEquals(1L, fixture.native.ended.count)
                fixture.native.releaseControllerRead()
                assertTrue(cleaned.await(5, TimeUnit.SECONDS)); cleaning!!.join(5_000)
                assertFalse(cleaning!!.isAlive); assertNull(cleanupFailure.get())
                runBlocking { withTimeout(5_000) { actualController.awaitClosed() } }
                assertTrue(fixture.native.ended.await(5, TimeUnit.SECONDS)); assertFalse(originalIcon.isOpen)
                destination.cleanup()
                assertTrue(destination.directory.isDirectory) // Deferred until main native close.
                assertSame(fixture.rule.database, HabitDatabaseProvider.getInstance(fixture.context))
            }
            assertFalse(fixture.native.originalIsOpen()); assertFalse(fixture.databaseFile.exists())
            assertFalse(destination.directory.exists())
            assertFalse(fixture.context.getDatabasePath(com.dayforge.data.appearance.AccountIconDatabase.NAME).exists())
            assertEquals(1, fixture.native.nativeCloses.get())
            assertTrue(fixture.native.events.indexOf("END") < fixture.native.events.indexOf("CLOSE"))
            val originalCalls = fixture.native.queries.get()
            controller!!.retry()
            runBlocking { withTimeout(5_000) { controller!!.awaitClosed() } }
            assertEquals(originalCalls, fixture.native.queries.get())
            assertTrue(controller!!.state.value is ReplicaPresentationState.Checking)
            val replacement = HabitDatabaseProvider.getInstance(fixture.context)
            assertNotSame(fixture.rule.database, replacement)
            replacement.close(); HabitDatabaseProvider.clearInstanceForTesting()
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally { preserveOnFailedDisposal(primary) {
            fixture.native.releaseControllerRead()
            controller?.let { it.close(); runBlocking { withTimeout(5_000) { it.awaitClosed() } } }
            method?.joinMethodAndOwner(); cleaning?.join(5_000); check(cleaning?.isAlive != true)
            if (setup) {
                runBlocking { withTimeout(5_000) { destination.scope.coroutineContext[Job]!!.cancelAndJoin() } }
                destination.iconDb.openHelper.close()
                destination.client.connectionPool.evictAll(); destination.client.dispatcher.executorService.shutdown()
            }
            fixture.disposeAfterOwnedEnd {
                if (setup) {
                    check(fixture.context.deleteDatabase(com.dayforge.data.appearance.AccountIconDatabase.NAME) ||
                        !fixture.context.getDatabasePath(com.dayforge.data.appearance.AccountIconDatabase.NAME).exists())
                    check(destination.directory.deleteRecursively())
                }
            }
        } }
    }

    @Test fun interruptedOriginalMethodJoinsItsActualRoomEndBeforeAnyCleanupPublication() {
        val fixture = Fixture()
        var read: HeldRead? = null
        val cleanupEntered = CountDownLatch(1)
        val cleanupReturned = CountDownLatch(1)
        val cleanupFailure = AtomicReference<Throwable?>()
        var cleaning: Thread? = null
        var primary: Throwable? = null
        try {
            fixture.evaluate {
                read = HeldRead(fixture, register = true)
                val held = requireNotNull(read)
                assertTrue(held.entered.await(5, TimeUnit.SECONDS))
                held.method.thread.interrupt() // Same runBlocking interruption used by JUnit's deadline.
                assertTrue(held.exitEntered.await(5, TimeUnit.SECONDS))
                assertTrue(held.method.returned.await(5, TimeUnit.SECONDS))
                assertTrue(held.method.failure.get() is InterruptedException)
                assertFalse(requireNotNull(held.method.owner.get()).isCompleted)
                cleaning = Thread({
                    try {
                        runBlocking {
                            cleanupEntered.countDown()
                            fixture.rule.recoveryCleanup {
                                assertTrue(fixture.native.ended.count == 0L)
                                fixture.rule.afterDatabaseClose {
                                    assertFalse(fixture.native.originalIsOpen())
                                    assertTrue(fixture.directory.deleteRecursively())
                                }
                            }
                        }
                    } catch (error: Throwable) { cleanupFailure.set(error) }
                    finally { cleanupReturned.countDown() }
                }, "recovery-cleanup-observer").also { it.start() }
                assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))
                assertEquals(1L, cleanupReturned.count)
                assertEquals(0, fixture.native.nativeCloses.get())
                assertEquals(1L, fixture.native.ended.count)
                assertTrue(fixture.native.originalIsOpen()); fixture.assertFilesRetained()
                held.releaseAndJoin()
                assertTrue(cleanupReturned.await(5, TimeUnit.SECONDS)); cleaning!!.join(5_000)
                assertFalse(cleaning!!.isAlive); assertNull(cleanupFailure.get())
                assertTrue(held.method.failure.get() is InterruptedException) // Cleanup never replaces the body failure.
            }
            assertFalse(fixture.native.originalIsOpen()); assertFalse(fixture.directory.exists())
            assertTrue(fixture.native.events.indexOf("END") < fixture.native.events.indexOf("CLOSE"))
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally { preserveOnFailedDisposal(primary) {
            read?.releaseAndJoin()
            cleaning?.join(5_000)
            check(cleaning?.isAlive != true)
            fixture.disposeAfterOwnedEnd()
        } }
    }

    @Test fun actualJUnitTimeoutAndUndrainedOwnerPreserveAllAftersAndTheNextSetup() {
        val fixture = Fixture()
        var read: TimedHeldRead? = null
        val afters = CleanupAfters(fixture.rule, fixture.directory)
        var primary: Throwable? = null
        try {
            val failure = runCatching {
                fixture.evaluate(afters) {
                    fixture.rule.database.openHelper.writableDatabase.query("SELECT 1").use { assertTrue(it.moveToFirst()) }
                    read = TimedHeldRead(fixture)
                    // This finite inner deadline deliberately exercises JUnit's real interrupt/
                    // no-join behavior. The instrumentation's ordinary 150-second limit is intact.
                    FailOnTimeout.builder().withTimeout(5, TimeUnit.SECONDS).build(object : Statement() {
                        override fun evaluate() { requireNotNull(read).evaluate() }
                    }).evaluate()
                }
            }.exceptionOrNull()
            val errors = allFailures(requireNotNull(failure))
            assertEquals(1, errors.filterIsInstance<TestTimedOutException>().size)
            assertEquals(0L, requireNotNull(read).entered.count)
            val timeout = errors.filterIsInstance<TimeoutCancellationException>().single()
            assertEquals(2, afters.attempts.get()) // Real RunAfters continued after the first failure.
            assertEquals(0, afters.effects.get())
            assertTrue(errors.any { it.message == "PHYSICAL_DATABASE_CLEANUP_BLOCKED" && it.cause === timeout })
            assertFalse(requireNotNull(read!!.owner.get()).isCompleted)
            assertEquals(0, fixture.native.nativeCloses.get()); assertEquals(1L, fixture.native.ended.count)
            fixture.assertFilesRetained(); fixture.assertProviderRetained(); assertBlockedSetup(fixture, timeout)
            val lateCleanup = runCatching { runBlocking { fixture.rule.recoveryCleanup {
                fail("A later @After must not remove the original scene")
            } } }.exceptionOrNull()
            assertEquals("PHYSICAL_DATABASE_CLEANUP_BLOCKED", lateCleanup?.message)
            assertSame(timeout, lateCleanup?.cause)
            fixture.assertFilesRetained(); fixture.assertProviderRetained()
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally { preserveOnFailedDisposal(primary) {
            read?.releaseAndJoin()
            fixture.disposeAfterOwnedEnd() // The independent negative-case fence remains blocked.
        } }
    }

    @Test fun actualEditorAftersRetainBothDatabasesUntilTheOriginalInterruptedOwnerEnds() {
        val fixture = Fixture()
        val editor = object : NextObjectEditorFixture(fixture.rule) {}
        var read: TimedHeldRead? = null
        var icon: SupportSQLiteDatabase? = null
        var preferences: ByteArray? = null
        var setupStarted = false
        var setupCompleted = false
        var primary: Throwable? = null
        try {
            val failure = runCatching {
                fixture.evaluate(editor, outer = editor.widgetRefresh) {
                    setupStarted = true
                    editor.setup(); editor.openEditor(); setupCompleted = true
                    icon = editor.iconDatabase.openHelper.writableDatabase
                    preferences = File(editor.directory, "auth.preferences_pb").readBytes()
                    read = TimedHeldRead(fixture)
                    // Real JUnit interrupt, then real subclass/superclass @After methods.
                    // The original Room child remains held across the existing five-second join.
                    FailOnTimeout.builder().withTimeout(5, TimeUnit.SECONDS).build(object : Statement() {
                        override fun evaluate() { requireNotNull(read).evaluate() }
                    }).evaluate()
                }
            }.exceptionOrNull()
            val errors = allFailures(requireNotNull(failure))
            assertEquals(1, errors.filterIsInstance<TestTimedOutException>().size)
            val timeout = errors.filterIsInstance<TimeoutCancellationException>().single()
            val blocked = errors.filter { it.message == "PHYSICAL_DATABASE_CLEANUP_BLOCKED" }
            assertEquals(2, blocked.size) // Core @After and Physical Rule.after both refuse.
            blocked.forEach { assertSame(timeout, it.cause) }
            assertEquals(0L, requireNotNull(read).entered.count)
            assertFalse(requireNotNull(read!!.owner.get()).isCompleted)
            assertEquals(0, fixture.native.nativeCloses.get()); assertEquals(1L, fixture.native.ended.count)
            assertTrue(requireNotNull(icon).isOpen)
            assertTrue(editor.app.getDatabasePath(com.dayforge.data.appearance.AccountIconDatabase.NAME).isFile)
            assertTrue(editor.directory.isDirectory)
            assertFalse(requireNotNull(editor.scope.coroutineContext[Job]).isCancelled)
            assertArrayEquals(requireNotNull(preferences), File(editor.directory, "auth.preferences_pb").readBytes())
            fixture.assertFilesRetained(); fixture.assertProviderRetained(); assertBlockedSetup(fixture, timeout)
            val repeated = runCatching { editor.closeEditor() }.exceptionOrNull()
            assertEquals("PHYSICAL_DATABASE_CLEANUP_BLOCKED", repeated?.message); assertSame(timeout, repeated?.cause)
            assertTrue(requireNotNull(icon).isOpen); assertEquals(0, fixture.native.nativeCloses.get())
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally { preserveOnFailedDisposal(primary) {
            // This private negative-case fence stays blocked. Disposal is only possible after
            // the original native END, completed Job and original JUnit worker's actual exit.
            if (setupStarted) {
                check(setupCompleted) { "EDITOR_FIXTURE_SETUP_OUTCOME_UNKNOWN" }
                val held = requireNotNull(read)
                held.releaseAndJoin()
                check(requireNotNull(held.owner.get()).isCompleted)
                check(fixture.native.ended.count == 0L && !fixture.native.watchingTransaction())
                runBlocking { withTimeout(5_000) { editor.scope.coroutineContext[Job]!!.cancelAndJoin() } }
                runBlocking { withTimeout(5_000) { editor.icons.awaitImages() } }
                editor.icons.close()
                editor.iconDatabase.close()
                check(!requireNotNull(icon).isOpen)
                editor.client.connectionPool.evictAll(); editor.client.dispatcher.executorService.shutdown()
            }
            fixture.disposeAfterOwnedEnd {
                if (setupCompleted) {
                    check(editor.app.deleteDatabase(com.dayforge.data.appearance.AccountIconDatabase.NAME) ||
                        !editor.app.getDatabasePath(com.dayforge.data.appearance.AccountIconDatabase.NAME).exists())
                    check(editor.directory.deleteRecursively())
                }
            }
        } }
    }

    @Test fun actualCoreCleanupBoundsTheIndependentDataStoreActorAfterTheMethodAlreadyCompleted() {
        val fixture = Fixture()
        val core = object : NextCoreRequestFixture(fixture.rule) {}
        val transformEntered = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        var method: MethodThread? = null
        var writer: MethodThread? = null
        var before: ByteArray? = null
        var setup = false
        var primary: Throwable? = null
        try {
            val failure = runCatching {
                fixture.evaluate(core) {
                    core.setup(); setup = true
                    method = MethodThread(fixture.rule, register = true) {}
                    method!!.joinMethodAndOwner(); assertNull(method!!.failure.get())
                    assertTrue(requireNotNull(method!!.owner.get()).isCompleted)
                    before = File(core.directory, "auth.preferences_pb").readBytes()
                    writer = MethodThread(fixture.rule, register = false) {
                        core.dataStore.updateData { old ->
                            withContext(NonCancellable) { transformEntered.countDown(); release.await() }
                            old
                        }
                    }
                    assertTrue(transformEntered.await(5, TimeUnit.SECONDS))
                }
            }.exceptionOrNull()
            val errors = allFailures(requireNotNull(failure))
            val timeout = errors.filterIsInstance<TimeoutCancellationException>().single()
            val dataStoreOwner = requireNotNull(core.scope.coroutineContext[Job])
            assertTrue(dataStoreOwner.isCancelled); assertFalse(dataStoreOwner.isCompleted)
            assertTrue(requireNotNull(method!!.owner.get()).isCompleted)
            assertEquals(0, fixture.native.nativeCloses.get()); fixture.assertProviderRetained()
            assertTrue(core.directory.isDirectory)
            assertArrayEquals(requireNotNull(before), File(core.directory, "auth.preferences_pb").readBytes())
            val repeated = runCatching { core.cleanup() }.exceptionOrNull()
            assertEquals("PHYSICAL_DATABASE_CLEANUP_BLOCKED", repeated?.message); assertSame(timeout, repeated?.cause)
            assertBlockedSetup(fixture, timeout)
            assertArrayEquals(requireNotNull(before), File(core.directory, "auth.preferences_pb").readBytes())
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally { preserveOnFailedDisposal(primary) {
            release.complete(Unit)
            writer?.joinMethodAndOwner(); method?.joinMethodAndOwner()
            if (setup) {
                runBlocking { withTimeout(5_000) { core.scope.coroutineContext[Job]!!.cancelAndJoin() } }
                core.client.connectionPool.evictAll(); core.client.dispatcher.executorService.shutdown()
            }
            fixture.disposeAfterOwnedEnd { if (setup) check(core.directory.deleteRecursively()) }
        } }
    }

    @Test fun actualReadScopeCloseRejectionPreservesProviderDatabaseAndLaterCleanupInputs() {
        val fixture = Fixture()
        var read: HeldRead? = null
        var primary: Throwable? = null
        try {
            val failure = runCatching {
                fixture.evaluate {
                    // Foreign work is intentionally not registered: exercise the real native
                    // close guard, independently of the recovery owner's earlier drain barrier.
                    read = HeldRead(fixture, register = false)
                    assertTrue(requireNotNull(read).entered.await(5, TimeUnit.SECONDS))
                }
            }.exceptionOrNull()
            assertEquals("COUNT_TRUST_READ_SNAPSHOT_WRITE_DENIED", failure?.message)
            assertEquals(0, fixture.native.nativeCloses.get())
            assertTrue(fixture.native.originalIsOpen()); fixture.assertFilesRetained(); fixture.assertProviderRetained()
            val afters = CleanupAfters(fixture.rule, fixture.directory)
            val continued = runCatching { withAfters(emptyStatement(), afters).evaluate() }.exceptionOrNull()
            val errors = allFailures(requireNotNull(continued))
            assertEquals(2, afters.attempts.get()); assertEquals(0, afters.effects.get())
            assertTrue(errors.all { it.message == "PHYSICAL_DATABASE_CLEANUP_BLOCKED" && it.cause === failure })
            assertBlockedSetup(fixture, requireNotNull(failure))
            fixture.assertFilesRetained(); fixture.assertProviderRetained()
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally { preserveOnFailedDisposal(primary) {
            read?.releaseAndJoin()
            // Room's first failed close has already sealed its CloseBarrier: another Room.close
            // can return without calling native close. Dispose the original helper only after END.
            fixture.disposeAfterOwnedEnd()
        } }
    }

    @Test fun completedOwnerWithActualUnknownNativeEndCannotCloseOrDeleteItsScene() {
        val fixture = Fixture()
        var method: MethodThread? = null
        var original: Throwable? = null
        var primary: Throwable? = null
        try {
            val failure = runCatching {
                fixture.evaluate {
                    method = MethodThread(fixture.rule, register = true) {
                        fixture.rule.database.withTransaction {
                            fixture.native.watchCurrentTransaction()
                            fixture.rule.database.openHelper.writableDatabase.query("SELECT 1").use { assertTrue(it.moveToFirst()) }
                            fixture.native.failNextOwnedEnd()
                        }
                    }
                    method!!.joinMethodAndOwner()
                    original = requireNotNull(method!!.failure.get())
                    assertEquals("RECOVERY_FIXTURE_NATIVE_END_UNKNOWN", original?.message)
                    assertTrue(requireNotNull(method!!.owner.get()).isCompleted)
                    assertTrue(fixture.native.ended.await(5, TimeUnit.SECONDS))
                    assertFalse(fixture.native.watchingTransaction())
                    assertTrue(requireNotNull(CountTrustRuntime.forDatabase(fixture.rule.database)).hasUnknownGraphBoundary())
                    runBlocking { fixture.rule.recoveryCleanup {
                        fixture.rule.afterDatabaseClose { check(fixture.directory.deleteRecursively()) }
                    } }
                    throw requireNotNull(original) // Preserve the actual body error beside Rule.after's refusal.
                }
            }.exceptionOrNull()
            val errors = allFailures(requireNotNull(failure))
            assertTrue(errors.any { it === original })
            val refused = errors.single { it.message == "RECOVERY_TEST_NATIVE_END_UNKNOWN" }
            assertEquals(0, fixture.native.nativeCloses.get()); assertTrue(fixture.native.originalIsOpen())
            fixture.assertProviderRetained(); fixture.assertFilesRetained(); assertBlockedSetup(fixture, refused)
            assertSame(original, method!!.failure.get())
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally { preserveOnFailedDisposal(primary) {
            method?.joinMethodAndOwner()
            fixture.disposeAfterOwnedEnd()
        } }
    }

    @Test fun cleanupSealingRejectsALateOwnerBeforeItsOriginalBodyCanRun() {
        val fixture = Fixture()
        val bodyCalls = AtomicInteger()
        var primary: Throwable? = null
        try {
            fixture.evaluate {
                fixture.rule.database.openHelper.writableDatabase.query("SELECT 1").use { assertTrue(it.moveToFirst()) }
                runBlocking { fixture.rule.recoveryCleanup {} }
                val originalQueries = fixture.native.lateBodyQueries.get()
                val late = MethodThread(fixture.rule, register = true) {
                    bodyCalls.incrementAndGet()
                    fixture.rule.database.openHelper.writableDatabase.query("SELECT 47").use { assertTrue(it.moveToFirst()) }
                }
                late.joinMethodAndOwner()
                assertEquals("RECOVERY_TEST_OWNER_ADMISSION_CLOSED", late.failure.get()?.message)
                assertEquals(0, bodyCalls.get()); assertEquals(originalQueries, fixture.native.lateBodyQueries.get())
            }
            assertFalse(fixture.databaseFile.exists())
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally { preserveOnFailedDisposal(primary) { fixture.disposeAfterOwnedEnd() } }
    }

    /** Observe the real cleanup thread parked in its actual appearanceCleanup runBlocking
     * while the controller's native query is still held. A close-without-await mutation can
     * neither return early nor use a merely unscheduled cleanup thread as passing evidence.
     */
    private suspend fun awaitActualControllerJoin(thread: Thread, returned: CountDownLatch) = withTimeout(5_000) {
        while (true) {
            check(returned.count == 1L) { "Cleanup returned before the retained controller transaction ended" }
            val state = thread.state
            val stack = thread.stackTrace
            val parked = state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING
            val join = stack.any { it.className == "kotlinx.coroutines.BlockingCoroutine" && it.methodName == "joinBlocking" }
            val cleanup = stack.any { it.className == "com.dayforge.data.repository.NextReplicaDestinationFixture" &&
                (it.methodName == "appearanceCleanup" || it.methodName.startsWith("appearanceCleanup\$")) }
            val physicallyClosing = stack.any { it.className == "androidx.room.RoomDatabase" && it.methodName == "close" }
            if (parked && join && cleanup && !physicallyClosing) {
                check(returned.count == 1L)
                return@withTimeout
            }
            delay(1) // Scheduling a bounded observation, never elapsed-time evidence of a join.
        }
    }

    private fun assertBlockedSetup(fixture: Fixture, original: Throwable) {
        val next = PhysicalDatabaseRule(fixture.fence)
        val failure = runCatching {
            next.apply(object : Statement() {
                override fun evaluate() { fail("A blocked setup must not enter its method") }
            }, description()).evaluate()
        }.exceptionOrNull()
        assertEquals("PHYSICAL_DATABASE_CLEANUP_BLOCKED", failure?.message)
        assertSame(original, failure?.cause)
        fixture.assertProviderRetained(); fixture.assertFilesRetained()
    }

    /** Public method/class visibility lets the real JUnit FrameworkMethod invoke both @After
     * methods. The superclass cleanup must be attempted even if its subclass cleanup throws.
     */
    open class ParentCleanupAfters(protected val rule: PhysicalDatabaseRule, protected val directory: File) {
        val attempts = AtomicInteger(); val effects = AtomicInteger()
        @After fun sharedCleanup() {
            attempts.incrementAndGet()
            runBlocking { rule.recoveryCleanup {
                effects.incrementAndGet()
                rule.afterDatabaseClose { check(File(directory, "preferences").delete()) }
            } }
        }
    }
    class CleanupAfters(rule: PhysicalDatabaseRule, directory: File) : ParentCleanupAfters(rule, directory) {
        @After fun appearanceCleanup() {
            attempts.incrementAndGet()
            runBlocking { rule.recoveryCleanup {
                effects.incrementAndGet()
                rule.afterDatabaseClose { check(File(directory, "material").delete()) }
            } }
        }
    }

    private class MethodThread(rule: PhysicalDatabaseRule, register: Boolean,
        body: suspend CoroutineScope.() -> Unit) {
        val owner = AtomicReference<Job?>(); val failure = AtomicReference<Throwable?>()
        val returned = CountDownLatch(1)
        val thread = Thread({
            try {
                runBlocking<Unit> {
                    val actual = requireNotNull(currentCoroutineContext()[Job]); owner.set(actual)
                    if (register) rule.ownRecoveryMethod(actual)
                    body()
                }
            } catch (error: Throwable) { failure.set(error) }
            finally { returned.countDown() }
        }, "recovery-original-method").also { it.start() }
        fun joinMethodAndOwner() {
            check(returned.await(5, TimeUnit.SECONDS)) { "Original method thread did not return" }
            thread.join(5_000); check(!thread.isAlive)
            owner.get()?.let { job -> runBlocking { withTimeout(5_000) { job.join() } }; check(job.isCompleted) }
        }
    }

    /** The actual JUnit deadline thread owns this runBlocking. Its interrupt may return
     * before its Room child has passed the retained native transaction's finally boundary.
     */
    private class TimedHeldRead(private val fixture: Fixture) {
        val entered = CountDownLatch(1)
        val owner = AtomicReference<Job?>()
        private val release = CompletableDeferred<Unit>()
        private val returned = CountDownLatch(1)
        private val thread = AtomicReference<Thread?>()
        fun evaluate() {
            thread.set(Thread.currentThread())
            try {
                runBlocking<Unit> {
                    val actual = requireNotNull(currentCoroutineContext()[Job]); owner.set(actual)
                    fixture.rule.ownRecoveryMethod(actual)
                    fixture.rule.database.withTransaction {
                        fixture.native.watchCurrentTransaction()
                        requireNotNull(CountTrustRuntime.forDatabase(fixture.rule.database)).withReadSnapshot(fixture.rule.database) {
                            entered.countDown()
                            try { awaitCancellation() }
                            finally { withContext(NonCancellable) { release.await() } }
                        }
                    }
                }
            } finally { returned.countDown() }
        }
        fun releaseAndJoin() {
            release.complete(Unit); thread.get()?.interrupt()
            check(returned.await(5, TimeUnit.SECONDS))
            owner.get()?.let { job -> runBlocking { withTimeout(5_000) { job.join() } }; check(job.isCompleted) }
            thread.get()?.join(5_000); check(thread.get()?.isAlive != true)
        }
    }

    private class HeldRead(fixture: Fixture, register: Boolean) {
        val entered = CountDownLatch(1); val exitEntered = CountDownLatch(1)
        private val release = CompletableDeferred<Unit>()
        val method = MethodThread(fixture.rule, register) {
            val db = fixture.rule.database
            db.withTransaction {
                fixture.native.watchCurrentTransaction()
                requireNotNull(CountTrustRuntime.forDatabase(db)).withReadSnapshot(db) {
                    entered.countDown()
                    try { awaitCancellation() }
                    finally {
                        exitEntered.countDown()
                        withContext(NonCancellable) { release.await() }
                    }
                }
            }
        }
        fun releaseAndJoin() {
            release.complete(Unit)
            method.thread.interrupt()
            method.joinMethodAndOwner()
        }
    }

    private class Fixture {
        val context: Context = ApplicationProvider.getApplicationContext()
        val fence = PhysicalDatabaseRule.CleanupFence()
        val native = NativeEndProbe()
        val directory: File = Files.createTempDirectory(context.filesDir.toPath(), "recovery-lifecycle-").toFile()
        val databaseFile: File get() = context.getDatabasePath("habit_database")
        private var built: HabitDatabase? = null
        private var buildStarted = false
        val rule = PhysicalDatabaseRule(fence).apply {
            isolatedBuilder = { ctx ->
                buildStarted = true
                HabitDatabaseProvider.build(ctx, nativeFactory = native).also { built = it }
            }
        }
        init {
            check(context.packageName == "com.dayforge.testbed")
            File(directory, "material").writeBytes(byteArrayOf(1, 4, 9))
            File(directory, "preferences").writeBytes(byteArrayOf(2, 5, 8))
        }
        fun evaluate(afters: Any? = null, outer: TestRule? = null, body: () -> Unit) {
            val statement = object : Statement() { override fun evaluate() { body() } }
            val physical = rule.apply(if (afters == null) statement else withAfters(statement, afters), description())
            (outer?.apply(physical, description()) ?: physical).evaluate()
        }
        fun assertFilesRetained() {
            assertTrue(databaseFile.isFile)
            assertArrayEquals(byteArrayOf(1, 4, 9), File(directory, "material").readBytes())
            assertArrayEquals(byteArrayOf(2, 5, 8), File(directory, "preferences").readBytes())
        }
        fun assertProviderRetained() { assertSame(rule.database, HabitDatabaseProvider.getInstance(context)) }
        fun disposeAfterOwnedEnd(afterNativeClose: () -> Unit = {}) {
            val owned = built
            if (owned == null) {
                // A refused before() never gave this fixture ownership of the shared Provider
                // or DB. Its finally may only remove its own private directory. An attempted
                // build without a returned handle is unknown, not proof that nothing opened.
                check(!buildStarted) { "RECOVERY_FIXTURE_BUILD_OUTCOME_UNKNOWN" }
                check(directory.deleteRecursively())
                return
            }
            check(!native.watchingTransaction()) { "Do not dispose a still-owned original native transaction" }
            // Join/release happened above; now stop Room's own refresh lifecycle before closing
            // its helper. A helper-only close leaves Room's close barrier and scope active.
            // A prior failed Room.close may already have sealed that barrier and become a no-op;
            // the original helper close and physical checks remain mandatory in either case.
            owned.close()
            owned.openHelper.close()
            check(!native.originalIsOpen()) { "Original native handle was not physically closed" }
            check(native.allObservedClosed()) { "An earlier observed native handle was not physically closed" }
            afterNativeClose()
            HabitDatabaseProvider.clearInstanceForTesting()
            check(context.deleteDatabase("habit_database") || !databaseFile.exists())
            check(directory.deleteRecursively())
        }
    }

    /** Observe only the original transaction's successful outer native END, after delegate
     * return. Room invalidation on another thread cannot satisfy this witness.
     */
    private class NativeEndProbe : SupportSQLiteOpenHelper.Factory {
        private val native = AtomicReference<SupportSQLiteDatabase?>()
        private val observedNatives = CopyOnWriteArrayList<SupportSQLiteDatabase>()
        private val transactionThread = AtomicReference<Thread?>()
        val ended = CountDownLatch(1)
        private val failOwnedEnd = java.util.concurrent.atomic.AtomicBoolean()
        fun failNextOwnedEnd() { check(failOwnedEnd.compareAndSet(false, true)) }
        val nativeCloses = AtomicInteger()
        val queries = AtomicInteger()
        val lateBodyQueries = AtomicInteger()
        val controllerEntered = CountDownLatch(1)
        private val controllerRelease = CountDownLatch(1)
        private val controllerArmed = java.util.concurrent.atomic.AtomicBoolean()
        fun armControllerRead() { check(controllerArmed.compareAndSet(false, true)) }
        fun releaseControllerRead() { controllerRelease.countDown() }
        private fun beforeQuery(sql: String) {
            queries.incrementAndGet()
            if (sql == "SELECT 47") lateBodyQueries.incrementAndGet()
            if (sql == "SELECT * FROM next_replica_fence ORDER BY id" &&
                Thread.currentThread().stackTrace.any { it.className == "com.dayforge.data.repository.ReplicaPresentationRepository" &&
                    (it.methodName == "readFenceInTransaction" || it.methodName.startsWith("readFenceInTransaction\$")) } && controllerArmed.compareAndSet(true, false)) {
                watchCurrentTransaction(); controllerEntered.countDown()
                check(controllerRelease.await(5, TimeUnit.SECONDS)) { "Controller native query was not released" }
            }
        }
        val events = CopyOnWriteArrayList<String>()
        fun watchCurrentTransaction() {
            check(requireNotNull(native.get()).inTransaction())
            check(transactionThread.compareAndSet(null, Thread.currentThread()))
        }
        fun watchingTransaction(): Boolean = transactionThread.get() != null
        // Historical name: this is the latest wrapped native. Disposal also checks every
        // observed native so a replacement cannot conceal an earlier still-open handle.
        fun originalIsOpen(): Boolean = native.get()?.isOpen == true
        fun allObservedClosed(): Boolean = observedNatives.all { !it.isOpen }
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            var original: SupportSQLiteDatabase? = null
            var adapted: SupportSQLiteDatabase? = null
            val monitor = Any()
            fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
                if (original !== raw) {
                    original = raw; native.set(raw); observedNatives += raw
                    adapted = object : SupportSQLiteDatabase by raw {
                        override fun query(query: String): Cursor { beforeQuery(query); return raw.query(query) }
                        override fun query(query: String, bindArgs: Array<out Any?>): Cursor {
                            beforeQuery(query); return raw.query(query, bindArgs)
                        }
                        override fun query(query: SupportSQLiteQuery): Cursor { beforeQuery(query.sql); return raw.query(query) }
                        override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor {
                            beforeQuery(query.sql); return raw.query(query, cancellationSignal)
                        }
                        override fun endTransaction() {
                            raw.endTransaction()
                            val current = Thread.currentThread()
                            if (!raw.inTransaction() && transactionThread.compareAndSet(current, null)) {
                                events += "END"; ended.countDown()
                                if (failOwnedEnd.compareAndSet(true, false)) error("RECOVERY_FIXTURE_NATIVE_END_UNKNOWN")
                            }
                        }
                        override fun close() {
                            events += "CLOSE"; nativeCloses.incrementAndGet(); raw.close()
                        }
                    }
                }
                requireNotNull(adapted)
            }
            val callback = configuration.callback
            val delegate = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(configuration.context).name(configuration.name)
                    .callback(object : SupportSQLiteOpenHelper.Callback(callback.version) {
                        override fun onConfigure(db: SupportSQLiteDatabase) = callback.onConfigure(wrap(db))
                        override fun onCreate(db: SupportSQLiteDatabase) = callback.onCreate(wrap(db))
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                            callback.onUpgrade(wrap(db), oldVersion, newVersion)
                        override fun onOpen(db: SupportSQLiteDatabase) = callback.onOpen(wrap(db))
                        override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                            callback.onDowngrade(wrap(db), oldVersion, newVersion)
                        override fun onCorruption(db: SupportSQLiteDatabase) = callback.onCorruption(wrap(db))
                    }).build())
            return object : SupportSQLiteOpenHelper by delegate {
                override val readableDatabase: SupportSQLiteDatabase get() = wrap(delegate.readableDatabase)
                override val writableDatabase: SupportSQLiteDatabase get() = wrap(delegate.writableDatabase)
            }
        }
    }

    companion object {
        private fun preserveOnFailedDisposal(primary: Throwable?, block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                // A negative case may intentionally use an independent refusal fence; failed
                // outer disposal is different and must stop every later fixture in this process.
                PhysicalDatabaseRule.preserveProcessCleanupFailure(error)
                if (primary == null) throw error
                if (primary !== error) primary.addSuppressed(error)
            }
        }
        private fun description() = Description.createTestDescription(RecoveryFixtureLifecycleTest::class.java, "nestedLifecycle")
        private fun emptyStatement() = object : Statement() { override fun evaluate() = Unit }
        private fun withAfters(statement: Statement, target: Any) = RunAfters(statement,
            TestClass(target.javaClass).getAnnotatedMethods(After::class.java), target)
        private fun allFailures(error: Throwable): List<Throwable> =
            if (error is MultipleFailureException) error.failures.flatMap(::allFailures) else listOf(error)
    }
}
