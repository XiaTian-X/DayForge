package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0CountIndependentSafetyTest:G0CountIndependentFixture() {
    private suspend fun seeded()=seedFact()
    private suspend fun failure(worker:G0CountSyncWorker):Exception {
        val error=try {worker.finish();null} catch(e:Exception) {e}
        return requireNotNull(error)
    }
    @Test fun cancellationBeforeStartCannotReportADrainedQueue()=runBlocking<Unit> {
        configureAction();val original=seeded();val (http,server)=channel {successReply(it)};val sync=worker(http)
        assertTrue(sync.offer(original));val before=pageNativeState();sync.cancel()
        assertTrue(failure(sync) is CancellationException);assertFalse(sync.offer(original));assertTrue(sync.attempts.isEmpty())
        assertEquals(before,pageNativeState());assertTrue(server.requests.isEmpty());assertEquals(1,count("sync_outbox"))
    }
    @Test fun heldNetworkResponseCannotBlockThreeRealClicksOrLoseTheirExactSources()=runBlocking<Unit> {
        configureAction(target=1000);val prior=seeded()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val (http,_)=channel {input->
            if(input.path.endsWith("/push") && entered.count>0) {entered.countDown();check(release.await(8,TimeUnit.SECONDS))}
            successReply(input)
        }
        val sync=worker(http);assertTrue(sync.offer(prior));sync.start()
        try {
            assertTrue(withContext(Dispatchers.IO) {entered.await(5,TimeUnit.SECONDS)})
            val flow=journey(afterResult=true,loadOnOpen=true,shareInitialPure=true,prioritizeDashboard=true)
            val home=dashboard(coalesce=true);val a=G0ScreenObservation();val b=G0ScreenObservation()
            installBoth(flow,home,a,b);both(flow,home,a,b)
            repeat(3) {index->
                val old=flow.state.value.version;a.tap("increment");nextResult(flow,old)
                assertEquals((index+2).toLong(),flow.state.value.view!!.countHistory!!.todayQuantity)
                assertTrue(sync.offer(knownResult(flow)));a.await(flow.state.value.version)
            }
            assertEquals(4,count("completions"));assertEquals(0,count("next_acceptances"));assertEquals(4,count("sync_outbox"))
            release.countDown();sync.finish();both(flow,home,a,b)
            assertEquals(4,sync.attempts.size);assertTrue(sync.attempts.all {it.accepted==NextOperationAcceptance.COMMITTED})
            assertEquals(4,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
            for(attempt in sync.attempts) {
                val before=pageNativeState();assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(requireNotNull(attempt.delivery)))
                assertEquals(before,pageNativeState())
            }
        } finally {release.countDown()}
    }
    @Test fun boundedAdmissionAndDuplicateHintsLeaveUnscheduledDurableFactsIntact()=runBlocking<Unit> {
        configureAction(target=1000);val facts=List(3) {seeded()};val (http,_)=channel {successReply(it)}
        val sync=worker(http,2);assertTrue(sync.offer(facts[0]));assertTrue(sync.offer(facts[0]));assertTrue(sync.offer(facts[1]));assertFalse(sync.offer(facts[2]))
        sync.finish();assertEquals(2,sync.attempts.size);assertEquals(3,count("completions"));assertEquals(2,count("next_acceptances"));assertEquals(1,count("sync_outbox"))
        ackOnlyOperation();assertEquals(3,count("next_acceptances"))
    }
    @Test fun mismatchedKnownFactIdentityCannotSelectLatestOutboxOrSendAnything()=runBlocking<Unit> {
        configureAction();val original=seeded();val (http,server)=channel {successReply(it)};val sync=worker(http)
        val before=pageNativeState();assertTrue(sync.offer(original.copy(uuid=id(2601))))
        assertEquals("G0_SYNC_SOURCE_CHANGED",failure(sync).message);assertEquals(before,pageNativeState());assertTrue(server.requests.isEmpty())
    }
    @Test fun queuedOldAccountHintCannotBecomeANewAccountsSend()=runBlocking<Unit> {
        configureAction();val original=seeded();val (http,server)=channel {successReply(it)};val sync=worker(http)
        assertTrue(sync.offer(original));val before=pageNativeState()
        tokens.saveLoginSession("synthetic-other","synthetic-refresh","member",id(2602),false)
        assertEquals("G0_SYNC_ACCOUNT_CHANGED",failure(sync).message);assertEquals(before,pageNativeState());assertTrue(server.requests.isEmpty())
    }
    @Test fun freshAckAuthenticationFailureStopsLaterHintsWithoutConsumingTheirOutbox()=runBlocking<Unit> {
        configureAction(target=1000);val facts=List(2) {seeded()};val (http,_)=channel {successReply(it)};val sync=worker(http)
        facts.forEach {assertTrue(sync.offer(it))};sync.beforeAcceptForTest={failDecrypt=true}
        try {
            failure(sync);assertEquals(1,sync.attempts.size);assertNull(sync.attempts.single().accepted)
            assertEquals(2,count("completions"));assertEquals(0,count("next_acceptances"));assertEquals(2,count("sync_outbox"))
            assertFalse(sync.offer(facts[1]))
        } finally {failDecrypt=false}
    }
    @Test fun unknownAckReturnStopsAndOriginalReplayResolvesOnlyTheExactRequest()=runBlocking<Unit> {
        configureAction(target=1000);val facts=List(2) {seeded()};val (http,_)=channel {successReply(it)};val sync=worker(http)
        facts.forEach {assertTrue(sync.offer(it))};sync.beforeAcceptForTest={endFaults.failAfterNextEndIn(it.ack)}
        assertEquals("G0_COMMIT_RESULT_LOST",failure(sync).message)
        assertEquals(1,sync.attempts.size);assertEquals(2,count("completions"));assertEquals(1,count("next_acceptances"));assertEquals(1,count("sync_outbox"))
        assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(requireNotNull(sync.attempts.single().delivery)))
        assertEquals(1,count("next_acceptances"));ackOnlyOperation();assertEquals(2,count("completions"))
    }
    @Test fun cancellationDuringNetworkLeavesKnownFactRecoverableWithoutRepeatingItsWrite()=runBlocking<Unit> {
        configureAction();val original=seeded();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val (http,_)=channel {input->
            if(input.path.endsWith("/push") && entered.count>0) {entered.countDown();check(release.await(8,TimeUnit.SECONDS))}
            successReply(input).copy(allowClientClose=true)
        }
        val sync=worker(http);sync.offer(original);sync.start()
        try {assertTrue(withContext(Dispatchers.IO) {entered.await(5,TimeUnit.SECONDS)});sync.cancel()} finally {release.countDown()}
        sync.shutdown();assertEquals(0,count("next_acceptances"));assertEquals(1,count("completions"));assertEquals(1,count("sync_outbox"))
        val next=worker(http);assertTrue(next.offer(original));next.finish()
        assertEquals(1,count("next_acceptances"));assertEquals(1,count("completions"));assertEquals(0,count("sync_outbox"))
    }
}
