package com.dayforge.data.repository

import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.model.contractLongOrNull
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.*

internal data class NextDurationProjection(val session: TimeLogEntity, val allocations: List<TimeLogDayAllocationEntity>)

/**
 * Lossless representable projections of accepted common facts. The repository must retain the
 * complete immutable payload/shadow in the same transaction (including source/note/metadata).
 * This mapper neither accepts a timer command nor creates a completed timer from user input.
 */
internal object NextCommonFactMapper {
    private val header = setOf("public_id", "revision", "created_at", "updated_at", "deleted_at")
    private val source = setOf("occurred_at", "local_date", "timezone", "note", "source_type", "source_device_id",
        "external_event_id", "metadata", "received_at")
    private val eventFields = setOf("activity_uuid", "event_type", "value", "duration_seconds", "duration_milliseconds",
        "started_at", "ended_at", "reverts_event_uuid") + source
    private val observationFields = setOf("metric_uuid", "value", "unit") + source
    private val linkFields = setOf("activity_uuid", "metric_uuid", "coefficient", "show_in_activity_detail", "prompt_on_complete", "is_active")

    /** Structural link validation without inventing local endpoint IDs. */
    fun validateLinkWrite(body: JsonObject) {
        require(body.keys == linkFields)
        require(isContractUuid(body.text("activity_uuid")) && isContractUuid(body.text("metric_uuid")))
        body.decimal("coefficient").exactDouble()
        body.boolean("show_in_activity_detail"); body.boolean("prompt_on_complete"); body.boolean("is_active")
    }

    fun validateLinkSnapshot(change: SyncV2Change) {
        val body = header(change, "activity_metric_link", linkFields)
        validateLinkWrite(JsonObject(body.filterKeys { it in linkFields }))
    }

    fun completion(change: SyncV2Change, habit: HabitEntity, previous: CompletionEntity? = null): CompletionEntity {
        val body = event(change, habit)
        val kind = body.text("event_type")
        require(kind in setOf("check_in", "count_delta", "count_snapshot"))
        ancillaryDuration(body)
        require(body.getValue("reverts_event_uuid") == JsonNull)
        val value = if (kind == "check_in") {
            require(body.getValue("value") == JsonNull || body.decimal("value").compareTo(BigDecimal.ONE) == 0)
            1
        } else body.decimal("value").intValueExact()
        val occurred = body.instant("occurred_at")
        val (zone, day) = source(body, occurred)
        if (previous != null) require(previous.uuid == change.entityUuid && previous.habitId == habit.id &&
            previous.oneTimeAction == null && previous.oneTimeExpectedVersion == null &&
            previous.oneTimeExpectedHeadEventUuid == null && previous.oneTimeRevertsEventUuid == null)
        return CompletionEntity(id = previous?.id ?: 0, habitId = habit.id, habitUuid = habit.uuid,
            uuid = change.entityUuid, date = day.atStartOfDay(zone).toInstant().toEpochMilli(), value = value,
            actualCompletedAt = occurred.toEpochMilli(), createdAt = body.instant("created_at").toEpochMilli(),
            recordedTimezone = zone.id, recordedLocalDate = day.toString(), timeMetadataSource = previous?.takeIf {
                (it.actualCompletedAt ?: it.date) == occurred.toEpochMilli() && it.recordedTimezone == zone.id && it.recordedLocalDate == day.toString()
            }?.timeMetadataSource ?: "server")
    }

    fun revert(change: SyncV2Change, habit: HabitEntity): String {
        val body = event(change, habit)
        require(body.text("event_type") == "revert")
        if (body.getValue("value") != JsonNull) body.decimal("value")
        ancillaryDuration(body)
        source(body, body.instant("occurred_at"))
        return body.text("reverts_event_uuid").also { require(isContractUuid(it) && it != change.entityUuid) }
    }

