package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.local.CountTrustJournal
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.data.local.entity.SyncConflictEntity
import com.dayforge.data.local.entity.SyncEntityStateEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import java.util.Collections
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Scoped observation ACK adapter, independent of full merger.
 * Production callers MUST require the new observation-coverage marker, global_common and an
 * independently prepared formal metadata sidecar (head=null). This class cannot supply any of
 * those certificates. No acceptance receipt/outbox deletion happens here; the request owner
 * constructs those effects separately and compares the full ordered native journal.
 */
internal class CountTrustMetricAck(private val database: HabitDatabase, private val sources: CountTrustAck.Sources) {
    class Prepared internal constructor(val plan: MetricAckFactRules.Plan,
        internal val observation: CountTrustRows.Row?, internal val shadow: CountTrustRows.Row?,
        internal val control: CountTrustRows.Row)
    private val sql get() = database.openHelper.writableDatabase
    private val native get() = CountTrustRows(sql)

    private suspend fun rows(kind: String, table: String, vararg parts: String,
        where: String = "", args: Array<out Any?> = emptyArray()) =
        sources.rows(CountTrustMembership.key(kind, *parts), table, where, args)

    suspend fun prepare(change: SyncV2Change, operation: SyncV2Operation, deviceId: String,
        acceptedQueueId: Long, shadowUpdatedAt: Long): Prepared? {
        check(database.inTransaction())
        if (!MetricAckFactRules.supports(operation, change)) return null
        val parent = change.payload.text("metric_uuid")
        val parentRows = rows("metric.uuid", "metrics", parent, where = "uuid=?", args = arrayOf(parent))
        // Missing/deleted parent can be a legitimate shadow-only ACK. Preserve the original
        // proof of parent deletion; do not invent a rejection or fabricate that proof here.
        if (parentRows.size != 1) return null
        val metric = requireNotNull(database.metricDao().getMetricByUuid(parent))
        check(metric.id == parentRows.single().long("id"))
        val id = change.entityUuid
        val logs = rows("metricLog.uuid", "metric_logs", id, where = "uuid=?", args = arrayOf(id))
        if (logs.size > 1) throw NextFactMergeException(NextFactMergeException.Reason.INVALID_LOCAL_STATE)
        val shadows = rows("shadow.entity", "sync_entity_state", "metric_observation", id,
            where = "entityType=? AND entityUuid=?", args = arrayOf("metric_observation", id))
        require(shadows.size <= 1)
        val pending = (rows("outbox.entity", "sync_outbox", "metric_observation", id) +
            rows("outbox.wire", "sync_outbox", "metric_observation", id)).distinctBy { it.name }.map { it.outbox() }
        val conflicts = (rows("conflict.entity", "sync_conflicts", "metric_observation", id) +
            rows("conflict.wire", "sync_conflicts", "metric_observation", id)).distinctBy { it.name }
            .filter { it.text("status") == "unresolved" }.map { it.conflict() }
        val control = rows("control.id", "sync_control", "1", where = "id=?", args = arrayOf(1)).single()
        require(control.int("id") == 1 && control.int("suppressOutbox") == 0)
        val plan = MetricAckFactRules.prepare(MetricAckFactRules.Input(change, operation, deviceId,
            acceptedQueueId, shadowUpdatedAt, metric, logs.singleOrNull()?.observation(),
            shadows.singleOrNull()?.shadow(), pending, conflicts))
        return Prepared(plan, logs.singleOrNull(), shadows.singleOrNull(), control)
    }

    suspend fun apply(prepared: Prepared): List<CountTrustAck.Effect> {
        check(database.inTransaction())
        val effects = arrayListOf<CountTrustAck.Effect>()
        val busy = replace(prepared.control, mapOf("suppressOutbox" to 1))
        sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        effects += CountTrustAck.Effect("sync_control", prepared.control, busy)
        prepared.plan.observationUpsert?.let { observation ->
            val saved = database.metricLogDao().upsert(observation)
            check(saved > 0 && (observation.id == 0L || observation.id == saved))
            effects += CountTrustAck.Effect("metric_logs", prepared.observation, observationRow(observation.copy(id = saved)))
        }
        prepared.plan.shadowUpsert?.let { shadow ->
            check(prepared.shadow == null)
            val expected = shadowRow(shadow)
            database.syncOutboxDao().upsertState(shadow)
            effects += CountTrustAck.Effect("sync_entity_state", null, expected)
        }
        sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        effects += CountTrustAck.Effect("sync_control", busy, prepared.control)
        for (effect in effects) {
            val expected = requireNotNull(effect.after)
            if (effect.table == "sync_control" && expected !== prepared.control) continue
            check(native.byIdentity(effect.table, expected.identity)?.value == expected.value) { "METRIC_ACK_EFFECT_CHANGED" }
        }
        return Collections.unmodifiableList(effects)
    }

