package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import java.util.UUID
import kotlinx.serialization.json.*

/** Entire original legacy prompt input. Native definitions freeze the original complete DTO
 * equality rule; no serialized field is an authority. Original current claim/link checks run
 * again in T1, under fresh permissions. This is a child of one authenticated COUNT outcome.
 */
internal object CountCommandLegacyMetricInput {
    suspend fun create(database: HabitDatabase, access: LocalCoreWriteAccess, parent: CountCommandReceipt,
        claim: HabitPreferenceClaim, preferences: HabitPreferenceRepository, habit: HabitEntity,
        metrics: List<MetricEntity>, values: List<MetricValueDraft>, recordedAt: Long): CountCommandRecords.Intent {
        require(habit.appearance == null && habit.habitType == com.dayforge.data.model.HabitType.COUNTING &&
            claim.session == access.session && claim.habitId == habit.id && claim.habitUuid == habit.uuid)
        require(values.isNotEmpty() && values.map { it.metricId }.distinct().size == values.size &&
            metrics.map { it.id }.toSet() == values.map { it.metricId }.toSet() && metrics.size == values.size && metrics.all { it.appearance == null })
        require(parent.recorded && parent.committed.outcome.action == CountCommandRecords.Action.COUNT_ADD &&
            parent.committed.outcome.effects.single().objectUuid == habit.uuid)
        val captured = database.withTransaction {
            preferences.requireWidgetWriteInTransaction(claim)
            check(database.habitDao().getVisibleHabitById(habit.id) == habit) { "METRIC_PROMPT_ACTIVITY_CHANGED" }
            val native = CountTrustRows(database.openHelper.writableDatabase)
            val selected = values.map { value ->
                require(value.value.isFinite()) { "Metric value must be finite" }
                val metric = metrics.single { it.id == value.metricId }
                check(database.metricDao().getVisibleMetricById(metric.id) == metric) { "METRIC_PROMPT_METADATA_CHANGED" }
                val link = requireNotNull(database.habitMetricLinkDao().getLink(habit.id, metric.id)) { "METRIC_PROMPT_LINK_CHANGED" }
                NextMetricDeletionStore(database).requireLinkWritable(link)
                check(link.isActive && link.promptOnComplete && link.habitUuid == habit.uuid && link.metricUuid == metric.uuid) { "METRIC_PROMPT_LINK_CHANGED" }
                buildJsonObject {
                    put("metric_id", metric.id); put("metric_uuid", metric.uuid)
                    put("definition_native", native.read("metrics", "id=?", arrayOf(metric.id)).single().value)
                    put("link", CountCommandInputs.metricLink(link)); put("value_bits", value.value.toRawBits().toULong().toString(16).padStart(16, '0'))
                    put("note", value.note); put("reserved_fact_uuid", UUID.randomUUID().toString())
                }
            }
            native.read("habits", "id=?", arrayOf(habit.id)).single().value to selected
        }
        val namespace = CountCommandInputs.locator(database, access.session, access.capturedDeviceId)
        val input = buildJsonObject {
            put("local_profile", CountCommandLegacyInput.PROFILE); put("parent_command_id", parent.commandId)
            put("habit_id", habit.id); put("habit_uuid", habit.uuid); put("habit_native", captured.first)
            put("source_fact_uuid", requireNotNull(parent.factUuid)); put("recorded_at", recordedAt)
            put("clock_policy", "original-recordValues-entry-timezone-and-construction/v1")
            put("widget_claim", claim.widgetIdentity?.let { Json.parseToJsonElement(it.encode()) } ?: JsonNull)
            put("metrics", JsonArray(captured.second))
        }
        val body = buildJsonObject {
            put("format", CountCommandRecords.FORMAT); put("command_id", UUID.randomUUID().toString()); put("namespace", namespace.body())
            put("original_auth_generation", access.session.authentication.generation); put("action", CountCommandRecords.Action.LINKED_METRICS.name)
            put("capture", CountCommandRecords.Capture.EXECUTION_INPUT.name); put("input", input)
        }
        val bytes = CountCommandRecords.canonical(body).toByteArray(Charsets.UTF_8)
        return CountCommandRecords.Intent.decode(bytes, bytes.size, {})
    }

