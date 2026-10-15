package com.dayforge.data.repository

import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.entity.NextRequestOriginEntity
import com.dayforge.data.local.entity.NextTransmissionEntity
import com.dayforge.data.model.NextSyncProblem
import com.dayforge.domain.model.isContractUuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** Bound persisted failure, not an acceptance, parent birth ticket or conflict payload authority. */
internal data class NextOriginalRejection(val origin: NextRequestOriginEntity, val problem: NextSyncProblem) {
    val isPermanent get() = problem.code !in setOf("MISSING_PREDECESSOR", "OPERATION_IN_PROGRESS")
}

/** Actual live SQL evidence, not a terminal/accepted ancestor. Complete group proof is separate. */
internal data class NextOriginalPendingProof(val origin: NextRequestOriginEntity, val originHash: String,
    val transmissionHash: String?, val rejectionHash: String?, val state: NextRecoveryRequestState) {
    val evidenceHash get() = nextRequestHash(listOf(origin.kind, origin.requestId, originHash, origin.sourceHash,
        transmissionHash ?: "none", rejectionHash ?: "none", state.name).joinToString(";").toByteArray(Charsets.UTF_8))
}

/**
 * Shared SELECT-only original proof used by senders, display and recovery classification. Caller
 * owns its account lock and Room transaction. No authority capture, HTTP, prepare or mutation.
 * Live origins ALWAYS require the actual source row; retired evidence uses the complete audit reader.
 */
internal class NextOriginalRequestProofStore(private val database: HabitDatabase) {
    private suspend fun requireNotAbandoned(access: LocalSyncAccess, kind: String, id: String) {
        // Only complete original-source/group evidence earns the terminal classification. A partial
        // audit still fails, and neither a valid nor damaged terminal ever grants a live origin/ACK.
        if (NextRecoveryAuditStore(database).source(access, NextRecoveryKey(kind, id)) != null)
            rejectNextRequest(NextRequestException.Reason.LOCALLY_ABANDONED)
    }

    suspend fun liveOrigin(access: LocalSyncAccess, kind: String, id: String,
        requirePermission: Boolean = true): NextRequestOriginEntity {
        check(database.inTransaction()); currentCoroutineContext().ensureActive()
        require(kind in setOf(NEXT_OPERATION, NEXT_TIMER) && isContractUuid(id))
        requireNotAbandoned(access, kind, id)
        if (NextRequestSql.rowHash(database, "next_request_origins", "kind=? AND requestId=?", arrayOf(kind, id)) == null)
            rejectNextRequest(NextRequestException.Reason.OLD_INTENT)
        val row = requireNotNull(database.nextRequestDao().origin(kind, id))
        if (row.kind != kind || row.requestId != id || row.protocol != 5 || row.queueId <= 0 ||
            row.sourceHash.length != 64 || row.sourceHash.any { it !in "0123456789abcdef" } ||
            (row.serverInstanceId == null) != (row.syncEpoch == null))
            rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
        if (row.accountId != access.session.authentication.userId ||
            (row.serverInstanceId != null && (row.serverInstanceId != access.session.serverInstanceId || row.syncEpoch != access.session.syncEpoch)))
            rejectNextRequest(NextRequestException.Reason.TRANSMISSION_CONTEXT_CHANGED)
        if (NextRequestSql.rowHash(database, NextRequestSql.table(kind), "id=?", arrayOf(row.queueId)) != row.sourceHash)
            rejectNextRequest(NextRequestException.Reason.SOURCE_CHANGED)
        val permission = if (kind == NEXT_OPERATION) {
            val queue = requireNotNull(database.syncOutboxDao().getById(row.queueId))
            val operation = decodeNextOperationIntent(row.intentJson)
            require(queue.operationId == id && operation.operationId == id && isContractUuid(operation.entityUuid))
            val type = when (queue.recordType) {
                "habit" -> "plan_node"; "metric" -> "metric"; "completion" -> "activity_event"
                "metric_log" -> "metric_observation"; "link" -> "activity_metric_link"
                else -> rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
            }
            require(operation.entityType == type)
            if (queue.recordType == "completion" && queue.action == "delete") {
                require(operation.action == "upsert" && operation.entityUuid != queue.entityUuid &&
                    operation.payload["reverts_event_uuid"] == JsonPrimitive(queue.entityUuid))
            } else require(operation.action == queue.action && operation.entityUuid == queue.entityUuid)
            if (queue.recordType in setOf("completion", "metric_log")) "facts.append" else "structure.write"
        } else {
            val queue = requireNotNull(database.timeLogDao().getTimerCommand(row.queueId))
            val command = decodeNextTimerIntent(row.intentJson).command
            require(queue.commandId == id && timerRequest(queue) == command.copy(startPolicy = null))
            command.startPolicy?.let { require(command.commandType == "start"); it.validate() }
            "timer.control"
        }
        if (requirePermission && permission !in access.capabilities) rejectNextRequest(NextRequestException.Reason.PERMISSION_DENIED)
        return row
    }

