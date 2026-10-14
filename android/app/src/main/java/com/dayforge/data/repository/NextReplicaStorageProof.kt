package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.api.SYNC_REQUEST_LIMIT
import com.dayforge.data.local.HabitDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Exact observed SQLite schema and typed values, not a schema approval, archive or send queue. */
internal class ReplicaStorageProof internal constructor(val contents: JsonObject, val hash: String)

/** One bounded, SELECT-only snapshot of ALL tables, including original bytes and retained receipts.
 * Row paging bounds CursorWindow use; the preview memory budget never truncates a collection.
 * sqlite_master binds indexes/triggers/constraints as well as table names/version. Observing this
 * schema does not prove it is safe to restore: private archive validation is a separate gate.
 */
internal class NextReplicaStorageProof(private val database: HabitDatabase) {
    suspend fun readInTransaction(): ReplicaStorageProof {
        check(database.inTransaction())
        val caller = currentCoroutineContext()
        val sql = database.openHelper.writableDatabase
        var encodedBytes = 0L
        fun retain(row: JsonObject): JsonObject {
            encodedBytes += row.toString().toByteArray(Charsets.UTF_8).size
            require(encodedBytes <= PREVIEW_LIMIT) { "SYNC_RECOVERY_PREVIEW_TOO_LARGE" }
            return row
        }
        fun row(cursor: Cursor): JsonObject = buildJsonObject {
            for (column in 0 until cursor.columnCount) {
                caller.ensureActive()
                val type = cursor.getType(column)
                put(cursor.getColumnName(column), buildJsonObject {
                    put("sqlite_type", type)
                    put("value", when (type) {
                        Cursor.FIELD_TYPE_NULL -> JsonNull
                        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(cursor.getLong(column))
                        // Preserve signed zero and every floating-point bit without JSON coercion.
                        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(cursor.getDouble(column).toRawBits().toString(16))
                        Cursor.FIELD_TYPE_STRING -> JsonPrimitive(cursor.getString(column))
                        Cursor.FIELD_TYPE_BLOB -> JsonPrimitive(cursor.getBlob(column).joinToString("") { "%02x".format(it) })
                        else -> error("SYNC_RECOVERY_INVALID_STORAGE")
                    })
                })
            }
        }
        sql.query("SELECT COALESCE(length(CAST(sql AS BLOB)),0) FROM sqlite_master").use { c ->
            while (c.moveToNext()) require(c.getType(0) == Cursor.FIELD_TYPE_INTEGER && c.getLong(0) in 0..ROW_LIMIT)
        }
        val schema = sql.query("SELECT type,name,tbl_name,rootpage,sql FROM sqlite_master ORDER BY type,name").use { c ->
            buildList { while (c.moveToNext()) { caller.ensureActive(); add(retain(row(c))) } }
        }
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) {
                require(c.getType(0) == Cursor.FIELD_TYPE_STRING)
                add(c.getString(0).also { require(it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*"))) })
            } }
        }
        val contents = buildJsonObject {
            put("user_version", sql.version)
            put("schema", JsonArray(schema))
            put("tables", buildJsonObject {
                for (table in tables) {
                    caller.ensureActive()
                    val columns = sql.query("PRAGMA table_info(`$table`)").use { c -> buildList {
                        while (c.moveToNext()) {
                            require(c.getType(1) == Cursor.FIELD_TYPE_STRING)
                            add(c.getString(1).also { require(it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*"))) })
                        }
                    } }
                    require(columns.isNotEmpty() && "__recovery_rowid" !in columns)
                    val length = columns.joinToString("+") { "COALESCE(length(CAST(`$it` AS BLOB)),0)" }
                    var last: Long? = null
                    val rows = buildList {
                        while (true) {
                            caller.ensureActive()
                            val boundary = last
                            val where = if (boundary == null) "" else "WHERE rowid>?"
                            val args = if (boundary == null) emptyArray<Any>() else arrayOf<Any>(boundary)
                            val ids = sql.query("SELECT rowid,($length) FROM `$table` $where ORDER BY rowid LIMIT 128", args).use { c ->
                                buildList {
                                    var bytes = 0L
                                    while (c.moveToNext()) {
                                        caller.ensureActive()
                                        require(c.getType(0) == Cursor.FIELD_TYPE_INTEGER &&
                                            (lastOrNull() ?: boundary)?.let { c.getLong(0) > it } != false &&
                                            c.getType(1) == Cursor.FIELD_TYPE_INTEGER && c.getLong(1) in 0..ROW_LIMIT) {
                                            "SYNC_RECOVERY_INVALID_STORAGE"
                                        }
                                        val size = c.getLong(1)
                                        if (isNotEmpty() && bytes + size > ROW_LIMIT) break
                                        bytes += size; add(c.getLong(0))
                                    }
                                }
                            }
                            if (ids.isEmpty()) break
                            val range = if (boundary == null) "rowid<=?" else "rowid>? AND rowid<=?"
                            sql.query("SELECT rowid AS __recovery_rowid,* FROM `$table` WHERE $range ORDER BY rowid", args + ids.last()).use { c ->
                                require(c.columnCount == columns.size + 1)
                                var count = 0
                                while (c.moveToNext()) {
                                    require(count < ids.size && c.getType(0) == Cursor.FIELD_TYPE_INTEGER && c.getLong(0) == ids[count])
                                    add(retain(row(c))); count++
                                }
                                require(count == ids.size)
                            }
                            last = ids.last()
                        }
                    }
                    put(table, JsonArray(rows))
                }
            })
        }
        caller.ensureActive()
        val text = contents.toString()
        require(text.toByteArray(Charsets.UTF_8).size <= PREVIEW_LIMIT) { "SYNC_RECOVERY_PREVIEW_TOO_LARGE" }
        return ReplicaStorageProof(contents, syncPayloadHash(text))
    }

    companion object {
        private const val ROW_LIMIT = SYNC_REQUEST_LIMIT + 4096L
        private const val PREVIEW_LIMIT = 32 * 1024 * 1024L
    }
}
