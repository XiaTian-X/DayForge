package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 20 alternating pairs per size, same closed image. No UI hiding, no lowered old budgets. */
@RunWith(AndroidJUnit4::class)
class G0PersistentCheckpointCostTest : G0PersistentCheckpointFixture() {
    @Test(timeout=900_000) fun eightHistoryColdRecoveryAndWriteMaintenance()=measure(8)
    @Test(timeout=900_000) fun eightyHistoryColdRecoveryAndWriteMaintenance()=measure(80)
    private fun measure(history:Int)=runBlocking<Unit> {
        seed(history);val image=storage.snapshotClosedDatabase();cold()
        val issue=checkpoint();val issuedAt=SystemClock.elapsedRealtimeNanos()
        certify(issue);val issueEnd=SystemClock.elapsedRealtimeNanos();assertTrue(issue.persisted)
        val packet=checkpointFile.readBytes()
        Log.i("G0CheckpointIssue",buildJsonObject { put("history",history);put("total_ns",issueEnd-issuedAt);put("bytes",packet.size) }.toString())
        val coldControl=mutableListOf<Long>();val coldCandidate=mutableListOf<Long>()
        val writeControl=mutableListOf<Long>();val writeCandidate=mutableListOf<Long>();val resources=mutableListOf<Long>()
        repeat(20) { sample ->
            for(enabled in if(sample%2==0) listOf(false,true) else listOf(true,false)) {
                storage.restoreClosedDatabase(image);cold();checkpointFile.writeBytes(packet)
                val current=access();val cp=if(enabled) checkpoint() else null
                val trace=G0PhaseTrace(driverTiming=false);val started=SystemClock.elapsedRealtimeNanos()
                val candidate=store(cp)
                trace.run { candidate.initialize(current,trustedDomainSchema(true)) }
                val ready=SystemClock.elapsedRealtimeNanos();val view=candidate.read(current);val read=SystemClock.elapsedRealtimeNanos()
                assertEquals("Seed ${history-1}",view.name);assertEquals(0,view.pending)
                assertEquals(2,trace.count("decrypt_access"));assertEquals(if(enabled) history else 0,trace.count("checkpoint_reuse"))
                assertEquals(if(enabled) 0 else history,trace.count("cold_origin_proof"));if(enabled) assertTrue(cp!!.hit)
                if(enabled) coldCandidate+=ready-started else coldControl+=ready-started
                Log.i("G0CheckpointCold",buildJsonObject {
                    put("history",history);put("sample",sample);put("enabled",enabled);put("ready_ns",ready-started);put("first_read_ns",read-started)
                    put("capture_ns",trace.nanos("checkpoint_capture"));put("recapture_ns",trace.nanos("checkpoint_recapture"));put("integrity_ns",trace.nanos("checkpoint_integrity"));put("verify_ns",trace.nanos("checkpoint_verify"));put("reuse_count",trace.count("checkpoint_reuse"))
                    put("reuse_ns",trace.nanos("checkpoint_reuse"));put("origin_capture_ns",trace.nanos("cold_origin_capture"));put("typed_row_reuse",trace.count("checkpoint_typed_row_reuse"));put("rows",cp?.scannedRows ?: 0);put("account_hold_max_ns",trace.spans.filter { it.phase=="short_account_hold" }.maxOf { it.end-it.start })
                    put("proof_ns",trace.nanos("cold_origin_proof"));put("validation_ns",trace.nanos("cold_validation"));put("journal_ns",trace.nanos("cold_journal"))
                    put("cold_publish_ns",trace.nanos("checkpoint_publish"));put("actual_frame_measured",false)
                }.toString())
            }
        }
        repeat(20) { sample ->
            for(refresh in if(sample%2==0) listOf(false,true) else listOf(true,false)) {
                storage.restoreClosedDatabase(image);cold();checkpointFile.writeBytes(packet)
                val cp=checkpoint();val candidate=certify(cp);assertTrue(cp.hit)
                val trace=G0PhaseTrace(driverTiming=false);var committed=0L;val start=SystemClock.elapsedRealtimeNanos()
                trace.run {
                    requireNotNull(candidate.edit("Write $sample","checkpoint cost",id(900+sample),millis+2000))
                    committed=SystemClock.elapsedRealtimeNanos()
                    if(refresh) assertTrue(candidate.refreshCheckpoint())
                }
                val finished=SystemClock.elapsedRealtimeNanos();assertEquals("Write $sample",candidate.read(access()).name)
                val resource=trace.spans.filter { it.phase=="domain_sql_hold" }.maxOf { it.end-it.start }
                if(refresh) { writeCandidate+=finished-start;resources+=resource } else writeControl+=committed-start
                Log.i("G0CheckpointWrite",buildJsonObject {
                    put("history",history);put("sample",sample);put("refresh",refresh);put("commit_ns",committed-start)
                    put("maintenance_ns",finished-committed);put("total_ns",finished-start);put("capture_ns",trace.nanos("checkpoint_capture"))
                    put("publish_ns",trace.nanos("checkpoint_publish"));put("sql_hold_max_ns",resource)
                    put("account_hold_max_ns",trace.spans.filter { it.phase=="short_account_hold" }.maxOf { it.end-it.start })
                    put("decrypt_access_count",trace.count("decrypt_access"));put("checkpoint_bytes",checkpointFile.length());put("actual_frame_measured",false)
                }.toString())
            }
        }
        assertEquals(20,coldCandidate.size);assertEquals(20,writeCandidate.size)
        // Aggregate failures after all pairs so a cold failure does not discard write evidence.
        val failures=buildList {
            if(coldCandidate.sorted()[18]>coldControl.sorted()[18]) add("cold P95 regressed")
            if(coldCandidate.sorted()[18]>2_000_000_000L) add("cold P95 exceeds 2s")
            if(writeCandidate.sorted()[18]>500_000_000L) add("write plus checkpoint P95 exceeds 500ms")
            if(resources.any { it>150_000_000L }) add("warm SQL resource exceeds 150ms")
        }
        assertTrue("G0 persistent checkpoint history=$history: $failures",failures.isEmpty())
    }
}