    suspend fun inspectionOrigin(access: LocalSyncAccess, kind: String, id: String): NextRequestOriginEntity {
        val source = liveOrigin(access, kind, id, requirePermission = false)
        val captured = if (kind == NEXT_OPERATION) requireNotNull(roundOperationIntent(source.intentJson)).capturedDeviceId
            else requireNotNull(roundTimerIntent(source.intentJson)).capturedDeviceId
        require(captured == access.deviceId && source.serverInstanceId == access.session.serverInstanceId &&
            source.syncEpoch == access.session.syncEpoch)
        return source
    }

    /** Absence of a journal is usable only with the complete NEW original/live source proof. */
    suspend fun pending(access: LocalSyncAccess, kind: String, id: String): NextOriginalPendingProof {
        val source = inspectionOrigin(access, kind, id)
        val args = arrayOf<Any>(kind, id)
        val originHash = requireNotNull(NextRequestSql.rowHash(database, "next_request_origins", "kind=? AND requestId=?", args))
        require(NextRequestSql.rowHash(database, "next_acceptances", "kind=? AND requestId=?", args) == null)
        // An ordinary source cannot borrow/ignore the independent once HTTP journal.
        if (kind == NEXT_OPERATION) require(NextRequestSql.rowHash(database, "one_time_transmissions",
            "operationId=?", arrayOf(id)) == null)
        val sent = NextRequestSql.rowHash(database, "next_transmissions", "kind=? AND requestId=?", args)
        val failed = NextRequestSql.rowHash(database, "next_rejections", "kind=? AND requestId=?", args)
        val state = if (failed != null) {
            val rejected = rejection(access, kind, id)
            require(rejected.origin == source && sent != null)
            if (rejected.isPermanent) NextRecoveryRequestState.PERMANENT_REJECTION else NextRecoveryRequestState.UNKNOWN_RESULT
        } else if (sent != null) {
            journal(source, requireNotNull(database.nextRequestDao().transmission(kind, id)), access)
            NextRecoveryRequestState.UNKNOWN_RESULT
        } else {
            if (kind == NEXT_OPERATION) {
                val row = requireNotNull(database.syncOutboxDao().getById(source.queueId))
                // Ordinary trigger rows have no payload. The actual completion-prompt producer
                // instead commits a metric payload AND an immutable local receipt with its origin.
                // Inspect that exact birth, never adopt arbitrary legacy pre-filled payloads.
                row.payloadJson?.let { payload ->
                    val operation = decodeNextOperationIntent(source.intentJson)
                    require(operation.entityType == "metric_observation" && operation.action == "upsert" && operation.baseRevision == null &&
                        row.recordType == "metric_log" && row.entityUuid == operation.entityUuid && row.wireEntityUuid == operation.entityUuid &&
                        row.referenceUuid == operation.payload.getValue("metric_uuid").jsonPrimitive.content &&
                        payload == operation.payload.toString())
                    requireNotNull(NextRequestSql.rowHash(database, "local_fact_submissions", "operationId=?", arrayOf(id)))
                    require(database.completionFollowUpDao().submission(id) ==
                        com.dayforge.data.local.entity.LocalFactSubmissionEntity(id, "metric_observation", operation.entityUuid,
                            requireNotNull(row.referenceUuid), payload))
                }
                require(row.baseRevision == null && row.basePayloadJson == null &&
                    row.attemptedAt == null && row.attemptCount == 0 && row.lastError == null &&
                    row.errorCode == null && row.deadLetteredAt == null)
            } else {
                val row = requireNotNull(database.timeLogDao().getTimerCommand(source.queueId))
                require(row.attemptCount == 0 && row.lastError == null && row.errorCode == null && row.deadLetteredAt == null)
            }
            NextRecoveryRequestState.NEVER_SENT
        }
        return NextOriginalPendingProof(source, originHash, sent, failed, state)
    }

