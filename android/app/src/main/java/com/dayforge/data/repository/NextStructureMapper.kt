package com.dayforge.data.repository

import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.model.PlanStructureMetadata
import com.dayforge.data.model.validPlanDate
import com.dayforge.data.model.parsePlanTime
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.model.OneTimeState
import com.dayforge.domain.model.iconAllowed
import com.dayforge.domain.model.contractLongOrNull
import com.dayforge.domain.model.isContractName
import com.dayforge.domain.model.isContractUuid
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.*

/** V5 structure mapping. Callers own parent/asset authorization, history locks and atomic persistence. */
internal object NextStructureMapper {
    private val header = setOf("public_id", "revision", "created_at", "updated_at", "deleted_at")
    private val planFields = setOf("node_kind", "title", "description", "status", "visibility", "sort_order",
        "created_at", "parent_uuid", "goal", "activity", "appearance")
    private val metricFields = setOf("name", "description", "unit", "decimal_places", "aggregation_type",
        "target_direction", "target_value", "target_value_upper", "status", "appearance")

    fun readPlan(payload: JsonObject, uuid: String, revision: Long, previous: HabitEntity? = null): HabitEntity {
        validateHeader(payload, uuid, revision, planFields)
        require(previous == null || previous.uuid == uuid)
        return decodePlan(JsonObject(payload.filterKeys { it in planFields }), uuid,
            instant(payload, "updated_at").toEpochMilli(), previous)
    }

    private fun decodePlan(body: JsonObject, uuid: String, updatedAt: Long, previous: HabitEntity?): HabitEntity {
        require(body.keys == planFields && isContractUuid(uuid))
        val kind = body.text("node_kind")
        require(kind == "goal" || kind == "activity")
        val goal = kind == "goal"
        val name = body.text("title")
        require(isContractName(name, 100))
        val description = body.text("description").also { require(it.codePointCount(0, it.length) <= 1000) }
        require(body.text("visibility") == "private")
        val parent = body.optionalText("parent_uuid")
        require(parent == null || (isContractUuid(parent) && parent != uuid && !goal))
        val created = instant(body, "created_at")
        val appearance = Json.decodeFromJsonElement<ObjectAppearance>(body.getValue("appearance"))
        val status = body.text("status")
        val metadata: PlanStructureMetadata
        val schedule: HabitSchedule
        val habitType: HabitType
        val target: Int
        val countdown: Boolean
        val cycles: Int?
        val failMode: FailMode
        val result: Boolean?
        val policy: String?
        if (goal) {
            require(body["activity"] == JsonNull)
            val detail = body.getValue("goal").jsonObject
            require(detail.keys == setOf("start_date", "due_date", "target_cycles", "failure_policy", "evaluation_policy", "manual_result"))
            val evaluation = detail.getValue("evaluation_policy").jsonObject
            require(evaluation.keys == setOf("schema_version", "type") && evaluation.int("schema_version") == 1 && evaluation.text("type") == "manual")
            result = when (detail.optionalText("manual_result")) {
                null -> null
                "succeeded" -> true
                "failed" -> false
                else -> error("Invalid goal result")
            }
            require(when (result) { true -> status == "completed"; false -> status == "failed"; null -> status in setOf("active", "archived") })
            cycles = detail.positiveIntOrNull("target_cycles")
            failMode = failure(detail)
            metadata = PlanStructureMetadata(body.text("created_at"), body.long("sort_order"),
                detail.optionalText("start_date"), detail.optionalText("due_date"), null, null, null, null)
            schedule = HabitSchedule.Daily // Goals have no habit recurrence; dates live in structural metadata.
            habitType = HabitType.GOAL; target = 1; countdown = false; policy = null
        } else {
            require(body["goal"] == JsonNull && status in setOf("active", "archived"))
            val detail = body.getValue("activity").jsonObject
            require(detail.keys == setOf("tracking_mode", "is_countdown", "recurrence_rule", "completion_policy", "target_value",
                "target_unit", "target_cycles", "failure_policy", "preferred_local_time", "timezone", "origin_assignment_id"))
            habitType = when (detail.text("tracking_mode")) {
                "check" -> HabitType.CHECK_IN
                "count" -> HabitType.COUNTING
                "duration" -> HabitType.TIMER
                else -> error("Invalid tracking mode")
            }
            countdown = detail.boolean("is_countdown")
            require(!countdown || habitType in setOf(HabitType.COUNTING, HabitType.TIMER))
            policy = detail.text("completion_policy").also { require(it in setOf("recurring", "one_and_done")) }
            val rule = detail.getValue("recurrence_rule").jsonObject
            val (parsedSchedule, anchor) = schedule(rule)
            schedule = parsedSchedule
            require((policy == "one_and_done") == (schedule is HabitSchedule.Once))
            val rawTarget = detail.decimal("target_value")
            // Never floor a fractional value or overflow Android's existing numeric domain.
            val wholeTarget = rawTarget.intValueExact()
            require(wholeTarget >= 0 && (habitType == HabitType.CHECK_IN || wholeTarget > 0))
            val unit = detail.optionalText("target_unit")
            if (habitType == HabitType.TIMER) require(wholeTarget % 60 == 0 && wholeTarget <= 2_147_483_640 && unit == "second")
            target = if (habitType == HabitType.TIMER) wholeTarget / 60 else wholeTarget
            cycles = detail.positiveIntOrNull("target_cycles")
            failMode = failure(detail)
            metadata = PlanStructureMetadata(body.text("created_at"), body.long("sort_order"), anchor, null,
                detail.text("timezone"), unit, detail.optionalText("preferred_local_time"), detail.optionalText("origin_assignment_id"))
            if (policy == "one_and_done") require(habitType == HabitType.CHECK_IN && target == 1 && !countdown &&
                cycles == null && failMode == FailMode.LOOSE && unit == null &&
                metadata.preferredLocalTime == null && metadata.originAssignmentId == null)
            result = null
        }
        val once = policy == "one_and_done"
        if (appearance.icon is IconReference.Role) require(iconAllowed(appearance.icon, once))
        // Structure changes do not acknowledge/erase completion state. History locking is a repository responsibility.
        val state = if (once && previous?.completionPolicy == "one_and_done")
            OneTimeState(requireNotNull(previous.oneTimeConfirmedVersion), previous.oneTimeConfirmedHeadEventUuid,
                previous.oneTimeConfirmedCompletionEventUuid) else if (once) OneTimeState(0, null, null) else null
        val preferred = metadata.preferredLocalTime?.let(::parsePlanTime)?.let { it.hour * 60L + it.minute }
        val entity = HabitEntity(id = previous?.id ?: 0, uuid = uuid, name = name, description = description,
            habitType = habitType, iconResId = previous?.iconResId ?: 0, colorHex = previous?.colorHex ?: "#000000",
            schedule = schedule, targetValue = target, isCountdown = countdown, isActive = status == "active",
            parentHabitId = parent, targetCycles = cycles, failMode = failMode, goalSuccess = result,
            activityRate = previous?.activityRate ?: 100, activityRateUpdatedAt = previous?.activityRateUpdatedAt ?: updatedAt,
            bestTime = preferred, createdAt = created.toEpochMilli(), updatedAt = updatedAt, completionPolicy = policy,
            oneTimeConfirmedVersion = state?.version, oneTimeConfirmedHeadEventUuid = state?.headEventUuid,
            oneTimeConfirmedCompletionEventUuid = state?.completionEventUuid, appearance = appearance, planMetadata = metadata)
        return entity
    }

