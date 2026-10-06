package com.dayforge.data.repository

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.api.SYNC_REQUEST_LIMIT
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val NEXT_OPERATION = "sync_operation"
internal const val NEXT_TIMER = "timer_command"

internal class NextRequestException(val reason: Reason) : IllegalStateException(reason.name) {
    enum class Reason { STALE_ACCESS, PERMISSION_DENIED, INVALID_LOCAL_STATE, OLD_INTENT,
        SOURCE_CHANGED, TRANSMISSION_CONTEXT_CHANGED, REQUEST_ID_REUSED, UNSUPPORTED_ACCEPTANCE, RESULT_CHANGED }
}

internal fun rejectNextRequest(reason: NextRequestException.Reason): Nothing = throw NextRequestException(reason)
internal fun nextRequestHash(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Audit original SQLite types/ranges BEFORE Room's getInt/getLong/String coercion. */
internal object NextRequestSql {
    fun requireOutboxEnabled(sql: SupportSQLiteDatabase) {
        val enabled = sql.query("SELECT id,suppressOutbox FROM sync_control").use { c ->
            c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_INTEGER && c.getLong(0) == 1L &&
                c.getType(1) == Cursor.FIELD_TYPE_INTEGER && c.getLong(1) == 0L && !c.moveToNext()
        }
        if (!enabled) rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
    }

    private val ints = setOf("protocol", "attemptCount", "sequence", "expectedControlGeneration", "expectedRevision")
    private val longs = ints + setOf("id", "queueId", "baseRevision", "attemptedAt", "deadLetteredAt", "createdAt",
        "occurredAt", "activeElapsedMillis", "revision", "deleted", "updatedAt")
    private val tables = setOf("sync_outbox", "timer_command_outbox", "next_request_origins", "next_transmissions", "next_acceptances", "sync_entity_state")
    fun table(kind: String): String = when (kind) {
        NEXT_OPERATION -> "sync_outbox"
        NEXT_TIMER -> "timer_command_outbox"
        else -> rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
    }

    suspend fun rowHash(sql: SupportSQLiteDatabase, table: String, where: String, args: Array<Any>): String? {
        require(table in tables)
        val caller = currentCoroutineContext()
        // Check lengths in SQLite before materializing a possibly damaged TEXT/BLOB in memory.
        val columns = sql.query("PRAGMA table_info($table)").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(1)) }
        }
        require(columns.isNotEmpty())
        val length = columns.joinToString(" + ") { "COALESCE(length(CAST(`$it` AS BLOB)),0)" }
        sql.query("SELECT ($length) FROM $table WHERE $where", args).use { c ->
            if (!c.moveToFirst()) return null
            val limit = SYNC_REQUEST_LIMIT + 4096 // Finite payload plus source/journal identity overhead.
            if (c.getType(0) != Cursor.FIELD_TYPE_INTEGER || c.getLong(0) > limit || c.moveToNext())
                rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
        }
        return sql.query("SELECT * FROM $table WHERE $where", args).use { c ->
            check(c.moveToFirst())
            val hash = MessageDigest.getInstance("SHA-256")
            fun bytes(value: ByteArray) {
                hash.update(ByteBuffer.allocate(4).putInt(value.size).array()); hash.update(value)
            }
            bytes(table.toByteArray(Charsets.UTF_8))
            for (i in 0 until c.columnCount) {
                caller.ensureActive()
                val name = c.getColumnName(i)
                val type = c.getType(i)
                bytes(name.toByteArray(Charsets.UTF_8)); hash.update(type.toByte())
                if (type == Cursor.FIELD_TYPE_NULL) continue
                val expected = when { name in longs -> Cursor.FIELD_TYPE_INTEGER
                    name == "wireBytes" -> Cursor.FIELD_TYPE_BLOB
                    else -> Cursor.FIELD_TYPE_STRING }
                if (type != expected) rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
                when (type) {
                    Cursor.FIELD_TYPE_INTEGER -> {
                        val value = c.getLong(i)
                        if (name in ints && value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                            rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
                        if (name == "deleted" && value !in 0..1) rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
                        bytes(ByteBuffer.allocate(8).putLong(value).array())
                    }
                    Cursor.FIELD_TYPE_BLOB -> bytes(c.getBlob(i))
                    Cursor.FIELD_TYPE_STRING -> bytes(c.getString(i).toByteArray(Charsets.UTF_8))
                    Cursor.FIELD_TYPE_FLOAT, Cursor.FIELD_TYPE_NULL -> rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
                }
            }
            if (c.moveToNext()) rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
            hash.digest().joinToString("") { "%02x".format(it) }
        }
    }

    /** Includes deleted historical IDs: new origins must come from a new AUTOINCREMENT allocation. */
    fun watermark(sql: SupportSQLiteDatabase, table: String): Long {
        require(table in setOf("sync_outbox", "timer_command_outbox"))
        return sql.query("SELECT seq FROM sqlite_sequence WHERE name=?", arrayOf(table)).use { c ->
            if (!c.moveToFirst()) 0L else {
                if (c.getType(0) != Cursor.FIELD_TYPE_INTEGER) rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
                val value = c.getLong(0)
                if (value < 0 || c.moveToNext()) rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
                value
            }
        }
    }

    suspend fun sources(sql: SupportSQLiteDatabase, table: String): Map<Long, String> {
        require(table in setOf("sync_outbox", "timer_command_outbox"))
        val ids = sql.query("SELECT id FROM $table ORDER BY id LIMIT 10001").use { c ->
            buildList {
                while (c.moveToNext()) {
                    if (c.getType(0) != Cursor.FIELD_TYPE_INTEGER || c.getLong(0) <= 0)
                        rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
                    add(c.getLong(0))
                }
            }
        }
        if (ids.size > 10000) rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
        val watermark = watermark(sql, table)
        if (ids.any { it > watermark }) rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
        return ids.associateWith { requireNotNull(rowHash(sql, table, "id=?", arrayOf(it))) }
    }
}
