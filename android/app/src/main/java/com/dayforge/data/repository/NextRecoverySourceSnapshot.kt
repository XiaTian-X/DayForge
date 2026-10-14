package com.dayforge.data.repository

import com.dayforge.data.appearance.strictAppearanceJson
import com.dayforge.data.local.entity.SyncOutboxEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Exact source-row representation, not a replacement request or a relaxed live-origin check. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal object NextRecoverySourceSnapshot {
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private val columns = setOf("id", "operationId", "recordType", "entityUuid", "wireEntityUuid", "action",
        "referenceUuid", "payloadJson", "baseRevision", "basePayloadJson", "attemptedAt", "attemptCount",
        "lastError", "errorCode", "deadLetteredAt", "createdAt")

    suspend fun encode(row: SyncOutboxEntity, sourceHash: String): ByteArray {
        currentCoroutineContext().ensureActive()
        requireHash(row, sourceHash)
        val bytes = json.encodeToString(row).toByteArray(Charsets.UTF_8)
        require(bytes.size <= NEXT_RECOVERY_SOURCE_BYTES)
        require(decode(bytes, sourceHash) == row)
        return bytes
    }

    suspend fun decode(bytes: ByteArray, sourceHash: String): SyncOutboxEntity {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val text = strictAppearanceJson(bytes, NEXT_RECOVERY_SOURCE_BYTES, { caller.ensureActive() }) {
            error("RECOVERY_SOURCE_$it")
        }
        require(json.parseToJsonElement(text).jsonObject.keys == columns) { "RECOVERY_SOURCE_FIELDS_INVALID" }
        return json.decodeFromString<SyncOutboxEntity>(text).also { requireHash(it, sourceHash) }
    }

    private fun requireHash(row: SyncOutboxEntity, hash: String) {
        require(row.id > 0 && hash.matches(Regex("[0-9a-f]{64}")) && NextRequestSql.sourceHash(row) == hash) {
            "RECOVERY_SOURCE_HASH_CHANGED"
        }
    }
}
