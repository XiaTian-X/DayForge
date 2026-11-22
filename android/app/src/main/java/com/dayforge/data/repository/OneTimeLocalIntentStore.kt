package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.encodeSyncRequest
import com.dayforge.data.api.dto.ChallengeSourceContext
import com.dayforge.data.api.dto.RoundSyncPushRequest
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.local.entity.LocalFactSubmissionEntity
import com.dayforge.data.local.entity.NextRequestOriginEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.OneTimeIntent
import com.dayforge.domain.model.OneTimeQueueView
import com.dayforge.domain.model.OneTimeState
import com.dayforge.domain.model.PendingOneTimeIntent
import com.dayforge.domain.model.advanceOneTime
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.model.projectPendingOneTime
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.flow.first

internal class OneTimeLocalException(val reason: Reason) : IllegalStateException(reason.name) {
    enum class Reason {
        STALE_SESSION, FACTS_DENIED, ACTIVITY_NOT_FOUND, ENTITY_DELETED, UNINITIALIZED,
        INVALID_LOCAL_STATE, OPERATION_ID_REUSED, EVENT_ID_REUSED, PENDING_REPLAY, PENDING_REJECTED,
        TRANSMISSION_CONTEXT_CHANGED, HISTORY_BEHIND_LOCAL, RECOVERY_CONTEXT_CHANGED,
        RECOVERY_STATE_CHANGED, RECOVERY_EXHAUSTED
    }
}

internal data class OneTimeLocalCommand(
    val activityUuid: String,
    val pending: PendingOneTimeIntent,
    val occurredAtMillis: Long,
    val timezone: String
) {
    init {
        require(isContractUuid(activityUuid))
        require(timezone in ZoneId.getAvailableZoneIds()) { "An IANA timezone is required" }
        require(Instant.ofEpochMilli(occurredAtMillis).atZone(ZoneId.of("UTC")).year in 1..9999)
        require(Instant.ofEpochMilli(occurredAtMillis).atZone(ZoneId.of(timezone)).year in 1..9999)
    }
}

internal data class OneTimeLocalSnapshot(
    val activityUuid: String,
    val session: LocalDataSession,
    val confirmed: OneTimeState,
    val queue: OneTimeQueueView
)

/** A local durability result, never a server acknowledgement. */
internal data class OneTimeLocalAppendResult(
    val factId: Long,
    val alreadyStored: Boolean,
    val snapshot: OneTimeLocalSnapshot
)

/**
 * V5 local storage implementation. Not injected into the active v4 UI/sync path.
 * Activation also requires prompt UI wiring, strict server confirmation and all view consumers.
 */
