package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Four original producer/acceptance combinations, not four fabricated witness values. */
@RunWith(AndroidJUnit4::class)
class CountCommandFormalUndoEndTest : CountCommandFormalUndoFixture() {
    @Test(timeout = 150_000) fun ordinaryNeverSentUndoNeedsExplicitEndAndRetainsBothOriginalSourcesAcrossColdReads() = runBlocking<Unit> {
        add(); undoOriginal(durable = false)
        assertEquals(1, count("count_command_intents")); assertEquals(0, count("next_acceptances"))
        val owner = store(); val proof = decision(owner)
        // Merely displaying the complete decision leaves C pending and all original bytes intact.
        assertNull(owner.closedFollowUp(parent.reference.native())); pending(owner)
        endAndCold(owner, proof)
        assertEquals(2, count("sync_outbox")); assertEquals(2, count("next_request_origins"))
    }
    @Test(timeout = 150_000) fun durableNeverSentUndoEndsOnlyParentAndPreparedChildWhileKeepingUndoOutcome() = runBlocking<Unit> {
        add(); val child = childPrepared(); undoOriginal(durable = true)
        val undoBefore = row(requireNotNull(undo).reference.native())
        val owner = store(); endAndCold(owner, decision(owner), child)
        val undoAfter = row(requireNotNull(undo).reference.native())
        assertEquals(undoBefore.intent.text(), undoAfter.intent.text())
        assertEquals(undoBefore.outcome!!.text(), undoAfter.outcome!!.text())
        assertEquals(CountCommandRecords.Execution.APPLIED, undoAfter.status.execution)
        assertTrue(undoAfter.status.pending); assertEquals(2, count("count_command_results"))
    }
    @Test(timeout = 150_000) fun ordinaryAcceptedUndoKeepsOriginalFactHeadAfterRealRestartAndClosesThePreparedChild() = runBlocking<Unit> {
        add(ack = true); val child = childPrepared(); advanceRound()
        val latest = requireNotNull(editor.habit(habit.id).authority).rounds!!.completionRounds.head(habit.uuid)
        undoOriginal(durable = false, ack = true)
        val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, revert.operationId))
        val oldHead = requireNotNull(roundOperationIntent(origin.intentJson)).context.head
        assertNotEquals(latest, oldHead) // Legal undo inherits the retained fact head, not today's head.
        val owner = store(); endAndCold(owner, decision(owner), child)
        assertEquals(0, count("sync_outbox")); assertEquals(3, count("next_acceptances"))
        assertEquals(1, count("next_restart_plan_proofs"))
    }
    @Test(timeout = 150_000) fun durableAcceptedUndoUsesRealAckRatherThanVanishedQueueAndKeepsImmutableHistory() = runBlocking<Unit> {
        add(ack = true); undoOriginal(durable = true, ack = true)
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, revert.operationId))
        assertNotNull(db.nextRequestDao().transmission(NEXT_OPERATION, revert.operationId))
        val owner = store(); endAndCold(owner, decision(owner))
        assertEquals(2, count("count_command_intents")); assertEquals(2, count("count_command_results"))
        assertEquals(2, count("next_request_origins")); assertEquals(2, count("next_acceptances"))
    }
}
