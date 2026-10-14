package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.local.entity.NextReplicaFenceEntity
import kotlinx.serialization.json.*

internal fun canonicalReplicaJson(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonicalReplicaJson(it.value) })
    is JsonArray -> JsonArray(value.map(::canonicalReplicaJson))
    else -> value
}

/** Compare complete source contents while excluding ONLY the exact validated control row.
 * Original raw proof (including the row) is still saved in the archive, never rewritten.
 */
internal object NextReplicaSourceClosure {
    fun controlHash(fence: NextReplicaFenceEntity?): String? = fence?.let { syncPayloadHash(row(it).toString()) }

    fun storage(proof: ReplicaStorageProof, actual: NextReplicaFenceEntity?): String {
        val tables = proof.contents.getValue("tables").jsonObject
        val expected = JsonArray(actual?.let { listOf(row(it)) }.orEmpty())
        require(tables.getValue("next_replica_fence") == expected) { "SYNC_REPLICA_FENCE_CHANGED" }
        val normalized = JsonObject(proof.contents + ("tables" to
            JsonObject(tables + ("next_replica_fence" to JsonArray(emptyList())))))
        return syncPayloadHash(normalized.toString())
    }

    fun input(input: ReplicaArchiveInput, actual: NextReplicaFenceEntity?): String =
        syncPayloadHash(JsonObject(input.manifest + ("business_hash" to JsonPrimitive(storage(input.storage, actual)))).toString())

    private fun row(fence: NextReplicaFenceEntity) = buildJsonObject {
        fun text(key: String, value: String) = put(key, buildJsonObject {
            put("sqlite_type", Cursor.FIELD_TYPE_STRING); put("value", value)
        })
        fun integer(key: String, value: Long) = put(key, buildJsonObject {
            put("sqlite_type", Cursor.FIELD_TYPE_INTEGER); put("value", value)
        })
        integer("__recovery_rowid", 1); integer("id", 1)
        text("recoveryId", fence.recoveryId); text("accountId", fence.accountId)
        text("serverInstanceId", fence.serverInstanceId); text("oldEpoch", fence.oldEpoch); text("newEpoch", fence.newEpoch)
        text("authenticationGeneration", fence.authenticationGeneration); text("deviceId", fence.deviceId)
        integer("capabilityRevision", fence.capabilityRevision); text("sourceHash", fence.sourceHash); text("mode", fence.mode)
    }
}
