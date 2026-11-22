package com.dayforge.data.repository

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CountActionIdentity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.json.*

/** Local-v4 input interpretation. An undo is an exact local DELETE, never a fabricated
 * v5 revert. These values are ordinary frozen input; fresh producer checks remain required.
 * Repository clock/target selection remain at their original pre-mutation positions.
 */
internal object CountCommandLegacyInput {
    const val PROFILE = "legacy-v4-repository/v1"
    fun isLegacy(intent: CountCommandRecords.Intent): Boolean =
        intent.body().getValue("input").jsonObject["local_profile"] == JsonPrimitive(PROFILE) || CountCommandLegacyWidgetInput.matches(intent)

    suspend fun add(database: HabitDatabase, access: LocalCoreWriteAccess, habit: HabitEntity,
        identity: CountActionIdentity?, at: Instant, zone: ZoneId, quantity: Int): CountCommandRecords.Intent {
        requireHabit(habit, identity)
        return encode(database, access, CountCommandRecords.Action.COUNT_ADD, buildJsonObject {
            common(habit, identity)
            put("clock_policy", "repository-captured-input/v1")
            put("occurred_at", at.toString()); put("timezone", zone.id); put("local_date", at.atZone(zone).toLocalDate().toString())
            put("quantity", quantity); put("reserved_fact_uuid", UUID.randomUUID().toString())
        })
    }

    suspend fun undo(database: HabitDatabase, access: LocalCoreWriteAccess, habit: HabitEntity,
        identity: CountActionIdentity?, target: CompletionEntity): CountCommandRecords.Intent {
        requireHabit(habit, identity)
        require(target.habitId == habit.id && target.oneTimeAction == null)
        // Ordinary repository permits nullable or differing OLD.habitUuid; its original
        // equality check and trigger COALESCE are preserved, not replaced by Widget rules.
        return encode(database, access, CountCommandRecords.Action.COUNT_UNDO, buildJsonObject {
            common(habit, identity)
            put("target_policy", "repository-captured-native-target/v1")
            put("target_fact_uuid", target.uuid); put("target_native", CountCommandProducerPlan.frozenCompletion(target))
        })
    }

    suspend fun noCurrent(database: HabitDatabase, access: LocalCoreWriteAccess, habit: HabitEntity,
        identity: CountActionIdentity, selection: LegacyCountNoTarget): CountCommandRecords.Intent {
        requireHabit(habit, identity)
        require(selection.quantity.habitId == habit.id)
        val observed = selection.body()
        return encode(database, access, CountCommandRecords.Action.COUNT_UNDO, buildJsonObject {
            common(habit, identity)
            put("reason", CountCommandRecords.NoEffectReason.NO_CURRENT_COUNT.name)
            put("selector", "service-legacy-quantity-then-latest/v1"); put("selection", observed)
            put("dependency_hash", CountCommandRecords.hash("legacy-no-target-selection", CountCommandRecords.canonical(observed)))
        }, CountCommandRecords.Capture.VERIFIED_NO_EFFECT)
    }

