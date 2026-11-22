package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
open class G0CountDashboardScreenSafetyTest:G0CountDashboardFixture() {
    @Test fun bothActualRegionsShowTheSameCommittedFactWhileOriginalAckCompetes()=runBlocking<Unit> {
        configureAction();increment(actions())
        val (http,_)=channel {successReply(it)};val requests=sender(http)
        val prior=requireNotNull(requests.sendOperation(access(),db.syncOutboxDao().getAll().single().operationId))
        val flow=journey(afterResult=true,loadOnOpen=true,shareInitialPure=true,prioritizeDashboard=true);val home=dashboard()
        val a=G0ScreenObservation();val b=G0ScreenObservation();installBoth(flow,home,a,b);both(flow,home,a,b)
        requireVisiblePageText(setOf("已验证计数：1","首页已验证计数：1"))
        val ack=async(Dispatchers.IO) {requests.acceptOperation(prior)};a.tap("increment")
        phase(flow,G0CountJourney.Phase.RESULT);flow.awaitSettled();assertNull(flow.lastError)
        assertEquals(NextOperationAcceptance.COMMITTED,ack.await());both(flow,home,a,b)
        requireVisiblePageText(setOf("已验证计数：2","首页已验证计数：2","已保存，结果已核验"))
        assertTrue(G0CountDashboard.compatible(flow.state.value,home.state.value));ackOnlyOperation()
        assertEquals(2,count("completions"));assertEquals(2,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
    }
    @Test fun initialHomeReadDoesNotDisableTheActualCommandAndCannotPublishAfterShutdown()=runBlocking<Unit> {
        configureAction();val flow=journey(afterResult=true,loadOnOpen=true,shareInitialPure=true,prioritizeDashboard=true);val home=dashboard()
        val entered=CompletableDeferred<Unit>();home.beforeReadForTest={entered.complete(Unit);awaitCancellation()}
        val a=G0ScreenObservation();val b=G0ScreenObservation();installBoth(flow,home,a,b)
        a.awaitAny {listOf(0L)+flow.events.filter {it.kind=="state_READY"}.map {it.version}}
        entered.await();requireCommandBounds(a);a.tap("increment");phase(flow,G0CountJourney.Phase.RESULT);flow.awaitSettled()
        assertNull(flow.lastError);assertFalse(G0CountDashboard.compatible(flow.state.value,home.state.value))
        home.shutdown();flow.close();flow.awaitSettled();assertNull(home.state.value.batch);assertEquals(0,home.publications.get())
        assertEquals(1,count("completions"));ackOnlyOperation()
    }
    @Test fun accountTransitionClearsBothRegionsAndCannotUseOldFrameCompatibility()=runBlocking<Unit> {
        configureAction();val flow=journey(afterResult=true,loadOnOpen=true,shareInitialPure=true,prioritizeDashboard=true);val home=dashboard()
        val a=G0ScreenObservation();val b=G0ScreenObservation();installBoth(flow,home,a,b);both(flow,home,a,b)
        val old=flow.state.value;val oldHome=home.state.value
        tokens.saveLoginSession("synthetic-other","synthetic-refresh","member",id(2302),false)
        assertFalse(old.renderable());assertFalse(oldHome.valid());assertFalse(G0CountDashboard.compatible(old,oldHome))
        assertNull(flow.state.value.view);assertNull(home.state.value.batch);assertFalse(flow.increment())
        home.shutdown();flow.awaitSettled();assertEquals(0,count("completions"))
    }
    @Test fun actualCloseButtonCancelsTheSecondConsumerWithoutAnExplicitShutdownCall()=runBlocking<Unit> {
        configureAction();val flow=journey(afterResult=true,loadOnOpen=true,shareInitialPure=true,prioritizeDashboard=true);val home=dashboard()
        val entered=CompletableDeferred<Unit>();val exited=CompletableDeferred<Unit>()
        home.beforeReadForTest={entered.complete(Unit);try {awaitCancellation()} finally {exited.complete(Unit)}}
        val a=G0ScreenObservation();val b=G0ScreenObservation();installBoth(flow,home,a,b)
        a.awaitAny {listOf(0L)+flow.events.filter {it.kind=="state_READY"}.map {it.version}}
        entered.await();requireCommandBounds(a);a.tap("close");phase(flow,G0CountJourney.Phase.CLOSED)
        withTimeout(10_000) {exited.await()};assertNull(home.state.value.batch);assertFalse(home.start())
        assertEquals(0,home.publications.get());assertEquals(0,count("completions"))
    }
    @Test fun initialPersonalContentDoesNotMoveEitherActualHitTarget()=runBlocking<Unit> {
        configureAction();val flow=journey(afterResult=true,loadOnOpen=true,shareInitialPure=true,prioritizeDashboard=true)
        val home=dashboard();val release=CompletableDeferred<Unit>();flow.beforeInitialReadForTest={release.await()}
        val a=G0ScreenObservation();val b=G0ScreenObservation();installBoth(flow,home,a,b);a.await(0)
        requireCommandBounds(a);val before=listOf(a.bounds.getValue("increment"),a.bounds.getValue("close"))
        release.complete(Unit);both(flow,home,a,b);requireCommandBounds(a)
        assertEquals(before,listOf(a.bounds.getValue("increment"),a.bounds.getValue("close")))
        a.tap("increment");phase(flow,G0CountJourney.Phase.RESULT);flow.awaitSettled();assertNull(flow.lastError)
        both(flow,home,a,b);assertEquals(1,count("completions"));ackOnlyOperation()
    }
}
