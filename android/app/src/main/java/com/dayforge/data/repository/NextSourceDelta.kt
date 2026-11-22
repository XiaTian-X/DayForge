package com.dayforge.data.repository

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.local.CountTrustJournal
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.entity.SyncOutboxEntity
import java.util.Collections

/** A replacement ONLY for the producer's post-business source scans. A real original before
 * snapshot is retained. Inputs come from private write-before plans, never journal NEW cells.
 * Other business effects remain under every original validator and the complete kernel audit.
 */
internal class NextSourceDelta private constructor(
    private val capture: Any,
    private val before: Map<String, Map<Long, String>>,
    private val expected: List<SyncOutboxEntity>,
    private val rows: List<CountTrustRows.Row>
) {
    private val oldMax = before.mapValues { (_, rows) -> rows.keys.maxOrNull() ?: 0L }
    private val additions: Map<Long, String> = Collections.unmodifiableMap(linkedMapOf<Long, String>().apply {
        expected.forEach { put(it.id, NextRequestSql.sourceHash(it)) }
    })

    fun requireCapture(value: Any) { check(capture === value) { "SOURCE_DELTA_CAPTURE_CHANGED" } }

    fun newSources(table: String): Map<Long, String> = when (table) {
        "sync_outbox" -> additions
        "timer_command_outbox" -> emptyMap()
        else -> error("SOURCE_DELTA_TABLE")
    }

    /** Used ONLY if a later unsupported queue trace needs the original footer. It is the
     * independently proven post-business snapshot, not a new snapshot of already-mutated SQL.
     */
    fun completeExpected(): Map<String, Map<Long, String>> = before.mapValues { (table, old) -> old + newSources(table) }

    val sourceCount: Int get() = expected.size

    fun verifyWindow(owner: CountTrustRuntime, window: CountTrustJournal.SourceWindow): Boolean {
        val columns = CountTrustJournal.COLUMNS.getValue("sync_outbox")
        return owner.verifySourceWindow(window, rows.map { row -> columns.map(row::cell) })
    }

    suspend fun verifyCurrent(sql: SupportSQLiteDatabase, native: CountTrustRows) {
        for ((source, row) in expected.zip(rows)) {
            // Original scalar byte/type/range audit must precede any full native row read.
            if (NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(source.id)) != additions.getValue(source.id))
                rejectNextRequest(NextRequestException.Reason.SOURCE_CHANGED)
            check(native.byIdentity("sync_outbox", row.identity)?.value == row.value) { "SOURCE_DELTA_FINAL_CHANGED" }
        }
        for (table in TABLES) {
            val watermark = NextRequestSql.watermark(sql, table)
            if (oldMax.getValue(table) > watermark || newSources(table).keys.any { it > watermark })
                rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
        }
    }

    companion object {
        private val TABLES = setOf("sync_outbox", "timer_command_outbox")

        suspend fun prepare(capture: Any, before: Map<String, Map<Long, String>>, watermarks: Map<String, Long>,
            expected: List<SyncOutboxEntity>, window: CountTrustJournal.SourceWindow,
            owner: CountTrustRuntime, sql: SupportSQLiteDatabase, native: CountTrustRows): NextSourceDelta? {
            require(before.keys == TABLES && watermarks.keys == TABLES && expected.isNotEmpty())
            val ordered = expected.sortedBy { it.id }.map { it.copy() }
            if (ordered.map { it.id }.distinct().size != ordered.size) return null
            if (ordered.any { it.id in before.getValue("sync_outbox") }) return null
            if (ordered.any { it.id <= watermarks.getValue("sync_outbox") })
                rejectNextRequest(NextRequestException.Reason.OLD_INTENT)
            if (before.getValue("sync_outbox").size + ordered.size > 10000)
                rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
            val old = Collections.unmodifiableMap(before.mapValues { (_, value) ->
                Collections.unmodifiableMap(LinkedHashMap(value))
            })
            val result = NextSourceDelta(capture, old, Collections.unmodifiableList(ordered), Collections.unmodifiableList(ordered.map { sourceRow(native, it) }))
            result.verifyCurrent(sql, native)
            if (!result.verifyWindow(owner, window)) return null
            return result
        }

        private fun sourceRow(native: CountTrustRows, row: SyncOutboxEntity): CountTrustRows.Row {
            val values: Map<String, Any?> = linkedMapOf(
                "id" to row.id, "operationId" to row.operationId, "recordType" to row.recordType,
                "entityUuid" to row.entityUuid, "wireEntityUuid" to row.wireEntityUuid, "action" to row.action,
                "referenceUuid" to row.referenceUuid, "payloadJson" to row.payloadJson, "baseRevision" to row.baseRevision,
                "basePayloadJson" to row.basePayloadJson, "attemptedAt" to row.attemptedAt, "attemptCount" to row.attemptCount.toLong(),
                "lastError" to row.lastError, "errorCode" to row.errorCode, "deadLetteredAt" to row.deadLetteredAt,
                "createdAt" to row.createdAt)
            val layout = native.table("sync_outbox")
            require(values.keys == layout.columns.toSet())
            return CountTrustRows.Row(layout, layout.columns.map { name -> when (val value = values.getValue(name)) {
                null -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_NULL, null)
                is Long -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_INTEGER, value)
                is String -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_STRING, value)
                else -> error("SOURCE_DELTA_CELL")
            } })
        }
    }
}
