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

/** Same cold chain/gates. Twenty balanced five-arm blocks separate rule caching from inner
 * timing, with the original decoder as fifth arm. All outer timing remains enabled. */
@RunWith(AndroidJUnit4::class)
class G0CodecFactorCostTest : G0SealedDomainFixture() {
    @Test(timeout=900_000) fun eightCompletedRequestsWithPairedInnerProof() = measure(8)
    @Test(timeout=900_000) fun eightyCompletedRequestsWithPairedInnerProof() = measure(80)
    private data class Mode(val name:String,val refined:Boolean,val cache:Boolean,val timing:Boolean)
    private val modes=listOf(Mode("legacy",false,true,true),Mode("uncached_quiet",true,false,false),
        Mode("uncached_timed",true,false,true),Mode("cached_quiet",true,true,false),Mode("cached_timed",true,true,true))
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
            val rotated=modes.indices.map { modes[(it+sample)%modes.size] }
            for((position,mode) in (if((sample/5)%2==0) rotated else rotated.reversed()).withIndex()) {
                val batched=mode.refined
                storage.restoreClosedDatabase(image);freshGateForColdOpen()
                storage.isolatedBuilder={ context -> Room.databaseBuilder(context,HabitDatabase::class.java,"habit_database")
                    .addCallback(G0SchemaOpeningCallback(true)).openHelperFactory(domainGate.factory).build() }
                storage.reopen();val current=access()
                val start=SystemClock.elapsedRealtimeNanos()
                val referenceTrace=G0PhaseTrace(driverTiming=false)
                val expected=referenceTrace.run { trustedDomainSchema(batchedOpening=true) }
                val schemaEnd=SystemClock.elapsedRealtimeNanos()
                val store=G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid,blockColdEvidence=true,innerColdProof=true,refineColdDependencies=true,refineColdDecoding=batched,cacheCodecRules=mode.cache,measureCodecStages=mode.timing)
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
                if(batched) { assertEquals(history,trace.count("cold_proof_dependency_binding"));assertEquals(0,trace.count("cold_proof_lineage_fallback"));assertEquals(history-(history+15)/16,trace.count("cold_normalize_appearance_reuse")) }
                if(batched) { assertEquals(if(mode.timing) history*3 else 0,trace.count("cold_codec_syntax"));assertEquals(0,trace.count("cold_proof_restart")) }
                if(mode.name=="cached_timed") candidate+=ready-start
                if(mode.name=="legacy") old+=ready-start
                Log.i("G0CodecFactorCost",buildJsonObject {
                    put("mode",mode.name);put("cache",mode.cache);put("codec_timing",mode.timing);put("position",position);put("history",history);put("sample",sample);put("refined",batched);put("evidence_blocks",trace.count("cold_evidence_block"));put("evidence_batches",trace.count("cold_evidence_batch"))
                    put("ready_ns",ready-start);put("first_read_ns",read-start);put("schema_ns",schemaEnd-start)
                    put("certificate_ns",ready-schemaEnd)
                    for(phase in listOf("schema_callback_total","schema_callback_body","schema_callback_trigger","schema_callback_begin","schema_callback_end"))
                        put("reference_"+phase+"_ns",referenceTrace.nanos(phase))
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
        assertTrue("G0 codec-factor cold regressed history=$history",candidate.sorted()[18]<=old.sorted()[18])
        assertTrue("G0 codec-factor cold history=$history P95=${candidate.sorted()[18]} exceeds 2s",candidate.sorted()[18]<=2_000_000_000L)
    }
}
