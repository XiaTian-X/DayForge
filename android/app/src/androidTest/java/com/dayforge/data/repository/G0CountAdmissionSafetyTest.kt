package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0CountAdmissionSafetyTest:G0CountDashboardFixture() {
    private suspend fun until(block:()->Boolean)=withTimeout(10_000) {while(!block()) delay(1)}
    @Test fun queuedAckAndWriteInvalidationsNeedTwoReferenceAuditsButOneCandidateAudit()=runBlocking<Unit> {
        configureAction();increment(actions());val (http,_)=channel {successReply(it)}
        val prior=requireNotNull(sender(http).sendOperation(access(),db.syncOutboxDao().getAll().single().operationId))
        val image=storage.snapshotClosedDatabase()
        for(coalesce in listOf(false,true)) {
            restoreForJourney(image);val requests=sender(http)
            val flow=journey(afterResult=true,loadOnOpen=true,shareInitialPure=true,prioritizeDashboard=true)
            flow.opened();flow.awaitInitial();val home=dashboard(coalesce);val trace=G0PhaseTrace(driverTiming=false);home.trace=trace
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val ackInvalidation=CompletableDeferred<Int>()
            flow.beforeWriteForTest={entered.complete(Unit);release.await()}
            flow.beforePublishForTest={val previous=ackInvalidation.await();until {home.invalidations.get()>previous}}
            assertTrue(flow.increment());entered.await();home.start(flow)
            try {
                until {home.events.any {it.kind==if(coalesce) "admission_wait" else "read_invoked"}}
                val before=home.invalidations.get()
                assertEquals(NextOperationAcceptance.COMMITTED,requests.acceptOperation(prior))
                until {home.invalidations.get()>before};ackInvalidation.complete(home.invalidations.get())
                assertEquals(if(coalesce) 0 else 1,home.reads.get());assertEquals(0,home.publications.get())
            } finally {release.complete(Unit)}
            flow.awaitSettled();assertNull(flow.lastError);homeQuantity(home,2)
            assertTrue(G0CountDashboard.compatible(flow.state.value,home.state.value))
            home.shutdown()
            assertEquals(if(coalesce) 1 else 2,home.reads.get())
            assertEquals(if(coalesce) 0 else 1,home.events.count {it.kind=="discarded"})
            assertEquals(1,home.publications.get());assertTrue(trace.count("decrypt_access")>=4)
            val invoked=home.events.first {it.kind=="read_invoked"}
            val returned=home.events.first {it.kind=="read_returned"}
            if(coalesce) {
                assertEquals(invoked.expected,returned.current)
                assertTrue(home.events.single {it.kind=="admission_resumed"}.current>home.events.single {it.kind=="admission_wait"}.expected)
            } else assertTrue(returned.current>invoked.expected)
            val firstSql=synchronized(trace.spans) {trace.spans.first {it.phase=="tx_begin_outer"}.start}
            assertTrue("The obsolete reference audit really started after the command result",firstSql>=flow.events.single {it.kind=="state_RESULT"}.at)
            assertEquals(2,count("completions"));assertEquals(1,count("next_acceptances"));assertEquals(1,count("sync_outbox"))
            ackOnlyOperation();assertEquals(2,count("next_acceptances"));flow.close();flow.awaitSettled()
        }
    }
    @Test fun aChangeAfterTheReadStillDiscardsTheBatchAndDetectsNativeCorruption()=runBlocking<Unit> {
        configureAction();increment(actions());ackOnlyOperation();val home=dashboard(true)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var first=true
        home.beforePublishForTest={if(first) {first=false;entered.complete(Unit);release.await()}}
        home.start();entered.await();val before=home.invalidations.get()
        try {
            db.withTransaction {db.openHelper.writableDatabase.execSQL("UPDATE completions SET value=1.5 WHERE habitId=?",arrayOf(habit.id))}
            until {home.invalidations.get()>before}
        } finally {release.complete(Unit)}
        val corrupt=pageNativeState();val failure=withTimeout(10_000) {home.state.first {it.error!=null}}
        assertNull(failure.batch);assertEquals(0,home.publications.get());assertEquals(1,home.events.count {it.kind=="discarded"})
        assertEquals(2,home.reads.get());assertEquals(corrupt,pageNativeState())
    }
    @Test fun closingDuringAdmissionCannotStartAReadOrLeaveACommandRunning()=runBlocking<Unit> {
        configureAction();val flow=journey(prioritizeDashboard=true);val home=dashboard(true)
        val entered=CompletableDeferred<Unit>();var exited=false
        flow.beforeWriteForTest={entered.complete(Unit);try {awaitCancellation()} finally {exited=true}}
        flow.increment();entered.await();home.start(flow);until {home.events.any {it.kind=="admission_wait"}}
        assertEquals(0,home.reads.get());flow.close();flow.awaitSettled();home.shutdown()
        assertTrue(exited);assertEquals(0,home.reads.get());assertEquals(0,home.publications.get())
        assertEquals(0,count("completions"));assertEquals(0,count("sync_outbox"))
    }
    @Test fun anAccountTransitionDuringAdmissionCannotAuthorizeThePendingCommandOrHome()=runBlocking<Unit> {
        configureAction();val flow=journey(prioritizeDashboard=true);val home=dashboard(true)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        flow.beforeWriteForTest={entered.complete(Unit);release.await()}
        flow.increment();entered.await();home.start(flow);until {home.events.any {it.kind=="admission_wait"}}
        try {tokens.saveLoginSession("synthetic-other","synthetic-refresh","member",id(2401),false)} finally {release.complete(Unit)}
        flow.awaitSettled();home.shutdown();assertNotNull(flow.lastError)
        assertEquals(0,home.reads.get());assertEquals(0,home.publications.get());assertNull(flow.state.value.view)
        assertEquals(0,count("completions"));assertEquals(0,count("sync_outbox"))
    }
}

@RunWith(AndroidJUnit4::class)
class G0CountAdmissionInheritedSafetyTest:G0CountDashboardSafetyTest() {
    override val coalesceDashboardBeforeRead=true
}

@RunWith(AndroidJUnit4::class)
class G0CountAdmissionScreenSafetyTest:G0CountDashboardScreenSafetyTest() {
    override val coalesceDashboardBeforeRead=true
}
