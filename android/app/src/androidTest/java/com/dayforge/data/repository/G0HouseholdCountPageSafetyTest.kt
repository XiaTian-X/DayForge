package com.dayforge.data.repository

import androidx.activity.compose.setContent
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.api.NextSyncHttp
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

abstract class G0HouseholdCountPageFixture:G0HouseholdCountFixture() {
    internal fun installHouseholdRuntime(runtime:G0HouseholdCountRuntime,seen:G0ScreenObservation,
        pageSeen:(G0HouseholdCountPage)->G0ScreenObservation) {
        activity.scenario.onActivity {it.setContent {G0HouseholdCountRuntimeScreen(runtime,seen,pageSeen)}}
    }
    internal suspend fun householdPage(runtime:G0HouseholdCountRuntime)=withTimeout(20_000) {
        requireNotNull(runtime.state.first {it.phase==G0HouseholdCountRuntime.Phase.OPEN}.page)
    }
    internal suspend fun householdPagePhase(page:G0HouseholdCountPage,phase:G0HouseholdCountPage.Phase)=
        withTimeout(20_000) {page.state.first {it.phase==phase}}
    internal suspend fun householdPixels(runtime:G0HouseholdCountRuntime,page:G0HouseholdCountPage,seen:G0ScreenObservation)=
        G0ScreenObservation.awaitTogether {
            val outer=runtime.state.value;val state=page.state.value;val view=state.view
            if(outer.page!==page || outer.phase!=G0HouseholdCountRuntime.Phase.OPEN || !page.live() || view==null || !view.valid())null
            else listOf(G0ScreenObservation.Target(seen,state.version) {
                runtime.state.value.version==outer.version && runtime.state.value.page===page && runtime.renderable() &&
                    page.live() && page.state.value.version==state.version && view.valid()
            })
        }
}

