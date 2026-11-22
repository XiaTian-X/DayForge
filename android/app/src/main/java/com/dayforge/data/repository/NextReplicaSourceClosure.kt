package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.local.entity.NextReplicaFenceEntity
import kotlinx.serialization.json.*

internal fun canonicalReplicaJson(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonicalReplicaJson(it.value) })
    is JsonArray -> JsonArray(value.map(::canonicalReplicaJson))
    else -> value
}

/** Compare all original business contents while excluding the exact validated fence and the
 * optional derived COUNT tables. In v20 primary command truth remains in this source hash;
 * only v19's unused diagnostic result table follows its frozen old exclusion. Original raw storage,
 * remains in the archive. Local proof qualification never crosses an epoch/replica boundary.
 */
internal object NextReplicaSourceClosure {
    fun controlHash(fence: NextReplicaFenceEntity?): String? = fence?.let { syncPayloadHash(row(it).toString()) }

    fun storage(proof: ReplicaStorageProof, actual: NextReplicaFenceEntity?): String {
        val tables = proof.contents.getValue("tables").jsonObject
        val expected = JsonArray(actual?.let { listOf(row(it)) }.orEmpty())
        require(tables.getValue("next_replica_fence") == expected) { "SYNC_REPLICA_FENCE_CHANGED" }
        val version = proof.contents.getValue("user_version").jsonPrimitive.int
        val inventory = com.dayforge.data.local.CountCommandV20Schema.localTables(version)
        val local = if (version >= 20) com.dayforge.data.local.CountTrustSchema.LOCAL_TABLES - "count_command_results"
            else com.dayforge.data.local.CountTrustSchema.LOCAL_TABLES
        require(tables.keys.containsAll(inventory)) { "SYNC_REPLICA_RECOVERY_SCHEMA_REVIEW_REQUIRED" }
        val normalized = JsonObject(proof.contents + ("tables" to JsonObject(tables +
            (local + "next_replica_fence").associateWith { JsonArray(emptyList()) })))
        return syncPayloadHash(normalized.toString())
    }

    fun input(input: ReplicaArchiveInput, actual: NextReplicaFenceEntity?): String =
        syncPayloadHash(JsonObject(input.manifest + ("business_hash" to JsonPrimitive(storage(input.storage, actual)))).toString())

    /** Retain the immutable old stage hash. Only the reviewed additive migration has a view. */
    fun matchesStorage(proof: ReplicaStorageProof, actual: NextReplicaFenceEntity?, expectedHash: String): Boolean =
        storage(proof, actual) == expectedHash || NextReplicaCompatibilityViews.projected(proof).any {
            previousStorage(it, actual) == expectedHash
        }

    fun matchesInput(input: ReplicaArchiveInput, actual: NextReplicaFenceEntity?, expectedHash: String): Boolean =
        this.input(input, actual) == expectedHash || NextReplicaCompatibilityViews.projected(input.storage).any {
            manifestHash(input.manifest, previousStorage(it, actual)) == expectedHash
        }

    /** Both hashes must describe the SAME complete raw view. Do not mix a v18 archive body
     * with a current manifest, and do not replace verification of the retained archive file.
     */
    fun matchesArchiveInput(input: ReplicaArchiveInput, inputHash: String, storageHash: String): Boolean =
        matchesArchiveInput(input.manifest, input.storage, inputHash, storageHash)

    internal fun matchesArchiveInput(manifest: JsonObject, proof: ReplicaStorageProof,
        inputHash: String, storageHash: String): Boolean {
        require(manifest.getValue("business_hash") == JsonPrimitive(proof.hash)) { "SYNC_ARCHIVE_INPUT_CHANGED" }
        return syncPayloadHash(manifest.toString()) == inputHash && proof.hash == storageHash ||
            NextReplicaCompatibilityViews.projected(proof).any {
                manifestHash(manifest, it.hash) == inputHash && it.hash == storageHash
            }
    }

    private fun manifestHash(manifest: JsonObject, storageHash: String) =
        syncPayloadHash(JsonObject(manifest + ("business_hash" to JsonPrimitive(storageHash))).toString())

    private fun previousStorage(proof: ReplicaStorageProof, actual: NextReplicaFenceEntity?): String {
        if (proof.contents.getValue("user_version") == JsonPrimitive(19)) return storage(proof, actual)
        val tables = proof.contents.getValue("tables").jsonObject
        require(proof.contents.getValue("user_version") == JsonPrimitive(18) &&
            tables.keys.none { it in com.dayforge.data.local.CountTrustSchema.LOCAL_TABLES } &&
            tables.getValue("next_replica_fence") == JsonArray(actual?.let { listOf(row(it)) }.orEmpty())) {
            "SYNC_REPLICA_FENCE_CHANGED"
        }
        return syncPayloadHash(JsonObject(proof.contents + ("tables" to JsonObject(tables +
            ("next_replica_fence" to JsonArray(emptyList()))))).toString())
    }

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
