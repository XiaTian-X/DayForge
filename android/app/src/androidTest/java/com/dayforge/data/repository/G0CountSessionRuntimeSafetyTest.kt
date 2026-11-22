package com.dayforge.data.repository

import androidx.activity.compose.setContent
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.local.TokenCipher
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

abstract class G0CountSessionRuntimeFixture:G0CountLoadingFixture() {
    internal fun installRuntime(runtime:G0CountSessionRuntime,seen:G0ScreenObservation,pageSeen:(G0ShortCountPage)->G0ScreenObservation) {
        activity.scenario.onActivity {it.setContent {G0CountSessionScreen(runtime,seen,pageSeen)}}
    }
    internal suspend fun runtimePhase(runtime:G0CountSessionRuntime,phase:G0CountSessionRuntime.Phase)=
        withTimeout(30_000) {runtime.state.first {it.phase==phase}}
    internal suspend fun runtimePage(runtime:G0CountSessionRuntime)=
        requireNotNull(runtimePhase(runtime,G0CountSessionRuntime.Phase.OPEN).page)
}

/** One retained, bounded G0 owner. Every save is the real count/outbox command,
 * every send is real HTTP, and reentry is a fresh authenticated read, not cached UI. */
@RunWith(AndroidJUnit4::class)
class G0CountSessionRuntimeSafetyTest:G0CountSessionRuntimeFixture() {
    private class RefreshPause(val trace:G0PhaseTrace) {
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        val decrypts=AtomicInteger()
        @Volatile var decryptThreadInTransaction=false
    }
    private val refreshPause=AtomicReference<RefreshPause?>()
    override fun tokenCipher():TokenCipher {
        val actual=super.tokenCipher()
        return object:TokenCipher {
            override fun encrypt(value:String)=actual.encrypt(value)
            override fun decrypt(value:String):String? {
                val result=actual.decrypt(value)
                val pause=refreshPause.get();val trace=G0PhaseTrace.current.get()
                if(pause!=null && trace!=null && trace===pause.trace && trace.role(value)=="access" && pause.decrypts.incrementAndGet()==2) {
                    // TokenManager switches final authentication to Dispatchers.IO.
                    // This thread-local diagnostic cannot attest to the Room transaction;
                    // the test below checks the same trace's real driver BEGIN/END spans.
                    pause.decryptThreadInTransaction=db.inTransaction();pause.entered.countDown()
                    check(pause.release.await(15,TimeUnit.SECONDS)) {"G0_SESSION_REFRESH_DECRYPT_RELEASE_TIMEOUT"}
                }
                return result
            }
        }
    }
    private inner class Harness(http:NextSyncHttp) {
        val job=SupervisorJob()
        val control=CoroutineScope(job+Dispatchers.IO)
        val closes=AtomicInteger()
        lateinit var store:G0ShortCountStore
        var beforeCold:suspend ()->Unit={}
        var beforeClose:()->Unit={}
        val connection=G0CountConnectionOwner {beforeClose();closes.incrementAndGet();db.close()}
        val runtime=G0CountSessionRuntime(control,connection,authority,tokens,{domainGate.changeSequence},http) {
            beforeCold();coldCount(http).also {store=it}
        }
        suspend fun open():G0ShortCountPage {
            assertTrue(runtime.openPage());val page=runtimePage(runtime)
            withTimeout(20_000) {page.state.first {it.view?.valid()==true}}
            return page
        }
        suspend fun away() {
            runtime.leavePage();runtime.awaitPageTransition()
            assertEquals(G0CountSessionRuntime.Phase.AWAY,runtime.state.value.phase)
        }
        suspend fun idle()=withTimeout(60_000) {runtime.awaitSynced()}
        suspend fun cleanup()=withContext(NonCancellable) {
            runtime.shutdown();job.cancelAndJoin()
        }
    }
    private suspend fun withRuntime(http:NextSyncHttp,body:suspend (Harness)->Unit) {
        val h=Harness(http)
        try {body(h)} finally {h.cleanup()}
    }
    private suspend fun saved(page:G0ShortCountPage):G0ShortCountStore.Saved {
        assertTrue(page.increment());phase(page,G0ShortCountPage.Phase.RESULT);page.settled()
        assertNull(page.error);return requireNotNull(page.saved)
    }
    private suspend fun terminalFailure(h:Harness) {
        withTimeout(20_000) {h.runtime.state.first {
            it.phase in setOf(G0CountSessionRuntime.Phase.RECOVERY,G0CountSessionRuntime.Phase.CLOSED)
        }}
        assertFalse(h.runtime.renderable());assertFalse(h.runtime.openPage())
    }
    private suspend fun changeAndRestore() {
        val prior=dataStore.data.first()
        dataStore.edit {it[stringPreferencesKey("sync_device_capability_revision")]="2"}
        dataStore.updateData {prior};assertEquals(prior,dataStore.data.first())
    }

