package com.dayforge.data.repository

import androidx.room.withTransaction
import java.time.ZonedDateTime
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Original rejection matrix inherited; candidate changes only cold result retention. */
class G0ColdRecurringProofTest:G0MixedMetricAdmissionTest() {
    override val retainRecurringResults=true
    private fun store()=G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid,
        queueClock={millis},admitRecurringReads=true,retainRecurringResults=true)
    private fun reader(store:G0ShortMetricStore)=G0RecurringReadOwner(db,tokens,sessions,authority,domainGate,
        sharedScope=true,reuseEvidence=true,coldSeed={store.recurringReadSeed()})
    @Test fun coldResultMatchesOriginalReadersAndFreshAuthRunsOnFirstReuse()=runBlocking<Unit> {
        val ids=seed();val now=ZonedDateTime.now();val before=state()
        val original=db.withTransaction {
            CountHistoryReader(db,tokens,sessions).readInTransaction(requireNotNull(db.habitDao().getHabitById(ids[0])),now.toLocalDate()) to
                CheckHistoryReader(db,tokens,sessions).readInTransaction(requireNotNull(db.habitDao().getHabitById(ids[1])),now.toLocalDate())
        }
        val store=store();store.initialize(access(),trustedDomainSchema(true))
        assertNotNull(store.coldReadCaptureFailure,store.recurringReadSeed())
        val reader=reader(store);val start=decryptCount.get();val result=reader.read(ids,now)
        assertEquals(original.first,result.content.counts.getValue(ids[0]));assertEquals(original.second,result.content.checks.getValue(ids[1]))
        assertEquals(0,reader.fullReads);assertEquals(1,reader.coldHits);assertEquals(1,reader.reuseHits)
        assertTrue(reader.publish(result) { assertTrue(it.valid()) });assertEquals(4,decryptCount.get()-start)
        assertEquals(before,state())
    }
    @Test fun firstReadAfterRealWriteAndAckRevalidatesCompleteColdProof()=runBlocking<Unit> {
        val ids=seed();val (http,_)=channel { successReply(it) };val store=store();store.initialize(access(),trustedDomainSchema(true))
        assertNotNull(store.coldReadCaptureFailure,store.recurringReadSeed())
        store.edit("Before first read","",id(7900),millis)
        val delivery=requireNotNull(store.send(http,access(),id(7900)))
        assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(delivery))
        val reader=reader(store);val result=reader.read(ids,ZonedDateTime.now())
        assertEquals(5L,result.content.counts.getValue(ids[0]).todayQuantity)
        assertEquals(0,reader.fullReads);assertEquals(1,reader.coldHits)
        assertTrue(reader.publish(result) { assertTrue(it.valid()) })
    }
    @Test fun exhaustedRecordingKeepsOriginalValidationAndFallsBack()=runBlocking<Unit> {
        val ids=seed();val store=store();store.coldCaptureForTest={G0ObservedReadProof.Capture(maxSteps=0)}
        store.initialize(access(),trustedDomainSchema(true));assertNull(store.recurringReadSeed())
        val reader=reader(store);val batch=reader.read(ids,ZonedDateTime.now())
        assertEquals(5L,batch.content.counts.getValue(ids[0]).todayQuantity);assertEquals(1,reader.fullReads);assertEquals(0,reader.coldHits)
    }
    @Test fun oldDayOrDifferentIdOrderCannotBorrowColdResult()=runBlocking<Unit> {
        val ids=seed();val store=store();store.initialize(access(),trustedDomainSchema(true))
        val reader=reader(store);val tomorrow=ZonedDateTime.now().plusDays(1)
        reader.nowForTest={tomorrow};val batch=reader.read(ids,tomorrow)
        assertEquals(tomorrow.toLocalDate(),batch.content.counts.getValue(ids[0]).today)
        assertEquals(1,reader.fullReads);assertEquals(0,reader.coldHits)
        val reordered=reader(store);reordered.read(ids.reversed(),ZonedDateTime.now())
        assertEquals(1,reordered.fullReads);assertEquals(0,reordered.coldHits)
    }
    @Test fun firstReuseStillRejectsFinalCredentialFailure()=runBlocking<Unit> {
        val ids=seed();val store=store();store.initialize(access(),trustedDomainSchema(true));val reader=reader(store)
        val before=state();reader.beforeReuseFinalForTest={failDecrypt=true}
        try { rejected { reader.read(ids,ZonedDateTime.now()) } } finally { failDecrypt=false }
        assertEquals(0,reader.coldHits);assertEquals(before,state())
    }
    @Test fun captureCannotStartInsideAlreadyPopulatedEvidenceSession()=runBlocking<Unit> {
        seed();val before=state();val request=db.syncOutboxDao().getAll().first().operationId
        db.withTransaction { NextEvidenceReadSession.read(db) {
            NextRequestSql.rowHash(db,"next_request_origins","kind=? AND requestId=?",arrayOf(NEXT_OPERATION,request))
            assertEquals("G0_COLD_READ_REQUIRES_EMPTY_SESSION",rejected {
                G0ColdRecurringProof.capture(db,tokens,sessions,G0ObservedReadProof.Capture())
            }.message)
        } }
        assertEquals(before,state())
    }
    @Test fun firstBorrowRejectsExternalMutationWithoutTrustingZeroLocalRevision()=runBlocking<Unit> {
        val ids=seed();val store=store();store.initialize(access(),trustedDomainSchema(true));val retained=store.recurringReadSeed()
        assertNotNull(retained)
        val reader=G0RecurringReadOwner(db,tokens,sessions,authority,domainGate,sharedScope=true,reuseEvidence=true,coldSeed={retained})
        android.database.sqlite.SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE completions SET value=99 WHERE id=(SELECT min(id) FROM completions)")
        }
        val before=state();assertEquals("G0_DOMAIN_EXTERNAL_CHANGE",rejected { reader.read(ids,ZonedDateTime.now()) }.message)
        assertEquals(0,reader.coldHits);assertEquals(before,state())
    }
    @Test fun authorityReservationDuringFirstBorrowRejectsBeforePersistence()=runBlocking<Unit> {
        val ids=seed();val store=store();store.initialize(access(),trustedDomainSchema(true));val reader=reader(store)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val reserved=CompletableDeferred<Unit>()
        reader.beforeReuseFinalForTest={entered.complete(Unit);release.await()};authority.onTransitionForTest={reserved.complete(Unit)}
        val reading=async { runCatching { reader.read(ids,ZonedDateTime.now()) } };withTimeout(3_000) { entered.await() }
        val changing=async { tokens.saveDeviceRegistration(id(4),setOf("sync.read"),true,2) }
        try { withTimeout(3_000) { reserved.await() };assertFalse(changing.isCompleted) } finally { release.complete(Unit) }
        assertTrue(reading.await().exceptionOrNull() is G0AuthorityOwner.Denied)
        withTimeout(3_000) { changing.await() };assertEquals(0,reader.coldHits)
    }
}
