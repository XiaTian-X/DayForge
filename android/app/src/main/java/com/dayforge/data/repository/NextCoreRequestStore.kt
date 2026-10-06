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
import com.dayforge.data.local.entity.NextAcceptanceEntity
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountSessionCoordinator

/** A strictly bound HTTP result, NOT an acknowledgement/queue-consumption/cursor commit. */
internal data class NextCoreDelivery<T>(val access: LocalSyncAccess, val requestId: String, val result: T,
    val transmissionProof: String)

internal enum class NextOperationAcceptance { COMMITTED, REPLAYED }

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
            NextCoreDelivery(access, operationId, response, prepared.proof)
        }
    }

    suspend fun sendCommand(captured: LocalSyncAccess, commandId: String): NextCoreDelivery<TimerCommandBatchResponse>? {
        require(isContractUuid(commandId) && captured.deviceId != null)
        val access = captured.copy(capabilities = captured.capabilities.toSet())
        return http.session(access) { channel ->
            val prepared = prepare(access, NEXT_TIMER, commandId)
            val response = channel.commandsFrozen(prepared.row.wireBytes)
            requireStillCurrent(access, prepared)
            NextCoreDelivery(access, commandId, response, prepared.proof)
        }
    }

    private data class Prepared(val row: NextTransmissionEntity, val proof: String)

    /** Actual send → acceptance entrypoint. A rejected/conflicting delivery never consumes work. */
    suspend fun sendAndAcceptOperation(access: LocalSyncAccess, operationId: String): NextOperationAcceptance? {
        val saved = sessions.exclusive {
            authorize(access)
            database.withTransaction {
                val sql = database.openHelper.writableDatabase
                if (NextRequestSql.rowHash(sql, "next_acceptances", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, operationId)) == null) null
                else {
                    val receipt = requireNotNull(database.nextRequestDao().acceptance(NEXT_OPERATION, operationId))
                    val result = decodeFrozenSyncRequest(receipt.resultJson.toByteArray(Charsets.UTF_8), NextSyncOperationResult.serializer())
                    val proof = requireNotNull(NextRequestSql.rowHash(sql, "next_transmissions", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, operationId)))
                    NextCoreDelivery(access, operationId, NextSyncPushResponse(listOf(result)), proof)
                }
            }
        }
        val delivery = saved ?: sendOperation(access, operationId) ?: return null
        return acceptOperation(delivery)
    }

    /** Returns only after Room's outer COMMIT. No cursor/once/timer acknowledgement is inferred. */
    suspend fun acceptOperation(delivery: NextCoreDelivery<NextSyncPushResponse>): NextOperationAcceptance = sessions.exclusive {
        val access = delivery.access
        authorize(access)
        database.withTransaction {
            val sql = database.openHelper.writableDatabase
            NextRequestSql.requireOutboxEnabled(sql)
            val id = delivery.requestId
            require(isContractUuid(id) && delivery.result.results.size == 1)
            val dao = database.nextRequestDao()
            val originHash = NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id))
                ?: rejectNextRequest(NextRequestException.Reason.OLD_INTENT)
            val transmissionHash = NextRequestSql.rowHash(sql, "next_transmissions", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id))
                ?: rejectNextRequest(NextRequestException.Reason.OLD_INTENT)
            if (transmissionHash != delivery.transmissionProof) rejectNextRequest(NextRequestException.Reason.SOURCE_CHANGED)
            val rawOrigin = requireNotNull(dao.origin(NEXT_OPERATION, id))
            val transmission = requireNotNull(dao.transmission(NEXT_OPERATION, id))
            validate(rawOrigin, transmission, access)
            val operation = decodeFrozenSyncRequest(transmission.wireBytes, NextSyncPushRequest.serializer()).operations.single()
            val permission = if (operation.entityType in setOf("activity_event", "metric_observation")) "facts.append" else "structure.write"
            if (permission !in access.capabilities) rejectNextRequest(NextRequestException.Reason.PERMISSION_DENIED)
            val result = delivery.result.results.single()
            validateTaskResultBinding(operation, result)
            if (operation.action != "upsert" || operation.entityType !in setOf("plan_node", "metric", "activity_event", "metric_observation", "activity_metric_link") ||
                operation.payload["one_time"].let { it != null && it != kotlinx.serialization.json.JsonNull } ||
                operation.payload["event_type"] == kotlinx.serialization.json.JsonPrimitive("duration_session"))
                rejectNextRequest(NextRequestException.Reason.UNSUPPORTED_ACCEPTANCE)
            require(result.status in setOf("applied", "already_applied") && result.revision != null && result.revision > 0 &&
                result.entity != null && result.errorCode == null && result.message == null && result.baseEntity == null &&
                result.localEntity == null && result.conflictingFields.isEmpty() && result.conflictKind == null && result.oneTimeConflict == null)
            require(listOf("one_time", "one_time_state_after").all { result.entity[it].let { value -> value == null || value == kotlinx.serialization.json.JsonNull } })
            val normalized = result.copy(status = "applied")
            val bytes = encodeSyncRequest(NextSyncOperationResult.serializer(), normalized)
            decodeFrozenSyncRequest(bytes, NextSyncOperationResult.serializer())
            val resultJson = bytes.toString(Charsets.UTF_8)
            val previousHash = NextRequestSql.rowHash(sql, "next_acceptances", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id))
            val outcome = if (previousHash != null) {
                val receipt = requireNotNull(dao.acceptance(NEXT_OPERATION, id))
                require(receipt.kind == NEXT_OPERATION && receipt.requestId == id && receipt.originHash == originHash &&
                    receipt.transmissionHash == transmissionHash && receipt.resultHash == nextRequestHash(receipt.resultJson.toByteArray(Charsets.UTF_8)))
                if (decodeFrozenSyncRequest(receipt.resultJson.toByteArray(Charsets.UTF_8), NextSyncOperationResult.serializer()) != normalized)
                    rejectNextRequest(NextRequestException.Reason.RESULT_CHANGED)
                // Absence alone proves nothing. Here it is justified by the exact atomic receipt.
                if (NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(rawOrigin.queueId)) != null ||
                    database.syncOutboxDao().getByOperationId(id) != null)
                    rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
                NextOperationAcceptance.REPLAYED
            } else {
                val verifiedOrigin = origin(access, NEXT_OPERATION, id)
                require(verifiedOrigin == rawOrigin)
                val queue = requireNotNull(database.syncOutboxDao().getById(rawOrigin.queueId))
                // A later callback cannot jump a predecessor and install an incorrect merge base.
                val predecessors = database.syncOutboxDao().getAll() + database.syncOutboxDao().getDeadLetters()
                require(predecessors.none { it.id < queue.id && it.recordType == queue.recordType && it.entityUuid == queue.entityUuid })
                val change = SyncV2Change(sequence = 0, entityType = operation.entityType, entityUuid = operation.entityUuid,
                    operation = "upsert", revision = requireNotNull(result.revision), payload = requireNotNull(result.entity),
                    changedAt = requireNotNull(result.entity["updated_at"] as? kotlinx.serialization.json.JsonPrimitive).also { require(it.isString) }.content)
                auditShadow(change.entityType, change.entityUuid)
                if (change.entityType in setOf("activity_event", "metric_observation", "activity_metric_link")) {
                    // Common-fact revert validation reads historical shadows, including invisible events.
                    val ids = sql.query("SELECT entityUuid FROM sync_entity_state WHERE entityType='activity_event' LIMIT 10001").use { c ->
                        buildList { while (c.moveToNext()) {
                            require(c.getType(0) == android.database.Cursor.FIELD_TYPE_STRING)
                            add(c.getString(0))
                        } }
                    }
                    require(ids.size <= 10000)
                    ids.forEach { auditShadow("activity_event", it) }
                }
                when (operation.entityType) {
                    "plan_node", "metric" -> {
                        if (operation.entityType == "plan_node") {
                            require(operation.payload["node_kind"] == result.entity["node_kind"])
                        }
                        NextStructureStore(database).restoreInTransaction(listOf(change), queue.id)
                    }
                    else -> {
                        if (operation.entityType in setOf("activity_event", "metric_observation")) require(operation.baseRevision in listOf(null, 0L))
                        NextCommonFactStore(database).restoreInTransaction(listOf(change), requireNotNull(access.deviceId), queue.id, operation)
                    }
                }
                val acceptedShadow = requireNotNull(NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?",
                    arrayOf(change.entityType, change.entityUuid)))
                val receipt = NextAcceptanceEntity(NEXT_OPERATION, id, originHash, transmissionHash, nextRequestHash(bytes), resultJson)
                dao.insertAcceptance(receipt)
                check(NextRequestSql.rowHash(sql, "next_acceptances", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id)) != null)
                check(dao.acceptance(NEXT_OPERATION, id) == receipt)
                check(NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(queue.id)) == rawOrigin.sourceHash)
                database.syncOutboxDao().deleteById(queue.id)
                check(NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(queue.id)) == null &&
                    database.syncOutboxDao().getByOperationId(id) == null)
                // Trigger faults must not rewrite durable original evidence while acknowledging.
                check(NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id)) == originHash &&
                    NextRequestSql.rowHash(sql, "next_transmissions", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id)) == transmissionHash)
                check(NextRequestSql.rowHash(sql, "next_acceptances", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id)) != null &&
                    dao.acceptance(NEXT_OPERATION, id) == receipt)
                check(NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?",
                    arrayOf(change.entityType, change.entityUuid)) == acceptedShadow)
                NextOperationAcceptance.COMMITTED
            }
            NextRequestSql.requireOutboxEnabled(sql)
            authorize(access)
            outcome
        }
    }

    private suspend fun auditShadow(type: String, id: String) {
        val sql = database.openHelper.writableDatabase
        if (NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?", arrayOf(type, id)) == null) return
        val state = requireNotNull(database.syncOutboxDao().getState(type, id))
        require(state.entityType == type && state.entityUuid == id && state.revision > 0 && isContractUuid(id))
        val payload = requireNotNull(state.payloadJson)
        require(state.payloadHash == syncPayloadHash(payload))
    }

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
