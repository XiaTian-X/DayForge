package com.dayforge.data.repository

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0AckCredentialBoundaryTest : G0DomainFixture() {
    @Test fun unusedRefreshDamageDoesNotBlockAckButAccessIsCheckedTwice() = runBlocking<Unit> {
        register(); val row = editMetric("ACK credentials")
        val (http, _) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
        dataStore.edit { it[stringPreferencesKey("refresh_token")] = "enc:v1:broken" }
        val trace = G0PhaseTrace()
        trace.run { assertEquals(NextOperationAcceptance.COMMITTED, bridge(true).acknowledge(http, delivery)) }
        assertEquals(2, trace.count("decrypt_access")); assertEquals(0, trace.count("decrypt_refresh"))
        assertEquals(2, trace.count("datastore_read"))
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        assertNull(db.syncOutboxDao().getById(row.id))
    }

    @Test fun accessFailureAtFinalRollsBackAllAckWrites() = runBlocking<Unit> {
        register(); val row = editMetric("Final failure")
        val (http, _) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
        val image = storage.snapshotClosedDatabase(); storage.reopen()
        for (split in listOf(false, true)) {
            if (split) { storage.restoreClosedDatabase(image); storage.reopen() }
            unreadableAccess = false
            val trace = G0PhaseTrace().apply { beforeFinal = { unreadableAccess = true } }
            // Direct candidate isolates the boundary; a failed bridge intentionally quarantines its scheduler.
            val error = rejected { trace.run { G0AckCredentialProbe(db, tokens, sessions, split).acceptOperation(delivery) } }
            assertTrue(error is NextRequestException)
            assertEquals(2, trace.count("datastore_read")); assertEquals(2, trace.count("decrypt_access"))
            assertEquals(if (split) 0 else 1, trace.count("decrypt_refresh"))
            assertUnaccepted(row)
            assertNull(db.syncOutboxDao().getState("metric", metric.uuid))
            assertEquals(1, trace.count("tx_end_outer"))
            assertFalse(trace.spans.single { it.phase == "final_auth" }.ok)
        }
        unreadableAccess = false
    }

    @Test fun lostOuterCommitStillRequiresColdReceiptRecovery() = runBlocking<Unit> {
        register(); val row = editMetric("Lost commit")
        val (http, server) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
        val requests = server.requests.size
        faultTargetAck = row.operationId; faults.failAfterNextEnd.set(true)
        val trace = G0PhaseTrace()
        val error = rejected { trace.run { bridge(true).acknowledge(http, delivery) } }
        assertEquals("G0_COMMIT_RESULT_LOST", error.message)
        assertFalse(trace.spans.single { it.phase == "tx_end_outer" }.ok)
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        assertNull(db.syncOutboxDao().getById(row.id))
        assertEquals("G0_DOMAIN_RECOVERY_REQUIRED", rejected { bridge(true).acknowledge(http, delivery) }.message)
        storage.reopen()
        // Recover with the unchanged original full validator, not a cached ACK success.
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).acceptOperation(delivery))
        assertEquals(requests, server.requests.size)
        assertEquals(1, count("next_acceptances"))
    }
    @Test fun tracingPreservesContinuousCredentialObservation() = runBlocking<Unit> {
        val observed = mutableListOf<androidx.datastore.preferences.core.Preferences>()
        val first = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            dataStore.data.take(2).collect { observed.add(it); first.complete(Unit) }
        }
        first.await()
        dataStore.edit { it[stringPreferencesKey("trace_probe")] = "changed" }
        withTimeout(2_000) { job.join() }
        assertEquals(2, observed.size)
        assertNull(observed[0][stringPreferencesKey("trace_probe")])
        assertEquals("changed", observed[1][stringPreferencesKey("trace_probe")])
    }

    @Test fun generationChangedBetweenEntryAndFinalCannotCommit() = runBlocking<Unit> {
        register(); val row = editMetric("Generation change")
        val (http, _) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
        val trace = G0PhaseTrace().apply {
            beforeFinal = { dataStore.edit { it[stringPreferencesKey("auth_session_generation")] = id(991) } }
        }
        val error = rejected { trace.run { G0AckCredentialProbe(db, tokens, sessions, true).acceptOperation(delivery) } }
        assertTrue(error is NextRequestException)
        assertEquals(2, trace.count("datastore_read"))
        assertEquals(1, trace.count("decrypt_access")) // Changed metadata rejects before final decryption.
        assertUnaccepted(row)
    }

    @Test fun failedSnapshotReadIsTimedAndPropagated() = runBlocking<Unit> {
        val failure = java.io.IOException("synthetic datastore failure")
        val broken = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = flow<androidx.datastore.preferences.core.Preferences> { throw failure }
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences = throw failure
        }
        val trace = G0PhaseTrace()
        val thrown = rejected { trace.run { G0PhaseTrace.wrap(broken).data.first() } }
        assertSame(failure, thrown)
        assertEquals(1, trace.count("datastore_read"))
        assertFalse(trace.spans.single { it.phase == "datastore_read" }.ok)
    }

}