    @Test fun constructionWithoutOpenDoesNotBuildReadDecryptOrStartNetwork()=runBlocking<Unit> {
        seedAccepted(1);val (http,server)=channel {successReply(it)}
        val before=pageNativeState();val decrypts=decryptCount.get()
        withRuntime(http) {h->
            yield()
            assertEquals(G0CountSessionRuntime.Phase.NEW,h.runtime.state.value.phase)
            assertEquals(0,h.runtime.coldStarts);assertEquals(0,h.runtime.pendingCount)
            assertTrue(h.runtime.workerAttempts.isEmpty());assertFalse(h.connection.busy())
            assertEquals(decrypts,decryptCount.get());assertTrue(server.requests.isEmpty())
            assertEquals(before,pageNativeState());assertTrue(h.runtime.shutdown())
            assertEquals(0,h.closes.get());assertEquals(0,h.runtime.coldStarts)
        }
    }

    @Test fun actualSaveHandsOffBeforePageExitThenAckAndFreshReentryShareOneColdOwner()=runBlocking<Unit> {
        seedAccepted(1);val (http,server)=channel {successReply(it)}
        withRuntime(http) {h->
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val seen=G0ScreenObservation();val observations=ConcurrentHashMap<G0ShortCountPage,G0ScreenObservation>()
            fun pageSeen(page:G0ShortCountPage)=observations.computeIfAbsent(page) {G0ScreenObservation()}
            try {
                installRuntime(h.runtime,seen,::pageSeen);seen.await(h.runtime.state.value.version);seen.tap("open")
                val page=runtimePage(h.runtime);pageSeen(page).awaitAny {listOf(page.state.value.version)}
                page.afterCommitForTest={entered.complete(Unit);release.await()}
                pageSeen(page).tap("increment");withTimeout(20_000) {entered.await()}
                val committed=requireNotNull(page.saved);assertTrue(page.commandTiming!!.knownCommitAt>0)
                assertEquals(2,pageRowCount("completions"))
                // This screen exit occurs after the real commit but before its result read.
                seen.tap("leave")
                assertFalse(page.live());assertNull(page.state.value.view)
                assertEquals(G0CountSessionRuntime.Phase.LEAVING,h.runtime.state.value.phase)
                assertTrue(h.connection.busy());assertEquals(0,h.closes.get())
                release.complete(Unit);h.runtime.awaitPageTransition()
                assertEquals(G0CountSessionRuntime.Phase.AWAY,h.runtime.state.value.phase)
                h.idle();assertEquals(0,pageRowCount("sync_outbox"))
                assertEquals(listOf(committed.requestId),h.runtime.workerAttempts.map {it.requestId})
                val before=decryptCount.get();val again=h.open()
                assertNotSame(page,again)
                assertTrue(decryptCount.get()-before>=2);assertEquals(1,h.runtime.coldStarts)
                pageSeen(again).awaitAny {listOf(again.state.value.version)}
                requireVisiblePageText(setOf("卡片：2","首页：2 / 1000000"))
                val result=requireNotNull(again.state.value.view)
                assertEquals(2L,result.history.todayQuantity);assertNull(page.state.value.view)
                val terminal=pageNativeState();val delivery=requireNotNull(h.runtime.workerAttempts.single().delivery)
                assertEquals(1,server.requests.count {it.path.endsWith("/push")})
                assertTrue(h.runtime.shutdown());assertEquals(1,h.closes.get());assertFalse(h.connection.busy())
                reopenReference();sameAsOriginal(result)
                assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(delivery))
                assertEquals(terminal,pageNativeState())
            } finally {release.complete(Unit)}
        }
    }

    @Test fun leavingDuringPreparedWriteOrAdmissionReadWaitsWithoutLosingAcceptedIntent()=runBlocking<Unit> {
        seedAccepted(1);val image=storage.snapshotClosedDatabase()
        for(admission in listOf(false,true)) {
            freshGateForColdOpen();restoreForJourney(image);val (http,_)=channel {successReply(it)}
            withRuntime(http) {h->
                val page=h.open();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
                val syncRelease=CompletableDeferred<Unit>()
                val refreshRelease=CompletableDeferred<Unit>()
                h.runtime.beforeSyncBatchForTest={syncRelease.await()}
                try {
                    if(admission) {
                        // Freeze/send an already handed-off real command while the
                        // runtime batch waits. The source stays in its known pending set.
                        val prior=saved(page)
                        page.beforeRefreshForTest={refreshRelease.await()}
                        h.control.async {requireNotNull(h.store.send(http,prior.requestId))}.await()
                        assertFalse(page.state.value.view!!.valid())
                        page.beforeAdmissionReadForTest={entered.complete(Unit);release.await()}
                    } else h.store.afterPrepareForTest={entered.complete(Unit);release.await()}
                    val priorEvents=page.events.size
                    assertTrue(page.increment());withTimeout(20_000) {entered.await()}
                    if(admission)assertEquals(G0ShortCountPage.Phase.VERIFYING,page.state.value.phase)
                    val command=requireNotNull(page.command)
                    h.runtime.leavePage();assertFalse(page.live());assertNull(page.state.value.view)
                    assertEquals(G0CountSessionRuntime.Phase.LEAVING,h.runtime.state.value.phase)
                    val opens=h.runtime.openTraces.size
                    assertTrue(h.runtime.openPage());assertFalse(h.runtime.openPage())
                    assertEquals(G0CountSessionRuntime.Phase.LOADING,h.runtime.state.value.phase)
                    assertEquals(opens,h.runtime.openTraces.size);assertEquals(0,h.closes.get())
                    // detach requests a graceful observer stop. This test-only refresh
                    // barrier must also be released; departure must not cancel it.
                    release.complete(Unit);refreshRelease.complete(Unit)
                    h.runtime.awaitPageTransition();page.settled()
                    assertNull(page.error);assertEquals(command.requestId,page.saved!!.requestId)
                    assertEquals(G0ShortCountPage.Phase.CLOSED,page.state.value.phase)
                    assertNull(page.state.value.view);assertFalse(page.events.drop(priorEvents).any {it.phase==G0ShortCountPage.Phase.RESULT})
                    h.store.afterPrepareForTest={}
                    val reopened=runtimePage(h.runtime);assertNotSame(page,reopened);assertEquals(1,h.runtime.coldStarts)
                    assertEquals(if(admission)3L else 2L,reopened.state.value.view!!.history.todayQuantity)
                    syncRelease.complete(Unit);h.idle()
                    assertEquals(if(admission)3 else 2,pageRowCount("completions"))
                    assertEquals(0,pageRowCount("sync_outbox"))
                    assertEquals(if(admission)2 else 1,h.runtime.workerAttempts.map {it.requestId}.distinct().size)
                } finally {release.complete(Unit);syncRelease.complete(Unit);refreshRelease.complete(Unit);h.store.afterPrepareForTest={}}
            }
        }
    }

    @Test fun coldAndWarmPrivatePublicationCannotSurviveAuthorityAba()=runBlocking<Unit> {
        seedAccepted(1);val image=storage.snapshotClosedDatabase()
        for(warm in listOf(false,true)) {
            freshGateForColdOpen();restoreForJourney(image);val (http,_)=channel {successReply(it)}
            val before=pageNativeState()
            withRuntime(http) {h->
                if(warm) {h.open();h.away()}
                val priorOpens=h.runtime.events.count {it.phase==G0CountSessionRuntime.Phase.OPEN}
                val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
                h.runtime.beforePublicationForTest={withContext(NonCancellable) {entered.complete(Unit);release.await()}}
                try {
                    assertTrue(h.runtime.openPage());withTimeout(20_000) {entered.await()}
                    assertFalse(h.runtime.renderable());changeAndRestore()
                    release.complete(Unit);h.runtime.awaitPageTransition();terminalFailure(h)
                    assertEquals(priorOpens,h.runtime.events.count {it.phase==G0CountSessionRuntime.Phase.OPEN})
                    assertNull(h.runtime.state.value.page);assertEquals(before,pageNativeState())
                    assertEquals(1,h.runtime.coldStarts)
                } finally {release.complete(Unit)}
            }
        }
    }

    @Test fun finalFreshAuthorityFailureRollsBackAndPreventsWarmReentry()=runBlocking<Unit> {
        seedAccepted(1);val (http,server)=channel {successReply(it)}
        withRuntime(http) {h->
            val page=h.open();val before=pageNativeState()
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            h.store.beforeFinalForTest={entered.complete(Unit);release.await();failDecrypt=true}
            try {
                assertTrue(page.increment());withTimeout(20_000) {entered.await()}
                h.runtime.leavePage();assertTrue(h.runtime.openPage());assertFalse(h.runtime.openPage())
                assertEquals(G0CountSessionRuntime.Phase.LOADING,h.runtime.state.value.phase)
                release.complete(Unit);page.settled()
                assertNotNull(page.error);assertEquals(0L,page.commandTiming!!.knownCommitAt)
                h.runtime.awaitPageTransition();terminalFailure(h)
                assertEquals(1,h.runtime.openTraces.size)
                assertEquals(before,pageNativeState());assertTrue(h.runtime.workerAttempts.isEmpty())
                assertTrue(server.requests.none {it.path.endsWith("/push")})
            } finally {release.complete(Unit);failDecrypt=false;h.store.beforeFinalForTest={}}
        }
    }

    @Test fun externalWriteOrConnectionReplacementCannotReuseTheWarmCertificate()=runBlocking<Unit> {
        seedAccepted(1);val image=storage.snapshotClosedDatabase()
        for(replace in listOf(false,true)) {
            freshGateForColdOpen();restoreForJourney(image);val (http,_)=channel {successReply(it)}
            withRuntime(http) {h->
                val page=h.open();val old=requireNotNull(page.state.value.view);h.away()
                if(replace) {
                    val unchanged=pageNativeState()
                    // The sealed gate rejects Room's reconfiguration before this new
                    // connection can become an application SQL capability. Preserve that
                    // refusal, then prove the closed file really can be reopened read-only.
                    val refused=runCatching {storage.reopen()}.exceptionOrNull()
                    assertTrue("Sealed Room reconfiguration must be rejected: $refused",refused is G0DomainSqlGate.Denied)
                    val file=app.getDatabasePath("habit_database");val bytes=file.readBytes()
                    android.database.sqlite.SQLiteDatabase.openDatabase(file.path,null,
                        android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use {raw->
                        assertTrue(raw.isOpen);assertTrue(raw.isReadOnly)
                        raw.rawQuery("SELECT COUNT(*) FROM completions",null).use {cursor->
                            assertTrue(cursor.moveToFirst())
                            assertEquals(unchanged.getValue("completions").size,cursor.getInt(0))
                            assertFalse(cursor.moveToNext())
                        }
                    }
                    assertArrayEquals(bytes,file.readBytes());assertEquals(unchanged,pageNativeState())
                } else android.database.sqlite.SQLiteDatabase.openDatabase(
                    app.getDatabasePath("habit_database").path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
                    it.execSQL("UPDATE completions SET createdAt=createdAt+1 WHERE id=(SELECT MIN(id) FROM completions)")
                }
                val before=pageNativeState();assertTrue(h.runtime.openPage())
                terminalFailure(h);h.runtime.awaitPageTransition()
                assertFalse(old.valid());assertNull(h.runtime.state.value.page)
                assertEquals(before,pageNativeState());assertEquals(1,h.runtime.coldStarts)
                assertFalse(h.runtime.openPage())
            }
        }
    }

    @Test fun unknownLocalCommitRetainsItsIdentityAndCannotReopenOrOfferAnotherSave()=runBlocking<Unit> {
        seedAccepted(1);val (http,server)=channel {successReply(it)}
        withRuntime(http) {h->
            val page=h.open();val trace=G0PhaseTrace(driverTiming=false)
            page.trace=trace;endFaults.failAfterNextEndIn(trace)
            assertTrue(page.increment());page.settled()
            val command=requireNotNull(page.command)
            assertEquals("G0_COMMIT_RESULT_LOST",page.error?.message)
            assertEquals(0L,page.commandTiming!!.knownCommitAt);assertNull(page.saved)
            h.runtime.leavePage();h.runtime.awaitPageTransition();terminalFailure(h)
            assertEquals(command,h.runtime.retiredPage!!.command);assertFalse(page.increment())
            assertTrue(h.runtime.workerAttempts.isEmpty());assertTrue(server.requests.none {it.path.endsWith("/push")})
            assertEquals(2,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
            h.runtime.shutdown();reopenReference();val recovered=coldCount(http)
            val before=pageNativeState();val found=requireNotNull(recovered.recover(command))
            assertEquals(command.requestId,found.requestId);assertEquals(command.factUuid,found.factUuid)
            assertEquals(before,pageNativeState());assertEquals(2,pageRowCount("completions"))
        }
    }

    @Test fun unknownTransmissionCommitStopsBeforeHttpAndKeepsOriginalPendingIdentity()=runBlocking<Unit> {
        seedAccepted(1);val (http,server)=channel {successReply(it)}
        withRuntime(http) {h->
            val page=h.open();h.runtime.beforeSendForTest={endFaults.failAfterNextEndIn(it.send)}
            assertTrue(page.increment());terminalFailure(h);h.runtime.awaitPageTransition();page.settled()
            assertTrue(h.runtime.shutdown()) // Join the worker before checking its final failure.
            val attempt=h.runtime.workerAttempts.single();val command=requireNotNull(page.command)
            assertEquals(command.requestId,attempt.requestId);assertNull(attempt.delivery);assertNull(attempt.accepted)
            assertEquals("G0_COMMIT_RESULT_LOST",h.runtime.error?.message)
            assertTrue(server.requests.none {it.path.endsWith("/push")})
            assertEquals(2,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
            assertEquals(2,pageRowCount("next_transmissions"));assertEquals(1,pageRowCount("next_acceptances"))
            val before=pageNativeState();h.runtime.shutdown();assertEquals(before,pageNativeState())
            assertEquals(1,h.runtime.workerAttempts.size);assertFalse(h.runtime.openPage())
        }
    }

    @Test fun unknownAckCommitStopsAndOriginalDeliveryReplaysWithoutAnotherFact()=runBlocking<Unit> {
        seedAccepted(1);val (http,server)=channel {successReply(it)}
        withRuntime(http) {h->
            val page=h.open();h.runtime.beforeAcceptForTest={endFaults.failAfterNextEndIn(it.ack)}
            assertTrue(page.increment());terminalFailure(h);h.runtime.awaitPageTransition();page.settled()
            assertTrue(h.runtime.shutdown()) // Page transition completion does not mean ACK drained.
            val attempt=h.runtime.workerAttempts.single();val delivery=requireNotNull(attempt.delivery)
            assertEquals(page.command!!.requestId,attempt.requestId);assertNull(attempt.accepted)
            assertEquals("G0_COMMIT_RESULT_LOST",h.runtime.error?.message)
            assertEquals(2,pageRowCount("completions"));assertEquals(0,pageRowCount("sync_outbox"))
            assertEquals(2,pageRowCount("next_acceptances"));assertEquals(1,server.requests.count {it.path.endsWith("/push")})
            val before=pageNativeState();h.runtime.shutdown();reopenReference()
            assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(delivery))
            assertEquals(before,pageNativeState());assertEquals(1,h.runtime.workerAttempts.size)
        }
    }

    @Test fun nineRealSavesCrossWorkerCapacityAndReentryDoesNotLoseOrResendCommands()=runBlocking<Unit> {
        seedAccepted(1);val (http,server)=channel {successReply(it)}
        withRuntime(http) {h->
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            h.runtime.beforeSyncBatchForTest={entered.complete(Unit);release.await()}
            try {
                val page=h.open();val facts=mutableListOf<G0ShortCountStore.Saved>()
                repeat(9) {facts+=saved(page)}
                withTimeout(20_000) {entered.await()}
                assertEquals(9,pageRowCount("sync_outbox"));assertEquals(9,h.runtime.pendingCount)
                assertEquals(facts.map {it.requestId}.toSet(),h.runtime.knownPending)
                h.away();val next=h.open();assertEquals(10L,next.state.value.view!!.history.todayQuantity)
                assertEquals(9,h.runtime.pendingCount);assertEquals(1,h.runtime.coldStarts)
                release.complete(Unit);h.idle()
                assertEquals(facts.map {it.requestId},h.runtime.workerAttempts.map {it.requestId})
                assertEquals(9,h.runtime.workerAttempts.map {it.requestId}.distinct().size)
                assertTrue(h.runtime.workerAttempts.all {it.accepted==NextOperationAcceptance.COMMITTED})
                assertEquals(9,server.requests.count {it.path.endsWith("/push")})
                assertEquals(10,pageRowCount("completions"));assertEquals(10,pageRowCount("next_acceptances"))
                assertEquals(0,pageRowCount("sync_outbox"));assertEquals(0,h.runtime.pendingCount)
            } finally {release.complete(Unit)}
        }
    }

    @Test fun coldOriginalUnsentOutboxIsDiscoveredWithoutNewBusinessWrite()=runBlocking<Unit> {
        seedAccepted(1);val pending=seedFact()
        val request=db.syncOutboxDao().getEntityIntents("completion",pending.uuid).single().operationId
        val (http,server)=channel {successReply(it)}
        withRuntime(http) {h->
            val page=h.open();h.idle()
            withTimeout(20_000) {page.state.first {it.view?.valid()==true}}
            assertNull(page.command);assertNull(page.saved);assertEquals(1,h.runtime.coldStarts)
            assertEquals(listOf(request),h.runtime.workerAttempts.map {it.requestId})
            assertEquals(1,server.requests.count {it.path.endsWith("/push")})
            assertEquals(2,pageRowCount("completions"));assertEquals(2,pageRowCount("next_acceptances"))
            assertEquals(0,pageRowCount("sync_outbox"));val result=page.state.value.view!!
            val terminal=pageNativeState();val delivery=requireNotNull(h.runtime.workerAttempts.single().delivery)
            h.runtime.shutdown();reopenReference();sameAsOriginal(result)
            assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(delivery))
            assertEquals(terminal,pageNativeState())
        }
    }

    @Test fun shutdownDuringRealNetworkWaitClosesOnlyAfterClientWorkerDrains()=runBlocking<Unit> {
        seedAccepted(1);val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val (http,server)=channel {input->
            if(input.path.endsWith("/push")) {entered.countDown();check(release.await(30,TimeUnit.SECONDS))}
            successReply(input).copy(allowClientClose=true)
        }
        try {
            withRuntime(http) {h->
                val page=h.open();assertTrue(page.increment())
                assertTrue(withContext(Dispatchers.IO) {entered.await(15,TimeUnit.SECONDS)})
                val before=pageNativeState();assertEquals(0,h.closes.get());assertTrue(h.connection.busy())
                h.beforeClose={
                    val attempts=h.runtime.workerAttempts
                    assertEquals(1,attempts.size)
                    assertTrue(attempts.all {it.sendEnded>0 && (it.ackStarted==0L || it.ackEnded>0)})
                }
                // OkHttp cancellation can drain the client before our synthetic server
                // returns. Only client work belongs to this owner, not the server thread.
                assertTrue(withTimeout(20_000) {h.runtime.shutdown()})
                assertTrue(h.runtime.drained);assertFalse(h.connection.busy());assertEquals(1,h.closes.get())
                assertNull(h.runtime.state.value.page);assertFalse(page.live());assertFalse(h.runtime.openPage())
                assertEquals(before,pageNativeState());assertEquals(1,pageRowCount("sync_outbox"))
                assertEquals(1,server.requests.count {it.path.endsWith("/push")})
                assertEquals(1,pageRowCount("next_acceptances"));assertEquals(2,pageRowCount("completions"))
            }
        } finally {release.countDown()}
    }

    @Test fun parentCancellationAtLoadingBeforeIoStillDrainsTheNewRuntimeTicket()=runBlocking<Unit> {
        seedAccepted(1);val (http,_)=channel {successReply(it)};val before=pageNativeState()
        withRuntime(http) {h->
            val cancelled=launch(Dispatchers.Unconfined,start=CoroutineStart.UNDISPATCHED) {
                h.runtime.state.first {it.phase==G0CountSessionRuntime.Phase.LOADING}
                h.control.cancel()
            }
            try {
                assertTrue(h.runtime.openPage());assertTrue(cancelled.isCompleted)
                runtimePhase(h.runtime,G0CountSessionRuntime.Phase.CLOSED)
                assertTrue(h.runtime.shutdown());assertTrue(h.runtime.drained)
                assertEquals(0,h.runtime.coldStarts);assertTrue(h.runtime.openTraces.isEmpty())
                assertTrue(h.runtime.workerAttempts.isEmpty());assertEquals(1,h.closes.get())
                assertFalse(h.connection.busy());assertFalse(h.runtime.openPage())
                assertEquals(before,pageNativeState())
            } finally {cancelled.cancelAndJoin()}
        }
    }

    @Test fun synchronousShutdownAtLoadingCannotReturnBeforeTheStartupTicketDrains()=runBlocking<Unit> {
        seedAccepted(1);val (http,_)=channel {successReply(it)};val before=pageNativeState()
        withRuntime(http) {h->
            val closing=CountDownLatch(1);val release=CountDownLatch(1)
            h.beforeClose={
                closing.countDown()
                check(release.await(15,TimeUnit.SECONDS)) {"G0_SESSION_STARTUP_CLOSE_RELEASE_TIMEOUT"}
            }
            // The original order emitted LOADING before assigning lifetime. This
            // synchronous observer could then join null and incorrectly return false.
            val stopped=async(Dispatchers.Unconfined,start=CoroutineStart.UNDISPATCHED) {
                h.runtime.state.first {it.phase==G0CountSessionRuntime.Phase.LOADING}
                h.runtime.shutdown()
            }
            try {
                assertTrue(h.runtime.openPage())
                assertTrue(withContext(Dispatchers.IO) {closing.await(10,TimeUnit.SECONDS)})
                assertFalse(stopped.isCompleted);assertFalse(h.runtime.drained)
                assertTrue(h.connection.busy());assertEquals(0,h.closes.get())
                assertEquals(0,h.runtime.coldStarts);assertTrue(h.runtime.openTraces.isEmpty())
                release.countDown();assertTrue(withTimeout(10_000) {stopped.await()})
                assertTrue(h.runtime.drained);assertFalse(h.connection.busy());assertEquals(1,h.closes.get())
                assertEquals(G0CountSessionRuntime.Phase.CLOSED,h.runtime.state.value.phase)
                assertFalse(h.runtime.openPage());assertEquals(before,pageNativeState())
            } finally {release.countDown();stopped.join()}
        }
    }

    @Test fun failedRuntimeCloseQuarantinesSharedConnectionAndBlocksTheNextFactory()=runBlocking<Unit> {
        seedAccepted(1);val (http,_)=channel {successReply(it)};val before=pageNativeState()
        withRuntime(http) {h->
            val page=h.open();val attempts=AtomicInteger();val nextFactories=AtomicInteger()
            val failure=IOException("G0_COUNT_SESSION_CLOSE_FAILED")
            h.beforeClose={
                attempts.incrementAndGet();assertFalse(page.live())
                assertTrue(h.runtime.workerAttempts.isEmpty());throw failure
            }
            val next=G0CountSessionRuntime(h.control,h.connection,authority,tokens,{domainGate.changeSequence},http) {
                nextFactories.incrementAndGet();coldCount(http)
            }
            try {
                assertFalse(h.runtime.shutdown());h.runtime.awaitPageTransition();page.settled()
                assertSame(failure,h.runtime.error);assertFalse(h.runtime.drained)
                assertTrue(h.connection.busy());assertEquals(1,attempts.get())
                assertNull(page.state.value.view);assertFalse(page.increment())
                assertFalse(next.openPage());assertEquals(G0CountSessionRuntime.Phase.BUSY,next.state.value.phase)
                assertEquals(0,nextFactories.get());assertEquals(0,next.coldStarts)
                assertFalse(h.runtime.shutdown());assertEquals(1,attempts.get())
                assertEquals(before,pageNativeState())
            } finally {
                next.shutdown()
                // Cleanup the test database only; the failed owner's ticket stays busy.
                db.close()
            }
        }
    }

    @Test fun cancellingQueuedReopenDoesNotCancelTheAcceptedWriteOrItsBackgroundAck()=runBlocking<Unit> {
        seedAccepted(1);val (http,server)=channel {successReply(it)}
        withRuntime(http) {h->
            val page=h.open();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            h.store.afterPrepareForTest={entered.complete(Unit);release.await()}
            val priorEvents=page.events.size
            try {
                assertTrue(page.increment());withTimeout(20_000) {entered.await()}
                val command=requireNotNull(page.command)
                h.runtime.leavePage();assertTrue(h.runtime.openPage())
                assertEquals(G0CountSessionRuntime.Phase.LOADING,h.runtime.state.value.phase)
                h.runtime.leavePage()
                assertEquals(G0CountSessionRuntime.Phase.LEAVING,h.runtime.state.value.phase)
                assertFalse(h.runtime.renderable());assertNull(h.runtime.state.value.page)
                assertFalse(page.live());assertNull(page.state.value.view);assertNull(page.error)
                assertEquals(0L,page.commandTiming!!.knownCommitAt)
                assertTrue(h.connection.busy());assertEquals(0,h.closes.get())
                assertEquals(1,h.runtime.openTraces.size)
                release.complete(Unit);h.runtime.awaitPageTransition();page.settled()
                h.store.afterPrepareForTest={};h.idle()
                assertNull(page.error);assertTrue(page.commandTiming!!.knownCommitAt>0)
                assertEquals(command.requestId,page.saved!!.requestId)
                assertEquals(G0CountSessionRuntime.Phase.AWAY,h.runtime.state.value.phase)
                assertEquals(1,h.runtime.openTraces.size);assertEquals(1,h.runtime.coldStarts)
                assertNull(page.state.value.view)
                assertFalse(page.events.drop(priorEvents).any {it.phase==G0ShortCountPage.Phase.RESULT})
                assertEquals(listOf(command.requestId),h.runtime.workerAttempts.map {it.requestId})
                assertEquals(1,server.requests.count {it.path.endsWith("/push")})
                assertEquals(2,pageRowCount("completions"));assertEquals(2,pageRowCount("next_acceptances"))
                assertEquals(0,pageRowCount("sync_outbox"));assertEquals(0,h.runtime.pendingCount)
                val result=h.control.async {view(h.store,page.saved!!.factId)}.await()
                val terminal=pageNativeState();val delivery=requireNotNull(h.runtime.workerAttempts.single().delivery)
                assertTrue(h.runtime.shutdown());reopenReference();sameAsOriginal(result)
                assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(delivery))
                assertEquals(terminal,pageNativeState())
            } finally {release.complete(Unit);h.store.afterPrepareForTest={}}
        }
    }

    @Test fun cancellingPrivateWarmPageDiscardsPublicationButLetsTheKnownAckFinish()=runBlocking<Unit> {
        seedAccepted(1);val (http,server)=channel {successReply(it)}
        withRuntime(http) {h->
            val batchEntered=CompletableDeferred<Unit>();val batchRelease=CompletableDeferred<Unit>()
            val publicationEntered=CompletableDeferred<Unit>();val publicationRelease=CompletableDeferred<Unit>()
            h.runtime.beforeSyncBatchForTest={batchEntered.complete(Unit);batchRelease.await()}
            try {
                val page=h.open();val committed=saved(page)
                withTimeout(20_000) {batchEntered.await()};h.away()
                val retired=page.state.value;val initialOpens=h.runtime.events.count {it.phase==G0CountSessionRuntime.Phase.OPEN}
                h.runtime.beforePublicationForTest={publicationEntered.complete(Unit);publicationRelease.await()}
                assertTrue(h.runtime.openPage());withTimeout(20_000) {publicationEntered.await()}
                assertEquals(G0CountSessionRuntime.Phase.LOADING,h.runtime.state.value.phase)
                assertEquals(2,h.runtime.openTraces.size);assertFalse(h.runtime.renderable())
                h.runtime.leavePage()
                assertFalse(h.runtime.renderable());assertNull(h.runtime.state.value.page)
                assertEquals(retired,page.state.value);assertFalse(page.live())
                assertEquals(1,h.runtime.pendingCount);assertEquals(1,pageRowCount("sync_outbox"))
                assertTrue(h.connection.busy());assertEquals(0,h.closes.get())
                publicationRelease.complete(Unit);h.runtime.awaitPageTransition()
                assertEquals(G0CountSessionRuntime.Phase.AWAY,h.runtime.state.value.phase)
                assertNull(h.runtime.error)
                assertEquals(initialOpens,h.runtime.events.count {it.phase==G0CountSessionRuntime.Phase.OPEN})
                batchRelease.complete(Unit);h.idle()
                assertEquals(G0CountSessionRuntime.Phase.AWAY,h.runtime.state.value.phase)
                assertEquals(initialOpens,h.runtime.events.count {it.phase==G0CountSessionRuntime.Phase.OPEN})
                assertEquals(1,h.runtime.coldStarts);assertEquals(2,h.runtime.openTraces.size)
                assertEquals(retired,page.state.value);assertEquals(0,h.runtime.pendingCount)
                assertEquals(listOf(committed.requestId),h.runtime.workerAttempts.map {it.requestId})
                assertEquals(1,server.requests.count {it.path.endsWith("/push")})
                assertEquals(2,pageRowCount("completions"));assertEquals(2,pageRowCount("next_acceptances"))
                assertEquals(0,pageRowCount("sync_outbox"))
                val result=h.control.async {view(h.store,committed.factId)}.await()
                val terminal=pageNativeState();val delivery=requireNotNull(h.runtime.workerAttempts.single().delivery)
                assertTrue(h.runtime.shutdown());reopenReference();sameAsOriginal(result)
                assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(delivery))
                assertEquals(terminal,pageNativeState())
            } finally {publicationRelease.complete(Unit);batchRelease.complete(Unit)}
        }
    }

    @Test fun leavingDuringActualReadFinalAuthenticationDoesNotPoisonTheRetainedStore()=runBlocking<Unit> {
        seedAccepted(1);val (http,server)=channel {successReply(it)}
        withRuntime(http) {h->
            val batchEntered=CompletableDeferred<Unit>();val batchRelease=CompletableDeferred<Unit>()
            val ackEntered=CompletableDeferred<Unit>();val ackRelease=CompletableDeferred<Unit>()
            val pause=RefreshPause(G0PhaseTrace(driverTiming=false))
            h.runtime.beforeSyncBatchForTest={batchEntered.complete(Unit);batchRelease.await()}
            h.runtime.beforeAcceptForTest={ackEntered.complete(Unit);ackRelease.await()}
            try {
                val page=h.open();val committed=saved(page)
                withTimeout(20_000) {batchEntered.await()}
                page.refreshTrace=pause.trace;refreshPause.set(pause)
                batchRelease.complete(Unit)
                assertTrue(withContext(Dispatchers.IO) {pause.entered.await(15,TimeUnit.SECONDS)})
                assertEquals(2,pause.decrypts.get())
                val active=synchronized(pause.trace.spans) {pause.trace.spans.toList()}
                assertEquals("Driver BEGIN must have completed; decrypt-thread inTransaction=${pause.decryptThreadInTransaction}",
                    1,active.count {it.phase=="tx_begin_outer" && it.ok})
                assertEquals(0,active.count {it.phase=="tx_end_outer"})
                assertEquals(1,active.count {it.phase=="domain_entry_audit" && it.ok})
                assertEquals(0,active.count {it.phase=="domain_business"})
                h.runtime.leavePage()
                val retired=page.state.value
                assertEquals(G0ShortCountPage.Phase.CLOSED,retired.phase);assertNull(retired.view)
                assertFalse(page.live());assertFalse(h.runtime.renderable())
                assertEquals(G0CountSessionRuntime.Phase.LEAVING,h.runtime.state.value.phase)
                val leaving=async(Dispatchers.Unconfined,start=CoroutineStart.UNDISPATCHED) {h.runtime.awaitPageTransition()}
                assertFalse(leaving.isCompleted);assertEquals(0,h.closes.get());assertTrue(h.connection.busy())
                pause.release.countDown();withTimeout(20_000) {leaving.await()}
                val finished=synchronized(pause.trace.spans) {pause.trace.spans.toList()}
                assertEquals(1,finished.count {it.phase=="tx_end_outer" && it.ok})
                assertEquals(1,finished.count {it.phase=="domain_business" && it.ok})
                assertEquals(G0CountSessionRuntime.Phase.AWAY,h.runtime.state.value.phase)
                assertEquals(retired,page.state.value);assertNull(page.error);assertNull(h.runtime.error)
                withTimeout(20_000) {ackEntered.await()};assertEquals(1,pageRowCount("sync_outbox"))
                ackRelease.complete(Unit);h.idle()
                val reopened=h.open();assertNotSame(page,reopened);assertEquals(1,h.runtime.coldStarts)
                assertEquals(2L,reopened.state.value.view!!.history.todayQuantity)
                assertEquals(retired,page.state.value);assertNull(page.error)
                assertEquals(0,h.runtime.pendingCount);assertEquals(0,pageRowCount("sync_outbox"))
                assertEquals(2,pageRowCount("completions"));assertEquals(2,pageRowCount("next_acceptances"))
                assertEquals(listOf(committed.requestId),h.runtime.workerAttempts.map {it.requestId})
                assertEquals(1,server.requests.count {it.path.endsWith("/push")})
                val result=reopened.state.value.view!!;val terminal=pageNativeState()
                val delivery=requireNotNull(h.runtime.workerAttempts.single().delivery)
                assertTrue(h.runtime.shutdown());reopenReference();sameAsOriginal(result)
                assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(delivery))
                assertEquals(terminal,pageNativeState())
            } finally {
                pause.release.countDown();refreshPause.set(null);batchRelease.complete(Unit);ackRelease.complete(Unit)
            }
        }
    }
}
