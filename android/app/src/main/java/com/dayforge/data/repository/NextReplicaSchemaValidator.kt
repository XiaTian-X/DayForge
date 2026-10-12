package com.dayforge.data.repository

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.local.*
import com.dayforge.domain.service.AccountSessionCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Schema/type proof only; does not validate business/source/material closure or authorize restore. */
internal class ReplicaSchemaProof internal constructor(val original: LocalSyncAccess, val storageHash: String) {
    val activationAuthorized: Boolean get() = false
}

/** Compare with this build's actual private Room schema, not a second hand-maintained schema.
 * All changes are confined to an owned in-memory reference DB; active storage is SELECT-only.
 */
internal class NextReplicaSchemaValidator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator
) {
    suspend fun validate(original: LocalSyncAccess, expectedStorageHash: String): ReplicaSchemaProof = withContext(Dispatchers.IO) {
        require(expectedStorageHash.matches(Regex("[0-9a-f]{64}")))
        sessions.exclusive {
            authorize(original)
            val reference = Room.inMemoryDatabaseBuilder(context.applicationContext, HabitDatabase::class.java)
                .addCallback(SyncSchemaCallback).build()
            var failure: Throwable? = null
            try {
                // Statistics are optional in the source, but their schema cannot be guessed.
                val expected = reference.withTransaction {
                    val sql = reference.openHelper.writableDatabase
                    sql.execSQL("ANALYZE")
                    schema(sql)
                }
                database.withTransaction {
                    val sql = database.openHelper.writableDatabase
                    val actual = schema(sql)
                    val optional = setOf("table.sqlite_stat1", "table.sqlite_stat4", "table.android_metadata")
                    require(actual.keys - optional == expected.keys - optional &&
                        actual.all { (key, value) -> expected[key] == value }) { "SYNC_RECOVERY_SCHEMA_INVALID" }
                    require(sql.version == reference.openHelper.writableDatabase.version) { "SYNC_RECOVERY_SCHEMA_INVALID" }
                    val expectedHash = reference.openHelper.writableDatabase.query("SELECT identity_hash FROM room_master_table WHERE id=42").use {
                        require(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_STRING); it.getString(0)
                    }
                    sql.query("SELECT id,identity_hash FROM room_master_table ORDER BY id").use {
                        require(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 42L &&
                            it.getType(1) == Cursor.FIELD_TYPE_STRING && it.getString(1) == expectedHash && !it.moveToNext()) {
                            "SYNC_RECOVERY_SCHEMA_INVALID"
                        }
                    }
                    for (table in actual.values.filter { it.type == "table" }) {
                        currentCoroutineContext().ensureActive()
                        verifyTypes(sql, table.name)
                    }
                    sql.query("PRAGMA integrity_check").use {
                        require(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_STRING && it.getString(0) == "ok" && !it.moveToNext()) {
                            "SYNC_RECOVERY_STORAGE_INVALID"
                        }
                    }
                    sql.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) { "SYNC_RECOVERY_FOREIGN_KEY_INVALID" } }
                    val proof = NextReplicaStorageProof(database).readInTransaction()
                    require(proof.hash == expectedStorageHash) { "SYNC_RECOVERY_PREVIEW_CHANGED" }
                    authorize(original)
                    ReplicaSchemaProof(original.copy(capabilities = original.capabilities.toSet()), proof.hash)
                }
            } catch (error: Throwable) {
                failure = error; throw error
            } finally {
                try { reference.close() } catch (close: Throwable) {
                    if (failure == null) throw close else failure.addSuppressed(close)
                }
            }
        }
    }

    private suspend fun authorize(original: LocalSyncAccess) {
        check(tokens.localSyncAccess() == original && tokens.syncAuthenticationSnapshot(original) != null) { "SYNC_ACCOUNT_CHANGED" }
    }

    private data class SchemaObject(val type: String, val name: String, val owner: String, val definition: List<List<String>>)

    private suspend fun schema(sql: SupportSQLiteDatabase): Map<String, SchemaObject> {
        sql.query("SELECT COALESCE(length(CAST(sql AS BLOB)),0) FROM sqlite_master").use { cursor ->
            while (cursor.moveToNext()) require(cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER && cursor.getLong(0) in 0..262_144L) {
                "SYNC_RECOVERY_SCHEMA_INVALID"
            }
        }
        return sql.query("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name").use { cursor -> buildMap {
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                require((0..2).all { cursor.getType(it) == Cursor.FIELD_TYPE_STRING }) { "SYNC_RECOVERY_SCHEMA_INVALID" }
                val type = cursor.getString(0); val name = cursor.getString(1); val owner = cursor.getString(2)
                require(type in setOf("table", "index", "trigger", "view"))
                require(listOf(name, owner).all { it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) }) { "SYNC_RECOVERY_SCHEMA_INVALID" }
                val definition = when (cursor.getType(3)) {
                    Cursor.FIELD_TYPE_NULL -> {
                        require(type == "index" && name.startsWith("sqlite_autoindex_")) { "SYNC_RECOVERY_SCHEMA_INVALID" }
                        index(sql, name)
                    }
                    Cursor.FIELD_TYPE_STRING -> {
                        val tokens = ReplicaSchemaSql.tokens(cursor.getString(3))
                        when (type) {
                            "table" -> ReplicaSchemaSql.table(tokens, name)
                            // SQLite index PRAGMAs bind column order, uniqueness, collation and direction.
                            "index" -> index(sql, name)
                            else -> listOf(tokens)
                        }
                    }
                    else -> error("SYNC_RECOVERY_SCHEMA_INVALID")
                }
                val canonicalName = name.lowercase(Locale.ROOT)
                val key = "$type.$canonicalName"
                require(put(key, SchemaObject(type, canonicalName, owner.lowercase(Locale.ROOT), definition)) == null)
            }
        } }
    }

    private fun index(sql: SupportSQLiteDatabase, name: String): List<List<String>> {
        val owner = sql.query("SELECT tbl_name FROM sqlite_master WHERE type='index' AND name=?", arrayOf(name)).use {
            require(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_STRING); it.getString(0)
        }
        val header = sql.query("PRAGMA index_list(`$owner`)").use { cursor ->
            var found: List<String>? = null
            while (cursor.moveToNext()) {
                require(cursor.getType(1) == Cursor.FIELD_TYPE_STRING)
                if (cursor.getString(1) == name) {
                    require(found == null && cursor.getType(2) == Cursor.FIELD_TYPE_INTEGER && cursor.getType(3) == Cursor.FIELD_TYPE_STRING &&
                        cursor.getType(4) == Cursor.FIELD_TYPE_INTEGER)
                    found = listOf(name.lowercase(Locale.ROOT), cursor.getLong(2).toString(), cursor.getString(3), cursor.getLong(4).toString())
                    require(cursor.getLong(4) == 0L) { "SYNC_RECOVERY_SCHEMA_INVALID" } // No partial indices in the owned schema.
                }
            }
            requireNotNull(found)
        }
        val columns = sql.query("PRAGMA index_xinfo(`$name`)").use { cursor -> buildList {
            while (cursor.moveToNext()) {
                require(cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER && cursor.getType(1) == Cursor.FIELD_TYPE_INTEGER &&
                    cursor.getType(3) == Cursor.FIELD_TYPE_INTEGER && cursor.getType(4) == Cursor.FIELD_TYPE_STRING &&
                    cursor.getType(5) == Cursor.FIELD_TYPE_INTEGER && cursor.getLong(1) >= -1) { "SYNC_RECOVERY_SCHEMA_INVALID" }
                require(cursor.getType(2) in setOf(Cursor.FIELD_TYPE_NULL, Cursor.FIELD_TYPE_STRING))
                // Physical cid changes when ALTER appended a column; column identity/order does not.
                add(listOf(cursor.getLong(0).toString(), if (cursor.getLong(1) == -1L) "rowid" else "column",
                    cursor.getString(2)?.lowercase(Locale.ROOT) ?: "", cursor.getLong(3).toString(),
                    cursor.getString(4).lowercase(Locale.ROOT), cursor.getLong(5).toString()))
            }
        } }
        return listOf(header) + columns
    }

    private fun verifyTypes(sql: SupportSQLiteDatabase, table: String) {
        val invalid = sql.query("PRAGMA table_info(`$table`)").use { cursor -> buildList {
            while (cursor.moveToNext()) {
                require(cursor.getType(1) == Cursor.FIELD_TYPE_STRING && cursor.getType(2) == Cursor.FIELD_TYPE_STRING &&
                    cursor.getType(3) == Cursor.FIELD_TYPE_INTEGER)
                val name = cursor.getString(1)
                require(name.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")))
                val allowed = when (cursor.getString(2).uppercase(Locale.ROOT)) {
                    "INTEGER" -> "'integer'"
                    "REAL" -> "'real','integer'"
                    "TEXT" -> "'text'"
                    "BLOB" -> "'blob'"
                    "" -> {
                        if (table == "sqlite_sequence") when (name.lowercase(Locale.ROOT)) {
                            "name" -> "'text'"
                            "seq" -> "'integer'"
                            else -> error("SYNC_RECOVERY_SCHEMA_INVALID")
                        } else {
                            require(table in setOf("sqlite_stat1", "sqlite_stat4")) { "SYNC_RECOVERY_SCHEMA_INVALID" }
                            "'integer','real','text','blob'" // SQLite-owned statistics have no declared affinity.
                        }
                    }
                    else -> error("SYNC_RECOVERY_SCHEMA_INVALID")
                }
                add(if (cursor.getLong(3) == 1L || table == "sqlite_sequence") "typeof(`$name`) NOT IN ($allowed)"
                    else "typeof(`$name`) NOT IN ($allowed,'null')")
            }
        } }
        require(invalid.isNotEmpty())
        sql.query("SELECT 1 FROM `$table` WHERE ${invalid.joinToString(" OR ")} LIMIT 1").use {
            require(!it.moveToFirst()) { "SYNC_RECOVERY_SQL_TYPE_INVALID" }
        }
    }
}

