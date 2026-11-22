package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.LocalReplicaIdentity
import com.dayforge.domain.service.StructuralEditGuard
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Same original v4 workflow and original T1 clock/target points. Real Room/native evidence
 * and real known-ID recovery; no Android task handoff/PID-kill or performance claim.
 */
@RunWith(AndroidJUnit4::class)
class CountCommandLegacyWidgetTest : NextObjectEditorFixture() {
    private val native = SourceWindowNativeSpy()
    init { storage.isolatedBuilder = { context -> native.build(context) } }

    private suspend fun legacy(countdown: Boolean = false) {
        tokens.getOrCreateInstallationId()
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            habit = habit.copy(appearance = null, planMetadata = null, completionPolicy = null,
                isCountdown = countdown, targetValue = if (countdown) 1 else 10)
            timerHabit = timerHabit.copy(appearance = null, planMetadata = null, completionPolicy = null)
            metric = metric.copy(appearance = null)
            db.habitDao().update(habit); db.habitDao().update(timerHabit); db.metricDao().update(metric)
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        assertEquals(0, count("sync_outbox")); db.withTransaction { assertFalse(NextProtocolAdmission.hasNextState(db)) }
    }
    private fun workflow(): LegacyWidgetFactWorkflow {
        val verifier = LegacyWidgetIdentityVerifier(db, tokens, ReplicaPresentationRepository(db, tokens, sessions))
        // Active habits never invoke the structure refresh. No business writer, authority,
        // DAO or native transaction is replaced by this unused network collaborator.
        return LegacyWidgetFactWorkflow(db, tokens, sessions, verifier, creatingHabits(), StructuralEditGuard(tokens, mockk()))
    }
    private suspend fun source(action: String): LegacyWidgetFactClaim {
        val identity = sessions.exclusive { db.withTransaction {
            LegacyWidgetRestartClaim.displayed(requireNotNull(db.habitDao().getVisibleHabitById(habit.id)),
                requireNotNull(tokens.localCoreWriteAccess()), LocalReplicaIdentity.readInTransaction(db))
        } }
        return workflow().capture(identity).forAction(action)
    }
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private fun reopen() {
        storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons)
    }
    private suspend fun exactReceipt(receipt: CountCommandReceipt) {
        val located = CountCommandStore(db, tokens, sessions).resolve(receipt.committed.reference)
        CountCommandLegacyEvidence.verify(located.historical.intent, requireNotNull(located.historical.outcome))
        assertEquals(receipt.inputHash, located.historical.intent.inputHash)
        assertEquals(receipt.factUuid, located.historical.outcome?.primaryFactUuid)
    }

    @Test(timeout = 150_000) fun actualWidgetAddAndDeleteRemainHistoricalAfterLaterCountAndReopen() = runBlocking<Unit> {
        legacy()
        val addClaim = source("increment"); val addId = UUID.randomUUID().toString()
        val added = workflow().performCountWithReceipt(app, addClaim, addId)
        val add = requireNotNull(added.result.command)
        assertTrue(added.added && add.recorded); assertEquals(addId, add.commandId)
        assertEquals(1, count("completions")); assertEquals(1, count("sync_outbox")); exactReceipt(add)
        val undoClaim = source("decrement"); val undoId = UUID.randomUUID().toString()
        val deleted = workflow().performCountWithReceipt(app, undoClaim, undoId)
        val undo = requireNotNull(deleted.result.command)
        assertFalse(deleted.added); assertTrue(undo.recorded); assertEquals(add.factUuid, undo.factUuid)
        assertEquals(0, count("completions")); assertEquals(2, count("sync_outbox")); exactReceipt(undo)
        val later = workflow().perform(app, source("increment")) // Original non-command oracle path.
        assertTrue(later.added); assertNull(later.result.command)
        assertEquals(1, count("completions")); assertEquals(3, count("sync_outbox"))
        val before = image(); reopen()
        for ((claim, id, expected) in listOf(Triple(addClaim, addId, add), Triple(undoClaim, undoId, undo))) {
            val result = workflow().resumeCountCommand(app, claim, id)
            assertTrue(result is WidgetCountCommandRecovery.Committed)
            assertEquals(expected.reference, (result as WidgetCountCommandRecovery.Committed).receipt.reference)
            exactReceipt(expected)
        }
        assertEquals(before, image())
        assertEquals(1, creatingHabits().getTodayCompletionCount(habit.id))
        assertEquals(2, count("count_command_results")); assertEquals(0, count("next_request_origins"))
        assertEquals(0, count("next_acceptances")); assertEquals(0, count("metric_logs"))
    }

    @Test(timeout = 150_000) fun actualExtraNoopSourceWriteRollsBackWholeT1AndSameIdCanRecover() = runBlocking<Unit> {
        legacy()
        val claim = source("increment"); val id = UUID.randomUUID().toString(); val before = image()
        native.armLegacyCountRateNoop()
        val failure = try { workflow().performCountWithReceipt(app, claim, id); null }
            catch (error: IllegalStateException) { error }
        assertTrue("Fault followed the actual rate UPDATE and affected one original source", native.legacyCountRateNoopTouched())
        assertEquals("COMMAND_LEGACY_UNEXPECTED_EFFECT_COUNT", failure?.message)
        val after = image()
        val excluded = com.dayforge.data.local.CountCommandV20Schema.tables + "count_command_results"
        assertEquals(before.getValue("schema"), after.getValue("schema"))
        fun oldBusiness(tables: JsonObject): JsonObject = JsonObject(tables.filterKeys { it !in excluded }.mapValues { (table, rows) ->
            if (table != "count_trust_control") rows else JsonArray(rows.jsonArray.map { row ->
                JsonObject(row.jsonObject.filterKeys { it != "commandRootHash" })
            })
        })
        assertEquals(oldBusiness(before.getValue("tables").jsonObject), oldBusiness(after.getValue("tables").jsonObject))
        // The separately committed T0 legitimately changes only the new anchor; its Root
        // must authenticate against that exact current value, while T1 entirely rolled back.
        db.withTransaction { com.dayforge.data.local.CountCommandStorageInitialization.verifyRootAnchor(db.openHelper.writableDatabase) }
        assertNotEquals(before.getValue("tables").jsonObject.getValue("count_trust_control"),
            after.getValue("tables").jsonObject.getValue("count_trust_control"))
        assertEquals(1, count("count_command_intents")); assertEquals(0, count("count_command_results"))
        assertEquals(0, count("completions")); assertEquals(0, count("sync_outbox"))
        val resumed = workflow().resumeCountCommand(app, claim, id)
        assertTrue(resumed is WidgetCountCommandRecovery.Committed)
        val receipt = (resumed as WidgetCountCommandRecovery.Committed).receipt
        assertEquals(id, receipt.commandId); assertTrue(receipt.recorded); exactReceipt(receipt)
        assertEquals(1, count("completions")); assertEquals(1, count("sync_outbox")); assertEquals(1, count("count_command_results"))
        val committed = image()
        assertTrue(workflow().resumeCountCommand(app, claim, id) is WidgetCountCommandRecovery.Committed)
        assertEquals(committed, image()); assertEquals(1, creatingHabits().getTodayCompletionCount(habit.id))
    }

    @Test(timeout = 150_000) fun preparedCountdownResumesSameInputThenAtZeroCommitsOriginalNoEffect() = runBlocking<Unit> {
        legacy(countdown = true)
        val claim = source("decrement"); val id = UUID.randomUUID().toString()
        val executor = CountCommandLegacyWidgetExecutor(db, tokens, sessions)
        val (input, ticket) = executor.begin(habit, claim, id)
        executor.release(ticket)
        assertEquals(0, count("completions")); assertEquals(0, count("sync_outbox"))
        reopen()
        val recovered = workflow().resumeCountCommand(app, claim, id)
        assertTrue(recovered is WidgetCountCommandRecovery.Committed)
        val applied = (recovered as WidgetCountCommandRecovery.Committed).receipt
        assertEquals(input.inputHash, applied.inputHash); assertEquals(id, applied.commandId)
        assertTrue(applied.recorded); assertEquals(1, count("completions")); exactReceipt(applied)
        val nativeFacts = db.completionDao().getAllCompletionsOnce(); val queued = db.syncOutboxDao().getAll()
        val noEffect = workflow().performCountWithReceipt(app, claim, UUID.randomUUID().toString())
        val receipt = requireNotNull(noEffect.result.command)
        assertFalse(noEffect.added); assertFalse(receipt.recorded); assertTrue(noEffect.result.completed)
        assertNull(receipt.factUuid); exactReceipt(receipt)
        assertEquals(nativeFacts, db.completionDao().getAllCompletionsOnce()); assertEquals(queued, db.syncOutboxDao().getAll())
        val before = image()
        val ordinary = workflow().perform(app, claim)
        assertFalse(ordinary.added); assertTrue(ordinary.result.completed); assertNull(ordinary.result.command)
        assertEquals(before, image())
        assertEquals(2, count("count_command_results")); assertEquals(0, count("next_request_origins"))
        assertEquals(0, count("metric_logs")); assertEquals(1, creatingHabits().getTodayCompletionCount(habit.id))
    }
}