    fun duration(change: SyncV2Change, habit: HabitEntity, previous: TimeLogEntity? = null): NextDurationProjection {
        val body = event(change, habit, duration = true)
        require(body.text("event_type") == "duration_session" && body.getValue("value") == JsonNull &&
            body.getValue("reverts_event_uuid") == JsonNull)
        val start = body.instant("started_at")
        val end = body.instant("ended_at")
        require(end >= start && body.instant("occurred_at") == end)
        val seconds = body.integer("duration_seconds")
        val millis = body.integer("duration_milliseconds")
        require(seconds in 0..86_400 && millis in 0..86_400_000 && millis / 1000 == seconds)
        val (zone, day) = source(body, start)
        if (previous != null) require(previous.uuid == change.entityUuid && previous.habitId == habit.id)
        val session = TimeLogEntity(id = previous?.id ?: 0, habitId = habit.id, uuid = change.entityUuid,
            startTime = start.toEpochMilli(), endTime = end.toEpochMilli(), durationSeconds = seconds.toInt(),
            // This snapshot has no pause segments; do not infer pause time from stop latency.
            accumulatedPauseMillis = previous?.accumulatedPauseMillis ?: 0,
            timerNextCommandSequence = previous?.timerNextCommandSequence ?: 1,
            timerControlGeneration = previous?.timerControlGeneration ?: 0,
            timerLastCommandAt = previous?.timerLastCommandAt, timerTimezone = zone.id,
            timerActiveElapsedMillis = millis, date = day.atStartOfDay(zone).toInstant().toEpochMilli(),
            createdAt = body.instant("created_at").toEpochMilli(), updatedAt = body.instant("updated_at").toEpochMilli())
        val allocations = body.getValue("day_allocations").jsonArray.map { value ->
            val item = value.jsonObject
            require(item.keys == setOf("local_date", "timezone", "duration_milliseconds") && item.text("timezone") == zone.id)
            val localDate = date(item.text("local_date"))
            val from = localDate.atStartOfDay(zone).toInstant()
            val until = localDate.plusDays(1).atStartOfDay(zone).toInstant()
            val overlapStart = maxOf(start, from)
            // Stop's wall instant is not a monotonic duration bound. The server may correct
            // a closed segment to start + measured elapsed, beyond the command instant.
            // Without segment snapshots we can only check a conservative outer bound:
            // every segment starts no later than stop and lasts no longer than total active.
            val overlapEnd = minOf(end.plusMillis(millis), until)
            val allocated = item.integer("duration_milliseconds")
            require(allocated in 1..86_400_000 && overlapEnd > overlapStart)
            // Cumulative per-segment flooring can carry a fractional millisecond over midnight.
            // Bound by the ceiling of this day's wall interval, while the exact total below
            // still forbids manufacturing elapsed time. Never round the stored UTC instants.
            val overlap = Duration.between(overlapStart, overlapEnd)
            val wallMillis = overlap.toMillis()
            val capacity = wallMillis + if (overlap.minusMillis(wallMillis).isZero) 0 else 1
            require(allocated <= capacity)
            TimeLogDayAllocationEntity(change.entityUuid, habit.id, localDate.toString(), from.toEpochMilli(), zone.id, allocated)
        }
        require(allocations.map { it.localDate }.distinct().size == allocations.size && allocations.sumOf { it.durationMillis } == millis)
        return NextDurationProjection(session, allocations.sortedBy { it.localDate })
    }

    fun observation(change: SyncV2Change, metric: MetricEntity, previous: MetricLogEntity? = null): MetricLogEntity {
        val body = header(change, "metric_observation", observationFields)
        require(body.text("metric_uuid") == metric.uuid && isContractUuid(metric.uuid) && metric.id > 0)
        val occurred = body.instant("occurred_at")
        val (zone, day) = source(body, occurred)
        val unit = body.text("unit").also { require(it.codePointCount(0, it.length) in 1..50) }
        if (previous != null) require(previous.uuid == change.entityUuid && previous.metricId == metric.id)
        return MetricLogEntity(id = previous?.id ?: 0, metricId = metric.id, uuid = change.entityUuid,
            date = occurred.toEpochMilli(), value = body.decimal("value").exactDouble(), unit = unit,
            note = body.text("note"), createdAt = body.instant("created_at").toEpochMilli(), updatedAt = body.instant("updated_at").toEpochMilli(),
            recordedTimezone = zone.id, recordedLocalDate = day.toString(), timeMetadataSource = previous?.takeIf {
                it.date == occurred.toEpochMilli() && it.recordedTimezone == zone.id && it.recordedLocalDate == day.toString()
            }?.timeMetadataSource ?: "server")
    }

