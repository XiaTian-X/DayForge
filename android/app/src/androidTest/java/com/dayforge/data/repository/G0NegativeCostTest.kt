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

/** Paired complete cold chain. Both keep the strict codec and every business proof; only
 * same-session exact negative batching differs. Reference/rows/journal are identical. No prewarming. */
@RunWith(AndroidJUnit4::class)
class G0NegativeCostTest : G0SealedDomainFixture() {
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
                storage.restoreClosedDatabase(image);freshGateForColdOpen(compactJournal=true)
                storage.isolatedBuilder={ context -> Room.databaseBuilder(context,HabitDatabase::class.java,"habit_database")
                    .addCallback(G0SchemaOpeningCallback(true)).openHelperFactory(domainGate.factory).build() }
                storage.reopen();val current=access()
                val start=SystemClock.elapsedRealtimeNanos()
                val reference=G0PhaseTrace(driverTiming=false)
                val expected=reference.run { trustedDomainSchema(batchedOpening=true,inMemory=true) }
                val schemaEnd=SystemClock.elapsedRealtimeNanos()
                val store=G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid,blockColdEvidence=true,innerColdProof=true,refineColdDependencies=true,refineColdDecoding=true,reuseColdRows=true,batchColdNegatives=batched)
                val trace=G0PhaseTrace(driverTiming=false)
                trace.run { store.initialize(current,expected) }
                val ready=SystemClock.elapsedRealtimeNanos()
                val view=store.read(current)
                val read=SystemClock.elapsedRealtimeNanos()
                assertEquals("Seed ${history-1}",view.name);assertEquals(0,view.pending)
                assertEquals(2,trace.count("decrypt_access"))
                assertEquals(history,trace.count("cold_origin_proof"))
                run { assertEquals(history,trace.count("cold_proof_lineage")); assertEquals(history,trace.count("cold_proof_wire")); assertEquals(history,trace.count("cold_proof_source_absence")) }
                assertEquals(expected.count { it[0]=="main" && it[1]=="trigger" },trace.count("schema_callback_trigger"))
                assertEquals(1,trace.count("schema_callback_control"))
                assertEquals(1,trace.count("schema_callback_begin"))
                assertEquals((history+15)/16,trace.count("cold_evidence_block"))
                run { assertEquals(history,trace.count("cold_proof_dependency_binding"));assertEquals(0,trace.count("cold_proof_lineage_fallback"));assertEquals(history-(history+15)/16,trace.count("cold_normalize_appearance_reuse")) }
                run { assertEquals(history*3,trace.count("cold_codec_syntax"));assertEquals(0,trace.count("cold_proof_restart")) }
                assertEquals(G0DomainSqlCatalog.columns.size*3,trace.count("journal_trigger_ddl"))
                assertEquals(1,trace.count("journal_payload_ddl"))
                assertEquals(((history+15)/16)*3,trace.count("cold_rows_table"))
                assertEquals(if(batched) ((history+15)/16)*5 else 0,trace.count("cold_negative_query"))
                assertEquals(if(batched) history*5 else 0,trace.count("cold_negative_hit"))
                if(batched) candidate+=ready-start else old+=ready-start
                Log.i("G0NegativeCost",buildJsonObject {
                    put("negative_load_ns",trace.nanos("cold_negative_load"));put("negative_query_ns",trace.nanos("cold_negative_query"));put("negative_query_count",trace.count("cold_negative_query"));put("negative_hit_count",trace.count("cold_negative_hit"));put("optimized",batched);put("history",history);put("sample",sample);put("refined",batched);put("evidence_blocks",trace.count("cold_evidence_block"));put("evidence_batches",trace.count("cold_evidence_batch"))
                    put("ready_ns",ready-start);put("first_read_ns",read-start);put("schema_ns",schemaEnd-start)
                    put("certificate_ns",ready-schemaEnd)
                    for(phase in listOf("builder","open","snapshot","close","delete")) put("reference_"+phase+"_ns",reference.nanos("reference_$phase"))
                    for(phase in listOf("schema_callback_total","schema_callback_body","schema_callback_trigger","schema_callback_begin","schema_callback_end")) put("reference_"+phase+"_ns",reference.nanos(phase))
                    for(phase in listOf("schema","tables","columns","sql_build","payload_ddl","trigger_ddl")) put("journal_"+phase+"_ns",trace.nanos("journal_$phase"))
                    put("journal_trigger_count",trace.count("journal_trigger_ddl"));put("journal_payload_count",trace.count("journal_payload_ddl"))
                    put("cold_batch_audit_ns",trace.nanos("cold_batch_audit"));put("cold_rows_load_ns",trace.nanos("cold_rows_load"));put("cold_rows_tables",trace.count("cold_rows_table"))
                    for(phase in listOf("cold_prepare_history","cold_finish_history","cold_permit","cold_publish","cold_transaction","cold_open","cold_entry_auth","cold_room_wait","cold_validation","cold_journal",
                        "cold_final_auth","cold_tx_finish","cold_index","cold_origin_scope_decode","cold_origin_scope","cold_origin_proof","cold_origin_capture","schema_callback_total","schema_callback_body",
                        "schema_callback_trigger","schema_callback_control","schema_callback_begin","schema_callback_end"))
                        put(phase+"_ns",trace.nanos(phase))
                    for(phase in listOf("lineage","origin","intent","restart","receipt_hash","transmission_hash","transmission_row","wire","receipt_binding","normalized_result","source_absence"))
                        put("cold_proof_"+phase+"_ns",trace.nanos("cold_proof_"+phase))
                    for(phase in listOf("lineage_fallback","lineage_supersession","lineage_dependency","lineage_recovery","dependency_binding","result_decode","result_entity"))
                        put("cold_proof_"+phase+"_ns",trace.nanos("cold_proof_"+phase))
                    for(phase in listOf("header","fields","numbers","appearance")) put("cold_normalize_"+phase+"_ns",trace.nanos("cold_normalize_"+phase))
                    put("root_bindings",trace.count("cold_proof_dependency_binding"));put("lineage_fallbacks",trace.count("cold_proof_lineage_fallback"))
                    put("appearance_reuse",trace.count("cold_normalize_appearance_reuse"))
                    for(phase in listOf("syntax","tree","typed","dto")) {
                        put("cold_codec_"+phase+"_ns",trace.nanos("cold_codec_"+phase));put("cold_codec_"+phase+"_count",trace.count("cold_codec_"+phase))
                    }
                    put("intent_reuse",trace.count("cold_proof_intent_reuse"))
                    put("callback_trigger_statements",trace.count("schema_callback_trigger"))
                    put("account_hold_ns",trace.nanos("short_account_hold"))
                    put("actual_frame_measured",false)
                }.toString())
            }
        }
        assertEquals(20,candidate.size);assertEquals(20,old.size)
        assertTrue("G0 negative cold regressed history=$history",candidate.sorted()[18]<=old.sorted()[18])
        assertTrue("G0 negative cold history=$history P95=${candidate.sorted()[18]} exceeds 2s",candidate.sorted()[18]<=2_000_000_000L)
    }
}
