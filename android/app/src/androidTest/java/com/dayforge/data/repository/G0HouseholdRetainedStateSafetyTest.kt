package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Normal-use safety: one continuous account/store/connection lifetime, not a cold
 * reopen for every operation. Eight facts are made and accepted by the original
 * implementation across two objects and four past days. Hooks below are deliberate
 * safety barriers, never latency samples. This does not install a production page,
 * extend capacity, or reuse authentication/publication merely because data is warm.
 */
@RunWith(AndroidJUnit4::class)
class G0HouseholdRetainedStateSafetyTest:G0HouseholdHistoryFixture() {
    private inner class Harness(http:NextSyncHttp) {
        val job=SupervisorJob()
        val control=CoroutineScope(job+Dispatchers.IO)
        val creates=AtomicInteger()
        val closes=AtomicInteger()
        val cold=G0PhaseTrace(driverTiming=false)
        lateinit var store:G0HouseholdCountStore
        private var database:Any?=null
        private var sqlite:Any?=null
        private var sealedGate:G0DomainSqlGate?=null
        val connection=G0CountConnectionOwner {closes.incrementAndGet();db.close()}
        val runtime=G0HouseholdCountRuntime(control,connection,authority,tokens,{domainGate.changeSequence},http) {
            check(creates.incrementAndGet()==1) {"G0_RETAINED_UNEXPECTED_COLD_REBUILD"}
            cold.run {coldHousehold(http)}.also {
                store=it;database=db;sqlite=db.openHelper.writableDatabase;sealedGate=domainGate
            }
        }
        suspend fun open():G0HouseholdCountPage {
            assertTrue(runtime.openPage())
            return householdPage(runtime).also {ready(it)}
        }
        suspend fun leave(page:G0HouseholdCountPage) {
            runtime.leavePage();runtime.awaitPageTransition()
            assertEquals(G0HouseholdCountRuntime.Phase.AWAY,runtime.state.value.phase)
            assertNull(runtime.state.value.page);assertFalse(page.live());assertNull(page.state.value.view)
            assertFalse(page.increment(currentHousehold.first().uuid))
            assertContinuous()
        }
        suspend fun synced()=withTimeout(20_000) {runtime.awaitSynced()}
        fun assertContinuous() {
            assertEquals(1,creates.get());assertEquals(1,runtime.coldStarts)
            assertEquals(0,closes.get());assertTrue(connection.busy())
            assertSame(database,db);assertSame(sqlite,db.openHelper.writableDatabase)
            assertSame(sealedGate,domainGate)
            assertEquals(2,cold.count("count_complete_history"))
            assertEquals(1,cold.count("reference_builder"));assertEquals(1,cold.count("reference_open"))
        }
        suspend fun close()=withContext(NonCancellable) {runtime.shutdown();job.cancelAndJoin()}
    }

