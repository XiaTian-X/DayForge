package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountTrustJournal
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.HabitDatabaseProvider
import java.time.ZoneId
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real formal COUNT -> two linked metrics through the original Repository and native gate.
 * Primitive rejection cases use actual SQLite changes, never supplied metricEffects or seeds.
 * Existing source-read/END tests remain separate complete regression classes.
 */
@RunWith(AndroidJUnit4::class)
class CountMetricChallengeReuseTest : CountMetricSourceReadFixture() {
    private val challengeSpy = ChallengeReuseNativeSpy(sourceSpy)
    init { storage.isolatedBuilder = { HabitDatabaseProvider.build(it, nativeFactory = challengeSpy) } }
    private class Rollback : RuntimeException()

    private suspend fun rolledBack(block: suspend () -> Unit) {
        try { db.withTransaction<Unit> { block(); throw Rollback() } } catch (_: Rollback) { }
    }
    private fun assertAudits(sample: ChallengeReuseNativeSpy.Sample, count: Int) {
        assertEquals(setOf("next_challenge_state", "next_challenge_rounds", "next_challenge_births"), sample.audits.keys)
        sample.audits.forEach { (table, actual) -> assertEquals("Original complete raw audit of $table", count, actual) }
    }
    private suspend fun saveActual(inputs: MetricScopeInputs, label: String): List<Long> {
        val metrics = inputs.metrics.values.toList()
        val authority = requireNotNull(editor.metrics(metrics.map { it.id }).first().authority)
        val drafts = metrics.mapIndexed { i, row -> MetricValueDraft(row.id, 7.25 + i, "$label $i") }
        val at = System.currentTimeMillis(); val zone = ZoneId.systemDefault().id
        val earliest = System.currentTimeMillis()
        val ids = creatingMetrics().recordValues(drafts, at, authority, expectedMetrics = metrics,
            promptHabit = inputs.currentHabit, promptFact = inputs.claim, promptCompletionSource = true)
        assertActualRows(inputs, drafts, ids, at, earliest, System.currentTimeMillis(), zone)
        return ids
    }

