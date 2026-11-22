package com.dayforge.data.repository

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

abstract class G0ShortCountScreenFixture:G0ShortCountFixture() {
    internal fun install(page:G0ShortCountPage,seen:G0ScreenObservation) {
        activity.scenario.onActivity {it.setContent {G0ShortCountScreen(page,seen)}}
    }
    internal suspend fun phase(page:G0ShortCountPage,value:G0ShortCountPage.Phase)=withTimeout(20_000) {page.state.first {it.phase==value}}
}

@RunWith(AndroidJUnit4::class)
open class G0ShortCountScreenSafetyTest:G0ShortCountScreenFixture() {
    @Test fun tapPublishesSavedAfterCommitThenOneVersionForCardHomeAndClaim()=runBlocking<Unit> {
        seedAccepted(2);val store=openCount();val page=G0ShortCountPage(store,tokens,this);val seen=G0ScreenObservation()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        page.afterCommitForTest={entered.complete(Unit);release.await()}
        try {
            page.open();install(page,seen);seen.await(page.state.value.version);val start=seen.tap("increment");entered.await()
            val saved=phase(page,G0ShortCountPage.Phase.SAVED);val pixels=seen.await(saved.version)
            assertTrue(pixels.observed>=page.committedAt);assertTrue(page.committedAt>start)
            assertNull(saved.view);assertFalse(page.increment());assertEquals(3,pageRowCount("completions"))
            release.complete(Unit)
            val result=phase(page,G0ShortCountPage.Phase.RESULT);seen.await(result.version);page.settled()
            val view=requireNotNull(result.view);assertEquals(3L,view.history.todayQuantity)
            assertEquals(page.saved!!.factUuid,view.followUp.claim.completionUuid)
            requireVisiblePageText(setOf("卡片：3","首页：3 / 1000000","已保存"))
            reopenReference();sameAsOriginal(view)
        } finally {release.complete(Unit);page.close();page.settled()}
    }
    @Test fun actualLostCommitNeverDisplaysSavedOrAllowsSecondTap()=runBlocking<Unit> {
        seedAccepted(2);val store=openCount();val page=G0ShortCountPage(store,tokens,this);val seen=G0ScreenObservation()
        try {
            page.open();install(page,seen);seen.await(page.state.value.version)
            val trace=G0PhaseTrace(driverTiming=false);page.trace=trace;endFaults.failAfterNextEndIn(trace)
            seen.tap("increment");val unknown=phase(page,G0ShortCountPage.Phase.RECOVERY);seen.await(unknown.version);page.settled()
            assertEquals("G0_COMMIT_RESULT_LOST",page.error?.message)
            assertFalse(page.events.any {it.phase in setOf(G0ShortCountPage.Phase.SAVED,G0ShortCountPage.Phase.RESULT)})
            assertFalse(page.increment());assertEquals(0L,page.committedAt);assertEquals(3,pageRowCount("completions"))
            requireVisiblePageText(setOf("需要核验结果，请勿重复记录"))
            val command=requireNotNull(page.command);page.close();page.settled()
            reopenReference();val recovered=openCount();val before=pageNativeState()
            val found=requireNotNull(recovered.recover(command))
            assertEquals(command.requestId,found.requestId);assertEquals(command.factUuid,found.factUuid)
            assertEquals(before,pageNativeState());assertEquals(3,pageRowCount("completions"))
        } finally {page.close();page.settled()}
    }
    @Test fun compositionExitCancelsPreparedWriteAndRevocationClearsOldView()=runBlocking<Unit> {
        seedAccepted(2);val store=openCount();val page=G0ShortCountPage(store,tokens,this);val seen=G0ScreenObservation()
        val entered=CompletableDeferred<Unit>();store.afterPrepareForTest={entered.complete(Unit);awaitCancellation()}
        try {
            page.open();val old=page.state.value.view!!;install(page,seen);seen.await(page.state.value.version)
            seen.tap("increment");entered.await()
            activity.scenario.onActivity {it.setContent {Text("已离开计数页面")}}
            phase(page,G0ShortCountPage.Phase.CLOSED);page.settled();assertEquals(2,pageRowCount("completions"))
            tokens.saveLoginSession("synthetic-other","synthetic-refresh","member",id(2990),false)
            assertFalse(old.valid());assertNull(page.state.value.view);assertFalse(page.increment())
        } finally {page.close();page.settled()}
    }
    @Test fun cancelledOwnerCannotLeaveTheScreenSavingOrCommit()=runBlocking<Unit> {
        seedAccepted(2);val store=openCount();val owner=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val page=G0ShortCountPage(store,tokens,owner)
        try {
            page.open();owner.cancel();assertTrue(page.increment());page.settled()
            assertEquals(G0ShortCountPage.Phase.RECOVERY,page.state.value.phase)
            assertEquals(2,pageRowCount("completions"));assertEquals(0L,page.committedAt)
        } finally {page.close();page.settled()}
    }
    @Test fun continuousTapsKeepNetworkIndependentAndAckRefreshesOnlyThroughFreshReads()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount();val prior=store.increase()
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val (http,_)=channel {input->
            if(input.path.endsWith("/push") && entered.count>0) {entered.countDown();check(release.await(30,java.util.concurrent.TimeUnit.SECONDS))}
            successReply(input)
        }
        val worker=G0ShortCountSyncWorker(store,http,this);worker.offer(prior.requestId)
        val page=G0ShortCountPage(store,tokens,this);val seen=G0ScreenObservation()
        try {
            page.open();val old=page.state.value.view!!;page.watchChanges(domainGate.changeSequence);install(page,seen)
            seen.awaitAny {listOf(page.state.value.version)};worker.start()
            assertTrue(withContext(Dispatchers.IO) {entered.await(8,java.util.concurrent.TimeUnit.SECONDS)})
            assertFalse(old.valid())
            withTimeout(10_000) {page.state.first {it.view?.valid()==true}}
            repeat(3) {index->
                val version=page.state.value.version;seen.tap("increment")
                withTimeout(20_000) {page.state.first {it.version>version && it.phase in setOf(G0ShortCountPage.Phase.RESULT,G0ShortCountPage.Phase.RECOVERY)}}
                page.settled();assertNull(page.error);seen.awaitAny {listOf(page.state.value.version)}
                assertEquals((index+3).toLong(),page.state.value.view!!.history.todayQuantity)
                assertTrue(worker.offer(page.saved!!.requestId))
            }
            assertEquals(4,pageRowCount("sync_outbox"));release.countDown();worker.finish()
            withTimeout(10_000) {page.state.first {it.view?.valid()==true}}
            seen.awaitAny {listOf(page.state.value.version)};requireVisiblePageText(setOf("卡片：5","首页：5 / 1000000","已保存"))
            assertEquals(0,pageRowCount("sync_outbox"));val result=page.state.value.view!!
            page.close();page.settled();reopenReference();sameAsOriginal(result)
        } finally {release.countDown();worker.shutdown();page.close();page.settled()}
    }
    @Test fun closedPageObserverCannotPublishAfterBackgroundAck()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount();val fact=store.increase();val page=G0ShortCountPage(store,tokens,this)
        page.open();page.watchChanges(domainGate.changeSequence);page.close();page.settled();val closed=page.state.value
        val (http,_)=channel {successReply(it)};val worker=G0ShortCountSyncWorker(store,http,this)
        worker.offer(fact.requestId);worker.finish();assertEquals(closed,page.state.value);assertNull(page.state.value.view)
        assertFalse(page.increment());assertEquals(2,pageRowCount("completions"));assertEquals(0,pageRowCount("sync_outbox"))
    }
    @Test fun invalidationHintCannotBypassFreshReadAuthentication()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount();val page=G0ShortCountPage(store,tokens,this)
        try {
            page.open();val stale=page.state.value.view!!;store.increase();assertFalse(stale.valid())
            failDecrypt=true;page.watchChanges(domainGate.changeSequence)
            phase(page,G0ShortCountPage.Phase.RECOVERY);assertNotNull(page.error);assertNull(page.state.value.view)
            assertFalse(page.increment());assertEquals(2,pageRowCount("completions"))
        } finally {failDecrypt=false;page.close();page.settled()}
    }
    @Test fun delayedPriorSourceRefreshCannotReplaceTheNextCommittedClaim()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount();val page=G0ShortCountPage(store,tokens,this)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        try {
            page.open();assertTrue(page.increment());page.settled();assertNull(page.error)
            val prior=page.saved!!
            page.beforeRefreshForTest={entered.complete(Unit);release.await()}
            val refresh=async(Dispatchers.IO) {page.refresh()};entered.await()
            assertTrue(page.increment());page.settled();assertNull(page.error);val next=page.saved!!
            assertNotEquals(prior.factUuid,next.factUuid);release.complete(Unit);refresh.await()
            assertEquals(next.factUuid,page.state.value.view!!.followUp.claim.completionUuid)
            assertEquals(3L,page.state.value.view!!.history.todayQuantity)
        } finally {release.complete(Unit);page.close();page.settled()}
    }
}
