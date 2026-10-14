package com.dayforge.data.repository

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
        val TABLES: Set<String> = NextReplicaRecoveryReader.TABLES - "next_replica_fence"
        suspend fun schemaHash(database: HabitDatabase): String = database.withTransaction {
            val sql = database.openHelper.writableDatabase
            syncPayloadHash(buildJsonObject {
                put("version", sql.version)
                put("objects", buildJsonObject {
                    for ((key, item) in ReplicaSqlSchema.read(sql).toSortedMap()) put(key, buildJsonObject {
                        put("type", item.type); put("name", item.name); put("owner", item.owner)
                        put("definition", JsonArray(item.definition.map { JsonArray(it.map(::JsonPrimitive)) }))
                    })
                })
            }.toString())
        }
        suspend fun capture(database: HabitDatabase): ReplicaProjection = database.withTransaction {
            val all = NextReplicaStorageProof(database).readInTransaction().contents.getValue("tables").jsonObject
            require(all.keys.filterNot { it.startsWith("sqlite_") || it in setOf("android_metadata", "room_master_table") }.toSet() ==
                TABLES + "next_replica_fence") { "SYNC_REPLICA_PROJECTION_INVALID" }
            ReplicaProjection(JsonObject(TABLES.sorted().associateWith { all.getValue(it) }))
        }
    }
}
