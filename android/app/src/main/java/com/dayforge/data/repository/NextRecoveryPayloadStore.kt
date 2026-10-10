@file:kotlinx.serialization.UseSerializers(com.dayforge.domain.model.ContractStringSerializer::class,
    com.dayforge.domain.model.ContractIntegerSerializer::class)

package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.api.SYNC_REQUEST_LIMIT
import com.dayforge.data.local.HabitDatabase
import com.dayforge.domain.model.isContractUuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

@Serializable
internal data class NextRecoveryPayloadReference(val key: String, val byteCount: Int, val hash: String) {
    init {
        require(byteCount in 1..nextRecoveryPayloadLimit(key) && hash.matches(Regex("[0-9a-f]{64}")))
    }
}

internal const val NEXT_RECOVERY_CHUNK_BYTES = 65_536
// JSON escaping may expand one original byte to six. Do not reuse the wire-body byte ceiling here.
internal const val NEXT_RECOVERY_SOURCE_BYTES = 6 * (SYNC_REQUEST_LIMIT + 4096) + 4096

private fun nextRecoveryPayloadLimit(key: String): Int = when {
    key == "manifest" -> 67_108_864 // Bounded descriptors/graph; never inline all source bodies here.
    key == "checkpoint" -> SYNC_REQUEST_LIMIT
    key.matches(Regex("source:sync_operation:[0-9a-f-]{36}")) && isContractUuid(key.substringAfterLast(':')) -> NEXT_RECOVERY_SOURCE_BYTES
    key.matches(Regex("projection_(before|after):(plan_node|metric|activity_metric_link):[0-9a-f-]{36}")) &&
        isContractUuid(key.substringAfterLast(':')) -> NEXT_RECOVERY_SOURCE_BYTES
    else -> error("RECOVERY_PAYLOAD_KEY_INVALID")
}

/**
 * Byte storage only, NOT a terminal-state or authority validator. The complete audit consumer must
 * additionally bind the exact key set, membership, original sources, graph, account and results.
 * Caller owns the account lock and Room transaction. No HTTP, queue retirement or business writes.
 */
internal class NextRecoveryPayloadStore(private val database: HabitDatabase) {
    private val sql get() = database.openHelper.writableDatabase

    suspend fun write(recoveryId: String, key: String, value: ByteArray): NextRecoveryPayloadReference {
        check(database.inTransaction()); require(isContractUuid(recoveryId))
        currentCoroutineContext().ensureActive()
        require(value.size in 1..nextRecoveryPayloadLimit(key))
        val reference = NextRecoveryPayloadReference(key, value.size, nextRequestHash(value))
        sql.query("SELECT 1 FROM next_recovery_audit_payloads WHERE recoveryId=? AND payloadKey=? LIMIT 1",
            arrayOf(recoveryId, key)).use { require(!it.moveToFirst()) { "RECOVERY_PAYLOAD_ALREADY_EXISTS" } }
        for (offset in value.indices step NEXT_RECOVERY_CHUNK_BYTES) {
            currentCoroutineContext().ensureActive()
            val chunk = value.copyOfRange(offset, minOf(offset + NEXT_RECOVERY_CHUNK_BYTES, value.size))
            sql.execSQL("INSERT INTO next_recovery_audit_payloads(recoveryId,payloadKey,part,bytes) VALUES(?,?,?,?)",
                arrayOf(recoveryId, key, offset / NEXT_RECOVERY_CHUNK_BYTES, chunk))
        }
        require(read(recoveryId, reference).contentEquals(value)) { "RECOVERY_PAYLOAD_CHANGED" }
        return reference
    }

