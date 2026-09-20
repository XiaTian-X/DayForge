package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Test

@RunWith(AndroidJUnit4::class)
class SyncV2MapperTest {
    @Test
    fun captured_dates_survive_DST_folds_negative_zones_and_later_edits() {
        val originalZone = TimeZone.getDefault()
        try {
            for ((captureZone, instantText, date) in listOf(
                Triple("America/New_York", "2026-11-01T05:30:00Z", "2026-11-01"),
                Triple("America/New_York", "2026-11-01T06:30:00Z", "2026-11-01"),
                Triple("America/New_York", "2026-03-08T07:30:00Z", "2026-03-08"),
                Triple("America/Los_Angeles", "2026-01-01T07:30:00Z", "2025-12-31")
            )) {
                TimeZone.setDefault(TimeZone.getTimeZone(captureZone))
                val instant = Instant.parse(instantText).toEpochMilli()
                val completion = CompletionEntity(habitId = 1, date = instant, actualCompletedAt = instant)
                val observation = MetricLogEntity(metricId = 1, date = instant, value = 1.0, unit = "kg")
                TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
                val payloads = listOf(SyncV2Mapper.completion(completion.copy(value = 2), habit(HabitType.COUNTING)),
                    SyncV2Mapper.metricObservation(observation.copy(value = 2.0, note = "Edited"), "metric"))
                payloads.forEach {
                    assertEquals(captureZone, it.getValue("timezone").jsonPrimitive.content)
                    assertEquals(date, it.getValue("local_date").jsonPrimitive.content)
                    assertEquals(instantText, it.getValue("occurred_at").jsonPrimitive.content)
                }
            }
        } finally { TimeZone.setDefault(originalZone) }
    }