    /** Pure reconstruction of the exact legacy DTO definition captured by the original
     * writer. This is input matching, never evidence that current native rows are trusted. */
    fun matchesDefinition(frozen: JsonObject, metric: MetricEntity): Boolean {
        if (metric.appearance != null) return false
        val values: Map<String, Any?> = linkedMapOf(
            "id" to metric.id, "name" to metric.name, "description" to metric.description,
            "unit" to metric.unit, "decimalPlaces" to metric.decimalPlaces.toLong(), "aggregationType" to metric.aggregationType,
            "targetDirection" to metric.targetDirection, "targetValue" to metric.targetValue,
            "targetValueUpper" to metric.targetValueUpper, "iconResId" to metric.iconResId.toLong(),
            "colorHex" to metric.colorHex, "isActive" to if (metric.isActive) 1L else 0L,
            "uuid" to metric.uuid, "createdAt" to metric.createdAt, "updatedAt" to metric.updatedAt, "appearance" to null)
        require(values.keys == com.dayforge.data.local.CountTrustJournal.COLUMNS.getValue("metrics").toSet())
        val expected = buildJsonObject {
            for ((name, value) in values) put(name, CountTrustRows.encode(when (value) {
                null -> com.dayforge.data.local.CountTrustJournal.Cell(android.database.Cursor.FIELD_TYPE_NULL, null)
                is String -> com.dayforge.data.local.CountTrustJournal.Cell(android.database.Cursor.FIELD_TYPE_STRING, value)
                is Long -> com.dayforge.data.local.CountTrustJournal.Cell(android.database.Cursor.FIELD_TYPE_INTEGER, value)
                is Double -> com.dayforge.data.local.CountTrustJournal.Cell(android.database.Cursor.FIELD_TYPE_FLOAT, value.toRawBits())
                else -> error("COMMAND_METRIC_DEFINITION_TYPE")
            }))
        }
        return frozen == expected
    }

    fun validate(input: JsonObject) {
        CountCommandRecords.fields(input, "local_profile", "parent_command_id", "habit_id", "habit_uuid", "habit_native",
            "source_fact_uuid", "recorded_at", "clock_policy", "widget_claim", "metrics")
        for (key in listOf("parent_command_id", "habit_uuid", "source_fact_uuid")) CountCommandRecords.uuid(input, key)
        require(CountCommandRecords.integer(input, "habit_id") > 0); CountCommandRecords.integer(input, "recorded_at")
        require(CountCommandRecords.text(input, "clock_policy") == "original-recordValues-entry-timezone-and-construction/v1")
        val habitNative = input.getValue("habit_native").jsonObject
        native("habits", habitNative)
        require(nativeCell(habitNative, "id").value == CountCommandRecords.integer(input, "habit_id") &&
            nativeCell(habitNative, "uuid").value == CountCommandRecords.uuid(input, "habit_uuid"))
        input.getValue("widget_claim").takeUnless { it == JsonNull }?.let { LegacyWidgetRestartClaim.decode(CountCommandRecords.canonical(it)) }
        val metrics = input.getValue("metrics").jsonArray.map { it.jsonObject }
        require(metrics.isNotEmpty())
        for (metric in metrics) {
            CountCommandRecords.fields(metric, "metric_id", "metric_uuid", "definition_native", "link", "value_bits", "note", "reserved_fact_uuid")
            require(CountCommandRecords.integer(metric, "metric_id") > 0)
            CountCommandRecords.uuid(metric, "metric_uuid"); CountCommandRecords.uuid(metric, "reserved_fact_uuid")
            val bits = CountCommandRecords.text(metric, "value_bits"); require(bits.matches(Regex("[0-9a-f]{16}")))
            require(Double.fromBits(java.lang.Long.parseUnsignedLong(bits, 16)).isFinite()); CountCommandRecords.text(metric, "note")
            val definition = metric.getValue("definition_native").jsonObject
            native("metrics", definition)
            require(nativeCell(definition, "id").value == CountCommandRecords.integer(metric, "metric_id") &&
                nativeCell(definition, "uuid").value == CountCommandRecords.uuid(metric, "metric_uuid"))
            val link = metric.getValue("link").jsonObject
            CountCommandRecords.fields(link, "habit_id", "habit_uuid", "metric_id", "metric_uuid", "active", "prompt_on_complete")
            require(CountCommandRecords.integer(link, "habit_id") == CountCommandRecords.integer(input, "habit_id") &&
                CountCommandRecords.uuid(link, "habit_uuid") == CountCommandRecords.uuid(input, "habit_uuid") &&
                CountCommandRecords.integer(link, "metric_id") == CountCommandRecords.integer(metric, "metric_id") &&
                CountCommandRecords.uuid(link, "metric_uuid") == CountCommandRecords.uuid(metric, "metric_uuid") &&
                link.getValue("active") == JsonPrimitive(true) && link.getValue("prompt_on_complete") == JsonPrimitive(true))
        }
        require(metrics.map { CountCommandRecords.integer(it, "metric_id") }.distinct().size == metrics.size &&
            metrics.map { CountCommandRecords.uuid(it, "reserved_fact_uuid") }.distinct().size == metrics.size)
    }
    private fun nativeCell(row: JsonObject, field: String) = CountTrustRows.decodeCell(row.getValue(field))
    private fun native(table: String, row: JsonObject) {
        require(row.keys == com.dayforge.data.local.CountTrustJournal.COLUMNS.getValue(table).toSet())
        row.values.forEach { encoded -> require(CountTrustRows.encode(CountTrustRows.decodeCell(encoded)) == encoded) }
    }
    fun values(input: JsonObject): List<MetricValueDraft> = input.getValue("metrics").jsonArray.map { it.jsonObject }.map {
        MetricValueDraft(CountCommandRecords.integer(it, "metric_id"), Double.fromBits(java.lang.Long.parseUnsignedLong(CountCommandRecords.text(it, "value_bits"), 16)), CountCommandRecords.text(it, "note"))
    }
}
