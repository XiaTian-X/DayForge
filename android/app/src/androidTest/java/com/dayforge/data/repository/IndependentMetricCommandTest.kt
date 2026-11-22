package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountCommandStorageInitialization
import com.dayforge.data.local.entity.MetricLogEntity
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real repository/SQLite/T0/T1/T2 and HTTP. No UI pixels or performance claim. */
@RunWith(AndroidJUnit4::class)
class IndependentMetricCommandTest : NextObjectEditorFixture() {
    private suspend fun capture(value: Double = 12.5): IndependentMetricAttempt {
        val original = editingMetrics().getMetricForEditing(metric.id)
        return editingMetrics().beginIndependentValue(metric.id, value, "independent", millis,
            original.authority, metric.uuid)
    }
    private fun cold() { storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons) }
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }

    @Test(timeout = 150_000) fun sameValueAndTimeAreTwoExplicitCommandsButResolutionNeverInsertsAgain() = runBlocking<Unit> {
        register()
        val first = editingMetrics().executeIndependentValue(capture())
        val second = editingMetrics().executeIndependentValue(capture())
        assertNotEquals(first.factUuid, second.factUuid)
        assertNotEquals(first.reference, second.reference)
        assertEquals(2, count("metric_logs")); assertEquals(2, count("sync_outbox"))
        assertEquals(2, count("next_request_origins")); assertEquals(2, count("count_command_results"))
        val before = image()
        val resolved = editingMetrics().resolveIndependentValue(requireNotNull(first.reference))
        assertTrue(resolved is IndependentMetricResolution.Saved)
        assertEquals(first.factUuid, (resolved as IndependentMetricResolution.Saved).value.factUuid)
        assertEquals(before, image())
        assertNotNull(runCatching { editingMetrics().acknowledgeIndependentValue(first) { false } }.exceptionOrNull())
        val store = CountCommandStore(db, tokens, sessions)
        assertTrue(store.resolve(first.reference!!.native()).historical.status.pending)
        editingMetrics().acknowledgeIndependentValue(first) { true }
        editingMetrics().acknowledgeIndependentValue(first) { true }
        assertEquals(CountCommandRecords.Delivery.DELIVERED, store.resolve(first.reference!!.native()).historical.status.delivery)
        assertTrue(store.resolve(second.reference!!.native()).historical.status.pending)
        assertEquals(2, count("metric_logs")); assertEquals(2, count("sync_outbox"))
    }

    @Test(timeout = 150_000) fun ordinaryRealAckAndReplayThenColdResolutionKeepOriginalFactAndOutcome() = runBlocking<Unit> {
        register()
        val saved = editingMetrics().executeIndependentValue(capture())
        val reference = requireNotNull(saved.reference)
        val before = CountCommandStore(db, tokens, sessions).resolve(reference.native()).historical.outcome!!.text()
        val row = db.syncOutboxDao().getAll().single()
        val (http, server) = channel { successReply(it) }
        assertNotNull(sender(http).sendAndAcceptOperation(access(), row.operationId))
        assertNotNull(sender(http).sendAndAcceptOperation(access(), row.operationId))
        assertEquals(1, server.requests.count { it.path.endsWith("/push") })
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        cold()
        val result = editingMetrics().resolveIndependentValue(reference) as IndependentMetricResolution.Saved
        assertEquals(saved.factUuid, result.value.factUuid)
        assertEquals(before, CountCommandStore(db, tokens, sessions).resolve(reference.native()).historical.outcome!!.text())
        assertEquals(1, count("metric_logs")); assertEquals(1, count("next_request_origins"))
        editingMetrics().acknowledgeIndependentValue(result.value) { true }
        assertEquals(1, count("metric_logs"))
    }

    @Test(timeout = 150_000) fun legacyCurrentUnitMatchesTheOriginalRepositoryWhileTypedStaleAuthorityRejects() = runBlocking<Unit> {
        // Use the real old repository path as oracle, with a canonical authenticated account.
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.metricDao().update(metric.copy(appearance = null))
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val legacy = requireNotNull(db.metricDao().getMetricById(metric.id))
        val attempt = editingMetrics().beginIndependentValue(metric.id, 3.0, "unit", millis, expectedMetricUuid = metric.uuid)
        editingMetrics().updateMetric(legacy.copy(unit = "lb"))
        val oldId = editingMetrics().recordValue(metric.id, 3.0, "unit", millis, expectedMetricUuid = metric.uuid)
        val original = requireNotNull(db.metricLogDao().getById(oldId))
        val saved = editingMetrics().executeIndependentValue(attempt)
        val actual = requireNotNull(db.metricLogDao().getById(saved.factId))
        assertEquals("lb", original.unit); assertEquals(original.unit, actual.unit)
        assertEquals(original.value, actual.value, 0.0); assertEquals(original.date, actual.date)
        assertEquals(original.note, actual.note); assertNotEquals(original.uuid, actual.uuid)
        val before = image()
        assertTrue(editingMetrics().resolveIndependentValue(saved.reference!!) is IndependentMetricResolution.Saved)
        assertEquals(before, image())
    }

    @Test(timeout = 150_000) fun typedDefinitionChangeAndUuidReuseRetainOriginalRefusalAndNoPartialFact() = runBlocking<Unit> {
        register()
        val ticket = editingMetrics().getMetricForEditing(metric.id)
        val attempt = capture()
        editingMetrics().updateMetric(metric.copy(unit = "lb"), ticket.authority)
        val queues = db.syncOutboxDao().getAll()
        assertNotNull(runCatching { editingMetrics().recordValue(metric.id, 12.5, "independent", millis,
            ticket.authority, metric.uuid) }.exceptionOrNull())
        assertNotNull(runCatching { editingMetrics().executeIndependentValue(attempt) }.exceptionOrNull())
        assertEquals(0, count("metric_logs")); assertEquals(0, count("count_command_results"))
        assertEquals(queues, db.syncOutboxDao().getAll())
        assertTrue(CountCommandStore(db, tokens, sessions).resolve(attempt.reference!!.native()).historical.status.pending)
        val current = requireNotNull(db.metricDao().getMetricById(metric.id))
        val next = editingMetrics().beginIndependentValue(metric.id, 4.0, "", authority = editingMetrics().getMetricForEditing(metric.id).authority)
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.metricDao().update(current.copy(uuid = id(881)))
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        assertNotNull(runCatching { editingMetrics().executeIndependentValue(next) }.exceptionOrNull())
        assertEquals(0, count("metric_logs")); assertEquals(0, count("count_command_results"))
    }

    @Test(timeout = 150_000) fun emptyBindingAndBoundUpgradePreserveV1PendingBytesBeforeMixedAppend() = runBlocking<Unit> {
        register()
        val old = editor.beginCountAdd(habit, editor.habit(habit.id).authority, Instant.now(), ZoneId.systemDefault(), 1)
        editor.releaseUnexecuted(old)
        val ref = CountCommandReference(old.input.commandId, old.input.inputHash, old.input.namespace.hash, old.input.namespace.dataset)
        val store = CountCommandStore(db, tokens, sessions)
        val before = store.resolve(ref.native()).historical
        val root = db.withTransaction { CountCommandStorageInitialization.verifyRootAnchor(db.openHelper.writableDatabase).header }
        assertEquals(1, root.format)
        store.prepareIndependentRoot()
        val upgraded = db.withTransaction { CountCommandStorageInitialization.verifyRootAnchor(db.openHelper.writableDatabase).header }
        assertEquals(2, upgraded.format); assertEquals(root.catalog, upgraded.catalog)
        assertEquals(root.pending, upgraded.pending); assertEquals(root.revision + 1, upgraded.revision)
        assertEquals(before.member(), store.resolve(ref.native()).historical.member())
        val saved = editingMetrics().executeIndependentValue(capture())
        val oldAfter = store.resolve(ref.native()).historical
        assertEquals(1, oldAfter.intent.format); assertEquals(1, oldAfter.status.format)
        assertEquals(before.intent.text(), oldAfter.intent.text())
        assertEquals(2, store.resolve(saved.reference!!.native()).historical.intent.format)
    }

    @Test(timeout = 150_000) fun pendingRecoveryDispatchesMetricWithoutReadingHabitFieldsOrPrematureDelivery() = runBlocking<Unit> {
        register()
        val saved = editingMetrics().executeIndependentValue(capture())
        val ref = requireNotNull(saved.reference)
        val reader = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions))
        val legacy = LegacyWidgetFactWorkflow(db, tokens, sessions,
            LegacyWidgetIdentityVerifier(db, tokens, ReplicaPresentationRepository(db, tokens, sessions)), creatingHabits(),
            com.dayforge.domain.service.StructuralEditGuard(tokens, io.mockk.mockk()))
        val pending = CountCommandPendingRepository(db, tokens, sessions, creatingHabits(), legacy, reader, preferences, editingMetrics())
        val admission = pending.prepare(ref)
        assertTrue(admission is CountCommandPendingRepository.Admission.Metric)
        assertEquals(metric.uuid, admission.subjectUuid); assertEquals(metric.id, admission.subjectId)
        val result = pending.resume(app, admission) as CountCommandPendingRepository.Result.StandaloneMetricSaved
        assertEquals(saved.factUuid, result.saved.factUuid)
        assertTrue(CountCommandStore(db, tokens, sessions).resolve(ref.native()).historical.status.pending)
        pending.acknowledgeIndependent(result) { true }
        assertEquals(CountCommandRecords.Delivery.DELIVERED, CountCommandStore(db, tokens, sessions).resolve(ref.native()).historical.status.delivery)
        assertEquals(1, count("metric_logs")); assertEquals(1, count("sync_outbox"))
    }

    @Test(timeout = 150_000) fun reservedExistingFactCannotBeReplacedAndPermissionChangeCannotPartiallyCommit() = runBlocking<Unit> {
        register()
        val attempt = capture()
        val uuid = CountCommandRecords.uuid(IndependentMetricInput.input(requireNotNull(attempt.intent)), "reserved_fact_uuid")
        val prior = MetricLogEntity(metricId = metric.id, date = millis, value = 9.0, unit = "kg", uuid = uuid)
        db.metricLogDao().insert(prior)
        val facts = db.metricLogDao().getAllLogsForMetric(metric.id); val queue = db.syncOutboxDao().getAll()
        assertNotNull(runCatching { editingMetrics().executeIndependentValue(attempt) }.exceptionOrNull())
        assertEquals(facts, db.metricLogDao().getAllLogsForMetric(metric.id)); assertEquals(queue, db.syncOutboxDao().getAll())
        assertEquals(0, count("count_command_results"))
        val denied = capture(5.0)
        tokens.saveDeviceRegistration(id(4), caps - "facts.append", true, 2)
        assertNotNull(runCatching { editingMetrics().executeIndependentValue(denied) }.exceptionOrNull())
        assertEquals(facts, db.metricLogDao().getAllLogsForMetric(metric.id)); assertEquals(queue, db.syncOutboxDao().getAll())
    }
}
