package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import com.dayforge.data.api.dto.*
import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.*
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.domain.service.DurationDayAllocator
import com.dayforge.util.DateTimeUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** One account/Room snapshot; audit ALL retained timers before projecting the displayed round. */
@Singleton
class TimerHistoryReader @Inject constructor(private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator) {
    val changes = combine(database.timeLogDao().observeReadEvidenceChanges(), tokens.factAccessChanges) { _, _ -> Unit }
    private val sql get() = database.openHelper.writableDatabase

    suspend fun read(expected: HabitEntity, today: LocalDate = DateTimeUtils.today()): TimerHistory = withContext(Dispatchers.IO) {
        sessions.exclusive { database.withTransaction { readInTransaction(expected, today) } }
    }

    internal suspend fun readInTransaction(expected: HabitEntity, today: LocalDate): TimerHistory {
        check(database.inTransaction()) { "TIMER_READ_TRANSACTION_REQUIRED" }
        val access = requireNotNull(tokens.localCoreWriteAccess()) { "TIMER_SESSION_CHANGED" }
        val habit = requireNotNull(database.habitDao().getHabitById(expected.id))
        check(habit == expected && habit.appearance != null && habit.habitType == HabitType.TIMER &&
            habit.completionPolicy == "recurring") { "TIMER_ACTIVITY_CHANGED" }
        require(habit.targetValue > 0 && isContractUuid(habit.uuid)) { "TIMER_ACTIVITY_INVALID" }
        val scope = NextCoreLocalIntentStore(database, tokens, sessions).captureDisplayedRoundsInTransaction()
        val metadata = scope?.let { Json.decodeFromString(ChallengeMetadata.serializer(), it.metadataJson) }
        val checkpoint = metadata?.checkpoints?.singleOrNull { it.head.activityUuid == habit.uuid }
        val head = scope?.let { it.pendingRestarts[habit.uuid]?.head ?: checkpoint?.head ?:
            requireNotNull(it.pendingInitials[habit.uuid]) { "TIMER_ROUND_REQUIRED" }.head }
        val known = checkpoint?.records?.mapTo(hashSetOf()) { it.head }.orEmpty() + listOfNotNull(scope?.pendingInitials?.get(habit.uuid)?.head)
        val originals = originalStarts(habit.uuid)
        auditStorage(habit.id)
        val logs = database.timeLogDao().getAllTimeLogsForHabit(habit.id)
        val reverted = acceptedReverts(habit)
        check(logs.count { it.endTime == null } <= 1) { "TIMER_FAILURE_MULTIPLE_ACTIVE_SESSIONS" }
        val visible = ArrayList<TimeLogEntity>()
        val totals = linkedMapOf<LocalDate, Long>()
        var active: TimeLogEntity? = null
        var activeSegments = emptyList<TimerSegmentEntity>()
        for (log in logs) {
            currentCoroutineContext().ensureActive()
            check(log.uuid !in reverted) { "TIMER_REVERTED_FACT_PRESENT" }
            val shadow = accepted(log.uuid)
            val sessionId = shadow?.payload?.getValue("metadata")?.jsonObject?.getValue("timer_session_id")?.let {
                require(it is JsonPrimitive && it.isString && isContractUuid(it.content)); it.content
            } ?: log.uuid
            val original = originals[sessionId]
            val segments = database.timeLogDao().getTimerSegments(log.uuid)
            val allocations = database.timeLogDao().getDayAllocations(log.uuid)
            check(isContractUuid(log.uuid) && log.durationSeconds in 0..86_400 && log.timerActiveElapsedMillis in 0..86_400_000 &&
                log.timerControlGeneration >= 0 && log.timerNextCommandSequence > 0 && log.accumulatedPauseMillis >= 0 &&
                log.timerTimezone in ZoneId.getAvailableZoneIds()) { "TIMER_STORAGE_INVALID" }
            if (segments.isNotEmpty()) validateSegments(log, segments)
            val source = original?.first?.let { roundTimerIntent(it.intentJson) }
            val pendingHead = source?.takeIf { it.context.head !in known }?.restartFrontier?.let { ref ->
                requireNotNull(scope) { "TIMER_PROFILE_REQUIRED" }
                NextRestartBindingStore(database).captured(scope.access, ref, source.timer.planQueueWatermark)
                ref.head
            }
            original?.let { (row, start) ->
                check(row.accountId == access.session.authentication.userId && start.command.sessionId == sessionId &&
                    Instant.parse(start.command.occurredAt).toEpochMilli() == log.startTime && start.command.timezone == log.timerTimezone) {
                    "TIMER_START_CHANGED"
                }
                auditCommand(row, start.command, access.session.authentication.userId)
            }
            if (shadow != null) {
                check(log.endTime != null) { "TIMER_FACT_INVALID" }
                val mapped = NextCommonFactMapper.duration(shadow, habit, log)
                check(mapped.session == log && mapped.allocations == allocations) { "TIMER_FACT_INVALID" }
                // A remote completed fact is already a full immutable duration proof. Do not
                // manufacture a local start or recompute its allocations from wall timestamps.
            } else {
                val (row, start) = requireNotNull(original) { "TIMER_START_PROOF_REQUIRED" }
                check(row.serverInstanceId == null || row.serverInstanceId == access.session.serverInstanceId &&
                    row.syncEpoch == access.session.syncEpoch) { "TIMER_START_CONTEXT_CHANGED" }
                check(start.capturedDeviceId == access.capturedDeviceId) { "TIMER_START_DEVICE_CHANGED" }
                val policy = NextTimerPolicyStore(database).policy(access, log.uuid)
                validateSegments(log, segments)
                check(log.timerNextCommandSequence > 1 && log.timerControlGeneration > 0 && log.timerLastCommandAt != null)
                val commands = commandOrigins(log.uuid).sortedBy { it.second.sequence }
                check(commands.size == log.timerNextCommandSequence - 1 && commands.map { it.second.sequence } == (1 until log.timerNextCommandSequence).toList()) {
                    "TIMER_COMMAND_CHAIN_REQUIRED"
                }
                commands.forEach { auditCommand(it.first, it.second, access.session.authentication.userId) }
                var running = true
                for ((index, entry) in commands.withIndex()) {
                    val command = entry.second
                    if (index == 0) require(command == start.command)
                    else when (command.commandType) {
                        "pause" -> { require(running); running = false }
                        "resume" -> { require(!running); running = true }
                        "stop" -> require(log.endTime != null && index == commands.lastIndex)
                        else -> error("TIMER_COMMAND_CHAIN_INVALID")
                    }
                    require(command.sequence == 1 || command.expectedControlGeneration == log.timerControlGeneration)
                    source?.let { birthSource ->
                        val next = requireNotNull(roundTimerIntent(entry.first.intentJson))
                        require(next.context.head == birthSource.context.head && next.capturedDeviceId == birthSource.capturedDeviceId)
                    }
                }
                check(log.endTime != null || log.isPaused == !running) { "TIMER_STATE_CHANGED" }
                check(Instant.parse(commands.last().second.occurredAt).toEpochMilli() == log.timerLastCommandAt) { "TIMER_COMMAND_TIME_CHANGED" }
                if (log.endTime == null) {
                    check(log.pausedAt == if (log.isPaused) log.timerLastCommandAt else null) { "TIMER_PAUSE_CHANGED" }
                    commands.lastOrNull { it.second.commandType == "pause" }?.second?.activeElapsedMs?.let {
                        check(log.timerActiveElapsedMillis == it) { "TIMER_ELAPSED_CHANGED" }
                    }
                }
                val runs = commands.map { it.second }.filter { it.commandType in setOf("start", "resume") }
                check(runs.size == segments.size) { "TIMER_SEGMENT_PROOF_REQUIRED" }
                for ((segment, command) in segments.zip(runs)) {
                    val closing = commands.getOrNull(command.sequence)?.second
                    check(segment.sequence == command.sequence && segment.startedAt == Instant.parse(command.occurredAt).toEpochMilli() &&
                        segment.endedAt == closing?.let { require(it.commandType in setOf("pause", "stop")); Instant.parse(it.occurredAt).toEpochMilli() }) {
                        "TIMER_SEGMENT_PROOF_CHANGED"
                    }
                }
                if (log.endTime == null) {
                    check(allocations.isEmpty()) { "TIMER_ACTIVE_ALLOCATION_INVALID" }
                } else {
                    check(!log.isPaused && log.pausedAt == null &&
                        log.timerActiveElapsedMillis / 1000 == log.durationSeconds.toLong() &&
                        log.timerActiveElapsedMillis in policy.targetSeconds.toLong() * 1000..policy.maxDurationSeconds.toLong() * 1000) {
                        "TIMER_COMPLETION_INVALID"
                    }
                    val stop = commands.singleOrNull { it.second.commandType == "stop" &&
                        it.second.sequence == log.timerNextCommandSequence - 1 } ?: error("TIMER_STOP_PROOF_REQUIRED")
                    check(Instant.parse(stop.second.occurredAt).toEpochMilli() == log.endTime &&
                        stop.second.activeElapsedMs == log.timerActiveElapsedMillis) { "TIMER_STOP_CHANGED" }
                    check(allocations == DurationDayAllocator.allocate(log.uuid, habit.id, requireNotNull(log.timerTimezone),
                        segments, log.timerActiveElapsedMillis) &&
                        allocations.fold(0L) { sum, a -> Math.addExact(sum, a.durationMillis) } == log.timerActiveElapsedMillis) {
                        "TIMER_ALLOCATION_INVALID"
                    }
                }
            }
            val birth = if (scope == null) null else {
                val type = if (shadow == null) "timer_session" else "activity_event"
                val acceptedBirth = metadata!!.births.singleOrNull { it.entityType == type && it.entityUuid == log.uuid }?.head
                acceptedBirth?.let { require(it.activityUuid == habit.uuid && it in known) { "TIMER_ROUND_INVALID" } }
                if (shadow != null) {
                    require(shadow.payload["external_event_id"] == JsonPrimitive(sessionId)) { "TIMER_SESSION_ID_CHANGED" }
                    val sessionBirth = metadata.births.singleOrNull { it.entityType == "timer_session" && it.entityUuid == sessionId }?.head
                    require(sessionBirth != null && sessionBirth == acceptedBirth) { "TIMER_SESSION_BIRTH_REQUIRED" }
                }
                source?.let { require(it.context.head in known || it.context.head == pendingHead) { "TIMER_ROUND_INVALID" }
                    acceptedBirth?.let { birth -> require(it.context.head == birth) { "TIMER_ROUND_INVALID" } } }
                if (shadow != null) requireNotNull(acceptedBirth) { "TIMER_ROUND_BIRTH_REQUIRED" }
                else acceptedBirth ?: run {
                    requireNotNull(source) { "TIMER_ROUND_SOURCE_REQUIRED" }
                    require(source.capturedDeviceId == scope.access.deviceId && source.context.head?.activityUuid == habit.uuid)
                    require(database.nextRequestDao().acceptance(NEXT_TIMER, original!!.first.requestId) == null) {
                        "TIMER_ROUND_BIRTH_REQUIRED"
                    }
                    requireNotNull(source.context.head)
                }
            }
            if (birth == head) {
                visible += log
                if (log.endTime == null) { active = log; activeSegments = segments }
                else {
                    allocations.forEach { allocation ->
                        val date = LocalDate.parse(allocation.localDate)
                        totals[date] = Math.addExact(totals[date] ?: 0L, allocation.durationMillis)
                    }
                }
            }
        }
        check(tokens.localCoreWriteAccess() == access && (scope == null || tokens.localSyncAccess() == scope.access)) { "TIMER_SESSION_CHANGED" }
        return TimerHistory(today, Math.multiplyExact(habit.targetValue.toLong(), 60L), totals, visible, active, activeSegments, head)
    }

