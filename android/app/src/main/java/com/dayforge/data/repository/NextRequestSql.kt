package com.dayforge.data.repository

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.api.SYNC_REQUEST_LIMIT
import com.dayforge.data.local.HabitDatabase
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val NEXT_OPERATION = "sync_operation"
internal const val NEXT_TIMER = "timer_command"

internal class NextRequestException(val reason: Reason) : IllegalStateException(reason.name) {
    enum class Reason { STALE_ACCESS, PERMISSION_DENIED, INVALID_LOCAL_STATE, OLD_INTENT,
        SOURCE_CHANGED, TRANSMISSION_CONTEXT_CHANGED, REQUEST_ID_REUSED, UNSUPPORTED_ACCEPTANCE, RESULT_CHANGED,
        CAUSAL_PREDECESSOR_PENDING, TIMER_START_CONFIG_CHANGED, COUNT_START_CONFIG_CHANGED, LOCALLY_ABANDONED }
}

internal fun rejectNextRequest(reason: NextRequestException.Reason): Nothing = throw NextRequestException(reason)
internal fun nextRequestHash(bytes: ByteArray): String =
    digestHex(MessageDigest.getInstance("SHA-256").digest(bytes))

/** Identical lowercase SHA encoding, without allocating a Formatter for every digest byte. */
private fun digestHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
    val digits = "0123456789abcdef"
    for (byte in bytes) {
        val value = byte.toInt() and 255
        append(digits[value ushr 4]); append(digits[value and 15])
    }
}

/** Original framing, buffered only to avoid a native digest call for every length/type/cell.
 * Owns at most 4 KiB scratch per row, no SQL/authority cache and no whole-row byte allocation.
 */
private class RowDigest(private val checkpoint: () -> Unit = {}) {
    private val hash = MessageDigest.getInstance("SHA-256")
    private val buffer = ByteArray(4096)
    private var size = 0

    fun byte(value: Int) {
        if (size == buffer.size) flush()
        buffer[size++] = value.toByte()
    }

    fun bytes(value: ByteArray) {
        for (shift in 24 downTo 0 step 8) byte(value.size ushr shift)
        var offset = 0
        while (offset < value.size) {
            if (size == buffer.size) flush()
            val count = minOf(buffer.size - size, value.size - offset)
            value.copyInto(buffer, size, offset, offset + count)
            size += count; offset += count
        }
    }

    fun integer(value: Long) {
        // Framed 8-byte signed integer, in the same big-endian two's-complement encoding.
        byte(0); byte(0); byte(0); byte(8)
        for (shift in 56 downTo 0 step 8) byte((value ushr shift).toInt())
    }

    private fun flush() {
        checkpoint()
        if (size != 0) { hash.update(buffer, 0, size); size = 0 }
    }

    // MessageDigest.digest resets its state; flush has already emptied the scratch buffer.
    // A scanner may reuse this workspace for the NEXT row, never a previously computed proof.
    fun finish(): String { flush(); return digestHex(hash.digest()) }
}

/** Audit original SQLite types/ranges BEFORE Room's getInt/getLong/String coercion. */
internal object NextRequestSql {
    /** Same original SQLite row encoding; allows a retired source to remain independently auditable. */
    fun sourceHash(row: com.dayforge.data.local.entity.SyncOutboxEntity): String {
        val values = linkedMapOf<String, Any?>("id" to row.id, "operationId" to row.operationId,
            "recordType" to row.recordType, "entityUuid" to row.entityUuid, "wireEntityUuid" to row.wireEntityUuid,
            "action" to row.action, "referenceUuid" to row.referenceUuid, "payloadJson" to row.payloadJson,
            "baseRevision" to row.baseRevision, "basePayloadJson" to row.basePayloadJson,
            "attemptedAt" to row.attemptedAt, "attemptCount" to row.attemptCount.toLong(),
            "lastError" to row.lastError, "errorCode" to row.errorCode, "deadLetteredAt" to row.deadLetteredAt,
            "createdAt" to row.createdAt)
        return savedRowHash("sync_outbox", values)
    }

