package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import java.time.Instant
import java.util.TimeZone
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NextCommonFactMapperTest {
    @Test fun countPolicyIsStrictAndCannotHideOnOtherFactTypes() {
        val policy = buildJsonObject { put("target_value", 10); put("is_countdown", true) }
        val counted = mutate(event("count_snapshot", JsonPrimitive(6)), "count_policy" to policy)
        assertEquals(6, NextCommonFactMapper.completion(counted, habit(HabitType.COUNTING)).value)
        assertEquals(com.dayforge.domain.model.CountDayPolicy(10, true), NextCommonFactMapper.countPolicy(counted.payload))
        assertNull(NextCommonFactMapper.countPolicy(event("count_snapshot").payload))
        for (bad in listOf(JsonNull, JsonPrimitive("policy"),
            buildJsonObject { put("target_value", "10"); put("is_countdown", true) },
            buildJsonObject { put("target_value", 10.0); put("is_countdown", true) },
            buildJsonObject { put("target_value", 10); put("is_countdown", 1) },
            buildJsonObject { put("target_value", 0); put("is_countdown", false) },
            buildJsonObject { put("target_value", 10); put("is_countdown", false); put("extra", 1) }))
            assertTrue(runCatching { NextCommonFactMapper.completion(mutate(counted, "count_policy" to bad), habit(HabitType.COUNTING)) }.isFailure)
        for (other in listOf(event(), event("revert", JsonNull), duration()))
            assertTrue(runCatching { NextCommonFactMapper.countPolicy(mutate(other, "count_policy" to policy).payload) }.isFailure)
    }

    @Test fun countReceiptMustMatchOriginalRuleNotTodaysPlanOrAnAbsentLegacyProof() {
        val policy = buildJsonObject { put("target_value", 10); put("is_countdown", false) }
        val counted = mutate(event("count_delta", JsonPrimitive(6)), "count_policy" to policy)
        val common = setOf("activity_uuid", "event_type", "value", "occurred_at", "local_date", "timezone", "note", "source_type",
            "source_device_id", "external_event_id", "metadata", "count_policy")
        val request = JsonObject(counted.payload.filterKeys { it in common })
        NextCommonFactProof.requireOriginalPayload(request, counted, device)
        assertTrue(runCatching { NextCommonFactProof.requireOriginalPayload(JsonObject(request - "count_policy"), counted, device) }.isFailure)
        val changed = mutate(counted, "count_policy" to buildJsonObject { put("target_value", 5); put("is_countdown", false) })
        assertTrue(runCatching { NextCommonFactProof.requireOriginalPayload(request, changed, device) }.isFailure)
    }
    private val id = "83000000-0000-4000-8000-000000000001"
    private val activity = "83000000-0000-4000-8000-000000000002"
    private val metricId = "83000000-0000-4000-8000-000000000003"
    private val device = "83000000-0000-4000-8000-000000000004"
    private val target = "83000000-0000-4000-8000-000000000005"
    private val stamp = "2026-09-27T16:00:01.123Z"
    private fun habit(type: HabitType = HabitType.CHECK_IN) = HabitEntity(id = 7, uuid = activity, name = "habit",
        habitType = type, iconResId = 0, colorHex = "#000000", schedule = HabitSchedule.Daily, completionPolicy = "recurring")
    private fun metric() = MetricEntity(id = 9, uuid = metricId, name = "metric", unit = "kg", iconResId = 0, colorHex = "#000000")
    private fun header() = buildJsonObject {
        put("public_id", id); put("revision", 1); put("created_at", stamp); put("updated_at", stamp); put("deleted_at", JsonNull)
    }
    private fun source(occurred: String = stamp, day: String = "2026-09-28", zone: String = "Asia/Shanghai") = buildJsonObject {
        put("occurred_at", occurred); put("local_date", day); put("timezone", zone); put("received_at", stamp)
        put("note", "record note"); put("source_type", "app"); put("source_device_id", device)
        put("external_event_id", JsonNull); put("metadata", buildJsonObject { put("capture", "original") })
    }
    private fun event(kind: String = "check_in", value: JsonElement = JsonPrimitive("1")) = change("activity_event", JsonObject(
        header() + source() + buildJsonObject {
            put("activity_uuid", activity); put("event_type", kind); put("value", value)
            put("reverts_event_uuid", if (kind == "revert") JsonPrimitive(target) else JsonNull)
            listOf("duration_seconds", "duration_milliseconds", "started_at", "ended_at").forEach { put(it, JsonNull) }
        }))
    private fun change(type: String, body: JsonObject) = SyncV2Change(0, type, id, "upsert", 1, body, stamp)
    private fun mutate(row: SyncV2Change, vararg fields: Pair<String, JsonElement>) = row.copy(payload = JsonObject(row.payload + fields))
    private fun observation() = change("metric_observation", JsonObject(header() + source() + mapOf(
        "metric_uuid" to JsonPrimitive(metricId), "value" to JsonPrimitive("12.125"), "unit" to JsonPrimitive("kg"))))
    private fun link() = change("activity_metric_link", JsonObject(header() + mapOf(
        "activity_uuid" to JsonPrimitive(activity), "metric_uuid" to JsonPrimitive(metricId), "coefficient" to JsonPrimitive("1.25"),
        "show_in_activity_detail" to JsonPrimitive(true), "prompt_on_complete" to JsonPrimitive(true), "is_active" to JsonPrimitive(false))))
    private fun duration() = mutate(event("duration_session", JsonNull),
        "started_at" to JsonPrimitive("2026-09-27T15:59:30Z"), "ended_at" to JsonPrimitive("2026-09-27T16:00:30Z"),
        "occurred_at" to JsonPrimitive("2026-09-27T16:00:30Z"), "local_date" to JsonPrimitive("2026-09-27"),
        "duration_seconds" to JsonPrimitive(60), "duration_milliseconds" to JsonPrimitive(60_000),
        "day_allocations" to Json.parseToJsonElement("""[
            {"local_date":"2026-09-27","timezone":"Asia/Shanghai","duration_milliseconds":30000},
            {"local_date":"2026-09-28","timezone":"Asia/Shanghai","duration_milliseconds":30000}]"""))

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test fun opaqueFactHeadersDurationAndAllocationIntegersCannotBeCoerced() {
        val cases: List<Pair<SyncV2Change, (SyncV2Change) -> Any>> = listOf(
            event() to { row -> NextCommonFactMapper.completion(row, habit()) },
            event("revert", JsonNull) to { row -> NextCommonFactMapper.revert(row, habit()) },
            duration() to { row -> NextCommonFactMapper.duration(row, habit(HabitType.TIMER)) },
            observation() to { row -> NextCommonFactMapper.observation(row, metric()) },
            link() to { row -> NextCommonFactMapper.link(row, habit(), metric()) })
        for ((source, decode) in cases) {
            assertNotNull(decode(source))
            for (token in listOf(JsonUnquotedLiteral("1e0"), JsonUnquotedLiteral("1.0"), JsonPrimitive("1"), JsonPrimitive(true))) {
                val raw = Json.parseToJsonElement(mutate(source, "revision" to token).payload.toString()).jsonObject
                assertThrows(source.entityType, IllegalArgumentException::class.java) { decode(source.copy(payload = raw)) }
            }
            for (revision in listOf(9_007_199_254_740_993L, Long.MAX_VALUE)) {
                assertEquals(decode(source), decode(mutate(source, "revision" to JsonUnquotedLiteral(revision.toString())).copy(revision = revision)))
            }
        }
        val timer = duration()
        val baseline = NextCommonFactMapper.duration(timer, habit(HabitType.TIMER))
        for (key in listOf("duration_seconds", "duration_milliseconds")) {
            val original = timer.payload.getValue(key).jsonPrimitive.content
            val changed = mutate(timer, key to JsonUnquotedLiteral("${original}e0"))
            assertThrows(key, IllegalArgumentException::class.java) { NextCommonFactMapper.duration(changed, habit(HabitType.TIMER)) }
            val ancillary = mutate(event(), key to JsonUnquotedLiteral("${original}e0"))
            assertThrows(key, IllegalArgumentException::class.java) { NextCommonFactMapper.completion(ancillary, habit()) }
        }
        val days = timer.payload.getValue("day_allocations").jsonArray
        for (index in days.indices) {
            val changed = JsonArray(days.mapIndexed { position, day -> if (position != index) day else
                JsonObject(day.jsonObject + ("duration_milliseconds" to JsonUnquotedLiteral("30000e0"))) })
            assertThrows(IllegalArgumentException::class.java) {
                NextCommonFactMapper.duration(mutate(timer, "day_allocations" to changed), habit(HabitType.TIMER))
            }
        }
        assertEquals(baseline, NextCommonFactMapper.duration(timer, habit(HabitType.TIMER)))
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test fun legitimateFactDecimalExponentsRetainCountsObservationsAndLinkCoefficients() {
        for (kind in listOf("count_delta", "count_snapshot")) {
            assertEquals(NextCommonFactMapper.completion(event(kind, JsonPrimitive("3")), habit(HabitType.COUNTING)),
                NextCommonFactMapper.completion(event(kind, JsonUnquotedLiteral("3e0")), habit(HabitType.COUNTING)))
        }
        assertEquals(NextCommonFactMapper.observation(observation(), metric()),
            NextCommonFactMapper.observation(mutate(observation(), "value" to JsonUnquotedLiteral("1.2125e1")), metric()))
        assertEquals(NextCommonFactMapper.link(link(), habit(), metric()),
            NextCommonFactMapper.link(mutate(link(), "coefficient" to JsonUnquotedLiteral("1.25e0")), habit(), metric()))
    }

    @Test fun ordinaryChecksAndBothCountEventKindsKeepValuesAndCapturedDay() {
        assertEquals(1, NextCommonFactMapper.completion(event(), habit()).value)
        assertEquals(1, NextCommonFactMapper.completion(event(value = JsonNull), habit()).value)
        for (kind in listOf("count_delta", "count_snapshot")) for (value in listOf(Int.MIN_VALUE, -3, 0, 12, Int.MAX_VALUE)) {
            val row = NextCommonFactMapper.completion(event(kind, JsonPrimitive(value)), habit(HabitType.COUNTING))
            assertEquals(value, row.value); assertEquals("2026-09-28", row.recordedLocalDate)
            assertEquals("Asia/Shanghai", row.recordedTimezone); assertEquals(Instant.parse(stamp).toEpochMilli(), row.actualCompletedAt)
            assertNull(row.oneTimeAction)
        }
    }

    @Test fun deviceTimezoneChangesCannotAlterAnyFactProjection() {
        val old = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val first = NextCommonFactMapper.completion(event(), habit())
            val firstMetric = NextCommonFactMapper.observation(observation(), metric())
            val firstTimer = NextCommonFactMapper.duration(duration(), habit(HabitType.TIMER))
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            assertEquals(first, NextCommonFactMapper.completion(event(), habit()))
            assertEquals(firstMetric, NextCommonFactMapper.observation(observation(), metric()))
            assertEquals(firstTimer, NextCommonFactMapper.duration(duration(), habit(HabitType.TIMER)))
        } finally { TimeZone.setDefault(old) }
    }

    @Test fun malformedHeadersUnknownFieldsAndOutOfRangeCountsNeverBecomeDefaults() {
        for ((key, value) in listOf("public_id" to JsonPrimitive(target), "revision" to JsonPrimitive("1"),
            "revision" to JsonPrimitive(2), "deleted_at" to JsonPrimitive(stamp), "created_at" to JsonPrimitive("invalid"),
            "activity_uuid" to JsonPrimitive(target), "extra" to JsonPrimitive(true))) {
            assertTrue(runCatching { NextCommonFactMapper.completion(mutate(event(), key to value), habit()) }.isFailure)
        }
        for (value in listOf("1.5", "2147483648", "-2147483649", "NaN", "1e400"))
            assertTrue(runCatching { NextCommonFactMapper.completion(event("count_delta", JsonPrimitive(value)), habit()) }.isFailure)
        assertTrue(runCatching { NextCommonFactMapper.completion(event(value = JsonPrimitive(2)), habit()) }.isFailure)
        assertTrue(runCatching { NextCommonFactMapper.completion(event().copy(revision = 0), habit()) }.isFailure)
        assertTrue(runCatching { NextCommonFactMapper.completion(event().copy(operation = "delete"), habit()) }.isFailure)
    }

    @Test fun onceProofsAndInvalidSourceTimeCannotLeakIntoRecurringFactStorage() {
        assertTrue(runCatching { NextCommonFactMapper.completion(event(), habit().copy(completionPolicy = "one_and_done")) }.isFailure)
        assertTrue(runCatching { NextCommonFactMapper.completion(event(), habit().copy(schedule = HabitSchedule.Once())) }.isFailure)
        for ((key, value) in listOf("one_time" to buildJsonObject {}, "one_time_state_after" to buildJsonObject {},
            "local_date" to JsonPrimitive("2026-09-27"), "timezone" to JsonPrimitive("+08:00"),
            "source_device_id" to JsonPrimitive("invalid"), "source_type" to JsonPrimitive("unknown"),
            "metadata" to JsonNull, "duration_seconds" to JsonPrimitive(-1)))
            assertTrue(runCatching { NextCommonFactMapper.completion(mutate(event(), key to value), habit()) }.isFailure)
        assertTrue(runCatching { NextCommonFactMapper.completion(mutate(event(), "source_type" to JsonPrimitive("automation")), habit()) }.isFailure)
        assertEquals(1, NextCommonFactMapper.completion(mutate(event(), "source_type" to JsonPrimitive("automation"),
            "external_event_id" to JsonPrimitive("capture-1")), habit()).value)
        assertEquals(1, NextCommonFactMapper.completion(mutate(event(), "one_time" to JsonNull, "one_time_state_after" to JsonNull), habit()).value)
    }

    @Test fun historicalTrackingModeDoesNotHaveToMatchTheCurrentEditableMode() {
        val historical = event("count_delta", JsonPrimitive(5))
        assertEquals(5, NextCommonFactMapper.completion(historical, habit(HabitType.TIMER)).value)
        assertEquals(60, NextCommonFactMapper.duration(duration(), habit()).session.durationSeconds)
        assertTrue(runCatching { NextCommonFactMapper.completion(historical, habit(HabitType.GOAL)) }.isFailure)
    }

    @Test fun revertPreservesTargetIdentityWithoutMutatingOrProjectingACheck() {
        val undo = event("revert", JsonNull)
        assertEquals(target, NextCommonFactMapper.revert(undo, habit()))
        assertTrue(runCatching { NextCommonFactMapper.completion(undo, habit()) }.isFailure)
        assertTrue(runCatching { NextCommonFactMapper.revert(mutate(undo, "reverts_event_uuid" to JsonPrimitive(id)), habit()) }.isFailure)
        assertEquals(target, NextCommonFactMapper.revert(mutate(undo, "value" to JsonPrimitive(1)), habit()))
    }

    @Test fun acceptedAncillaryFieldsNeverTurnOrdinaryFactsIntoTimersOrChangeTheirDay() {
        val row = mutate(event(), "started_at" to JsonPrimitive("2026-09-26T15:59:30.123456Z"),
            "ended_at" to JsonPrimitive("2026-09-26T16:00:30.123456Z"),
            "duration_seconds" to JsonPrimitive(60), "duration_milliseconds" to JsonPrimitive(60_000))
        val result = NextCommonFactMapper.completion(row, habit())
        assertEquals("2026-09-28", result.recordedLocalDate)
        assertEquals(Instant.parse(stamp).toEpochMilli(), result.actualCompletedAt)
        assertEquals(1, result.value)
        assertEquals(JsonPrimitive("2026-09-26T15:59:30.123456Z"), row.payload["started_at"])
        assertTrue(runCatching { NextCommonFactMapper.duration(row, habit()) }.isFailure)
    }

    @Test fun completedTimerKeepsCrossMidnightAllocationsAndDoesNotBecomeRunning() {
        val result = NextCommonFactMapper.duration(duration(), habit(HabitType.TIMER))
        assertEquals(60_000L, result.session.timerActiveElapsedMillis)
        assertEquals("Asia/Shanghai", result.session.timerTimezone)
        assertFalse(result.session.isPaused); assertNotNull(result.session.endTime)
        assertNull(result.session.timerElapsedRealtimeAnchor); assertNull(result.session.timerBootCount)
        assertEquals(listOf("2026-09-27", "2026-09-28"), result.allocations.map { it.localDate })
        assertEquals(listOf(30_000L, 30_000L), result.allocations.map { it.durationMillis })
        assertEquals(result.session.timerActiveElapsedMillis, result.allocations.sumOf { it.durationMillis })
        val previous = result.session.copy(id = 77, timerControlGeneration = 3, timerNextCommandSequence = 4, accumulatedPauseMillis = 2_000)
        val again = NextCommonFactMapper.duration(duration(), habit(HabitType.TIMER), previous).session
        assertEquals(77L, again.id); assertEquals(3, again.timerControlGeneration); assertEquals(2_000L, again.accumulatedPauseMillis)
    }

    @Test fun microsecondMidnightCarryPreservesExactTotalWithAndWithoutPauses() {
        for (end in listOf("2026-09-27T16:00:30.000500Z", "2026-09-27T16:01:30.000500Z")) {
            val row = mutate(duration(), "started_at" to JsonPrimitive("2026-09-27T15:59:30.000500Z"),
                "ended_at" to JsonPrimitive(end), "occurred_at" to JsonPrimitive(end),
                "day_allocations" to Json.parseToJsonElement("""[
                    {"local_date":"2026-09-27","timezone":"Asia/Shanghai","duration_milliseconds":29999},
                    {"local_date":"2026-09-28","timezone":"Asia/Shanghai","duration_milliseconds":30001}]"""))
            val result = NextCommonFactMapper.duration(row, habit(HabitType.TIMER))
            assertEquals(listOf(29_999L, 30_001L), result.allocations.map { it.durationMillis })
            assertEquals(60_000L, result.allocations.sumOf { it.durationMillis })
            assertEquals(60_000L, result.session.timerActiveElapsedMillis)
            assertEquals(JsonPrimitive("2026-09-27T15:59:30.000500Z"), row.payload["started_at"])
            val days = row.payload.getValue("day_allocations").jsonArray
            val short = JsonArray(listOf(days.first(), JsonObject(days.last().jsonObject +
                ("duration_milliseconds" to JsonPrimitive(30_000)))))
            assertTrue(runCatching { NextCommonFactMapper.duration(mutate(row, "day_allocations" to short), habit(HabitType.TIMER)) }.isFailure)
        }
    }

    @Test fun monotonicServerElapsedIsNotRejectedByTheStopCommandWallClock() {
        val row = mutate(duration(), "started_at" to JsonPrimitive("2026-09-27T15:59:30.000500Z"),
            "ended_at" to JsonPrimitive("2026-09-27T15:59:30.001000Z"),
            "occurred_at" to JsonPrimitive("2026-09-27T15:59:30.001000Z"),
            "day_allocations" to Json.parseToJsonElement("""[
                {"local_date":"2026-09-27","timezone":"Asia/Shanghai","duration_milliseconds":29999},
                {"local_date":"2026-09-28","timezone":"Asia/Shanghai","duration_milliseconds":30001}]"""))
        val result = NextCommonFactMapper.duration(row, habit(HabitType.TIMER))
        assertEquals(60_000L, result.session.timerActiveElapsedMillis)
        assertEquals(60_000L, result.allocations.sumOf { it.durationMillis })
        assertEquals(Instant.parse("2026-09-27T15:59:30.001000Z").toEpochMilli(), result.session.endTime)
        val days = row.payload.getValue("day_allocations").jsonArray
        val impossibleDay = JsonArray(listOf(days.first(), JsonObject(days.last().jsonObject +
            ("local_date" to JsonPrimitive("2026-09-29")))))
        assertTrue(runCatching { NextCommonFactMapper.duration(mutate(row, "day_allocations" to impossibleDay), habit(HabitType.TIMER)) }.isFailure)
    }

    @Test fun daylightSavingDayCapacityUsesCapturedZoneNotTwentyFourHourAssumption() {
        for ((start, end, day, active) in listOf(
            listOf("2026-03-08T05:00:00Z", "2026-03-09T04:00:00Z", "2026-03-08", "82800000"),
            listOf("2026-11-01T04:00:00Z", "2026-11-02T05:00:00Z", "2026-11-01", "86400000")
        )) {
            val millis = active.toLong()
            val row = mutate(duration(), "started_at" to JsonPrimitive(start), "ended_at" to JsonPrimitive(end),
                "occurred_at" to JsonPrimitive(end), "timezone" to JsonPrimitive("America/New_York"),
                "local_date" to JsonPrimitive(day), "duration_seconds" to JsonPrimitive(millis / 1000),
                "duration_milliseconds" to JsonPrimitive(millis), "day_allocations" to buildJsonArray {
                    add(buildJsonObject { put("local_date", day); put("timezone", "America/New_York"); put("duration_milliseconds", millis) })
                })
            assertEquals(millis, NextCommonFactMapper.duration(row, habit(HabitType.TIMER)).allocations.single().durationMillis)
            if (day == "2026-03-08") {
                val excess = mutate(row, "duration_milliseconds" to JsonPrimitive(millis + 1),
                    "day_allocations" to buildJsonArray {
                        add(buildJsonObject { put("local_date", day); put("timezone", "America/New_York"); put("duration_milliseconds", millis + 1) })
                    })
                assertTrue(runCatching { NextCommonFactMapper.duration(excess, habit(HabitType.TIMER)) }.isFailure)
            }
        }
    }

    @Test fun inconsistentIncompleteOrManufacturedTimerIntervalsAreRejected() {
        for ((key, value) in listOf("started_at" to JsonNull, "ended_at" to JsonNull, "duration_seconds" to JsonPrimitive(61),
            "duration_milliseconds" to JsonPrimitive(60_001), "duration_milliseconds" to JsonPrimitive(86_400_001),
            "ended_at" to JsonPrimitive("2026-09-27T15:00:00Z"), "value" to JsonPrimitive(1),
            "local_date" to JsonPrimitive("2026-09-28"), "occurred_at" to JsonPrimitive("2026-09-27T16:00:31Z")))
            assertTrue(runCatching { NextCommonFactMapper.duration(mutate(duration(), key to value), habit(HabitType.TIMER)) }.isFailure)
        assertTrue(runCatching { NextCommonFactMapper.duration(duration().copy(payload = JsonObject(duration().payload - "day_allocations")), habit(HabitType.TIMER)) }.isFailure)
    }

    @Test fun allocationDuplicatesWrongDaysZonesAndWrongTotalsAreRejected() {
        val original = duration().payload.getValue("day_allocations").jsonArray
        val first = original.first().jsonObject
        for (allocations in listOf(JsonArray(emptyList()), JsonArray(listOf(first, first)),
            JsonArray(listOf(JsonObject(first + ("duration_milliseconds" to JsonPrimitive(29_999))), original.last())),
            JsonArray(listOf(JsonObject(first + ("timezone" to JsonPrimitive("UTC"))), original.last())),
            JsonArray(listOf(JsonObject(first + ("local_date" to JsonPrimitive("2026-09-26"))), original.last()))))
            assertTrue(runCatching { NextCommonFactMapper.duration(mutate(duration(), "day_allocations" to allocations), habit(HabitType.TIMER)) }.isFailure)
    }

    @Test fun observationsRetainExactDisplayTimeHistoricalUnitAndNote() {
        val body = observation()
        val row = NextCommonFactMapper.observation(body, metric().copy(unit = "g"))
        assertEquals(Instant.parse(stamp).toEpochMilli(), row.date); assertEquals("kg", row.unit)
        assertEquals("record note", row.note); assertEquals(12.125, row.value, 0.0)
        val previous = row.copy(id = 8, timeMetadataSource = "captured")
        assertEquals(previous, NextCommonFactMapper.observation(body, metric(), previous))
        assertEquals(" ", NextCommonFactMapper.observation(mutate(body, "unit" to JsonPrimitive(" ")), metric()).unit)
        for (value in listOf("NaN", "Infinity", "1e400", "9007199254740993"))
            assertTrue(runCatching { NextCommonFactMapper.observation(mutate(body, "value" to JsonPrimitive(value)), metric()) }.isFailure)
        assertTrue(runCatching { NextCommonFactMapper.observation(body, metric().copy(uuid = target)) }.isFailure)
    }

    @Test fun linksRetainAllPresentationAndPromptFlagsAndNeverChangeEndpoints() {
        val row = NextCommonFactMapper.link(link(), habit(), metric())
        assertEquals(1.25, row.coefficient, 0.0); assertTrue(row.showInHabitDetail); assertTrue(row.promptOnComplete); assertFalse(row.isActive)
        assertEquals(row.copy(id = 44), NextCommonFactMapper.link(link(), habit(), metric(), row.copy(id = 44)))
        assertTrue(runCatching { NextCommonFactMapper.link(link(), habit(HabitType.GOAL), metric()) }.isFailure)
        assertTrue(runCatching { NextCommonFactMapper.link(link(), habit(), metric(), row.copy(metricId = 55)) }.isFailure)
        assertTrue(runCatching { NextCommonFactMapper.link(mutate(link(), "prompt_on_complete" to JsonPrimitive("true")), habit(), metric()) }.isFailure)
        assertTrue(runCatching { NextCommonFactMapper.link(mutate(link(), "coefficient" to JsonPrimitive("1e400")), habit(), metric()) }.isFailure)
    }
}
