package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.withTransaction
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.entity.HabitTypeConverter
import com.dayforge.data.export.ConfigMapper
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.IconReference
import java.time.Instant
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NextStructureMapperTest {
    @get:Rule val storage = PhysicalDatabaseRule()
    private val uuid = "81000000-0000-4000-8000-000000000011"
    private val other = "81000000-0000-4000-8000-000000000012"
    private val created = "2026-09-27T15:59:59.123456Z"
    private val api by lazy {
        val text = InstrumentationRegistry.getInstrumentation().context.assets.open("next/api.json").bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonObject
    }
    private fun task() = JsonObject(api.getValue("task").jsonObject + mapOf(
        "created_at" to JsonPrimitive(created), "sort_order" to JsonPrimitive(37)))
    private fun snapshot(body: JsonObject) = JsonObject(body + mapOf(
        "public_id" to JsonPrimitive(uuid), "revision" to JsonPrimitive(7), "created_at" to JsonPrimitive(created),
        "updated_at" to JsonPrimitive("2026-09-28T00:00:00Z"), "deleted_at" to JsonNull))
    private fun activity(body: JsonObject, vararg fields: Pair<String, JsonElement>) =
        JsonObject(body + ("activity" to JsonObject(body.getValue("activity").jsonObject + fields)))
    private fun recurrence(type: String, extra: String = "") = Json.parseToJsonElement(
        """{"schema_version":1,"type":"$type"$extra}""").jsonObject
    private fun recurring(mode: String = "check", rule: JsonObject = recurrence("daily", ",\"interval\":1,\"start_date\":\"2026-01-15\"")): JsonObject {
        val base = activity(task(), "completion_policy" to JsonPrimitive("recurring"), "tracking_mode" to JsonPrimitive(mode),
            "recurrence_rule" to rule, "timezone" to JsonPrimitive("Pacific/Kiritimati"),
            "target_value" to JsonPrimitive(if (mode == "duration") "120" else "3"),
            "target_unit" to if (mode == "duration") JsonPrimitive("second") else JsonNull,
            "preferred_local_time" to JsonPrimitive("09:30:45.123456"), "target_cycles" to JsonPrimitive(7))
        return JsonObject(base + ("appearance" to Json.parseToJsonElement(
            """{"icon":{"kind":"role","role":"habit.custom"},"accent_color":"#123456","icon_tint":"object"}""")))
    }
    private fun normalized(body: JsonObject): JsonObject {
        // Independent comparison of numeric value, not of two production encoders.
        fun number(value: JsonElement): JsonElement = if (value == JsonNull) value else JsonPrimitive(value.jsonPrimitive.content.toBigDecimal().stripTrailingZeros().toPlainString())
        return if (body["activity"] is JsonObject) activity(body, "target_value" to number(body.getValue("activity").jsonObject.getValue("target_value")))
        else JsonObject(body.mapValues { (key, value) -> if (key in setOf("target_value", "target_value_upper")) number(value) else value })
    }
    private fun reject(body: JsonObject) = assertTrue(runCatching { NextStructureMapper.readPlan(body, uuid, 7) }.isFailure)

    @Test fun onceDueDateRoleAndCapturedTimezoneRoundTripWithoutInferringLegacyTaskFields() {
        val body = activity(task(), "recurrence_rule" to recurrence("once", ",\"due_date\":\"2028-02-29\""))
        val record = NextStructureMapper.readPlan(snapshot(body), uuid, 7)
        assertEquals(HabitSchedule.Once("2028-02-29"), record.schedule)
        assertEquals("one_and_done", record.completionPolicy)
        assertNull(record.targetCycles)
        assertEquals(0, record.oneTimeConfirmedVersion)
        assertEquals("Asia/Shanghai", record.planMetadata!!.timezone)
        assertEquals(created, record.planMetadata!!.creationTimestamp)
        assertEquals(Instant.parse(created).toEpochMilli(), record.createdAt)
        assertEquals(37L, record.planMetadata!!.sortOrder)
        assertEquals(IconReference.Role("task.default"), record.appearance!!.icon)
        assertEquals(normalized(body), normalized(NextStructureMapper.writePlan(record)))
    }

    @Test fun allRecurringSchedulesAndTrackingModesKeepAnchorsUnitsAndSubMinutePreference() {
        val rules = listOf(recurrence("daily", ",\"interval\":1,\"start_date\":\"2026-01-15\""),
            recurrence("weekly", ",\"interval\":1,\"start_date\":null,\"weekdays\":[1,3,7]"),
            recurrence("monthly", ",\"interval\":1,\"start_date\":\"2026-01-15\",\"day_of_month\":31"),
            recurrence("interval", ",\"every_days\":8,\"start_date\":\"2026-01-15\""))
        for (mode in listOf("check", "count", "duration")) for (rule in rules) for (down in listOf(false, true)) {
            if (mode == "check" && down) continue
            val body = activity(recurring(mode, rule), "is_countdown" to JsonPrimitive(down))
            val record = NextStructureMapper.readPlan(snapshot(body), uuid, 7)
            assertEquals(if (mode == "duration") 2 else 3, record.targetValue)
            assertEquals(570L, record.bestTime)
            assertEquals("09:30:45.123456", record.planMetadata!!.preferredLocalTime)
            assertEquals("Pacific/Kiritimati", record.planMetadata!!.timezone)
            assertNull(record.oneTimeConfirmedVersion)
            assertEquals(normalized(body), normalized(NextStructureMapper.writePlan(record)))
        }
    }

    @Test fun differentDeviceTimezoneCannotRewriteAnUneditedPlan() {
        val before = TimeZone.getDefault()
        try {
            val body = recurring("count", recurrence("interval", ",\"every_days\":3,\"start_date\":\"2026-01-01\""))
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val record = NextStructureMapper.readPlan(snapshot(body), uuid, 7)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals(normalized(body), normalized(NextStructureMapper.writePlan(record)))
            val renamed = record.copy(name = "renamed")
            assertEquals(normalized(JsonObject(body + ("title" to JsonPrimitive("renamed")))), normalized(NextStructureMapper.writePlan(renamed)))
        } finally { TimeZone.setDefault(before) }
    }

    @Test fun structuralRefreshKeepsKnownCompletionBaselineAndLocalIdentity() {
        val body = task()
        val first = NextStructureMapper.readPlan(snapshot(body), uuid, 7)
        val prior = first.copy(id = 91, oneTimeConfirmedVersion = 3,
            oneTimeConfirmedHeadEventUuid = other, oneTimeConfirmedCompletionEventUuid = other, activityRate = 83)
        val record = NextStructureMapper.readPlan(snapshot(JsonObject(body + ("title" to JsonPrimitive("edited")))), uuid, 7, prior)
        assertEquals(91L, record.id); assertEquals(83, record.activityRate)
        assertEquals(3, record.oneTimeConfirmedVersion)
        assertEquals(other, record.oneTimeConfirmedCompletionEventUuid)
        assertFalse(NextStructureMapper.writePlan(record).containsKey("one_time_state_after"))
        assertTrue(runCatching { NextStructureMapper.readPlan(snapshot(body), uuid, 7, prior.copy(uuid = other)) }.isFailure)
    }

    @Test fun goalsPreserveDateWindowManualResultAndTopLevelConstraint() {
        for ((result, status) in listOf(null to "active", "succeeded" to "completed", "failed" to "failed")) {
            val goal = Json.parseToJsonElement("""{"start_date":"2026-01-01","due_date":"2026-12-31","target_cycles":9,
                "failure_policy":{"schema_version":1,"type":"strict"},"evaluation_policy":{"schema_version":1,"type":"manual"},
                "manual_result":${result?.let { "\"$it\"" } ?: "null"}}""").jsonObject
            val body = JsonObject(recurring() + mapOf("node_kind" to JsonPrimitive("goal"), "activity" to JsonNull,
                "goal" to goal, "status" to JsonPrimitive(status)))
            val record = NextStructureMapper.readPlan(snapshot(body), uuid, 7)
            assertEquals(HabitType.GOAL, record.habitType)
            assertNull(record.planMetadata!!.timezone)
            assertEquals("2026-12-31", record.planMetadata!!.goalDueDate)
            assertEquals(body, NextStructureMapper.writePlan(record))
            reject(snapshot(JsonObject(body + ("parent_uuid" to JsonPrimitive(other)))))
            reject(snapshot(JsonObject(body + ("goal" to JsonObject(goal + ("due_date" to JsonPrimitive("2025-12-31")))))))
        }
    }

    @Test fun invalidEnvelopeAndLegacyAppearanceCannotBeAcceptedAsNewStructure() {
        val valid = snapshot(task())
        for ((key, value) in listOf("public_id" to JsonPrimitive(other), "revision" to JsonPrimitive(8),
            "revision" to JsonPrimitive("7"), "deleted_at" to JsonPrimitive(created), "created_at" to JsonNull,
            "updated_at" to JsonPrimitive("2026-01-01T12:00:00"), "visibility" to JsonPrimitive("family"),
            "unknown" to JsonPrimitive(true), "icon" to JsonPrimitive("task_alt"))) reject(JsonObject(valid + (key to value)))
        reject(JsonObject(valid - "appearance"))
        reject(JsonObject(valid - "sort_order"))
        reject(JsonObject(valid + ("parent_uuid" to JsonPrimitive(uuid))))
    }

    @Test fun invalidNestedRulesAndOverflowCannotBeNormalizedIntoValidDailyPlans() {
        val body = recurring()
        for (rule in listOf(recurrence("weekly", ",\"interval\":1,\"start_date\":null,\"weekdays\":[4294967297]"),
            recurrence("weekly", ",\"interval\":1,\"start_date\":null,\"weekdays\":[1,1]"),
            recurrence("daily", ",\"interval\":2,\"start_date\":null"),
            recurrence("monthly", ",\"interval\":1,\"start_date\":null,\"day_of_month\":32"),
            recurrence("interval", ",\"every_days\":3,\"start_date\":null"),
            recurrence("daily", ",\"interval\":1,\"start_date\":\"2026-02-29\"")))
            reject(snapshot(activity(body, "recurrence_rule" to rule)))
        for (target in listOf("1.5", "2147483648", "NaN", "-1")) reject(snapshot(activity(body, "target_value" to JsonPrimitive(target))))
        reject(snapshot(activity(recurring("duration"), "target_value" to JsonPrimitive("61"))))
        reject(snapshot(activity(body, "timezone" to JsonPrimitive("not/a-zone"))))
        reject(snapshot(activity(body, "preferred_local_time" to JsonPrimitive("25:00:00"))))
    }

    @Test fun oneTimePolicyAndRoleRestrictionsAreExplicitRatherThanIconInferences() {
        reject(snapshot(activity(task(), "completion_policy" to JsonPrimitive("recurring"))))
        reject(snapshot(activity(task(), "target_cycles" to JsonPrimitive(1))))
        reject(snapshot(activity(task(), "is_countdown" to JsonPrimitive("false"))))
        reject(snapshot(activity(task(), "origin_assignment_id" to JsonPrimitive(other))))
        val generalIcon = recurring().getValue("appearance")
        reject(snapshot(JsonObject(task() + ("appearance" to generalIcon))))
        val recurringTaskIcon = JsonObject(recurring() + ("appearance" to task().getValue("appearance")))
        reject(snapshot(recurringTaskIcon))
    }

    private fun metric(aggregation: String) = Json.parseToJsonElement("""{
        "name":"weight","description":"range","unit":"kg","decimal_places":3,"aggregation_type":"$aggregation",
        "target_direction":"range","target_value":"10.125","target_value_upper":"20.5","status":"archived",
        "appearance":{"icon":{"kind":"asset","asset_id":"$other"},"accent_color":"#123456","icon_tint":"object"}}""").jsonObject

    @Test fun metricAggregationsRangeAndFixedReferenceRoundTrip() {
        for (aggregation in listOf("average", "sum", "by_time")) {
            val body = metric(aggregation)
            val record = NextStructureMapper.readMetric(snapshot(body), uuid, 7)
            assertEquals(aggregation, record.aggregationType); assertFalse(record.isActive)
            assertEquals(IconReference.Asset(other), record.appearance!!.icon)
            assertEquals(normalized(body), normalized(NextStructureMapper.writeMetric(record)))
        }
    }

    @Test fun metricCannotRoundAwayOutOfRangePrecisionOrAcceptInvalidStructure() {
        val body = metric("average")
        for ((key, value) in listOf("target_value" to JsonPrimitive("9007199254740993"),
            "target_value" to JsonPrimitive("1e400"), "target_value_upper" to JsonPrimitive("1"),
            "decimal_places" to JsonPrimitive(7), "aggregation_type" to JsonPrimitive("median"),
            "appearance" to task().getValue("appearance"))) {
            assertTrue(runCatching { NextStructureMapper.readMetric(snapshot(JsonObject(body + (key to value))), uuid, 7) }.isFailure)
        }
        val valid = NextStructureMapper.readMetric(snapshot(body), uuid, 7)
        assertTrue(runCatching { NextStructureMapper.writeMetric(valid.copy(targetValue = Double.NaN)) }.isFailure)
    }

    @Test fun inconsistentLocalMetadataMustBeUpdatedExplicitlyBeforeWriting() {
        val record = NextStructureMapper.readPlan(snapshot(recurring()), uuid, 7)
        assertTrue(runCatching { NextStructureMapper.writePlan(record.copy(bestTime = 580)) }.isFailure)
        assertTrue(runCatching { NextStructureMapper.writePlan(record.copy(createdAt = 0)) }.isFailure)
        assertTrue(runCatching { NextStructureMapper.writePlan(record.copy(oneTimeConfirmedVersion = 0)) }.isFailure)
        val changed = record.copy(bestTime = 580, planMetadata = record.planMetadata!!.copy(preferredLocalTime = "09:40:00"))
        assertEquals(JsonPrimitive("09:40:00"), NextStructureMapper.writePlan(changed).getValue("activity").jsonObject["preferred_local_time"])
    }

    @Test fun planningMetadataSurvivesRoomReopenEditOutboxRollbackAndAccountClear() = runBlocking {
        val body = activity(recurring("count"), "preferred_local_time" to JsonPrimitive("09:30:45.123456+08:00"),
            "origin_assignment_id" to JsonPrimitive(other), "target_unit" to JsonPrimitive("steps"))
        val initial = NextStructureMapper.readPlan(snapshot(body), uuid, 7)
        val id = storage.database.habitDao().insert(initial)
        storage.reopen()
        val row = storage.database.habitDao().getHabitById(id)!!
        assertEquals(initial.copy(id = id), row)
        assertEquals(normalized(body), normalized(NextStructureMapper.writePlan(row)))
        assertEquals(1, storage.database.syncOutboxDao().count())
        val changed = row.copy(planMetadata = row.planMetadata!!.copy(sortOrder = 72, startDate = "2026-02-01"))
        storage.database.habitDao().updateForSync(changed)
        assertEquals(2, storage.database.syncOutboxDao().count())
        storage.database.habitDao().updateForSync(changed)
        assertEquals(2, storage.database.syncOutboxDao().count())
        val queue = storage.database.syncOutboxDao().getAll()
        assertTrue(runCatching { storage.database.withTransaction {
            storage.database.habitDao().updateForSync(changed.copy(planMetadata = changed.planMetadata!!.copy(timezone = "Asia/Tokyo")))
            assertEquals(3, storage.database.syncOutboxDao().count())
            error("synthetic rollback")
        } }.isFailure)
        storage.reopen()
        assertEquals(changed, storage.database.habitDao().getHabitById(id))
        assertEquals(queue, storage.database.syncOutboxDao().getAll())
        val written = NextStructureMapper.writePlan(storage.database.habitDao().getHabitById(id)!!)
        assertEquals(JsonPrimitive(72), written["sort_order"])
        assertEquals(JsonPrimitive("2026-02-01"), written.getValue("activity").jsonObject.getValue("recurrence_rule").jsonObject["start_date"])
        storage.database.clearAllData()
        storage.reopen()
        assertTrue(storage.database.habitDao().getAllHabitsOnce().isEmpty())
        assertEquals(0, storage.database.syncOutboxDao().count())
    }

    @Test fun corruptPlanningMetadataFailsStrictlyAndCannotBeDroppedByLegacyExport() {
        val record = NextStructureMapper.readPlan(snapshot(recurring()), uuid, 7)
        val codec = HabitTypeConverter()
        val encoded = codec.fromPlanMetadata(record.planMetadata)!!
        assertEquals(record.planMetadata, codec.toPlanMetadata(encoded))
        assertNull(codec.toPlanMetadata(null)); assertNull(codec.fromPlanMetadata(null))
        for (invalid in listOf("null", "{}", "[]", encoded.dropLast(1) + ",\"unknown\":true}",
            encoded.replace("Pacific/Kiritimati", "invalid/zone"), encoded.replace("2026-01-15", "2026-02-29"),
            encoded.replace("09:30:45.123456", "25:00:00"))) {
            assertTrue(invalid, runCatching { codec.toPlanMetadata(invalid) }.isFailure)
        }
        val onlyMetadata = record.copy(appearance = null, completionPolicy = null)
        assertTrue(runCatching { ConfigMapper.habitEntityToDto(onlyMetadata) }.isFailure)
        assertTrue(runCatching { SyncV2Mapper.planNode(onlyMetadata) }.exceptionOrNull() is ProtocolNextDataRequiresUpgradeException)
    }
}
