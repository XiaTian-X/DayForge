package com.dayforge.data.repository

import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.data.history.AuthenticatedHistoryDirectory

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.HistoryBlockCodec.Leaf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Logical BLOB read-volume experiment only. The root is supplied by a trusted test caller.
 * Same-day/same-unit growth is a directory pressure sample, not annual household cold recovery,
 * UI latency, P95, disk-page I/O, native-business-table completeness, or post-commit maintenance. */
@RunWith(AndroidJUnit4::class)
class G0AuthenticatedHistoryDirectoryGrowthTest {
    private val directory = AuthenticatedHistoryDirectory()
    private val sizes = listOf(8, 730, 8192)

    @Test fun fixedPointQueriesLoadOnlyOnePathAsSingleUnitHistoryGrows() {
        for (size in sizes) {
            val input = DirectoryTestData.records(size)
            val built = directory.build(DirectoryTestData.unit, input)
            val selected = input[size / 2]
            DirectoryTestData.SqlStore.memory().use { store ->
                store.install(built.blocks)
                val point = directory.readPoint(built.root, selected.key, store)
                DirectoryTestData.assertRecords(listOf(selected), point.records)
                assertEquals(built.root.height + 1, point.stats.visitedNodes)
                assertEquals(point.stats.visitedNodes, store.loaded.size)
                assertEquals(point.stats.loadedBytes, store.loaded.sumOf { it.second.toLong() })
                assertEquals(store.loaded.size, store.loaded.map { it.first }.distinct().size)
                val pointNodes = store.loaded.toList()
                store.loaded.clear()
                val complete = directory.readRange(built.root, null, null, store,
                    AuthenticatedHistoryDirectory.Budget(maxRecords = size))
                DirectoryTestData.assertRecords(DirectoryTestData.oracle(input), complete.records)
                assertEquals(built.blocks.size, complete.stats.visitedNodes)
                assertEquals(built.blocks.values.sumOf { it.size.toLong() }, complete.stats.loadedBytes)
                assertTrue(pointNodes.all { node -> store.loaded.contains(node) })
                if (size > 128) {
                    assertTrue(point.stats.visitedNodes < complete.stats.visitedNodes)
                    assertTrue(point.stats.loadedBytes < complete.stats.loadedBytes)
                }
                measure("point", size, built.root.height, built.blocks.size,
                    built.blocks.values.sumOf { it.size.toLong() }, point.stats, complete.stats)
            }
        }
    }

    @Test fun fixedSmallRangeLoadsOnlyIntersectingLeavesAndAuthenticatedAncestors() {
        for (size in sizes) {
            val input = DirectoryTestData.records(size)
            val built = directory.build(DirectoryTestData.unit, input)
            val start = if (size <= 128) 2 else 126
            val lower = DirectoryTestData.key(start)
            val upper = DirectoryTestData.key(start + 4)
            val expected = DirectoryTestData.oracle(input, lower, upper)
            DirectoryTestData.SqlStore.memory().use { store ->
                store.install(built.blocks)
                val selected = directory.readRange(built.root, lower, upper, store)
                DirectoryTestData.assertRecords(expected, selected.records)
                assertEquals(4, selected.records.size)
                val refs = references(built)
                val loadedLeaves = store.loaded.mapNotNull { (digest, _) ->
                    val bytes = built.blocks.getValue(digest).toByteArray()
                    // This introspection counts the actual selected leaf contents; expected
                    // query records above come only from the independent original-input oracle.
                    HistoryBlockCodec.decode(refs.getValue(digest), bytes) as? Leaf
                }
                assertEquals(if (size <= 128) 1 else 2, loadedLeaves.size)
                assertTrue(loadedLeaves.all { leaf ->
                    DirectoryTestData.oracle(leaf.records, lower, upper).isNotEmpty()
                })
                assertEquals(selected.stats.visitedNodes, store.loaded.size)
                assertEquals(selected.stats.loadedBytes, store.loaded.sumOf { it.second.toLong() })
                assertTrue(selected.stats.visitedNodes <= 1 + loadedLeaves.size * (built.root.height + 1))
                store.loaded.clear()
                val complete = directory.readRange(built.root, null, null, store,
                    AuthenticatedHistoryDirectory.Budget(maxRecords = size))
                DirectoryTestData.assertRecords(DirectoryTestData.oracle(input), complete.records)
                if (size > 128) {
                    assertTrue(selected.stats.visitedNodes < complete.stats.visitedNodes)
                    assertTrue(selected.stats.loadedBytes < complete.stats.loadedBytes)
                }
                measure("range-four", size, built.root.height, built.blocks.size,
                    built.blocks.values.sumOf { it.size.toLong() }, selected.stats, complete.stats)
            }
        }
    }

    /** Test-only post-query inspection, excluded from the recorded SQLite loader counts. */
    private fun references(built: AuthenticatedHistoryDirectory.Built): Map<String, HistoryBlockCodec.Ref> {
        val found = linkedMapOf<String, HistoryBlockCodec.Ref>()
        val queue = ArrayDeque<HistoryBlockCodec.Ref>()
        queue.addLast(built.root)
        while (queue.isNotEmpty()) {
            val ref = queue.removeFirst()
            check(found.put(ref.digest, ref) == null)
            val node = HistoryBlockCodec.decode(ref, built.blocks.getValue(ref.digest).toByteArray())
            if (node is HistoryBlockCodec.Branch) node.children.forEach { queue.addLast(it) }
        }
        return found
    }

    private fun measure(query: String, records: Int, height: Int, totalNodes: Int, totalBytes: Long,
        selected: AuthenticatedHistoryDirectory.Stats, complete: AuthenticatedHistoryDirectory.Stats) {
        Log.i("G0_BLOCK_DIRECTORY_MEASURE", buildJsonObject {
            put("query", query); put("records", records); put("root_height", height)
            put("total_nodes", totalNodes); put("total_blob_bytes", totalBytes)
            put("selected_nodes", selected.visitedNodes); put("selected_blob_bytes", selected.loadedBytes)
            put("selected_records", selected.returnedRecords); put("selected_payload_bytes", selected.returnedPayloadBytes)
            put("full_nodes", complete.visitedNodes); put("full_blob_bytes", complete.loadedBytes)
            put("full_records", complete.returnedRecords); put("disk_io", false); put("latency_measured", false)
        }.toString())
    }
}
