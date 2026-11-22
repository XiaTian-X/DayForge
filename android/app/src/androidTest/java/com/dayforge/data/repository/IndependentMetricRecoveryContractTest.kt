package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.MetricLogEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndependentMetricRecoveryContractTest : NextObjectEditorFixture() {
    private suspend fun capture() = editingMetrics().beginIndependentValue(metric.id, 5.0, "recovery", millis,
        if (metric.appearance == null) null else editingMetrics().getMetricForEditing(metric.id).authority, metric.uuid)
    private suspend fun legacy() {
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            metric = metric.copy(appearance = null); db.metricDao().update(metric)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
    }
    private suspend fun prepared(): Pair<CountCommandReference, IndependentMetricAttempt> {
        val attempt = capture(); val store = CountCommandStore(db, tokens, sessions)
        store.prepareIndependentRoot()
        val registered = store.register(requireNotNull(attempt.intent))
        store.releaseUnadmitted(requireNotNull(registered.ticket))
        return requireNotNull(attempt.reference) to attempt
    }
    private fun cold() { storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons) }
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private fun nonCommand(value: JsonObject): JsonObject {
        val tables = value.getValue("tables").jsonObject
        return JsonObject(tables.filterKeys { !it.startsWith("count_command_") }.mapValues { (name, rows) ->
            if (name != "count_trust_control") rows else JsonArray(rows.jsonArray.map { JsonObject(it.jsonObject - "commandRootHash") })
        })
    }
    private fun pending(): CountCommandPendingRepository {
        val reader = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions))
        val legacy = LegacyWidgetFactWorkflow(db, tokens, sessions,
            LegacyWidgetIdentityVerifier(db, tokens, ReplicaPresentationRepository(db, tokens, sessions)), creatingHabits(),
            com.dayforge.domain.service.StructuralEditGuard(tokens, io.mockk.mockk()))
        return CountCommandPendingRepository(db, tokens, sessions, creatingHabits(), legacy, reader, preferences, editingMetrics())
    }

    @Test(timeout = 150_000) fun firstRegistrationAllowsHistoricalSavedWithoutNewExecutionOrDeliveryPermission() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[kotlinx.coroutines.Job]))
        tokens.getOrCreateInstallationId(); legacy()
        val saved = editingMetrics().executeIndependentValue(capture())
        val ref = requireNotNull(saved.reference)
        val original = CountCommandStore(db, tokens, sessions).resolve(ref.native()).historical.intent.text()
        register(); CountCommandStore(db, tokens, sessions).acknowledgeInitialRegistration()
        cold()
        val before = image()
        val resolved = editingMetrics().resolveIndependentValue(ref, true) as IndependentMetricResolution.Saved
        assertEquals(saved.factUuid, resolved.value.factUuid); assertFalse(resolved.value.deliveryAllowed)
        assertEquals(original, CountCommandStore(db, tokens, sessions).resolve(ref.native()).historical.intent.text())
        val consumer = pending()
        val admission = consumer.prepare(ref) as CountCommandPendingRepository.Admission.Metric
        assertEquals(CountCommandStore.LocationScope.INITIAL_REGISTRATION_REVALIDATION_REQUIRED, admission.scope)
        val shown = consumer.resume(app, admission) as CountCommandPendingRepository.Result.StandaloneMetricSaved
        assertFalse(shown.pending); assertEquals(saved.factUuid, shown.saved.factUuid)
        assertNotNull(runCatching { editingMetrics().acknowledgeIndependentValue(resolved.value) { true } }.exceptionOrNull())
        assertEquals(before, image()); assertEquals(1, count("metric_logs"))
    }

    @Test(timeout = 150_000) fun firstRegistrationPreparedAndOldNamespaceNonmemberCannotBecomeFreshExecution() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[kotlinx.coroutines.Job]))
        tokens.getOrCreateInstallationId(); legacy()
        val (ref, _) = prepared()
        register(); CountCommandStore(db, tokens, sessions).acknowledgeInitialRegistration()
        cold()
        val before = image()
        val result = editingMetrics().resolveIndependentValue(ref, true) as IndependentMetricResolution.Unresolved
        assertEquals("INITIAL_REGISTRATION_EXECUTION_PROOF_REQUIRED", result.reason)
        assertNotNull(runCatching { pending().prepare(ref) }.exceptionOrNull())
        val absent = ref.copy(commandId = id(991))
        assertNotNull(runCatching { editingMetrics().resolveIndependentValue(absent, true) }.exceptionOrNull())
        assertEquals(before, image()); assertEquals(0, count("metric_logs")); assertEquals(0, count("count_command_results"))
    }

    @Test(timeout = 150_000) fun nonInitialEpochReplacementCannotPublishOldSavedOrGrantAnotherWrite() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[kotlinx.coroutines.Job]))
        register(); legacy()
        val saved = editingMetrics().executeIndependentValue(capture())
        val before = image()
        tokens.saveServerIdentity(id(2), id(992))
        assertNotNull(runCatching { editingMetrics().resolveIndependentValue(saved.reference!!, true) }.exceptionOrNull())
        assertEquals(before, image()); assertEquals(1, count("metric_logs"))
    }

    @Test(timeout = 150_000) fun staleTypedDefinitionCanBeExplicitlyEndedOnlyAfterCompleteNoEffectsAndActualDelivery() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[kotlinx.coroutines.Job]))
        register()
        val attempt = capture()
        val authority = editingMetrics().getMetricForEditing(metric.id).authority
        editingMetrics().updateMetric(metric.copy(unit = "lb"), authority)
        assertNotNull(runCatching { editingMetrics().executeIndependentValue(attempt) }.exceptionOrNull())
        val ref = requireNotNull(attempt.reference)
        val before = image()
        val plan = requireNotNull(editingMetrics().prepareIndependentEnd(ref))
        assertEquals(before, image())
        val ended = editingMetrics().endIndependentValue(plan) { true }
        assertEquals(ref, ended.reference); assertTrue(ended.pendingDelivery); assertTrue(ended.deliveryAllowed)
        assertEquals(nonCommand(before), nonCommand(image()))
        assertEquals(0, count("metric_logs")); assertEquals(0, count("count_command_results"))
        cold()
        val found = editingMetrics().resolveIndependentValue(ref) as IndependentMetricResolution.Ended
        val beforeDelivery = image()
        assertNotNull(runCatching { editingMetrics().acknowledgeIndependentEnd(found.value) { false } }.exceptionOrNull())
        assertEquals(beforeDelivery, image())
        editingMetrics().acknowledgeIndependentEnd(found.value) { true }
        editingMetrics().acknowledgeIndependentEnd(found.value) { true }
        val status = CountCommandStore(db, tokens, sessions).resolve(ref.native()).historical.status
        assertEquals(CountCommandRecords.Execution.CANCELLED, status.execution)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, status.delivery)
        val selected = editingMetrics().getMetricForEditing(metric.id)
        val next = editingMetrics().beginIndependentValue(metric.id, 6.0, "new explicit input", millis,
            selected.authority, metric.uuid)
        val saved = editingMetrics().executeIndependentValue(next)
        assertNotEquals(ref, saved.reference); assertEquals(1, count("metric_logs"))
    }

    @Test(timeout = 150_000) fun endPlanExpiresOnPermissionMetadataOrConsumerChangeWithoutRemovingPrepared() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[kotlinx.coroutines.Job]))
        register()
        val (ref, _) = prepared()
        val plan = requireNotNull(editingMetrics().prepareIndependentEnd(ref))
        val before = image()
        assertNotNull(runCatching { editingMetrics().endIndependentValue(plan) { false } }.exceptionOrNull())
        assertEquals(before, image())
        register(revision = 2)
        assertNotNull(runCatching { editingMetrics().endIndependentValue(plan) { true } }.exceptionOrNull())
        assertEquals(before, image())
        assertEquals(CountCommandRecords.Execution.PREPARED, CountCommandStore(db, tokens, sessions).resolve(ref.native()).historical.status.execution)
        val fresh = requireNotNull(editingMetrics().prepareIndependentEnd(ref))
        assertEquals(ref, editingMetrics().endIndependentValue(fresh) { true }.reference)
    }

    @Test(timeout = 150_000) fun existingReservedEffectNeverOffersExplicitEnd() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[kotlinx.coroutines.Job]))
        register()
        val (ref, attempt) = prepared()
        val fact = CountCommandRecords.uuid(IndependentMetricInput.input(requireNotNull(attempt.intent)), "reserved_fact_uuid")
        db.metricLogDao().insert(MetricLogEntity(metricId = metric.id, date = millis, value = 5.0, unit = metric.unit, uuid = fact))
        val before = image()
        val prepared = runCatching { editingMetrics().prepareIndependentEnd(ref) }
        assertTrue(prepared.isFailure || prepared.getOrNull() == null)
        assertEquals(before, image())
        assertEquals(CountCommandRecords.Execution.PREPARED, CountCommandStore(db, tokens, sessions).resolve(ref.native()).historical.status.execution)
    }

    @Test(timeout = 150_000) fun appliedCommandNeverOffersExplicitEnd() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[kotlinx.coroutines.Job]))
        register()
        val saved = editingMetrics().executeIndependentValue(capture())
        val before = image()
        assertNull(editingMetrics().prepareIndependentEnd(saved.reference!!))
        assertEquals(before, image()); assertEquals(1, count("metric_logs")); assertEquals(1, count("count_command_results"))
    }

    @Test(timeout = 150_000) fun pendingConsumerOffersExplicitEndThenPublishesCancelledInsteadOfSaved() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[kotlinx.coroutines.Job]))
        register()
        val attempt = capture()
        editingMetrics().updateMetric(metric.copy(unit = "lb"), editingMetrics().getMetricForEditing(metric.id).authority)
        assertNotNull(runCatching { editingMetrics().executeIndependentValue(attempt) }.exceptionOrNull())
        val ref = requireNotNull(attempt.reference); val consumer = pending()
        val available = consumer.resume(app, consumer.prepare(ref)) as CountCommandPendingRepository.Result.IndependentEndAvailable
        assertEquals(ref, available.reference)
        val ended = consumer.endIndependent(available) { true }
        assertTrue(ended.pending)
        consumer.acknowledgeIndependentEnd(ended) { true }
        val shown = consumer.resume(app, consumer.prepare(ref)) as CountCommandPendingRepository.Result.IndependentEnded
        assertFalse(shown.pending); assertEquals(ref, shown.reference)
        assertEquals(0, count("metric_logs")); assertEquals(0, count("count_command_results"))
    }
    @Test(timeout = 150_000) fun legacyDeletedTargetCanEndWithOriginalNoAppendPolicyThenColdPendingDeliveryWritesNoFact() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[kotlinx.coroutines.Job]))
        legacy(); register(permissions = caps - "facts.append")
        assertFalse(requireNotNull(tokens.localFactAccess()).canAppend)
        val (ref, _) = prepared()
        val original = requireNotNull(db.metricDao().getMetricById(metric.id))
        editingMetrics().deleteMetric(original)
        assertNull(db.metricDao().getMetricById(metric.id))
        assertNotNull(runCatching {
            editingMetrics().recordValue(metric.id, 5.0, "recovery", millis, expectedMetricUuid = original.uuid)
        }.exceptionOrNull())
        assertNotNull(runCatching { editingMetrics().resolveIndependentValue(ref, true) }.exceptionOrNull())
        val before = image()
        val consumer = pending()
        val stale = consumer.resume(app, consumer.prepare(ref)) as CountCommandPendingRepository.Result.IndependentEndAvailable
        assertEquals(before, image())
        assertNotNull(runCatching { consumer.endIndependent(stale) { false } }.exceptionOrNull())
        assertEquals(before, image())
        register(permissions = caps - "facts.append", revision = 2)
        assertNotNull(runCatching { consumer.endIndependent(stale) { true } }.exceptionOrNull())
        assertEquals(before, image())
        val current = consumer.resume(app, consumer.prepare(ref)) as CountCommandPendingRepository.Result.IndependentEndAvailable
        val ended = consumer.endIndependent(current) { true }
        assertEquals(ref, ended.reference); assertTrue(ended.pending)
        assertEquals(nonCommand(before), nonCommand(image()))
        assertEquals(0, count("metric_logs")); assertEquals(0, count("count_command_results"))
        cold()
        val resolved = editingMetrics().resolveIndependentValue(ref) as IndependentMetricResolution.Ended
        assertTrue(resolved.value.pendingDelivery); assertTrue(resolved.value.deliveryAllowed)
        val coldConsumer = pending()
        val shown = coldConsumer.resume(app, coldConsumer.prepare(ref)) as CountCommandPendingRepository.Result.IndependentEnded
        val beforeT2 = image()
        assertNotNull(runCatching { coldConsumer.acknowledgeIndependentEnd(shown) { false } }.exceptionOrNull())
        assertEquals(beforeT2, image())
        coldConsumer.acknowledgeIndependentEnd(shown) { true }
        coldConsumer.acknowledgeIndependentEnd(shown) { true }
        val final = CountCommandStore(db, tokens, sessions).resolve(ref.native()).historical
        assertEquals(CountCommandRecords.Execution.CANCELLED, final.status.execution)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, final.status.delivery); assertNull(final.outcome)
        assertEquals(nonCommand(before), nonCommand(image()))
        assertEquals(0, count("metric_logs")); assertNull(db.metricDao().getMetricById(metric.id))
    }

    @Test(timeout = 150_000) fun legacyIdReusedByLegalDeleteCreateCannotRetargetOldCommandButExplicitEndRemainsAvailable() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[kotlinx.coroutines.Job]))
        legacy(); register(permissions = caps - "facts.append")
        val (ref, attempt) = prepared()
        val old = requireNotNull(db.metricDao().getMetricById(metric.id))
        editingMetrics().deleteMetric(old)
        val replacement = old.copy(uuid = id(993), name = "Replacement legacy metric", unit = "lb")
        assertEquals(old.id, editingMetrics().createMetric(replacement))
        val actualReplacement = requireNotNull(db.metricDao().getMetricById(old.id))
        assertEquals(replacement.uuid, actualReplacement.uuid)
        assertNotNull(runCatching {
            editingMetrics().recordValue(old.id, 5.0, "recovery", millis, expectedMetricUuid = old.uuid)
        }.exceptionOrNull())
        assertNotNull(runCatching { editingMetrics().resolveIndependentValue(ref, true) }.exceptionOrNull())
        val before = image()
        val plan = requireNotNull(editingMetrics().prepareIndependentEnd(ref))
        val ended = editingMetrics().endIndependentValue(plan) { true }
        assertEquals(ref, ended.reference); assertEquals(nonCommand(before), nonCommand(image()))
        cold()
        val found = editingMetrics().resolveIndependentValue(ref) as IndependentMetricResolution.Ended
        editingMetrics().acknowledgeIndependentEnd(found.value) { true }
        val aggregate = CountCommandStore(db, tokens, sessions).resolve(ref.native()).historical
        assertEquals(requireNotNull(attempt.intent).text(), aggregate.intent.text())
        assertEquals(CountCommandRecords.Execution.CANCELLED, aggregate.status.execution)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, aggregate.status.delivery)
        assertEquals(actualReplacement, db.metricDao().getMetricById(old.id))
        assertEquals(nonCommand(before), nonCommand(image()))
        assertEquals(0, count("metric_logs")); assertEquals(0, count("count_command_results"))
    }

    @Test(timeout = 150_000) fun legacyRunningLeaseReservedFactAndReservedSourceCannotBeExplicitlyEnded() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[kotlinx.coroutines.Job]))
        legacy(); register(permissions = caps - "facts.append")
        val first = capture(); val store = CountCommandStore(db, tokens, sessions)
        store.prepareIndependentRoot()
        val registered = store.register(requireNotNull(first.intent))
        try {
            val before = image()
            assertNull(editingMetrics().prepareIndependentEnd(first.reference!!))
            assertEquals(before, image())
        } finally { store.releaseUnadmitted(requireNotNull(registered.ticket)) }
        val firstUuid = CountCommandRecords.uuid(IndependentMetricInput.input(requireNotNull(first.intent)), "reserved_fact_uuid")
        db.metricLogDao().insert(MetricLogEntity(metricId = metric.id, date = millis, value = 9.0, unit = metric.unit, uuid = firstUuid))
        val (secondRef, second) = prepared()
        val secondUuid = CountCommandRecords.uuid(IndependentMetricInput.input(requireNotNull(second.intent)), "reserved_fact_uuid")
        db.syncOutboxDao().insert(com.dayforge.data.local.entity.SyncOutboxEntity(operationId = id(994),
            recordType = "metric_log", entityUuid = secondUuid, wireEntityUuid = secondUuid,
            action = "upsert", referenceUuid = metric.uuid, createdAt = millis))
        val before = image()
        for (ref in listOf(first.reference!!, secondRef)) {
            val plan = runCatching { editingMetrics().prepareIndependentEnd(ref) }
            assertTrue(plan.isFailure || plan.getOrNull() == null)
            assertEquals(before, image())
            assertEquals(CountCommandRecords.Execution.PREPARED, store.resolve(ref.native()).historical.status.execution)
        }
        assertEquals(1, count("metric_logs")); assertEquals(0, count("count_command_results"))
    }

    @Test(timeout = 150_000) fun sameLegacyIdentityWithNewUnitResumesOriginalInputAndNeverBecomesCancelled() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[kotlinx.coroutines.Job]))
        legacy(); register(permissions = caps - "facts.append")
        val (ref, attempt) = prepared()
        editingMetrics().updateMetric(metric.copy(unit = "lb"))
        val oldId = editingMetrics().recordValue(metric.id, 5.0, "recovery", millis, expectedMetricUuid = metric.uuid)
        val old = requireNotNull(db.metricLogDao().getById(oldId))
        val result = editingMetrics().resolveIndependentValue(ref, true) as IndependentMetricResolution.Saved
        val saved = requireNotNull(db.metricLogDao().getById(result.value.factId))
        assertEquals("lb", old.unit); assertEquals(old.unit, saved.unit)
        assertEquals(old.value, saved.value, 0.0); assertEquals(old.date, saved.date)
        assertNotEquals(old.uuid, saved.uuid); assertEquals(ref, result.value.reference)
        assertNull(editingMetrics().prepareIndependentEnd(ref))
        val aggregate = CountCommandStore(db, tokens, sessions).resolve(ref.native()).historical
        assertEquals(requireNotNull(attempt.intent).text(), aggregate.intent.text())
        assertEquals(CountCommandRecords.Execution.APPLIED, aggregate.status.execution); assertNotNull(aggregate.outcome)
        val before = image()
        cold()
        val repeated = editingMetrics().resolveIndependentValue(ref, true) as IndependentMetricResolution.Saved
        assertEquals(saved.uuid, repeated.value.factUuid); assertEquals(before, image()); assertEquals(2, count("metric_logs"))
    }

}
