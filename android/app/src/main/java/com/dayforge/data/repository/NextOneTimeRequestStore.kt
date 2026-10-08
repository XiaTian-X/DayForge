package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.encodeSyncRequest
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.NextAcceptanceEntity
import com.dayforge.data.local.entity.NextRequestOriginEntity
import com.dayforge.data.local.entity.NextTransmissionEntity
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountSessionCoordinator
import kotlinx.serialization.json.*

internal sealed interface NextOneTimeOutcome {
    data class Accepted(val acceptance: NextOperationAcceptance) : NextOneTimeOutcome
    data class Rejected(val result: NextSyncOperationResult) : NextOneTimeOutcome
}

/** Actual once HTTP/ACK path. Reuses once facts and projections; never changes an unknown request. */
internal class NextOneTimeRequestStore(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val http: NextSyncHttp,
    private val facts: OneTimeAcceptedEventStore,
    private val core: NextCoreRequestStore
) {
    private data class Proofs(val origin: String, val transmission: String?, val submission: String, val binding: String?)
    private data class Evidence(val origin: NextRequestOriginEntity, val prepared: PreparedOneTimeSubmission,
        val transmission: NextTransmissionEntity?, val hashes: Proofs)

    suspend fun sendAndAccept(captured: LocalSyncAccess, id: String): NextOneTimeOutcome? {
        val access = captured.copy(capabilities = captured.capabilities.toSet())
        require(isContractUuid(id))
        val saved = sessions.exclusive {
            authorize(access)
            database.withTransaction { savedOutcome(access, id) }
        }
        if (saved != null) return saved
        val delivery = send(access, id) ?: return null // Unsupported is not a completed synchronization.
        return accept(delivery)
    }

    suspend fun send(captured: LocalSyncAccess, id: String): NextCoreDelivery<NextSyncPushResponse>? {
        require(isContractUuid(id))
        val access = captured.copy(capabilities = captured.capabilities.toSet())
        authorize(access)
        return http.session(access) { channel ->
            val prepared = sessions.exclusive {
                authorize(access)
                database.withTransaction {
                    NextRequestSql.requireOutboxEnabled(database.openHelper.writableDatabase)
                    var evidence = evidence(access, id)
                    val profile = roundOperationIntent(evidence.origin.intentJson) != null
                    val checkpoint = if (profile) NextChallengeStore(database).activeInTransaction(access) else null
                    if (evidence.transmission == null) {
                        // Birth is already proved. A legacy attempt/binding must never be adopted.
                        require(evidence.hashes.binding == null && hash("next_acceptances", id) == null)
                        requireParent(access, evidence.prepared.operation, allowDeleted = false)
                        requireHead(evidence)
                        val original = requireNotNull(facts.prepareInTransaction(evidence.prepared.context,
                            activity(evidence.prepared.operation), journalled = true))
                        require(original == evidence.prepared)
                        val round = roundOperationIntent(evidence.origin.intentJson)
                        val bytes = if (round == null) encodeSyncRequest(NextSyncPushRequest.serializer(), NextSyncPushRequest(
                            requireNotNull(access.deviceId), listOf(original.operation)))
                        else encodeSyncRequest(RoundSyncPushRequest.serializer(), RoundSyncPushRequest(1,
                            requireNotNull(access.deviceId), listOf(original.operation), listOf(round.context)))
                        val transmission = NextTransmissionEntity(NEXT_OPERATION, id, evidence.origin.queueId, 5,
                            evidence.origin.accountId, requireNotNull(access.session.serverInstanceId),
                            requireNotNull(access.session.syncEpoch), requireNotNull(access.deviceId), nextRequestHash(bytes), bytes)
                        database.nextRequestDao().insertTransmission(transmission)
                        val current = evidence(access, id)
                        check(current.hashes.origin == evidence.hashes.origin && current.hashes.submission == evidence.hashes.submission &&
                            current.transmission?.wireBytes?.contentEquals(bytes) == true)
                        evidence = current
                    }
                    require(savedOutcome(access, id) == null)
                    requireHead(evidence)
                    NextRequestSql.requireOutboxEnabled(database.openHelper.writableDatabase)
                    if (checkpoint != null) check(NextChallengeStore(database).activeInTransaction(access) == checkpoint) {
                        "SYNC_CHALLENGE_SOURCE_CHANGED"
                    }
                    authorize(access)
                    evidence
                }
            }
            val transmission = requireNotNull(prepared.transmission)
            // Both the account mutex and Room write transaction have ended before network I/O.
            val round = if (roundOperationIntent(prepared.origin.intentJson) != null) channel.roundPushFrozen(transmission.wireBytes) else null
            val response = if (round == null) channel.pushFrozen(transmission.wireBytes) else NextSyncPushResponse(round.results)
            sessions.exclusive {
                authorize(access)
                database.withTransaction {
                    val current = evidence(access, id, consumed = hash("next_acceptances", id) != null)
                    require(current.hashes == prepared.hashes)
                    if (hash("next_acceptances", id) != null) require(savedOutcome(access, id) is NextOneTimeOutcome.Accepted)
                }
            }
            NextCoreDelivery(access, id, response, requireNotNull(prepared.hashes.transmission), round?.metadata())
        }
    }

    /** Full response and exact queue consumption commit together, including final COMMIT failures. */
    suspend fun accept(delivery: NextCoreDelivery<NextSyncPushResponse>): NextOneTimeOutcome = sessions.exclusive {
        val access = delivery.access
        authorize(access)
        require(delivery.result.results.size == 1)
        val result = decodeFrozenSyncRequest(encodeSyncRequest(NextSyncOperationResult.serializer(),
            delivery.result.results.single()), NextSyncOperationResult.serializer())
        database.withTransaction {
            val sql = database.openHelper.writableDatabase
            NextRequestSql.requireOutboxEnabled(sql)
            val saved = savedOutcome(access, delivery.requestId)
            val evidence = evidence(access, delivery.requestId, consumed = saved is NextOneTimeOutcome.Accepted,
                rejected = saved is NextOneTimeOutcome.Rejected)
            require(evidence.hashes.transmission == delivery.transmissionProof)
            val rounds = NextChallengeStore(database)
            val round = roundOperationIntent(evidence.origin.intentJson)
            val checkpoint = if (round == null) { require(delivery.challengeMetadata == null); null } else rounds.activeInTransaction(access)
            val merged = if (round == null) null else {
                val metadata = decodeFrozenSyncRequest(encodeSyncRequest(ChallengeMetadata.serializer(),
                    requireNotNull(delivery.challengeMetadata) { "SYNC_CHALLENGE_ACK_REQUIRED" }), ChallengeMetadata.serializer())
                // Once facts have their own state/history, never a recurring challenge birth.
                require(metadata.births.none { it.entityType == "activity_event" && it.entityUuid == evidence.prepared.operation.entityUuid })
                validateRoundResultBinding(decodeFrozenSyncRequest(requireNotNull(evidence.transmission).wireBytes, RoundSyncPushRequest.serializer()),
                    RoundSyncPushResponse(listOf(result), 1, metadata.checkpoints, metadata.births))
                rounds.acknowledgeInTransaction(access, metadata)
            }
            suspend fun verifyRounds() {
                if (checkpoint != null) {
                    val actual = rounds.activeInTransaction(access)
                    check(actual.first == checkpoint.first && actual.second == merged) { "SYNC_CHALLENGE_ACK_CHANGED" }
                } else rounds.requirePlainInTransaction()
            }
            if (saved != null) {
                when (saved) {
                    is NextOneTimeOutcome.Accepted -> {
                        val receipt = requireNotNull(database.nextRequestDao().acceptance(NEXT_OPERATION, delivery.requestId))
                        require(decodeFrozenSyncRequest(receipt.resultJson.toByteArray(Charsets.UTF_8),
                            NextSyncOperationResult.serializer()) == result.copy(status = "applied"))
                        validateSuccess(evidence.prepared, result)
                    }
                    is NextOneTimeOutcome.Rejected -> require(saved.result == result)
                }
                verifyRounds(); authorize(access)
                return@withTransaction saved
            }
            requireHead(evidence)
            val parentProof = requireParent(access, evidence.prepared.operation, allowDeleted = true)
            val sources = NextRequestSql.sources(sql, "sync_outbox")
            val outcome: NextOneTimeOutcome
            if (result.status in setOf("applied", "already_applied")) {
                validateSuccess(evidence.prepared, result)
                facts.acknowledgeInTransaction(evidence.prepared, result, journalled = true, deletedParent = parentProof != null)
                val bytes = encodeSyncRequest(NextSyncOperationResult.serializer(), result.copy(status = "applied"))
                val receipt = NextAcceptanceEntity(NEXT_OPERATION, delivery.requestId, evidence.hashes.origin,
                    requireNotNull(evidence.hashes.transmission), nextRequestHash(bytes), bytes.toString(Charsets.UTF_8))
                database.nextRequestDao().insertAcceptance(receipt)
                check(hash("next_acceptances", delivery.requestId) != null &&
                    database.nextRequestDao().acceptance(NEXT_OPERATION, delivery.requestId) == receipt)
                check(NextRequestSql.sources(sql, "sync_outbox") == sources - evidence.origin.queueId)
                val shadowHash = requireNotNull(NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?",
                    arrayOf("activity_event", result.entityUuid)))
                val shadow = requireNotNull(database.syncOutboxDao().getState("activity_event", result.entityUuid))
                require(!shadow.deleted && shadow.revision == result.revision &&
                    shadow.payloadJson?.let { Json.parseToJsonElement(it) } == result.entity &&
                    shadow.payloadHash == syncPayloadHash(requireNotNull(shadow.payloadJson)))
                check(evidence(access, delivery.requestId, consumed = true).hashes == evidence.hashes)
                if (parentProof == null) facts.verifyAcknowledgedInTransaction(evidence.prepared, result)
                check(NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?",
                    arrayOf("activity_event", result.entityUuid)) == shadowHash)
                require(savedOutcome(access, delivery.requestId) is NextOneTimeOutcome.Accepted)
                outcome = NextOneTimeOutcome.Accepted(NextOperationAcceptance.COMMITTED)
            } else {
                facts.rejectInTransaction(evidence.prepared, result, journalled = true, deletedParent = parentProof != null)
                val current = evidence(access, delivery.requestId, rejected = true)
                check(current.hashes.copy(binding = null) == evidence.hashes.copy(binding = null))
                val rejectedHash = requireNotNull(NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(evidence.origin.queueId)))
                check(NextRequestSql.sources(sql, "sync_outbox") == sources + (evidence.origin.queueId to rejectedHash))
                val rejection = savedOutcome(access, delivery.requestId)
                require(rejection == NextOneTimeOutcome.Rejected(result))
                outcome = requireNotNull(rejection)
            }
            if (parentProof != null) require(core.parentDeletionProofInTransaction(access,
                activity(evidence.prepared.operation)) == parentProof)
            NextRequestSql.requireOutboxEnabled(sql)
            verifyRounds()
            authorize(access)
            outcome
        }
    }

    /** Ordered drain for the unified runtime. No rejected or unsupported head counts as success. */
    suspend fun pushPending(access: LocalSyncAccess): Int {
        var count = 0
        while (true) {
            val ids = sessions.exclusive {
                authorize(access)
                database.withTransaction {
                    NextRequestSql.sources(database.openHelper.writableDatabase, "sync_outbox")
                    (database.syncOutboxDao().getAll() + database.syncOutboxDao().getDeadLetters())
                        .filter { it.recordType == "one_time_completion" }.sortedBy { it.id }.take(100)
                        .map { it.operationId }
                }
            }
            if (ids.isEmpty()) return count
            require(count <= 10_000 - ids.size)
            for (id in ids) {
                when (sendAndAccept(access, id)) {
                    is NextOneTimeOutcome.Accepted -> count++
                    is NextOneTimeOutcome.Rejected -> throw OneTimeLocalException(OneTimeLocalException.Reason.PENDING_REJECTED)
                    null -> rejectNextRequest(NextRequestException.Reason.UNSUPPORTED_ACCEPTANCE)
                }
            }
        }
    }

    private suspend fun evidence(access: LocalSyncAccess, id: String, consumed: Boolean = false,
        rejected: Boolean = false): Evidence {
        check(database.inTransaction())
        val sql = database.openHelper.writableDatabase
        val originHash = hash("next_request_origins", id) ?: rejectNextRequest(NextRequestException.Reason.OLD_INTENT)
        val origin = requireNotNull(database.nextRequestDao().origin(NEXT_OPERATION, id))
        require(origin.kind == NEXT_OPERATION && origin.requestId == id && origin.protocol == 5 && origin.queueId > 0 &&
            origin.sourceHash.matches(Regex("[0-9a-f]{64}")) && (origin.serverInstanceId == null) == (origin.syncEpoch == null))
        if (origin.accountId != access.session.authentication.userId || origin.serverInstanceId != null &&
            (origin.serverInstanceId != access.session.serverInstanceId || origin.syncEpoch != access.session.syncEpoch))
            rejectNextRequest(NextRequestException.Reason.TRANSMISSION_CONTEXT_CHANGED)
        val operation = decodeNextOperationIntent(origin.intentJson)
        val round = roundOperationIntent(origin.intentJson)
        if (round == null) NextChallengeStore(database).requirePlainInTransaction()
        else {
            NextChallengeStore(database).activeInTransaction(access)
            require(round.capturedDeviceId == access.deviceId && round.context.head == null && round.context.affectedHeads.isEmpty())
        }
        require(operation.operationId == id && operation.entityType == "activity_event" && operation.action == "upsert" &&
            operation.baseRevision == null && operation.payload["one_time"] is JsonObject)
        validateNextSyncOperation(operation)
        val activity = activity(operation)
        val prepared = PreparedOneTimeSubmission(OneTimeSyncContext(access.session, requireNotNull(access.deviceId)), operation)
        val birthHash = requireNotNull(linkedHash("local_fact_submissions", id))
        facts.requireReceipt(operation, activity)
        val transmissionHash = hash("next_transmissions", id)
        val bindingHash = linkedHash("one_time_transmissions", id)
        val transmission = if (transmissionHash == null) null else requireNotNull(database.nextRequestDao().transmission(NEXT_OPERATION, id)).also {
            require(it.kind == origin.kind && it.requestId == id && it.queueId == origin.queueId && it.protocol == 5 &&
                it.accountId == origin.accountId && it.serverInstanceId == access.session.serverInstanceId &&
                it.syncEpoch == access.session.syncEpoch && it.deviceId == access.deviceId && nextRequestHash(it.wireBytes) == it.wireHash)
            require(validateNextOperationEnvelope(origin.intentJson, it.wireBytes, it.deviceId) == operation)
            requireNotNull(bindingHash)
            facts.validateTransmission(requireNotNull(database.completionFollowUpDao().transmission(id)), prepared.context, operation, sending = true)
        }
        if (transmission == null && bindingHash != null) rejectNextRequest(NextRequestException.Reason.OLD_INTENT)
        val queueHash = NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(origin.queueId))
        if (consumed) require(queueHash == null && database.syncOutboxDao().getByOperationId(id) == null)
        else {
            val queue = requireNotNull(database.syncOutboxDao().getById(origin.queueId))
            require(queue.operationId == id && queue.referenceUuid == activity && facts.operation(queue) == operation &&
                queue.attemptedAt == null && queue.attemptCount == 0)
            if (rejected) {
                val result = decodeFrozenSyncRequest(requireNotNull(database.completionFollowUpDao().transmission(id)?.rejectionJson)
                    .toByteArray(Charsets.UTF_8), NextSyncOperationResult.serializer())
                validateTaskResultBinding(operation, result)
                require(result.status in setOf("conflict", "rejected") && !result.errorCode.isNullOrBlank() &&
                    result.entity == null && result.revision == null && result.baseEntity == null && result.localEntity == null &&
                    result.conflictingFields.isEmpty() && result.conflictKind == null && queue.deadLetteredAt != null &&
                    queue.deadLetteredAt >= 0 && queue.errorCode == result.errorCode && queue.lastError == (result.message ?: result.errorCode))
                require(NextRequestSql.sourceHash(queue.copy(deadLetteredAt = null, errorCode = null, lastError = null)) == origin.sourceHash)
            } else if (queueHash != origin.sourceHash || queue.deadLetteredAt != null || queue.errorCode != null || queue.lastError != null)
                rejectNextRequest(NextRequestException.Reason.SOURCE_CHANGED)
        }
        return Evidence(origin, prepared, transmission, Proofs(originHash, transmissionHash, birthHash, bindingHash))
    }

    private suspend fun savedOutcome(access: LocalSyncAccess, id: String): NextOneTimeOutcome? {
        val acceptanceHash = hash("next_acceptances", id)
        if (acceptanceHash != null) {
            val evidence = evidence(access, id, consumed = true)
            val receipt = requireNotNull(database.nextRequestDao().acceptance(NEXT_OPERATION, id))
            require(receipt.kind == NEXT_OPERATION && receipt.requestId == id && receipt.originHash == evidence.hashes.origin &&
                receipt.transmissionHash == evidence.hashes.transmission && receipt.resultHash == nextRequestHash(receipt.resultJson.toByteArray(Charsets.UTF_8)))
            require(database.completionFollowUpDao().transmission(id)?.rejectionJson == null)
            val result = decodeFrozenSyncRequest(receipt.resultJson.toByteArray(Charsets.UTF_8), NextSyncOperationResult.serializer())
            require(result.status == "applied")
            validateSuccess(evidence.prepared, result)
            verifySavedMetadata(access, evidence, result)
            return NextOneTimeOutcome.Accepted(NextOperationAcceptance.REPLAYED)
        }
        if (linkedHash("one_time_transmissions", id) == null) return null
        val binding = requireNotNull(database.completionFollowUpDao().transmission(id))
        if (binding.rejectionJson == null) return null
        val evidence = evidence(access, id, rejected = true)
        val result = decodeFrozenSyncRequest(binding.rejectionJson.toByteArray(Charsets.UTF_8), NextSyncOperationResult.serializer())
        verifySavedMetadata(access, evidence, result)
        return NextOneTimeOutcome.Rejected(result)
    }

    private suspend fun verifySavedMetadata(access: LocalSyncAccess, evidence: Evidence, result: NextSyncOperationResult) {
        if (roundOperationIntent(evidence.origin.intentJson) == null) return
        val actual = NextChallengeStore(database).activeInTransaction(access).second
        require(actual.births.none { it.entityType == "activity_event" && it.entityUuid == evidence.prepared.operation.entityUuid })
        validateRoundResultBinding(decodeFrozenSyncRequest(requireNotNull(evidence.transmission).wireBytes, RoundSyncPushRequest.serializer()),
            RoundSyncPushResponse(listOf(result), 1, actual.checkpoints, actual.births))
    }

    private fun validateSuccess(prepared: PreparedOneTimeSubmission, result: NextSyncOperationResult) {
        require(result.status in setOf("applied", "already_applied"))
        validateTaskResultBinding(prepared.operation, result)
        require(result.errorCode == null && result.oneTimeConflict == null && result.baseEntity == null &&
            result.localEntity == null && result.conflictingFields.isEmpty() && result.conflictKind == null)
        OneTimeServerFact(requireNotNull(result.entity), requireNotNull(result.revision))
            .requireOriginal(prepared.operation, prepared.context.deviceId)
    }

    private suspend fun requireHead(evidence: Evidence) {
        if (database.syncOutboxDao().getActivityIntents(activity(evidence.prepared.operation)).firstOrNull()?.operationId != evidence.origin.requestId)
            rejectNextRequest(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING)
    }

    private suspend fun requireParent(access: LocalSyncAccess, operation: SyncV2Operation, allowDeleted: Boolean): String? {
        val uuid = activity(operation)
        if (database.habitDao().getHabitByUuid(uuid) != null) {
            require(database.syncOutboxDao().getState("plan_node", uuid)?.deleted != true)
            return null
        }
        require(allowDeleted)
        return core.parentDeletionProofInTransaction(access, uuid)
            ?: rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
    }

    private fun activity(operation: SyncV2Operation): String = operation.payload.getValue("activity_uuid").let {
        require(it is JsonPrimitive && it.isString && isContractUuid(it.content)); it.content
    }

    private suspend fun hash(table: String, id: String) = NextRequestSql.rowHash(database.openHelper.writableDatabase,
        table, "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id))

    private suspend fun linkedHash(table: String, id: String) = NextRequestSql.rowHash(database.openHelper.writableDatabase,
        table, "operationId=?", arrayOf(id))

    private suspend fun authorize(access: LocalSyncAccess) {
        if (tokens.syncAuthenticationSnapshot(access) == null) rejectNextRequest(NextRequestException.Reason.STALE_ACCESS)
        if (access.deviceId == null || "facts.append" !in access.capabilities) rejectNextRequest(NextRequestException.Reason.PERMISSION_DENIED)
    }
}
