package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.local.CountCommandControlSchema
import com.dayforge.data.local.CountCommandV20Schema
import com.dayforge.data.local.ReplicaSchemaSql
import com.dayforge.data.local.ReplicaSqlSchema
import com.dayforge.data.local.entity.CountCommandRecoveryRootEntity
import kotlinx.serialization.json.*

/** Sole additive20 ->19 COMPARISON view. No table import, schema repair, root issuance or
 * business permission. Nonempty enrolled commands can never disappear into an old view.
 * Every retained v19 typed cell and sqlite_master rootpage remains byte-for-byte in the hash.
 */
internal object NextReplicaV19StorageView {
    const val PREVIOUS_ROOM_IDENTITY = "0461f88592d1a5af33ac34f052cd4f83"
    // Integration gate: replace only from the real joint KSP20 export. No predicted schema
    // identity is allowed to authorize a v20 -> v19 comparison before that build evidence.
    const val CURRENT_ROOM_IDENTITY = "73032af79771976bf5fd94d2715d5119"
    private val owned get() = CountCommandV20Schema.definitions.keys
    internal val removedSchemaKeys: Set<String> get() = CountCommandV20Schema.definitions.map { (name, definition) ->
        "${definition.type}.${name.lowercase(java.util.Locale.ROOT)}"
    }.toSet()

    fun project(proof: ReplicaStorageProof): ReplicaStorageProof? = projectContents(proof)?.let {
        ReplicaStorageProof(it, syncPayloadHash(it.toString()))
    }

    /** A completed authorized whole-store reset may still be compared to a retained target
     * schema. This returns ONLY validated schema rows, never old table contents or a storage
     * proof. The original migration-only projectContents/source/archive rules stay separate. */
    internal fun resetSchemaRows(proof: ReplicaStorageProof): Map<Int, JsonArray>? {
        if (proof.contents.getValue("user_version") != JsonPrimitive(20)) return null
        val schema = validatedSchema(proof)
        val tables = proof.contents.getValue("tables").jsonObject
        val roots = tables.getValue("count_command_recovery_roots").jsonArray
        require(roots.size == 1) { "COMMAND_PRIMARY_ROOT_MISSING" }
        val controls = tables.getValue("count_trust_control").jsonArray
        require(controls.size == 1)
        val currentControl = control(controls.single().jsonObject)
        val untyped = schema.map { item -> item.jsonObject.let { row ->
            listOf(text(row, "type"), text(row, "name"), text(row, "tbl_name"), nullableText(row, "sql"))
        } }
        val header = CountCommandSeal().authenticate(root(roots.single().jsonObject), text(currentControl, "storageId"),
            com.dayforge.data.local.CountCommandPrimarySchema.hash(untyped)) { nullableText(currentControl, "commandRootHash") }.header
        if (header.mode != CountCommandRootCodec.Mode.EMPTY || header.source != CountCommandRootCodec.Source.AUTHORIZED_CLEAR_REPLACE ||
            header.revision != 1L) return null
        require(listOf("count_command_intents", "count_command_status", "count_command_nodes", "count_command_results")
            .all { tables.getValue(it).jsonArray.isEmpty() }) { "COMMAND_INITIAL_ANCHOR_CONTENT_CHANGED" }
        val identity = tables.getValue("room_master_table").jsonArray.single().jsonObject
        require(identity.keys == setOf("__recovery_rowid", "id", "identity_hash") && number(identity, "__recovery_rowid") == 42L &&
            number(identity, "id") == 42L && text(identity, "identity_hash") == CURRENT_ROOM_IDENTITY) { "SYNC_REPLICA_V19_VIEW_INVALID" }
        val previous = JsonArray(schema.filter { text(it.jsonObject, "name") !in owned }.map { element ->
            val row = element.jsonObject
            val oldSql = when (text(row, "name")) {
                CountCommandControlSchema.TABLE -> CountCommandControlSchema.previousTable(text(row, "sql"))
                CountCommandControlSchema.GUARD -> CountCommandControlSchema.previousGuard(text(row, "sql"))
                else -> null
            }
            if (oldSql == null) element else JsonObject(row + ("sql" to cell(Cursor.FIELD_TYPE_STRING, JsonPrimitive(oldSql))))
        })
        return mapOf(19 to previous, 18 to NextReplicaV18StorageView.previousSchemaRows(previous))
    }

