package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.SyncSchemaCallback
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Attribution only, same 2s total gate and all 20 reopened samples retained. No UI claim. */
@RunWith(AndroidJUnit4::class)
class G0ColdBreakdownTest : G0SealedDomainFixture() {
    @Test(timeout=600_000) fun eightCompletedRequestsWithCompleteColdStageAccounting() = runBlocking<Unit> {
        register()
        val (http,_)=channel { input -> successReply(input,revision=(if(input.path.endsWith("/identity")) 0L else
            wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0L)+1) }
        repeat(8) { i ->
            val row=editMetric("Seed $i")
            val delivery=requireNotNull(sender(http).sendOperation(access(),row.operationId))
            assertEquals(NextOperationAcceptance.COMMITTED,sender(http).acceptOperation(delivery))
        }
        val image=storage.snapshotClosedDatabase();val values=mutableListOf<Long>()
        repeat(20) { sample ->
            storage.restoreClosedDatabase(image);freshGateForColdOpen()
            storage.isolatedBuilder={ context -> Room.databaseBuilder(context,HabitDatabase::class.java,"habit_database")
                .addCallback(SyncSchemaCallback).openHelperFactory(domainGate.factory).build() }
            storage.reopen();val current=access()
            val start=SystemClock.elapsedRealtimeNanos();val expected=trustedDomainSchema()
            val schemaEnd=SystemClock.elapsedRealtimeNanos()
            val store=G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid)
            val trace=G0PhaseTrace(driverTiming=false)
            trace.run { store.initialize(current,expected) }
            val ready=SystemClock.elapsedRealtimeNanos()
            val view=store.read(current);assertEquals("Seed 7",view.name);assertEquals(0,view.pending)
            values+=ready-start
            val phases=listOf("cold_entry_auth","cold_permit","cold_open","cold_room_wait",
                "cold_validation","cold_journal","cold_final_auth","cold_tx_finish","cold_publish")
            phases.forEach { assertEquals("one complete $it",1,trace.count(it)) }
            assertEquals(2,trace.count("decrypt_access"))
            Log.i("G0ColdBreakdown",buildJsonObject {
                put("sample",sample);put("ready_ns",ready-start);put("schema_ns",schemaEnd-start)
                put("certificate_ns",ready-schemaEnd);put("first_read_ns",SystemClock.elapsedRealtimeNanos()-start)
                phases.forEach { put(it+"_ns",trace.nanos(it)) }
                put("account_wait_ns",trace.nanos("short_account_wait"));put("account_hold_ns",trace.nanos("short_account_hold"))
                put("driver_begin_ns",trace.nanos("tx_begin_outer"));put("driver_end_ns",trace.nanos("tx_end_outer"))
                put("unattributed_ns",ready-schemaEnd-phases.sumOf { trace.nanos(it) })
            }.toString())
        }
        assertEquals(20,values.size)
        assertTrue("G0 cold P95=${values.sorted()[18]} exceeds 2s",values.sorted()[18]<=2_000_000_000L)
    }
}
