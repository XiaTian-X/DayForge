package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.HistoryBlockDiagnostics
import com.dayforge.data.local.*
import com.dayforge.util.DateTimeUtils
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic faults at the real native preparation→read boundary, no production hook. */
@RunWith(AndroidJUnit4::class)
class CountDisplayHandoffTest : NextCoreRequestFixture() {
    private val spy = CountDisplayNativeSpy()
    init { storage.isolatedBuilder = { spy.build(it) } }
    private fun kernel() = CountTrustKernel(db, tokens, sessions)
    private fun assertions() = CountConsumerReadFixtureAssertions(db, tokens, sessions)
    private suspend fun ready() {
        register()
        CountHistoryReader(db, tokens, sessions).readSummary(habit, DateTimeUtils.today())
        db.withTransaction { kernel().withOpen(requireNotNull(tokens.localCoreWriteAccess())) { assertNotNull(it) } }
    }
    private suspend fun schema() = db.withTransaction {
        db.openHelper.writableDatabase.query("SELECT type,name,tbl_name,sql FROM main.sqlite_master ORDER BY type,name").use { raw ->
            buildList { while (raw.moveToNext()) add((0..3).map { if (raw.isNull(it)) null else raw.getString(it) }) }
        }
    }

    @Test fun realMainDdlAtHandoffIsRejectedByOriginalAndCandidateWithExactRollbackAndRetry() = runBlocking<Unit> {
        ready(); val image = assertions().capture(); val originalSchema = schema()
        spy.armHandoff(CountDisplayNativeSpy.HandoffFault.MAIN_INDEX)
        val old = rejected { sessions.exclusive { db.withTransaction {
            val access = requireNotNull(tokens.localCoreWriteAccess())
            kernel().prepareInTransaction(access)
            NextEvidenceReadSession.read(db) { kernel().summary(habit, DateTimeUtils.today(), access) }
        } } }
        // Room20's mandatory schema bridge rejects this native DDL before COUNT seal opening.
        assertTrue(spy.handoffTouched()); assertTrue(old is CountCommandSchemaBridge.Invalid)
        assertEquals("COMMAND_OLD_COUNT_SCHEMA_INVALID", old.message)
        assertions().assertUnchanged(image); assertEquals(originalSchema, schema())
        spy.armHandoff(CountDisplayNativeSpy.HandoffFault.MAIN_INDEX)
        val candidate = rejected { CountHistoryReader(db, tokens, sessions).readSummary(habit, DateTimeUtils.today()) }
        assertTrue(spy.handoffTouched()); assertEquals("COUNT_DISPLAY_HANDOFF_CHANGED", candidate.message)
        assertions().assertUnchanged(image); assertEquals(originalSchema, schema())
        assertNotNull(CountHistoryReader(db, tokens, sessions).readSummary(habit, DateTimeUtils.today()))
        assertions().assertUnchanged(image)
    }

    @Test fun actualNoopAtHandoffCannotBecomeReadBaseline() = runBlocking<Unit> {
        ready(); val image = assertions().capture()
        spy.armHandoff(CountDisplayNativeSpy.HandoffFault.NOOP)
        assertEquals("COUNT_DISPLAY_HANDOFF_CHANGED", rejected {
            CountHistoryReader(db, tokens, sessions).readSummary(habit, DateTimeUtils.today())
        }.message)
        assertTrue(spy.handoffTouched()); assertions().assertUnchanged(image)
        // This continuity rule forbids a write in the private handoff; it does not claim the
        // legacy original full-reader fallback rejected this otherwise benign native noop.
        assertNotNull(CountHistoryReader(db, tokens, sessions).readSummary(habit, DateTimeUtils.today()))
    }

    @Test fun cancellationAtActualHandoffLeavesNoPublishedValueAndPermitsCleanRetry() = runBlocking<Unit> {
        ready(); val image = assertions().capture(); var returned = false
        val child = launch {
            spy.armHandoff(CountDisplayNativeSpy.HandoffFault.CANCEL, requireNotNull(currentCoroutineContext()[Job]))
            CountHistoryReader(db, tokens, sessions).readSummary(habit, DateTimeUtils.today())
            returned = true
        }
        child.join(); assertTrue(child.isCancelled); assertTrue(spy.handoffTouched()); assertFalse(returned)
        assertions().assertUnchanged(image)
        assertNotNull(CountHistoryReader(db, tokens, sessions).readSummary(habit, DateTimeUtils.today()))
        assertions().assertUnchanged(image)
    }

    @Test fun newEvidenceJobCancellationIsObservedInsideActualCodecBeforeACompleteNode() = runBlocking<Unit> {
        ready(); val image = assertions().capture()
        var began = false; var successfulDecode: Boolean? = null; var cancelled = false
        try { sessions.exclusive { db.withTransaction {
            kernel().withPreparedDisplayRead(requireNotNull(tokens.localCoreWriteAccess()), true) { read ->
                val job = requireNotNull(currentCoroutineContext()[Job])
                val old = HistoryBlockDiagnostics.current.get()
                HistoryBlockDiagnostics.current.set(object : HistoryBlockDiagnostics.Observer {
                    override fun begin(stage: HistoryBlockDiagnostics.Stage): HistoryBlockDiagnostics.Observation {
                        if (stage == HistoryBlockDiagnostics.Stage.NODE_DECODE && !began) {
                            began = true; job.cancel()
                            return HistoryBlockDiagnostics.Observation { successfulDecode = it }
                        }
                        return HistoryBlockDiagnostics.Observation { }
                    }
                })
                try { requireNotNull(read).summary(db, tokens, habit, DateTimeUtils.today()) }
                finally { HistoryBlockDiagnostics.current.set(old) }
            }
        } } } catch (_: CancellationException) { cancelled = true }
        assertTrue(began); assertTrue(cancelled); assertEquals(false, successfulDecode)
        currentCoroutineContext().ensureActive()
        assertions().assertUnchanged(image)
        assertNotNull(CountHistoryReader(db, tokens, sessions).readSummary(habit, DateTimeUtils.today()))
    }

    @Test fun optionalFalseDoesNotCreateRootAndPrepareCannotBeNestedInExistingReadPhase() = runBlocking<Unit> {
        register(); val image = assertions().capture()
        sessions.exclusive { db.withTransaction {
            val access = requireNotNull(tokens.localCoreWriteAccess())
            spy.begin()
            try { kernel().withPreparedDisplayRead(access, false) { assertNull(it) } }
            finally { assertEquals(0, spy.end()) }
            NextEvidenceReadSession.read(db) {
                assertEquals("EVIDENCE_READ_WRITE_FORBIDDEN", rejected {
                    kernel().withPreparedDisplayRead(access, true) { fail("Nested prepare must not run") }
                }.message)
            }
        } }
        assertions().assertUnchanged(image)
    }
}
