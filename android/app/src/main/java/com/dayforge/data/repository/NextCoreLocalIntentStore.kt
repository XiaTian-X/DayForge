package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.encodeSyncRequest
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.api.dto.TimerCommandRequest
import com.dayforge.data.api.dto.NextSyncPushRequest
import com.dayforge.data.api.dto.TimerCommandBatchRequest
import com.dayforge.data.api.dto.validateNextSyncOperation
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.NextRequestOriginEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.json.*

/**
 * Explicit NEW v5 producer boundary; not injected into the active v4 writers.
 * The callback is a repository's local business transaction, never network/file/UI work.
 * It must not acquire the non-reentrant account coordinator. Old rows cannot be relabelled,
 * rewritten or consumed here. A proof/permission/cancellation failure rolls back business too.
 */
internal class NextCoreLocalIntentStore(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator
) {
    suspend fun <T> write(session: LocalDataSession, writeInTransaction: suspend () -> T): T = sessions.exclusive {
        val access = tokens.localCoreWriteAccess()
        if (access == null || access.session != session) rejectNextRequest(NextRequestException.Reason.STALE_ACCESS)
        database.withTransaction {
            val sql = database.openHelper.writableDatabase
            NextRequestSql.requireOutboxEnabled(sql)
            val tables = listOf("sync_outbox", "timer_command_outbox")
            val before = tables.associateWith { NextRequestSql.sources(sql, it) }
            val watermarks = tables.associateWith { NextRequestSql.watermark(sql, it) }
            val result = writeInTransaction()
            NextRequestSql.requireOutboxEnabled(sql)
            for ((index, table) in tables.withIndex()) {
                val after = NextRequestSql.sources(sql, table)
                if (before.getValue(table).any { (id, hash) -> after[id] != hash })
                    rejectNextRequest(NextRequestException.Reason.SOURCE_CHANGED)
                for ((id, hash) in after.filterKeys { it !in before.getValue(table) }) {
                    if (id <= watermarks.getValue(table)) rejectNextRequest(NextRequestException.Reason.OLD_INTENT)
                    val kind = if (index == 0) NEXT_OPERATION else NEXT_TIMER
                    val requestId: String
                    val permission: String
                    val bytes: ByteArray
                    if (kind == NEXT_OPERATION) {
                        val row = database.syncOutboxDao().getById(id)
                            ?: rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
                        if (row.attemptedAt != null || row.attemptCount != 0 || row.deadLetteredAt != null ||
                            row.payloadJson != null || row.baseRevision != null || row.basePayloadJson != null ||
                            row.entityUuid != row.wireEntityUuid || row.lastError != null || row.errorCode != null)
                            rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
                        requestId = row.operationId
                        permission = if (row.recordType in setOf("completion", "metric_log")) "facts.append" else "structure.write"
                        val operation = operation(row)
                        bytes = encodeSyncRequest(SyncV2Operation.serializer(), operation)
                        decodeFrozenSyncRequest(bytes, SyncV2Operation.serializer())
                        // Prove full-envelope limits while still able to roll back the local write.
                        // This placeholder is only a size/type oracle, never persisted or transmitted.
                        val envelope = encodeSyncRequest(NextSyncPushRequest.serializer(),
                            NextSyncPushRequest(SIZE_DEVICE, listOf(operation)))
                        decodeFrozenSyncRequest(envelope, NextSyncPushRequest.serializer())
                    } else {
                        val row = database.timeLogDao().getTimerCommand(id)
                            ?: rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
                        if (row.attemptCount != 0 || row.deadLetteredAt != null || row.lastError != null || row.errorCode != null)
                            rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
                        requestId = row.commandId; permission = "timer.control"
                        val command = timerRequest(row)
                        bytes = encodeSyncRequest(TimerCommandRequest.serializer(), command)
                        decodeFrozenSyncRequest(bytes, TimerCommandRequest.serializer())
                        val envelope = encodeSyncRequest(TimerCommandBatchRequest.serializer(),
                            TimerCommandBatchRequest(SIZE_DEVICE, listOf(command)))
                        decodeFrozenSyncRequest(envelope, TimerCommandBatchRequest.serializer())
                    }
                    if (!isContractUuid(requestId)) rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
                    if (access.capabilities != null && permission !in access.capabilities)
                        rejectNextRequest(NextRequestException.Reason.PERMISSION_DENIED)
                    val dao = database.nextRequestDao()
                    if (dao.origin(kind, requestId) != null || dao.transmission(kind, requestId) != null || dao.acceptance(kind, requestId) != null)
                        rejectNextRequest(NextRequestException.Reason.REQUEST_ID_REUSED)
                    val origin = NextRequestOriginEntity(kind, requestId, id, 5, session.authentication.userId,
                        session.serverInstanceId, session.syncEpoch, hash, bytes.toString(Charsets.UTF_8))
                    dao.insertOrigin(origin)
                    check(NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?", arrayOf(kind, requestId)) != null)
                    check(dao.origin(kind, requestId) == origin)
                }
            }
            if (tokens.localCoreWriteAccess() != access) rejectNextRequest(NextRequestException.Reason.STALE_ACCESS)
            result
        }
    }

    private companion object { const val SIZE_DEVICE = "00000000-0000-4000-8000-000000000000" }

    private suspend fun operation(row: SyncOutboxEntity): SyncV2Operation {
        require(isContractUuid(row.entityUuid) && row.action in setOf("upsert", "delete"))
        val type = when (row.recordType) {
            "habit" -> "plan_node"; "metric" -> "metric"; "completion" -> "activity_event"
            "metric_log" -> "metric_observation"; "link" -> "activity_metric_link"
            else -> rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE) // once has its own durable producer
        }
        val revert = row.recordType == "completion" && row.action == "delete"
        var wireId = row.entityUuid
        val payload = if (revert) {
            // Capture the NEW undo's time and stable identity now, never on a later send.
            val activity = requireNotNull(row.referenceUuid).also { require(isContractUuid(it)) }
            val zone = ZoneId.systemDefault()
            val instant = Instant.ofEpochMilli(row.createdAt)
            wireId = UUID.randomUUID().toString()
            buildJsonObject {
                put("activity_uuid", activity); put("event_type", "revert"); put("reverts_event_uuid", row.entityUuid)
                put("occurred_at", instant.toString()); put("timezone", zone.id)
                put("local_date", instant.atZone(zone).toLocalDate().toString()); put("source_type", "app")
            }
        } else if (row.action == "delete") {
            require(row.recordType in setOf("habit", "metric", "metric_log", "link"))
            SyncV2Mapper.deletePayload(row.recordType, row.referenceUuid)
        } else when (row.recordType) {
            "habit" -> NextStructureMapper.writePlan(requireNotNull(database.habitDao().getHabitByUuid(row.entityUuid)))
            "metric" -> NextStructureMapper.writeMetric(requireNotNull(database.metricDao().getMetricByUuid(row.entityUuid)))
            "completion" -> {
                val fact = requireNotNull(database.completionDao().getCompletionByUuid(row.entityUuid))
                val habit = requireNotNull(database.habitDao().getHabitById(fact.habitId))
                require(habit.habitType in setOf(HabitType.CHECK_IN, HabitType.COUNTING) && habit.completionPolicy == "recurring" &&
                    fact.oneTimeAction == null && fact.oneTimeExpectedVersion == null &&
                    fact.oneTimeExpectedHeadEventUuid == null && fact.oneTimeRevertsEventUuid == null)
                SyncV2Mapper.completion(fact, habit)
            }
            "metric_log" -> {
                val fact = requireNotNull(database.metricLogDao().getLogByUuid(row.entityUuid))
                val metric = requireNotNull(database.metricDao().getMetricById(fact.metricId))
                SyncV2Mapper.metricObservation(fact, metric.uuid)
            }
            "link" -> SyncV2Mapper.link(requireNotNull(database.habitMetricLinkDao().getLinkByUuid(row.entityUuid)))
            else -> rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
        }
        if (!revert) NextRequestSql.rowHash(database.openHelper.writableDatabase, "sync_entity_state",
            "entityType=? AND entityUuid=?", arrayOf(type, row.entityUuid))
        val shadow = if (revert) null else database.syncOutboxDao().getState(type, row.entityUuid)
        require(shadow == null || (!shadow.deleted && shadow.revision > 0))
        val base = shadow?.revision
        return SyncV2Operation(row.operationId, type, wireId, if (revert) "upsert" else row.action, base, payload)
            .also(::validateNextSyncOperation)
    }
}

internal fun timerRequest(row: TimerCommandEntity): TimerCommandRequest {
    require(isContractUuid(row.commandId) && isContractUuid(row.sessionUuid) && row.sequence > 0 &&
        row.commandType in setOf("start", "pause", "resume", "stop", "cancel", "takeover") &&
        row.expectedControlGeneration >= 0 && (row.expectedRevision == null || row.expectedRevision > 0) &&
        (row.activeElapsedMillis == null || row.activeElapsedMillis in 0..86_400_000))
    row.activityUuid?.let { require(isContractUuid(it)) }
    row.timezone?.let { require(it in ZoneId.getAvailableZoneIds()) }
    val instant = Instant.ofEpochMilli(row.occurredAt)
    require(instant.atZone(ZoneId.of("UTC")).year in 1..9999)
    return TimerCommandRequest(row.commandId, row.sessionUuid, row.sequence, row.commandType,
        instant.toString(), row.expectedControlGeneration, row.expectedRevision, row.activityUuid, row.timezone, row.activeElapsedMillis)
}
