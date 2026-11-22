package com.dayforge.data.repository

import com.dayforge.data.appearance.strictAppearanceJson
import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.domain.model.isContractUuid
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.*

/** Strict representation of a persisted request, not a credential or permission to execute.
 * Canonical text is owned; callers obtain newly parsed trees and cannot mutate retained input.
 * Size is supplied by the caller's established native/input budget, not a new product limit.
 */
internal object CountCommandRecords {
    const val FORMAT = 1
    const val VALIDATOR = "count-command-recovery/v1"
    const val METRIC_FORMAT = 2
    const val METRIC_VALIDATOR = "count-command-recovery/v2"
    enum class Action { COUNT_ADD, COUNT_UNDO, LINKED_METRICS, METRIC_APPEND }
    enum class Capture { EXECUTION_INPUT, VERIFIED_NO_EFFECT }
    enum class NoEffectReason { NO_CURRENT_COUNT, EXPLICIT_TARGET_ABSENT, TARGET_REMOVED_BEFORE_MUTATION, COUNTDOWN_COMPLETE }
    enum class Execution { PREPARED, APPLIED, NO_EFFECT, CANCELLED, INVALIDATED }
    enum class Delivery { PENDING_RESOLUTION, RESULT_PENDING, FOLLOW_UP_PENDING, METRICS_COMMITTED, CLOSED, DELIVERED }

    data class Namespace(val account: String, val server: String?, val epoch: String?,
        val device: String?, val storage: String, val dataset: String) {
        init {
            require(isContractUuid(account) && isContractUuid(storage) && isContractUuid(dataset))
            require(server == null || isContractUuid(server)); require(epoch == null || isContractUuid(epoch))
            require(device == null || isContractUuid(device)); require((server == null) == (epoch == null))
        }
        fun body(): JsonObject = buildJsonObject {
            put("account", account); put("server", server?.let(::JsonPrimitive) ?: JsonNull)
            put("epoch", epoch?.let(::JsonPrimitive) ?: JsonNull); put("device", device?.let(::JsonPrimitive) ?: JsonNull)
            put("storage", storage); put("dataset", dataset)
        }
        val hash: String get() = CountCommandRecords.hash("namespace", canonical(body()))
        // Physical catalog lifetime. Full protocol namespace above remains on EACH command.
        // Dataset is issued only by trusted create/migrate/clear-replace initialization.
        // Ordinary login and first registration retain it; MAC/native owner bind installation.
        val catalogBinding: String get() = CountCommandRecords.hash("account-storage-dataset", canonical(buildJsonObject {
            put("account", account); put("storage", storage); put("dataset", dataset)
        }))
        fun permitsInitialRegistration(next: Namespace): Boolean = account == next.account && storage == next.storage && dataset == next.dataset &&
            server == null && epoch == null && next.server != null && next.epoch != null && next.device != null &&
            (device == null || device == next.device)
        companion object {
            fun fromJson(ns: JsonObject): Namespace {
                fields(ns, "account", "server", "epoch", "device", "storage", "dataset")
                return Namespace(uuid(ns, "account"), nullableUuid(ns, "server"), nullableUuid(ns, "epoch"), nullableUuid(ns, "device"), uuid(ns, "storage"), uuid(ns, "dataset"))
            }
        }
    }

    class Intent private constructor(val commandId: String, val namespace: Namespace,
        val action: Action, val capture: Capture, val inputHash: String, private val encoded: String, val format: Int) {
        fun text(): String = encoded
        fun body(): JsonObject = Json.parseToJsonElement(encoded).jsonObject
        companion object {
            fun decode(bytes: ByteArray, maximumBytes: Int, poll: () -> Unit): Intent {
                val body = parse(bytes, maximumBytes, poll)
                fields(body, "format", "command_id", "namespace", "original_auth_generation", "action", "capture", "input")
                val format = integer(body, "format").let(Math::toIntExact)
                require(format == FORMAT || format == METRIC_FORMAT)
                val command = uuid(body, "command_id")
                val ns = body.getValue("namespace").jsonObject
                fields(ns, "account", "server", "epoch", "device", "storage", "dataset")
                val namespace = Namespace(uuid(ns, "account"), nullableUuid(ns, "server"), nullableUuid(ns, "epoch"),
                    nullableUuid(ns, "device"), uuid(ns, "storage"), uuid(ns, "dataset"))
                require(text(body, "original_auth_generation").isNotBlank())
                val action = Action.valueOf(text(body, "action"))
                val capture = Capture.valueOf(text(body, "capture"))
                val input = body.getValue("input").jsonObject
                if (format == METRIC_FORMAT) {
                    require(action == Action.METRIC_APPEND && capture == Capture.EXECUTION_INPUT)
                    IndependentMetricInput.validate(input)
                } else if ("local_profile" in input) CountCommandLegacyInput.validate(action, capture, input)
                else if (capture == Capture.EXECUTION_INPUT) validateInputShape(action, input)
                else validateNoEffectCapture(action, input)
                require((format == METRIC_FORMAT) == (action == Action.METRIC_APPEND))
                val encoded = canonical(body)
                // Authentication generation is history inside the signed request, not namespace.
                return Intent(command, namespace, action, capture, hash("intent", encoded), encoded, format)
            }
        }
    }