    fun projectContents(proof: ReplicaStorageProof): JsonObject? {
        if (proof.contents.getValue("user_version") != JsonPrimitive(20)) return null
        val schema = validatedSchema(proof)
        val tables = proof.contents.getValue("tables").jsonObject
        val untyped = schema.map { item -> item.jsonObject.let { row ->
            listOf(text(row, "type"), text(row, "name"), text(row, "tbl_name"), nullableText(row, "sql"))
        } }
        val roots = tables.getValue("count_command_recovery_roots").jsonArray
        require(roots.size == 1) { "COMMAND_PRIMARY_ROOT_MISSING" }
        val rootRow = root(roots.single().jsonObject)
        val controls = tables.getValue("count_trust_control").jsonArray
        require(controls.size == 1)
        val control = control(controls.single().jsonObject)
        val schemaHash = com.dayforge.data.local.CountCommandPrimarySchema.hash(untyped)
        // Bad MAC/missing key/unknown schema propagate. They are never an optional miss.
        val initial = CountCommandSeal().authenticate(rootRow, text(control, "storageId"), schemaHash) { nullableText(control, "commandRootHash") }.header
        if (CountCommandRootCodec.bound(initial.mode) || initial.source != CountCommandRootCodec.Source.MIGRATION_19_20 ||
            initial.revision != 1L) return null
        require(initial.mode in setOf(CountCommandRootCodec.Mode.EMPTY, CountCommandRootCodec.Mode.LEGACY_UNENROLLED) &&
            listOf("count_command_intents", "count_command_status", "count_command_nodes").all { tables.getValue(it).jsonArray.isEmpty() }) {
            "COMMAND_INITIAL_ANCHOR_CONTENT_CHANGED"
        }
        val legacy = tables.getValue("count_command_results").jsonArray
        if (initial.mode == CountCommandRootCodec.Mode.EMPTY) require(legacy.isEmpty())
        else require(initial.legacyCount == legacy.size.toLong())
        // The original legacy Outcome cells remain in oldTables below. This comparison does
        // not enroll them as commands or replace native mandatory-open legacy fingerprinting.
        return previousContents(proof, schema)
    }

    /** Shape-only counting view, never accepted for legacy hash comparison. Every command
     * payload is re-added by ReplicaStorageBudget. Only one bounded strict root row is fixed
     * metadata; it is not authenticated or used as a permission by this helper.
     */
    internal fun accountingContents(proof: ReplicaStorageProof): JsonObject? {
        if (proof.contents.getValue("user_version") != JsonPrimitive(20)) return null
        val schema = validatedSchema(proof)
        val tables = proof.contents.getValue("tables").jsonObject
        val roots = tables.getValue("count_command_recovery_roots").jsonArray
        require(roots.size == 1 && roots.toString().toByteArray(Charsets.UTF_8).size <= 16_384) {
            "SYNC_RECOVERY_PREVIEW_TOO_LARGE"
        }
        val header = CountCommandRootCodec.fromEntity(root(roots.single().jsonObject))
        val controls = tables.getValue("count_trust_control").jsonArray
        require(controls.size == 1 && header.storageId == text(control(controls.single().jsonObject), "storageId"))
        return previousContents(proof, schema)
    }