    suspend fun read(recoveryId: String, reference: NextRecoveryPayloadReference): ByteArray {
        check(database.inTransaction()); require(isContractUuid(recoveryId))
        currentCoroutineContext().ensureActive()
        val count = (reference.byteCount + NEXT_RECOVERY_CHUNK_BYTES - 1) / NEXT_RECOVERY_CHUNK_BYTES
        val args = arrayOf<Any>(recoveryId, reference.key)
        // Scalar lengths/types first: never ask CursorWindow to allocate a forged multi-MiB row.
        sql.query("SELECT CASE WHEN typeof(part)='integer' THEN part ELSE NULL END,typeof(bytes),length(bytes) FROM next_recovery_audit_payloads " +
            "WHERE recoveryId=? AND payloadKey=? ORDER BY part", args).use { c ->
            var part = 0
            while (c.moveToNext()) {
                currentCoroutineContext().ensureActive()
                require(part < count && c.getType(0) == Cursor.FIELD_TYPE_INTEGER && c.getLong(0) == part.toLong() &&
                    c.getType(1) == Cursor.FIELD_TYPE_STRING && c.getString(1) == "blob" &&
                    c.getType(2) == Cursor.FIELD_TYPE_INTEGER &&
                    c.getLong(2) == minOf(NEXT_RECOVERY_CHUNK_BYTES, reference.byteCount - part * NEXT_RECOVERY_CHUNK_BYTES).toLong()) {
                    "RECOVERY_PAYLOAD_PARTS_INVALID"
                }
                part++
            }
            require(part == count) { "RECOVERY_PAYLOAD_INCOMPLETE" }
        }
        val output = ByteArray(reference.byteCount)
        sql.query("SELECT part,bytes FROM next_recovery_audit_payloads WHERE recoveryId=? AND payloadKey=? ORDER BY part", args).use { c ->
            var part = 0
            while (c.moveToNext()) {
                currentCoroutineContext().ensureActive()
                require(part < count && c.getType(0) == Cursor.FIELD_TYPE_INTEGER && c.getLong(0) == part.toLong() &&
                    c.getType(1) == Cursor.FIELD_TYPE_BLOB)
                val bytes = c.getBlob(1)
                require(bytes.size == minOf(NEXT_RECOVERY_CHUNK_BYTES, reference.byteCount - part * NEXT_RECOVERY_CHUNK_BYTES))
                bytes.copyInto(output, part * NEXT_RECOVERY_CHUNK_BYTES); part++
            }
            require(part == count)
        }
        return output.also {
            require(it.size == reference.byteCount && nextRequestHash(it) == reference.hash) { "RECOVERY_PAYLOAD_HASH_CHANGED" }
        }
    }

    /** Exact keys must be compared by the complete receipt reader; unknown/extra keys are not ignored. */
    suspend fun keys(recoveryId: String): Set<String> {
        check(database.inTransaction()); require(isContractUuid(recoveryId))
        currentCoroutineContext().ensureActive()
        val result = linkedSetOf<String>()
        sql.query("SELECT typeof(payloadKey),length(CAST(payloadKey AS BLOB)) FROM next_recovery_audit_payloads " +
            "WHERE recoveryId=? GROUP BY payloadKey LIMIT 30003", arrayOf(recoveryId)).use { c ->
            var count = 0
            while (c.moveToNext()) {
                currentCoroutineContext().ensureActive()
                require(++count <= 30_002 && c.getType(0) == Cursor.FIELD_TYPE_STRING && c.getString(0) == "text" &&
                    c.getType(1) == Cursor.FIELD_TYPE_INTEGER && c.getLong(1) in 1L..128L)
            }
        }
        sql.query("SELECT DISTINCT payloadKey,length(CAST(payloadKey AS BLOB)) FROM next_recovery_audit_payloads " +
            "WHERE recoveryId=? ORDER BY payloadKey LIMIT 30003", arrayOf(recoveryId)).use { c ->
            while (c.moveToNext()) {
                currentCoroutineContext().ensureActive()
                require(c.getType(1) == Cursor.FIELD_TYPE_INTEGER && c.getLong(1) in 1L..128L)
                require(c.getType(0) == Cursor.FIELD_TYPE_STRING)
                val key = c.getString(0); nextRecoveryPayloadLimit(key)
                require(result.add(key) && result.size <= 30_002)
            }
        }
        return result.toSet()
    }
}
