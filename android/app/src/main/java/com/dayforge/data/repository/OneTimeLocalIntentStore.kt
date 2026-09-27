package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.local.entity.LocalFactSubmissionEntity
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
        INVALID_LOCAL_STATE, OPERATION_ID_REUSED, EVENT_ID_REUSED, PENDING_REPLAY, PENDING_REJECTED
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
    internal suspend fun readInTransaction(activityUuid: String, session: LocalDataSession): OneTimeLocalSnapshot {
        check(database.inTransaction())
        return load(activityUuid, session).snapshot
    }

    suspend fun append(session: LocalDataSession, command: OneTimeLocalCommand): OneTimeLocalAppendResult =
        sessions.exclusive {
            val access = tokens.localFactAccess()
            if (access == null || access.session != session) reject(OneTimeLocalException.Reason.STALE_SESSION)
            if (!access.canAppend) reject(OneTimeLocalException.Reason.FACTS_DENIED)
            database.withTransaction {
                val loaded = load(command.activityUuid, session)
                val fact = fact(command, loaded.habit.id)
                val payload = payload(fact)
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
                    return@withTransaction OneTimeLocalAppendResult(stored.id, true, loaded.snapshot)
                }
                val previous = outbox.getByOperationId(command.pending.operationId)
                if (previous != null) {
                    val stored = facts.getCompletionByUuid(fact.uuid)
                    if (stored == null || stored.habitId != fact.habitId || payload(stored) != payload ||
                        !matches(previous, fact, payload)
                    ) reject(OneTimeLocalException.Reason.OPERATION_ID_REUSED)
                    return@withTransaction OneTimeLocalAppendResult(stored.id, true, loaded.snapshot)
                }
                if (facts.getCompletionByUuid(fact.uuid) != null || outbox.getState("activity_event", fact.uuid) != null ||
                    followUps.submissionForEntity("activity_event", fact.uuid) != null
                ) {
                    reject(OneTimeLocalException.Reason.EVENT_ID_REUSED)
                }
                val queue = loaded.snapshot.queue
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
                sqlite.execSQL("UPDATE sync_control SET suppressOutbox = 1 WHERE id = 1")
                val id = facts.insertForSync(fact)
                sqlite.execSQL("UPDATE sync_control SET suppressOutbox = 0 WHERE id = 1")
                outbox.insert(SyncOutboxEntity(
                    operationId = command.pending.operationId, recordType = RECORD_TYPE,
                    entityUuid = fact.uuid, wireEntityUuid = fact.uuid, action = "upsert",
                    referenceUuid = command.activityUuid, payloadJson = payload,
                    createdAt = command.occurredAtMillis
                ))
                followUps.insertSubmission(LocalFactSubmissionEntity(command.pending.operationId,
                    "activity_event", fact.uuid, command.activityUuid, payload))
                if (fact.oneTimeAction == "complete" && !preferences.getNeverAskAgain(loaded.habit.id).first()) {
                    CompletionMetricPromptStore.createInTransaction(database, loaded.habit, fact.uuid)
                }
                OneTimeLocalAppendResult(id, false, load(command.activityUuid, session).snapshot)
            }
        }

    private data class Loaded(val habit: HabitEntity, val snapshot: OneTimeLocalSnapshot)

    private suspend fun load(activityUuid: String, session: LocalDataSession): Loaded = try {
        loadValidated(activityUuid, session)
    } catch (_: IllegalArgumentException) {
        // Invalid persisted constructors/chains are corruption, not a new user CAS conflict.
        // Cancellation and database/I/O failures must retain their original meaning.
        reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
    }

    private suspend fun loadValidated(activityUuid: String, session: LocalDataSession): Loaded {
        if (outbox.getState("plan_node", activityUuid)?.deleted == true) reject(OneTimeLocalException.Reason.ENTITY_DELETED)
        val habit = habits.getHabitByUuid(activityUuid) ?: reject(OneTimeLocalException.Reason.ACTIVITY_NOT_FOUND)
        if (habit.completionPolicy != "one_and_done" || habit.oneTimeConfirmedVersion == null) {
            reject(OneTimeLocalException.Reason.UNINITIALIZED)
        }
        if (habit.habitType != HabitType.CHECK_IN || habit.targetValue != 1 || habit.isCountdown ||
            habit.failMode != FailMode.LOOSE || habit.targetCycles != null || habit.bestTime != null
        ) reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
        val confirmed = OneTimeState(habit.oneTimeConfirmedVersion,
            habit.oneTimeConfirmedHeadEventUuid, habit.oneTimeConfirmedCompletionEventUuid)
        val records = facts.getByHabitOnce(habit.id)
        if (records.map { it.uuid }.distinct().size != records.size) reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
        val byUuid = records.associateBy { it.uuid }
        if (confirmed.version > 0) {
            val head = byUuid[confirmed.headEventUuid] ?: reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
            val headIntent = intent(head)
            val before = OneTimeState(headIntent.expectedVersion, headIntent.expectedHeadEventUuid,
                headIntent.expectedHeadEventUuid.takeIf { headIntent.expectedVersion % 2 == 1 })
            if (advanceOneTime(before, headIntent) != confirmed) reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
        }
        val rows = outbox.getActivityIntents(activityUuid)
        val pending = rows.map { row ->
            val record = byUuid[row.entityUuid] ?: reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
            if (!matches(row, record, payload(record))) reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
            PendingOneTimeIntent(row.operationId, intent(record))
        }
        val pendingEventIds = pending.map { it.intent.eventUuid }.toSet()
        records.forEach { record ->
            if (record.habitUuid != activityUuid || record.value != if (record.oneTimeAction == "complete") 1 else 0) {
                reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
            }
            val recordIntent = intent(record)
            // Older confirmed facts can be absent during incremental pull, but every fact
            // that is present must still prove a legal transition of its own.
            advanceOneTime(OneTimeState(recordIntent.expectedVersion, recordIntent.expectedHeadEventUuid,
                recordIntent.expectedHeadEventUuid.takeIf { recordIntent.expectedVersion % 2 == 1 }), recordIntent)
            val instant = record.actualCompletedAt?.let(Instant::ofEpochMilli)
                ?: reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
            if (record.recordedTimezone !in ZoneId.getAvailableZoneIds()) reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
            val captured = instant.atZone(ZoneId.of(record.recordedTimezone))
            if (instant.atZone(ZoneId.of("UTC")).year !in 1..9999 || captured.year !in 1..9999 ||
                captured.toLocalDate().toString() != record.recordedLocalDate
            ) reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
            if (recordIntent.expectedVersion >= confirmed.version && record.uuid !in pendingEventIds) {
                reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
            }
        }
        val rejected = rows.firstOrNull { it.deadLetteredAt != null }?.operationId
        return Loaded(habit, OneTimeLocalSnapshot(activityUuid, session, confirmed,
            projectPendingOneTime(confirmed, pending, rejected)))
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

    private fun payload(record: CompletionEntity): String = buildJsonObject {
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
