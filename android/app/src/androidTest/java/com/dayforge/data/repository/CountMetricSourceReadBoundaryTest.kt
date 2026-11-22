package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.HistoryBlockDiagnostics
import com.dayforge.data.local.TokenManager
import java.time.LocalDate
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountMetricSourceReadBoundaryTest : CountMetricSourceReadFixture() {
    private class DeliberateRollback : RuntimeException()

    @Test fun escapedHandleCannotCrossScopeCaptureOrActualTransactionEnd() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val writer = producer(); val scope = writer.captureRounds()
        lateinit var held: CountTrustKernel.MetricSourceRead
        writer.writeRoundsWithMetricSource(scope) {
            CountTrustKernel.withMetricSourceRead(db) { read ->
                held = requireNotNull(read)
                assertEquals(scope.metadataJson, held.rounds(db, tokens, scope.access).metadataJson)
                widgets().requireCompletionInTransaction(inputs.claim, metricSourceRead = held)
            }
            assertEquals("METRIC_SOURCE_READ_EXPIRED", rejected { held.rounds(db, tokens, scope.access) }.message)
            CountTrustKernel.withMetricSourceRead(db) { next ->
                assertNotNull(next); assertNotSame(held, next)
                assertEquals("METRIC_SOURCE_READ_EXPIRED", rejected { held.rounds(db, tokens, scope.access) }.message)
            }
        }
        assertEquals("METRIC_SOURCE_READ_EXPIRED", rejected { held.rounds(db, tokens, scope.access) }.message)
        writer.writeRoundsWithMetricSource(scope) {
            CountTrustKernel.withMetricSourceRead(db) { next ->
                assertNotNull(next)
                assertEquals("METRIC_SOURCE_READ_EXPIRED", rejected { held.rounds(db, tokens, scope.access) }.message)
                widgets().requireCompletionInTransaction(inputs.claim, metricSourceRead = next)
            }
        }
        assertMetricScopeRolledBack(inputs)
    }

    @Test fun childJobAndDifferentTokenOwnerCannotBorrowIssuedProof() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val writer = producer(); val scope = writer.captureRounds()
        writer.writeRoundsWithMetricSource(scope) {
            CountTrustKernel.withMetricSourceRead(db) { optional ->
                val handle = requireNotNull(optional); val parent = requireNotNull(currentCoroutineContext()[Job])
                val foreign = TokenManager(dataStore, tokenCipher())
                assertEquals("METRIC_SOURCE_READ_EXPIRED", rejected { handle.rounds(db, foreign, scope.access) }.message)
                supervisorScope {
                    val task = async(start = CoroutineStart.UNDISPATCHED) {
                        assertTrue(db.inTransaction()); assertNotSame(parent, currentCoroutineContext()[Job])
                        rejected { handle.rounds(db, tokens, scope.access) }
                    }
                    assertEquals("METRIC_SOURCE_READ_EXPIRED", task.await().message)
                }
                widgets().requireCompletionInTransaction(inputs.claim, metricSourceRead = handle)
            }
        }
        assertMetricScopeRolledBack(inputs)
    }

    @Test fun realMainDdlBeforeInitialProbeIsRejectedByBothPathsAndFullyRolledBack() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val writer = producer(); val scope = writer.captureRounds()
        val image = nativeImage().capture(); val schemaBefore = schema()
        val original = rejected { writer.writeRounds(scope) {
            sourceSpy.armMetricSourceBoundary(SourceWindowNativeSpy.MetricSourceFault.MAIN_INDEX)
            // Same actual before-read fault; v20 mandatory primary schema validation now rejects first.
            NextEvidenceReadSession.read(db) { widgets().requireCompletionInTransaction(inputs.claim) }
        } }
        assertTrue(sourceSpy.metricSourceBoundaryTouched())
        assertTrue(original is CountCommandSchemaBridge.Invalid)
        assertEquals("COMMAND_OLD_COUNT_SCHEMA_INVALID", original.message)
        val primary = requireNotNull(original.cause)
        assertTrue(primary is IllegalArgumentException)
        assertEquals("COMMAND_PRIMARY_BINDING_CHANGED", primary.message)
        assertTrue(primary.stackTrace.any { it.className.startsWith("com.dayforge.data.repository.CountCommandSeal") })
        assertTrue(primary.stackTrace.any { it.className == "com.dayforge.data.repository.CountCommandSchemaBridge" })
        nativeImage().assertUnchanged(image); assertEquals(schemaBefore, schema())
        val candidate = rejected { writer.writeRoundsWithMetricSource(scope) {
            sourceSpy.armMetricSourceBoundary(SourceWindowNativeSpy.MetricSourceFault.MAIN_INDEX)
            CountTrustKernel.withMetricSourceRead(db) { fail("Changed pre-auth input cannot issue a proof") }
        } }
        assertTrue(sourceSpy.metricSourceBoundaryTouched()); assertEquals("METRIC_SOURCE_READ_CHANGED", candidate.message)
        nativeImage().assertUnchanged(image); assertEquals(schemaBefore, schema())
        assertEquals(1, readSource(inputs, reuse = true).schemas)
        assertMetricScopeRolledBack(inputs)
    }

    @Test fun actualReadPhaseNoopCannotBeHiddenByUnchangedBusinessValues() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val writer = producer(); val scope = writer.captureRounds()
        var changed = false
        val failure = rejected { writer.writeRoundsWithMetricSource(scope) {
            CountTrustKernel.withMetricSourceRead(db) { read ->
                widgets().requireCompletionInTransaction(inputs.claim, metricSourceRead = requireNotNull(read))
                db.openHelper.writableDatabase.execSQL("UPDATE metrics SET updatedAt=updatedAt WHERE id=?",
                    arrayOf(inputs.metrics.keys.first()))
                changed = true
            }
        } }
        assertTrue(changed); assertEquals("EVIDENCE_READ_SESSION_WROTE_DATA", failure.message)
        assertMetricScopeRolledBack(inputs)
    }

    @Test fun newReadJobCancellationInterruptsActualCodecAndNoResultSurvivesEnd() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val writer = producer(); val scope = writer.captureRounds()
        var began = false; var finished: Boolean? = null; var returned = false; var cancelled = false
        try { writer.writeRoundsWithMetricSource(scope) {
            CountTrustKernel.withMetricSourceRead(db) { handle ->
                val actual = requireNotNull(handle); val job = requireNotNull(currentCoroutineContext()[Job])
                val observer = HistoryBlockDiagnostics.current.get()
                HistoryBlockDiagnostics.current.set(object : HistoryBlockDiagnostics.Observer {
                    override fun begin(stage: HistoryBlockDiagnostics.Stage): HistoryBlockDiagnostics.Observation {
                        if (stage == HistoryBlockDiagnostics.Stage.NODE_DECODE && !began) {
                            began = true; job.cancel(CancellationException("METRIC_SOURCE_CODEC_CANCEL"))
                            return HistoryBlockDiagnostics.Observation { finished = it }
                        }
                        return HistoryBlockDiagnostics.Observation { }
                    }
                })
                try { actual.summary(db, tokens, requireNotNull(tokens.localCoreWriteAccess()),
                    inputs.currentHabit, LocalDate.parse(inputs.claim.date)) }
                finally { HistoryBlockDiagnostics.current.set(observer) }
            }
        }; returned = true } catch (_: CancellationException) { cancelled = true }
        currentCoroutineContext().ensureActive()
        assertTrue(began); assertTrue(cancelled); assertEquals(false, finished); assertFalse(returned)
        assertMetricScopeRolledBack(inputs)
        assertEquals(1, readSource(inputs, reuse = true).schemas)
        assertMetricScopeRolledBack(inputs)
    }

    @Test fun rollbackLeavesNoTransferableSourceProofAndAllowsFullMetricRetry() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val writer = producer(); val scope = writer.captureRounds()
        lateinit var held: CountTrustKernel.MetricSourceRead
        var inserted = false
        try { writer.writeRoundsWithMetricSource<Unit>(scope) {
            CountTrustKernel.withMetricSourceRead(db) { read ->
                held = requireNotNull(read); widgets().requireCompletionInTransaction(inputs.claim, metricSourceRead = read)
            }
            insertKnown(inputs); inserted = true
            throw DeliberateRollback()
        }; fail("Real inserted rows must roll back") } catch (_: DeliberateRollback) { }
        assertTrue(inserted); assertMetricScopeRolledBack(inputs)
        assertEquals("METRIC_SOURCE_READ_EXPIRED", rejected { held.rounds(db, tokens, scope.access) }.message)
        val ids = writer.writeRoundsWithMetricSource(scope) {
            CountTrustKernel.withMetricSourceRead(db) { read ->
                assertNotNull(read); widgets().requireCompletionInTransaction(inputs.claim, metricSourceRead = read)
            }
            insertKnown(inputs)
        }
        assertMetricScopeCommitted(inputs, ids)
    }
    @Test fun finalFreshAuthAfterActualSealingStillRollsBackWholeRealMetricGroup() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs()
        val metrics = inputs.metrics.values.toList()
        val authority = requireNotNull(editor.metrics(metrics.map { it.id }).first().authority)
        val drafts = metrics.map { MetricValueDraft(it.id, 9.5, "final fresh gate") }
        val recordedAt = System.currentTimeMillis()
        resetCredentialCounts(); failAccessAt.set(16)
        val failure: Throwable
        try { failure = rejected { creatingMetrics().recordValues(drafts, recordedAt, authority,
            expectedMetrics = metrics, promptHabit = inputs.currentHabit, promptFact = inputs.claim,
            promptCompletionSource = true) } }
        finally { failAccessAt.set(0) }
        assertCredentialCounts(access = 16, refresh = 0)
        assertTrue(failure is NextRequestException)
        assertEquals(NextRequestException.Reason.STALE_ACCESS, (failure as NextRequestException).reason)
        assertMetricScopeRolledBack(inputs)
        val earliest = System.currentTimeMillis(); val zone = java.time.ZoneId.systemDefault().id
        val ids = creatingMetrics().recordValues(drafts, recordedAt, authority,
            expectedMetrics = metrics, promptHabit = inputs.currentHabit, promptFact = inputs.claim,
            promptCompletionSource = true)
        assertActualRows(inputs, drafts, ids, recordedAt, earliest, System.currentTimeMillis(), zone)
    }

}