/** Strict lexer for the owned CREATE grammar, not a permissive general-purpose SQLite parser.
 * Preserve string literals, punctuation and every constraint. Normalize ONLY whitespace,
 * identifier case/backticks, top-level clause order and the bare inline single-column primary
 * keys used by reviewed additive migrations.
 * Other quoting/comment syntax must be explicitly reviewed before it can enter an archive.
 */
internal object ReplicaSchemaSql {
    fun tokens(sql: String): List<String> {
        val result = mutableListOf<String>()
        var position = 0
        while (position < sql.length) {
            val ch = sql[position]
            when {
                ch in " \t\n\r\u000B\u000C" -> position++
                ch == '\'' || ch == '`' -> {
                    val quote = ch; val text = StringBuilder(); position++
                    var closed = false
                    while (position < sql.length) {
                        val next = sql[position++]
                        if (next != quote) text.append(next)
                        else if (position < sql.length && sql[position] == quote) { text.append(quote); position++ }
                        else { closed = true; break }
                    }
                    require(closed) { "SYNC_RECOVERY_SCHEMA_INVALID" }
                    result += if (quote == '\'') "literal:$text" else "word:${text.toString().lowercase(Locale.ROOT)}"
                }
                ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_' -> {
                    val start = position++
                    while (position < sql.length && (sql[position] in 'a'..'z' || sql[position] in 'A'..'Z' ||
                        sql[position] in '0'..'9' || sql[position] == '_')) position++
                    result += "word:${sql.substring(start, position).lowercase(Locale.ROOT)}"
                }
                ch in "(),;+-*/%=<>!|." -> {
                    require(!sql.startsWith("--", position) && !sql.startsWith("/*", position)) { "SYNC_RECOVERY_SCHEMA_INVALID" }
                    result += ch.toString(); position++
                }
                else -> error("SYNC_RECOVERY_SCHEMA_INVALID")
            }
        }
        require(result.isNotEmpty())
        return result
    }

    fun table(tokens: List<String>, name: String): List<List<String>> {
        val opening = tokens.indexOf("(")
        val expected = listOf("word:create", "word:table", "word:${name.lowercase(Locale.ROOT)}")
        val prefix = tokens.take(opening)
        require(prefix == expected || prefix == expected.take(2) + listOf("word:if", "word:not", "word:exists") + expected.last()) {
            "SYNC_RECOVERY_SCHEMA_INVALID"
        }
        val clauses = mutableListOf<List<String>>()
        var depth = 1; var start = opening + 1; var closing = -1
        for (i in start until tokens.size) {
            when (tokens[i]) {
                "(" -> { depth++; require(depth <= 64) }
                ")" -> depth--
            }
            if (depth == 0) { clauses += tokens.subList(start, i).toList(); closing = i; break }
            if (depth == 1 && tokens[i] == ",") { clauses += tokens.subList(start, i).toList(); start = i + 1 }
        }
        require(closing > opening && clauses.all { it.isNotEmpty() } &&
            tokens.drop(closing + 1).let { it.isEmpty() || it == listOf(";") }) { "SYNC_RECOVERY_SCHEMA_INVALID" }
        // Reviewed additive migrations used a bare single-column inline PRIMARY KEY;
        // generated Room uses the equivalent table clause. Do not normalize DESC,
        // AUTOINCREMENT, conflict policy or other inline constraints away.
        val primary = listOf("word:primary", "word:key")
        val normalized = mutableListOf<List<String>>()
        for (clause in clauses) {
            if (clause.size >= 4 && clause[0].startsWith("word:") &&
                clause[1] in setOf("word:integer", "word:text", "word:real", "word:blob") &&
                clause.takeLast(2) == primary &&
                clause.drop(2).dropLast(2).let { it.isEmpty() || it == listOf("word:not", "word:null") }) {
                normalized += clause.dropLast(2)
                normalized += primary + listOf("(", clause[0], ")")
            } else normalized += clause
        }
        return normalized.sortedBy { JsonArray(it.map(::JsonPrimitive)).toString() }
    }
}
