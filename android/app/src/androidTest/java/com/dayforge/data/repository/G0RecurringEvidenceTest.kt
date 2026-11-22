package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.ZonedDateTime
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0RecurringEvidenceTest : G0RecurringReadOwnerTest() {
    override fun owner()=G0RecurringReadOwner(db,tokens,sessions,authority,domainGate,sharedScope=true,reuseEvidence=true)
    @Test fun unchangedProofReusesFullResultButAuthenticatesEntryAndFinal()=runBlocking<Unit> {
        val ids=seed();val before=state();sealDomain();val reader=owner();val now=ZonedDateTime.now()
        val first=reader.read(ids,now)
        assertTrue(reader.lastCaptureFailure,reader.proofQueries>0)
        val start=decryptCount.get();val second=reader.read(ids,now)
        assertEquals(first.content,second.content);assertEquals(1,reader.fullReads);assertEquals(1,reader.reuseHits)
        assertTrue(reader.publish(second) { assertTrue(it.valid()) });assertEquals(4,decryptCount.get()-start)
        reader.beforeReuseFinalForTest={ failDecrypt=true }
        try { rejected { reader.read(ids,now) } } finally { failDecrypt=false }
        assertEquals(before,state())
    }
    @Test fun unrelatedControlledMetricMutationDoesNotRevalidateUnchangedFacts()=runBlocking<Unit> {
        val ids=seed();sealDomain();val reader=owner();val now=ZonedDateTime.now();val first=reader.read(ids,now)
        assertTrue(reader.lastCaptureFailure,reader.proofQueries>0)
        // Boundary mutation only, not a business ACK: independently assert the whole actual write set.
        domainGate.transaction(db,G0DomainSqlGate.Phase.BUSINESS) {
            domainGate.sqlClient().execSQL("UPDATE metrics SET name=?,description=?,updatedAt=? WHERE id=?",arrayOf<Any>("Unrelated metric","",millis,metric.id))
            domainGate.certifyChanges { changes -> assertEquals(listOf("metrics","sync_outbox"),changes.map { it.table });assertEquals(listOf("UPDATE","INSERT"),changes.map { it.operation }) }
        }
        assertFalse(reader.publish(first) { fail("Old revision published") })
        val second=reader.read(ids,now);assertEquals(first.content,second.content)
        assertEquals(1,reader.fullReads);assertEquals(1,reader.reuseHits)
        assertTrue(reader.publish(second) { assertTrue(it.valid()) })
    }
    @Test fun boundedRecordingFallsBackWithoutTruncatingBusinessValidation()=runBlocking<Unit> {
        val ids=seed();sealDomain();val reader=owner();reader.captureForTest={ G0ObservedReadProof.Capture(maxSteps=0) }
        val now=ZonedDateTime.now();val first=reader.read(ids,now);val second=reader.read(ids,now)
        assertEquals(first.content,second.content);assertEquals(2,reader.fullReads);assertEquals(0,reader.reuseHits);assertEquals(0,reader.proofQueries)
        assertEquals(5L,second.content.counts.getValue(ids[0]).todayQuantity)
    }

    @Test fun warmProofRejectsExternalMutationEvenWhenLocalTableVersionsDidNotChange()=runBlocking<Unit> {
        val ids=seed();sealDomain();val reader=owner();val now=ZonedDateTime.now();reader.read(ids,now)
        android.database.sqlite.SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE completions SET value=99 WHERE id=(SELECT min(id) FROM completions)")
        }
        val before=state()
        assertEquals("G0_DOMAIN_EXTERNAL_CHANGE",rejected { reader.read(ids,now) }.message)
        assertEquals(0,reader.reuseHits);assertEquals(before,state())
    }
    @Test fun pendingAuthorityTransitionRejectsWarmResultBeforePersistence()=runBlocking<Unit> {
        val ids=seed();sealDomain();val reader=owner();val now=ZonedDateTime.now();reader.read(ids,now)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val reserved=CompletableDeferred<Unit>()
        reader.beforeReuseFinalForTest={ entered.complete(Unit);release.await() };authority.onTransitionForTest={ reserved.complete(Unit) }
        val reading=async { runCatching { reader.read(ids,now) } };withTimeout(3_000) { entered.await() }
        val changing=async { tokens.saveDeviceRegistration(id(4),setOf("sync.read"),true,2) }
        try { withTimeout(3_000) { reserved.await() };assertFalse(changing.isCompleted) } finally { release.complete(Unit) }
        assertTrue(reading.await().exceptionOrNull() is G0AuthorityOwner.Denied)
        withTimeout(3_000) { changing.await() };assertEquals(0,reader.reuseHits)
    }
    @Test fun unknownCommitCannotReuseAnUnadvancedTableStamp()=runBlocking<Unit> {
        val ids=seed();sealDomain();val reader=owner();val now=ZonedDateTime.now();reader.read(ids,now)
        assertEquals("G0_COMMIT_RESULT_LOST",rejected {
            domainGate.transaction(db,G0DomainSqlGate.Phase.BUSINESS) {
                domainGate.sqlClient().execSQL("UPDATE metrics SET name=?,description=?,updatedAt=? WHERE id=?",arrayOf<Any>("Unknown result","",millis,metric.id))
                domainGate.certifyChanges { assertEquals(2,it.size);assertEquals(setOf("metrics","sync_outbox"),it.map { row -> row.table }.toSet()) }
                domainGate.finalRead { endFaults.failAfterNextEnd.set(true) }
            }
        }.message)
        val before=state();assertTrue(rejected { reader.read(ids,now) } is G0DomainSqlGate.Denied)
        assertEquals(0,reader.reuseHits);assertEquals(before,state())
    }
}
