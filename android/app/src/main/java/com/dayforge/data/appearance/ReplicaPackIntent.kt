package com.dayforge.data.appearance

import android.database.Cursor
import com.dayforge.domain.model.IconPack
import kotlinx.serialization.json.*

/** Archived device preference only, never destination readiness or a selection override.
 * Created by the full archive reader; the recovery lease fixes this value for its entire lifetime.
 */
internal class ReplicaPackIntent private constructor(val originalGeneration: Long, val pack: IconPack?) {
    val version get() = pack?.let { IconPackVersion(it.packId, it.revision) }
    fun requireRestored(selection: AccountIconSelection) {
        check(selection == AccountIconSelection(if (originalGeneration == 0L) 0 else 1, version)) {
            "ICON_RECOVERY_SELECTION_CHANGED"
        }
    }
    fun requirePack(value: IconPack?) {
        check(value == pack) { "ICON_RECOVERY_PACK_CHANGED" }
    }

    companion object {
        /** Only called with the actual fully verified icons.json frame, not a new-scope DTO. */
        internal fun fromArchive(namespace: AccountIconNamespace, contents: JsonObject, checkpoint: () -> Unit): ReplicaPackIntent {
            require(contents.getValue("namespace") == buildJsonObject {
                put("account_id", namespace.accountId); put("server_instance_id", namespace.serverInstanceId)
                put("sync_epoch", namespace.syncEpoch)
            }) { "ICON_RECOVERY_ARCHIVE_SCOPE_CHANGED" }
            fun cell(row: JsonObject, name: String, type: Int): JsonElement {
                val cell = row.getValue(name).jsonObject
                require(cell.keys == setOf("sqlite_type", "value") && cell.getValue("sqlite_type") == JsonPrimitive(type)) {
                    "ICON_RECOVERY_ARCHIVE_VALUE_INVALID"
                }
                return cell.getValue("value")
            }
            fun scope(row: JsonObject) {
                require(cell(row, "accountId", Cursor.FIELD_TYPE_STRING) == JsonPrimitive(namespace.accountId) &&
                    cell(row, "serverInstanceId", Cursor.FIELD_TYPE_STRING) == JsonPrimitive(namespace.serverInstanceId) &&
                    cell(row, "syncEpoch", Cursor.FIELD_TYPE_STRING) == JsonPrimitive(namespace.syncEpoch)) {
                    "ICON_RECOVERY_ARCHIVE_SCOPE_CHANGED"
                }
            }
            val tables = contents.getValue("tables").jsonObject
            val selections = tables.getValue("icon_pack_selection").jsonArray
            require(selections.size <= 1) { "ICON_RECOVERY_ARCHIVE_SELECTION_INVALID" }
            if (selections.isEmpty()) return ReplicaPackIntent(0, null)
            val row = selections.single().jsonObject; scope(row)
            val generation = cell(row, "generation", Cursor.FIELD_TYPE_INTEGER).jsonPrimitive.long
            require(generation > 0)
            val rawId = row.getValue("packId").jsonObject
            if (rawId.getValue("sqlite_type") == JsonPrimitive(Cursor.FIELD_TYPE_NULL)) {
                require(cell(row, "packId", Cursor.FIELD_TYPE_NULL) == JsonNull &&
                    cell(row, "revision", Cursor.FIELD_TYPE_NULL) == JsonNull)
                return ReplicaPackIntent(generation, null)
            }
            val id = cell(row, "packId", Cursor.FIELD_TYPE_STRING).jsonPrimitive.content
            val revision = cell(row, "revision", Cursor.FIELD_TYPE_INTEGER).jsonPrimitive.int
            val version = IconPackVersion(id, revision)
            val packs = tables.getValue("icon_packs").jsonArray.map { it.jsonObject }.filter {
                checkpoint()
                scope(it)
                cell(it, "packId", Cursor.FIELD_TYPE_STRING) == JsonPrimitive(version.packId) &&
                    cell(it, "revision", Cursor.FIELD_TYPE_INTEGER) == JsonPrimitive(version.revision)
            }
            require(packs.size == 1) { "ICON_RECOVERY_ARCHIVE_PACK_INVALID" }
            val raw = cell(packs.single(), "metadataJson", Cursor.FIELD_TYPE_STRING).jsonPrimitive.content
            val text = strictAppearanceJson(raw.toByteArray(Charsets.UTF_8), 1_048_576, checkpoint) { error("ICON_RECOVERY_ARCHIVE_PACK_$it") }
            val pack = Json.decodeFromString<IconPack>(text).let { it.copy(
                assets = java.util.Collections.unmodifiableList(it.assets.toList()),
                roles = java.util.Collections.unmodifiableMap(it.roles.toSortedMap())) }
            require(pack.packId == version.packId && pack.revision == version.revision)
            return ReplicaPackIntent(generation, pack)
        }
    }
}
