package com.dayforge.data.repository

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Both modes are sealed. Actual original validators, same image, ABBA/BAAB five-sample blocks. */
@RunWith(AndroidJUnit4::class)
class G0SharedRecurringReadCostTest : G0RecurringReadFixture() {
    private suspend fun compare(history:Int) {
        val ids=seed(history);val now=ZonedDateTime.now();val before=state()
        val expectedCount=CountHistoryReader(db,tokens,sessions).read(requireNotNull(db.habitDao().getHabitById(ids[0])),now.toLocalDate())
        val expectedCheck=CheckHistoryReader(db,tokens,sessions).read(requireNotNull(db.habitDao().getHabitById(ids[1])),now.toLocalDate())
        val image=storage.snapshotClosedDatabase();val holds=mutableListOf<Long>()
        listOf(false,true,true,false,true,false,false,true).forEachIndexed { block,shared ->
            storage.restoreClosedDatabase(image);freshGateForColdOpen(recurringReads=true);storage.reopen();sealDomain()
            val reader=G0RecurringReadOwner(db,tokens,sessions,authority,domainGate,sharedScope=shared)
            repeat(5) { offset ->
                val trace=G0PhaseTrace(driverTiming=false);val auth=decryptCount.get()
                trace.run {
                    val batch=trace.stage("shared_read") { reader.read(ids,now) }
                    assertEquals(expectedCount,batch.content.counts.getValue(ids[0]))
                    assertEquals(expectedCheck,batch.content.checks.getValue(ids[1]))
                    assertTrue(trace.stage("shared_publish") { reader.publish(batch) { assertTrue(it.valid()) } })
                }
                val decryptions=decryptCount.get()-auth;assertEquals(if(shared) 4 else 8,decryptions)
                val owned=trace.spans.filter { it.phase=="domain_sql_hold" }.sortedBy { it.start }
                val begins=trace.spans.filter { it.phase=="tx_begin_outer" }.sortedBy { it.start }
                val ends=trace.spans.filter { it.phase=="tx_end_outer" }.sortedBy { it.start }
                val expectedTransactions=if(shared) 3 else 2
                assertEquals(expectedTransactions,owned.size);assertEquals(expectedTransactions,begins.size)
                assertEquals(expectedTransactions,ends.size)
                for(i in owned.indices) {
                    assertTrue("Resource timer must contain actual engine begin/end",owned[i].start<=begins[i].start && owned[i].end>=ends[i].end)
                }
                val hold=owned.maxOf { it.end-it.start }
                if(shared) holds+=hold
                Log.i("G0SharedRecurringCost",buildJsonObject {
                    put("history",history);put("block",block);put("offset",offset);put("shared",shared)
                    put("read_ns",trace.nanos("shared_read"));put("publish_ns",trace.nanos("shared_publish"))
                    put("total_ns",trace.nanos("operation"));put("decryptions",decryptions)
                    put("decrypt_ns",trace.spans.filter { it.phase.startsWith("decrypt_") }.sumOf { it.end-it.start })
                    put("max_sql_hold_ns",hold);put("owned_interval_count",owned.size);put("engine_intervals_covered",true);put("tx_outer_count",trace.count("tx_begin_outer"))
                    put("tx_end_outer_ns",trace.nanos("tx_end_outer"))
                }.toString())
            }
            reader.close();assertEquals(before,state())
        }
        Log.i("G0SharedRecurringTerminal",buildJsonObject {
            put("history",history);put("legacy_samples",20);put("shared_samples",20)
            put("all_results_equal",true);put("all_persistent_tables_unchanged",true)
        }.toString())
        assertTrue("Shared original scope still exceeds original 150 ms resource budget",holds.max()<=150_000_000)
    }
    @Test fun eightFactsLegacyVersusShared()=runBlocking<Unit> { compare(8) }
    @Test fun eightyFactsLegacyVersusShared()=runBlocking<Unit> { compare(80) }
}
