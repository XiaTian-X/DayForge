package com.dayforge.data.repository

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.api.NextSyncHttp
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Anonymous feedback is independently observed. It is never an authorization to
 * show private data, claim a saved result, or skip a fresh business boundary. Small
 * deterministic safety interleavings do not claim latency or annual UI acceptance. */
@RunWith(AndroidJUnit4::class)
class G0HouseholdFeedbackSafetyTest:G0HouseholdCountPageFixture() {
    private inner class Harness(http:NextSyncHttp) {
        val job=SupervisorJob();val control=CoroutineScope(job+Dispatchers.IO)
        lateinit var store:G0HouseholdCountStore
        val runtime=G0HouseholdCountRuntime(control,G0CountConnectionOwner {db.close()},authority,tokens,
            {domainGate.changeSequence},http) {coldHousehold(http).also {store=it}}
        val outer=G0ScreenObservation()
        private val screens=ConcurrentHashMap<G0HouseholdCountPage,G0ScreenObservation>()
        private val feedbacks=ConcurrentHashMap<G0HouseholdCountPage,G0HouseholdFeedbackObservation>()
        fun screen(page:G0HouseholdCountPage)=screens.computeIfAbsent(page) {G0ScreenObservation()}
        fun feedback(page:G0HouseholdCountPage)=feedbacks.computeIfAbsent(page) {G0HouseholdFeedbackObservation()}
        suspend fun open():G0HouseholdCountPage {
            activity.scenario.onActivity {it.setContent {G0HouseholdCountRuntimeScreen(runtime,outer,::screen,::feedback)}}
            outer.await(runtime.state.value.version);outer.tap("open")
            return householdPage(runtime).also {householdPixels(runtime,it,screen(it))}
        }
        suspend fun close()=withContext(NonCancellable) {runtime.shutdown();job.cancelAndJoin()}
    }
    private suspend fun withHarness(http:NextSyncHttp,body:suspend (Harness)->Unit) {
        val h=Harness(http);try {body(h)} finally {h.close()}
    }
    private suspend fun busy(h:Harness,page:G0HouseholdCountPage):G0HouseholdFeedbackObservation.Draw {
        val timing=requireNotNull(page.commandTiming);val state=page.state.value
        assertTrue(state.phase in setOf(G0HouseholdCountPage.Phase.VERIFYING,G0HouseholdCountPage.Phase.SAVING))
        assertTrue(h.runtime.feedbackRenderable(h.runtime.state.value,state,timing))
        val observer=h.feedback(page);observer.pixels.await(state.version)
        val drawn=observer.draws.single {it.version==state.version}
        assertEquals(timing.firstStateVersion,drawn.commandVersion);assertEquals(state.phase,drawn.phase)
        assertEquals(0L,drawn.knownCommitAt);assertTrue(drawn.at>=state.publishedAt)
        assertEquals(drawn.at,observer.pixels.draws.single {it.version==state.version}.at)
        return drawn
    }
    private suspend fun hidden(seen:G0ScreenObservation,version:Long,frozen:(Bitmap)->Boolean) {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        withTimeout(15_000) {while(true) {
            val bitmap=automation.takeScreenshot()
            if(bitmap!=null) {
                val present=try {frozen(bitmap) || G0ScreenObservation.matchesTogether(bitmap,
                    listOf(G0ScreenObservation.Target(seen,version)))} finally {bitmap.recycle()}
                if(!present)break
            }
            delay(16)
        }}
    }
    private suspend fun result(h:Harness,page:G0HouseholdCountPage):G0HouseholdCountStore.View {
        householdPagePhase(page,G0HouseholdCountPage.Phase.RESULT);page.settled()
        val pixels=householdPixels(h.runtime,page,h.screen(page))
        return requireNotNull(page.events.single {it.version==pixels.version}.view)
    }
    private suspend fun terminal(h:Harness,http:NextSyncHttp,page:G0HouseholdCountPage,total:Int) {
        withTimeout(20_000) {h.runtime.awaitSynced()}
        val pixels=householdPixels(h.runtime,page,h.screen(page))
        val view=requireNotNull(page.events.single {it.version==pixels.version}.view)
        val native=pageNativeState();val attempts=h.runtime.workerAttempts
        assertTrue(h.runtime.shutdown());reopenReference();sameHouseholdAsOriginal(view)
        for(attempt in attempts)assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(requireNotNull(attempt.delivery)))
        assertEquals(native,pageNativeState());assertEquals(total,pageRowCount("completions"))
        assertEquals(total,pageRowCount("next_acceptances"));assertEquals(0,pageRowCount("sync_outbox"))
    }

    @Test fun actualBusyFramePrecedesCommitAndCannotBecomeSavedOrMatchTheNextCommand()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,_)=channel {successReply(it)}
        withHarness(http) {h->
            val syncRelease=CompletableDeferred<Unit>();h.runtime.beforeSyncBatchForTest={syncRelease.await()}
            val release=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
            val secondRelease=CompletableDeferred<Unit>();val secondEntered=CompletableDeferred<Unit>()
            try {
                val page=h.open();assertTrue(h.feedback(page).draws.isEmpty())
                val before=pageNativeState()
                h.store.afterPrepareForTest={entered.complete(Unit);release.await()}
                h.screen(page).tap("increment:${seed.habits[0].uuid}");withTimeout(20_000) {entered.await()}
                val first=busy(h,page);val firstState=page.state.value;val firstTiming=requireNotNull(page.commandTiming)
                val outer=h.runtime.state.value;val frozen=h.feedback(page).pixels.frozenMarker(first.version)
                assertEquals(before,pageNativeState());assertEquals(0L,page.committedAt);assertNull(page.saved)
                assertFalse(page.events.any {it.phase in setOf(G0HouseholdCountPage.Phase.SAVED,G0HouseholdCountPage.Phase.RESULT)})
                requireVisiblePageText(setOf("正在保存"))
                release.complete(Unit);val shown=result(h,page)
                assertEquals(1L,shown.cards.getValue(seed.habits[0].uuid).history.todayQuantity)
                assertTrue(page.committedAt>first.at);assertTrue(page.events.filter {it.phase in setOf(
                    G0HouseholdCountPage.Phase.SAVED,G0HouseholdCountPage.Phase.RESULT)}.all {it.publishedAt>=page.committedAt})
                requireVisiblePageText(setOf("已保存"));hidden(h.feedback(page).pixels,first.version,frozen)
                assertFalse(h.runtime.feedbackRenderable(outer,firstState,firstTiming))
                h.store.afterPrepareForTest={secondEntered.complete(Unit);secondRelease.await()}
                h.screen(page).tap("increment:${seed.habits[1].uuid}");withTimeout(20_000) {secondEntered.await()}
                val second=busy(h,page)
                assertTrue(second.commandVersion>first.commandVersion);assertTrue(second.version>first.version)
                assertFalse(h.runtime.feedbackRenderable(h.runtime.state.value,firstState,firstTiming))
                hidden(h.feedback(page).pixels,first.version,frozen)
                secondRelease.complete(Unit);result(h,page);syncRelease.complete(Unit)
                terminal(h,http,page,10)
            } finally {release.complete(Unit);secondRelease.complete(Unit);syncRelease.complete(Unit)}
        }
    }

    @Test fun LeavingAndANewPageImmediatelyRetireTheOldAnonymousCommandFrame()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,_)=channel {successReply(it)}
        withHarness(http) {h->
            val syncRelease=CompletableDeferred<Unit>();h.runtime.beforeSyncBatchForTest={syncRelease.await()}
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val secondEntered=CompletableDeferred<Unit>();val secondRelease=CompletableDeferred<Unit>()
            try {
                val page=h.open();h.store.afterPrepareForTest={entered.complete(Unit);release.await()}
                h.screen(page).tap("increment:${seed.habits[0].uuid}");withTimeout(20_000) {entered.await()}
                val first=busy(h,page);val outer=h.runtime.state.value;val old=page.state.value;val timing=requireNotNull(page.commandTiming)
                val observer=h.feedback(page);val frozen=observer.pixels.frozenMarker(first.version)
                h.outer.tap("leave")
                assertFalse(page.live());assertFalse(h.runtime.feedbackRenderable(outer,old,timing))
                hidden(observer.pixels,first.version,frozen)
                assertTrue(h.runtime.openPage()) // One pending new-page intent, not a duplicate command.
                release.complete(Unit);h.runtime.awaitPageTransition()
                val next=householdPage(h.runtime);assertNotSame(page,next)
                householdPixels(h.runtime,next,h.screen(next));assertTrue(h.feedback(next).draws.isEmpty())
                assertNotSame(observer,h.feedback(next));assertFalse(h.runtime.feedbackRenderable(h.runtime.state.value,old,timing))
                h.store.afterPrepareForTest={secondEntered.complete(Unit);secondRelease.await()}
                h.screen(next).tap("increment:${seed.habits[1].uuid}");withTimeout(20_000) {secondEntered.await()}
                busy(h,next);hidden(observer.pixels,first.version,frozen)
                secondRelease.complete(Unit);result(h,next);syncRelease.complete(Unit)
                terminal(h,http,next,10)
            } finally {release.complete(Unit);secondRelease.complete(Unit);syncRelease.complete(Unit)}
        }
    }

    @Test fun authorityReservationClearsAnonymousPixelsBeforeCredentialPersistence()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,_)=channel {successReply(it)}
        withHarness(http) {h->
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val reserved=CompletableDeferred<Unit>();val persistence=CompletableDeferred<Unit>()
            var mutation:Job?=null
            try {
                val page=h.open();val before=pageNativeState()
                h.store.afterPrepareForTest={entered.complete(Unit);release.await()}
                h.screen(page).tap("increment:${seed.habits[0].uuid}");withTimeout(20_000) {entered.await()}
                val drawn=busy(h,page);val outer=h.runtime.state.value;val state=page.state.value;val timing=requireNotNull(page.commandTiming)
                val observer=h.feedback(page);val frozen=observer.pixels.frozenMarker(drawn.version)
                authority.onTransitionForTest={reserved.complete(Unit);persistence.await()}
                mutation=launch(Dispatchers.IO) {tokens.saveLoginSession("synthetic-feedback-other","synthetic-feedback-refresh","member",id(4910),false)}
                withTimeout(20_000) {reserved.await()}
                assertFalse(page.live());assertFalse(h.runtime.feedbackRenderable(outer,state,timing))
                hidden(observer.pixels,drawn.version,frozen)
                assertEquals(0L,page.commandTiming!!.knownCommitAt);assertEquals(before,pageNativeState())
                persistence.complete(Unit);mutation.join();assertTrue(h.runtime.shutdown())
                assertEquals(before,pageNativeState())
            } finally {persistence.complete(Unit);release.complete(Unit);mutation?.join();authority.onTransitionForTest={}}
        }
    }

    @Test fun realAckInvalidatesOldPrivatePixelsWhileVerifyingGetsItsOwnProtectedFeedback()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,_)=channel {successReply(it)}
        withHarness(http) {h->
            val ackEntered=CompletableDeferred<Unit>();val ackRelease=CompletableDeferred<Unit>()
            val refreshEntered=CompletableDeferred<Unit>();val refreshRelease=CompletableDeferred<Unit>()
            val admissionEntered=CompletableDeferred<Unit>();val admissionRelease=CompletableDeferred<Unit>()
            h.runtime.beforeAcceptForTest={ackEntered.complete(Unit);ackRelease.await()}
            try {
                val page=h.open();h.screen(page).tap("increment:${seed.habits[0].uuid}");result(h,page)
                withTimeout(20_000) {ackEntered.await()};householdPixels(h.runtime,page,h.screen(page))
                val old=page.state.value;val personal=h.screen(page).frozenMarker(old.version)
                page.beforeRefreshForTest={refreshEntered.complete(Unit);refreshRelease.await()}
                ackRelease.complete(Unit);withTimeout(20_000) {refreshEntered.await()}
                assertFalse(old.view!!.valid());assertFalse(h.runtime.renderable())
                hidden(h.screen(page),old.version,personal)
                page.beforeAdmissionReadForTest={admissionEntered.complete(Unit);admissionRelease.await()}
                // Safety callback arriving after ACK invalidation. This is not a
                // claim about hardware input latency or tapping invisible content.
                InstrumentationRegistry.getInstrumentation().runOnMainSync {assertTrue(page.increment(seed.habits[1].uuid))}
                withTimeout(20_000) {admissionEntered.await()}
                val verifying=busy(h,page);assertEquals(G0HouseholdCountPage.Phase.VERIFYING,verifying.phase)
                assertFalse(old.view.valid());hidden(h.screen(page),old.version,personal)
                assertEquals(9,pageRowCount("completions"));assertEquals(0L,page.commandTiming!!.knownCommitAt)
                requireVisiblePageText(setOf("正在核验，请稍候"))
                admissionRelease.complete(Unit);val shown=result(h,page)
                assertEquals(listOf(1L,1L),seed.habits.map {shown.cards.getValue(it.uuid).history.todayQuantity})
                refreshRelease.complete(Unit);terminal(h,http,page,10)
            } finally {ackRelease.complete(Unit);refreshRelease.complete(Unit);admissionRelease.complete(Unit)}
        }
    }
}