internal class OneTimeLocalIntentStore(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val preferences: PreferencesManager
) {
    private val habits = database.habitDao()
    private val facts = database.completionDao()
    private val outbox = database.syncOutboxDao()
    private val followUps = database.completionFollowUpDao()
    private val json = Json { encodeDefaults = true }

    suspend fun read(activityUuid: String): OneTimeLocalSnapshot = sessions.exclusive {
        require(isContractUuid(activityUuid))
        val access = tokens.localFactAccess() ?: reject(OneTimeLocalException.Reason.STALE_SESSION)
        database.withTransaction { load(activityUuid, access.session).snapshot }
    }

    /** Shared validation for the authenticated sync transaction; do not reacquire the session lock. */
    internal suspend fun readInTransaction(activityUuid: String, session: LocalDataSession,
        scope: RecurringDisplayReadScope? = null): OneTimeLocalSnapshot {
        check(database.inTransaction())
        return load(activityUuid, session, scope).snapshot
    }

    suspend fun append(session: LocalDataSession, command: OneTimeLocalCommand,
        validateInTransaction: suspend (HabitEntity) -> Unit = {}): OneTimeLocalAppendResult =
        append(session, command, null, validateInTransaction)

    /** Explicit profile source; once keeps its independent CAS, never a challenge birth. */
    suspend fun appendRounds(scope: NextRoundWriteScope, command: OneTimeLocalCommand,
        validateInTransaction: suspend (HabitEntity) -> Unit = {}): OneTimeLocalAppendResult =
        append(scope.access.session, command, scope, validateInTransaction)

    private suspend fun append(session: LocalDataSession, command: OneTimeLocalCommand, scope: NextRoundWriteScope?,
        validateInTransaction: suspend (HabitEntity) -> Unit): OneTimeLocalAppendResult =
        sessions.exclusive {
            val access = tokens.localFactAccess()
            if (access == null || access.session != session) reject(OneTimeLocalException.Reason.STALE_SESSION)
            if (!access.canAppend) reject(OneTimeLocalException.Reason.FACTS_DENIED)
            database.withTransaction {
                val trust = CountTrustKernel(database, tokens, sessions)
                trust.withOnceCapture(tokens.localCoreWriteAccess()?.takeIf { it.session == session }, prepare = true) once@ { trusted ->
                    val rounds = NextChallengeStore(database)
                    val checkpoint = if (scope == null) { rounds.requirePlainInTransaction(); null }
                        else rounds.activeInTransaction(scope.access)
                    suspend fun verifyScope() {
                        if (scope != null) {
                            require(tokens.localSyncAccess() == scope.access && tokens.syncAuthenticationSnapshot(scope.access) != null &&
                                "facts.append" in scope.access.capabilities) { "SYNC_CHALLENGE_CONTEXT_CHANGED" }
                            check(rounds.activeInTransaction(scope.access) == checkpoint) { "SYNC_CHALLENGE_SOURCE_CHANGED" }
                        } else rounds.requirePlainInTransaction()
                        if (tokens.localFactAccess() != access) reject(OneTimeLocalException.Reason.STALE_SESSION)
                    }
                    verifyScope()
                    val loaded = load(command.activityUuid, session)
                    validateInTransaction(loaded.habit)
                    val controlBefore = trusted?.context?.let { context -> context.index.withReadScope {
                        if (!context.onceEligible(loaded.habit)) null else context.checked(
                            CountTrustMembership.key("control.id", "1"), "sync_control", "id=?", arrayOf(1)).single()
                    } }
                    val fact = fact(command, loaded.habit.id)
                    val payload = payload(fact)
                    val operation = SyncV2Operation(command.pending.operationId,
                        "activity_event", fact.uuid, "upsert", payload = Json.parseToJsonElement(payload).let {
                            require(it is kotlinx.serialization.json.JsonObject); it
                        })
                    suspend fun verifyOriginal() {
                        if (scope != null) {
                            requireNotNull(NextRequestSql.rowHash(database.openHelper.writableDatabase, "next_request_origins",
                                "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, command.pending.operationId)))
                            val original = requireNotNull(database.nextRequestDao().origin(NEXT_OPERATION, command.pending.operationId))
                            val source = requireNotNull(roundOperationIntent(original.intentJson)) { "SYNC_CHALLENGE_SOURCE_REQUIRED" }
                            require(original.accountId == session.authentication.userId && original.serverInstanceId == session.serverInstanceId &&
                                original.syncEpoch == session.syncEpoch && original.protocol == 5 && source == NextRoundOperationIntent(1,
                                operation, ChallengeSourceContext(operation.operationId, null), requireNotNull(scope.access.deviceId)))
                        }
                    }
                    val receipt = followUps.submission(command.pending.operationId)
                    if (receipt != null) {
                        if (receipt != LocalFactSubmissionEntity(command.pending.operationId,
                                "activity_event", fact.uuid, command.activityUuid, payload)) {
                            reject(OneTimeLocalException.Reason.OPERATION_ID_REUSED)
                        }
                        val stored = facts.getCompletionByUuid(fact.uuid)
                            ?: reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
                        if (stored.habitId != fact.habitId || payload(stored) != payload) {
                            reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
                        }
                        verifyOriginal(); verifyScope()
                        return@once OneTimeLocalAppendResult(stored.id, true, loaded.snapshot)
                    }
                    val previous = outbox.getByOperationId(command.pending.operationId)
                    if (previous != null) {
                        val stored = facts.getCompletionByUuid(fact.uuid)
                        if (stored == null || stored.habitId != fact.habitId || payload(stored) != payload ||
                            !matches(previous, fact, payload)
                        ) reject(OneTimeLocalException.Reason.OPERATION_ID_REUSED)
                        verifyOriginal(); verifyScope()
                        return@once OneTimeLocalAppendResult(stored.id, true, loaded.snapshot)
                    }
                    if (facts.getCompletionByUuid(fact.uuid) != null || outbox.getState("activity_event", fact.uuid) != null ||
                        followUps.submissionForEntity("activity_event", fact.uuid) != null
                    ) {
                        reject(OneTimeLocalException.Reason.EVENT_ID_REUSED)
                    }
                    val queue = loaded.snapshot.queue
                    if (habits.hasPendingNextDeletion(command.activityUuid)) reject(OneTimeLocalException.Reason.ENTITY_DELETED)
                    if (queue.blockedOperationIds.isNotEmpty()) reject(OneTimeLocalException.Reason.PENDING_REJECTED)
                    if (queue.awaitingReplayOperationIds.isNotEmpty()) reject(OneTimeLocalException.Reason.PENDING_REPLAY)
                    advanceOneTime(queue.optimisticState, command.pending.intent)

                    // Suppress only the legacy trigger; create exactly the caller's durable operation.
                    // Any failure rolls back the flag, fact and outbox together.
                    val sqlite = database.openHelper.writableDatabase
                    val enabled = sqlite.query("SELECT suppressOutbox FROM sync_control WHERE id = 1").use {
                        it.moveToFirst() && it.getInt(0) == 0
                    }
                    if (!enabled) reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
                    val expectedFactId = if (controlBefore != null) OnceTrustEvidence.nextId(database, "completions") else null
                    val expectedQueueId = if (controlBefore != null) OnceTrustEvidence.nextId(database, "sync_outbox") else null
                    val intendedQueue = SyncOutboxEntity(
                        operationId = command.pending.operationId, recordType = RECORD_TYPE,
                        entityUuid = fact.uuid, wireEntityUuid = fact.uuid, action = "upsert",
                        referenceUuid = command.activityUuid, payloadJson = payload,
                        createdAt = command.occurredAtMillis
                    )
                    val expectedQueue = expectedQueueId?.let { intendedQueue.copy(id = it) }
                    val expectedSourceHash = expectedQueue?.let(NextRequestSql::sourceHash)
                    sqlite.execSQL("UPDATE sync_control SET suppressOutbox = 1 WHERE id = 1")
                    val id = facts.insertForSync(fact)
                    sqlite.execSQL("UPDATE sync_control SET suppressOutbox = 0 WHERE id = 1")
                    val queueId = outbox.insert(intendedQueue)
                    val intendedSubmission = LocalFactSubmissionEntity(command.pending.operationId,
                        "activity_event", fact.uuid, command.activityUuid, payload)
                    followUps.insertSubmission(intendedSubmission)
                    // Prove birth with the business write, never adopt an old zero-attempt queue at send time.
                    val queueRow = requireNotNull(outbox.getByOperationId(command.pending.operationId))
                    val sourceHash = requireNotNull(NextRequestSql.rowHash(sqlite, "sync_outbox", "id=?", arrayOf(queueRow.id)))
                    val intent = if (scope == null) json.encodeToString(operation) else {
                        val source = NextRoundOperationIntent(1, operation,
                            ChallengeSourceContext(operation.operationId, null), requireNotNull(scope.access.deviceId))
                        val bytes = encodeSyncRequest(NextRoundOperationIntent.serializer(), source)
                        decodeNextOperationIntent(bytes.toString(Charsets.UTF_8))
                        val envelope = encodeSyncRequest(RoundSyncPushRequest.serializer(),
                            RoundSyncPushRequest(1, source.capturedDeviceId, listOf(operation), listOf(source.context)))
                        decodeFrozenSyncRequest(envelope, RoundSyncPushRequest.serializer())
                        bytes.toString(Charsets.UTF_8)
                    }
                    val origin = NextRequestOriginEntity(NEXT_OPERATION, command.pending.operationId, queueRow.id, 5,
                        session.authentication.userId, session.serverInstanceId, session.syncEpoch, sourceHash,
                        intent)
                    database.nextRequestDao().insertOrigin(origin)
                    val intendedPrompt = if (fact.oneTimeAction == "complete" && !preferences.getNeverAskAgain(session, loaded.habit.uuid).first()) {
                        CompletionMetricPromptStore.createInTransaction(database, loaded.habit, fact.uuid)
                    } else null
                    check(NextRequestSql.rowHash(sqlite, "next_request_origins", "kind=? AND requestId=?",
                        arrayOf(NEXT_OPERATION, origin.requestId)) != null &&
                        database.nextRequestDao().origin(NEXT_OPERATION, origin.requestId) == origin &&
                        NextRequestSql.rowHash(sqlite, "sync_outbox", "id=?", arrayOf(queueRow.id)) == sourceHash &&
                        followUps.submission(command.pending.operationId) == LocalFactSubmissionEntity(command.pending.operationId,
                            "activity_event", fact.uuid, command.activityUuid, payload))
                    verifyOriginal(); verifyScope()
                    val result = OneTimeLocalAppendResult(id, false, load(command.activityUuid, session).snapshot)
                    // Native returned/read IDs only check the frozen prediction. A mismatch
                    // keeps the optional root dirty; it never changes original business success.
                    if (trusted != null && controlBefore != null && expectedFactId != null && expectedQueue != null &&
                        id == expectedFactId && queueId == expectedQueue.id && queueRow.id == expectedQueue.id) {
                        val native = trusted.context.rows
                        val enabledControl = OnceTrustEvidence.replace(controlBefore, mapOf("suppressOutbox" to 1))
                        val effects = arrayListOf(
                            CountTrustAck.Effect("sync_control", controlBefore, enabledControl),
                            CountTrustAck.Effect("completions", null, OnceTrustEvidence.completionRow(native, fact.copy(id = expectedFactId))),
                            CountTrustAck.Effect("sync_control", enabledControl, controlBefore),
                            CountTrustAck.Effect("sync_outbox", null, OnceTrustEvidence.outboxRow(native, expectedQueue)),
                            CountTrustAck.Effect("local_fact_submissions", null, OnceTrustEvidence.submissionRow(native, intendedSubmission)),
                            CountTrustAck.Effect("next_request_origins", null, OnceTrustEvidence.originRow(native, origin.copy(
                                queueId = expectedQueue.id, sourceHash = requireNotNull(expectedSourceHash)))))
                        intendedPrompt?.let { effects += CountTrustAck.Effect("completion_metric_prompts", null,
                            OnceTrustEvidence.promptRow(native, it)) }
                        trust.finishOnce(trusted, LocalUpdate(database, trusted.context, effects, command.activityUuid))
                    }
                    result
                }
            }
        }

    private class LocalUpdate(private val owner: HabitDatabase, override val context: CountTrustKernel.Context,
        effects: List<CountTrustAck.Effect>, activity: String) : OnceTrustUpdate {
        override val effects = java.util.Collections.unmodifiableList(effects.toList())
        override val metadata: com.dayforge.data.api.dto.ChallengeMetadata? = null
        override val affectedHabitUuids: Set<String> = setOf(activity)
        override suspend fun verifyFinal(database: HabitDatabase) {
            check(database === owner && database.inTransaction()) { "ONCE_LOCAL_SCOPE_CHANGED" }
        }
    }

    private data class Loaded(val habit: HabitEntity, val snapshot: OneTimeLocalSnapshot)

    private suspend fun load(activityUuid: String, session: LocalDataSession,
        scope: RecurringDisplayReadScope? = null): Loaded = try {
        val trusted = OnceTrustProjection(database, tokens, sessions).read(activityUuid, session, scope)
        if (trusted == null) loadValidated(activityUuid, session) else Loaded(trusted.habit, trusted.snapshot)
    } catch (_: IllegalArgumentException) {
        // Invalid persisted constructors/chains are corruption, not a new user CAS conflict.
        // Cancellation and database/I/O failures must retain their original meaning.
        reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
    }

    private suspend fun loadValidated(activityUuid: String, session: LocalDataSession): Loaded =
        OnceTrustEvidence.audit(database, activityUuid, session).let { Loaded(it.habit, it.snapshot) }

    /** Independent full retained-fact oracle and cold qualifier; never takes the warm branch. */
    internal suspend fun readOriginalInTransaction(activityUuid: String, session: LocalDataSession): OneTimeLocalSnapshot {
        check(database.inTransaction())
        return loadValidated(activityUuid, session).snapshot
    }

    private fun intent(record: CompletionEntity) = OneTimeIntent(
        record.uuid,
        record.oneTimeAction ?: reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE),
        record.oneTimeExpectedVersion ?: reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE),
        record.oneTimeExpectedHeadEventUuid, record.oneTimeRevertsEventUuid
    )

    private fun fact(command: OneTimeLocalCommand, habitId: Long): CompletionEntity {
        val day = Instant.ofEpochMilli(command.occurredAtMillis).atZone(ZoneId.of(command.timezone)).toLocalDate()
        val intent = command.pending.intent
        return CompletionEntity(
            habitId = habitId, habitUuid = command.activityUuid, uuid = intent.eventUuid,
            date = day.atStartOfDay(ZoneId.of(command.timezone)).toInstant().toEpochMilli(),
            actualCompletedAt = command.occurredAtMillis, createdAt = command.occurredAtMillis,
            value = if (intent.action == "complete") 1 else 0,
            recordedTimezone = command.timezone, recordedLocalDate = day.toString(),
            oneTimeAction = intent.action, oneTimeExpectedVersion = intent.expectedVersion,
            oneTimeExpectedHeadEventUuid = intent.expectedHeadEventUuid,
            oneTimeRevertsEventUuid = intent.revertsEventUuid
        )
    }

    internal fun payload(record: CompletionEntity): String = buildJsonObject {
        put("activity_uuid", record.habitUuid)
        put("event_type", if (record.oneTimeAction == "complete") "check_in" else "revert")
        put("occurred_at", Instant.ofEpochMilli(record.actualCompletedAt
            ?: reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)).toString())
        put("local_date", record.recordedLocalDate)
        put("timezone", record.recordedTimezone)
        put("source_type", "app")
        if (record.oneTimeAction == "undo") put("reverts_event_uuid", record.oneTimeRevertsEventUuid)
        put("one_time", json.parseToJsonElement(json.encodeToString(intent(record))))
    }.toString()

    private fun matches(row: SyncOutboxEntity, record: CompletionEntity, payload: String): Boolean =
        row.recordType == RECORD_TYPE && row.entityUuid == record.uuid && row.wireEntityUuid == record.uuid &&
            row.referenceUuid == record.habitUuid && row.action == "upsert" &&
            row.baseRevision == null && row.basePayloadJson == null && row.payloadJson == payload

    private fun reject(reason: OneTimeLocalException.Reason): Nothing = throw OneTimeLocalException(reason)

    private companion object { const val RECORD_TYPE = "one_time_completion" }
}
