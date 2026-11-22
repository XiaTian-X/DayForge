package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.entity.HabitEntity
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Faults are injected by a fixed native spy below the actual controlled Room connection.
 * No alternate producer/expected journal is fabricated. Every negative branch keeps the
 * complete main image (including nodes, roots, sequences and control) and fresh retry oracle.
 */
@RunWith(AndroidJUnit4::class)
class CountAddResourceBoundaryTest : CountTrustFormalProfileFixture() {
    private val spy = SourceWindowNativeSpy()
    override fun sourceWindowNativeSpy() = spy
    private val sql get() = db.openHelper.writableDatabase

    private suspend fun add(habit: HabitEntity): CountAddExecutor.Result = editor.addCountPrepared(
        requireNotNull(db.habitDao().getHabitById(habit.id)), requireNotNull(editor.habit(habit.id).authority),
        null, Instant.now(), ZoneId.systemDefault())

    private suspend fun rejectSelectedCount(action: suspend () -> Unit): Exception {
        try { action() } catch (error: Exception) {
            if (error is CancellationException) throw error
            return error
        }
        throw AssertionError("The actual selected transaction must reject, not fall back or commit")
    }

    private fun bounded(sample: SourceWindowNativeSpy.Snapshot) {
        assertFalse(sample.overflow)
        assertEquals(0, sample.count(SourceWindowNativeSpy.Kind.JOURNAL_HEADERS_UNBOUNDED))
        assertEquals(0, sample.count(SourceWindowNativeSpy.Kind.JOURNAL_CELLS_FULL))
    }

    private suspend fun freshRetry(habit: HabitEntity) {
        // Journal-integrity rejection can retire that owner. Reopening a clean rolled-back
        // file is the existing recovery boundary; it must not reset a retired owner in place.
        storage.reopen()
        editor = NextObjectEditor(db, tokens, sessions, icons)
        val retry = add(habit)
        assertTrue(retry is CountAddExecutor.Result.Committed && retry.id > 0)
        assertEquals(1, db.completionDao().getByHabitOnce(habit.id).size)
        assertEquals(1L, CountHistoryReader(db, tokens, sessions).readSummary(requireNotNull(db.habitDao().getHabitById(habit.id))).totalQuantity)
    }

    private suspend fun sourceRefusal(field: SourceWindowNativeSpy.LateSourceField) {
        val habit = openCountAddPlanInputs().first()
        val images = CountConsumerReadFixtureAssertions(db, tokens, sessions); val before = images.capture()
        spy.armAfterNextRateFault(field)
        val failure = rejectSelectedCount { add(habit) }
        val sample = spy.finishRateFault()
        assertTrue("The real rate UPDATE and real source UPDATE must be reached", sample.injected)
        assertTrue(failure is NextRequestException)
        assertEquals(NextRequestException.Reason.INVALID_LOCAL_STATE, (failure as NextRequestException).reason)
        bounded(sample.queries)
        assertTrue(sample.queries.count(SourceWindowNativeSpy.Kind.SOURCE_IDS_BOUNDED) > 0)
        assertTrue(sample.queries.count(SourceWindowNativeSpy.Kind.SOURCE_ROW_SIZE_PREFLIGHT) > 0)
        assertEquals(0, sample.queries.count(SourceWindowNativeSpy.Kind.SOURCE_ROW_FULL))
        images.assertUnchanged(before); freshRetry(habit)
    }

    @Test fun oversizedActualPayloadRetainsOriginalScalarRefusalBeforeFullRead() = runBlocking<Unit> {
        sourceRefusal(SourceWindowNativeSpy.LateSourceField.PAYLOAD_JSON)
    }
    @Test fun oversizedActualErrorRetainsOriginalScalarRefusalBeforeFullRead() = runBlocking<Unit> {
        sourceRefusal(SourceWindowNativeSpy.LateSourceField.LAST_ERROR)
    }

    private suspend fun journalRefusal(fault: SourceWindowNativeSpy.CountAddFault, prefix: String) {
        val habit = openCountAddPlanInputs().first()
        val images = CountConsumerReadFixtureAssertions(db, tokens, sessions); val before = images.capture()
        spy.armCountAddFault(fault)
        val failure = rejectSelectedCount { add(habit) }
        val sample = spy.end()
        assertTrue("The actual origin INSERT and selected native fault must be reached", spy.countAddFaultTouched())
        bounded(sample)
        assertTrue("Expected closed-journal refusal, got ${failure.javaClass.simpleName}:${failure.message}",
            failure.message.orEmpty().startsWith(prefix))
        images.assertUnchanged(before); freshRetry(habit)
    }

