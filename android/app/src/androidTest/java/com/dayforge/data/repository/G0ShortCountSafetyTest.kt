package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

abstract class G0ShortCountFixture:G0IndexFixture() {
    internal open val jointCountAuthority:Boolean=false
    @org.junit.Before fun singleCountProfile()=runBlocking<Unit> {
        // The shared reference fixture creates an unused timer plan. This experiment
        // deliberately admits ONE count plan; remove only that synthetic setup row.
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitDao().delete(timerHabit)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
    }
    // Independent read-only observer, never an application SQL capability.
    internal fun pageRowCount(table:String)=pageNativeState().getValue(table).size
    internal suspend fun openCount(maxFacts:Int=128,jointAuthority:Boolean=jointCountAuthority):G0ShortCountStore {
        val schema=trustedDomainSchema(batchedOpening=true)
        freshGateForColdOpen(recurringReads=true,countWrites=true);storage.reopen()
        val current=requireNotNull(db.habitDao().getHabitById(habit.id))
        val (http,_)=channel {successReply(it)}
        return G0ShortCountStore(db,tokens,sessions,authority,domainGate,sender(http),maxFacts,jointAuthority).also {it.initialize(current,schema)}
    }
    internal suspend fun view(store:G0ShortCountStore,source:Long?=null):G0ShortCountStore.View {
        var result:G0ShortCountStore.View?=null;store.readAndPublish(source) {result=it};return requireNotNull(result)
    }
    internal fun reopenReference() {freshGateForColdOpen();storage.reopen()}
    internal suspend fun sameAsOriginal(result:G0ShortCountStore.View) {
        val home=readHome()
        assertEquals(result.habit,home.habits.single())
        assertEquals(result.history,home.counts.getValue(habit.id))
        assertEquals(result.history.completions.toSet(),home.completions.toSet())
        val row=requireNotNull(db.habitDao().getHabitById(habit.id))
        val original=WidgetFactReader(db,tokens,sessions,preferences,CountHistoryReader(db,tokens,sessions),CheckHistoryReader(db,tokens,sessions)).read(row)
        assertEquals(original.count,result.followUp.count)
        assertEquals(original.claim.completionUuid,result.followUp.claim.completionUuid)
    }
}

