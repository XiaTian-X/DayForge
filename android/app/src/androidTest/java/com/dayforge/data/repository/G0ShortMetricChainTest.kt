package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0ShortMetricChainTest : G0SealedDomainFixture() {
    private suspend fun store(): G0ShortMetricStore {
        register()
        return G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid).also { it.initialize(access(), trustedDomainSchema()) }
    }
    private fun reopenOriginal() { storage.isolatedBuilder = null; storage.reopen() }
    @Test fun actualShortEditSendAckAndReplayPreserveOriginalReceiptsAndFreshAuthentication() = runBlocking<Unit> {
        val store = store()
        val row = requireNotNull(store.edit("Short full chain", "Prepared once", id(950), millis))
        val (http, server) = channel { successReply(it) }
        val delivery = requireNotNull(store.send(http, access(), row.operationId))
        val count = decryptCount.get()
        assertEquals(NextOperationAcceptance.COMMITTED, store.acknowledge(delivery))
        assertEquals(2, decryptCount.get() - count)
        val networkCount = server.requests.size
        assertEquals(NextOperationAcceptance.REPLAYED, store.acknowledge(delivery))
        assertEquals(networkCount, server.requests.size)
        reopenOriginal()
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).acceptOperation(delivery))
        assertEquals(1, count("next_acceptances")); assertEquals(0, count("sync_outbox"))
        assertEquals("Short full chain", db.metricDao().getMetricById(metric.id)!!.name)
    }
    @Test fun oldAckPreservesNewerLocalDefinitionAndItsExactPendingSource() = runBlocking<Unit> {
        val store = store()
        val first = requireNotNull(store.edit("Earlier", "", id(951), millis))
        val (http, _) = channel { successReply(it) }
        val delivery = requireNotNull(store.send(http, access(), first.operationId))
        val later = requireNotNull(store.edit("Later", "", id(952), millis+1))
        assertEquals(NextOperationAcceptance.COMMITTED, store.acknowledge(delivery))
        reopenOriginal()
        assertEquals("Later", db.metricDao().getMetricById(metric.id)!!.name)
        assertEquals(listOf(later), db.syncOutboxDao().getAll())
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).acceptOperation(delivery))
    }
    @Test fun finalCredentialFailureRollsBackReceiptAndLeavesOriginalQueueIntact() = runBlocking<Unit> {
        val store = store(); val row = requireNotNull(store.edit("Unaccepted", "", id(953), millis))
        val (http, _) = channel { successReply(it) }; val delivery = requireNotNull(store.send(http, access(), row.operationId))
        store.beforeFinalForTest = { failDecrypt = true }
        assertEquals("G0_SHORT_STALE_ACCESS", rejected { store.acknowledge(delivery) }.message)
        failDecrypt = false; reopenOriginal(); assertUnaccepted(row)
        assertEquals("Unaccepted", db.metricDao().getMetricById(metric.id)!!.name)
    }
    @Test fun actualAckCommitResultLossRejectsWarmReplayAndOriginalColdReplayFindsReceipt() = runBlocking<Unit> {
        val store = store(); val row = requireNotNull(store.edit("Unknown ack", "", id(954), millis))
        val (http, server) = channel { successReply(it) }; val delivery = requireNotNull(store.send(http, access(), row.operationId))
        store.beforeFinalForTest = { endFaults.failAfterNextEnd.set(true) }
        assertEquals("G0_COMMIT_RESULT_LOST", rejected { store.acknowledge(delivery) }.message)
        assertEquals("G0_SHORT_RECOVERY_REQUIRED", rejected { store.acknowledge(delivery) }.message)
        val count = server.requests.size
        reopenOriginal()
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).acceptOperation(delivery))
        assertEquals(count, server.requests.size); assertEquals(1, count("next_acceptances")); assertEquals(0, count("sync_outbox"))
    }
    @Test fun responseChangedOnReplayIsRejectedWithoutNewBusinessOrNetwork() = runBlocking<Unit> {
        val store = store(); val row = requireNotNull(store.edit("Exact reply", "", id(955), millis))
        val (http, server) = channel { successReply(it) }; val delivery = requireNotNull(store.send(http, access(), row.operationId))
        store.acknowledge(delivery)
        val result = delivery.result.results.single()
        val changed = result.copy(entity = JsonObject(requireNotNull(result.entity) + ("description" to JsonPrimitive("Changed reply"))))
        val count = server.requests.size
        assertEquals("G0_SHORT_RESULT_CHANGED", rejected { store.acknowledge(delivery.copy(result = delivery.result.copy(results = listOf(changed)))) }.message)
        assertEquals(count, server.requests.size)
        reopenOriginal(); assertEquals(1, count("next_acceptances")); assertEquals(0, count("sync_outbox"))
    }
    @Test fun noOpDefinitionDoesNotManufactureASecondOriginOrOutbox() = runBlocking<Unit> {
        val store = store()
        assertNull(store.edit(metric.name,metric.description,id(956),millis+1))
        reopenOriginal()
        assertEquals(0, count("sync_outbox")); assertEquals(0, count("next_request_origins"))
        assertEquals(millis+1, db.metricDao().getMetricById(metric.id)!!.updatedAt)
    }
    @Test fun lateMutationInAckIsRejectedByActualEffectsNotJustNetResult() = runBlocking<Unit> {
        val store = store(); val row = requireNotNull(store.edit("Expected", "", id(957), millis))
        val (http, _) = channel { successReply(it) }; val delivery = requireNotNull(store.send(http, access(), row.operationId))
        store.beforeFinalForTest = {
            db.openHelper.writableDatabase.execSQL("UPDATE metrics SET name=?,description=?,updatedAt=? WHERE id=?", arrayOf<Any>("Transient late", "", millis, metric.id))
            db.openHelper.writableDatabase.execSQL("UPDATE metrics SET name=?,description=?,updatedAt=? WHERE id=?", arrayOf<Any>("Expected", "", millis, metric.id))
        }
        assertEquals("G0_SHORT_UNEXPECTED_EFFECT", rejected { store.acknowledge(delivery) }.message)
        reopenOriginal(); assertUnaccepted(row)
    }
    @Test fun committedReadModelUsesFreshReadAuthorityWithoutRequiringWritePermission() = runBlocking<Unit> {
        val store = store(); val row = requireNotNull(store.edit("Readable", "Exact view", id(958), millis))
        assertEquals(G0ShortMetricStore.View("Readable","Exact view",1,null),store.read(access()))
        val (http, _) = channel { successReply(it) }; val delivery = requireNotNull(store.send(http,access(),row.operationId))
        store.acknowledge(delivery)
        tokens.saveDeviceRegistration(id(4),setOf("sync.read"),true,2)
        val readAccess = access()
        val before = decryptCount.get()
        assertEquals(G0ShortMetricStore.View("Readable","Exact view",0,1L),store.read(readAccess))
        assertEquals(2,decryptCount.get()-before)
        rejected { store.edit("No write permission","",id(959),millis) }
        tokens.saveLoginSession("other-synthetic","other-refresh","member",id(999),false)
        rejected { store.read(delivery.access) }
    }
    @Test fun coldRebuildAfterUnknownAckUsesOriginalReceiptAndNewConnectionCertificate() = runBlocking<Unit> {
        val first = store(); val row = requireNotNull(first.edit("Cold actual receipt","",id(960),millis))
        val (http, server) = channel { successReply(it) }; val delivery = requireNotNull(first.send(http,access(),row.operationId))
        first.beforeFinalForTest = { endFaults.failAfterNextEnd.set(true) }
        assertEquals("G0_COMMIT_RESULT_LOST",rejected { first.acknowledge(delivery) }.message)
        freshGateForColdOpen(); storage.reopen()
        val rebuilt = G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid)
        rebuilt.initialize(access(),trustedDomainSchema())
        val networkCount = server.requests.size
        assertEquals(NextOperationAcceptance.REPLAYED,rebuilt.acknowledge(delivery))
        assertEquals(networkCount,server.requests.size)
        assertEquals(0,rebuilt.read(access()).pending)
        rejected { first.acknowledge(delivery) }
    }
    @Test fun coldRebuildAfterUnknownLocalCommitSendsTheSameDurableOperationOnlyOnce() = runBlocking<Unit> {
        val first = store(); first.beforeFinalForTest = { endFaults.failAfterNextEnd.set(true) }
        assertEquals("G0_COMMIT_RESULT_LOST",rejected { first.edit("Cold source","",id(961),millis) }.message)
        freshGateForColdOpen(); storage.reopen()
        val rebuilt = G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid)
        rebuilt.initialize(access(),trustedDomainSchema())
        val (http, server) = channel { successReply(it) }
        val delivery = requireNotNull(rebuilt.send(http,access(),id(961)))
        assertEquals(NextOperationAcceptance.COMMITTED,rebuilt.acknowledge(delivery))
        assertEquals(1,server.requests.count { it.path.endsWith("/push") })
        assertEquals(0,rebuilt.read(access()).pending)
    }

}
