package com.dayforge.data.repository

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.SyncSchemaCallback
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlin.coroutines.ContinuationInterceptor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Recovery for the bounded synthetic append contract only. This does not implement the
 * production domain validators, cross-store FinalPermit owner, ACK retirement or UI readers.
 * Rebuilding from raw rows/journal is required even when the saved root looks unchanged.
 */
@RunWith(AndroidJUnit4::class)
class G0ColdRecoveryTest : G0GuardedFixture() {
    private val authorityOwner = G0AuthorityOwner()
    override fun isolatedDataStore(data: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) =
        authorityOwner.wrap(data)

    private suspend fun reference(): List<G0SchemaRow> = withContext(Dispatchers.IO) {
        val name = "g0-proof-reference-${UUID.randomUUID()}"
        val clean = Room.databaseBuilder(app, HabitDatabase::class.java, name)
            .addCallback(SyncSchemaCallback).build()
        try {
            G0BlockProofProbe(clean, tokens, sessions).initialize(128)
            g0Schema(clean.openHelper.writableDatabase)
        } finally { clean.close(); check(app.deleteDatabase(name)) }
    }

    private suspend fun recover(expected: List<G0SchemaRow>, afterColdCommit: () -> Unit = {},
        onBlock: (Long) -> Unit = {}): G0BlockProofProbe = withContext(Dispatchers.IO) {
        lateinit var probe: G0BlockProofProbe
        lateinit var nextGate: G0SqlGate
        val lease = authorityOwner.lease()
        var permit: AutoCloseable? = null
        val parent = currentCoroutineContext().minusKey(ContinuationInterceptor)
        val cold = G0ColdOpenFactory(expected, baseline = { sql ->
            runBlocking(parent) { probe.prepareColdRecovery(sql, onBlock = {
                authorityOwner.checkLease(lease)
                onBlock(it)
            }, beforeFinal = { permit = authorityOwner.finalPermit(lease) }) }
        }, onFailure = { nextGate.invalidateConnection() }, afterActualEnd = afterColdCommit)
        nextGate = G0SqlGate(cold)
        storage.isolatedBuilder = { context ->
            Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
                .addCallback(SyncSchemaCallback).openHelperFactory(nextGate.factory).build()
        }
        val next = storage.reopen()
        probe = G0BlockProofProbe(next, tokens, sessions, nextGate)
        try {
            try { next.openHelper.writableDatabase } finally { permit?.close() }
            assertTrue(cold.validated && cold.committed)
            authorityOwner.checkLease(lease)
            probe.acceptColdRecovery(cold.committed)
            probe
        } catch (failure: Throwable) {
            nextGate.invalidateConnection()
            throw failure
        }
    }

