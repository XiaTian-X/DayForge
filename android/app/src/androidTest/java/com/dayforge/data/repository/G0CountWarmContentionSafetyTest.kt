package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Original fact writer and original ACK; this does not admit facts to the metric SQL owner. */
@RunWith(AndroidJUnit4::class)
open class G0CountWarmContentionSafetyTest:G0CountJourneyFixture() {
    override val reuseCountPreparation=true
    override fun domainOpeningCallback()=G0SchemaOpeningCallback(true)
    private suspend fun saved(flow:G0CountJourney,quantity:Long) {
        flow.awaitSettled();assertNull(flow.lastError)
        assertEquals(G0CountJourney.Phase.RESULT,flow.state.value.phase)
        val view=requireNotNull(flow.state.value.view)
        assertEquals(quantity,view.countHistory!!.todayQuantity)
        assertEquals(db.completionDao().getByHabitOnce(habit.id).single { it.id==flow.recordedId }.uuid,view.followUp.claim.completionUuid)
        val original=actions().repository.prepareCompletion(habit.id).evidence!!
        assertEquals(original.countHistory,view.countHistory)
    }
    @Test fun predecessorAckAtBothSnapshotBoundariesKeepsExactSourceAndFreshAuthority()=runBlocking<Unit> {
        configureAction(target=1000);val flow=journey();assertTrue(flow.increment());saved(flow,1)
        repeat(4) { index ->
            val (http,_)=channel { successReply(it) };val requests=sender(http)
            val row=db.syncOutboxDao().getAll().single()
            val delivery=requireNotNull(requests.sendOperation(access(),row.operationId))
            suspend fun ack() {
                assertEquals(NextOperationAcceptance.COMMITTED,requests.acceptOperation(delivery))
                assertEquals(NextOperationAcceptance.REPLAYED,requests.acceptOperation(delivery))
            }
            flow.beforeWriteForTest=if(index%2==0) ({ack()}) else ({})
            flow.beforePublishForTest=if(index%2==1) ({ack()}) else ({})
            val trace=G0PhaseTrace(driverTiming=false);flow.traceForTest=trace
            assertTrue(flow.increment());saved(flow,(index+2).toLong())
            // ACK hooks have their own original fresh checks too; never allow fewer than
            // the foreground's six decryptions. This diagnostic is not an authority token.
            assertTrue(trace.count("decrypt_access")>=6)
            assertEquals(index+1,count("next_acceptances"));assertEquals(1,count("sync_outbox"))
        }
        flow.beforeWriteForTest={};flow.beforePublishForTest={};ackOnlyOperation()
        assertEquals(5,count("completions"));assertEquals(5,count("next_acceptances"));assertTrue(flow.factBlockHits>0)
    }
    @Test fun simultaneousOriginalAckAndCountHaveOneFactPerCommandAndReplayIsReadOnly()=runBlocking<Unit> {
        configureAction(target=1000);val flow=journey();assertTrue(flow.increment());saved(flow,1)
        repeat(4) { index ->
            val (http,_)=channel { successReply(it) };val requests=sender(http)
            val delivery=requireNotNull(requests.sendOperation(access(),db.syncOutboxDao().getAll().single().operationId))
            val started=CompletableDeferred<Unit>();val ackTrace=G0PhaseTrace(driverTiming=false)
            val ack=async(Dispatchers.IO) { ackTrace.run { started.complete(Unit);requests.acceptOperation(delivery) } }
            started.await();val trace=G0PhaseTrace(driverTiming=false);flow.traceForTest=trace
            assertTrue(flow.increment());saved(flow,(index+2).toLong());assertEquals(NextOperationAcceptance.COMMITTED,ack.await())
            val before=pageNativeState();assertEquals(NextOperationAcceptance.REPLAYED,requests.acceptOperation(delivery));assertEquals(before,pageNativeState())
            // A displayed CountActionIdentity adds the original mutateHabit device check.
            assertEquals(7,trace.count("decrypt_access"));assertEquals(0,trace.count("decrypt_refresh"))
            val events=flow.events.takeLastWhile { it.at>=trace.spans.first().start }
            val writeStart=events.single { it.kind=="write_started" }.at
            val writeEnd=events.single { it.kind=="write_returned" }.at
            assertEquals(3,trace.spans.count { it.phase=="decrypt_access" && it.start>=writeStart && it.end<=writeEnd })
            assertTrue(ackTrace.count("decrypt_access")>=2)
            assertEquals(index+2,count("completions"));assertEquals(index+1,count("next_acceptances"));assertEquals(1,count("sync_outbox"))
        }
        ackOnlyOperation();assertEquals(5,count("next_acceptances"))
    }
    @Test fun cancellingPreparedNextCommandCannotUndoThePreviousCommittedAck()=runBlocking<Unit> {
        configureAction();val flow=journey();assertTrue(flow.increment());saved(flow,1)
        val entered=CompletableDeferred<Unit>();flow.beforeWriteForTest={entered.complete(Unit);awaitCancellation()}
        assertTrue(flow.increment());entered.await();ackOnlyOperation();flow.close();flow.awaitSettled()
        assertEquals(G0CountJourney.Phase.CLOSED,flow.state.value.phase);assertNull(flow.state.value.view)
        assertEquals(1,count("completions"));assertEquals(1,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
    }
    @Test fun previousAckAndWarmCacheDoNotReplaceTheNextCommandsFreshAuthentication()=runBlocking<Unit> {
        configureAction();val flow=journey();assertTrue(flow.increment());saved(flow,1)
        flow.beforeWriteForTest={ackOnlyOperation();failDecrypt=true}
        try {
            assertTrue(flow.increment());flow.awaitSettled()
            assertEquals(G0CountJourney.Phase.RECOVERY,flow.state.value.phase);assertNull(flow.state.value.view)
            assertEquals(1,count("completions"));assertEquals(1,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
            assertEquals(1,flow.events.count { it.kind=="committed" });assertFalse(flow.increment())
        } finally { failDecrypt=false }
    }
    @Test fun unknownAckReturnDoesNotRequireRepeatingTheFactOrReuseAnUnverifiedView()=runBlocking<Unit> {
        configureAction();val flow=journey();assertTrue(flow.increment());saved(flow,1)
        val (http,_)=channel { successReply(it) };val requests=sender(http)
        val delivery=requireNotNull(requests.sendOperation(access(),db.syncOutboxDao().getAll().single().operationId))
        val trace=G0PhaseTrace(driverTiming=false);endFaults.failAfterNextEndIn(trace)
        val failure=try { trace.run { requests.acceptOperation(delivery) };null } catch(error:Exception) {error}
        assertEquals("G0_COMMIT_RESULT_LOST",failure?.message)
        assertEquals(1,count("completions"));assertEquals(1,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
        assertEquals(NextOperationAcceptance.REPLAYED,requests.acceptOperation(delivery))
        assertTrue(flow.increment());saved(flow,2);ackOnlyOperation();assertEquals(2,count("next_acceptances"))
    }
}
