package com.dayforge.data.repository

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Same sealed full validator and image; no persistence/permission cache or mixed ACK claim. */
@RunWith(AndroidJUnit4::class)
class G0RecurringEvidenceCostTest : G0RecurringReadFixture() {
    private suspend fun compare(history:Int) {
        val ids=seed(history);val now=ZonedDateTime.now();val before=state()
        val expectedCount=CountHistoryReader(db,tokens,sessions).read(requireNotNull(db.habitDao().getHabitById(ids[0])),now.toLocalDate())
        val expectedCheck=CheckHistoryReader(db,tokens,sessions).read(requireNotNull(db.habitDao().getHabitById(ids[1])),now.toLocalDate())
        val image=storage.snapshotClosedDatabase();val warmHolds=mutableListOf<Long>();var warmHits=0
        listOf(false,true,true,false,true,false,false,true).forEachIndexed { block,reuse ->
            storage.restoreClosedDatabase(image);freshGateForColdOpen(recurringReads=true);storage.reopen();sealDomain()
            val reader=G0RecurringReadOwner(db,tokens,sessions,authority,domainGate,sharedScope=true,reuseEvidence=reuse)
            // First complete validation is measured and retained separately, never a hidden warm-up.
            repeat(6) { offset ->
                val trace=G0PhaseTrace(driverTiming=false);val auth=decryptCount.get();val hits=reader.reuseHits
                trace.run {
                    val batch=trace.stage("evidence_read") { reader.read(ids,now) }
                    assertEquals(expectedCount,batch.content.counts.getValue(ids[0]))
                    assertEquals(expectedCheck,batch.content.checks.getValue(ids[1]))
                    assertTrue(trace.stage("evidence_publish") { reader.publish(batch) { assertTrue(it.valid()) } })
                }
                val hit=reader.reuseHits>hits;val decryptions=decryptCount.get()-auth;assertEquals(4,decryptions)
                val owned=trace.spans.filter { it.phase=="domain_sql_hold" }.sortedBy { it.start }
                val begins=trace.spans.filter { it.phase=="tx_begin_outer" }.sortedBy { it.start }
                val ends=trace.spans.filter { it.phase=="tx_end_outer" }.sortedBy { it.start }
                val expectedTransactions=if(hit) 2 else 3
                assertEquals(expectedTransactions,owned.size);assertEquals(expectedTransactions,begins.size);assertEquals(expectedTransactions,ends.size)
                for(i in owned.indices) assertTrue("Resource timer must contain actual engine begin/end",owned[i].start<=begins[i].start && owned[i].end>=ends[i].end)
                val hold=owned.maxOf { it.end-it.start }
                if(reuse && offset>0) { warmHolds+=hold;if(hit) warmHits++ }
                Log.i("G0RecurringEvidenceCost",buildJsonObject {
                    put("history",history);put("block",block);put("offset",offset);put("reuse",reuse);put("hit",hit)
                    put("first_validation",offset==0);put("proof_queries",reader.proofQueries);put("capture_failure",reader.lastCaptureFailure)
                    put("read_ns",trace.nanos("evidence_read"));put("publish_ns",trace.nanos("evidence_publish"));put("total_ns",trace.nanos("operation"))
                    put("decryptions",decryptions);put("decrypt_ns",trace.spans.filter { it.phase.startsWith("decrypt_") }.sumOf { it.end-it.start })
                    put("max_sql_hold_ns",hold);put("owned_interval_count",owned.size);put("engine_intervals_covered",true)
                    put("tx_end_outer_ns",trace.nanos("tx_end_outer"))
                }.toString())
            }
            reader.close();assertEquals(before,state())
        }
        Log.i("G0RecurringEvidenceTerminal",buildJsonObject {
            put("history",history);put("baseline_warm_samples",20);put("candidate_warm_samples",20);put("candidate_warm_hits",warmHits)
            put("first_validation_samples",8);put("all_results_equal",true);put("all_persistent_tables_unchanged",true)
        }.toString())
        assertEquals("Bounded evidence did not cover this history; preserve full fallback",20,warmHits)
        assertTrue("Evidence reuse still exceeds original 150 ms resource budget",warmHolds.max()<=150_000_000)
    }
    @Test fun eightFactsFullVersusEvidence()=runBlocking<Unit> { compare(8) }
    @Test fun eightyFactsFullVersusEvidence()=runBlocking<Unit> { compare(80) }
}
