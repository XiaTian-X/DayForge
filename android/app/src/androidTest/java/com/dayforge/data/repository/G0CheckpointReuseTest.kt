package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0CheckpointReuseTest : G0PersistentCheckpointFixture() {
    private fun state(window:LongRange):Map<String,String> = G0PersistentCheckpoint.columns.keys.associateWith { table ->
        db.openHelper.writableDatabase.query(G0PersistentCheckpoint.select(table)).use { c ->
            val rows=buildList { while(c.moveToNext()) add((0 until c.columnCount).joinToString("|") { i ->
                if(table=="sync_entity_state" && c.columnNames[i]=="updatedAt" &&
                    c.getString(c.getColumnIndexOrThrow("entityType"))=="metric" &&
                    c.getString(c.getColumnIndexOrThrow("entityUuid"))==metric.uuid) {
                    assertEquals(Cursor.FIELD_TYPE_INTEGER,c.getType(i));assertTrue(c.getLong(i) in window);"SHADOW_CLOCK"
                } else when(c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> "N"
                    Cursor.FIELD_TYPE_INTEGER -> "I${c.getLong(i)}"
                    Cursor.FIELD_TYPE_FLOAT -> "F${java.lang.Double.doubleToRawLongBits(c.getDouble(i))}"
                    else -> "${c.getType(i)}:${nextRequestHash(c.getBlob(i))}"
                }
            }) }
            nextRequestHash((c.columnNames.toList().toString()+rows.sorted().joinToString("\n")).toByteArray())
        }
    }
    @Test fun bufferedFingerprintMatchesUnbufferedAcrossNativeValuesAndBufferEdges()=runBlocking<Unit> {
        seed(1);val expected=trustedDomainSchema(true)
        val reference=checkpoint(bufferDigests=false);val candidate=checkpoint()
        db.withTransaction {
            val sql=db.openHelper.writableDatabase
            var previous=reference.capture(sql,expected)
            for(value in listOf(null,byteArrayOf(0,-1,1),"中文🙂", "x".repeat(4095),"y".repeat(4096),"z".repeat(4097),"a".repeat(8193))) {
                sql.execSQL("INSERT INTO android_metadata(locale) VALUES(?)",arrayOf(value))
                val actual=candidate.capture(sql,expected)
                assertArrayEquals(reference.capture(sql,expected),actual)
                assertFalse(java.security.MessageDigest.isEqual(previous,actual));previous=actual
            }
            sql.execSQL("UPDATE sqlite_sequence SET seq=1.25 WHERE name='sync_outbox'")
            assertArrayEquals(reference.capture(sql,expected),candidate.capture(sql,expected))
            assertFalse(java.security.MessageDigest.isEqual(previous,candidate.capture(sql,expected)))
        }
    }
    @Test fun oldPacketHitsAndReusesOnlySameBlockRowsWithFreshAuthentication()=runBlocking<Unit> {
        seed(2);val old=checkpoint(bufferDigests=false)
        store(old,reuseRows=false).initialize(access(),trustedDomainSchema(true));val packet=checkpointFile.readBytes()
        cold();val cp=checkpoint();val candidate=store(cp);val trace=G0PhaseTrace(driverTiming=false)
        val current=access();val expected=trustedDomainSchema(true)
        trace.run { candidate.initialize(current,expected) }
        assertTrue(cp.hit);assertEquals(4,trace.count("checkpoint_typed_row_reuse"))
        assertEquals(2,trace.count("checkpoint_reuse"));assertEquals(0,trace.count("cold_origin_proof"))
        assertEquals(2,trace.count("decrypt_access"))
        assertEquals("Seed 1",candidate.read(access()).name);assertArrayEquals(packet,checkpointFile.readBytes())
    }
    @Test fun reusedRowsAndLegacyHydrationProduceIdenticalEditOutboxAckAndPersistentState()=runBlocking<Unit> {
        seed(2);val image=storage.snapshotClosedDatabase();cold();certify();val packet=checkpointFile.readBytes()
        val expected=trustedDomainSchema(true);var reference:Map<String,String>?=null
        for(reuse in listOf(false,true)) {
            storage.restoreClosedDatabase(image);cold();checkpointFile.writeBytes(packet)
            val cp=checkpoint(bufferDigests=reuse);val candidate=G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid,queueClock={millis},checkpoint=cp,reuseCheckpointRows=reuse)
            candidate.initialize(access(),expected);assertTrue(cp.hit)
            val started=System.currentTimeMillis()
            val written=requireNotNull(candidate.edit("Same persisted result","reuse",id(970),millis+2000))
            assertEquals(1,candidate.read(access()).pending)
            val (http,_)=channel { input -> successReply(input,revision=(if(input.path.endsWith("/identity")) 0L else
                wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0L)+1) }
            val delivery=requireNotNull(candidate.send(http,access(),written.operationId))
            assertEquals(NextOperationAcceptance.COMMITTED,candidate.acknowledge(delivery))
            assertEquals(0,candidate.read(access()).pending);assertTrue(candidate.refreshCheckpoint())
            rawReopen();val current=access()
            db.withTransaction { NextStructuralCausalStore(db).acceptedOperationProof(written.operationId,current) }
            val actual=state(started..System.currentTimeMillis());assertEquals(39,actual.size)
            if(reference==null) reference=actual else assertEquals(reference,actual)
        }
    }
}
