package com.dayforge.data.local

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/** Raw old rows have no business, owner, execution, or delivery meaning here. */
internal object CountCommandLegacyNative {
    /** Raw SQLite fingerprint of uninterpreted old Outcome. Stream TEXT/BLOB in fixed
     * chunks rather than imposing a new per-row product cap on previously preserved data.
     * Original native types, float bits and SQLite text bytes are retained. Legacy content
     * is NEVER decoded as a valid command or erased to obtain EMPTY.
     */
    fun fingerprint(sql: SupportSQLiteDatabase): Pair<Long, String> = stream(sql, { _, _, _ -> false }, ::poll)

    internal fun stream(sql: SupportSQLiteDatabase, excludeEnrolled: (Long, String, Long?) -> Boolean,
        poll: () -> Unit): Pair<Long, String> {
        val columns = listOf("commandId", "inputHash", "factUuid", "requestId", "resultJson", "state")
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("DayForge/count-command-unenrolled-native/v1\u0000".toByteArray(Charsets.UTF_8))
        var count = 0L
        // commandId's original BINARY primary-key order is stable across compaction. rowid
        // is only a current-snapshot locator and is not part of the fingerprint or identity.
        sql.query("SELECT rowid,${columns.joinToString(",") { "typeof(`$it`),length(CAST(`$it` AS BLOB))" }} " +
            "FROM count_command_results ORDER BY commandId").use { c ->
            while (c.moveToNext()) {
                poll()
                require(c.getType(0) == Cursor.FIELD_TYPE_INTEGER); val rowid = c.getLong(0)
                require(c.getType(1) == Cursor.FIELD_TYPE_STRING)
                val keyBytes = if (c.isNull(2)) null else c.getLong(2).also { require(c.getType(2) == Cursor.FIELD_TYPE_INTEGER && it >= 0L) }
                if (excludeEnrolled(rowid, c.getString(1), keyBytes)) continue
                count = Math.addExact(count, 1L)
                for ((index, column) in columns.withIndex()) {
                    val typeColumn = 1 + index * 2
                    require(c.getType(typeColumn) == Cursor.FIELD_TYPE_STRING)
                    val kind = c.getString(typeColumn)
                    val type = when (kind) {
                        "null" -> Cursor.FIELD_TYPE_NULL
                        "integer" -> Cursor.FIELD_TYPE_INTEGER
                        "real" -> Cursor.FIELD_TYPE_FLOAT
                        "text" -> Cursor.FIELD_TYPE_STRING
                        "blob" -> Cursor.FIELD_TYPE_BLOB
                        else -> error("COMMAND_LEGACY_NATIVE_TYPE")
                    }
                    val bytes = when (type) {
                        Cursor.FIELD_TYPE_NULL -> { require(c.getType(typeColumn + 1) == Cursor.FIELD_TYPE_NULL); 0L }
                        Cursor.FIELD_TYPE_INTEGER, Cursor.FIELD_TYPE_FLOAT -> 8L
                        else -> {
                            require(c.getType(typeColumn + 1) == Cursor.FIELD_TYPE_INTEGER)
                            c.getLong(typeColumn + 1).also { require(it >= 0) }
                        }
                    }
                    digest.update(type.toByte()); digest.update(ByteBuffer.allocate(8).putLong(bytes).array())
                    if (type in setOf(Cursor.FIELD_TYPE_INTEGER, Cursor.FIELD_TYPE_FLOAT)) {
                        sql.query("SELECT `$column` FROM count_command_results WHERE rowid=? LIMIT 2", arrayOf(rowid)).use { value ->
                            require(value.moveToFirst() && value.getType(0) == type)
                            val bits = if (type == Cursor.FIELD_TYPE_INTEGER) value.getLong(0) else value.getDouble(0).toRawBits()
                            digest.update(ByteBuffer.allocate(8).putLong(bits).array()); require(!value.moveToNext())
                        }
                    } else if (type in setOf(Cursor.FIELD_TYPE_STRING, Cursor.FIELD_TYPE_BLOB)) {
                        var offset = 0L
                        while (offset < bytes) {
                            poll(); val size = minOf(65_536L, bytes - offset)
                            sql.query("SELECT substr(CAST(`$column` AS BLOB),?,?) FROM count_command_results WHERE rowid=? LIMIT 2",
                                arrayOf(Math.addExact(offset, 1L), size, rowid)).use { value ->
                                require(value.moveToFirst() && value.getType(0) == Cursor.FIELD_TYPE_BLOB)
                                val chunk = value.getBlob(0); require(chunk.size.toLong() == size && !value.moveToNext())
                                digest.update(chunk)
                            }
                            offset = Math.addExact(offset, size)
                        }
                    }
                }
            }
        }
        digest.update(ByteBuffer.allocate(8).putLong(count).array())
        return count to digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun poll() { if (Thread.currentThread().isInterrupted) throw CancellationException("Command initialization interrupted") }
}
