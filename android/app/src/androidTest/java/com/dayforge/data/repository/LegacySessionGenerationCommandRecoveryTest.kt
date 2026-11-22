package com.dayforge.data.repository

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.MetricLogEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real historical missing key -> new capture -> original M1 native boundaries and Pending.
 * The old opaque attempt is never rebound; generation does not change command namespace. */
@RunWith(AndroidJUnit4::class)
class LegacySessionGenerationCommandRecoveryTest : NextObjectEditorFixture() {
    private val fault = IndependentMetricNativeFault()
    private val generationKey = stringPreferencesKey("auth_session_generation")
    init { storage.isolatedBuilder = { fault.build(it) } }
    override fun tokenCipher(): TokenCipher = AndroidKeystoreTokenCipher()
    private suspend fun historical(registered: Boolean = true, legacy: Boolean = false) {
        if (registered) register()
        if (legacy) db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            metric = metric.copy(appearance = null); db.metricDao().update(metric)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        dataStore.edit { it.remove(generationKey) }
        assertNull(tokens.localCoreWriteAccess()); assertNotNull(tokens.localOpaqueMetricAccess())
    }
    private suspend fun migrate(): String = sessions.exclusive {
        val before = tokens.captureLegacySessionPreparationWhileAccountLocked()
        assertTrue(before.needsGeneration)
        db.withTransaction { assertNull(ReplicaPresentationRepository(db, tokens, sessions).inspectInTransactionWhileAccountLocked().problem) }
        assertFalse(db.inTransaction())
        (tokens.prepareLegacySessionGenerationWhileAccountLocked(before) as LegacySessionGenerationResult.CreatedGeneration).generation
    }
    private suspend fun capture() = editingMetrics().beginIndependentValue(metric.id, 7.25, "migrated", millis,
        if (metric.appearance == null) null else editingMetrics().getMetricForEditing(metric.id).authority, metric.uuid)
    private fun cold() { storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons) }
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private fun pending(): CountCommandPendingRepository {
        val facts = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions))
        val legacy = LegacyWidgetFactWorkflow(db, tokens, sessions,
            LegacyWidgetIdentityVerifier(db, tokens, ReplicaPresentationRepository(db, tokens, sessions)), creatingHabits(),
            com.dayforge.domain.service.StructuralEditGuard(tokens, io.mockk.mockk()))
        return CountCommandPendingRepository(db, tokens, sessions, creatingHabits(), legacy, facts, preferences, editingMetrics())
    }
    private suspend fun prepared(): Pair<CountCommandReference, IndependentMetricAttempt> {
        val attempt = capture(); val store = CountCommandStore(db, tokens, sessions)
        store.prepareIndependentRoot()
        val registered = store.register(requireNotNull(attempt.intent)); store.releaseUnadmitted(requireNotNull(registered.ticket))
        return requireNotNull(attempt.reference) to attempt
    }
    private suspend fun assertSingle(reference: CountCommandReference) {
        assertEquals(1, count("metric_logs")); assertEquals(1, count("sync_outbox"))
        assertEquals(1, count("count_command_intents")); assertEquals(1, count("count_command_results"))
        assertEquals(CountCommandRecords.Delivery.DELIVERED, CountCommandStore(db, tokens, sessions).resolve(reference.native()).historical.status.delivery)
    }

    @Test(timeout = 150_000) fun migratedT0ActualEndLostColdPendingExplicitlyContinuesTheSameReferenceOnce() = runBlocking<Unit> {
        historical(); val generation = migrate(); val attempt = capture(); val reference = requireNotNull(attempt.reference)
        fault.arm(IndependentMetricNativeFault.Mode.AFTER_T0_END)
        assertNotNull(runCatching { editingMetrics().executeIndependentValue(attempt) }.exceptionOrNull())
        assertTrue(fault.actualEnd); assertEquals(1, fault.hits); assertEquals(0, count("metric_logs"))
        cold(); assertEquals(generation, requireNotNull(tokens.localCoreWriteAccess()).session.authentication.generation)
        val consumer = pending(); val before = image()
        assertTrue(consumer.page().entries.any { it.reference == reference })
        assertTrue(editingMetrics().resolveIndependentValue(reference) is IndependentMetricResolution.Unresolved)
        assertEquals(before, image())
        val saved = consumer.resume(app, consumer.prepare(reference)) as CountCommandPendingRepository.Result.StandaloneMetricSaved
        assertEquals(reference, saved.saved.reference); assertTrue(saved.pending)
        consumer.acknowledgeIndependent(saved) { true }; consumer.acknowledgeIndependent(saved) { true }
        assertSingle(reference)
    }

    @Test(timeout = 150_000) fun migratedT1ActualCommitLostColdPendingFindsSameFactAndOnlyDelivers() = runBlocking<Unit> {
        historical(); migrate(); val attempt = capture(); val reference = requireNotNull(attempt.reference)
        fault.arm(IndependentMetricNativeFault.Mode.AFTER_VALUE_END)
        assertNotNull(runCatching { editingMetrics().executeIndependentValue(attempt) }.exceptionOrNull())
        assertTrue(fault.actualEnd); assertEquals(1, fault.hits)
        cold(); val before = image(); val original = db.metricLogDao().getAllLogsForMetric(metric.id).single()
        val consumer = pending()
        val saved = consumer.resume(app, consumer.prepare(reference)) as CountCommandPendingRepository.Result.StandaloneMetricSaved
        assertEquals(original.uuid, saved.saved.factUuid); assertEquals(reference, saved.saved.reference)
        assertEquals(before, image()); consumer.acknowledgeIndependent(saved) { true }
        val again = consumer.resume(app, consumer.prepare(reference)) as CountCommandPendingRepository.Result.StandaloneMetricSaved
        assertFalse(again.pending); assertEquals(original.uuid, again.saved.factUuid)
        consumer.acknowledgeIndependent(again) { true }; assertSingle(reference)
    }

    @Test(timeout = 150_000) fun migratedT1KnownRollbackKeepsPreparedAndExplicitColdPendingCreatesOnlyOneFact() = runBlocking<Unit> {
        historical(); migrate(); val attempt = capture(); val reference = requireNotNull(attempt.reference)
        fault.arm(IndependentMetricNativeFault.Mode.DEFERRED_VALUE_END)
        assertNotNull(runCatching { editingMetrics().executeIndependentValue(attempt) }.exceptionOrNull())
        assertEquals(1, fault.deferredEndFailures); assertTrue(fault.stagedOutcomeObserved)
        assertEquals(0, count("metric_logs")); assertEquals(0, count("sync_outbox")); assertEquals(0, count("count_command_results"))
        cold(); val consumer = pending()
        val saved = consumer.resume(app, consumer.prepare(reference)) as CountCommandPendingRepository.Result.StandaloneMetricSaved
        assertEquals(reference, saved.saved.reference); consumer.acknowledgeIndependent(saved) { true }; assertSingle(reference)
    }

    @Test(timeout = 150_000) fun migratedT2ActualCommitLostColdPendingDoesNotReplayTheFactOrRotateSession() = runBlocking<Unit> {
        historical(); val generation = migrate(); val saved = editingMetrics().executeIndependentValue(capture())
        val reference = requireNotNull(saved.reference); val original = db.metricLogDao().getAllLogsForMetric(metric.id).single()
        fault.arm(IndependentMetricNativeFault.Mode.AFTER_T2_END)
        assertNotNull(runCatching { editingMetrics().acknowledgeIndependentValue(saved) { true } }.exceptionOrNull())
        assertTrue(fault.actualEnd); assertTrue(fault.deliveryObserved); assertEquals(1, fault.hits)
        cold(); val before = image(); val consumer = pending()
        assertFalse(consumer.isPending(reference))
        val shown = consumer.resume(app, consumer.prepare(reference)) as CountCommandPendingRepository.Result.StandaloneMetricSaved
        assertEquals(original.uuid, shown.saved.factUuid); assertEquals(reference, shown.saved.reference); assertFalse(shown.pending)
        consumer.acknowledgeIndependent(shown) { true }; consumer.acknowledgeIndependent(shown) { true }
        assertEquals(before, image()); assertEquals(generation, requireNotNull(tokens.localCoreWriteAccess()).session.authentication.generation)
        assertSingle(reference)
    }

    @Test(timeout = 150_000) fun oldOpaqueCapturedInputCannotSwitchToNewCanonicalLaneAfterPreparation() = runBlocking<Unit> {
        historical(legacy = true); val old = capture()
        assertNull(old.intent); assertNull(old.reference); assertNotNull(old.opaque)
        migrate(); val before = image()
        assertNotNull(runCatching { editingMetrics().executeIndependentValue(old) }.exceptionOrNull())
        assertEquals(before, image()); assertEquals(0, count("metric_logs")); assertEquals(0, count("count_command_intents"))
        val fresh = capture(); assertNotNull(fresh.reference); assertNull(fresh.opaque)
        val saved = editingMetrics().executeIndependentValue(fresh)
        assertEquals(fresh.reference, saved.reference); assertEquals(1, count("metric_logs")); assertEquals(1, count("count_command_intents"))
    }

    @Test(timeout = 150_000) fun migratedLegacyWriterPreservesWholeOriginalNoAppendPermissionAndPayloadMatrix() = runBlocking<Unit> {
        historical(legacy = true)
        tokens.saveDeviceRegistration(id(4), caps - "facts.append", true, 2)
        assertFalse(requireNotNull(tokens.localFactAccess()).canAppend)
        val originalId = editingMetrics().recordValue(metric.id, 7.25, "migrated", millis, expectedMetricUuid = metric.uuid)
        val original = requireNotNull(db.metricLogDao().getById(originalId))
        migrate(); assertNull(tokens.localOpaqueMetricAccess()); assertFalse(requireNotNull(tokens.localFactAccess()).canAppend)
        val fresh = capture(); assertNotNull(fresh.reference)
        val saved = editingMetrics().executeIndependentValue(fresh)
        val actual = requireNotNull(db.metricLogDao().getById(saved.factId))
        assertEquals(original.metricId, actual.metricId); assertEquals(original.value, actual.value, 0.0)
        assertEquals(original.unit, actual.unit); assertEquals(original.note, actual.note); assertEquals(original.date, actual.date)
        assertNotEquals(original.uuid, actual.uuid); assertEquals(2, count("metric_logs")); assertEquals(2, count("sync_outbox"))
        assertEquals(1, count("count_command_intents")); assertEquals(1, count("count_command_results"))
    }

    @Test(timeout = 150_000) fun migrationBeforeFirstRegistrationKeepsHistoricalSavedAndPreparedExecutionBoundaries() = runBlocking<Unit> {
        historical(registered = false, legacy = true); tokens.getOrCreateInstallationId(); migrate()
        val saved = editingMetrics().executeIndependentValue(capture()); val savedRef = requireNotNull(saved.reference)
        val (preparedRef, _) = prepared()
        register(); CountCommandStore(db, tokens, sessions).acknowledgeInitialRegistration(); cold()
        val before = image()
        val historical = editingMetrics().resolveIndependentValue(savedRef, true) as IndependentMetricResolution.Saved
        assertEquals(saved.factUuid, historical.value.factUuid); assertFalse(historical.value.deliveryAllowed)
        assertNotNull(runCatching { editingMetrics().acknowledgeIndependentValue(historical.value) { true } }.exceptionOrNull())
        val blocked = editingMetrics().resolveIndependentValue(preparedRef, true) as IndependentMetricResolution.Unresolved
        assertEquals("INITIAL_REGISTRATION_EXECUTION_PROOF_REQUIRED", blocked.reason)
        assertNotNull(runCatching { pending().prepare(preparedRef) }.exceptionOrNull())
        assertEquals(before, image()); assertEquals(1, count("metric_logs"))
    }

    @Test(timeout = 150_000) fun advancedBusinessWithReservedEffectCannotUseMissingOutcomeAsNoEffectsAfterMigration() = runBlocking<Unit> {
        historical(); migrate(); val (reference, attempt) = prepared()
        val reserved = CountCommandRecords.uuid(IndependentMetricInput.input(requireNotNull(attempt.intent)), "reserved_fact_uuid")
        db.metricLogDao().insert(MetricLogEntity(metricId = metric.id, date = millis, value = 7.25, unit = metric.unit, uuid = reserved))
        val before = image(); val store = CountCommandStore(db, tokens, sessions)
        val fast = store.recover(reference.native()) as CountCommandStore.Recovery.Unresolved
        assertEquals("ADVANCED_BUSINESS_REQUIRES_COMPLETE_NO_EFFECTS_PROOF", fast.reason)
        val resolved = runCatching { editingMetrics().resolveIndependentValue(reference, true) }
        assertTrue(resolved.isFailure || resolved.getOrNull() is IndependentMetricResolution.Unresolved)
        val ending = runCatching { editingMetrics().prepareIndependentEnd(reference) }
        assertTrue(ending.isFailure || ending.getOrNull() == null)
        assertEquals(before, image()); assertEquals(0, count("count_command_results"))
    }
}