    fun writePlan(h: HabitEntity): JsonObject {
        val m = requireNotNull(h.planMetadata)
        require(Instant.parse(m.creationTimestamp).toEpochMilli() == h.createdAt)
        val goal = h.habitType == HabitType.GOAL
        require(if (goal) h.completionPolicy == null && m.timezone == null && m.targetUnit == null &&
            m.preferredLocalTime == null && m.originAssignmentId == null
            else m.timezone != null && m.goalDueDate == null)
        val preferred = m.preferredLocalTime?.let(::parsePlanTime)?.let { it.hour * 60L + it.minute }
        require(preferred == h.bestTime) { "Preferred time must be explicitly updated together with its metadata" }
        val body = buildJsonObject {
            put("node_kind", if (goal) "goal" else "activity"); put("title", h.name); put("description", h.description)
            put("status", when { goal && h.goalSuccess == true -> "completed"; goal && h.goalSuccess == false -> "failed";
                h.isActive -> "active"; else -> "archived" })
            put("visibility", "private"); put("sort_order", m.sortOrder); put("created_at", m.creationTimestamp)
            put("parent_uuid", nullable(h.parentHabitId)); put("appearance", Json.encodeToJsonElement(requireNotNull(h.appearance)))
            put("goal", if (!goal) JsonNull else buildJsonObject {
                put("start_date", nullable(m.startDate)); put("due_date", nullable(m.goalDueDate))
                put("target_cycles", h.targetCycles?.let(::JsonPrimitive) ?: JsonNull)
                put("failure_policy", failure(h.failMode)); put("evaluation_policy", buildJsonObject { put("schema_version", 1); put("type", "manual") })
                put("manual_result", nullable(h.goalSuccess?.let { if (it) "succeeded" else "failed" }))
            })
            put("activity", if (goal) JsonNull else buildJsonObject {
                put("tracking_mode", when (h.habitType) { HabitType.CHECK_IN -> "check"; HabitType.COUNTING -> "count";
                    HabitType.TIMER -> "duration"; HabitType.GOAL -> error("Goal is not activity") })
                put("is_countdown", h.isCountdown); put("completion_policy", requireNotNull(h.completionPolicy))
                put("target_value", JsonPrimitive(if (h.habitType == HabitType.TIMER) h.targetValue.toLong() * 60 else h.targetValue.toLong()))
                put("target_unit", nullable(m.targetUnit)); put("target_cycles", h.targetCycles?.let(::JsonPrimitive) ?: JsonNull)
                put("failure_policy", failure(h.failMode)); put("preferred_local_time", nullable(m.preferredLocalTime))
                put("timezone", requireNotNull(m.timezone)); put("origin_assignment_id", nullable(m.originAssignmentId))
                put("recurrence_rule", buildJsonObject {
                    put("schema_version", 1)
                    when (val schedule = h.schedule) {
                        is HabitSchedule.Once -> { require(m.startDate == null); put("type", "once"); put("due_date", nullable(schedule.dueDate)) }
                        HabitSchedule.Daily -> { put("type", "daily"); put("interval", 1); put("start_date", nullable(m.startDate)) }
                        is HabitSchedule.Weekly -> { put("type", "weekly"); put("interval", 1); put("start_date", nullable(m.startDate));
                            put("weekdays", JsonArray(schedule.daysOfWeek.sorted().map(::JsonPrimitive))) }
                        is HabitSchedule.Monthly -> { put("type", "monthly"); put("interval", 1); put("start_date", nullable(m.startDate)); put("day_of_month", schedule.dayOfMonth) }
                        is HabitSchedule.Custom -> { put("type", "interval"); put("every_days", schedule.frequencyDays); put("start_date", requireNotNull(m.startDate)) }
                    }
                })
            })
        }
        // Reuse the same full structural checks for writes, without inventing a server header/result.
        val canonical = h.copy(schedule = (h.schedule as? HabitSchedule.Weekly)?.let { it.copy(daysOfWeek = it.daysOfWeek.sorted()) } ?: h.schedule)
        require(decodePlan(body, h.uuid, h.updatedAt, h) == canonical) { "Inconsistent local structure projection" }
        return body
    }

