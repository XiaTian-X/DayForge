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

/** Reopened connections, not force-stopped processes. Same image and original validators,
 * alternating old/new order. Cold budget remains 2s; keep every first and slow sample. */
@RunWith(AndroidJUnit4::class)
class G0ColdCertificateCostTest : G0SealedDomainFixture() {
    @Test(timeout=900_000) fun eightAcceptedRequestsColdCertificate() = measure(8)
    @Test(timeout=900_000) fun eightyAcceptedRequestsColdCertificate() = measure(80)
    private fun measure(history:Int) = runBlocking<Unit> {
        register()
        val (http,_)=channel { input -> successReply(input,revision=(if(input.path.endsWith("/identity")) 0L else
            wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0L)+1) }
        repeat(history) { i ->
            val row=editMetric("Seed $i")
            val delivery=requireNotNull(sender(http).sendOperation(access(),row.operationId))
            assertEquals(NextOperationAcceptance.COMMITTED,sender(http).acceptOperation(delivery))
        }
        val image=storage.snapshotClosedDatabase()
        val values=mutableListOf<Long>()
        repeat(20) { sample ->
            for(shared in if(sample%2==0) listOf(false,true) else listOf(true,false)) {
                storage.restoreClosedDatabase(image);freshGateForColdOpen()
                storage.isolatedBuilder={ context -> Room.databaseBuilder(context,HabitDatabase::class.java,"habit_database")
                    .addCallback(SyncSchemaCallback).openHelperFactory(domainGate.factory).build() }
                storage.reopen()
                val current=access()
                val start=SystemClock.elapsedRealtimeNanos()
                val expected=trustedDomainSchema()
                val schemaEnd=SystemClock.elapsedRealtimeNanos()
                val store=G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid,shareColdEvidence=shared)
                val trace=G0PhaseTrace(driverTiming=false)
                trace.run { store.initialize(current,expected) }
                val ready=SystemClock.elapsedRealtimeNanos()
                val view=store.read(current)
                assertEquals("Seed ${history-1}",view.name);assertEquals(0,view.pending)
                val read=SystemClock.elapsedRealtimeNanos()
                if(shared) values += ready-start
                Log.i("G0ColdCertificate",buildJsonObject {
                    put("history",history);put("sample",sample);put("shared",shared)
                    put("schema_ns",schemaEnd-start);put("certificate_ns",ready-schemaEnd)
                    put("ready_ns",ready-start);put("first_read_ns",read-start)
                    put("validation_ns",trace.nanos("cold_validation"));put("index_ns",trace.nanos("cold_index"))
                    put("journal_ns",trace.nanos("cold_journal"));put("decryptions",trace.count("decrypt_access"))
                }.toString())
            }
        }
        val sorted=values.sorted();assertEquals(20,sorted.size)
        assertTrue("G0 cold certificate history=$history P95=${sorted[18]} exceeds 2s",sorted[18]<=2_000_000_000L)
    }
}
