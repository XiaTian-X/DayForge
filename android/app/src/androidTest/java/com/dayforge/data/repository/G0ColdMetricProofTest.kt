package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.entity.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0ColdMetricProofTest : G0SealedDomainFixture() {
    override fun domainOpeningCallback() = G0SchemaOpeningCallback(true)
    private suspend fun seed(): SyncOutboxEntity {
        register();val row=editMetric("Proof")
        val (http,_)=channel { successReply(it) };val delivery=requireNotNull(sender(http).sendOperation(access(),row.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED,sender(http).acceptOperation(delivery))
        return row
    }
    private suspend fun candidate(id: String, cache: Boolean = true, timing: Boolean = true): G0ColdMetricProof.Result {
        val memo=NextStructuralCausalStore.PureMemo()
        val origin=requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION,id))
        val negative=G0ColdNegativeEvidence.load(db,listOf(id),mapOf(id to origin.queueId))
        return G0ColdMetricProof(db,metric.uuid,cacheCodecRules=cache,measureCodecStages=timing,negatives=negative).verify(id,access(),
            requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION,id)),
            requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION,id)),NextStructuralCausalStore(db,memo),memo)
    }
    private suspend fun read(id:String) = db.withTransaction { NextEvidenceReadSession.read(db) { candidate(id) } }
    private suspend fun reference(id:String) = db.withTransaction { NextStructuralCausalStore(db).acceptedOperationProof(id,access()) }
    private suspend fun wire(id:String,bytes:ByteArray) {
        val sql=db.openHelper.writableDatabase
        sql.execSQL("UPDATE next_transmissions SET wireBytes=?,wireHash=? WHERE requestId=?",arrayOf(bytes,nextRequestHash(bytes),id))
        val hash=requireNotNull(NextRequestSql.rowHash(sql,"next_transmissions","kind=? AND requestId=?",arrayOf(NEXT_OPERATION,id)))
        sql.execSQL("UPDATE next_acceptances SET transmissionHash=? WHERE requestId=?",arrayOf(hash,id))
    }
    private fun state():Map<String,String> {
        val sql=db.openHelper.writableDatabase
        val tables=sql.query("SELECT name FROM sqlite_master WHERE type='table'").use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
        return tables.associateWith { table -> sql.query("SELECT * FROM `$table`").use { c ->
            val rows=buildList { while(c.moveToNext()) add((0 until c.columnCount).joinToString("|") { i -> when(c.getType(i)) {
                android.database.Cursor.FIELD_TYPE_NULL -> "N"
                android.database.Cursor.FIELD_TYPE_INTEGER -> "I${c.getLong(i)}"
                android.database.Cursor.FIELD_TYPE_FLOAT -> "F${java.lang.Double.doubleToRawLongBits(c.getDouble(i))}"
                android.database.Cursor.FIELD_TYPE_STRING -> "S${nextRequestHash(c.getString(i).toByteArray())}"
                else -> "B${nextRequestHash(c.getBlob(i))}"
            } }) }
            nextRequestHash((c.columnNames.toList().toString()+rows.sorted().joinToString("\n")).toByteArray())
        } }
    }
    @Test(timeout=120_000) fun originalAndCandidateReturnExactSameOperationAndReceiptWithoutWrites() = runBlocking<Unit> {
        val row=seed();val before=state();val expected=reference(row.operationId)
        val trace=G0PhaseTrace(driverTiming=false);val actual=trace.run { read(row.operationId) }
        assertEquals(expected,actual.operation to actual.receiptHash);assertEquals(before,state())
        assertEquals(1,trace.count("cold_proof_lineage"));assertEquals(1,trace.count("cold_proof_wire"))
        assertEquals(1,trace.count("cold_proof_dependency_binding"));assertEquals(0,trace.count("cold_proof_lineage_fallback"));assertEquals(1,trace.count("cold_proof_source_absence"))
    }
    @Test(timeout=120_000) fun allCodecModesKeepFullProofAndRehashedWireRejection()=runBlocking<Unit> {
        val row=seed();val expected=reference(row.operationId);val before=state()
        suspend fun mode(cache:Boolean,timing:Boolean)=db.withTransaction {
            NextEvidenceReadSession.read(db) { candidate(row.operationId,cache,timing) }
        }
        for(cache in listOf(false,true)) for(timing in listOf(false,true)) {
            val proof=mode(cache,timing)
            assertEquals(expected,proof.operation to proof.receiptHash);assertEquals(before,state())
        }
        val old=requireNotNull(db.nextRequestDao().transmission(NEXT_OPERATION,row.operationId))
        val body=Json.parseToJsonElement(old.wireBytes.toString(Charsets.UTF_8)).jsonObject
        db.withTransaction { wire(row.operationId,JsonObject(body+("device_id" to JsonPrimitive(id(4300)))).toString().toByteArray()) }
        val corrupt=state()
        rejected { reference(row.operationId) }
        for(cache in listOf(false,true)) for(timing in listOf(false,true)) { rejected { mode(cache,timing) };assertEquals(corrupt,state()) }
    }
    @Test(timeout=180_000) fun rehashedWrongEnvelopesAndReceiptBindingsStillRejectWithoutWrites() = runBlocking<Unit> {
        val row=seed();val image=storage.snapshotClosedDatabase()
        for(case in listOf("unknown-wire","round-wire","wire-device","wire-operation","receipt-operation","receipt-extra")) {
            storage.restoreClosedDatabase(image);storage.reopen()
            db.withTransaction {
                val transmission=requireNotNull(db.nextRequestDao().transmission(NEXT_OPERATION,row.operationId))
                val body=Json.parseToJsonElement(transmission.wireBytes.toString(Charsets.UTF_8)).jsonObject
                if(case.startsWith("receipt")) {
                    val receipt=requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION,row.operationId))
                    val result=Json.parseToJsonElement(receipt.resultJson).jsonObject
                    val changed=JsonObject(result + if(case=="receipt-operation") mapOf("operation_id" to JsonPrimitive(id(3901))) else mapOf("unexpected" to JsonPrimitive(true)))
                    val bytes=changed.toString().toByteArray()
                    db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE requestId=?",arrayOf(changed.toString(),nextRequestHash(bytes),row.operationId))
                } else {
                    val changed=when(case) {
                        "unknown-wire" -> JsonObject(body+("unexpected" to JsonPrimitive(true)))
                        "round-wire" -> JsonObject(body+mapOf("challenge_contract" to JsonPrimitive(1),"contexts" to JsonArray(emptyList())))
                        "wire-device" -> JsonObject(body+("device_id" to JsonPrimitive(id(3902))))
                        else -> {
                            val op=body.getValue("operations").jsonArray.single().jsonObject
                            JsonObject(body+("operations" to JsonArray(listOf(JsonObject(op+("operation_id" to JsonPrimitive(id(3903))))))))
                        }
                    }
                    wire(row.operationId,changed.toString().toByteArray())
                }
            }
            val before=state()
            assertNotNull("$case original",rejected { reference(row.operationId) })
            assertNotNull("$case candidate",rejected { read(row.operationId) })
            assertEquals("$case persistent tables",before,state())
        }
    }
    @Test(timeout=120_000) fun sourceUnderDifferentQueueIdentityIsNotMistakenForAbsence() = runBlocking<Unit> {
        val row=seed();db.syncOutboxDao().insert(row.copy(id=row.id+500))
        val before=state();assertNotNull(rejected { reference(row.operationId) });assertNotNull(rejected { read(row.operationId) });assertEquals(before,state())
    }
    @Test(timeout=120_000) fun reusedPureMemoCannotHideChangedReceiptInANewSnapshot() = runBlocking<Unit> {
        val row=seed();val memo=NextStructuralCausalStore.PureMemo();val causal=NextStructuralCausalStore(db,memo)
        suspend fun proof() = db.withTransaction { NextEvidenceReadSession.read(db) {
            G0ColdMetricProof(db,metric.uuid).verify(row.operationId,access(),requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION,row.operationId)),
                requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION,row.operationId)),causal,memo)
        } }
        proof();db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultHash='changed'")
        val before=state();assertNotNull(rejected { reference(row.operationId) });assertNotNull(rejected { proof() });assertEquals(before,state())
    }
    @Test(timeout=120_000) fun largeStrictEnvelopeUsesSameDecoderBoundaryAndSameOriginalProof() = runBlocking<Unit> {
        val row=seed();db.withTransaction {
            val original=requireNotNull(db.nextRequestDao().transmission(NEXT_OPERATION,row.operationId)).wireBytes
            wire(row.operationId," ".repeat(9000).toByteArray()+original)
        }
        val before=state();val expected=reference(row.operationId);val actual=read(row.operationId)
        assertEquals(expected,actual.operation to actual.receiptHash);assertEquals(before,state())
    }
    @Test(timeout=120_000) fun missingOrBorrowedEvidenceScopeCannotAuthorizeReceiptReuse() = runBlocking<Unit> {
        val row=seed()
        assertEquals("EVIDENCE_READ_SESSION_REQUIRED",rejected { db.withTransaction { candidate(row.operationId) } }.message)
        db.withTransaction { NextEvidenceReadSession.read(db) {
            val failure=coroutineScope { async { rejected { candidate(row.operationId) } }.await() }
            assertEquals("EVIDENCE_READ_SESSION_EXPIRED",failure.message)
        } }
        assertEquals(reference(row.operationId).second,read(row.operationId).receiptHash)
    }
    @Test(timeout=180_000) fun rootDependencyCorruptionsAndLegacyFallbackMatchOriginal() = runBlocking<Unit> {
        val row=seed();val image=storage.snapshotClosedDatabase()
        val changes=listOf("logicalOrder=0","logicalOrder=logicalOrder+1","originHash='wrong'",
            "capturedDeviceId='invalid'","capturedDeviceId='00000000-0000-4000-8000-000000000777'",
            "predecessorOriginHash='stray'","predecessorDependencyHash='stray'",
            "predecessorId=operationId,predecessorOriginHash=originHash,predecessorDependencyHash='wrong'")
        for(change in changes) {
            storage.restoreClosedDatabase(image);storage.reopen()
            db.openHelper.writableDatabase.execSQL("UPDATE next_structural_dependencies SET $change")
            val before=state()
            assertNotNull(change,rejected { reference(row.operationId) });assertNotNull(change,rejected { read(row.operationId) })
            assertEquals(change,before,state())
        }
        storage.restoreClosedDatabase(image);storage.reopen()
        db.openHelper.writableDatabase.execSQL("DELETE FROM next_structural_dependencies")
        val expected=reference(row.operationId);val trace=G0PhaseTrace(false)
        val actual=trace.run { read(row.operationId) }
        assertEquals(expected,actual.operation to actual.receiptHash)
        assertEquals(1,trace.count("cold_proof_lineage_fallback"));assertNull(actual.dependency)
    }
    @Test(timeout=180_000) fun rehashedMalformedMetricResultsMatchOriginalAndLeaveAllTablesUnchanged() = runBlocking<Unit> {
        val row=seed();val receipt=requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION,row.operationId))
        val result=Json.parseToJsonElement(receipt.resultJson).jsonObject
        val entity=result.getValue("entity").jsonObject
        val mutations=listOf(
            "public_id" to JsonPrimitive(id(3991)),"revision" to JsonPrimitive(0),"revision" to JsonPrimitive("1"),
            "created_at" to JsonPrimitive("invalid"),"updated_at" to JsonPrimitive("+10000-01-01T00:00:00Z"),
            "deleted_at" to JsonPrimitive("2026-01-01T00:00:00Z"),"name" to JsonPrimitive(""),
            "unit" to JsonPrimitive(""),"description" to JsonPrimitive("x".repeat(1001)),
            "decimal_places" to JsonPrimitive(7),"decimal_places" to JsonPrimitive("2"),
            "aggregation_type" to JsonPrimitive("unknown"),"target_direction" to JsonPrimitive("range"),
            "target_value" to JsonPrimitive("1e999"),"target_value" to JsonPrimitive("0.10000000000000001"),
            "status" to JsonPrimitive("invalid"),"appearance" to JsonNull,"extra" to JsonPrimitive(true))
        for((key,value) in mutations) {
            val changed=JsonObject(result+("entity" to JsonObject(entity+(key to value)))).toString()
            db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE requestId=?",
                arrayOf(changed,nextRequestHash(changed.toByteArray()),row.operationId))
            val before=state()
            assertNotNull("$key=$value original",rejected { reference(row.operationId) })
            assertNotNull("$key=$value candidate",rejected { read(row.operationId) })
            assertEquals("$key tables",before,state())
        }
    }
    @Test(timeout=120_000) fun fusedMetricProjectionMatchesOriginalIncludingExactPureMemoInputs() = runBlocking<Unit> {
        val row=seed();val receipt=requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION,row.operationId))
        val entity=Json.parseToJsonElement(receipt.resultJson).jsonObject.getValue("entity").jsonObject
        val revision=entity.getValue("revision").jsonPrimitive.long
        val normalizer=G0ColdMetricNormalization();val trace=G0PhaseTrace(false)
        val variants=listOf(entity,
            JsonObject(entity+mapOf("target_direction" to JsonPrimitive("range"),"target_value" to JsonPrimitive("1.25"),"target_value_upper" to JsonPrimitive(2))),
            JsonObject(entity+mapOf("status" to JsonPrimitive("archived"),"decimal_places" to JsonPrimitive(6))))
        trace.run { for(value in variants) assertEquals(NextStructureMapper.readMetric(value,metric.uuid,revision),normalizer.read(value,metric.uuid,revision)) }
        assertEquals(2,trace.count("cold_normalize_appearance_reuse"))
        val changed=JsonObject(entity+("appearance" to JsonObject(entity.getValue("appearance").jsonObject+("unexpected" to JsonPrimitive(true)))))
        assertNotNull(rejected { NextStructureMapper.readMetric(changed,metric.uuid,revision) })
        assertNotNull(rejected { normalizer.read(changed,metric.uuid,revision) })
        assertEquals(NextStructureMapper.readMetric(entity,metric.uuid,revision),normalizer.read(entity,metric.uuid,revision))
    }

    @Test(timeout=120_000) fun rehashedSourceProfilesCannotBorrowPlainProofAndLargePlainStillMatches()=runBlocking<Unit> {
        val row=seed();val origin=requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION,row.operationId))
        val receipt=requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION,row.operationId))
        val json=Json.parseToJsonElement(origin.intentJson).jsonObject
        suspend fun replace(text:String) = db.withTransaction {
            val sql=db.openHelper.writableDatabase
            sql.execSQL("UPDATE next_request_origins SET intentJson=CAST(? AS TEXT) WHERE requestId=?",arrayOf(text.toByteArray(Charsets.UTF_8),row.operationId))
            assertEquals("exact injected source",text,requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION,row.operationId)).intentJson)
            val hash=requireNotNull(NextRequestSql.rowHash(sql,"next_request_origins","kind=? AND requestId=?",arrayOf(NEXT_OPERATION,row.operationId)))
            sql.execSQL("UPDATE next_structural_dependencies SET originHash=? WHERE operationId=?",arrayOf(hash,row.operationId))
            sql.execSQL("UPDATE next_acceptances SET originHash=? WHERE requestId=?",arrayOf(hash,row.operationId))
        }
        for(key in listOf("challenge_contract","count_plan_frontier","restart_frontier")) {
            replace(JsonObject(json+(key to JsonPrimitive(1))).toString())
            val before=state();assertNotNull(rejected { reference(row.operationId) });assertNotNull(rejected { read(row.operationId) });assertEquals(before,state())
        }
        val bom="\uFEFF"+origin.intentJson
        // Keep the fixture failure too: Android's String binding removes its initial BOM.
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET intentJson=? WHERE requestId=?",arrayOf(bom,row.operationId))
        assertEquals("String binding strips BOM",origin.intentJson,requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION,row.operationId)).intentJson)
        assertNotNull(rejected { roundOperationIntent(bom) })
        replace(bom)
        // Keep the failed design counterexample: using the byte codec alone broadens
        // original source acceptance. The guarded full candidate must still reject it.
        assertEquals(row.operationId,G0ColdStrictDecoder().decode(bom.toByteArray(),SyncV2Operation.serializer()).operationId)
        val bomState=state();assertNotNull(rejected { reference(row.operationId) });assertNotNull(rejected { read(row.operationId) });assertEquals(bomState,state())
        replace(" ".repeat(9000)+origin.intentJson)
        val padded=" ".repeat(9000)+receipt.resultJson
        db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE requestId=?",
            arrayOf(padded,nextRequestHash(padded.toByteArray()),row.operationId))
        val before=state();val expected=reference(row.operationId);val actual=read(row.operationId)
        assertTrue(actual.strictPlain);assertEquals(expected,actual.operation to actual.receiptHash);assertEquals(before,state())
    }

}
