package com.dayforge.data.local

import android.content.Context
import android.net.ConnectivityManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.NextReplicaFenceEntity
import com.dayforge.data.repository.NextReplicaWriteFence
import com.dayforge.data.repository.ReplicaPresentationProblem
import com.dayforge.di.DatabaseModule
import com.dayforge.domain.service.DeviceCalendar
import com.dayforge.domain.service.DeviceThemeController
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

@HiltAndroidTest @UninstallModules(DatabaseModule::class) @RunWith(AndroidJUnit4::class)
class LegacySessionGenerationHostTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(150)
    @get:Rule(order = 1) internal val preferences = LegacySessionHostPreferences()
    private val native = LegacySessionNativeInitializationProbe()
    @get:Rule(order = 2) val epochs = PhysicalDatabaseEpochFixture().also {
        it.nativeFactory = native; preferences.bind(it)
        val original = it.beforeCleanup
        it.beforeCleanup = { original?.invoke(); closeTheme() }
    }
    @get:Rule(order = 3) val hilt = HiltAndroidRule(this)
    @Inject lateinit var themes: DeviceThemeController
    private var themeClosed = false
    private var themeCleanupFailure: Throwable? = null
    private val generationKey = stringPreferencesKey("auth_session_generation")
    @Before fun start() { hilt.inject(); epochs.connect() }
    @After fun finish() = closeTheme()
    private fun preparedTest(block: suspend CoroutineScope.() -> Unit) = preferences.ownedTest {
        withTimeout(45_000) { themes.current() }
        assertEquals(0, epochs.realRoomCreates); assertNull(generation())
        preferences.store.committedChanges.set(0)
        block()
    }
    private fun closeTheme() = runBlocking<Unit> {
        themeCleanupFailure?.let { throw it }
        if (themeClosed) return@runBlocking
        try {
            preferences.drainMethod()
            if (::themes.isInitialized) withTimeout(5_000) { themes.close() }
            themeClosed = true
        } catch (error: Throwable) {
            themeCleanupFailure = error; preferences.preserve(error); throw error
        }
    }
    private suspend fun generation() = preferences.store.data.first()[generationKey]
    private suspend fun control() = epochs.host.withGraph(DatabaseRuntimeHost.Work.CONTROL) { it }
    private suspend fun denied(block: suspend () -> Unit) {
        val failure = try { block(); null } catch (error: DatabaseRuntimeHost.SessionPreparationUnavailable) { error }
        assertNotNull("Unprepared business must not be entered", failure)
    }
    private suspend fun failPreparation(): SessionPreparationState.RetryableFailure {
        preferences.store.observeNextGenerationChange(afterTransform = { throw IOException("synthetic preparation storage failure") })
        control()
        return epochs.host.sessionPreparation.value as SessionPreparationState.RetryableFailure
    }
    private fun orphan() = NextReplicaFenceEntity(legacyGenerationId(11), legacyGenerationId(1), legacyGenerationId(2),
        legacyGenerationId(3), legacyGenerationId(12), legacyGenerationId(13), legacyGenerationId(4), 3, "a".repeat(64))

    @Test fun actualColdFactoryPreparesOnceBeforeBusinessAndDoesNotDeadlockItsOwnSourceListener() = preparedTest {
        val observed = AtomicReference<Pair<Boolean, Int>?>()
        preferences.store.observeNextGenerationChange(afterTransform = {
            observed.set(native.selected.get() to native.actualEnds.get())
        })
        val graph = withTimeout(45_000) { epochs.host.withGraph { it } }
        val evidence = requireNotNull(observed.get()) { "GENERATION_TARGET_TRANSFORM_NOT_OBSERVED" }
        assertTrue("Original Room control SELECT must really be stepped", evidence.first)
        assertEquals("Generation writes start only after the original actual native END", 1, evidence.second)
        assertTrue(graph.database.isOpen); assertFalse(graph.database.inTransaction())
        assertNotNull(generation()); assertTrue(epochs.host.sessionPreparation.value is SessionPreparationState.Satisfied)
        assertEquals(1, preferences.store.committedChanges.get()); assertEquals(1, epochs.realRoomCreates)
        val original = generation()
        coroutineScope { List(6) { async(Dispatchers.IO) { epochs.host.withGraph { assertSame(graph, it) } } }.awaitAll() }
        assertEquals(original, generation()); assertEquals(1, preferences.store.committedChanges.get())
    }

    @Test fun storageFailurePreservesControlAndRequiresExplicitMetadataOnlyRetry() = preparedTest {
        val failed = failPreparation(); val original = failed.cause
        val graph = control()
        assertTrue(original is IOException); assertSame(failed, epochs.host.sessionPreparation.value)
        assertNull(generation())
        denied { epochs.host.withGraph { fail("Ordinary callback cannot run") } }
        epochs.host.withGraph(DatabaseRuntimeHost.Work.CONTROL) {
            assertSame(graph, it)
            denied { epochs.host.withGraph { fail("Nested ordinary callback cannot inherit control") } }
        }
        assertEquals(7, epochs.host.tryWithActiveGraph {
            denied { epochs.host.withGraph { fail("Optional maintenance cannot become ordinary admission") } }
            7
        })
        assertSame(failed, epochs.host.sessionPreparation.value)
        val retried = epochs.host.retrySessionPreparation(failed.attempt) as SessionPreparationRetry.Completed
        assertTrue(retried.state is SessionPreparationState.Satisfied)
        val saved = generation(); assertNotNull(saved); assertEquals(1, preferences.store.committedChanges.get())
        epochs.host.withGraph { assertSame(graph, it) }
        assertEquals(saved, generation())
    }

    @Test fun loginBRemainsAvailableAndLateAccountARetryIsStale() = preparedTest {
        val old = failPreparation().attempt
        epochs.host.withGraph(DatabaseRuntimeHost.Work.CONTROL) {
            epochs.sessions.exclusive {
                epochs.tokens.saveLoginSession("synthetic-b", "synthetic-b-refresh", "B", legacyGenerationId(50), false)
            }
        }
        val b = generation(); val before = preferences.store.data.first()
        assertSame(SessionPreparationRetry.Stale, epochs.host.retrySessionPreparation(old))
        assertEquals(before, preferences.store.data.first())
        control()
        assertTrue(epochs.host.sessionPreparation.value is SessionPreparationState.Satisfied)
        assertEquals(b, generation()); assertEquals(legacyGenerationId(50), epochs.tokens.userId.first())
    }

    @Test fun sqlFrozenAndUnmatchedClosedFenceDeferOnlyPreparationAndKeepControl() = preparedTest {
        val failed = failPreparation(); val graph = control(); val sentinel = orphan()
        epochs.sessions.exclusive { NextReplicaWriteFence(graph.database).acquire(sentinel) }
        val first = epochs.host.retrySessionPreparation(failed.attempt) as SessionPreparationRetry.Completed
        val deferred = first.state as SessionPreparationState.DeferredFence
        assertEquals(ReplicaPresentationProblem.ORPHAN_STAGE, deferred.problem.problem)
        assertNull(generation()); denied { epochs.host.withGraph { fail("Frozen ordinary callback") } }
        control(); assertEquals(sentinel, graph.database.nextReplicaFenceDao().state())
        epochs.sessions.exclusive { NextReplicaWriteFence(graph.database).closeBeforeCommit(sentinel) }
        val closed = epochs.host.retrySessionPreparation(deferred.attempt) as SessionPreparationRetry.Completed
        assertTrue(closed.state is SessionPreparationState.DeferredFence)
        assertNull(generation()); assertEquals(0, preferences.store.committedChanges.get())
    }

    @Test fun lostPointerNonterminalJournalStillDefersAndNeverGetsGeneration() = preparedTest {
        preferences.store.edit { it[generationKey] = legacyGenerationId(20) }
        val stage = epochs.tokens.replicaStageFixture()
        epochs.tokens.persistReplicaStage(null, stage)
        preferences.store.edit { it.remove(generationKey); it.remove(ReplicaSwitchGuard.POINTER) }
        control()
        val state = epochs.host.sessionPreparation.value as SessionPreparationState.DeferredFence
        assertEquals(ReplicaPresentationProblem.ORPHAN_STAGE, state.problem.problem)
        assertNull(generation()); assertEquals(stage, epochs.tokens.replicaStage(stage.recoveryId))
        denied { epochs.host.withGraph { fail("Lost pointer is not an open replica") } }
    }

    @Test fun damagedJournalRetainsOriginalCauseAndControlWithoutNormalizingAnything() = preparedTest {
        preferences.store.edit { it[ReplicaSwitchStage.key(legacyGenerationId(21))] = "synthetic-invalid-journal" }
        val before = preferences.store.data.first()
        control()
        val state = epochs.host.sessionPreparation.value as SessionPreparationState.DeferredFence
        assertEquals(ReplicaPresentationProblem.DAMAGED_STATE, state.problem.problem)
        assertNotNull(state.cause); assertEquals(before, preferences.store.data.first()); assertNull(generation())
        denied { epochs.host.withGraph { fail("Damaged metadata is not ready") } }
    }

    private suspend fun damagedSqlFenceKeepsControl(fault: LegacySessionNativeInitializationProbe.DamagedFence) {
        native.initialDamagedFence.set(fault)
        val preferencesBefore = preferences.store.data.first()
        val graph = control() // FIRST Factory publication, not an already-published retry shortcut.
        // Validate the real fault before interpreting readiness. An opening retry cannot turn
        // a rejected INSERT into a missing-row success and then masquerade as a decoder result.
        assertEquals(1, native.successfulFenceFaults.get()); assertNull(native.initialDamagedFence.get())
        val expectedFence = listOf("integer", "1") + listOf(11, 1, 2, 3, 12, 13, 4).flatMap {
            listOf("text", "'${legacyGenerationId(it)}'")
        } + listOf("integer", if (fault == LegacySessionNativeInitializationProbe.DamagedFence.REVISION) "0" else "3") +
            (if (fault == LegacySessionNativeInitializationProbe.DamagedFence.NATIVE_TYPE) listOf("blob", "X'0102'")
             else listOf("text", "'${"a".repeat(64)}'")) +
            listOf("text", if (fault == LegacySessionNativeInitializationProbe.DamagedFence.MODE) "'broken'" else "'frozen'")
        assertEquals(expectedFence, native.initialFenceImage)
        if (fault == LegacySessionNativeInitializationProbe.DamagedFence.MODE) {
            val evidence = requireNotNull(native.modeFaultEvidence.get())
            assertTrue(evidence.rejectedInsert.message?.contains("SYNC_REPLICA_SWITCH_FENCED") == true)
            assertEquals(1, native.modeFaultActualEnds.get())
            assertEquals(evidence.schemaBefore, evidence.schemaAfter)
            assertTrue("The schema cookie advanced naturally, never reset", evidence.schemaVersionAfter > evidence.schemaVersionBefore)
        } else {
            assertNull(native.modeFaultEvidence.get()); assertEquals(0, native.modeFaultActualEnds.get())
        }
        graph.database.withTransaction {
            assertEquals(expectedFence, native.currentFenceImage(graph.database.openHelper.writableDatabase))
        }
        val state = epochs.host.sessionPreparation.value as SessionPreparationState.DeferredFence
        assertEquals(ReplicaPresentationProblem.DAMAGED_STATE, state.problem.problem)
        assertNotNull(state.cause)
        if (fault == LegacySessionNativeInitializationProbe.DamagedFence.NATIVE_TYPE)
            assertEquals("SYNC_REPLICA_FENCE_INVALID", state.cause?.message)
        else assertTrue(state.cause is IllegalArgumentException)
        assertTrue(native.selected.get()); assertEquals(1, native.actualEnds.get())
        assertNull(generation()); assertEquals(preferencesBefore, preferences.store.data.first())
        assertEquals(0, preferences.store.committedChanges.get())
        graph.database.withTransaction {
            assertEquals(native.initialFenceImage, native.currentFenceImage(graph.database.openHelper.writableDatabase))
            val original = try { graph.replicaPresentationRepository.readFenceInTransaction(); null }
                catch (failure: RuntimeException) { failure }
            assertEquals(state.cause?.javaClass, original?.javaClass)
            assertEquals(state.cause?.message, original?.message)
        }
        denied { epochs.host.withGraph { fail("Damaged SQL cannot admit ordinary work") } }
        epochs.host.withGraph(DatabaseRuntimeHost.Work.CONTROL) {
            assertSame(graph, it)
            assertTrue(epochs.host.currentControlPublication().isCurrentInMemory())
            denied { epochs.host.withGraph { fail("Damaged SQL cannot inherit CONTROL for business") } }
        }
        val retried = epochs.host.retrySessionPreparation(state.attempt) as SessionPreparationRetry.Completed
        val stillDamaged = retried.state as SessionPreparationState.DeferredFence
        assertEquals(ReplicaPresentationProblem.DAMAGED_STATE, stillDamaged.problem.problem)
        assertNotNull(stillDamaged.cause); assertNull(generation())
        assertEquals(preferencesBefore, preferences.store.data.first())
        // Same original CONTROL path used by login remains available; it never repairs SQL.
        epochs.host.withGraph(DatabaseRuntimeHost.Work.CONTROL) {
            epochs.sessions.exclusive {
                epochs.tokens.saveLoginSession("synthetic-b", "synthetic-b-refresh", "B", legacyGenerationId(50), false)
            }
        }
        assertEquals(legacyGenerationId(50), epochs.tokens.userId.first())
        epochs.host.withGraph(DatabaseRuntimeHost.Work.CONTROL) { current ->
            epochs.sessions.exclusive {
                current.database.withTransaction {
                    assertEquals(native.initialFenceImage, native.currentFenceImage(current.database.openHelper.writableDatabase))
                }
                assertFalse(current.replicaPresentationRepository.isOpenWhileAccountLocked())
            }
        }
    }

    @Test fun coldMalformedSqlModeKeepsControlButNeverPreparesBusiness() = preparedTest {
        damagedSqlFenceKeepsControl(LegacySessionNativeInitializationProbe.DamagedFence.MODE)
    }
    @Test fun coldNonPositiveSqlRevisionKeepsControlButNeverPreparesBusiness() = preparedTest {
        damagedSqlFenceKeepsControl(LegacySessionNativeInitializationProbe.DamagedFence.REVISION)
    }
    @Test fun coldWrongNativeSqlTypeKeepsControlButNeverPreparesBusiness() = preparedTest {
        damagedSqlFenceKeepsControl(LegacySessionNativeInitializationProbe.DamagedFence.NATIVE_TYPE)
    }
    @Test fun nativeSqlQueryFailureStillFailsThePhysicalHostAndDoesNotBecomeDamagedReadiness() = preparedTest {
        native.failFenceQuery.set(true)
        val preferencesBefore = preferences.store.data.first()
        // The first initializer propagates the original physical cause; later callers get Unavailable.
        val failure = try { control(); null } catch (error: Exception) { error }
        assertNotNull(failure)
        assertTrue(generateSequence<Throwable>(requireNotNull(failure)) { it.cause }
            .any { it is android.database.sqlite.SQLiteException })
        assertEquals(1, native.failedFenceQueries.get())
        assertTrue(native.selected.get()); assertEquals(1, native.actualEnds.get())
        assertFalse(epochs.host.sessionPreparation.value is SessionPreparationState.Satisfied)
        assertFalse(epochs.host.sessionPreparation.value is SessionPreparationState.DeferredFence)
        assertNull(generation()); assertEquals(preferencesBefore, preferences.store.data.first())
        val deniedControl = try {
            epochs.host.withGraph(DatabaseRuntimeHost.Work.CONTROL) { fail("Physical failure cannot become CONTROL ready") }
            null
        } catch (error: DatabaseRuntimeHost.Unavailable) { error }
        assertNotNull(deniedControl); assertEquals(1, epochs.realRoomCreates)
        assertEquals(0, preferences.store.committedChanges.get())
    }

    @Test fun generationCommittedThenFirstPublisherCancelledColdContinuesWithoutAnotherGeneration() = preparedTest {
        val saved = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        preferences.store.observeNextGenerationChange(afterPersistence = { saved.complete(Unit); release.await() })
        val tasks = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = tasks.async { epochs.host.withGraph { fail("Cancelled initializer cannot execute business") } }
        try {
            withTimeout(45_000) { saved.await() }; val savedGeneration = requireNotNull(generation())
            first.cancel(); release.complete(Unit)
            try { withTimeout(5_000) { first.await() }; fail("Cancellation must propagate") }
            catch (_: CancellationException) { assertTrue(first.isCancelled) }
            val fresh = withTimeout(45_000) { epochs.host.withGraph { it.database } }
            assertTrue(fresh.isOpen); assertEquals(savedGeneration, generation())
            assertEquals(1, preferences.store.committedChanges.get())
            assertTrue(epochs.host.sessionPreparation.value is SessionPreparationState.Satisfied)
        } finally {
            release.complete(Unit); withTimeout(5_000) { first.cancelAndJoin() }; tasks.cancel()
        }
    }

    @Test fun existingCanonicalSessionRetainsOriginalRecoveryControlContract() = preparedTest {
        val graph = epochs.host.withGraph { it }; val saved = generation()
        epochs.sessions.exclusive { NextReplicaWriteFence(graph.database).acquire(orphan()) }
        epochs.host.withGraph(DatabaseRuntimeHost.Work.CONTROL) {
            assertSame(graph, it)
            assertEquals(ReplicaPresentationProblem.ORPHAN_STAGE, it.replicaPresentationRepository.inspect().problem)
        }
        assertEquals(saved, generation())
        assertTrue(epochs.host.sessionPreparation.value is SessionPreparationState.Satisfied)
    }

    @Test fun actualPersistedGenerationWithLostReturnAllowsExplicitReadinessRetryWithoutRotation() = preparedTest {
        preferences.store.observeNextGenerationChange(afterPersistence = { throw IOException("synthetic persisted return lost") })
        control()
        val failed = epochs.host.sessionPreparation.value as SessionPreparationState.RetryableFailure
        assertTrue(failed.cause is IOException)
        val saved = requireNotNull(generation()); assertEquals(1, preferences.store.committedChanges.get())
        denied { epochs.host.withGraph { fail("Lost caller return must not automatically run a business callback") } }
        val retried = epochs.host.retrySessionPreparation(failed.attempt) as SessionPreparationRetry.Completed
        val ready = retried.state as SessionPreparationState.Satisfied
        assertEquals(SessionPreparationDisposition.CURRENT_GENERATION, ready.disposition)
        assertEquals(saved, generation()); assertEquals(1, preferences.store.committedChanges.get())
    }

    @Test fun ordinaryObservationWaitsAcrossPreparationFailureThenCapturesOnlyAfterExplicitRetry() = preparedTest {
        val failed = failPreparation()
        val started = java.util.concurrent.atomic.AtomicInteger()
        val values = async(start = CoroutineStart.UNDISPATCHED) {
            epochs.host.observe { started.incrementAndGet(); flowOf(42) }.first()
        }
        try {
            yield(); assertFalse(values.isCompleted); assertEquals(0, started.get())
            epochs.host.retrySessionPreparation(failed.attempt)
            val result = withTimeout(5_000) { values.await() }
            assertEquals(42, result.value); assertTrue(result.publication.isCurrentInMemory())
            assertEquals(1, started.get())
        } finally { withTimeout(5_000) { values.cancelAndJoin() } }
    }

    @Test fun observationWaitingWithoutLeaseEndsNormallyWhenHostShutsDown() = preparedTest {
        failPreparation()
        val started = java.util.concurrent.atomic.AtomicInteger()
        val values = async(start = CoroutineStart.UNDISPATCHED) {
            epochs.host.observe { started.incrementAndGet(); flowOf(42) }.toList()
        }
        try {
            yield(); assertFalse(values.isCompleted); assertEquals(0, started.get())
            withTimeout(5_000) { epochs.host.shutdownWhenIdle() }
            assertTrue(withTimeout(5_000) { values.await() }.isEmpty())
            assertEquals(0, started.get()); assertTrue(epochs.host.lifecycleEnded)
        } finally { withTimeout(5_000) { values.cancelAndJoin() } }
    }

    @Test fun preparationInvalidatesOriginalControlSourceAndNeverRunsItsLateCallback() = preparedTest {
        val failed = failPreparation()
        val old = epochs.host.withGraph(DatabaseRuntimeHost.Work.CONTROL) { epochs.host.currentControlPublication() }
        assertTrue(old.isCurrentInMemory())
        epochs.host.retrySessionPreparation(failed.attempt)
        assertFalse(old.isCurrentInMemory())
        var callbacks = 0
        val expired = try {
            epochs.host.withControlSourceGraph(old) { callbacks++ }; null
        } catch (error: IllegalStateException) { error }
        assertEquals("DATABASE_EPOCH_INPUT_EXPIRED", expired?.message); assertEquals(0, callbacks)
        epochs.host.withGraph(DatabaseRuntimeHost.Work.CONTROL) {
            assertTrue(epochs.host.currentControlPublication().isCurrentInMemory())
        }
    }

    @Module @InstallIn(SingletonComponent::class)
    object PreferencesModule {
        @Provides @Singleton fun data(): DataStore<Preferences> = LegacySessionHostPreferences.active().store
        @Provides @Singleton fun cipher(): TokenCipher = LegacySessionHostPreferences.active().cipher
        @Provides @Singleton fun preferences(data: DataStore<Preferences>, calendar: DeviceCalendar, tokens: TokenManager) =
            PreferencesManager(data, calendar, tokens)
        @Provides @Singleton fun connectivity(@ApplicationContext context: Context): ConnectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
}
