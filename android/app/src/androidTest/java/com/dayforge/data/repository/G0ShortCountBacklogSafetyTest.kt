package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
open class G0ShortCountBacklogSafetyTest:G0ShortCountFixture() {
    private suspend fun failed(worker:G0ShortCountSyncWorker):Exception =
        requireNotNull(try {worker.finish();null} catch(e:Exception) {e})
    @Test fun cancelledBeforeStartLeavesExactDurableQueueAndCannotClaimDrain()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount();val saved=store.increase();val (http,server)=channel {successReply(it)}
        val worker=G0ShortCountSyncWorker(store,http,this);worker.offer(saved.requestId);val before=pageNativeState()
        worker.cancel();assertTrue(failed(worker) is CancellationException);assertTrue(worker.attempts.isEmpty())
        assertFalse(worker.offer(saved.requestId));assertEquals(before,pageNativeState());assertTrue(server.requests.isEmpty())
    }
    @Test fun capacityDuplicatesAndFreedSlotsNeverLoseDurableSources()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount();val facts=List(3) {store.increase()};val (http,_)=channel {successReply(it)}
        val worker=G0ShortCountSyncWorker(store,http,this,2)
        assertTrue(worker.offer(facts[0].requestId));assertTrue(worker.offer(facts[0].requestId))
        assertTrue(worker.offer(facts[1].requestId));assertFalse(worker.offer(facts[2].requestId));worker.start()
        withTimeout(20_000) {while(worker.depths.last().outstanding==2) {worker.failure?.let {throw it};delay(10)}}
        assertTrue(worker.offer(facts[0].requestId));assertTrue(worker.offer(facts[2].requestId));worker.finish()
        assertEquals(facts.map {it.requestId},worker.attempts.map {it.requestId});assertEquals(0,worker.depths.last().outstanding)
        assertEquals(0,pageRowCount("sync_outbox"));assertEquals(4,pageRowCount("completions"));assertEquals(4,pageRowCount("next_acceptances"))
        val result=view(store,facts.last().factId);val before=pageNativeState()
        for(a in worker.attempts)assertEquals(NextOperationAcceptance.REPLAYED,store.acknowledge(requireNotNull(a.delivery)))
        assertEquals(before,pageNativeState());reopenReference();sameAsOriginal(result)
    }
    @Test fun blockedNetworkAllowsThreeCommitsAndSharedReadsThenFifoDrain()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount();val first=store.increase()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val (http,_)=channel {input->
            if(input.path.endsWith("/push") && entered.count>0) {entered.countDown();check(release.await(30,TimeUnit.SECONDS))}
            successReply(input)
        }
        val worker=G0ShortCountSyncWorker(store,http,this);worker.offer(first.requestId);worker.start()
        try {
            assertTrue(withContext(Dispatchers.IO) {entered.await(8,TimeUnit.SECONDS)})
            repeat(3) {i->val fact=store.increase();assertTrue(worker.offer(fact.requestId))
                val read=view(store,fact.factId);assertEquals((i+3).toLong(),read.history.todayQuantity)
                assertEquals(fact.factUuid,read.followUp.claim.completionUuid)}
            assertEquals(4,pageRowCount("sync_outbox"));assertEquals(1,pageRowCount("next_acceptances"))
            release.countDown();worker.finish();assertEquals(4,worker.attempts.size)
            assertEquals(0,pageRowCount("sync_outbox"));assertEquals(5,pageRowCount("completions"))
            // Candidate still decrypts at TWO independent boundaries; its original
            // arm performs two decryptions per boundary. This is not a cached grant.
            assertTrue(worker.attempts.all {it.accepted==NextOperationAcceptance.COMMITTED &&
                it.ack.count("decrypt_access")>=if(jointCountAuthority)2 else 4})
            val result=view(store);reopenReference();sameAsOriginal(result)
        } finally {release.countDown();worker.shutdown()}
    }
    @Test fun cancellationInFlightRetainsExactOutboxForSameRequestRecovery()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount();val fact=store.increase()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val (http,_)=channel {input->
            if(input.path.endsWith("/push") && entered.count>0) {entered.countDown();check(release.await(15,TimeUnit.SECONDS))}
            successReply(input).copy(allowClientClose=true)
        }
        val worker=G0ShortCountSyncWorker(store,http,this);worker.offer(fact.requestId);worker.start()
        try {assertTrue(withContext(Dispatchers.IO) {entered.await(8,TimeUnit.SECONDS)});worker.cancel()}
        finally {release.countDown();worker.shutdown()}
        assertTrue(failed(worker) is CancellationException);assertEquals(1,pageRowCount("sync_outbox"));assertEquals(2,pageRowCount("completions"))
        val next=G0ShortCountSyncWorker(store,http,this);next.offer(fact.requestId);next.finish()
        assertEquals(0,pageRowCount("sync_outbox"));assertEquals(2,pageRowCount("completions"))
    }
    @Test fun revokedAccountAndUnknownHintCannotSendOrPublish()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount();val fact=store.increase();val (http,server)=channel {successReply(it)}
        val bad=G0ShortCountSyncWorker(store,http,this);bad.offer(id(2901));val before=pageNativeState();failed(bad)
        assertTrue(server.requests.none {it.path.endsWith("/push")});assertEquals(before,pageNativeState())
        val old=G0ShortCountSyncWorker(store,http,this);old.offer(fact.requestId)
        tokens.saveLoginSession("synthetic-other","synthetic-refresh","member",id(2902),false)
        failed(old);assertEquals(before,pageNativeState());assertTrue(server.requests.none {it.path.endsWith("/push")})
        var published=false;assertTrue(runCatching {store.readAndPublish {published=true}}.isFailure);assertFalse(published)
    }
    @Test fun freshFinalAckFailureRollsBackAndStopsLaterQueueEntries()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount();val facts=List(2) {store.increase()};val (http,_)=channel {successReply(it)}
        val worker=G0ShortCountSyncWorker(store,http,this);facts.forEach {worker.offer(it.requestId)}
        worker.beforeAcceptForTest={store.beforeFinalForTest={failDecrypt=true}}
        try {failed(worker);assertEquals(1,worker.attempts.size);assertNull(worker.attempts.single().accepted)
            assertEquals(2,pageRowCount("sync_outbox"));assertEquals(1,pageRowCount("next_acceptances"));assertEquals(3,pageRowCount("completions"))
            assertFalse(worker.offer(facts.last().requestId))} finally {failDecrypt=false;store.beforeFinalForTest={}}
    }
    @Test fun unknownAckCommitStopsWithoutRetryAndExactOriginalReplayRecovers()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount();val facts=List(2) {store.increase()};val (http,_)=channel {successReply(it)}
        val worker=G0ShortCountSyncWorker(store,http,this);facts.forEach {worker.offer(it.requestId)}
        worker.beforeAcceptForTest={endFaults.failAfterNextEndIn(it.ack)}
        assertEquals("G0_COMMIT_RESULT_LOST",failed(worker).message);assertEquals(1,worker.attempts.size)
        assertEquals(1,pageRowCount("sync_outbox"));assertEquals(2,pageRowCount("next_acceptances"));assertEquals(3,pageRowCount("completions"))
        assertTrue(runCatching {view(store)}.isFailure);reopenReference()
        val before=pageNativeState();assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(requireNotNull(worker.attempts.single().delivery)))
        assertEquals(before,pageNativeState())
    }
    @Test fun unknownTransmissionCommitStopsBeforeNetworkAndNeverAddsAnotherFact()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount();val facts=List(2) {store.increase()};val (http,server)=channel {successReply(it)}
        val worker=G0ShortCountSyncWorker(store,http,this);facts.forEach {worker.offer(it.requestId)}
        worker.beforeSendForTest={endFaults.failAfterNextEndIn(it.send)}
        assertEquals("G0_COMMIT_RESULT_LOST",failed(worker).message);assertEquals(1,worker.attempts.size)
        assertNull(worker.attempts.single().delivery);assertEquals(2,pageRowCount("sync_outbox"));assertEquals(3,pageRowCount("completions"))
        assertTrue(server.requests.none {it.path.endsWith("/push")});assertTrue(runCatching {view(store)}.isFailure)
    }
    @Test fun sendPreparationConflictReauthenticatesAndSendsExactRequestOnlyOnce()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount();val first=store.increase();val (http,server)=channel {successReply(it)}
        var prepared=0;var second:G0ShortCountStore.Saved?=null
        store.afterSendPrepareForTest={if(prepared++==0)second=store.increase()}
        val worker=G0ShortCountSyncWorker(store,http,this);worker.offer(first.requestId);worker.finish()
        assertEquals(2,prepared);assertEquals(1,server.requests.count {it.path.endsWith("/push")})
        assertEquals(NextOperationAcceptance.COMMITTED,worker.attempts.single().accepted)
        assertTrue(worker.attempts.single().send.count("decrypt_access")>=if(jointCountAuthority)15 else 17)
        assertEquals(3,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
        val next=G0ShortCountSyncWorker(store,http,this);next.offer(requireNotNull(second).requestId);next.finish()
        assertEquals(0,pageRowCount("sync_outbox"));val result=view(store,second!!.factId);reopenReference();sameAsOriginal(result)
    }
    @Test fun threeSendPreparationConflictsStopBeforeTransmissionOrNetwork()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount();val first=store.increase();val (http,server)=channel {successReply(it)}
        var prepared=0;store.afterSendPrepareForTest={prepared++;store.increase()}
        val worker=G0ShortCountSyncWorker(store,http,this);worker.offer(first.requestId)
        assertEquals("G0_COUNT_PREPARATION_CHANGED",failed(worker).message);assertEquals(3,prepared)
        assertTrue(server.requests.none {it.path.endsWith("/push")});assertEquals(1,pageRowCount("next_transmissions"))
        assertEquals(5,pageRowCount("completions"));assertEquals(4,pageRowCount("sync_outbox"));assertFalse(worker.offer(first.requestId))
        store.afterSendPrepareForTest={};assertEquals(5L,view(store).history.todayQuantity)
    }
    @Test fun finalSendAuthenticationFailureAfterReprepareIsNotRetried()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount();val first=store.increase();val (http,server)=channel {successReply(it)}
        var prepared=0;store.afterSendPrepareForTest={if(prepared++==0)store.increase() else store.beforeFinalForTest={failDecrypt=true}}
        val worker=G0ShortCountSyncWorker(store,http,this);worker.offer(first.requestId)
        try {
            failed(worker);assertEquals(2,prepared);assertTrue(server.requests.none {it.path.endsWith("/push")})
            assertEquals(1,pageRowCount("next_transmissions"));assertEquals(3,pageRowCount("completions"));assertEquals(2,pageRowCount("sync_outbox"))
            assertTrue(runCatching {view(store)}.isFailure)
        } finally {failDecrypt=false;store.beforeFinalForTest={}}
    }
}
