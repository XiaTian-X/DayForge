package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** New explicit generic recovery only. Old ticket/T0/restore APIs retain their original
 * rejection; no Widget/metric claim or protocol namespace is rewritten. */
@RunWith(AndroidJUnit4::class)
class CountCommandGenericReauthenticationTest : NextObjectEditorFixture() {
    private data class Prepared(val store: CountCommandStore, val input: CountCommandRecords.Intent,
        val reference: CountCommandReference, val ticket: CountCommandStore.ExecutionTicket)
    private suspend fun prepared(widget: Boolean = false): Prepared {
        tokens.getOrCreateInstallationId()
        val authority = requireNotNull(editor.habit(habit.id).authority)
        val claim = if (widget) WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions)).read(habit).claim else null
        val input = CountCommandInputs.add(db, UUID.randomUUID().toString(), habit, authority, Instant.now(), ZoneId.systemDefault(), 1, claim)
        val store = CountCommandStore(db, tokens, sessions); val registered = store.register(input)
        return Prepared(store, input, CountCommandReference.from(registered.reference), requireNotNull(registered.ticket))
    }
    private suspend fun relogin() {
        val old = requireNotNull(tokens.localCoreWriteAccess())
        val installation = tokens.registeredInstallationId()
        tokens.saveLoginSession("explicit-new-session", "explicit-new-refresh", "member", old.session.authentication.userId, false)
        val current = requireNotNull(tokens.localCoreWriteAccess())
        assertNotEquals(old.session.authentication.generation, current.session.authentication.generation)
        assertEquals(old.session.authentication.userId, current.session.authentication.userId)
        assertEquals(old.session.serverInstanceId, current.session.serverInstanceId)
        assertEquals(old.session.syncEpoch, current.session.syncEpoch); assertEquals(old.capturedDeviceId, current.capturedDeviceId)
        assertEquals(installation, tokens.registeredInstallationId())
    }
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private suspend fun reject(code: String, block: suspend () -> Unit) {
        val failure = try { block(); null } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: IllegalArgumentException) { error } catch (error: IllegalStateException) { error }
        assertEquals(code, failure?.message)
    }
    private fun reopen() {
        storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons)
    }

    @Test(timeout = 150_000) fun freshExplicitGenericFormalActionResumesOldInputOnceWhileOldRestoreRemainsRejected() = runBlocking<Unit> {
        register(); val p = prepared(); p.store.releaseUnadmitted(p.ticket)
        val old = p.store.resolve(p.reference.native()).historical
        db.withTransaction { db.openHelper.writableDatabase.execSQL("UPDATE metrics SET name=name WHERE id=?", arrayOf(metric.id)) }
        relogin(); reopen()
        val store = CountCommandStore(db, tokens, sessions); val before = image()
        val refused = store.recoverAfterCompleteProof(p.reference.native()) as CountCommandStore.Recovery.Unresolved
        assertEquals("ORIGINAL_EXECUTION_SESSION_REVALIDATION_REQUIRED", refused.reason)
        assertEquals(before, image())
        val result = creatingHabits().resumeCountCommandAfterReauthentication(app, p.reference) as CountCommandRecovery.HistoricalCommitted
        assertEquals(p.reference, result.receipt.reference)
        val input = p.input.body().getValue("input").jsonObject
        val fact = db.completionDao().getAllCompletionsOnce().single()
        assertEquals(CountCommandRecords.uuid(input, "reserved_fact_uuid"), fact.uuid)
        assertEquals(Instant.parse(CountCommandRecords.text(input, "occurred_at")).toEpochMilli(), fact.actualCompletedAt)
        assertEquals(CountCommandRecords.text(input, "timezone"), fact.recordedTimezone)
        val current = store.resolve(p.reference.native()).historical
        assertEquals(p.input.text(), current.intent.text())
        assertEquals(old.status.businessGenerationAtAdmission, current.status.businessGenerationAtAdmission)
        assertEquals(CountCommandRecords.Execution.APPLIED, current.status.execution)
        val source = db.syncOutboxDao().getAll().single()
        assertEquals(NextRequestSql.sourceHash(source), originalIntent(source).sourceHash)
        val terminal = image(); reopen()
        val again = creatingHabits().resumeCountCommandAfterReauthentication(app, p.reference) as CountCommandRecovery.HistoricalCommitted
        assertEquals(p.reference, again.receipt.reference); assertEquals(terminal, image())
    }

    @Test(timeout = 150_000) fun legacyUndoKeepsItsFrozenTargetEvenWhenALaterCountExistsBeforeFreshSessionRecovery() = runBlocking<Unit> {
        db.withTransaction {
            val sql = db.openHelper.writableDatabase; sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            habit = habit.copy(appearance = null, planMetadata = null, completionPolicy = null)
            timerHabit = timerHabit.copy(appearance = null, planMetadata = null, completionPolicy = null)
            metric = metric.copy(appearance = null)
            db.habitDao().update(habit); db.habitDao().update(timerHabit); db.metricDao().update(metric)
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val repository = creatingHabits(); repository.logCompletion(app, habit.id, 1)
        val target = db.completionDao().getAllCompletionsOnce().single()
        val current = requireNotNull(db.habitDao().getHabitById(habit.id))
        val identity = CountActionReader(db, tokens, sessions).legacy(current)
        val input = CountCommandLegacyInput.undo(db, requireNotNull(tokens.localCoreWriteAccess()), current, identity, target)
        val store = CountCommandStore(db, tokens, sessions); val registered = store.register(input)
        store.releaseUnadmitted(requireNotNull(registered.ticket))
        repository.logCompletion(app, habit.id, 1)
        val later = db.completionDao().getAllCompletionsOnce().single { it.uuid != target.uuid }
        relogin(); reopen()
        val reference = CountCommandReference.from(registered.reference)
        val result = creatingHabits().resumeCountCommandAfterReauthentication(app, reference) as CountCommandRecovery.HistoricalCommitted
        assertEquals(reference, result.receipt.reference)
        assertNull(db.completionDao().getCompletionByUuid(target.uuid))
        assertEquals(later, db.completionDao().getCompletionByUuid(later.uuid))
        val resolved = CountCommandStore(db, tokens, sessions).resolve(registered.reference).historical
        assertEquals(input.text(), resolved.intent.text())
        CountCommandLegacyEvidence.verify(resolved.intent, requireNotNull(resolved.outcome))
        val before = image()
        creatingHabits().resumeCountCommandAfterReauthentication(app, reference)
        assertEquals(before, image())

        // A separately removed frozen target retains the original legal no-effect branch;
        // it must not select the still later fact just because the session was refreshed.
        val currentHabit = requireNotNull(db.habitDao().getHabitById(habit.id))
        val input2 = CountCommandLegacyInput.undo(db, requireNotNull(tokens.localCoreWriteAccess()), currentHabit,
            CountActionReader(db, tokens, sessions).legacy(currentHabit), later)
        val store2 = CountCommandStore(db, tokens, sessions); val saved2 = store2.register(input2)
        store2.releaseUnadmitted(requireNotNull(saved2.ticket))
        creatingHabits().undoCompletion(app, later.id)
        creatingHabits().logCompletion(app, habit.id, 1)
        val newest = db.completionDao().getAllCompletionsOnce().single()
        val queueBefore = db.syncOutboxDao().getAll()
        relogin()
        val reference2 = CountCommandReference.from(saved2.reference)
        val noEffect = creatingHabits().resumeCountCommandAfterReauthentication(app, reference2) as CountCommandRecovery.HistoricalCommitted
        assertFalse(noEffect.receipt.recorded)
        assertEquals(newest, db.completionDao().getAllCompletionsOnce().single())
        assertEquals(queueBefore, db.syncOutboxDao().getAll())
        val row2 = store2.resolve(saved2.reference).historical
        assertEquals(input2.text(), row2.intent.text())
        assertEquals(CountCommandRecords.Execution.NO_EFFECT, row2.status.execution)
        assertEquals(CountCommandRecords.NoEffectReason.TARGET_REMOVED_BEFORE_MUTATION, requireNotNull(row2.outcome).reason)
    }

    @Test(timeout = 150_000) fun oldTicketAndOldT0CannotUseNewSessionAndNewProofStillExpiresOnRevisionChange() = runBlocking<Unit> {
        register(); val p = prepared(); relogin()
        var entered = false; val before = image()
        reject("COMMAND_TICKET_CALLER_EXPIRED") {
            p.store.producerTransaction<Unit>(p.ticket, requireNotNull(tokens.localCoreWriteAccess())) { entered = true; error("unexpected") }
        }
        p.store.releaseUnadmitted(p.ticket)
        reject("COMMAND_CAPTURE_AUTHORITY_EXPIRED") { p.store.register(p.input) }
        assertFalse(entered); assertEquals(before, image())
        val ready = p.store.recoverAfterReauthentication(p.reference.native()) as CountCommandStore.Recovery.Ready
        val access = access(); tokens.saveDeviceRegistration(requireNotNull(access.deviceId), access.capabilities, true,
            requireNotNull(access.capabilityRevision) + 1)
        reject("COMMAND_COMPLETE_RECOVERY_REVALIDATION_REQUIRED") {
            p.store.producerTransaction<Unit>(ready.ticket, requireNotNull(tokens.localCoreWriteAccess())) { entered = true; error("unexpected") }
        }
        assertFalse(entered); assertEquals(before, image())
        val actual = creatingHabits().resumeCountCommandAfterReauthentication(app, p.reference) as CountCommandRecovery.HistoricalCommitted
        assertEquals(p.reference, actual.receipt.reference); assertEquals(1, db.completionDao().getAllCompletionsOnce().size)
    }

    @Test(timeout = 150_000) fun oldEmbeddedWidgetClaimIsNotRewrittenByTheNewExplicitGenericRoute() = runBlocking<Unit> {
        register(); val p = prepared(widget = true); p.store.releaseUnadmitted(p.ticket); relogin()
        val before = image()
        val result = p.store.recoverAfterReauthentication(p.reference.native()) as CountCommandStore.Recovery.Unresolved
        assertEquals("COMMAND_REAUTHENTICATION_REQUIRES_ORIGINAL_DOMAIN_HANDLER", result.reason)
        assertEquals(before, image()); assertTrue(db.completionDao().getAllCompletionsOnce().isEmpty())
        assertEquals(p.input.text(), p.store.resolve(p.reference.native()).historical.intent.text())
        assertFalse(CountCommandReauthentication.supports(p.input))
    }

    @Test(timeout = 150_000) fun changedDeviceAndChangedOriginalPlanRemainRejectedWithoutCreatingAnotherCommand() = runBlocking<Unit> {
        register(); val p = prepared(); p.store.releaseUnadmitted(p.ticket); relogin()
        tokens.saveDeviceRegistration(id(8001), caps, true, 1)
        var before = image()
        reject("COMMAND_CURRENT_NAMESPACE_ISOLATED") { p.store.recoverAfterReauthentication(p.reference.native()) }
        assertEquals(before, image())
        register()
        val selected = editor.habit(habit.id)
        creatingHabits().updateHabit(requireNotNull(selected.value).copy(targetValue = habit.targetValue + 1),
            editAuthority = requireNotNull(selected.authority))
        before = image()
        reject("COMMAND_ORIGINAL_SELECTION_CHANGED") { creatingHabits().resumeCountCommandAfterReauthentication(app, p.reference) }
        assertEquals(before, image()); assertTrue(db.completionDao().getAllCompletionsOnce().isEmpty())
        assertEquals(1, count("count_command_intents")); assertEquals(0, count("count_command_results"))
        assertEquals(p.input.text(), p.store.resolve(p.reference.native()).historical.intent.text())
    }

    @Test(timeout = 150_000) fun newExplicitProofCannotMoveToAnotherJobAndSameCallerCanRecoverTheOriginalId() = runBlocking<Unit> {
        val p = prepared(); p.store.releaseUnadmitted(p.ticket); relogin()
        val ready = p.store.recoverAfterReauthentication(p.reference.native()) as CountCommandStore.Recovery.Ready
        var entered = false; val before = image()
        supervisorScope {
            reject("COMMAND_TICKET_CALLER_EXPIRED") {
                withContext(Dispatchers.Default) {
                    p.store.producerTransaction<Unit>(ready.ticket, requireNotNull(tokens.localCoreWriteAccess())) { entered = true; error("unexpected") }
                }
            }
        }
        p.store.releaseUnadmitted(ready.ticket)
        assertFalse(entered); assertEquals(before, image())
        val result = creatingHabits().resumeCountCommandAfterReauthentication(app, p.reference) as CountCommandRecovery.HistoricalCommitted
        assertEquals(p.reference, result.receipt.reference); assertEquals(1, db.completionDao().getAllCompletionsOnce().size)
    }
    @Test(timeout = 150_000) fun firstRegistrationRemainsADifferentNamespaceProofAndCannotUseGenericRelogin() = runBlocking<Unit> {
        val p = prepared(); p.store.releaseUnadmitted(p.ticket)
        assertNull(p.input.namespace.server); assertNull(p.input.namespace.epoch)
        register(); p.store.acknowledgeInitialRegistration(); relogin()
        val located = p.store.resolve(p.reference.native())
        assertEquals(CountCommandStore.LocationScope.INITIAL_REGISTRATION_REVALIDATION_REQUIRED, located.scope)
        val before = image()
        val result = p.store.recoverAfterReauthentication(p.reference.native()) as CountCommandStore.Recovery.Unresolved
        assertEquals("INITIAL_REGISTRATION_EXECUTION_PROOF_REQUIRED", result.reason)
        assertEquals(before, image()); assertEquals(p.input.text(), p.store.resolve(p.reference.native()).historical.intent.text())
        assertTrue(db.completionDao().getAllCompletionsOnce().isEmpty()); assertEquals(0, count("count_command_results"))
    }

}
