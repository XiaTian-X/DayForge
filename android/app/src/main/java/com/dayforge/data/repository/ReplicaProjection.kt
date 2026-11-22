package com.dayforge.data.repository

import com.dayforge.data.local.ReplicaSqlSchema
import com.dayforge.data.local.ReplicaRecoveryTiming

import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import kotlinx.serialization.json.*

/** Complete, typed projection of the 35 owned business tables, produced by isolated validation.
 * Not source archives, a SQL script, file paths or authority to replace an active replica.
 */
internal class ReplicaProjection private constructor(val tables: JsonObject) {
    // Migrated physical column order can differ from fresh Room; names/types/values may not.
    val hash: String = syncPayloadHash(canonicalReplicaJson(tables).toString())
    companion object {
        // Local proof/command state is an inventory obligation, never a portable business grant.
        val TABLES: Set<String> = NextReplicaRecoveryReader.BUSINESS_TABLES - "next_replica_fence"
        suspend fun schemaHash(database: HabitDatabase): String = database.withTransaction {
            val sql = database.openHelper.writableDatabase
            schemaHash(sql.version, ReplicaSqlSchema.read(sql))
        }
        /** Pure framing shared with the one-transaction v18 comparison; no proof is retained. */
        internal fun schemaHash(version: Int, objects: Map<String, ReplicaSqlSchema.SchemaObject>): String =
            syncPayloadHash(buildJsonObject {
                put("version", version)
                put("objects", buildJsonObject {
                    for ((key, item) in objects.toSortedMap()) put(key, buildJsonObject {
                        put("type", item.type); put("name", item.name); put("owner", item.owner)
                        put("definition", JsonArray(item.definition.map { JsonArray(it.map(::JsonPrimitive)) }))
                    })
                })
            }.toString())
        suspend fun capture(database: HabitDatabase): ReplicaProjection = database.withTransaction {
            captureInTransaction(database)
        }
        internal suspend fun captureInTransaction(database: HabitDatabase, snapshot: ReplicaStorageSnapshot? = null): ReplicaProjection {
            check(database.inTransaction()) { "SYNC_REPLICA_AMBIENT_TRANSACTION" }
            return snapshot?.projection(database) ?: fromStorage(NextReplicaStorageProof(database).readInTransaction())
        }

        internal fun fromStorage(storage: ReplicaStorageProof): ReplicaProjection =
            ReplicaRecoveryTiming.compute(ReplicaRecoveryTiming.Stage.BUSINESS_PROJECTION) {
                val all = storage.contents.getValue("tables").jsonObject
                require(all.keys.filterNot { it.startsWith("sqlite_") || it in setOf("android_metadata", "room_master_table") }.toSet() ==
                    NextReplicaRecoveryReader.TABLES) { "SYNC_REPLICA_PROJECTION_INVALID" }
                ReplicaProjection(JsonObject(TABLES.sorted().associateWith { all.getValue(it) }))
            }
    }
}
