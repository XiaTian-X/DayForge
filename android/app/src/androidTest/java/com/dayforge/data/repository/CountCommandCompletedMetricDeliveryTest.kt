package com.dayforge.data.repository

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.HabitDevicePreferences
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.domain.model.StableHabitBinding
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real private COUNT+two metric T1s. Historical delivery writes only the original parent/
 * child T2; this is not a form replay, PID-kill, or pixel test. Ordinary 150 seconds unchanged. */
@RunWith(AndroidJUnit4::class)
class CountCommandCompletedMetricDeliveryTest : NextObjectEditorFixture() {
    private lateinit var second: MetricEntity
    private lateinit var parent: CountCommandReceipt
    private lateinit var child: CountCommandReceipt
    private lateinit var replacedChild: CountCommandReference
    private fun store() = CountCommandStore(db, tokens, sessions)
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private suspend fun prefImage() = dataStore.data.first().asMap().mapKeys { it.key.name }
    private suspend fun row(ref: CountCommandReference) = store().resolve(ref.native()).historical
    private fun preferenceOwner() = HabitPreferenceRepository(db, tokens, sessions, preferences, ReplicaPresentationRepository(db, tokens, sessions))
    private suspend fun group(legacy: Boolean, replacePreparedChild: Boolean = false) {
        tokens.getOrCreateInstallationId(); register()
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            if (legacy) {
                habit = habit.copy(appearance = null, planMetadata = null, completionPolicy = null)
                timerHabit = timerHabit.copy(appearance = null, planMetadata = null, completionPolicy = null)
                metric = metric.copy(appearance = null)
                db.habitDao().update(habit); db.habitDao().update(timerHabit); db.metricDao().update(metric)
            }
            val candidate = metric.copy(id = 0, uuid = UUID.randomUUID().toString(), name = "Second historical metric")
            second = candidate.copy(id = db.metricDao().insert(candidate))
            for (value in listOf(metric, second)) db.habitMetricLinkDao().insertOrIgnore(HabitMetricLinkEntity(
                habitId = habit.id, habitUuid = habit.uuid, metricId = value.id, metricUuid = value.uuid, promptOnComplete = true))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val input = listOf(MetricValueDraft(metric.id, -0.0, "original signed zero"), MetricValueDraft(second.id, 4.5, "original second"))
        if (legacy) {
            parent = requireNotNull(creatingHabits().logCompletionWithReceipt(app, habit.id,
                countIdentity = CountActionReader(db, tokens, sessions).legacy(habit)).command)
            val current = requireNotNull(db.habitDao().getVisibleHabitById(habit.id))
            val owner = preferenceOwner(); val claim = owner.read(current).claim
            if (replacePreparedChild) {
                val previous = editor.beginLegacyMetricChild(parent, claim, owner, current, listOf(metric, second),
                    input.map { it.copy(value = it.value + 1.0) }, millis)
                replacedChild = CountCommandReference(previous.input.commandId, previous.input.inputHash,
                    previous.input.namespace.hash, previous.input.namespace.dataset)
                editor.releaseLegacyMetrics(previous)
            }
            child = requireNotNull(creatingMetrics().recordLegacyPromptValuesWithReceipt(input, millis, claim, owner,
                current, listOf(metric, second), parent).command)
        } else {
            parent = requireNotNull(creatingHabits().logCompletionWithReceipt(app, habit.id,
                authority = requireNotNull(editor.habit(habit.id).authority), expectedHabitUuid = habit.uuid).command)
            val source = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions))
                .read(requireNotNull(db.habitDao().getVisibleHabitById(habit.id)))
            val targets = editor.metrics(listOf(metric.id, second.id))
            child = requireNotNull(creatingMetrics().recordValuesWithReceipt(input, millis,
                authority = requireNotNull(targets.first().authority), expectedMetrics = targets.map { requireNotNull(it.value) },
                promptHabit = source.habit, promptFact = source.claim, promptCompletionSource = true, parentCommand = parent).command)
        }
        assertEquals(2, count("metric_logs")); assertEquals(2, count("count_command_results"))
        assertEquals(CountCommandRecords.Delivery.METRICS_COMMITTED, row(parent.reference).status.delivery)
        assertEquals(CountCommandRecords.Execution.APPLIED, row(child.reference).status.execution)
    }
    private fun reopen() {
        storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons)
    }
    private fun nonCommand(contents: JsonObject): JsonObject {
        val excluded = setOf("count_command_intents", "count_command_status", "count_command_results", "count_command_nodes", "count_command_recovery_roots")
        val tables = contents.getValue("tables").jsonObject
        val controls = tables.getValue("count_trust_control").jsonArray.map { entry ->
            val row = entry.jsonObject
            require(row.keys.containsAll(setOf("id", "storageId", "generation", "dirty", "commandRootHash")))
            JsonObject(row - "commandRootHash")
        }
        return JsonObject(contents + ("tables" to JsonObject(tables.filterKeys { it !in excluded } +
            ("count_trust_control" to JsonArray(controls)))))
    }
    private suspend fun deliver(owner: CountCommandStore, proof: CountCommandStore.CompletedMetricGroup) {
        val before = image(); val prefs = prefImage()
        owner.deliverCompletedMetricGroup(proof, preferences) { true }
        assertTrue("only exact command T2/anchor may change", nonCommand(before) == nonCommand(image()))
        assertTrue("no preference byte is written", prefs == prefImage())
        assertEquals(CountCommandRecords.Delivery.DELIVERED, row(parent.reference).status.delivery)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, row(child.reference).status.delivery)
    }
    private suspend fun refused(code: String, block: suspend () -> Unit) {
        val error = try { block(); null } catch (cancel: CancellationException) { throw cancel }
            catch (failure: IllegalArgumentException) { failure } catch (failure: IllegalStateException) { failure }
        assertEquals(code, error?.message)
    }

    @Test(timeout = 150_000) fun actualFormalMetricGroupDeliversHistoricalInputAfterDefinitionChangeWithoutSecondSave() = runBlocking<Unit> {
        group(false)
        val original = row(child.reference).intent.text(); val outcome = requireNotNull(row(child.reference).outcome).text()
        val current = editor.metric(metric.id)
        creatingMetrics().updateMetric(requireNotNull(current.value).copy(unit = "changed-unit"), editAuthority = requireNotNull(current.authority))
        reopen(); val owner = store(); val proof = requireNotNull(owner.completedMetricGroup(parent.reference.native(), preferences))
        assertEquals(2, proof.count); assertTrue(proof.preferenceReady); assertTrue(proof.pending)
        deliver(owner, proof)
        assertEquals(original, row(child.reference).intent.text()); assertEquals(outcome, requireNotNull(row(child.reference).outcome).text())
        assertEquals(2, count("metric_logs")); assertEquals(1, count("completions"))
        reopen(); val fresh = store(); val done = requireNotNull(fresh.completedMetricGroup(child.reference.native(), preferences))
        assertFalse(done.pending); val stable = image()
        fresh.deliverCompletedMetricGroup(done, preferences) { true }
        assertTrue("repeat T2 is native-read-only", stable == image())
    }

    @Test(timeout = 150_000) fun legacyDeletedHabitKeepsHistoricalWholeGroupAndDoesNotRecreateDeletedRows() = runBlocking<Unit> {
        group(true)
        val input = row(child.reference).intent.text(); val outcome = requireNotNull(row(child.reference).outcome).text()
        creatingHabits().deleteHabit(requireNotNull(db.habitDao().getVisibleHabitById(habit.id)))
        assertNull(db.habitDao().getHabitById(habit.id))
        val facts = db.completionDao().getAllCompletionsOnce(); val logs = db.metricLogDao().getAllLogsForMetric(metric.id) + db.metricLogDao().getAllLogsForMetric(second.id)
        reopen(); val owner = store(); val proof = requireNotNull(owner.completedMetricGroup(child.reference.native(), preferences))
        deliver(owner, proof)
        assertEquals(input, row(child.reference).intent.text()); assertEquals(outcome, requireNotNull(row(child.reference).outcome).text())
        assertEquals(facts, db.completionDao().getAllCompletionsOnce())
        assertEquals(logs, db.metricLogDao().getAllLogsForMetric(metric.id) + db.metricLogDao().getAllLogsForMetric(second.id))
        assertNull(db.habitDao().getHabitById(habit.id))
    }

    @Test(timeout = 150_000) fun existingOrMalformedOrProvisionalHintIsNeverClearedOrAcknowledged() = runBlocking<Unit> {
        group(true); val session = requireNotNull(tokens.localCoreWriteAccess()).session
        val binding = StableHabitBinding.capture(session, habit.uuid); val key = HabitDevicePreferences.key(binding)
        val provisional = StableHabitBinding.capture(session.copy(serverInstanceId = null, syncEpoch = null), habit.uuid)
        val alternatives = listOf(
            kotlin.Pair(key, HabitDevicePreferences(binding, pending = true, pendingEpoch = session.syncEpoch).encode()),
            kotlin.Pair(key, "malformed original preference"),
            kotlin.Pair(HabitDevicePreferences.key(provisional), HabitDevicePreferences(provisional, pending = true).encode()),
            kotlin.Pair(stringPreferencesKey("pending_metric_habits"), "[814]")
        )
        for ((target, value) in alternatives) {
            dataStore.edit { it.remove(key); it.remove(HabitDevicePreferences.key(provisional)); it.remove(stringPreferencesKey("pending_metric_habits")); it[target] = value }
            val before = image(); val prefs = prefImage(); val owner = store()
            val proof = requireNotNull(owner.completedMetricGroup(parent.reference.native(), preferences))
            assertFalse(proof.preferenceReady); assertEquals(2, proof.count)
            refused("COMMAND_METRIC_PREFERENCE_UNRESOLVED") { owner.deliverCompletedMetricGroup(proof, preferences) { true } }
            assertTrue(before == image()); assertTrue(prefs == prefImage())
            assertTrue(row(parent.reference).status.pending); assertTrue(row(child.reference).status.pending)
        }
    }

    @Test(timeout = 150_000) fun businessGenerationAndPrimaryRootChangesRequireFreshWholeGroupProof() = runBlocking<Unit> {
        group(true); val owner = store(); val old = requireNotNull(owner.completedMetricGroup(parent.reference.native(), preferences))
        db.metricDao().update(metric.copy(unit = "unrelated-business-version"))
        val changed = image()
        refused("COMMAND_METRIC_DELIVERY_REVALIDATION_REQUIRED") { owner.deliverCompletedMetricGroup(old, preferences) { true } }
        assertTrue(changed == image())
        val next = requireNotNull(owner.completedMetricGroup(parent.reference.native(), preferences))
        val identity = CountActionReader(db, tokens, sessions).legacy(requireNotNull(db.habitDao().getVisibleHabitById(habit.id)))
        val extra = owner.register(CountCommandInputs.explicitTargetAbsent(db, UUID.randomUUID().toString(), 990_000, identity))
        owner.releaseUnadmitted(requireNotNull(extra.ticket)); val appended = image()
        refused("COMMAND_METRIC_DELIVERY_REVALIDATION_REQUIRED") { owner.deliverCompletedMetricGroup(next, preferences) { true } }
        assertTrue(appended == image())
        val fresh = requireNotNull(owner.completedMetricGroup(parent.reference.native(), preferences))
        refused("COMMAND_METRIC_DELIVERY_FOREIGN_PROOF") { store().deliverCompletedMetricGroup(fresh, preferences) { true } }
        assertTrue(appended == image())
        deliver(owner, fresh)
        val remaining = store().discover(8).commands.single()
        assertEquals(extra.reference, remaining.reference)
        assertEquals(CountCommandRecords.Execution.PREPARED, remaining.historical.status.execution)
    }

    @Test(timeout = 150_000) fun changedAuthorityOrLateHintCannotDeliverAnOldReadPlan() = runBlocking<Unit> {
        group(true); val owner = store(); val old = requireNotNull(owner.completedMetricGroup(parent.reference.native(), preferences))
        register(revision = 2); val before = image()
        refused("COMMAND_METRIC_DELIVERY_REVALIDATION_REQUIRED") { owner.deliverCompletedMetricGroup(old, preferences) { true } }
        assertTrue(before == image())
        val next = requireNotNull(owner.completedMetricGroup(child.reference.native(), preferences))
        preferences.addPendingMetricHabit(requireNotNull(tokens.localCoreWriteAccess()).session, habit.uuid)
        val prefs = prefImage()
        refused("COMMAND_METRIC_PREFERENCE_UNRESOLVED") { owner.deliverCompletedMetricGroup(next, preferences) { true } }
        assertTrue(before == image()); assertTrue(prefs == prefImage())
    }

    @Test(timeout = 150_000) fun currentFormalSourceDamageNeverBecomesHistoricalDeliveryPermission() = runBlocking<Unit> {
        group(false)
        val source = db.syncOutboxDao().getAll().first { it.recordType == "metric_log" }
        db.withTransaction { db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET lastError='fixed-unexplained-change' WHERE id=?", arrayOf(source.id)) }
        val before = image(); val prefs = prefImage()
        refused("COMMAND_LIVE_SOURCE_CHANGED") { store().completedMetricGroup(child.reference.native(), preferences) }
        assertTrue(before == image()); assertTrue(prefs == prefImage()); assertEquals(2, count("metric_logs"))
    }

    @Test(timeout = 150_000) fun lateConsumerInvalidationRollsBackBothT2RowsAndNewConsumerCanRetry() = runBlocking<Unit> {
        group(true); val owner = store(); val proof = requireNotNull(owner.completedMetricGroup(parent.reference.native(), preferences))
        val before = image(); val prefs = prefImage(); var calls = 0
        refused("COMMAND_RECOVERY_CONSUMER_CLOSED") { owner.deliverCompletedMetricGroup(proof, preferences) { ++calls < 3 } }
        assertEquals(3, calls); assertTrue(before == image()); assertTrue(prefs == prefImage())
        assertTrue(row(parent.reference).status.pending); assertTrue(row(child.reference).status.pending)
        deliver(owner, requireNotNull(owner.completedMetricGroup(parent.reference.native(), preferences)))
    }
    @Test(timeout = 150_000) fun replacedOldChildReferenceNeverSelectsTheNewAppliedSiblingGroup() = runBlocking<Unit> {
        group(true, replacePreparedChild = true)
        val retired = row(replacedChild)
        assertEquals(CountCommandRecords.Execution.INVALIDATED, retired.status.execution)
        assertEquals(CountCommandRecords.Delivery.CLOSED, retired.status.delivery)
        assertNull(retired.outcome)
        assertNotEquals(replacedChild, child.reference)
        assertEquals(child.commandId, row(parent.reference).status.child)
        val before = image(); val prefs = prefImage(); val owner = store()
        assertNull(owner.completedMetricGroup(replacedChild.native(), preferences))
        assertTrue(before == image()); assertTrue(prefs == prefImage())
        val current = requireNotNull(owner.completedMetricGroup(child.reference.native(), preferences))
        assertEquals(child.reference.native(), current.child)
        assertEquals(parent.reference.native(), current.parent)
        deliver(owner, current)
        assertEquals(retired.intent.text(), row(replacedChild).intent.text())
        assertEquals(retired.status, row(replacedChild).status)
        assertNull(row(replacedChild).outcome)
        assertEquals(3, count("count_command_intents")); assertEquals(2, count("count_command_results")); assertEquals(2, count("metric_logs"))
    }

}
