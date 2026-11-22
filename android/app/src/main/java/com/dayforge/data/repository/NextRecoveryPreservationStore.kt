package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.api.SYNC_REQUEST_LIMIT
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.CountTrustJournal
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.CountTrustSchema
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Transaction-local change detector, NOT an authority, classification or acceptance proof. */
internal class NextRecoveryPreservationStore(private val database: HabitDatabase) {
    private val sql get() = database.openHelper.writableDatabase

    /** New business tables are protected by default, rather than silently omitted from a whitelist. */
    internal class Snapshot internal constructor(
        internal val values: Map<String, String>,
        internal val control: CountTrustRecoveryControl?,
        internal val boundary: CountTrustJournal.Boundary?
    )

    suspend fun snapshot(recoveryId: String): Snapshot {
        val runtime = CountTrustRuntime.forDatabase(database)
        val control = CountTrustRecoveryControl.read(database)
        val boundary = if (runtime != null && !runtime.isRetired) runtime.maintenance(database) {
            runtime.captureChangeBoundary()
        } else null
        val values = contents(recoveryId, control)
        // A query observer may perform business SQL. Never capture a mixed before-image.
        require(CountTrustRecoveryControl.read(database) == control) { "SYNC_RECOVERY_PROTECTED_STATE_CHANGED" }
        if (boundary != null) requireNotNull(runtime).maintenance(database) {
            require(runtime.pendingChanges(boundary).isEmpty()) { "SYNC_RECOVERY_PROTECTED_STATE_CHANGED" }
        }
        require(CountTrustRecoveryControl.read(database) == control) { "SYNC_RECOVERY_PROTECTED_STATE_CHANGED" }
        return Snapshot(values, control, boundary)
    }

    suspend fun requireUnchanged(recoveryId: String, before: Snapshot) {
        val after = CountTrustRecoveryControl.read(database)
        require(contents(recoveryId, after) == before.values &&
            CountTrustRecoveryControl.read(database) == after) { "SYNC_RECOVERY_PROTECTED_STATE_CHANGED" }
        val old = before.control
        if (old == null) {
            require(after == null) { "SYNC_RECOVERY_PROTECTED_STATE_CHANGED" }
            return
        }
        require(after != null && after.storageId == old.storageId && after.generation >= old.generation) {
            "SYNC_RECOVERY_PROTECTED_STATE_CHANGED"
        }
        val runtime = requireNotNull(CountTrustRuntime.forDatabase(database))
        if (before.boundary != null) {
            // The lease is scoped to this actual transaction; root qualification is not issued.
            val changes = runtime.maintenance(database) { runtime.pendingChanges(before.boundary) }
            require(after.generation == Math.addExact(old.generation, changes.size.toLong())) {
                "SYNC_RECOVERY_PROTECTED_STATE_CHANGED"
            }
        }
        // Soft-retired owners still execute the original complete preservation proof. Their
        // generation is allowed to advance only toward unusable/dirty state; proof and command
        // bytes remain in values above. Retirement is permanent and never becomes a new root.
        require(if (after.generation == old.generation) after.dirty == old.dirty else after.dirty == 1L) {
            "SYNC_RECOVERY_PROTECTED_STATE_CHANGED"
        }
        require(CountTrustRecoveryControl.read(database) == after) { "SYNC_RECOVERY_PROTECTED_STATE_CHANGED" }
    }

