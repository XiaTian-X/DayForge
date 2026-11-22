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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** G0-only ACK extraction. Business statements/helpers are copied from the original store.
 * This is credential-purpose isolation, NOT an independent incremental validator.
 * No sender, timer entry, registration entry or production call site uses this candidate.
 */
internal class G0AckCredentialProbe(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val splitCredentials: Boolean
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
            val parentDeletionProofs = mutableMapOf<Pair<String, String>, String>()
            val countDays = mutableListOf<Pair<com.dayforge.data.local.entity.CountDayEntity, String>>()
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
                if (operation.action == "delete")
                    NextPlanDeletionStore(database).requireReady(queue)
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
                countDays += NextCountDayStore(database).proofs(listOf(change), allowDeletedParents = true)
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
            NextCountDayStore(database).verify(countDays)
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
            val operation = decodeNextOperationIntent(proof.intentJson)
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
            val operation = validateNextOperationEnvelope(original.intentJson, transmission.wireBytes, requireNotNull(access.deviceId))
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

    private suspend fun origin(access: LocalSyncAccess, kind: String, id: String,
        requirePermission: Boolean = true): NextRequestOriginEntity =
        NextOriginalRequestProofStore(database).liveOrigin(access, kind, id, requirePermission)

    private suspend fun validate(origin: NextRequestOriginEntity, row: NextTransmissionEntity, access: LocalSyncAccess) =
        NextOriginalRequestProofStore(database).journal(origin, row, access)

    private suspend fun authorize(access: LocalSyncAccess) {
        val allowed = if (splitCredentials && access.deviceId != null) tokens.isCurrentSyncAuthority(access)
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
    companion object {
        const val SOURCE_SHA256 = "e9558f18c741f46c1f9c22a35d0fe234af7f1b148668e2ad56f78d52945a828f"
        private val originalLines = intArrayOf(1,2,3,5,6,7,8,9,10,11,12,13,14,15,16,17,18,0,0,0,30,0,32,33,34,0,37,234,235,236,237,0,239,0,0,0,242,243,244,245,246,247,248,249,250,251,252,253,254,255,256,257,258,259,260,261,262,263,264,265,266,267,268,269,270,271,272,273,274,275,276,277,278,279,280,281,282,283,284,285,286,287,288,289,290,291,292,293,294,295,296,297,298,299,300,301,302,303,304,305,306,307,308,309,310,311,312,313,314,315,316,317,318,319,320,321,322,323,324,325,326,327,328,329,330,331,332,333,334,335,336,337,338,339,340,341,342,343,344,345,346,347,348,349,350,351,352,353,354,355,356,357,358,359,360,361,362,363,364,365,366,367,368,369,370,371,372,373,374,375,377,0,0,0,0,0,379,380,381,382,383,384,385,386,387,395,396,397,398,399,400,401,402,403,404,405,406,407,408,409,410,411,412,413,414,415,416,417,418,419,420,421,422,423,424,425,426,427,428,429,430,431,432,433,434,435,436,437,438,439,440,441,442,443,444,445,446,447,448,449,450,451,452,453,454,455,456,457,458,459,460,572,573,574,619,620,621,622,623,0,0,0,625,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0)
        fun originalLine(line: Int): Int = originalLines.getOrElse(line - 1) { 0 }
    }
}
