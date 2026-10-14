package com.dayforge.data.repository

import com.dayforge.data.api.dto.*
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.domain.model.*
import kotlinx.serialization.json.*

/** Capture with the displayed domain snapshot, not when an old callback finally runs. */
internal class NextRoundWriteScope internal constructor(
    val access: LocalSyncAccess,
    internal val metadataJson: String,
    internal val pendingInitials: Map<String, NextPendingInitial> = emptyMap(),
    internal val pendingRestarts: Map<String, NextRestartReference> = emptyMap()
)

/** Transaction participant for NEW operations and local timer sources. Not a restart producer. */
internal class NextRoundOperationCapture(
    private val database: HabitDatabase,
    private val scope: NextRoundWriteScope,
    private val before: ChallengeMetadata,
    private val existing: List<HabitEntity>,
    private val pendingInitials: Map<String, NextPendingInitial> = emptyMap(),
    private val pendingRestarts: Map<String, NextRestartReference> = emptyMap()
) {
    private val shown = Json.decodeFromString<ChallengeMetadata>(scope.metadataJson)
    private val touched = mutableSetOf<String>()
    private val freshHeads = mutableMapOf<String, ChallengeRoundHead>()
    private val capturedInitialSources = mutableSetOf<String>()
    private val goalFrontiers = mutableListOf<NextRoundOperationIntent>()
    private val capturedRestarts = mutableSetOf<NextRestartReference>()

    private suspend fun current(activity: String): ChallengeRoundHead {
        freshHeads[activity]?.let { return it }
        pendingRestarts[activity]?.let { pending ->
            require(scope.pendingRestarts[activity] == pending) { "SYNC_CHALLENGE_STALE_ACTION" }
            NextRestartBindingStore(database).captured(scope.access, pending)
            capturedRestarts += pending
            touched += activity
            return pending.head
        }
        val actual = before.checkpoints.singleOrNull { it.head.activityUuid == activity }?.head
        val expected = scope.pendingRestarts[activity]?.head ?: shown.checkpoints.singleOrNull { it.head.activityUuid == activity }?.head
            ?: scope.pendingInitials[activity]?.head
        scope.pendingRestarts[activity]?.let {
            NextRestartBindingStore(database).ready(scope.access, it)
            capturedRestarts += it
        }
        if (actual != null) {
            require(expected == actual) { "SYNC_CHALLENGE_STALE_ACTION" }
            val latest = before.checkpoints.single { it.head == actual }.records.single { it.head == actual }
            latest.restartIntent?.let { intent ->
                val sql = database.openHelper.writableDatabase
                requireNotNull(NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?",
                    arrayOf("plan_node", activity)))
                val shadow = requireNotNull(database.syncOutboxDao().getState("plan_node", activity))
                require(!shadow.deleted && shadow.revision >= Math.addExact(intent.expectedPlanRevision, 1L) &&
                    shadow.payloadHash == syncPayloadHash(requireNotNull(shadow.payloadJson))) { "SYNC_CHALLENGE_PLAN_CATCHUP_REQUIRED" }
            }
            touched += activity
            return actual
        }
        pendingInitials[activity]?.let { pending ->
            require(scope.pendingInitials[activity] == pending && expected == pending.head) { "SYNC_CHALLENGE_STALE_ACTION" }
            require(existing.single { it.uuid == activity }.let {
                it.completionPolicy == "recurring" && it.habitType != com.dayforge.data.model.HabitType.GOAL
            })
            touched += activity
            return pending.head
        }
        // Only a genuinely NEW local identity in this transaction gets a deterministic baseline.
        // An omitted/deleted old activity must never be guessed into generation zero.
        require(expected == null && existing.none { it.uuid == activity }) { "SYNC_CHALLENGE_HEAD_REQUIRED" }
        val sql = database.openHelper.writableDatabase
        require(NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?",
            arrayOf("plan_node", activity)) == null) { "SYNC_CHALLENGE_HEAD_REQUIRED" }
        require(!sql.query("SELECT 1 FROM next_request_origins WHERE intentJson LIKE ? LIMIT 1",
            arrayOf("%$activity%")).use { it.moveToFirst() }) { "SYNC_CHALLENGE_HEAD_REQUIRED" }
        val habit = requireNotNull(database.habitDao().getHabitByUuid(activity))
        require(habit.completionPolicy == "recurring" && habit.habitType != com.dayforge.data.model.HabitType.GOAL)
        touched += activity
        return initialChallengeRoundHead(activity).also { freshHeads[activity] = it }
    }

    suspend fun capture(operation: SyncV2Operation): NextRoundOperationIntent {
        if (operation.entityType == "plan_node" && operation.action == "delete" && "child_policy" in operation.payload &&
            existing.any { it.parentHabitId == operation.entityUuid && it.uuid in pendingRestarts })
            rejectNextRequest(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING)
        val head = when (operation.entityType) {
            "plan_node" -> {
                val original = existing.singleOrNull { it.uuid == operation.entityUuid }
                val recurring = if (original != null) original.let {
                    it.habitType != com.dayforge.data.model.HabitType.GOAL && it.completionPolicy == "recurring"
                } else (operation.payload["activity"] as? JsonObject)?.get("completion_policy") == JsonPrimitive("recurring")
                if (recurring) current(operation.entityUuid) else null
            }
            "activity_event" -> {
                require(operation.payload["one_time"].let { it == null || it == JsonNull })
                val activity = operation.payload.getValue("activity_uuid").jsonPrimitive.content
                val current = current(activity) // Even an intentional historical undo needs a fresh action ticket.
                val reverted = operation.payload["reverts_event_uuid"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
                if (reverted == null) current else before.births.singleOrNull {
                    it.entityType == "activity_event" && it.entityUuid == reverted && it.head.activityUuid == activity
                }?.head ?: pendingBirth(reverted, activity)
            }
            else -> null
        }
        val frontier = if (operation.entityType == "plan_node" && operation.action == "delete" && "child_policy" in operation.payload)
            NextGoalChildFrontierStore(database).capture(operation, scope.access, existing) else null
        return NextRoundOperationIntent(1, operation, ChallengeSourceContext(operation.operationId, head),
            requireNotNull(scope.access.deviceId), initialCreation = operation.entityType == "plan_node" &&
                operation.action == "upsert" && freshHeads.containsKey(operation.entityUuid) && existing.none { it.uuid == operation.entityUuid } &&
                capturedInitialSources.add(operation.entityUuid), goalChildFrontier = frontier,
            restartFrontier = head?.activityUuid?.let { scope.pendingRestarts[it] })
            .also { if (frontier != null) goalFrontiers += it }
    }

    suspend fun captureTimer(intent: NextTimerIntent): NextRoundTimerIntent {
        val command = intent.command
        var restart: NextRestartReference? = null
        val head = if (command.commandType == "start") {
            val local = requireNotNull(database.timeLogDao().getTimeLogByUuid(command.sessionId)) { "SYNC_CHALLENGE_TIMER_LOCAL_START_REQUIRED" }
            require(database.habitDao().getHabitById(local.habitId)?.uuid == command.activityUuid &&
                java.time.Instant.ofEpochMilli(local.startTime) == java.time.Instant.parse(command.occurredAt) &&
                local.timerTimezone == command.timezone)
            val segment = database.timeLogDao().getTimerSegments(command.sessionId).firstOrNull()
            require(segment?.sequence == 1 && segment.startedAt == local.startTime) { "SYNC_CHALLENGE_TIMER_LOCAL_START_REQUIRED" }
            current(requireNotNull(command.activityUuid)).also { restart = scope.pendingRestarts[it.activityUuid] }
        } else {
            // Successors retain the actual local immutable start's birth, even if today's head moved.
            // Remote-recovered sessions need a separate authenticated policy/origin proof, not a
            // fake local start receipt or a lookup of the current activity configuration.
            val starts = NextTimerPolicyStore(database).starts(sessionUuid = command.sessionId)
            val (origin, start) = starts.single { it.second.command.sessionId == command.sessionId }
            val round = requireNotNull(roundTimerIntent(origin.intentJson)) { "SYNC_CHALLENGE_TIMER_START_REQUIRED" }
            restart = round.restartFrontier
            restart?.let { NextRestartBindingStore(database).captured(scope.access, it, start.planQueueWatermark); capturedRestarts += it }
            require(origin.accountId == scope.access.session.authentication.userId &&
                origin.serverInstanceId == scope.access.session.serverInstanceId && origin.syncEpoch == scope.access.session.syncEpoch &&
                round.capturedDeviceId == scope.access.deviceId && round.timer == start &&
                round.timer.command.commandId == origin.requestId)
            require(NextTimerPolicyStore(database).policy(com.dayforge.data.local.LocalCoreWriteAccess(
                scope.access.session, scope.access.capabilities, scope.access.deviceId), command.sessionId) == start.command.startPolicy)
            requireNotNull(round.context.head).also { birth ->
                require(known(birth) || restart?.head == birth)
                if (database.nextRequestDao().acceptance(NEXT_TIMER, origin.requestId) != null)
                    require(before.requireBirth("timer_session", command.sessionId, birth.activityUuid).head == birth)
                before.births.singleOrNull { it.entityType == "timer_session" && it.entityUuid == command.sessionId }
                    ?.let { require(it.head == birth) }
            }
        }
        return NextRoundTimerIntent(1, intent, ChallengeSourceContext(command.commandId, head), requireNotNull(scope.access.deviceId), restart)
    }

    /** Undo of an unaccepted local fact inherits its immutable NEW source, never current appearance. */
    private suspend fun pendingBirth(identity: String, activity: String): ChallengeRoundHead {
        val sql = database.openHelper.writableDatabase
        val ids = sql.query("SELECT requestId FROM next_request_origins WHERE kind=? AND intentJson LIKE ? LIMIT 10001",
            arrayOf(NEXT_OPERATION, "%$identity%")).use { c -> buildList { while (c.moveToNext()) {
                require(c.getType(0) == android.database.Cursor.FIELD_TYPE_STRING); add(c.getString(0))
            } } }
        require(ids.size <= 10000)
        val matches = ids.mapNotNull { id ->
            requireNotNull(NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id)))
            val row = requireNotNull(database.nextRequestDao().origin(NEXT_OPERATION, id))
            val source = roundOperationIntent(row.intentJson) ?: return@mapNotNull null
            val operation = source.operation
            if (operation.entityType != "activity_event" || operation.entityUuid != identity) return@mapNotNull null
            require(row.protocol == 5 && row.accountId == scope.access.session.authentication.userId &&
                row.serverInstanceId == scope.access.session.serverInstanceId && row.syncEpoch == scope.access.session.syncEpoch &&
                source.capturedDeviceId == scope.access.deviceId && operation.operationId == id &&
                operation.payload["activity_uuid"] == JsonPrimitive(activity) &&
                operation.payload["reverts_event_uuid"].let { it == null || it == JsonNull } &&
                NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(row.queueId)) == row.sourceHash &&
                database.nextRequestDao().acceptance(NEXT_OPERATION, id) == null)
            requireNotNull(source.context.head).also { head ->
                source.restartFrontier?.let {
                    NextRestartBindingStore(database).captured(scope.access, it, row.queueId - 1)
                    capturedRestarts += it
                }
                require(head.activityUuid == activity && (known(head) || source.restartFrontier?.head == head))
            }
        }
        return matches.single()
    }

    private fun known(head: ChallengeRoundHead) = before.checkpoints.any { point -> point.records.any { it.head == head } } ||
        pendingInitials[head.activityUuid]?.head == head || freshHeads[head.activityUuid] == head

    suspend fun verify(after: ChallengeMetadata) {
        require(after == before) { "SYNC_CHALLENGE_SOURCE_CHANGED" }
        for (source in goalFrontiers) for (item in requireNotNull(source.goalChildFrontier))
            NextGoalChildFrontierStore(database).validateCaptured(item, source.operation, scope.access)
        // Inherited timer successors do not call current(): their birth must not follow today's
        // head. Re-audit all prior pending roots once, including origin-only late trigger faults.
        if (pendingInitials.isNotEmpty()) {
            val actualPending = NextRoundPendingInitialStore(database).read(scope.access, after)
            require(pendingInitials.all { (activity, proof) -> actualPending[activity] == proof }) {
                "SYNC_CHALLENGE_INITIAL_SOURCE_CHANGED"
            }
        }
        require(NextRestartStore(database).pending(scope.access, after,
            NextRoundPendingInitialStore(database).read(scope.access, after)) == pendingRestarts) { "SYNC_RESTART_SOURCE_CHANGED" }
        for (reference in capturedRestarts) NextRestartBindingStore(database).captured(scope.access, reference)
        for (activity in touched) {
            val actual = pendingRestarts[activity]?.head ?: before.checkpoints.singleOrNull { it.head.activityUuid == activity }?.head
            if (actual != null) require((scope.pendingRestarts[activity]?.head ?: shown.checkpoints.singleOrNull { it.head.activityUuid == activity }?.head
                ?: scope.pendingInitials[activity]?.head) == actual)
            else pendingInitials[activity]?.let { pending ->
                require(scope.pendingInitials[activity] == pending)
            }
        }
    }
}
