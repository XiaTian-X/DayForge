package com.dayforge.data.repository

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** STRICT control experiment, not a mixed-page or verified-incremental acceptance test.
 * Each pair restores the same real completed-request history; no fabricated receipts/hashes.
 * Connection is opened before timing, but each candidate starts WITHOUT a runtime certificate.
 * Original split-auth ACK is the reference, isolating block work from credential savings.
 */
@RunWith(AndroidJUnit4::class)
class G0MetricBlockGrowthTest : G0DomainFixture() {
    @Test(timeout = 600_000) fun strictBlocksWithEightCompletedRequests() = measure(8)
    @Test(timeout = 600_000) fun strictBlocksWithEightyCompletedRequests() = measure(80)

    private fun measure(history: Int) = runBlocking<Unit> {
        captureWriteTrace = false
        register(); val session = local()
        val (http, _) = channel { input ->
            if (input.path.endsWith("/identity")) successReply(input)
            else successReply(input, revision = (wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0L) + 1)
        }
        repeat(history) { index ->
            val row = editMetric("Seed $index")
            val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
            assertEquals(NextOperationAcceptance.COMMITTED, sender(http).acceptOperation(delivery))
        }
        assertEquals(history, count("next_acceptances")); assertEquals(0, count("sync_outbox"))
        val image = storage.snapshotClosedDatabase()
        val candidateOccupied = mutableListOf<Long>()
        repeat(20) { sample ->
            for (candidate in if (sample % 2 == 0) listOf(false, true) else listOf(true, false)) {
                storage.restoreClosedDatabase(image); storage.reopen()
                assertEquals(history, count("next_acceptances")) // Same opened-connection start.
                val queue = G0CommitScheduler()
                val blocks = G0MetricBlocks(db, metric.uuid)
                val blockRuntime = blockRuntime(blocks, queue)
                // Reference uses the existing original bridge with its unchanged scheduler.
                val reference = bridge(true)
                val mode = if (candidate) "BLOCK_STRICT" else "REFERENCE_SPLIT"
                val currentAccess = access()
                val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                Log.i("G0MetricBlockGrowth", buildJsonObject {
                    put("stage", "ENV"); put("history", history); put("sample", sample); put("mode", mode)
                    put("temperature_tenths_c", battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1)
                    put("battery_level", battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1)
                    put("thermal_status", if (android.os.Build.VERSION.SDK_INT >= 29) app.getSystemService(PowerManager::class.java).currentThermalStatus else -1)
                }.toString())
                val journeyStart = SystemClock.elapsedRealtimeNanos()
                val body: suspend () -> Unit = {
                    val old = requireNotNull(db.metricDao().getMetricById(metric.id))
                    db.metricDao().update(old.copy(name = "Measured $sample", updatedAt = millis + sample))
                    val created = db.syncOutboxDao().getAll().single()
                    // Stabilize fixture-generated identity before the unchanged origin capture.
                    db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET operationId=?,createdAt=? WHERE id=?",
                        arrayOf<Any>(id(20_000 + sample), millis + sample, created.id))
                }
                suspend fun measured(stage: String, work: suspend () -> Unit) {
                    val trace = G0PhaseTrace(driverTiming = false)
                    val beforeRows = blocks.capturedRows; val beforeCaptures = blocks.captures
                    trace.run(work)
                    val admission = (if (candidate) queue else scheduler).samples().last()
                    if (candidate) candidateOccupied.add(admission.occupiedNs)
                    Log.i("G0MetricBlockGrowth", buildJsonObject {
                        put("stage", stage); put("history", history); put("sample", sample); put("mode", mode)
                        put("queue_ns", admission.queuedNs); put("occupied_ns", admission.occupiedNs)
                        put("return_ns", trace.nanos("operation")); put("outer_end_ns", trace.nanos("tx_end_outer"))
                        put("block_capture_ns", trace.nanos("block_capture"))
                        put("block_captures", blocks.captures - beforeCaptures)
                        put("block_rows", blocks.capturedRows - beforeRows)
                        putJsonObject("phase_ns") { trace.spans.map { it.phase }.distinct().sorted().forEach { put(it, trace.nanos(it)) } }
                        put("access_decryptions", trace.count("decrypt_access")); put("refresh_decryptions", trace.count("decrypt_refresh"))
                    }.toString())
                }
                measured("WRITE") { if (candidate) blockRuntime.write(session, body) else reference.write(session, body) }
                val row = db.syncOutboxDao().getAll().single()
                val networkStart = SystemClock.elapsedRealtimeNanos()
                val delivery = requireNotNull(sender(http).sendOperation(currentAccess, row.operationId))
                val sendNs = SystemClock.elapsedRealtimeNanos() - networkStart
                measured("ACK") {
                    assertEquals(NextOperationAcceptance.COMMITTED,
                        if (candidate) blockRuntime.acknowledge(delivery) else reference.acknowledge(http, delivery))
                }
                if (candidate) {
                    measured("READ_MODEL") {
                        val view = blockRuntime.read(currentAccess)
                        assertEquals("Measured $sample", view.definition.value.name)
                        assertEquals(0, view.sync.value.pending); assertEquals(history + 1L, view.sync.value.revision)
                    }
                    val journeyNs = SystemClock.elapsedRealtimeNanos() - journeyStart
                    Log.i("G0MetricBlockGrowth", buildJsonObject {
                        put("stage", "JOURNEY_COMPONENT"); put("history", history); put("sample", sample); put("mode", mode)
                        put("elapsed_ns", journeyNs); put("send_preparation_loopback_ns", sendNs)
                        put("actual_frame_measured", false); put("prompt_measured", false)
                    }.toString())
                }
                assertEquals(history + 1, count("next_acceptances")); assertEquals(0, count("sync_outbox"))
            }
        }
        assertEquals(60, candidateOccupied.size)
        assertTrue("G0 STRICT blocks exceed 150ms: history=$history max=${candidateOccupied.max()} count=${candidateOccupied.count { it > 150_000_000L }}",
            candidateOccupied.all { it <= 150_000_000L })
    }
}
