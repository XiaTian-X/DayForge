package com.dayforge.data.repository

import com.dayforge.data.local.entity.CountCommandResultEntity
import com.dayforge.domain.model.isContractUuid
import java.util.Collections
import kotlinx.serialization.json.*

/** Immutable historical execution result. It contains no current count, goalReached,
 * reusable ObjectEditAuthority or permission to publish a metric form. Current presentation
 * must be re-established separately. Parsing is not evidence that these effects happened.
 */
internal class CountCommandOutcome private constructor(
    val commandId: String,
    val inputHash: String,
    val namespaceHash: String,
    val action: CountCommandRecords.Action,
    val kind: Kind,
    val primaryFactUuid: String?,
    val primaryRequestId: String?,
    val reason: CountCommandRecords.NoEffectReason?,
    val followUp: FollowUp,
    val evidence: CountCommandEvidence,
    effects: List<Effect>,
    origins: List<Origin>,
    private val encoded: String, val format: Int, internal val metricResolution: JsonObject?
) {
    enum class Kind { APPLIED, NO_EFFECT }
    enum class FollowUp { NONE, RECHECK_ORIGINAL_RULES }
    enum class FactKind { COUNT_ADD, COUNT_REVERT, LOCAL_COUNT_DELETE, METRIC_OBSERVATION }
    data class Effect(val factKind: FactKind, val factUuid: String, val objectUuid: String,
        val revertsFactUuid: String?, internal val legacyNativeMetricIdentity: Boolean = false) {
        init {
            require(isContractUuid(factUuid))
            require(if (legacyNativeMetricIdentity) factKind == FactKind.METRIC_OBSERVATION else isContractUuid(objectUuid))
            require((factKind == FactKind.COUNT_REVERT) == (revertsFactUuid != null))
            require(revertsFactUuid == null || isContractUuid(revertsFactUuid) && revertsFactUuid != factUuid)
        }
    }
    data class Origin(val requestId: String, val operationId: String, val entityType: String,
        val entityUuid: String, val operationType: String, val immutableOriginHash: String) {
        init {
            require(isContractUuid(requestId) && isContractUuid(operationId) && isContractUuid(entityUuid))
            require(entityType.isNotBlank() && operationType.isNotBlank())
            require(validHash(immutableOriginHash))
        }
    }

    val effects: List<Effect> = Collections.unmodifiableList(effects.toList())
    val origins: List<Origin> = Collections.unmodifiableList(origins.toList())
    fun text(): String = encoded

    /** Original six-column Outcome schema; empty factUuid is legal ONLY for explicit NO_EFFECT.
     * state is immutable execution result. Mutable delivery is in CountCommandStatus instead.
     */
    fun row(): CountCommandResultEntity = CountCommandResultEntity(commandId, inputHash,
        primaryFactUuid.orEmpty(), primaryRequestId, encoded, kind.name)

    fun requireIntent(intent: CountCommandRecords.Intent) {
        require(format == intent.format && commandId == intent.commandId && inputHash == intent.inputHash &&
            namespaceHash == intent.namespace.hash && action == intent.action) { "COMMAND_OUTCOME_INTENT_MISMATCH" }
        if (action == CountCommandRecords.Action.METRIC_APPEND) {
            IndependentMetricEvidence.requireInput(intent, this)
            return
        }
        if (intent.capture == CountCommandRecords.Capture.VERIFIED_NO_EFFECT) {
            require(kind == Kind.NO_EFFECT && reason?.name ==
                CountCommandRecords.text(intent.body().getValue("input").jsonObject, "reason"))
        }
        require((evidence.profile == CountCommandEvidence.Profile.LEGACY_V4) == CountCommandLegacyInput.isLegacy(intent)) {
            "COMMAND_OUTCOME_PROFILE_CHANGED"
        }
        if (kind == Kind.APPLIED) {
            val input = intent.body().getValue("input").jsonObject
            when (action) {
                CountCommandRecords.Action.METRIC_APPEND -> error("COMMAND_METRIC_V2_REQUIRED")
                CountCommandRecords.Action.COUNT_ADD -> require(primaryFactUuid == CountCommandRecords.uuid(input, "reserved_fact_uuid") &&
                    effects.single().objectUuid == CountCommandRecords.uuid(input, "habit_uuid"))
                CountCommandRecords.Action.COUNT_UNDO -> if (CountCommandLegacyInput.isLegacy(intent)) {
                    require(evidence.profile == CountCommandEvidence.Profile.LEGACY_V4 &&
                        effects.single().factKind == FactKind.LOCAL_COUNT_DELETE &&
                        primaryFactUuid == CountCommandRecords.uuid(input, "target_fact_uuid") &&
                        effects.single().objectUuid == CountCommandRecords.uuid(input, "habit_uuid"))
                } else require(primaryFactUuid == CountCommandRecords.uuid(input, "reserved_revert_uuid") &&
                    effects.single().objectUuid == CountCommandRecords.uuid(input, "habit_uuid") &&
                    effects.single().revertsFactUuid == CountCommandRecords.uuid(input, "target_fact_uuid"))
                CountCommandRecords.Action.LINKED_METRICS -> {
                    val reserved = input.getValue("metrics").jsonArray.map { it.jsonObject }.map {
                        CountCommandRecords.uuid(it, "reserved_fact_uuid") to CountCommandRecords.uuid(it, "metric_uuid") }.toSet()
                    require(effects.map { it.factUuid to it.objectUuid }.toSet() == reserved && effects.size == reserved.size)
                }
            }
        }
    }

    companion object {
        fun decode(bytes: ByteArray, maximumBytes: Int, poll: () -> Unit): CountCommandOutcome {
            val b = CountCommandRecords.parse(bytes, maximumBytes, poll)
            val format = CountCommandRecords.integer(b, "format").let(Math::toIntExact)
            require(format == 1 || format == 2)
            val fields = arrayOf("format", "command_id", "input_hash", "namespace_hash", "action", "kind", "primary_fact_uuid",
                "primary_request_id", "reason", "follow_up", "effects", "origins", "evidence")
            CountCommandRecords.fields(b, *(if (format == 2) fields + "metric_resolution" else fields))
            val resolution = if (format == 2) b.getValue("metric_resolution").jsonObject.also(IndependentMetricInput::validateResolution) else null
            val action = CountCommandRecords.Action.valueOf(CountCommandRecords.text(b, "action"))
            val kind = Kind.valueOf(CountCommandRecords.text(b, "kind"))
            require((format == 2) == (action == CountCommandRecords.Action.METRIC_APPEND))
            require(format != 2 || kind == Kind.APPLIED)
            val primary = CountCommandRecords.nullableUuid(b, "primary_fact_uuid")
            val request = CountCommandRecords.nullableUuid(b, "primary_request_id")
            val reason = if (b.getValue("reason") == JsonNull) null else
                CountCommandRecords.NoEffectReason.valueOf(CountCommandRecords.text(b, "reason"))
            val followUp = FollowUp.valueOf(CountCommandRecords.text(b, "follow_up"))
            val effects = b.getValue("effects").jsonArray.map { element ->
                poll()
                val e = element.jsonObject
                CountCommandRecords.fields(e, "kind", "fact_uuid", "object_uuid", "reverts_fact_uuid")
                Effect(FactKind.valueOf(CountCommandRecords.text(e, "kind")), CountCommandRecords.uuid(e, "fact_uuid"),
                    CountCommandRecords.text(e, "object_uuid"), CountCommandRecords.nullableUuid(e, "reverts_fact_uuid"),
                    legacyNativeMetricIdentity = format == 2 && action == CountCommandRecords.Action.METRIC_APPEND &&
                        resolution?.get("profile") == JsonPrimitive("legacy-v4"))
            }
            val origins = b.getValue("origins").jsonArray.map { element ->
                poll()
                val e = element.jsonObject
                CountCommandRecords.fields(e, "request_id", "operation_id", "entity_type", "entity_uuid", "operation_type", "origin_hash")
                Origin(CountCommandRecords.uuid(e, "request_id"), CountCommandRecords.uuid(e, "operation_id"),
                    CountCommandRecords.text(e, "entity_type"), CountCommandRecords.uuid(e, "entity_uuid"),
                    CountCommandRecords.text(e, "operation_type"), CountCommandRecords.digest(e, "origin_hash"))
            }
            val evidence = CountCommandEvidence.decode(b.getValue("evidence").jsonObject,
                CountCommandRecords.digest(b, "input_hash"), CountCommandRecords.digest(b, "namespace_hash"))
            require(evidence.format == format) { "COMMAND_EVIDENCE_FORMAT_CHANGED" }
            require(effects.map { it.factUuid }.distinct().size == effects.size)
            require(origins.map { it.requestId }.distinct().size == origins.size)
            require(origins.map { it.operationId }.distinct().size == origins.size)
            if (kind == Kind.NO_EFFECT) {
                require(primary == null && request == null && reason != null && effects.isEmpty() && origins.isEmpty() &&
                    followUp == FollowUp.NONE && action != CountCommandRecords.Action.LINKED_METRICS)
                if (action == CountCommandRecords.Action.COUNT_ADD) require(reason == CountCommandRecords.NoEffectReason.COUNTDOWN_COMPLETE)
                else require(reason in setOf(CountCommandRecords.NoEffectReason.NO_CURRENT_COUNT,
                    CountCommandRecords.NoEffectReason.EXPLICIT_TARGET_ABSENT, CountCommandRecords.NoEffectReason.TARGET_REMOVED_BEFORE_MUTATION))
            } else {
                require(primary != null && effects.any { it.factUuid == primary } && reason == null)
                if (evidence.isTyped)
                    require(request == null || origins.any { it.requestId == request })
                else require(origins.isEmpty() && request != null && evidence.records.any {
                    it.role == CountCommandEvidence.Role.ORIGINAL_SOURCE && it.text("operationId") == request })
                require(effects.isNotEmpty())
                when (action) {
                    CountCommandRecords.Action.METRIC_APPEND -> require(effects.size == 1 && effects.single().factKind == FactKind.METRIC_OBSERVATION &&
                        followUp == FollowUp.NONE && evidence.format == 2)
                    CountCommandRecords.Action.COUNT_ADD -> require(effects.size == 1 && effects.single().factKind == FactKind.COUNT_ADD)
                    CountCommandRecords.Action.COUNT_UNDO -> require(effects.size == 1 && effects.single().factKind ==
                        (if (evidence.profile == CountCommandEvidence.Profile.FORMAL_V5) FactKind.COUNT_REVERT else FactKind.LOCAL_COUNT_DELETE) &&
                        followUp == FollowUp.NONE)
                    CountCommandRecords.Action.LINKED_METRICS -> require(effects.all { it.factKind == FactKind.METRIC_OBSERVATION } && followUp == FollowUp.NONE)
                }
            }
            if (kind == Kind.NO_EFFECT) require(evidence.records.isEmpty())
            return CountCommandOutcome(CountCommandRecords.uuid(b, "command_id"), CountCommandRecords.digest(b, "input_hash"),
                CountCommandRecords.digest(b, "namespace_hash"), action, kind, primary, request, reason, followUp,
                evidence, effects, origins, CountCommandRecords.canonical(b), format, resolution)
        }

        /** Use AFTER native types and catalog row hash are checked. Cross-check every redundant
         * column, not only JSON, and never treat a mismatch as absence or a rebuildable cache.
         */
        fun fromRow(row: CountCommandResultEntity, maximumBytes: Int, poll: () -> Unit): CountCommandOutcome =
            decode(row.resultJson.toByteArray(Charsets.UTF_8), maximumBytes, poll).also {
                require(it.row() == row) { "COMMAND_OUTCOME_NATIVE_COLUMNS_CHANGED" }
            }

        private fun validHash(value: String) = value.length == 64 && value.all { it in "0123456789abcdef" }
    }
}