    fun readMetric(payload: JsonObject, uuid: String, revision: Long, previous: MetricEntity? = null): MetricEntity {
        validateHeader(payload, uuid, revision, metricFields)
        require(previous == null || previous.uuid == uuid)
        val body = JsonObject(payload.filterKeys { it in metricFields })
        validateMetric(body)
        return MetricEntity(id = previous?.id ?: 0, uuid = uuid, name = body.text("name"), description = body.text("description"),
            unit = body.text("unit"), decimalPlaces = body.int("decimal_places"), aggregationType = body.text("aggregation_type"),
            targetDirection = body.optionalText("target_direction"), targetValue = body.optionalDecimal("target_value")?.exactDouble(),
            targetValueUpper = body.optionalDecimal("target_value_upper")?.exactDouble(), iconResId = previous?.iconResId ?: 0,
            colorHex = previous?.colorHex ?: "#000000", isActive = body.text("status") == "active",
            createdAt = instant(payload, "created_at").toEpochMilli(), updatedAt = instant(payload, "updated_at").toEpochMilli(),
            appearance = Json.decodeFromJsonElement(body.getValue("appearance")))
    }

    fun writeMetric(metric: MetricEntity): JsonObject = buildJsonObject {
        put("name", metric.name); put("description", metric.description); put("unit", metric.unit)
        put("decimal_places", metric.decimalPlaces); put("aggregation_type", metric.aggregationType)
        put("target_direction", nullable(metric.targetDirection)); put("target_value", metric.targetValue?.let(::JsonPrimitive) ?: JsonNull)
        put("target_value_upper", metric.targetValueUpper?.let(::JsonPrimitive) ?: JsonNull)
        put("status", if (metric.isActive) "active" else "archived")
        put("appearance", Json.encodeToJsonElement(requireNotNull(metric.appearance)))
    }.also(::validateMetric)

