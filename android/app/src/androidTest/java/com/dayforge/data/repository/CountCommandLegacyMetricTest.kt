package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.MetricEntity
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real COUNT parent -> private legacy child T0/T1 -> original preference -> T2. No fake
 * command participant, signing shortcut or suspend spy. Normal reopen is not process death.
 */
@RunWith(AndroidJUnit4::class)
class CountCommandLegacyMetricTest : NextObjectEditorFixture() {
    private lateinit var second: MetricEntity
    private suspend fun legacy() {
        tokens.getOrCreateInstallationId(); register()
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            habit = habit.copy(appearance = null, planMetadata = null, completionPolicy = null)
            timerHabit = timerHabit.copy(appearance = null, planMetadata = null, completionPolicy = null)
            metric = metric.copy(appearance = null)
            db.habitDao().update(habit); db.habitDao().update(timerHabit); db.metricDao().update(metric)
            val new = metric.copy(id = 0, uuid = id(901), name = "Second legacy metric")
            second = new.copy(id = db.metricDao().insert(new))
            for (target in listOf(metric, second)) db.habitMetricLinkDao().insertOrIgnore(HabitMetricLinkEntity(
                habitId = habit.id, habitUuid = habit.uuid, metricId = target.id, metricUuid = target.uuid, promptOnComplete = true))
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        assertEquals(0, count("sync_outbox"))
    }
    private fun preferenceOwner() = HabitPreferenceRepository(db, tokens, sessions, preferences, ReplicaPresentationRepository(db, tokens, sessions))
    private fun store() = CountCommandStore(db, tokens, sessions)
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private suspend fun parent(): CountCommandReceipt = requireNotNull(creatingHabits().logCompletionWithReceipt(app, habit.id,
        countIdentity = CountActionReader(db, tokens, sessions).legacy(requireNotNull(db.habitDao().getHabitById(habit.id)))).command)
    private fun values() = listOf(MetricValueDraft(metric.id, -0.0, "negative zero is original input"), MetricValueDraft(second.id, 4.5, "second"))
    private fun reopen() { storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons) }

    @Test(timeout = 150_000) fun actualLegacyDoubleMetricCommitAndT2KeepTheOriginalHistoricalResults() = runBlocking<Unit> {
        legacy(); val parent = parent(); val preferences = preferenceOwner()
        val current = requireNotNull(db.habitDao().getHabitById(habit.id)); val claim = preferences.read(current).claim
        val result = creatingMetrics().recordLegacyPromptValuesWithReceipt(values(), millis, claim, preferences, current, listOf(metric, second), parent)
        val child = requireNotNull(result.command)
        assertEquals(2, result.value.size); assertEquals(2, count("metric_logs")); assertEquals(3, count("sync_outbox"))
        assertEquals(0, count("next_request_origins")); assertEquals(2, count("count_command_results"))
        val historical = store().resolve(child.reference.native()).historical
        assertEquals(CountCommandRecords.Execution.APPLIED, historical.status.execution)
        assertEquals(CountCommandEvidence.Profile.LEGACY_V4, requireNotNull(historical.outcome).evidence.profile)
        assertEquals("8000000000000000", historical.intent.body().getValue("input").jsonObject.getValue("metrics").jsonArray.first().jsonObject.getValue("value_bits").jsonPrimitive.content)
        assertEquals(0.0, db.metricLogDao().getLatestLog(metric.id)?.value ?: Double.NaN, 0.0)
        preferences.removePending(claim) // Original publication/claim boundary returns before T2.
        creatingMetrics().acknowledgeMetricCommandDelivery(parent, child)
        val after = image()
        creatingMetrics().acknowledgeMetricCommandDelivery(parent, child)
        assertEquals(after, image())
        reopen(); val reopened = image()
        val recovered = creatingMetrics().resumeLinkedMetricCommand(child.reference, preferenceOwner()) as CountCommandRecovery.HistoricalCommitted
        assertEquals(child.reference, recovered.receipt.reference)
        assertEquals(reopened, image()); assertEquals(2, count("metric_logs")); assertEquals(1, count("completions"))
    }

    @Test(timeout = 150_000) fun actualPreparedChildReopensAndUsesExactlyTheReservedIdsAndOriginalInput() = runBlocking<Unit> {
        legacy(); val parent = parent(); val preferences = preferenceOwner()
        val current = requireNotNull(db.habitDao().getHabitById(habit.id)); val claim = preferences.read(current).claim
        val prepared = editor.beginLegacyMetricChild(parent, claim, preferences, current, listOf(metric, second), values(), millis)
        val frozen = prepared.input; val ref = CountCommandReference(frozen.commandId, frozen.inputHash, frozen.namespace.hash, frozen.namespace.dataset)
        editor.releaseLegacyMetrics(prepared)
        assertEquals(0, count("metric_logs")); assertEquals(1, count("count_command_results"))
        reopen()
        val done = creatingMetrics().resumeLinkedMetricCommand(ref, preferenceOwner()) as CountCommandRecovery.HistoricalCommitted
        assertEquals(ref, done.receipt.reference)
        val logs = listOf(metric.id, second.id).flatMap { db.metricLogDao().getAllLogsForMetric(it) }
        val expected = frozen.body().getValue("input").jsonObject.getValue("metrics").jsonArray.map { it.jsonObject }
        assertEquals(expected.map { CountCommandRecords.uuid(it, "reserved_fact_uuid") }.toSet(), logs.map { it.uuid }.toSet())
        assertTrue(logs.all { it.date == millis }); assertEquals(2, logs.size)
        val beforeRepeat = image()
        assertTrue(creatingMetrics().resumeLinkedMetricCommand(ref, preferenceOwner()) is CountCommandRecovery.HistoricalCommitted)
        assertEquals(beforeRepeat, image()); assertEquals(2, count("count_command_results"))
    }

    @Test(timeout = 150_000) fun unexpectedSameValueBusinessWriteRollsBackAndSameLivingCallerCanRecover() = runBlocking<Unit> {
        legacy(); val parent = parent(); val preferences = preferenceOwner()
        val current = requireNotNull(db.habitDao().getHabitById(habit.id)); val claim = preferences.read(current).claim
        val prepared = editor.beginLegacyMetricChild(parent, claim, preferences, current, listOf(metric, second), values(), millis)
        val input = prepared.input; val ref = CountCommandReference(input.commandId, input.inputHash, input.namespace.hash, input.namespace.dataset)
        val before = image(); var wrote = false
        val error = rejected { editor.mutateLegacyMetrics(prepared, preferences) {
            creatingMetrics().recordValues(values(), millis)
            assertEquals(2, count("metric_logs"))
            db.metricDao().update(metric) // A real extra same-value write must not disappear.
            wrote = true
        } }
        assertTrue(wrote); assertEquals("COMMAND_LEGACY_UNEXPECTED_EFFECT_COUNT", error.message)
        assertEquals(before, image()); assertEquals(0, count("metric_logs"))
        assertTrue(creatingMetrics().resumeLinkedMetricCommand(ref, preferences) is CountCommandRecovery.HistoricalCommitted)
        assertEquals(2, count("metric_logs")); assertEquals(2, count("count_command_results"))
    }

    @Test(timeout = 150_000) fun advancedBusinessCannotUseMissingChildOutcomeAsNoEffects() = runBlocking<Unit> {
        legacy(); val parent = parent(); val preferences = preferenceOwner()
        val current = requireNotNull(db.habitDao().getHabitById(habit.id)); val claim = preferences.read(current).claim
        val prepared = editor.beginLegacyMetricChild(parent, claim, preferences, current, listOf(metric, second), values(), millis)
        val input = prepared.input; val ref = CountCommandReference(input.commandId, input.inputHash, input.namespace.hash, input.namespace.dataset)
        editor.releaseLegacyMetrics(prepared)
        val original = store().resolve(ref.native()).historical
        db.metricDao().update(metric.copy(unit = "changed after T0"))
        val changed = image()
        // The low-level fast path still cannot infer NoEffects from a missing child Outcome.
        val result = store().recover(ref.native()) as CountCommandStore.Recovery.Unresolved
        assertEquals("ADVANCED_BUSINESS_REQUIRES_COMPLETE_NO_EFFECTS_PROOF", result.reason)
        assertEquals(changed, image())
        // The public retry now obtains its separate complete proof, then the original T1
        // plan must reject the changed complete metric definition before writing a value.
        val failure = rejected { creatingMetrics().resumeLinkedMetricCommand(ref, preferences) }
        assertEquals(IllegalStateException::class.java, failure.javaClass)
        assertEquals("METRIC_PROMPT_METADATA_CHANGED", failure.message)
        assertTrue(failure.stackTrace.any { it.className.startsWith("com.dayforge.data.repository.CountCommandLegacyMetricPlan") &&
            (it.className.contains("capture") || it.methodName == "capture") })
        assertEquals(changed, image()); assertEquals(0, count("metric_logs")); assertEquals(1, count("count_command_results"))
        val retained = store().resolve(ref.native()).historical
        assertEquals(input.text(), retained.intent.text()); assertEquals(original.status, retained.status)
        assertNull(retained.outcome); assertEquals(changed, image())
    }

    @Test(timeout = 150_000) fun unrelatedBusinessAdvanceUsesCompleteProofForExactlyTheOriginalMetricGroup() = runBlocking<Unit> {
        legacy(); val parent = parent(); val preferences = preferenceOwner()
        val current = requireNotNull(db.habitDao().getHabitById(habit.id)); val claim = preferences.read(current).claim
        val prepared = editor.beginLegacyMetricChild(parent, claim, preferences, current, listOf(metric, second), values(), millis)
        val input = prepared.input; val ref = CountCommandReference(input.commandId, input.inputHash, input.namespace.hash, input.namespace.dataset)
        editor.releaseLegacyMetrics(prepared)
        val unrelated = requireNotNull(db.habitDao().getHabitById(timerHabit.id)).copy(description = "unrelated change after metric T0")
        db.habitDao().update(unrelated)
        assertEquals(unrelated, db.habitDao().getHabitById(timerHabit.id))
        val changed = image(); val oldSources = db.syncOutboxDao().getAll()
        val fast = store().recover(ref.native()) as CountCommandStore.Recovery.Unresolved
        assertEquals("ADVANCED_BUSINESS_REQUIRES_COMPLETE_NO_EFFECTS_PROOF", fast.reason)
        assertEquals(changed, image()); assertEquals(0, count("metric_logs")); assertEquals(1, count("count_command_results"))

        val recovered = creatingMetrics().resumeLinkedMetricCommand(ref, preferences) as CountCommandRecovery.HistoricalCommitted
        assertEquals(ref, recovered.receipt.reference)
        val historical = store().resolve(ref.native()).historical
        assertEquals(input.text(), historical.intent.text()); assertEquals(CountCommandRecords.Execution.APPLIED, historical.status.execution)
        assertEquals(parent.reference.commandId, historical.status.parent)
        assertEquals(CountCommandEvidence.Profile.LEGACY_V4, requireNotNull(historical.outcome).evidence.profile)
        val selected = input.body().getValue("input").jsonObject.getValue("metrics").jsonArray.map { it.jsonObject }
        val logs = listOf(metric.id, second.id).flatMap { db.metricLogDao().getAllLogsForMetric(it) }
        assertEquals(2, logs.size)
        for (frozen in selected) {
            val log = logs.single { it.uuid == CountCommandRecords.uuid(frozen, "reserved_fact_uuid") }
            val metricId = CountCommandRecords.integer(frozen, "metric_id")
            val draft = values().single { it.metricId == metricId }
            val definition = listOf(metric, second).single { it.id == metricId }
            assertEquals(metricId, log.metricId); assertEquals(millis, log.date)
            // SQLite preserves the original negative-zero input in Intent and stores numeric zero.
            assertEquals(if (draft.value == 0.0) 0.0 else draft.value, log.value, 0.0)
            assertEquals(draft.note, log.note); assertEquals(definition.unit, log.unit)
        }
        val sources = db.syncOutboxDao().getAll()
        assertEquals(oldSources, sources.filter { row -> oldSources.any { it.id == row.id } })
        val added = sources.filter { row -> oldSources.none { it.id == row.id } }
        assertEquals(2, added.size); assertTrue(added.all { it.recordType == "metric_log" })
        assertEquals(logs.map { it.uuid }.toSet(), added.map { it.entityUuid }.toSet())
        assertEquals(2, count("count_command_results")); assertEquals(1, count("completions")); assertEquals(0, count("next_request_origins"))
        assertEquals(unrelated, db.habitDao().getHabitById(timerHabit.id))
        val committed = image()
        val repeated = creatingMetrics().resumeLinkedMetricCommand(ref, preferences) as CountCommandRecovery.HistoricalCommitted
        assertEquals(ref, repeated.receipt.reference); assertEquals(committed, image())
        reopen(); val cold = image()
        val reopened = creatingMetrics().resumeLinkedMetricCommand(ref, preferenceOwner()) as CountCommandRecovery.HistoricalCommitted
        assertEquals(ref, reopened.receipt.reference); assertEquals(cold, image())
        assertEquals(2, count("metric_logs")); assertEquals(2, count("count_command_results"))
    }

    @Test(timeout = 150_000) fun actualCancelledChildRollsBackAllValuesAndReleasesItsExecutionLease() = runBlocking<Unit> {
        legacy(); val parent = parent(); val preferences = preferenceOwner()
        val current = requireNotNull(db.habitDao().getHabitById(habit.id)); val claim = preferences.read(current).claim
        val reference = CompletableDeferred<CountCommandReference>(); var inserted = false
        val worker = launch {
            val prepared = editor.beginLegacyMetricChild(parent, claim, preferences, current, listOf(metric, second), values(), millis)
            val input = prepared.input
            reference.complete(CountCommandReference(input.commandId, input.inputHash, input.namespace.hash, input.namespace.dataset))
            editor.mutateLegacyMetrics(prepared, preferences) {
                creatingMetrics().recordValues(values(), millis)
                inserted = true
                currentCoroutineContext().cancel(CancellationException("fixed actual child cancellation"))
                currentCoroutineContext().ensureActive()
            }
        }
        val ref = reference.await(); worker.join()
        assertTrue(worker.isCancelled); assertTrue(inserted)
        assertEquals(0, count("metric_logs")); assertEquals(1, count("count_command_results")); assertEquals(1, count("sync_outbox"))
        assertTrue(creatingMetrics().resumeLinkedMetricCommand(ref, preferences) is CountCommandRecovery.HistoricalCommitted)
        assertEquals(2, count("metric_logs")); assertEquals(2, count("count_command_results"))
    }
    @Test(timeout = 150_000) fun capturedLegacyWidgetChildCannotAcquireAnotherStorageAtActualT0() = runBlocking<Unit> {
        legacy(); val parent = parent(); val current = requireNotNull(db.habitDao().getHabitById(habit.id))
        val access = requireNotNull(tokens.localCoreWriteAccess())
        val storage = db.withTransaction { com.dayforge.data.local.LocalReplicaIdentity.readInTransaction(db) }
        val identity = LegacyWidgetRestartClaim.displayed(current, access, storage)
        val replica = ReplicaPresentationRepository(db, tokens, sessions)
        val preferences = HabitPreferenceRepository(db, tokens, sessions, this@CountCommandLegacyMetricTest.preferences, replica,
            LegacyWidgetIdentityVerifier(db, tokens, replica))
        val claim = preferences.read(current, widgetIdentity = identity).claim
        val original = CountCommandLegacyMetricInput.create(db, access, parent, claim, preferences, current,
            listOf(metric, second), values(), millis)
        val body = original.body(); val input = body.getValue("input").jsonObject
        val wrongWidget = Json.parseToJsonElement(identity.copy(storageId = id(9805)).encode())
        val changedInput = JsonObject(input + ("widget_claim" to wrongWidget))
        val encoded = CountCommandRecords.canonical(JsonObject(body + ("input" to changedInput))).toByteArray()
        val changed = CountCommandRecords.Intent.decode(encoded, encoded.size) {}
        val before = image(); val failure = rejected { store().registerChild(parent.reference.native(), changed) }
        assertEquals("COMMAND_WIDGET_REPLICA_CHANGED", failure.message); assertEquals(before, image())
        assertEquals(1, count("count_command_intents")); assertEquals(0, count("metric_logs"))
        // The unmodified actually captured input remains usable in this same living caller.
        val issuer = store(); val registered = issuer.registerChild(parent.reference.native(), original)
        issuer.releaseUnadmitted(requireNotNull(registered.ticket))
        val located = store().resolve(registered.reference)
        assertEquals(original.text(), located.historical.intent.text())
        assertEquals(CountCommandRecords.Execution.PREPARED, located.historical.status.execution)
    }

}