    fun validate(action: CountCommandRecords.Action, capture: CountCommandRecords.Capture, input: JsonObject) {
        if (input["local_profile"] == JsonPrimitive(CountCommandLegacyWidgetInput.PROFILE)) {
            CountCommandLegacyWidgetInput.validate(action, capture, input); return
        }
        require(CountCommandRecords.text(input, "local_profile") == PROFILE)
        if (action == CountCommandRecords.Action.LINKED_METRICS) {
            require(capture == CountCommandRecords.Capture.EXECUTION_INPUT)
            CountCommandLegacyMetricInput.validate(input)
            return
        }
        if (capture == CountCommandRecords.Capture.VERIFIED_NO_EFFECT) {
            require(action == CountCommandRecords.Action.COUNT_UNDO)
            CountCommandRecords.fields(input, "local_profile", "habit_uuid", "habit_id", "identity_plan", "reason", "selector", "selection", "dependency_hash")
            CountCommandRecords.uuid(input, "habit_uuid"); require(CountCommandRecords.integer(input, "habit_id") > 0 && input.getValue("identity_plan") is JsonObject)
            require(CountCommandRecords.text(input, "reason") == CountCommandRecords.NoEffectReason.NO_CURRENT_COUNT.name &&
                CountCommandRecords.text(input, "selector") == "service-legacy-quantity-then-latest/v1")
            val selection = input.getValue("selection").jsonObject; LegacyCountNoTarget.validate(selection)
            require(CountCommandRecords.integer(selection, "habit_id") == CountCommandRecords.integer(input, "habit_id") &&
                CountCommandRecords.digest(input, "dependency_hash") == CountCommandRecords.hash("legacy-no-target-selection", CountCommandRecords.canonical(selection)))
            return
        }
        require(capture == CountCommandRecords.Capture.EXECUTION_INPUT)
        val common = arrayOf("local_profile", "habit_uuid", "habit_id", "identity_plan")
        CountCommandRecords.uuid(input, "habit_uuid"); require(CountCommandRecords.integer(input, "habit_id") > 0)
        require(input.getValue("identity_plan") == JsonNull || input.getValue("identity_plan") is JsonObject)
        when (action) {
            CountCommandRecords.Action.COUNT_ADD -> {
                CountCommandRecords.fields(input, *common, "clock_policy", "occurred_at", "timezone", "local_date", "quantity", "reserved_fact_uuid")
                require(CountCommandRecords.text(input, "clock_policy") == "repository-captured-input/v1")
                val at = Instant.parse(CountCommandRecords.text(input, "occurred_at"))
                val zone = ZoneId.of(CountCommandRecords.text(input, "timezone"))
                require(LocalDate.parse(CountCommandRecords.text(input, "local_date")) == at.atZone(zone).toLocalDate())
                require(CountCommandRecords.integer(input, "quantity") in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                CountCommandRecords.uuid(input, "reserved_fact_uuid")
            }
            CountCommandRecords.Action.COUNT_UNDO -> {
                CountCommandRecords.fields(input, *common, "target_policy", "target_fact_uuid", "target_native")
                require(CountCommandRecords.text(input, "target_policy") == "repository-captured-native-target/v1")
                val target = CountCommandProducerPlan.restoredCompletion(input.getValue("target_native").jsonObject)
                require(target.id > 0 && target.uuid == CountCommandRecords.uuid(input, "target_fact_uuid") &&
                    target.habitId == CountCommandRecords.integer(input, "habit_id") && target.oneTimeAction == null)
            }
            CountCommandRecords.Action.LINKED_METRICS -> error("COMMAND_LEGACY_METRIC_INPUT_REQUIRED")
            CountCommandRecords.Action.METRIC_APPEND -> error("COMMAND_METRIC_V2_REQUIRED")
        }
    }

    private fun requireHabit(habit: HabitEntity, identity: CountActionIdentity?) {
        require(habit.habitType == HabitType.COUNTING && habit.appearance == null && habit.completionPolicy != "one_and_done")
        identity?.let { require(it.legacy != null && it.habitId == habit.id && it.habitUuid == habit.uuid &&
            it.plan == SyncV2Mapper.planNode(habit)) }
    }
    private fun JsonObjectBuilder.common(habit: HabitEntity, identity: CountActionIdentity?) {
        put("local_profile", PROFILE); put("habit_uuid", habit.uuid); put("habit_id", habit.id)
        put("identity_plan", identity?.plan ?: JsonNull)
    }
    private suspend fun encode(database: HabitDatabase, access: LocalCoreWriteAccess, action: CountCommandRecords.Action,
        input: JsonObject, capture: CountCommandRecords.Capture = CountCommandRecords.Capture.EXECUTION_INPUT): CountCommandRecords.Intent {
        val namespace = CountCommandInputs.locator(database, access.session, access.capturedDeviceId)
        val bytes = CountCommandRecords.canonical(buildJsonObject {
            put("format", CountCommandRecords.FORMAT); put("command_id", UUID.randomUUID().toString()); put("namespace", namespace.body())
            put("original_auth_generation", access.session.authentication.generation); put("action", action.name)
            put("capture", capture.name); put("input", input)
        }).toByteArray(Charsets.UTF_8)
        return CountCommandRecords.Intent.decode(bytes, bytes.size, {})
    }
}
