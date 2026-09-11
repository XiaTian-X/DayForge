package com.dayforge.data.repository

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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncV2MapperTest {
    @Test
    fun `goal remains a top level goal node`() {
        val payload = SyncV2Mapper.planNode(habit(HabitType.GOAL))
        assertEquals("goal", payload["node_kind"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, payload["parent_uuid"])
        assertEquals("manual", payload["goal"]?.jsonObject
            ?.get("evaluation_policy")?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `temporary task maps to once and one and done`() {
        val payload = SyncV2Mapper.planNode(
            habit(HabitType.CHECK_IN).copy(targetCycles = 1, failMode = FailMode.LOOSE)
        )
        val activity = payload["activity"]!!.jsonObject
        assertEquals("one_and_done", activity["completion_policy"]?.jsonPrimitive?.content)
        assertEquals("once", activity["recurrence_rule"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `timer target minutes convert to wire seconds`() {
        val payload = SyncV2Mapper.planNode(
            habit(HabitType.TIMER).copy(targetValue = 10, isCountdown = true)
        )
        val activity = payload["activity"]!!.jsonObject
        assertEquals(600, activity["target_value"]?.jsonPrimitive?.content?.toInt())
        assertEquals("second", activity["target_unit"]?.jsonPrimitive?.content)
        assertEquals(true, activity["is_countdown"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `goal preserves target policy and creation time`() {
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
    fun `counting completion maps to a count snapshot`() {
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
    fun `completion local date follows its occurrence timestamp`() {
        val occurredAt = 1_786_327_200_000L
        val payload = SyncV2Mapper.completion(
            CompletionEntity(
                habitId = 1,
                date = 0,
                actualCompletedAt = occurredAt
            ),
            habit(HabitType.CHECK_IN)
        )

        assertEquals(
            Instant.ofEpochMilli(occurredAt).atZone(ZoneId.systemDefault()).toLocalDate().toString(),
            payload["local_date"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun `legacy completion without occurrence uses its recorded day`() {
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
    fun `metric observation and link preserve their dependencies`() {
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
