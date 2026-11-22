package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountTrustRetirementReason
import com.dayforge.data.local.CountTrustRuntime
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Same ID/old input, complete NoEffects, structured original-domain mismatch. Not a generic
 * exception-to-cancel adapter, no linked child/C/source-loss policy and no real process kill. */
@RunWith(AndroidJUnit4::class)
class CountCommandGenericInvalidationTest : NextObjectEditorFixture() {
    private val fault = GenericInvalidationNativeFault()
    init { storage.isolatedBuilder = { buildDiagnosticFaultDatabase(it, fault) } }
    private fun store() = CountCommandStore(db, tokens, sessions)
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private suspend fun row(ref: CountCommandStore.Reference) = store().resolve(ref).historical
    private data class Pending(val input: CountCommandRecords.Intent, val ref: CountCommandStore.Reference)
    private suspend fun prepare(legacy: Boolean = false): Pending {
        tokens.getOrCreateInstallationId(); register()
        if (legacy) db.withTransaction {
            val sql = db.openHelper.writableDatabase; sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            habit = habit.copy(appearance = null, planMetadata = null, completionPolicy = null)
            timerHabit = timerHabit.copy(appearance = null, planMetadata = null, completionPolicy = null)
            metric = metric.copy(appearance = null)
            db.habitDao().update(habit); db.habitDao().update(timerHabit); db.metricDao().update(metric)
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val input = if (legacy) CountCommandLegacyInput.add(db, requireNotNull(tokens.localCoreWriteAccess()), habit,
            CountActionReader(db, tokens, sessions).legacy(habit), Instant.now(), ZoneId.systemDefault(), 1)
        else CountCommandInputs.add(db, UUID.randomUUID().toString(), habit, requireNotNull(editor.habit(habit.id).authority),
            Instant.now(), ZoneId.systemDefault(), 1)
        val owner = store(); val saved = owner.register(input); owner.releaseUnadmitted(requireNotNull(saved.ticket))
        return Pending(input, saved.reference)
    }
    private suspend fun changePlan(legacy: Boolean = false) {
        val current = requireNotNull(db.habitDao().getVisibleHabitById(habit.id))
        creatingHabits().updateHabit(current.copy(targetValue = current.targetValue + 1),
            editAuthority = if (legacy) null else requireNotNull(editor.habit(habit.id).authority))
    }
    private fun reopen() { storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons) }
    private fun nonCommand(contents: JsonObject): JsonObject {
        val excluded = setOf("count_command_intents", "count_command_status", "count_command_results", "count_command_nodes", "count_command_recovery_roots")
        val tables = contents.getValue("tables").jsonObject
        val controls = tables.getValue("count_trust_control").jsonArray.map {
            require(it.jsonObject.keys.containsAll(setOf("id", "storageId", "generation", "dirty", "commandRootHash")))
            JsonObject(it.jsonObject - "commandRootHash")
        }
        return JsonObject(contents + ("tables" to JsonObject(tables.filterKeys { it !in excluded } +
            ("count_trust_control" to JsonArray(controls)))))
    }
    private suspend fun refused(code: String, block: suspend () -> Unit) {
        val error = try { block(); null } catch (cancel: CancellationException) { throw cancel }
            catch (failure: IllegalArgumentException) { failure } catch (failure: IllegalStateException) { failure }
        assertEquals(code, error?.message)
    }
    private suspend fun assertNoExecution(p: Pending) {
        assertEquals(p.input.text(), row(p.ref).intent.text()); assertNull(row(p.ref).outcome)
        assertEquals(0, count("completions")); assertEquals(0, count("count_command_results"))
    }

