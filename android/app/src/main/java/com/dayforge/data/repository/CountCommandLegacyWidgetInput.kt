package com.dayforge.data.repository

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import java.util.UUID
import kotlinx.serialization.json.*

/** Complete original Widget execution input. Clock and target lookup remain at the original
 * T1 selection point; the rendered target UUID can never be replaced by a later latest row.
 * No server payload/time is fabricated for a no-effect decision.
 */
internal object CountCommandLegacyWidgetInput {
    const val PROFILE = "legacy-v4-widget/v1"
    fun matches(intent: CountCommandRecords.Intent) =
        intent.body().getValue("input").jsonObject["local_profile"] == JsonPrimitive(PROFILE)
    fun claim(input: JsonObject) = LegacyWidgetFactClaim.decode(CountCommandRecords.canonical(input.getValue("widget_fact")))

    suspend fun create(database: HabitDatabase, access: LocalCoreWriteAccess, habit: HabitEntity,
        source: LegacyWidgetFactClaim, commandId: String): CountCommandRecords.Intent {
        source.validate()
        require(com.dayforge.domain.model.isContractUuid(commandId) && habit.habitType == HabitType.COUNTING &&
            habit.appearance == null && source.action in setOf("increment", "decrement") &&
            source.identity.habitId == habit.id && source.identity.habitUuid == habit.uuid &&
            source.identity.plan == SyncV2Mapper.planNode(habit)) { "COMMAND_LEGACY_WIDGET_INPUT_CHANGED" }
        require(source.identity.session() == access.session && source.identity.deviceId == access.capturedDeviceId)
        val added = source.action == "increment" || habit.isCountdown
        val namespace = CountCommandInputs.locator(database, access.session, access.capturedDeviceId)
        val input = buildJsonObject {
            put("local_profile", PROFILE); put("habit_id", habit.id); put("habit_uuid", habit.uuid)
            put("identity_plan", source.identity.plan); put("widget_fact", Json.parseToJsonElement(source.encode()))
            put("clock_policy", "widget-original-t1-clock/v1"); put("target_policy", "widget-rendered-last-uuid/v1")
            put("reserved_fact_uuid", if (added) JsonPrimitive(UUID.randomUUID().toString()) else JsonNull)
            put("target_fact_uuid", if (!added) source.completionUuid?.let(::JsonPrimitive) ?: JsonNull else JsonNull)
        }
        val bytes = CountCommandRecords.canonical(buildJsonObject {
            put("format", CountCommandRecords.FORMAT); put("command_id", commandId); put("namespace", namespace.body())
            put("original_auth_generation", access.session.authentication.generation)
            put("action", if (added) CountCommandRecords.Action.COUNT_ADD.name else CountCommandRecords.Action.COUNT_UNDO.name)
            put("capture", CountCommandRecords.Capture.EXECUTION_INPUT.name); put("input", input)
        }).toByteArray(Charsets.UTF_8)
        return CountCommandRecords.Intent.decode(bytes, bytes.size, {})
    }

    fun validate(action: CountCommandRecords.Action, capture: CountCommandRecords.Capture, input: JsonObject) {
        require(capture == CountCommandRecords.Capture.EXECUTION_INPUT && action != CountCommandRecords.Action.LINKED_METRICS)
        CountCommandRecords.fields(input, "local_profile", "habit_id", "habit_uuid", "identity_plan", "widget_fact",
            "clock_policy", "target_policy", "reserved_fact_uuid", "target_fact_uuid")
        require(CountCommandRecords.text(input, "local_profile") == PROFILE &&
            CountCommandRecords.text(input, "clock_policy") == "widget-original-t1-clock/v1" &&
            CountCommandRecords.text(input, "target_policy") == "widget-rendered-last-uuid/v1")
        val source = claim(input)
        require(source.action in setOf("increment", "decrement") && source.identity.habitId == CountCommandRecords.integer(input, "habit_id") &&
            source.identity.habitUuid == CountCommandRecords.uuid(input, "habit_uuid") && source.identity.plan == input.getValue("identity_plan"))
        if (action == CountCommandRecords.Action.COUNT_ADD) {
            CountCommandRecords.uuid(input, "reserved_fact_uuid"); require(input.getValue("target_fact_uuid") == JsonNull)
        } else {
            require(source.action == "decrement" && input.getValue("reserved_fact_uuid") == JsonNull &&
                input.getValue("target_fact_uuid") == (source.completionUuid?.let(::JsonPrimitive) ?: JsonNull))
        }
    }
}