    /** Exact raw shadow encoding, so a frozen baseline binds all seven original SQL cells. */
    fun entityStateHash(row: com.dayforge.data.local.entity.SyncEntityStateEntity): String = savedRowHash("sync_entity_state",
        linkedMapOf("entityType" to row.entityType, "entityUuid" to row.entityUuid, "revision" to row.revision,
            "deleted" to if (row.deleted) 1L else 0L, "payloadJson" to row.payloadJson,
            "payloadHash" to row.payloadHash, "updatedAt" to row.updatedAt))

    private fun savedRowHash(table: String, values: Map<String, Any?>): String {
        val hash = RowDigest()
        hash.bytes(table.toByteArray(Charsets.UTF_8))
        values.forEach { (name, value) ->
            hash.bytes(name.toByteArray(Charsets.UTF_8))
            hash.byte(when (value) { null -> Cursor.FIELD_TYPE_NULL; is Long -> Cursor.FIELD_TYPE_INTEGER
                is String -> Cursor.FIELD_TYPE_STRING; else -> error("Invalid source snapshot") })
            when (value) {
                is Long -> hash.integer(value)
                is String -> hash.bytes(value.toByteArray(Charsets.UTF_8))
            }
        }
        return hash.finish()
    }
    fun requireOutboxEnabled(sql: SupportSQLiteDatabase) {
        val enabled = sql.query("SELECT id,suppressOutbox FROM sync_control").use { c ->
            c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_INTEGER && c.getLong(0) == 1L &&
                c.getType(1) == Cursor.FIELD_TYPE_INTEGER && c.getLong(1) == 0L && !c.moveToNext()
        }
        if (!enabled) rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
    }

    private val ints = setOf("protocol", "attemptCount", "sequence", "expectedControlGeneration", "expectedRevision", "targetValue", "challengeContract", "selectedCount")
    private val longs = ints + setOf("id", "queueId", "baseRevision", "attemptedAt", "deadLetteredAt", "createdAt",
        "occurredAt", "activeElapsedMillis", "revision", "deleted", "updatedAt", "logicalOrder", "originalQueueId", "replacementQueueId", "generation", "cursor",
        "habitId", "isCountdown", "planQueueWatermark", "logSequence", "recordedAtMillis")
    private val tables = setOf("sync_outbox", "timer_command_outbox", "next_request_origins", "next_transmissions", "next_acceptances",
        "sync_entity_state", "next_structural_dependencies", "next_structural_supersessions",
        "local_fact_submissions", "completion_metric_prompts", "one_time_transmissions", "next_sync_state", "next_rejections", "count_days",
        "next_challenge_state", "next_challenge_rounds", "next_challenge_births", "next_restart_materializations", "next_restart_plan_proofs",
        "next_recovery_audits", "next_abandoned_requests")
    fun table(kind: String): String = when (kind) {
        NEXT_OPERATION -> "sync_outbox"
        NEXT_TIMER -> "timer_command_outbox"
        else -> rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
    }

    /** Legacy raw-handle path stays entirely fresh, even inside another owner's read session. */
    suspend fun rowHash(sql: SupportSQLiteDatabase, table: String, where: String, args: Array<Any>): String? {
        require(table in tables)
        return auditRowHash(sql, table, where, args, lengthExpression(sql, table))
    }

    /** Explicit owner-bound path: share raw audits only within a controlled SELECT-only phase. */
    suspend fun rowHash(database: HabitDatabase, table: String, where: String, args: Array<Any>): String? {
        require(table in tables)
        return NextEvidenceReadSession.rowHash(database, table, where, args,
            length = { sql -> lengthExpression(sql, table) },
            audit = { sql, length -> auditRowHash(sql, table, where, args, length) })
    }

