package com.dayforge.data.local

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Identical schema/type comparison for the business and separately scoped appearance databases. */
internal object ReplicaSqlSchema {
    private val compilations = ReplicaSchemaCompilationCache()
    data class SchemaObject(val type: String, val name: String, val owner: String, val definition: List<List<String>>)

    suspend fun read(sql: SupportSQLiteDatabase): Map<String, SchemaObject> =
        ReplicaRecoveryTiming.measure(ReplicaRecoveryTiming.Stage.SCHEMA) { readActual(sql) }

    private suspend fun readActual(sql: SupportSQLiteDatabase): Map<String, SchemaObject> {
        // Only this invocation owns these observations. Unknown/outer wrappers keep the
        // original independent queries; transaction presence alone is not ownership.
        val indexLists = if (CountTrustRuntime.isControlledDatabase(sql) && sql.inTransaction())
            mutableMapOf<String, List<IndexHeaderRow>>() else null
        sql.query("SELECT COALESCE(length(CAST(sql AS BLOB)),0) FROM main.sqlite_master").use { cursor ->
            while (cursor.moveToNext()) require(cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER && cursor.getLong(0) in 0..262_144L) {
                "SYNC_RECOVERY_SCHEMA_INVALID"
            }
        }
        return sql.query("SELECT type,name,tbl_name,sql FROM main.sqlite_master ORDER BY type,name").use { cursor -> buildMap {
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                require((0..2).all { cursor.getType(it) == Cursor.FIELD_TYPE_STRING }) { "SYNC_RECOVERY_SCHEMA_INVALID" }
                val type = cursor.getString(0); val name = cursor.getString(1); val owner = cursor.getString(2)
                require(type in setOf("table", "index", "trigger", "view"))
                require(listOf(name, owner).all { it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) }) { "SYNC_RECOVERY_SCHEMA_INVALID" }
                val definition = when (cursor.getType(3)) {
                    Cursor.FIELD_TYPE_NULL -> {
                        require(type == "index" && name.startsWith("sqlite_autoindex_")) { "SYNC_RECOVERY_SCHEMA_INVALID" }
                        index(sql, name, owner, indexLists)
                    }
                    Cursor.FIELD_TYPE_STRING -> {
                        val caller = currentCoroutineContext()
                        val compiled = compilations.compile(type, name, cursor.getString(3)) { caller.ensureActive() }
                        // Index SQL still passes the original lexer, even on a cache miss.
                        // Only that pure result survives this read. Native header observations
                        // are local to this call; every index_xinfo remains an actual query.
                        if (type == "index") index(sql, name, owner, indexLists) else compiled
                    }
                    else -> error("SYNC_RECOVERY_SCHEMA_INVALID")
                }
                val canonicalName = name.lowercase(Locale.ROOT)
                val key = "$type.$canonicalName"
                require(put(key, SchemaObject(type, canonicalName, owner.lowercase(Locale.ROOT), definition)) == null)
            }
        } }
    }

    private fun index(sql: SupportSQLiteDatabase, name: String, declaredOwner: String,
        indexLists: MutableMap<String, List<IndexHeaderRow>>?): List<List<String>> {
        val owner = if (indexLists != null) declaredOwner else
            sql.query("SELECT tbl_name FROM main.sqlite_master WHERE type='index' AND name=?", arrayOf(name)).use {
                require(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_STRING); it.getString(0)
            }
        val header = if (indexLists != null) {
            indexHeader(indexLists.getOrPut(owner) { readIndexHeaders(sql, owner) }, name)
        } else sql.query("PRAGMA main.index_list(`$owner`)").use { cursor ->
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
        val columns = sql.query("PRAGMA main.index_xinfo(`$name`)").use { cursor -> buildList {
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

    /** Null represents a wrong SQLite storage type, retained until that row's original
     * validation turn. No numeric coercion, eager sibling validation or native handle. */
    private data class IndexHeaderRow(val name: String?, val unique: Long?, val origin: String?, val partial: Long?)

    private fun readIndexHeaders(sql: SupportSQLiteDatabase, owner: String): List<IndexHeaderRow> =
        sql.query("PRAGMA main.index_list(`$owner`)").use { cursor -> buildList {
            while (cursor.moveToNext()) add(IndexHeaderRow(
                if (cursor.getType(1) == Cursor.FIELD_TYPE_STRING) cursor.getString(1) else null,
                if (cursor.getType(2) == Cursor.FIELD_TYPE_INTEGER) cursor.getLong(2) else null,
                if (cursor.getType(3) == Cursor.FIELD_TYPE_STRING) cursor.getString(3) else null,
                if (cursor.getType(4) == Cursor.FIELD_TYPE_INTEGER) cursor.getLong(4) else null))
        } }

    private fun indexHeader(rows: List<IndexHeaderRow>, name: String): List<String> {
        var found: List<String>? = null
        for (row in rows) {
            require(row.name != null)
            if (row.name == name) {
                require(found == null && row.unique != null && row.origin != null && row.partial != null)
                found = listOf(name.lowercase(Locale.ROOT), row.unique.toString(), row.origin, row.partial.toString())
                require(row.partial == 0L) { "SYNC_RECOVERY_SCHEMA_INVALID" }
            }
        }
        return requireNotNull(found)
    }

    fun verifyTypes(sql: SupportSQLiteDatabase, table: String) {
        val invalid = sql.query("PRAGMA main.table_info(`$table`)").use { cursor -> buildList {
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
        sql.query("SELECT 1 FROM main.`$table` WHERE ${invalid.joinToString(" OR ")} LIMIT 1").use {
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
    data class TokenSpan(val value: String, val start: Int, val end: Int)
    fun tokens(sql: String): List<String> = buildList {
        scan(sql) { value, _, _ -> add(value) }
    }.also { require(it.isNotEmpty()) }

    /** Same grammar, with positions only when the exact additive bridge needs old bytes. */
    fun tokenSpans(sql: String): List<TokenSpan> = buildList {
        scan(sql) { value, start, end -> add(TokenSpan(value, start, end)) }
    }.also { require(it.isNotEmpty()) }

    private inline fun scan(sql: String, emit: (String, Int, Int) -> Unit) {
        var position = 0
        while (position < sql.length) {
            val start = position
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
                    emit(if (quote == '\'') "literal:$text" else "word:${text.toString().lowercase(Locale.ROOT)}", start, position)
                }
                ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_' -> {
                    position++
                    while (position < sql.length && (sql[position] in 'a'..'z' || sql[position] in 'A'..'Z' ||
                        sql[position] in '0'..'9' || sql[position] == '_')) position++
                    emit("word:${sql.substring(start, position).lowercase(Locale.ROOT)}", start, position)
                }
                ch in "(),;+-*/%=<>!|." -> {
                    require(!sql.startsWith("--", position) && !sql.startsWith("/*", position)) { "SYNC_RECOVERY_SCHEMA_INVALID" }
                    position++; emit(ch.toString(), start, position)
                }
                else -> error("SYNC_RECOVERY_SCHEMA_INVALID")
            }
        }
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
        if (normalized.size <= 1) return normalized.toList()
        // Keep the exact JSON key and stable ordering, but encode each clause only once.
        return normalized.map { clause ->
            JsonArray(clause.map(::JsonPrimitive)).toString() to clause
        }.sortedBy { it.first }.map { it.second }
    }
}
