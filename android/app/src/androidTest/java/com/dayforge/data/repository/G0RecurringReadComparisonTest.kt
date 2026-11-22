package com.dayforge.data.repository

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic only. Original Dashboard is an unsealed reference, not a candidate for G0 admission.
 * Same file image/authentication, counterbalanced five-sample blocks; no production changes.
 * The previous 150 ms failure test remains unchanged. A passing comparison is NOT its replacement.
 */
@RunWith(AndroidJUnit4::class)
class G0RecurringReadComparisonTest : G0RecurringReadFixture() {
    private fun original()=G0DetailedDashboardReadRepository(db,tokens,sessions,
        ReplicaPresentationRepository(db,tokens,sessions),CountHistoryReader(db,tokens,sessions),CheckHistoryReader(db,tokens,sessions))
    private suspend fun compare(history:Int) {
        val ids=seed(history);val now=ZonedDateTime.now();val before=state()
        val expected=original().read(now)
        assertEquals(ids.toSet()+timerHabit.id,expected.habits.map { it.id }.toSet())
        assertEquals(timerHabit,expected.habits.single { it.id==timerHabit.id })
        assertEquals(setOf(ids[0]),expected.counts.keys);assertEquals(setOf(ids[1]),expected.checks.keys)
        val projectedHabits=expected.habits.filter { it.id in ids }.sortedBy { it.id }
        assertEquals(history.toLong()*(history+3)/2,expected.counts.getValue(ids[0]).todayQuantity)
        assertEquals(1L,expected.checks.getValue(ids[1]).todayQuantity)
        val image=storage.snapshotClosedDatabase()
        val order=listOf(false,true,true,false,true,false,false,true)
        order.forEachIndexed { block,sealed ->
            storage.restoreClosedDatabase(image);freshGateForColdOpen(recurringReads=true);storage.reopen()
            db.openHelper.writableDatabase
            if(sealed) sealDomain()
            val candidate=if(sealed) owner() else null
            val reference=if(!sealed) original() else null
            repeat(5) { offset ->
                val trace=G0PhaseTrace(driverTiming=false);val auth=decryptCount.get()
                trace.run {
                    if(candidate!=null) {
                        val batch=trace.stage("comparison_read") { candidate.read(ids,now) }
                        assertEquals(projectedHabits,batch.content.habits.sortedBy { it.id })
                        assertEquals(expected.counts,batch.content.counts);assertEquals(expected.checks,batch.content.checks)
                        assertTrue(trace.stage("comparison_publish") { candidate.publish(batch) { assertTrue(it.valid()) } })
                    } else {
                        val batch=trace.stage("comparison_read") { requireNotNull(reference).read(now) }
                        assertEquals(expected.habits,batch.habits);assertEquals(expected.counts,batch.counts)
                        assertEquals(expected.checks,batch.checks);assertEquals(expected.completions,batch.completions)
                        var delivered=false
                        assertTrue(trace.stage("comparison_publish") { requireNotNull(reference).publish(batch) { delivered=true } })
                        assertTrue(delivered)
                    }
                }
                Log.i("G0RecurringComparison",buildJsonObject {
                    put("history",history);put("block",block);put("offset",offset);put("sealed",sealed)
                    put("read_ns",trace.nanos("comparison_read"));put("publish_ns",trace.nanos("comparison_publish"))
                    put("total_ns",trace.nanos("operation"));put("decryptions",decryptCount.get()-auth)
                    put("decrypt_ns",trace.spans.filter { it.phase.startsWith("decrypt_") }.sumOf { it.end-it.start })
                    put("tx_outer_count",trace.count("tx_begin_outer"));put("tx_nested_count",trace.count("tx_begin_nested"))
                    put("tx_end_outer_ns",trace.nanos("tx_end_outer"))
                    put("g0_sql_hold_ns",trace.nanos("domain_sql_hold"))
                }.toString())
            }
            candidate?.close();assertEquals(before,state())
        }
        Log.i("G0RecurringComparisonTerminal",buildJsonObject {
            put("history",history);put("reference_samples",20);put("sealed_samples",20)
            put("all_persistent_tables_unchanged",true);put("all_results_equal",true)
        }.toString())
    }
    @Test fun eightFactsOriginalDashboardVersusSealedOwner()=runBlocking<Unit> { compare(8) }
    @Test fun eightyFactsOriginalDashboardVersusSealedOwner()=runBlocking<Unit> { compare(80) }
}
