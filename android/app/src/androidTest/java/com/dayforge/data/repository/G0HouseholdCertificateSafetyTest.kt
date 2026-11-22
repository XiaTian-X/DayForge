package com.dayforge.data.repository

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fixed Q/H schema with real SQLite/Keystore and independent original consumers. */
@RunWith(AndroidJUnit4::class)
class G0HouseholdCertificateSafetyTest:G0HouseholdCertificateFixture() {
    private suspend fun seed():com.dayforge.data.api.NextSyncHttp {
        seedHouseholdAccepted(8);val (http,_)=channel {successReply(it)}
        prepareCertificateSchema();return http
    }
    private fun calls(trace:G0PhaseTrace,phase:String)=synchronized(trace.spans) {trace.spans.count {it.phase==phase}}
    private suspend fun assertOriginal(store:G0HouseholdCountStore,http:com.dayforge.data.api.NextSyncHttp) {
        val saved=store.increase(currentHousehold.first().uuid)
        val sent=requireNotNull(store.send(http,saved.requestId))
        assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(sent))
        val view=householdView(store,mapOf(saved.habitUuid to saved.factId));val before=pageNativeState()
        assertEquals(NextOperationAcceptance.REPLAYED,store.acknowledge(sent))
        assertEquals(0,pageRowCount("sync_outbox"));assertEquals(9,pageRowCount("completions"))
        reopenReference();sameHouseholdAsOriginal(view)
        assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(sent));assertEquals(before,pageNativeState())
    }
    @Test fun certifiedColdHitRestoresWholeStateAndContinuesOriginalCountSendAck()=runBlocking<Unit> {
        val http=seed();val primary=primaryState();signInitial(http)
        val before=pageNativeState();val trace=G0PhaseTrace(driverTiming=false)
        val opened=trace.run {openCertificate(http)}
        assertTrue(opened.store.certificateHit);assertEquals(0,calls(trace,"count_complete_history"))
        assertTrue(calls(trace,"decrypt_access")>=2);assertEquals(primary,primaryState());assertEquals(before,pageNativeState())
        val view=householdView(opened.store);assertEquals(8,view.cards.values.sumOf {it.history.completions.size})
        assertTrue(view.valid());assertOriginal(opened.store,http)
    }
    @Test fun damagedOrIncompleteOptionalChunksMissWithoutRepairingOrMutatingFacts()=runBlocking<Unit> {
        val http=seed();signInitial(http);reopenReference();val clean=storage.snapshotClosedDatabase()
        for(statement in listOf(
            "DELETE FROM g0_count_image_chunks WHERE chunkIndex=0",
            "UPDATE g0_count_image_chunks SET content=zeroblob(length(content)) WHERE chunkIndex=0",
            "UPDATE g0_count_certificate SET imageBytes=imageBytes+1 WHERE slot=1",
            "UPDATE g0_count_certificate SET mac=zeroblob(32) WHERE slot=1",
            "INSERT INTO g0_count_image_chunks (chunkIndex,content) VALUES (999,zeroblob(1))")) {
            freshGateForColdOpen();restoreForJourney(clean);mutateHouseholdFile(statement)
            val before=pageNativeState();val trace=G0PhaseTrace(driverTiming=false)
            val opened=trace.run {openCertificate(http)}
            assertFalse(opened.store.certificateHit);assertEquals(2,calls(trace,"count_complete_history"))
            assertEquals(8,householdView(opened.store).cards.values.sumOf {it.history.completions.size})
            assertEquals(before,pageNativeState())
        }
    }
    @Test fun changedProtectedSourceCannotBorrowAnOldValidCertificate()=runBlocking<Unit> {
        val http=seed();signInitial(http);reopenReference();val clean=storage.snapshotClosedDatabase()
        val errors=mutableListOf<Pair<Class<*>,String?>>()
        for(lookup in listOf(false,true)) {
            freshGateForColdOpen();restoreForJourney(clean)
            mutateHouseholdFile("UPDATE next_request_origins SET sourceHash=? WHERE requestId=(SELECT MIN(requestId) FROM next_request_origins)",arrayOf("0".repeat(64)))
            val before=pageNativeState()
            val failure=runCatching {openCertificate(http,lookup=lookup)}.exceptionOrNull()
            assertNotNull(failure);errors+=requireNotNull(failure).javaClass to failure.message
            assertEquals(before,pageNativeState())
        }
        assertEquals(errors.first(),errors.last())
    }
    @Test fun writesAfterMatchOrRepairAfterCaptureCannotQualifyAnotherSnapshot()=runBlocking<Unit> {
        val http=seed();signInitial(http);reopenReference();val clean=storage.snapshotClosedDatabase()
        for(repair in listOf(false,true)) {
            freshGateForColdOpen();restoreForJourney(clean)
            if(repair)mutateHouseholdFile("UPDATE next_acceptances SET resultHash=? WHERE requestId=(SELECT MIN(requestId) FROM next_acceptances)",arrayOf("0".repeat(64)))
            val before=pageNativeState();var attempted=false
            val failure=runCatching {openCertificate(http,configure={store->
                val write:suspend ()->Unit={
                    attempted=true
                    assertTrue(runCatching {db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultHash=?",arrayOf("a".repeat(64)))}.isFailure)
                }
                if(repair)store.afterColdFreezeForTest=write else store.beforeColdFinalForTest=write
            })}.exceptionOrNull()
            assertTrue(attempted);assertNotNull(failure);assertEquals(before,pageNativeState())
        }
    }
    @Test fun unknownMaintenanceEndRetiresOldOwnerAndNewOwnerProvesActualStoredPacket()=runBlocking<Unit> {
        val http=seed();val store=openCertificate(http,lookup=false).store;val primary=primaryState()
        val trace=G0PhaseTrace(driverTiming=false);endFaults.failAfterNextEndIn(trace)
        assertEquals("G0_COMMIT_RESULT_LOST",runCatching {trace.run {store.persistColdCertificate()}}.exceptionOrNull()?.message)
        assertTrue(runCatching {householdView(store)}.isFailure);assertEquals(primary,primaryState())
        assertEquals(1,pageRowCount(G0CountCertificateSql.table))
        val restored=openCertificate(http).store
        assertTrue(restored.certificateHit);assertEquals(primary,primaryState())
        assertOriginal(restored,http)
    }
    @Test fun finalFreshAuthFailureRollsBackOnlyMaintenanceWithoutClaimingReady()=runBlocking<Unit> {
        val http=seed();val store=openCertificate(http,lookup=false).store;val before=pageNativeState()
        store.beforeCertificateFinalForTest={failDecrypt=true}
        try {assertTrue(runCatching {store.persistColdCertificate()}.isFailure)}
        finally {failDecrypt=false;store.beforeCertificateFinalForTest={}}
        assertEquals(before,pageNativeState());assertTrue(runCatching {householdView(store)}.isFailure)
        val recovered=openCertificate(http).store;assertFalse(recovered.certificateHit)
        assertEquals(before,pageNativeState());assertOriginal(recovered,http)
    }
    @Test fun publicationDuringEncodingDiscardsCandidateAndNeverInvalidatesThatView()=runBlocking<Unit> {
        val http=seed();val store=openCertificate(http,lookup=false).store;val before=pageNativeState()
        var view:G0HouseholdCountStore.View?=null
        store.afterCertificatePreparedForTest={view=householdView(store)}
        assertFalse(store.persistColdCertificate());assertTrue(requireNotNull(view).valid())
        assertEquals(before,pageNativeState());assertFalse(store.persistColdCertificate())
    }
    @Test fun knownBusinessCommitDuringEncodingRetainsSavedFactAndDiscardsOldCandidate()=runBlocking<Unit> {
        val http=seed();val store=openCertificate(http,lookup=false).store
        var saved:G0HouseholdCountStore.Saved?=null
        store.afterCertificatePreparedForTest={saved=store.increase(currentHousehold.first().uuid)}
        assertFalse(store.persistColdCertificate());assertEquals(0,pageRowCount(G0CountCertificateSql.table))
        val value=requireNotNull(saved);assertEquals(9,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
        val sent=requireNotNull(store.send(http,value.requestId));assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(sent))
        val view=householdView(store);reopenReference();sameHouseholdAsOriginal(view)
    }
    @Test fun maintenanceCannotWritePrimaryRowsAndOrdinaryBusinessCannotWriteCertificateRows()=runBlocking<Unit> {
        val http=seed();val original=storage.snapshotClosedDatabase()
        for(maintenance in listOf(false,true)) {
            freshGateForColdOpen();restoreForJourney(original);openCertificate(http,lookup=false)
            val before=pageNativeState()
            val failure=runCatching {domainGate.transaction(db,if(maintenance)G0DomainSqlGate.Phase.MAINTENANCE else G0DomainSqlGate.Phase.BUSINESS) {
                if(maintenance)db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                else db.openHelper.writableDatabase.execSQL(G0CountCertificateSql.deleteHeader)
            }}.exceptionOrNull()
            assertTrue(failure is G0DomainSqlGate.Denied);assertEquals(before,pageNativeState())
        }
    }
    @Test fun publishedCallbackFailureDoesNotReopenFirstSignWindow()=runBlocking<Unit> {
        val http=seed();val store=openCertificate(http,lookup=false).store;val before=pageNativeState()
        assertTrue(runCatching {store.readAndPublish {error("G0_TEST_PUBLICATION_FAILURE")}}.isFailure)
        assertFalse(store.persistColdCertificate());assertEquals(before,pageNativeState())
        assertTrue(householdView(store).valid())
    }
    @Test fun authorityAbaAfterEncodingCannotPublishAStaleCertificate()=runBlocking<Unit> {
        val http=seed();val store=openCertificate(http,lookup=false).store;val before=pageNativeState()
        val original=dataStore.data.first()
        store.afterCertificatePreparedForTest={
            dataStore.edit {it[stringPreferencesKey("sync_device_capability_revision")]="2"}
            dataStore.updateData {original}
        }
        val failure=runCatching {store.persistColdCertificate()}.exceptionOrNull()
        assertTrue(failure is G0AuthorityOwner.Denied);assertEquals(original,dataStore.data.first())
        assertEquals(before,pageNativeState());assertTrue(householdView(store).valid())
    }
    @Test fun cancellingMaintenanceRollsBackItsWholePacketAndRetiresItsOwner()=runBlocking<Unit> {
        val http=seed();val store=openCertificate(http,lookup=false).store;val before=pageNativeState()
        val entered=CompletableDeferred<Unit>()
        store.beforeCertificateFinalForTest={entered.complete(Unit);awaitCancellation()}
        val work=launch(Dispatchers.IO) {store.persistColdCertificate()}
        withTimeout(20_000) {entered.await()};work.cancelAndJoin()
        assertEquals(before,pageNativeState());assertTrue(runCatching {householdView(store)}.isFailure)
        val next=openCertificate(http).store;assertFalse(next.certificateHit);assertEquals(before,pageNativeState())
        assertTrue(householdView(next).valid())
    }
    @Test fun missingCertificateSchemaRejectsBeforeAnyAutomaticRepair()=runBlocking<Unit> {
        val http=seed();signInitial(http);reopenReference()
        mutateHouseholdFile("DROP TABLE g0_count_certificate")
        val before=pageNativeState()
        assertTrue(runCatching {openCertificate(http)}.isFailure)
        assertEquals(before,pageNativeState());assertFalse(pageNativeState().containsKey(G0CountCertificateSql.table))
    }
    @Test fun captureCapacityMissUsesNewProtectedFullValidatorAndCannotIssueAPacket()=runBlocking<Unit> {
        val http=seed();signInitial(http);val before=pageNativeState()
        val trace=G0PhaseTrace(driverTiming=false)
        val next=trace.run {openCertificate(http,maxInputBytes=1)}
        assertTrue(next.budgetFallback);assertFalse(next.store.certificateHit)
        assertFalse(next.store.sharedColdInputUsed);assertEquals(2,calls(trace,"count_complete_history"))
        assertFalse(next.store.persistColdCertificate());assertEquals(before,pageNativeState())
        assertOriginal(next.store,http)
    }
    @Test fun optionalPacketReadsCannotEscapeIntoAWarmPermission()=runBlocking<Unit> {
        val http=seed();signInitial(http);reopenReference();val clean=storage.snapshotClosedDatabase()
        for(phase in G0DomainSqlGate.Phase.entries) {
            freshGateForColdOpen();restoreForJourney(clean);openCertificate(http)
            val before=pageNativeState()
            val failure=runCatching {domainGate.transaction(db,phase) {
                db.openHelper.writableDatabase.query(G0CountCertificateSql.select).use {it.count}
            }}.exceptionOrNull()
            assertTrue(failure is G0DomainSqlGate.Denied);assertEquals(before,pageNativeState())
        }
    }
    @Test fun missingLocalKeyLosesOnlyAccelerationAndDoesNotDeletePersistedBlocks()=runBlocking<Unit> {
        val http=seed();signInitial(http);val before=pageNativeState()
        removeCountCertificateKey()
        val trace=G0PhaseTrace(driverTiming=false);val next=trace.run {openCertificate(http)}
        assertFalse(next.store.certificateHit);assertEquals(2,calls(trace,"count_complete_history"))
        assertEquals(before,pageNativeState());assertOriginal(next.store,http)
    }
    @Test fun additionalPermittedMaintenanceWriteFailsActualDeltaAuditAndRollsBack()=runBlocking<Unit> {
        val http=seed();val store=openCertificate(http,lookup=false).store;val before=pageNativeState()
        store.beforeCertificateFinalForTest={
            db.openHelper.writableDatabase.execSQL(G0CountCertificateSql.insertChunk,arrayOf<Any>(999L,byteArrayOf(1)))
        }
        val failure=runCatching {store.persistColdCertificate()}.exceptionOrNull()
        assertEquals("G0_CERTIFICATE_EXTRA_EFFECT",failure?.message)
        assertEquals(before,pageNativeState());assertTrue(runCatching {householdView(store)}.isFailure)
        val next=openCertificate(http).store;assertFalse(next.certificateHit);assertTrue(householdView(next).valid())
    }
    @Test fun unsentFirstDayRestoresPendingSourceAndContinuesItsOriginalRequest()=runBlocking<Unit> {
        val http=seed();reopenReference()
        val fact=originalHouseholdFact(currentHousehold.first())
        val request=db.syncOutboxDao().getEntityIntents("completion",fact.uuid).single().operationId
        signInitial(http);val before=pageNativeState()
        val store=openCertificate(http).store
        assertTrue(store.certificateHit);assertEquals(listOf(request),store.pendingRequestIds())
        assertEquals(before,pageNativeState())
        val delivery=requireNotNull(store.send(http,request))
        assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(delivery))
        val view=householdView(store,mapOf(currentHousehold.first().uuid to fact.id))
        val committed=pageNativeState();assertEquals(0,pageRowCount("sync_outbox"))
        assertEquals(9,pageRowCount("completions"));reopenReference();sameHouseholdAsOriginal(view)
        assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(delivery))
        assertEquals(committed,pageNativeState())
    }
    @Test fun matchingCertificateStillRequiresFreshFinalColdAuthentication()=runBlocking<Unit> {
        val http=seed();signInitial(http);val before=pageNativeState()
        lateinit var attempted:G0HouseholdCountStore
        try {
            assertTrue(runCatching {openCertificate(http,configure={store->
                attempted=store;store.beforeColdFinalForTest={assertTrue(store.certificateHit);failDecrypt=true}
            })}.isFailure)
        } finally {failDecrypt=false}
        assertTrue(attempted.certificateHit);assertTrue(runCatching {householdView(attempted)}.isFailure)
        assertEquals(before,pageNativeState())
        assertTrue(openCertificate(http).store.certificateHit)
    }
    @Test fun matchingCertificateWithLostColdEndCannotQualifyThatOwner()=runBlocking<Unit> {
        val http=seed();signInitial(http);val before=pageNativeState()
        lateinit var attempted:G0HouseholdCountStore
        val trace=G0PhaseTrace(driverTiming=false);endFaults.failAfterNextEndIn(trace)
        val failure=runCatching {trace.run {openCertificate(http,configure={attempted=it})}}.exceptionOrNull()
        assertEquals("G0_COMMIT_RESULT_LOST",failure?.message)
        assertTrue(attempted.certificateHit);assertTrue(runCatching {householdView(attempted)}.isFailure)
        assertEquals(before,pageNativeState())
        // A failed cold helper intentionally refuses getters. Retain this old
        // premature replacement as a counterexample; never treat its error as
        // inTransaction=false or invent a known END in order to reuse it.
        val retired=domainGate;val retiredDatabase=db
        assertEquals("G0_COMMIT_RESULT_LOST",runCatching {freshGateForColdOpen()}.exceptionOrNull()?.message)
        assertSame(retired,domainGate)
        // The failed operation has returned and drained. Its existing close
        // barrier must succeed before a new owner opens the unchanged file.
        db.close();assertFalse(db.isOpen);assertEquals(before,pageNativeState())
        val recovered=openCertificate(http).store
        assertNotSame(retiredDatabase,db);assertNotSame(retired,domainGate)
        assertTrue(recovered.certificateHit);assertTrue(householdView(recovered).valid())
        assertTrue(runCatching {householdView(attempted)}.isFailure)
    }

}
