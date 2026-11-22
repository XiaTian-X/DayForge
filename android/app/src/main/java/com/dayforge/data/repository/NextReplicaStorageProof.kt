package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.api.SYNC_REQUEST_LIMIT
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.ReplicaRecoveryTiming
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import kotlin.coroutines.CoroutineContext

/** Exact observed SQLite schema and typed values, not a schema approval, archive or send queue. */
internal class ReplicaStorageProof internal constructor(val contents: JsonObject, val hash: String)

/** Same lowercase, two-digit encoding as the original per-byte Formatter. No content is omitted,
 * and raw JSON/storage limits remain at the caller. Cursor.getBlob supplies an owned byte array.
 */
internal fun replicaStorageBlobHex(bytes: ByteArray, poll: () -> Unit = {}): String {
    val alphabet = "0123456789abcdef"
    val text = CharArray(Math.multiplyExact(bytes.size, 2))
    for (index in bytes.indices) {
        if ((index and 2047) == 0) poll()
        val value = bytes[index].toInt() and 255
        text[index * 2] = alphabet[value ushr 4]
        text[index * 2 + 1] = alphabet[value and 15]
    }
    poll()
    return text.concatToString()
}

/** One bounded, SELECT-only snapshot of ALL tables, including original bytes and retained receipts.
 * Row paging bounds CursorWindow use; the preview memory budget never truncates a collection.
 * sqlite_master binds indexes/triggers/constraints as well as table names/version. Observing this
 * schema does not prove it is safe to restore: private archive validation is a separate gate.
 */
internal class NextReplicaStorageProof(private val database: HabitDatabase) {
    suspend fun readInTransaction(): ReplicaStorageProof =
        ReplicaRecoveryTiming.measure(ReplicaRecoveryTiming.Stage.BUSINESS_RAW) { readActualInTransaction() }

    private suspend fun readActualInTransaction(): ReplicaStorageProof {
        check(database.inTransaction())
        val caller = currentCoroutineContext()
        // Native reads, typed builders and per-row bounds are one inclusive child, not SQL-only time.
        val contents = if (com.dayforge.BuildConfig.DEBUG)
            ReplicaRecoveryTiming.compute(ReplicaRecoveryTiming.Stage.RAW_READ_BUILD) { readContents(caller) }
        else readContents(caller)
        caller.ensureActive()
        val text = if (com.dayforge.BuildConfig.DEBUG)
            ReplicaRecoveryTiming.compute(ReplicaRecoveryTiming.Stage.RAW_JSON) { contents.toString() }
        else contents.toString()
        // Retain only the original length, never the temporary encoded array.
        val observedBytes = if (com.dayforge.BuildConfig.DEBUG)
            ReplicaRecoveryTiming.compute(ReplicaRecoveryTiming.Stage.RAW_UTF8_LENGTH) {
                text.toByteArray(Charsets.UTF_8).size.toLong()
            }
        else text.toByteArray(Charsets.UTF_8).size.toLong()
        if (com.dayforge.BuildConfig.DEBUG)
            ReplicaRecoveryTiming.compute(ReplicaRecoveryTiming.Stage.RAW_BUDGET) {
                ReplicaStorageBudget.requireWithin(contents, observedBytes)
            }
        else ReplicaStorageBudget.requireWithin(contents, observedBytes)
        // Deliberately retain the original second UTF8 encoding and SHA/hex work.
        val hash = if (com.dayforge.BuildConfig.DEBUG)
            ReplicaRecoveryTiming.compute(ReplicaRecoveryTiming.Stage.RAW_HASH_WITH_UTF8) { syncPayloadHash(text) }
        else syncPayloadHash(text)
        return ReplicaStorageProof(contents, hash)
    }

    private fun readContents(caller: CoroutineContext): JsonObject {
        val sql = database.openHelper.writableDatabase
        var encodedBytes = 0L
        fun retain(row: JsonObject): JsonObject {
            encodedBytes += row.toString().toByteArray(Charsets.UTF_8).size
            require(encodedBytes <= ReplicaStorageBudget.RAW_LIMIT) { "SYNC_RECOVERY_PREVIEW_TOO_LARGE" }
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
                        Cursor.FIELD_TYPE_BLOB -> JsonPrimitive(replicaStorageBlobHex(cursor.getBlob(column)) { caller.ensureActive() })
                        else -> error("SYNC_RECOVERY_INVALID_STORAGE")
                    })
                })
            }
        }
        sql.query("SELECT COALESCE(length(CAST(sql AS BLOB)),0) FROM main.sqlite_master").use { c ->
            while (c.moveToNext()) require(c.getType(0) == Cursor.FIELD_TYPE_INTEGER && c.getLong(0) in 0..ROW_LIMIT)
        }
        val schema = sql.query("SELECT type,name,tbl_name,rootpage,sql FROM main.sqlite_master ORDER BY type,name").use { c ->
            buildList { while (c.moveToNext()) { caller.ensureActive(); add(retain(row(c))) } }
        }
        val tables = sql.query("SELECT name FROM main.sqlite_master WHERE type='table' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) {
                require(c.getType(0) == Cursor.FIELD_TYPE_STRING)
                add(c.getString(0).also { require(it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*"))) })
            } }
        }
        return buildJsonObject {
            put("user_version", sql.version)
            put("schema", JsonArray(schema))
            put("tables", buildJsonObject {
                for (table in tables) {
                    caller.ensureActive()
                    val columns = sql.query("PRAGMA main.table_info(`$table`)").use { c -> buildList {
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
                            val ids = sql.query("SELECT rowid,($length) FROM main.`$table` $where ORDER BY rowid LIMIT 128", args).use { c ->
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
                            sql.query("SELECT rowid AS __recovery_rowid,* FROM main.`$table` WHERE $range ORDER BY rowid", args + ids.last()).use { c ->
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
    }

    companion object {
        private const val ROW_LIMIT = SYNC_REQUEST_LIMIT + 4096L
    }
}
