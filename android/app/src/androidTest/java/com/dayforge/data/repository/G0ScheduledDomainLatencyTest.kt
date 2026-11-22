package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic component budget, not mixed UI/frame/steady-load acceptance. Retains original
 * validators/account mutex. All 20 samples count, including the first; no percentile subtraction.
 * HTTP prepare/delivery is outside the measured already-ready ACK. Actual final decryption,
 * domain validation, receipt/outbox commit and structured handoff are inside admission time.
 */
@RunWith(AndroidJUnit4::class)
class G0ScheduledDomainLatencyTest : G0DomainFixture() {
    @Test(timeout = 180_000) fun originalWarmWriteAndAckMustFitNonPreemptibleBudget() = runBlocking<Unit> {
        captureWriteTrace = false // Differential-only hashing must not contaminate component cost.
        register()
        val (http, _) = channel { input ->
            if (input.path.endsWith("/identity")) successReply(input)
            else successReply(input, revision = (wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0L) + 1L)
        }
        val session = local(); val runtime = bridge()
        val total = mapOf(G0CommitScheduler.Kind.WRITE to mutableListOf<Long>(), G0CommitScheduler.Kind.ACK to mutableListOf())
        suspend fun measure(kind: G0CommitScheduler.Kind, index: Int, body: suspend () -> Unit) {
            val authBefore = decrypts.get(); val authNsBefore = decryptNs.get()
            val start = SystemClock.elapsedRealtimeNanos()
            body()
            val elapsed = SystemClock.elapsedRealtimeNanos() - start
            val sample = scheduler.samples().last()
            assertEquals(kind, sample.kind); assertTrue(sample.completed)
            total.getValue(kind).add(elapsed)
            Log.i("G0DomainCost", "kind=$kind sample=$index total_ns=$elapsed queue_ns=${sample.queuedNs} occupied_ns=${sample.occupiedNs} " +
                "decrypts=${decrypts.get() - authBefore} decrypt_ns=${decryptNs.get() - authNsBefore}")
        }
        repeat(20) { index ->
            measure(G0CommitScheduler.Kind.WRITE, index) {
                runtime.write(session) {
                    val current = requireNotNull(db.metricDao().getMetricById(metric.id))
                    db.metricDao().update(current.copy(name = "G0 measured $index", updatedAt = millis + 5000L + index))
                }
            }
            val row = db.syncOutboxDao().getAll().single()
            val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
            measure(G0CommitScheduler.Kind.ACK, index) {
                assertEquals(NextOperationAcceptance.COMMITTED, runtime.acknowledge(http, delivery))
            }
            assertNull(db.syncOutboxDao().getById(row.id))
            assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        }
        assertEquals(20, count("next_acceptances")); assertEquals(0, count("sync_outbox"))
        assertEquals(20L, db.syncOutboxDao().getState("metric", metric.uuid)!!.revision)
        val failures = mutableListOf<String>()
        for (kind in total.keys) {
            val times = total.getValue(kind).sorted()
            val held = scheduler.samples().filter { it.kind == kind }.map { it.occupiedNs }.sorted()
            Log.i("G0DomainCost", "summary=$kind count=${times.size} p95_ns=${times[18]} max_ns=${times.last()} occupied_p95_ns=${held[18]} occupied_max_ns=${held.last()}")
            if (held.last() > 150_000_000L) failures.add("$kind occupied_max_ns=${held.last()} > 150000000")
            if (kind == G0CommitScheduler.Kind.WRITE && times[18] > 500_000_000L)
                failures.add("WRITE reliable P95_ns=${times[18]} > 500000000")
        }
        assertTrue("G0 original-domain admission budget failed: $failures", failures.isEmpty())
    }
}
