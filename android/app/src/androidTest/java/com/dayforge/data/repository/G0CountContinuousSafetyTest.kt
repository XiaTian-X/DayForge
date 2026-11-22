package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.withTransaction
import com.dayforge.data.api.dto.NextSyncPushResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Consecutive actual commands do not wait for the second region to catch up. */
@RunWith(AndroidJUnit4::class)
open class G0CountContinuousSafetyTest:G0CountDashboardFixture() {
    override val coalesceDashboardBeforeRead=true
    private fun page()=journey(afterResult=true,loadOnOpen=true,shareInitialPure=true,prioritizeDashboard=true)
    private suspend fun clicked(flow:G0CountJourney,a:G0ScreenObservation,quantity:Long) {
        val previous=flow.state.value.version
        a.tap("increment")
        withTimeout(20_000) {while(flow.state.value.version<=previous || flow.state.value.phase!=G0CountJourney.Phase.RESULT) delay(1)}
        flow.awaitSettled();assertNull(flow.lastError);a.await(flow.state.value.version)
        assertEquals(quantity,flow.state.value.view!!.countHistory!!.todayQuantity)
        assertEquals(db.completionDao().getByHabitOnce(habit.id).single {it.id==flow.recordedId}.uuid,
            flow.state.value.view!!.followUp.claim.completionUuid)
    }
    @Test fun pipelinedSendAndAckAfterThreeRealClicksStillReachOneCurrentPage()=runBlocking<Unit> {
        configureAction(target=1000);increment(actions());val (http,_)=channel {successReply(it)}
        val requests=sender(http)
        var sent:Deferred<NextCoreDelivery<NextSyncPushResponse>> = CompletableDeferred(requireNotNull(
            requests.sendOperation(access(),db.syncOutboxDao().getAll().single().operationId)))
        val flow=page();val home=dashboard();val a=G0ScreenObservation();val b=G0ScreenObservation()
        installBoth(flow,home,a,b);both(flow,home,a,b)
        repeat(3) {index->
            val previous=sent
            val ack=async(Dispatchers.IO) {requests.acceptOperation(previous.await())}
            clicked(flow,a,(index+2).toLong());assertEquals(NextOperationAcceptance.COMMITTED,ack.await())
            val operation=db.syncOutboxDao().getAll().single().operationId
            sent=async(Dispatchers.IO) {requireNotNull(requests.sendOperation(access(),operation))}
        }
        try {both(flow,home,a,b)} catch(failure:Exception) {diagnoseContinuousPage(flow,home);throw failure}
        assertEquals(NextOperationAcceptance.COMMITTED,requests.acceptOperation(sent.await()))
        assertEquals(4,count("completions"));assertEquals(4,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
    }
    @Test fun originalAckCanChangeCreationMetadataButEveryOtherFactFieldStillSeparatesCards()=runBlocking<Unit> {
        configureAction(target=1000);val flow=page();val home=dashboard();val a=G0ScreenObservation();val b=G0ScreenObservation()
        installBoth(flow,home,a,b);both(flow,home,a,b);clicked(flow,a,1);both(flow,home,a,b)
        val before=flow.state.value.view!!.countHistory!!;ackOnlyOperation()
        withTimeout(10_000) {home.state.first {it.valid() && it.batch?.counts?.get(habit.id)!=before}}
        val after=home.state.value.batch!!.counts.getValue(habit.id)
        assertNotEquals(before,after);assertTrue(G0CountDashboard.sameCountCardEvidence(before,after))
        both(flow,home,a,b);requireVisiblePageText(setOf("已验证计数：1","首页已验证计数：1"))
        val row=after.completions.single()
        val changed=listOf(row.copy(id=row.id+1),row.copy(habitId=row.habitId+1),row.copy(date=row.date+1),
            row.copy(value=row.value+1),row.copy(actualCompletedAt=requireNotNull(row.actualCompletedAt)+1),
            row.copy(uuid=id(2450)),row.copy(habitUuid=id(2451)),row.copy(recordedTimezone="Pacific/Honolulu"),
            row.copy(recordedLocalDate="2000-01-01"),row.copy(timeMetadataSource="different"),row.copy(oneTimeAction="complete"),
            row.copy(oneTimeExpectedVersion=1),row.copy(oneTimeExpectedHeadEventUuid=id(2452)),row.copy(oneTimeRevertsEventUuid=id(2453)))
        changed.forEach {assertFalse(G0CountDashboard.sameCountCardEvidence(before,after.copy(completions=listOf(it))))}
        assertFalse(G0CountDashboard.sameCountCardEvidence(before,after.copy(completions=emptyList())))
        assertFalse(G0CountDashboard.sameCountCardEvidence(before,after.copy(completions=after.completions+row)))
        assertFalse(G0CountDashboard.sameCountCardEvidence(before,after.copy(today=after.today.plusDays(1))))
        assertFalse(G0CountDashboard.sameCountCardEvidence(before,after.copy(actionIdentity=null)))
    }
    @Test fun rawCreationMetadataTamperingStillFailsBothFullReadersAndCannotAuthorizeNextWrite()=runBlocking<Unit> {
        configureAction(target=1000);val flow=page();val home=dashboard();val a=G0ScreenObservation();val b=G0ScreenObservation()
        installBoth(flow,home,a,b);both(flow,home,a,b);clicked(flow,a,1);ackOnlyOperation();both(flow,home,a,b)
        val old=home.state.value
        db.withTransaction {db.openHelper.writableDatabase.execSQL("UPDATE completions SET createdAt=createdAt+1 WHERE habitId=?",arrayOf(habit.id))}
        val corrupt=pageNativeState()
        withTimeout(10_000) {home.state.first {it.error!=null}}
        assertFalse(old.valid());assertNull(home.state.value.batch)
        assertTrue(flow.increment());flow.awaitSettled();assertNotNull(flow.lastError)
        assertEquals(G0CountJourney.Phase.RECOVERY,flow.state.value.phase);assertFalse(flow.increment())
        assertEquals(corrupt,pageNativeState());assertEquals(1,count("completions"));assertEquals(0,count("sync_outbox"))
    }
    @Test fun nextActualClickCanCompleteBeforeTheHomeCatchesUpAndOldReadCannotPublish()=runBlocking<Unit> {
        configureAction(target=1000);increment(actions())
        val (http,_)=channel {successReply(it)};val requests=sender(http)
        val prior=requireNotNull(requests.sendOperation(access(),db.syncOutboxDao().getAll().single().operationId))
        val flow=page();val home=dashboard();val a=G0ScreenObservation();val b=G0ScreenObservation()
        installBoth(flow,home,a,b);both(flow,home,a,b)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var first=true
        home.beforePublishForTest={if(first) {first=false;entered.complete(Unit);release.await()}}
        val ack=async(Dispatchers.IO) {requests.acceptOperation(prior)}
        clicked(flow,a,2);assertEquals(NextOperationAcceptance.COMMITTED,ack.await());entered.await()
        val blockedPublication=home.publications.get()
        try {
            assertFalse(G0CountDashboard.compatible(flow.state.value,home.state.value))
            val trace=G0PhaseTrace(driverTiming=false);flow.traceForTest=trace
            clicked(flow,a,3);assertEquals(7,trace.count("decrypt_access"));assertEquals(0,trace.count("decrypt_refresh"))
            assertEquals(blockedPublication,home.publications.get())
            assertEquals(3,count("completions"));assertEquals(1,count("next_acceptances"));assertEquals(2,count("sync_outbox"))
        } finally {release.complete(Unit)}
        both(flow,home,a,b);assertTrue(home.events.any {it.kind=="discarded"})
        requireVisiblePageText(setOf("已验证计数：3","首页已验证计数：3"))
        for(row in db.syncOutboxDao().getAll()) {
            val sent=requireNotNull(requests.sendOperation(access(),row.operationId))
            assertEquals(NextOperationAcceptance.COMMITTED,requests.acceptOperation(sent))
            assertEquals(NextOperationAcceptance.REPLAYED,requests.acceptOperation(sent))
        }
        assertEquals(3,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
    }
    @Test fun actualCloseDuringNextPreparationPreservesPreviousCommitAndNewPageReadsIt()=runBlocking<Unit> {
        configureAction(target=1000);val flow=page();val home=dashboard();val a=G0ScreenObservation();val b=G0ScreenObservation()
        installBoth(flow,home,a,b);both(flow,home,a,b);clicked(flow,a,1);both(flow,home,a,b)
        val old=flow.state.value;val oldHome=home.state.value
        val entered=CompletableDeferred<Unit>();flow.beforeWriteForTest={entered.complete(Unit);awaitCancellation()}
        a.tap("increment");entered.await();ackOnlyOperation();a.tap("close")
        phase(flow,G0CountJourney.Phase.CLOSED);flow.awaitSettled();home.shutdown()
        assertFalse(old.renderable());assertFalse(oldHome.valid());assertEquals(1,count("completions"))
        val next=page();val nextHome=dashboard();val x=G0ScreenObservation();val y=G0ScreenObservation()
        installBoth(next,nextHome,x,y);both(next,nextHome,x,y)
        assertEquals(1L,next.state.value.view!!.countHistory!!.todayQuantity)
        assertEquals(1,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
        assertFalse(next.events.any {it.kind=="committed"});assertEquals(0,home.state.value.batch?.counts?.size ?: 0)
    }
    @Test fun closeAfterReliableCommitThenReenterDoesNotReplayTheCommand()=runBlocking<Unit> {
        configureAction(target=1000);val flow=page();val home=dashboard();val a=G0ScreenObservation();val b=G0ScreenObservation()
        installBoth(flow,home,a,b);both(flow,home,a,b)
        val committed=CompletableDeferred<Unit>();flow.afterCommitForTest={committed.complete(Unit);awaitCancellation()}
        a.tap("increment");committed.await();a.await(flow.state.value.version)
        assertEquals(G0CountJourney.Phase.SAVED_REFRESHING,flow.state.value.phase)
        a.tap("close");phase(flow,G0CountJourney.Phase.CLOSED);flow.awaitSettled();home.shutdown()
        assertEquals(1,count("completions"));assertEquals(1,count("sync_outbox"))
        val next=page();val nextHome=dashboard();val x=G0ScreenObservation();val y=G0ScreenObservation()
        installBoth(next,nextHome,x,y);both(next,nextHome,x,y)
        assertEquals(1L,next.state.value.view!!.countHistory!!.todayQuantity)
        assertFalse(next.events.any {it.kind=="committed"});ackOnlyOperation()
        assertEquals(1,count("completions"));assertEquals(1,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
    }
    @Test fun nextCommandStillRejectsFreshCredentialFailureAfterPreviousSuccessfulPublication()=runBlocking<Unit> {
        configureAction(target=1000);val flow=page();val home=dashboard();val a=G0ScreenObservation();val b=G0ScreenObservation()
        installBoth(flow,home,a,b);both(flow,home,a,b);clicked(flow,a,1);both(flow,home,a,b);ackOnlyOperation()
        flow.beforeWriteForTest={failDecrypt=true}
        try {
            a.tap("increment");phase(flow,G0CountJourney.Phase.RECOVERY);flow.awaitSettled();home.shutdown()
            assertNotNull(flow.lastError);assertFalse(flow.increment());assertNull(flow.state.value.view);assertNull(home.state.value.batch)
            assertEquals(1,count("completions"));assertEquals(1,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
        } finally {failDecrypt=false}
    }
}

@RunWith(AndroidJUnit4::class)
class G0CountContinuousReferenceSafetyTest:G0CountContinuousSafetyTest() {
    override val coalesceDashboardBeforeRead=false
}
