package com.dayforge.data.repository

import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** No speculative snapshot is a read result or authority. Every outcome still validates SQL. */
@RunWith(AndroidJUnit4::class)
class G0SpeculativePreparationTest : G0SealedDomainFixture() {
    override fun domainOpeningCallback() = G0SchemaOpeningCallback(true)
    private suspend fun owner(): G0ShortMetricStore {
        register()
        return G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid).also {
            it.initialize(access(),trustedDomainSchema(true))
        }
    }
    private fun externalChange() {
        SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path,null,SQLiteDatabase.OPEN_READWRITE).use { sql ->
            sql.beginTransaction()
            try {
                sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                sql.execSQL("UPDATE metrics SET description='external' WHERE id=?",arrayOf(metric.id))
                sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
                sql.setTransactionSuccessful()
            } finally { sql.endTransaction() }
        }
    }
    private fun original() { storage.isolatedBuilder=null;storage.reopen() }
    @Test fun preparationUsesNoSqlButCommitStillHasTwoFreshAuthentications() = runBlocking<Unit> {
        val store=owner();val trace=G0PhaseTrace(driverTiming=false)
        store.afterPrepareForTest={ assertEquals(0,trace.count("tx_begin_outer")) }
        val row=trace.run { requireNotNull(store.edit("Prepared","",id(3101),millis)) }
        assertEquals(1,trace.count("tx_begin_outer"));assertEquals(2,trace.count("decrypt_access"))
        original();assertEquals(row,db.syncOutboxDao().getById(row.id));assertEquals(1,count("next_request_origins"))
    }
    @Test fun externalCommitDuringPreparationRejectsBeforeBusinessWrites() = runBlocking<Unit> {
        val store=owner();store.afterPrepareForTest={ externalChange() }
        assertEquals("G0_DOMAIN_EXTERNAL_CHANGE",rejected { store.edit("Must not commit","",id(3102),millis) }.message)
        original();assertEquals(metric.name,db.metricDao().getMetricById(metric.id)!!.name)
        assertEquals("external",db.metricDao().getMetricById(metric.id)!!.description)
        assertEquals(0,count("next_request_origins"));assertEquals(0,count("sync_outbox"))
    }
    @Test fun noOpStillDetectsExternalChangeInsteadOfReturningSuccessFromMemory() = runBlocking<Unit> {
        val store=owner();externalChange()
        assertEquals("G0_DOMAIN_EXTERNAL_CHANGE",rejected { store.edit(metric.name,metric.description,id(3103),millis) }.message)
        original();assertEquals(0,count("next_request_origins"));assertEquals(0,count("sync_outbox"))
    }
    @Test fun ackAndReplayCannotAcceptExternalChangeUsingSpeculativeReceipt() = runBlocking<Unit> {
        val store=owner();val row=requireNotNull(store.edit("ACK","",id(3104),millis))
        val (http,_)=channel { successReply(it) };val delivery=requireNotNull(store.send(http,access(),row.operationId))
        store.afterPrepareForTest={externalChange()}
        assertEquals("G0_DOMAIN_EXTERNAL_CHANGE",rejected { store.acknowledge(delivery) }.message)
        original();assertUnaccepted(row)
    }
    @Test fun replayStillEntersSqlAndDetectsExternalChange() = runBlocking<Unit> {
        val store=owner();val row=requireNotNull(store.edit("Replay","",id(3105),millis))
        val (http,_)=channel { successReply(it) };val delivery=requireNotNull(store.send(http,access(),row.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(delivery))
        externalChange()
        assertEquals("G0_DOMAIN_EXTERNAL_CHANGE",rejected { store.acknowledge(delivery) }.message)
        original();assertEquals(1,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
    }
    @Test fun sharedColdProofPreservesOriginalWritePermissionRejection() = runBlocking<Unit> {
        register();val row=editMetric("Accepted")
        val (http,_)=channel { successReply(it) };val delivery=requireNotNull(sender(http).sendOperation(access(),row.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED,sender(http).acceptOperation(delivery))
        tokens.saveDeviceRegistration(id(4),setOf("sync.read"),true,2)
        val current=access()
        val reference=rejected { db.withTransaction { NextStructuralCausalStore(db).acceptedOperationProof(row.operationId,current) } }
        val candidate=G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid)
        val actual=rejected { candidate.initialize(current,trustedDomainSchema(true)) }
        assertTrue(reference is NextRequestException);assertTrue(actual is NextRequestException)
        assertEquals(reference.message,actual.message)
        assertEquals(1,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
    }
}
