package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.data.local.dao.SyncOutboxDao
import com.dayforge.data.local.dao.SyncConflictDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.data.local.entity.SyncEntityStateEntity
import com.dayforge.data.local.entity.SyncConflictEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.local.entity.TimeLogDayAllocationEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.util.IconMapper
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Applies server changes transactionally while keeping local triggers silent. */
class SyncV2Merger(
    private val database: HabitDatabase,
    private val habitDao: HabitDao,
    private val completionDao: CompletionDao,
    private val timeLogDao: TimeLogDao,
    private val metricDao: MetricDao,
    private val metricLogDao: MetricLogDao,
    private val linkDao: HabitMetricLinkDao,
    private val outboxDao: SyncOutboxDao,
    private val conflictDao: SyncConflictDao
) {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    suspend fun apply(changes: List<SyncV2Change>) {
        database.withTransaction {
            withOutboxSuppressed {
                applyChanges(changes)
            }
        }
    }

    /** Move an acknowledged conflict out of the queue without losing any local version. */
    suspend fun preserveConflictAtomically(
        recordType: String,
        localEntityUuid: String,
        operationId: String,
        action: String,
        referenceUuid: String?,
        baseRevision: Long?,
        entityType: String,
        wireEntityUuid: String,
        revision: Long,
        basePayload: JsonObject?,
        localPayload: JsonObject,
        serverPayload: JsonObject,
        conflictingFields: List<String>,
        conflictKind: String?,
        errorCode: String?,
        message: String?
    ) = database.withTransaction {
        withOutboxSuppressed {
            applyChanges(
                listOf(authoritativeChange(entityType, wireEntityUuid, revision, serverPayload))
            )
        }
        conflictDao.insert(
            SyncConflictEntity(
                operationId = operationId,
                recordType = recordType,
                localEntityUuid = localEntityUuid,
                wireEntityUuid = wireEntityUuid,
                entityType = entityType,
                action = action,
                referenceUuid = referenceUuid,
                baseRevision = baseRevision,
                serverRevision = revision,
                basePayloadJson = basePayload?.toString(),
                localPayloadJson = localPayload.toString(),
                serverPayloadJson = serverPayload.toString(),
                conflictingFieldsJson = Json.encodeToString(conflictingFields),
                conflictKind = conflictKind,
                errorCode = errorCode,
                message = message
            )
        )
        // Later local edits are represented by the newest frozen local payload above.
        // Keeping them in the active queue would silently turn a conflict into local-wins.
        outboxDao.deleteByEntity(recordType, localEntityUuid)
    }

    suspend fun resolveConflictUseServer(id: Long): Boolean = database.withTransaction {
        conflictDao.markResolved(id, "server", System.currentTimeMillis()) == 1
    }

    /** Reapply the saved local intent against the now-current server revision. */
    suspend fun resolveConflictUseLocal(id: Long): Boolean = database.withTransaction {
        val conflict = conflictDao.getUnresolvedById(id) ?: return@withTransaction false
        val serverPayload = JSON.parseToJsonElement(conflict.serverPayloadJson).jsonObject
        if (serverPayload.string("deleted_at") != null && conflict.action != "delete") {
            // Restoring a server tombstone must be a separate, explicit protocol action.
            return@withTransaction false
        }
        val localPayload = JSON.parseToJsonElement(conflict.localPayloadJson).jsonObject
        withOutboxSuppressed {
            val localChange = authoritativeChange(
                conflict.entityType,
                conflict.wireEntityUuid,
                conflict.serverRevision,
                localPayload
            ).copy(operation = conflict.action)
            applyChanges(listOf(localChange))
        }
        outboxDao.upsertState(
            SyncEntityStateEntity(
                entityType = conflict.entityType,
                entityUuid = conflict.wireEntityUuid,
                revision = conflict.serverRevision,
                deleted = serverPayload.string("deleted_at") != null,
                payloadJson = conflict.serverPayloadJson,
                payloadHash = syncPayloadHash(conflict.serverPayloadJson)
            )
        )
        outboxDao.insert(
            SyncOutboxEntity(
                operationId = UUID.randomUUID().toString(),
                recordType = conflict.recordType,
                entityUuid = conflict.localEntityUuid,
                wireEntityUuid = conflict.wireEntityUuid,
                action = conflict.action,
                referenceUuid = conflict.referenceUuid,
                payloadJson = conflict.localPayloadJson,
                baseRevision = conflict.serverRevision,
                basePayloadJson = conflict.serverPayloadJson,
                attemptedAt = System.currentTimeMillis()
            )
        )
        conflictDao.markResolved(id, "local", System.currentTimeMillis()) == 1
    }

    /**
     * Replace a clean local cache with a server bootstrap after a malformed
     * incremental change. Callers must ensure there are no pending/dead-letter
     * local mutations before using this recovery path.
     */
    suspend fun replaceWithBootstrap(changes: List<SyncV2Change>) {
        database.withTransaction {
            if (outboxDao.count() > 0 || outboxDao.countDeadLetters() > 0 ||
                timeLogDao.countPendingTimerCommands() > 0 ||
                timeLogDao.countRejectedTimerCommands() > 0 ||
                timeLogDao.getActiveTimeLog() != null
            ) {
                throw IllegalStateException("本地存在未处理修改，不能替换同步缓存")
            }
            withOutboxSuppressed {
                val sqlite = database.openHelper.writableDatabase
                listOf(
                    "habit_metric_links",
                    "metric_logs",
                    "completions",
                    "timelog_day_allocations",
                    "timer_segments",
                    "timelogs",
                    "timer_command_outbox",
                    "habits",
                    "metrics",
                    "sync_entity_state"
                ).forEach { table -> sqlite.execSQL("DELETE FROM $table") }
                applyChanges(changes)
            }
        }
    }

    private suspend fun applyChanges(changes: List<SyncV2Change>) {
        changes.forEach { change ->
            try {
                if (change.operation == "delete") delete(change) else upsert(change)
                outboxDao.upsertState(
                    SyncEntityStateEntity(
                        entityType = change.entityType,
                        entityUuid = change.entityUuid,
                        revision = change.revision,
                        deleted = change.operation == "delete",
                        payloadJson = change.payload.toString(),
                        payloadHash = syncPayloadHash(change.payload.toString())
                    )
                )
            } catch (error: SyncMergeException) {
                throw error
            } catch (error: Exception) {
                throw SyncMergeException(change, "unexpected payload or dependency error", error)
            }
        }
    }

    suspend fun applyAuthoritativeEntity(
        entityType: String,
        entityUuid: String,
        revision: Long,
        payload: JsonObject
    ) = apply(listOf(authoritativeChange(entityType, entityUuid, revision, payload)))

    private fun authoritativeChange(
        entityType: String,
        entityUuid: String,
        revision: Long,
        payload: JsonObject
    ) = SyncV2Change(
        sequence = 0,
        entityType = entityType,
        entityUuid = entityUuid,
        operation = if (payload.string("deleted_at") != null) "delete" else "upsert",
        revision = revision,
        payload = payload,
        changedAt = payload.string("updated_at") ?: Instant.now().toString()
    )

    private suspend fun <T> withOutboxSuppressed(block: suspend () -> T): T {
        setSuppressed(true)
        return try {
            block()
        } finally {
            setSuppressed(false)
        }
    }

    private fun setSuppressed(suppressed: Boolean) {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sync_control SET suppressOutbox = ? WHERE id = 1",
            arrayOf(if (suppressed) 1 else 0)
        )
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }

    private suspend fun upsert(change: SyncV2Change) {
        when (change.entityType) {
            "plan_node" -> upsertPlanNode(change)
            "activity_event" -> upsertActivityEvent(change)
            "metric" -> upsertMetric(change)
            "metric_observation" -> upsertObservation(change)
            "activity_metric_link" -> upsertLink(change)
            else -> invalidChange(change, "unknown entity type")
        }
    }

    private suspend fun delete(change: SyncV2Change) {
        when (change.entityType) {
            "plan_node" -> habitDao.getHabitByUuid(change.entityUuid)?.let { habitDao.delete(it) }
            "activity_event" -> {
                completionDao.getCompletionByUuid(change.entityUuid)?.let { completionDao.delete(it) }
                timeLogDao.getTimeLogByUuid(change.entityUuid)?.let {
                    timeLogDao.deleteDayAllocations(it.uuid)
                    timeLogDao.deleteTimerSegments(it.uuid)
                    timeLogDao.delete(it)
                }
            }
            "metric" -> metricDao.getMetricByUuid(change.entityUuid)?.let { metricDao.delete(it) }
            "metric_observation" -> metricLogDao.getLogByUuid(change.entityUuid)?.let { metricLogDao.delete(it) }
            "activity_metric_link" -> linkDao.getLinkByUuid(change.entityUuid)?.let { linkDao.delete(it) }
            else -> invalidChange(change, "unknown entity type")
        }
    }

    private suspend fun upsertPlanNode(change: SyncV2Change) {
        val payload = change.payload
        val existing = habitDao.getHabitByUuid(change.entityUuid)
        val isGoal = payload.string("node_kind") == "goal"
        val activity = payload.obj("activity")
        val rule = activity?.obj("recurrence_rule")
        val isOnce = rule?.string("type") == "once"
        val type = if (isGoal) HabitType.GOAL else when (activity?.string("tracking_mode")) {
            "count" -> HabitType.COUNTING
            "duration" -> HabitType.TIMER
            else -> HabitType.CHECK_IN
        }
        val goalResult = payload.obj("goal")?.string("manual_result")
        val entity = HabitEntity(
            id = existing?.id ?: 0,
            name = payload.string("title") ?: invalidChange(change, "missing title"),
            description = payload.string("description") ?: "",
            habitType = type,
            iconResId = IconMapper.toIconResId(payload.string("icon") ?: "health"),
            colorHex = payload.string("color_hex") ?: "#2196F3",
            schedule = if (isOnce) HabitSchedule.Daily else schedule(rule),
            targetValue = ((activity?.double("target_value") ?: 1.0) /
                if (type == HabitType.TIMER) 60.0 else 1.0).toInt().coerceAtLeast(1),
            isCountdown = activity?.bool("is_countdown") ?: false,
            isActive = payload.string("status") !in setOf("archived", "completed", "failed"),
            uuid = change.entityUuid,
            parentHabitId = payload.string("parent_uuid"),
            targetCycles = if (isGoal) payload.obj("goal")?.int("target_cycles") else if (isOnce) 1 else activity?.int("target_cycles"),
            failMode = if (isOnce || (if (isGoal) payload.obj("goal") else activity)
                    ?.obj("failure_policy")?.string("type") == "loose") FailMode.LOOSE else FailMode.STRICT,
            goalSuccess = when (goalResult) { "succeeded" -> true; "failed" -> false; else -> null },
            activityRate = existing?.activityRate ?: 100,
            activityRateUpdatedAt = existing?.activityRateUpdatedAt ?: System.currentTimeMillis(),
            bestTime = activity?.string("preferred_local_time")?.let(::minutesOfDay),
            createdAt = payload.instantMillis("created_at") ?: existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = payload.instantMillis("updated_at") ?: System.currentTimeMillis()
        )
        habitDao.upsert(entity)
    }

    private suspend fun upsertActivityEvent(change: SyncV2Change) {
        val payload = change.payload
        val eventType = payload.string("event_type") ?: invalidChange(change, "missing event_type")
        if (eventType == "revert") {
            val target = payload.string("reverts_event_uuid")
                ?: invalidChange(change, "missing reverts_event_uuid")
            completionDao.getCompletionByUuid(target)?.let { completionDao.delete(it) }
            timeLogDao.getTimeLogByUuid(target)?.let {
                timeLogDao.deleteDayAllocations(it.uuid)
                timeLogDao.deleteTimerSegments(it.uuid)
                timeLogDao.delete(it)
            }
            return
        }
        val activityUuid = payload.string("activity_uuid")
            ?: invalidChange(change, "missing activity_uuid")
        val habit = habitDao.getHabitByUuid(activityUuid)
            ?: invalidChange(change, "activity dependency $activityUuid is missing")
        val date = payload.localDateMillis("local_date") ?: System.currentTimeMillis()
        if (eventType == "duration_session") {
            val existing = timeLogDao.getTimeLogByUuid(change.entityUuid)
            val timezone = payload.string("timezone") ?: zone.id
            val localDate = payload.string("local_date")
                ?: invalidChange(change, "missing local_date")
            val localDateEpoch = runCatching {
                LocalDate.parse(localDate).atStartOfDay(ZoneId.of(timezone)).toInstant().toEpochMilli()
            }.getOrElse { invalidChange(change, "invalid local_date or timezone") }
            val durationSeconds = payload.int("duration_seconds") ?: 0
            timeLogDao.upsert(
                TimeLogEntity(
                    id = existing?.id ?: 0,
                    habitId = habit.id,
                    startTime = payload.instantMillis("started_at")
                        ?: invalidChange(change, "missing or invalid started_at"),
                    endTime = payload.instantMillis("ended_at"),
                    durationSeconds = durationSeconds,
                    accumulatedPauseMillis = existing?.accumulatedPauseMillis ?: 0,
                    timerNextCommandSequence = existing?.timerNextCommandSequence ?: 1,
                    timerControlGeneration = existing?.timerControlGeneration ?: 0,
                    timerLastCommandAt = existing?.timerLastCommandAt,
                    timerTimezone = existing?.timerTimezone ?: timezone,
                    timerActiveElapsedMillis = payload.long("duration_milliseconds")
                        ?: existing?.timerActiveElapsedMillis
                        ?: durationSeconds * 1_000L,
                    date = localDateEpoch,
                    uuid = change.entityUuid,
                    createdAt = payload.instantMillis("created_at") ?: System.currentTimeMillis(),
                    updatedAt = payload.instantMillis("updated_at") ?: System.currentTimeMillis()
                )
            )
            val allocations = payload["day_allocations"]?.jsonArray?.map { item ->
                val allocation = item.jsonObject
                val allocationDate = allocation.string("local_date")
                    ?: invalidChange(change, "allocation missing local_date")
                val allocationZone = allocation.string("timezone")
                    ?: invalidChange(change, "allocation missing timezone")
                val allocationEpoch = runCatching {
                    LocalDate.parse(allocationDate).atStartOfDay(ZoneId.of(allocationZone))
                        .toInstant().toEpochMilli()
                }.getOrElse { invalidChange(change, "invalid allocation date or timezone") }
                TimeLogDayAllocationEntity(
                    sessionUuid = change.entityUuid,
                    habitId = habit.id,
                    localDate = allocationDate,
                    localDateEpoch = allocationEpoch,
                    timezone = allocationZone,
                    durationMillis = allocation.long("duration_milliseconds")
                        ?: invalidChange(change, "allocation missing duration")
                )
            } ?: listOf(
                TimeLogDayAllocationEntity(
                    sessionUuid = change.entityUuid,
                    habitId = habit.id,
                    localDate = localDate,
                    localDateEpoch = localDateEpoch,
                    timezone = timezone,
                    durationMillis = durationSeconds * 1_000L
                )
            )
            timeLogDao.replaceDayAllocations(change.entityUuid, allocations)
        } else {
            val existing = completionDao.getCompletionByUuid(change.entityUuid)
            completionDao.upsert(
                CompletionEntity(
                    id = existing?.id ?: 0,
                    habitId = habit.id,
                    date = date,
                    value = payload.double("value")?.toInt() ?: 1,
                    actualCompletedAt = payload.instantMillis("occurred_at"),
                    uuid = change.entityUuid,
                    habitUuid = habit.uuid,
                    createdAt = payload.instantMillis("created_at") ?: System.currentTimeMillis()
                )
            )
        }
    }

    private suspend fun upsertMetric(change: SyncV2Change) {
        val payload = change.payload
        val existing = metricDao.getMetricByUuid(change.entityUuid)
        metricDao.upsert(
            MetricEntity(
                id = existing?.id ?: 0,
                name = payload.string("name") ?: invalidChange(change, "missing metric name"),
                description = payload.string("description") ?: "",
                unit = payload.string("unit") ?: invalidChange(change, "missing metric unit"),
                decimalPlaces = payload.int("decimal_places") ?: 0,
                aggregationType = payload.string("aggregation_type") ?: "average",
                targetDirection = payload.string("target_direction"),
                targetValue = payload.double("target_value"),
                targetValueUpper = payload.double("target_value_upper"),
                iconResId = IconMapper.toIconResId(payload.string("icon") ?: "health"),
                colorHex = payload.string("color_hex") ?: "#2196F3",
                isActive = payload.string("status") == "active",
                uuid = change.entityUuid,
                createdAt = payload.instantMillis("created_at") ?: existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = payload.instantMillis("updated_at") ?: System.currentTimeMillis()
            )
        )
    }

    private suspend fun upsertObservation(change: SyncV2Change) {
        val payload = change.payload
        val metricUuid = payload.string("metric_uuid")
            ?: invalidChange(change, "missing metric_uuid")
        val metric = metricDao.getMetricByUuid(metricUuid)
            ?: invalidChange(change, "metric dependency $metricUuid is missing")
        val existing = metricLogDao.getLogByUuid(change.entityUuid)
        metricLogDao.upsert(
            MetricLogEntity(
                id = existing?.id ?: 0,
                metricId = metric.id,
                date = payload.instantMillis("occurred_at")
                    ?: payload.localDateMillis("local_date")
                    ?: System.currentTimeMillis(),
                value = payload.double("value") ?: invalidChange(change, "missing observation value"),
                unit = payload.string("unit") ?: metric.unit,
                note = payload.string("note") ?: "",
                uuid = change.entityUuid,
                createdAt = payload.instantMillis("created_at") ?: System.currentTimeMillis(),
                updatedAt = payload.instantMillis("updated_at") ?: System.currentTimeMillis()
            )
        )
    }

    private suspend fun upsertLink(change: SyncV2Change) {
        val payload = change.payload
        val activityUuid = payload.string("activity_uuid")
            ?: invalidChange(change, "missing activity_uuid")
        val metricUuid = payload.string("metric_uuid")
            ?: invalidChange(change, "missing metric_uuid")
        val habit = habitDao.getHabitByUuid(activityUuid)
            ?: invalidChange(change, "activity dependency $activityUuid is missing")
        val metric = metricDao.getMetricByUuid(metricUuid)
            ?: invalidChange(change, "metric dependency $metricUuid is missing")
        val existing = linkDao.getLinkByUuid(change.entityUuid)
        linkDao.upsert(
            HabitMetricLinkEntity(
                id = existing?.id ?: 0,
                habitId = habit.id,
                habitUuid = habit.uuid,
                metricId = metric.id,
                metricUuid = metric.uuid,
                coefficient = payload.double("coefficient") ?: 1.0,
                showInHabitDetail = payload.bool("show_in_activity_detail") ?: true,
                promptOnComplete = payload.bool("prompt_on_complete") ?: false,
                isActive = payload.bool("is_active") ?: true,
                uuid = change.entityUuid,
                createdAt = payload.instantMillis("created_at") ?: System.currentTimeMillis(),
                updatedAt = payload.instantMillis("updated_at") ?: System.currentTimeMillis()
            )
        )
    }

    private fun schedule(rule: JsonObject?): HabitSchedule = when (rule?.string("type")) {
        "weekly" -> HabitSchedule.Weekly(rule["weekdays"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull } ?: listOf(1))
        "monthly" -> HabitSchedule.Monthly(rule.int("day_of_month") ?: 1)
        "interval" -> HabitSchedule.Custom(rule.int("every_days") ?: 1)
        else -> HabitSchedule.Daily
    }

    private fun minutesOfDay(value: String): Long? = runCatching {
        val time = LocalTime.parse(value)
        (time.hour * 60 + time.minute).toLong()
    }.getOrNull()

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
    private fun JsonObject.double(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.obj(key: String): JsonObject? = runCatching { this[key]?.jsonObject }.getOrNull()
    private fun JsonObject.instantMillis(key: String): Long? = string(key)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    private fun JsonObject.localDateMillis(key: String): Long? = string(key)?.let {
        runCatching { LocalDate.parse(it).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun invalidChange(change: SyncV2Change, reason: String): Nothing =
        throw SyncMergeException(change, reason)
}

internal fun syncPayloadHash(payloadJson: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(payloadJson.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

class SyncMergeException(
    val change: SyncV2Change,
    reason: String,
    cause: Throwable? = null
) : IllegalStateException(
    "无法应用服务端变更 ${change.sequence}/${change.entityType}/${change.entityUuid}: $reason",
    cause
)
