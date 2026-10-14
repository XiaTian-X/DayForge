package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.api.SYNC_REQUEST_LIMIT
import com.dayforge.data.local.HabitDatabase
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Transaction-local change detector, NOT an authority, classification or acceptance proof. */
internal class NextRecoveryPreservationStore(private val database: HabitDatabase) {
    private val sql get() = database.openHelper.writableDatabase

    /** New business tables are protected by default, rather than silently omitted from a whitelist. */
    suspend fun snapshot(recoveryId: String): Map<String, String> {
        check(database.inTransaction())
        require(com.dayforge.domain.model.isContractUuid(recoveryId))
        val caller = currentCoroutineContext()
        val definitions = sql.query("SELECT name,sql FROM sqlite_master WHERE type='table' ORDER BY name").use { c -> buildList {
            while (c.moveToNext()) add(c.getString(0) to c.getString(1))
        } }
        val excluded = setOf("habits", "metrics", "habit_metric_links", "sync_outbox")
        val recoveryTables = setOf("next_recovery_audits", "next_abandoned_requests", "next_recovery_audit_payloads")
        return buildMap {
            for ((table, definition) in definitions) {
                caller.ensureActive()
                require(table.matches(Regex("[a-zA-Z_][a-zA-Z_0-9]*")))
                // Bind schema as well: this writer neither adds columns nor creates business tables.
                put("schema:$table", nextRequestHash(definition.toByteArray(Charsets.UTF_8)))
                if (table in excluded) continue
                // Only this new group may change. Older independent audits are immutable too.
                val where = if (table in recoveryTables) " WHERE recoveryId<>?" else ""
                val args = if (table in recoveryTables) arrayOf<Any>(recoveryId) else emptyArray()
                val columns = sql.query("PRAGMA table_info(`$table`)").use { c -> buildList {
                    while (c.moveToNext()) add(c.getString(1) to c.getInt(5))
                } }
                require(columns.isNotEmpty())
                val length = columns.joinToString("+") { "COALESCE(length(CAST(`${it.first}` AS BLOB)),0)" }
                sql.query("SELECT MAX($length) FROM `$table`$where", args).use { c ->
                    require(c.moveToFirst() && (c.isNull(0) || c.getType(0) == Cursor.FIELD_TYPE_INTEGER &&
                        c.getLong(0) <= SYNC_REQUEST_LIMIT + 4096)) { "RECOVERY_PROTECTED_ROW_LIMIT" }
                }
                val order = columns.filter { it.second > 0 }.sortedBy { it.second }.joinToString(",") { "`${it.first}`" }.ifEmpty { "rowid" }
                val hash = MessageDigest.getInstance("SHA-256")
                fun bytes(value: ByteArray) {
                    hash.update(ByteBuffer.allocate(4).putInt(value.size).array()); hash.update(value)
                }
                sql.query("SELECT * FROM `$table`$where ORDER BY $order", args).use { c ->
                    var rows = 0L
                    while (c.moveToNext()) {
                        caller.ensureActive(); rows++
                        for (i in 0 until c.columnCount) {
                            val type = c.getType(i)
                            bytes(c.getColumnName(i).toByteArray(Charsets.UTF_8)); hash.update(type.toByte())
                            when (type) {
                                Cursor.FIELD_TYPE_NULL -> Unit
                                Cursor.FIELD_TYPE_INTEGER -> bytes(ByteBuffer.allocate(8).putLong(c.getLong(i)).array())
                                Cursor.FIELD_TYPE_FLOAT -> bytes(ByteBuffer.allocate(8).putDouble(c.getDouble(i).also { require(it.isFinite()) }).array())
                                Cursor.FIELD_TYPE_STRING -> bytes(c.getString(i).toByteArray(Charsets.UTF_8))
                                Cursor.FIELD_TYPE_BLOB -> bytes(c.getBlob(i))
                                else -> error("RECOVERY_PROTECTED_SQL_TYPE_INVALID")
                            }
                        }
                    }
                    bytes(ByteBuffer.allocate(8).putLong(rows).array())
                }
                put(table, hash.digest().joinToString("") { "%02x".format(it) })
            }
        }
    }
}
