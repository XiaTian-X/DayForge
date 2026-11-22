package com.dayforge.data.repository

import androidx.room.withTransaction
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

/** G0 metric-only ACK: independent structure merge with STRICT block captures.
 * Remaining original request/causal/recovery validators and both fresh auth boundaries stay.
 * No runtime certificate or bounded incremental SQL cost is claimed.
 * No sender, timer entry, registration entry or production call site uses this candidate.
 */
internal class G0MetricBlockAckProbe(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val blocks: G0MetricBlocks
) {
    suspend fun acceptOperation(delivery: NextCoreDelivery<NextSyncPushResponse>): NextOperationAcceptance =
        acceptOperation(delivery, NextStructuralCausalStore.PureMemo())

    private suspend fun acceptOperation(delivery: NextCoreDelivery<NextSyncPushResponse>,
        memo: NextStructuralCausalStore.PureMemo): NextOperationAcceptance = accountScope {
        val access = delivery.access
        timed("entry_auth") { authorize(access) }
        timed("transaction") { database.withTransaction {
            timed("domain") {
            val sql = database.openHelper.writableDatabase
            NextRequestSql.requireOutboxEnabled(sql)
            val terminals = NextRecoveryAuditStore(database).validatedMembers(access)
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
            val restartProof = NextRestartBindingStore(database).requireReady(access, rawOrigin)
            val transmission = requireNotNull(dao.transmission(NEXT_OPERATION, id))
            val rounds = NextChallengeStore(database)
            val roundOrigin = roundOperationIntent(rawOrigin.intentJson)
            val goalFrontierProof = roundOrigin?.let { NextGoalChildFrontierStore(database).requireAccepted(it, access) }
            val currentCheckpoint = if (roundOrigin == null) {
                rounds.requirePlainInTransaction()
                require(delivery.challengeMetadata == null)
                null
            } else rounds.activeInTransaction(access)
            validate(rawOrigin, transmission, access)
            val operation = validateNextOperationEnvelope(rawOrigin.intentJson, transmission.wireBytes, requireNotNull(access.deviceId))
            require(operation.entityType == "metric" && operation.action == "upsert" && operation.entityUuid == blocks.metricUuid)
            val imports = NextConfigImportBarrier(database)
            val replay = NextRequestSql.rowHash(sql, "next_acceptances", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id)) != null
            val importProof = imports.requireReady(operation, access, delivery.configImportProof, replay)
            check(importProof == delivery.configImportProof) { "CONFIG_IMPORT_ACCEPTANCES_CHANGED" }
            val mergedMetadata = if (roundOrigin == null) null else {
                val metadata = requireNotNull(delivery.challengeMetadata) { "SYNC_CHALLENGE_ACK_REQUIRED" }
                val frozen = decodeFrozenSyncRequest(encodeSyncRequest(ChallengeMetadata.serializer(), metadata), ChallengeMetadata.serializer())
                val response = RoundSyncPushResponse(delivery.result.results, 1, frozen.checkpoints, frozen.births)
                validateRoundResultBinding(decodeFrozenSyncRequest(transmission.wireBytes, RoundSyncPushRequest.serializer()), response)
                rounds.acknowledgeInTransaction(access, frozen)
            }
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
                blocks.merge(change, queue.id)
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
            if (roundOrigin != null) check(NextGoalChildFrontierStore(database).requireAccepted(roundOrigin, access) == goalFrontierProof)
            require(causal.resolve(id, access) == id)
            require(NextRestartBindingStore(database).requireReady(access, rawOrigin) == restartProof) { "SYNC_RESTART_PLAN_PROOF_CHANGED" }
            check(imports.requireReady(operation, access, importProof, replay) == importProof) { "CONFIG_IMPORT_ACCEPTANCES_CHANGED" }
            imports.finishIfAccepted(access)
            NextRequestSql.requireOutboxEnabled(sql)
            if (currentCheckpoint != null) {
                val actual = rounds.activeInTransaction(access)
                check(actual.first == currentCheckpoint.first && actual.second == mergedMetadata) { "SYNC_CHALLENGE_ACK_CHANGED" }
            }
            require(NextRecoveryAuditStore(database).validatedMembers(access) == terminals) { "RECOVERY_TERMINAL_CHANGED" }
            outcome
            }.also {
                G0PhaseTrace.current.get()?.beforeFinal?.invoke()
                blocks.stageAck()
                timed("final_auth") { authorize(access) }
            }
        } }
    }

    private suspend fun auditShadow(type: String, id: String) {
        val sql = database.openHelper.writableDatabase
        if (NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?", arrayOf(type, id)) == null) return
        val state = requireNotNull(database.syncOutboxDao().getState(type, id))
        require(state.entityType == type && state.entityUuid == id && state.revision > 0 && isContractUuid(id))
        val payload = requireNotNull(state.payloadJson)
        require(state.payloadHash == syncPayloadHash(payload))
    }

    private suspend fun origin(access: LocalSyncAccess, kind: String, id: String,
        requirePermission: Boolean = true): NextRequestOriginEntity =
        NextOriginalRequestProofStore(database).liveOrigin(access, kind, id, requirePermission)

    private suspend fun validate(origin: NextRequestOriginEntity, row: NextTransmissionEntity, access: LocalSyncAccess) =
        NextOriginalRequestProofStore(database).journal(origin, row, access)

    private suspend fun authorize(access: LocalSyncAccess) {
        val allowed = if (access.deviceId != null) tokens.isCurrentSyncAuthority(access)
            else tokens.syncAuthenticationSnapshot(access) != null
        if (!allowed) rejectNextRequest(NextRequestException.Reason.STALE_ACCESS)
    }

    private suspend fun <T> timed(name: String, block: suspend () -> T): T =
        G0PhaseTrace.current.get().let { trace -> if (trace == null) block() else trace.stage(name, block) }

    private suspend fun <T> accountScope(block: suspend () -> T): T {
        val trace = G0PhaseTrace.current.get()
        val requested = trace?.now()
        return timed("account_scope") {
            sessions.exclusive {
                if (requested != null) trace.record("account_wait", requested, true)
                timed("account_body", block)
            }
        }
    }
}
