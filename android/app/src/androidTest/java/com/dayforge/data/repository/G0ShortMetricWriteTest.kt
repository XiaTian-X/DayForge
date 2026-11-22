package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0ShortMetricWriteTest : G0SealedDomainFixture() {
    private suspend fun store(): G0ShortMetricStore {
        register()
        return G0ShortMetricStore(db, tokens, sessions, authority, domainGate, metric.uuid).also { it.initialize(access(), trustedDomainSchema()) }
    }
    private fun openOriginalAfterCandidate() {
        storage.isolatedBuilder = null
        storage.reopen()
    }
    @Test fun shortEditCreatesExactOriginalSourceAndOriginalSenderAcceptsItAfterColdOpen() = runBlocking<Unit> {
        val candidate = store()
        val row = requireNotNull(candidate.edit("Prepared outside SQL", "Immutable input", id(901), millis))
        openOriginalAfterCandidate()
        assertEquals(row, db.syncOutboxDao().getById(row.id))
        assertEquals(1, count("next_request_origins")); assertEquals(1, count("next_structural_dependencies"))
        val (http, _) = channel { successReply(it) }
        val original = sender(http)
        val delivery = requireNotNull(original.sendOperation(access(), row.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED, original.acceptOperation(delivery))
        assertEquals(NextOperationAcceptance.REPLAYED, original.acceptOperation(delivery))
        assertEquals(0, count("sync_outbox")); assertEquals(1, count("next_acceptances"))
    }
    @Test fun preparedInputsHoldNeitherAccountLockNorSqlResource() = runBlocking<Unit> {
        val candidate = store()
        val prepared = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        candidate.afterPrepareForTest = { prepared.complete(Unit); release.await() }
        val write = async { candidate.edit("Not holding locks", "", id(902), millis) }
        prepared.await()
        withTimeout(1000) {
            sessions.exclusive {
                domainGate.transaction(db, G0DomainSqlGate.Phase.READ) { assertFalse(db.metricDao().getMetricById(metric.id)!!.name == "Not holding locks") }
            }
        }
        release.complete(Unit); write.await()
    }
    @Test fun lateExtraMetricMutationCannotBeAcceptedAsThePlannedWrite() = runBlocking<Unit> {
        val candidate = store()
        candidate.beforeFinalForTest = {
            db.openHelper.writableDatabase.execSQL("UPDATE metrics SET name=?,description=?,updatedAt=? WHERE id=?", arrayOf<Any>("Hidden extra", "", millis, metric.id))
        }
        assertEquals("G0_SHORT_UNEXPECTED_EFFECT", rejected { candidate.edit("Expected", "", id(903), millis) }.message)
        openOriginalAfterCandidate()
        assertEquals(metric, db.metricDao().getMetricById(metric.id))
        assertEquals(0, count("sync_outbox")); assertEquals(0, count("next_request_origins"))
    }
    @Test fun revocationWhilePurePreparationIsSuspendedPreventsAnyCommit() = runBlocking<Unit> {
        val candidate = store()
        candidate.afterPrepareForTest = { tokens.saveLoginSession("other-test", "other-refresh", "member", id(999), false) }
        assertTrue(rejected { candidate.edit("Revoked", "", id(904), millis) } is G0AuthorityOwner.Denied)
        openOriginalAfterCandidate()
        assertEquals(metric, db.metricDao().getMetricById(metric.id)); assertEquals(0, count("next_request_origins"))
    }
    @Test fun resultLostAfterRealCommitDoesNotAllowBlindSecondWrite() = runBlocking<Unit> {
        val candidate = store()
        candidate.beforeFinalForTest = { endFaults.failAfterNextEnd.set(true) }
        assertEquals("G0_COMMIT_RESULT_LOST", rejected { candidate.edit("Durable original", "", id(905), millis) }.message)
        assertEquals("G0_SHORT_RECOVERY_REQUIRED", rejected { candidate.edit("Durable original", "", id(905), millis) }.message)
        openOriginalAfterCandidate()
        assertEquals(1, count("sync_outbox")); assertEquals(1, count("next_request_origins"))
        val row = db.syncOutboxDao().getAll().single()
        assertEquals(id(905), row.operationId)
        val (http, _) = channel { successReply(it) }; val original = sender(http)
        val delivery = requireNotNull(original.sendOperation(access(), row.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED, original.acceptOperation(delivery))
        assertEquals(1, count("next_acceptances"))
    }
}
