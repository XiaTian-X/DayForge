package com.dayforge.data.local

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/** One-time native row preservation check around the exact additive 19→20 edge. Streaming
 * keeps allocation bounded without adding a new maximum history/row size. It is never used by
 * normal cold open, warm reads, COUNT verification or command T0/T1/T2.
 */
internal object CountCommandMigrationRows {
    fun fingerprint(sql: SupportSQLiteDatabase, beforeSchema: List<List<String?>>, v20Control: Boolean = false): String {
        check(sql.inTransaction())
        val tables = beforeSchema.filter { it[0] == "table" }.map { requireNotNull(it[1]) }.sorted()
        val known = CountTrustSchema.BUSINESS_TABLES + CountTrustSchema.LOCAL_TABLES + setOf(
            "room_master_table", "android_metadata", "sqlite_sequence", "sqlite_stat1", "sqlite_stat4")
        require(tables.all { it in known }) { "COMMAND_MIGRATION_UNKNOWN_OLD_TABLE" }
        val hash = MessageDigest.getInstance("SHA-256")
        fun bytes(value: ByteArray) { hash.update(ByteBuffer.allocate(8).putLong(value.size.toLong()).array()); hash.update(value) }
        bytes("DayForge/19-20/native-preservation/v1".toByteArray(Charsets.UTF_8))
        for (table in tables) {
            poll(); bytes(table.toByteArray(Charsets.UTF_8))
            val actualColumns = sql.query("PRAGMA main.table_info(${quoted(table)})").use { c -> buildList {
                while (c.moveToNext()) { require(c.getType(1) == Cursor.FIELD_TYPE_STRING); add(c.getString(1)) }
            } }
            val columns = if (v20Control && table == CountCommandControlSchema.TABLE) {
                require(actualColumns == listOf("id", "storageId", "generation", "dirty", "commandRootHash")) { "COMMAND_MIGRATION_CONTROL_COLUMNS" }
                actualColumns.dropLast(1)
            } else actualColumns
            require(columns.isNotEmpty())
            columns.forEach { bytes(it.toByteArray(Charsets.UTF_8)) }
            if (table == "room_master_table") {
                require(columns == listOf("id", "identity_hash"))
                sql.query("SELECT rowid,id,typeof(identity_hash),length(identity_hash) FROM room_master_table LIMIT 2").use {
                    require(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 42L &&
                        it.getType(1) == Cursor.FIELD_TYPE_INTEGER && it.getLong(1) == 42L &&
                        it.getType(2) == Cursor.FIELD_TYPE_STRING && it.getString(2) == "text" && it.getLong(3) > 0 && !it.moveToNext())
                }
                // The ONLY permitted old-table value change: Room's own final updateIdentity,
                // executed after onValidateSchema in the same framework upgrade transaction.
                // All other rows/columns, including control, binding and old COUNT MAC, stay exact.
                bytes("room-generated-identity-slot/id=42".toByteArray(Charsets.UTF_8)); continue
            }
            var rowCount = 0L
            sql.query("SELECT rowid,${columns.joinToString(",") { "typeof(${quoted(it)}),length(CAST(${quoted(it)} AS BLOB))" }} " +
                "FROM ${quoted(table)} ORDER BY rowid").use { meta ->
                while (meta.moveToNext()) {
                    poll(); require(meta.getType(0) == Cursor.FIELD_TYPE_INTEGER)
                    val rowid = meta.getLong(0); hash.update(ByteBuffer.allocate(8).putLong(rowid).array())
                    rowCount = Math.addExact(rowCount, 1L)
                    for ((index, column) in columns.withIndex()) {
                        val at = index * 2 + 1
                        require(meta.getType(at) == Cursor.FIELD_TYPE_STRING)
                        val type = when (meta.getString(at)) {
                            "null" -> Cursor.FIELD_TYPE_NULL; "integer" -> Cursor.FIELD_TYPE_INTEGER
                            "real" -> Cursor.FIELD_TYPE_FLOAT; "text" -> Cursor.FIELD_TYPE_STRING
                            "blob" -> Cursor.FIELD_TYPE_BLOB; else -> error("COMMAND_MIGRATION_NATIVE_TYPE")
                        }
                        val size = when (type) {
                            Cursor.FIELD_TYPE_NULL -> { require(meta.getType(at + 1) == Cursor.FIELD_TYPE_NULL); 0L }
                            Cursor.FIELD_TYPE_INTEGER, Cursor.FIELD_TYPE_FLOAT -> 8L
                            else -> { require(meta.getType(at + 1) == Cursor.FIELD_TYPE_INTEGER); meta.getLong(at + 1).also { require(it >= 0) } }
                        }
                        hash.update(type.toByte()); hash.update(ByteBuffer.allocate(8).putLong(size).array())
                        if (type == Cursor.FIELD_TYPE_INTEGER || type == Cursor.FIELD_TYPE_FLOAT) {
                            sql.query("SELECT ${quoted(column)} FROM ${quoted(table)} WHERE rowid=? LIMIT 2", arrayOf(rowid)).use { c ->
                                require(c.moveToFirst() && c.getType(0) == type)
                                val value = if (type == Cursor.FIELD_TYPE_INTEGER) c.getLong(0) else c.getDouble(0).toRawBits()
                                hash.update(ByteBuffer.allocate(8).putLong(value).array()); require(!c.moveToNext())
                            }
                        } else if (type == Cursor.FIELD_TYPE_STRING || type == Cursor.FIELD_TYPE_BLOB) {
                            var offset = 0L
                            while (offset < size) {
                                poll(); val count = minOf(65_536L, size - offset)
                                sql.query("SELECT substr(CAST(${quoted(column)} AS BLOB),?,?) FROM ${quoted(table)} WHERE rowid=? LIMIT 2",
                                    arrayOf(Math.addExact(offset, 1L), count, rowid)).use { c ->
                                    require(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_BLOB)
                                    val chunk = c.getBlob(0); require(chunk.size.toLong() == count && !c.moveToNext()); hash.update(chunk)
                                }
                                offset = Math.addExact(offset, count)
                            }
                        }
                    }
                }
            }
            hash.update(ByteBuffer.allocate(8).putLong(rowCount).array())
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun quoted(value: String) = "\"${value.replace("\"", "\"\"")}\""
    private fun poll() { if (Thread.currentThread().isInterrupted) throw CancellationException("Command migration interrupted") }
}
