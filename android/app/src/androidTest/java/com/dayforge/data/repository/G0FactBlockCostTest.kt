package com.dayforge.data.repository

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Same persistent image, alternating order. Measures complete read to DTO, NOT a screen,
 * writer, commit result or closed SQL admission. Real original commands/ACKs seed history. */
@RunWith(AndroidJUnit4::class)
class G0FactBlockCostTest:G0RecurringActionFixture() {
    @Test(timeout=900_000) fun eightAcceptedFactsPairedFullReads()=runBlocking<Unit> { measure(8) }
    @Test(timeout=900_000) fun eightyAcceptedFactsPairedFullReads()=runBlocking<Unit> { measure(80) }
    private fun now()=android.os.SystemClock.elapsedRealtimeNanos()
    private fun p95(values:List<Long>)=values.sorted()[kotlin.math.ceil(values.size*.95).toInt()-1]
    private fun hold(trace:G0PhaseTrace):Long {
        val all=synchronized(trace.spans) { trace.spans.toList() }
        val begin=all.filter { it.phase=="tx_begin_outer" };val end=all.filter { it.phase=="tx_end_outer" }
        assertEquals(1,begin.size);assertEquals(1,end.size)
        return end.single().end-begin.single().start
    }
    private suspend fun measure(history:Int) {
        configureAction(target=1_000_000);val actions=actions()
        val (http,_)=channel { successReply(it) };val requests=sender(http)
        repeat(history) {
            increment(actions)
            val row=db.syncOutboxDao().getAll().single()
            val delivery=requireNotNull(requests.sendOperation(access(),row.operationId))
            assertEquals(NextOperationAcceptance.COMMITTED,requests.acceptOperation(delivery))
        }
        assertEquals(history,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
        val current=requireNotNull(db.habitDao().getHabitById(habit.id))
        val reference=RecurringCompletionEvidenceReader(db,tokens,sessions)
        val candidate=G0BlockCompletionReader(db,tokens,sessions)
        val before=pageNativeState();val times=mutableListOf<Long>();val holds=mutableListOf<Long>()
        try {
            val coldAt=now();val cold=candidate.read(current);val coldElapsed=now()-coldAt
            assertEquals(reference.read(current),cold)
            Log.i("G0FactBlockCold", "{\"history\":$history,\"ns\":$coldElapsed,\"entries\":${candidate.blocks.entries}}")
            repeat(20) { sample ->
                val outputs=mutableMapOf<Boolean,RecurringCompletionEvidence>()
                val rows=mutableMapOf<Boolean,Triple<Long,Long,Int>>()
                val hits=candidate.blocks.hits
                for(isCandidate in if(sample%2==0) listOf(false,true) else listOf(true,false)) {
                    val trace=G0PhaseTrace(driverTiming=false);val at=now()
                    outputs[isCandidate]=trace.run { if(isCandidate) candidate.read(current) else reference.read(current) }
                    rows[isCandidate]=Triple(now()-at,hold(trace),trace.count("decrypt_access"))
                    assertEquals(0,trace.count("decrypt_refresh"))
                }
                assertEquals(outputs[false],outputs[true]);assertEquals(history,outputs.getValue(true).completions.size)
                val original=rows.getValue(false);val blocked=rows.getValue(true)
                assertEquals(original.third,blocked.third);assertTrue(blocked.third>=2)
                assertTrue(candidate.blocks.entries<=512);assertTrue(candidate.blocks.retainedBytes<=2_097_152)
                times+=blocked.first;holds+=blocked.second
                Log.i("G0FactBlockCost",buildJsonObject {
                    put("history",history);put("sample",sample);put("candidate_first",sample%2==1)
                    put("reference_ns",original.first);put("candidate_ns",blocked.first)
                    put("reference_hold_ns",original.second);put("candidate_hold_ns",blocked.second)
                    put("access_decryptions_each",blocked.third);put("hits",candidate.blocks.hits-hits)
                    put("entries",candidate.blocks.entries);put("retained_weight",candidate.blocks.retainedBytes)
                    put("screen",false);put("sealed",false);put("includes_write",false)
                }.toString())
            }
            assertEquals(before,pageNativeState())
        } finally { candidate.close() }
        storage.reopen()
        val recovered=G0BlockCompletionReader(db,tokens,sessions)
        try {
            val original=RecurringCompletionEvidenceReader(db,tokens,sessions).read(current)
            assertEquals(original,recovered.read(current));assertEquals(history,original.completions.size)
        } finally { recovered.close() }
        val failures=buildList {
            if(p95(times)>700_000_000L) add("full read/result P95 >700ms")
            if(holds.max()>150_000_000L) add("full read transaction max >150ms")
        }
        assertTrue(failures.joinToString(";"),failures.isEmpty())
    }
}
