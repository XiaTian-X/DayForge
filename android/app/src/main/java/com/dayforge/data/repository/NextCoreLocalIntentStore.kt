package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.encodeSyncRequest
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.api.dto.TimerCommandRequest
import com.dayforge.data.api.dto.NextSyncPushRequest
import com.dayforge.data.api.dto.TimerCommandBatchRequest
import com.dayforge.data.api.dto.validateNextSyncOperation
import com.dayforge.data.api.dto.ChallengeMetadata
import com.dayforge.data.api.dto.RoundSyncPushRequest
import com.dayforge.data.api.dto.RoundTimerCommandBatchRequest
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.NextRequestOriginEntity
import com.dayforge.data.local.entity.NextSyncStateEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    suspend fun captureRounds(): NextRoundWriteScope = sessions.exclusive {
        val access = requireNotNull(tokens.localSyncAccess())
        require(tokens.syncAuthenticationSnapshot(access) != null && access.deviceId != null)
        database.withTransaction {
            captureRoundsInTransaction(access)
        }
    }

    /** The caller already owns the account lock and the same displayed Room snapshot. */
    internal suspend fun captureRoundsInTransaction(access: com.dayforge.data.local.LocalSyncAccess): NextRoundWriteScope {
        check(database.inTransaction())
        require(tokens.localSyncAccess() == access && tokens.syncAuthenticationSnapshot(access) != null && access.deviceId != null)
        val (_, metadata, initials) = NextRoundPendingInitialStore(database).capture(access)
        return NextRoundWriteScope(access.copy(capabilities = access.capabilities.toSet()),
            Json.encodeToString(ChallengeMetadata.serializer(), metadata), initials,
            NextRestartStore(database).pending(access, metadata, initials))
    }

    /** Same displayed snapshot chooses an already accepted profile; never upgrade an old ticket. */
    internal suspend fun captureDisplayedRoundsInTransaction(): NextRoundWriteScope? {
        check(database.inTransaction())
        val contracts = database.openHelper.writableDatabase.query("SELECT challengeContract FROM next_sync_state").use { raw ->
            buildList { while (raw.moveToNext()) {
                require(raw.getType(0) == android.database.Cursor.FIELD_TYPE_INTEGER && raw.getLong(0) in 0L..1L)
                add(raw.getLong(0))
            } }
        }
        require(contracts.size <= 1)
        if (contracts.singleOrNull() == 1L) return captureRoundsInTransaction(requireNotNull(tokens.localSyncAccess()))
        NextChallengeStore(database).requirePlainInTransaction()
        return null
    }

    suspend fun <T> writeRounds(scope: NextRoundWriteScope, writeInTransaction: suspend () -> T): T =
        write(scope.access.session, scope, writeInTransaction)

    suspend fun <T> write(session: LocalDataSession, writeInTransaction: suspend () -> T): T =
        write(session, null, writeInTransaction)

    private suspend fun <T> write(session: LocalDataSession, scope: NextRoundWriteScope?,
        writeInTransaction: suspend () -> T): T {
        // Reject before acquiring the non-reentrant account lock. A display/read participant
        // must not wrap a writer and accidentally reuse its phase's proof across mutations.
        NextEvidenceReadSession.requireWritePhase()
        return writeWhileUnscoped(session, scope, writeInTransaction)
    }

    private suspend fun <T> writeWhileUnscoped(session: LocalDataSession, scope: NextRoundWriteScope?,
        writeInTransaction: suspend () -> T): T = sessions.exclusive(AccountSessionCoordinator.Work.NEXT_WRITE) {
        val timing = WriteTiming()
        val access = tokens.localCoreWriteAccess()
        if (access == null || access.session != session) rejectNextRequest(NextRequestException.Reason.STALE_ACCESS)
        timing.mark(WriteStage.ENTRY_AUTH)
        database.withTransaction {
            val rounds = NextChallengeStore(database)
            val sql = database.openHelper.writableDatabase
            val tables = listOf("sync_outbox", "timer_command_outbox")
            // Reuse raw evidence only within this SELECT-only stage. The session is closed
            // before business writes; the returned values remain comparison inputs, not a cache.
            val prepared = NextEvidenceReadSession.read(database) {
                val roundSnapshot = scope?.let { NextChallengeReadSnapshot.capture(database, it.access) }
                val roundState = scope?.let { requireNotNull(roundSnapshot).borrow(database, it.access) }
                val roundCapture = if (scope == null) {
                    rounds.requirePlainInTransaction(); null
                } else {
                    require(tokens.localSyncAccess() == scope.access && access.capturedDeviceId == scope.access.deviceId &&
                        tokens.syncAuthenticationSnapshot(scope.access) != null) { "SYNC_CHALLENGE_CONTEXT_CHANGED" }
                    val initials = NextRoundPendingInitialStore(database).read(scope.access, requireNotNull(roundSnapshot))
                    NextRoundOperationCapture(database, scope, requireNotNull(roundState).second,
                        database.habitDao().getAllHabitsOnce(), initials,
                        NextRestartStore(database).pending(scope.access, roundState.second, initials))
                }
                NextRequestSql.requireOutboxEnabled(sql)
                PreparedWrite(roundState?.first, roundCapture,
                    tables.associateWith { NextRequestSql.sources(sql, it) },
                    tables.associateWith { NextRequestSql.watermark(sql, it) })
            }
            val roundCapture = prepared.roundCapture
            val before = prepared.before
            val watermarks = prepared.watermarks
            timing.mark(WriteStage.PREPARE)
            val result = writeInTransaction()
            timing.mark(WriteStage.BUSINESS)
            NextRequestSql.requireOutboxEnabled(sql)
            val expectedSources = tables.associateWith { NextRequestSql.sources(sql, it) }
            val createdOrigins = mutableMapOf<Pair<String, String>, String>()
            val createdDependencies = mutableMapOf<String, String>()
            val countDays = mutableMapOf<Pair<Long, String>, String>()
            val causal = NextStructuralCausalStore(database)
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
                    val decodedOperation: SyncV2Operation?
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
                        if (operation.action == "upsert") {
                            val deleted = NextPlanDeletionStore(database)
                            val metrics = NextMetricDeletionStore(database)
                            if (operation.entityType == "plan_node") {
                                deleted.requireWritable(operation.entityUuid)
                                operation.payload["parent_uuid"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
                                    ?.let { deleted.requireWritable(it) }
                            } else if (operation.entityType in setOf("activity_event", "activity_metric_link")) {
                                deleted.requireWritable(operation.payload.getValue("activity_uuid").jsonPrimitive.content)
                            }
                            if (operation.entityType == "metric") metrics.requireWritable(operation.entityUuid)
                            if (operation.entityType in setOf("metric_observation", "activity_metric_link"))
                                metrics.requireWritable(operation.payload.getValue("metric_uuid").jsonPrimitive.content)
                            if (operation.entityType == "activity_metric_link") metrics.requireLinkWritable(
                                requireNotNull(database.habitMetricLinkDao().getLinkByUuid(operation.entityUuid)))
                        }
                        val countFrontier = NextCountDayStore(database).capturePlanFrontier(operation, id, access,
                            tokens::localSyncAccess)
                        val round = roundCapture?.capture(operation)?.copy(countPlanFrontier = countFrontier)
                        timing.mark(WriteStage.MAP_OPERATION)
                        // Pure work on owned immutable inputs: one dispatcher round trip and one
                        // strict decode of these exact bytes. No SQL or authority proof is reused.
                        val encoded = withContext(Dispatchers.Default) {
                            val body = if (round == null) countFrontier?.let {
                                encodeSyncRequest(NextCountOperationIntent.serializer(), NextCountOperationIntent(operation, it))
                            } ?: encodeSyncRequest(SyncV2Operation.serializer(), operation)
                                else encodeSyncRequest(NextRoundOperationIntent.serializer(), round)
                            val verified = decodeNextOperationIntent(body.toString(Charsets.UTF_8))
                            // Prove full-envelope limits while still able to roll back the local write.
                            // This placeholder is only a size/type oracle, never persisted or transmitted.
                            if (round == null) {
                                val envelope = encodeSyncRequest(NextSyncPushRequest.serializer(),
                                    NextSyncPushRequest(SIZE_DEVICE, listOf(operation)))
                                decodeFrozenSyncRequest(envelope, NextSyncPushRequest.serializer())
                            } else {
                                val envelope = encodeSyncRequest(RoundSyncPushRequest.serializer(),
                                    RoundSyncPushRequest(1, round.capturedDeviceId, listOf(operation), listOf(round.context)))
                                decodeFrozenSyncRequest(envelope, RoundSyncPushRequest.serializer())
                            }
                            body to verified
                        }
                        bytes = encoded.first
                        decodedOperation = encoded.second
                        timing.mark(WriteStage.ENCODE_OPERATION)
                    } else {
                        decodedOperation = null
                        val row = database.timeLogDao().getTimerCommand(id)
                            ?: rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
                        if (row.attemptCount != 0 || row.deadLetteredAt != null || row.lastError != null || row.errorCode != null)
                            rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
                        requestId = row.commandId; permission = "timer.control"
                        val originalCommand = timerRequest(row)
                        val intent = if (row.commandType == "start")
                            NextTimerPolicyStore(database).capture(originalCommand, access, scope?.pendingRestarts?.get(originalCommand.activityUuid))
                            else NextTimerIntent(originalCommand)
                        val command = intent.command
                        val activity = row.activityUuid ?: database.timeLogDao().getTimeLogByUuid(row.sessionUuid)?.let {
                            database.habitDao().getHabitById(it.habitId)?.uuid
                        }
                        activity?.let { NextPlanDeletionStore(database).requireWritable(it) }
                        val round = roundCapture?.captureTimer(intent)
                        bytes = if (round != null) encodeSyncRequest(NextRoundTimerIntent.serializer(), round)
                            else if (command.startPolicy != null)
                            encodeSyncRequest(NextTimerIntent.serializer(), intent)
                            else encodeSyncRequest(TimerCommandRequest.serializer(), command)
                        decodeNextTimerIntent(bytes.toString(Charsets.UTF_8))
                        if (round == null) {
                            val envelope = encodeSyncRequest(TimerCommandBatchRequest.serializer(),
                                TimerCommandBatchRequest(SIZE_DEVICE, listOf(command)))
                            decodeFrozenSyncRequest(envelope, TimerCommandBatchRequest.serializer())
                        } else {
                            val envelope = encodeSyncRequest(RoundTimerCommandBatchRequest.serializer(),
                                RoundTimerCommandBatchRequest(1, round.capturedDeviceId, listOf(command), listOf(round.context)))
                            decodeFrozenSyncRequest(envelope, RoundTimerCommandBatchRequest.serializer())
                        }
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
                    check(NextRequestSql.rowHash(database, "next_request_origins", "kind=? AND requestId=?", arrayOf(kind, requestId)) != null)
                    check(dao.origin(kind, requestId) == origin)
                    if (kind == NEXT_OPERATION) {
                        val operation = requireNotNull(decodedOperation)
                        causal.capture(origin, operation, access)
                        NextCountDayStore(database).bind(origin, operation, access)
                        if ("count_policy" in operation.payload) {
                            val habit = requireNotNull(database.habitDao().getHabitByUuid(operation.payload.getValue("activity_uuid").jsonPrimitive.content))
                            val date = operation.payload.getValue("local_date").jsonPrimitive.content
                            countDays[habit.id to date] = requireNotNull(NextRequestSql.rowHash(database, "count_days", "habitId=? AND localDate=?", arrayOf(habit.id, date)))
                        }
                    }
                    createdOrigins[kind to requestId] = requireNotNull(NextRequestSql.rowHash(database,
                        "next_request_origins", "kind=? AND requestId=?", arrayOf(kind, requestId)))
                    if (kind == NEXT_OPERATION) NextRequestSql.rowHash(database, "next_structural_dependencies", "operationId=?", arrayOf(requestId))
                        ?.let { createdDependencies[requestId] = it }
                }
            }
            timing.mark(WriteStage.CAPTURE_ORIGINS)
            // Start empty after ALL writes. In particular, a missing receipt or an old origin
            // from preparation cannot hide changes made by a later origin/day/queue trigger.
            NextEvidenceReadSession.read(database) {
                check(tables.associateWith { NextRequestSql.sources(sql, it) } == expectedSources)
                for ((key, hash) in createdOrigins) check(NextRequestSql.rowHash(database, "next_request_origins",
                    "kind=? AND requestId=?", arrayOf(key.first, key.second)) == hash)
                for ((id, hash) in createdDependencies) {
                    check(NextRequestSql.rowHash(database, "next_structural_dependencies", "operationId=?", arrayOf(id)) == hash)
                    causal.auditCaptured(id, access)
                }
                for ((key, hash) in countDays) {
                    check(NextRequestSql.rowHash(database, "count_days", "habitId=? AND localDate=?", arrayOf(key.first, key.second)) == hash)
                    requireNotNull(NextCountDayStore(database).read(requireNotNull(database.habitDao().getHabitById(key.first)), key.second))
                }
                NextRequestSql.requireOutboxEnabled(sql)
                if (scope != null) {
                    val finalSnapshot = NextChallengeReadSnapshot.capture(database, scope.access)
                    val actual = finalSnapshot.borrow(database, scope.access)
                    require(actual.first == requireNotNull(prepared.roundState)) { "SYNC_CHALLENGE_CURSOR_CHANGED" }
                    roundCapture!!.verify(finalSnapshot)
                }
            }
            if (scope != null) {
                require(tokens.localSyncAccess() == scope.access && tokens.syncAuthenticationSnapshot(scope.access) != null)
            }
            timing.mark(WriteStage.FINAL_AUDIT)
            if (tokens.localCoreWriteAccess() != access) rejectNextRequest(NextRequestException.Reason.STALE_ACCESS)
            timing.mark(WriteStage.EXIT_AUTH)
            result
        }.also { timing.mark(WriteStage.TRANSACTION_COMMITTED) }
    }

    private data class PreparedWrite(
        val roundState: NextSyncStateEntity?,
        val roundCapture: NextRoundOperationCapture?,
        val before: Map<String, Map<Long, String>>,
        val watermarks: Map<String, Long>
    )

    private companion object { const val SIZE_DEVICE = "00000000-0000-4000-8000-000000000000" }

    internal suspend fun operation(row: SyncOutboxEntity): SyncV2Operation {
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
            NextPlanDeletionStore.payload(row) ?: NextMetricDeletionStore.payload(row) ?:
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
                val body = SyncV2Mapper.completion(fact, habit)
                if (habit.habitType == HabitType.COUNTING && habit.appearance != null) {
                    val day = requireNotNull(NextCountDayStore(database).read(habit, fact.recordedLocalDate, allowUnbound = true))
                    JsonObject(body + ("count_policy" to day.policy.toJson()))
                } else body
            }
            "metric_log" -> {
                val fact = requireNotNull(database.metricLogDao().getLogByUuid(row.entityUuid))
                val metric = requireNotNull(database.metricDao().getMetricById(fact.metricId))
                SyncV2Mapper.metricObservation(fact, metric.uuid)
            }
            "link" -> SyncV2Mapper.link(requireNotNull(database.habitMetricLinkDao().getLinkByUuid(row.entityUuid)))
            else -> rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
        }
        if (!revert) NextRequestSql.rowHash(database, "sync_entity_state",
            "entityType=? AND entityUuid=?", arrayOf(type, row.entityUuid))
        val shadow = if (revert) null else database.syncOutboxDao().getState(type, row.entityUuid)
        require(shadow == null || (!shadow.deleted && shadow.revision > 0))
        val base = shadow?.revision
        return SyncV2Operation(row.operationId, type, wireId, if (revert) "upsert" else row.action, base, payload)
            .also(::validateNextSyncOperation)
    }
}

private enum class WriteStage { ENTRY_AUTH, PREPARE, BUSINESS, MAP_OPERATION, ENCODE_OPERATION, CAPTURE_ORIGINS, FINAL_AUDIT, EXIT_AUTH, TRANSACTION_COMMITTED }

/** Debug-only durations; no business data, identity, credentials, or change to transaction order. */
private class WriteTiming {
    private var previous = if (com.dayforge.BuildConfig.DEBUG) android.os.SystemClock.elapsedRealtime() else 0L
    fun mark(stage: WriteStage) {
        if (!com.dayforge.BuildConfig.DEBUG) return
        val now = android.os.SystemClock.elapsedRealtime()
        android.util.Log.d("LocalWriteTiming", "stage=$stage elapsed_ms=${now - previous}")
        previous = now
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
