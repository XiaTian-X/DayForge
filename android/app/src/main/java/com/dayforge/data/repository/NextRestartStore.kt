package com.dayforge.data.repository

import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.entity.*
import com.dayforge.domain.model.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Private transaction participant. Neither a decoded proposal nor a pending head grants authority. */
internal class NextRestartStore(private val database: HabitDatabase) {
    private val sql get() = database.openHelper.writableDatabase
    private val requests get() = database.nextRequestDao()
    private val dao get() = database.nextRestartDao()

    internal data class Original(val row: NextRequestOriginEntity, val value: NextRestartProposal,
        val hash: String) {
        val reference get() = NextRestartReference(row.requestId, hash, value.head)
    }

    private suspend fun rowHash(table: String, id: String) = when (table) {
        "next_request_origins", "next_transmissions", "next_acceptances", "next_rejections" ->
            NextRequestSql.rowHash(sql, table, "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id))
        else -> NextRequestSql.rowHash(sql, table, "operationId=?", arrayOf(id))
    }

    suspend fun original(access: LocalSyncAccess, id: String): Original {
        val first = readOriginal(access, id)
        var current = first
        // Strictly decreasing physical birth order is a durable acyclic proof, not call-stack depth.
        while (true) {
            currentCoroutineContext().ensureActive()
            val previous = current.value.restartFrontier ?: break
            val parent = readOriginal(access, previous.operationId)
            require(parent.reference == previous && parent.row.queueId < current.row.queueId)
            current = parent
        }
        return first
    }

    private suspend fun readOriginal(access: LocalSyncAccess, id: String): Original {
        check(database.inTransaction())
        require(isContractUuid(id) && access.deviceId != null)
        val hash = requireNotNull(rowHash("next_request_origins", id))
        val origin = requireNotNull(requests.origin(NEXT_OPERATION, id))
        require(origin.kind == NEXT_OPERATION && origin.requestId == id && origin.queueId > 0 && origin.protocol == 5 &&
            origin.accountId == access.session.authentication.userId && origin.serverInstanceId == access.session.serverInstanceId &&
            origin.syncEpoch == access.session.syncEpoch) { "SYNC_RESTART_SOURCE_CHANGED" }
        val proposal = requireNotNull(restartProposal(origin.intentJson)) { "SYNC_RESTART_PROPOSAL_REQUIRED" }
        require(proposal.operationId == id && proposal.deviceId == access.deviceId)
        val queue = database.syncOutboxDao().getById(origin.queueId)
        if (queue == null) require(rowHash("next_acceptances", id) != null && database.syncOutboxDao().getByOperationId(id) == null)
        else {
            require(NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(origin.queueId)) == origin.sourceHash &&
                NextRequestSql.sourceHash(queue) == origin.sourceHash && queue.operationId == id &&
                queue.recordType == RESTART_RECORD && queue.entityUuid == proposal.head.activityUuid &&
                queue.wireEntityUuid == proposal.head.roundUuid && queue.action == "upsert" && queue.referenceUuid == null &&
                queue.payloadJson == origin.intentJson && queue.baseRevision == null && queue.basePayloadJson == null &&
                queue.attemptedAt == null && queue.attemptCount == 0 && queue.lastError == null && queue.errorCode == null &&
                queue.deadLetteredAt == null && rowHash("next_acceptances", id) == null) { "SYNC_RESTART_SOURCE_CHANGED" }
        }
        proposal.planFrontier?.let { frontier ->
            require(rowHash("next_request_origins", frontier.operationId) == frontier.originHash &&
                rowHash("next_structural_dependencies", frontier.operationId) == frontier.dependencyHash)
            val source = requireNotNull(requests.origin(NEXT_OPERATION, frontier.operationId))
            val intent = requireNotNull(roundOperationIntent(source.intentJson))
            require(source.accountId == origin.accountId && source.serverInstanceId == origin.serverInstanceId &&
                source.syncEpoch == origin.syncEpoch && source.queueId < origin.queueId &&
                intent.capturedDeviceId == access.deviceId && intent.operation.entityType == "plan_node" &&
                intent.operation.entityUuid == proposal.head.activityUuid && intent.operation.action == "upsert" &&
                intent.context.head == proposal.expectedHead)
            NextStructuralCausalStore(database).auditCaptured(frontier.operationId,
                com.dayforge.data.local.LocalCoreWriteAccess(access.session, access.capabilities, access.deviceId))
        }
        for (terminal in proposal.terminals) {
            val raw = requireNotNull(NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_TIMER, terminal.commandId)))
            val source = requireNotNull(requests.origin(NEXT_TIMER, terminal.commandId))
            val intent = requireNotNull(roundTimerIntent(source.intentJson))
            require(raw == terminal.originHash && source.protocol == 5 && source.accountId == origin.accountId &&
                source.serverInstanceId == origin.serverInstanceId && source.syncEpoch == origin.syncEpoch &&
                intent.capturedDeviceId == access.deviceId && intent.timer.command.commandId == terminal.commandId &&
                intent.timer.command.sessionId == terminal.sessionId && intent.timer.command.sequence == terminal.sequence &&
                intent.timer.command.commandType in setOf("stop", "cancel") &&
                intent.context.head?.activityUuid == proposal.head.activityUuid)
        }
        return Original(origin, proposal, hash)
    }

    /** Pending projections are an audited chain, never rows in accepted challenge tables. */
    suspend fun pending(access: LocalSyncAccess, metadata: ChallengeMetadata,
        initials: Map<String, NextPendingInitial>): Map<String, NextRestartReference> {
        check(database.inTransaction())
        val rows = (database.syncOutboxDao().getAll() + database.syncOutboxDao().getDeadLetters())
            .filter { it.recordType == RESTART_RECORD }.sortedBy { it.id }
        require(rows.size <= 10_000)
        val result = linkedMapOf<String, NextRestartReference>()
        for (row in rows) {
            currentCoroutineContext().ensureActive()
            val original = original(access, row.operationId)
            val proposal = original.value
            val checkpoint = metadata.checkpoints.singleOrNull { it.head.activityUuid == proposal.head.activityUuid }
            val known = checkpoint?.records?.singleOrNull { it.head == proposal.head }
            val current = result[proposal.head.activityUuid]?.head ?: if (known != null) {
                require(checkpoint.records.any { it.head == proposal.expectedHead })
                proposal.expectedHead
            } else checkpoint?.head ?: initials[proposal.head.activityUuid]?.head
            // A response lost after server COMMIT can expose this very head via a real pull.
            // It is still NOT a local ACK: dependents must await the complete original receipt.
            if (known != null) {
                val record = known
                val canonical = materialization(access, original)
                require(record.sourceDeviceUuid == proposal.deviceId && record.restartOperationUuid == proposal.operationId &&
                    record.restartIntent == Json.decodeFromJsonElement<ChallengeRestartIntent>(canonical.payload))
            }
            require(current == proposal.expectedHead) { "SYNC_RESTART_HEAD_CONFLICT" }
            result[proposal.head.activityUuid] = original.reference
        }
        for ((activity, last) in result) {
            val acceptedHead = metadata.checkpoints.singleOrNull { it.head.activityUuid == activity }?.head
            require(acceptedHead == null || acceptedHead.generation <= last.head.generation) { "SYNC_RESTART_HEAD_CONFLICT" }
            if (acceptedHead?.generation == last.head.generation) require(acceptedHead == last.head) { "SYNC_RESTART_HEAD_CONFLICT" }
        }
        return result
    }

    suspend fun materialization(access: LocalSyncAccess, original: Original): SyncV2Operation {
        val id = original.row.requestId
        requireNotNull(rowHash("next_restart_materializations", id))
        val stored = requireNotNull(dao.materialization(id))
        require(stored.operationId == id && stored.kind == NEXT_OPERATION && stored.originHash == original.hash &&
            stored.operationHash == nextRequestHash(stored.operationJson.toByteArray(Charsets.UTF_8)))
        val operation = decodeFrozenSyncRequest(stored.operationJson.toByteArray(Charsets.UTF_8), SyncV2Operation.serializer())
        val proposal = original.value
        val intent = Json.decodeFromJsonElement<ChallengeRestartIntent>(operation.payload)
        require(operation.operationId == id && operation.entityType == "challenge_round" && operation.action == "upsert" &&
            operation.baseRevision == null && operation.entityUuid == proposal.head.roundUuid &&
            intent.activityUuid == proposal.head.activityUuid && intent.roundUuid == proposal.head.roundUuid &&
            intent.expectedRoundUuid == proposal.expectedHead.roundUuid && intent.expectedGeneration == proposal.expectedHead.generation)
        RoundSyncPushRequest(1, requireNotNull(access.deviceId), listOf(operation), listOf(ChallengeSourceContext(id, null)))
        return operation
    }

    /** Real accepted restart record and shared receipt, NOT queue absence or a head alone. */
    suspend fun accepted(access: LocalSyncAccess, reference: NextRestartReference): Pair<SyncV2Operation, String> {
        val original = original(access, reference.operationId)
        require(original.reference == reference)
        val operation = materialization(access, original)
        val transmitted = transmission(access, original, operation)
        val receiptHash = rowHash("next_acceptances", reference.operationId)
            ?: rejectNextRequest(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING)
        val receipt = requireNotNull(requests.acceptance(NEXT_OPERATION, reference.operationId))
        val journalHash = requireNotNull(rowHash("next_transmissions", reference.operationId))
        require(receipt.kind == NEXT_OPERATION && receipt.requestId == reference.operationId && receipt.originHash == original.hash &&
            receipt.transmissionHash == journalHash && receipt.resultHash == nextRequestHash(receipt.resultJson.toByteArray(Charsets.UTF_8)))
        val result = decodeFrozenSyncRequest(receipt.resultJson.toByteArray(Charsets.UTF_8), NextSyncOperationResult.serializer())
        val metadata = NextChallengeStore(database).activeInTransaction(access).second
        validateRoundResultBinding(transmitted, RoundSyncPushResponse(listOf(result), 1, metadata.checkpoints, metadata.births))
        require(result.status == "applied" && database.syncOutboxDao().getByOperationId(reference.operationId) == null)
        return operation to receiptHash
    }

    suspend fun acceptedPlan(access: LocalSyncAccess, reference: NextRestartReference): Pair<JsonObject, String> {
        val (operation, _) = accepted(access, reference)
        val intent = Json.decodeFromJsonElement<ChallengeRestartIntent>(operation.payload)
        val proofHash = rowHash("next_restart_plan_proofs", reference.operationId)
            ?: rejectNextRequest(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING)
        val proof = requireNotNull(dao.planProof(reference.operationId))
        require(proof.operationId == reference.operationId && proof.originHash == reference.originHash &&
            proof.transmissionHash == rowHash("next_transmissions", reference.operationId) &&
            proof.revision == Math.addExact(intent.expectedPlanRevision, 1L) && proof.logSequence > 0 &&
            proof.deviceId == access.deviceId && proof.planHash == syncPayloadHash(proof.planJson))
        val plan = Json.parseToJsonElement(proof.planJson).jsonObject
        val actual = NextStructureMapper.readPlan(plan, reference.head.activityUuid, proof.revision)
        require(actual.isActive && actual.completionPolicy == "recurring")
        return plan to proofHash
    }

    /** Authenticated CONTIGUOUS log evidence, usable only together with an independently real ACK. */
    suspend fun capturePlanFrame(access: LocalSyncAccess, change: SyncV2Change, metadata: ChallengeMetadata) {
        check(database.inTransaction())
        if (change.entityType != "plan_node" || change.operation != "upsert" || change.originDeviceId != access.deviceId) return
        require(change.sequence > 0)
        val ids = sql.query("SELECT operationId FROM next_restart_materializations ORDER BY rowid").use { raw ->
            buildList { while (raw.moveToNext()) {
                require(raw.getType(0) == android.database.Cursor.FIELD_TYPE_STRING); add(raw.getString(0))
            } }
        }
        require(ids.size <= 10_000)
        for (id in ids) {
            val original = original(access, id)
            if (original.value.head.activityUuid != change.entityUuid) continue
            val operation = materialization(access, original)
            val intent = Json.decodeFromJsonElement<ChallengeRestartIntent>(operation.payload)
            if (change.revision != Math.addExact(intent.expectedPlanRevision, 1L)) continue
            val record = metadata.requireRecord(intent.roundUuid)
            require(record.head == original.value.head && record.restartIntent == intent &&
                record.sourceDeviceUuid == access.deviceId && record.restartOperationUuid == id)
            transmission(access, original, operation)
            val actual = NextStructureMapper.readPlan(change.payload, change.entityUuid, change.revision)
            require(actual.isActive && actual.completionPolicy == "recurring" &&
                java.time.Instant.parse(change.payload.getValue("created_at").jsonPrimitive.content) ==
                    java.time.Instant.parse(original.value.displayedPlan.getValue("created_at").jsonPrimitive.content))
            val planJson = change.payload.toString()
            val proof = NextRestartPlanProofEntity(id, original.hash, requireNotNull(rowHash("next_transmissions", id)),
                change.revision, change.sequence, requireNotNull(access.deviceId), planJson, syncPayloadHash(planJson))
            val previous = dao.planProof(id)
            val protected = nextRestartDatabaseProof(database, excluded = setOf("next_restart_plan_proofs"))
            val otherProofs = nextRestartDatabaseProof(database, only = setOf("next_restart_plan_proofs"), excludedRequestId = id)
            if (previous == null) dao.insertPlanProof(proof) else {
                requireNotNull(rowHash("next_restart_plan_proofs", id)); require(previous == proof) { "SYNC_RESTART_PLAN_PROOF_CHANGED" }
            }
            requireNotNull(rowHash("next_restart_plan_proofs", id))
            require(dao.planProof(id) == proof && original(access, id).hash == original.hash &&
                nextRestartDatabaseProof(database, excluded = setOf("next_restart_plan_proofs")) == protected &&
                nextRestartDatabaseProof(database, only = setOf("next_restart_plan_proofs"), excludedRequestId = id) == otherProofs)
        }
    }

    suspend fun transmission(access: LocalSyncAccess, original: Original, operation: SyncV2Operation): RoundSyncPushRequest {
        requireNotNull(rowHash("next_transmissions", original.row.requestId))
        val saved = requireNotNull(requests.transmission(NEXT_OPERATION, original.row.requestId))
        require(saved.kind == NEXT_OPERATION && saved.requestId == original.row.requestId && saved.queueId == original.row.queueId &&
            saved.protocol == 5 && saved.accountId == original.row.accountId && saved.serverInstanceId == original.row.serverInstanceId &&
            saved.syncEpoch == original.row.syncEpoch && saved.deviceId == access.deviceId && saved.wireHash == nextRequestHash(saved.wireBytes))
        return decodeFrozenSyncRequest(saved.wireBytes, RoundSyncPushRequest.serializer()).also {
            require(it.deviceId == access.deviceId && it.operations == listOf(operation) &&
                it.contexts == listOf(ChallengeSourceContext(original.row.requestId, null)))
        }
    }

    /** Immutable frontiers are re-audited even on cold replay. Only actual accepted Plan revisions qualify. */
    suspend fun frontier(access: LocalSyncAccess, original: Original, timers: NextTimerRequestStore): Pair<JsonObject, String> {
        val proposal = original.value
        val proofs = mutableListOf(original.hash)
        proposal.restartFrontier?.let { previous -> proofs += acceptedPlan(access, previous).second }
        val plan = proposal.planFrontier?.let { dependency ->
            val causal = NextStructuralCausalStore(database)
            val accepted = causal.acceptedPlan(dependency.operationId, access)
            val actualId = causal.resolve(dependency.operationId, access)
            proofs += listOf(dependency.originHash, dependency.dependencyHash, actualId,
                requireNotNull(rowHash("next_request_origins", actualId)),
                requireNotNull(rowHash("next_transmissions", actualId)), requireNotNull(rowHash("next_acceptances", actualId)))
            accepted
        } ?: proposal.restartFrontier?.let { acceptedPlan(access, it).first } ?: requireNotNull(proposal.basePlan).also {
            proofs += syncPayloadHash(it.toString())
        }
        val revision = requireNotNull(plan["revision"]?.jsonPrimitive?.longOrNull)
        val actual = NextStructureMapper.readPlan(plan, proposal.head.activityUuid, revision)
        require(actual.completionPolicy == "recurring" && requireNotNull(actual.targetCycles) > 0 &&
            revision < Long.MAX_VALUE && java.time.Instant.parse(plan.getValue("created_at").jsonPrimitive.content) ==
                java.time.Instant.parse(proposal.displayedPlan.getValue("created_at").jsonPrimitive.content))
        for (terminal in proposal.terminals) {
            proofs += timers.requireAcceptedTerminalInTransaction(access, terminal.commandId, terminal.sessionId, terminal.sequence)
        }
        return plan to nextRequestHash(JsonArray(proofs.map(::JsonPrimitive)).toString().toByteArray(Charsets.UTF_8))
    }

    suspend fun requireMaterializedFrontier(access: LocalSyncAccess, original: Original, timers: NextTimerRequestStore): SyncV2Operation {
        val operation = materialization(access, original)
        val (plan, proof) = frontier(access, original, timers)
        val stored = requireNotNull(dao.materialization(original.row.requestId))
        require(stored.frontierHash == proof && Json.decodeFromJsonElement<ChallengeRestartIntent>(operation.payload).expectedPlanRevision ==
            plan.getValue("revision").jsonPrimitive.long)
        return operation
    }
}