    private suspend fun auditRowHash(sql: SupportSQLiteDatabase, table: String, where: String,
        args: Array<Any>, length: String): String? {
        val caller = currentCoroutineContext()
        // Check lengths in SQLite before materializing a possibly damaged TEXT/BLOB in memory.
        sql.query("SELECT ($length) FROM $table WHERE $where", args).use { c ->
            if (!c.moveToFirst()) return null
            val limit = SYNC_REQUEST_LIMIT + 4096 // Finite payload plus source/journal identity overhead.
            if (c.getType(0) != Cursor.FIELD_TYPE_INTEGER || c.getLong(0) > limit || c.moveToNext())
                rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
        }
        return sql.query("SELECT * FROM $table WHERE $where", args).use { c ->
            check(c.moveToFirst())
            val value = hashRow(c, table) { caller.ensureActive() }
            if (c.moveToNext()) rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
            value
        }
    }

    private fun lengthExpression(sql: SupportSQLiteDatabase, table: String): String {
        val columns = sql.query("PRAGMA table_info($table)").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(1)) }
        }
        require(columns.isNotEmpty())
        return columns.joinToString(" + ") { "COALESCE(length(CAST(`$it` AS BLOB)),0)" }
    }

    /** Same full raw-row audit as rowHash, streamed within ONE caller-owned transaction.
     * Challenge readers discard individual hashes; looking up every already-enumerated key
     * again only adds SQL/PRAGMA round trips. No authority, schema or row result is cached.
     * The scalar preflight rejects oversized cells before a full row enters a CursorWindow.
     */
    suspend fun auditChallengeRows(sql: SupportSQLiteDatabase, table: String) {
        check(sql.inTransaction())
        val keys = when (table) {
            "next_challenge_state" -> listOf("id")
            "next_challenge_rounds" -> listOf("roundUuid")
            "next_challenge_births" -> listOf("entityType", "entityUuid")
            else -> error("Not a challenge audit table")
        }
        val caller = currentCoroutineContext()
        fun key(cursor: Cursor): List<Any> = keys.map { name ->
            val index = cursor.getColumnIndexOrThrow(name)
            if (name == "id") {
                require(cursor.getType(index) == Cursor.FIELD_TYPE_INTEGER && cursor.getLong(index) == 1L)
                cursor.getLong(index)
            } else {
                require(cursor.getType(index) == Cursor.FIELD_TYPE_STRING)
                cursor.getString(index)
            }
        }
        val expected = hashSetOf<List<Any>>()
        val length = lengthExpression(sql, table)
        sql.query("SELECT ${keys.joinToString(",")},($length) FROM $table ORDER BY rowid").use { cursor ->
            while (cursor.moveToNext()) {
                caller.ensureActive()
                require(expected.add(key(cursor)))
                if (cursor.getType(keys.size) != Cursor.FIELD_TYPE_INTEGER || cursor.getLong(keys.size) > SYNC_REQUEST_LIMIT + 4096)
                    rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
            }
        }
        val digest = RowDigest { caller.ensureActive() }
        sql.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
            while (cursor.moveToNext()) {
                caller.ensureActive()
                require(expected.remove(key(cursor)))
                hashRow(cursor, table, digest) { caller.ensureActive() }
            }
        }
        require(expected.isEmpty())
    }

    /** SELECT-only snapshot hint. Oversized batches fall back, never reject/truncate a valid chain. */
    suspend fun boundedRowHashes(sql: SupportSQLiteDatabase, table: String, column: String,
        ids: List<Any>, kind: String? = null, entityType: String? = null): Map<Any, String?>? {
        check(sql.inTransaction())
        require(table in tables && column in setOf("requestId", "operationId", "originalId", "id", "entityUuid") &&
            ids.size in 1..128 && ids.distinct().size == ids.size &&
            ids.all { if (column == "id") it is Long && it > 0 else it is String && com.dayforge.domain.model.isContractUuid(it) } &&
            (kind == null || kind in setOf(NEXT_OPERATION, NEXT_TIMER) && column == "requestId") &&
            (if (column == "entityUuid") table == "sync_entity_state" && entityType == "activity_event" && kind == null
                else entityType == null))
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val discriminator = kind ?: entityType
        val where = (if (kind != null) "kind=? AND " else if (entityType != null) "entityType=? AND " else "") +
            "`$column` IN (${ids.joinToString(",") { "?" }})"
        val args = (if (discriminator == null) ids else listOf(discriminator) + ids).toTypedArray()
        val length = lengthExpression(sql, table)
        val found = mutableSetOf<Any>()
        var total = 0L
        // Only the small, requested key and scalar length are read before the full-row allocation.
        sql.query("SELECT `$column`,($length) FROM $table WHERE $where", args).use { c ->
            while (c.moveToNext()) {
                caller.ensureActive()
                val key: Any = if (column == "id") {
                    if (c.getType(0) != Cursor.FIELD_TYPE_INTEGER) rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
                    c.getLong(0)
                } else {
                    if (c.getType(0) != Cursor.FIELD_TYPE_STRING) rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
                    c.getString(0)
                }
                require(key in ids && found.add(key))
                if (c.getType(1) != Cursor.FIELD_TYPE_INTEGER || c.getLong(1) > SYNC_REQUEST_LIMIT + 4096)
                    rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
                total += c.getLong(1)
            }
        }
        if (total > 2_097_152) return null // Same original per-row audit remains available.
        val hashes = ids.associateWith<Any, String?> { null }.toMutableMap()
        val read = mutableSetOf<Any>()
        val digest = RowDigest { caller.ensureActive() }
        sql.query("SELECT * FROM $table WHERE $where", args).use { c ->
            while (c.moveToNext()) {
                caller.ensureActive()
                val value = hashRow(c, table, digest) { caller.ensureActive() }
                val i = c.getColumnIndexOrThrow(column)
                val key: Any = if (column == "id") c.getLong(i) else c.getString(i)
                require(key in found && read.add(key))
                hashes[key] = value
            }
        }
        require(read == found)
        return hashes
    }

    /** Both single and bounded audits retain the identical original row byte encoding. */
    private fun hashRow(c: Cursor, table: String, workspace: RowDigest? = null, checkpoint: () -> Unit): String {
            val hash = workspace ?: RowDigest(checkpoint)
            hash.bytes(table.toByteArray(Charsets.UTF_8))
            for (i in 0 until c.columnCount) {
                checkpoint()
                val name = c.getColumnName(i)
                val type = c.getType(i)
                hash.bytes(name.toByteArray(Charsets.UTF_8)); hash.byte(type)
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
                        if (table == "count_days" && (name == "isCountdown" && value !in 0..1 ||
                            name == "targetValue" && value <= 0 || name == "habitId" && value <= 0 ||
                            name == "planQueueWatermark" && value < 0))
                            rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
                        hash.integer(value)
                    }
                    Cursor.FIELD_TYPE_BLOB -> hash.bytes(c.getBlob(i))
                    Cursor.FIELD_TYPE_STRING -> hash.bytes(c.getString(i).toByteArray(Charsets.UTF_8))
                    Cursor.FIELD_TYPE_FLOAT, Cursor.FIELD_TYPE_NULL -> rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
                }
            }
            return hash.finish()
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
        check(sql.inTransaction())
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
        val result = linkedMapOf<Long, String>()
        // Each invocation reads a new complete snapshot. Batching only reduces SQL round trips;
        // the row encoding, scalar/type audit and post-write comparisons remain unchanged.
        for (chunk in ids.chunked(128)) {
            currentCoroutineContext().ensureActive()
            val batch = boundedRowHashes(sql, table, "id", chunk)
            for (id in chunk) {
                result[id] = requireNotNull(if (batch == null) rowHash(sql, table, "id=?", arrayOf(id)) else batch[id])
            }
        }
        return result
    }
}
