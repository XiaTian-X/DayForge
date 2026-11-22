package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.model.CheckInResult
import com.dayforge.domain.service.CheckInService
import com.dayforge.ui.components.MetricValueInput
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Original form restoration through the real Coordinator/private producer. This is a
 * fixed-graph contract class, not a substitute for Hilt Activity/kill/Compose field evidence. */
@RunWith(AndroidJUnit4::class)
class CountCommandRestoredMetricFormTest : NextObjectEditorFixture() {
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
        val result = CheckInService(creatingHabits(), db.completionDao(), db.timeLogDao()).incrementCount(app, habit.id, identity)
        return requireNotNull((result as CheckInResult.Success).command) to identity
    }
    private fun preferenceOwner() = HabitPreferenceRepository(db, tokens, sessions, preferences, ReplicaPresentationRepository(db, tokens, sessions))
    private fun coordinator() = LinkedMetricCoordinator(app, preferences, creatingMetrics(), creatingHabits(), habitPreferences = preferenceOwner())
    private suspend fun row(reference: CountCommandReference) = CountCommandStore(db, tokens, sessions).resolve(reference.native()).historical
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private suspend fun reopen() {
        storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons)
    }

    @Test(timeout = 150_000) fun actualRestoredPendingChildKeepsOriginalIdBitsDefinitionAndTimeAfterUnrelatedBusiness() = runBlocking<Unit> {
        val (parent, identity) = prepare(); val old = coordinator()
        old.showCountPrompt(identity, habit.name, parent)
        val original = requireNotNull(old.postCheckInState.value?.habitPrompt)
        val at = System.currentTimeMillis()
        val attempt = editor.beginLegacyMetricChild(parent, original.source, preferenceOwner(), original.habit,
            original.metrics.map { requireNotNull(it.value) }, listOf(MetricValueDraft(metric.id, -0.0, "frozen note")), at)
        val reference = CountCommandReference.from(requireNotNull(editor.locateMetricChild(parent)).reference)
        val input = attempt.input.text(); editor.releaseLegacyMetrics(attempt)
        db.withTransaction { db.openHelper.writableDatabase.execSQL("UPDATE metrics SET unit=unit WHERE id=?", arrayOf(metric.id)) }
        preferences.setNeverAskAgain(identity.session, habit.uuid, true)
        reopen()
        val fresh = coordinator(); val current = requireNotNull(db.habitDao().getVisibleHabitById(habit.id))
        val displayIdentity = CountActionReader(db, tokens, sessions).legacy(current)
        val beforeRead = image()
        fresh.showCountPrompt(displayIdentity, current.name, parent, restorePending = true)
        val state = requireNotNull(fresh.postCheckInState.value)
        assertEquals(reference.commandId, state.restoredChildId)
        val restored = requireNotNull(state.restoredInputs[metric.id])
        assertEquals((-0.0).toRawBits(), requireNotNull(com.dayforge.util.NumericInputUtils.parseFiniteDouble(restored.first)).toRawBits())
        assertEquals("frozen note", restored.second); assertEquals(beforeRead, image())
        val prompt = requireNotNull(state.habitPrompt)
        assertEquals(reference, prompt.restoredChild)
        withContext(Dispatchers.Main) {
            assertTrue(fresh.recordMetricValues(habit.id, listOf(MetricValueInput(metric.id,
                requireNotNull(com.dayforge.util.NumericInputUtils.parseFiniteDouble(restored.first)), restored.second)),
                expectedPreferenceClaim = prompt.source))
        }
        val child = requireNotNull(prompt.submittedCommand)
        assertEquals(reference, child.reference); assertEquals(input, row(reference).intent.text())
        assertEquals(1, count("metric_logs")); assertEquals(2, count("count_command_intents")); assertEquals(2, count("count_command_results"))
        assertEquals(at, requireNotNull(db.metricLogDao().getLogByUuid(requireNotNull(child.factUuid))).date)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, row(reference).status.delivery)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, row(parent.reference).status.delivery)
        CountCommandLegacyEvidence.verify(row(reference).intent, requireNotNull(row(reference).outcome))
        assertTrue(CountCommandStore(db, tokens, sessions).discover(8).commands.isEmpty())
    }

    @Test(timeout = 150_000) fun restoredAppliedChildCannotDisplayFrozenNumberUnderAChangedDefinitionOrCloseIt() = runBlocking<Unit> {
        val (parent, identity) = prepare(); val old = coordinator()
        old.showCountPrompt(identity, habit.name, parent)
        val original = requireNotNull(old.postCheckInState.value?.habitPrompt)
        val currentMetric = requireNotNull(original.metrics.single().value)
        val child = requireNotNull(creatingMetrics().recordLegacyPromptValuesWithReceipt(
            listOf(MetricValueDraft(metric.id, 3.0, "original definition")), System.currentTimeMillis(), original.source,
            preferenceOwner(), original.habit, listOf(currentMetric), parent).command)
        val input = row(child.reference).intent.text()
        creatingMetrics().updateMetric(currentMetric.copy(unit = currentMetric.unit + "-changed"))
        reopen()
        val fresh = coordinator(); val current = requireNotNull(db.habitDao().getVisibleHabitById(habit.id))
        val displayIdentity = CountActionReader(db, tokens, sessions).legacy(current)
        val before = image()
        val failure = try {
            fresh.showCountPrompt(displayIdentity, current.name, parent, restorePending = true)
            null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: IllegalArgumentException) { error }
        assertEquals("COMMAND_METRIC_DEFINITION_CHANGED", failure?.message)
        assertNull(fresh.postCheckInState.value); assertEquals(before, image())
        assertEquals(input, row(child.reference).intent.text())
        assertEquals(1, count("metric_logs")); assertEquals(2, count("count_command_intents")); assertEquals(2, count("count_command_results"))
        assertTrue(row(child.reference).status.pending); assertTrue(row(parent.reference).status.pending)
    }
}
