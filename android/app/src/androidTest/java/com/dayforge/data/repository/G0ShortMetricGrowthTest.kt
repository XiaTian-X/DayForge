package com.dayforge.data.repository

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
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

/** Warm-owner controlled cost experiment. Seed history is cloned; each mode then runs the same
 * 20 sequential definition commands. Histories are seed+i, not a constant seed for every sample.
 * Cold validation is recorded separately and excluded from warm timing. No UI-frame claim.
 */
@RunWith(AndroidJUnit4::class)
class G0ShortMetricGrowthTest : G0SealedDomainFixture() {
    @Test(timeout=900_000) fun warmOwnerStartingWithEightCompletedRequests() = measure(8)
    @Test(timeout=900_000) fun warmOwnerStartingWithEightyCompletedRequests() = measure(80)
    private fun measure(history: Int) = runBlocking<Unit> {
        register(); val session = local()
        val guardedBuilder = storage.isolatedBuilder
        val originalBuilder: (android.content.Context) -> HabitDatabase = { context ->
            Room.databaseBuilder(context,HabitDatabase::class.java,"habit_database").addCallback(SyncSchemaCallback)
                .openHelperFactory(G0TimingFactory(endFaults)).build()
        }
        val (http,_) = channel { input ->
            if(input.path.endsWith("/identity")) successReply(input)
            else successReply(input,revision=(wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0L)+1)
        }
        repeat(history) { sample ->
            val row = editMetric("Seed $sample")
            val delivery = requireNotNull(sender(http).sendOperation(access(),row.operationId))
            assertEquals(NextOperationAcceptance.COMMITTED,sender(http).acceptOperation(delivery))
        }
        assertEquals(history,count("next_acceptances"))
        val image = storage.snapshotClosedDatabase()
        val holds = mutableListOf<Long>()
        for(candidate in if(history==8) listOf(false,true) else listOf(true,false)) {
            storage.restoreClosedDatabase(image)
            freshGateForColdOpen(); storage.isolatedBuilder=guardedBuilder; storage.reopen()
            val store = G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid)
            val coldStart = SystemClock.elapsedRealtimeNanos()
            store.initialize(access(),trustedDomainSchema())
            val mode = if(candidate) "INCREMENTAL" else "ORIGINAL_SPLIT"
            Log.i("G0ShortGrowth",buildJsonObject {
                put("stage","COLD_BASELINE");put("history",history);put("mode",mode)
                put("elapsed_ns",SystemClock.elapsedRealtimeNanos()-coldStart)
            }.toString())
            if(!candidate) { storage.isolatedBuilder=originalBuilder;storage.reopen() }
            val reference = G0ScheduledDomainProbe(db,tokens,sessions,authority,G0CommitScheduler(),splitAck=true)
            val currentAccess = access()
            repeat(20) { sample ->
                val battery = app.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                Log.i("G0ShortGrowth",buildJsonObject {
                    put("stage","ENV");put("history",history);put("sample",sample);put("mode",mode)
                    put("temperature_tenths_c",battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,-1) ?: -1)
                    put("battery_level",battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1) ?: -1)
                    put("thermal_status",if(android.os.Build.VERSION.SDK_INT>=29) app.getSystemService(PowerManager::class.java).currentThermalStatus else -1)
                }.toString())
                suspend fun <T> measured(stage:String,work:suspend ()->T):T {
                    val trace=G0PhaseTrace(driverTiming=false)
                    val result=trace.run(work)
                    fun maxSpan(phase:String)=trace.spans.filter { it.phase==phase }.maxOfOrNull { it.end-it.start } ?: 0L
                    val accountMax=maxSpan("short_account_hold")
                    val sqlMax=maxSpan("domain_sql_hold")
                    val starts=trace.spans.filter { it.phase=="tx_begin_outer" }.sortedBy { it.start }
                    val ends=trace.spans.filter { it.phase=="tx_end_outer" }.sortedBy { it.start }
                    check(starts.size==ends.size)
                    val transactionMax=starts.zip(ends).maxOfOrNull { (a,b)->check(b.end>=a.start);b.end-a.start } ?: 0L
                    if(candidate) holds += maxOf(accountMax,sqlMax,transactionMax)
                    Log.i("G0ShortGrowth",buildJsonObject {
                        put("stage",stage);put("history",history);put("sample",sample);put("mode",mode)
                        put("return_ns",trace.nanos("operation"));put("account_max_ns",accountMax);put("sql_max_ns",sqlMax)
                        put("transaction_max_ns",transactionMax);put("transaction_count",starts.size)
                        put("account_wait_ns",trace.nanos("short_account_wait"));put("sql_wait_ns",trace.nanos("domain_sql_wait"))
                        put("reference_combined_hold_ns",if(candidate || stage=="SEND") 0L else reference.scheduler.samples().last().occupiedNs)
                        put("access_decryptions",trace.count("decrypt_access"));put("refresh_decryptions",trace.count("decrypt_refresh"))
                    }.toString())
                    return result
                }
                val journeyStart=SystemClock.elapsedRealtimeNanos()
                val name="Measured $sample"
                var written: com.dayforge.data.local.entity.SyncOutboxEntity? = null
                measured("WRITE") {
                    if(candidate) { written = requireNotNull(store.edit(name,"",at=millis+sample)) }
                    else {
                        reference.write(session) {
                            val old=requireNotNull(db.metricDao().getMetricById(metric.id))
                            db.metricDao().update(old.copy(name=name,description="",updatedAt=millis+sample))
                        }
                    }
                }
                val row = written ?: db.syncOutboxDao().getAll().single()
                val delivery=measured("SEND") { requireNotNull(if(candidate) store.send(http,currentAccess,row.operationId) else sender(http).sendOperation(currentAccess,row.operationId)) }
                measured("ACK") {
                    assertEquals(NextOperationAcceptance.COMMITTED,if(candidate) store.acknowledge(delivery) else reference.acknowledge(http,delivery))
                }
                if(candidate) measured("READ_MODEL") {
                    val view=store.read(currentAccess)
                    assertEquals(name,view.name);assertEquals(0,view.pending);assertEquals(history+sample+1L,view.serverRevision)
                }
                Log.i("G0ShortGrowth",buildJsonObject {
                    put("stage","JOURNEY_COMPONENT");put("history",history);put("sample",sample);put("mode",mode)
                    put("elapsed_ns",SystemClock.elapsedRealtimeNanos()-journeyStart)
                    put("actual_frame_measured",false);put("prompt_measured",false)
                }.toString())
            }
            storage.isolatedBuilder=originalBuilder;storage.reopen()
            assertEquals(history+20,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
        }
        assertEquals(80,holds.size)
        assertTrue("G0 short domain exceeds 150ms history=$history max=${holds.max()} over=${holds.count { it>150_000_000L }}",holds.all { it<=150_000_000L })
    }
}
