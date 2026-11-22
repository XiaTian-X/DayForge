package com.dayforge.data.repository

import com.dayforge.data.local.ReplicaSqlSchema
import com.dayforge.data.local.ReplicaSchemaSql

import android.database.Cursor
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.CountTrustSchema
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.NextReplicaFenceSql
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** The sole reviewed recovery compatibility edge is the additive 18 -> 19 migration.
 * This is a comparison view, never a replacement image, a rewritten stage or a root grant.
 * Every old typed row and sqlite_master byte (including rootpage) remains in the old hash.
 */
internal object NextReplicaV18StorageView {
    const val PREVIOUS_ROOM_IDENTITY = "ea4615ceb013fc2a9269d6ec9077adeb"
    // Filled from the first generated v19 schema before the implementation/test freeze.
    const val CURRENT_ROOM_IDENTITY = "0461f88592d1a5af33ac34f052cd4f83"

    private val autoIndexes = mapOf(
        "sqlite_autoindex_count_trust_nodes_1" to "count_trust_nodes",
        "sqlite_autoindex_count_trust_routes_1" to "count_trust_routes",
        "sqlite_autoindex_count_command_results_1" to "count_command_results"
    )
    private data class Definition(val type: String, val declaredSql: String, val storedSql: String,
        val tokens: List<String>)

    // Reuse only this build's exact fixed DDL spellings and pure normalization. All actual
    // fields/content are still read; any different text follows the unchanged strict parser.
    private val definitions: Map<String, Definition> by lazy {
        val declared = CountTrustSchema.ownedDefinitions() +
            NextReplicaFenceSql.localTrustDefinitions().mapValues { "trigger" to it.value }
        Collections.unmodifiableMap(declared.mapValues { (_, definition) ->
            val sql = definition.second
            // SQLite removes this header from stored trigger DDL. Derive its alternate only
            // from the fixed declaration, never by rewriting an unknown actual SQL input.
            val prefix = "CREATE TRIGGER IF NOT EXISTS "
            val stored = if (sql.startsWith(prefix)) "CREATE TRIGGER " + sql.removePrefix(prefix) else sql
            Definition(definition.first, sql, stored, Collections.unmodifiableList(normalized(sql).toList()))
        })
    }
    private val ownedNames: Set<String> by lazy {
        Collections.unmodifiableSet(definitions.keys + autoIndexes.keys)
    }
    internal val removedSchemaKeys: Set<String> by lazy {
        Collections.unmodifiableSet(definitions.map { (name, value) ->
            "${value.type}.${name.lowercase(Locale.ROOT)}"
        }.toSet() + autoIndexes.keys.map { "index.${it.lowercase(Locale.ROOT)}" })
    }

    fun project(proof: ReplicaStorageProof): ReplicaStorageProof? = projectContents(proof)?.let {
        ReplicaStorageProof(it, syncPayloadHash(it.toString()))
    }

    /** Same strict comparison shape for bounded byte accounting; no unused legacy hash. */
    fun projectContents(proof: ReplicaStorageProof): JsonObject? {
        if (proof.contents.getValue("user_version") != JsonPrimitive(19)) return null
        val schema = proof.contents.getValue("schema").jsonArray
        val tables = proof.contents.getValue("tables").jsonObject
        val retained = previousSchemaRows(schema)
        require(tables.keys.containsAll(CountTrustSchema.LOCAL_TABLES)) { "SYNC_REPLICA_V18_VIEW_INVALID" }
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
            "schema" to retained, "tables" to oldTables))
        return old
    }

    /** Exact schema-only reuse of the original additive19->18 validation. No table rows,
     * source/archive hash or execution permission are returned by this pure helper. */
    internal fun previousSchemaRows(schema: JsonArray): JsonArray {
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
                    val definition = expected.getValue(name)
                    require(type == definition.type && (if (type == "trigger") integer(row, "rootpage") == 0L else integer(row, "rootpage") > 0L) &&
                        matchesFixedDefinition(text(row, "sql"), definition)) {
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
        require(seen == ownedNames) { "SYNC_REPLICA_V18_VIEW_INVALID" }
        return JsonArray(retained)
    }

    /** Comparison uses the same parsed schema/index metadata as the original target hash.
     * Full typed storage validates that only the precisely owned additions can disappear.
     */
    suspend fun matchesSchema(database: HabitDatabase, expectedHash: String): Boolean = database.withTransaction {
        matchesSchemaInTransaction(database, expectedHash)
    }

    internal suspend fun matchesSchemaInTransaction(database: HabitDatabase, expectedHash: String,
        snapshot: ReplicaStorageSnapshot? = null): Boolean {
        check(database.inTransaction()) { "SYNC_REPLICA_AMBIENT_TRANSACTION" }
        val runtime = CountTrustRuntime.forDatabase(database)
        if (runtime == null) {
            // An unowned handle can reset schema_version. Preserve its original independent
            // reads; a transaction alone does not prove that a callback cannot change schema.
            if (ReplicaProjection.schemaHash(database) == expectedHash) return true
            val proof = NextReplicaStorageProof(database).readInTransaction()
            if (projectContents(proof) == null) return false
            val old = ReplicaSqlSchema.read(database.openHelper.writableDatabase).filterKeys { it !in removedSchemaKeys }
            return ReplicaProjection.schemaHash(18, old) == expectedHash
        }

        // The owner survives Room's query-observer wrapper. Recovery may have retired COUNT
        // qualification, but its SQL boundary still rejects schema_version setters and native
        // handle escape. Bind both owner generation and the native main schema cookie so DDL
        // (even drop/recreate of equal text) cannot make an earlier parsed view reusable.
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val sql = database.openHelper.writableDatabase
        val generation = runtime.generation
        val initialSchemaVersion = schemaVersion(sql)
        val version = sql.version
        val objects = ReplicaSqlSchema.read(sql)
        val matches = if (ReplicaProjection.schemaHash(version, objects) == expectedHash) true else {
            val proof = snapshot?.storage(database) ?: NextReplicaStorageProof(database).readInTransaction()
            if (projectContents(proof) == null) false else {
                // Normalize only names of the owned additions already checked in full raw
                // storage. The retained v18 framing and every original object stay unchanged.
                val old = objects.filterKeys { it !in removedSchemaKeys }
                ReplicaProjection.schemaHash(18, old) == expectedHash
            }
        }
        caller.ensureActive()
        val finalSchemaVersion = schemaVersion(sql)
        check(CountTrustRuntime.forDatabase(database) === runtime && runtime.generation == generation &&
            finalSchemaVersion == initialSchemaVersion) { "SYNC_REPLICA_SCHEMA_CHANGED" }
        caller.ensureActive()
        return matches
    }

    private fun schemaVersion(sql: SupportSQLiteDatabase): Long = sql.query("PRAGMA main.schema_version").use { cursor ->
        require(cursor.columnCount == 1 && cursor.moveToFirst() && cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER) {
            "SYNC_RECOVERY_SCHEMA_INVALID"
        }
        val version = cursor.getLong(0)
        require(!cursor.moveToNext()) { "SYNC_RECOVERY_SCHEMA_INVALID" }
        version
    }

    private fun matchesFixedDefinition(actualSql: String, expected: Definition): Boolean =
        actualSql == expected.declaredSql || actualSql == expected.storedSql || normalized(actualSql) == expected.tokens

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