    data class Status(val commandId: String, val inputHash: String, val revision: Long,
        val execution: Execution, val delivery: Delivery, val parent: String?, val child: String?,
        val previous: String?, val next: String?, val reason: String?, val businessGenerationAtAdmission: Long,
        val format: Int = FORMAT) {
        init {
            require(format == FORMAT || format == METRIC_FORMAT)
            require(businessGenerationAtAdmission >= 0)
            require(isContractUuid(commandId) && HistoryHash.valid(inputHash) && revision > 0)
            for (id in listOf(parent, child, previous, next)) require(id == null || isContractUuid(id) && id != commandId)
            require((execution in setOf(Execution.CANCELLED, Execution.INVALIDATED)) == (reason != null))
            require(reason == null || reason.isNotBlank())
            require(pending || previous == null && next == null)
            when (execution) {
                Execution.PREPARED -> require(delivery == Delivery.PENDING_RESOLUTION && child == null)
                Execution.NO_EFFECT, Execution.CANCELLED, Execution.INVALIDATED ->
                    require(delivery in setOf(Delivery.RESULT_PENDING, Delivery.CLOSED, Delivery.DELIVERED) && child == null)
                Execution.APPLIED -> require(delivery != Delivery.PENDING_RESOLUTION)
            }
        }
        val pending: Boolean get() = delivery !in setOf(Delivery.CLOSED, Delivery.DELIVERED)
        fun body(): JsonObject = buildJsonObject {
            put("format", format); put("command_id", commandId); put("input_hash", inputHash); put("revision", revision)
            put("business_generation_at_admission", businessGenerationAtAdmission)
            put("execution", execution.name); put("delivery", delivery.name)
            put("parent", parent?.let(::JsonPrimitive) ?: JsonNull); put("child", child?.let(::JsonPrimitive) ?: JsonNull)
            put("previous", previous?.let(::JsonPrimitive) ?: JsonNull); put("next", next?.let(::JsonPrimitive) ?: JsonNull)
            put("reason", reason?.let(::JsonPrimitive) ?: JsonNull)
        }
        val hash: String get() = CountCommandRecords.hash("status", canonical(body()))
        companion object {
            fun decode(bytes: ByteArray, maximumBytes: Int, poll: () -> Unit): Status {
                val b = parse(bytes, maximumBytes, poll)
                fields(b, "format", "command_id", "input_hash", "revision", "execution", "delivery", "parent", "child", "previous", "next", "reason", "business_generation_at_admission")
                val format = integer(b, "format").let(Math::toIntExact)
                require(format == FORMAT || format == METRIC_FORMAT)
                return Status(uuid(b, "command_id"), digest(b, "input_hash"), integer(b, "revision"),
                    Execution.valueOf(text(b, "execution")), Delivery.valueOf(text(b, "delivery")),
                    nullableUuid(b, "parent"), nullableUuid(b, "child"), nullableUuid(b, "previous"), nullableUuid(b, "next"),
                    if (b.getValue("reason") == JsonNull) null else text(b, "reason"), integer(b, "business_generation_at_admission"), format)
            }
        }
    }