    /** Exact source/context/wire binding shared with acceptance; does not infer a live source/ACK. */
    suspend fun journal(origin: NextRequestOriginEntity, row: NextTransmissionEntity, access: LocalSyncAccess) {
        check(database.inTransaction()); currentCoroutineContext().ensureActive()
        requireNotAbandoned(access, origin.kind, origin.requestId)
        if (row.kind != origin.kind || row.requestId != origin.requestId || row.queueId != origin.queueId || row.protocol != 5)
            rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
        if (row.accountId != origin.accountId || row.accountId != access.session.authentication.userId ||
            row.serverInstanceId != access.session.serverInstanceId || row.syncEpoch != access.session.syncEpoch || row.deviceId != access.deviceId)
            rejectNextRequest(NextRequestException.Reason.TRANSMISSION_CONTEXT_CHANGED)
        if (nextRequestHash(row.wireBytes) != row.wireHash) rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
        NextRestartBindingStore(database).requireReady(access, origin)
        if (row.kind == NEXT_OPERATION) {
            validateNextOperationEnvelope(origin.intentJson, row.wireBytes, row.deviceId)
            if (roundOperationIntent(origin.intentJson) != null) NextChallengeStore(database).activeInTransaction(access)
        } else {
            require(row.kind == NEXT_TIMER)
            validateNextTimerEnvelope(origin.intentJson, row.wireBytes, row.deviceId)
            if (roundTimerIntent(origin.intentJson) != null) NextChallengeStore(database).activeInTransaction(access)
        }
    }

    /** Includes fully bound transient results; they are never relabelled as permanent rejection. */
    suspend fun rejection(access: LocalSyncAccess, kind: String, id: String): NextOriginalRejection {
        check(database.inTransaction()); currentCoroutineContext().ensureActive()
        val source = inspectionOrigin(access, kind, id)
        val args = arrayOf<Any>(kind, id)
        requireNotNull(NextRequestSql.rowHash(database, "next_rejections", "kind=? AND requestId=?", args))
        val row = requireNotNull(database.nextSyncStateDao().rejection(kind, id))
        require(row.kind == kind && row.requestId == id &&
            row.originHash == NextRequestSql.rowHash(database, "next_request_origins", "kind=? AND requestId=?", args) &&
            row.transmissionHash == NextRequestSql.rowHash(database, "next_transmissions", "kind=? AND requestId=?", args) &&
            row.resultHash == nextRequestHash(row.resultJson.toByteArray(Charsets.UTF_8)) &&
            NextRequestSql.rowHash(database, "next_acceptances", "kind=? AND requestId=?", args) == null)
        val transmitted = requireNotNull(database.nextRequestDao().transmission(kind, id))
        journal(source, transmitted, access)
        val metadata = NextChallengeStore(database).activeInTransaction(access).second
        val problem = if (kind == NEXT_OPERATION) {
            val operation = validateNextOperationEnvelope(source.intentJson, transmitted.wireBytes, requireNotNull(access.deviceId))
            val result = decodeFrozenSyncRequest(row.resultJson.toByteArray(Charsets.UTF_8), NextSyncOperationResult.serializer())
            validateTaskResultBinding(operation, result)
            validateRoundResultBinding(decodeFrozenSyncRequest(transmitted.wireBytes, RoundSyncPushRequest.serializer()),
                RoundSyncPushResponse(listOf(result), 1, metadata.checkpoints, metadata.births))
            require(result.status in setOf("conflict", "rejected") && !result.errorCode.isNullOrBlank())
            NextSyncProblem(kind, id, operation.entityType, operation.entityUuid, requireNotNull(result.errorCode), result.conflictingFields)
        } else {
            val command = validateNextTimerEnvelope(source.intentJson, transmitted.wireBytes, requireNotNull(access.deviceId))
            val result = decodeFrozenSyncRequest(row.resultJson.toByteArray(Charsets.UTF_8), TimerCommandResult.serializer())
            require(result.commandId == command.commandId && result.sessionId == command.sessionId &&
                result.status in setOf("conflict", "rejected") && !result.errorCode.isNullOrBlank())
            result.session?.let { timer ->
                require(timer.sessionId == command.sessionId)
                val birth = metadata.requireBirth("timer_session", timer.sessionId, timer.activityUuid)
                timer.completedEventId?.let {
                    require(metadata.requireBirth("activity_event", it, timer.activityUuid).head == birth.head)
                }
            }
            NextSyncProblem(kind, id, "timer_session", command.sessionId, requireNotNull(result.errorCode))
        }
        return NextOriginalRejection(source, problem)
    }
}
