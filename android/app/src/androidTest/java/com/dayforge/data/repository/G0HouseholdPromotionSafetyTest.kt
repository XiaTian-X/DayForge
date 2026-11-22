package com.dayforge.data.repository

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.NextSyncPushResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic retained-owner promotion interleavings, never cost samples. The old
 * uncoordinated current() guard is retained as a diagnostic counterexample; no unsafe
 * send implementation or bypassing write capability is installed in the candidate.
 */
@RunWith(AndroidJUnit4::class)
class G0HouseholdPromotionSafetyTest:G0HouseholdCountFixture() {
    private inner class PromotionPause(trace:G0PhaseTrace):AutoCloseable {
        val hits=AtomicInteger()
        val entered=CompletableDeferred<Unit>()
        private val release=CountDownLatch(1)
        private val registration=domainGate.beforeOwnerPromotionForTest(trace) {
            assertFalse(db.inTransaction())
            hits.incrementAndGet();entered.complete(Unit)
            check(release.await(20,TimeUnit.SECONDS)) {"G0_PROMOTION_TEST_RELEASE_TIMEOUT"}
        }
        suspend fun await()=withTimeout(15_000) {entered.await()}
        fun resume() {release.countDown()}
        override fun close() {resume();registration.close()}
    }
    private fun spans(trace:G0PhaseTrace)=synchronized(trace.spans) {trace.spans.toList()}
    private fun calls(trace:G0PhaseTrace,phase:String)=spans(trace).count {it.phase==phase}
    private suspend fun recorded(trace:G0PhaseTrace,phase:String)=withTimeout(15_000) {
        while(calls(trace,phase)==0)delay(1)
    }
    private suspend fun stillWaiting(work:Deferred<*>) {
        repeat(2) {index->
            assertFalse("A contending operation must not finish before owner promotion",work.isCompleted)
            if(index==0)delay(50)
        }
    }
    private fun fresh(trace:G0PhaseTrace) {
        assertTrue(calls(trace,"decrypt_access")>=2)
        assertEquals(0,calls(trace,"decrypt_unclassified"))
        assertEquals(0,calls(trace,"count_complete_history"))
    }
    private fun oldGuardRejects(store:G0HouseholdCountStore,prior:G0HouseholdCountStore.View) {
        val decrypts=decryptCount.get()
        val failure=runCatching {store.probeUncoordinatedCurrentForTest()}.exceptionOrNull()
        assertEquals("G0_COUNT_UNOBSERVED_COMMIT",failure?.message)
        assertEquals("The old first guard fails before any fresh authentication",decrypts,decryptCount.get())
        assertFalse("Old private pixels must not bypass the promotion fence",prior.valid())
    }
    private suspend fun delivery(store:G0HouseholdCountStore,http:NextSyncHttp,saved:G0HouseholdCountStore.Saved)=
        requireNotNull(store.send(http,saved.requestId))
    private suspend fun accept(store:G0HouseholdCountStore,http:NextSyncHttp,saved:G0HouseholdCountStore.Saved):NextCoreDelivery<NextSyncPushResponse> {
        val sent=delivery(store,http,saved)
        assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(sent))
        return sent
    }
    private suspend fun terminal(store:G0HouseholdCountStore,http:NextSyncHttp,total:Int,
        deliveries:List<NextCoreDelivery<NextSyncPushResponse>>) {
        val result=householdView(store)
        assertTrue(result.valid())
        assertEquals(total,pageRowCount("completions"));assertEquals(total,pageRowCount("next_request_origins"))
        assertEquals(total,pageRowCount("next_acceptances"));assertEquals(0,pageRowCount("sync_outbox"))
        val native=pageNativeState()
        reopenReference();sameHouseholdAsOriginal(result)
        deliveries.forEach {assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(it))}
        assertEquals(native,pageNativeState())
    }

    @Test fun oldUncoordinatedGuardRejectsKnownPromotionWhileRealSendWaitsAndCommitsOnce()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(8);val (http,server)=channel {successReply(it)}
        val store=openHousehold(http)
        val a=store.increase(seed.habits[0].uuid);val prior=householdView(store)
        val bCommand=store.newCommand(seed.habits[1].uuid)
        val bTrace=G0PhaseTrace(driverTiming=false);val sendTrace=G0PhaseTrace(driverTiming=false)
        PromotionPause(bTrace).use {pause->
            val b=async(Dispatchers.IO) {bTrace.run {store.increase(bCommand)}}
            try {
                pause.await();assertEquals(10,pageRowCount("completions"));assertEquals(2,pageRowCount("sync_outbox"))
                // A different native read connection sees B, so the barrier is after
                // real COMMIT, not merely after setTransactionSuccessful().
                val committed=pageNativeState();oldGuardRejects(store,prior)
                val sent=async(Dispatchers.IO) {sendTrace.run {delivery(store,http,a)}}
                recorded(sendTrace,"count_send_session_ready");stillWaiting(sent)
                assertTrue(server.requests.none {it.path.endsWith("/push")})
                assertEquals(committed,pageNativeState())
                pause.resume()
                val savedB=withTimeout(20_000) {b.await()}
                val sentA=withTimeout(20_000) {sent.await()}
                assertEquals(bCommand.requestId,savedB.requestId);assertEquals(1,pause.hits.get())
                fresh(bTrace);fresh(sendTrace)
                assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(sentA))
                val sentB=accept(store,http,savedB)
                assertEquals(2,server.requests.count {it.path.endsWith("/push")})
                terminal(store,http,10,listOf(sentA,sentB))
            } finally {pause.resume();withContext(NonCancellable) {b.join()}}
        }
    }

    @Test fun sendResponseAuthenticationAlsoWaitsForAConcurrentKnownPromotion()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(8)
        val pushed=CountDownLatch(1);val httpRelease=CountDownLatch(1)
        val (http,server)=channel {input->
            if(input.path.endsWith("/push")) {
                pushed.countDown();check(httpRelease.await(20,TimeUnit.SECONDS)) {"G0_PROMOTION_HTTP_RELEASE_TIMEOUT"}
            }
            successReply(input)
        }
        val store=openHousehold(http);val a=store.increase(seed.habits[0].uuid)
        val bCommand=store.newCommand(seed.habits[1].uuid)
        val sendTrace=G0PhaseTrace(driverTiming=false);val bTrace=G0PhaseTrace(driverTiming=false)
        val sent=async(Dispatchers.IO) {sendTrace.run {delivery(store,http,a)}}
        try {
            assertTrue(withContext(Dispatchers.IO) {pushed.await(15,TimeUnit.SECONDS)})
            val prior=householdView(store)
            PromotionPause(bTrace).use {pause->
                val b=async(Dispatchers.IO) {bTrace.run {store.increase(bCommand)}}
                try {
                    pause.await();oldGuardRejects(store,prior);val committed=pageNativeState()
                    httpRelease.countDown();recorded(sendTrace,"count_send_push");stillWaiting(sent)
                    assertEquals(committed,pageNativeState());assertEquals(1,server.requests.count {it.path.endsWith("/push")})
                    pause.resume()
                    val savedB=withTimeout(20_000) {b.await()}
                    val sentA=withTimeout(20_000) {sent.await()}
                    assertEquals(1,pause.hits.get());fresh(sendTrace);fresh(bTrace)
                    assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(sentA))
                    val sentB=accept(store,http,savedB)
                    assertEquals(2,server.requests.count {it.path.endsWith("/push")})
                    terminal(store,http,10,listOf(sentA,sentB))
                } finally {pause.resume();withContext(NonCancellable) {b.join()}}
            }
        } finally {httpRelease.countDown();withContext(NonCancellable) {sent.join()}}
    }

    @Test fun ackAndFreshCommandIdentityWaitForPromotionWithoutNestedOwnerLockOrCachedAuth()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(8);val (http,server)=channel {successReply(it)}
        val store=openHousehold(http);val a=store.increase(seed.habits[0].uuid);val sentA=delivery(store,http,a)
        val bCommand=store.newCommand(seed.habits[1].uuid);val prior=householdView(store)
        val bTrace=G0PhaseTrace(driverTiming=false);val ackTrace=G0PhaseTrace(driverTiming=false)
        val cTrace=G0PhaseTrace(driverTiming=false)
        PromotionPause(bTrace).use {pause->
            val b=async(Dispatchers.IO) {bTrace.run {store.increase(bCommand)}}
            try {
                pause.await();oldGuardRejects(store,prior)
                val native=pageNativeState();val decrypts=decryptCount.get()
                // This synchronous API allocates only immutable identity. It cannot
                // block the UI on the owner or grant a write from half-promoted state.
                val cCommand=store.newCommand(seed.habits[0].uuid)
                assertEquals(seed.habits[0].uuid,cCommand.habitUuid)
                assertEquals(decrypts,decryptCount.get());assertEquals(native,pageNativeState())
                // UNDISPATCHED drives these no-network entries to the occupied
                // account Mutex before returning control to the test.
                val ack=async(start=CoroutineStart.UNDISPATCHED) {ackTrace.run {store.acknowledge(sentA)}}
                val c=async(start=CoroutineStart.UNDISPATCHED) {cTrace.run {store.increase(cCommand)}}
                stillWaiting(ack);stillWaiting(c)
                assertEquals(0,calls(ackTrace,"decrypt_access"));assertEquals(0,calls(cTrace,"decrypt_access"))
                assertEquals(native,pageNativeState());pause.resume()
                val savedB=withTimeout(20_000) {b.await()}
                assertEquals(NextOperationAcceptance.COMMITTED,withTimeout(20_000) {ack.await()})
                val savedC=withTimeout(20_000) {c.await()}
                assertEquals(cCommand.requestId,savedC.requestId);fresh(ackTrace);fresh(cTrace);fresh(bTrace)
                val sentB=accept(store,http,savedB);val sentC=accept(store,http,savedC)
                assertEquals(3,server.requests.count {it.path.endsWith("/push")})
                val read=G0PhaseTrace(driverTiming=false)
                val result=withTimeout(20_000) {read.run {householdView(store)}}
                fresh(read);assertEquals(2L,result.cards.getValue(seed.habits[0].uuid).history.todayQuantity)
                assertEquals(1L,result.cards.getValue(seed.habits[1].uuid).history.todayQuantity)
                terminal(store,http,11,listOf(sentA,sentB,sentC))
            } finally {pause.resume();withContext(NonCancellable) {b.join()}}
        }
    }

    @Test fun authorityReservationWhileAContenderWaitsDoesNotReplaceItsLeaseOrAcceptAnAck()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(8);val (http,_)=channel {successReply(it)}
        val store=openHousehold(http);val a=store.increase(seed.habits[0].uuid);val sentA=delivery(store,http,a)
        val bCommand=store.newCommand(seed.habits[1].uuid);val bTrace=G0PhaseTrace(driverTiming=false)
        PromotionPause(bTrace).use {pause->
            val b=async(Dispatchers.IO) {bTrace.run {store.increase(bCommand)}}
            try {
                pause.await();val committed=pageNativeState()
                val ack=async(start=CoroutineStart.UNDISPATCHED) {runCatching {store.acknowledge(sentA)}}
                stillWaiting(ack)
                val reserved=CompletableDeferred<Unit>();authority.onTransitionForTest={reserved.complete(Unit)}
                val change=async(Dispatchers.IO) {tokens.saveDeviceRegistration(id(4),caps-"facts.append",true,2)}
                try {
                    withTimeout(15_000) {reserved.await()}
                    assertFalse(change.isCompleted);assertFalse(ack.isCompleted)
                    assertEquals(committed,pageNativeState());pause.resume()
                    val savedB=withTimeout(20_000) {b.await()}
                    assertEquals(bCommand.requestId,savedB.requestId)
                    val denied=withTimeout(20_000) {ack.await()}.exceptionOrNull()
                    assertTrue("Reservation must deny queued ACK: $denied",denied is G0AuthorityOwner.Denied)
                    withTimeout(20_000) {change.await()}
                    assertEquals(committed,pageNativeState());assertEquals(8,pageRowCount("next_acceptances"))
                    assertEquals(2,pageRowCount("sync_outbox"));assertEquals(10,pageRowCount("completions"))
                    assertTrue(runCatching {householdView(store)}.isFailure)
                } finally {pause.resume();authority.onTransitionForTest={};withContext(NonCancellable) {change.join()}}
                // New authority and an original unsealed reader validate the durable
                // known B independently; the rejected ACK wrote nothing.
                tokens.saveDeviceRegistration(id(4),caps,true,3);reopenReference()
                val native=pageNativeState();val original=readHome()
                assertEquals(10,original.completions.size);assertEquals(native,pageNativeState())
            } finally {pause.resume();withContext(NonCancellable) {b.join()}}
        }
    }

    @Test fun unknownActualEndNeverReachesPromotionProbeAndQueuedWriteStillRequiresRecovery()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(8);val (http,_)=channel {successReply(it)}
        val store=openHousehold(http);val a=store.increase(seed.habits[0].uuid);val sentA=accept(store,http,a)
        val command=store.newCommand(seed.habits[1].uuid);val nextCommand=store.newCommand(seed.habits[0].uuid)
        val trace=G0PhaseTrace(driverTiming=false);val queuedTrace=G0PhaseTrace(driverTiming=false)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        store.beforeFinalForTest={if(G0PhaseTrace.current.get()===trace) {entered.complete(Unit);release.await()}}
        endFaults.failAfterNextEndIn(trace)
        PromotionPause(trace).use {pause->
            val write=async(Dispatchers.IO) {runCatching {trace.run {store.increase(command)}}}
            try {
                withTimeout(15_000) {entered.await()}
                val queued=async(start=CoroutineStart.UNDISPATCHED) {runCatching {queuedTrace.run {store.increase(nextCommand)}}}
                stillWaiting(queued);assertEquals(0,calls(queuedTrace,"decrypt_access"))
                release.complete(Unit)
                assertEquals("G0_COMMIT_RESULT_LOST",withTimeout(20_000) {write.await()}.exceptionOrNull()?.message)
                assertEquals("G0_COUNT_RECOVERY_REQUIRED",withTimeout(20_000) {queued.await()}.exceptionOrNull()?.message)
                assertEquals(0,pause.hits.get());assertFalse(pause.entered.isCompleted)
                assertEquals("G0_COUNT_RECOVERY_REQUIRED",runCatching {store.newCommand(seed.habits[0].uuid)}.exceptionOrNull()?.message)
                assertEquals(10,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
                assertEquals(9,pageRowCount("next_acceptances"))
                reopenReference();val recovered=openHousehold(http);val native=pageNativeState()
                val found=requireNotNull(recovered.recover(command))
                assertEquals(command.requestId,found.requestId);assertEquals(command.factUuid,found.factUuid)
                assertNull(recovered.recover(nextCommand));assertEquals(native,pageNativeState())
                assertTrue(runCatching {recovered.increase(command)}.isFailure);assertEquals(native,pageNativeState())
                val sentB=accept(recovered,http,found)
                terminal(recovered,http,10,listOf(sentA,sentB))
            } finally {release.complete(Unit);store.beforeFinalForTest={};withContext(NonCancellable) {write.join()}}
        }
    }

    @Test fun queuedWriteAndAckKeepTheirPreWaitAuthorityEvenAfterPreferencesReturnToIdenticalValues()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(8);val (http,server)=channel {successReply(it)}
        val store=openHousehold(http);val a=store.increase(seed.habits[0].uuid);val sentA=delivery(store,http,a)
        for(acknowledge in listOf(false,true)) {
            val command=store.newCommand(seed.habits[1].uuid)
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val trace=G0PhaseTrace(driverTiming=false);val before=pageNativeState()
            // Ordinary owner contention, without a final permit: the authority ABA
            // can finish while the business operation is still awaiting the mutex.
            val holder=async(Dispatchers.IO) {sessions.exclusive {entered.complete(Unit);release.await()}}
            try {
                withTimeout(15_000) {entered.await()}
                val pending=async(start=CoroutineStart.UNDISPATCHED) {runCatching {trace.run {
                    if(acknowledge)store.acknowledge(sentA) else store.increase(command)
                }}}
                stillWaiting(pending);assertEquals(0,calls(trace,"decrypt_access"))
                val original=dataStore.data.first()
                dataStore.edit {it[stringPreferencesKey("sync_device_capability_revision")]="2"}
                dataStore.updateData {original};assertEquals(original,dataStore.data.first())
                assertFalse(pending.isCompleted);assertEquals(before,pageNativeState())
                release.complete(Unit);withTimeout(20_000) {holder.await()}
                val rejected=withTimeout(20_000) {pending.await()}.exceptionOrNull()
                assertTrue("Queued ${if(acknowledge) "ACK" else "write"} must retain its pre-wait lease: $rejected",
                    rejected is G0AuthorityOwner.Denied)
                assertEquals(0,calls(trace,"decrypt_access"))
                assertEquals(before,pageNativeState());assertEquals(8,pageRowCount("next_acceptances"))
                assertEquals(1,pageRowCount("sync_outbox"));assertEquals(9,pageRowCount("completions"))
            } finally {release.complete(Unit);withContext(NonCancellable) {holder.join()}}
        }
        // This is a separate, newly admitted call after both rejected operations.
        // It cannot turn either old queued lease into a success or duplicate A.
        assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(sentA))
        assertEquals(1,server.requests.count {it.path.endsWith("/push")})
        terminal(store,http,9,listOf(sentA))
    }
}
