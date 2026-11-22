package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.local.CountTrustJournal
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.MetricLogEntity
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Complete independent legacy 2N journal, constructed independently from original logs and the narrow
 * actual INSERT IDs/trigger ID-operationId-createdAt allocations. Command stage cannot add
 * any business write, including same-value UPDATE. Native terminal reads are extra checks.
 */
internal class IndependentMetricLegacyPlan private constructor(private val database: HabitDatabase,
    val intent: CountCommandRecords.Intent, private val caller: Job?, private val token: Any,
    private val boundary: CountTrustRuntime.PrimaryBusinessBoundary, private val watermark: Long,
    private val selection: JsonObject, private val resolution: JsonObject) {
    private val owner get() = requireNotNull(CountTrustRuntime.forDatabase(database))
    private val input = intent.body().getValue("input").jsonObject
    private val selected = listOf(selection)
    private var logs: List<MetricLogEntity>? = null
    private var ids: List<Long>? = null
    private var effects: LegacyCountEffectPlan.Bound? = null
    private var proven = false
    private var closed = false
    private suspend fun live() {
        currentCoroutineContext().ensureActive()
        check(!closed && database.inTransaction() && currentCoroutineContext()[Job] === caller) { "COMMAND_LEGACY_METRIC_FRAME_EXPIRED" }
        owner.requirePrimaryReadToken(database, token)
    }
    suspend fun uuid(metricId: Long): String {
        live(); return CountCommandRecords.uuid(selected.single { CountCommandRecords.integer(it, "metric_id") == metricId }, "reserved_fact_uuid")
    }
    suspend fun constructed(values: List<MetricLogEntity>) {
        live(); check(logs == null && ids == null && values.size == selected.size)
        values.forEachIndexed { index, log ->
            val planned = selected[index]
            val requested = Double.fromBits(java.lang.Long.parseUnsignedLong(CountCommandRecords.text(planned, "value_bits"), 16))
            check(log.id == 0L && log.metricId == CountCommandRecords.integer(planned, "metric_id") &&
                log.uuid == CountCommandRecords.uuid(planned, "reserved_fact_uuid") && log.date == CountCommandRecords.integer(input, "recorded_at") &&
                log.value.toRawBits() == requested.toRawBits() && log.note == CountCommandRecords.text(planned, "note") &&
                log.unit == nativeText(planned.getValue("definition_native").jsonObject, "unit") &&
                log.recordedLocalDate == Instant.ofEpochMilli(log.date).atZone(ZoneId.of(log.recordedTimezone)).toLocalDate().toString() &&
                log.timeMetadataSource == "captured" && log.recordedTimezone == CountCommandRecords.text(input, "timezone")) { "COMMAND_LEGACY_METRIC_INPUT_CHANGED" }
        }
        logs = values.map { it.copy() }
    }
    suspend fun inserted(values: List<Long>) {
        live(); check(ids == null && logs != null && values.size == selected.size && values.all { it > 0 } && values.distinct().size == values.size)
        ids = values.toList()
    }
    suspend fun result(): CountCommandOutcome {
        live(); check(!proven); proven = true
        val raw = CountTrustRows(database.openHelper.writableDatabase)
        val allocated = allocations()
        val complete = requireNotNull(logs).mapIndexed { index, log ->
            val id = requireNotNull(ids)[index]
            // SQLite may normalize only the sign of zero. Keep the original T0 bit string;
            // bind this one allowed native representation, never arbitrary observed fields.
            val value = if (log.value.toRawBits() == (-0.0).toRawBits()) database.openHelper.writableDatabase.query(
                "SELECT typeof(value),value FROM metric_logs WHERE id=?", arrayOf(id)).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "real" && cursor.getDouble(1) == 0.0)
                cursor.getDouble(1).also { check(!cursor.moveToNext()) }
            } else log.value
            log.copy(id = id, value = value)
        }
        val groups = complete.mapIndexed { index, log ->
            val source = allocated[index]
            listOf(CountTrustAck.Effect("metric_logs", null, row(raw.table("metric_logs"), logFields(log))),
                CountTrustAck.Effect("sync_outbox", null, row(raw.table("sync_outbox"), sourceFields(source, log, selected[index]))))
        }
        effects = LegacyCountEffectPlan.Bound(groups)
        verifyAfterStage()
        val records = requireNotNull(effects).effects.map { effect ->
            val row = requireNotNull(effect.after)
            CountCommandEvidence.Record.frozen(effect.table,
                if (effect.table == "metric_logs") CountCommandEvidence.Role.INSERTED_FACT else CountCommandEvidence.Role.ORIGINAL_SOURCE,
                row.table.columns.associateWith { name -> row.cell(name).let { cell -> when (cell.type) {
                    Cursor.FIELD_TYPE_FLOAT -> Double.fromBits(cell.value as Long)
                    else -> cell.value
                } } })
        }
        val evidence = CountCommandEvidence.independent(intent, resolution, records)
        val body = buildJsonObject {
            put("format", intent.format); put("metric_resolution", resolution); put("command_id", intent.commandId); put("input_hash", intent.inputHash)
            put("namespace_hash", intent.namespace.hash); put("action", intent.action.name); put("kind", "APPLIED")
            put("primary_fact_uuid", complete.first().uuid); put("primary_request_id", allocated.first().operationId)
            put("reason", JsonNull); put("follow_up", "NONE"); put("origins", JsonArray(emptyList())); put("evidence", evidence.body())
            put("effects", JsonArray(complete.mapIndexed { index, log -> buildJsonObject {
                put("kind", "METRIC_OBSERVATION"); put("fact_uuid", log.uuid)
                put("object_uuid", CountCommandRecords.text(selected[index], "metric_uuid")); put("reverts_fact_uuid", JsonNull)
            } }))
        }
        val bytes = CountCommandRecords.canonical(body).toByteArray(Charsets.UTF_8)
        return CountCommandOutcome.decode(bytes, bytes.size, {}).also { verifyHistorical(intent, it) }
    }
    suspend fun verifyAfterStage() {
        live(); val expected = requireNotNull(effects)
        owner.maintenance(database) {
            expected.verifyActual(owner.primaryBusinessChanges(boundary))
            val raw = CountTrustRows(database.openHelper.writableDatabase)
            expected.verifyFinal(expected.finalRows.mapValues { (_, row) -> requireNotNull(row).let { raw.byIdentity(it.table.name, it.identity) } })
        }
    }
    internal suspend fun databaseForProof(): HabitDatabase { live(); check(proven); return database }
    fun close() { closed = true }
    private fun allocations(): List<LegacyCountEffectPlan.SourceAllocation> {
        val sql = database.openHelper.writableDatabase; val count = selected.size
        sql.query("SELECT typeof(id),typeof(operationId),length(CAST(operationId AS BLOB)),typeof(createdAt) FROM sync_outbox WHERE id>? ORDER BY id LIMIT ?",
            arrayOf<Any>(watermark, count + 1)).use { cursor ->
            var observed = 0
            while (cursor.moveToNext()) {
                observed++; check(cursor.getString(0) == "integer" && cursor.getString(1) == "text" && cursor.getLong(2) in 36..144 && cursor.getString(3) == "integer")
            }
            check(observed == count) { "COMMAND_LEGACY_METRIC_SOURCE_COUNT" }
        }
        return sql.query("SELECT id,operationId,createdAt FROM sync_outbox WHERE id>? ORDER BY id LIMIT ?", arrayOf<Any>(watermark, count + 1)).use { cursor ->
            var previous = watermark
            buildList { while (cursor.moveToNext()) {
                val source = LegacyCountEffectPlan.SourceAllocation(cursor.getLong(0), cursor.getString(1), cursor.getLong(2))
                require(source.id > previous && com.dayforge.domain.model.isContractUuid(source.operationId) && source.createdAt % 1000L == 0L)
                previous = source.id; add(source)
            } }.also { list -> require(list.map { it.operationId }.distinct().size == count) }
        }
    }
    companion object {
        suspend fun capture(database: HabitDatabase, intent: CountCommandRecords.Intent,
            metric: com.dayforge.data.local.entity.MetricEntity): IndependentMetricLegacyPlan {
            require(IndependentMetricInput.isLegacy(intent) && metric.appearance == null)
            val owner = requireNotNull(CountTrustRuntime.forDatabase(database)); val caller = currentCoroutineContext()[Job]
            return owner.maintenance(database) {
                val token = owner.capturePrimaryReadToken(database); val boundary = owner.capturePrimaryBusinessBoundary()
                val sql = database.openHelper.writableDatabase; val raw = CountTrustRows(sql); val input = IndependentMetricInput.input(intent)
                val native = raw.read("metrics", "id=?", arrayOf(metric.id)).single()
                require(CountCommandLegacyMetricInput.matchesDefinition(native.value, metric)) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
                val selected = buildJsonObject {
                    put("metric_id", metric.id); put("metric_uuid", metric.uuid); put("definition_native", native.value)
                    put("reserved_fact_uuid", input.getValue("reserved_fact_uuid"))
                    put("value_bits", input.getValue("value_bits")); put("note", input.getValue("note"))
                }
                IndependentMetricLegacyPlan(database, intent, caller, token, boundary, NextRequestSql.watermark(sql, "sync_outbox"),
                    selected, IndependentMetricInput.resolution(intent, metric))
            }
        }
        fun verifyHistorical(intent: CountCommandRecords.Intent, outcome: CountCommandOutcome) =
            IndependentMetricEvidence.requireLegacy(intent, outcome)
        private fun nativeText(row: JsonObject, name: String): String = CountTrustRows.decodeCell(row.getValue(name)).let {
            require(it.type == Cursor.FIELD_TYPE_STRING); it.value as String
        }
        private fun row(table: CountTrustRows.Table, values: Map<String, Any?>): CountTrustRows.Row {
            require(table.columns.toSet() == values.keys)
            return CountTrustRows.Row(table, table.columns.map { key -> when (val value = values.getValue(key)) {
                null -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_NULL, null)
                is String -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_STRING, value)
                is Long -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_INTEGER, value)
                is Double -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_FLOAT, value.toRawBits())
                else -> error("COMMAND_LEGACY_METRIC_EXPECTED_TYPE")
            } })
        }
        private fun logFields(log: MetricLogEntity): Map<String, Any?> = mapOf("id" to log.id, "metricId" to log.metricId, "date" to log.date,
            "value" to log.value, "unit" to log.unit, "note" to log.note, "uuid" to log.uuid, "createdAt" to log.createdAt,
            "updatedAt" to log.updatedAt, "recordedTimezone" to log.recordedTimezone, "recordedLocalDate" to log.recordedLocalDate, "timeMetadataSource" to log.timeMetadataSource)
        private fun sourceFields(source: LegacyCountEffectPlan.SourceAllocation, log: MetricLogEntity, selected: JsonObject): Map<String, Any?> = mapOf(
            "id" to source.id, "operationId" to source.operationId, "recordType" to "metric_log", "entityUuid" to log.uuid, "wireEntityUuid" to log.uuid,
            "action" to "upsert", "referenceUuid" to CountCommandRecords.text(selected, "metric_uuid"), "payloadJson" to null, "baseRevision" to null,
            "basePayloadJson" to null, "attemptedAt" to null, "attemptCount" to 0L, "lastError" to null, "errorCode" to null, "deadLetteredAt" to null, "createdAt" to source.createdAt)
    }
}
