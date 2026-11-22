package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.SyncOutboxEntity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0ColdNegativeEvidenceTest : G0SealedDomainFixture() {
    override fun domainOpeningCallback()=G0SchemaOpeningCallback(true)
    private suspend fun seed():SyncOutboxEntity {
        register();val row=editMetric("Negative")
        val (http,_)=channel { successReply(it) }
        val delivery=requireNotNull(sender(http).sendOperation(access(),row.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED,sender(http).acceptOperation(delivery));return row
    }
    private suspend fun proof(row:SyncOutboxEntity,negative:G0ColdNegativeEvidence):G0ColdMetricProof.Result {
        val memo=NextStructuralCausalStore.PureMemo()
        return G0ColdMetricProof(db,metric.uuid,negatives=negative).verify(row.operationId,access(),
            requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION,row.operationId)),
            requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION,row.operationId)),NextStructuralCausalStore(db,memo),memo)
    }
    @Test fun allPredicatesMatchSingleLookupsAcrossBlocksAndRejectBorrowedScope()=runBlocking<Unit> {
        register();val rows=(0 until 17).map { editMetric("Negative $it") };val ids=rows.map { it.operationId }
        lateinit var negative:G0ColdNegativeEvidence;lateinit var first:G0ColdNegativeEvidence
        var blocks=0;val trace=G0PhaseTrace(false)
        db.withTransaction {
            trace.run { G0ColdEvidenceBlocks(db).read(ids,reuseRows=true,batchNegatives=true,onNegatives={
                negative=it;blocks++;if(blocks==1)first=it
            }) { id ->
                val queue=rows.single { it.operationId==id }.id
                for(probe in G0ColdNegativeEvidence.Probe.entries) {
                    val args:Array<Any> = when(probe) {
                        G0ColdNegativeEvidence.Probe.SOURCE_QUEUE -> arrayOf(queue)
                        G0ColdNegativeEvidence.Probe.RECOVERY -> arrayOf(NEXT_OPERATION,id)
                        else -> arrayOf(id)
                    }
                    val expected=db.openHelper.writableDatabase.query("SELECT 1 FROM ${probe.table} WHERE ${probe.predicate} LIMIT 1",args).use { !it.moveToFirst() }
                    assertEquals(expected,negative.absent(probe,id,if(probe==G0ColdNegativeEvidence.Probe.SOURCE_QUEUE)queue else null))
                }
                assertEquals("G0_NEGATIVE_QUEUE",rejected { negative.absent(G0ColdNegativeEvidence.Probe.SOURCE_QUEUE,id,queue+99) }.message)
                if(id==ids.first()) {
                    assertEquals("G0_NEGATIVE_SCOPE",rejected { negative.absent(G0ColdNegativeEvidence.Probe.ORIGINAL,id(4900)) }.message)
                    assertNotNull(coroutineScope { async { rejected { negative.absent(G0ColdNegativeEvidence.Probe.ORIGINAL,id) } }.await() })
                }
                if(blocks==2) assertEquals("G0_NEGATIVE_SCOPE",rejected { first.absent(G0ColdNegativeEvidence.Probe.ORIGINAL,ids.first()) }.message)
            } }
        }
        assertEquals(2,blocks);assertEquals(10,trace.count("cold_negative_query"))
        db.withTransaction { NextEvidenceReadSession.read(db) {
            assertEquals("G0_NEGATIVE_SCOPE",rejected { first.absent(G0ColdNegativeEvidence.Probe.ORIGINAL,ids.first()) }.message)
        } }
    }
    @Test fun fullProofMatchesOriginalAndMissingQueueWitnessUsesOriginalLookup()=runBlocking<Unit> {
        val row=seed();val expected=db.withTransaction { NextStructuralCausalStore(db).acceptedOperationProof(row.operationId,access()) }
        val trace=G0PhaseTrace(false)
        val actual=trace.run { db.withTransaction { NextEvidenceReadSession.read(db) {
            val negative=G0ColdNegativeEvidence.load(db,listOf(row.operationId),emptyMap())
            assertFalse(negative.absent(G0ColdNegativeEvidence.Probe.SOURCE_QUEUE,row.operationId,row.id))
            proof(row,negative)
        } } }
        assertEquals(expected,actual.operation to actual.receiptHash)
        assertEquals(4,trace.count("cold_negative_query"));assertEquals(4,trace.count("cold_negative_hit"))
    }
    @Test fun presentMalformedMembershipAndBothSourceKeysStillRejectLikeOriginal()=runBlocking<Unit> {
        val row=seed();val image=storage.snapshotClosedDatabase()
        for(probe in G0ColdNegativeEvidence.Probe.entries) {
            storage.restoreClosedDatabase(image);storage.reopen()
            val error=rejected { db.withTransaction {
                val sql=db.openHelper.writableDatabase;sql.execSQL("PRAGMA defer_foreign_keys=ON")
                when(probe) {
                    G0ColdNegativeEvidence.Probe.SOURCE_QUEUE -> db.syncOutboxDao().insert(row.copy(operationId=id(4901)))
                    G0ColdNegativeEvidence.Probe.SOURCE_OPERATION -> db.syncOutboxDao().insert(row.copy(id=row.id+500))
                    else -> {
                        val fields=G0DomainSqlCatalog.columns.getValue(probe.table)
                        val values=fields.map { field -> when(field) {
                            "kind" -> NEXT_OPERATION
                            "originalId" -> if(probe==G0ColdNegativeEvidence.Probe.ORIGINAL)row.operationId else id(4902)
                            "replacementId" -> if(probe==G0ColdNegativeEvidence.Probe.REPLACEMENT)row.operationId else id(4903)
                            "requestId" -> row.operationId
                            "originalQueueId" -> row.id
                            "replacementQueueId" -> row.id+900
                            else -> "malformed"
                        } }
                        sql.execSQL("INSERT INTO ${probe.table} VALUES(${fields.joinToString(",") { "?" }})",values.toTypedArray<Any>())
                    }
                }
                assertNotNull(rejected { NextStructuralCausalStore(db).acceptedOperationProof(row.operationId,access()) })
                NextEvidenceReadSession.read(db) {
                    val negative=G0ColdNegativeEvidence.load(db,listOf(row.operationId),mapOf(row.operationId to row.id))
                    assertFalse(negative.absent(probe,row.operationId,if(probe==G0ColdNegativeEvidence.Probe.SOURCE_QUEUE)row.id else null))
                    assertNotNull(rejected { proof(row,negative) })
                }
                error("FIXTURE_ROLLBACK")
            } }
            assertEquals("FIXTURE_ROLLBACK",error.message)
            assertEquals(0,count("sync_outbox"));assertEquals(0,count("next_structural_supersessions"));assertEquals(0,count("next_abandoned_requests"))
        }
    }
    @Test fun mutationAfterNegativeReadRollsBackAndCannotPublishProof()=runBlocking<Unit> {
        val row=seed();lateinit var stale:G0ColdNegativeEvidence
        val error=rejected { db.withTransaction { NextEvidenceReadSession.read(db) {
            stale=G0ColdNegativeEvidence.load(db,listOf(row.operationId),mapOf(row.operationId to row.id))
            assertTrue(stale.absent(G0ColdNegativeEvidence.Probe.SOURCE_QUEUE,row.operationId,row.id))
            db.syncOutboxDao().insert(row)
        } } }
        assertEquals("EVIDENCE_READ_SESSION_WROTE_DATA",error.message);assertEquals(0,count("sync_outbox"))
        db.withTransaction { NextEvidenceReadSession.read(db) {
            assertEquals("G0_NEGATIVE_SCOPE",rejected { stale.absent(G0ColdNegativeEvidence.Probe.SOURCE_OPERATION,row.operationId) }.message)
        } }
    }
    @Test fun realCancellationDiscardsWitnessAndNewSessionSeesInsertedSource()=runBlocking<Unit> {
        val row=seed();val reached=CompletableDeferred<Unit>();lateinit var stale:G0ColdNegativeEvidence
        val work=launch { db.withTransaction { NextEvidenceReadSession.read(db) {
            stale=G0ColdNegativeEvidence.load(db,listOf(row.operationId),mapOf(row.operationId to row.id))
            reached.complete(Unit);awaitCancellation()
        } } }
        reached.await();work.cancelAndJoin();db.syncOutboxDao().insert(row)
        db.withTransaction { NextEvidenceReadSession.read(db) {
            assertEquals("G0_NEGATIVE_SCOPE",rejected { stale.absent(G0ColdNegativeEvidence.Probe.SOURCE_OPERATION,row.operationId) }.message)
            val fresh=G0ColdNegativeEvidence.load(db,listOf(row.operationId),mapOf(row.operationId to row.id))
            assertFalse(fresh.absent(G0ColdNegativeEvidence.Probe.SOURCE_OPERATION,row.operationId))
            assertNotNull(rejected { proof(row,fresh) })
        } }
    }
    @Test fun oversizedOriginBlockDoesNotManufactureQueueAbsence()=runBlocking<Unit> {
        register();val ids=(0 until 16).map { editMetric("Large $it").operationId };val trace=G0PhaseTrace(false)
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET intentJson=?",arrayOf("x".repeat(140_000)))
            lateinit var negative:G0ColdNegativeEvidence
            trace.run { G0ColdEvidenceBlocks(db).read(ids,reuseRows=true,batchNegatives=true,onNegatives={negative=it}) { id ->
                assertFalse(negative.absent(G0ColdNegativeEvidence.Probe.SOURCE_QUEUE,id,99999L))
                assertFalse(negative.absent(G0ColdNegativeEvidence.Probe.SOURCE_OPERATION,id))
            } }
        }
        assertEquals(4,trace.count("cold_negative_query"))
    }
}
