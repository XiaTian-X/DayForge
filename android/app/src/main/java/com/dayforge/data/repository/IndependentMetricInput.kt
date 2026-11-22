package com.dayforge.data.repository

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.entity.MetricEntity
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.json.*

/** Values only. A legacy definition is resolved at its original T1 selection point. */
internal object IndependentMetricInput {
    const val TYPED = "typed-original-selection/v1"
    const val LEGACY = "legacy-current-row-at-t1/v1"
    const val CANCELLED = "METRIC_EXPLICIT_CANCEL_AFTER_COMPLETE_NO_EFFECTS"
    const val INVALIDATED = "METRIC_TYPED_INPUT_INVALIDATED_AFTER_COMPLETE_NO_EFFECTS"
    fun isLegacy(intent: CountCommandRecords.Intent): Boolean = intent.action == CountCommandRecords.Action.METRIC_APPEND &&
        CountCommandRecords.text(input(intent), "profile") == LEGACY
    fun input(intent: CountCommandRecords.Intent): JsonObject {
        require(intent.format == 2 && intent.action == CountCommandRecords.Action.METRIC_APPEND)
        return intent.body().getValue("input").jsonObject
    }
    suspend fun capture(database: HabitDatabase, access: LocalCoreWriteAccess, metric: MetricEntity,
        authority: ObjectEditAuthority?, value: Double, note: String, at: Long, zone: String): CountCommandRecords.Intent {
        require(value.isFinite())
        val typed = metric.appearance != null
        if (typed) require(authority != null && authority.session == access.session && authority.type == "metric" &&
            authority.uuid == metric.uuid && authority.original == NextStructureMapper.writeMetric(metric)) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
        else require(authority == null) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
        val namespace = CountCommandInputs.locator(database, access.session, access.capturedDeviceId)
        val body = buildJsonObject {
            put("format", 2); put("command_id", UUID.randomUUID().toString()); put("namespace", namespace.body())
            put("original_auth_generation", access.session.authentication.generation)
            put("action", "METRIC_APPEND"); put("capture", "EXECUTION_INPUT")
            put("input", buildJsonObject {
                put("profile", if (typed) TYPED else LEGACY)
                put("metric_id", metric.id); put("metric_uuid", metric.uuid)
                put("value_bits", value.toRawBits().toULong().toString(16).padStart(16, '0'))
                put("note", note); put("recorded_at", at); put("timezone", zone)
                put("reserved_fact_uuid", UUID.randomUUID().toString())
                put("definition", authority?.original ?: JsonNull)
                put("round_profile", typed && authority?.rounds != null)
            })
        }
        val bytes = CountCommandRecords.canonical(body).toByteArray(Charsets.UTF_8)
        return CountCommandRecords.Intent.decode(bytes, bytes.size, {})
    }
    fun validate(input: JsonObject) {
        CountCommandRecords.fields(input, "profile", "metric_id", "metric_uuid", "value_bits", "note", "recorded_at", "timezone",
            "reserved_fact_uuid", "definition", "round_profile")
        val profile = CountCommandRecords.text(input, "profile"); require(profile in setOf(TYPED, LEGACY))
        CountCommandRecords.integer(input, "metric_id")
        CountCommandRecords.text(input, "metric_uuid"); CountCommandRecords.uuid(input, "reserved_fact_uuid")
        val bits = CountCommandRecords.text(input, "value_bits")
        require(bits.length == 16 && bits.all { it in "0123456789abcdef" } && value(input).isFinite())
        CountCommandRecords.text(input, "note")
        Instant.ofEpochMilli(CountCommandRecords.integer(input, "recorded_at")).atZone(ZoneId.of(CountCommandRecords.text(input, "timezone")))
        val round = input.getValue("round_profile").jsonPrimitive
        require(!round.isString && round.booleanOrNull != null)
        if (profile == LEGACY) require(input.getValue("definition") == JsonNull && !round.boolean)
        else { CountCommandRecords.uuid(input, "metric_uuid"); require(input.getValue("definition") is JsonObject) }
    }
    fun value(input: JsonObject): Double = Double.fromBits(java.lang.Long.parseUnsignedLong(CountCommandRecords.text(input, "value_bits"), 16))
    fun requireTypedSelection(intent: CountCommandRecords.Intent, metric: MetricEntity, authority: ObjectEditAuthority) {
        val input = input(intent)
        require(!isLegacy(intent) && metric.appearance != null && metric.id == CountCommandRecords.integer(input, "metric_id") &&
            metric.uuid == CountCommandRecords.text(input, "metric_uuid") && authority.uuid == metric.uuid && authority.type == "metric" &&
            authority.original == input.getValue("definition") && NextStructureMapper.writeMetric(metric) == authority.original &&
            (authority.rounds != null) == input.getValue("round_profile").jsonPrimitive.boolean) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
    }
    fun resolution(intent: CountCommandRecords.Intent, metric: MetricEntity): JsonObject {
        val input = input(intent)
        require(metric.id == CountCommandRecords.integer(input, "metric_id") && metric.uuid == CountCommandRecords.text(input, "metric_uuid"))
        val definition = if (isLegacy(intent)) legacyDefinition(metric) else NextStructureMapper.writeMetric(metric).also {
            require(it == input.getValue("definition")) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
        }
        return buildJsonObject {
            put("profile", if (isLegacy(intent)) "legacy-v4" else if (input.getValue("round_profile").jsonPrimitive.boolean) "typed-round-v5" else "typed-plain-v5")
            put("metric_id", metric.id); put("metric_uuid", metric.uuid); put("resolved_unit", metric.unit); put("resolved_definition", definition)
        }
    }
    fun validateResolution(value: JsonObject) {
        CountCommandRecords.fields(value, "profile", "metric_id", "metric_uuid", "resolved_unit", "resolved_definition")
        require(CountCommandRecords.text(value, "profile") in setOf("legacy-v4", "typed-round-v5", "typed-plain-v5"))
        CountCommandRecords.integer(value, "metric_id"); CountCommandRecords.text(value, "metric_uuid")
        if (CountCommandRecords.text(value, "profile") != "legacy-v4") CountCommandRecords.uuid(value, "metric_uuid")
        CountCommandRecords.text(value, "resolved_unit"); require(value.getValue("resolved_definition") is JsonObject)
    }
    private fun legacyDefinition(metric: MetricEntity): JsonObject {
        require(metric.appearance == null)
        return buildJsonObject {
            put("id", metric.id); put("uuid", metric.uuid); put("name", metric.name); put("description", metric.description)
            put("unit", metric.unit); put("decimalPlaces", metric.decimalPlaces); put("aggregationType", metric.aggregationType)
            put("targetDirection", metric.targetDirection?.let(::JsonPrimitive) ?: JsonNull)
            put("targetValue", metric.targetValue?.let(::JsonPrimitive) ?: JsonNull)
            put("targetValueUpper", metric.targetValueUpper?.let(::JsonPrimitive) ?: JsonNull)
            put("iconResId", metric.iconResId); put("colorHex", metric.colorHex); put("isActive", metric.isActive)
            put("createdAt", metric.createdAt); put("updatedAt", metric.updatedAt); put("appearance", JsonNull)
        }
    }
    fun requireStatus(status: CountCommandRecords.Status, outcome: CountCommandOutcome?) {
        require(status.format == 2 && status.parent == null && status.child == null)
        when (status.execution) {
            CountCommandRecords.Execution.PREPARED -> require(status.delivery == CountCommandRecords.Delivery.PENDING_RESOLUTION && outcome == null && status.reason == null)
            CountCommandRecords.Execution.APPLIED -> require(status.delivery in setOf(CountCommandRecords.Delivery.RESULT_PENDING, CountCommandRecords.Delivery.DELIVERED) &&
                outcome?.action == CountCommandRecords.Action.METRIC_APPEND && outcome.kind == CountCommandOutcome.Kind.APPLIED && status.reason == null)
            CountCommandRecords.Execution.CANCELLED -> require(status.delivery in setOf(CountCommandRecords.Delivery.RESULT_PENDING, CountCommandRecords.Delivery.DELIVERED) && outcome == null && status.reason == CANCELLED)
            CountCommandRecords.Execution.INVALIDATED -> require(status.delivery in setOf(CountCommandRecords.Delivery.RESULT_PENDING, CountCommandRecords.Delivery.DELIVERED) && outcome == null && status.reason == INVALIDATED)
            CountCommandRecords.Execution.NO_EFFECT -> error("COMMAND_METRIC_NO_EFFECT_FORBIDDEN")
        }
    }
}
