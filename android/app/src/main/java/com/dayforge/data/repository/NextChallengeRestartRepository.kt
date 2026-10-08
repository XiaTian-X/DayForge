package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.encodeSyncRequest
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.*
import com.dayforge.domain.service.AccountSessionCoordinator
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Actual offline restart producer/sender/receipt. No formal UI or protocol activation switch. */
internal enum class NextRestartOutcome { COMMITTED, REPLAYED, REJECTED, RETRY_REQUIRED }

@Singleton
internal class NextChallengeRestartRepository @Inject constructor(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val http: NextSyncHttp
) {
    private val store = NextRestartStore(database)
    private val core = NextCoreRequestStore(database, tokens, sessions, http)
    private val timers = NextTimerRequestStore(database, tokens, sessions, core)
    private val sql get() = database.openHelper.writableDatabase
    private val requests get() = database.nextRequestDao()

    private suspend fun authorize(access: LocalSyncAccess) {
        require(tokens.localSyncAccess() == access && access.deviceId != null && tokens.syncAuthenticationSnapshot(access) != null) {
            "SYNC_RESTART_ACCESS_CHANGED"
        }
        if ("structure.write" !in access.capabilities) rejectNextRequest(NextRequestException.Reason.PERMISSION_DENIED)
    }

    private suspend fun requestHash(table: String, id: String) = NextRequestSql.rowHash(sql, table,
        "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id))

    /** Discovery precedes first freeze. Failed or lost responses leave the original envelope intact. */
    suspend fun sendAndAccept(captured: LocalSyncAccess, id: String): NextRestartOutcome? {
        require(isContractUuid(id) && captured.deviceId != null)
        val access = captured.copy(capabilities = captured.capabilities.toSet())
        val replay = sessions.exclusive {
            authorize(access)
            database.withTransaction {
                val original = store.original(access, id)
                if (requestHash("next_acceptances", id) == null) false
                else {
                    store.requireMaterializedFrontier(access, original, timers)
                    store.accepted(access, original.reference); authorize(access); true
                }
            }
        }
        if (replay) return NextRestartOutcome.REPLAYED
        return http.session(access) { channel ->
            val prepared = sessions.exclusive {
                authorize(access)
                database.withTransaction {
                    NextRequestSql.requireOutboxEnabled(sql)
                    val original = store.original(access, id)
                    val unchanged = nextRestartDatabaseProof(database, excludedRequestId = id)
                    val (plan, frontier) = store.frontier(access, original, timers)
                    if (database.nextRestartDao().materialization(id) == null) {
                        require(requests.transmission(NEXT_OPERATION, id) == null && requestHash("next_rejections", id) == null)
                        val metadata = NextChallengeStore(database).activeInTransaction(access).second
                        require(metadata.checkpoints.singleOrNull { it.head.activityUuid == original.value.head.activityUuid }?.head ==
                            original.value.expectedHead) { "SYNC_RESTART_HEAD_CONFLICT" }
                        val proposal = original.value
                        val intent = ChallengeRestartIntent(proposal.head.activityUuid, proposal.head.roundUuid,
                            proposal.expectedHead.roundUuid, proposal.expectedHead.generation, plan.getValue("revision").jsonPrimitive.long)
                        val operation = SyncV2Operation(id, "challenge_round", proposal.head.roundUuid, "upsert",
                            null, Json.encodeToJsonElement(ChallengeRestartIntent.serializer(), intent).jsonObject)
                        val bytes = encodeSyncRequest(SyncV2Operation.serializer(), operation)
                        database.nextRestartDao().materialize(NextRestartMaterializationEntity(id, NEXT_OPERATION,
                            original.hash, frontier, bytes.toString(Charsets.UTF_8), nextRequestHash(bytes)))
                        val body = RoundSyncPushRequest(1, requireNotNull(access.deviceId), listOf(operation), listOf(ChallengeSourceContext(id, null)))
                        val wire = encodeSyncRequest(RoundSyncPushRequest.serializer(), body)
                        requests.insertTransmission(NextTransmissionEntity(NEXT_OPERATION, id, original.row.queueId, 5,
                            original.row.accountId, requireNotNull(original.row.serverInstanceId), requireNotNull(original.row.syncEpoch),
                            access.deviceId, nextRequestHash(wire), wire))
                    }
                    val operation = store.requireMaterializedFrontier(access, store.original(access, id), timers)
                    store.transmission(access, original, operation)
                    require(store.original(access, id).hash == original.hash)
                    require(nextRestartDatabaseProof(database, excludedRequestId = id) == unchanged)
                    NextRequestSql.requireOutboxEnabled(sql); authorize(access)
                    requireNotNull(requests.transmission(NEXT_OPERATION, id)).wireBytes.copyOf() to
                        requireNotNull(requestHash("next_transmissions", id))
                }
            }
            val result = channel.roundPushFrozen(prepared.first)
            accept(NextCoreDelivery(access, id, result, prepared.second, result.metadata()))
        }
    }

    /** Complete receipt, metadata and queue consumption have one outer Room COMMIT. */
    internal suspend fun accept(delivery: NextCoreDelivery<RoundSyncPushResponse>): NextRestartOutcome = sessions.exclusive {
        val access = delivery.access
        authorize(access)
        database.withTransaction {
            NextRequestSql.requireOutboxEnabled(sql)
            val id = delivery.requestId
            val original = store.original(access, id)
            val operation = store.requireMaterializedFrontier(access, original, timers)
            val body = store.transmission(access, original, operation)
            val transmittedHash = requireNotNull(requestHash("next_transmissions", id))
            require(transmittedHash == delivery.transmissionProof)
            val frozen = decodeFrozenSyncRequest(encodeSyncRequest(RoundSyncPushResponse.serializer(), delivery.result), RoundSyncPushResponse.serializer())
            require(delivery.challengeMetadata == frozen.metadata())
            validateRoundResultBinding(body, frozen)
            val result = frozen.results.single()
            val success = result.status in setOf("applied", "already_applied")
            val normalized = if (success) result.copy(status = "applied") else result
            val bytes = encodeSyncRequest(NextSyncOperationResult.serializer(), normalized)
            val json = bytes.toString(Charsets.UTF_8)
            val receipt = requests.acceptance(NEXT_OPERATION, id)
            if (receipt != null) {
                store.accepted(access, original.reference)
                require(success && receipt.resultJson == json) { "SYNC_RESTART_RESULT_CHANGED" }
                authorize(access)
                return@withTransaction NextRestartOutcome.REPLAYED
            }
            val protected = nextRestartDatabaseProof(database, excluded = setOf("sync_outbox", "next_challenge_state",
                "next_challenge_rounds", "next_challenge_births"), excludedRequestId = id)
            val sources = NextRequestSql.sources(sql, "sync_outbox")
            val oldMetadata = NextChallengeStore(database).activeInTransaction(access).second
            var expectedMetadata = oldMetadata
            val outcome = if (success) {
                require(requestHash("next_rejections", id) == null) { "SYNC_RESTART_REJECTED" }
                expectedMetadata = NextChallengeStore(database).acknowledgeInTransaction(access, frozen.metadata())
                val accepted = NextAcceptanceEntity(NEXT_OPERATION, id, original.hash, transmittedHash, nextRequestHash(bytes), json)
                requests.insertAcceptance(accepted)
                database.syncOutboxDao().deleteById(original.row.queueId)
                store.accepted(access, original.reference)
                require(requests.acceptance(NEXT_OPERATION, id) == accepted)
                require(NextRequestSql.sources(sql, "sync_outbox") == sources - original.row.queueId)
                NextRestartOutcome.COMMITTED
            } else if (result.errorCode in setOf("MISSING_PREDECESSOR", "OPERATION_IN_PROGRESS")) {
                require(requestHash("next_rejections", id) == null && NextRequestSql.sources(sql, "sync_outbox") == sources &&
                    NextChallengeStore(database).activeInTransaction(access).second == oldMetadata)
                NextRestartOutcome.RETRY_REQUIRED
            } else {
                val previous = database.nextSyncStateDao().rejections().singleOrNull { it.kind == NEXT_OPERATION && it.requestId == id }
                val rejected = NextRejectionEntity(NEXT_OPERATION, id, original.hash, transmittedHash, nextRequestHash(bytes), json)
                if (previous == null) database.nextSyncStateDao().insertRejection(rejected) else require(previous == rejected) { "SYNC_RESTART_RESULT_CHANGED" }
                require(NextRequestSql.sources(sql, "sync_outbox") == sources &&
                    NextChallengeStore(database).activeInTransaction(access).second == oldMetadata)
                requireNotNull(requestHash("next_rejections", id))
                require(database.nextSyncStateDao().rejections().single { it.kind == NEXT_OPERATION && it.requestId == id } == rejected)
                NextRestartOutcome.REJECTED
            }
            require(nextRestartDatabaseProof(database, excluded = setOf("sync_outbox", "next_challenge_state",
                "next_challenge_rounds", "next_challenge_births"), excludedRequestId = id) == protected)
            require(store.original(access, id).hash == original.hash &&
                requestHash("next_transmissions", id) == transmittedHash)
            store.requireMaterializedFrontier(access, original, timers)
            require(NextChallengeStore(database).activeInTransaction(access).second == expectedMetadata)
            NextRequestSql.requireOutboxEnabled(sql); authorize(access)
            outcome
        }
    }

    suspend fun restart(expected: HabitEntity, ticket: ObjectEditAuthority): NextRestartReference = sessions.exclusive {
        val access = requireNotNull(tokens.localSyncAccess()).let { it.copy(capabilities = it.capabilities.toSet()) }
        authorize(access)
        database.withTransaction {
            val shown = requireNotNull(ticket.rounds) { "SYNC_RESTART_PROFILE_REQUIRED" }
            require(ticket.session == access.session && shown.access == access && ticket.type == "plan_node" &&
                ticket.uuid == expected.uuid && ticket.original == NextStructureMapper.writePlan(expected)) { "SYNC_RESTART_STALE_ACTION" }
            NextRequestSql.requireOutboxEnabled(sql)
            val metadata = NextChallengeStore(database).activeInTransaction(access).second
            val initials = NextRoundPendingInitialStore(database).read(access, metadata)
            val pending = store.pending(access, metadata, initials)
            val old = pending[expected.uuid]?.head ?: metadata.checkpoints.singleOrNull { it.head.activityUuid == expected.uuid }?.head
                ?: initials[expected.uuid]?.head
            val displayed = shown.pendingRestarts[expected.uuid]?.head ?:
                Json.decodeFromString<ChallengeMetadata>(shown.metadataJson).checkpoints.singleOrNull { it.head.activityUuid == expected.uuid }?.head
                ?: shown.pendingInitials[expected.uuid]?.head
            require(old != null && old == displayed && old.generation < Int.MAX_VALUE) { "SYNC_RESTART_STALE_ACTION" }
            shown.pendingRestarts[expected.uuid]?.let { require(store.original(access, it.operationId).reference == it) }
            val habit = requireNotNull(database.habitDao().getVisibleHabitById(expected.id))
            require(habit == expected && habit.appearance != null && habit.habitType != HabitType.GOAL &&
                habit.completionPolicy == "recurring" && requireNotNull(habit.targetCycles) > 0)
            NextPlanDeletionStore(database).requireWritable(habit.uuid)
            require(database.timeLogDao().getAllTimeLogsForHabit(habit.id).none { it.endTime == null }) { "CHALLENGE_TIMER_UNFINISHED" }
            val previous = pending[habit.uuid]
            val priorQueue = previous?.let { store.original(access, it.operationId).row.queueId } ?: 0L
            val causal = NextStructuralCausalStore(database)
            val configurations = database.syncOutboxDao().getEntityIntents("habit", habit.uuid).filter { causal.logicalOrder(it) > priorQueue }
            val latest = configurations.maxByOrNull { causal.logicalOrder(it) }
            val configuration = latest?.let { row ->
                require(row.action == "upsert" && row.deadLetteredAt == null)
                val originHash = requireNotNull(NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, row.operationId)))
                val dependencyHash = requireNotNull(NextRequestSql.rowHash(sql, "next_structural_dependencies", "operationId=?", arrayOf(row.operationId)))
                val original = requireNotNull(requests.origin(NEXT_OPERATION, row.operationId))
                val source = requireNotNull(roundOperationIntent(original.intentJson))
                require(source.context.head == old && source.operation.payload == ticket.original && source.capturedDeviceId == access.deviceId)
                causal.auditCaptured(row.operationId, com.dayforge.data.local.LocalCoreWriteAccess(access.session, access.capabilities, access.deviceId))
                NextRestartPlanFrontier(row.operationId, originHash, dependencyHash)
            }
            val base = if (configuration != null || previous != null) null else {
                requireNotNull(NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?", arrayOf("plan_node", habit.uuid)))
                val shadow = requireNotNull(database.syncOutboxDao().getState("plan_node", habit.uuid))
                require(!shadow.deleted && shadow.revision > 0 && shadow.payloadHash == syncPayloadHash(requireNotNull(shadow.payloadJson)))
                Json.parseToJsonElement(shadow.payloadJson).jsonObject.also {
                    require(NextStructureMapper.writePlan(NextStructureMapper.readPlan(it, habit.uuid, shadow.revision)) == ticket.original) {
                        "SYNC_RESTART_PLAN_PROOF_REQUIRED"
                    }
                }
            }
            val terminals = pendingTerminals(access, habit.uuid)
            val head = ChallengeRoundHead(habit.uuid, UUID.randomUUID().toString(), old.generation + 1)
            require(metadata.checkpoints.none { checkpoint -> checkpoint.records.any { it.head.roundUuid == head.roundUuid } } &&
                pending.values.none { it.head.roundUuid == head.roundUuid })
            val operationId = UUID.randomUUID().toString()
            require(requests.origin(NEXT_OPERATION, operationId) == null && requests.transmission(NEXT_OPERATION, operationId) == null &&
                requests.acceptance(NEXT_OPERATION, operationId) == null)
            val proposal = NextRestartProposal(1, operationId, head, old, requireNotNull(access.deviceId), ticket.original,
                base, configuration, previous, terminals)
            val intent = encodeSyncRequest(NextRestartProposal.serializer(), proposal).toString(Charsets.UTF_8)
            val before = nextRestartDatabaseProof(database, setOf("sync_outbox", "next_request_origins"), habit.id)
            val origins = nextRestartDatabaseProof(database, only = setOf("next_request_origins"))
            val sources = NextRequestSql.sources(sql, "sync_outbox")
            val watermark = NextRequestSql.watermark(sql, "sync_outbox")
            val updated = habit.copy(isActive = true, updatedAt = System.currentTimeMillis())
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            database.habitDao().update(updated)
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            val queueId = database.syncOutboxDao().insert(SyncOutboxEntity(operationId = operationId, recordType = RESTART_RECORD,
                entityUuid = habit.uuid, wireEntityUuid = head.roundUuid, action = "upsert", payloadJson = intent))
            require(queueId > watermark)
            val sourceHash = requireNotNull(NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(queueId)))
            val origin = NextRequestOriginEntity(NEXT_OPERATION, operationId, queueId, 5, access.session.authentication.userId,
                access.session.serverInstanceId, access.session.syncEpoch, sourceHash, intent)
            require(nextRestartDatabaseProof(database, only = setOf("next_request_origins")) == origins)
            requests.insertOrigin(origin)
            val original = store.original(access, operationId)
            require(original.row == origin && original.value == proposal && database.habitDao().getHabitById(habit.id) == updated &&
                NextRequestSql.sources(sql, "sync_outbox") == sources + (queueId to sourceHash) &&
                nextRestartDatabaseProof(database, setOf("sync_outbox", "next_request_origins"), habit.id) == before)
            require(nextRestartDatabaseProof(database, only = setOf("next_request_origins"), excludedRequestId = operationId) == origins)
            NextRequestSql.requireOutboxEnabled(sql); authorize(access)
            original.reference
        }
    }

    private suspend fun pendingTerminals(access: LocalSyncAccess, activity: String): List<NextRestartTimerFrontier> {
        val pending = database.timeLogDao().getPendingTimerCommands(10_001) + database.timeLogDao().getRejectedTimerCommands()
        require(pending.size <= 10_000)
        val matching = pending.mapNotNull { row ->
            val origin = requests.origin(NEXT_TIMER, row.commandId)
            val source = origin?.let { roundTimerIntent(it.intentJson) }
            if (source?.context?.head?.activityUuid != activity) {
                val local = database.timeLogDao().getTimeLogByUuid(row.sessionUuid)
                require(row.activityUuid != activity && (local == null || database.habitDao().getHabitById(local.habitId)?.uuid != activity)) {
                    "SYNC_RESTART_TIMER_PROOF_REQUIRED"
                }; null
            } else {
                val originHash = requireNotNull(NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_TIMER, row.commandId)))
                require(requireNotNull(origin).protocol == 5 && origin.accountId == access.session.authentication.userId &&
                    origin.serverInstanceId == access.session.serverInstanceId && origin.syncEpoch == access.session.syncEpoch &&
                    source.capturedDeviceId == access.deviceId && row.deadLetteredAt == null &&
                    NextRequestSql.rowHash(sql, "timer_command_outbox", "id=?", arrayOf(row.id)) == origin.sourceHash &&
                    timerRequest(row) == source.timer.command.copy(startPolicy = null))
                Triple(row, source, originHash)
            }
        }
        return matching.groupBy { it.first.sessionUuid }.map { (session, commands) ->
            val (row, _, originHash) = commands.maxBy { it.first.sequence }
            require(row.commandType in setOf("stop", "cancel")) { "CHALLENGE_TIMER_UNFINISHED" }
            NextRestartTimerFrontier(row.commandId, originHash, session, row.sequence)
        }.sortedBy { it.commandId }
    }
}

