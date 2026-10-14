@file:kotlinx.serialization.UseSerializers(com.dayforge.domain.model.ContractStringSerializer::class,
    com.dayforge.domain.model.ContractLongSerializer::class, com.dayforge.domain.model.ContractIntegerSerializer::class)

package com.dayforge.data.repository

import com.dayforge.data.api.dto.ChallengeMetadata
import com.dayforge.data.appearance.strictAppearanceJson
import com.dayforge.domain.model.isContractUuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import com.dayforge.data.local.entity.SyncEntityStateEntity

@Serializable
internal data class NextRecoveryAuditCheckpoint(val accountId: String, val serverInstanceId: String,
    val syncEpoch: String, val deviceId: String, val syncHash: String, val challengeHash: String,
    val metadata: ChallengeMetadata) {
    init {
        require(listOf(accountId, serverInstanceId, syncEpoch, deviceId).all(::isContractUuid))
        require(listOf(syncHash, challengeHash).all(::recoveryHash))
    }
}

/** Frozen immutable row reference. Table/column selection is NOT supplied by a document. */
@Serializable
internal data class NextRecoveryAuditRow(val table: String, val key: NextRecoveryKey, val hash: String) {
    init {
        require(table in setOf("next_request_origins", "next_transmissions", "next_acceptances", "next_rejections",
            "next_structural_dependencies", "next_structural_supersessions"))
        require(recoveryHash(hash) && (table !in setOf("next_structural_dependencies", "next_structural_supersessions") || key.kind == NEXT_OPERATION))
    }
}

@Serializable
internal data class NextRecoveryAuditRequest(val key: NextRecoveryKey, val state: NextRecoveryRequestState,
    val source: NextRecoveryPayloadReference, val sourceHash: String, val originHash: String,
    val transmissionHash: String?, val rejectionHash: String?) {
    init {
        require(key.kind == NEXT_OPERATION && state in setOf(NextRecoveryRequestState.PERMANENT_REJECTION, NextRecoveryRequestState.NEVER_SENT))
        require(source.key == "source:${key.kind}:${key.requestId}" && listOf(sourceHash, originHash).all(::recoveryHash))
        require(listOfNotNull(transmissionHash, rejectionHash).all(::recoveryHash))
        require(if (state == NextRecoveryRequestState.PERMANENT_REJECTION) transmissionHash != null && rejectionHash != null
            else transmissionHash == null && rejectionHash == null)
    }
}

/** Private projection evidence, not a server payload or a revision/ACK. Raw cells preserve local fields. */
@Serializable
internal data class NextRecoveryProjectionSnapshot(val entityType: String, val entityUuid: String,
    val localId: Long?, val body: JsonObject?, val local: JsonObject) {
    init {
        require(entityType in setOf("plan_node", "metric", "activity_metric_link") && isContractUuid(entityUuid))
        require(if (localId == null) body == null && local.isEmpty() else localId > 0 && body != null)
    }
}

/** Exact accepted shadow captured by the writer; null means no accepted object, not a tombstone. */
@Serializable
internal data class NextRecoveryProjectionBaseline(val entityType: String, val entityUuid: String,
    val rowHash: String?, val revision: Long?, val deleted: Boolean?, val payloadJson: String?,
    val payloadHash: String?, val updatedAt: Long?) {
    init {
        require(entityType in setOf("plan_node", "metric", "activity_metric_link") && isContractUuid(entityUuid))
        require(if (rowHash == null) revision == null && deleted == null && payloadJson == null && payloadHash == null && updatedAt == null
            else recoveryHash(rowHash) && revision != null && revision > 0 && deleted != null && payloadJson != null && payloadHash != null && updatedAt != null)
        if (rowHash != null) {
            require(payloadHash == syncPayloadHash(requireNotNull(payloadJson)))
            require(rowHash == NextRequestSql.entityStateHash(SyncEntityStateEntity(entityType, entityUuid,
                requireNotNull(revision), requireNotNull(deleted), payloadJson, payloadHash, requireNotNull(updatedAt))))
        }
    }
    internal val canonical get() = payloadJson?.let {
        Json.parseToJsonElement(strictAppearanceJson(it.toByteArray(Charsets.UTF_8),
            com.dayforge.data.api.SYNC_REQUEST_LIMIT, {}) { error("RECOVERY_BASELINE_$it") }).jsonObject
    }
}

@Serializable
internal data class NextRecoveryAuditProjection(val entityType: String, val entityUuid: String,
    val before: NextRecoveryPayloadReference, val after: NextRecoveryPayloadReference,
    val baseline: NextRecoveryPayloadReference) {
    init {
        require(entityType in setOf("plan_node", "metric", "activity_metric_link") && isContractUuid(entityUuid))
        require(before.key == "projection_before:$entityType:$entityUuid" && after.key == "projection_after:$entityType:$entityUuid")
        require(baseline.key == "projection_base:$entityType:$entityUuid")
    }
}

@Serializable
internal data class NextRecoveryAuditManifest(val contract: Int, val root: NextRecoveryKey,
    val graph: NextRecoveryGraphArchive, val checkpoint: NextRecoveryPayloadReference,
    val requests: List<NextRecoveryAuditRequest>, val rows: List<NextRecoveryAuditRow>,
    val projections: List<NextRecoveryAuditProjection>) {
    init {
        require(contract == 1 && checkpoint.key == "checkpoint" && requests.size in 1..10_000)
        require(requests.map { it.key }.distinct().size == requests.size && root in requests.map { it.key })
        require(rows.size <= 8 * NEXT_RECOVERY_PROOF_NODE_LIMIT && rows.map { it.table to it.key }.distinct().size == rows.size)
        require(projections.size in 1..10_000 && projections.map { it.entityType to it.entityUuid }.distinct().size == projections.size)
    }
}

internal fun recoveryHash(value: String) = value.matches(Regex("[0-9a-f]{64}"))

/** Full defaults, explicit nulls, duplicate/UTF-8/type checks; documents never relax SQL authority. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal object NextRecoveryAuditCodec {
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    suspend inline fun <reified T> encode(value: T): ByteArray = encodeText(value).toByteArray(Charsets.UTF_8)
    inline fun <reified T> encodeText(value: T): String = encodingJson.encodeToString(value)
    internal val encodingJson get() = json
    suspend inline fun <reified T> decode(bytes: ByteArray, limit: Int): T {
        val caller = currentCoroutineContext(); caller.ensureActive()
        val text = strictAppearanceJson(bytes, limit, { caller.ensureActive() }) { error("RECOVERY_AUDIT_$it") }
        val value = encodingJson.decodeFromString<T>(text)
        // Missing default/null fields are not an older or valid receipt. Field order may differ.
        require(encodingJson.parseToJsonElement(text) == encodingJson.parseToJsonElement(encodeText(value))) {
            "RECOVERY_AUDIT_FIELDS_INVALID"
        }
        return value
    }
}
