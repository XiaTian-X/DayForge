package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.encodeSyncRequest
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.NextRequestOriginEntity
import com.dayforge.data.local.entity.NextTransmissionEntity
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountSessionCoordinator

/** A strictly bound HTTP result, NOT an acknowledgement/queue-consumption/cursor commit. */
internal data class NextCoreDelivery<T>(val access: LocalSyncAccess, val requestId: String, val result: T)

/**
 * Actual v5 first-send/original replay entrypoint, not installed into the v4 scheduler.
 * Version proof precedes preparation; only short local transactions hold the account lock.
 * Each journal contains one complete envelope, so batch membership is immutable on retry.
 */
internal class NextCoreRequestStore(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val http: NextSyncHttp
) {
    suspend fun sendOperation(captured: LocalSyncAccess, operationId: String): NextCoreDelivery<NextSyncPushResponse>? {
        require(isContractUuid(operationId) && captured.deviceId != null)
        val access = captured.copy(capabilities = captured.capabilities.toSet())
        return http.session(access) { channel ->
            val prepared = prepare(access, NEXT_OPERATION, operationId)
            val response = channel.pushFrozen(prepared.row.wireBytes)
            requireStillCurrent(access, prepared)
            NextCoreDelivery(access, operationId, response)
        }
    }

    suspend fun sendCommand(captured: LocalSyncAccess, commandId: String): NextCoreDelivery<TimerCommandBatchResponse>? {
        require(isContractUuid(commandId) && captured.deviceId != null)
        val access = captured.copy(capabilities = captured.capabilities.toSet())
        return http.session(access) { channel ->
            val prepared = prepare(access, NEXT_TIMER, commandId)
            val response = channel.commandsFrozen(prepared.row.wireBytes)
            requireStillCurrent(access, prepared)
            NextCoreDelivery(access, commandId, response)
        }
    }

    private data class Prepared(val row: NextTransmissionEntity, val proof: String)

    private suspend fun prepare(access: LocalSyncAccess, kind: String, id: String): Prepared = sessions.exclusive {
        authorize(access)
        database.withTransaction {
            val origin = origin(access, kind, id)
            val dao = database.nextRequestDao()
            val sql = database.openHelper.writableDatabase
            val oldProof = NextRequestSql.rowHash(sql, "next_transmissions", "kind=? AND requestId=?", arrayOf(kind, id))
            val row = if (oldProof == null) {
                val bytes = if (kind == NEXT_OPERATION) {
                    val operation = decodeFrozenSyncRequest(origin.intentJson.toByteArray(Charsets.UTF_8), SyncV2Operation.serializer())
                    require(operation.operationId == id)
                    encodeSyncRequest(NextSyncPushRequest.serializer(), NextSyncPushRequest(requireNotNull(access.deviceId), listOf(operation)))
                } else {
                    val command = decodeFrozenSyncRequest(origin.intentJson.toByteArray(Charsets.UTF_8), TimerCommandRequest.serializer())
                    require(command.commandId == id)
                    encodeSyncRequest(TimerCommandBatchRequest.serializer(), TimerCommandBatchRequest(requireNotNull(access.deviceId), listOf(command)))
                }
                NextTransmissionEntity(kind, id, origin.queueId, 5, origin.accountId,
                    requireNotNull(access.session.serverInstanceId), requireNotNull(access.session.syncEpoch),
                    requireNotNull(access.deviceId), nextRequestHash(bytes), bytes).also { dao.insertTransmission(it) }
            } else requireNotNull(dao.transmission(kind, id))
            validate(origin, row, access)
            val proof = requireNotNull(NextRequestSql.rowHash(sql, "next_transmissions", "kind=? AND requestId=?", arrayOf(kind, id)))
            val stored = requireNotNull(dao.transmission(kind, id))
            validate(origin, stored, access)
            check(stored.wireBytes.contentEquals(row.wireBytes))
            authorize(access)
            Prepared(stored, proof)
        }
    }

    private suspend fun requireStillCurrent(access: LocalSyncAccess, prepared: Prepared) = sessions.exclusive {
        authorize(access)
        database.withTransaction {
            val row = prepared.row
            val origin = origin(access, row.kind, row.requestId)
            val proof = NextRequestSql.rowHash(database.openHelper.writableDatabase, "next_transmissions",
                "kind=? AND requestId=?", arrayOf(row.kind, row.requestId))
            if (proof != prepared.proof) rejectNextRequest(NextRequestException.Reason.SOURCE_CHANGED)
            validate(origin, requireNotNull(database.nextRequestDao().transmission(row.kind, row.requestId)), access)
            authorize(access)
        }
    }

    private suspend fun origin(access: LocalSyncAccess, kind: String, id: String): NextRequestOriginEntity {
        val sql = database.openHelper.writableDatabase
        if (NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?", arrayOf(kind, id)) == null)
            rejectNextRequest(NextRequestException.Reason.OLD_INTENT)
        val row = requireNotNull(database.nextRequestDao().origin(kind, id))
        if (row.kind != kind || row.requestId != id || row.protocol != 5 || row.queueId <= 0 ||
            row.sourceHash.length != 64 || row.sourceHash.any { it !in "0123456789abcdef" } ||
            (row.serverInstanceId == null) != (row.syncEpoch == null))
            rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
        if (row.accountId != access.session.authentication.userId ||
            (row.serverInstanceId != null && (row.serverInstanceId != access.session.serverInstanceId || row.syncEpoch != access.session.syncEpoch)))
            rejectNextRequest(NextRequestException.Reason.TRANSMISSION_CONTEXT_CHANGED)
        val table = NextRequestSql.table(kind)
        if (NextRequestSql.rowHash(sql, table, "id=?", arrayOf(row.queueId)) != row.sourceHash)
            rejectNextRequest(NextRequestException.Reason.SOURCE_CHANGED)
        val permission = if (kind == NEXT_OPERATION) {
            val queue = requireNotNull(database.syncOutboxDao().getById(row.queueId))
            val operation = decodeFrozenSyncRequest(row.intentJson.toByteArray(Charsets.UTF_8), SyncV2Operation.serializer())
            require(queue.operationId == id && operation.operationId == id && isContractUuid(operation.entityUuid))
            val type = when (queue.recordType) {
                "habit" -> "plan_node"; "metric" -> "metric"; "completion" -> "activity_event"
                "metric_log" -> "metric_observation"; "link" -> "activity_metric_link"
                else -> rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
            }
            require(operation.entityType == type)
            if (queue.recordType == "completion" && queue.action == "delete") {
                require(operation.action == "upsert" && operation.entityUuid != queue.entityUuid &&
                    operation.payload["reverts_event_uuid"] == kotlinx.serialization.json.JsonPrimitive(queue.entityUuid))
            } else require(operation.action == queue.action && operation.entityUuid == queue.entityUuid)
            if (queue.recordType in setOf("completion", "metric_log")) "facts.append" else "structure.write"
        } else {
            val queue = requireNotNull(database.timeLogDao().getTimerCommand(row.queueId))
            val command = decodeFrozenSyncRequest(row.intentJson.toByteArray(Charsets.UTF_8), TimerCommandRequest.serializer())
            require(queue.commandId == id && timerRequest(queue) == command)
            "timer.control"
        }
        if (permission !in access.capabilities) rejectNextRequest(NextRequestException.Reason.PERMISSION_DENIED)
        return row
    }

    private suspend fun validate(origin: NextRequestOriginEntity, row: NextTransmissionEntity, access: LocalSyncAccess) {
        if (row.kind != origin.kind || row.requestId != origin.requestId || row.queueId != origin.queueId || row.protocol != 5)
            rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
        if (row.accountId != origin.accountId || row.accountId != access.session.authentication.userId ||
            row.serverInstanceId != access.session.serverInstanceId || row.syncEpoch != access.session.syncEpoch || row.deviceId != access.deviceId)
            rejectNextRequest(NextRequestException.Reason.TRANSMISSION_CONTEXT_CHANGED)
        if (nextRequestHash(row.wireBytes) != row.wireHash) rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
        if (row.kind == NEXT_OPERATION) {
            val request = decodeFrozenSyncRequest(row.wireBytes, NextSyncPushRequest.serializer())
            require(request.deviceId == row.deviceId && request.operations.size == 1 &&
                request.operations.single() == decodeFrozenSyncRequest(origin.intentJson.toByteArray(Charsets.UTF_8), SyncV2Operation.serializer()))
        } else {
            val request = decodeFrozenSyncRequest(row.wireBytes, TimerCommandBatchRequest.serializer())
            require(request.deviceId == row.deviceId && request.commands.size == 1 &&
                request.commands.single() == decodeFrozenSyncRequest(origin.intentJson.toByteArray(Charsets.UTF_8), TimerCommandRequest.serializer()))
        }
    }

    private suspend fun authorize(access: LocalSyncAccess) {
        if (tokens.syncAuthenticationSnapshot(access) == null) rejectNextRequest(NextRequestException.Reason.STALE_ACCESS)
    }
}
