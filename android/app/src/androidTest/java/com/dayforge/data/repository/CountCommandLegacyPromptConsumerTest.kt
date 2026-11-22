package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.model.CheckInResult
import com.dayforge.domain.service.CheckInService
import com.dayforge.ui.components.MetricValueInput
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual App legacy COUNT receipt -> original prompt consumer -> original metric writer ->
 * T2. These fixed-graph cases complement (not replace) the separate production Host gates.
 */
@RunWith(AndroidJUnit4::class)
class CountCommandLegacyPromptConsumerTest : NextObjectEditorFixture() {
    private suspend fun prepare(): Pair<CountCommandReceipt, com.dayforge.domain.model.CountActionIdentity> {
        tokens.getOrCreateInstallationId()
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            habit = habit.copy(appearance = null, planMetadata = null, completionPolicy = null)
            timerHabit = timerHabit.copy(appearance = null, planMetadata = null, completionPolicy = null)
            metric = metric.copy(appearance = null)
            db.habitDao().update(habit); db.habitDao().update(timerHabit); db.metricDao().update(metric)
            db.habitMetricLinkDao().insert(HabitMetricLinkEntity(habitId = habit.id, habitUuid = habit.uuid,
                metricId = metric.id, metricUuid = metric.uuid, promptOnComplete = true))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val identity = CountActionReader(db, tokens, sessions).legacy(habit)
        val service = CheckInService(creatingHabits(), db.completionDao(), db.timeLogDao())
        val result = service.incrementCount(app, habit.id, identity)
        assertTrue(result is CheckInResult.Success)
        val receipt = requireNotNull((result as CheckInResult.Success).command)
        assertTrue(receipt.recorded); assertEquals(1, count("completions"))
        preferences.addPendingMetricHabit(identity.session, habit.uuid)
        return receipt to identity
    }
    private fun coordinator(): LinkedMetricCoordinator {
        val owner = HabitPreferenceRepository(db, tokens, sessions, preferences, ReplicaPresentationRepository(db, tokens, sessions))
        return LinkedMetricCoordinator(app, preferences, creatingMetrics(), creatingHabits(), habitPreferences = owner)
    }
    private suspend fun saved(parent: CountCommandReceipt) = CountCommandStore(db, tokens, sessions).resolve(parent.committed.reference)
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }

    @Test(timeout = 150_000) fun realCountPromptWritesOneChildGroupAndAcknowledgesParentAndChildAfterPreferenceDelivery() = runBlocking<Unit> {
        val (parent, identity) = prepare(); val ui = coordinator()
        ui.showCountPrompt(identity, habit.name, parent)
        val prompt = requireNotNull(ui.postCheckInState.value?.habitPrompt)
        assertSame(parent, prompt.command)
        val values = listOf(MetricValueInput(metric.id, 3.0, "original legacy consumer"))
        withContext(Dispatchers.Main) {
            repeat(2) { assertTrue(ui.recordMetricValues(habit.id, values, expectedPreferenceClaim = prompt.source)) }
        }
        val child = requireNotNull(prompt.submittedCommand)
        assertEquals(1, count("completions")); assertEquals(1, count("metric_logs"))
        assertEquals(2, count("count_command_intents")); assertEquals(2, count("count_command_results"))
        assertEquals(CountCommandRecords.Delivery.DELIVERED, saved(parent).historical.status.delivery)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, saved(child).historical.status.delivery)
        assertTrue(CountCommandStore(db, tokens, sessions).discover(8).commands.isEmpty())
        assertEquals(1, db.syncOutboxDao().getAll().count { it.recordType == "metric_log" })
        for (receipt in listOf(parent, child)) {
            val row = saved(receipt)
            CountCommandLegacyEvidence.verify(row.historical.intent, requireNotNull(row.historical.outcome))
        }
        val before = image(); storage.reopen()
        assertEquals(before, image())
        assertEquals(CountCommandRecords.Delivery.DELIVERED, saved(parent).historical.status.delivery)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, saved(child).historical.status.delivery)
    }

    @Test(timeout = 150_000) fun originalEmptySubmissionClosesParentWithoutInventingAnEmptyMetricIntentOrTimestamp() = runBlocking<Unit> {
        val (parent, identity) = prepare(); val ui = coordinator()
        ui.showCountPrompt(identity, habit.name, parent)
        val prompt = requireNotNull(ui.postCheckInState.value?.habitPrompt)
        withContext(Dispatchers.Main) {
            assertTrue(ui.recordMetricValues(habit.id, emptyList(), expectedPreferenceClaim = prompt.source))
        }
        assertEquals(emptyList<MetricValueInput>(), prompt.submitted); assertNull(prompt.submittedCommand)
        assertEquals(1, count("count_command_intents")); assertEquals(1, count("count_command_results"))
        assertEquals(1, count("completions")); assertEquals(0, count("metric_logs"))
        assertEquals(CountCommandRecords.Delivery.CLOSED, saved(parent).historical.status.delivery)
        assertNull(saved(parent).historical.status.child)
        assertTrue(CountCommandStore(db, tokens, sessions).discover(8).commands.isEmpty())
        val result = saved(parent)
        CountCommandLegacyEvidence.verify(result.historical.intent, requireNotNull(result.historical.outcome))
        assertEquals(1, db.syncOutboxDao().getAll().count { it.recordType == "completion" })
        assertEquals(0, db.syncOutboxDao().getAll().count { it.recordType == "metric_log" })
    }

    @Test(timeout = 150_000) fun alreadyCommittedChildAfterNativeReopenIsDeliveredByFreshPromptWithoutSecondMetricWrite() = runBlocking<Unit> {
        val (parent, identity) = prepare(); val abandoned = coordinator()
        abandoned.showCountPrompt(identity, habit.name, parent)
        val original = requireNotNull(abandoned.postCheckInState.value?.habitPrompt)
        val owner = HabitPreferenceRepository(db, tokens, sessions, preferences, ReplicaPresentationRepository(db, tokens, sessions))
        val values = listOf(MetricValueDraft(metric.id, 7.0, "same explicit Save"))
        val committed = creatingMetrics().recordLegacyPromptValuesWithReceipt(values, System.currentTimeMillis(), original.source,
            owner, original.habit, original.metrics.map { requireNotNull(it.value) }, parent)
        val child = requireNotNull(committed.command)
        val frozenInput = saved(child).historical.intent.text()
        assertNull(original.submitted); assertEquals(1, count("metric_logs"))
        // T1 returned to this test driver, but no original UI/preference/T2 consumer ran.
        assertNotEquals(CountCommandRecords.Delivery.DELIVERED, saved(parent).historical.status.delivery)
        storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons)
        val fresh = coordinator()
        val current = requireNotNull(db.habitDao().getVisibleHabitById(habit.id))
        fresh.showCountPrompt(CountActionReader(db, tokens, sessions).legacy(current), current.name, parent)
        val prompt = requireNotNull(fresh.postCheckInState.value?.habitPrompt)
        withContext(Dispatchers.Main) {
            assertTrue(fresh.recordMetricValues(habit.id, listOf(MetricValueInput(metric.id, 7.0, "same explicit Save")),
                expectedPreferenceClaim = prompt.source))
        }
        assertEquals(child.reference, requireNotNull(prompt.submittedCommand).reference)
        assertEquals(frozenInput, saved(child).historical.intent.text())
        assertEquals(1, count("metric_logs")); assertEquals(2, count("count_command_intents")); assertEquals(2, count("count_command_results"))
        assertEquals(1, db.syncOutboxDao().getAll().count { it.recordType == "metric_log" })
        assertEquals(CountCommandRecords.Delivery.DELIVERED, saved(parent).historical.status.delivery)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, saved(child).historical.status.delivery)
        CountCommandLegacyEvidence.verify(saved(child).historical.intent, requireNotNull(saved(child).historical.outcome))
        assertTrue(CountCommandStore(db, tokens, sessions).discover(8).commands.isEmpty())
    }

    @Test(timeout = 150_000) fun sameExplicitSaveResumesPreparedChildOriginalIdTimeAndCompleteGroupAfterNativeReopen() = runBlocking<Unit> {
        val (parent, identity) = prepare(); val abandoned = coordinator()
        abandoned.showCountPrompt(identity, habit.name, parent)
        val original = requireNotNull(abandoned.postCheckInState.value?.habitPrompt)
        val owner = HabitPreferenceRepository(db, tokens, sessions, preferences, ReplicaPresentationRepository(db, tokens, sessions))
        val values = listOf(MetricValueDraft(metric.id, -0.0, "retain original bits and time"))
        val originalAt = System.currentTimeMillis()
        val attempt = editor.beginLegacyMetricChild(parent, original.source, owner, original.habit,
            original.metrics.map { requireNotNull(it.value) }, values, originalAt)
        val originalId = attempt.input.commandId; val originalInput = attempt.input.text()
        editor.releaseLegacyMetrics(attempt)
        assertEquals(0, count("metric_logs")); assertEquals(2, count("count_command_intents")); assertEquals(1, count("count_command_results"))
        storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons)
        val fresh = coordinator(); val current = requireNotNull(db.habitDao().getVisibleHabitById(habit.id))
        fresh.showCountPrompt(CountActionReader(db, tokens, sessions).legacy(current), current.name, parent)
        val prompt = requireNotNull(fresh.postCheckInState.value?.habitPrompt)
        withContext(Dispatchers.Main) {
            assertTrue(fresh.recordMetricValues(habit.id, listOf(MetricValueInput(metric.id, -0.0, "retain original bits and time")),
                expectedPreferenceClaim = prompt.source))
        }
        val child = requireNotNull(prompt.submittedCommand)
        assertEquals(originalId, child.commandId); assertEquals(originalInput, saved(child).historical.intent.text())
        assertEquals(1, count("metric_logs")); assertEquals(2, count("count_command_intents")); assertEquals(2, count("count_command_results"))
        val fact = db.openHelper.writableDatabase.query("SELECT date FROM metric_logs").use {
            assertTrue(it.moveToFirst()); val date = it.getLong(0); assertFalse(it.moveToNext()); date
        }
        assertEquals(originalAt, fact)
        assertEquals(1, db.syncOutboxDao().getAll().count { it.recordType == "metric_log" })
        assertEquals(CountCommandRecords.Delivery.DELIVERED, saved(parent).historical.status.delivery)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, saved(child).historical.status.delivery)
        CountCommandLegacyEvidence.verify(saved(child).historical.intent, requireNotNull(saved(child).historical.outcome))
        assertTrue(CountCommandStore(db, tokens, sessions).discover(8).commands.isEmpty())
    }


    @Test(timeout = 150_000) fun sameNumericSaveAfterSameUuidDefinitionChangeCannotDeliverOldGroupAsNewDefinition() = runBlocking<Unit> {
        val (parent, identity) = prepare(); val abandoned = coordinator()
        abandoned.showCountPrompt(identity, habit.name, parent)
        val original = requireNotNull(abandoned.postCheckInState.value?.habitPrompt)
        val owner = HabitPreferenceRepository(db, tokens, sessions, preferences, ReplicaPresentationRepository(db, tokens, sessions))
        val originalMetric = requireNotNull(original.metrics.single().value)
        val child = requireNotNull(creatingMetrics().recordLegacyPromptValuesWithReceipt(
            listOf(MetricValueDraft(metric.id, 3.0, "same number, different definition")), System.currentTimeMillis(),
            original.source, owner, original.habit, listOf(originalMetric), parent).command)
        val frozen = saved(child).historical.intent.text()
        creatingMetrics().updateMetric(originalMetric.copy(unit = originalMetric.unit + "-changed"))
        storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons)
        val fresh = coordinator(); val current = requireNotNull(db.habitDao().getVisibleHabitById(habit.id))
        fresh.showCountPrompt(CountActionReader(db, tokens, sessions).legacy(current), current.name, parent)
        val prompt = requireNotNull(fresh.postCheckInState.value?.habitPrompt)
        assertEquals(originalMetric.uuid, prompt.metrics.single().value?.uuid)
        assertEquals(originalMetric.unit + "-changed", prompt.metrics.single().value?.unit)
        val before = image()
        withContext(Dispatchers.Main) {
            assertFalse(fresh.recordMetricValues(habit.id,
                listOf(MetricValueInput(metric.id, 3.0, "same number, different definition")),
                expectedPreferenceClaim = prompt.source))
        }
        assertEquals(before, image()); assertNull(prompt.submitted); assertNull(prompt.submittedCommand)
        assertEquals(frozen, saved(child).historical.intent.text())
        assertEquals(1, count("metric_logs")); assertEquals(2, count("count_command_intents")); assertEquals(2, count("count_command_results"))
        assertNotEquals(CountCommandRecords.Delivery.DELIVERED, saved(child).historical.status.delivery)
        assertNotEquals(CountCommandRecords.Delivery.DELIVERED, saved(parent).historical.status.delivery)
        CountCommandLegacyEvidence.verify(saved(child).historical.intent, requireNotNull(saved(child).historical.outcome))
    }

}
