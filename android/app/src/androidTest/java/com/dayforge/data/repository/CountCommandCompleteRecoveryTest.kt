package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.LocalReplicaIdentity
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.domain.service.StructuralEditGuard
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Complete native/repository recovery route, not UI/pixel or actual-process-death evidence.
 * Existing fast-g tests remain unchanged; these explicitly request the independent full path.
 */
@RunWith(AndroidJUnit4::class)
class CountCommandCompleteRecoveryTest : NextObjectEditorFixture() {
    private data class Prepared(val store: CountCommandStore, val input: CountCommandRecords.Intent,
        val reference: CountCommandReference)
    private val sql get() = db.openHelper.writableDatabase
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private suspend fun prepared(): Prepared {
        val authority = requireNotNull(editor.habit(habit.id).authority)
        val input = CountCommandInputs.add(db, UUID.randomUUID().toString(), habit, authority,
            Instant.now(), ZoneId.systemDefault(), 1)
        val store = CountCommandStore(db, tokens, sessions); val result = store.register(input)
        store.releaseUnadmitted(requireNotNull(result.ticket))
        return Prepared(store, input, CountCommandReference.from(result.reference))
    }
    private suspend fun advance() { db.withTransaction { sql.execSQL("UPDATE metrics SET name=name WHERE id=?", arrayOf(metric.id)) } }
    private fun reopen() {
        storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons)
    }
    private suspend fun rejected(code: String, block: suspend () -> Unit) {
        val failure = try { block(); null } catch (error: CancellationException) { throw error }
            catch (error: IllegalStateException) { error } catch (error: IllegalArgumentException) { error }
        assertEquals(code, failure?.message)
    }

    @Test(timeout = 150_000) fun unrelatedCommittedBusinessThenColdCompleteProofResumesSameInputOnce() = runBlocking<Unit> {
        val p = prepared(); val old = p.store.resolve(p.reference.native()).historical
        advance()
        val before = image()
        val fast = p.store.recover(p.reference.native()) as CountCommandStore.Recovery.Unresolved
        assertEquals("ADVANCED_BUSINESS_REQUIRES_COMPLETE_NO_EFFECTS_PROOF", fast.reason)
        assertTrue(before == image())
        reopen()
        val result = creatingHabits().resumeCountCommandAfterCompleteProof(app, p.reference) as CountCommandRecovery.HistoricalCommitted
        assertEquals(p.reference, result.receipt.reference); assertTrue(result.receipt.recorded)
        val rows = db.completionDao().getAllCompletionsOnce(); assertEquals(1, rows.size)
        val input = p.input.body().getValue("input").jsonObject
        assertEquals(CountCommandRecords.uuid(input, "reserved_fact_uuid"), rows.single().uuid)
        assertEquals(Instant.parse(CountCommandRecords.text(input, "occurred_at")).toEpochMilli(), rows.single().actualCompletedAt)
        assertEquals(CountCommandRecords.text(input, "timezone"), rows.single().recordedTimezone)
        val current = CountCommandStore(db, tokens, sessions).resolve(p.reference.native()).historical
        assertEquals(old.intent.text(), current.intent.text())
        assertEquals(old.status.businessGenerationAtAdmission, current.status.businessGenerationAtAdmission)
        assertEquals(CountCommandRecords.Execution.APPLIED, current.status.execution)
        val queue = db.syncOutboxDao().getAll(); assertEquals(1, queue.size)
        assertEquals(NextRequestSql.sourceHash(queue.single()), originalIntent(queue.single()).sourceHash)
        val terminal = image(); reopen()
        val again = creatingHabits().resumeCountCommandAfterCompleteProof(app, p.reference) as CountCommandRecovery.HistoricalCommitted
        assertEquals(p.reference, again.receipt.reference); assertTrue(terminal == image())
    }

    @Test(timeout = 150_000) fun actualKnownTypedWidgetResumeUsesCompleteProofWithoutMintingAnotherCommand() = runBlocking<Unit> {
        val reader = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions))
        val claim = reader.read(habit).claim
        val input = CountCommandInputs.add(db, UUID.randomUUID().toString(), habit,
            requireNotNull(editor.habit(habit.id).authority), Instant.now(), ZoneId.systemDefault(), 1, claim)
        val store = CountCommandStore(db, tokens, sessions); val registered = store.register(input)
        store.releaseUnadmitted(requireNotNull(registered.ticket)); advance(); reopen()
        val result = creatingHabits().resumeWidgetCountCommand(app, claim, "increment", input.commandId) as WidgetCountCommandRecovery.Committed
        assertEquals(input.commandId, result.receipt.commandId)
        assertEquals(CountCommandRecords.uuid(input.body().getValue("input").jsonObject, "reserved_fact_uuid"), result.receipt.factUuid)
        val view = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions))
            .afterCountCommand(claim, result.receipt.factUuid)
        assertEquals(result.receipt.factUuid, view.completionSource?.claim?.completionUuid)
        assertEquals(1L, requireNotNull(view.current.count).todayQuantity)
        assertEquals(1, db.completionDao().getAllCompletionsOnce().size)
        assertEquals(1, db.syncOutboxDao().getAll().size)
        val terminal = CountCommandStore(db, tokens, sessions).resolve(registered.reference).historical
        assertEquals(input.text(), terminal.intent.text())
        val before = image()
        val repeated = creatingHabits().resumeWidgetCountCommand(app, claim, "increment", input.commandId) as WidgetCountCommandRecovery.Committed
        assertEquals(result.receipt.reference, repeated.receipt.reference); assertTrue(before == image())
    }

    @Test(timeout = 150_000) fun realReservedFactAndSourceWithoutOutcomeCannotBecomeNoEffects() = runBlocking<Unit> {
        val p = prepared(); val input = p.input.body().getValue("input").jsonObject
        val at = Instant.parse(CountCommandRecords.text(input, "occurred_at")); val zone = ZoneId.of(CountCommandRecords.text(input, "timezone"))
        // Deterministic partial native state, not a claimed legitimate command commit.
        db.withTransaction { db.completionDao().insert(CompletionEntity(habitId = habit.id, habitUuid = habit.uuid,
            date = at.toEpochMilli(), actualCompletedAt = at.toEpochMilli(), recordedTimezone = zone.id,
            recordedLocalDate = at.atZone(zone).toLocalDate().toString(), uuid = CountCommandRecords.uuid(input, "reserved_fact_uuid"))) }
        assertEquals(1, db.completionDao().getAllCompletionsOnce().size)
        assertEquals(1, db.syncOutboxDao().getAll().count { it.recordType == "completion" })
        val before = image()
        rejected("COMMAND_EFFECT_OR_SOURCE_ALREADY_PRESENT") { p.store.recoverAfterCompleteProof(p.reference.native()) }
        assertTrue(before == image()); assertNull(p.store.resolve(p.reference.native()).historical.outcome)
    }

    @Test(timeout = 150_000) fun provenTicketRejectsLaterBusinessMetadataAndForeignJobWithoutEnteringDomain() = runBlocking<Unit> {
        for (mode in listOf("business", "primary", "child")) {
            val p = prepared(); advance()
            val ready = p.store.recoverAfterCompleteProof(p.reference.native()) as CountCommandStore.Recovery.Ready
            if (mode == "business") advance()
            if (mode == "primary") prepared() // Another actual durable T0 changes root, not the old baseline.
            val before = image(); var entered = false
            suspend fun execute() { p.store.producerTransaction<Unit>(ready.ticket, requireNotNull(tokens.localCoreWriteAccess())) {
                entered = true; error("Must fail before the original domain callback")
            } }
            if (mode == "child") {
                supervisorScope { async { rejected("COMMAND_TICKET_CALLER_EXPIRED") { execute() } }.await() }
                p.store.releaseUnadmitted(ready.ticket)
            } else rejected("COMMAND_COMPLETE_RECOVERY_REVALIDATION_REQUIRED") { execute() }
            assertFalse(entered); assertTrue(before == image())
            assertTrue(db.completionDao().getAllCompletionsOnce().isEmpty())
        }
    }

    @Test(timeout = 150_000) fun completeRouteCannotBypassCurrentPrimaryAnchorWhenBusinessGenerationAdvanced() = runBlocking<Unit> {
        val p = prepared(); advance()
        val root = db.withTransaction { CountCommandNativeRows(sql) {}.root() }
        val control = db.withTransaction { CountCommandRootAnchor.read(sql) }
        assertEquals(CountCommandRootAnchor.digest(root), control.hash)
        db.withTransaction { requireNotNull(com.dayforge.data.local.CountTrustRuntime.forDatabase(db)).maintenance(db) {
            sql.execSQL("UPDATE count_trust_control SET commandRootHash=? WHERE id=1", arrayOf("0".repeat(64)))
        } }
        val before = image()
        rejected("COMMAND_PRIMARY_ANCHOR_MISMATCH") { p.store.recoverAfterCompleteProof(p.reference.native()) }
        assertTrue(before == image()); assertTrue(db.completionDao().getAllCompletionsOnce().isEmpty())
    }

    @Test(timeout = 150_000) fun legacyOriginalDeleteAfterUnrelatedWriteUsesSameTargetAndRetainedOutcome() = runBlocking<Unit> {
        tokens.getOrCreateInstallationId()
        db.withTransaction {
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            habit = habit.copy(appearance = null, planMetadata = null, completionPolicy = null, targetValue = 10)
            timerHabit = timerHabit.copy(appearance = null, planMetadata = null, completionPolicy = null)
            metric = metric.copy(appearance = null)
            db.habitDao().update(habit); db.habitDao().update(timerHabit); db.metricDao().update(metric)
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        fun workflow(): LegacyWidgetFactWorkflow = LegacyWidgetFactWorkflow(db, tokens, sessions,
            LegacyWidgetIdentityVerifier(db, tokens, ReplicaPresentationRepository(db, tokens, sessions)),
            creatingHabits(), StructuralEditGuard(tokens, mockk()))
        suspend fun source(action: String): LegacyWidgetFactClaim {
            val identity = sessions.exclusive { db.withTransaction { LegacyWidgetRestartClaim.displayed(habit,
                requireNotNull(tokens.localCoreWriteAccess()), LocalReplicaIdentity.readInTransaction(db)) } }
            return workflow().capture(identity).forAction(action)
        }
        val added = workflow().perform(app, source("increment")); assertTrue(added.added)
        val target = db.completionDao().getAllCompletionsOnce().single()
        val claim = source("decrement"); val commandId = UUID.randomUUID().toString()
        val executor = CountCommandLegacyWidgetExecutor(db, tokens, sessions)
        val pending = executor.begin(habit, claim, commandId); executor.release(pending.second)
        advance(); reopen()
        val result = workflow().resumeCountCommand(app, claim, commandId) as WidgetCountCommandRecovery.Committed
        assertEquals(commandId, result.receipt.commandId); assertEquals(target.uuid, result.receipt.factUuid)
        assertTrue(result.receipt.recorded); assertTrue(db.completionDao().getAllCompletionsOnce().isEmpty())
        val sources = db.syncOutboxDao().getAll(); assertEquals(2, sources.size)
        assertEquals(target.uuid, sources.single { it.action == "delete" }.entityUuid)
        val located = CountCommandStore(db, tokens, sessions).resolve(result.receipt.committed.reference).historical
        CountCommandLegacyEvidence.verify(located.intent, requireNotNull(located.outcome))
        assertEquals(pending.first.text(), located.intent.text())
        val before = image(); reopen()
        val again = workflow().resumeCountCommandAfterCompleteProof(app, claim, commandId) as WidgetCountCommandRecovery.Committed
        assertEquals(result.receipt.reference, again.receipt.reference); assertTrue(before == image())
    }
}