    private fun validateMetric(body: JsonObject) {
        require(body.keys == metricFields && isContractName(body.text("name"), 100) && isContractName(body.text("unit"), 50))
        require(body.text("description").let { it.codePointCount(0, it.length) <= 1000 })
        require(body.int("decimal_places") in 0..6 && body.text("aggregation_type") in setOf("average", "sum", "by_time"))
        val direction = body.optionalText("target_direction")
        require(direction == null || direction in setOf("increase", "decrease", "range"))
        val lower = body.optionalDecimal("target_value"); val upper = body.optionalDecimal("target_value_upper")
        lower?.exactDouble(); upper?.exactDouble()
        if (direction == "range") require(lower != null && upper != null && lower <= upper)
        require(body.text("status") in setOf("active", "archived"))
        val appearance = Json.decodeFromJsonElement<ObjectAppearance>(body.getValue("appearance"))
        if (appearance.icon is IconReference.Role) require(iconAllowed(appearance.icon, false))
    }

    private fun schedule(rule: JsonObject): Pair<HabitSchedule, String?> {
        require(rule.int("schema_version") == 1)
        val type = rule.text("type")
        if (type in setOf("daily", "weekly", "monthly")) require(rule.int("interval") == 1)
        val date = if (type == "once") null else rule.optionalText("start_date").also { require(validPlanDate(it)) }
        val common = setOf("schema_version", "type", "interval", "start_date")
        val result = when (type) {
            "daily" -> { require(rule.keys == common); HabitSchedule.Daily }
            "weekly" -> {
                require(rule.keys == common + "weekdays")
                val days = rule.getValue("weekdays").jsonArray.map { integer(it).also { n -> require(n in 1..7) }.toInt() }
                require(days.size in 1..7 && days.distinct().size == days.size)
                HabitSchedule.Weekly(days.sorted())
            }
            "monthly" -> { require(rule.keys == common + "day_of_month"); HabitSchedule.Monthly(rule.int("day_of_month").also { require(it in 1..31) }) }
            "interval" -> { require(rule.keys == setOf("schema_version", "type", "every_days", "start_date") && date != null)
                HabitSchedule.Custom(rule.int("every_days").also { require(it in 1..3650) }) }
            "once" -> { require(rule.keys == setOf("schema_version", "type", "due_date")); HabitSchedule.Once(rule.optionalText("due_date")) }
            else -> error("Unsupported recurrence rule")
        }
        return result to date
    }

    private fun validateHeader(payload: JsonObject, uuid: String, revision: Long, fields: Set<String>) {
        require(isContractUuid(uuid) && revision > 0 && payload.keys == fields + header)
        require(payload.text("public_id") == uuid && payload.long("revision") == revision && payload["deleted_at"] == JsonNull)
        instant(payload, "created_at"); instant(payload, "updated_at")
    }
    private fun failure(detail: JsonObject): FailMode {
        val policy = detail.getValue("failure_policy").jsonObject
        require(policy.keys == setOf("schema_version", "type") && policy.int("schema_version") == 1)
        return when (policy.text("type")) { "strict" -> FailMode.STRICT; "loose" -> FailMode.LOOSE; else -> error("Invalid failure policy") }
    }
    private fun failure(mode: FailMode) = buildJsonObject { put("schema_version", 1); put("type", if (mode == FailMode.STRICT) "strict" else "loose") }
    private fun nullable(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun JsonObject.text(key: String): String = getValue(key).let { require(it is JsonPrimitive && it.isString); it.content }
    private fun JsonObject.optionalText(key: String): String? = if (getValue(key) == JsonNull) null else text(key)
    private fun integer(value: JsonElement): Long = requireNotNull(contractLongOrNull(value))
    private fun JsonObject.long(key: String): Long = integer(getValue(key))
    private fun JsonObject.int(key: String): Int = long(key).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
    private fun JsonObject.positiveIntOrNull(key: String): Int? = if (getValue(key) == JsonNull) null else int(key).also { require(it > 0) }
    private fun JsonObject.boolean(key: String): Boolean = getValue(key).let { require(it is JsonPrimitive && !it.isString); requireNotNull(it.booleanOrNull) }
    private fun JsonObject.decimal(key: String): BigDecimal = getValue(key).let { require(it is JsonPrimitive); requireNotNull(it.content.toBigDecimalOrNull()) }
    private fun JsonObject.optionalDecimal(key: String): BigDecimal? = if (getValue(key) == JsonNull) null else decimal(key)
    private fun BigDecimal.exactDouble(): Double = toDouble().also { require(it.isFinite() && BigDecimal.valueOf(it).compareTo(this) == 0) }
    private fun instant(value: JsonObject, key: String): Instant = Instant.parse(value.text(key)).also { require(it.atZone(ZoneId.of("UTC")).year in 1..9999) }
}
