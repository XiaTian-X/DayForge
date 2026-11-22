package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
open class G0CountJourneyScreenSafetyTest:G0CountJourneyScreenFixture() {
    @Test fun currentDateCardDoesNotUseTheCommittedSourcesOtherDateCompletion()=runBlocking<Unit> {
        val previous=java.util.TimeZone.getDefault()
        var flow:G0CountJourney?=null
        try {
            // Process-only zones exactly 24h apart: retain the fact's recorded IANA date,
            // then display a different date without changing the device clock or data.
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Kiritimati"))
            configureAction(target=1)
            val current=journey();flow=current;val seen=G0ScreenObservation()
            current.afterCommitForTest={java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Honolulu"))}
            install(current,seen);seen.await(0);seen.tap("increment")
            val result=phase(current,G0CountJourney.Phase.RESULT);seen.await(result.version);current.awaitSettled()
            val view=requireNotNull(result.view);val history=requireNotNull(view.countHistory)
            assertEquals(0L,history.todayQuantity);assertFalse(history.completedToday)
            assertTrue(view.followUp.completed)
            assertNotEquals(history.today.toString(),view.followUp.claim.date)
            assertEquals("Pacific/Kiritimati",view.followUp.claim.timezone)
            assertEquals(1L,view.followUp.count!!.todayQuantity)
            requireVisiblePageText(setOf("已验证计数：0","目标尚未达到"))
        } finally {
            flow?.close();flow?.awaitSettled();java.util.TimeZone.setDefault(previous)
        }
    }
    @Test fun actualTapShowsKnownSavedBeforeResultAndCloseClearsTheResultFrame()=runBlocking<Unit> {
        configureAction();val flow=journey();val seen=G0ScreenObservation()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        flow.afterCommitForTest={entered.complete(Unit);release.await()}
        install(flow,seen);seen.await(0);val started=seen.tap("increment");entered.await()
        try {
            val saved=phase(flow,G0CountJourney.Phase.SAVED_REFRESHING);val pixels=seen.await(saved.version)
            assertTrue(pixels.observed>=flow.events.single { it.kind=="committed" }.at)
            assertTrue(pixels.observed>started);assertNull(saved.view);assertEquals(1,count("completions"))
        } finally { release.complete(Unit) }
        val result=phase(flow,G0CountJourney.Phase.RESULT);seen.await(result.version);flow.awaitSettled()
        assertEquals(1L,result.view!!.countHistory!!.todayQuantity)
        seen.tap("close");val closed=phase(flow,G0CountJourney.Phase.CLOSED);seen.await(closed.version)
        assertFalse(result.renderable());assertNull(closed.view)
    }
    @Test fun lostCommitNeverProducesSavedPixelsOrEnablesAnotherIncrement()=runBlocking<Unit> {
        configureAction();val flow=journey();val seen=G0ScreenObservation();val trace=G0PhaseTrace(driverTiming=false)
        flow.traceForTest=trace;flow.beforeWriteForTest={endFaults.failAfterNextEndIn(trace)}
        install(flow,seen);seen.await(0);seen.tap("increment")
        val unknown=phase(flow,G0CountJourney.Phase.RECOVERY);seen.await(unknown.version);flow.awaitSettled()
        assertEquals("G0_COMMIT_RESULT_LOST",flow.lastError?.message)
        assertFalse(flow.events.any { it.kind in setOf("committed","state_RESULT","state_SAVED_REFRESHING") })
        seen.tap("increment");assertFalse(flow.increment());assertEquals(1,count("completions"))
        assertNull(flow.state.value.view)
    }
    @Test fun leavingCompositionCancelsPreparationAndLaterAccountChangesCannotReopenIt()=runBlocking<Unit> {
        configureAction();val flow=journey();val seen=G0ScreenObservation();val entered=CompletableDeferred<Unit>()
        flow.beforeWriteForTest={entered.complete(Unit);awaitCancellation()}
        install(flow,seen);seen.await(0);seen.tap("increment");entered.await();val old=flow.state.value
        activity.scenario.onActivity { it.setContent { Text("已离开计数页面") } }
        phase(flow,G0CountJourney.Phase.CLOSED);flow.awaitSettled();assertFalse(old.renderable())
        assertEquals(0,count("completions"));assertNull(flow.state.value.view)
        register(device=id(1998));assertEquals(G0CountJourney.Phase.CLOSED,flow.state.value.phase)
    }

}
