package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.CountTrustIndex
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Existing 16MiB value ceiling exercised through bounded 512KiB slices. Separate capacity
 * admission follows measured small-slice cost; 600s is not a product latency threshold. */
@RunWith(AndroidJUnit4::class)
class CountNodeGcValueCapacityTest : CountNodeGcFixture() {
    @Test(timeout = 600_000) fun maximumLegalJsonValueIsChunkedAndParsedOutsideTheActualTransaction() = runBlocking<Unit> {
        ready()
        rootValue("gc_fixture_large", JsonPrimitive("L".repeat(CountTrustIndex.MAX_VALUE_BYTES - 2)))
        val before = compactNativeProof(); val live = reachable()
        val reports = finish()
        assertTrue(reports.any { it.parseNanos > 0 })
        assertTrue(reports.all { it.deletedNodes <= 32 && it.deletedBytes <= 512 * 1024 })
        val after = compactNativeProof()
        assertEquals(before - "count_trust_nodes", after - "count_trust_nodes")
        assertEquals(live, reachable())
        db.withTransaction { owner.maintenance(db) {
            val root = requireNotNull(CountTrustSeal.read(sql))
            val value = CountTrustIndex(sql, root.binding, root.root) {}.get("gc_fixture_large")!!.jsonPrimitive.content
            assertEquals(CountTrustIndex.MAX_VALUE_BYTES - 2, value.length)
            assertTrue(value.all { it == 'L' })
        } }
    }

}