    private fun external(block: (SQLiteDatabase) -> Unit) {
        check(app.packageName == "com.dayforge.testbed")
        SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).use(block)
    }

    private fun persisted(facts: Long, intents: Long, effects: Long) = external { sql ->
        for ((table, expected) in mapOf("g0_facts" to facts, "g0_intents" to intents,
            "g0_results" to effects, "g0_outbox" to effects)) {
            sql.rawQuery("SELECT count(*) FROM $table", null).use {
                assertTrue(it.moveToFirst()); assertEquals(table, expected, it.getLong(0))
            }
        }
    }

    private suspend fun assertColdRejected(expected: List<G0SchemaRow>, message: String? = null) {
        val failure = rejected { recover(expected) }
        if (message != null) assertTrue(generateSequence(failure) { it.cause }.any { it.message == message })
        assertTrue("Rejection must not restore generic bootstrap authority",
            runCatching { db.openHelper.writableDatabase.execSQL("DROP TRIGGER g0_no_update") }.isFailure)
    }

    @Test fun coldRebuildReadsAllFactsAndRestoresNormalReadAppendAndIdempotentRetry() = runBlocking<Unit> {
        val old = guardedProbe(256)
        val expected = reference()
        old.append("committed")
        val cold = recover(expected)
        assertEquals(257L, cold.work.rows)
        assertEquals(257L, cold.fullAudit().count)
        assertEquals(257L, cold.append("committed").count)
        assertEquals(258L, cold.append("next").count)
        persisted(258, 2, 2)
        assertTrue(runCatching { old.read() }.isFailure)
    }

    @Test fun unknownBusinessCommitIsRebuiltAndOriginalCommandCannotDuplicateEffects() = runBlocking<Unit> {
        val old = guardedProbe()
        val expected = reference()
        assertEquals("G0_COMMIT_RESULT_LOST", rejected {
            old.append("unknown-business", beforeFinal = { commitFaults.failAfterNextEnd.set(true) })
        }.message)
        persisted(129, 1, 1)
        assertTrue(rejected { old.read() } is G0SqlGate.Denied)
        val cold = recover(expected)
        assertEquals(129L, cold.append("unknown-business").count)
        persisted(129, 1, 1)
        assertEquals(130L, cold.append("later").count)
    }

    @Test fun unknownIntentCommitResumesSamePendingCommandAfterColdProof() = runBlocking<Unit> {
        val old = guardedProbe()
        val expected = reference()
        commitFaults.failAfterNextEnd.set(true)
        assertEquals("G0_COMMIT_RESULT_LOST", rejected { old.append("unknown-intent") }.message)
        persisted(128, 1, 0)
        assertTrue(rejected { old.append("unknown-intent") } is G0SqlGate.Denied)
        val cold = recover(expected)
        assertEquals(129L, cold.append("unknown-intent").count)
        assertEquals(129L, cold.append("unknown-intent").count)
        persisted(129, 1, 1)
    }

    @Test fun unknownRollbackOutcomeIsProvenAbsentBeforeSameCommandResumes() = runBlocking<Unit> {
        val old = guardedProbe()
        val expected = reference()
        val failure = rejected {
            old.append("unknown-rollback", beforeFinal = {
                commitFaults.failAfterNextEnd.set(true)
                error("G0_FORCE_ROLLBACK_BEFORE_FINAL")
            })
        }
        assertEquals("G0_COMMIT_RESULT_LOST", failure.message)
        persisted(128, 1, 0)
        assertTrue(rejected { old.read() } is G0SqlGate.Denied)
        val cold = recover(expected)
        assertEquals(129L, cold.append("unknown-rollback").count)
        assertEquals(129L, cold.append("unknown-rollback").count)
        persisted(129, 1, 1)
    }

    @Test fun knownRollbackPendingIntentAndDefinitelyAbsentCommandCanBothResume() = runBlocking<Unit> {
        val old = guardedProbe()
        val expected = reference()
        assertEquals("G0_INJECTED_FAILURE", rejected { old.append("pending", failAfterFacts = true) }.message)
        persisted(128, 1, 0)
        val cold = recover(expected)
        assertEquals(129L, cold.append("pending").count)
        assertEquals(130L, cold.append("never-admitted").count)
        persisted(130, 2, 2)
    }

    @Test fun cancelledDeliveryAfterConfirmedCommitRetainsUsableCommittedProof() = runBlocking<Unit> {
        val probe = guardedProbe()
        val cancelled = CancellationException("G0_DELIVERY_CANCELLED")
        val failure = runCatching { probe.append("delivered-later", afterCommit = { throw cancelled }) }.exceptionOrNull()
        assertSame(cancelled, failure)
        assertEquals(129L, probe.read().count)
        assertEquals(129L, probe.append("delivered-later").count)
        persisted(129, 1, 1)
    }

    @Test fun unchangedSavedRootDoesNotAuthorizeCorruptedOldFactAfterColdOpen() = runBlocking<Unit> {
        guardedProbe(256)
        val expected = reference()
        db.close()
        external { sql ->
            val definition = sql.rawQuery("SELECT sql FROM sqlite_master WHERE name='g0_no_update'", null).use {
                check(it.moveToFirst()); it.getString(0)
            }
            sql.execSQL("DROP TRIGGER g0_no_update")
            sql.execSQL("UPDATE g0_facts SET owner=? WHERE seq=1", arrayOf(id(999)))
            sql.execSQL(definition)
        }
        assertColdRejected(expected)
        persisted(256, 0, 0)
    }

    @Test fun malformedResultTypeCannotBeHiddenByValidTree() = runBlocking<Unit> {
        val probe = guardedProbe()
        val expected = reference()
        probe.append("bad-result")
        db.close()
        external { sql ->
            val definition = sql.rawQuery("SELECT sql FROM sqlite_master WHERE name='g0_capture_results_UPDATE'", null).use {
                check(it.moveToFirst()); it.getString(0)
            }
            sql.execSQL("DROP TRIGGER g0_capture_results_UPDATE")
            sql.execSQL("UPDATE g0_results SET seq=129.5 WHERE command='bad-result'")
            sql.execSQL(definition)
        }
        assertColdRejected(expected, "G0_COLD_RESULT_TYPE")
    }

    @Test fun missingResultAndOutboxPairCannotBeReclassifiedAsSeedFacts() = runBlocking<Unit> {
        val probe = guardedProbe()
        val expected = reference()
        probe.append("missing-pair")
        db.close()
        external { sql ->
            // Restore exact trigger definitions: a schema-only check must not pass this loss.
            val definitions = sql.rawQuery("SELECT name,sql FROM sqlite_master WHERE name IN ('g0_capture_results_DELETE','g0_capture_outbox_DELETE') ORDER BY name", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
            }
            assertEquals(2, definitions.size)
            definitions.forEach { (name, _) -> sql.execSQL("DROP TRIGGER $name") }
            sql.execSQL("DELETE FROM g0_results")
            sql.execSQL("DELETE FROM g0_outbox")
            definitions.forEach { (_, definition) -> sql.execSQL(definition) }
        }
        assertColdRejected(expected, "G0_COLD_RESULT_MEMBERSHIP")
        persisted(129, 1, 0)
    }

    @Test fun newAccountCannotAdoptOldColdFacts() = runBlocking<Unit> {
        guardedProbe()
        val expected = reference()
        tokens.saveLoginSession("other", "refresh-other", "member", id(999), false)
        assertColdRejected(expected, "G0_COLD_IDENTITY")
    }

    @Test fun sameAccountWithDifferentReplicaCannotAdoptOldColdProof() = runBlocking<Unit> {
        guardedProbe()
        val expected = reference()
        tokens.saveServerIdentity(id(902), id(903))
        assertColdRejected(expected, "G0_COLD_IDENTITY")
    }

    @Test fun sameAccountReloginCanQueryOriginalCommittedCommandWithoutNewEffects() = runBlocking<Unit> {
        val old = guardedProbe()
        val expected = reference()
        old.append("before-relogin")
        tokens.saveLoginSession("new-synthetic", "new-refresh", "member", id(1), false)
        register()
        val cold = recover(expected)
        assertEquals(129L, cold.append("before-relogin").count)
        persisted(129, 1, 1)
    }

    @Test fun cancelledColdScanMustReleaseOwnerWithinBudgetAndRemainRecoverable() = runBlocking<Unit> {
        guardedProbe(256)
        val expected = reference()
        var rows = 0L
        var cancellationAt = 0L
        val failure = runCatching {
            coroutineScope {
                val request = currentCoroutineContext().job
                recover(expected) {
                    rows = it
                    cancellationAt = android.os.SystemClock.elapsedRealtimeNanos()
                    request.cancel(CancellationException("G0_COLD_CANCELLED"))
                }
            }
        }.exceptionOrNull()
        val cancellationNs = android.os.SystemClock.elapsedRealtimeNanos() - cancellationAt
        assertTrue(failure is CancellationException)
        assertEquals("Stop after the first bounded block", 128L, rows)
        persisted(256, 0, 0)
        assertTrue(runCatching { db.metricDao().getMetricById(metric.id) }.isFailure)
        val next = recover(expected)
        assertEquals(256L, next.read().count)
        assertEquals(257L, next.append("after-cancel").count)
        android.util.Log.i("G0ColdRecovery", "kind=cancel rows=$rows cancellation_ns=$cancellationNs")
        assertTrue("G0_COLD_CANCEL_BUDGET: ${cancellationNs / 1_000_000}ms exceeds 100ms", cancellationNs <= 100_000_000L)
    }

    @Test fun uncertainColdCommitCannotPublishCandidateAndNextIncarnationCanRecover() = runBlocking<Unit> {
        val old = guardedProbe()
        val expected = reference()
        old.append("before-cold-unknown")
        assertEquals("G0_COLD_COMMIT_RESULT_LOST", rejected {
            recover(expected, afterColdCommit = { error("G0_COLD_COMMIT_RESULT_LOST") })
        }.message)
        persisted(129, 1, 1)
        val next = recover(expected)
        assertEquals(129L, next.append("before-cold-unknown").count)
        persisted(129, 1, 1)
    }

    private suspend fun coldGrowth(size: Int) {
        guardedProbe(size)
        val expected = reference()
        val ready = mutableListOf<Long>()
        val readable = mutableListOf<Long>()
        // Every sample destroys the previous Room owner and its in-memory certificate.
        // This is cold proof reconstruction on a warm OS, not an app/process startup result.
        repeat(20) {
            val started = android.os.SystemClock.elapsedRealtimeNanos()
            val next = recover(expected)
            ready += android.os.SystemClock.elapsedRealtimeNanos() - started
            assertEquals("Cold proof must read the whole history", size.toLong(), next.work.rows)
            assertEquals(size.toLong(), next.read().count)
            readable += android.os.SystemClock.elapsedRealtimeNanos() - started
        }
        val p95 = ready.sorted()[18]
        android.util.Log.i("G0ColdRecovery", "kind=growth size=$size samples=20 ready_ns=${ready.joinToString(",")} readable_ns=${readable.joinToString(",")}")
        assertTrue("G0_COLD_READY_BUDGET size=$size P95=${p95 / 1_000_000}ms exceeds 2000ms", p95 <= 2_000_000_000L)
    }

    @Test(timeout = 300_000) fun coldProofAtOneThousandFacts() = runBlocking<Unit> { coldGrowth(1024) }
    @Test(timeout = 300_000) fun coldProofAtTenThousandFacts() = runBlocking<Unit> { coldGrowth(10240) }
}
