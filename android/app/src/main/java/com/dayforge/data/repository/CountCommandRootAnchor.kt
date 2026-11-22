package com.dayforge.data.repository

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.local.CountTrustSchema
import com.dayforge.data.local.entity.CountCommandRecoveryRootEntity
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Local continuity binding in the existing controlled control row. It is not an external
 * rollback anchor: the reviewed threat boundary preserves that current row in its entirety.
 * Ordinary business generation/dirty are neither changed nor replaced by this commitment.
 */
internal object CountCommandRootAnchor {
    const val COLUMN = "commandRootHash"
    data class Control(val storage: String, val generation: Long, val dirty: Long, val hash: String?)

    fun digest(header: CountCommandRootCodec.Header, mac: ByteArray): String {
        require(mac.size == 32)
        val body = CountCommandRootCodec.body(header)
        val hash = MessageDigest.getInstance("SHA-256")
        for (part in listOf("DayForge/count-command/control-root/v1".toByteArray(Charsets.UTF_8), body, mac)) {
            hash.update(ByteBuffer.allocate(8).putLong(part.size.toLong()).array()); hash.update(part)
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun digest(row: CountCommandRecoveryRootEntity) = digest(CountCommandRootCodec.fromEntity(row), row.mac)

    /** Scalar preflight first; 64 ASCII hex characters can occupy128 bytes in UTF16 SQLite.
     * Do not load oversized/ill-typed metadata into a cursor field merely to reject it later. */
    fun read(sql: SupportSQLiteDatabase): Control {
        CountTrustSchema.requireControl(sql)
        return sql.query("SELECT storageId,generation,dirty,typeof(commandRootHash)," +
            "CASE WHEN typeof(commandRootHash)='text' AND length(commandRootHash)=64 AND " +
            "length(CAST(commandRootHash AS BLOB))<=256 AND commandRootHash NOT GLOB '*[^0-9a-f]*' " +
            "THEN commandRootHash ELSE NULL END FROM count_trust_control WHERE id=1 LIMIT 2").use { c ->
            require(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_STRING &&
                c.getType(1) == Cursor.FIELD_TYPE_INTEGER && c.getType(2) == Cursor.FIELD_TYPE_INTEGER &&
                c.getType(3) == Cursor.FIELD_TYPE_STRING) { "COMMAND_PRIMARY_ANCHOR_INVALID" }
            val anchor = when (c.getString(3)) {
                "null" -> { require(c.getType(4) == Cursor.FIELD_TYPE_NULL); null }
                "text" -> { require(c.getType(4) == Cursor.FIELD_TYPE_STRING) { "COMMAND_PRIMARY_ANCHOR_INVALID" }; c.getString(4) }
                else -> error("COMMAND_PRIMARY_ANCHOR_INVALID")
            }
            Control(c.getString(0), c.getLong(1), c.getLong(2), anchor).also {
                require(!c.moveToNext()) { "COMMAND_PRIMARY_ANCHOR_INVALID" }
            }
        }
    }
    fun requireMatches(header: CountCommandRootCodec.Header, mac: ByteArray, anchor: String?) {
        val actual = requireNotNull(anchor) { "COMMAND_PRIMARY_ANCHOR_MISSING" }
        require(actual == digest(header, mac)) { "COMMAND_PRIMARY_ANCHOR_MISMATCH" }
    }

    /** Caller holds the private original root transition/initialization permit in this TX.
     * The complete control OLD/NEW pair is also a separately expected journal mutation.
     * This cannot recover a missing/old root or advance an old anchor to observed data. */
    fun update(sql: SupportSQLiteDatabase, before: Control, hash: String): Control {
        check(sql.inTransaction()) { "COMMAND_ANCHOR_TRANSACTION_REQUIRED" }
        require(hash.length == 64 && hash.all { it in "0123456789abcdef" })
        require(read(sql) == before) { "COMMAND_PRIMARY_ANCHOR_CAS_CHANGED" }
        val changed = sql.compileStatement("UPDATE count_trust_control SET commandRootHash=? WHERE id=1 AND storageId=? " +
            "AND generation=? AND dirty=? AND commandRootHash IS ?").use { statement ->
            statement.bindString(1, hash); statement.bindString(2, before.storage)
            statement.bindLong(3, before.generation); statement.bindLong(4, before.dirty)
            before.hash?.let { statement.bindString(5, it) } ?: statement.bindNull(5)
            statement.executeUpdateDelete()
        }
        require(changed == 1) { "COMMAND_PRIMARY_ANCHOR_CAS_CHANGED" }
        return before.copy(hash = hash).also { require(read(sql) == it) { "COMMAND_PRIMARY_ANCHOR_WRITE_CHANGED" } }
    }
}
