package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.HabitDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Full cold chain, including a newly created trusted reference each time. No prewarming. */
@RunWith(AndroidJUnit4::class)
class G0InnerProofCostTest : G0SealedDomainFixture() {
    @Test(timeout=900_000) fun eightCompletedRequestsWithPairedInnerProof() = measure(8)
    @Test(timeout=900_000) fun eightyCompletedRequestsWithPairedInnerProof() = measure(80)
    private fun measure(history: Int) = runBlocking<Unit> {
        register()
        val (http,_)=channel { input -> successReply(input,revision=(if(input.path.endsWith("/identity")) 0L else
            wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0L)+1) }
        repeat(history) { i ->
            val row=editMetric("Seed $i")
            val delivery=requireNotNull(sender(http).sendOperation(access(),row.operationId))
            assertEquals(NextOperationAcceptance.COMMITTED,sender(http).acceptOperation(delivery))
        }
        val image=storage.snapshotClosedDatabase()
        val old=mutableListOf<Long>();val candidate=mutableListOf<Long>()
        repeat(20) { sample ->
            for(batched in if(sample%2==0) listOf(false,true) else listOf(true,false)) {
                storage.restoreClosedDatabase(image);freshGateForColdOpen()
                storage.isolatedBuilder={ context -> Room.databaseBuilder(context,HabitDatabase::class.java,"habit_database")
                    .addCallback(G0SchemaOpeningCallback(true)).openHelperFactory(domainGate.factory).build() }
                storage.reopen();val current=access()
                val start=SystemClock.elapsedRealtimeNanos()
                val expected=trustedDomainSchema(batchedOpening=true)
                val schemaEnd=SystemClock.elapsedRealtimeNanos()
                val store=G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid,blockColdEvidence=true,innerColdProof=batched)
                val trace=G0PhaseTrace(driverTiming=false)
                trace.run { store.initialize(current,expected) }
                val ready=SystemClock.elapsedRealtimeNanos()
                val view=store.read(current)
                val read=SystemClock.elapsedRealtimeNanos()
                assertEquals("Seed ${history-1}",view.name);assertEquals(0,view.pending)
                assertEquals(2,trace.count("decrypt_access"))
                assertEquals(history,trace.count("cold_origin_proof"))
                if(batched) { assertEquals(history,trace.count("cold_proof_lineage")); assertEquals(history,trace.count("cold_proof_wire")); assertEquals(history,trace.count("cold_proof_source_absence")) }
                assertEquals(expected.count { it[0]=="main" && it[1]=="trigger" },trace.count("schema_callback_trigger"))
                assertEquals(1,trace.count("schema_callback_control"))
                assertEquals(1,trace.count("schema_callback_begin"))
                assertEquals((history+15)/16,trace.count("cold_evidence_block"))
                if(batched) candidate+=ready-start else old+=ready-start
                Log.i("G0InnerProofCost",buildJsonObject {
                    put("history",history);put("sample",sample);put("inner",batched);put("evidence_blocks",trace.count("cold_evidence_block"));put("evidence_batches",trace.count("cold_evidence_batch"))
                    put("ready_ns",ready-start);put("first_read_ns",read-start);put("schema_ns",schemaEnd-start)
                    put("certificate_ns",ready-schemaEnd)
                    for(phase in listOf("cold_open","cold_entry_auth","cold_room_wait","cold_validation","cold_journal",
                        "cold_final_auth","cold_tx_finish","cold_index","cold_origin_scope_decode","cold_origin_scope","cold_origin_proof","cold_origin_capture","schema_callback_total","schema_callback_body",
                        "schema_callback_trigger","schema_callback_control","schema_callback_begin","schema_callback_end"))
                        put(phase+"_ns",trace.nanos(phase))
                    for(phase in listOf("lineage","origin","intent","restart","receipt_hash","transmission_hash","transmission_row","wire","receipt_binding","normalized_result","source_absence"))
                        put("cold_proof_"+phase+"_ns",trace.nanos("cold_proof_"+phase))
                    put("intent_reuse",trace.count("cold_proof_intent_reuse"))
                    put("callback_trigger_statements",trace.count("schema_callback_trigger"))
                    put("account_hold_ns",trace.nanos("short_account_hold"))
                    put("actual_frame_measured",false)
                }.toString())
            }
        }
        assertEquals(20,candidate.size);assertEquals(20,old.size)
        assertTrue("G0 inner-proof cold regressed history=$history",candidate.sorted()[18]<=old.sorted()[18])
        assertTrue("G0 inner-proof cold history=$history P95=${candidate.sorted()[18]} exceeds 2s",candidate.sorted()[18]<=2_000_000_000L)
    }
}
