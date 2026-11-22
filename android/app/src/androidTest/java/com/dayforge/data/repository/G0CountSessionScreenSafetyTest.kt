package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0CountSessionScreenSafetyTest:G0CountJourneyScreenSafetyTest() {
    override val reuseCountPreparationAfterResult=true
    override fun domainOpeningCallback()=G0SchemaOpeningCallback(true)
    @Test fun sameActualPageSwitchesOnlyAfterResultAndKeepsCorrectContentUnderAck()=runBlocking<Unit> {
        configureAction();val flow=journey();val seen=G0ScreenObservation();install(flow,seen);seen.await(0)
        seen.tap("increment");val first=phase(flow,G0CountJourney.Phase.RESULT);seen.await(first.version);flow.awaitSettled()
        requireVisiblePageText(setOf("已验证计数：1","目标尚未达到"))
        val (http,_)=channel { successReply(it) };val requests=sender(http)
        val delivery=requireNotNull(requests.sendOperation(access(),db.syncOutboxDao().getAll().single().operationId))
        val started=CompletableDeferred<Unit>()
        val ack=async(Dispatchers.IO) { started.complete(Unit);requests.acceptOperation(delivery) }
        started.await();seen.tap("increment")
        val second=withTimeout(20_000) { flow.state.first { it.phase==G0CountJourney.Phase.RESULT && it.version>first.version } }
        seen.await(second.version);flow.awaitSettled();assertNull(flow.lastError)
        assertEquals(NextOperationAcceptance.COMMITTED,ack.await())
        assertEquals(listOf("prepare_original","prepare_shared"),flow.events.filter { it.kind in setOf("prepare_original","prepare_shared") }.map { it.kind })
        assertFalse(first.renderable());requireVisiblePageText(setOf("已验证计数：2","目标尚未达到"))
        assertEquals(db.completionDao().getByHabitOnce(habit.id).single { it.id==flow.recordedId }.uuid,second.view!!.followUp.claim.completionUuid)
        assertEquals(NextOperationAcceptance.REPLAYED,requests.acceptOperation(delivery));ackOnlyOperation()
        seen.tap("close");val closed=phase(flow,G0CountJourney.Phase.CLOSED);seen.await(closed.version)
        assertFalse(second.renderable());assertEquals(2,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
    }
}
