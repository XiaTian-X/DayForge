package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.api.encodeSyncRequest
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.local.CountTrustJournal
import com.dayforge.data.local.entity.NextRequestOriginEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import java.util.Collections
import kotlinx.serialization.json.*

/** Full immutable T1 evidence, not a current projection or execution permission. The only
 * signing path accepts a private Proven from an independent write plan and complete producer
 * accumulator. This codec alone cannot make such a Proven, stage a result, or authorize ACK.
 * Retaining exact rows allows local fate to survive original legitimate outbox retirement.
 */
internal class CountCommandEvidence private constructor(val profile: Profile, val inputHash: String,
    val namespaceHash: String, records: List<Record>, private val encoded: String, val format: Int) {
    enum class Profile { FORMAL_V5, LEGACY_V4, METRIC_PLAIN_V5, METRIC_ROUND_V5, METRIC_LEGACY_V4 }
    val isTyped: Boolean get() = profile in setOf(Profile.FORMAL_V5, Profile.METRIC_PLAIN_V5, Profile.METRIC_ROUND_V5)
    enum class Role { INSERTED_FACT, DELETED_FACT, ORIGINAL_SOURCE, ORIGINAL_ORIGIN }
    class Record private constructor(val table: String, val role: Role, private val encoded: String) {
        fun body(): JsonObject = Json.parseToJsonElement(encoded).jsonObject
        fun cell(name: String): CountTrustJournal.Cell = CountTrustRows.decodeCell(body().getValue("cells").jsonObject.getValue(name))
        fun text(name: String): String = cell(name).let { require(it.type == Cursor.FIELD_TYPE_STRING); it.value as String }
        fun number(name: String): Long = cell(name).let { require(it.type == Cursor.FIELD_TYPE_INTEGER); it.value as Long }
        fun nullableText(name: String): String? = if (cell(name).type == Cursor.FIELD_TYPE_NULL) null else text(name)
        fun nullableNumber(name: String): Long? = if (cell(name).type == Cursor.FIELD_TYPE_NULL) null else number(name)
        fun real(name: String): Double = cell(name).let { require(it.type == Cursor.FIELD_TYPE_FLOAT); Double.fromBits(it.value as Long) }
        companion object {
            internal fun frozen(table: String, role: Role, fields: Map<String, Any?>): Record {
                val columns = COLUMNS.getValue(table)
                require(fields.keys == columns.toSet()) { "COMMAND_EVIDENCE_NATIVE_FIELDS" }
                val cells = buildJsonObject { columns.forEach { name ->
                    val value = fields.getValue(name)
                    val cell = when (value) {
                        null -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_NULL, null)
                        is String -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_STRING, value)
                        is Long -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_INTEGER, value)
                        is Double -> { require(value.isFinite()); CountTrustJournal.Cell(Cursor.FIELD_TYPE_FLOAT, value.toRawBits()) }
                        else -> error("COMMAND_EVIDENCE_NATIVE_VALUE")
                    }
                    put(name, CountTrustRows.encode(cell))
                } }
                return parse(buildJsonObject { put("table", table); put("role", role.name); put("cells", cells) })
            }
            internal fun parse(body: JsonObject): Record {
                CountCommandRecords.fields(body, "table", "role", "cells")
                val table = CountCommandRecords.text(body, "table")
                val role = Role.valueOf(CountCommandRecords.text(body, "role"))
                require(when (role) {
                    Role.INSERTED_FACT -> table in setOf("completions", "metric_logs")
                    Role.DELETED_FACT -> table == "completions"
                    Role.ORIGINAL_SOURCE -> table == "sync_outbox"
                    Role.ORIGINAL_ORIGIN -> table == "next_request_origins"
                })
                val columns = COLUMNS.getValue(table); val cells = body.getValue("cells").jsonObject
                require(cells.keys == columns.toSet()) { "COMMAND_EVIDENCE_NATIVE_FIELDS" }
                cells.values.forEach { value ->
                    val pair = value.jsonArray
                    require(pair.size == 2 && pair[0].jsonPrimitive.content in setOf("0", "1", "2", "3"))
                    val decoded = CountTrustRows.decodeCell(value)
                    if (decoded.type == Cursor.FIELD_TYPE_FLOAT) require(Double.fromBits(decoded.value as Long).isFinite())
                    require(CountTrustRows.encode(decoded) == value) { "COMMAND_EVIDENCE_NATIVE_ENCODING" }
                }
                return Record(table, role, CountCommandRecords.canonical(body))
            }
        }
    }
    val records: List<Record> = Collections.unmodifiableList(records.toList())
    fun body(): JsonObject = Json.parseToJsonElement(encoded).jsonObject

    /** Historical proof replay only. No current facts, current authority or pending absence
     * is inferred; all bytes here must first belong to the authenticated primary catalog row.
     */
    suspend fun requireFormalOutcome(intent: CountCommandRecords.Intent, outcome: CountCommandOutcome) {
        require(isTyped && inputHash == intent.inputHash && namespaceHash == intent.namespace.hash)
        if (intent.action == CountCommandRecords.Action.METRIC_APPEND) IndependentMetricEvidence.requireInput(intent, outcome)
        else require(format == 1 && profile == Profile.FORMAL_V5)
        if (outcome.kind == CountCommandOutcome.Kind.NO_EFFECT) { require(records.isEmpty()); return }
        val sources = records.filter { it.role == Role.ORIGINAL_SOURCE }.associate { r ->
            val source = SyncOutboxEntity(r.number("id"), r.text("operationId"), r.text("recordType"), r.text("entityUuid"),
                r.text("wireEntityUuid"), r.text("action"), r.nullableText("referenceUuid"), r.nullableText("payloadJson"),
                r.nullableNumber("baseRevision"), r.nullableText("basePayloadJson"), r.nullableNumber("attemptedAt"),
                Math.toIntExact(r.number("attemptCount")), r.nullableText("lastError"), r.nullableText("errorCode"),
                r.nullableNumber("deadLetteredAt"), r.number("createdAt"))
            require(source.id > 0 && source.createdAt % 1000L == 0L && source.attemptCount == 0 && source.attemptedAt == null &&
                source.deadLetteredAt == null && source.payloadJson == null && source.baseRevision == null && source.basePayloadJson == null &&
                source.lastError == null && source.errorCode == null && source.entityUuid == source.wireEntityUuid)
            source.id to source
        }
        val origins = records.filter { it.role == Role.ORIGINAL_ORIGIN }
        require(origins.size == sources.size && outcome.origins.size == origins.size)
        val operations = linkedMapOf<String, com.dayforge.data.api.dto.SyncV2Operation>()
        for (r in origins) {
            val origin = NextRequestOriginEntity(r.text("kind"), r.text("requestId"), r.number("queueId"), Math.toIntExact(r.number("protocol")),
                r.text("accountId"), r.nullableText("serverInstanceId"), r.nullableText("syncEpoch"), r.text("sourceHash"), r.text("intentJson"))
            val source = requireNotNull(sources[origin.queueId])
            require(origin.kind == NEXT_OPERATION && origin.protocol == 5 && origin.accountId == intent.namespace.account &&
                origin.serverInstanceId == intent.namespace.server && origin.syncEpoch == intent.namespace.epoch &&
                origin.requestId == source.operationId && origin.sourceHash == NextRequestSql.sourceHash(source))
            val operation = decodeNextOperationIntent(origin.intentJson)
            if (intent.action == CountCommandRecords.Action.METRIC_APPEND) {
                val round = roundOperationIntent(origin.intentJson)
                if (profile == Profile.METRIC_ROUND_V5) require(round == NextRoundOperationIntent(1, operation,
                    com.dayforge.data.api.dto.ChallengeSourceContext(operation.operationId, null), requireNotNull(intent.namespace.device)))
                else require(round == null && CountCommandRecords.canonical(Json.parseToJsonElement(origin.intentJson)) ==
                    CountCommandRecords.canonical(Json.parseToJsonElement(
                        encodeSyncRequest(SyncV2Operation.serializer(), operation).toString(Charsets.UTF_8))))
            }
            com.dayforge.data.api.dto.validateNextSyncOperation(operation)
            require(operation.operationId == origin.requestId && operations.put(operation.entityUuid, operation) == null)
            val summary = outcome.origins.single { it.requestId == origin.requestId }
            require(summary.operationId == operation.operationId && summary.entityType == operation.entityType && summary.entityUuid == operation.entityUuid &&
                summary.operationType == operation.action && summary.immutableOriginHash == NextRequestSql.immutableOriginHash(origin))
        }
        for (effect in outcome.effects) {
            val operation = requireNotNull(operations[effect.factUuid])
            require(operation.action == "upsert")
            if (effect.factKind == CountCommandOutcome.FactKind.COUNT_REVERT) {
                val removed = records.single { it.role == Role.DELETED_FACT }
                require(removed.text("uuid") == effect.revertsFactUuid && removed.nullableText("habitUuid") == effect.objectUuid &&
                    operation.entityType == "activity_event" && operation.payload["activity_uuid"] == JsonPrimitive(effect.objectUuid) &&
                    operation.payload["event_type"] == JsonPrimitive("revert") && operation.payload["reverts_event_uuid"] == JsonPrimitive(effect.revertsFactUuid))
            } else {
                val fact = records.single { it.role == Role.INSERTED_FACT && it.text("uuid") == effect.factUuid }
                if (effect.factKind == CountCommandOutcome.FactKind.METRIC_OBSERVATION) {
                    require(fact.table == "metric_logs" && operation.entityType == "metric_observation")
                    val log = com.dayforge.data.local.entity.MetricLogEntity(id = fact.number("id"), metricId = fact.number("metricId"),
                        date = fact.number("date"), value = fact.real("value"), unit = fact.text("unit"), note = fact.text("note"), uuid = fact.text("uuid"),
                        createdAt = fact.number("createdAt"), updatedAt = fact.number("updatedAt"), recordedTimezone = fact.text("recordedTimezone"),
                        recordedLocalDate = fact.text("recordedLocalDate"), timeMetadataSource = fact.text("timeMetadataSource"))
                    require(operation.payload == SyncV2Mapper.metricObservation(log, effect.objectUuid))
                } else {
                    require(fact.table == "completions" && operation.entityType == "activity_event" && fact.nullableText("habitUuid") == effect.objectUuid &&
                        operation.payload["activity_uuid"] == JsonPrimitive(effect.objectUuid) && operation.payload["value"] == JsonPrimitive(fact.number("value")) &&
                        operation.payload["occurred_at"] == JsonPrimitive(java.time.Instant.ofEpochMilli(fact.nullableNumber("actualCompletedAt") ?: fact.number("date")).toString()) &&
                        operation.payload["local_date"] == JsonPrimitive(fact.text("recordedLocalDate")) && operation.payload["timezone"] == JsonPrimitive(fact.text("recordedTimezone")))
                }
            }
        }
        val extras = operations.values.filter { operation -> outcome.effects.none { it.factUuid == operation.entityUuid } }
        require(extras.all { intent.action == CountCommandRecords.Action.COUNT_ADD && it.entityType == "plan_node" &&
            it.entityUuid == CountCommandRecords.uuid(intent.body().getValue("input").jsonObject, "habit_uuid") })
        require(records.count { it.role in setOf(Role.INSERTED_FACT, Role.DELETED_FACT) } == outcome.effects.size)
    }

    companion object {
        // Frozen v1 receipt schema. Future Room evolution must add an explicit decoder/version,
        // not silently reinterpret historical receipts through a changing live table manifest.
        private val COLUMNS = mapOf(
            "completions" to listOf("id", "habitId", "date", "value", "actualCompletedAt", "uuid", "habitUuid", "createdAt", "recordedTimezone", "recordedLocalDate", "timeMetadataSource", "oneTimeAction", "oneTimeExpectedVersion", "oneTimeExpectedHeadEventUuid", "oneTimeRevertsEventUuid"),
            "metric_logs" to listOf("id", "metricId", "date", "value", "unit", "note", "uuid", "createdAt", "updatedAt", "recordedTimezone", "recordedLocalDate", "timeMetadataSource"),
            "sync_outbox" to listOf("id", "operationId", "recordType", "entityUuid", "wireEntityUuid", "action", "referenceUuid", "payloadJson", "baseRevision", "basePayloadJson", "attemptedAt", "attemptCount", "lastError", "errorCode", "deadLetteredAt", "createdAt"),
            "next_request_origins" to listOf("kind", "requestId", "queueId", "protocol", "accountId", "serverInstanceId", "syncEpoch", "sourceHash", "intentJson")
        )
        internal fun frozen(profile: Profile, intent: CountCommandRecords.Intent, records: List<Record>): CountCommandEvidence = decode(buildJsonObject {
            put("format", 1); put("profile", profile.name); put("input_hash", intent.inputHash); put("namespace_hash", intent.namespace.hash)
            put("records", JsonArray(records.map { it.body() }))
        }, intent.inputHash, intent.namespace.hash)

        internal fun independent(intent: CountCommandRecords.Intent, resolution: JsonObject, records: List<Record>): CountCommandEvidence = decode(buildJsonObject {
            put("format", 2); put("profile", CountCommandRecords.text(resolution, "profile")); put("input_hash", intent.inputHash)
            put("namespace_hash", intent.namespace.hash); put("records", JsonArray(records.map { it.body() }))
        }, intent.inputHash, intent.namespace.hash)

        fun decode(body: JsonObject, inputHash: String, namespaceHash: String): CountCommandEvidence {
            CountCommandRecords.fields(body, "format", "profile", "input_hash", "namespace_hash", "records")
            val format = CountCommandRecords.integer(body, "format").let(Math::toIntExact)
            require(format in 1..2 && CountCommandRecords.digest(body, "input_hash") == inputHash &&
                CountCommandRecords.digest(body, "namespace_hash") == namespaceHash)
            val profile = if (format == 1) Profile.valueOf(CountCommandRecords.text(body, "profile")).also {
                require(it in setOf(Profile.FORMAL_V5, Profile.LEGACY_V4))
            } else when (CountCommandRecords.text(body, "profile")) {
                "typed-plain-v5" -> Profile.METRIC_PLAIN_V5
                "typed-round-v5" -> Profile.METRIC_ROUND_V5
                "legacy-v4" -> Profile.METRIC_LEGACY_V4
                else -> error("COMMAND_METRIC_EVIDENCE_PROFILE")
            }
            val records = body.getValue("records").jsonArray.map { Record.parse(it.jsonObject) }
            if (profile in setOf(Profile.LEGACY_V4, Profile.METRIC_LEGACY_V4)) require(records.none { it.role == Role.ORIGINAL_ORIGIN })
            val identities = records.map { record ->
                val fields = record.body().getValue("cells").jsonObject
                val keys = if (record.table == "next_request_origins") listOf("kind", "requestId") else listOf("id")
                Triple(record.table, record.role, keys.map { fields.getValue(it) })
            }
            require(identities.distinct().size == identities.size) { "COMMAND_EVIDENCE_DUPLICATE_ROW" }
            return CountCommandEvidence(profile, inputHash, namespaceHash, records, CountCommandRecords.canonical(body), format)
        }
        internal fun source(row: SyncOutboxEntity): Record = Record.frozen("sync_outbox", Role.ORIGINAL_SOURCE, linkedMapOf(
            "id" to row.id, "operationId" to row.operationId, "recordType" to row.recordType, "entityUuid" to row.entityUuid,
            "wireEntityUuid" to row.wireEntityUuid, "action" to row.action, "referenceUuid" to row.referenceUuid,
            "payloadJson" to row.payloadJson, "baseRevision" to row.baseRevision, "basePayloadJson" to row.basePayloadJson,
            "attemptedAt" to row.attemptedAt, "attemptCount" to row.attemptCount.toLong(), "lastError" to row.lastError,
            "errorCode" to row.errorCode, "deadLetteredAt" to row.deadLetteredAt, "createdAt" to row.createdAt))
        internal fun origin(row: NextRequestOriginEntity): Record = Record.frozen("next_request_origins", Role.ORIGINAL_ORIGIN, linkedMapOf(
            "kind" to row.kind, "requestId" to row.requestId, "queueId" to row.queueId, "protocol" to row.protocol.toLong(),
            "accountId" to row.accountId, "serverInstanceId" to row.serverInstanceId, "syncEpoch" to row.syncEpoch,
            "sourceHash" to row.sourceHash, "intentJson" to row.intentJson))
    }
}