@RunWith(AndroidJUnit4::class)
class G0HouseholdCountPageSafetyTest:G0HouseholdCountPageFixture() {
    private inner class Harness(http:NextSyncHttp) {
        val job=SupervisorJob();val control=CoroutineScope(job+Dispatchers.IO);val closes=AtomicInteger()
        lateinit var store:G0HouseholdCountStore
        val connection=G0CountConnectionOwner {closes.incrementAndGet();db.close()}
        val runtime=G0HouseholdCountRuntime(control,connection,authority,tokens,{domainGate.changeSequence},http) {
            coldHousehold(http).also {store=it}
        }
        val seen=G0ScreenObservation();val observations=ConcurrentHashMap<G0HouseholdCountPage,G0ScreenObservation>()
        fun seen(page:G0HouseholdCountPage)=observations.computeIfAbsent(page) {G0ScreenObservation()}
        suspend fun open(screen:Boolean=false):G0HouseholdCountPage {
            if(screen) {installHouseholdRuntime(runtime,seen,::seen);seen.await(runtime.state.value.version);seen.tap("open")}
            else assertTrue(runtime.openPage())
            val page=householdPage(runtime)
            withTimeout(20_000) {page.state.first {it.view?.valid()==true}}
            if(screen)householdPixels(runtime,page,seen(page))
            return page
        }
        suspend fun synced()=withTimeout(20_000) {runtime.awaitSynced()}
        fun resetFinalHook() {if(::store.isInitialized)store.beforeFinalForTest={}}
        suspend fun close()=withContext(NonCancellable) {runtime.shutdown();job.cancelAndJoin()}
    }
    private suspend fun withRuntime(http:NextSyncHttp,body:suspend (Harness)->Unit) {
        val h=Harness(http);try {body(h)} finally {h.close()}
    }
    private suspend fun save(page:G0HouseholdCountPage,id:String):G0HouseholdCountStore.Saved {
        assertTrue(page.increment(id));householdPagePhase(page,G0HouseholdCountPage.Phase.RESULT);page.settled()
        assertNull(page.error);return requireNotNull(page.saved)
    }
    private suspend fun assertTerminal(h:Harness,http:NextSyncHttp,result:G0HouseholdCountStore.View,total:Int) {
        h.synced();assertEquals(total,pageRowCount("completions"));assertEquals(total,pageRowCount("next_acceptances"))
        assertEquals(0,pageRowCount("sync_outbox"));assertEquals(0,h.runtime.pendingCount)
        val terminal=pageNativeState();val deliveries=h.runtime.workerAttempts.map {requireNotNull(it.delivery)}
        assertTrue(h.runtime.shutdown());reopenReference();sameHouseholdAsOriginal(result)
        for(delivery in deliveries)assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(delivery))
        assertEquals(terminal,pageNativeState())
    }

    @Test fun constructingHouseholdRuntimeDoesNotReadAuthenticateOrBuildBeforeOpen()=runBlocking<Unit> {
        seedHouseholdAccepted();val (http,server)=channel {successReply(it)}
        val before=pageNativeState();val decrypts=decryptCount.get()
        withRuntime(http) {h->
            yield();assertEquals(0,h.runtime.coldStarts);assertEquals(decrypts,decryptCount.get())
            assertEquals(G0HouseholdCountRuntime.Phase.NEW,h.runtime.state.value.phase)
            assertTrue(h.runtime.workerAttempts.isEmpty());assertFalse(h.connection.busy())
            assertTrue(server.requests.isEmpty());assertEquals(before,pageNativeState())
            assertTrue(h.runtime.shutdown());assertEquals(0,h.closes.get())
        }
    }

    @Test fun actualObjectButtonsPublishOneTwoCardVersionOnlyAfterAtomicCommit()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,_)=channel {successReply(it)}
        withRuntime(http) {h->
            val commitEntered=CompletableDeferred<Unit>();val commitRelease=CompletableDeferred<Unit>()
            val syncRelease=CompletableDeferred<Unit>();h.runtime.beforeSyncBatchForTest={syncRelease.await()}
            try {
                val page=h.open(screen=true);val before=pageNativeState()
                assertEquals(seed.habits.map {it.uuid}.toSet(),page.state.value.members.toSet())
                h.store.beforeFinalForTest={commitEntered.complete(Unit);commitRelease.await()}
                h.seen(page).tap("increment:${seed.habits[0].uuid}")
                withTimeout(20_000) {commitEntered.await()}
                assertEquals(G0HouseholdCountPage.Phase.SAVING,page.state.value.phase)
                assertFalse(page.events.any {it.phase in setOf(G0HouseholdCountPage.Phase.SAVED,G0HouseholdCountPage.Phase.RESULT)})
                assertEquals(0L,page.commandTiming!!.knownCommitAt);assertNull(page.saved)
                assertEquals(before,pageNativeState())
                commitRelease.complete(Unit);householdPagePhase(page,G0HouseholdCountPage.Phase.RESULT);page.settled()
                h.store.beforeFinalForTest={};assertNull(page.error);val a=requireNotNull(page.saved)
                householdPixels(h.runtime,page,h.seen(page))
                requireVisiblePageText(setOf("Household A：1 / 3","Household B：0 / 7","首页：Household A=1，Household B=0","已保存"))
                val priorVersion=page.state.value.version;h.seen(page).tap("increment:${seed.habits[1].uuid}")
                withTimeout(20_000) {page.state.first {it.version>priorVersion && it.phase==G0HouseholdCountPage.Phase.RESULT}}
                page.settled();assertNull(page.error)
                val b=requireNotNull(page.saved);assertEquals(seed.habits[1].uuid,b.habitUuid)
                householdPixels(h.runtime,page,h.seen(page))
                val pending=page.state.value.view!!
                assertEquals(a.factUuid,pending.cards.getValue(a.habitUuid).followUp.claim.completionUuid)
                assertEquals(b.factUuid,pending.cards.getValue(b.habitUuid).followUp.claim.completionUuid)
                requireVisiblePageText(setOf("Household A：1 / 3","Household B：1 / 7","首页：Household A=1，Household B=1"))
                assertEquals(2,pageRowCount("sync_outbox"));assertEquals(10,pageRowCount("count_days"))
                syncRelease.complete(Unit);h.synced();householdPixels(h.runtime,page,h.seen(page))
                val result=page.state.value.view!!
                assertEquals(a.factUuid,result.cards.getValue(a.habitUuid).followUp.claim.completionUuid)
                assertEquals(b.factUuid,result.cards.getValue(b.habitUuid).followUp.claim.completionUuid)
                assertTerminal(h,http,result,10)
            } finally {commitRelease.complete(Unit);syncRelease.complete(Unit);h.resetFinalHook()}
        }
    }

    @Test fun ackInvalidationAdmitsOnlyOneOtherObjectIntentAndLeavingDoesNotLoseIt()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,_)=channel {successReply(it)}
        withRuntime(http) {h->
            val ackEntered=CompletableDeferred<Unit>();val ackRelease=CompletableDeferred<Unit>()
            val refreshEntered=CompletableDeferred<Unit>();val refreshRelease=CompletableDeferred<Unit>()
            val admissionEntered=CompletableDeferred<Unit>();val admissionRelease=CompletableDeferred<Unit>()
            h.runtime.beforeAcceptForTest={ackEntered.complete(Unit);ackRelease.await()}
            try {
                val page=h.open();val a=save(page,seed.habits[0].uuid)
                withTimeout(20_000) {ackEntered.await()};withTimeout(20_000) {page.state.first {it.view?.valid()==true}}
                val prior=page.state.value.view!!
                page.beforeRefreshForTest={refreshEntered.complete(Unit);refreshRelease.await()}
                page.beforeAdmissionReadForTest={admissionEntered.complete(Unit);admissionRelease.await()}
                ackRelease.complete(Unit);withTimeout(20_000) {refreshEntered.await()};assertFalse(prior.valid())
                val beforeEvents=page.events.size
                assertTrue(page.increment(seed.habits[1].uuid));withTimeout(20_000) {admissionEntered.await()}
                assertEquals(G0HouseholdCountPage.Phase.VERIFYING,page.state.value.phase)
                val bCommand=page.command!!;assertEquals(seed.habits[1].uuid,bCommand.habitUuid)
                assertFalse(page.increment(seed.habits[0].uuid));assertFalse(page.increment(seed.habits[1].uuid))
                assertSame(bCommand,page.command)
                h.runtime.leavePage();assertFalse(page.live());assertNull(page.state.value.view)
                admissionRelease.complete(Unit);refreshRelease.complete(Unit)
                h.runtime.awaitPageTransition();page.settled();assertNull(page.error)
                assertEquals(bCommand.requestId,page.saved!!.requestId)
                assertFalse(page.events.drop(beforeEvents).any {it.phase==G0HouseholdCountPage.Phase.RESULT})
                assertEquals(G0HouseholdCountRuntime.Phase.AWAY,h.runtime.state.value.phase)
                h.synced();val b=page.saved!!
                assertEquals(setOf(a.requestId,b.requestId),h.runtime.workerAttempts.map {it.requestId}.toSet())
                val result=h.control.async {householdView(h.store,mapOf(a.habitUuid to a.factId,b.habitUuid to b.factId))}.await()
                assertEquals(1L,result.cards.getValue(a.habitUuid).history.todayQuantity)
                assertEquals(1L,result.cards.getValue(b.habitUuid).history.todayQuantity)
                assertTerminal(h,http,result,10)
            } finally {ackRelease.complete(Unit);refreshRelease.complete(Unit);admissionRelease.complete(Unit)}
        }
    }

    @Test fun privateHouseholdPublicationCannotSurviveAuthorityAbaOrOutliveDrain()=runBlocking<Unit> {
        seedHouseholdAccepted();val image=storage.snapshotClosedDatabase()
        for(warm in listOf(false,true)) {
            freshGateForColdOpen();restoreForJourney(image);val (http,_)=channel {successReply(it)};val before=pageNativeState()
            withRuntime(http) {h->
                if(warm) {h.open();h.runtime.leavePage();h.runtime.awaitPageTransition()}
                val priorOpens=h.runtime.events.count {it.phase==G0HouseholdCountRuntime.Phase.OPEN}
                val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
                h.runtime.beforePublicationForTest={withContext(NonCancellable) {entered.complete(Unit);release.await()}}
                try {
                    assertTrue(h.runtime.openPage());withTimeout(20_000) {entered.await()}
                    val original=dataStore.data.first()
                    dataStore.edit {it[stringPreferencesKey("sync_device_capability_revision")]="2"}
                    dataStore.updateData {original};assertEquals(original,dataStore.data.first())
                    assertFalse(h.runtime.renderable());assertNull(h.runtime.state.value.page)
                    assertTrue(h.connection.busy());assertEquals(0,h.closes.get())
                    release.complete(Unit);assertTrue(h.runtime.shutdown())
                    assertEquals(priorOpens,h.runtime.events.count {it.phase==G0HouseholdCountRuntime.Phase.OPEN})
                    assertFalse(h.connection.busy());assertEquals(1,h.closes.get());assertTrue(h.runtime.drained)
                    assertFalse(h.runtime.openPage());assertEquals(before,pageNativeState())
                } finally {release.complete(Unit)}
            }
        }
    }

    @Test fun unknownSecondObjectCommitKeepsItsOwnIdentityAndColdRecoveryDoesNotRepeatIt()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,server)=channel {successReply(it)}
        withRuntime(http) {h->
            val page=h.open();val a=save(page,seed.habits[0].uuid);h.synced()
            withTimeout(20_000) {page.state.first {it.view?.valid()==true}}
            val trace=G0PhaseTrace(driverTiming=false);page.trace=trace;endFaults.failAfterNextEndIn(trace)
            assertTrue(page.increment(seed.habits[1].uuid));page.settled()
            val command=page.command!!;assertEquals(seed.habits[1].uuid,command.habitUuid)
            assertEquals("G0_COMMIT_RESULT_LOST",page.error?.message);assertEquals(0L,page.commandTiming!!.knownCommitAt)
            assertEquals(a,page.saved) // A's known success must not be confused with B's outcome.
            assertTrue(h.runtime.shutdown());assertEquals(command,h.runtime.retiredPage!!.command)
            assertFalse(page.increment(seed.habits[0].uuid));assertFalse(page.increment(seed.habits[1].uuid))
            assertFalse(h.runtime.openPage());assertEquals(listOf(a.requestId),h.runtime.workerAttempts.map {it.requestId})
            assertEquals(1,server.requests.count {it.path.endsWith("/push")})
            assertEquals(10,pageRowCount("completions"));assertEquals(10,pageRowCount("count_days"))
            assertEquals(1,pageRowCount("sync_outbox"))
            reopenReference();val recovered=openHousehold(http);val before=pageNativeState()
            val found=requireNotNull(recovered.recover(command));assertEquals(command.requestId,found.requestId)
            assertEquals(command.factUuid,found.factUuid);assertEquals(command.habitUuid,found.habitUuid)
            assertEquals(before,pageNativeState());assertTrue(runCatching {recovered.increase(command)}.isFailure)
            assertEquals(before,pageNativeState())
            val delivery=requireNotNull(recovered.send(http,found.requestId))
            assertEquals(NextOperationAcceptance.COMMITTED,recovered.acknowledge(delivery))
            val result=householdView(recovered,mapOf(a.habitUuid to a.factId,found.habitUuid to found.factId))
            val terminal=pageNativeState();reopenReference();sameHouseholdAsOriginal(result)
            assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(delivery))
            assertEquals(terminal,pageNativeState());assertEquals(0,pageRowCount("sync_outbox"))
        }
    }

    @Test fun lateRefreshOfOnlyACannotReplaceTheNewBClaimOrCurrentCommand()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,_)=channel {successReply(it)}
        withRuntime(http) {h->
            val syncRelease=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            h.runtime.beforeSyncBatchForTest={syncRelease.await()}
            var refresh:Deferred<Unit>?=null
            try {
                val page=h.open();val a=save(page,seed.habits[0].uuid)
                page.beforeRefreshForTest={entered.complete(Unit);release.await()}
                refresh=h.control.async {page.refresh()};withTimeout(20_000) {entered.await()}
                val b=save(page,seed.habits[1].uuid);val command=page.command;val result=page.state.value
                assertEquals(a.factUuid,result.view!!.cards.getValue(a.habitUuid).followUp.claim.completionUuid)
                assertEquals(b.factUuid,result.view!!.cards.getValue(b.habitUuid).followUp.claim.completionUuid)
                release.complete(Unit);requireNotNull(refresh).await()
                assertEquals(result,page.state.value);assertSame(command,page.command);assertEquals(b,page.saved)
                page.beforeRefreshForTest={};syncRelease.complete(Unit);h.synced()
                withTimeout(20_000) {page.state.first {it.view?.valid()==true}}
                val terminal=page.state.value.view!!
                assertEquals(a.factUuid,terminal.cards.getValue(a.habitUuid).followUp.claim.completionUuid)
                assertEquals(b.factUuid,terminal.cards.getValue(b.habitUuid).followUp.claim.completionUuid)
                assertTerminal(h,http,terminal,10)
            } finally {release.complete(Unit);syncRelease.complete(Unit);refresh?.join()}
        }
    }

    @Test fun realAckNaturallyHidesAndRestoresTheCommonTwoCardFrameAtFrozenCoordinates()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,_)=channel {successReply(it)}
        withRuntime(http) {h->
            val ackEntered=CompletableDeferred<Unit>();val ackRelease=CompletableDeferred<Unit>()
            val refreshEntered=CompletableDeferred<Unit>();val refreshRelease=CompletableDeferred<Unit>()
            h.runtime.beforeAcceptForTest={ackEntered.complete(Unit);ackRelease.await()}
            try {
                val page=h.open(screen=true);h.seen(page).tap("increment:${seed.habits[0].uuid}")
                householdPagePhase(page,G0HouseholdCountPage.Phase.RESULT);page.settled()
                withTimeout(20_000) {ackEntered.await()};householdPixels(h.runtime,page,h.seen(page))
                val prior=page.state.value;val frozen=h.seen(page).frozenMarker(prior.version)
                requireVisiblePageText(setOf("Household A：1 / 3","Household B：0 / 7","首页：Household A=1，Household B=0"))
                page.beforeRefreshForTest={refreshEntered.complete(Unit);refreshRelease.await()}
                ackRelease.complete(Unit);withTimeout(20_000) {refreshEntered.await()}
                assertFalse(prior.view!!.valid());assertEquals(prior.version,page.state.value.version)
                // Actual gate-change notification must redraw. No invalidate, extra
                // input or recomposition request is used to hide or restore this frame.
                val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
                withTimeout(15_000) {while(true) {
                    val bitmap=automation.takeScreenshot()
                    if(bitmap!=null) {
                        val present=try {frozen(bitmap) || G0ScreenObservation.matchesTogether(bitmap,
                            listOf(G0ScreenObservation.Target(h.seen(page),prior.version)))} finally {bitmap.recycle()}
                        if(!present)break
                    }
                    delay(16)
                }}
                assertFalse(h.runtime.renderable());assertTrue(page.live());assertEquals(prior.version,page.state.value.version)
                refreshRelease.complete(Unit)
                withTimeout(20_000) {page.state.first {it.version>prior.version && it.view?.valid()==true}}
                householdPixels(h.runtime,page,h.seen(page))
                requireVisiblePageText(setOf("Household A：1 / 3","Household B：0 / 7","首页：Household A=1，Household B=0"))
                assertNull(page.error);assertNull(h.runtime.error);assertTrue(h.runtime.renderable())
                assertTerminal(h,http,page.state.value.view!!,9)
            } finally {ackRelease.complete(Unit);refreshRelease.complete(Unit)}
        }
    }

    @Test fun damageInBRejectsTheSharedAActionWithoutPublishingOrWritingEitherObject()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,server)=channel {successReply(it)}
        withRuntime(http) {h->
            val page=h.open();val old=page.state.value.view!!
            mutateHouseholdFile("UPDATE completions SET createdAt=createdAt+1 WHERE id=?",arrayOf(seed.factIds[1]))
            val before=pageNativeState();val priorEvents=page.events.size
            assertTrue(page.increment(seed.habits[0].uuid));page.settled();assertNotNull(page.error)
            assertEquals(0L,page.commandTiming!!.knownCommitAt);assertNull(page.saved)
            assertFalse(page.events.drop(priorEvents).any {it.phase in setOf(G0HouseholdCountPage.Phase.SAVED,G0HouseholdCountPage.Phase.RESULT)})
            assertTrue(h.runtime.shutdown());assertFalse(old.valid());assertNull(page.state.value.view)
            assertEquals(before,pageNativeState());assertTrue(h.runtime.workerAttempts.isEmpty())
            assertTrue(server.requests.none {it.path.endsWith("/push")})
        }
    }
}