    fun link(change: SyncV2Change, habit: HabitEntity, metric: MetricEntity, previous: HabitMetricLinkEntity? = null): HabitMetricLinkEntity {
        val body = header(change, "activity_metric_link", linkFields)
        require(habit.id > 0 && metric.id > 0 && habit.habitType != HabitType.GOAL && isContractUuid(habit.uuid) && isContractUuid(metric.uuid))
        require(body.text("activity_uuid") == habit.uuid && body.text("metric_uuid") == metric.uuid)
        if (previous != null) require(previous.uuid == change.entityUuid && previous.habitId == habit.id && previous.metricId == metric.id)
        return HabitMetricLinkEntity(id = previous?.id ?: 0, habitId = habit.id, habitUuid = habit.uuid,
            metricId = metric.id, metricUuid = metric.uuid, uuid = change.entityUuid, coefficient = body.decimal("coefficient").exactDouble(),
            showInHabitDetail = body.boolean("show_in_activity_detail"), promptOnComplete = body.boolean("prompt_on_complete"),
            isActive = body.boolean("is_active"), createdAt = body.instant("created_at").toEpochMilli(), updatedAt = body.instant("updated_at").toEpochMilli())
    }

    private fun event(change: SyncV2Change, habit: HabitEntity, duration: Boolean = false): JsonObject {
        require(habit.id > 0 && isContractUuid(habit.uuid) && habit.habitType != HabitType.GOAL &&
            habit.completionPolicy == "recurring" && habit.schedule !is HabitSchedule.Once)
        val optionalOnce = change.payload.keys.intersect(setOf("one_time", "one_time_state_after"))
        require(optionalOnce.all { change.payload[it] == JsonNull })
        val body = header(change, "activity_event", eventFields + optionalOnce + if (duration) setOf("day_allocations") else emptySet())
        require(body.text("activity_uuid") == habit.uuid)
        // Historical tracking mode may differ from today's editable habit type; do not discard old facts.
        return body
    }

    private fun header(change: SyncV2Change, type: String, fields: Set<String>): JsonObject {
        require(change.entityType == type && change.operation == "upsert" && change.sequence >= 0 && change.revision > 0 && isContractUuid(change.entityUuid))
        val body = change.payload
        require(body.keys == header + fields && body.text("public_id") == change.entityUuid &&
            body.integer("revision") == change.revision && body.getValue("deleted_at") == JsonNull)
        body.instant("created_at"); body.instant("updated_at")
        return body
    }
    private fun source(body: JsonObject, basis: Instant): Pair<ZoneId, LocalDate> {
        body.instant("received_at"); body.instant("occurred_at")
        val zone = body.text("timezone").also { require(it.length <= 64 && it in ZoneId.getAvailableZoneIds()) }.let(ZoneId::of)
        val day = date(body.text("local_date"))
        require(basis.atZone(zone).toLocalDate() == day)
        require(body.text("note").let { it.codePointCount(0, it.length) <= 1000 })
        val source = body.text("source_type")
        require(source in setOf("app", "widget", "api", "smart_device", "automation", "import"))
        require(isContractUuid(body.text("source_device_id")))
        val external = body.getValue("external_event_id").let { if (it == JsonNull) null else body.text("external_event_id") }
        require(external == null || external.codePointCount(0, external.length) <= 200)
        if (source in setOf("smart_device", "automation")) require(!external.isNullOrEmpty())
        require(body.getValue("metadata") is JsonObject)
        return zone to day
    }
    // The server permits these ancillary fields on ordinary facts. Preserve them in the
    // immutable shadow; they neither turn a check into a timer nor change its business date.
    private fun ancillaryDuration(body: JsonObject) {
        if (body.getValue("duration_seconds") != JsonNull) require(body.integer("duration_seconds") in 0..Int.MAX_VALUE.toLong())
        if (body.getValue("duration_milliseconds") != JsonNull) require(body.integer("duration_milliseconds") in 0..86_400_000)
        for (key in listOf("started_at", "ended_at")) if (body.getValue(key) != JsonNull) body.instant(key)
    }
    private fun JsonObject.text(key: String): String = getValue(key).let { require(it is JsonPrimitive && it.isString); it.content }
    private fun JsonObject.integer(key: String): Long = requireNotNull(contractLongOrNull(getValue(key)))
    private fun JsonObject.boolean(key: String): Boolean = getValue(key).let { require(it is JsonPrimitive && !it.isString); requireNotNull(it.booleanOrNull) }
    private fun JsonObject.decimal(key: String): BigDecimal = getValue(key).let { require(it is JsonPrimitive); requireNotNull(it.content.toBigDecimalOrNull()) }
    private fun BigDecimal.exactDouble(): Double = toDouble().also { require(it.isFinite() && BigDecimal.valueOf(it).compareTo(this) == 0) }
    private fun JsonObject.instant(key: String): Instant = Instant.parse(text(key)).also { require(it.atZone(ZoneId.of("UTC")).year in 1..9999) }
    private fun date(value: String): LocalDate = LocalDate.parse(value).also { require(it.year in 1..9999 && it.toString() == value) }
}
