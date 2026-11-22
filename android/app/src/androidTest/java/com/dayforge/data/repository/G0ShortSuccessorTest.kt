package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0ShortSuccessorTest : G0SealedDomainFixture() {
    private suspend fun owner():G0ShortMetricStore {
        register()
        return G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid).also { it.initialize(access(),trustedDomainSchema()) }
    }
    private fun original() { storage.isolatedBuilder=null;storage.reopen() }
    private suspend fun cold():G0ShortMetricStore {
        freshGateForColdOpen()
        storage.isolatedBuilder={ context -> androidx.room.Room.databaseBuilder(context,com.dayforge.data.local.HabitDatabase::class.java,"habit_database")
            .addCallback(com.dayforge.data.local.SyncSchemaCallback).openHelperFactory(domainGate.factory).build() }
        storage.reopen()
        return G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid).also { it.initialize(access(),trustedDomainSchema()) }
    }
    @Test(timeout=180_000) fun twoSuccessorsFollowLogicalOrderAcrossReplacementAndColdRebuild() = runBlocking<Unit> {
        var store=owner()
        val (http,_)=channel { input -> successReply(input,revision=(if(input.path.endsWith("/identity")) 0 else wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0L)+1) }
        val a=requireNotNull(store.edit("First","",id(1101),millis))
        val da=requireNotNull(store.send(http,access(),a.operationId))
        val b=requireNotNull(store.edit("Second","",id(1102),millis+1))
        val c=requireNotNull(store.edit("Third","",id(1103),millis+2))
        assertNotNull(runCatching { store.send(http,access(),c.operationId) }.exceptionOrNull())
        // The conservative failed-read policy requires a new certificate; no source is consumed.
        store=cold()
        assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(da))
        val dbb=requireNotNull(store.send(http,access(),b.operationId))
        assertNotEquals(b.operationId,dbb.requestId)
        assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(dbb))
        store=cold()
        val dc=requireNotNull(store.send(http,access(),c.operationId))
        assertNotEquals(c.operationId,dc.requestId)
        assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(dc))
        original()
        assertEquals(3,count("next_acceptances"));assertEquals(2,count("next_structural_supersessions"));assertEquals(0,count("sync_outbox"))
        assertEquals("Third",db.metricDao().getMetricById(metric.id)!!.name)
        assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(dc))
        assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(dbb))
    }
    @Test fun transmissionRetriesKeepTheSameReplacementIdentityAndBytes() = runBlocking<Unit> {
        val store=owner();val (http,_)=channel { input -> successReply(input, revision=(if(input.path.endsWith("/identity")) 0 else wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0L)+1) }
        val a=requireNotNull(store.edit("First","",id(1111),millis));val da=requireNotNull(store.send(http,access(),a.operationId))
        val b=requireNotNull(store.edit("Second","",id(1112),millis+1));store.acknowledge(da)
        val first=requireNotNull(store.send(http,access(),b.operationId))
        val retry=requireNotNull(store.send(http,access(),b.operationId))
        assertEquals(first.requestId,retry.requestId);assertEquals(first.transmissionProof,retry.transmissionProof)
        original();assertEquals(1,count("next_structural_supersessions"));assertEquals(2,count("next_transmissions"))
        assertEquals(NextOperationAcceptance.COMMITTED,sender(http).acceptOperation(retry))
    }
    @Test fun conflictingSuccessorLeavesOriginalSourceAndReceiptUntouched() = runBlocking<Unit> {
        val store=owner();val (http,_)=channel { successReply(it,edit={ body -> JsonObject(body+("name" to JsonPrimitive("Remote"))) }) }
        val a=requireNotNull(store.edit("First","",id(1121),millis));val da=requireNotNull(store.send(http,access(),a.operationId))
        val b=requireNotNull(store.edit("Local","",id(1122),millis+1));store.acknowledge(da)
        val failure=runCatching { store.send(http,access(),b.operationId) }.exceptionOrNull()
        assertTrue(failure is NextStructuralCausalConflict)
        original();assertEquals(b,db.syncOutboxDao().getById(b.id));assertEquals(0,count("next_structural_supersessions"))
        assertTrue(runCatching { sender(http).sendOperation(access(),b.operationId) }.exceptionOrNull() is NextStructuralCausalConflict)
    }
    @Test fun lostSupersessionCommitRecoversOneDurableReplacementWithoutBlindReplay() = runBlocking<Unit> {
        var store=owner();val (http,_)=channel { input -> successReply(input, revision=(if(input.path.endsWith("/identity")) 0 else wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0L)+1) }
        val a=requireNotNull(store.edit("First","",id(1131),millis));val da=requireNotNull(store.send(http,access(),a.operationId))
        val b=requireNotNull(store.edit("Second","",id(1132),millis+1));store.acknowledge(da)
        store.beforeFinalForTest={endFaults.failAfterNextEnd.set(true)}
        assertEquals("G0_COMMIT_RESULT_LOST",runCatching { store.send(http,access(),b.operationId) }.exceptionOrNull()?.message)
        assertNotNull(runCatching { store.send(http,access(),b.operationId) }.exceptionOrNull())
        store=cold();val delivery=requireNotNull(store.send(http,access(),b.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(delivery))
        original();assertEquals(1,count("next_structural_supersessions"));assertEquals(2,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
        assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(delivery))
    }
    @Test fun finalAuthFailureCannotRetireTheOriginalSuccessor() = runBlocking<Unit> {
        val store=owner();val (http,_)=channel { input -> successReply(input, revision=(if(input.path.endsWith("/identity")) 0 else wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0L)+1) }
        val a=requireNotNull(store.edit("First","",id(1141),millis));val da=requireNotNull(store.send(http,access(),a.operationId))
        val b=requireNotNull(store.edit("Second","",id(1142),millis+1));store.acknowledge(da)
        store.beforeFinalForTest={failDecrypt=true}
        assertNotNull(runCatching { store.send(http,access(),b.operationId) }.exceptionOrNull());failDecrypt=false
        original();assertEquals(b,db.syncOutboxDao().getById(b.id));assertEquals(0,count("next_structural_supersessions"))
    }
}
