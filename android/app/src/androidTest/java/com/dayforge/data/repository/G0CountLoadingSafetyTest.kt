package com.dayforge.data.repository

import androidx.activity.compose.setContent
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.api.NextSyncHttp
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The channel is prepared BEFORE entry subscription. A cold factory must never
 * change DataStore merely to construct its HTTP dependency: that is a revocation. */
abstract class G0CountLoadingFixture:G0ShortCountScreenFixture() {
    override val jointCountAuthority=true
    internal suspend fun coldCount(http:NextSyncHttp):G0ShortCountStore {
        val schema=trustedDomainSchema(batchedOpening=true)
        freshGateForColdOpen(recurringReads=true,countWrites=true);storage.reopen()
        val current=requireNotNull(db.habitDao().getHabitById(habit.id))
        return G0ShortCountStore(db,tokens,sessions,authority,domainGate,sender(http),
            jointAuthority=jointCountAuthority).also {it.initialize(current,schema)}
    }
    internal fun installLoading(entry:G0CountLoadingEntry,seen:G0ScreenObservation,pageSeen:G0ScreenObservation) {
        activity.scenario.onActivity {it.setContent {G0CountLoadingScreen(entry,seen,pageSeen)}}
    }
    internal suspend fun entryPhase(entry:G0CountLoadingEntry,value:G0CountLoadingEntry.Phase)=
        withTimeout(20_000) {entry.state.first {it.phase==value}}
}

