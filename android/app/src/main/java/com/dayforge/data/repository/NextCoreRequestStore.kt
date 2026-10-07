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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

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
    suspend fun sendOperation(captured: LocalSyncAccess, operationId: String): NextCoreDelivery<NextSyncPushResponse>? =
        sendOperation(captured, operationId, NextStructuralCausalStore.PureMemo())

    private suspend fun sendOperation(captured: LocalSyncAccess, operationId: String,
        memo: NextStructuralCausalStore.PureMemo): NextCoreDelivery<NextSyncPushResponse>? {
        require(isContractUuid(operationId) && captured.deviceId != null)
        val access = captured.copy(capabilities = captured.capabilities.toSet())
        return http.session(access) { channel ->
            val prepared = prepare(access, NEXT_OPERATION, operationId, memo)
            val response = channel.pushFrozen(prepared.row.wireBytes)
            requireStillCurrent(access, prepared, memo)
            NextCoreDelivery(access, prepared.row.requestId, response, prepared.proof)
        }
    }

    suspend fun sendCommand(captured: LocalSyncAccess, commandId: String,
        firstSendOrder: (suspend (com.dayforge.data.local.entity.TimerCommandEntity) -> Unit)? = null): NextCoreDelivery<TimerCommandBatchResponse>? {
        require(isContractUuid(commandId) && captured.deviceId != null)
        val access = captured.copy(capabilities = captured.capabilities.toSet())
        return http.session(access) { channel ->
            val prepared = prepare(access, NEXT_TIMER, commandId, timerOrder = firstSendOrder)
            val response = channel.commandsFrozen(prepared.row.wireBytes)
            requireStillCurrent(access, prepared)
            NextCoreDelivery(access, commandId, response, prepared.proof)
        }
    }

    /** Timer acceptance shares the exact journal/source proof; callers already own the local transaction. */
    internal suspend fun requireTimerOriginInTransaction(access: LocalSyncAccess, id: String): NextRequestOriginEntity {
        check(database.inTransaction())
        return origin(access, NEXT_TIMER, id)
    }

    internal suspend fun validateTimerJournalInTransaction(origin: NextRequestOriginEntity,
        transmission: NextTransmissionEntity, access: LocalSyncAccess) {
        check(database.inTransaction())
        require(origin.kind == NEXT_TIMER && origin.protocol == 5 && origin.queueId > 0 &&
            origin.sourceHash.length == 64 && origin.sourceHash.all { it in "0123456789abcdef" } &&
            (origin.serverInstanceId == null) == (origin.syncEpoch == null))
        if (origin.serverInstanceId != null && (origin.serverInstanceId != access.session.serverInstanceId ||
            origin.syncEpoch != access.session.syncEpoch)) rejectNextRequest(NextRequestException.Reason.TRANSMISSION_CONTEXT_CHANGED)
        validate(origin, transmission, access)
    }

    private data class Prepared(val row: NextTransmissionEntity, val proof: String)

    /** Actual send → acceptance entrypoint. A rejected/conflicting delivery never consumes work. */
    suspend fun sendAndAcceptOperation(access: LocalSyncAccess, operationId: String): NextOperationAcceptance? {
        val memo = NextStructuralCausalStore.PureMemo()
        val saved = sessions.exclusive {
            authorize(access)
            database.withTransaction {
                val sql = database.openHelper.writableDatabase
                val actualId = NextStructuralCausalStore(database, memo).resolve(operationId, access)
                if (NextRequestSql.rowHash(sql, "next_acceptances", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, actualId)) == null) null
                else {
                    val receipt = requireNotNull(database.nextRequestDao().acceptance(NEXT_OPERATION, actualId))
                    val result = decodeFrozenSyncRequest(receipt.resultJson.toByteArray(Charsets.UTF_8), NextSyncOperationResult.serializer())
                    val proof = requireNotNull(NextRequestSql.rowHash(sql, "next_transmissions", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, actualId)))
                    NextCoreDelivery(access, actualId, NextSyncPushResponse(listOf(result)), proof)
                }
            }
        }
        val delivery = saved ?: sendOperation(access, operationId, memo) ?: return null
        return acceptOperation(delivery, memo)
    }

    /** Returns only after Room's outer COMMIT. No cursor/once/timer acknowledgement is inferred. */
    suspend fun acceptOperation(delivery: NextCoreDelivery<NextSyncPushResponse>): NextOperationAcceptance =
        acceptOperation(delivery, NextStructuralCausalStore.PureMemo())

    private suspend fun acceptOperation(delivery: NextCoreDelivery<NextSyncPushResponse>,
        memo: NextStructuralCausalStore.PureMemo): NextOperationAcceptance = sessions.exclusive {
        val access = delivery.access
        authorize(access)
        database.withTransaction {
            val sql = database.openHelper.writableDatabase
            NextRequestSql.requireOutboxEnabled(sql)
            val id = delivery.requestId
            require(isContractUuid(id) && delivery.result.results.size == 1)
            val causal = NextStructuralCausalStore(database, memo)
            require(causal.resolve(id, access) == id)
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
            val normalized = result.copy(status = "applied")
            val bytes = encodeSyncRequest(NextSyncOperationResult.serializer(), normalized)
            decodeFrozenSyncRequest(bytes, NextSyncOperationResult.serializer())
            val resultJson = bytes.toString(Charsets.UTF_8)
            if (operation.action == "upsert" && operation.entityType in setOf("plan_node", "metric", "activity_metric_link"))
                causal.validateStructuralResult(resultJson)
            val previousHash = NextRequestSql.rowHash(sql, "next_acceptances", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id))
            val parentDeletionProofs = mutableMapOf<Pair<String, String>, String>()
            val outcome = if (previousHash != null) {
                val receipt = requireNotNull(dao.acceptance(NEXT_OPERATION, id))
                require(receipt.kind == NEXT_OPERATION && receipt.requestId == id && receipt.originHash == originHash &&
                    receipt.transmissionHash == transmissionHash && receipt.resultHash == nextRequestHash(receipt.resultJson.toByteArray(Charsets.UTF_8)))
                if (decodeFrozenSyncRequest(receipt.resultJson.toByteArray(Charsets.UTF_8), NextSyncOperationResult.serializer()) != normalized)
                    rejectNextRequest(NextRequestException.Reason.RESULT_CHANGED)
                NextOrdinaryResultMapper.validate(operation, result, requireNotNull(access.deviceId))
                // Absence alone proves nothing. Here it is justified by the exact atomic receipt.
                if (NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(rawOrigin.queueId)) != null ||
                    database.syncOutboxDao().getByOperationId(id) != null)
                    rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
                NextOperationAcceptance.REPLAYED
            } else {
                val verifiedOrigin = origin(access, NEXT_OPERATION, id)
                require(verifiedOrigin == rawOrigin)
                val change = NextOrdinaryResultMapper.validate(operation, result, requireNotNull(access.deviceId))
                val queue = requireNotNull(database.syncOutboxDao().getById(rawOrigin.queueId))
                // A later callback cannot jump a predecessor and install an incorrect merge base.
                causal.requireHead(queue)
                auditShadow(change.entityType, change.entityUuid)
                if (operation.action == "upsert" && change.entityType in setOf("activity_event", "metric_observation", "activity_metric_link")) {
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
                if (operation.action == "delete") NextOrdinaryDeletionStore(database).acceptInTransaction(change, queue.id)
                else when (operation.entityType) {
                    "plan_node", "metric" -> {
                        NextStructureStore(database).restoreInTransaction(listOf(change), queue.id)
                    }
                    else -> {
                        if (operation.entityType in setOf("activity_event", "metric_observation")) require(operation.baseRevision in listOf(null, 0L))
                        NextCommonFactStore(database).restoreInTransaction(listOf(change), requireNotNull(access.deviceId), queue.id, operation) {
                            type, uuid ->
                            val proof = provenParentDeletion(access, type, uuid)
                            if (proof == null) false else {
                                val key = type to uuid
                                require(parentDeletionProofs.put(key, proof).let { it == null || it == proof })
                                true
                            }
                        }
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
            // Receipt/outbox triggers may change another intent; re-audit exact parent evidence after all writes.
            for ((key, proof) in parentDeletionProofs) require(provenParentDeletion(access, key.first, key.second) == proof)
            require(causal.resolve(id, access) == id)
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

    /** Absence alone is never authorization to skip a parent FK projection. */
    private suspend fun provenParentDeletion(access: LocalSyncAccess, type: String, uuid: String): String? {
        require(type in setOf("plan_node", "metric") && isContractUuid(uuid))
        val record = if (type == "plan_node") "habit" else "metric"
        val outbox = database.syncOutboxDao()
        val sql = database.openHelper.writableDatabase
        val pending = (outbox.getAll() + outbox.getDeadLetters()).filter {
            it.recordType == record && it.entityUuid == uuid && it.action == "delete"
        }
        for (queue in pending) {
            val proof = origin(access, NEXT_OPERATION, queue.operationId)
            val operation = decodeFrozenSyncRequest(proof.intentJson.toByteArray(Charsets.UTF_8), SyncV2Operation.serializer())
            validateNextSyncOperation(operation)
            require(operation.entityType == type && operation.entityUuid == uuid && operation.action == "delete")
            val originHash = requireNotNull(NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?",
                arrayOf(NEXT_OPERATION, queue.operationId)))
            return "pending:${queue.operationId}:$originHash:${proof.sourceHash}"
        }
        auditShadow(type, uuid)
        val state = outbox.getState(type, uuid) ?: return null
        if (!state.deleted) return null
        // Narrow SQL candidates only; exact typed origin/envelope/receipt proof below decides identity.
        val ids = sql.query("SELECT requestId FROM next_acceptances WHERE kind=? AND resultJson LIKE ? LIMIT 10001",
            arrayOf(NEXT_OPERATION, "%$uuid%")).use { c -> buildList {
            while (c.moveToNext()) {
                require(c.getType(0) == android.database.Cursor.FIELD_TYPE_STRING)
                add(c.getString(0))
            }
        } }
        require(ids.size <= 10000)
        val dao = database.nextRequestDao()
        for (id in ids) {
            require(isContractUuid(id))
            val receiptHash = requireNotNull(NextRequestSql.rowHash(sql, "next_acceptances", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id)))
            val receipt = requireNotNull(dao.acceptance(NEXT_OPERATION, id))
            require(receipt.resultHash == nextRequestHash(receipt.resultJson.toByteArray(Charsets.UTF_8)))
            val result = decodeFrozenSyncRequest(receipt.resultJson.toByteArray(Charsets.UTF_8), NextSyncOperationResult.serializer())
            if (result.entityType != type || result.entityUuid != uuid) continue
            val originHash = requireNotNull(NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id)))
            val transmissionHash = requireNotNull(NextRequestSql.rowHash(sql, "next_transmissions", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id)))
            val original = requireNotNull(dao.origin(NEXT_OPERATION, id))
            val transmission = requireNotNull(dao.transmission(NEXT_OPERATION, id))
            require(original.kind == NEXT_OPERATION && original.requestId == id && original.protocol == 5 && original.queueId > 0 &&
                original.sourceHash.length == 64 && original.sourceHash.all { it in "0123456789abcdef" } &&
                (original.serverInstanceId == null) == (original.syncEpoch == null))
            if (original.serverInstanceId != null && (original.serverInstanceId != access.session.serverInstanceId || original.syncEpoch != access.session.syncEpoch))
                rejectNextRequest(NextRequestException.Reason.TRANSMISSION_CONTEXT_CHANGED)
            validate(original, transmission, access)
            require(receipt.kind == NEXT_OPERATION && receipt.requestId == id && receipt.originHash == originHash &&
                receipt.transmissionHash == transmissionHash && result.status == "applied")
            val operation = decodeFrozenSyncRequest(transmission.wireBytes, NextSyncPushRequest.serializer()).operations.single()
            if (operation.action != "delete") continue
            val change = NextOrdinaryResultMapper.validate(operation, result, requireNotNull(access.deviceId))
            require(change.entityType == type && change.entityUuid == uuid && change.revision <= state.revision)
            if (change.revision == state.revision) require(Json.parseToJsonElement(requireNotNull(state.payloadJson)).jsonObject == change.payload)
            require(NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(original.queueId)) == null &&
                outbox.getByOperationId(id) == null)
            check(NextRequestSql.rowHash(sql, "next_acceptances", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id)) == receiptHash)
            val stateHash = requireNotNull(NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?", arrayOf(type, uuid)))
            return "accepted:$id:$receiptHash:$originHash:$transmissionHash:$stateHash"
        }
        return null
    }

    private suspend fun prepare(access: LocalSyncAccess, kind: String, requestedId: String,
        memo: NextStructuralCausalStore.PureMemo? = null,
        timerOrder: (suspend (com.dayforge.data.local.entity.TimerCommandEntity) -> Unit)? = null): Prepared = sessions.exclusive {
        authorize(access)
        database.withTransaction {
            val causal = if (kind == NEXT_OPERATION) NextStructuralCausalStore(database, requireNotNull(memo)) else null
            val id = causal?.prepare(requestedId, access) ?: requestedId
            val origin = origin(access, kind, id)
            val dao = database.nextRequestDao()
            val sql = database.openHelper.writableDatabase
            val oldProof = NextRequestSql.rowHash(sql, "next_transmissions", "kind=? AND requestId=?", arrayOf(kind, id))
            val row = if (oldProof == null) {
                val bytes = if (kind == NEXT_OPERATION) {
                    val operation = decodeFrozenSyncRequest(origin.intentJson.toByteArray(Charsets.UTF_8), SyncV2Operation.serializer())
                    require(operation.operationId == id)
                    // Mutable deletes cannot jump their predecessor. Immutable undo has a new wire ID;
                    // preserve its original send/replay behavior, with dependency checks at acceptance.
                    if (operation.action == "delete") requireNotNull(causal).requireHead(
                        requireNotNull(database.syncOutboxDao().getById(origin.queueId)))
                    encodeSyncRequest(NextSyncPushRequest.serializer(), NextSyncPushRequest(requireNotNull(access.deviceId), listOf(operation)))
                } else {
                    val command = decodeFrozenSyncRequest(origin.intentJson.toByteArray(Charsets.UTF_8), TimerCommandRequest.serializer())
                    require(command.commandId == id)
                    timerOrder?.invoke(requireNotNull(database.timeLogDao().getTimerCommand(origin.queueId)))
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
            check(origin(access, kind, id) == origin)
            if (causal != null) require(causal.resolve(requestedId, access) == id)
            NextRequestSql.requireOutboxEnabled(sql)
            authorize(access)
            Prepared(stored, proof)
        }
    }

    private suspend fun requireStillCurrent(access: LocalSyncAccess, prepared: Prepared,
        memo: NextStructuralCausalStore.PureMemo? = null) = sessions.exclusive {
        authorize(access)
        database.withTransaction {
            val row = prepared.row
            if (row.kind == NEXT_OPERATION) require(NextStructuralCausalStore(database, requireNotNull(memo)).resolve(row.requestId, access) == row.requestId)
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
