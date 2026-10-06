package com.dayforge.data.repository

import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.domain.model.OneTimeEventProof
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.model.contractLongOrNull
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.*

/** Full immutable event validation, in addition to the shared six-field state proof. */
internal class OneTimeServerFact(val payload: JsonObject, val revision: Long) {
    val proof: OneTimeEventProof
    val occurredAt: Instant
    val timezone: String
    val localDate: String
    val createdAt: Instant
    val sourceDeviceId: String

    init {
        require(revision > 0 && contractLongOrNull(payload["revision"]) == revision)
        require(payload.containsKey("deleted_at") && payload["deleted_at"] == JsonNull)
        val keys = listOf("public_id", "activity_uuid", "event_type", "reverts_event_uuid", "one_time", "one_time_state_after")
        proof = Json.decodeFromJsonElement<OneTimeEventProof>(JsonObject(keys.associateWith { payload.getValue(it) }))
        occurredAt = instant(payload, "occurred_at")
        createdAt = instant(payload, "created_at")
        instant(payload, "updated_at")
        instant(payload, "received_at")
        timezone = string(payload, "timezone")
        require(timezone in ZoneId.getAvailableZoneIds())
        val captured = occurredAt.atZone(ZoneId.of(timezone))
        localDate = string(payload, "local_date")
        require(captured.year in 1..9999 && captured.toLocalDate().toString() == localDate)
        require(listOf("duration_seconds", "duration_milliseconds", "started_at", "ended_at").all { payload[it] == JsonNull })
        require("day_allocations" !in payload)
        val value = payload.getValue("value")
        if (proof.oneTime.action == "complete") {
            require(value is JsonPrimitive && value.content.toBigDecimalOrNull()?.compareTo(java.math.BigDecimal.ONE) == 0)
        } else require(value == JsonNull)
        require(string(payload, "note").let { it.codePointCount(0, it.length) <= 1000 })
        val source = string(payload, "source_type")
        require(source in setOf("app", "widget", "api", "smart_device", "automation", "import"))
        sourceDeviceId = string(payload, "source_device_id")
        require(isContractUuid(sourceDeviceId))
        require("external_event_id" in payload)
        val external = nullableString(payload, "external_event_id")
        require(external == null || external.codePointCount(0, external.length) <= 200)
        if (source in setOf("smart_device", "automation")) require(!external.isNullOrEmpty())
        require(payload.getValue("metadata") is JsonObject)
    }

    /** Local once operations currently contain only app check/revert, with no auxiliary value. */
    fun requireOriginal(operation: SyncV2Operation, deviceId: String) {
        val original = operation.payload
        require(operation.entityType == "activity_event" && operation.action == "upsert" && operation.baseRevision == null)
        require(operation.entityUuid == proof.publicId && string(original, "activity_uuid") == proof.activityUuid)
        require(original.getValue("one_time") == payload.getValue("one_time"))
        require(string(original, "event_type") == proof.eventType && nullableString(original, "reverts_event_uuid") == proof.revertsEventUuid)
        require(instant(original, "occurred_at") == occurredAt && string(original, "timezone") == timezone &&
            string(original, "local_date") == localDate)
        require(string(original, "source_type") == "app" && string(payload, "source_type") == "app" && sourceDeviceId == deviceId)
        require(string(payload, "note").isEmpty() && payload["metadata"] == JsonObject(emptyMap()) &&
            payload["external_event_id"] == JsonNull)
        val allowed = setOf("activity_uuid", "event_type", "occurred_at", "local_date", "timezone", "source_type", "reverts_event_uuid", "one_time")
        require(original.keys.all { it in allowed })
    }

    companion object {
        private fun string(value: JsonObject, key: String): String {
            val item = value.getValue(key)
            require(item is JsonPrimitive && item.isString)
            return item.content
        }

        private fun nullableString(value: JsonObject, key: String): String? =
            if (value[key] == null || value[key] == JsonNull) null else string(value, key)

        private fun instant(value: JsonObject, key: String): Instant {
            val parsed = Instant.parse(string(value, key))
            require(parsed.atZone(ZoneId.of("UTC")).year in 1..9999)
            return parsed
        }
    }
}