    private suspend fun withRuntime(http:NextSyncHttp,body:suspend (Harness)->Unit) {
        val h=Harness(http)
        try {body(h)} finally {h.close()}
    }
    private suspend fun ready(page:G0HouseholdCountPage):G0HouseholdCountStore.View=withTimeout(20_000) {
        requireNotNull(page.state.first {it.view?.valid()==true}.view)
    }
    private fun spans(trace:G0PhaseTrace)=synchronized(trace.spans) {trace.spans.toList()}
    private fun noColdWork(trace:G0PhaseTrace) {
        val observed=spans(trace)
        assertEquals(0,observed.count {it.phase=="count_complete_history"})
        assertFalse(observed.any {it.phase.startsWith("reference_")})
    }
    private fun freshRead(trace:G0PhaseTrace) {
        // The actual cipher is wrapped by G0SealedDomainFixture; this is not a
        // metadata read count. Entry AND final publication must decrypt access.
        val observed=spans(trace)
        assertTrue(observed.count {it.phase=="decrypt_access"}>=2)
        assertEquals(0,observed.count {it.phase=="decrypt_refresh"})
        assertEquals(0,observed.count {it.phase=="decrypt_unclassified"})
        assertEquals(0,observed.count {it.phase=="count_complete_history"})
        assertFalse(observed.any {it.phase.startsWith("reference_")})
    }
    private suspend fun save(page:G0HouseholdCountPage,id:String):G0HouseholdCountStore.Saved {
        page.trace=G0PhaseTrace(driverTiming=false)
        assertTrue(page.increment(id))
        val request=requireNotNull(page.command).requestId
        withTimeout(20_000) {page.state.first {
            it.phase==G0HouseholdCountPage.Phase.RESULT && page.saved?.requestId==request
        }}
        page.settled();assertNull(page.error)
        noColdWork(requireNotNull(page.trace))
        return requireNotNull(page.saved)
    }
    private suspend fun terminal(h:Harness,http:NextSyncHttp,view:G0HouseholdCountStore.View,total:Int) {
        h.synced();h.assertContinuous()
        assertEquals(total,pageRowCount("completions"));assertEquals(total,pageRowCount("next_request_origins"))
        assertEquals(total,pageRowCount("next_acceptances"));assertEquals(0,pageRowCount("sync_outbox"))
        assertEquals(0,h.runtime.pendingCount)
        val before=pageNativeState()
        val attempts=h.runtime.workerAttempts
        assertEquals(attempts.size,attempts.map {it.requestId}.distinct().size)
        attempts.forEach {
            assertEquals(NextOperationAcceptance.COMMITTED,it.accepted)
            assertTrue(it.ack.count("decrypt_access")>=2);noColdWork(it.send);noColdWork(it.ack)
        }
        val deliveries=attempts.map {requireNotNull(it.delivery)}
        assertTrue(h.runtime.shutdown());assertEquals(1,h.closes.get());assertFalse(h.connection.busy())
        reopenReference()
        // Independent production readers and original ACK replay, after the candidate
        // has drained. Neither side obtains its answer from the retained store.
        sameHouseholdAsOriginal(view)
        deliveries.forEach {assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(it))}
        assertEquals(before,pageNativeState())
    }

    @Test fun completedHistoryAndRealWritesSurviveTwoWarmReentriesWithoutRebuildingTrust()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(8);val (http,server)=channel {successReply(it)}
        withRuntime(http) {h->
            var page=h.open();val originalStore=h.store
            assertEquals(8,ready(page).cards.values.sumOf {it.history.completions.size})
            val saved=mutableListOf<G0HouseholdCountStore.Saved>()
            repeat(3) {index->
                val value=save(page,seed.habits[index%2].uuid);saved+=value
                h.synced();val result=ready(page)
                assertEquals(value.factUuid,result.cards.getValue(value.habitUuid).followUp.claim.completionUuid)
                h.assertContinuous();assertSame(originalStore,h.store)
                if(index<2) {
                    val old=page;val before=pageNativeState();val decrypts=decryptCount.get()
                    h.leave(old);page=h.open()
                    assertNotSame(old,page);assertSame(originalStore,h.store)
                    assertTrue(decryptCount.get()>=decrypts+2)
                    freshRead(h.runtime.openTraces.last())
                    assertEquals(before,pageNativeState())
                }
            }
            assertEquals(3,h.runtime.openTraces.size);h.runtime.openTraces.forEach(::freshRead)
            assertEquals(saved.map {it.requestId}.toSet(),h.runtime.workerAttempts.map {it.requestId}.toSet())
            assertEquals(3,server.requests.count {it.path.endsWith("/push")})
            val result=ready(page)
            assertEquals(2L,result.cards.getValue(seed.habits[0].uuid).history.todayQuantity)
            assertEquals(1L,result.cards.getValue(seed.habits[1].uuid).history.todayQuantity)
            terminal(h,http,result,11)
        }
    }

    @Test fun backgroundAckInvalidatesTheOldCommonViewUntilAFreshReadRebasesIt()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(8);val (http,_)=channel {successReply(it)}
        withRuntime(http) {h->
            val ackEntered=CompletableDeferred<Unit>();val ackRelease=CompletableDeferred<Unit>()
            val refreshEntered=CompletableDeferred<Unit>();val refreshRelease=CompletableDeferred<Unit>()
            h.runtime.beforeAcceptForTest={ackEntered.complete(Unit);ackRelease.await()}
            try {
                val page=h.open();val saved=save(page,seed.habits[0].uuid)
                withTimeout(20_000) {ackEntered.await()}
                val prior=ready(page);val nativeBeforeAck=pageNativeState()
                val refresh=G0PhaseTrace(driverTiming=false);page.refreshTrace=refresh
                page.beforeRefreshForTest={refreshEntered.complete(Unit);refreshRelease.await()}
                ackRelease.complete(Unit);withTimeout(20_000) {refreshEntered.await()};h.synced()
                assertFalse(prior.valid());assertFalse(h.runtime.renderable())
                assertEquals(9,pageRowCount("completions"));assertEquals(9,pageRowCount("next_acceptances"))
                assertEquals(0,pageRowCount("sync_outbox"));assertNotEquals(nativeBeforeAck,pageNativeState())
                assertSame(prior,page.state.value.view)
                refreshRelease.complete(Unit)
                val result=ready(page)
                assertNotSame(prior,result);assertTrue(h.runtime.renderable())
                assertTrue(result.proofRevision>prior.proofRevision)
                assertEquals(prior.presentationRevision,result.presentationRevision)
                var storedFactChanged=false
                for(uuid in prior.cards.keys) {
                    val before=prior.cards.getValue(uuid).history
                    val after=result.cards.getValue(uuid).history
                    assertEquals(before.completions.map {it.uuid}.toSet(),after.completions.map {it.uuid}.toSet())
                    for(fact in after.completions) {
                        val original=before.completions.single {it.uuid==fact.uuid}
                        // ACK may normalize storage createdAt. Every other retained
                        // fact field and every displayed quantity/policy must agree.
                        assertEquals(original.copy(createdAt=fact.createdAt),fact)
                        if(original!=fact)storedFactChanged=true
                    }
                    assertEquals(before,after.copy(completions=before.completions))
                }
                assertEquals(prior.factRevision+(if(storedFactChanged)1L else 0L),result.factRevision)
                assertEquals(saved.factUuid,result.cards.getValue(saved.habitUuid).followUp.claim.completionUuid)
                freshRead(refresh);h.assertContinuous()
                terminal(h,http,result,9)
            } finally {ackRelease.complete(Unit);refreshRelease.complete(Unit)}
        }
    }

    @Test fun cancellingOnlyAWarmOpeningDiscardsItsPrivatePageButKeepsTheLiveStore()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(8);val (http,_)=channel {successReply(it)}
        withRuntime(http) {h->
            val old=h.open();save(old,seed.habits[0].uuid);h.synced();ready(old);h.leave(old)
            val store=h.store;val before=pageNativeState()
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            h.runtime.beforePublicationForTest={entered.complete(Unit);release.await()}
            try {
                val published=h.runtime.events.count {it.phase==G0HouseholdCountRuntime.Phase.OPEN}
                assertTrue(h.runtime.openPage());withTimeout(20_000) {entered.await()}
                assertNull(h.runtime.state.value.page);assertFalse(h.runtime.renderable())
                h.runtime.leavePage();release.complete(Unit);h.runtime.awaitPageTransition()
                assertEquals(G0HouseholdCountRuntime.Phase.AWAY,h.runtime.state.value.phase)
                assertEquals(published,h.runtime.events.count {it.phase==G0HouseholdCountRuntime.Phase.OPEN})
                val abandoned=requireNotNull(h.runtime.retiredPage)
                assertNotSame(old,abandoned);assertFalse(abandoned.live());assertNull(abandoned.state.value.view)
                assertFalse(abandoned.increment(seed.habits[1].uuid));assertNull(abandoned.saved)
                freshRead(h.runtime.openTraces.last());h.assertContinuous()
                h.runtime.beforePublicationForTest={}
                val next=h.open();assertNotSame(abandoned,next);assertSame(store,h.store)
                freshRead(h.runtime.openTraces.last());assertEquals(before,pageNativeState())
                assertEquals(3,h.runtime.openTraces.size)
                terminal(h,http,ready(next),9)
            } finally {release.complete(Unit);h.runtime.beforePublicationForTest={}}
        }
    }

    @Test fun revocationBetweenFreshWarmReadAndPublicationCannotBorrowTheOldCertificate()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(8);val (http,server)=channel {successReply(it)}
        withRuntime(http) {h->
            val old=h.open();val oldView=ready(old);h.leave(old)
            val before=pageNativeState();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            h.runtime.beforePublicationForTest={withContext(NonCancellable) {entered.complete(Unit);release.await()}}
            try {
                val published=h.runtime.events.count {it.phase==G0HouseholdCountRuntime.Phase.OPEN}
                assertTrue(h.runtime.openPage());withTimeout(20_000) {entered.await()}
                freshRead(h.runtime.openTraces.last())
                withTimeout(10_000) {tokens.saveDeviceRegistration(id(4),caps-"facts.append",true,2)}
                assertFalse(oldView.valid());assertFalse(h.runtime.renderable());assertNull(h.runtime.state.value.page)
                assertFalse(old.increment(seed.habits[0].uuid));assertFalse(h.runtime.openPage())
                assertTrue(h.connection.busy());assertEquals(0,h.closes.get())
                release.complete(Unit);assertTrue(h.runtime.shutdown())
                assertEquals(published,h.runtime.events.count {it.phase==G0HouseholdCountRuntime.Phase.OPEN})
                assertTrue(h.runtime.drained);assertEquals(1,h.closes.get());assertFalse(h.connection.busy())
                assertEquals(1,h.creates.get());assertEquals(1,h.runtime.coldStarts)
                assertEquals(2,h.cold.count("count_complete_history"))
                assertEquals(before,pageNativeState());assertTrue(server.requests.isEmpty())
            } finally {release.complete(Unit);h.runtime.beforePublicationForTest={}}
        }
    }

    @Test fun outsideConnectionChangesForceRecoveryBeforeWarmPublicationAndOriginalReaderAlsoRejects()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(8);val (http,server)=channel {successReply(it)}
        withRuntime(http) {h->
            val old=h.open();val prior=ready(old);h.leave(old)
            val opens=h.runtime.events.count {it.phase==G0HouseholdCountRuntime.Phase.OPEN}
            mutateHouseholdFile("UPDATE next_request_origins SET sourceHash=? WHERE requestId=?",
                arrayOf("0".repeat(64),seed.requestIds.first()))
            val damaged=pageNativeState()
            assertTrue(h.runtime.openPage())
            withTimeout(20_000) {h.runtime.state.first {it.phase==G0HouseholdCountRuntime.Phase.RECOVERY}}
            assertTrue(h.runtime.shutdown())
            assertFalse(prior.valid());assertFalse(h.runtime.renderable());assertNull(h.runtime.state.value.page)
            assertEquals(opens,h.runtime.events.count {it.phase==G0HouseholdCountRuntime.Phase.OPEN})
            assertFalse(h.runtime.openPage());assertFalse(old.increment(seed.habits[1].uuid))
            assertEquals(1,h.creates.get());assertEquals(1,h.runtime.coldStarts)
            assertEquals(2,h.cold.count("count_complete_history"));assertEquals(1,h.closes.get())
            assertTrue(h.runtime.drained);assertTrue(server.requests.isEmpty())
            assertEquals(damaged,pageNativeState())
            val failure=requireNotNull(h.runtime.error)
            assertTrue("Expected external-change rejection, got ${failure.javaClass.name}: ${failure.message}",
                causes(failure).any {it.message=="G0_DOMAIN_EXTERNAL_CHANGE"})
            reopenReference()
            val rejected=runCatching {CountHistoryReader(db,tokens,sessions).read(seed.habits.first(),seed.today)}.exceptionOrNull()
            assertNotNull("The independent original history reader must reject the same damaged source",rejected)
            // count_days retains the original source row hash; the original
            // day reader's exact failed requirement is independent of the new gate.
            assertTrue(rejected is IllegalArgumentException);assertEquals("Failed requirement.",rejected?.message)
            assertFalse(causes(requireNotNull(rejected)).any {it is G0DomainSqlGate.Denied})
            assertEquals(damaged,pageNativeState())
        }
    }

    private fun causes(error:Throwable):List<Throwable> {
        val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable,Boolean>())
        val result=mutableListOf<Throwable>()
        fun visit(value:Throwable) {
            if(!seen.add(value))return
            result+=value;value.cause?.let(::visit);value.suppressed.forEach(::visit)
        }
        visit(error);return result
    }
}