    @Test(timeout = 150_000) fun actualTypedPlanChangeInvalidatesOnlyTheOriginalUnexecutedInputAndDeliversAfterNativeEnd() = runBlocking<Unit> {
        val p = prepare(); val owner = store()
        val stillExecuting = owner.recover(p.ref) as CountCommandStore.Recovery.Ready
        changePlan(); val before = image()
        assertNull(owner.prepareGenericInvalidation(p.ref)); assertTrue(before == image())
        owner.releaseUnadmitted(stillExecuting.ticket)
        val plan = requireNotNull(owner.prepareGenericInvalidation(p.ref))
        assertTrue(before == image())
        val result = owner.finishGenericInvalidation(plan)
        assertEquals(p.ref, result.reference); assertEquals(CountCommandInvalidationDomain.Reason.ORIGINAL_COUNT_PLAN_CHANGED, result.reason)
        assertEquals(CountCommandRecords.Execution.INVALIDATED, row(p.ref).status.execution)
        assertEquals(CountCommandRecords.Delivery.RESULT_PENDING, row(p.ref).status.delivery)
        assertTrue(nonCommand(before) == nonCommand(image())); assertNoExecution(p)
        owner.deliverInvalidatedGeneric(result) { true }
        assertEquals(CountCommandRecords.Delivery.DELIVERED, row(p.ref).status.delivery)
        assertNoExecution(p)
        reopen(); val current = store(); val historical = requireNotNull(current.invalidatedGeneric(p.ref))
        assertFalse(historical.pending); val stable = image()
        current.deliverInvalidatedGeneric(historical) { true }
        assertTrue(stable == image())
        val oldApi = current.recover(p.ref) as CountCommandStore.Recovery.Unresolved
        assertEquals("ORIGINAL_COMMAND_TERMINAL_WITHOUT_BUSINESS_RESULT", oldApi.reason)
        assertNoExecution(p)
    }

    @Test(timeout = 150_000) fun actualLegacyPlanChangeUsesTheOriginalPlanAndNeverRewritesFactTimeOrId() = runBlocking<Unit> {
        val p = prepare(true); changePlan(true); val owner = store(); val before = image()
        val result = owner.finishGenericInvalidation(requireNotNull(owner.prepareGenericInvalidation(p.ref)))
        assertEquals(CountCommandInvalidationDomain.Reason.ORIGINAL_COUNT_PLAN_CHANGED, result.reason)
        assertNoExecution(p); assertTrue(nonCommand(before) == nonCommand(image()))
        owner.deliverInvalidatedGeneric(result) { true }; assertNoExecution(p)
        assertEquals(1, count("count_command_intents")); assertEquals(1, count("count_command_status"))
    }

    @Test(timeout = 150_000) fun unchangedPlanAndOriginalMissingUndoTargetKeepTheirExistingExecutionAndNoEffectPaths() = runBlocking<Unit> {
        val p = prepare(true); val owner = store(); val before = image()
        assertNull(owner.prepareGenericInvalidation(p.ref)); assertTrue(before == image())
        val committed = creatingHabits().resumeCountCommand(app, CountCommandReference.from(p.ref)) as CountCommandRecovery.HistoricalCommitted
        assertTrue(committed.receipt.recorded)
        val target = requireNotNull(db.completionDao().getCompletionByUuid(requireNotNull(committed.receipt.factUuid)))
        val current = requireNotNull(db.habitDao().getVisibleHabitById(habit.id))
        val identity = CountActionReader(db, tokens, sessions).legacy(current)
        val undo = CountCommandLegacyInput.undo(db, requireNotNull(tokens.localCoreWriteAccess()), current, identity, target)
        val saved = owner.register(undo); owner.releaseUnadmitted(requireNotNull(saved.ticket))
        creatingHabits().undoCompletion(app, target.id, countIdentity = identity)
        val afterDelete = image()
        assertNull(owner.prepareGenericInvalidation(saved.reference)); assertTrue(afterDelete == image())
        val noEffect = creatingHabits().resumeCountCommandAfterCompleteProof(app, CountCommandReference.from(saved.reference)) as CountCommandRecovery.HistoricalCommitted
        assertFalse(noEffect.receipt.recorded)
        assertEquals(CountCommandRecords.Execution.NO_EFFECT, row(saved.reference).status.execution)
        assertEquals(undo.text(), row(saved.reference).intent.text()); assertEquals(0, count("completions"))
    }

