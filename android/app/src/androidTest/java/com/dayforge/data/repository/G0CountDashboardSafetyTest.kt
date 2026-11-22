package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.withTransaction
import java.time.ZonedDateTime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
open class G0CountDashboardSafetyTest:G0CountDashboardFixture() {
    @Test fun originalHomeReadMatchesReferenceWithoutChangingPersistentState()=runBlocking<Unit> {
        configureAction();increment(actions());ackOnlyOperation()
        val expected=dashboardRepository().read(ZonedDateTime.now());val before=pageNativeState()
        val home=dashboard();val trace=G0PhaseTrace(driverTiming=false);home.trace=trace
        assertTrue(home.start());assertFalse(home.start());val actual=homeQuantity(home,1)
        assertEquals(expected.habits,actual.batch!!.habits);assertEquals(expected.counts,actual.batch.counts)
        assertEquals(expected.completions,actual.batch.completions);assertEquals(before,pageNativeState())
        assertTrue(trace.count("decrypt_access")>=2);home.shutdown();assertFalse(actual.valid())
    }
    @Test fun realSameRowInvalidationRejectsTheReadBeforeItsPublication()=runBlocking<Unit> {
        configureAction();increment(actions());ackOnlyOperation()
        val home=dashboard();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        var intercepted=false
        home.beforePublishForTest={if(!intercepted) {intercepted=true;entered.complete(Unit);release.await()}}
        home.start();entered.await();val previous=home.invalidations.get()
        try {
            db.withTransaction {db.openHelper.writableDatabase.execSQL("UPDATE completions SET value=value WHERE habitId=?",arrayOf(habit.id))}
            withTimeout(10_000) {while(home.invalidations.get()==previous) yield()}
            assertEquals(0,home.publications.get());assertNull(home.state.value.batch)
        } finally {release.complete(Unit)}
        val result=homeQuantity(home,1);assertTrue(result.valid());assertEquals(1,home.publications.get())
        assertTrue(home.reads.get()>=2)
    }
    @Test fun actualWriteInvalidatesOldHomeAndEventuallyMatchesItsNewFact()=runBlocking<Unit> {
        configureAction();val flow=journey(afterResult=true,loadOnOpen=true,shareInitialPure=true,prioritizeDashboard=true)
        flow.opened();flow.awaitInitial();val home=dashboard();home.start();val old=homeQuantity(home,0)
        assertTrue(G0CountDashboard.compatible(flow.state.value,old))
        assertTrue(flow.increment());assertFalse(G0CountDashboard.compatible(flow.state.value,old))
        flow.awaitSettled();assertNull(flow.lastError);val next=homeQuantity(home,1)
        assertFalse(old.valid());assertTrue(G0CountDashboard.compatible(flow.state.value,next))
        ackOnlyOperation();homeQuantity(home,1)
    }
    @Test fun finalCredentialFailureCannotPublishTheEarlierValidBatch()=runBlocking<Unit> {
        configureAction();val home=dashboard();home.beforePublishForTest={failDecrypt=true}
        try {
            home.start();val failed=withTimeout(10_000) {home.state.first {it.error!=null}}
            assertNull(failed.batch);assertFalse(failed.valid());assertEquals(0,home.publications.get())
            assertEquals(0,count("completions"))
        } finally {failDecrypt=false;home.shutdown()}
    }
    @Test fun nativeCorruptionInvalidationClearsTheEarlierSnapshotAndFailsClosed()=runBlocking<Unit> {
        configureAction();increment(actions());ackOnlyOperation();val home=dashboard();home.start();val old=homeQuantity(home,1)
        db.withTransaction {db.openHelper.writableDatabase.execSQL("UPDATE completions SET value=1.5 WHERE habitId=?",arrayOf(habit.id))}
        val corrupt=pageNativeState();val failed=withTimeout(10_000) {home.state.first {it.error!=null}}
        assertFalse(old.valid());assertNull(failed.batch);assertEquals(corrupt,pageNativeState())
    }
    @Test fun shutdownCancelsAnInFlightReadAndAccountChangeRevokesPublishedContent()=runBlocking<Unit> {
        configureAction();val pending=dashboard();val entered=CompletableDeferred<Unit>();var exited=false
        pending.beforeReadForTest={entered.complete(Unit);try {awaitCancellation()} finally {exited=true}}
        pending.start();entered.await();pending.shutdown();assertTrue(exited);assertEquals(0,pending.publications.get())
        val home=dashboard();home.start();val old=homeQuantity(home,0)
        tokens.saveLoginSession("synthetic-other","synthetic-refresh","member",id(2301),false)
        assertFalse(old.valid());assertNull(home.state.value.batch);assertFalse(home.start())
        home.shutdown();assertEquals(0,count("completions"))
    }
    @Test fun displayedEvidenceCannotSurviveChangedDateOrZoneBeforeTheNextRead()=runBlocking<Unit> {
        configureAction();val home=dashboard();home.start();val old=homeQuantity(home,0)
        val now=ZonedDateTime.now()
        home.clockForTest={now.plusDays(1)};assertFalse(old.valid())
        home.clockForTest={now.withZoneSameInstant(java.time.ZoneId.of(if(now.zone.id=="UTC") "Asia/Tokyo" else "UTC"))}
        assertFalse(old.valid())
        // Same invalidation operation used by the registered time/date/zone receiver.
        home.clockForTest=ZonedDateTime::now;home.refresh()
        val next=homeQuantity(home,0);assertFalse(old.valid());assertTrue(next.valid())
    }
    @Test fun originalFinalPublicationRejectsADifferentBusinessDate()=runBlocking<Unit> {
        configureAction();val home=dashboard();home.clockForTest={ZonedDateTime.now().plusDays(1)}
        home.start();val failed=withTimeout(10_000) {home.state.first {it.error!=null}}
        assertEquals("G0_DASHBOARD_PUBLICATION_REJECTED",failed.error!!.message)
        assertNull(failed.batch);assertEquals(0,home.publications.get());assertEquals(0,count("completions"))
    }
    @Test fun cancelledInteractiveCommandReleasesOriginalPriorityWithoutHoldingTheAccountLock()=runBlocking<Unit> {
        configureAction();val flow=journey(prioritizeDashboard=true)
        val entered=CompletableDeferred<Unit>();flow.beforeWriteForTest={entered.complete(Unit);awaitCancellation()}
        assertTrue(flow.increment());entered.await()
        val home=dashboard();val reading=CompletableDeferred<Unit>();home.beforeReadForTest={reading.complete(Unit)}
        home.start();reading.await()
        // The hint is not an account lock or authorization. Ordinary account work can enter.
        withTimeout(10_000) {sessions.exclusive {assertEquals(0,count("completions"))}}
        flow.close();flow.awaitSettled();homeQuantity(home,0)
        assertEquals(0,count("completions"));assertEquals(0,count("sync_outbox"))
    }
}
