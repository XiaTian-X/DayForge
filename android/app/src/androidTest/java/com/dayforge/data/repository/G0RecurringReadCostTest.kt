package com.dayforge.data.repository

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real full validators and fresh auth; read/publish cost only, not a screen or ACK benchmark. */
@RunWith(AndroidJUnit4::class)
class G0RecurringReadCostTest : G0RecurringReadFixture() {
    private suspend fun measure(history:Int) {
        val ids=seed(history);val before=state();sealDomain();val reader=owner()
        val holds=mutableListOf<Long>()
        repeat(20) { sample ->
            val trace=G0PhaseTrace(driverTiming=false);val auth=decryptCount.get()
            trace.run {
                val batch=trace.stage("recurring_read") { reader.read(ids,ZonedDateTime.now()) }
                assertEquals(history.toLong()*(history+3)/2,batch.content.counts.getValue(ids[0]).todayQuantity)
                assertEquals(1L,batch.content.checks.getValue(ids[1]).todayQuantity)
                assertTrue(trace.stage("recurring_publish") { reader.publish(batch) { assertTrue(it.valid()) } })
            }
            val hold=trace.spans.filter { it.phase=="domain_sql_hold" }.maxOf { it.end-it.start };holds+=hold
            Log.i("G0RecurringCost",buildJsonObject {
                put("history",history);put("sample",sample);put("read_ns",trace.nanos("recurring_read"))
                put("publish_ns",trace.nanos("recurring_publish"));put("total_ns",trace.nanos("operation"))
                put("max_sql_hold_ns",hold);put("decryptions",decryptCount.get()-auth)
                put("decrypt_ns",trace.spans.filter { it.phase.startsWith("decrypt_") }.sumOf { it.end-it.start })
            }.toString())
        }
        reader.close();assertEquals(before,state())
        Log.i("G0RecurringTerminal",buildJsonObject { put("history",history);put("samples",20);put("persistent_state_unchanged",true) }.toString())
        // Original shared-resource budget retained. Failure is evidence against full-scan UI admission.
        assertTrue("Recurring full proof exceeds original 150 ms resource budget",holds.max()<=150_000_000)
    }
    @Test fun eightFactsReadAndPublishTwentyTimes()=runBlocking<Unit> { measure(8) }
    @Test fun eightyFactsReadAndPublishTwentyTimes()=runBlocking<Unit> { measure(80) }
}
