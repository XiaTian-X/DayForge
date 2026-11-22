package com.dayforge.data.repository

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.domain.model.CountDayPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0HouseholdCountSafetyTest:G0HouseholdCountFixture() {
    private suspend fun rejectedUnchanged(body:suspend ()->Unit):Throwable {
        val before=pageNativeState();val failure=runCatching {body()}.exceptionOrNull()
        assertNotNull("Unsupported or damaged operation was accepted",failure)
        assertEquals(before,pageNativeState());return requireNotNull(failure)
    }
    private suspend fun ack(store:G0HouseholdCountStore,http:NextSyncHttp,saved:G0HouseholdCountStore.Saved) {
        val delivery=requireNotNull(store.send(http,saved.requestId))
        assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(delivery))
        val before=pageNativeState()
        assertEquals(NextOperationAcceptance.REPLAYED,store.acknowledge(delivery));assertEquals(before,pageNativeState())
    }
    private suspend fun aba() {
        val original=dataStore.data.first()
        dataStore.edit {it[stringPreferencesKey("sync_device_capability_revision")]="2"}
        dataStore.updateData {original};assertEquals(original,dataStore.data.first())
    }

    @Test fun twoPastOnlyHistoriesHaveOriginalCurrentPoliciesWithoutCapturingToday()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,_)=channel {successReply(it)};val before=pageNativeState()
        val store=openHousehold(http);val result=householdView(store)
        assertEquals(seed.habits.map {it.uuid}.toSet(),result.cards.keys)
        for(row in seed.habits) {
            val card=result.cards.getValue(row.uuid)
            assertEquals(seed.references.getValue(row.uuid),card.history)
            assertEquals(0L,card.history.todayQuantity);assertFalse(seed.today in card.history.policies)
            assertEquals(CountDayPolicy(row.targetValue,false),card.history.todayPolicy)
        }
        assertEquals(before,pageNativeState());assertEquals(8,pageRowCount("count_days"))
        reopenReference();sameHouseholdAsOriginal(result);assertEquals(before,pageNativeState())
    }

    @Test fun twoCompletelyNewObjectsReadWithoutWritesThenCaptureIndependentFirstDays()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(0);val (http,_)=channel {successReply(it)};val before=pageNativeState()
        val store=openHousehold(http);val empty=householdView(store)
        assertEquals(before,pageNativeState());assertEquals(0,pageRowCount("count_days"))
        for(row in seed.habits) {
            val history=empty.cards.getValue(row.uuid).history
            assertTrue(history.completions.isEmpty());assertTrue(history.policies.isEmpty());assertTrue(history.unknownDates.isEmpty())
            assertEquals(0L,history.todayQuantity);assertEquals(CountDayPolicy(row.targetValue,false),history.todayPolicy)
        }
        val a=store.increase(seed.habits[0].uuid);val b=store.increase(seed.habits[1].uuid)
        ack(store,http,a);ack(store,http,b)
        val result=householdView(store,mapOf(a.habitUuid to a.factId,b.habitUuid to b.factId))
        assertEquals(2,pageRowCount("count_days"));assertEquals(2,pageRowCount("completions"))
        assertEquals(2,pageRowCount("next_acceptances"));assertEquals(0,pageRowCount("sync_outbox"))
        reopenReference();sameHouseholdAsOriginal(result)
    }

    @Test fun firstAndLaterCountsForBothObjectsBindTheirOwnDayAndMatchOriginalReaders()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,_)=channel {successReply(it)};val store=openHousehold(http)
        val a=seed.habits[0];val b=seed.habits[1]
        val firstA=store.increase(a.uuid);val firstB=store.increase(b.uuid)
        val laterA=store.increase(a.uuid);val laterB=store.increase(b.uuid)
        assertEquals(a.uuid,firstA.habitUuid);assertEquals(b.uuid,firstB.habitUuid)
        assertEquals(10,pageRowCount("count_days"));assertEquals(12,pageRowCount("completions"))
        assertEquals(4,pageRowCount("sync_outbox"))
        val pending=householdView(store,mapOf(a.uuid to laterA.factId,b.uuid to laterB.factId))
        for(row in seed.habits) {
            val history=pending.cards.getValue(row.uuid).history
            assertEquals(2L,history.todayQuantity);assertEquals(CountDayPolicy(row.targetValue,false),history.policies[seed.today])
            assertEquals(seed.references.getValue(row.uuid).quantities+mapOf(seed.today to 2L),history.quantities)
        }
        for(saved in listOf(firstA,firstB,laterA,laterB))ack(store,http,saved)
        val result=householdView(store,mapOf(a.uuid to laterA.factId,b.uuid to laterB.factId))
        assertFalse(pending.valid());assertEquals(pending.cards.mapValues {it.value.history.quantities},
            result.cards.mapValues {it.value.history.quantities})
        assertEquals(12,pageRowCount("next_acceptances"));assertEquals(0,pageRowCount("sync_outbox"))
        assertEquals(10,pageRowCount("count_days"));reopenReference();sameHouseholdAsOriginal(result)
    }

    @Test fun laterCountCannotSendBeforeItsOwnFirstAckOrBorrowOtherObjectsFirstAck()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,server)=channel {successReply(it)};var store=openHousehold(http)
        val a1=store.increase(seed.habits[0].uuid);val a2=store.increase(seed.habits[0].uuid)
        val b1=store.increase(seed.habits[1].uuid);val b2=store.increase(seed.habits[1].uuid)
        rejectedUnchanged {store.send(http,a2.requestId)};rejectedUnchanged {store.send(http,b2.requestId)}
        assertTrue(server.requests.none {it.path.endsWith("/push")})
        ack(store,http,a1)
        val candidateFailure=rejectedUnchanged {store.send(http,b2.requestId)}
        assertTrue(candidateFailure is NextRequestException)
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,(candidateFailure as NextRequestException).reason)
        assertEquals(1,server.requests.count {it.path.endsWith("/push")})
        val beforeOriginal=pageNativeState();reopenReference()
        val originalFailure=rejectedUnchanged {sender(http).sendOperation(access(),b2.requestId)}
        assertTrue(originalFailure is NextRequestException)
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,(originalFailure as NextRequestException).reason)
        assertEquals(beforeOriginal,pageNativeState());assertEquals(1,server.requests.count {it.path.endsWith("/push")})
        store=openHousehold(http);assertEquals(beforeOriginal,pageNativeState())
        ack(store,http,a2);ack(store,http,b1);ack(store,http,b2)
        assertEquals(4,server.requests.count {it.path.endsWith("/push")})
        assertEquals(12,pageRowCount("completions"));assertEquals(12,pageRowCount("next_acceptances"))
        assertEquals(0,pageRowCount("sync_outbox"))
        val result=householdView(store);reopenReference();sameHouseholdAsOriginal(result)
    }

    @Test fun foreignObjectSourceCannotPublishAnotherObjectsFollowUp()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,_)=channel {successReply(it)};val store=openHousehold(http)
        val b=store.increase(seed.habits[1].uuid);var published=false
        rejectedUnchanged {store.readAndPublish(mapOf(seed.habits[0].uuid to b.factId)) {published=true}}
        assertFalse(published);assertEquals(9,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
    }

    @Test fun coldSourceUnionRejectsWrongObjectMissingMemberAndOrphanReceipt()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val image=storage.snapshotClosedDatabase()
        for(kind in listOf("wrong_object","missing_fact","orphan_receipt")) {
            freshGateForColdOpen();restoreForJourney(image)
            when(kind) {
                "wrong_object"->mutateHouseholdFile("UPDATE next_request_origins SET intentJson=REPLACE(intentJson,?,?) WHERE requestId=?",
                    arrayOf(seed.habits[0].uuid,seed.habits[1].uuid,seed.requestIds[0]))
                "missing_fact"->mutateHouseholdFile("DELETE FROM completions WHERE id=?",arrayOf(seed.factIds[1]))
                else->mutateHouseholdFile("UPDATE next_acceptances SET requestId=? WHERE requestId=?",
                    arrayOf(id(4802),seed.requestIds[1]))
            }
            val (http,_)=channel {successReply(it)}
            rejectedUnchanged {openHousehold(http)}
        }
    }

    @Test fun missingDayWithFactOrOnlyRetainedSourceCannotBeRecaptured()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val row=originalHouseholdFact(seed.habits[0])
        val request=db.syncOutboxDao().getEntityIntents("completion",row.uuid).single().operationId
        val image=storage.snapshotClosedDatabase()
        for(removeProjection in listOf(false,true)) {
            freshGateForColdOpen();restoreForJourney(image)
            mutateHouseholdFile("DELETE FROM count_days WHERE habitId=? AND localDate=?",arrayOf(row.habitId,seed.today.toString()))
            if(removeProjection) {
                mutateHouseholdFile("DELETE FROM completions WHERE uuid=?",arrayOf(row.uuid))
                mutateHouseholdFile("DELETE FROM sync_outbox WHERE operationId=?",arrayOf(request))
            }
            val retained=db.nextRequestDao().origin(NEXT_OPERATION,request);assertNotNull(retained)
            val failure=rejectedUnchanged {originalHouseholdFact(seed.habits[0])}
            assertEquals("COUNT_DAY_INVALID",failure.message)
            val (http,_)=channel {successReply(it)};rejectedUnchanged {openHousehold(http)}
            reopenReference();assertEquals(retained,db.nextRequestDao().origin(NEXT_OPERATION,request))
        }
    }

    @Test fun finalAuthenticationFailureRollsBackFirstDayFactSourceAndOutboxTogether()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,_)=channel {successReply(it)};val store=openHousehold(http)
        store.beforeFinalForTest={failDecrypt=true}
        try {rejectedUnchanged {store.increase(seed.habits[0].uuid)}} finally {failDecrypt=false;store.beforeFinalForTest={}}
        assertEquals(8,pageRowCount("count_days"));assertEquals(8,pageRowCount("completions"))
        assertEquals(8,pageRowCount("next_request_origins"));assertEquals(0,pageRowCount("sync_outbox"))
        assertTrue(runCatching {householdView(store)}.isFailure)
        reopenReference();for(row in seed.habits)assertEquals(seed.references.getValue(row.uuid),CountHistoryReader(db,tokens,sessions).read(row))
    }

    @Test fun cancelledPreparationDoesNotCaptureEitherDayAndARealNextCommandStillWorks()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,_)=channel {successReply(it)};val store=openHousehold(http)
        val before=pageNativeState();val entered=CompletableDeferred<Unit>()
        store.afterPrepareForTest={entered.complete(Unit);awaitCancellation()}
        val operation=launch(Dispatchers.IO) {store.increase(seed.habits[0].uuid)}
        withTimeout(15_000) {entered.await()};operation.cancelAndJoin();store.afterPrepareForTest={}
        assertEquals(before,pageNativeState())
        val next=store.increase(seed.habits[1].uuid);ack(store,http,next)
        val result=householdView(store,mapOf(seed.habits[1].uuid to next.factId))
        assertEquals(0L,result.cards.getValue(seed.habits[0].uuid).history.todayQuantity)
        assertEquals(1L,result.cards.getValue(seed.habits[1].uuid).history.todayQuantity)
        reopenReference();sameHouseholdAsOriginal(result)
    }

    @Test fun unknownFirstDayCommitRecoversSameUnsentCommandWithoutAnotherDayOrFact()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,_)=channel {successReply(it)};val store=openHousehold(http)
        val command=store.newCommand(seed.habits[0].uuid);val trace=G0PhaseTrace(driverTiming=false)
        endFaults.failAfterNextEndIn(trace)
        assertEquals("G0_COMMIT_RESULT_LOST",runCatching {trace.run {store.increase(command)}}.exceptionOrNull()?.message)
        assertTrue(runCatching {householdView(store)}.isFailure)
        assertEquals(9,pageRowCount("count_days"));assertEquals(9,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
        reopenReference();val recovered=openHousehold(http);val before=pageNativeState()
        val found=requireNotNull(recovered.recover(command))
        assertEquals(command.requestId,found.requestId);assertEquals(command.factUuid,found.factUuid)
        assertEquals(seed.habits[0].uuid,found.habitUuid);assertEquals(before,pageNativeState())
        assertEquals(listOf(command.requestId),recovered.pendingRequestIds())
        rejectedUnchanged {recovered.increase(command)};ack(recovered,http,found)
        val result=householdView(recovered,mapOf(found.habitUuid to found.factId))
        assertEquals(9,pageRowCount("count_days"));assertEquals(0,pageRowCount("sync_outbox"))
        reopenReference();sameHouseholdAsOriginal(result)
    }

    @Test fun unknownAckReplaysOriginalReceiptAndColdOwnerRetainsOtherObjectsUnsentFirstDay()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,_)=channel {successReply(it)};val store=openHousehold(http)
        val a=store.increase(seed.habits[0].uuid);val b=store.increase(seed.habits[1].uuid)
        val delivery=requireNotNull(store.send(http,a.requestId));val trace=G0PhaseTrace(driverTiming=false)
        endFaults.failAfterNextEndIn(trace)
        assertEquals("G0_COMMIT_RESULT_LOST",runCatching {trace.run {store.acknowledge(delivery)}}.exceptionOrNull()?.message)
        assertEquals(1,pageRowCount("sync_outbox"));assertEquals(9,pageRowCount("next_acceptances"))
        val durable=pageNativeState();reopenReference()
        assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(delivery));assertEquals(durable,pageNativeState())
        val recovered=openHousehold(http);assertEquals(listOf(b.requestId),recovered.pendingRequestIds())
        ack(recovered,http,b);val result=householdView(recovered,mapOf(a.habitUuid to a.factId,b.habitUuid to b.factId))
        assertEquals(10,pageRowCount("completions"));assertEquals(10,pageRowCount("count_days"))
        assertEquals(10,pageRowCount("next_acceptances"));assertEquals(0,pageRowCount("sync_outbox"))
        reopenReference();sameHouseholdAsOriginal(result)
    }

    @Test fun authorityAbaCannotRebaseEitherObjectsReadWriteRecoveryOrSyncEntry()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val image=storage.snapshotClosedDatabase()
        for(kind in listOf("write","read","pending","recover","send","ack")) {
            freshGateForColdOpen();restoreForJourney(image);val (http,_)=channel {successReply(it)};val store=openHousehold(http)
            val command=store.newCommand(seed.habits[1].uuid)
            val saved=if(kind in setOf("send","ack"))store.increase(seed.habits[0].uuid) else null
            val delivered=if(kind=="ack")requireNotNull(store.send(http,saved!!.requestId)) else null
            store.afterEntryAuthenticationForTest={aba()};var published=false
            val failure=rejectedUnchanged {when(kind) {
                "write"->store.increase(command)
                "read"->store.readAndPublish {published=true}
                "pending"->store.pendingRequestIds()
                "recover"->store.recover(command)
                "send"->store.send(http,saved!!.requestId)
                else->store.acknowledge(requireNotNull(delivered))
            }}
            assertTrue("$kind must retain the original authority lease: $failure",failure is G0AuthorityOwner.Denied)
            assertFalse(published)
        }
    }

    @Test fun crossObjectAckOrForgedTransmissionCannotChangeEitherFactOrDay()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,_)=channel {successReply(it)};val store=openHousehold(http)
        val a=store.increase(seed.habits[0].uuid);val b=store.increase(seed.habits[1].uuid)
        val da=requireNotNull(store.send(http,a.requestId));val db=requireNotNull(store.send(http,b.requestId))
        rejectedUnchanged {store.acknowledge(da.copy(requestId=b.requestId))}
        rejectedUnchanged {store.acknowledge(db.copy(transmissionProof="0".repeat(64)))}
        assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(da))
        assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(db))
        val result=householdView(store,mapOf(a.habitUuid to a.factId,b.habitUuid to b.factId))
        reopenReference();sameHouseholdAsOriginal(result)
    }

    @Test fun externalHistoryChangeOrClosedConnectionInvalidatesBothCachedCards()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val image=storage.snapshotClosedDatabase()
        for(replace in listOf(false,true)) {
            freshGateForColdOpen();restoreForJourney(image);val (http,_)=channel {successReply(it)};val store=openHousehold(http)
            val old=householdView(store)
            if(replace)assertTrue(runCatching {storage.reopen()}.exceptionOrNull() is G0DomainSqlGate.Denied)
            else mutateHouseholdFile("UPDATE completions SET createdAt=createdAt+1 WHERE id=?",arrayOf(seed.factIds[1]))
            var published=false
            rejectedUnchanged {store.readAndPublish {published=true}}
            assertFalse(published);assertFalse(old.valid())
        }
    }

    @Test fun unsupportedThirdObjectAndCountdownCannotAcquireHouseholdCertificate()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val image=storage.snapshotClosedDatabase()
        for(third in listOf(false,true)) {
            freshGateForColdOpen();restoreForJourney(image)
            // Unsupported synthetic profile, not a production structural-write grant.
            db.withTransaction {
                val sql=db.openHelper.writableDatabase;sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                if(third)db.habitDao().insert(seed.habits[0].copy(id=0,uuid=id(4803),name="Unsupported third"))
                else db.habitDao().update(seed.habits[1].copy(isCountdown=true))
                sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            }
            val (http,_)=channel {successReply(it)};rejectedUnchanged {openHousehold(http)}
        }
    }

    @Test fun capacityIsGlobalAcrossBothObjectsAndRefusalDoesNotCreateTheOtherDay()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,_)=channel {successReply(it)}
        rejectedUnchanged {openHousehold(http,maxFacts=7)}
        reopenReference();val store=openHousehold(http,maxFacts=9)
        val a=store.increase(seed.habits[0].uuid)
        rejectedUnchanged {store.increase(seed.habits[1].uuid)}
        assertEquals(9,pageRowCount("completions"));assertEquals(9,pageRowCount("count_days"))
        val full=householdView(store);assertEquals(0L,full.cards.getValue(seed.habits[1].uuid).history.todayQuantity)
        assertFalse(seed.today in full.cards.getValue(seed.habits[1].uuid).history.policies)
        ack(store,http,a);assertEquals(0,pageRowCount("sync_outbox"))
        val result=householdView(store);reopenReference();sameHouseholdAsOriginal(result)
    }

    @Test fun daySqlIsDeniedByOldCountScopeAndByReadFinalOrMissingHouseholdLease()=runBlocking<Unit> {
        val empty=storage.snapshotClosedDatabase()
        val day=java.time.LocalDate.now().plusDays(1).toString()
        fun statement(bind:Boolean)=if(bind)G0HouseholdCountSql.bindDay else G0HouseholdCountSql.insertDay
        fun args(row:com.dayforge.data.local.entity.HabitEntity,bind:Boolean):Array<Any?> =
            if(bind)arrayOf(id(4805),"synthetic-origin-hash",0L,id(4),row.id,day)
            else arrayOf(row.id,row.uuid,day,row.targetValue,0L,id(4804),null,null,null,null,null,null,null,NEXT_OPERATION)
        seedAccepted(1);val single=storage.snapshotClosedDatabase()
        for(bind in listOf(false,true)) {
            freshGateForColdOpen();restoreForJourney(single);openCount(jointAuthority=true)
            val originalFailure=rejectedUnchanged {
                domainGate.transaction(db,G0DomainSqlGate.Phase.BUSINESS) {
                    db.openHelper.writableDatabase.execSQL(statement(bind),args(habit,bind))
                }
            }
            assertTrue(originalFailure is G0DomainSqlGate.Denied)
        }
        freshGateForColdOpen();restoreForJourney(empty)
        val seed=seedHouseholdAccepted(0);val image=storage.snapshotClosedDatabase()
        for(phase in listOf("no_owner","read","final"))for(bind in listOf(false,true)) {
            freshGateForColdOpen();restoreForJourney(image);val (http,_)=channel {successReply(it)};openHousehold(http)
            val failure=rejectedUnchanged {
                suspend fun write() {db.openHelper.writableDatabase.execSQL(statement(bind),args(seed.habits[0],bind))}
                when(phase) {
                    "no_owner"->write()
                    "read"->domainGate.transaction(db,G0DomainSqlGate.Phase.READ) {write()}
                    else->domainGate.transaction(db,G0DomainSqlGate.Phase.BUSINESS) {domainGate.finalRead {write()}}
                }
            }
            assertTrue("$phase bind=$bind must reject the day capability: $failure",failure is G0DomainSqlGate.Denied)
            assertEquals(0,pageRowCount("count_days"));assertEquals(0,pageRowCount("completions"))
        }
    }
}
