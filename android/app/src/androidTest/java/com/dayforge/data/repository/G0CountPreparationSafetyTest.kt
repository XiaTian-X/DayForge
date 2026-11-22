package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0CountPreparationSafetyTest:G0CountJourneySafetyTest() {
    override val reuseCountPreparation=true
    override fun domainOpeningCallback()=G0SchemaOpeningCallback(true)
    private fun same(a:CompletionActionSnapshot,b:CompletionActionSnapshot) {
        assertEquals(a.edit.value,b.edit.value)
        val x=a.edit.authority;val y=b.edit.authority
        assertEquals(x==null,y==null)
        if(x!=null && y!=null) {
            assertEquals(x.session,y.session);assertEquals(x.uuid,y.uuid);assertEquals(x.type,y.type)
            assertEquals(x.original,y.original);assertEquals(x.goalChildUuids,y.goalChildUuids)
            assertEquals(x.capturedDeviceId,y.capturedDeviceId);assertEquals(x.completionDeviceCaptured,y.completionDeviceCaptured)
            // This fixture is plain recurring. Do not claim round-profile equivalence from it.
            assertNull(x.rounds);assertNull(y.rounds)
        }
        val p=a.evidence;val q=b.evidence;assertEquals(p==null,q==null)
        if(p!=null && q!=null) {
            assertEquals(p.habit,q.habit);assertEquals(p.countHistory,q.countHistory)
            assertEquals(p.checkHistory,q.checkHistory);assertEquals(p.followUp,q.followUp)
        }
    }
    private suspend fun equalPrepare(reader:G0CountResultReader) {
        val before=pageNativeState();val repo=actions().repository
        same(repo.prepareCompletion(habit.id),reader.prepareCompletion(habit.id))
        assertEquals(before,pageNativeState())
    }
    private suspend fun bothReject(reader:G0CountResultReader) {
        val a=rejectWithoutChanges { actions().repository.prepareCompletion(habit.id) }
        val b=rejectWithoutChanges { reader.prepareCompletion(habit.id) }
        assertEquals(a.javaClass,b.javaClass);assertEquals(a.message,b.message)
    }
    @Test fun completePreparationMatchesOriginalBeforeAndAfterAppendAndAck()=runBlocking<Unit> {
        configureAction();val reader=resultReader();equalPrepare(reader)
        increment(actions());equalPrepare(reader);ackOnlyOperation();equalPrepare(reader)
        val trace=G0PhaseTrace(driverTiming=false);val beforeHits=reader.factBlockHits
        trace.run { equalPrepare(reader) }
        assertTrue(reader.factBlockHits>beforeHits)
        // equalPrepare executes two independent complete reads, neither is a permission cache hit.
        assertEquals(4,trace.count("decrypt_access"));assertEquals(0,trace.count("decrypt_refresh"))
    }
    @Test fun checkPreparationStillMatchesOriginalThroughUndoAndAck()=runBlocking<Unit> {
        configureAction(check=true);val reader=resultReader();val actions=actions()
        equalPrepare(reader);actions.service.toggleCheckIn(app,habit.id);equalPrepare(reader)
        ackOnlyOperation();equalPrepare(reader);actions.service.toggleCheckIn(app,habit.id)
        equalPrepare(reader);ackOnlyOperation();equalPrepare(reader)
    }
    private suspend fun changeAcceptedValue() {
        val fact=db.completionDao().getByHabitOnce(habit.id).minBy { it.id }
        val state=requireNotNull(db.syncOutboxDao().getState("activity_event",fact.uuid))
        val body=JsonObject(Json.parseToJsonElement(state.payloadJson!!).jsonObject+("value" to JsonPrimitive(9))).toString()
        db.syncOutboxDao().upsertState(state.copy(payloadJson=body,payloadHash=syncPayloadHash(body)))
    }
    @Test fun warmedPreparationRejectsChangedFactsMembershipSourcesAndCoherentShadow()=runBlocking<Unit> {
        configureAction();increment(actions());ackOnlyOperation();val image=storage.snapshotClosedDatabase()
        repeat(5) { kind ->
            restoreForJourney(image);val reader=resultReader();equalPrepare(reader);equalPrepare(reader)
            val origin=db.nextRequestDao().let { dao ->
                // The seed has exactly one origin, irrespective of ACK consuming its outbox.
                db.openHelper.writableDatabase.query("SELECT requestId FROM next_request_origins").use {
                    assertTrue(it.moveToFirst());requireNotNull(dao.origin(NEXT_OPERATION,it.getString(0)))
                }
            }
            when(kind) {
                0->db.openHelper.writableDatabase.execSQL("UPDATE completions SET value=1.5 WHERE habitId=?",arrayOf(habit.id))
                1->db.openHelper.writableDatabase.execSQL("DELETE FROM count_days WHERE habitUuid=?",arrayOf(habit.uuid))
                2->changeAcceptedValue()
                3->{
                    val newId=id(2010);val op=decodeNextOperationIntent(origin.intentJson)
                    db.nextRequestDao().insertOrigin(origin.copy(requestId=newId,queueId=origin.queueId+10000,
                        intentJson=Json.encodeToString(op.copy(operationId=newId))))
                }
                4->db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET accountId=? WHERE requestId=?",arrayOf(id(2011),origin.requestId))
            }
            bothReject(reader);reader.close()
        }
    }
    @Test fun changedOldAcceptedFactAfterCommitCannotReusePreparationSuccess()=runBlocking<Unit> {
        configureAction();increment(actions());ackOnlyOperation();val flow=journey()
        flow.afterCommitForTest={changeAcceptedValue()}
        assertTrue(flow.increment());flow.awaitSettled()
        assertEquals(G0CountJourney.Phase.SAVED_REFRESH_FAILED,flow.state.value.phase)
        assertNull(flow.state.value.view);assertEquals(2,count("completions"))
        assertEquals(1,flow.events.count { it.kind=="committed" });assertFalse(flow.increment())
    }
    @Test fun preparationEndFailureCannotStartAWrite()=runBlocking<Unit> {
        configureAction();val flow=journey();val trace=G0PhaseTrace(driverTiming=false);flow.traceForTest=trace
        endFaults.failAfterNextEndIn(trace);assertTrue(flow.increment());flow.awaitSettled()
        assertEquals("G0_COMMIT_RESULT_LOST",flow.lastError?.message)
        assertEquals(G0CountJourney.Phase.RECOVERY,flow.state.value.phase)
        assertFalse(flow.events.any { it.kind in setOf("prepared","write_started","committed") })
        assertEquals(0,count("completions"));assertEquals(0,count("sync_outbox"))
    }
    @Test fun warmedPreparationCannotReuseAuthorityAfterRealDecryptFailureOrAccountChange()=runBlocking<Unit> {
        configureAction();increment(actions());val reader=resultReader();equalPrepare(reader);equalPrepare(reader)
        failDecrypt=true
        try { bothReject(reader) } finally { failDecrypt=false }
        tokens.saveLoginSession("synthetic-other","synthetic-refresh","member",id(2012),false)
        bothReject(reader)
    }
}
