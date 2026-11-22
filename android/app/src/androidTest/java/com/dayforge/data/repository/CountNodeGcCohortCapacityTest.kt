package com.dayforge.data.repository

import android.os.SystemClock
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.CountTrustIndex
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** New isolated capacity diagnostic. 17000 bounded rows / 64-row metadata pages / 32-row
 * sweep batches; predeclared 600s is execution capacity, not a latency or product-size limit.
 * Admit only after normal safety classes and measured small-slice cost; no automatic launch.
 */
@RunWith(AndroidJUnit4::class)
class CountNodeGcCohortCapacityTest : CountNodeGcFixture() {
    @Test(timeout = 600_000) fun multipleFrozenCohortsUseRowidContinuationAndPreserveTheExactLiveGraph() = runBlocking<Unit> {
        ready()
        val started = SystemClock.elapsedRealtimeNanos()
        db.withTransaction { owner.maintenance(db) {
            (0 until 17000).chunked(64).forEach { batch ->
                val arguments = arrayListOf<Any>()
                batch.forEach { value ->
                    val bytes = "capacity-orphan-$value".toByteArray()
                    arguments += CountTrustIndex.hash(bytes); arguments += bytes
                }
                sql.execSQL("INSERT INTO count_trust_nodes(digest,body) VALUES" + batch.joinToString(",") { "(?,?)" }, arguments.toTypedArray())
            }
        } }
        val seedNs = SystemClock.elapsedRealtimeNanos() - started
        val live = reachable(); val before = compactNativeProof()
        val began = SystemClock.elapsedRealtimeNanos(); val reports = finish()
        val elapsed = SystemClock.elapsedRealtimeNanos() - began
        assertTrue(reports.maxOf { it.cohort } >= 2)
        assertTrue(reports.all { it.catalogueRows <= 16384 && it.deletedNodes <= 32 && it.deletedBytes <= 512 * 1024 })
        assertEquals(17000, reports.sumOf { it.deletedNodes })
        assertEquals(live, reachable())
        val after = compactNativeProof(); assertEquals(before - "count_trust_nodes", after - "count_trust_nodes")
        assertEquals(live.size, after.getValue("count_trust_nodes").size)
        android.util.Log.i("CountNodeGcCapacity", buildJsonObject {
            put("orphan_rows", 17000); put("seed_ns", seedNs); put("maintenance_ns", elapsed)
            put("slices", reports.size); put("cohorts", reports.maxOf { it.cohort })
            put("account_end_sum_ns", reports.sumOf { it.accountAndEndNanos })
            put("account_end_max_ns", reports.maxOf { it.accountAndEndNanos })
            put("json_parse_sum_ns", reports.sumOf { it.parseNanos })
            put("deleted_nodes", reports.sumOf { it.deletedNodes }); put("deleted_bytes", reports.sumOf { it.deletedBytes })
            put("multiple_cohorts_repeat_full_root_walk", true); put("performance_gate_evaluated", false)
            put("percentile", false); put("ui_pixels", false); put("physical_file_shrink_promised", false)
        }.toString())
    }
}
