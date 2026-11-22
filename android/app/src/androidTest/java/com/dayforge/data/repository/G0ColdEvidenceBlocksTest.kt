package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0ColdEvidenceBlocksTest : G0SealedDomainFixture() {
    override fun domainOpeningCallback() = G0SchemaOpeningCallback(true)
    private suspend fun seed(size: Int): List<String> {
        register()
        return (0 until size).map { editMetric("Evidence $it").operationId }
    }
    private suspend fun compare(ids: List<String>) {
        val sql = db.openHelper.writableDatabase
        for (table in listOf("next_request_origins", "next_transmissions", "next_acceptances", "next_structural_dependencies")) {
            val where = if (table == "next_structural_dependencies") "operationId=?" else "kind=? AND requestId=?"
            for (id in ids) {
                val args: Array<Any> = if (table == "next_structural_dependencies") arrayOf(id) else arrayOf(NEXT_OPERATION,id)
                assertEquals(NextRequestSql.rowHash(sql,table,where,args),NextRequestSql.rowHash(db,table,where,args))
            }
        }
    }
    @Test(timeout=120_000) fun nativeHashesAbsenceAndUnprefetchedLayoutMatchAcrossBlockBoundary() = runBlocking<Unit> {
        val ids=seed(17);val trace=G0PhaseTrace(driverTiming=false)
        db.withTransaction {
            trace.run { G0ColdEvidenceBlocks(db).read(ids) { id -> compare(listOf(id, id(2999))) } }
        }
        assertEquals(2,trace.count("cold_evidence_block"));assertEquals(8,trace.count("cold_evidence_batch"))
    }
    @Test(timeout=120_000) fun aggregateOversizeFallsBackWithoutTruncatingRows() = runBlocking<Unit> {
        val ids=seed(16);val trace=G0PhaseTrace(driverTiming=false)
        db.withTransaction {
            // Raw audit fixture only: these strings are NOT valid business intents.
            db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET intentJson=?",arrayOf("x".repeat(140_000)))
            trace.run { G0ColdEvidenceBlocks(db).read(ids) { compare(listOf(it)) } }
        }
        assertEquals(1,trace.count("cold_evidence_block"));assertEquals(3,trace.count("cold_evidence_batch"))
    }
    @Test(timeout=120_000) fun corruptionInSecondBlockCannotBeHiddenByFirstBlockEvidence() = runBlocking<Unit> {
        val ids=seed(17)
        for (oversize in listOf(false,true)) {
            var visited=0
            try {
                db.withTransaction {
                    val sql=db.openHelper.writableDatabase
                    if(oversize) sql.execSQL("UPDATE next_request_origins SET intentJson=? WHERE requestId=?",
                        arrayOf("x".repeat(com.dayforge.data.api.SYNC_REQUEST_LIMIT+4097),ids.last()))
                    else sql.execSQL("UPDATE next_request_origins SET protocol=5.5 WHERE requestId=?",arrayOf(ids.last()))
                    val args: Array<Any> = arrayOf(NEXT_OPERATION,ids.last())
                    assertNotNull(rejected { NextRequestSql.rowHash(sql,"next_request_origins","kind=? AND requestId=?",args) })
                    G0ColdEvidenceBlocks(db).read(ids) { visited++ }
                }
                fail("corrupt second block accepted")
            } catch(e:NextRequestException) { assertEquals(16,visited) }
        }
        db.withTransaction { G0ColdEvidenceBlocks(db).read(ids) { compare(listOf(it)) } }
    }
    @Test(timeout=120_000) fun mutationDuringReadPhaseRollsBackAndEvidenceCannotEscape() = runBlocking<Unit> {
        val ids=seed(1)
        lateinit var expired: NextEvidenceReadSession
        val failure=rejected { db.withTransaction {
            G0ColdEvidenceBlocks(db).read(ids) {
                expired=NextEvidenceReadSession.current(db)
                db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET protocol=4")
            }
        } }
        assertEquals("EVIDENCE_READ_SESSION_WROTE_DATA",failure.message)
        db.withTransaction {
            assertEquals(5,requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION,ids.single())).protocol)
            val stale=rejected { withContext(expired) { compare(ids) } }
            assertEquals("EVIDENCE_READ_SESSION_EXPIRED",stale.message)
        }
    }
    @Test(timeout=120_000) fun childJobCannotBorrowEvidenceAndNestedBlockCannotReuseOuterSession() = runBlocking<Unit> {
        val ids=seed(1)
        db.withTransaction {
            G0ColdEvidenceBlocks(db).read(ids) {
                val result=coroutineScope { async { rejected { compare(ids) } }.await() }
                assertEquals("EVIDENCE_READ_SESSION_EXPIRED",result.message)
                assertEquals("G0_BLOCK_NESTED_SESSION",rejected { G0ColdEvidenceBlocks(db).read(ids) {} }.message)
            }
        }
    }
    @Test(timeout=120_000) fun realCancellationDiscardsBlockAndNewTransactionRereadsChangedRows() = runBlocking<Unit> {
        val ids=seed(17);val reached=CompletableDeferred<Unit>();var visited=0
        val work=launch { db.withTransaction {
            G0ColdEvidenceBlocks(db).read(ids) { visited++;if(visited==16) { reached.complete(Unit);awaitCancellation() } }
        } }
        reached.await();work.cancelAndJoin();assertEquals(16,visited)
        db.withTransaction { db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET protocol=5.5 WHERE requestId=?",arrayOf(ids.first())) }
        assertNotNull(rejected { db.withTransaction { G0ColdEvidenceBlocks(db).read(ids) {} } })
    }
}
