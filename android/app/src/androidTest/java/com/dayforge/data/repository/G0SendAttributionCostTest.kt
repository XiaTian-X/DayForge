package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Three alternating pairs of the SAME sender and database image. New whole-window
 * observation is on/off; legacy tracing and all security checks stay enabled in BOTH.
 * This estimates diagnostic perturbation, not an optimized HTTP implementation or UI.
 */
@RunWith(AndroidJUnit4::class)
class G0SendAttributionCostTest : G0SendAttributionFixture() {
    private fun now() = SystemClock.elapsedRealtimeNanos()
    @Test(timeout = 600_000) fun originalSendBreakdownAndObservationOverhead() = runBlocking<Unit> {
        seedAccepted(8); seedFact(); val image = storage.snapshotClosedDatabase()
        val failures = mutableListOf<String>(); var complete = 0
        repeat(3) { pair ->
            for (observe in if (pair % 2 == 0) listOf(false, true) else listOf(true, false)) {
                freshGateForColdOpen(); restoreForJourney(image)
                val requestId = db.syncOutboxDao().getAll().single().operationId
                val store = openCount(); val (http, _) = channel { successReply(it) }
                val legacy = G0PhaseTrace(driverTiming = false); val before = decryptCount.get(); val start = now()
                val delivery = legacy.run {
                    if (observe) sendTrace.capture { requireNotNull(store.send(http, requestId)) }
                    else requireNotNull(store.send(http, requestId))
                }
                val end = now(); val allDecrypts = decryptCount.get() - before
                val sample = if (observe) sendTrace.snapshot() else null
                if (sample != null) {
                    assertTrue("Incomplete diagnostic window", sample.complete); assertTrue(sample.returned)
                    assertEquals(allDecrypts, sample.events.count { it.kind.startsWith("decrypt_") })
                    assertEquals(0, sample.events.count { it.kind == "decrypt_unclassified" })
                    assertEquals(1, sample.events.count { it.kind == "http_identity" && it.ok })
                    assertEquals(1, sample.events.count { it.kind == "http_push" && it.ok })
                    assertEquals(0, sample.events.count { it.kind == "http_other" })
                    assertTrue(sample.events.all { it.start >= start && it.end <= end && it.end >= it.start })
                    assertEquals(0, sendTrace.retainedRoleCount())
                }
                assertEquals(3, legacy.count("count_account_hold")); assertEquals(3, legacy.count("count_account_wait"))
                for (name in listOf("count_send_session_ready", "count_send_prepare_commit", "count_send_push", "count_send_after_response"))
                    assertEquals(name, 1, legacy.count(name))
                val ackTrace = G0PhaseTrace(driverTiming = false); val ackStart = now()
                assertEquals(NextOperationAcceptance.COMMITTED, ackTrace.run { store.acknowledge(delivery) })
                val ackEnd = now(); val result = view(store); val state = pageNativeState()
                reopenReference(); sameAsOriginal(result)
                assertEquals(NextOperationAcceptance.REPLAYED, sender(http).acceptOperation(delivery))
                assertEquals(state, pageNativeState())
                assertEquals(9, count("completions")); assertEquals(9, count("next_acceptances")); assertEquals(0, count("sync_outbox"))
                val sampleId = pair * 2 + if (observe) 1 else 0
                // Keep each logcat payload bounded. The collector must require every
                // event index before treating the final sample as complete evidence.
                sample?.events?.forEachIndexed { index, event ->
                    Log.i("G0SendAttribution", buildJsonObject {
                        put("kind", "trace_event"); put("sample_id", sampleId); put("index", index)
                        put("event_count", sample.events.size); put("category", event.kind)
                        put("start_ns", event.start - start); put("end_ns", event.end - start)
                        put("ok", event.ok); put("legacy_attached", event.legacyAttached); put("thread", event.threadId)
                    }.toString())
                }
                Log.i("G0SendAttribution", buildJsonObject {
                    put("kind", "sample"); put("sample_id", sampleId); put("pair", pair); put("observe", observe)
                    put("event_count", sample?.events?.size ?: 0)
                    put("history", 8); put("pending_at_entry", 1); put("network_synthetic", true)
                    put("send_ns", end - start); put("ack_ns", ackEnd - ackStart)
                    put("all_thread_decrypt_count", allDecrypts)
                    put("legacy_access_count", legacy.count("decrypt_access")); put("legacy_refresh_count", legacy.count("decrypt_refresh"))
                    put("send_resource_ns", transactionHold(legacy)); put("ack_resource_ns", transactionHold(ackTrace))
                    put("send_account_wait_ns", legacy.nanos("count_account_wait"))
                    put("send_account_hold_ns", legacy.nanos("count_account_hold"))
                    put("ack_account_wait_ns", ackTrace.nanos("count_account_wait"))
                    put("ack_account_hold_ns", ackTrace.nanos("count_account_hold"))
                    put("phases", buildJsonObject {
                        for (name in listOf("count_send_session_ready", "count_send_prepare_commit", "count_send_push", "count_send_after_response"))
                            put(name, legacy.nanos(name))
                    })
                    put("facts", 9); put("receipts", 9); put("outbox", 0)
                    put("original_reader_and_replay_verified", true)
                }.toString())
                complete++
                if (ackEnd - ackStart > 500_000_000L) failures += "$pair/$observe ACK >500ms"
                if (maxOf(transactionHold(legacy), transactionHold(ackTrace)) > 150_000_000L)
                    failures += "$pair/$observe resource >150ms"
            }
        }
        assertEquals(6, complete)
        assertTrue("Original component budgets; observation experiment is NOT UI/P95: " + failures.joinToString(";"), failures.isEmpty())
    }
}
