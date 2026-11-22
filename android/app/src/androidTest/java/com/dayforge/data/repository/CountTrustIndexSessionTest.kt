package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.history.HistoryNodeSession
import com.dayforge.data.local.CountTrustRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real production index/codec on controlled Room. Cached parsing is not account authority. */
@RunWith(AndroidJUnit4::class)
class CountTrustIndexSessionTest : NextCoreRequestFixture() {
    private suspend fun <T> owning(block: suspend () -> T): T = db.withTransaction {
        requireNotNull(CountTrustRuntime.forDatabase(db)).maintenance(db) { block() }
    }

    @Test fun changedRootCannotReuseOldDecodedTreeAndOldContentRemainsImmutable() = runBlocking<Unit> { owning {
        val sql = db.openHelper.writableDatabase; val binding = "11".repeat(32)
        val before = (0 until 40).associate { "key:$it" to JsonPrimitive(it) }
        val root = CountTrustIndex.build(sql, binding, before) {}
        val index = CountTrustIndex(sql, binding, root) {}
        index.withReadScope {
            before.forEach { (key, value) -> assertEquals(value, index.get(key)) }
            index.putAll(mapOf("key:0" to JsonPrimitive(100), "key:41" to JsonPrimitive(41), "key:1" to null))
            assertNotEquals(root, index.root)
            assertEquals(JsonPrimitive(100), index.get("key:0")); assertNull(index.get("key:1"))
            assertEquals(JsonPrimitive(41), index.get("key:41"))
        }
        val previous = CountTrustIndex(sql, binding, root) {}
        previous.withReadScope { before.forEach { (key, value) -> assertEquals(value, previous.get(key)) } }
        val reopened = CountTrustIndex(sql, binding, index.root) {}
        reopened.withReadScope {
            assertEquals(JsonPrimitive(100), reopened.get("key:0")); assertNull(reopened.get("key:1"))
            assertEquals(JsonPrimitive(41), reopened.get("key:41"))
        }
    } }

    @Test fun decodedCacheBudgetStopsReuseWithoutTruncatingPointProofs() = runBlocking<Unit> { owning {
        val sql = db.openHelper.writableDatabase; val binding = "22".repeat(32)
        val values = (0 until 4).associate { "key:$it" to JsonPrimitive(it) }
        val root = CountTrustIndex.build(sql, binding, values) {}
        val index = CountTrustIndex(sql, binding, root) {}
        index.withReadScope(HistoryNodeSession.Budget(maxVisits = 1, maxDecodedNodes = 1)) {
            repeat(3) { values.forEach { (key, value) -> assertEquals(value, index.get(key)) } }
            assertNull(index.get("absent"))
        }
        assertEquals(JsonPrimitive(2), index.get("key:2"))
    } }

    @Test fun cancelledScopeDoesNotLeaveDecodedStateForNextOperationAndCorruptionRejects() = runBlocking<Unit> { owning {
        val sql = db.openHelper.writableDatabase; val binding = "33".repeat(32)
        val root = CountTrustIndex.build(sql, binding, mapOf("key" to JsonPrimitive(1))) {}
        var cancelled = false
        val index = CountTrustIndex(sql, binding, root) { if (cancelled) throw CancellationException("INDEX_SCOPE_CANCELLED") }
        val failure = try { index.withReadScope {
            assertEquals(JsonPrimitive(1), index.get("key")); cancelled = true; index.get("key")
        }; throw AssertionError("Cancellation must propagate") } catch (error: CancellationException) { error }
        assertEquals("INDEX_SCOPE_CANCELLED", failure.message)
        cancelled = false
        index.withReadScope { assertEquals(JsonPrimitive(1), index.get("key")) }
        val raw = sql.query("SELECT body FROM count_trust_nodes WHERE digest=?", arrayOf(root.digest)).use {
            assertTrue(it.moveToFirst()); it.getBlob(0)
        }
        raw[0] = (raw[0].toInt() xor 1).toByte()
        sql.execSQL("UPDATE count_trust_nodes SET body=? WHERE digest=?", arrayOf(raw, root.digest))
        val fresh = CountTrustIndex(sql, binding, root) {}
        assertNotNull(rejected { fresh.withReadScope { fresh.get("key") } })
    } }
}
