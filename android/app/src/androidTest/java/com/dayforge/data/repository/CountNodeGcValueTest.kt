package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.*
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountNodeGcValueTest : CountNodeGcFixture() {
    @Test fun pointerExtraFieldMatchesOriginalRefusalAndKeepsTheWholePool() = runBlocking<Unit> {
        ready(); orphan(401)
        val head = db.withTransaction { requireNotNull(CountTrustSeal.read(sql)) }
        val key = "gc_fixture_bad"; val id = CountTrustIndex.hash(key.toByteArray())
        val chunk = "{}".toByteArray(); val digest = orphan(402, bytes = chunk)
        val pointer = buildJsonObject {
            put("key", key); put("length", chunk.size); put("chunks", JsonArray(listOf(JsonPrimitive(digest)))); put("extra", true)
        }
        val record = HistoryBlockCodec.Record(HistoryBlockPlanner.OrderKey(LocalDate.of(1970, 1, 1), id), id,
            HistoryBlockCodec.Bytes.copyOf(pointer.toString().toByteArray()))
        val leaf = HistoryBlockCodec.encode(HistoryBlockCodec.Leaf(head.root.range, listOf(record)))
        db.withTransaction { owner.maintenance(db) {
            sql.execSQL("INSERT INTO count_trust_nodes(digest,body) VALUES(?,?)", arrayOf<Any>(leaf.ref.digest, leaf.bytes.toByteArray()))
            CountTrustSeal.store(sql, CountTrustSeal().prepare(head.binding, head.schemaHash, leaf.ref,
                head.revision + 1, head.generation, head.validator))
            assertNotNull(rejected { CountTrustIndex(sql, head.binding, leaf.ref) {}.verifyContents() })
        } }
        val before = compactNativeProof(); val gc = collector(); var rejected = false
        for (step in 0 until 10000) {
            val result = nextSlice(gc); assertEquals(0, result.deletedNodes)
            if (result.status == CountTrustNodeCollector.Status.FAILED) { rejected = true; break }
            assertEquals(CountTrustNodeCollector.Status.PROGRESS, result.status)
        }
        assertTrue(rejected); assertEquals(before, compactNativeProof())
    }
    @Test fun missingOrRewrittenLiveValueChunkCannotPermitAnySweep() = runBlocking<Unit> {
        ready(); orphan(404)
        val encoded = JsonPrimitive("bounded-live-value").toString().toByteArray()
        val digest = CountTrustIndex.hash(encoded)
        rootValue("gc_fixture_chunk", JsonPrimitive("bounded-live-value"))
        for (missing in listOf(true, false)) {
            db.withTransaction { owner.maintenance(db) {
                if (missing) sql.execSQL("DELETE FROM count_trust_nodes WHERE digest=?", arrayOf<Any>(digest))
                else sql.execSQL("UPDATE count_trust_nodes SET body=? WHERE digest=?", arrayOf<Any>("wrong-bytes".toByteArray(), digest))
            } }
            val before = compactNativeProof(); val gc = collector(); var failed = false
            for (step in 0 until 10000) {
                val report = nextSlice(gc); assertEquals(0, report.deletedNodes)
                if (report.status == CountTrustNodeCollector.Status.FAILED) { failed = true; break }
                assertEquals(CountTrustNodeCollector.Status.PROGRESS, report.status)
            }
            assertTrue(failed); assertEquals(before, compactNativeProof())
            db.withTransaction { owner.maintenance(db) {
                if (missing) sql.execSQL("INSERT INTO count_trust_nodes(digest,body) VALUES(?,?)", arrayOf<Any>(digest, encoded))
                else sql.execSQL("UPDATE count_trust_nodes SET body=? WHERE digest=?", arrayOf<Any>(encoded, digest))
            } }
        }
        finish()
    }

}