    private fun validatedSchema(proof: ReplicaStorageProof): JsonArray {
        val schema = proof.contents.getValue("schema").jsonArray
        val tables = proof.contents.getValue("tables").jsonObject
        require(tables.keys.containsAll(CountCommandV20Schema.currentLocalTables)) { "SYNC_REPLICA_V19_VIEW_INVALID" }
        val untyped = schema.map { item ->
            val row = item.jsonObject
            require(row.keys == setOf("type", "name", "tbl_name", "rootpage", "sql")) { "SYNC_REPLICA_V19_VIEW_INVALID" }
            require(number(row, "rootpage") >= 0)
            listOf(text(row, "type"), text(row, "name"), text(row, "tbl_name"), nullableText(row, "sql"))
        }
        CountCommandV20Schema.previousSchema(untyped) // Exact complete additions, guards and all autoindexes.
        for (item in schema) {
            val row = item.jsonObject; val definition = CountCommandV20Schema.definitions[text(row, "name")] ?: continue
            require(number(row, "rootpage").let { if (definition.type == "trigger") it == 0L else it > 0L }) {
                "SYNC_REPLICA_V19_VIEW_INVALID"
            }
        }
        return schema
    }

    private fun previousContents(proof: ReplicaStorageProof, schema: JsonArray): JsonObject {
        val tables = proof.contents.getValue("tables").jsonObject
        val master = tables.getValue("room_master_table").jsonArray
        require(master.size == 1)
        val identity = master.single().jsonObject
        require(identity.keys == setOf("__recovery_rowid", "id", "identity_hash") && number(identity, "__recovery_rowid") == 42L &&
            number(identity, "id") == 42L && text(identity, "identity_hash") == CURRENT_ROOM_IDENTITY) { "SYNC_REPLICA_V19_VIEW_INVALID" }
        val oldIdentity = JsonObject(identity + ("identity_hash" to cell(Cursor.FIELD_TYPE_STRING, JsonPrimitive(PREVIOUS_ROOM_IDENTITY))))
        val retainedSchema = schema.filter { text(it.jsonObject, "name") !in owned }.map { element ->
            val row = element.jsonObject
            val previousSql = when (text(row, "name")) {
                CountCommandControlSchema.TABLE -> CountCommandControlSchema.previousTable(text(row, "sql"))
                CountCommandControlSchema.GUARD -> CountCommandControlSchema.previousGuard(text(row, "sql"))
                else -> null
            }
            if (previousSql == null) element else JsonObject(row + ("sql" to cell(Cursor.FIELD_TYPE_STRING, JsonPrimitive(previousSql))))
        }
        val controls = tables.getValue(CountCommandControlSchema.TABLE).jsonArray
        require(controls.size == 1)
        val oldControl = JsonObject(control(controls.single().jsonObject).filterKeys { it != CountCommandControlSchema.COLUMN })
        val retainedTables = JsonObject(tables.filterKeys { it !in CountCommandV20Schema.tables } +
            mapOf("room_master_table" to JsonArray(listOf(oldIdentity)), CountCommandControlSchema.TABLE to JsonArray(listOf(oldControl))))
        return JsonObject(proof.contents + mapOf("user_version" to JsonPrimitive(19), "schema" to JsonArray(retainedSchema), "tables" to retainedTables))
    }

