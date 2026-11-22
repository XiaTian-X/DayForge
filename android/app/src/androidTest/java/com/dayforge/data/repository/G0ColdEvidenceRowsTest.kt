package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0ColdEvidenceRowsTest : G0SealedDomainFixture() {
    override fun domainOpeningCallback()=G0SchemaOpeningCallback(true)
    private suspend fun seed():String {
        register();val row=editMetric("Rows");val (http,_)=channel { successReply(it) }
        val delivery=requireNotNull(sender(http).sendOperation(access(),row.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED,sender(http).acceptOperation(delivery));return row.operationId
    }
    @Test fun typedRowsMatchOriginalAreBoundToSessionAndDefensivelyCopyWire()=runBlocking<Unit> {
        val id=seed();lateinit var rows:G0ColdEvidenceRows
        db.withTransaction {
            G0ColdEvidenceBlocks(db).read(listOf(id),reuseRows=true,onRows={rows=it}) {
                assertEquals(db.nextRequestDao().origin(NEXT_OPERATION,id),rows.origin(id))
                assertEquals(db.nextRequestDao().acceptance(NEXT_OPERATION,id),rows.receipt(id))
                val original=requireNotNull(db.nextRequestDao().transmission(NEXT_OPERATION,id))
                val first=requireNotNull(rows.transmission(id));assertArrayEquals(original.wireBytes,first.wireBytes)
                first.wireBytes.fill(0);assertArrayEquals(original.wireBytes,requireNotNull(rows.transmission(id)).wireBytes)
                assertNotNull(coroutineScope { async { rejected { rows.origin(id) } }.await() })
                assertEquals("G0_COLD_ROWS_SCOPE",rejected { rows.origin(this@G0ColdEvidenceRowsTest.id(4400)) }.message)
            }
        }
        db.withTransaction { NextEvidenceReadSession.read(db) { assertEquals("G0_COLD_ROWS_SCOPE",rejected { rows.origin(id) }.message) } }
    }
    @Test fun nativeCorruptionIsRejectedBeforeTypedRowsAreOffered()=runBlocking<Unit> {
        val id=seed();var offered=false
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET protocol=5.5 WHERE requestId=?",arrayOf(id))
            assertNotNull(rejected { G0ColdEvidenceBlocks(db).read(listOf(id),reuseRows=true,onRows={offered=true}) {} })
            assertFalse(offered)
        }
    }
    @Test fun aggregateDtoBudgetFallsBackAndNextBlockCannotBorrowRows()=runBlocking<Unit> {
        register();val ids=(0 until 17).map { editMetric("Rows $it").operationId }
        val trace=G0PhaseTrace(false);lateinit var first:G0ColdEvidenceRows;var blocks=0
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET intentJson=?",arrayOf("x".repeat(140_000)))
            trace.run { G0ColdEvidenceBlocks(db).read(ids,reuseRows=true,onRows={ rows -> blocks++;if(blocks==1)first=rows }) { id ->
                assertEquals(ids.indexOf(id)/16+1,blocks)
                if(blocks==1) assertEquals(db.nextRequestDao().origin(NEXT_OPERATION,id),first.origin(id))
                else assertEquals("G0_COLD_ROWS_SCOPE",rejected { first.origin(ids.first()) }.message)
            } }
        }
        assertEquals(2,blocks)
        // First block's origin native audit is oversized: only empty wire/receipt DTO tables.
        assertEquals(5,trace.count("cold_rows_table"))
    }
    @Test fun rowsPublishOnceOnlyAfterEveryNativeTableAuditCompletes()=runBlocking<Unit> {
        val id=seed();var publications=0
        db.withTransaction {
            G0ColdEvidenceBlocks(db).read(listOf(id),reuseRows=true,onRows={ publications++;assertEquals(1,publications) }) {
                assertEquals(1,publications)
            }
        }
        assertEquals(1,publications)
        val image=storage.snapshotClosedDatabase()
        for(table in listOf("next_transmissions","next_acceptances")) {
            storage.restoreClosedDatabase(image);storage.reopen();publications=0
            db.withTransaction {
                val sql=db.openHelper.writableDatabase
                if(table=="next_transmissions") sql.execSQL("UPDATE next_transmissions SET wireBytes='wrong native type'")
                else sql.execSQL("UPDATE next_acceptances SET resultJson=CAST('wrong native type' AS BLOB)")
                assertNotNull(rejected { G0ColdEvidenceBlocks(db).read(listOf(id),reuseRows=true,onRows={ publications++ }) { fail("validated damaged block") } })
                assertEquals(0,publications)
            }
        }
    }
    @Test fun combinedDtoBudgetFallsBackWithoutTruncatingReceipt()=runBlocking<Unit> {
        val id=seed();val text="x".repeat(900_000);val wire=ByteArray(900_000) { 1 };val trace=G0PhaseTrace(false)
        db.withTransaction {
            val sql=db.openHelper.writableDatabase
            sql.execSQL("UPDATE next_request_origins SET intentJson=?",arrayOf(text))
            sql.execSQL("UPDATE next_transmissions SET wireBytes=?",arrayOf(wire))
            sql.execSQL("UPDATE next_acceptances SET resultJson=?",arrayOf(text))
            lateinit var rows:G0ColdEvidenceRows
            trace.run { G0ColdEvidenceBlocks(db).read(listOf(id),reuseRows=true,onRows={ rows=it }) {
                assertEquals(text,requireNotNull(rows.origin(id)).intentJson)
                assertArrayEquals(wire,requireNotNull(rows.transmission(id)).wireBytes)
                assertEquals(text,requireNotNull(rows.receipt(id)).resultJson)
            } }
        }
        assertEquals(4,trace.count("cold_evidence_batch"))
        assertEquals(2,trace.count("cold_rows_table"))
    }

}
