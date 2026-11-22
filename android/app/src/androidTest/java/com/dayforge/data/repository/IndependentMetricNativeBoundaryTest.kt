package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountCommandStorageInitialization
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.CountTrustRetirementReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndependentMetricNativeBoundaryTest : NextObjectEditorFixture() {
    private val fault = IndependentMetricNativeFault()
    init { storage.isolatedBuilder = { fault.build(it) } }
    private suspend fun capture(): IndependentMetricAttempt {
        val selected = editingMetrics().getMetricForEditing(metric.id)
        return editingMetrics().beginIndependentValue(metric.id, 4.25, "native", millis, selected.authority, metric.uuid)
    }
    private fun cold() { storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons) }
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private suspend fun failure(attempt: IndependentMetricAttempt): Throwable {
        val rejected = runCatching { editingMetrics().executeIndependentValue(attempt) }.exceptionOrNull()
        assertNotNull(rejected)
        assertEquals(1, fault.hits)
        return requireNotNull(rejected)
    }

    @Test(timeout = 150_000) fun t0BeforeInsertIsUnregisteredAndOnlyExplicitContinuationRegistersOriginalInput() = runBlocking<Unit> {
        register()
        val attempt = capture()
        fault.arm(IndependentMetricNativeFault.Mode.BEFORE_T0_INSERT)
        failure(attempt)
        assertEquals(0, count("count_command_intents")); assertEquals(0, count("metric_logs"))
        assertTrue(editingMetrics().resolveIndependentValue(attempt.reference!!) is IndependentMetricResolution.Unregistered)
        val result = editingMetrics().resolveIndependentAttempt(attempt, true) as IndependentMetricResolution.Saved
        assertEquals(attempt.reference, result.value.reference)
        assertEquals(1, count("count_command_intents")); assertEquals(1, count("metric_logs"))
    }

    @Test(timeout = 150_000) fun committedT0WithLostEndResultNeverAutomaticallyWritesTheValue() = runBlocking<Unit> {
        register()
        val attempt = capture()
        fault.arm(IndependentMetricNativeFault.Mode.AFTER_T0_END)
        failure(attempt); assertTrue(fault.actualEnd)
        assertEquals(1, count("count_command_intents")); assertEquals(0, count("metric_logs"))
        cold()
        val before = image()
        val observed = editingMetrics().resolveIndependentValue(attempt.reference!!)
        assertTrue(observed is IndependentMetricResolution.Unresolved)
        assertEquals(before, image())
        val saved = editingMetrics().resolveIndependentValue(attempt.reference!!, true) as IndependentMetricResolution.Saved
        assertEquals(attempt.reference, saved.value.reference)
        assertEquals(1, count("metric_logs")); assertEquals(1, count("sync_outbox"))
    }

    @Test(timeout = 150_000) fun actualRoomExecuteFailureRollsBackT1AndExplicitRecoveryCreatesExactlyOneFact() = runBlocking<Unit> {
        register()
        val attempt = capture()
        fault.arm(IndependentMetricNativeFault.Mode.BEFORE_VALUE_EXECUTE)
        failure(attempt)
        assertTrue(fault.executeEntries > 0)
        assertEquals(0, count("metric_logs")); assertEquals(0, count("sync_outbox")); assertEquals(0, count("count_command_results"))
        val before = image()
        assertTrue(editingMetrics().resolveIndependentValue(attempt.reference!!) is IndependentMetricResolution.Unresolved)
        assertEquals(before, image())
        val saved = editingMetrics().resolveIndependentValue(attempt.reference!!, true) as IndependentMetricResolution.Saved
        assertEquals(attempt.reference, saved.value.reference)
        assertEquals(1, count("metric_logs")); assertEquals(1, count("sync_outbox")); assertEquals(1, count("count_command_results"))
    }

    @Test(timeout = 150_000) fun actualT1CommitWithLostEndResultResolvesAfterColdReopenWithoutASecondFact() = runBlocking<Unit> {
        register()
        val attempt = capture()
        fault.arm(IndependentMetricNativeFault.Mode.AFTER_VALUE_END)
        failure(attempt); assertTrue(fault.actualEnd); assertTrue(fault.executeEntries > 0)
        assertEquals(1, count("metric_logs")); assertEquals(1, count("sync_outbox")); assertEquals(1, count("count_command_results"))
        cold()
        val before = image()
        val saved = editingMetrics().resolveIndependentValue(attempt.reference!!) as IndependentMetricResolution.Saved
        assertEquals(attempt.reference, saved.value.reference)
        assertEquals(before, image())
        val again = editingMetrics().resolveIndependentValue(attempt.reference!!, true) as IndependentMetricResolution.Saved
        assertEquals(saved.value.factUuid, again.value.factUuid)
        assertEquals(before, image())
    }

    @Test(timeout = 150_000) fun sameValueExtraNativeUpdateIsRejectedByCompleteJournalAndLeavesOnlyT0() = runBlocking<Unit> {
        register()
        val attempt = capture()
        fault.arm(IndependentMetricNativeFault.Mode.EXTRA_VALUE_NOOP)
        failure(attempt); assertTrue(fault.executeEntries > 0)
        assertEquals(0, count("metric_logs")); assertEquals(0, count("sync_outbox")); assertEquals(0, count("next_request_origins"))
        assertEquals(1, count("count_command_intents")); assertEquals(0, count("count_command_results"))
    }

    @Test(timeout = 150_000) fun unchangedStoreRecoveryWitnessReusesOriginalNoEffectsWithoutRejectingUnrelatedNativeLegacyIdentity() = runBlocking<Unit> {
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.metricDao().update(metric.copy(appearance = null))
            db.metricLogDao().insert(com.dayforge.data.local.entity.MetricLogEntity(metricId = metric.id,
                date = millis - 1, value = 2.0, unit = metric.unit, uuid = "old native fact identity"))
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        // The whole old repository is the legality oracle, not an access flag.
        val original = editingMetrics().recordValue(metric.id, 3.0, "old", millis, expectedMetricUuid = metric.uuid)
        assertTrue(original > 0)
        val attempt = editingMetrics().beginIndependentValue(metric.id, 4.25, "native", millis, expectedMetricUuid = metric.uuid)
        fault.arm(IndependentMetricNativeFault.Mode.BEFORE_VALUE_EXECUTE)
        failure(attempt); assertTrue(fault.executeEntries > 0)
        val before = image()
        assertEquals(2, count("metric_logs")); assertEquals(0, count("count_command_results"))
        assertTrue(editingMetrics().resolveIndependentValue(attempt.reference!!) is IndependentMetricResolution.Unresolved)
        assertEquals(before, image())
        val result = editingMetrics().resolveIndependentValue(attempt.reference!!, true) as IndependentMetricResolution.Saved
        assertEquals(attempt.reference, result.value.reference)
        assertEquals(3, count("metric_logs")); assertEquals(1, count("count_command_results"))
        val committed = image()
        assertTrue(editingMetrics().resolveIndependentValue(attempt.reference!!, true) is IndependentMetricResolution.Saved)
        assertEquals(committed, image())
    }

    @Test(timeout = 150_000) fun deferredNativeForeignKeyRejectsActualT1EndAfterFactAndOutcomeThenColdRetryIsSingle() = runBlocking<Unit> {
        register()
        val attempt = capture()
        val originalOwner = requireNotNull(CountTrustRuntime.forDatabase(db))
        fault.arm(IndependentMetricNativeFault.Mode.DEFERRED_VALUE_END)
        val rejected = failure(attempt)
        assertEquals(1, fault.deferredEndFailures); assertTrue(fault.stagedOutcomeObserved)
        assertTrue(fault.executeEntries > 0)
        val originalFailure = requireNotNull(fault.deferredFailure)
        assertTrue("The caller retains the engine's original COMMIT failure", generateSequence(rejected) { it.cause }.any { it === originalFailure })
        assertTrue(originalOwner.isRetired); assertTrue(CountTrustRetirementReason.UNKNOWN_END in originalOwner.retirementReasons)
        // A rejected COMMIT can leave SQLite's transaction open after the Framework stack ends.
        // Persistence is decided only on the cold owner, never by that connection's visibility.
        cold()
        assertNotSame(originalOwner, requireNotNull(CountTrustRuntime.forDatabase(db)))
        assertEquals(0, count("metric_logs")); assertEquals(0, count("sync_outbox")); assertEquals(0, count("count_command_results"))
        assertEquals(1, count("count_command_intents"))
        val saved = editingMetrics().resolveIndependentValue(attempt.reference!!, true) as IndependentMetricResolution.Saved
        assertEquals(attempt.reference, saved.value.reference)
        assertEquals(1, count("metric_logs")); assertEquals(1, count("count_command_results"))
    }

    @Test(timeout = 150_000) fun deferredNativeT2FailureLeavesOriginalSavedFactAndColdDeliveryRetriesOnlyMetadata() = runBlocking<Unit> {
        register()
        val saved = editingMetrics().executeIndependentValue(capture())
        val reference = requireNotNull(saved.reference)
        val before = image()
        val originalOwner = requireNotNull(CountTrustRuntime.forDatabase(db))
        fault.arm(IndependentMetricNativeFault.Mode.DEFERRED_T2_END)
        val rejected = runCatching { editingMetrics().acknowledgeIndependentValue(saved) { true } }.exceptionOrNull()
        assertNotNull(rejected)
        assertEquals(1, fault.hits); assertEquals(1, fault.deferredEndFailures)
        assertTrue(fault.deliveryObserved); assertTrue(fault.stagedOutcomeObserved)
        val originalFailure = requireNotNull(fault.deferredFailure)
        assertTrue("The caller retains the engine's original COMMIT failure", generateSequence(rejected) { it.cause }.any { it === originalFailure })
        assertTrue(originalOwner.isRetired); assertTrue(CountTrustRetirementReason.UNKNOWN_END in originalOwner.retirementReasons)
        // Do not start the image transaction on the connection whose physical END is unknown.
        cold()
        assertNotSame(originalOwner, requireNotNull(CountTrustRuntime.forDatabase(db)))
        assertEquals(before, image())
        val resolved = editingMetrics().resolveIndependentValue(reference) as IndependentMetricResolution.Saved
        assertEquals(saved.factUuid, resolved.value.factUuid)
        editingMetrics().acknowledgeIndependentValue(resolved.value) { true }
        assertEquals(1, count("metric_logs")); assertEquals(1, count("sync_outbox")); assertEquals(1, count("count_command_results"))
        assertEquals(CountCommandRecords.Delivery.DELIVERED, CountCommandStore(db, tokens, sessions).resolve(reference.native()).historical.status.delivery)
    }

    @Test(timeout = 150_000) fun upgradeEndLossCannotUseLegacyFallbackAndUnregisteredColdObservationIsExplicit() = runBlocking<Unit> {
        register()
        val attempt = capture()
        fault.arm(IndependentMetricNativeFault.Mode.AFTER_UPGRADE_END)
        failure(attempt); assertTrue(fault.actualEnd)
        assertEquals(0, count("metric_logs")); assertEquals(0, count("count_command_intents"))
        cold()
        val root = db.withTransaction { CountCommandStorageInitialization.verifyRootAnchor(db.openHelper.writableDatabase).header }
        assertEquals(2, root.format)
        val before = image()
        assertTrue(editingMetrics().resolveIndependentValue(attempt.reference!!) is IndependentMetricResolution.Unregistered)
        assertEquals(before, image())
        val result = editingMetrics().resolveIndependentValue(attempt.reference!!, true)
        assertTrue(result is IndependentMetricResolution.Unregistered) // no durable input to invent
        assertEquals(before, image())
    }
}
