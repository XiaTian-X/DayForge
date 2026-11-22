package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Same limited storage model as the first experiment, now behind the closed SQL adapter.
 * These numbers do not include a product frame, prompts, real domain ACK or all consumers.
 */
@RunWith(AndroidJUnit4::class)
class G0GuardedLatencyTest : G0GuardedFixture() {
    private suspend fun measure(size: Int) {
        val probe = guardedProbe(size)
        val samples = mapOf("read" to mutableListOf<Long>(), "verify" to mutableListOf(), "write" to mutableListOf())
        repeat(2) { round -> repeat(50) { sample ->
            for (kind in samples.keys) {
                val auth = guardedDecrypts.get(); val authNs = guardedDecryptNs.get()
                val start = SystemClock.elapsedRealtimeNanos()
                val result = when (kind) {
                    "read" -> probe.read()
                    "verify" -> probe.verifyBlock()
                    else -> probe.append("guarded-$round-$sample")
                }
                val elapsed = SystemClock.elapsedRealtimeNanos() - start
                samples.getValue(kind).add(elapsed)
                val expected = size + round * 50L + sample + if (kind == "write") 1 else 0
                assertEquals(expected, result.count); assertEquals(expected, result.sum)
                Log.i("G0Guarded", "kind=$kind size=$size round=$round sample=$sample ns=$elapsed " +
                    "decrypts=${guardedDecrypts.get() - auth} decrypt_ns=${guardedDecryptNs.get() - authNs} " +
                    "reservation_ns=${probe.work.reservationNs} rows=${probe.work.rows} nodes=${probe.work.nodes} hash_bytes=${probe.work.hashBytes}")
            }
        } }
        assertEquals(size + 100L, probe.fullAudit().count)
        for ((kind, timings) in samples) {
            val limit = if (kind == "write") 500_000_000L else 300_000_000L
            assertTrue("G0 guarded mechanism $kind P95 exceeded ${limit / 1_000_000}ms", timings.sorted()[94] <= limit)
        }
    }
    @Test(timeout = 300_000) fun guardedOneThousandFacts() = runBlocking { measure(1024) }
    @Test(timeout = 300_000) fun guardedTenThousandFacts() = runBlocking { measure(10240) }
}
