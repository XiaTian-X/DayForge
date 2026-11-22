package com.dayforge.data.repository

import com.dayforge.data.local.ReplicaSchemaSql

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.local.CountTrustSchema
import com.dayforge.data.local.NextReplicaFenceSql
import java.util.Collections
import kotlinx.serialization.json.*

/** Frozen pre-fast-path pure algorithms. No production fast comparator is called.
 * Constants remain the reviewed declarations; the old transforms/checks are independent.
 */
internal object FixedDdlCountOriginal {
    private const val DIRTY_PREFIX = CountTrustSchema.DIRTY_PREFIX
    private val declarations = CountTrustSchema.ownedDefinitions()
    private val tableSql = declarations.filterValues { it.first == "table" }.mapValues { it.value.second }
    private val indexSql = declarations.filterValues { it.first == "index" }.mapValues { it.value.second }
    private val dirtyTriggers = declarations.filterValues { it.first == "trigger" }.mapValues { it.value.second }

    private const val STORAGE_ID_VALID = "typeof(storageId)='text' AND length(storageId)=36 AND " +
        "storageId=lower(storageId) AND storageId NOT GLOB '*[^0-9a-f-]*' AND " +
        "length(replace(storageId,'-',''))=32 AND substr(storageId,9,1)='-' AND " +
        "substr(storageId,14,1)='-' AND substr(storageId,19,1)='-' AND substr(storageId,24,1)='-'"
    private const val CONTROL_VALID = "typeof(id)='integer' AND id=1 AND $STORAGE_ID_VALID AND " +
        "typeof(generation)='integer' AND generation>=0 AND typeof(dirty)='integer' AND dirty IN (0,1)"

    /** Exact owned DDL and a strict, bounded control-row check; never reads all business data. */
    fun validate(db: SupportSQLiteDatabase) {
        val expected = tableSql + indexSql + dirtyTriggers
        val placeholders = expected.keys.joinToString(",") { "?" }
        val args: Array<Any> = (expected.keys.toList() + listOf(DIRTY_PREFIX.length, DIRTY_PREFIX)).toTypedArray()
        db.query("SELECT type,CASE WHEN length(CAST(name AS BLOB))<=256 THEN name ELSE NULL END," +
            "CASE WHEN typeof(sql)='text' THEN CASE WHEN length(CAST(sql AS BLOB))<=16384 THEN sql ELSE NULL END ELSE NULL END " +
            "FROM sqlite_master WHERE name IN ($placeholders) OR " +
            "(type='trigger' AND substr(name,1,?)=?) LIMIT ${expected.size + 1}", args).use { cursor ->
            val seen = hashSetOf<String>()
            while (cursor.moveToNext()) {
                check((0..2).all { cursor.getType(it) == Cursor.FIELD_TYPE_STRING }) { "COUNT_TRUST_SCHEMA_INVALID" }
                val name = cursor.getString(1)
                val type = when {
                    name in tableSql -> "table"
                    name in indexSql -> "index"
                    else -> "trigger"
                }
                check(seen.add(name) && name in expected && cursor.getString(0) == type &&
                    normalized(cursor.getString(2)) == normalized(expected.getValue(name))) { "COUNT_TRUST_SCHEMA_INVALID" }
            }
            check(seen == expected.keys) { "COUNT_TRUST_SCHEMA_INVALID" }
        }
        requireControl(db)
    }

    fun requireControl(db: SupportSQLiteDatabase) {
        db.query("SELECT (SELECT count(*) FROM (SELECT id FROM count_trust_control LIMIT 2)), " +
            "(SELECT count(*) FROM count_trust_control WHERE $CONTROL_VALID)").use {
            check(it.moveToFirst() && (0..1).all { column -> it.getType(column) == Cursor.FIELD_TYPE_INTEGER && it.getLong(column) == 1L } &&
                !it.moveToNext()) { "COUNT_TRUST_CONTROL_INVALID" }
        }
    }

    // Owned DDL has no user text or arbitrary string payloads. Normalize only its formatting and
    // Room's CREATE IF NOT EXISTS prefix; punctuation, expressions and literal case remain bound.
    private fun normalized(sql: String): String = sql.trim().replace("IF NOT EXISTS ", "")
        .replace("`", "").replace(Regex("\\s+"), "")
}

internal object FixedDdlV18Original {
    const val PREVIOUS_ROOM_IDENTITY = "ea4615ceb013fc2a9269d6ec9077adeb"
    // Filled from the first generated v19 schema before the implementation/test freeze.
    const val CURRENT_ROOM_IDENTITY = "0461f88592d1a5af33ac34f052cd4f83"

    private val autoIndexes = mapOf(
        "sqlite_autoindex_count_trust_nodes_1" to "count_trust_nodes",
        "sqlite_autoindex_count_trust_routes_1" to "count_trust_routes",
        "sqlite_autoindex_count_command_results_1" to "count_command_results"
    )
    // Only this build's fixed DDL is prepared once. Actual sqlite_master bytes are still
    // decoded and normalized on every call; no storage, schema proof or authority is cached.
    private val definitions: Map<String, Pair<String, List<String>>> by lazy {
        val declared = CountTrustSchema.ownedDefinitions() +
            NextReplicaFenceSql.localTrustDefinitions().mapValues { "trigger" to it.value }
        Collections.unmodifiableMap(declared.mapValues { (_, definition) ->
            definition.first to Collections.unmodifiableList(normalized(definition.second).toList())
        })
    }
    private val ownedNames: Set<String> by lazy {
        Collections.unmodifiableSet(definitions.keys + autoIndexes.keys)
    }
    fun project(proof: ReplicaStorageProof): ReplicaStorageProof? = projectContents(proof)?.let {
        ReplicaStorageProof(it, syncPayloadHash(it.toString()))
    }