    @Test(timeout = 150_000) fun realTwoMetricRepositorySaveReusesOneCompleteAuditAndKeepsAllFreshChecks() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs()
        val metrics = inputs.metrics.values.toList()
        val authority = requireNotNull(editor.metrics(metrics.map { it.id }).first().authority)
        val drafts = metrics.mapIndexed { i, row -> MetricValueDraft(row.id, 8.25 + i, "challenge reuse $i") }
        val at = System.currentTimeMillis(); val zone = ZoneId.systemDefault().id
        resetCredentialCounts(); challengeSpy.begin()
        val ids: List<Long>; val sample: ChallengeReuseNativeSpy.Sample
        val earliest = System.currentTimeMillis()
        try { ids = creatingMetrics().recordValues(drafts, at, authority, expectedMetrics = metrics,
            promptHabit = inputs.currentHabit, promptFact = inputs.claim, promptCompletionSource = true) }
        finally { sample = challengeSpy.end() }
        val latest = System.currentTimeMillis()
        assertAudits(sample, 1)
        assertEquals("Exactly one final challenge no-touch window is consumed", 1, sample.windows)
        assertCredentialCounts(access = 16, refresh = 0)
        assertActualRows(inputs, drafts, ids, at, earliest, latest, zone)
    }

    @Test(timeout = 150_000) fun ordinaryWriterWithoutPrivateSeedStillRunsBothCompleteChallengeAudits() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val writer = producer(); val scope = writer.captureRounds()
        challengeSpy.begin()
        val sample: ChallengeReuseNativeSpy.Sample
        try { writer.writeRounds(scope) { Unit } }
        finally { sample = challengeSpy.end() }
        assertAudits(sample, 2); assertEquals(0, sample.windows)
        assertMetricScopeRolledBack(inputs)
    }

    @Test(timeout = 150_000) fun allFourChallengeTablesInvalidateOnSameValueAndRestoredWrites() = runBlocking<Unit> {
        openMetricScopeInputs()
        val queued = db.syncOutboxDao().getAll().single { it.recordType == "completion" }
        val requests = countAddPlanSender()
        val delivered = requireNotNull(requests.sendOperation(access(), queued.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED, requests.acceptOperation(delivered))
        assertTrue("Accepted real COUNT must establish a persisted birth", db.nextChallengeDao().births().isNotEmpty())
        val before = nativeImage().capture()
        val columns = linkedMapOf("next_sync_state" to "batchHash", "next_challenge_state" to "metadataHash",
            "next_challenge_rounds" to "recordHash", "next_challenge_births" to "entityUuid")
        for ((table, column) in columns) for (restore in listOf(false, true)) {
            rolledBack {
                val sql = db.openHelper.writableDatabase
                val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
                val boundary = owner.maintenance(db) { owner.captureChangeBoundary() }
                assertEquals(0L, owner.maintenance(db) { requireNotNull(owner.unchangedChallengeWindow(boundary)).totalChanges })
                val row = sql.query("SELECT rowid,$column FROM $table ORDER BY rowid LIMIT 1").use {
                    assertTrue("Formal fixture must contain $table", it.moveToFirst())
                    it.getLong(0) to it.getString(1)
                }
                if (restore) {
                    sql.execSQL("UPDATE $table SET $column=? WHERE rowid=?", arrayOf<Any>(row.second + "423", row.first))
                    sql.execSQL("UPDATE $table SET $column=? WHERE rowid=?", arrayOf<Any>(row.second, row.first))
                } else sql.execSQL("UPDATE $table SET $column=$column WHERE rowid=?", arrayOf(row.first))
                assertNull("$table restore=$restore is a real journal change even when contents match",
                    owner.maintenance(db) { owner.unchangedChallengeWindow(boundary) })
                val changes = owner.maintenance(db) { owner.pendingChanges(boundary) }
                assertEquals(if (restore) 2 else 1, changes.count { it.table == table })
            }
            nativeImage().assertUnchanged(before)
        }
    }

    @Test(timeout = 150_000) fun malformedAndMissingRealJournalHeadersThrowAndRollback() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val before = nativeImage().capture()
        for (fault in listOf(SourceWindowNativeSpy.Fault.HEADER_OPERATION, SourceWindowNativeSpy.Fault.SEQUENCE_GAP)) {
            val owner = requireNotNull(CountTrustRuntime.forDatabase(db)); var returned = false
            val failure = rejected { db.withTransaction {
                val boundary = owner.maintenance(db) { owner.captureChangeBoundary() }
                // Real DAO insert/outbox trigger creates the evidence that is then damaged.
                db.metricLogDao().insertAll(inputs.logs)
                sourceSpy.damageLatestSourceEvidence(fault)
                owner.maintenance(db) { owner.unchangedChallengeWindow(boundary) }
                returned = true
            } }
            assertFalse(returned); assertTrue(owner.isRetired)
            assertEquals(if (fault == SourceWindowNativeSpy.Fault.SEQUENCE_GAP) "COUNT_TRUST_JOURNAL_GAP" else "COUNT_TRUST_JOURNAL_INVALID", failure.message)
            nativeImage().assertUnchanged(before)
            storage.reopen()
        }
    }

    @Test(timeout = 150_000) fun lateActualChallengeWriteAfterNoTouchReadCannotAdoptTheOldProof() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val before = nativeImage().capture()
        challengeSpy.arm(ChallengeReuseNativeSpy.LateFault.CHALLENGE_NOOP)
        val failure = rejected { saveActual(inputs, "late challenge") }
        assertTrue("The real final no-touch query must have reached native close", challengeSpy.wasTouched())
        assertEquals("COUNT_TRUST_JOURNAL_CHANGED", failure.message)
        nativeImage().assertUnchanged(before)
    }

    @Test(timeout = 150_000) fun lateWriteAfterWindowAndControlMaterializationFailsTheFinalHandoff() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val before = nativeImage().capture()
        challengeSpy.arm(ChallengeReuseNativeSpy.LateFault.HANDOFF_CHALLENGE_NOOP)
        val failure = rejected { saveActual(inputs, "handoff challenge") }
        assertTrue("Both the final no-touch window and original control read must finish", challengeSpy.wasTouched())
        assertEquals("CHALLENGE_FINAL_HANDOFF_CHANGED", failure.message)
        nativeImage().assertUnchanged(before)
    }

    @Test(timeout = 150_000) fun missingGenerationOrChangedStorageAfterWindowCannotPublishOrCommit() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val before = nativeImage().capture()
        val faults = listOf(ChallengeReuseNativeSpy.LateFault.CONTROL_GENERATION to "COUNT_TRUST_JOURNAL_INCOMPLETE",
            ChallengeReuseNativeSpy.LateFault.CONTROL_STORAGE to "CHALLENGE_FINAL_BINDING_CHANGED")
        for ((fault, message) in faults) {
            challengeSpy.arm(fault)
            val failure = rejected { saveActual(inputs, "late control") }
            assertTrue(challengeSpy.wasTouched()); assertEquals(message, failure.message)
            nativeImage().assertUnchanged(before)
        }
    }

    @Test(timeout = 150_000) fun actualChallengeNoopUsesFullFallbackThenPreservesOriginalExtraEffectRefusal() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val before = nativeImage().capture()
        val writer = producer(); val scope = writer.captureRounds()
        val original = rejected { writer.writeRounds(scope) {
            val (ticket, ids) = registerAndInsertMetricScope(inputs)
            CountTrustKernel.bindMetricBatch(db, ticket, ids)
            db.openHelper.writableDatabase.execSQL("UPDATE next_challenge_state SET metadataHash=metadataHash WHERE id=1")
            ids
        } }
        assertEquals("METRIC_LOCAL_EXTRA_EFFECT", original.message)
        nativeImage().assertUnchanged(before)
        challengeSpy.begin()
        val candidate: Throwable; val sample: ChallengeReuseNativeSpy.Sample
        try { candidate = rejected { writer.writeRoundsWithMetricSource(scope) {
            CountTrustKernel.withMetricSourceRead(db) { handle ->
                widgets().requireCompletionInTransaction(inputs.claim, metricSourceRead = requireNotNull(handle))
            }
            val ids = insertKnown(inputs)
            db.openHelper.writableDatabase.execSQL("UPDATE next_challenge_state SET metadataHash=metadataHash WHERE id=1")
            ids
        } } } finally { sample = challengeSpy.end() }
        assertEquals(original.message, candidate.message)
        assertEquals(1, sample.windows); assertAudits(sample, 2)
        nativeImage().assertUnchanged(before)
    }

    @Test(timeout = 150_000) fun childJobCannotUseTheOriginalNativeBoundary() = runBlocking<Unit> {
        openMetricScopeInputs(); val before = nativeImage().capture()
        val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        val failure = rejected { db.withTransaction {
            val boundary = owner.maintenance(db) { owner.captureChangeBoundary() }
            supervisorScope {
                val child = async(start = CoroutineStart.UNDISPATCHED) {
                    assertTrue(db.inTransaction())
                    owner.maintenance(db) { owner.unchangedChallengeWindow(boundary) }
                }
                child.await()
            }
        } }
        assertEquals("COUNT_TRUST_BOUNDARY_EXPIRED", failure.message)
        assertTrue(owner.isRetired); nativeImage().assertUnchanged(before)
    }

    @Test(timeout = 150_000) fun commitRollbackAndReopenedConnectionNeverReuseAnEarlierBoundary() = runBlocking<Unit> {
        openMetricScopeInputs(); val before = nativeImage().capture()
        for (mode in listOf("commit", "rollback", "reopen")) {
            val previous = requireNotNull(CountTrustRuntime.forDatabase(db))
            lateinit var boundary: CountTrustJournal.Boundary
            suspend fun capture() { boundary = previous.maintenance(db) { previous.captureChangeBoundary() } }
            if (mode == "rollback") rolledBack { capture() } else db.withTransaction { capture() }
            assertFalse(db.inTransaction())
            if (mode == "reopen") storage.reopen()
            val current = requireNotNull(CountTrustRuntime.forDatabase(db))
            if (mode == "reopen") assertNotSame(previous, current)
            val failure = rejected { db.withTransaction { current.maintenance(db) { current.unchangedChallengeWindow(boundary) } } }
            assertEquals("COUNT_TRUST_BOUNDARY_EXPIRED", failure.message)
            assertTrue(current.isRetired); nativeImage().assertUnchanged(before)
            storage.reopen()
        }
    }

    @Test(timeout = 150_000) fun originalLastFreshAuthStillRejectsAfterReuseAndRollsBackBothMetrics() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs()
        val metrics = inputs.metrics.values.toList()
        val authority = requireNotNull(editor.metrics(metrics.map { it.id }).first().authority)
        val drafts = metrics.map { MetricValueDraft(it.id, 9.5, "final fresh unchanged") }
        resetCredentialCounts(); failAccessAt.set(16); challengeSpy.begin()
        val failure: Throwable; val sample: ChallengeReuseNativeSpy.Sample
        try { failure = rejected { creatingMetrics().recordValues(drafts, System.currentTimeMillis(), authority,
            expectedMetrics = metrics, promptHabit = inputs.currentHabit, promptFact = inputs.claim, promptCompletionSource = true) } }
        finally { failAccessAt.set(0); sample = challengeSpy.end() }
        assertAudits(sample, 1); assertEquals(1, sample.windows); assertCredentialCounts(access = 16, refresh = 0)
        assertTrue(failure is NextRequestException)
        assertEquals(NextRequestException.Reason.STALE_ACCESS, (failure as NextRequestException).reason)
        assertMetricScopeRolledBack(inputs)
        saveActual(inputs, "retry after final auth")
    }
}