    data class CatalogMember(val commandId: String, val inputHash: String, val intentRowHash: String,
        val outcomeRowHash: String?, val statusRowHash: String, val intentBytes: Long, val statusBytes: Long, val outcomeBytes: Long?) {
        init { require(isContractUuid(commandId)); listOf(inputHash, intentRowHash, statusRowHash).forEach { require(HistoryHash.valid(it)) }
            require(outcomeRowHash == null || HistoryHash.valid(outcomeRowHash))
            require(intentBytes in 1..Int.MAX_VALUE.toLong() && statusBytes in 1..Int.MAX_VALUE.toLong())
            require((outcomeRowHash == null) == (outcomeBytes == null)); require(outcomeBytes == null || outcomeBytes in 1..Int.MAX_VALUE.toLong()) }
        fun body() = buildJsonObject { put("command_id", commandId); put("input_hash", inputHash)
            put("intent", intentRowHash); put("outcome", outcomeRowHash?.let(::JsonPrimitive) ?: JsonNull); put("status", statusRowHash)
            put("intent_bytes", intentBytes); put("status_bytes", statusBytes); put("outcome_bytes", outcomeBytes?.let(::JsonPrimitive) ?: JsonNull) }
        companion object {
            fun decode(bytes: ByteArray, poll: () -> Unit): CatalogMember {
                val b = parse(bytes, 4096, poll); fields(b, "command_id", "input_hash", "intent", "outcome", "status", "intent_bytes", "status_bytes", "outcome_bytes")
                return CatalogMember(uuid(b, "command_id"), digest(b, "input_hash"), digest(b, "intent"),
                    if (b.getValue("outcome") == JsonNull) null else digest(b, "outcome"), digest(b, "status"),
                    integer(b, "intent_bytes"), integer(b, "status_bytes"), if (b.getValue("outcome_bytes") == JsonNull) null else integer(b, "outcome_bytes"))
            }
        }
    }

    private fun validateNoEffectCapture(action: Action, input: JsonObject) {
        // A Service/Repository decision at its ORIGINAL selection point. The private T0
        // owner must have verified this exact read and freeze it; callers cannot invent one.
        // Do not allocate a fact/revert UUID or business timestamp when the old path made none.
        require(action == Action.COUNT_UNDO)
        fields(input, "habit_uuid", "requested_completion_id", "reason", "selector", "selection", "dependency_hash")
        val habit = nullableUuid(input, "habit_uuid")
        val requested = if (input.getValue("requested_completion_id") == JsonNull) null else integer(input, "requested_completion_id").also { require(it > 0) }
        val reason = NoEffectReason.valueOf(text(input, "reason"))
        val selection = input.getValue("selection").jsonObject
        require(digest(input, "dependency_hash") == hash("no-effect-selection", canonical(selection)))
        when (reason) {
            NoEffectReason.NO_CURRENT_COUNT -> {
                require(text(input, "selector") == "service-original-today-latest/v1" && habit != null && requested == null)
                fields(selection, "habit_uuid", "plan", "date", "quantity", "latest_id", "round", "policy")
                require(uuid(selection, "habit_uuid") == habit && selection.getValue("plan") is JsonObject)
                require(LocalDate.parse(text(selection, "date")).toString() == text(selection, "date"))
                val latest = if (selection.getValue("latest_id") == JsonNull) null else integer(selection, "latest_id").also { require(it > 0) }
                require(integer(selection, "quantity") <= 0 || latest == null)
                require(selection.getValue("round") == JsonNull || selection.getValue("round") is JsonObject)
                require(selection.getValue("policy") == JsonNull || selection.getValue("policy") is JsonObject)
            }
            NoEffectReason.EXPLICIT_TARGET_ABSENT -> {
                require(text(input, "selector") == "repository-explicit-id/v1" && requested != null)
                fields(selection, "requested_completion_id", "present")
                require(integer(selection, "requested_completion_id") == requested && selection.getValue("present") == JsonPrimitive(false))
            }
            else -> error("COMMAND_NO_EFFECT_CAPTURE_REASON")
        }
    }