    /** Stream the retained index once; a habit's lifetime command count is not a read limit. */
    private suspend fun originalStarts(activity: String): Map<String, Pair<NextRequestOriginEntity, NextTimerIntent>> {
        val starts = linkedMapOf<String, Pair<NextRequestOriginEntity, NextTimerIntent>>()
        val page = ArrayList<String>(128)
        sql.query("SELECT requestId FROM next_request_origins WHERE kind=? AND (intentJson LIKE ? OR requestId IN " +
            "(SELECT commandId FROM timer_command_outbox WHERE activityUuid=?))", arrayOf(NEXT_TIMER, "%$activity%", activity)).use { c ->
            while (c.moveToNext()) {
                currentCoroutineContext().ensureActive()
                check(c.getType(0) == Cursor.FIELD_TYPE_STRING)
                page += c.getString(0)
                if (page.size == 128) {
                    originalStartPage(page, activity, starts)
                    page.clear()
                }
            }
        }
        if (page.isNotEmpty()) originalStartPage(page, activity, starts)
        return starts
    }

    /** Batch only SELECT hints, with the identical raw row hash and per-row oversized fallback. */
    private suspend fun originalStartPage(ids: List<String>, activity: String,
        starts: MutableMap<String, Pair<NextRequestOriginEntity, NextTimerIntent>>) {
        val originHints = NextRequestSql.boundedRowHashes(sql, "next_request_origins", "requestId", ids, NEXT_TIMER)
        if (originHints == null) {
            for (id in ids) {
                requireNotNull(NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_TIMER, id)))
                val row = requireNotNull(database.nextRequestDao().origin(NEXT_TIMER, id))
                val hash = NextRequestSql.rowHash(sql, "timer_command_outbox", "id=?", arrayOf(row.queueId))
                collectStart(row, decodeNextTimerIntent(row.intentJson),
                    hash?.let { database.timeLogDao().getTimerCommand(row.queueId) }, hash, activity, starts)
            }
            return
        }
        require(ids.all { originHints[it] != null })
        val rows = database.nextRequestDao().origins(NEXT_TIMER, ids)
        require(rows.size == ids.size && rows.map { it.requestId }.toSet() == ids.toSet())
        // These original rows were just audited and bounded to 2 MiB. Decode on one CPU
        // dispatch per page, not one Room -> Default -> Room round trip per retained row.
        // Keep the same strict decoder and cancellation checkpoints; SQL stays in Room.
        val decoded = withContext(Dispatchers.Default) {
            rows.associate { row ->
                currentCoroutineContext().ensureActive()
                row.requestId to decodeNextTimerIntent(row.intentJson)
            }
        }
        val queueIds = rows.map { it.queueId }
        val queueHints = NextRequestSql.boundedRowHashes(sql, "timer_command_outbox", "id", queueIds)
        if (queueHints == null) {
            for (row in rows) {
                val hash = NextRequestSql.rowHash(sql, "timer_command_outbox", "id=?", arrayOf(row.queueId))
                collectStart(row, decoded.getValue(row.requestId),
                    hash?.let { database.timeLogDao().getTimerCommand(row.queueId) }, hash, activity, starts)
            }
            return
        }
        val present = queueIds.filter { queueHints[it] != null }
        val queued = if (present.isEmpty()) emptyList() else database.timeLogDao().getTimerCommands(present)
        val commands = queued.associateBy { it.id }
        require(commands.size == queued.size && commands.keys == present.toSet())
        for (row in rows) collectStart(row, decoded.getValue(row.requestId),
            commands[row.queueId], queueHints[row.queueId], activity, starts)
    }

    private suspend fun collectStart(row: NextRequestOriginEntity, intent: NextTimerIntent,
        queued: TimerCommandEntity?, queuedHash: String?,
        activity: String, starts: MutableMap<String, Pair<NextRequestOriginEntity, NextTimerIntent>>) {
        currentCoroutineContext().ensureActive()
        require(row.queueId > 0)
        val command = intent.command
        require(command.commandId == row.requestId && isContractUuid(command.sessionId))
        require((queued == null) == (queuedHash == null))
        queued?.let {
            require(it.id == row.queueId && queuedHash == row.sourceHash && timerRequest(it) == command.copy(startPolicy = null))
        }
        if (command.commandType == "start" && command.startPolicy != null && command.activityUuid == activity) {
            command.startPolicy.validate()
            require(intent.planQueueWatermark != null && intent.planQueueWatermark >= 0 &&
                (intent.planPredecessorId == null || isContractUuid(intent.planPredecessorId)) &&
                (intent.capturedDeviceId == null || isContractUuid(intent.capturedDeviceId)))
            check(starts.put(command.sessionId, row to intent) == null) { "TIMER_DUPLICATE_START" }
        }
    }

    private suspend fun accepted(id: String, duration: Boolean = true): SyncV2Change? {
        NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?", arrayOf("activity_event", id)) ?: return null
        val state = requireNotNull(database.syncOutboxDao().getState("activity_event", id))
        check(!state.deleted && state.payloadJson != null && state.payloadHash == syncPayloadHash(state.payloadJson)) { "TIMER_FACT_INVALID" }
        val body = Json.parseToJsonElement(state.payloadJson).jsonObject
        return SyncV2Change(0, "activity_event", id, "upsert", state.revision, body,
            body.getValue("updated_at").jsonPrimitive.content).also {
                if (duration) NextCommonFactMapper.validateDurationSnapshot(it) else NextCommonFactMapper.validateOrdinaryFactSnapshot(it)
            }
    }

    private suspend fun acceptedReverts(habit: HabitEntity): Set<String> {
        val ids = sql.query("SELECT entityUuid FROM sync_entity_state WHERE entityType=? AND payloadJson LIKE ? AND payloadJson LIKE ?",
            arrayOf("activity_event", "%${habit.uuid}%", "%\"event_type\"%:%\"revert\"%"))
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return ids.mapNotNull { id ->
            val state = requireNotNull(accepted(id, duration = false))
            if (state.payload["activity_uuid"] == JsonPrimitive(habit.uuid) && state.payload["event_type"] == JsonPrimitive("revert"))
                NextCommonFactMapper.revert(state, habit) else null
        }.toSet()
    }

    private suspend fun commandOrigins(session: String): List<Pair<NextRequestOriginEntity, TimerCommandRequest>> {
        val ids = sql.query("SELECT requestId FROM next_request_origins WHERE kind=? AND (intentJson LIKE ? OR requestId IN " +
            "(SELECT commandId FROM timer_command_outbox WHERE sessionUuid=?))", arrayOf(NEXT_TIMER, "%$session%", session))
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return ids.mapNotNull { id ->
            requireNotNull(NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_TIMER, id)))
            val row = requireNotNull(database.nextRequestDao().origin(NEXT_TIMER, id))
            val command = decodeNextTimerIntent(row.intentJson).command
            if (command.sessionId == session) row to command else null
        }.also { rows -> require(rows.map { it.second.sequence }.distinct().size == rows.size) }
    }

    /** Verify retained original journals with their OWN capture, not today's device/epoch. Read-only. */
    private suspend fun auditCommand(origin: NextRequestOriginEntity, command: TimerCommandRequest, account: String) {
        val args = arrayOf<Any>(NEXT_TIMER, origin.requestId)
        val originHash = requireNotNull(NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?", args))
        require(origin.accountId == account && origin.protocol == 5 && origin.queueId > 0 && command.commandId == origin.requestId)
        val transmissionHash = NextRequestSql.rowHash(sql, "next_transmissions", "kind=? AND requestId=?", args)
        val acceptanceHash = NextRequestSql.rowHash(sql, "next_acceptances", "kind=? AND requestId=?", args)
        val sent = if (transmissionHash == null) null else requireNotNull(database.nextRequestDao().transmission(NEXT_TIMER, origin.requestId)).also {
            require(it.protocol == 5 && it.accountId == account && it.queueId == origin.queueId &&
                (origin.serverInstanceId == null || it.serverInstanceId == origin.serverInstanceId && it.syncEpoch == origin.syncEpoch) &&
                it.wireHash == nextRequestHash(it.wireBytes))
            require(validateNextTimerEnvelope(origin.intentJson, it.wireBytes, it.deviceId) == command)
        }
        if (acceptanceHash == null) {
            require(NextRequestSql.rowHash(sql, "timer_command_outbox", "id=?", arrayOf(origin.queueId)) == origin.sourceHash)
            require(timerRequest(requireNotNull(database.timeLogDao().getTimerCommand(origin.queueId))) == command.copy(startPolicy = null))
        } else {
            requireNotNull(sent)
            val receipt = requireNotNull(database.nextRequestDao().acceptance(NEXT_TIMER, origin.requestId))
            require(receipt.originHash == originHash && receipt.transmissionHash == transmissionHash &&
                receipt.resultHash == nextRequestHash(receipt.resultJson.toByteArray(Charsets.UTF_8)))
            val result = decodeFrozenSyncRequest(receipt.resultJson.toByteArray(Charsets.UTF_8), TimerCommandResult.serializer())
            require(result.status == "applied")
            NextTimerResultMapper.validate(command, result, sent.deviceId)
            require(database.timeLogDao().getTimerCommand(origin.queueId) == null)
        }
    }

    private fun validateSegments(log: TimeLogEntity, segments: List<TimerSegmentEntity>) {
        check(segments.isNotEmpty() && segments.first().startedAt == log.startTime &&
            segments.all { it.sessionUuid == log.uuid && it.sequence > 0 && it.startedAt >= log.startTime &&
                (it.endedAt == null || it.endedAt >= it.startedAt) } && segments.zipWithNext().all { (a, b) ->
                a.sequence < b.sequence && a.endedAt != null && a.endedAt <= b.startedAt } &&
            segments.count { it.endedAt == null } == if (log.endTime != null || log.isPaused) 0 else 1) { "TIMER_FAILURE_INVALID_SEGMENTS" }
    }

    private fun auditStorage(habitId: Long) {
        for ((table, where, args, text, optional) in listOf(
            Storage("timelogs", "habitId=?", arrayOf<Any>(habitId), setOf("uuid", "timerTimezone"), setOf("endTime", "pausedAt", "timerLastCommandAt", "timerElapsedRealtimeAnchor", "timerBootCount")),
            Storage("timelog_day_allocations", "habitId=?", arrayOf<Any>(habitId), setOf("sessionUuid", "localDate", "timezone"), emptySet()),
            Storage("timer_segments", "sessionUuid IN (SELECT uuid FROM timelogs WHERE habitId=?)", arrayOf<Any>(habitId), setOf("sessionUuid"), setOf("endedAt"))
        )) sql.query("SELECT * FROM $table WHERE $where", args).use { c ->
            while (c.moveToNext()) for (i in 0 until c.columnCount) {
                val name = c.getColumnName(i)
                check((name in optional && c.isNull(i)) || c.getType(i) == if (name in text) Cursor.FIELD_TYPE_STRING else Cursor.FIELD_TYPE_INTEGER) { "TIMER_STORAGE_INVALID" }
                if (name in storageInts && !c.isNull(i)) check(c.getLong(i) in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "TIMER_STORAGE_INVALID" }
                if (name == "isPaused") check(c.getLong(i) in 0..1) { "TIMER_STORAGE_INVALID" }
            }
        }
    }
    private data class Storage(val table: String, val where: String, val args: Array<Any>, val text: Set<String>, val optional: Set<String>)
    private val storageInts = setOf("durationSeconds", "timerNextCommandSequence", "timerControlGeneration", "timerBootCount", "sequence")
}