    private suspend fun contents(recoveryId: String, control: CountTrustRecoveryControl?): Map<String, String> {
        check(database.inTransaction())
        require(com.dayforge.domain.model.isContractUuid(recoveryId))
        val caller = currentCoroutineContext()
        val definitions = sql.query("SELECT name,sql FROM main.sqlite_master WHERE type='table' ORDER BY name").use { c -> buildList {
            while (c.moveToNext()) add(c.getString(0) to c.getString(1))
        } }
        val excluded = setOf("habits", "metrics", "habit_metric_links", "sync_outbox")
        val recoveryTables = setOf("next_recovery_audits", "next_abandoned_requests", "next_recovery_audit_payloads")
        return buildMap {
            // Keep triggers, indexes and views as well as every table definition. A new dirty
            // counter is not permission to change the transaction's schema or proof storage.
            sql.query("SELECT type,name,tbl_name,sql FROM main.sqlite_master ORDER BY type,name").use { cursor ->
                while (cursor.moveToNext()) {
                    val hash = MessageDigest.getInstance("SHA-256")
                    for (column in 0..3) {
                        hash.update(cursor.getType(column).toByte())
                        if (!cursor.isNull(column)) {
                            val bytes = cursor.getString(column).toByteArray(Charsets.UTF_8)
                            hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes)
                        }
                    }
                    put("object:${cursor.getString(0)}:${cursor.getString(1)}", nextRequestHash(hash.digest()))
                }
            }
            for ((table, definition) in definitions) {
                caller.ensureActive()
                require(table.matches(Regex("[a-zA-Z_][a-zA-Z_0-9]*")))
                // Bind schema as well: this writer neither adds columns nor creates business tables.
                put("schema:$table", nextRequestHash(definition.toByteArray(Charsets.UTF_8)))
                if (table in excluded) continue
                if (table == "count_trust_control" && control != null) {
                    // Strict id=1/native integer fields and complete singleton membership were
                    // checked by read(). Only generation/dirty have a separately proved delta.
                    put(table, "1:${control.storageId}")
                    continue
                }
                // Only this new group may change. Older independent audits are immutable too.
                val where = if (table in recoveryTables) " WHERE recoveryId<>?" else ""
                val args = if (table in recoveryTables) arrayOf<Any>(recoveryId) else emptyArray()
                val columns = sql.query("PRAGMA main.table_info(`$table`)").use { c -> buildList {
                    while (c.moveToNext()) add(c.getString(1) to c.getInt(5))
                } }
                require(columns.isNotEmpty())
                val length = columns.joinToString("+") { "COALESCE(length(CAST(`${it.first}` AS BLOB)),0)" }
                sql.query("SELECT MAX($length) FROM main.`$table`$where", args).use { c ->
                    require(c.moveToFirst() && (c.isNull(0) || c.getType(0) == Cursor.FIELD_TYPE_INTEGER &&
                        c.getLong(0) <= SYNC_REQUEST_LIMIT + 4096)) { "RECOVERY_PROTECTED_ROW_LIMIT" }
                }
                val order = columns.filter { it.second > 0 }.sortedBy { it.second }.joinToString(",") { "`${it.first}`" }.ifEmpty { "rowid" }
                val hash = MessageDigest.getInstance("SHA-256")
                fun bytes(value: ByteArray) {
                    hash.update(ByteBuffer.allocate(4).putInt(value.size).array()); hash.update(value)
                }
                sql.query("SELECT * FROM main.`$table`$where ORDER BY $order", args).use { c ->
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

/** Exact device-local control identity. This grants no credentials or usable proof. */
internal data class CountTrustRecoveryControl(val storageId: String, val generation: Long, val dirty: Long) {
    companion object {
        fun read(database: HabitDatabase): CountTrustRecoveryControl? {
            if (CountTrustRuntime.forDatabase(database) == null) return null
            check(database.inTransaction())
            val sql = database.openHelper.writableDatabase
            CountTrustSchema.validate(sql)
            return sql.query("SELECT id,storageId,generation,dirty FROM count_trust_control").use { cursor ->
                check(cursor.moveToFirst() && cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER && cursor.getLong(0) == 1L &&
                    cursor.getType(1) == Cursor.FIELD_TYPE_STRING && cursor.getType(2) == Cursor.FIELD_TYPE_INTEGER &&
                    cursor.getType(3) == Cursor.FIELD_TYPE_INTEGER) { "COUNT_TRUST_CONTROL_INVALID" }
                val value = CountTrustRecoveryControl(cursor.getString(1), cursor.getLong(2), cursor.getLong(3))
                check(com.dayforge.domain.model.isContractUuid(value.storageId) && value.generation >= 0 && value.dirty in 0L..1L) {
                    "COUNT_TRUST_CONTROL_INVALID"
                }
                check(!cursor.moveToNext()) { "COUNT_TRUST_CONTROL_INVALID" }
                value
            }
        }
    }
}
