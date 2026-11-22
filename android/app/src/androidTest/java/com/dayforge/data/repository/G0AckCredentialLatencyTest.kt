package com.dayforge.data.repository

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Same-image paired component experiment. Alternating order limits systematic warm-cache bias.
 * No network or restoration cost is claimed as part of a ready ACK. All real commits/auth count.
 */
@RunWith(AndroidJUnit4::class)
class G0AckCredentialLatencyTest : G0DomainFixture() {
    @Test(timeout = 240_000) fun pairedCredentialPurposeCostRetainsAtomicBudget() = runBlocking<Unit> {
        captureWriteTrace = false
        register(); val session = local()
        val (http, _) = channel { input ->
            if (input.path.endsWith("/identity")) successReply(input)
            else successReply(input, revision = (wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0L) + 1)
        }
        val occupied = mutableListOf<Long>()
        repeat(20) { index ->
            val writeTrace = G0PhaseTrace()
            writeTrace.run {
                bridge().write(session) {
                    val old = requireNotNull(db.metricDao().getMetricById(metric.id))
                    db.metricDao().update(old.copy(name = "Paired $index", updatedAt = millis + index))
                }
            }
            report("WRITE", index, writeTrace)
            val row = db.syncOutboxDao().getAll().single()
            val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
            val image = storage.snapshotClosedDatabase()
            for (split in if (index % 2 == 0) listOf(false, true) else listOf(true, false)) {
                storage.restoreClosedDatabase(image); storage.reopen()
                // Establish the same open-connection condition before timing either mode.
                assertEquals(1, count("sync_outbox"))
                val trace = G0PhaseTrace()
                trace.run { assertEquals(NextOperationAcceptance.COMMITTED, bridge(split).acknowledge(http, delivery)) }
                assertEquals(2, trace.count("datastore_read"))
                assertEquals(2, trace.count("decrypt_access"))
                assertEquals(if (split) 0 else 2, trace.count("decrypt_refresh"))
                assertEquals(1, trace.count("tx_begin_outer")); assertEquals(1, trace.count("tx_end_outer"))
                report(if (split) "SPLIT" else "REFERENCE", index, trace)
                if (split) occupied.add(scheduler.samples().last().occupiedNs)
                assertNull(db.syncOutboxDao().getById(row.id))
                assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
                assertEquals(index + 1, count("next_acceptances"))
            }
        }
        assertEquals(20, occupied.size)
        assertTrue("G0 split ACK still exceeds 150ms: max=${occupied.max()} ns count=${occupied.count { it > 150_000_000L }}",
            occupied.all { it <= 150_000_000L })
    }

    private fun report(mode: String, index: Int, trace: G0PhaseTrace) {
        val admission = scheduler.samples().last()
        val phases = trace.spans.map { it.phase }.distinct().sorted()
        val sqlSpans = trace.spans.filter { it.phase in setOf("sql_driver", "sql_cursor", "sql_cursor_step", "sql_statement", "tx_begin_outer", "tx_begin_nested", "tx_end") }
            .sortedBy { it.start }
        var end = 0L; var union = 0L
        for (span in sqlSpans) { union += (span.end - maxOf(span.start, end)).coerceAtLeast(0); end = maxOf(end, span.end) }
        Log.i("G0CredentialCost", buildJsonObject {
            put("mode", mode); put("sample", index); put("queue_ns", admission.queuedNs)
            put("occupied_ns", admission.occupiedNs); put("sql_union_ns", union)
            putJsonObject("phase_ns") { for (phase in phases) put(phase, trace.nanos(phase)) }
            putJsonObject("phase_calls") { for (phase in phases) put(phase, trace.count(phase)) }
        }.toString())
    }
}
