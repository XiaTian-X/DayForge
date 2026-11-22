package com.dayforge.data.repository

import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountTrustRetirementReason
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.HabitDevicePreferences
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.domain.model.StableHabitBinding
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountCommandFormalUndoBoundaryTest : CountCommandFormalUndoFixture() {
    @Test(timeout = 150_000) fun changedGenerationRootAndSourceCannotReuseAnEarlierExplicitDecision() = runBlocking<Unit> {
        add(); undoOriginal(durable = false); val owner = store()
        val first = decision(owner)
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            sql.execSQL("UPDATE metrics SET name=name WHERE id=?", arrayOf(metric.id))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val afterGeneration = image()
        refused("COMMAND_FOLLOWUP_REVALIDATION_REQUIRED") { owner.endObsoleteFollowUp(first, preferences) { true } }
        sameImage(afterGeneration, image())
        val second = decision(owner); val selected = editor.habit(habit.id)
        val extra = owner.register(CountCommandInputs.add(db, UUID.randomUUID().toString(), requireNotNull(selected.value),
            requireNotNull(selected.authority), Instant.now(), ZoneId.systemDefault(), 1))
        owner.releaseUnadmitted(requireNotNull(extra.ticket))
        val afterRoot = image()
        refused("COMMAND_FOLLOWUP_REVALIDATION_REQUIRED") { owner.endObsoleteFollowUp(second, preferences) { true } }
        sameImage(afterRoot, image())
        val third = decision(owner)
        mutation("UPDATE sync_outbox SET lastError='changed-after-decision' WHERE id=?", revert.id)
        val afterSource = image()
        assertNotNull(rejected { owner.endObsoleteFollowUp(third, preferences) { true } })
        sameImage(afterSource, image()); rejectedObservation()
    }
    @Test(timeout = 150_000) fun authorityRevisionAndLateConsumerInvalidationCannotFinishAnOldObservation() = runBlocking<Unit> {
        add(); undoOriginal(durable = false); val owner = store(); val old = decision(owner)
        register(revision = 2); val before = image()
        refused("COMMAND_FOLLOWUP_AUTHORITY_CHANGED") { owner.endObsoleteFollowUp(old, preferences) { true } }
        sameImage(before, image())
        val fresh = decision(owner); var calls = 0
        refused("COMMAND_RECOVERY_CONSUMER_CLOSED") { owner.endObsoleteFollowUp(fresh, preferences) { ++calls < 3 } }
        assertEquals(3, calls); sameImage(before, image()); pending(owner)
        endAndCold(owner, decision(owner))
    }
    @Test(timeout = 150_000) fun changedCredentialGenerationRejectsTheOldDecisionWithoutClearingThePendingGroup() = runBlocking<Unit> {
        add(); undoOriginal(durable = true); val owner = store(); val old = decision(owner)
        tokens.saveLoginSession("synthetic-fresh-C", "synthetic-fresh-refresh", "member", id(1), false)
        register(); val before = image(); val prefs = prefImage()
        refused("COMMAND_FOLLOWUP_AUTHORITY_CHANGED") { owner.endObsoleteFollowUp(old, preferences) { true } }
        sameImage(before, image()); assertEquals(prefs, prefImage()); pending(owner)
    }
    @Test(timeout = 150_000) fun validBrokenAndLateHintsStayByteExactAndDoNotBecomeAnExplicitEnd() = runBlocking<Unit> {
        add(); undoOriginal(durable = false); val owner = store(); val old = decision(owner)
        val session = local(); val binding = StableHabitBinding.capture(session, habit.uuid)
        val key = HabitDevicePreferences.key(binding)
        for (value in listOf(HabitDevicePreferences(binding, pending = true, pendingEpoch = session.syncEpoch).encode(), "malformed-original-hint")) {
            dataStore.edit { it[key] = value }
            val before = image(); val prefs = prefImage()
            val shown = requireNotNull(owner.obsoleteFollowUp(parent.reference.native(), preferences))
            assertFalse(shown.preferenceReady)
            refused("COMMAND_FOLLOWUP_PREFERENCE_UNRESOLVED") { owner.endObsoleteFollowUp(old, preferences) { true } }
            refused("COMMAND_FOLLOWUP_PREFERENCE_UNRESOLVED") { owner.endObsoleteFollowUp(shown, preferences) { true } }
            sameImage(before, image()); assertEquals(prefs, prefImage()); pending(owner)
        }
    }
    @Test(timeout = 150_000) fun activeLeaseAndRealReservedPartialMetricDoNotCountAsAnUnsubmittedChild() = runBlocking<Unit> {
        add(); val child = childPrepared(release = false); undoOriginal(durable = false)
        try { rejectedObservation() } finally { editor.releaseUnexecuted(requireNotNull(heldChild)); heldChild = null }
        decision()
        val original = row(child.native()); val item = original.intent.body().getValue("input").jsonObject.getValue("metrics").jsonArray.single().jsonObject
        val reserved = CountCommandRecords.uuid(item, "reserved_fact_uuid")
        db.withTransaction {
            val sql = db.openHelper.writableDatabase; sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val at = Instant.now(); val zone = ZoneId.systemDefault()
            db.metricLogDao().insert(MetricLogEntity(metricId = metric.id, date = at.toEpochMilli(), value = 2.5,
                unit = metric.unit, note = "actual partial child", uuid = reserved, createdAt = at.toEpochMilli(), updatedAt = at.toEpochMilli(),
                recordedTimezone = zone.id, recordedLocalDate = at.atZone(zone).toLocalDate().toString(), timeMetadataSource = "device"))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val before = image(); val prefs = prefImage()
        refused("COMMAND_EFFECT_OR_SOURCE_ALREADY_PRESENT") { store().obsoleteFollowUp(parent.reference.native(), preferences) }
        sameImage(before, image()); assertEquals(prefs, prefImage())
        assertEquals(CountCommandRecords.Execution.PREPARED, row(child.native()).status.execution)
        assertEquals(original.intent.text(), row(child.native()).intent.text()); assertNull(row(child.native()).outcome)
    }
    @Test(timeout = 150_000) fun anAppliedMetricChildStillBelongsToBDespiteTheLaterLegalCountUndo() = runBlocking<Unit> {
        add()
        val source = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions))
            .read(requireNotNull(db.habitDao().getVisibleHabitById(habit.id)))
        val target = editor.metric(metric.id)
        val child = requireNotNull(creatingMetrics().recordValuesWithReceipt(listOf(MetricValueDraft(metric.id, 3.0, "original saved")),
            System.currentTimeMillis(), authority = requireNotNull(target.authority), expectedMetrics = listOf(requireNotNull(target.value)),
            promptHabit = source.habit, promptFact = source.claim, promptCompletionSource = true, parentCommand = parent).command)
        undoOriginal(durable = false)
        val before = image(); val prefs = prefImage()
        assertNull(store().obsoleteFollowUp(parent.reference.native(), preferences))
        sameImage(before, image()); assertEquals(prefs, prefImage())
        assertEquals(CountCommandRecords.Delivery.METRICS_COMMITTED, row(parent.reference.native()).status.delivery)
        assertEquals(CountCommandRecords.Execution.APPLIED, row(child.reference.native()).status.execution)
        assertEquals(1, count("metric_logs"))
    }
    @Test(timeout = 150_000) fun cancellationAtTheRealCNativeRootWriteRollsBackTheWholeImageAndCanRetry() = runBlocking<Unit> {
        add(); undoOriginal(durable = false); val owner = store(); val proof = decision(owner)
        val before = image(); val prefs = prefImage(); var returned = false
        supervisorScope {
            val operation = launch(start = CoroutineStart.LAZY) {
                withContext(fault.cohort.asContextElement(true)) { owner.endObsoleteFollowUp(proof, preferences) { true } }
                returned = true; fail("Cancelled C may not return success")
            }
            fault.cancelAfterRoot(operation); operation.start(); operation.join(); assertTrue(operation.isCancelled)
        }
        assertFalse(returned); assertEquals(1, fault.cancelled.get()); assertEquals(1, fault.rootWrites.get())
        sameImage(before, image()); assertEquals(prefs, prefImage()); pending(owner)
        endAndCold(owner, decision(owner))
    }
    @Test(timeout = 150_000) fun actualUnknownEndColdResolvesTheSameParentAndChildTwiceWithoutReapplyingAnyBusiness() = runBlocking<Unit> {
        add(); val child = childPrepared(); undoOriginal(durable = true)
        val owner = store(); val proof = decision(owner); val before = authenticatedImage(); val prefs = prefImage()
        val oldOwner = requireNotNull(CountTrustRuntime.forDatabase(db)); fault.loseAfterRootEnd(); var returned = false
        val failure = try {
            withContext(fault.cohort.asContextElement(true)) { owner.endObsoleteFollowUp(proof, preferences) { true }; returned = true }
            null
        } catch (cancel: CancellationException) { throw cancel } catch (error: IllegalStateException) { error }
        fault.requireLostEnd(requireNotNull(failure)); assertFalse(returned)
        assertTrue(CountTrustRetirementReason.UNKNOWN_END in oldOwner.retirementReasons)
        repeat(2) {
            reopen(); assertNotSame(oldOwner, CountTrustRuntime.forDatabase(db)); val stable = image()
            assertEquals(parent.reference.native(), requireNotNull(store().closedFollowUp(parent.reference.native())).reference)
            assertEquals(child.native(), requireNotNull(store().closedFollowUp(child.native())).reference)
            resolvedClosed(parent.reference); resolvedClosed(child)
            sameImage(stable, image()); onlyCChanged(before, child); assertEquals(prefs, prefImage())
        }
        assertEquals(0, count("completions")); assertEquals(0, count("metric_logs")); assertEquals(2, count("count_command_results"))
    }
}
