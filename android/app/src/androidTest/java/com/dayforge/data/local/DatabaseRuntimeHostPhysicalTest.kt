package com.dayforge.data.local

import android.database.Cursor
import android.database.sqlite.SQLiteTransactionListener
import android.os.CancellationSignal
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.widget.BusinessWidgetIsolationRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Actual Hilt Host/whole GraphFactory, controlled Room and original clear. No fake owner,
 * Provider reset or process kill is used to stand in for live dependency replacement. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DatabaseRuntimeHostPhysicalTest {
    @get:Rule(order = 0) val timeout = Timeout.seconds(150)
    @get:Rule(order = 1) val widgets: BusinessWidgetIsolationRule = BusinessWidgetIsolationRule { epochs.cleanupCompleted }
    @get:Rule(order = 2) val epochs: PhysicalDatabaseEpochFixture = PhysicalDatabaseEpochFixture().also {
        it.beforeCleanup = { widgets.owner.requireInstalled() }
    }
    @get:Rule(order = 3) val hilt = HiltAndroidRule(this)
    /** Counts actual delegate entry attempts only. No fault, delay, SQL or supplied DB.
     * Cached statements remain wrapped; a zero count includes every real execution overload.
     */
    private class NativeOperations : SupportSQLiteOpenHelper.Factory {
        val calls = AtomicInteger()
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            val wrapped = IdentityHashMap<SupportSQLiteDatabase, SupportSQLiteDatabase>()
            fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(wrapped) {
                wrapped[raw] ?: object : SupportSQLiteDatabase by raw {
                    override fun beginTransaction() { calls.incrementAndGet(); raw.beginTransaction() }
                    override fun beginTransactionNonExclusive() { calls.incrementAndGet(); raw.beginTransactionNonExclusive() }
                    override fun beginTransactionReadOnly() { calls.incrementAndGet(); raw.beginTransactionReadOnly() }
                    override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) {
                        calls.incrementAndGet(); raw.beginTransactionWithListener(transactionListener)
                    }
                    override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) {
                        calls.incrementAndGet(); raw.beginTransactionWithListenerNonExclusive(transactionListener)
                    }
                    override fun beginTransactionWithListenerReadOnly(transactionListener: SQLiteTransactionListener) {
                        calls.incrementAndGet(); raw.beginTransactionWithListenerReadOnly(transactionListener)
                    }
                    override fun query(query: String): Cursor { calls.incrementAndGet(); return raw.query(query) }
                    override fun query(query: String, bindArgs: Array<out Any?>): Cursor {
                        calls.incrementAndGet(); return raw.query(query, bindArgs)
                    }
                    override fun query(query: SupportSQLiteQuery): Cursor { calls.incrementAndGet(); return raw.query(query) }
                    override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor {
                        calls.incrementAndGet(); return raw.query(query, cancellationSignal)
                    }
                    override fun execSQL(sql: String) { calls.incrementAndGet(); raw.execSQL(sql) }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) { calls.incrementAndGet(); raw.execSQL(sql, bindArgs) }
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        calls.incrementAndGet()
                        val statement = raw.compileStatement(sql)
                        return object : SupportSQLiteStatement by statement {
                            override fun execute() { calls.incrementAndGet(); statement.execute() }
                            override fun executeInsert(): Long { calls.incrementAndGet(); return statement.executeInsert() }
                            override fun executeUpdateDelete(): Int { calls.incrementAndGet(); return statement.executeUpdateDelete() }
                            override fun simpleQueryForLong(): Long { calls.incrementAndGet(); return statement.simpleQueryForLong() }
                            override fun simpleQueryForString(): String? { calls.incrementAndGet(); return statement.simpleQueryForString() }
                        }
                    }
                }.also { wrapped[raw] = it }
            }
            val original = configuration.callback
            val callback = object : SupportSQLiteOpenHelper.Callback(original.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = original.onConfigure(wrap(db))
                override fun onCreate(db: SupportSQLiteDatabase) = original.onCreate(wrap(db))
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    original.onUpgrade(wrap(db), oldVersion, newVersion)
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    original.onDowngrade(wrap(db), oldVersion, newVersion)
                override fun onOpen(db: SupportSQLiteDatabase) = original.onOpen(wrap(db))
                override fun onCorruption(db: SupportSQLiteDatabase) = original.onCorruption(wrap(db))
            }
            val delegate = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : SupportSQLiteOpenHelper by delegate {
                override val writableDatabase get() = wrap(delegate.writableDatabase)
                override val readableDatabase get() = wrap(delegate.readableDatabase)
            }
        }
    }
    private val native = NativeOperations()
    init { epochs.nativeFactory = native }
    @Inject lateinit var preferences: PreferencesManager
    @Inject internal lateinit var graphServices: javax.inject.Provider<DatabaseEpochServices>
    @Inject internal lateinit var graphRooms: DatabaseEpochRoomFactory
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun start() = runBlocking<Unit> { hilt.inject(); epochs.connect(); epochs.login() }
    private fun row(uuid: String = "bc194000-0000-4000-8000-000000000001") = HabitEntity(id = 41,
        name = "Original local habit", habitType = HabitType.COUNTING, iconResId = 1,
        colorHex = "#123456", schedule = HabitSchedule.Daily, targetValue = 3, uuid = uuid)
    private suspend fun seed(value: HabitEntity = row()) = epochs.current { graph -> graph.database.withTransaction {
        assertEquals(value.id, graph.habitDao.insert(value)); value
    } }
    private suspend fun rejected(expected: String, block: suspend () -> Unit) {
        val failure = try { block(); null } catch (error: IllegalStateException) { error }
        assertNotNull("Expected precise rejection $expected", failure)
        assertEquals(expected, failure?.message)
    }

    @Test fun realClearRebuildsTheEntireGraphAndOldInputCannotReachReusedNumericId() = runBlocking<Unit> {
        val original = seed()
        val old = epochs.current { it }
        val source = epochs.habitSelection(original.id)
        val createdBefore = epochs.realRoomCreates
        val (closed, opened) = epochs.clearAndAwaitReplacement()
        assertEquals("Actual close/new graph must use the same configured new-builder again", createdBefore + 1, epochs.realRoomCreates)
        val replacement = seed(original.copy(uuid = "bc194000-0000-4000-8000-000000000002", name = "Replacement"))
        val fresh = epochs.current { it }
        assertSame(old.database, closed); assertSame(fresh.database, opened)
        assertNotSame(old.habitDao, fresh.habitDao); assertNotSame(old.metricDao, fresh.metricDao)
        assertNotSame(old.habitRepository, fresh.habitRepository); assertNotSame(old.nextSyncRuntime, fresh.nextSyncRuntime)
        assertNotSame(old.nextTimerWriter, fresh.nextTimerWriter); assertNotSame(old.replicaRecoveryRepository, fresh.replicaRecoveryRepository)
        val before = epochs.exactMain()
        rejected("DATABASE_EPOCH_INPUT_EXPIRED") { epochs.host.withSourceGraph(source.publication) {
            it.habitRepository.getHabitById(source.id); fail("Old action was admitted to a replacement graph")
        } }
        assertEquals(before, epochs.exactMain())
        val current = epochs.habitSelection(replacement.id)
        epochs.host.withSourceGraph(current.publication) {
            current.requireHabit(requireNotNull(it.habitRepository.getHabitById(current.id)))
        }
        assertEquals(before, epochs.exactMain())
    }

    @Test fun completedClearDoesNotCloseItsOwnerUntilTheOriginalAcceptedCallerReturns() = runBlocking<Unit> {
        seed(); val old = epochs.current { it.database }
        val clearEnded = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val original = async(Dispatchers.IO) { epochs.current { graph ->
            epochs.host.withLocalDataReset { epochs.sessions.exclusive { graph.habitRepository.clearAllData(context) } }
            assertFalse(graph.database.inTransaction()); clearEnded.complete(Unit)
            release.await(); assertTrue(old.isOpen)
        } }
        var waiting: Deferred<HabitDatabase>? = null
        try {
            // Full clear preparation uses the outer 150s bound; the caller join stays separately bounded.
            clearEnded.await()
            waiting = async(start = CoroutineStart.UNDISPATCHED) { epochs.host.withGraph { it.database } }
            assertFalse("New business admission cannot bypass the still-owned clear", waiting.isCompleted)
            assertTrue(old.isOpen)
        } finally { release.complete(Unit); withTimeout(5_000) { original.await() } }
        val fresh = withTimeout(30_000) { requireNotNull(waiting).await() }
        assertNotSame(old, fresh); assertFalse(old.isOpen); assertTrue(fresh.isOpen)
        epochs.current { assertSame(fresh, it.database) }
    }

    @Test fun replacementJoinsOriginalRealRoomObservationCleanupBeforeClosingItsHandle() = runBlocking<Unit> {
        seed(); val old = epochs.current { it.database }
        val observed = CompletableDeferred<Unit>(); val cleanup = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val observer = launch(Dispatchers.IO) {
            epochs.host.observe { graph -> graph.habitDao.getAllHabits().onEach { observed.complete(Unit) }
                .onCompletion { cause -> if (graph.database === old && cause != null) withContext(NonCancellable) {
                    cleanup.complete(Unit); release.await(); assertTrue(old.isOpen)
                } }
            }.collect { }
        }
        var replacement: Deferred<Pair<HabitDatabase, HabitDatabase>>? = null
        try {
            withTimeout(5_000) { observed.await() }
            replacement = async(Dispatchers.IO) { epochs.clearAndAwaitReplacement() }
            withTimeout(5_000) { cleanup.await() }
            assertTrue(old.isOpen); assertFalse(requireNotNull(replacement).isCompleted)
        } finally { release.complete(Unit) }
        try { withTimeout(30_000) { requireNotNull(replacement).await() } }
        finally { observer.cancel(); withTimeout(5_000) { observer.join() } }
        assertFalse(old.isOpen)
    }

    @Test fun sameDatabaseReloginRevokesBothBusinessAndControlInputsWithoutResigningEither() = runBlocking<Unit> {
        seed(); val old = epochs.current { it.database }
        val (business, control) = epochs.current { epochs.host.currentPublication() to epochs.host.currentControlPublication() }
        val before = epochs.exactMain()
        epochs.login()
        epochs.current { assertSame(old, it.database) }
        rejected("DATABASE_EPOCH_INPUT_EXPIRED") { epochs.host.withSourceGraph(business) { fail("Old business source") } }
        rejected("DATABASE_EPOCH_INPUT_EXPIRED") { epochs.host.withControlSourceGraph(control) { fail("Old control source") } }
        assertFalse(business.publish { fail("Late business assignment") })
        assertFalse(control.publish { fail("Late control assignment") })
        assertEquals(before, epochs.exactMain())
    }

    @Test fun controlSourceCanUseOriginalControlReadsButCannotBecomeBusinessOrCrossAcceptBusinessSource() = runBlocking<Unit> {
        val habit = seed()
        val (business, control) = epochs.current { epochs.host.currentPublication() to epochs.host.currentControlPublication() }
        val before = epochs.exactMain()
        epochs.host.withControlSourceGraph(control) { graph ->
            assertEquals(habit, graph.habitRepository.getHabitById(habit.id))
            assertTrue(epochs.host.currentControlPublication().isCurrentInMemory())
        }
        rejected("DATABASE_EPOCH_INPUT_REQUIRED") { epochs.host.withSourceGraph(control) { fail("Control granted business input") } }
        rejected("DATABASE_EPOCH_INPUT_REQUIRED") { epochs.host.withControlSourceGraph(business) { fail("Business substituted for control") } }
        assertEquals(before, epochs.exactMain())
    }

    @Test fun originalClearStillRebuildsWhenTheFollowingRealPreferenceWriteRejectsItsStaleSession() = runBlocking<Unit> {
        val habit = seed(); val stale = requireNotNull(epochs.tokens.localFactAccess()).session
        epochs.login()
        val old = epochs.current { it.database }; val source = epochs.habitSelection(habit.id)
        rejected("HABIT_PREFERENCE_SESSION_CHANGED") { epochs.resetThen {
            preferences.setHabitNotificationEnabled(stale, habit.uuid, false)
        } }
        val fresh = withTimeout(30_000) { epochs.host.withGraph { it.database } }
        assertNotSame(old, fresh); assertFalse(old.isOpen)
        epochs.current { assertNull(it.habitRepository.getHabitById(habit.id)) }
        rejected("DATABASE_EPOCH_INPUT_EXPIRED") { epochs.host.withSourceGraph(source.publication) { fail("Old clear input") } }
        // This is an actual preferences session rejection, not a claim of simulated disk or token-cipher failure.
        assertNotNull(epochs.tokens.localFactAccess())
    }

    @Test fun shutdownRejectsAnAcceptedCallerInsteadOfCancellingItToManufactureDrain() = runBlocking<Unit> {
        seed(); val old = epochs.current { it.database }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val accepted = async(Dispatchers.IO) { epochs.host.withGraph {
            entered.complete(Unit); release.await(); assertTrue(it.database.isOpen)
        } }
        try {
            withTimeout(5_000) { entered.await() }
            rejected("DATABASE_EPOCH_SHUTDOWN_BUSY") { epochs.host.shutdownWhenIdle() }
            assertTrue(accepted.isActive); assertTrue(old.isOpen); assertFalse(epochs.host.lifecycleEnded)
        } finally { release.complete(Unit); withTimeout(5_000) { accepted.await() } }
        epochs.current { assertSame(old, it.database) }
    }
    @Test fun actualShutdownJoinsActiveRoomObservationThenEndsWithoutRestartingTheClosedHost() = runBlocking<Unit> {
        seed(); val old = epochs.current { it.database }
        val created = epochs.realRoomCreates
        val observed = CompletableDeferred<Unit>(); val cleanup = CompletableDeferred<Throwable?>()
        val release = CompletableDeferred<Unit>()
        val entered = AtomicInteger()
        val observer = async(Dispatchers.IO) {
            runCatching {
                epochs.host.observe { graph ->
                    entered.incrementAndGet(); assertSame(old, graph.database)
                    graph.habitDao.getAllHabits().onEach { observed.complete(Unit) }.onCompletion { cause ->
                        withContext(NonCancellable) {
                            cleanup.complete(cause); release.await(); assertTrue(old.isOpen)
                        }
                    }
                }.collect { }
            }.exceptionOrNull()
        }
        var closing: Deferred<Unit>? = null
        try {
            withTimeout(5_000) { observed.await() }
            assertTrue(native.calls.get() > 0)
            closing = async(Dispatchers.IO) { epochs.host.shutdownWhenIdle() }
            val cause = withTimeout(5_000) { cleanup.await() }
            assertTrue(cause is CancellationException)
            assertTrue("Original terminal cancellation must reach real Room Flow cleanup",
                generateSequence(cause) { it.cause }.any { it.message == "DATABASE_EPOCH_OBSERVATION_ENDED" })
            assertFalse(epochs.host.lifecycleEnded); assertTrue(old.isOpen); assertFalse(requireNotNull(closing).isCompleted)
            // New observers arriving after terminal admission closes do not acquire this old
            // lease or queue forever behind its deliberately blocked cleanup.
            val late = withTimeout(5_000) { epochs.host.observe<Int> {
                entered.incrementAndGet(); error("Closed Host admitted a late observation")
            }.firstOrNull() }
            assertNull(late); assertEquals(1, entered.get()); assertEquals(created, epochs.realRoomCreates)
        } finally {
            release.complete(Unit)
            try { closing?.let { withTimeout(5_000) { it.await() } } }
            finally { withTimeout(5_000) { observer.join() } }
        }
        assertNull("Normal lifecycle end must not escape as ordinary failure or restart", observer.await())
        assertEquals(1, entered.get()); assertEquals(created, epochs.realRoomCreates)
        assertFalse(old.isOpen); assertTrue(epochs.host.lifecycleEnded)
    }

    @Test fun closedHostNewObservationCompletesWithoutGraphOrSqlWhenDisplayStampIsAbsent() = runBlocking<Unit> {
        val old = epochs.current { it.database }; val created = epochs.realRoomCreates
        epochs.host.shutdownWhenIdle()
        assertFalse(old.isOpen); assertTrue(epochs.host.lifecycleEnded)
        val afterClose = native.calls.get()
        var entered = 0
        suspend fun finishedWithoutAdmission() {
            assertNull(withTimeout(5_000) { epochs.host.observe {
                entered++; flowOf("not permitted")
            }.firstOrNull() })
            assertEquals(0, entered); assertEquals(created, epochs.realRoomCreates)
            assertEquals("No native query/statement attempt after closure", afterClose, native.calls.get())
        }
        finishedWithoutAdmission()
        rejected("DATABASE_EPOCH_CLOSED") { epochs.host.withGraph { fail("Closed command") } }
        rejected("DATABASE_EPOCH_CLOSED") { epochs.host.withGraph(DatabaseRuntimeHost.Work.CONTROL) { fail("Closed control") } }
        epochs.sessions.exclusive { epochs.tokens.clearTokens() }
        // Logout has a valid anonymous display generation; it is not an absent stamp.
        assertNotNull(epochs.tokens.capturePersonalDisplayStamp())
        finishedWithoutAdmission()
        // Exercise the real transition fence, whose active interval deliberately has no
        // display stamp. A closed observer must finish without waiting for that interval.
        epochs.tokens.withLocalReplicaDisplayTransition {
            assertNull(epochs.tokens.capturePersonalDisplayStamp())
            finishedWithoutAdmission()
        }
        assertNotNull(epochs.tokens.capturePersonalDisplayStamp())
        assertFalse(old.isOpen); assertTrue(epochs.host.lifecycleEnded)
    }

    @Test fun realRoomBusyAfterReadyRemainsRetryableWithoutClosingOrRevokingTheOldGraph() = runBlocking<Unit> {
        val methodScope = this
        val methodOwner = requireNotNull(currentCoroutineContext()[Job])
        // A private real coordinator makes this component classification independent of
        // unrelated account scheduling. It is never used for close, authority or business.
        val componentFactory = DatabaseEpochGraphFactory(graphServices, epochs.tokens,
            com.dayforge.domain.service.AccountSessionCoordinator(), graphRooms)
        val displayLifetime = SupervisorJob()
        val displayScope = CoroutineScope(displayLifetime + Dispatchers.IO)
        val nativeEnded = java.util.concurrent.atomic.AtomicBoolean(false)
        val displayEntered = CompletableDeferred<Unit>(); val releaseDisplay = CompletableDeferred<Unit>()
        val nativeEntered = CompletableDeferred<Unit>(); val releaseNative = CompletableDeferred<Unit>()
        var displayOwner: Deferred<Unit>? = null
        var nativeOwner: Deferred<Unit>? = null
        var old: DatabaseEpochGraph? = null
        var primary: Throwable? = null
        val cleanupFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        fun preserve(failure: Throwable) {
            val first = primary
            if (first == null) primary = failure
            else if (first !== failure && first.suppressed.none { it === failure }) first.addSuppressed(failure)
        }
        epochs.beforeCleanup = {
            // A JUnit interruption cannot let fixture deletion race this method's real Jobs.
            check(methodOwner.isCompleted) { "FACTORY_BUSY_METHOD_NOT_JOINED" }
            cleanupFailure.get()?.let { throw it }
            check(displayLifetime.isCompleted) { "FACTORY_BUSY_DISPLAY_OWNER_NOT_JOINED" }
            widgets.owner.requireInstalled()
        }
        val created = epochs.realRoomCreates
        try {
            old = epochs.current { graph ->
                old = graph // Retain the actual owner even if the original clear fails.
                epochs.host.withLocalDataReset {
                    epochs.sessions.exclusive { graph.habitRepository.clearAllData(context) }
                    // Continue the real publication fence before the original reset fence
                    // ends. This separate method-owned Job carries no Host lease or SQL.
                    displayOwner = displayScope.async {
                        epochs.tokens.withLocalReplicaDisplayTransition {
                            displayEntered.complete(Unit)
                            releaseDisplay.await()
                        }
                    }
                    withTimeout(5_000) { displayEntered.await() }
                }
                graph
            }
            val graph = requireNotNull(old)
            val runtime = requireNotNull(CountTrustRuntime.forDatabase(graph.database))
            assertTrue(runtime.isRetired)
            assertTrue(graph.database.isOpen)
            assertNull(epochs.tokens.capturePersonalDisplayStamp())
            // The original CONTROL lease has returned. Observe actual readiness rather
            // than sleeping for a guessed Room cleanup duration; then introduce BUSY.
            val first = withTimeout(5_000) {
                var observed: DatabaseEpochGraphFactory.Eligibility
                do {
                    observed = componentFactory.mayReplace(graph)
                    if (observed == DatabaseEpochGraphFactory.Eligibility.BUSY) yield()
                } while (observed == DatabaseEpochGraphFactory.Eligibility.BUSY)
                observed
            }
            assertEquals(DatabaseEpochGraphFactory.Eligibility.READY, first)
            nativeOwner = methodScope.async(Dispatchers.IO) {
                graph.database.withTransaction {
                    assertTrue(graph.database.inTransaction())
                    graph.database.openHelper.writableDatabase.query("SELECT 1").use {
                        assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)); assertFalse(it.moveToNext())
                    }
                    nativeEntered.complete(Unit)
                    releaseNative.await()
                }
                nativeEnded.set(true) // Only after this real Room withTransaction/END returned.
            }
            withTimeout(5_000) { nativeEntered.await() }
            assertTrue(runtime.captureGraphDrain(graph.database) is CountTrustOpenHelperFactory.GraphDrain.Busy)
            assertFalse(requireNotNull(nativeOwner).isCompleted)
            val revocations = AtomicInteger()
            val result = componentFactory.closeIfStillEligible(graph) {
                revocations.incrementAndGet()
                error("FACTORY_BUSY_UNEXPECTED_REVOKE")
            }
            assertEquals("A newly BUSY native transaction is not an unknown/deferred recovery state",
                DatabaseEpochGraphFactory.Closed.BUSY, result)
            assertEquals(0, revocations.get())
            assertTrue(graph.database.isOpen)
            assertFalse(runtime.isGraphCloseConfirmed(graph.database))
            assertFalse(runtime.hasUnknownGraphBoundary())
            assertFalse(requireNotNull(nativeOwner).isCompleted)
            assertEquals(created, epochs.realRoomCreates)
        } catch (failure: Throwable) {
            preserve(failure)
        } finally {
            withContext(NonCancellable) {
                // Real Room END must precede release of the publication fence. Both Jobs
                // are joined even when the original-code DEFERRED assertion fails.
                releaseNative.complete(Unit)
                try {
                    withTimeout(5_000) { nativeOwner?.await() }
                    check(nativeOwner == null || nativeEnded.get()) { "FACTORY_BUSY_NATIVE_END_NOT_RETURNED" }
                    old?.let { graph ->
                        val runtime = requireNotNull(CountTrustRuntime.forDatabase(graph.database))
                        check(!runtime.hasUnknownGraphBoundary() &&
                            runtime.captureGraphDrain(graph.database) !is CountTrustOpenHelperFactory.GraphDrain.Unknown) {
                            "FACTORY_BUSY_NATIVE_END_UNKNOWN"
                        }
                    }
                } catch (failure: Throwable) {
                    cleanupFailure.compareAndSet(null, failure); preserve(failure)
                }
                if (cleanupFailure.get() == null) {
                    releaseDisplay.complete(Unit)
                    try {
                        withTimeout(5_000) { displayOwner?.await(); displayLifetime.cancelAndJoin() }
                    } catch (failure: Throwable) {
                        cleanupFailure.compareAndSet(null, failure); preserve(failure)
                    }
                }
                // Unknown/unfinished Room END keeps the actual display owner suspended;
                // do not cancel it (which would run the transition's finally and reopen it).
                // Its private lifetime and all files remain retained behind the existing fence.
                cleanupFailure.get()?.let { PhysicalDatabaseRule.preserveProcessCleanupFailure(it) }
            }
        }
        // This is a Factory component counterexample, followed by the actual Host's
        // original replacement. It does not claim to reproduce 711's unobserved schedule.
        if (cleanupFailure.get() == null && old != null && currentCoroutineContext().isActive) try {
            val fresh = withTimeout(30_000) { epochs.host.withGraph { it.database } }
            val previous = requireNotNull(old).database
            assertNotSame(previous, fresh); assertFalse(previous.isOpen); assertTrue(fresh.isOpen)
            assertTrue(requireNotNull(CountTrustRuntime.forDatabase(previous)).isGraphCloseConfirmed(previous))
            assertEquals(created + 1, epochs.realRoomCreates)
        } catch (failure: Throwable) { preserve(failure) }
        primary?.let { throw it }
    }

}
