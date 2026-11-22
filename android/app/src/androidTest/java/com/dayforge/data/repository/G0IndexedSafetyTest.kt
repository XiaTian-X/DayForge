package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Same complete existing assertion matrices, with only isolated physical indexes added. */
@RunWith(AndroidJUnit4::class)
class G0IndexedJourneySafetyTest:G0CountJourneySafetyTest() {
    override fun domainOpeningCallback()=G0CandidateIndexes.opening()
}
@RunWith(AndroidJUnit4::class)
class G0IndexedDashboardSafetyTest:G0CountDashboardSafetyTest() {
    override fun domainOpeningCallback()=G0CandidateIndexes.opening()
}

@RunWith(AndroidJUnit4::class)
class G0IndexDifferentialSafetyTest:G0IndexFixture() {
    private fun mode(value:G0CandidateIndexes.Mode)=G0CandidateIndexes.apply(db.openHelper.writableDatabase,value)
    private suspend fun equalReads() {
        mode(G0CandidateIndexes.Mode.NONE);val expected=readHome();val before=pageNativeState()
        val rows=db.syncOutboxDao().getActivityIntents(habit.uuid)
        for(candidate in G0CandidateIndexes.Mode.entries) {
            mode(candidate);val actual=readHome()
            assertEquals(expected.habits,actual.habits);assertEquals(expected.counts,actual.counts)
            assertEquals(expected.checks,actual.checks);assertEquals(expected.completions,actual.completions)
            assertEquals(rows,db.syncOutboxDao().getActivityIntents(habit.uuid));assertEquals(before,pageNativeState())
        }
    }
    private suspend fun sameRejection() {
        var expected:Pair<Class<*>,String?>?=null
        for(candidate in G0CandidateIndexes.Mode.entries) {
            mode(candidate);val error=rejectWithoutChanges {readHome()};val result=error.javaClass to error.message
            if(expected==null)expected=result else assertEquals(expected,result)
        }
    }
    @Test fun exactReadMembershipAndOriginalSendAckReplaySurviveEachIndexCombination()=runBlocking<Unit> {
        seedAccepted(3);seedFact();equalReads();ackOnlyOperation();equalReads()
        assertEquals(4,count("completions"));assertEquals(4,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
        storage.reopen();equalReads()
    }
    @Test fun nativeTypeAndOldAcceptedContentDamageStillRejectWithoutWrites()=runBlocking<Unit> {
        seedAccepted(2);equalReads();val image=storage.snapshotClosedDatabase()
        for(sql in listOf("UPDATE completions SET value=1.5 WHERE id=(SELECT MIN(id) FROM completions)",
            "UPDATE completions SET createdAt=createdAt+1 WHERE id=(SELECT MIN(id) FROM completions)")) {
            restoreForJourney(image);db.withTransaction {db.openHelper.writableDatabase.execSQL(sql)};sameRejection()
        }
    }
    @Test fun missingDayAndExtraSourceRemainRejectedWithIndexedSearch()=runBlocking<Unit> {
        configureAction();seedFact();equalReads();val image=storage.snapshotClosedDatabase()
        db.withTransaction {db.openHelper.writableDatabase.execSQL("DELETE FROM count_days WHERE habitId=?",arrayOf(habit.id))}
        sameRejection();restoreForJourney(image)
        val row=db.syncOutboxDao().getAll().single();val origin=requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION,row.operationId))
        db.nextRequestDao().insertOrigin(origin.copy(requestId=id(2801),queueId=origin.queueId+10000))
        sameRejection()
    }
    @Test fun pendingFirstCountCannotBeBypassedByAnyIndexedSendPlan()=runBlocking<Unit> {
        configureAction();val first=seedFact();val second=seedFact()
        val firstRow=db.syncOutboxDao().getEntityIntents("completion",first.uuid).single()
        val secondRow=db.syncOutboxDao().getEntityIntents("completion",second.uuid).single()
        val (http,_)=channel {successReply(it)};val requests=sender(http)
        for(candidate in G0CandidateIndexes.Mode.entries) {
            mode(candidate)
            val error=rejectWithoutChanges {requests.sendOperation(access(),secondRow.operationId)}
            assertTrue(error is NextRequestException)
            assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,(error as NextRequestException).reason)
        }
        for(row in listOf(firstRow,secondRow)) {
            val sent=requireNotNull(requests.sendOperation(access(),row.operationId))
            assertEquals(NextOperationAcceptance.COMMITTED,requests.acceptOperation(sent))
            val state=pageNativeState()
            assertEquals(NextOperationAcceptance.REPLAYED,requests.acceptOperation(sent));assertEquals(state,pageNativeState())
        }
        assertEquals(2,count("completions"));assertEquals(2,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
    }
    @Test fun actualUnknownAckCommitIsRecoveredByOnlyTheOriginalRequestForBothPlans()=runBlocking<Unit> {
        configureAction();seedFact();val (http,_)=channel {successReply(it)}
        val row=db.syncOutboxDao().getAll().single();val sent=requireNotNull(sender(http).sendOperation(access(),row.operationId))
        val image=storage.snapshotClosedDatabase();var expected:Map<String,List<String>>?=null
        for(candidate in listOf(G0CandidateIndexes.Mode.NONE,G0CandidateIndexes.Mode.BOTH)) {
            restoreForJourney(image);mode(candidate);val requests=sender(http);val trace=G0PhaseTrace(driverTiming=false)
            endFaults.failAfterNextEndIn(trace)
            val wallBefore=System.currentTimeMillis()
            val failure=try {trace.run {requests.acceptOperation(sent)};null} catch(e:Exception) {e}
            val wallAfter=System.currentTimeMillis()
            assertEquals("G0_COMMIT_RESULT_LOST",failure?.message)
            assertEquals(1,count("completions"));assertEquals(1,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
            val state=pageNativeState();assertEquals(NextOperationAcceptance.REPLAYED,requests.acceptOperation(sent));assertEquals(state,pageNativeState())
            // The original shadow constructor records local acceptance time per attempt.
            // Validate its raw type and fresh range; compare ALL other native cells exactly.
            // Replay above must preserve even this time byte-for-byte. No stored normalization.
            val shadow=state.getValue("sync_entity_state").single().split("|")
            assertEquals(7,shadow.size);assertTrue(shadow[6].startsWith("I"))
            assertTrue(shadow[6].drop(1).toLong() in wallBefore..wallAfter)
            val comparable=state+("sync_entity_state" to listOf((shadow.take(6)+"I<validated-attempt-time>").joinToString("|")))
            if(expected==null)expected=comparable else assertEquals(expected,comparable)
        }
    }
}