    /** Same strict comparison shape for bounded byte accounting; no unused legacy hash. */
    fun projectContents(proof: ReplicaStorageProof): JsonObject? {
        if (proof.contents.getValue("user_version") != JsonPrimitive(19)) return null
        val schema = proof.contents.getValue("schema").jsonArray
        val tables = proof.contents.getValue("tables").jsonObject
        val expected = definitions
        val seen = mutableSetOf<String>()
        val retained = schema.filter { value ->
            val row = value.jsonObject
            val name = text(row, "name")
            val owner = text(row, "tbl_name")
            if (name !in ownedNames) {
                require(owner !in CountTrustSchema.LOCAL_TABLES && !name.startsWith(CountTrustSchema.DIRTY_PREFIX)) {
                    "SYNC_REPLICA_V18_VIEW_INVALID"
                }
                true
            } else {
                require(row.keys == setOf("type", "name", "tbl_name", "rootpage", "sql")) {
                    "SYNC_REPLICA_V18_VIEW_INVALID"
                }
                require(seen.add(name)) { "SYNC_REPLICA_V18_VIEW_INVALID" }
                val type = text(row, "type")
                require(integer(row, "rootpage") >= 0) { "SYNC_REPLICA_V18_VIEW_INVALID" }
                if (name in autoIndexes) {
                    require(type == "index" && owner == autoIndexes.getValue(name) &&
                        row.getValue("sql") == cell(Cursor.FIELD_TYPE_NULL, JsonNull) && integer(row, "rootpage") > 0) {
                        "SYNC_REPLICA_V18_VIEW_INVALID"
                    }
                } else {
                    val (expectedType, expectedTokens) = expected.getValue(name)
                    require(type == expectedType && (if (type == "trigger") integer(row, "rootpage") == 0L else integer(row, "rootpage") > 0L) &&
                        normalized(text(row, "sql")) == expectedTokens) {
                        "SYNC_REPLICA_V18_VIEW_INVALID"
                    }
                    // The exact table DDL includes each PK/UNIQUE definition, so only its
                    // enumerated SQLite autoindex may be removed alongside the table.
                    val expectedOwner = when (type) {
                        "table" -> name
                        "index" -> "count_trust_routes"
                        else -> if (name.startsWith(CountTrustSchema.DIRTY_PREFIX))
                            name.removePrefix(CountTrustSchema.DIRTY_PREFIX).substringBeforeLast('_')
                        else name.removePrefix("replica_fence_").substringBeforeLast('_')
                    }
                    require(owner == expectedOwner) { "SYNC_REPLICA_V18_VIEW_INVALID" }
                }
                false
            }
        }
        require(seen == ownedNames && tables.keys.containsAll(CountTrustSchema.LOCAL_TABLES)) {
            "SYNC_REPLICA_V18_VIEW_INVALID"
        }
        val master = tables.getValue("room_master_table").jsonArray
        require(master.size == 1) { "SYNC_REPLICA_V18_VIEW_INVALID" }
        val identity = master.single().jsonObject
        require(identity.keys == setOf("__recovery_rowid", "id", "identity_hash") &&
            integer(identity, "__recovery_rowid") == 42L && integer(identity, "id") == 42L &&
            text(identity, "identity_hash") == CURRENT_ROOM_IDENTITY) { "SYNC_REPLICA_V18_VIEW_INVALID" }
        val oldIdentity = JsonObject(identity + ("identity_hash" to cell(Cursor.FIELD_TYPE_STRING, JsonPrimitive(PREVIOUS_ROOM_IDENTITY))))
        val oldTables = JsonObject(tables.filterKeys { it !in CountTrustSchema.LOCAL_TABLES } +
            ("room_master_table" to JsonArray(listOf(oldIdentity))))
        val old = JsonObject(proof.contents + mapOf("user_version" to JsonPrimitive(18),
            "schema" to JsonArray(retained), "tables" to oldTables))
        return old
    }

    private fun normalized(sql: String): List<String> {
        val tokens = ReplicaSchemaSql.tokens(sql)
        return if (tokens.size >= 5 && tokens.take(1) == listOf("word:create") &&
            tokens.subList(2, 5) == listOf("word:if", "word:not", "word:exists"))
            tokens.take(2) + tokens.drop(5) else tokens
    }
    private fun cell(type: Int, value: JsonElement) = buildJsonObject { put("sqlite_type", type); put("value", value) }
    private fun text(row: JsonObject, column: String): String {
        val value = row.getValue(column).jsonObject
        require(value.keys == setOf("sqlite_type", "value") && value["sqlite_type"] == JsonPrimitive(Cursor.FIELD_TYPE_STRING) &&
            value.getValue("value").jsonPrimitive.isString) { "SYNC_REPLICA_V18_VIEW_INVALID" }
        return value.getValue("value").jsonPrimitive.content
    }
    private fun integer(row: JsonObject, column: String): Long {
        val value = row.getValue(column).jsonObject
        require(value.keys == setOf("sqlite_type", "value") && value["sqlite_type"] == JsonPrimitive(Cursor.FIELD_TYPE_INTEGER) &&
            !value.getValue("value").jsonPrimitive.isString) { "SYNC_REPLICA_V18_VIEW_INVALID" }
        return value.getValue("value").jsonPrimitive.long
    }
}
