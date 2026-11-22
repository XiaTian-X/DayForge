package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.HabitEntity
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Complete original reader is the oracle. Mutations are deliberate raw corruption fixtures,
 * never an approved business SQL surface. Every read still checks all retained content. */
@RunWith(AndroidJUnit4::class)
class G0FactBlockEquivalenceTest:G0RecurringActionFixture() {
    private suspend fun current()=requireNotNull(db.habitDao().getHabitById(habit.id))
    private suspend fun equal(reader:G0BlockCompletionReader,expected:HabitEntity?=null):RecurringCompletionEvidence {
        val snapshot=expected ?: current()
        val before=pageNativeState()
        val reference=RecurringCompletionEvidenceReader(db,tokens,sessions).read(snapshot)
        assertEquals(reference,reader.read(snapshot));assertEquals(before,pageNativeState())
        return reference
    }
    private suspend fun bothReject(reader:G0BlockCompletionReader,expected:HabitEntity?=null):String? {
        val snapshot=expected ?: current()
        val reference=rejectWithoutChanges { RecurringCompletionEvidenceReader(db,tokens,sessions).read(snapshot) }
        val candidate=rejectWithoutChanges { reader.read(snapshot) }
        assertEquals(reference.javaClass,candidate.javaClass);assertEquals(reference.message,candidate.message)
        return reference.message
    }
    @Test fun appendAckAndFurtherAppendReuseOnlyUnchangedFacts()=runBlocking<Unit> {
        configureAction();val actions=actions();val reader=G0BlockCompletionReader(db,tokens,sessions)
        try {
            equal(reader);increment(actions);equal(reader);val initial=reader.blocks.hits
            equal(reader);assertTrue(reader.blocks.hits>initial)
            ackOnlyOperation();equal(reader);val accepted=reader.blocks.hits
            increment(actions);assertEquals(2,equal(reader).completions.size)
            assertTrue(reader.blocks.hits>accepted);ackOnlyOperation();equal(reader)
            reader.close();assertEquals(0,reader.blocks.entries)
            assertEquals("G0_FACT_READER_CLOSED",rejectWithoutChanges { reader.read(current()) }.message)
        } finally { reader.close() }
    }
    @Test fun acceptedCheckThenUndoCannotReuseDeletedMembership()=runBlocking<Unit> {
        configureAction(check=true);val actions=actions();val reader=G0BlockCompletionReader(db,tokens,sessions)
        try {
            actions.service.toggleCheckIn(app,habit.id);ackOnlyOperation();assertEquals(1,equal(reader).completions.size)
            val retained=db.completionDao().getByHabitOnce(habit.id).single()
            equal(reader);actions.service.toggleCheckIn(app,habit.id)
            assertTrue(equal(reader).completions.isEmpty());ackOnlyOperation();equal(reader)
            db.completionDao().insert(retained)
            assertEquals("COUNT_FACT_INVALID",bothReject(reader))
        } finally { reader.close() }
    }
    @Test fun nativeQuantityCoercionIsRejectedAfterWarmReuse()=runBlocking<Unit> {
        configureAction();increment(actions());ackOnlyOperation();val reader=G0BlockCompletionReader(db,tokens,sessions)
        try {
            equal(reader);equal(reader)
            db.openHelper.writableDatabase.execSQL("UPDATE completions SET value=1.5 WHERE habitId=?",arrayOf(habit.id))
            assertEquals("COUNT_FACT_INVALID",bothReject(reader))
        } finally { reader.close() }
    }
    @Test fun coherentShadowChangeIsNotHiddenByAFormerValidDigest()=runBlocking<Unit> {
        configureAction();increment(actions());ackOnlyOperation();val reader=G0BlockCompletionReader(db,tokens,sessions)
        try {
            equal(reader);equal(reader)
            val fact=db.completionDao().getByHabitOnce(habit.id).single()
            val state=requireNotNull(db.syncOutboxDao().getState("activity_event",fact.uuid))
            val body=JsonObject(Json.parseToJsonElement(state.payloadJson!!).jsonObject+ ("value" to JsonPrimitive(9))).toString()
            db.syncOutboxDao().upsertState(state.copy(payloadJson=body,payloadHash=syncPayloadHash(body)))
            bothReject(reader)
        } finally { reader.close() }
    }
    @Test fun additionalMatchingSourceAndChangedOwnerAreCheckedOnEverySnapshot()=runBlocking<Unit> {
        configureAction();increment(actions());val reader=G0BlockCompletionReader(db,tokens,sessions)
        try {
            equal(reader);equal(reader)
            val row=db.syncOutboxDao().getAll().single();val origin=requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION,row.operationId))
            val op=decodeNextOperationIntent(origin.intentJson);val newId=id(1901)
            db.nextRequestDao().insertOrigin(origin.copy(requestId=newId,queueId=origin.queueId+10000,
                intentJson=Json.encodeToString(op.copy(operationId=newId))))
            assertEquals("COUNT_FACT_INVALID",bothReject(reader))
            db.openHelper.writableDatabase.execSQL("DELETE FROM next_request_origins WHERE requestId=?",arrayOf(newId))
            equal(reader)
            db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET accountId=? WHERE requestId=?",arrayOf(id(1902),origin.requestId))
            // The original count-day origin hash fails before the later account diagnostic.
            assertEquals("Failed requirement.",bothReject(reader))
        } finally { reader.close() }
    }
    @Test fun missingDayAndChangedExpectedHabitCannotBorrowWarmValidity()=runBlocking<Unit> {
        configureAction();increment(actions());val reader=G0BlockCompletionReader(db,tokens,sessions)
        try {
            equal(reader);equal(reader)
            assertEquals("COUNT_ACTIVITY_CHANGED",bothReject(reader,habit))
            db.openHelper.writableDatabase.execSQL("DELETE FROM count_days WHERE habitUuid=?",arrayOf(habit.uuid))
            bothReject(reader)
        } finally { reader.close() }
    }
    @Test fun accountReplacementAndDecryptFailureStillRequireFreshAuthority()=runBlocking<Unit> {
        configureAction();increment(actions());val reader=G0BlockCompletionReader(db,tokens,sessions)
        try {
            equal(reader);val before=decryptCount.get();equal(reader);assertTrue(decryptCount.get()>before)
            failDecrypt=true
            try { bothReject(reader) } finally { failDecrypt=false }
            equal(reader)
            tokens.saveLoginSession("synthetic-other","synthetic-refresh","member",id(1903),false)
            assertEquals(0,reader.blocks.entries);bothReject(reader)
        } finally { reader.close() }
    }
    @Test fun everyPureMapperDependencyChangesTheMemoKey()=runBlocking<Unit> {
        configureAction();increment(actions());ackOnlyOperation()
        val base=current();val full=RecurringCompletionEvidenceReader(db,tokens,sessions).read(base)
        val blocks=G0RecurringFactBlocks(db);val original=RecurringCompletionFactEvidence(db)
        val owner=local().authentication.userId
        try { db.withTransaction {
            assertEquals(original.verify(base,full.completions,full.policies,owner),blocks.verify(base,full.completions,full.policies,owner))
            blocks.verify(base,full.completions,full.policies,owner);assertTrue(blocks.hits>0)
            val variants=listOf(base.copy(id=base.id+1000),base.copy(uuid=id(1910)),
                base.copy(habitType=com.dayforge.data.model.HabitType.GOAL),base.copy(completionPolicy="once"),
                base.copy(schedule=com.dayforge.data.model.HabitSchedule.Once()))
            for(changed in variants) {
                val a=try { original.verify(changed,full.completions,full.policies,owner);null } catch(e:Exception) { e }
                val b=try { blocks.verify(changed,full.completions,full.policies,owner);null } catch(e:Exception) { e }
                assertNotNull(a);assertNotNull(b);assertEquals(a!!.javaClass,b!!.javaClass);assertEquals(a.message,b.message)
            }
            val changedPolicies=full.policies.mapValues { (_,policy)->policy.copy(targetValue=policy.targetValue+1) }
            val a=try { original.verify(base,full.completions,changedPolicies,owner);null } catch(e:Exception) { e }
            val b=try { blocks.verify(base,full.completions,changedPolicies,owner);null } catch(e:Exception) { e }
            assertNotNull(a);assertNotNull(b);assertEquals(a!!.javaClass,b!!.javaClass);assertEquals(a.message,b.message)
        } } finally { blocks.close() }
    }
    @Test fun zeroAndTinyBudgetsFallBackToCompleteValidation()=runBlocking<Unit> {
        configureAction();increment(actions());ackOnlyOperation()
        for((entries,bytes) in listOf(0 to 0L,1 to 4096L)) {
            val reader=G0BlockCompletionReader(db,tokens,sessions,entries,bytes)
            try {
                equal(reader);equal(reader);assertTrue(reader.blocks.entries<=entries)
                assertTrue(reader.blocks.retainedBytes<=bytes)
            } finally { reader.close() }
        }
    }
    @Test fun pureMemoDoesNotRetainFailureCancellationOrMutableJsonAndHasBoundedEviction()=runBlocking<Unit> {
        val memo=G0FactPureMemo(2,20)
        assertEquals(1,memo.value("a",10) { 1 });assertEquals(1,memo.value("a",10) { error("HIT_REQUIRED") })
        val thrown=try { memo.value("bad",10) { error("EXPECTED") };null } catch(e:IllegalStateException) { e.message }
        assertEquals("EXPECTED",thrown);assertEquals(1,memo.entries)
        val job=launch { memo.value("cancel",10) { currentCoroutineContext().cancel();42 } }
        job.join();assertTrue(job.isCancelled);assertEquals(1,memo.entries)
        memo.value("b",10) { 2 };memo.value("c",10) { 3 };assertEquals(2,memo.entries);assertEquals(20L,memo.retainedBytes)
        memo.clear();assertEquals(0,memo.entries)
        val backing=mutableMapOf<String,JsonElement>("nested" to JsonArray(listOf(JsonPrimitive(1))))
        val frozen=G0FactPureMemo.freeze(JsonObject(backing));backing.clear()
        assertEquals(JsonPrimitive(1),frozen.getValue("nested").jsonArray.single())
        val failure=try { (frozen as MutableMap<String,JsonElement>).clear();null } catch(e:Exception) { e }
        assertNotNull(failure);assertEquals(1,frozen.size)
        memo.value("clearDuringCompute",10) { memo.clear();3 };assertEquals(0,memo.entries)
        memo.value("closeDuringCompute",10) { memo.close();3 };assertEquals(0,memo.entries)
        val closed=try { memo.value("closed",10) { error("MUST_NOT_COMPUTE") };null } catch(e:IllegalStateException) { e.message }
        assertEquals("G0_FACT_MEMO_CLOSED",closed)
    }
}
