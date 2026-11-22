@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.local.CountTrustSchema
import com.dayforge.data.local.CountCommandV20Schema
import com.dayforge.data.local.ReplicaArchiveLimits
import java.io.OutputStream
import java.util.UUID
import kotlinx.serialization.json.*

/** The old complete data image remains bounded at32MiB. Exact additive schema, invalidation
 * control and v20's single strict bounded primary-root header use the same512KiB metadata
 * allowance. All Intent/Status/Outcome/CommandNodes and optional COUNT payloads count as data.
 * Counting views are never old-source compatibility or trust; raw contents/hash stay intact.
 */
internal object ReplicaStorageBudget {
    const val BUSINESS_LIMIT = ReplicaArchiveLimits.BUSINESS_BYTES
    const val OWNED_METADATA_LIMIT = ReplicaArchiveLimits.OWNED_METADATA_BYTES
    const val RAW_LIMIT = ReplicaArchiveLimits.RAW_STORAGE_BYTES

    fun requireWithin(contents: JsonObject, observedBytes: Long? = null): Long {
        val rawBytes = bytes(contents)
        require((observedBytes == null || observedBytes == rawBytes) && rawBytes <= RAW_LIMIT) {
            "SYNC_RECOVERY_PREVIEW_TOO_LARGE"
        }
        // Preserve all original small-image behavior, including unapproved diagnostic images.
        // A complete schema approval remains a separate recovery gate.
        if (rawBytes <= BUSINESS_LIMIT) return rawBytes
        val version = contents.getValue("user_version")
        val v19 = if (version == JsonPrimitive(20)) requireNotNull(
            NextReplicaV19StorageView.accountingContents(ReplicaStorageProof(contents, ""))) else contents
        val previous = requireNotNull(NextReplicaV18StorageView.projectContents(ReplicaStorageProof(v19, ""))) {
            "SYNC_RECOVERY_PREVIEW_TOO_LARGE"
        }
        val currentTables = contents.getValue("tables").jsonObject
        val controls = currentTables.getValue("count_trust_control").jsonArray
        require(controls.size == 1) { "SYNC_RECOVERY_PREVIEW_TOO_LARGE" }
        val control = controls.single().jsonObject
        require(control.keys == setOf("__recovery_rowid", "id", "storageId", "generation", "dirty") +
            if (version == JsonPrimitive(20)) setOf("commandRootHash") else emptySet())
        if (version == JsonPrimitive(20)) {
            val anchor = control.getValue("commandRootHash").jsonObject
            require(anchor.keys == setOf("sqlite_type", "value") && anchor["sqlite_type"] == JsonPrimitive(Cursor.FIELD_TYPE_STRING) &&
                anchor.getValue("value").jsonPrimitive.isString && anchor.getValue("value").jsonPrimitive.content.matches(Regex("[0-9a-f]{64}")))
        }
        fun integer(name: String): Long = control.getValue(name).jsonObject.let {
            require(it.keys == setOf("sqlite_type", "value") && it["sqlite_type"] == JsonPrimitive(Cursor.FIELD_TYPE_INTEGER) &&
                !it.getValue("value").jsonPrimitive.isString)
            it.getValue("value").jsonPrimitive.long
        }
        val storage = control.getValue("storageId").jsonObject.let {
            require(it.keys == setOf("sqlite_type", "value") && it["sqlite_type"] == JsonPrimitive(Cursor.FIELD_TYPE_STRING) &&
                it.getValue("value").jsonPrimitive.isString)
            it.getValue("value").jsonPrimitive.content
        }
        require(integer("__recovery_rowid") == 1L && integer("id") == 1L && integer("generation") >= 0 &&
            integer("dirty") in 0L..1L && storage.length == 36 && UUID.fromString(storage).toString() == storage)
        val dataTables = (CountTrustSchema.LOCAL_TABLES - "count_trust_control") +
            if (version == JsonPrimitive(20)) CountCommandV20Schema.tables - "count_command_recovery_roots" else emptySet()
        val extraData = dataTables.mapNotNull { table ->
            val rows = currentTables.getValue(table).jsonArray
            if (rows.isEmpty()) null else table to rows
        }.toMap()
        val dataImage = JsonObject(previous + ("tables" to JsonObject(previous.getValue("tables").jsonObject + extraData)))
        val dataBytes = bytes(dataImage)
        require(dataBytes <= BUSINESS_LIMIT && rawBytes - dataBytes in 0..OWNED_METADATA_LIMIT) {
            "SYNC_RECOVERY_PREVIEW_TOO_LARGE"
        }
        return dataBytes
    }

    private fun bytes(value: JsonObject): Long {
        var count = 0L
        Json.encodeToStream(JsonObject.serializer(), value, object : OutputStream() {
            override fun write(value: Int) { count++; require(count <= RAW_LIMIT) { "SYNC_RECOVERY_PREVIEW_TOO_LARGE" } }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                count += length; require(count <= RAW_LIMIT) { "SYNC_RECOVERY_PREVIEW_TOO_LARGE" }
            }
        })
        return count
    }
}
