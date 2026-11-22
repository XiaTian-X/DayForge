package com.dayforge.data.repository

import com.dayforge.data.local.ReplicaSchemaSql
import com.dayforge.data.local.ReplicaSqlSchema
import java.util.Locale
import kotlinx.serialization.json.*

/** Pure schema comparison only. No ReplicaStorageProof, old tables, source hash or permission
 * escapes. Native fresh index metadata is checked before any owned object is filtered out. */
internal object NextReplicaResetSchemaComparison {
    fun matches(raw: ReplicaStorageProof, fresh: Map<String, ReplicaSqlSchema.SchemaObject>, expected: String): Boolean {
        val previous = NextReplicaV19StorageView.resetSchemaRows(raw) ?: return false
        val current = raw.contents.getValue("schema").jsonArray
        fun text(row: JsonObject, key: String) = row.getValue(key).jsonObject.getValue("value").jsonPrimitive.content
        fun key(row: JsonObject) = "${text(row, "type")}.${text(row, "name").lowercase(Locale.ROOT)}"
        val keys = current.map { key(it.jsonObject) }
        if (keys.size != keys.toSet().size || keys.toSet() != fresh.keys) return false
        fun parsed(row: JsonObject): ReplicaSqlSchema.SchemaObject {
            val type = text(row, "type"); val name = text(row, "name").lowercase(Locale.ROOT)
            val owner = text(row, "tbl_name").lowercase(Locale.ROOT)
            if (type == "index") return fresh.getValue(key(row))
            val tokens = ReplicaSchemaSql.tokens(text(row, "sql"))
            return ReplicaSqlSchema.SchemaObject(type, name, owner,
                if (type == "table") ReplicaSchemaSql.table(tokens, name) else listOf(tokens))
        }
        // Retained index metadata participates in the original target hash below. Every
        // removed index has a fixed original manifest and must match its actual fresh PRAGMA.
        for (item in current) {
            val row = item.jsonObject; val actual = fresh.getValue(key(row))
            if (actual.type != text(row, "type") || actual.owner != text(row, "tbl_name").lowercase(Locale.ROOT)) return false
            if (actual.type != "index") {
                if (actual != parsed(row)) return false
            } else removedIndices[actual.name]?.let { shape ->
                val header = listOf(actual.name, if (shape.first) "1" else "0", if (shape.first) "pk" else "c", "0")
                val columns = shape.second.mapIndexed { index, column -> listOf(index.toString(), "column", column, "0", "binary", "1") }
                val rowid = listOf(shape.second.size.toString(), "rowid", "", "0", "binary", "0")
                if (actual.definition != listOf(header) + columns + listOf(rowid)) return false
            }
        }
        return previous.any { (version, schema) ->
            val objects = schema.associate { element -> element.jsonObject.let { key(it) to parsed(it) } }
            ReplicaProjection.schemaHash(version, objects) == expected
        }
    }

    // Exact fixed CREATE declarations in CountCommandV20Schema and frozen CountTrustSchema.
    // All have BINARY/ASC ordinary rowid storage; any future definition requires review here.
    private val removedIndices = mapOf(
        "sqlite_autoindex_count_command_intents_1" to (true to listOf("commandid")),
        "sqlite_autoindex_count_command_status_1" to (true to listOf("commandid")),
        "sqlite_autoindex_count_command_nodes_1" to (true to listOf("digest")),
        "sqlite_autoindex_count_trust_nodes_1" to (true to listOf("digest")),
        "sqlite_autoindex_count_trust_routes_1" to (true to listOf("sourcetable", "sourcekey")),
        "sqlite_autoindex_count_command_results_1" to (true to listOf("commandid")),
        "index_count_trust_routes_owneruuid_businessdate" to (false to listOf("owneruuid", "businessdate")),
        "index_count_trust_routes_entityuuid" to (false to listOf("entityuuid")),
        "index_count_trust_routes_requestid" to (false to listOf("requestid"))
    )
}
