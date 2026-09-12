package com.dayforge.data.repository

import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.util.IconMapper
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Lossless mapping between the current Android domain and the v2 wire domain. */
object SyncV2Mapper {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private fun isoInstant(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()
    private fun localDate(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().format(DateTimeFormatter.ISO_DATE)

    fun planNode(habit: HabitEntity): JsonObject = buildJsonObject {
        val isGoal = habit.habitType == HabitType.GOAL
        val isOneTimeTask = habit.habitType == HabitType.CHECK_IN &&
            habit.targetCycles == 1 && habit.failMode == FailMode.LOOSE
        put("node_kind", if (isGoal) "goal" else "activity")
        put("title", habit.name)
        put("description", habit.description)
        put("icon", IconMapper.toIconName(habit.iconResId))
        put("color_hex", habit.colorHex)
        put("status", when {
            isGoal && habit.goalSuccess == true -> "completed"
            isGoal && habit.goalSuccess == false -> "failed"
            !habit.isActive -> "archived"
            else -> "active"
        })
        put("visibility", "private")
        put("created_at", isoInstant(habit.createdAt))
        if (habit.parentHabitId != null) put("parent_uuid", habit.parentHabitId) else put("parent_uuid", JsonNull)
        if (isGoal) {
            put("goal", buildJsonObject {
                if (habit.targetCycles != null) put("target_cycles", habit.targetCycles) else put("target_cycles", JsonNull)
                put("failure_policy", buildJsonObject {
                    put("schema_version", 1)
                    put("type", if (habit.failMode == FailMode.STRICT) "strict" else "loose")
                })
                put("evaluation_policy", buildJsonObject {
                    put("schema_version", 1)
                    put("type", "manual")
                })
                when (habit.goalSuccess) {
                    true -> put("manual_result", "succeeded")
                    false -> put("manual_result", "failed")
                    null -> put("manual_result", JsonNull)
                }
            })
        } else {
            put("activity", buildJsonObject {
                put("tracking_mode", when (habit.habitType) {
                    HabitType.CHECK_IN -> "check"
                    HabitType.COUNTING -> "count"
                    HabitType.TIMER -> "duration"
                    HabitType.GOAL -> error("Goal cannot be mapped as an activity")
                })
                put("is_countdown", habit.isCountdown)
                put("recurrence_rule", if (isOneTimeTask) {
                    buildJsonObject {
                        put("schema_version", 1)
                        put("type", "once")
                    }
                } else recurrenceRule(habit))
                put("completion_policy", if (isOneTimeTask) "one_and_done" else "recurring")
                put(
                    "target_value",
                    if (habit.habitType == HabitType.TIMER) habit.targetValue.toLong() * 60 else habit.targetValue
                )
                if (habit.habitType == HabitType.TIMER) put("target_unit", "second")
                val targetCycles = habit.targetCycles?.takeUnless { isOneTimeTask }
                if (targetCycles != null) put("target_cycles", targetCycles) else put("target_cycles", JsonNull)
                put("failure_policy", buildJsonObject {
                    put("schema_version", 1)
                    put("type", if (habit.failMode == FailMode.STRICT) "strict" else "loose")
                })
                if (habit.bestTime != null) {
                    val minutes = habit.bestTime
                    put("preferred_local_time", "%02d:%02d:00".format(Locale.ROOT, minutes / 60, minutes % 60))
                } else {
                    put("preferred_local_time", JsonNull)
                }
                put("timezone", zone.id)
            })
        }
    }

    private fun recurrenceRule(habit: HabitEntity): JsonObject = buildJsonObject {
        put("schema_version", 1)
        when (val schedule = habit.schedule) {
            HabitSchedule.Daily -> {
                put("type", "daily")
                put("interval", 1)
            }
            is HabitSchedule.Weekly -> {
                put("type", "weekly")
                put("interval", 1)
                val weekdays = schedule.daysOfWeek.takeIf { it.isNotEmpty() }
                    ?: listOf(Instant.ofEpochMilli(habit.createdAt).atZone(zone).dayOfWeek.value)
                put("weekdays", JsonArray(weekdays.distinct().sorted().map(::JsonPrimitive)))
            }
            is HabitSchedule.Monthly -> {
                put("type", "monthly")
                put("interval", 1)
                put("day_of_month", schedule.dayOfMonth)
            }
            is HabitSchedule.Custom -> {
                put("type", "interval")
                put("every_days", schedule.frequencyDays)
                put("start_date", localDate(habit.createdAt))
            }
        }
    }

    fun completion(completion: CompletionEntity, habit: HabitEntity): JsonObject = buildJsonObject {
        val occurredAt = completion.actualCompletedAt ?: completion.date
        put("activity_uuid", habit.uuid)
        put("event_type", if (habit.habitType == HabitType.COUNTING) "count_snapshot" else "check_in")
        if (habit.habitType == HabitType.COUNTING) put("value", completion.value)
        put("occurred_at", isoInstant(occurredAt))
        put("local_date", completion.recordedLocalDate)
        put("timezone", completion.recordedTimezone)
        put("source_type", "app")
    }

    fun revert(activityUuid: String, targetEventUuid: String): JsonObject = buildJsonObject {
        val now = System.currentTimeMillis()
        put("activity_uuid", activityUuid)
        put("event_type", "revert")
        put("occurred_at", isoInstant(now))
        put("local_date", localDate(now))
        put("timezone", zone.id)
        put("source_type", "app")
        put("reverts_event_uuid", targetEventUuid)
    }

    fun metric(metric: MetricEntity): JsonObject = buildJsonObject {
        put("name", metric.name)
        put("description", metric.description)
        put("unit", metric.unit)
        put("decimal_places", metric.decimalPlaces)
        put("aggregation_type", metric.aggregationType)
        if (metric.targetDirection != null) put("target_direction", metric.targetDirection) else put("target_direction", JsonNull)
        if (metric.targetValue != null) put("target_value", metric.targetValue) else put("target_value", JsonNull)
        if (metric.targetValueUpper != null) put("target_value_upper", metric.targetValueUpper) else put("target_value_upper", JsonNull)
        put("icon", IconMapper.toIconName(metric.iconResId))
        put("color_hex", metric.colorHex)
        put("status", if (metric.isActive) "active" else "archived")
    }

    fun metricObservation(log: MetricLogEntity, metricUuid: String): JsonObject = buildJsonObject {
        put("metric_uuid", metricUuid)
        put("value", log.value)
        put("unit", log.unit)
        put("occurred_at", isoInstant(log.date))
        put("local_date", log.recordedLocalDate)
        put("timezone", log.recordedTimezone)
        put("note", log.note)
        put("source_type", "app")
    }

    fun link(link: HabitMetricLinkEntity): JsonObject = buildJsonObject {
        put("activity_uuid", link.habitUuid)
        put("metric_uuid", link.metricUuid)
        put("coefficient", link.coefficient)
        put("show_in_activity_detail", link.showInHabitDetail)
        put("prompt_on_complete", link.promptOnComplete)
        put("is_active", link.isActive)
    }

    fun deletePayload(recordType: String, reference: String?): JsonObject = buildJsonObject {
        if (recordType == "habit" && reference == HabitType.GOAL.name) {
            put("child_policy", "detach_children")
        }
    }
}
