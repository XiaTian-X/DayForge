package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual T0/T1 and native late mutations. The same assertions must hold with and without
 * prefix reuse; committed business is never accepted in exchange for fewer proof reads. */
@RunWith(AndroidJUnit4::class)
class CountAddVerifiedPrefixTest : CountTrustFormalProfileFixture() {
    private val spy = SourceWindowNativeSpy()
    override fun sourceWindowNativeSpy() = spy

    @Test(timeout = 150_000) fun normalCommandCommitsOnceAndOriginalAckReplaysWithoutAnotherFact() = runBlocking<Unit> {
        val habit = openCountAddPlanInputs().first()
        val authority = requireNotNull(editor.habit(habit.id).authority)
        val now = Instant.now(); val zone = ZoneId.systemDefault()
        val command = editor.beginCountAdd(habit, authority, now, zone, 1)
        val before = spy.countAddClosedEffectBarriers()
        val id = editor.mutateCountAddCommand(habit, null, command, now, zone, allowPrepared = true) {
            error("PREFIX_TEST_UNEXPECTED_ORIGINAL_FALLBACK")
        }
        assertTrue(id > 0)
        assertTrue(command.committedReceipt().recorded)
        assertEquals(1, db.completionDao().getByHabitOnce(habit.id).size)
        val queue = db.syncOutboxDao().getAll().single { it.recordType == "completion" }
        val requests = countAddPlanSender()
        val delivery = requireNotNull(requests.sendOperation(access(), queue.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED, requests.acceptOperation(delivery))
        assertEquals(NextOperationAcceptance.REPLAYED, requests.acceptOperation(delivery))
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        val current = requireNotNull(db.habitDao().getHabitById(habit.id))
        assertEquals(1, RecurringCompletionEvidenceReader(db, tokens, sessions).read(current).completions.size)
        val store = CountCommandStore(db, tokens, sessions)
        assertEquals(command.committedReceipt().committed.outcome.text(),
            store.resolve(command.committedReceipt().committed.reference).historical.outcome?.text())
        android.util.Log.i("CountPrefixGate", "normal committed=true full_business_barriers=${spy.countAddClosedEffectBarriers() - before}")
    }

    @Test(timeout = 150_000) fun sameValueBusinessWriteAfterFirstFullVerificationRollsBackT1() = runBlocking<Unit> {
        rejected(SourceWindowNativeSpy.CountAddVerifiedPrefixFault.BUSINESS_NOOP)
    }

    @Test(timeout = 150_000) fun alteredJournalHeaderAfterFirstFullVerificationRollsBackT1() = runBlocking<Unit> {
        rejected(SourceWindowNativeSpy.CountAddVerifiedPrefixFault.JOURNAL_HEADER)
    }

    @Test(timeout = 150_000) fun alteredJournalCellAfterFirstFullVerificationRollsBackT1() = runBlocking<Unit> {
        rejected(SourceWindowNativeSpy.CountAddVerifiedPrefixFault.JOURNAL_CELL)
    }

    private suspend fun rejected(fault: SourceWindowNativeSpy.CountAddVerifiedPrefixFault) {
        val habit = openCountAddPlanInputs().first()
        val authority = requireNotNull(editor.habit(habit.id).authority)
        val now = Instant.now(); val zone = ZoneId.systemDefault()
        val command = editor.beginCountAdd(habit, authority, now, zone, 1)
        val images = CountConsumerReadFixtureAssertions(db, tokens, sessions)
        val before = images.capture() // T0 is durable; only T1 must roll back.
        val barriers = spy.countAddClosedEffectBarriers()
        spy.armCountAddVerifiedPrefixFault(fault)
        var saved: Long? = null
        val failure = try {
            saved = editor.mutateCountAddCommand(habit, null, command, now, zone, allowPrepared = true) {
                error("PREFIX_TEST_UNEXPECTED_ORIGINAL_FALLBACK")
            }
            null
        } catch (error: Exception) { error }
        val after = images.capture()
        android.util.Log.i("CountPrefixGate", "fault=$fault touched=${spy.countAddVerifiedPrefixTouched()} saved=${saved != null} " +
            "full_business_barriers=${spy.countAddClosedEffectBarriers() - barriers} unchanged=${before == after} " +
            "failure=${failure?.javaClass?.simpleName}:${failure?.message}")
        assertTrue("Must reach the native cursor close after actual first full proof", spy.countAddVerifiedPrefixTouched())
        assertNotNull("Damaged prefix must not become a committed command", failure)
        assertNull("No successful fact ID may escape a rejected T1", saved)
        assertEquals("Keep every typed MAIN row, metadata and sqlite_sequence exactly as after T0", before, after)
        assertEquals(0, db.completionDao().getByHabitOnce(habit.id).size)
    }
}
