package com.dayforge.data.repository

import android.util.Log
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.model.CheckInResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Original domain contract and explicit denial at the existing metric-only SQL boundary. */
@RunWith(AndroidJUnit4::class)
class G0RecurringActionAdmissionTest:G0RecurringActionFixture() {
    @Test fun countOriginalActionCommitsFactOriginAndOutboxThenAckReplays()=runBlocking<Unit> {
        configureAction();val actions=actions()
        val result=increment(actions)
        assertTrue(result.recorded);assertFalse(result.completed);assertFalse(result.goalReached)
        assertEquals(1,count("completions"));assertEquals(1,count("count_days"))
        assertEquals(1,count("next_request_origins"));assertEquals(1,count("sync_outbox"))
        ackOnlyOperation()
        assertEquals(1L,currentCount(actions))
        assertEquals(1,count("completions"));assertEquals(1,count("next_acceptances"))
    }
    @Test fun checkOriginalActionAndUndoKeepExactFactAndNoGoalOnUndo()=runBlocking<Unit> {
        configureAction(check=true);val actions=actions()
        val added=actions.service.toggleCheckIn(app,habit.id) as CheckInResult.Success
        assertTrue(added.completed);assertNotNull(added.followUp)
        val source=db.completionDao().getByHabitOnce(habit.id).single()
        assertEquals(source.uuid,added.followUp!!.claim.completionUuid)
        ackOnlyOperation()
        val undone=actions.service.toggleCheckIn(app,habit.id) as CheckInResult.Success
        assertFalse(undone.completed);assertFalse(undone.goalReached)
        assertEquals(0,count("completions"));assertEquals(1,count("sync_outbox"))
        assertEquals(2,count("next_request_origins"))
    }
    @Test fun completedCountdownIsAnUnchangedNoOpNotAnotherSavedFact()=runBlocking<Unit> {
        configureAction(countdown=true,target=1);val actions=actions()
        assertTrue(increment(actions).completed)
        val before=pageNativeState();val noop=increment(actions)
        assertFalse(noop.recorded);assertTrue(noop.completed);assertFalse(noop.goalReached)
        assertEquals(before,pageNativeState())
    }
    @Test fun stalePlanAndDeviceCannotReusePreparedCountIdentity()=runBlocking<Unit> {
        configureAction();val actions=actions()
        val prepared=actions.repository.prepareCompletion(habit.id)
        val identity=requireNotNull(prepared.evidence!!.countHistory!!.actionIdentity)
        register(device=id(772))
        assertEquals("COUNT_SESSION_CHANGED",rejectWithoutChanges { actions.service.incrementCount(app,habit.id,identity) }.message)
        val current=actions.repository.prepareCompletion(habit.id)
        producer().write(local()) { db.habitDao().update(habit.copy(targetValue=11)) }
        assertEquals("COUNT_ACTION_EXPIRED",rejectWithoutChanges { actions.service.incrementCount(app,habit.id,current.evidence!!.countHistory!!.actionIdentity) }.message)
    }
    @Test fun accountReplacementRejectsTheOldActionWithoutWriting()=runBlocking<Unit> {
        configureAction();val actions=actions()
        val identity=actions.repository.prepareCompletion(habit.id).evidence!!.countHistory!!.actionIdentity
        tokens.saveLoginSession("synthetic-other","synthetic-refresh","member",id(773),false)
        assertEquals("COUNT_ACTION_EXPIRED",rejectWithoutChanges { actions.service.incrementCount(app,habit.id,identity) }.message)
    }
    @Test fun revokedFactCapabilityRollsBackFactAndOutbox()=runBlocking<Unit> {
        configureAction();val actions=actions()
        val prepared=actions.repository.prepareCompletion(habit.id,includeHistory=false)
        register(permissions=setOf("sync.read","structure.write"),revision=2)
        val error=rejectWithoutChanges {
            actions.repository.logCompletion(app,habit.id,authority=prepared.edit.authority,expectedHabitUuid=habit.uuid)
        }
        assertEquals(NextRequestException.Reason.PERMISSION_DENIED,(error as NextRequestException).reason)
    }
    @Test fun preparedTicketDoesNotReplaceFreshCredentialDecryption()=runBlocking<Unit> {
        configureAction();val actions=actions()
        val prepared=actions.repository.prepareCompletion(habit.id,includeHistory=false)
        failDecrypt=true
        try {
            val error=rejectWithoutChanges {
                actions.repository.logCompletion(app,habit.id,authority=prepared.edit.authority,expectedHabitUuid=habit.uuid)
            }
            assertEquals(NextRequestException.Reason.STALE_ACCESS,(error as NextRequestException).reason)
        } finally { failDecrypt=false }
    }
    @Test fun lostWriteReturnColdReloadFindsOneFactAndAckIsIdempotent()=runBlocking<Unit> {
        configureAction();val actions=actions()
        val prepared=actions.repository.prepareCompletion(habit.id,includeHistory=false)
        val writer=G0PhaseTrace(driverTiming=false)
        endFaults.failAfterNextEndIn(writer)
        // A known unrelated Room transaction must not consume the business fault.
        db.withTransaction { assertEquals(0,db.completionDao().getByHabitOnce(habit.id).size) }
        assertTrue(endFaults.failAfterNextEnd.get())
        val failure=try {
            writer.run { actions.repository.logCompletion(app,habit.id,authority=prepared.edit.authority,expectedHabitUuid=habit.uuid) }
            null
        } catch(error:Exception) { error }
        assertEquals("G0_COMMIT_RESULT_LOST",failure?.message)
        assertFalse(endFaults.failAfterNextEnd.get())
        storage.reopen()
        val restored=actions()
        assertEquals(1L,currentCount(restored))
        assertEquals(1,count("completions"));assertEquals(1,count("next_request_origins"))
        // Never retry a fresh increment to recover an unknown result.
        ackOnlyOperation();assertEquals(1,count("completions"))
    }
    @Test fun sealedMetricGateDeniesOriginalFactWriterAndRollsBack()=runBlocking<Unit> {
        configureAction();val actions=actions()
        val prepared=actions.repository.prepareCompletion(habit.id,includeHistory=false)
        sealDomain()
        val error=rejectWithoutChanges {
            withTimeout(5_000) { domainGate.transaction(db,G0DomainSqlGate.Phase.BUSINESS) {
                actions.repository.logCompletion(app,habit.id,authority=prepared.edit.authority,expectedHabitUuid=habit.uuid)
            } }
        }
        assertTrue("Unrelated failure: $error",error is G0DomainSqlGate.Denied)
        Log.i("G0FactAdmission","sealed_original_writer=denied persistent_state=unchanged statement_hash=${(error as G0DomainSqlGate.Denied).statementHash}")
    }
}