    private fun validateInputShape(action: Action, input: JsonObject) {
        when (action) {
            Action.METRIC_APPEND -> error("COMMAND_METRIC_V2_REQUIRED")
            Action.COUNT_ADD -> {
                fields(input, "habit_uuid", "habit_id", "widget_claim", "quantity", "occurred_at", "timezone", "local_date", "plan", "round", "reserved_fact_uuid")
                uuid(input, "habit_uuid"); uuid(input, "reserved_fact_uuid")
                require(integer(input, "quantity") in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                val instant = Instant.parse(text(input, "occurred_at")); val zone = ZoneId.of(text(input, "timezone"))
                require(LocalDate.parse(text(input, "local_date")) == instant.atZone(zone).toLocalDate())
            }
            Action.COUNT_UNDO -> {
                fields(input, "habit_uuid", "habit_id", "widget_claim", "target_fact_uuid", "target_native", "plan", "round", "reserved_revert_uuid", "capture_policy")
                uuid(input, "habit_uuid"); uuid(input, "target_fact_uuid"); uuid(input, "reserved_revert_uuid")
                require(input.getValue("target_native") is JsonObject)
                require(text(input, "capture_policy") == "original-delete-trigger-and-producer/v1")
            }
            Action.LINKED_METRICS -> {
                fields(input, "parent_command_id", "habit_uuid", "source_fact_uuid", "source_claim", "recorded_at", "timezone", "metrics")
                uuid(input, "parent_command_id"); uuid(input, "habit_uuid"); uuid(input, "source_fact_uuid")
                val source = requireNotNull(CountCommandInputs.restoredWidget(input, "source_claim"))
                require(source.habitUuid == uuid(input, "habit_uuid") && source.completionUuid == uuid(input, "source_fact_uuid"))
                integer(input, "recorded_at"); ZoneId.of(text(input, "timezone"))
                val values = input.getValue("metrics").jsonArray
                require(values.isNotEmpty())
                val identities = hashSetOf<String>()
                for (value in values) {
                    val metric = value.jsonObject
                    fields(metric, "metric_uuid", "metric_id", "value_bits", "note", "definition", "link", "reserved_fact_uuid")
                    require(identities.add(uuid(metric, "metric_uuid"))); uuid(metric, "reserved_fact_uuid"); require(integer(metric, "metric_id") > 0)
                    val bits = text(metric, "value_bits")
                    require(bits.length == 16 && bits.all { it in "0123456789abcdef" })
                    require(Double.fromBits(java.lang.Long.parseUnsignedLong(bits, 16)).isFinite())
                    text(metric, "note"); require(metric.getValue("definition") is JsonObject && metric.getValue("link") is JsonObject)
                }
            }
        }
        if (action != Action.LINKED_METRICS) {
            require(integer(input, "habit_id") > 0)
            CountCommandInputs.restoredWidget(input)?.let { claim ->
                require(claim.habitId == integer(input, "habit_id") && claim.habitUuid == uuid(input, "habit_uuid"))
            }
            require(input.getValue("plan") is JsonObject || input.getValue("plan") == JsonNull)
            require(input.getValue("round") is JsonObject || input.getValue("round") == JsonNull)
        }
        // These are representation checks only. Original plan/round/fact/link business validators
        // MUST run in the private capture/revalidation owner; no decode return is execution proof.
    }

    fun canonical(value: JsonElement): String = copiedCanonical(value).toString()
    private fun copiedCanonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { it.key to copiedCanonical(it.value) })
        is JsonArray -> JsonArray(value.map(::copiedCanonical))
        else -> value // Json primitives are immutable; every caller-owned container is copied.
    }
    fun hash(domain: String, canonical: String): String = HistoryBlockCodec.hash(
        ("DayForge/count-command/$domain/v1\u0000" + canonical).toByteArray(Charsets.UTF_8))
    internal fun parse(bytes: ByteArray, limit: Int, poll: () -> Unit): JsonObject = Json.parseToJsonElement(
        strictAppearanceJson(bytes, limit, poll) { error("COMMAND_RECORD_$it") }).jsonObject
    internal fun fields(value: JsonObject, vararg keys: String) { require(value.keys == keys.toSet()) { "COMMAND_RECORD_FIELDS" } }
    internal fun text(value: JsonObject, key: String): String = value.getValue(key).jsonPrimitive.let {
        require(it.isString) { "COMMAND_RECORD_STRING" }; it.content
    }
    internal fun integer(value: JsonObject, key: String): Long = value.getValue(key).jsonPrimitive.let {
        require(!it.isString); val number = requireNotNull(it.longOrNull); require(it.content == number.toString()); number
    }
    internal fun uuid(value: JsonObject, key: String) = text(value, key).also { require(isContractUuid(it)) }
    internal fun nullableUuid(value: JsonObject, key: String) = if (value.getValue(key) == JsonNull) null else uuid(value, key)
    internal fun digest(value: JsonObject, key: String) = text(value, key).also { require(HistoryHash.valid(it)) }
    private object HistoryHash { fun valid(value: String) = value.length == 64 && value.all { it in "0123456789abcdef" } }
}