    @Test(timeout = 150_000) fun malformedNativeStateIsAnErrorAndMissingObjectIsNotGuessedInvalidated() = runBlocking<Unit> {
        val p = prepare(); changePlan()
        db.withTransaction { db.openHelper.writableDatabase.execSQL("UPDATE habits SET targetValue=-1 WHERE id=?", arrayOf(habit.id)) }
        val damaged = image()
        val error = try { store().prepareGenericInvalidation(p.ref); null } catch (cancel: CancellationException) { throw cancel }
            catch (failure: IllegalArgumentException) { failure } catch (failure: IllegalStateException) { failure }
        assertNotNull(error); assertTrue(damaged == image()); assertEquals(CountCommandRecords.Execution.PREPARED, row(p.ref).status.execution)
        // A native disappearance has no legitimate deletion proof. It must not be promoted
        // into a terminal status; this is deliberately not an authorized-delete positive.
        db.withTransaction { db.openHelper.writableDatabase.execSQL("DELETE FROM habits WHERE id=?", arrayOf(habit.id)) }
        val missing = image(); assertNull(store().prepareGenericInvalidation(p.ref)); assertTrue(missing == image())
        assertEquals(CountCommandRecords.Execution.PREPARED, row(p.ref).status.execution)
        assertNoExecution(p)
    }

    @Test(timeout = 150_000) fun staleProofAuthorityOrBusinessVersionRejectsAndReleasesTheSameLivingCallerLease() = runBlocking<Unit> {
        val p = prepare(); changePlan(); val owner = store()
        val initial = requireNotNull(owner.prepareGenericInvalidation(p.ref))
        val beforeForeign = image()
        withContext(Dispatchers.Default) {
            refused("COMMAND_INVALIDATION_CALLER_EXPIRED") { owner.finishGenericInvalidation(initial) }
        }
        assertTrue(beforeForeign == image())
        db.metricDao().update(metric.copy(unit = "unrelated-after-proof")); val advanced = image()
        refused("COMMAND_INVALIDATION_REVALIDATION_REQUIRED") { owner.finishGenericInvalidation(initial) }
        assertTrue(advanced == image()); assertEquals(CountCommandRecords.Execution.PREPARED, row(p.ref).status.execution)
        val second = requireNotNull(owner.prepareGenericInvalidation(p.ref))
        register(revision = 2); val before = image()
        refused("COMMAND_INVALIDATION_AUTHORITY_CHANGED") { owner.finishGenericInvalidation(second) }
        assertTrue(before == image()); assertEquals(CountCommandRecords.Execution.PREPARED, row(p.ref).status.execution)
        val final = owner.finishGenericInvalidation(requireNotNull(owner.prepareGenericInvalidation(p.ref)))
        assertEquals(CountCommandRecords.Execution.INVALIDATED, row(p.ref).status.execution)
        assertNoExecution(p); owner.deliverInvalidatedGeneric(final) { true }
    }

    @Test(timeout = 150_000) fun cancellationAfterActualStatusWriteRollsBackWholeMainAndAllowsOriginalIdRetry() = runBlocking<Unit> {
        val p = prepare(); changePlan(); val before = image()
        val worker = async {
            val owner = store(); val plan = requireNotNull(owner.prepareGenericInvalidation(p.ref))
            fault.cancelAfterStatus(p.ref.commandId, requireNotNull(currentCoroutineContext()[Job]))
            owner.finishGenericInvalidation(plan)
        }
        try { worker.await(); fail("cancelled invalidation must not return a terminal result") } catch (_: CancellationException) { }
        assertTrue(worker.isCancelled); assertEquals(1, fault.statusWrites); assertEquals(1, fault.cancelSignals)
        reopen(); assertTrue(before == image()); assertEquals(CountCommandRecords.Execution.PREPARED, row(p.ref).status.execution)
        val fresh = store(); fresh.finishGenericInvalidation(requireNotNull(fresh.prepareGenericInvalidation(p.ref)))
        assertNoExecution(p)
    }

