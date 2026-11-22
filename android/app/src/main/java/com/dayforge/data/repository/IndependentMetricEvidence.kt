package com.dayforge.data.repository

import android.database.Cursor
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.*

/** Replays an authenticated immutable single-value T1. Never issues execution authority. */
internal object IndependentMetricEvidence {
    fun requireInput(intent: CountCommandRecords.Intent, result: CountCommandOutcome) {
        val input = IndependentMetricInput.input(intent)
        require(result.format == 2 && result.commandId == intent.commandId && result.inputHash == intent.inputHash &&
            result.namespaceHash == intent.namespace.hash && result.action == CountCommandRecords.Action.METRIC_APPEND &&
            result.kind == CountCommandOutcome.Kind.APPLIED && result.followUp == CountCommandOutcome.FollowUp.NONE &&
            result.reason == null && result.effects.size == 1)
        val resolution = requireNotNull(result.metricResolution)
        IndependentMetricInput.validateResolution(resolution)
        val metric = CountCommandRecords.text(input, "metric_uuid")
        val id = CountCommandRecords.integer(input, "metric_id")
        val uuid = CountCommandRecords.uuid(input, "reserved_fact_uuid")
        val legacy = IndependentMetricInput.isLegacy(intent)
        val profile = if (legacy) "legacy-v4" else if (input.getValue("round_profile").jsonPrimitive.boolean) "typed-round-v5" else "typed-plain-v5"
        require(CountCommandRecords.text(resolution, "profile") == profile &&
            CountCommandRecords.text(resolution, "metric_uuid") == metric &&
            CountCommandRecords.integer(resolution, "metric_id") == id)
        val definition = resolution.getValue("resolved_definition").jsonObject
        if (!legacy) require(definition == input.getValue("definition"))
        else {
            CountCommandRecords.fields(definition, "id", "uuid", "name", "description", "unit", "decimalPlaces", "aggregationType",
                "targetDirection", "targetValue", "targetValueUpper", "iconResId", "colorHex", "isActive", "createdAt", "updatedAt", "appearance")
            require(CountCommandRecords.integer(definition, "id") == id && CountCommandRecords.text(definition, "uuid") == metric &&
                definition.getValue("appearance") == JsonNull)
        }
        require(CountCommandRecords.text(resolution, "resolved_unit") == CountCommandRecords.text(definition, "unit"))
        val effect = result.effects.single()
        require(effect.factKind == CountCommandOutcome.FactKind.METRIC_OBSERVATION && effect.factUuid == uuid &&
            effect.objectUuid == metric && effect.revertsFactUuid == null && result.primaryFactUuid == uuid)
        val evidence = result.evidence
        require(evidence.format == 2 && evidence.inputHash == intent.inputHash && evidence.namespaceHash == intent.namespace.hash &&
            evidence.body().getValue("profile") == JsonPrimitive(profile) && evidence.records.size == (if (legacy) 2 else 3))
        val fact = evidence.records.single { it.role == CountCommandEvidence.Role.INSERTED_FACT }
        require(fact.table == "metric_logs" && fact.number("id") > 0 && fact.number("metricId") == id && fact.text("uuid") == uuid &&
            fact.number("date") == CountCommandRecords.integer(input, "recorded_at") &&
            fact.text("note") == CountCommandRecords.text(input, "note") &&
            fact.text("unit") == CountCommandRecords.text(resolution, "resolved_unit") &&
            fact.text("recordedTimezone") == CountCommandRecords.text(input, "timezone") &&
            fact.text("timeMetadataSource") == "captured")
        val requested = IndependentMetricInput.value(input)
        require(fact.real("value").toRawBits() == requested.toRawBits() || requested.toRawBits() == Long.MIN_VALUE && fact.real("value") == 0.0)
        require(fact.text("recordedLocalDate") == Instant.ofEpochMilli(fact.number("date")).atZone(ZoneId.of(fact.text("recordedTimezone"))).toLocalDate().toString())
        val source = evidence.records.single { it.role == CountCommandEvidence.Role.ORIGINAL_SOURCE }
        require(source.table == "sync_outbox" && source.number("id") > 0 && source.text("recordType") == "metric_log" &&
            source.text("entityUuid") == uuid && source.text("wireEntityUuid") == uuid &&
            source.text("referenceUuid") == metric && source.text("action") == "upsert" &&
            com.dayforge.domain.model.isContractUuid(source.text("operationId")) &&
            source.number("createdAt") % 1000L == 0L && source.number("attemptCount") == 0L &&
            source.text("operationId") == result.primaryRequestId)
        for (column in listOf("payloadJson", "baseRevision", "basePayloadJson", "attemptedAt", "lastError", "errorCode", "deadLetteredAt"))
            require(source.cell(column).type == Cursor.FIELD_TYPE_NULL)
        if (legacy) require(result.origins.isEmpty() && evidence.records.none { it.role == CountCommandEvidence.Role.ORIGINAL_ORIGIN })
        else require(result.origins.size == 1 && evidence.records.count { it.role == CountCommandEvidence.Role.ORIGINAL_ORIGIN } == 1)
    }
    fun requireLegacy(intent: CountCommandRecords.Intent, result: CountCommandOutcome) {
        require(IndependentMetricInput.isLegacy(intent)); requireInput(intent, result)
        // Original v4 may retire/fold/retry its queue with another transport ID. Immutable
        // local T1 evidence proves the historical save; no mutable current row grants replay.
    }
    fun saved(value: CountCommandStore.DurableCommit, deliveryAllowed: Boolean = true): IndependentMetricSaved {
        val result = value.outcome
        require(result.action == CountCommandRecords.Action.METRIC_APPEND && result.format == 2)
        val fact = result.evidence.records.single { it.role == CountCommandEvidence.Role.INSERTED_FACT }
        return IndependentMetricSaved(fact.number("id"), fact.text("uuid"), result.effects.single().objectUuid,
            fact.real("value"), fact.text("unit"), value, deliveryAllowed)
    }
}
