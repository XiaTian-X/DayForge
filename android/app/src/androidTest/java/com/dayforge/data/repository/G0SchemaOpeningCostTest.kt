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
class G0SchemaOpeningCostTest : G0SealedDomainFixture() {
    @Test(timeout=900_000) fun eightCompletedRequestsWithPairedOpening() = measure(8)
    @Test(timeout=900_000) fun eightyCompletedRequestsWithPairedOpening() = measure(80)
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
                    .addCallback(G0SchemaOpeningCallback(batched)).openHelperFactory(domainGate.factory).build() }
                storage.reopen();val current=access()
                val start=SystemClock.elapsedRealtimeNanos()
                val expected=trustedDomainSchema(batchedOpening=batched)
                val schemaEnd=SystemClock.elapsedRealtimeNanos()
                val store=G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid)
                val trace=G0PhaseTrace(driverTiming=false)
                trace.run { store.initialize(current,expected) }
                val ready=SystemClock.elapsedRealtimeNanos()
                val view=store.read(current)
                val read=SystemClock.elapsedRealtimeNanos()
                assertEquals("Seed ${history-1}",view.name);assertEquals(0,view.pending)
                assertEquals(2,trace.count("decrypt_access"))
                assertEquals(expected.count { it[0]=="main" && it[1]=="trigger" },trace.count("schema_callback_trigger"))
                assertEquals(1,trace.count("schema_callback_control"))
                assertEquals(if(batched) 1 else 0,trace.count("schema_callback_begin"))
                if(batched) candidate+=ready-start else old+=ready-start
                Log.i("G0SchemaOpeningCost",buildJsonObject {
                    put("history",history);put("sample",sample);put("batched",batched)
                    put("ready_ns",ready-start);put("first_read_ns",read-start);put("schema_ns",schemaEnd-start)
                    put("certificate_ns",ready-schemaEnd)
                    for(phase in listOf("cold_open","cold_entry_auth","cold_room_wait","cold_validation","cold_journal",
                        "cold_final_auth","cold_tx_finish","schema_callback_total","schema_callback_body",
                        "schema_callback_trigger","schema_callback_control","schema_callback_begin","schema_callback_end"))
                        put(phase+"_ns",trace.nanos(phase))
                    put("callback_trigger_statements",trace.count("schema_callback_trigger"))
                    put("account_hold_ns",trace.nanos("short_account_hold"))
                    put("actual_frame_measured",false)
                }.toString())
            }
        }
        assertEquals(20,candidate.size);assertEquals(20,old.size)
        assertTrue("G0 batched cold regressed history=$history",candidate.sorted()[18]<=old.sorted()[18])
        assertTrue("G0 batched cold history=$history P95=${candidate.sorted()[18]} exceeds 2s",candidate.sorted()[18]<=2_000_000_000L)
    }
}