    private fun control(row: JsonObject): JsonObject {
        require(row.keys == setOf("__recovery_rowid", "id", "storageId", "generation", "dirty", "commandRootHash") &&
            number(row, "__recovery_rowid") == 1L && number(row, "id") == 1L && number(row, "generation") >= 0L &&
            number(row, "dirty") in 0L..1L && com.dayforge.domain.model.isContractUuid(text(row, "storageId"))) {
            "COMMAND_PRIMARY_ANCHOR_INVALID"
        }
        val anchor = nullableText(row, "commandRootHash")
        require(anchor != null && anchor.length == 64 && anchor.all { it in "0123456789abcdef" }) { "COMMAND_PRIMARY_ANCHOR_INVALID" }
        return row
    }
    /** Parsed-schema comparison must replace the two altered old objects, not merely
     * remove the four new tables. Require the independent fresh schema to match their
     * exact captured definitions before substituting the authenticated old proof's versions.
     * This is pure same-proof work; no new native read/cache or execution grant. */
    internal fun parsedPrevious(current: ReplicaStorageProof, previous: ReplicaStorageProof,
        fresh: Map<String, ReplicaSqlSchema.SchemaObject>): Map<String, ReplicaSqlSchema.SchemaObject>? {
        require(current.contents.getValue("user_version") == JsonPrimitive(20) &&
            previous.contents.getValue("user_version") == JsonPrimitive(19))
        val actualSchema = current.contents.getValue("schema").jsonArray
        val oldSchema = previous.contents.getValue("schema").jsonArray
        fun objectAt(schema: JsonArray, name: String): ReplicaSqlSchema.SchemaObject {
            val row = schema.single { text(it.jsonObject, "name") == name }.jsonObject
            val type = text(row, "type"); val owner = text(row, "tbl_name")
            val tokens = ReplicaSchemaSql.tokens(text(row, "sql"))
            require(type == if (name == CountCommandControlSchema.TABLE) "table" else "trigger")
            return ReplicaSqlSchema.SchemaObject(type, name.lowercase(java.util.Locale.ROOT), owner.lowercase(java.util.Locale.ROOT),
                if (type == "table") ReplicaSchemaSql.table(tokens, name) else listOf(tokens))
        }
        val result = fresh.filterKeys { it !in removedSchemaKeys }.toMutableMap()
        for (name in listOf(CountCommandControlSchema.TABLE, CountCommandControlSchema.GUARD)) {
            val captured = objectAt(actualSchema, name)
            val key = "${captured.type}.${captured.name}"
            if (fresh[key] != captured) return null
            result[key] = objectAt(oldSchema, name)
        }
        return result.toMap()
    }
    private fun root(row: JsonObject): CountCommandRecoveryRootEntity {
        require(row.keys == (CountCommandNativeRows.ROOT_COLUMNS + "__recovery_rowid").toSet() && number(row, "__recovery_rowid") == 1L)
        fun t(name: String) = text(row, name)
        fun nt(name: String) = nullableText(row, name)
        fun n(name: String) = number(row, name)
        fun nn(name: String) = if (isNull(row, name)) null else n(name)
        val blob = row.getValue("mac").jsonObject
        require(blob.keys == setOf("sqlite_type", "value") && blob["sqlite_type"] == JsonPrimitive(Cursor.FIELD_TYPE_BLOB))
        val hex = blob.getValue("value").jsonPrimitive.also { require(it.isString) }.content
        require(hex.matches(Regex("[0-9a-f]{64}")))
        require(n("id") == 1L)
        return CountCommandRecoveryRootEntity(1, t("mode"), t("storageId"), t("datasetId"), t("initializationSource"),
            nt("namespaceHash"), nt("namespaceJson"), nt("accountBindingHash"), nt("registrationPreviousHash"), nt("registrationInstallationId"),
            Math.toIntExact(n("format")), t("validator"), t("schemaHash"), nt("catalogHash"), nn("catalogHeight")?.let(Math::toIntExact),
            nn("catalogCount"), nt("pendingHeadId"), nt("pendingTailId"), n("pendingCount"), nn("legacyCount"), nt("legacyHash"), n("revision"),
            ByteArray(32) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() })
    }
    private fun isNull(row: JsonObject, name: String): Boolean = row.getValue(name) == cell(Cursor.FIELD_TYPE_NULL, JsonNull)
    private fun nullableText(row: JsonObject, name: String) = if (isNull(row, name)) null else text(row, name)
    private fun text(row: JsonObject, name: String): String {
        val c = row.getValue(name).jsonObject
        require(c.keys == setOf("sqlite_type", "value") && c["sqlite_type"] == JsonPrimitive(Cursor.FIELD_TYPE_STRING) && c.getValue("value").jsonPrimitive.isString)
        return c.getValue("value").jsonPrimitive.content
    }
    private fun number(row: JsonObject, name: String): Long {
        val c = row.getValue(name).jsonObject; val value = c.getValue("value").jsonPrimitive
        require(c.keys == setOf("sqlite_type", "value") && c["sqlite_type"] == JsonPrimitive(Cursor.FIELD_TYPE_INTEGER) && !value.isString)
        return requireNotNull(value.longOrNull).also { require(value.content == it.toString()) }
    }
    private fun cell(type: Int, value: JsonElement) = buildJsonObject { put("sqlite_type", type); put("value", value) }
}
