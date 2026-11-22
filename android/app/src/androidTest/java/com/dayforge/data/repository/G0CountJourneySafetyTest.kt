package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
open class G0CountJourneySafetyTest:G0CountJourneyFixture() {
    @Test fun postCommitViewAndExactSourceMatchOriginalAndChangedResultCannotPublish()=runBlocking<Unit> {
        configureAction();val repo=actions().repository;val captured=repo.prepareCompletion(habit.id)
        val id=repo.logCompletion(app,habit.id,authority=captured.edit.authority,expectedHabitUuid=habit.uuid)
        val reader=resultReader();val reference=repo.readCompletionAfter(captured,true,id)
        val value=reader.readCompletionAfter(captured,true,id)
        assertEquals(reference.habit,value.habit);assertEquals(reference.countHistory,value.countHistory)
        assertEquals(reference.checkHistory,value.checkHistory);assertEquals(reference.followUp,value.followUp)
        var publications=0
        reader.publish(captured,value,true,id) { publications++ };assertEquals(1,publications)
        increment(actions())
        val error=rejectWithoutChanges { reader.publish(captured,value,true,id) { publications++ } }
        assertEquals("G0_COUNT_RESULT_CHANGED",error.message);assertEquals(1,publications)
    }
    @Test fun reliableWriteReturnPrecedesResultAndRepeatedTapCannotWriteTwice()=runBlocking<Unit> {
        configureAction();val flow=journey();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        flow.afterCommitForTest={entered.complete(Unit);release.await()}
        assertTrue(flow.increment());entered.await()
        try {
            assertEquals(G0CountJourney.Phase.SAVED_REFRESHING,flow.state.value.phase)
            assertNull(flow.state.value.view);assertFalse(flow.increment());assertEquals(1,count("completions"))
        } finally { release.complete(Unit) }
        flow.awaitSettled();assertEquals(G0CountJourney.Phase.RESULT,flow.state.value.phase)
        val view=requireNotNull(flow.state.value.view);assertEquals(1L,view.countHistory!!.todayQuantity)
        assertEquals(db.completionDao().getByHabitOnce(habit.id).single().uuid,view.followUp.claim.completionUuid)
        assertTrue(flow.events.single { it.kind=="committed" }.at<flow.events.single { it.kind=="state_RESULT" }.at)
        ackOnlyOperation();assertEquals(1L,currentCount(actions()))
    }
    @Test fun finalFreshCredentialFailureNeverReportsSaved()=runBlocking<Unit> {
        configureAction();val flow=journey();flow.beforeWriteForTest={failDecrypt=true}
        try {
            assertTrue(flow.increment());flow.awaitSettled()
            assertEquals(G0CountJourney.Phase.RECOVERY,flow.state.value.phase)
            assertEquals(0,count("completions"));assertEquals(0,count("sync_outbox"))
            assertFalse(flow.events.any { it.kind=="committed" });assertNotNull(flow.lastError)
        } finally { failDecrypt=false }
    }
    @Test fun failedRefreshRetainsKnownCommitAndCannotOfferRepeat()=runBlocking<Unit> {
        configureAction();val flow=journey()
        flow.afterCommitForTest={db.openHelper.writableDatabase.execSQL("UPDATE completions SET value=1.5 WHERE habitId=?",arrayOf(habit.id))}
        assertTrue(flow.increment());flow.awaitSettled()
        assertEquals(G0CountJourney.Phase.SAVED_REFRESH_FAILED,flow.state.value.phase)
        assertEquals(1,count("completions"));assertEquals(1,flow.events.count { it.kind=="committed" })
        assertNull(flow.state.value.view);assertFalse(flow.increment())
    }
    @Test fun lostCommitReturnIsUnknownNotSavedAndReopenFindsExactlyOneFact()=runBlocking<Unit> {
        configureAction();val flow=journey();val trace=G0PhaseTrace(driverTiming=false);flow.traceForTest=trace
        flow.beforeWriteForTest={endFaults.failAfterNextEndIn(trace)}
        assertTrue(flow.increment());flow.awaitSettled()
        assertEquals("G0_COMMIT_RESULT_LOST",flow.lastError?.message)
        assertEquals(G0CountJourney.Phase.RECOVERY,flow.state.value.phase);assertFalse(flow.increment())
        assertFalse(flow.events.any { it.kind=="committed" });assertNull(flow.state.value.view)
        flow.close();storage.reopen();assertEquals(1L,currentCount(actions()));assertEquals(1,count("sync_outbox"))
        ackOnlyOperation();assertEquals(1,count("completions"))
    }
    @Test fun accountReplacementDuringResultWorkClearsAndNeverPublishesOldContent()=runBlocking<Unit> {
        configureAction();val flow=journey();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        flow.beforePublishForTest={entered.complete(Unit);release.await()}
        assertTrue(flow.increment());entered.await()
        try {
            tokens.saveLoginSession("synthetic-other","synthetic-refresh","member",id(1990),false)
            assertNull(flow.state.value.view);assertEquals(G0CountJourney.Phase.RECOVERY,flow.state.value.phase)
        } finally { release.complete(Unit) }
        flow.awaitSettled();assertFalse(flow.events.any { it.kind=="state_RESULT" });assertFalse(flow.increment())
    }
    @Test fun closeDuringPreparationCancelsWithoutWritingAndOldStateCannotRender()=runBlocking<Unit> {
        configureAction();val flow=journey();val entered=CompletableDeferred<Unit>()
        flow.beforeWriteForTest={entered.complete(Unit);awaitCancellation()}
        assertTrue(flow.increment());entered.await();val old=flow.state.value
        flow.close();flow.awaitSettled();assertFalse(old.renderable());assertNull(flow.state.value.view)
        assertEquals(G0CountJourney.Phase.CLOSED,flow.state.value.phase);assertEquals(0,count("completions"))
    }
    @Test fun countdownNoOpDoesNotInventACommitOrSuccessPrompt()=runBlocking<Unit> {
        configureAction(countdown=true,target=1);increment(actions());val before=pageNativeState();val flow=journey()
        assertTrue(flow.increment());flow.awaitSettled()
        assertEquals(G0CountJourney.Phase.UNCHANGED,flow.state.value.phase)
        assertFalse(flow.events.any { it.kind=="committed" });assertEquals(before,pageNativeState())
    }
    @Test fun nextClickKeepsTheDisplayedCountIdentityAndRejectsAChangedPlan()=runBlocking<Unit> {
        configureAction();val flow=journey();assertTrue(flow.increment());flow.awaitSettled()
        assertEquals(G0CountJourney.Phase.RESULT,flow.state.value.phase)
        val current=requireNotNull(db.habitDao().getHabitById(habit.id))
        producer().write(local()) { db.habitDao().update(current.copy(targetValue=current.targetValue+1)) }
        val before=pageNativeState();assertTrue(flow.increment());flow.awaitSettled()
        assertEquals(G0CountJourney.Phase.RECOVERY,flow.state.value.phase)
        assertEquals("COUNT_ACTION_EXPIRED",flow.lastError?.message);assertEquals(before,pageNativeState())
        assertEquals(1,flow.events.count { it.kind=="committed" })
    }
    @Test fun cancelledOwnerDoesNotLeaveAnUnstartedCommandPermanentlySaving()=runBlocking<Unit> {
        configureAction();val flow=journey();journeyOwner.cancel()
        assertTrue(flow.increment());flow.awaitSettled()
        assertEquals(G0CountJourney.Phase.RECOVERY,flow.state.value.phase)
        assertFalse(flow.increment());assertEquals(0,count("completions"))
    }

