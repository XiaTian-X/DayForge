package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real overlapping preparation, not concurrent writes inside one SQLite transaction. */
@RunWith(AndroidJUnit4::class)
class G0PreparationRetryTest : G0SealedDomainFixture() {
    private suspend fun owner(): G0ShortMetricStore {
        register()
        return G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid).also {
            it.initialize(access(),trustedDomainSchema())
        }
    }
    @Test fun foregroundPreparationSurvivesBackgroundAckWithoutColdRecovery() = runBlocking<Unit> {
        val store=owner();val (http,_)=channel { successReply(it) }
        val first=requireNotNull(store.edit("First","",id(1201),millis))
        val delivery=requireNotNull(store.send(http,access(),first.operationId))
        val prepared=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        var attempts=0
        store.afterPrepareForTest={ if(currentCoroutineContext()[CoroutineName]?.name=="foreground") {
            attempts++;prepared.complete(Unit);release.await()
        } }
        val foreground=async(CoroutineName("foreground")) {
            runCatching { store.edit("Second","",id(1202),millis+1) }
        }
        prepared.await()
        assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(delivery))
        release.complete(Unit)
        val result=foreground.await()
        assertTrue("Safe dependency change must reprepare: ${result.exceptionOrNull()}",result.isSuccess)
        assertEquals(id(1202),requireNotNull(result.getOrThrow()).operationId)
        assertEquals(2,attempts)
        val view=store.read(access());assertEquals("Second",view.name);assertEquals(1,view.pending)
        storage.isolatedBuilder=null;storage.reopen()
        assertEquals(1,count("next_acceptances"));assertEquals(1,count("sync_outbox"));assertEquals(2,count("next_request_origins"))
        val next=requireNotNull(sender(http).sendOperation(access(),id(1202)))
        assertEquals(id(1202),next.requestId)
    }
    @Test fun backgroundAckRepreparesAndPreservesNewerForegroundDefinition() = runBlocking<Unit> {
        val store=owner();val (http,_)=channel { successReply(it) }
        val first=requireNotNull(store.edit("First","",id(1211),millis))
        val delivery=requireNotNull(store.send(http,access(),first.operationId))
        val prepared=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        var attempts=0
        store.afterPrepareForTest={ if(currentCoroutineContext()[CoroutineName]?.name=="background") {
            attempts++;prepared.complete(Unit);release.await()
        } }
        val background=async(CoroutineName("background")) { runCatching { store.acknowledge(delivery) } }
        prepared.await()
        val second=requireNotNull(store.edit("Second","",id(1212),millis+1))
        release.complete(Unit)
        val result=background.await()
        assertTrue("ACK must reprepare instead of poisoning owner: ${result.exceptionOrNull()}",result.isSuccess)
        assertEquals(NextOperationAcceptance.COMMITTED,result.getOrThrow());assertEquals(2,attempts)
        val view=store.read(access());assertEquals("Second",view.name);assertEquals(1,view.pending)
        storage.isolatedBuilder=null;storage.reopen()
        assertEquals(second,db.syncOutboxDao().getById(second.id))
        assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(delivery))
    }
    @Test fun threeChangedPreparationsReturnBusyWithoutWritingOrPoisoningOwner() = runBlocking<Unit> {
        val store=owner();var attempts=0
        store.afterPrepareForTest={ if(currentCoroutineContext()[CoroutineName]?.name=="contended") {
            attempts++
            withContext(CoroutineName("interference")) {
                store.edit("Other $attempts","",id(1230+attempts),millis+attempts)
            }
        } }
        val result=withContext(CoroutineName("contended")) { runCatching { store.edit("Wanted","",id(1230),millis) } }
        assertTrue(result.exceptionOrNull() is G0ShortMetricStore.PreparationBusy)
        assertEquals(3,attempts)
        val view=store.read(access());assertEquals("Other 3",view.name);assertEquals(3,view.pending)
        store.afterPrepareForTest={}
        assertNotNull(store.edit("Still usable","",id(1230),millis+4))
        storage.isolatedBuilder=null;storage.reopen()
        assertEquals(4,count("next_request_origins"));assertEquals(4,count("sync_outbox"))
        assertEquals(1,db.syncOutboxDao().getAll().count { it.operationId==id(1230) })
    }
    @Test fun reprepareDoesNotReuseFinalAuthentication() = runBlocking<Unit> {
        val store=owner();var attempts=0
        store.afterPrepareForTest={ if(currentCoroutineContext()[CoroutineName]?.name=="contended") {
            attempts++
            if(attempts==1) withContext(CoroutineName("interference")) {
                store.edit("Other","",id(1241),millis)
            } else store.beforeFinalForTest={failDecrypt=true}
        } }
        val result=withContext(CoroutineName("contended")) { runCatching { store.edit("Denied","",id(1240),millis+1) } }
        assertTrue(result.isFailure);assertEquals(2,attempts);failDecrypt=false
        storage.isolatedBuilder=null;storage.reopen()
        assertEquals("Other",db.metricDao().getMetricById(metric.id)!!.name)
        assertEquals(1,count("next_request_origins"));assertEquals(id(1241),db.syncOutboxDao().getAll().single().operationId)
    }
    @Test fun cancellationDuringSecondPreparationLeavesOwnerUsable() = runBlocking<Unit> {
        val store=owner();var attempts=0;val second=CompletableDeferred<Unit>()
        store.afterPrepareForTest={ if(currentCoroutineContext()[CoroutineName]?.name=="contended") {
            attempts++
            if(attempts==1) withContext(CoroutineName("interference")) { store.edit("Other","",id(1251),millis) }
            else { second.complete(Unit);awaitCancellation() }
        } }
        val pending=launch(CoroutineName("contended")) { store.edit("Cancelled","",id(1250),millis+1) }
        second.await();pending.cancelAndJoin();assertEquals(2,attempts)
        val view=store.read(access());assertEquals("Other",view.name);assertEquals(1,view.pending)
        storage.isolatedBuilder=null;storage.reopen()
        assertEquals(1,count("next_request_origins"));assertEquals(id(1251),db.syncOutboxDao().getAll().single().operationId)
    }
    @Test fun unknownCommitAfterReprepareIsNeverRetried() = runBlocking<Unit> {
        val store=owner();var attempts=0
        store.afterPrepareForTest={ if(currentCoroutineContext()[CoroutineName]?.name=="contended") {
            attempts++
            if(attempts==1) withContext(CoroutineName("interference")) { store.edit("Other","",id(1261),millis) }
            else store.beforeFinalForTest={endFaults.failAfterNextEnd.set(true)}
        } }
        val result=withContext(CoroutineName("contended")) { runCatching { store.edit("Committed","",id(1260),millis+1) } }
        assertEquals("G0_COMMIT_RESULT_LOST",result.exceptionOrNull()?.message);assertEquals(2,attempts)
        assertNotNull(runCatching { store.read(access()) }.exceptionOrNull())
        storage.isolatedBuilder=null;storage.reopen()
        assertEquals("Committed",db.metricDao().getMetricById(metric.id)!!.name)
        assertEquals(2,count("next_request_origins"));assertEquals(2,count("sync_outbox"))
        assertEquals(1,db.syncOutboxDao().getAll().count { it.operationId==id(1260) })
    }
}