@RunWith(AndroidJUnit4::class)
open class G0ShortCountSafetyTest:G0ShortCountFixture() {
    @Test fun capacityBoundaryRejectsColdAndWarmGrowthWithoutWritingOrRetiringValidState()=runBlocking<Unit> {
        seedAccepted(2);val before=pageNativeState()
        assertEquals("G0_COUNT_CAPACITY_UNSUPPORTED",runCatching {openCount(maxFacts=1)}.exceptionOrNull()?.message)
        assertEquals(before,pageNativeState());reopenReference()
        val store=openCount(maxFacts=3);val saved=store.increase();val atLimit=pageNativeState()
        assertEquals("G0_COUNT_CAPACITY_UNSUPPORTED",runCatching {store.increase()}.exceptionOrNull()?.message)
        assertEquals(atLimit,pageNativeState());assertEquals(3L,view(store,saved.factId).history.todayQuantity)
        val (http,_)=channel {successReply(it)};val delivery=requireNotNull(store.send(http,saved.requestId))
        assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(delivery))
        val final=view(store,saved.factId);reopenReference();sameAsOriginal(final)
    }
    @Test fun deliveredCollectionsCannotModifyThePrivateProjection()=runBlocking<Unit> {
        seedAccepted(2);val store=openCount();val first=view(store)
        assertTrue(runCatching {(first.history.quantities as MutableMap).clear()}.isFailure)
        assertTrue(runCatching {(first.history.completions as MutableList).clear()}.isFailure)
        assertTrue(runCatching {(first.history.policies as MutableMap).clear()}.isFailure)
        assertEquals(first.history,view(store).history)
        assertEquals(3L,view(store,store.increase().factId).history.todayQuantity)
    }
    @Test fun externalContentChangeIsDetectedBeforeAnyCachedPublication()=runBlocking<Unit> {
        seedAccepted(2);val store=openCount();view(store)
        android.database.sqlite.SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path,null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {raw ->
            raw.execSQL("UPDATE completions SET createdAt=createdAt+1 WHERE id=(SELECT MIN(id) FROM completions)")
        }
        val before=pageNativeState();var published=false
        assertTrue(runCatching {store.readAndPublish {published=true}}.isFailure)
        assertFalse(published);assertEquals(before,pageNativeState())
        assertTrue(runCatching {store.increase()}.isFailure)
    }
    @Test fun cancelledPreparationAndRevokedAccountNeverWriteOrPublish()=runBlocking<Unit> {
        seedAccepted(2);val store=openCount();val before=pageNativeState();val entered=CompletableDeferred<Unit>()
        store.afterPrepareForTest={entered.complete(Unit);awaitCancellation()}
        val job=launch {store.increase()};entered.await();job.cancelAndJoin();assertEquals(before,pageNativeState())
        store.afterPrepareForTest={}
        tokens.saveLoginSession("synthetic-other","synthetic-refresh","member",id(2990),false)
        assertTrue(runCatching {store.increase()}.isFailure)
        var published=false;assertTrue(runCatching {store.readAndPublish {published=true}}.isFailure)
        assertFalse(published);assertEquals(before,pageNativeState())
    }
    @Test fun forgedAckAndWrongOriginalRequestCannotChangeFactOrReceipt()=runBlocking<Unit> {
        seedAccepted(2);val store=openCount();val saved=store.increase()
        val (http,_)=channel {successReply(it)};val delivery=requireNotNull(store.send(http,saved.requestId))
        val before=pageNativeState()
        assertTrue(runCatching {store.acknowledge(delivery.copy(requestId=id(2991)))}.isFailure)
        assertEquals(before,pageNativeState())
        assertTrue(runCatching {store.acknowledge(delivery.copy(transmissionProof="0".repeat(64)))}.isFailure)
        assertEquals(before,pageNativeState())
        assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(delivery))
    }
    @Test fun lostAppendResultDoesNotAuthorizeASecondAppend()=runBlocking<Unit> {
        seedAccepted(2);val store=openCount();val trace=G0PhaseTrace(driverTiming=false)
        val command=store.newCommand()
        endFaults.failAfterNextEndIn(trace)
        assertEquals("G0_COMMIT_RESULT_LOST",runCatching {trace.run {store.increase(command)}}.exceptionOrNull()?.message)
        assertTrue(runCatching {store.increase()}.isFailure)
        reopenReference();assertEquals(3,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
        assertEquals(3L,readHome().counts.getValue(habit.id).todayQuantity)
        val recovered=openCount();val found=requireNotNull(recovered.recover(command))
        assertEquals(command.requestId,found.requestId);assertEquals(command.factUuid,found.factUuid)
        val before=pageNativeState()
        assertEquals("G0_COUNT_COMMAND_ALREADY_EXISTS",runCatching {recovered.increase(command)}.exceptionOrNull()?.message)
        assertEquals(before,pageNativeState());reopenReference()
        ackOnlyOperation();assertEquals(3,pageRowCount("completions"))
    }
    @Test fun appendOutboxSendAckReplayAndThreeReadVersionsMatchOriginal()=runBlocking<Unit> {
        seedAccepted(3);val store=openCount();val before=view(store)
        val saved=store.increase();val pending=view(store,saved.factId)
        assertEquals(4L,pending.history.todayQuantity);assertEquals(saved.factUuid,pending.followUp.claim.completionUuid)
        assertTrue(pending.presentationRevision>before.presentationRevision)
        val statePending=pageNativeState()
        val (http,_)=channel {successReply(it)}
        val delivery=requireNotNull(store.send(http,saved.requestId))
        assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(delivery))
        val accepted=view(store,saved.factId)
        assertFalse(pending.valid());assertTrue(accepted.valid())
        assertEquals(pending.history.quantities,accepted.history.quantities)
        assertEquals(pending.presentationRevision,accepted.presentationRevision)
        assertTrue(accepted.proofRevision>pending.proofRevision)
        val state=pageNativeState()
        assertEquals(NextOperationAcceptance.REPLAYED,store.acknowledge(delivery));assertEquals(state,pageNativeState())
        assertNotEquals(statePending,state)
        reopenReference();sameAsOriginal(accepted)
        assertEquals(4,pageRowCount("completions"));assertEquals(4,pageRowCount("next_acceptances"));assertEquals(0,pageRowCount("sync_outbox"))
        assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(delivery));assertEquals(state,pageNativeState())
        val recovered=openCount();assertEquals(accepted.history,view(recovered).history)
    }
    @Test fun preparedAppendDoesNotHoldAccountAndStalePreparationIsRecomputed()=runBlocking<Unit> {
        seedAccepted(2);val store=openCount();val prepared=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        store.afterPrepareForTest={prepared.complete(Unit);release.await()}
        val pending=async {runCatching {store.increase()}}
        prepared.await()
        withTimeout(5000) {sessions.exclusive {Unit}}
        // A second admitted command advances the exact dependency witness.
        store.afterPrepareForTest={};store.increase()
        release.complete(Unit);val first=pending.await().getOrThrow()
        val result=view(store,first.factId);assertEquals(4L,result.history.todayQuantity)
        assertEquals(4,pageRowCount("completions"));assertEquals(2,pageRowCount("sync_outbox"))
    }
    @Test fun repeatedPreparationConflictsStopBeforeWritingTheOriginalCommand()=runBlocking<Unit> {
        seedAccepted(2);val store=openCount();val command=store.newCommand();var conflicts=0
        lateinit var conflict:suspend ()->Unit
        conflict={store.afterPrepareForTest={};store.increase();conflicts++;store.afterPrepareForTest=conflict}
        store.afterPrepareForTest=conflict
        assertEquals("G0_COUNT_PREPARATION_CHANGED",runCatching {store.increase(command)}.exceptionOrNull()?.message)
        assertEquals(3,conflicts);assertEquals(5L,view(store).history.todayQuantity)
        assertNull(store.recover(command));assertEquals(3,pageRowCount("sync_outbox"))
    }
    @Test fun ackPreparedBeforeAnotherCountUsesFreshDependenciesWithoutLosingEitherFact()=runBlocking<Unit> {
        seedAccepted(2);val store=openCount();val first=store.increase();val (http,_)=channel {successReply(it)}
        val delivery=requireNotNull(store.send(http,first.requestId))
        val prepared=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        store.afterPrepareForTest={prepared.complete(Unit);release.await()}
        val ack=async {store.acknowledge(delivery)};prepared.await()
        store.afterPrepareForTest={};val second=store.increase();release.complete(Unit)
        assertEquals(NextOperationAcceptance.COMMITTED,ack.await())
        val value=view(store,second.factId);assertEquals(4L,value.history.todayQuantity)
        reopenReference();sameAsOriginal(value);assertEquals(1,pageRowCount("sync_outbox"))
        assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(delivery))
    }
    @Test fun finalDecryptFailureRollsBackFactOutboxAndDerivedVersions()=runBlocking<Unit> {
        seedAccepted(2);val store=openCount();val before=pageNativeState()
        store.beforeFinalForTest={failDecrypt=true}
        assertTrue(runCatching {store.increase()}.isFailure)
        assertEquals(before,pageNativeState());failDecrypt=false
        assertTrue(runCatching {view(store)}.isFailure)
        reopenReference();assertEquals(2L,readHome().counts.getValue(habit.id).todayQuantity)
    }
    @Test fun unexpectedOldRowWriteEvenIfRestoredCannotAdvanceCertificate()=runBlocking<Unit> {
        seedAccepted(2);val store=openCount();val before=pageNativeState()
        val first=view(store).history.completions.minBy {it.id}
        store.beforeFinalForTest={
            db.openHelper.writableDatabase.execSQL(G0CountSql.acceptedTime,arrayOf(first.createdAt+1,first.id))
            db.openHelper.writableDatabase.execSQL(G0CountSql.acceptedTime,arrayOf(first.createdAt,first.id))
        }
        assertEquals("G0_COUNT_UNEXPECTED_EFFECT",runCatching {store.increase()}.exceptionOrNull()?.message)
        assertEquals(before,pageNativeState());assertTrue(runCatching {view(store)}.isFailure)
    }
    @Test fun actualUnknownAckCommitRetiresOwnerAndOriginalRequestRecovers()=runBlocking<Unit> {
        seedAccepted(2);val store=openCount();val saved=store.increase()
        val (http,_)=channel {successReply(it)};val delivery=requireNotNull(store.send(http,saved.requestId))
        val trace=G0PhaseTrace(driverTiming=false);endFaults.failAfterNextEndIn(trace)
        assertEquals("G0_COMMIT_RESULT_LOST",runCatching {trace.run {store.acknowledge(delivery)}}.exceptionOrNull()?.message)
        assertTrue(runCatching {view(store)}.isFailure)
        val state=pageNativeState();reopenReference()
        assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(delivery));assertEquals(state,pageNativeState())
        assertEquals(3L,readHome().counts.getValue(habit.id).todayQuantity)
    }
    @Test fun coldCorruptionAndUnsupportedFirstDayNeverBecomeCertified()=runBlocking<Unit> {
        configureAction();assertTrue(runCatching {openCount()}.isFailure)
        reopenReference();seedFact();ackOnlyOperation()
        db.withTransaction {db.openHelper.writableDatabase.execSQL("UPDATE completions SET createdAt=createdAt+1")}
        val before=pageNativeState();assertTrue(runCatching {openCount()}.isFailure);assertEquals(before,pageNativeState())
    }
    @Test fun defaultMetricGateStillRejectsCountSqlAndColdSentRequestsRemainUnsupported()=runBlocking<Unit> {
        seedAccepted(2);val fact=seedFact();val row=db.syncOutboxDao().getEntityIntents("completion",fact.uuid).single()
        val (http,_)=channel {successReply(it)};sender(http).sendOperation(access(),row.operationId)
        val before=pageNativeState();assertTrue(runCatching {openCount()}.isFailure);assertEquals(before,pageNativeState())
        reopenReference();sealDomain()
        assertTrue(runCatching {domainGate.transaction(db,G0DomainSqlGate.Phase.BUSINESS) {
            db.openHelper.writableDatabase.execSQL(G0CountSql.acceptedTime,arrayOf(0L,fact.id))
        }}.exceptionOrNull() is G0DomainSqlGate.Denied)
    }
}