@RunWith(AndroidJUnit4::class)
class G0CountLoadingSafetyTest:G0CountLoadingFixture() {
    private suspend fun withControl(body:suspend (CoroutineScope)->Unit) {
        val job=SupervisorJob();val control=CoroutineScope(job+Dispatchers.IO)
        try {body(control)} finally {withContext(NonCancellable) {job.cancelAndJoin()}}
    }
    private fun neverReady(entry:G0CountLoadingEntry) {
        assertFalse(entry.renderable())
        assertFalse(entry.events.any {it.phase==G0CountLoadingEntry.Phase.READY})
        assertNull(entry.state.value.page)
    }
    private fun visibleText():Set<String> {
        val texts=mutableSetOf<String>()
        fun visit(node:android.view.accessibility.AccessibilityNodeInfo) {
            if(node.isVisibleToUser)node.text?.toString()?.let(texts::add)
            for(i in 0 until node.childCount)node.getChild(i)?.let(::visit)
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(::visit)
        return texts
    }

    @Test fun loadingPixelsContainNoPersonalResultThenTapAckAndOriginalReadersAgree()=runBlocking<Unit> {
        seedAccepted(2);val (http,server)=channel {successReply(it)}
        val before=pageNativeState();val credentials=dataStore.data.first()
        withControl {control->
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val closes=AtomicInteger();val owner=G0CountConnectionOwner {closes.incrementAndGet();db.close()}
            var store:G0ShortCountStore?=null
            val entry=G0CountLoadingEntry(control,owner,authority,tokens,{domainGate.changeSequence}) {
                entered.complete(Unit);release.await();coldCount(http).also {store=it}
            }
            val seen=G0ScreenObservation();val pageSeen=G0ScreenObservation()
            try {
                installLoading(entry,seen,pageSeen);seen.await(entry.state.value.version);seen.tap("open")
                withTimeout(10_000) {entered.await()}
                val loading=entryPhase(entry,G0CountLoadingEntry.Phase.LOADING);seen.await(loading.version)
                requireVisiblePageText(setOf("正在准备页面，请稍候","取消"))
                assertFalse(visibleText().any {it.startsWith("卡片：") || it.startsWith("首页：") || it==habit.name})
                assertNull(loading.page);assertFalse(entry.renderable());assertNull(entry.retiredPage)
                assertEquals(before,pageNativeState());assertTrue(server.requests.isEmpty())
                release.complete(Unit)
                val ready=entryPhase(entry,G0CountLoadingEntry.Phase.READY);val page=requireNotNull(ready.page)
                pageSeen.await(page.state.value.version);assertTrue(entry.renderable())
                assertEquals(credentials,dataStore.data.first())
                requireVisiblePageText(setOf("卡片：2","首页：2 / 1000000"))
                pageSeen.tap("increment");phase(page,G0ShortCountPage.Phase.RESULT);page.settled()
                assertNull(page.error);pageSeen.awaitAny {listOf(page.state.value.version)}
                val saved=requireNotNull(page.saved);val live=requireNotNull(store)
                assertEquals(3,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
                val delivered=control.async {requireNotNull(live.send(http,saved.requestId))}.await()
                assertEquals(NextOperationAcceptance.COMMITTED,control.async {live.acknowledge(delivered)}.await())
                // The loading entry must install its own observer. No manual refresh.
                withTimeout(20_000) {page.state.first {it.view?.valid()==true}}
                pageSeen.awaitAny {listOf(page.state.value.version)}
                requireVisiblePageText(setOf("卡片：3","首页：3 / 1000000","已保存"))
                val final=requireNotNull(page.state.value.view);val terminal=pageNativeState()
                assertEquals(saved.factUuid,final.followUp.claim.completionUuid)
                assertEquals(NextOperationAcceptance.REPLAYED,control.async {live.acknowledge(delivered)}.await())
                assertEquals(terminal,pageNativeState());assertEquals(1,server.requests.count {it.path.endsWith("/push")})
                entry.shutdown();assertTrue(entry.drained);assertFalse(owner.busy());assertEquals(1,closes.get())
                reopenReference();sameAsOriginal(final)
                assertEquals(3,pageRowCount("next_acceptances"));assertEquals(0,pageRowCount("sync_outbox"))
                assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(delivered))
                assertEquals(terminal,pageNativeState())
            } finally {release.complete(Unit);entry.shutdown()}
        }
    }

    @Test fun cancelledNonCancellableFactoryRetainsConnectionUntilDrainAndBusyCanRetry()=runBlocking<Unit> {
        seedAccepted(1);val (http,_)=channel {successReply(it)};val before=pageNativeState()
        withControl {control->
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val closes=AtomicInteger();val calls=AtomicInteger()
            val owner=G0CountConnectionOwner {closes.incrementAndGet();db.close()}
            val first=G0CountLoadingEntry(control,owner,authority,tokens,{domainGate.changeSequence}) {
                calls.incrementAndGet()
                withContext(NonCancellable) {coldCount(http).also {entered.complete(Unit);release.await()}}
            }
            val second=G0CountLoadingEntry(control,owner,authority,tokens,{domainGate.changeSequence}) {
                calls.incrementAndGet();coldCount(http)
            }
            val seen=G0ScreenObservation();val pageSeen=G0ScreenObservation()
            try {
                installLoading(first,seen,pageSeen);seen.await(first.state.value.version);seen.tap("open")
                withTimeout(20_000) {entered.await()};seen.await(first.state.value.version);seen.tap("cancel")
                val closed=entryPhase(first,G0CountLoadingEntry.Phase.CLOSED);seen.await(closed.version)
                requireVisiblePageText(setOf("已关闭页面"))
                assertTrue(owner.busy());assertFalse(first.drained);assertEquals(0,closes.get());neverReady(first)
                assertFalse(second.open());assertEquals(G0CountLoadingEntry.Phase.BUSY,second.state.value.phase)
                assertEquals(1,calls.get());assertEquals(before,pageNativeState())
                release.complete(Unit);first.shutdown()
                assertTrue(first.drained);assertFalse(owner.busy());assertEquals(1,closes.get());neverReady(first)
                assertTrue(second.open());entryPhase(second,G0CountLoadingEntry.Phase.READY)
                assertEquals(2,calls.get());assertTrue(second.renderable())
                second.shutdown();assertFalse(owner.busy());assertEquals(2,closes.get())
                assertEquals(before,pageNativeState())
            } finally {release.complete(Unit);first.shutdown();second.shutdown()}
        }
    }

    @Test fun authorityReservationClosesColdFactoryBeforePersistenceAndAbaCannotReviveIt()=runBlocking<Unit> {
        seedAccepted(1);val (http,_)=channel {successReply(it)}
        val before=pageNativeState();val original=dataStore.data.first()
        withControl {control->
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val reserved=CompletableDeferred<Unit>();val persist=CompletableDeferred<Unit>()
            val owner=G0CountConnectionOwner {db.close()}
            val entry=G0CountLoadingEntry(control,owner,authority,tokens,{domainGate.changeSequence}) {
                withContext(NonCancellable) {coldCount(http).also {entered.complete(Unit);release.await()}}
            }
            var mutation:Deferred<Unit>?=null
            try {
                assertTrue(entry.open());withTimeout(20_000) {entered.await()}
                authority.onTransitionForTest={reserved.complete(Unit);persist.await()}
                mutation=control.async {
                    dataStore.edit {it[stringPreferencesKey("sync_device_capability_revision")]="2"};Unit
                }
                withTimeout(10_000) {reserved.await()}
                assertEquals(original,dataStore.data.first());entryPhase(entry,G0CountLoadingEntry.Phase.CLOSED)
                neverReady(entry);assertTrue(owner.busy());assertFalse(entry.drained)
                authority.onTransitionForTest={};persist.complete(Unit);requireNotNull(mutation).await()
                dataStore.updateData {original};assertEquals(original,dataStore.data.first())
                release.complete(Unit);entry.shutdown();neverReady(entry)
                assertTrue(entry.drained);assertFalse(owner.busy());assertEquals(before,pageNativeState())
            } finally {
                authority.onTransitionForTest={};persist.complete(Unit);release.complete(Unit)
                mutation?.join();entry.shutdown()
            }
        }
    }

    @Test fun authorityAbaAfterPageOpenCannotPublishTheAlreadyPreparedPrivateView()=runBlocking<Unit> {
        seedAccepted(1);val (http,_)=channel {successReply(it)}
        val before=pageNativeState();val original=dataStore.data.first()
        withControl {control->
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val owner=G0CountConnectionOwner {db.close()}
            val entry=G0CountLoadingEntry(control,owner,authority,tokens,{domainGate.changeSequence}) {coldCount(http)}
            entry.afterPageOpenForTest={withContext(NonCancellable) {entered.complete(Unit);release.await()}}
            try {
                assertTrue(entry.open());withTimeout(20_000) {entered.await()}
                val page=requireNotNull(entry.retiredPage);val privateView=requireNotNull(page.state.value.view)
                assertEquals(G0ShortCountPage.Phase.READY,page.state.value.phase)
                assertEquals(G0CountLoadingEntry.Phase.LOADING,entry.state.value.phase);assertNull(entry.state.value.page)
                dataStore.edit {it[stringPreferencesKey("sync_device_capability_revision")]="2"}
                dataStore.updateData {original};assertEquals(original,dataStore.data.first())
                entryPhase(entry,G0CountLoadingEntry.Phase.CLOSED);neverReady(entry)
                assertFalse(privateView.valid());assertFalse(page.live());assertNull(page.state.value.view)
                assertFalse(page.increment());release.complete(Unit);entry.shutdown()
                assertTrue(entry.drained);assertFalse(owner.busy());assertEquals(before,pageNativeState())
            } finally {release.complete(Unit);entry.shutdown()}
        }
    }

    @Test fun partialOpenFailureBecomesRecoveryAndClosesExactlyOnce()=runBlocking<Unit> {
        seedAccepted(1);channel {successReply(it)};val before=pageNativeState()
        withControl {control->
            val closes=AtomicInteger();val failure=IOException("G0_LOADING_PARTIAL_OPEN")
            val owner=G0CountConnectionOwner {closes.incrementAndGet();db.close()}
            val entry=G0CountLoadingEntry(control,owner,authority,tokens,{domainGate.changeSequence}) {
                freshGateForColdOpen(recurringReads=true,countWrites=true);storage.reopen()
                db.openHelper.writableDatabase
                throw failure
            }
            val seen=G0ScreenObservation();val pageSeen=G0ScreenObservation()
            try {
                installLoading(entry,seen,pageSeen);seen.await(entry.state.value.version);seen.tap("open")
                val recovery=entryPhase(entry,G0CountLoadingEntry.Phase.RECOVERY);seen.await(recovery.version)
                requireVisiblePageText(setOf("需要重新核验，请重新打开页面"));assertSame(failure,entry.error);neverReady(entry)
                entry.shutdown();entry.shutdown()
                assertTrue(entry.drained);assertFalse(owner.busy());assertEquals(1,closes.get())
                assertEquals(before,pageNativeState())
            } finally {entry.shutdown()}
        }
    }

    @Test fun failedCloseLeavesOwnerQuarantinedAndDoesNotLetAnotherFactoryRun()=runBlocking<Unit> {
        seedAccepted(1);val (http,_)=channel {successReply(it)};val before=pageNativeState()
        withControl {control->
            val closes=AtomicInteger();val calls=AtomicInteger();val closeFailure=IOException("G0_LOADING_CLOSE_FAILED")
            val owner=G0CountConnectionOwner {closes.incrementAndGet();throw closeFailure}
            val first=G0CountLoadingEntry(control,owner,authority,tokens,{domainGate.changeSequence}) {
                calls.incrementAndGet();coldCount(http);throw IOException("G0_LOADING_AFTER_INITIALIZE")
            }
            val second=G0CountLoadingEntry(control,owner,authority,tokens,{domainGate.changeSequence}) {
                calls.incrementAndGet();coldCount(http)
            }
            try {
                assertTrue(first.open());entryPhase(first,G0CountLoadingEntry.Phase.RECOVERY);first.shutdown()
                assertSame(closeFailure,first.error);assertFalse(first.drained);assertTrue(owner.busy())
                assertFalse(second.open());assertEquals(G0CountLoadingEntry.Phase.BUSY,second.state.value.phase)
                assertEquals(1,calls.get());assertEquals(1,closes.get());neverReady(first);neverReady(second)
                first.shutdown();assertEquals(1,closes.get());assertEquals(before,pageNativeState())
            } finally {
                first.shutdown();second.shutdown()
                // Test cleanup only. The quarantined owner itself remains unavailable.
                db.close()
            }
        }
    }

    @Test fun parentCancellationBeforeIoDispatchStillDrainsTheReservedTicket()=runBlocking<Unit> {
        seedAccepted(1);val (http,_)=channel {successReply(it)};val before=pageNativeState()
        withControl {control->
            val calls=AtomicInteger();val closes=AtomicInteger()
            val owner=G0CountConnectionOwner {closes.incrementAndGet();db.close()}
            val entry=G0CountLoadingEntry(control,owner,authority,tokens,{domainGate.changeSequence}) {
                calls.incrementAndGet();coldCount(http)
            }
            // StateFlow resumes this unconfined collector in emit(LOADING), before
            // open() reaches launch(IO). This is a deterministic lifecycle interleave.
            val cancelOnLoading=launch(Dispatchers.Unconfined,start=CoroutineStart.UNDISPATCHED) {
                entry.state.first {it.phase==G0CountLoadingEntry.Phase.LOADING}
                control.cancel()
            }
            try {
                assertTrue(entry.open());assertTrue(cancelOnLoading.isCompleted)
                entryPhase(entry,G0CountLoadingEntry.Phase.CLOSED);entry.shutdown()
                assertEquals(0,calls.get());assertEquals(1,closes.get());assertTrue(entry.drained)
                assertFalse(owner.busy());neverReady(entry);assertEquals(before,pageNativeState())
            } finally {cancelOnLoading.cancelAndJoin();entry.shutdown()}
        }
    }

    @Test fun realPageOpenSwallowingCancellationCannotBecomeEntryReady()=runBlocking<Unit> {
        seedAccepted(1);val (http,_)=channel {successReply(it)};val before=pageNativeState()
        withControl {control->
            val entered=CompletableDeferred<Unit>();val closes=AtomicInteger()
            val owner=G0CountConnectionOwner {closes.incrementAndGet();db.close()}
            val entry=G0CountLoadingEntry(control,owner,authority,tokens,{domainGate.changeSequence}) {
                coldCount(http).also {store->store.afterEntryAuthenticationForTest={entered.complete(Unit);awaitCancellation()}}
            }
            try {
                assertTrue(entry.open());withTimeout(20_000) {entered.await()}
                val page=requireNotNull(entry.retiredPage)
                control.cancel();entryPhase(entry,G0CountLoadingEntry.Phase.CLOSED);entry.shutdown()
                // Page.open catches this real coroutine cancellation and returns normally.
                // The entry must inspect its own task and cannot equate return with READY.
                assertTrue(page.error is CancellationException)
                assertFalse(page.events.any {it.phase==G0ShortCountPage.Phase.READY})
                assertFalse(page.live());assertNull(page.state.value.view);neverReady(entry)
                assertTrue(entry.drained);assertFalse(owner.busy());assertEquals(1,closes.get())
                assertEquals(before,pageNativeState())
            } finally {entry.shutdown()}
        }
    }

    @Test fun unknownCommitSurvivesClosedEntryAndColdRecoveryNeverRepeatsItsCommand()=runBlocking<Unit> {
        seedAccepted(2);val (http,_)=channel {successReply(it)}
        withControl {control->
            val closes=AtomicInteger();val owner=G0CountConnectionOwner {closes.incrementAndGet();db.close()}
            val first=G0CountLoadingEntry(control,owner,authority,tokens,{domainGate.changeSequence}) {coldCount(http)}
            var second:G0CountLoadingEntry?=null;var recoveredStore:G0ShortCountStore?=null
            val seen=G0ScreenObservation();val pageSeen=G0ScreenObservation()
            try {
                installLoading(first,seen,pageSeen);seen.await(first.state.value.version);seen.tap("open")
                val page=requireNotNull(entryPhase(first,G0CountLoadingEntry.Phase.READY).page)
                pageSeen.await(page.state.value.version)
                val trace=G0PhaseTrace(driverTiming=false);page.trace=trace;endFaults.failAfterNextEndIn(trace)
                pageSeen.tap("increment");val recovery=phase(page,G0ShortCountPage.Phase.RECOVERY)
                pageSeen.await(recovery.version);page.settled()
                assertEquals("G0_COMMIT_RESULT_LOST",page.error?.message)
                assertFalse(page.events.any {it.phase in setOf(G0ShortCountPage.Phase.SAVED,G0ShortCountPage.Phase.RESULT)})
                assertEquals(0L,page.commandTiming!!.knownCommitAt);assertFalse(page.increment())
                val command=requireNotNull(page.command);assertEquals(3,pageRowCount("completions"))
                assertEquals(1,pageRowCount("sync_outbox"));first.shutdown()
                assertSame(page,first.retiredPage);assertEquals(command,first.retiredPage!!.command)
                assertTrue(first.drained);assertFalse(owner.busy());assertFalse(page.live())
                val next=G0CountLoadingEntry(control,owner,authority,tokens,{domainGate.changeSequence}) {
                    coldCount(http).also {recoveredStore=it}
                };second=next
                assertTrue(next.open());entryPhase(next,G0CountLoadingEntry.Phase.READY)
                val before=pageNativeState();val live=requireNotNull(recoveredStore)
                val found=control.async {requireNotNull(live.recover(command))}.await()
                assertEquals(command.requestId,found.requestId);assertEquals(command.factUuid,found.factUuid)
                assertEquals(before,pageNativeState());assertEquals(3,pageRowCount("completions"))
                assertEquals("G0_COUNT_COMMAND_ALREADY_EXISTS",
                    control.async {runCatching {live.increase(command)}.exceptionOrNull()?.message}.await())
                assertEquals(before,pageNativeState());assertEquals(1,pageRowCount("sync_outbox"))
                next.shutdown();assertTrue(next.drained);assertFalse(owner.busy());assertEquals(2,closes.get())
                reopenReference();assertEquals(3L,readHome().counts.getValue(habit.id).todayQuantity)
            } finally {first.shutdown();second?.shutdown()}
        }
    }
}
