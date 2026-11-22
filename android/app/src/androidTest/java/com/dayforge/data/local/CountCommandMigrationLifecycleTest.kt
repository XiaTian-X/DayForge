package com.dayforge.data.local

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.internal.runners.statements.FailOnTimeout
import org.junit.internal.runners.statements.RunAfters
import org.junit.internal.runners.statements.RunBefores
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.junit.runners.model.TestClass
import org.junit.runners.model.TestTimedOutException

/** Real Provider/Room/files and the original JUnit Before -> timed method -> After order.
 * Every negative case owns a local no-reset fence. Outer disposal joins/releases actual
 * work first; failure there poisons the real process fence, never resets either fence.
 */
@RunWith(AndroidJUnit4::class)
class CountCommandMigrationLifecycleTest {
    @Test(timeout = 150_000) fun poisonedSetupStillRunsAftersWithoutClosingOrDeletingThePreviousScene() {
        val fixture = Fixture()
        val original = IllegalStateException("MIGRATION_PRIOR_OWNER_END_UNCONFIRMED")
        var primary: Throwable? = null
        try {
            val failure = runCatching { fixture.evaluate {
                fixture.openPhysical(); val image = fixture.authBytes()
                fixture.fence.preserve(original)
                assertBlockedSetup(fixture, original)
                fixture.assertRetained(image)
            } }.exceptionOrNull()
            assertEquals("PHYSICAL_DATABASE_CLEANUP_BLOCKED", failure?.message)
            assertSame(original, failure?.cause)
            assertEquals(1, fixture.afterCalls.get())
            assertFalse(fixture.migrationCleanupCompleted)
            assertEquals(0, fixture.native.closes.get())
            assertTrue(fixture.migrationAuthenticationJob.isActive)
        } catch (error: Throwable) { primary = error; throw error }
        finally { disposePreserving(primary) { fixture.disposeAfterOwnedEnd() } }
    }

