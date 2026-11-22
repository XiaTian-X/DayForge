package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.ReplicaSqlSchema
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Exactly two frozen additive comparison edges. Never modifies a stage/archive/root and
 * never projects enrolled v20 command contents out of a previously captured source hash.
 */
internal object NextReplicaCompatibilityViews {
    fun projected(proof: ReplicaStorageProof): List<ReplicaStorageProof> = when (proof.contents.getValue("user_version")) {
        JsonPrimitive(19) -> listOfNotNull(NextReplicaV18StorageView.project(proof))
        JsonPrimitive(20) -> NextReplicaV19StorageView.project(proof)?.let { previous ->
            listOfNotNull(previous, NextReplicaV18StorageView.project(previous))
        }.orEmpty()
        else -> emptyList()
    }

    suspend fun matchesSchema(database: HabitDatabase, expectedHash: String): Boolean = database.withTransaction {
        matchesSchemaInTransaction(database, expectedHash)
    }

    internal suspend fun matchesSchemaInTransaction(database: HabitDatabase, expectedHash: String,
        snapshot: ReplicaStorageSnapshot? = null): Boolean {
        check(database.inTransaction()) { "SYNC_REPLICA_AMBIENT_TRANSACTION" }
        val caller = currentCoroutineContext(); caller.ensureActive()
        val sql = database.openHelper.writableDatabase
        val owner = CountTrustRuntime.forDatabase(database)
        if (owner == null) {
            // Preserve the original independent read order for unowned connections. Their
            // callbacks can reset schema_version, so a prior parsed snapshot is not a guard.
            if (ReplicaProjection.schemaHash(database) == expectedHash) return true
            val raw = NextReplicaStorageProof(database).readInTransaction()
            val previous = projected(raw)
            if (previous.isEmpty()) return false
            val fresh = ReplicaSqlSchema.read(sql)
            return matchesProjected(raw, previous, fresh, expectedHash)
        }
        val epoch = owner.generation
        val cookie = schemaVersion(database)
        val version = sql.version
        val current = ReplicaSqlSchema.read(sql)
        val matches = if (ReplicaProjection.schemaHash(version, current) == expectedHash) true else {
            val raw = snapshot?.storage(database) ?: NextReplicaStorageProof(database).readInTransaction()
            matchesProjected(raw, projected(raw), current, expectedHash) || NextReplicaResetSchemaComparison.matches(raw, current, expectedHash)
        }
        caller.ensureActive()
        check(CountTrustRuntime.forDatabase(database) === owner && owner.generation == epoch) {
            "SYNC_REPLICA_SCHEMA_CHANGED"
        }
        val finalCookie = schemaVersion(database)
        // The final native query can itself retire this runtime without changing its
        // schema cookie. Recheck identity/generation after all SQL has returned.
        check(CountTrustRuntime.forDatabase(database) === owner && owner.generation == epoch && finalCookie == cookie) {
            "SYNC_REPLICA_SCHEMA_CHANGED"
        }
        caller.ensureActive(); return matches
    }

    private fun matchesProjected(raw: ReplicaStorageProof, previous: List<ReplicaStorageProof>,
        fresh: Map<String, ReplicaSqlSchema.SchemaObject>, expectedHash: String): Boolean {
        if (previous.isEmpty()) return false
        val comparable = if (raw.contents.getValue("user_version") == JsonPrimitive(20)) {
            val old19 = previous.single { it.contents.getValue("user_version") == JsonPrimitive(19) }
            NextReplicaV19StorageView.parsedPrevious(raw, old19, fresh) ?: return false
        } else fresh
        return previous.any { old ->
            val version = old.contents.getValue("user_version").jsonPrimitive.int
            val removed = if (version == 18) NextReplicaV18StorageView.removedSchemaKeys else emptySet()
            ReplicaProjection.schemaHash(version, comparable.filterKeys { it !in removed }) == expectedHash
        }
    }

    private fun schemaVersion(database: HabitDatabase): Long = database.openHelper.writableDatabase.query("PRAGMA main.schema_version").use {
        require(it.columnCount == 1 && it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER)
        it.getLong(0).also { _ -> require(!it.moveToNext()) }
    }
}