    @Test(timeout = 150_000) fun lostActualEndIsUnknownUntilNewOwnerFindsTheSameInvalidatedResultWithoutReexecution() = runBlocking<Unit> {
        val p = prepare(); changePlan(); val before = image(); val owner = store()
        val plan = requireNotNull(owner.prepareGenericInvalidation(p.ref)); val oldOwner = requireNotNull(CountTrustRuntime.forDatabase(db))
        fault.loseCommittedEnd(p.ref.commandId)
        val error = try { owner.finishGenericInvalidation(plan); null } catch (cancel: CancellationException) { throw cancel }
            catch (failure: IllegalStateException) { failure } catch (failure: IllegalArgumentException) { failure }
        assertNotNull(error); fault.requireActualLostEnd(requireNotNull(error))
        assertTrue(CountTrustRetirementReason.UNKNOWN_END in oldOwner.retirementReasons)
        reopen(); val fresh = store(); val result = requireNotNull(fresh.invalidatedGeneric(p.ref))
        assertTrue(result.pending); assertNoExecution(p); assertTrue(nonCommand(before) == nonCommand(image()))
        assertEquals(2L, row(p.ref).status.revision)
        fresh.deliverInvalidatedGeneric(result) { true }
        assertEquals(3L, row(p.ref).status.revision); assertEquals(1, fault.statusWrites); assertNoExecution(p)
    }
    @Test(timeout = 150_000) fun temporaryPermissionOrDefaultZoneChangeAndReservedEffectNeverBecomeInvalidation() = runBlocking<Unit> {
        val p = prepare(true); changePlan(true); val owner = store()
        register(permissions = caps - "facts.append", revision = 2)
        val denied = image(); assertNull(owner.prepareGenericInvalidation(p.ref)); assertTrue(denied == image())
        assertEquals(CountCommandRecords.Execution.PREPARED, row(p.ref).status.execution)
        register(revision = 3)
        val oldZone = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(if (oldZone.id == "Pacific/Auckland") "UTC" else "Pacific/Auckland"))
            val before = image(); assertNull(owner.prepareGenericInvalidation(p.ref)); assertTrue(before == image())
            assertEquals(CountCommandRecords.Execution.PREPARED, row(p.ref).status.execution)
        } finally { java.util.TimeZone.setDefault(oldZone) }
        val input = p.input.body().getValue("input").jsonObject
        val occurred = Instant.parse(CountCommandRecords.text(input, "occurred_at"))
        val zone = ZoneId.of(CountCommandRecords.text(input, "timezone"))
        val date = occurred.atZone(zone).toLocalDate()
        db.completionDao().insert(com.dayforge.data.local.entity.CompletionEntity(habitId = habit.id,
            date = date.atStartOfDay(zone).toInstant().toEpochMilli(), value = 1, actualCompletedAt = occurred.toEpochMilli(),
            uuid = CountCommandRecords.uuid(input, "reserved_fact_uuid"), habitUuid = habit.uuid,
            recordedTimezone = zone.id, recordedLocalDate = date.toString(), timeMetadataSource = "captured"))
        val collision = image()
        refused("COMMAND_EFFECT_OR_SOURCE_ALREADY_PRESENT") { owner.prepareGenericInvalidation(p.ref) }
        assertTrue(collision == image()); assertEquals(CountCommandRecords.Execution.PREPARED, row(p.ref).status.execution)
        assertNull(row(p.ref).outcome); assertEquals(0, count("count_command_results"))
    }

}
