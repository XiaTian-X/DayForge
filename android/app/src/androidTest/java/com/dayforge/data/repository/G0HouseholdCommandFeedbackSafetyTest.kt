package com.dayforge.data.repository

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.api.NextSyncHttp
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual handler refusals and reliable commits are different semantic events.
 * These bounded interleavings claim safety, not latency or steady-state capacity. */
@RunWith(AndroidJUnit4::class)
class G0HouseholdCommandFeedbackSafetyTest:G0HouseholdCountPageFixture() {
    private inner class Harness(http:NextSyncHttp,val candidate:Boolean=true) {
        val job=SupervisorJob();val control=CoroutineScope(job+Dispatchers.IO)
        lateinit var store:G0HouseholdCountStore
        val runtime=G0HouseholdCountRuntime(control,G0CountConnectionOwner {db.close()},authority,tokens,
            {domainGate.changeSequence},http) {coldHousehold(http).also {store=it}}
        val outer=G0ScreenObservation()
        private val screens=ConcurrentHashMap<G0HouseholdCountPage,G0ScreenObservation>()
        private val feedbacks=ConcurrentHashMap<G0HouseholdCountPage,G0HouseholdCommandFeedbackObservation>()
        fun screen(page:G0HouseholdCountPage)=screens.computeIfAbsent(page) {G0ScreenObservation()}
        fun feedback(page:G0HouseholdCountPage)=feedbacks.computeIfAbsent(page) {G0HouseholdCommandFeedbackObservation()}
        suspend fun open():G0HouseholdCountPage {
            activity.scenario.onActivity {it.setContent {
                G0HouseholdCountRuntimeScreen(runtime,outer,::screen,commandFeedback=candidate,commandSeen=::feedback)
            }}
            outer.await(runtime.state.value.version);outer.tap("open")
            return householdPage(runtime).also {householdPixels(runtime,it,screen(it))}
        }
        suspend fun close()=withContext(NonCancellable) {runtime.shutdown();job.cancelAndJoin()}
    }
    private suspend fun withHarness(http:NextSyncHttp,candidate:Boolean=true,body:suspend (Harness)->Unit) {
        val h=Harness(http,candidate);try {body(h)} finally {h.close()}
    }
    private suspend fun semantic(h:Harness,page:G0HouseholdCountPage,notice:Boolean,attempt:Boolean):G0HouseholdCountPage.State {
        val observed=G0ScreenObservation.awaitTogether {
            val outer=h.runtime.state.value;val state=page.state.value;val seen=h.feedback(page)
            val targets=listOfNotNull(if(notice)seen.noticeTarget(h.runtime,outer,state) else null,
                if(attempt)seen.attemptTarget(h.runtime,outer,state) else null)
            if(targets.size!=(if(notice)1 else 0)+(if(attempt)1 else 0))null else targets
        }
        return page.events.single {it.version==observed.version}
    }
    private suspend fun hidden(frozen:(Bitmap)->Boolean) {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        withTimeout(15_000) {while(true) {
            val image=automation.takeScreenshot()
            if(image!=null) {
                val visible=try {frozen(image)} finally {image.recycle()}
                if(!visible)break
            }
            delay(16)
        }}
    }
    private suspend fun result(h:Harness,page:G0HouseholdCountPage) {
        householdPagePhase(page,G0HouseholdCountPage.Phase.RESULT);page.settled()
        householdPixels(h.runtime,page,h.screen(page))
    }
    private fun viewport(h:Harness,page:G0HouseholdCountPage,ids:List<String>) {
        val bitmap=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            for(key in ids.map {"increment:$it"}+"home") {
                // Use un-clipped layout bounds; a clipped boundsInWindow rectangle
                // could conceal content that actually extends below the display.
                val bounds=requireNotNull(h.screen(page).bounds["viewport:$key"])
                assertTrue("$key must be fully inside the actual screenshot viewport: $bounds",
                    bounds.width>0f && bounds.height>0f && bounds.left>=0f && bounds.top>=0f &&
                        bounds.right<=bitmap.width.toFloat() && bounds.bottom<=bitmap.height.toFloat())
                if(key=="home")assertEquals(40f*app.resources.displayMetrics.density,bounds.height,1f)
            }
        } finally {bitmap.recycle()}
    }
    private suspend fun terminal(h:Harness,http:NextSyncHttp,page:G0HouseholdCountPage,total:Int) {
        withTimeout(20_000) {h.runtime.awaitSynced()}
        val pixels=householdPixels(h.runtime,page,h.screen(page))
        val view=requireNotNull(page.events.single {it.version==pixels.version}.view)
        val native=pageNativeState();val deliveries=h.runtime.workerAttempts.map {requireNotNull(it.delivery)}
        assertTrue(h.runtime.shutdown());reopenReference();sameHouseholdAsOriginal(view)
        deliveries.forEach {assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(it))}
        assertEquals(native,pageNativeState());assertEquals(total,pageRowCount("completions"))
        assertEquals(total,pageRowCount("next_acceptances"));assertEquals(0,pageRowCount("sync_outbox"))
    }

    @Test fun realBusyClicksBeforeAndAfterCommitAggregateWithoutAnotherSaveOrEarlySuccess()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(2);val (http,_)=channel {successReply(it)}
        withHarness(http) {h->
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val savedEntered=CompletableDeferred<Unit>();val savedRelease=CompletableDeferred<Unit>()
            val syncRelease=CompletableDeferred<Unit>();h.runtime.beforeSyncBatchForTest={syncRelease.await()}
            try {
                val page=h.open();val before=pageNativeState()
                viewport(h,page,seed.habits.map {it.uuid})
                h.store.afterPrepareForTest={entered.complete(Unit);release.await()}
                page.afterCommitForTest={savedEntered.complete(Unit);savedRelease.await()}
                h.screen(page).tap("increment:${seed.habits[0].uuid}");withTimeout(20_000) {entered.await()}
                h.screen(page).await(page.state.value.version)
                val command=requireNotNull(page.command)
                h.screen(page).tap("increment:${seed.habits[0].uuid}")
                h.screen(page).tap("increment:${seed.habits[1].uuid}")
                val refused=semantic(h,page,notice=false,attempt=true)
                assertNull(refused.commitNotice);assertEquals(2L,refused.attemptReceipt!!.count)
                assertEquals(listOf(1L,2L),page.admissions.filter {it.outcome==G0HouseholdCountPage.AdmissionOutcome.BUSY}.map {it.attemptSequence})
                assertSame(command,refused.attemptReceipt.command);assertEquals(before,pageNativeState())
                assertEquals(0L,page.commandTiming!!.knownCommitAt)
                requireVisiblePageText(setOf("刚才2次点击未记录，请稍后再试"))
                val stale=requireNotNull(h.feedback(page).attemptTarget(h.runtime,h.runtime.state.value,refused))
                release.complete(Unit);withTimeout(20_000) {savedEntered.await()}
                val committed=semantic(h,page,notice=true,attempt=true)
                val notice=requireNotNull(committed.commitNotice)
                assertSame(command,notice.command);assertEquals(page.saved,notice.saved)
                assertEquals(command.requestId,notice.saved.requestId);assertEquals(command.factUuid,notice.saved.factUuid)
                assertEquals(committed.intent!!.firstStateVersion,notice.firstStateVersion)
                assertFalse(stale.valid());assertEquals(3,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
                requireVisiblePageText(setOf("上一笔已保存在此设备，正在更新","刚才2次点击未记录，请稍后再试"))
                h.screen(page).tap("increment:${seed.habits[1].uuid}")
                val third=semantic(h,page,notice=true,attempt=true)
                assertEquals(3L,third.attemptReceipt!!.count);assertEquals(1L,third.attemptReceipt.firstSequence)
                assertEquals(3L,third.attemptReceipt.sequence);assertSame(notice,third.commitNotice)
                assertEquals(4,h.screen(page).clicks.count {it.key.startsWith("increment:")})
                assertEquals(1,page.commandRecords.size);assertSame(command,page.command)
                savedRelease.complete(Unit);result(h,page);syncRelease.complete(Unit)
                terminal(h,http,page,3)
                assertEquals(1,page.admissions.count {it.outcome==G0HouseholdCountPage.AdmissionOutcome.ACCEPTED})
            } finally {release.complete(Unit);savedRelease.complete(Unit);syncRelease.complete(Unit)}
        }
    }

    @Test fun defaultDisabledTouchesAndNonMemberCallbacksDoNotCreateSemanticReceipts()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(2);val (http,_)=channel {successReply(it)}
        withHarness(http,candidate=false) {h->
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            try {
                val page=h.open();val before=pageNativeState()
                h.store.afterPrepareForTest={entered.complete(Unit);release.await()}
                h.screen(page).tap("increment:${seed.habits[0].uuid}");withTimeout(20_000) {entered.await()}
                h.screen(page).await(page.state.value.version);val state=page.state.value
                h.screen(page).tap("increment:${seed.habits[1].uuid}")
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    assertFalse(page.increment(seed.habits[1].uuid))
                    assertFalse(page.increment(id(9234),reportBusy=true))
                }
                assertSame(state,page.state.value);assertNull(state.attemptReceipt);assertNull(state.commitNotice)
                assertEquals(1,h.screen(page).clicks.count {it.key.startsWith("increment:")})
                assertTrue(page.admissions.all {it.attemptSequence==null});assertTrue(h.feedback(page).draws.isEmpty())
                assertTrue(h.runtime.shutdown());assertEquals(before,pageNativeState())
                assertFalse(page.increment(seed.habits[0].uuid,reportBusy=true))
                assertNull(page.state.value.intent);assertNull(page.state.value.attemptReceipt)
            } finally {release.complete(Unit)}
        }
    }

    @Test fun nextUnknownCommitCannotBorrowThePreviousCommandsNotice()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(2);val (http,server)=channel {successReply(it)}
        withHarness(http) {h->
            val page=h.open();h.screen(page).tap("increment:${seed.habits[0].uuid}");result(h,page)
            withTimeout(20_000) {h.runtime.awaitSynced()};householdPixels(h.runtime,page,h.screen(page))
            val first=semantic(h,page,notice=true,attempt=false);val notice=requireNotNull(first.commitNotice)
            val stale=requireNotNull(h.feedback(page).noticeTarget(h.runtime,h.runtime.state.value,first))
            val frozen=h.feedback(page).noticePixels.frozenMarker(first.version)
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            try {
                h.store.afterPrepareForTest={entered.complete(Unit);release.await()}
                val trace=G0PhaseTrace(driverTiming=false);page.trace=trace;endFaults.failAfterNextEndIn(trace)
                h.screen(page).tap("increment:${seed.habits[1].uuid}");withTimeout(20_000) {entered.await()}
                assertNotSame(notice.command,page.command);assertNull(page.state.value.commitNotice)
                assertNull(page.state.value.attemptReceipt);assertFalse(stale.valid());hidden(frozen)
                release.complete(Unit);page.settled();assertTrue(h.runtime.shutdown())
                assertEquals("G0_COMMIT_RESULT_LOST",page.error?.message)
                assertEquals(notice.saved,page.saved);assertEquals(0L,page.commandTiming!!.knownCommitAt)
                assertNull(page.state.value.commitNotice);assertNull(page.state.value.attemptReceipt)
                assertFalse(page.increment(seed.habits[1].uuid,reportBusy=true));assertFalse(h.runtime.openPage())
                assertEquals(4,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
                assertEquals(1,server.requests.count {it.path.endsWith("/push")});assertEquals(1,h.runtime.workerAttempts.size)
            } finally {release.complete(Unit)}
        }
    }

    @Test fun leavingAndWarmReentryRetireOldNoticeAndAllBusyReceipts()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(2);val (http,_)=channel {successReply(it)}
        withHarness(http) {h->
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val syncRelease=CompletableDeferred<Unit>();h.runtime.beforeSyncBatchForTest={syncRelease.await()}
            try {
                val page=h.open();page.afterCommitForTest={entered.complete(Unit);release.await()}
                h.screen(page).tap("increment:${seed.habits[0].uuid}");withTimeout(20_000) {entered.await()}
                semantic(h,page,notice=true,attempt=false);h.screen(page).tap("increment:${seed.habits[1].uuid}")
                val state=semantic(h,page,notice=true,attempt=true);val outer=h.runtime.state.value
                val notice=requireNotNull(h.feedback(page).noticeTarget(h.runtime,outer,state))
                val receipt=requireNotNull(h.feedback(page).attemptTarget(h.runtime,outer,state))
                val a=h.feedback(page).noticePixels.frozenMarker(state.version);val b=h.feedback(page).attemptPixels.frozenMarker(state.version)
                h.outer.tap("leave");assertFalse(notice.valid());assertFalse(receipt.valid())
                assertNull(page.state.value.commitNotice);assertNull(page.state.value.attemptReceipt);hidden {a(it)||b(it)}
                release.complete(Unit);h.runtime.awaitPageTransition()
                assertTrue(h.runtime.openPage());val next=householdPage(h.runtime)
                householdPixels(h.runtime,next,h.screen(next));assertNotSame(page,next)
                assertNull(next.state.value.intent);assertNull(next.state.value.commitNotice);assertNull(next.state.value.attemptReceipt)
                assertTrue(h.feedback(next).draws.isEmpty());assertEquals(1,h.runtime.coldStarts)
                syncRelease.complete(Unit);terminal(h,http,next,3)
            } finally {release.complete(Unit);syncRelease.complete(Unit)}
        }
    }

    @Test fun authorityReservationAndAbaRetireSemanticPixelsBeforePersistence()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(2);val (http,_)=channel {successReply(it)}
        withHarness(http) {h->
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val reserved=CompletableDeferred<Unit>();val persistence=CompletableDeferred<Unit>()
            var mutation:Job?=null
            h.runtime.beforeSyncBatchForTest={awaitCancellation()}
            try {
                val page=h.open();page.afterCommitForTest={entered.complete(Unit);release.await()}
                h.screen(page).tap("increment:${seed.habits[0].uuid}");withTimeout(20_000) {entered.await()}
                semantic(h,page,notice=true,attempt=false);h.screen(page).tap("increment:${seed.habits[1].uuid}")
                val state=semantic(h,page,notice=true,attempt=true);val old=h.runtime.state.value
                val notice=requireNotNull(h.feedback(page).noticeTarget(h.runtime,old,state))
                val receipt=requireNotNull(h.feedback(page).attemptTarget(h.runtime,old,state))
                val a=h.feedback(page).noticePixels.frozenMarker(state.version);val b=h.feedback(page).attemptPixels.frozenMarker(state.version)
                val native=pageNativeState();val original=dataStore.data.first()
                authority.onTransitionForTest={reserved.complete(Unit);persistence.await()}
                mutation=launch(Dispatchers.IO) {dataStore.edit {it[stringPreferencesKey("sync_device_capability_revision")]="2"}}
                withTimeout(20_000) {reserved.await()}
                assertFalse(notice.valid());assertFalse(receipt.valid());assertFalse(page.live());hidden {a(it)||b(it)}
                assertNull(page.state.value.intent);assertNull(page.state.value.commitNotice);assertNull(page.state.value.attemptReceipt)
                assertEquals(native,pageNativeState());persistence.complete(Unit);mutation.join();authority.onTransitionForTest={}
                dataStore.updateData {original};assertEquals(original,dataStore.data.first())
                assertFalse(notice.valid());assertFalse(receipt.valid());assertFalse(h.runtime.openPage())
                assertTrue(h.runtime.shutdown());assertEquals(native,pageNativeState())
            } finally {persistence.complete(Unit);release.complete(Unit);mutation?.join();authority.onTransitionForTest={}}
        }
    }

    @Test fun ackInvalidationKeepsOnlyAnonymousCommitNoticeAndRefreshRetiresItsOldState()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(2);val (http,_)=channel {successReply(it)}
        withHarness(http) {h->
            val ackEntered=CompletableDeferred<Unit>();val ackRelease=CompletableDeferred<Unit>()
            val refreshEntered=CompletableDeferred<Unit>();val refreshRelease=CompletableDeferred<Unit>()
            h.runtime.beforeAcceptForTest={ackEntered.complete(Unit);ackRelease.await()}
            try {
                val page=h.open();h.screen(page).tap("increment:${seed.habits[0].uuid}");result(h,page)
                withTimeout(20_000) {ackEntered.await()};householdPixels(h.runtime,page,h.screen(page))
                val prior=semantic(h,page,notice=true,attempt=false);val outer=h.runtime.state.value
                val notice=requireNotNull(h.feedback(page).noticeTarget(h.runtime,outer,prior))
                val privatePixels=h.screen(page).frozenMarker(prior.version)
                page.beforeRefreshForTest={refreshEntered.complete(Unit);refreshRelease.await()}
                ackRelease.complete(Unit);withTimeout(20_000) {refreshEntered.await()}
                assertFalse(prior.view!!.valid());assertFalse(h.runtime.renderable());assertTrue(notice.valid())
                hidden(privatePixels);semantic(h,page,notice=true,attempt=false)
                requireVisiblePageText(setOf("上一笔已保存在此设备"))
                assertNull(page.state.value.attemptReceipt);assertEquals(3,pageRowCount("completions"))
                refreshRelease.complete(Unit);householdPixels(h.runtime,page,h.screen(page))
                val fresh=semantic(h,page,notice=true,attempt=false)
                assertFalse(notice.valid());assertSame(prior.commitNotice,fresh.commitNotice)
                terminal(h,http,page,3)
            } finally {ackRelease.complete(Unit);refreshRelease.complete(Unit)}
        }
    }
}
