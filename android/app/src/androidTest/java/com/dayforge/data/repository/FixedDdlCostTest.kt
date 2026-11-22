package com.dayforge.data.repository

import android.database.Cursor
import android.os.Debug
import android.os.SystemClock
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountTrustSchema
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic paired cost on the real device. Passing means equivalence/read-only behavior,
 * never a latency budget pass. Process/class initialization is outside these warmed samples;
 * the unchanged full recovery classes retain complete cold and end-to-end costs.
 */
@RunWith(AndroidJUnit4::class)
class FixedDdlCostTest : NextCoreRequestFixture() {
    private data class Sample<T>(val value: T, val wallNanos: Long, val cpuNanos: Long)
    private fun <T> measured(block: () -> T): Sample<T> {
        val wall = SystemClock.elapsedRealtimeNanos(); val cpu = Debug.threadCpuTimeNanos()
        val value = block()
        return Sample(value, SystemClock.elapsedRealtimeNanos() - wall, Debug.threadCpuTimeNanos() - cpu)
    }
    private fun emit(kind: String, pair: Int, first: String, old: Sample<*>, candidate: Sample<*>) {
        android.util.Log.i("FixedDdlCost", "kind=$kind pair=$pair first=$first initialization_excluded=true " +
            "old_wall_ns=${old.wallNanos} candidate_wall_ns=${candidate.wallNanos} " +
            "old_cpu_ns=${old.cpuNanos} candidate_cpu_ns=${candidate.cpuNanos}")
    }

    @Test fun pairedActualSchemaAndPureViewCostsKeepFullQueriesAndIdenticalResults() = runBlocking<Unit> {
        register(); val owner = access()
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            val before = NextReplicaStorageProof(db).readInTransaction()
            var queries = 0
            val counted = object : SupportSQLiteDatabase by sql {
                override fun query(query: String): Cursor { queries++; return sql.query(query) }
                override fun query(query: String, bindArgs: Array<out Any?>): Cursor { queries++; return sql.query(query, bindArgs) }
            }
            // Cold construction and reference loading must not be silently credited to this
            // diagnostic. Both paths are explicitly initialized before alternating order.
            FixedDdlCountOriginal.validate(counted); CountTrustSchema.validate(counted)
            assertEquals(FixedDdlV18Original.projectContents(before), NextReplicaV18StorageView.projectContents(before))
            repeat(6) { pair ->
                lateinit var oldCount: Sample<Unit>; lateinit var candidateCount: Sample<Unit>
                lateinit var oldView: Sample<kotlinx.serialization.json.JsonObject?>
                lateinit var candidateView: Sample<kotlinx.serialization.json.JsonObject?>
                fun original() {
                    queries = 0; oldCount = measured { FixedDdlCountOriginal.validate(counted) }; assertEquals(2, queries)
                    oldView = measured { FixedDdlV18Original.projectContents(before) }
                }
                fun candidate() {
                    queries = 0; candidateCount = measured { CountTrustSchema.validate(counted) }; assertEquals(2, queries)
                    candidateView = measured { NextReplicaV18StorageView.projectContents(before) }
                }
                val first = if (pair % 2 == 0) { original(); candidate(); "old" } else { candidate(); original(); "candidate" }
                assertEquals(oldView.value, candidateView.value)
                emit("COUNT_VALIDATE_WITH_SQL", pair, first, oldCount, candidateCount)
                emit("V18_PROJECT_CONTENTS_PURE", pair, first, oldView, candidateView)
            }
            assertEquals(before.contents, NextReplicaStorageProof(db).readInTransaction().contents)
        }
        assertEquals(owner, access()); assertTrue(tokens.isCurrentSyncAuthority(owner))
    }
}