    @Test fun actualInvalidHeaderIsCorruptionBeforePayloadRead() = runBlocking<Unit> {
        journalRefusal(SourceWindowNativeSpy.CountAddFault.HEADER_OPERATION, "COUNT_TRUST_JOURNAL_")
    }
    @Test fun actualSourcePaddingCannotBeAdopted() = runBlocking<Unit> {
        // Separate actual test transactions/owners; no synthetic event rows or omitted failure.
        journalRefusal(SourceWindowNativeSpy.CountAddFault.SOURCE_PADDING, "COUNT_TRUST_JOURNAL_")
    }
    @Test fun actualWrongSourceSideCannotBeAdopted() = runBlocking<Unit> {
        journalRefusal(SourceWindowNativeSpy.CountAddFault.SOURCE_SIDE, "COUNT_TRUST_JOURNAL_")
    }
    @Test fun actualWrongNativeCellTypeIsNotCoercedToExpected() = runBlocking<Unit> {
        journalRefusal(SourceWindowNativeSpy.CountAddFault.SOURCE_CELL_TYPE, "COUNT_ADD_UNEXPECTED_EFFECT")
    }
    @Test fun missingActualSourceCellsRejectAndFullyRollBack() = runBlocking<Unit> {
        journalRefusal(SourceWindowNativeSpy.CountAddFault.SOURCE_CELLS_MISSING, "COUNT_TRUST_JOURNAL_")
    }
    @Test fun hugeActualJournalValueNeverEntersFullCursorWindow() = runBlocking<Unit> {
        journalRefusal(SourceWindowNativeSpy.CountAddFault.HUGE_SOURCE_CELL, "COUNT_ADD_UNEXPECTED_EFFECT")
    }
    @Test fun sequenceRegressionAfterMetadataCannotBecomeApplicabilityMiss() = runBlocking<Unit> {
        journalRefusal(SourceWindowNativeSpy.CountAddFault.SEQUENCE_AFTER_METADATA, "COUNT_TRUST_JOURNAL_CHANGED")
    }
    @Test fun actualExtraNoopIsNeverHiddenByFixedFourOrSixEffectBudget() = runBlocking<Unit> {
        journalRefusal(SourceWindowNativeSpy.CountAddFault.EXTRA_HABIT_NOOP, "COUNT_ADD_UNEXPECTED_EFFECT")
    }

    @Test fun lostActualEndOutcomeCannotPublishCommittedOrRepeatTheFact() = runBlocking<Unit> {
        val habit = openCountAddPlanInputs().first()
        spy.armCountAddFault(SourceWindowNativeSpy.CountAddFault.END_AFTER_COMMIT)
        var returned: CountAddExecutor.Result? = null
        val failure = rejectSelectedCount { returned = add(habit) }
        spy.end()
        assertTrue(spy.countAddFaultTouched())
        assertEquals("COUNT_ADD_TEST_END_OUTCOME_LOST", failure.message)
        assertNull(returned)
        assertTrue(requireNotNull(CountTrustRuntime.forDatabase(db)).isRetired)
        storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons)
        assertEquals("The real committed operation must exist exactly once, without automatic replay", 1,
            db.completionDao().getByHabitOnce(habit.id).size)
        assertEquals(1, db.syncOutboxDao().getAll().count { it.recordType == "completion" })
        assertEquals(1L, CountHistoryReader(db, tokens, sessions).readSummary(requireNotNull(db.habitDao().getHabitById(habit.id))).totalQuantity)
    }

    @Test fun changingDefaultZoneAfterRateKeepsCommitButDoesNotPromoteStaleRate() = runBlocking<Unit> {
        val oldZone = TimeZone.getDefault()
        try {
            val habit = openCountAddPlanInputs().first()
            val oldRoot = db.withTransaction { requireNotNull(CountTrustSeal.read(sql)) }
            spy.armCountAddFault(SourceWindowNativeSpy.CountAddFault.DEFAULT_ZONE_AFTER_RATE)
            val result = add(habit); spy.end()
            assertTrue(spy.countAddFaultTouched())
            assertTrue(result is CountAddExecutor.Result.Committed && result.id > 0)
            db.withTransaction {
                assertEquals("Original optional promotion semantics must retain the old root", oldRoot, CountTrustSeal.read(sql))
                sql.query("SELECT dirty FROM count_trust_control WHERE id=1").use {
                    assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0)); assertFalse(it.moveToNext())
                }
            }
            assertEquals(1, db.completionDao().getByHabitOnce(habit.id).size)
            assertEquals(1, db.syncOutboxDao().getAll().count { it.recordType == "completion" })
        } finally { TimeZone.setDefault(oldZone) }
    }
}