    @Test(timeout = 150_000) fun successfulCaseJoinsItsActualPreferencesActorThenClosesAndDeletesItsOwnScene() {
        val fixture = Fixture()
        val actorEnded = CountDownLatch(1)
        val actorScene = AtomicReference<List<Boolean>?>()
        var child: Job? = null
        var primary: Throwable? = null
        try {
            fixture.evaluate {
                fixture.openPhysical()
                assertTrue(fixture.authBytes().isNotEmpty())
                child = CoroutineScope(fixture.migrationAuthenticationJob + Dispatchers.IO).launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally {
                        actorScene.set(listOf(fixture.databaseFile.isFile,
                            fixture.migrationAuthenticationDirectory.isDirectory, fixture.native.closes.get() == 0))
                        actorEnded.countDown()
                    }
                }
            }
            assertTrue(fixture.migrationCleanupCompleted)
            assertEquals(1, fixture.afterCalls.get())
            assertTrue(requireNotNull(child).isCompleted)
            assertTrue(fixture.migrationAuthenticationJob.isCompleted)
            assertEquals(0L, actorEnded.count)
            assertEquals(listOf(true, true, true), actorScene.get())
            assertTrue(fixture.native.closes.get() > 0)
            assertTrue(fixture.native.allClosed())
            assertFalse(fixture.databaseFile.exists())
            assertFalse(fixture.migrationAuthenticationDirectory.exists())
            fixture.fence.requireClear()
            val next = HabitDatabaseProvider.getInstance(fixture.appContext)
            try { assertNotSame(fixture.database, next) }
            finally { next.close(); next.openHelper.close() }
        } catch (error: Throwable) { primary = error; throw error }
        finally { disposePreserving(primary) { fixture.disposeAfterOwnedEnd() } }
    }

    @Test(timeout = 150_000) fun preferencesActorUnknownEndPreservesFilesAndProviderAndRejectsFollowingSetup() {
        val fixture = Fixture()
        val release = CompletableDeferred<Unit>()
        val joining = CountDownLatch(1)
        var child: Job? = null
        var image: ByteArray? = null
        var primary: Throwable? = null
        try {
            val failure = runCatching { fixture.evaluate {
                fixture.openPhysical(); image = fixture.authBytes()
                child = CoroutineScope(fixture.migrationAuthenticationJob + Dispatchers.IO).launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { withContext(NonCancellable) {
                        joining.countDown(); release.await()
                    } }
                }
            } }.exceptionOrNull()
            assertTrue("The original 5s actor join must fail", failure is TimeoutCancellationException)
            assertEquals(0L, joining.count)
            assertFalse(requireNotNull(child).isCompleted)
            assertFalse(fixture.migrationAuthenticationJob.isCompleted)
            assertFalse(fixture.migrationCleanupCompleted)
            assertEquals(1, fixture.afterCalls.get())
            fixture.assertRetained(requireNotNull(image))
            assertBlockedSetup(fixture, requireNotNull(failure))
            // A second After cannot retry the failed close or erase the original scene.
            fixture.closeMigrationCase()
            fixture.assertRetained(requireNotNull(image))
            val replay = runCatching { fixture.evaluate { fail("Cannot rerun a failed fixture") } }.exceptionOrNull()
            assertEquals("MIGRATION_FIXTURE_ALREADY_EVALUATED", replay?.message)
            fixture.assertRetained(requireNotNull(image))
        } catch (error: Throwable) { primary = error; throw error }
        finally { disposePreserving(primary) {
            release.complete(Unit)
            runBlocking { withTimeout(5_000) { child?.join(); fixture.migrationAuthenticationJob.cancelAndJoin() } }
            assertTrue(child?.isCompleted != false)
            fixture.disposeAfterOwnedEnd()
        } }
    }

    @Test(timeout = 150_000) fun actualUnknownNativeEndPreservesAllResourcesAndDoesNotBecomeSuccessfulCleanup() {
        val fixture = Fixture()
        var bodyError: Throwable? = null
        var image: ByteArray? = null
        var primary: Throwable? = null
        try {
            val failure = runCatching { fixture.evaluate {
                val database = fixture.openPhysical(); image = fixture.authBytes()
                bodyError = runCatching { runBlocking { database.withTransaction {
                    fixture.native.watchEnd(failAfterReturn = true)
                    database.openHelper.writableDatabase.query("SELECT 1").use { assertTrue(it.moveToFirst()) }
                } } }.exceptionOrNull()
                assertTrue(generateSequence(bodyError) { it.cause }.any { it.message == "MIGRATION_LIFECYCLE_NATIVE_END_LOST" })
                assertEquals(1, fixture.native.ends.get())
                assertFalse(fixture.native.transactionActive())
                assertTrue(requireNotNull(CountTrustRuntime.forDatabase(database)).hasUnknownGraphBoundary())
            } }.exceptionOrNull()
            assertNotNull(bodyError)
            assertEquals("MIGRATION_FIXTURE_NATIVE_END_UNKNOWN", failure?.message)
            assertFalse(fixture.migrationCleanupCompleted)
            assertTrue(fixture.migrationAuthenticationJob.isActive)
            fixture.assertRetained(requireNotNull(image))
            assertBlockedSetup(fixture, requireNotNull(failure))
        } catch (error: Throwable) { primary = error; throw error }
        finally { disposePreserving(primary) { fixture.disposeAfterOwnedEnd() } }
    }

    @Test(timeout = 150_000) fun originalTimedMethodIsNotClosedOrDeletedWhileItsRealRoomChildStillOwnsEnd() {
        val fixture = Fixture()
        val release = CompletableDeferred<Unit>()
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val owner = AtomicReference<Job?>()
        val methodThread = AtomicReference<Thread?>()
        var image: ByteArray? = null
        var primary: Throwable? = null
        try {
            val body = object : Statement() {
                override fun evaluate() {
                    methodThread.set(Thread.currentThread())
                    try { runBlocking<Unit> {
                        owner.set(coroutineContext[Job])
                        val database = fixture.database
                        database.withTransaction {
                            fixture.native.watchEnd(failAfterReturn = false)
                            entered.countDown()
                            try { awaitCancellation() } finally { withContext(NonCancellable) { release.await() } }
                        }
                    } } finally { exited.countDown() }
                }
            }
            // Build/open the physical fixture before this fixed lifecycle fault window.
            // The real JUnit timer still wraps the original runBlocking and its Room child.
            val failure = runCatching { fixture.evaluate {
                fixture.openPhysical(); image = fixture.authBytes()
                FailOnTimeout.builder().withTimeout(5, TimeUnit.SECONDS).build(body).evaluate()
            } }.exceptionOrNull()
            assertTrue(failure is TestTimedOutException)
            assertEquals(0L, entered.count)
            assertFalse(requireNotNull(owner.get()).isCompleted)
            assertTrue(fixture.native.transactionActive())
            assertEquals(0, fixture.native.ends.get())
            assertEquals(1, fixture.afterCalls.get())
            assertTrue(fixture.migrationAuthenticationJob.isActive)
            fixture.assertRetained(requireNotNull(image))
            assertBlockedSetup(fixture, requireNotNull(failure))
        } catch (error: Throwable) { primary = error; throw error }
        finally { disposePreserving(primary) {
            release.complete(Unit); methodThread.get()?.interrupt()
            if (methodThread.get() != null) check(exited.await(5, TimeUnit.SECONDS)) { "Original timed method did not return" }
            runBlocking { withTimeout(5_000) { owner.get()?.join() } }
            methodThread.get()?.join(5_000)
            check(methodThread.get()?.isAlive != true)
            check(!fixture.native.transactionActive())
            if (entered.count == 0L) check(fixture.native.ends.get() == 1) { "Original native END did not return" }
            fixture.disposeAfterOwnedEnd()
        } }
    }

    private fun assertBlockedSetup(original: Fixture, cause: Throwable) {
        val next = Fixture(original.fence)
        val entered = AtomicInteger()
        val failure = runCatching { next.evaluate { entered.incrementAndGet() } }.exceptionOrNull()
        assertEquals("PHYSICAL_DATABASE_CLEANUP_BLOCKED", failure?.message)
        assertSame(cause, failure?.cause)
        assertEquals(0, entered.get())
        assertEquals(1, next.afterCalls.get()) // Real RunAfters even though RunBefores failed.
        assertFalse(next.migrationCleanupCompleted)
        assertSame(original.database, HabitDatabaseProvider.getInstance(original.appContext))
        assertEquals(0, original.native.closes.get())
        assertTrue(original.databaseFile.isFile)
    }

    /** Public for JUnit FrameworkMethod invocation of the subclass After observer. */
    class Fixture internal constructor(internal val fence: PhysicalDatabaseRule.CleanupFence = PhysicalDatabaseRule.CleanupFence()) :
        CountCommandMigrationFixture(fence) {
        internal val native = NativeProbe()
        val afterCalls = AtomicInteger()
        private var built: HabitDatabase? = null
        internal val appContext get() = context
        internal val database get() = requireNotNull(built)
        internal val databaseFile: File get() = context.getDatabasePath("habit_database")
        @After fun observeOriginalAfter() { afterCalls.incrementAndGet() }
        internal fun openPhysical(): HabitDatabase = HabitDatabaseProvider.build(context, nativeFactory = native).also {
            built = it; room = it; HabitDatabaseProvider.setInstanceForTesting(it)
            check(it.openHelper.writableDatabase.isOpen)
        }
        internal fun authBytes(): ByteArray = File(migrationAuthenticationDirectory, "auth.preferences_pb").readBytes()
        internal fun assertRetained(auth: ByteArray) {
            assertSame(database, HabitDatabaseProvider.getInstance(context))
            assertTrue(native.hasOpenNative()); assertEquals(0, native.closes.get())
            assertTrue(databaseFile.isFile)
            assertArrayEquals(auth, authBytes())
        }
        internal fun evaluate(body: () -> Unit) = evaluate(object : Statement() { override fun evaluate() = body() })
        internal fun evaluate(body: Statement) {
            val type = TestClass(javaClass)
            val before = RunBefores(body, type.getAnnotatedMethods(Before::class.java), this)
            val after = RunAfters(before, type.getAnnotatedMethods(After::class.java), this)
            migrationLifecycle.apply(after, Description.createTestDescription(javaClass, "originalLifecycle")).evaluate()
        }
        internal fun disposeAfterOwnedEnd() {
            // This outer negative-test disposal is not success for the failed lifecycle.
            // No fence is reset. Exact child/native END proof must precede either close.
            check(!native.transactionActive()) { "Original native transaction still owns END" }
            runBlocking { withTimeout(5_000) { migrationAuthenticationJob.cancelAndJoin() } }
            check(migrationAuthenticationJob.isCompleted)
            built?.let { it.close(); it.openHelper.close() }
            check(native.allClosed()) { "An original physical native handle is still open" }
            HabitDatabaseProvider.clearInstanceForTesting()
            check(context.deleteDatabase("habit_database") || !databaseFile.exists())
            check(migrationAuthenticationDirectory.deleteRecursively())
        }
    }

    internal class NativeProbe : SupportSQLiteOpenHelper.Factory {
        private val natives = CopyOnWriteArrayList<SupportSQLiteDatabase>()
        private val ownedThread = AtomicReference<Thread?>()
        private val loseEnd = AtomicBoolean()
        val ends = AtomicInteger()
        val closes = AtomicInteger()
        fun watchEnd(failAfterReturn: Boolean) {
            check(natives.last().inTransaction())
            check(ownedThread.compareAndSet(null, Thread.currentThread()))
            loseEnd.set(failAfterReturn)
        }
        fun transactionActive() = ownedThread.get() != null
        fun allClosed() = natives.all { !it.isOpen }
        fun hasOpenNative() = natives.any { it.isOpen }
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            var original: SupportSQLiteDatabase? = null
            var adapted: SupportSQLiteDatabase? = null
            val lock = Any()
            fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(lock) {
                if (original !== raw) {
                    original = raw; natives += raw
                    adapted = object : SupportSQLiteDatabase by raw {
                        override fun endTransaction() {
                            raw.endTransaction()
                            if (!raw.inTransaction() && ownedThread.compareAndSet(Thread.currentThread(), null)) {
                                ends.incrementAndGet()
                                if (loseEnd.compareAndSet(true, false)) error("MIGRATION_LIFECYCLE_NATIVE_END_LOST")
                            }
                        }
                        override fun close() { closes.incrementAndGet(); raw.close() }
                    }
                }
                requireNotNull(adapted)
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
                }).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : SupportSQLiteOpenHelper by delegate {
                override val readableDatabase: SupportSQLiteDatabase get() = wrap(delegate.readableDatabase)
                override val writableDatabase: SupportSQLiteDatabase get() = wrap(delegate.writableDatabase)
            }
        }
    }

    private fun disposePreserving(primary: Throwable?, body: () -> Unit) {
        try { body() } catch (error: Throwable) {
            PhysicalDatabaseRule.preserveProcessCleanupFailure(error)
            if (primary == null) throw error
            if (primary !== error) primary.addSuppressed(error)
        }
    }
}
