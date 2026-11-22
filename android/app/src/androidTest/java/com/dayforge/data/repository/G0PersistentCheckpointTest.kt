package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.NextSyncOperationResult
import com.dayforge.data.api.encodeSyncRequest
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class G0PersistentCheckpointTest : G0PersistentCheckpointFixture() {
    private suspend fun failure(block:suspend ()->Unit):Throwable = try { block();error("EXPECTED_REJECTION") }
        catch(e:Exception) { if(e.message=="EXPECTED_REJECTION") throw e else e }
    private fun state():Map<String,String> {
        val sql=db.openHelper.writableDatabase
        return G0PersistentCheckpoint.columns.keys.associateWith { table -> sql.query(G0PersistentCheckpoint.select(table)).use { c ->
            val rows=buildList { while(c.moveToNext()) add((0 until c.columnCount).joinToString("|") { i ->
                when(c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> "N"
                    Cursor.FIELD_TYPE_INTEGER -> "I${c.getLong(i)}"
                    Cursor.FIELD_TYPE_FLOAT -> "F${java.lang.Double.doubleToRawLongBits(c.getDouble(i))}"
                    else -> "${c.getType(i)}:${nextRequestHash(c.getBlob(i))}"
                }
            }) }
            nextRequestHash(rows.sorted().joinToString("\n").toByteArray())
        } }
    }
    @Test fun diskRoundTripReusesOnlyAcceptedProofAndKeepsFreshAuthentication()=runBlocking<Unit> {
        seed(2);val issued=checkpoint();certify(issued);assertTrue(issued.persisted)
        cold();val restored=checkpoint();val current=access();val expected=trustedDomainSchema(true)
        val candidate=store(restored);val trace=G0PhaseTrace(driverTiming=false)
        trace.run { candidate.initialize(current,expected) }
        assertTrue(restored.hit);assertEquals(2,trace.count("checkpoint_reuse"));assertEquals(0,trace.count("cold_origin_proof"))
        assertEquals(2,trace.count("decrypt_access"));assertTrue(restored.scannedRows>0)
        assertEquals("Seed 1",candidate.read(current).name)
        failDecrypt=true;failure { candidate.read(current) };failDecrypt=false
    }
    @Test fun invalidPacketMissingKeyAndWrongDatasetFallBackToFullProof()=runBlocking<Unit> {
        seed(1);certify();val good=checkpointFile.readBytes()
        for(case in listOf("missing","mac","version","truncated","dataset","key")) {
            checkpointFile.writeBytes(good)
            when(case) {
                "missing" -> assertTrue(checkpointFile.delete())
                "mac" -> checkpointFile.writeBytes(good.copyOf().apply { this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte() })
                "version" -> checkpointFile.writeBytes(good.copyOf().apply { this[7]=2 })
                "truncated" -> checkpointFile.writeBytes(good.copyOf(20))
                "key" -> KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry(checkpointAlias) }
            }
            cold();val cp=checkpoint(if(case=="dataset") "different-dataset" else dataset);val trace=G0PhaseTrace(driverTiming=false)
            val candidate=store(cp);trace.run { candidate.initialize(access(),trustedDomainSchema(true)) }
            assertFalse(case,cp.hit);assertEquals(case,1,trace.count("cold_origin_proof"));assertEquals("Seed 0",candidate.read(access()).name)
        }
    }
    @Test(timeout=240_000) fun changedAcceptedHistoryCannotBorrowAnAuthenticOldCheckpoint()=runBlocking<Unit> {
        seed(1);val image=storage.snapshotClosedDatabase();cold();certify();val packet=checkpointFile.readBytes()
        for(case in listOf("owner","native-type","wire","device","receipt-origin","receipt-result","normalized","extra-source")) {
            storage.restoreClosedDatabase(image);rawReopen();checkpointFile.writeBytes(packet)
            db.withTransaction {
                val sql=db.openHelper.writableDatabase
                when(case) {
                    "owner" -> sql.execSQL("UPDATE next_request_origins SET accountId=?",arrayOf(id(800)))
                    "native-type" -> sql.execSQL("UPDATE next_request_origins SET protocol=5.5")
                    "wire" -> sql.execSQL("UPDATE next_transmissions SET wireHash='wrong'")
                    "device" -> sql.execSQL("UPDATE next_transmissions SET deviceId=?",arrayOf(id(801)))
                    "receipt-origin" -> sql.execSQL("UPDATE next_acceptances SET originHash='wrong'")
                    "receipt-result" -> sql.execSQL("UPDATE next_acceptances SET resultHash='wrong'")
                    "normalized" -> {
                        val row=sql.query("SELECT resultJson FROM next_acceptances").use { c -> check(c.moveToFirst());c.getString(0) }
                        val value=com.dayforge.data.api.decodeFrozenSyncRequest(row.toByteArray(),NextSyncOperationResult.serializer())
                        val bytes=encodeSyncRequest(NextSyncOperationResult.serializer(),value.copy(entity=null))
                        sql.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=?",arrayOf(bytes.toString(Charsets.UTF_8),nextRequestHash(bytes)))
                    }
                    "extra-source" -> sql.execSQL("INSERT INTO sync_outbox(operationId,recordType,entityUuid,wireEntityUuid,action,attemptCount,createdAt) SELECT requestId,'metric',?,?,'upsert',0,0 FROM next_request_origins",arrayOf(metric.uuid,metric.uuid))
                }
            }
            val before=state();val corrupt=storage.snapshotClosedDatabase()
            cold();failure { store().initialize(access(),trustedDomainSchema(true)) }
            storage.restoreClosedDatabase(corrupt);cold();val cp=checkpoint()
            failure { store(cp).initialize(access(),trustedDomainSchema(true)) };assertFalse(case,cp.hit)
            assertArrayEquals(packet,checkpointFile.readBytes());rawReopen();assertEquals(case,before,state())
        }
    }
    @Test fun fullCoverageIncludesUnrelatedRowsEmptyTablesAndWatermarks()=runBlocking<Unit> {
        seed(1);val image=storage.snapshotClosedDatabase();cold();certify();val packet=checkpointFile.readBytes()
        for(case in listOf("unrelated","sequence","extra-row")) {
            storage.restoreClosedDatabase(image);rawReopen();checkpointFile.writeBytes(packet)
            when(case) {
                "unrelated" -> db.withTransaction {
                    val sql=db.openHelper.writableDatabase
                    sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                    sql.execSQL("UPDATE habits SET description='other valid description' WHERE id=?",arrayOf(habit.id))
                    sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
                }
                "sequence" -> db.openHelper.writableDatabase.execSQL("UPDATE sqlite_sequence SET seq=seq+1 WHERE name='sync_outbox'")
                "extra-row" -> db.openHelper.writableDatabase.execSQL("INSERT INTO android_metadata(locale) VALUES('second')")
            }
            cold();val cp=checkpoint();val trace=G0PhaseTrace(driverTiming=false)
            trace.run { certify(cp) };assertFalse(case,cp.hit);assertEquals(1,trace.count("cold_origin_proof"))
        }
    }
    @Test fun changedSchemaCannotReuseCertifiedData()=runBlocking<Unit> {
        seed(1);certify();val packet=checkpointFile.readBytes();rawReopen()
        db.openHelper.writableDatabase.execSQL("CREATE TABLE checkpoint_hidden(value TEXT)")
        cold();val cp=checkpoint();failure { certify(cp) };assertFalse(cp.hit);assertArrayEquals(packet,checkpointFile.readBytes())
    }
    @Test fun revokedCapabilityCannotBorrowEarlierAuthorityContext()=runBlocking<Unit> {
        seed(1);certify();val packet=checkpointFile.readBytes()
        tokens.saveDeviceRegistration(id(4),setOf("sync.read"),true,2)
        cold();val cp=checkpoint();failure { certify(cp) };assertFalse(cp.hit);assertArrayEquals(packet,checkpointFile.readBytes())
    }
    @Test fun matchingContentStillRequiresFinalDecryptAndCancellationCannotPublish()=runBlocking<Unit> {
        seed(1);certify();val packet=checkpointFile.readBytes()
        for(cancel in listOf(false,true)) {
            cold();val cp=checkpoint();val candidate=store(cp)
            candidate.afterCheckpointMatchForTest={ if(cancel) throw CancellationException("G0_CHECKPOINT_CANCEL") else failDecrypt=true }
            val current=access();failure { candidate.initialize(current,trustedDomainSchema(true)) }
            assertTrue(cp.hit);assertArrayEquals(packet,checkpointFile.readBytes());failDecrypt=false
            failure { candidate.read(current) }
        }
    }
    @Test fun unknownCommitDoesNotPublishCheckpointOrRepeatBusiness()=runBlocking<Unit> {
        seed(1);val cp=checkpoint();val candidate=certify(cp);val old=checkpointFile.readBytes()
        endFaults.failAfterNextEnd.set(true);failure { candidate.edit("Unknown checkpoint write","",id(810)) }
        assertArrayEquals(old,checkpointFile.readBytes());failure { candidate.refreshCheckpoint() }
        cold();val restored=checkpoint();val recovered=certify(restored)
        assertFalse(restored.hit);assertEquals("Unknown checkpoint write",recovered.read(access()).name)
        rawReopen();assertEquals(2,count("next_request_origins"));assertEquals(1,count("sync_outbox"))
    }
    @Test fun failedSidecarPublicationKeepsKnownSavedWriteAndOldCheckpoint()=runBlocking<Unit> {
        seed(1);val cp=checkpoint();val candidate=certify(cp);val old=checkpointFile.readBytes()
        candidate.edit("Saved without checkpoint","",id(811));cp.failPublishForTest=true
        assertFalse(candidate.refreshCheckpoint());assertArrayEquals(old,checkpointFile.readBytes())
        assertEquals("Saved without checkpoint",candidate.read(access()).name)
        cold();val restored=checkpoint();assertEquals("Saved without checkpoint",certify(restored).read(access()).name)
        assertFalse(restored.hit);rawReopen();assertEquals(2,count("next_request_origins"));assertEquals(1,count("sync_outbox"))
    }
    @Test fun refreshedCheckpointRecoversPendingWriteThenAckWithoutBypassingOriginalProof()=runBlocking<Unit> {
        seed(1);val cp=checkpoint();val candidate=certify(cp)
        val written=requireNotNull(candidate.edit("Refreshed checkpoint","",id(812)));assertTrue(candidate.refreshCheckpoint())
        cold();val restored=checkpoint();val trace=G0PhaseTrace(driverTiming=false);val recovered=store(restored)
        trace.run { recovered.initialize(access(),trustedDomainSchema(true)) }
        assertTrue(restored.hit);assertEquals(1,trace.count("checkpoint_reuse"));assertEquals(1,trace.count("cold_origin_proof"))
        val (http,_)=channel { input -> successReply(input,revision=(if(input.path.endsWith("/identity")) 0L else wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0L)+1) }
        val delivery=requireNotNull(recovered.send(http,access(),written.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED,recovered.acknowledge(delivery));assertTrue(recovered.refreshCheckpoint())
        cold();val again=checkpoint();val final=certify(again);assertTrue(again.hit);assertEquals(0,final.read(access()).pending)
        rawReopen();val before=state();val current=access()
        db.withTransaction { NextStructuralCausalStore(db).acceptedOperationProof(written.operationId,current) }
        assertEquals(before,state());assertEquals(39,before.size);assertEquals(2,count("next_acceptances"))
    }
}