    private fun row(table: String, values: Map<String, Any?>): CountTrustRows.Row {
        val layout = native.table(table)
        require(values.keys == layout.columns.toSet())
        return CountTrustRows.Row(layout, layout.columns.map { cell(values.getValue(it)) })
    }
    private fun replace(row: CountTrustRows.Row, values: Map<String, Any?>) = CountTrustRows.Row(row.table,
        row.table.columns.map { if (it in values) cell(values[it]) else row.cell(it) })
    private fun observationRow(value: MetricLogEntity) = row("metric_logs", mapOf(
        "id" to value.id, "metricId" to value.metricId, "date" to value.date, "value" to value.value,
        "unit" to value.unit, "note" to value.note, "uuid" to value.uuid, "createdAt" to value.createdAt,
        "updatedAt" to value.updatedAt, "recordedTimezone" to value.recordedTimezone,
        "recordedLocalDate" to value.recordedLocalDate, "timeMetadataSource" to value.timeMetadataSource))
    private fun shadowRow(value: SyncEntityStateEntity) = row("sync_entity_state", mapOf(
        "entityType" to value.entityType, "entityUuid" to value.entityUuid, "revision" to value.revision,
        "deleted" to value.deleted, "payloadJson" to value.payloadJson, "payloadHash" to value.payloadHash,
        "updatedAt" to value.updatedAt))

    private fun CountTrustRows.Row.observation(): MetricLogEntity {
        val raw = cell("value").also { require(it.type == Cursor.FIELD_TYPE_FLOAT) }
        return MetricLogEntity(long("id"), long("metricId"), long("date"), Double.fromBits(raw.value as Long),
            text("unit"), text("note"), text("uuid"), long("createdAt"), long("updatedAt"),
            text("recordedTimezone"), text("recordedLocalDate"), text("timeMetadataSource"))
    }
    private fun CountTrustRows.Row.outbox() = SyncOutboxEntity(long("id"), text("operationId"), text("recordType"),
        text("entityUuid"), text("wireEntityUuid"), text("action"), nullableText("referenceUuid"), nullableText("payloadJson"),
        nullableLong("baseRevision"), nullableText("basePayloadJson"), nullableLong("attemptedAt"), int("attemptCount"),
        nullableText("lastError"), nullableText("errorCode"), nullableLong("deadLetteredAt"), long("createdAt"))
    private fun CountTrustRows.Row.conflict() = SyncConflictEntity(long("id"), text("operationId"), text("recordType"),
        text("localEntityUuid"), text("wireEntityUuid"), text("entityType"), text("action"), nullableText("referenceUuid"),
        nullableLong("baseRevision"), long("serverRevision"), nullableText("basePayloadJson"), text("localPayloadJson"),
        text("serverPayloadJson"), text("conflictingFieldsJson"), nullableText("conflictKind"), nullableText("errorCode"),
        nullableText("message"), text("status"), nullableText("resolution"), long("createdAt"), nullableLong("resolvedAt"))

    private fun JsonObject.text(key: String): String = getValue(key).let {
        require(it is JsonPrimitive && it.isString); it.content
    }
    private fun cell(value: Any?) = when (value) {
        null -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_NULL, null)
        is Boolean -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_INTEGER, if (value) 1L else 0L)
        is Int -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_INTEGER, value.toLong())
        is Long -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_INTEGER, value)
        is Double -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_FLOAT, value.toRawBits())
        is String -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_STRING, value)
        else -> error("METRIC_ACK_UNSUPPORTED_CELL")
    }
}