/** Exact raw database proof, confined to the current transaction; never a cached write permission. */
internal suspend fun nextRestartDatabaseProof(database: HabitDatabase, excluded: Set<String> = emptySet(),
    excludedHabitId: Long? = null, only: Set<String>? = null, excludedRequestId: String? = null): String {
    check(database.inTransaction())
    val sql = database.openHelper.writableDatabase
    val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' ORDER BY name")
        .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        .filter { it !in excluded && (only == null || it in only) }
    val digest = MessageDigest.getInstance("SHA-256")
    fun bytes(value: ByteArray) { digest.update(ByteBuffer.allocate(4).putInt(value.size).array()); digest.update(value) }
    for (table in tables) {
        require(table.matches(Regex("[a-zA-Z_][a-zA-Z_0-9]*"))); bytes(table.toByteArray(Charsets.UTF_8))
        val (where, args) = when {
            table == "habits" && excludedHabitId != null -> " WHERE id<>?" to arrayOf<Any>(excludedHabitId)
            table in setOf("next_request_origins", "next_transmissions", "next_acceptances", "next_rejections") && excludedRequestId != null ->
                " WHERE NOT(kind=? AND requestId=?)" to arrayOf<Any>(NEXT_OPERATION, excludedRequestId)
            (table == "next_restart_materializations" || table == "next_restart_plan_proofs" && only == setOf("next_restart_plan_proofs")) && excludedRequestId != null ->
                " WHERE operationId<>?" to arrayOf<Any>(excludedRequestId)
            else -> "" to emptyArray<Any>()
        }
        sql.query("SELECT * FROM $table$where ORDER BY rowid", args).use { c -> while (c.moveToNext()) {
            currentCoroutineContext().ensureActive()
            for (column in 0 until c.columnCount) {
                bytes(c.getColumnName(column).toByteArray(Charsets.UTF_8)); digest.update(c.getType(column).toByte())
                when (c.getType(column)) {
                    Cursor.FIELD_TYPE_NULL -> Unit
                    Cursor.FIELD_TYPE_INTEGER -> bytes(ByteBuffer.allocate(8).putLong(c.getLong(column)).array())
                    Cursor.FIELD_TYPE_FLOAT -> bytes(ByteBuffer.allocate(8).putDouble(c.getDouble(column)).array())
                    Cursor.FIELD_TYPE_STRING -> bytes(c.getString(column).toByteArray(Charsets.UTF_8))
                    Cursor.FIELD_TYPE_BLOB -> bytes(c.getBlob(column))
                }
            }
        } }
    }
    return nextRequestHash(digest.digest())
}