    @Test
    fun weekly_default_uses_creation_weekday_rather_than_synchronization_weekday() {
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            // Yesterday is always a different weekday, regardless of the date this runs.
            val creationDate = LocalDate.now().minusDays(1)
            val createdAt = creationDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val payload = SyncV2Mapper.planNode(habit(HabitType.CHECK_IN).copy(
                schedule = HabitSchedule.Weekly(emptyList()), createdAt = createdAt
            ))
            val rule = payload["activity"]!!.jsonObject["recurrence_rule"]!!.jsonObject
            assertEquals(listOf(creationDate.dayOfWeek.value), rule["weekdays"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    @Test
    fun wire_preferred_time_always_uses_ASCII_digits() {
        val originalLocale = Locale.getDefault()
        try {
            for (language in listOf("ar-EG", "fa-IR", "en-US")) {
                Locale.setDefault(Locale.forLanguageTag(language))
                val payload = SyncV2Mapper.planNode(habit(HabitType.CHECK_IN).copy(bestTime = 487))
                assertEquals("08:07:00", payload["activity"]!!.jsonObject["preferred_local_time"]!!.jsonPrimitive.content)
            }
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun goal_remains_a_top_level_goal_node() {
        val payload = SyncV2Mapper.planNode(habit(HabitType.GOAL))
        assertEquals("goal", payload["node_kind"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, payload["parent_uuid"])
        assertEquals("manual", payload["goal"]?.jsonObject
            ?.get("evaluation_policy")?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun temporary_task_maps_to_once_and_one_and_done() {
        val payload = SyncV2Mapper.planNode(
            habit(HabitType.CHECK_IN).copy(targetCycles = 1, failMode = FailMode.LOOSE)
        )
        val activity = payload["activity"]!!.jsonObject
        assertEquals("one_and_done", activity["completion_policy"]?.jsonPrimitive?.content)
        assertEquals("once", activity["recurrence_rule"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun timer_target_minutes_convert_to_wire_seconds() {
        val payload = SyncV2Mapper.planNode(
            habit(HabitType.TIMER).copy(targetValue = 10, isCountdown = true)
        )
        val activity = payload["activity"]!!.jsonObject
        assertEquals(600, activity["target_value"]?.jsonPrimitive?.content?.toInt())
        assertEquals("second", activity["target_unit"]?.jsonPrimitive?.content)
        assertEquals(true, activity["is_countdown"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun goal_preserves_target_policy_and_creation_time() {
        val payload = SyncV2Mapper.planNode(
            habit(HabitType.GOAL).copy(
                targetCycles = 30,
                failMode = FailMode.LOOSE,
                createdAt = 0
            )
        )
        val goal = payload["goal"]!!.jsonObject

        assertEquals(30, goal["target_cycles"]?.jsonPrimitive?.content?.toInt())
        assertEquals("loose", goal["failure_policy"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("1970-01-01T00:00:00Z", payload["created_at"]?.jsonPrimitive?.content)
    }

    @Test
    fun counting_completion_maps_to_a_count_snapshot() {
        val habit = habit(HabitType.COUNTING)
        val payload = SyncV2Mapper.completion(
            CompletionEntity(
                habitId = 1,
                habitUuid = habit.uuid,
                date = 1_786_291_200_000,
                value = 7,
                actualCompletedAt = 1_786_327_200_000
            ),
            habit
        )

        assertEquals("count_snapshot", payload["event_type"]?.jsonPrimitive?.content)
        assertEquals(7, payload["value"]?.jsonPrimitive?.content?.toInt())
        assertEquals(habit.uuid, payload["activity_uuid"]?.jsonPrimitive?.content)
    }

    @Test
    fun completion_local_date_follows_its_occurrence_timestamp() {
        val occurredAt = 1_786_327_200_000L
        val payload = SyncV2Mapper.completion(
            CompletionEntity(
                habitId = 1,
                date = 0,
                actualCompletedAt = occurredAt,
                recordedTimezone = "Asia/Shanghai"
            ),
            habit(HabitType.CHECK_IN)
        )

        assertEquals(
            "2026-08-10",
            payload["local_date"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun legacy_completion_without_occurrence_uses_its_recorded_day() {
        val recordedDay = 1_786_291_200_000L
        val payload = SyncV2Mapper.completion(
            CompletionEntity(
                habitId = 1,
                date = recordedDay,
                actualCompletedAt = null,
                createdAt = 1_786_399_200_000
            ),
            habit(HabitType.CHECK_IN)
        )

        assertEquals(
            Instant.ofEpochMilli(recordedDay).toString(),
            payload["occurred_at"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun metric_observation_and_link_preserve_their_dependencies() {
        val metric = MetricEntity(
            name = "Weight",
            unit = "kg",
            iconResId = 1,
            colorHex = "#2196F3"
        )
        val observation = SyncV2Mapper.metricObservation(
            MetricLogEntity(
                metricId = 2,
                date = 1_786_327_200_000,
                value = 60.5,
                unit = "kg",
                note = "Morning",
                updatedAt = 1_786_399_200_000
            ),
            metric.uuid
        )
        val link = SyncV2Mapper.link(
            HabitMetricLinkEntity(
                habitId = 1,
                habitUuid = "activity-uuid",
                metricId = 2,
                metricUuid = metric.uuid
            )
        )

        assertEquals(metric.uuid, observation["metric_uuid"]?.jsonPrimitive?.content)
        assertEquals(60.5, observation["value"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(
            Instant.ofEpochMilli(1_786_327_200_000).toString(),
            observation["occurred_at"]?.jsonPrimitive?.content
        )
        assertEquals("activity-uuid", link["activity_uuid"]?.jsonPrimitive?.content)
        assertEquals(metric.uuid, link["metric_uuid"]?.jsonPrimitive?.content)
    }

    private fun habit(type: HabitType) = HabitEntity(
        name = "Test",
        habitType = type,
        iconResId = 1,
        colorHex = "#2196F3",
        schedule = HabitSchedule.Daily
    )
}