    @Test fun combinedPublicationMatchesOriginalWithOneCompleteReadAndFreshEntryAndExit()=runBlocking<Unit> {
        configureAction();val repo=actions().repository;val captured=repo.prepareCompletion(habit.id)
        val id=repo.logCompletion(app,habit.id,authority=captured.edit.authority,expectedHabitUuid=habit.uuid)
        val expected=repo.readCompletionAfter(captured,true,id);val reader=resultReader();val trace=G0PhaseTrace(driverTiming=false)
        var view:RecurringCompletionSnapshot?=null
        trace.run { reader.readAndPublish(captured,true,id) { value ->
            assertFalse(db.inTransaction());view=value
        } }
        assertEquals(expected.countHistory,view!!.countHistory);assertEquals(expected.followUp,view!!.followUp)
        assertEquals(1,trace.count("tx_begin_outer"));assertEquals(1,trace.count("tx_end_outer"))
        assertEquals(2,trace.count("decrypt_access"));assertEquals(0,trace.count("decrypt_refresh"))
    }
    @Test fun failedReadTransactionEndCannotPublishAResultAfterAKnownWrite()=runBlocking<Unit> {
        configureAction();val flow=journey();val trace=G0PhaseTrace(driverTiming=false);flow.traceForTest=trace
        flow.beforePublishForTest={endFaults.failAfterNextEndIn(trace)}
        assertTrue(flow.increment());flow.awaitSettled()
        assertEquals(G0CountJourney.Phase.SAVED_REFRESH_FAILED,flow.state.value.phase)
        assertEquals(1,flow.events.count { it.kind=="committed" });assertFalse(flow.events.any { it.kind=="state_RESULT" })
        assertNull(flow.state.value.view);assertEquals(1,count("completions"));assertFalse(flow.increment())
    }

    @Test fun restoredImageRetainsRealTransactionMeasurementForBothStrategies()=runBlocking<Unit> {
        configureAction();val image=storage.snapshotClosedDatabase()
        for(candidate in listOf(false,true)) {
            restoreForJourney(image)
            val flow=journey(candidate);val trace=G0PhaseTrace(driverTiming=false);flow.traceForTest=trace
            assertTrue(flow.increment());flow.awaitSettled()
            assertEquals(G0CountJourney.Phase.RESULT,flow.state.value.phase)
            assertEquals(if(candidate) 3 else 4,trace.count("tx_begin_outer"))
            assertEquals(if(candidate) 3 else 4,trace.count("tx_end_outer"))
            assertEquals(if(candidate) 6 else 8,trace.count("decrypt_access"))
            assertEquals(1,count("completions"));flow.close();flow.awaitSettled()
        }
    }

}
