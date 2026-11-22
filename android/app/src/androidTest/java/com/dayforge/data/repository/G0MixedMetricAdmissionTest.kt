package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
open class G0MixedMetricAdmissionTest : G0RecurringReadFixture() {
    internal open val retainRecurringResults=false
    private fun writer(enabled:Boolean=true)=G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid,
        queueClock={millis},admitRecurringReads=enabled,retainRecurringResults=enabled && retainRecurringResults)
    private suspend fun open(enabled:Boolean=true)=writer(enabled).also { it.initialize(access(),trustedDomainSchema(true)) }
    @Test fun oldDefaultStillRejectsForeignFacts()=runBlocking<Unit> {
        seed();val before=state()
        assertEquals("G0_SHORT_UNSUPPORTED_SCOPE",rejected { open(false) }.message);assertEquals(before,state())
    }
    @Test fun realMetricWriteSendAckRevalidatesRecurringEvidenceAndPreservesForeignRows()=runBlocking<Unit> {
        val ids=seed();val before=state();val (http,_)=channel { successReply(it) };val store=open();val now=ZonedDateTime.now()
        val reader=G0RecurringReadOwner(db,tokens,sessions,authority,domainGate,sharedScope=true,reuseEvidence=true)
        val initial=reader.read(ids,now);assertTrue(reader.proofQueries>0)
        assertTrue(reader.publish(initial) { assertTrue(it.valid()) })
        store.edit("Mixed metric","",id(7400),millis)
        assertFalse(reader.publish(initial) { fail("Stale publication accepted") })
        suspend fun readCurrent() {
            val batch=reader.read(ids,now);assertEquals(initial.content,batch.content)
            assertTrue(reader.publish(batch) { assertTrue(it.valid()) })
        }
        readCurrent()
        val delivery=requireNotNull(store.send(http,access(),id(7400)));readCurrent()
        assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(delivery));readCurrent()
        assertEquals(NextOperationAcceptance.REPLAYED,store.acknowledge(delivery))
        assertEquals(1,reader.fullReads);assertEquals(3,reader.reuseHits)
        val after=state()
        for(table in listOf("habits","completions","count_days","timelogs","metric_logs","habit_metric_links")) assertEquals(table,before[table],after[table])
        for(table in listOf("sync_outbox","next_request_origins","next_structural_dependencies"))
            assertTrue(table,requireNotNull(after[table]).containsAll(requireNotNull(before[table])))
    }
    @Test fun changedQuantityCannotBeAdmittedByMetricProof()=runBlocking<Unit> {
        seed();db.openHelper.writableDatabase.execSQL("UPDATE completions SET value=99 WHERE id=(SELECT min(id) FROM completions)")
        val before=state();rejected { open() };assertEquals(before,state())
    }
    @Test fun foreignOwnerCannotBeHiddenBehindUnchangedFactResult()=runBlocking<Unit> {
        seed();db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET accountId=? WHERE requestId=(SELECT requestId FROM next_request_origins WHERE intentJson LIKE '%activity_event%' LIMIT 1)",arrayOf(id(7500)))
        val before=state();rejected { open() };assertEquals(before,state())
    }
    @Test fun missingForeignQueueCannotBeIgnored()=runBlocking<Unit> {
        seed();db.openHelper.writableDatabase.execSQL("DELETE FROM sync_outbox WHERE id=(SELECT min(id) FROM sync_outbox)")
        val before=state();rejected { open() };assertEquals(before,state())
    }
    @Test fun foreignRequestIdIsReservedEvenForMetricNoOp()=runBlocking<Unit> {
        seed();val foreign=db.syncOutboxDao().getAll().first().operationId;val before=state();val store=open()
        assertEquals("G0_SHORT_REQUEST_REUSED",rejected { store.edit(metric.name,metric.description,foreign,millis) }.message)
        assertEquals(before,state())
    }
    @Test fun externalForeignMutationInvalidatesWriterAndReadCertificate()=runBlocking<Unit> {
        val ids=seed();val store=open();val reader=G0RecurringReadOwner(db,tokens,sessions,authority,domainGate,sharedScope=true,reuseEvidence=true)
        val now=ZonedDateTime.now();reader.read(ids,now)
        android.database.sqlite.SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE completions SET value=99 WHERE id=(SELECT min(id) FROM completions)")
        }
        val before=state();rejected { store.edit("Must fail","",id(7501),millis) };rejected { reader.read(ids,now) };assertEquals(before,state())
    }

    @Test fun settingChangeInvalidatesPriorPublicationAndForcesCompleteProof()=runBlocking<Unit> {
        val ids=seed();open();val reader=G0RecurringReadOwner(db,tokens,sessions,authority,domainGate,sharedScope=true,reuseEvidence=true)
        val now=ZonedDateTime.now();val initial=reader.read(ids,now);val before=state()
        channel { successReply(it) } // Real settings persistence reserves a new authority generation.
        assertFalse(reader.publish(initial) { fail("Prior generation published") })
        val next=reader.read(ids,now);assertEquals(initial.content,next.content)
        assertEquals(2,reader.fullReads);assertEquals(0,reader.reuseHits)
        assertTrue(reader.publish(next) { assertTrue(it.valid()) });assertEquals(before,state())
    }
    @Test fun metricValuesCannotBorrowRecurringReadAdmission()=runBlocking<Unit> {
        seed();observation();val before=state()
        assertEquals("G0_MIXED_UNSUPPORTED_FACT_DOMAIN",rejected { open() }.message);assertEquals(before,state())
    }
    @Test fun unknownAckRequiresColdReproofAndPreservesForeignSources()=runBlocking<Unit> {
        val ids=seed();val (http,server)=channel { successReply(it) };val store=open()
        store.edit("Unknown mixed ACK","",id(7502),millis)
        val delivery=requireNotNull(store.send(http,access(),id(7502)))
        store.beforeFinalForTest={ endFaults.failAfterNextEnd.set(true) }
        assertEquals("G0_COMMIT_RESULT_LOST",rejected { store.acknowledge(delivery) }.message)
        assertEquals("G0_SHORT_RECOVERY_REQUIRED",rejected { store.acknowledge(delivery) }.message)
        val durable=state();val requests=server.requests.size
        freshGateForColdOpen(recurringReads=true);storage.reopen();val recovered=open()
        assertEquals(NextOperationAcceptance.REPLAYED,recovered.acknowledge(delivery))
        val batch=G0RecurringReadOwner(db,tokens,sessions,authority,domainGate,sharedScope=true).read(ids,ZonedDateTime.now())
        assertEquals(5L,batch.content.counts.getValue(ids[0]).todayQuantity)
        assertEquals(requests,server.requests.size);assertEquals(durable,state())
    }
}
