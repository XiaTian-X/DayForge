package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.local.CountTrustJournal
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.*
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.domain.service.DurationDayAllocator
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Collections
import kotlinx.serialization.json.*

/** The service freezes its already-selected transition after the writer's original guards,
 * before any DAO call. Clock/boot/elapsed choices and business decisions remain in Service.
 * These are commands, never a claim that an arbitrary callback was verified.
 */
internal sealed interface TimerTransition {
    val command: TimerCommandEntity
    suspend fun apply(database: HabitDatabase): Long

    data class Start(val log: TimeLogEntity, override val command: TimerCommandEntity,
        val firstSegment: TimerSegmentEntity) : TimerTransition {
        override suspend fun apply(database: HabitDatabase) = database.timeLogDao().insertSyncedTimer(log, command, firstSegment)
    }
    data class Pause(val id: Long, val isPaused: Boolean, val pausedAt: Long?, val accumulatedPauseMillis: Long,
        val nextSequence: Int, val commandAt: Long, val activeElapsedMillis: Long,
        val elapsedRealtimeAnchor: Long?, val bootCount: Int?, override val command: TimerCommandEntity,
        val resumedSegment: TimerSegmentEntity? = null) : TimerTransition {
        override suspend fun apply(database: HabitDatabase): Long {
            database.timeLogDao().updatePauseAndQueue(id, isPaused, pausedAt, accumulatedPauseMillis, nextSequence,
                commandAt, activeElapsedMillis, elapsedRealtimeAnchor, bootCount, command, resumedSegment)
            return id
        }
    }
    data class Stop(val id: Long, val endTime: Long, val durationSeconds: Int, val accumulatedPauseMillis: Long,
        val nextSequence: Int, val activeElapsedMillis: Long, override val command: TimerCommandEntity,
        val wasPaused: Boolean) : TimerTransition {
        override suspend fun apply(database: HabitDatabase): Long {
            val dao = database.timeLogDao()
            dao.finishTimerAndQueue(id, endTime, durationSeconds, accumulatedPauseMillis, nextSequence,
                activeElapsedMillis, command, wasPaused)
            // Identical original allocation point: after the actual terminal update/segment
            // closure, still in the writer's outer atomic producer transaction.
            val completed = dao.getById(id) ?: return id
            val allocations = DurationDayAllocator.allocate(completed.uuid, completed.habitId,
                completed.timerTimezone ?: ZoneId.systemDefault().id, dao.getTimerSegments(completed.uuid),
                completed.timerActiveElapsedMillis)
            dao.replaceDayAllocations(completed.uuid, allocations)
            return id
        }
    }
    data class Cancel(val log: TimeLogEntity, override val command: TimerCommandEntity) : TimerTransition {
        override suspend fun apply(database: HabitDatabase): Long {
            database.timeLogDao().deleteTimerAndQueue(log, command)
            return log.id
        }
    }

    /** Immutable native values are produced from input scalars and certified OLD rows.
     * Auto IDs are frozen from SQLite's allocation state before the first statement, never
     * copied from an actual after-image. Exact raw events and final rows are checked later.
     */
    class Prepared private constructor(override val context: CountTrustKernel.Context,
        private val database: HabitDatabase, private val tokens: TokenManager,
        private val sessions: AccountSessionCoordinator, private val transition: TimerTransition,
        private val habit: HabitEntity, private val expectedLog: TimeLogEntity?,
        private val expectedSegments: List<TimerSegmentEntity>, private val expectedAllocations: List<TimeLogDayAllocationEntity>,
        initialEffects: List<CountTrustAck.Effect>, override val displayEdits: Map<String, JsonElement?>) : TimerTrustUpdate {
        private val pending = initialEffects.toMutableList()
        private var origin: NextRequestOriginEntity? = null
        override val effects: List<CountTrustAck.Effect> get() = Collections.unmodifiableList(pending.toList())
        override val metadata: com.dayforge.data.api.dto.ChallengeMetadata? get() = null

        suspend fun freezeOrigin(value: NextRequestOriginEntity) {
            check(origin == null && value.kind == NEXT_TIMER && value.requestId == transition.command.commandId)
            val decoded = decodeNextTimerIntent(value.intentJson)
            check(decoded.command.copy(startPolicy = null) == timerRequest(transition.command)) { "TIMER_TRUST_COMMAND_CHANGED" }
            val source = roundTimerIntent(value.intentJson)
            // A cancel edit is a real tombstone; its origin still proves the old birth.
            val encoded = displayEdits[TimerTrustEvidence.birthKey(transition.command.sessionUuid)]
                ?: requireNotNull(context.index.get(TimerTrustEvidence.birthKey(transition.command.sessionUuid))) {
                    "TIMER_TRUST_BIRTH_MISSING"
                }
            val birth = TimerTrustEvidence.decodeBirth(encoded)
            check(birth in context.known(habit.uuid)) { "TIMER_ROUND_INVALID" }
            if (context.formal) check(source != null && source.context.head == birth) {
                "TIMER_TRUST_BIRTH_CHANGED"
            } else check(source == null && birth == null)
            val row = row(context, "next_request_origins", mapOf("kind" to value.kind, "requestId" to value.requestId,
                "queueId" to value.queueId, "protocol" to value.protocol, "accountId" to value.accountId,
                "serverInstanceId" to value.serverInstanceId, "syncEpoch" to value.syncEpoch,
                "sourceHash" to value.sourceHash, "intentJson" to value.intentJson))
            check(context.index.get(row.name) == null)
            pending += CountTrustAck.Effect(row.table.name, null, row)
            origin = value
        }

        override suspend fun verifyFinal(database: HabitDatabase) {
            check(database === this.database && database.inTransaction() && origin != null)
            val dao = database.timeLogDao()
            val uuid = transition.command.sessionUuid
            check(dao.getTimeLogByUuid(uuid) == expectedLog && dao.getTimerSegments(uuid) == expectedSegments &&
                dao.getDayAllocations(uuid) == expectedAllocations) { "TIMER_TRUST_TRANSITION_CHANGED" }
            check(database.nextRequestDao().origin(NEXT_TIMER, transition.command.commandId) == origin)
            if (expectedLog != null) {
                val proof = TimerHistoryReader(database, tokens, sessions).trustSessionInTransaction(habit, uuid)
                check(proof.sessions.single().let { it.log == expectedLog && it.segments == expectedSegments &&
                    it.allocations == expectedAllocations }) { "TIMER_TRUST_SESSION_CHANGED" }
            }
        }

        companion object {
            suspend fun prepare(context: CountTrustKernel.Context, database: HabitDatabase, tokens: TokenManager,
                sessions: AccountSessionCoordinator, transition: TimerTransition,
                diagnostic: TimerTrustMaintenanceDiagnostic.Attempt?): Prepared? = context.index.withReadScope {
                fun miss(reason: TimerTrustMaintenanceDiagnostic.Reason): Prepared? { diagnostic?.mark(reason); return null }
                val command = transition.command
                if (!context.timers) return@withReadScope miss(TimerTrustMaintenanceDiagnostic.Reason.NO_TIMER_COVERAGE)
                if (command.id != 0L || command.commandType !in setOf("start", "pause", "resume", "stop", "cancel"))
                    return@withReadScope miss(TimerTrustMaintenanceDiagnostic.Reason.COMMAND_INPUT)
                val dao = database.timeLogDao()
                val old = dao.getTimeLogByUuid(command.sessionUuid)
                val habitId = when (transition) { is Start -> transition.log.habitId; else -> old?.habitId }
                    ?: return@withReadScope miss(TimerTrustMaintenanceDiagnostic.Reason.SESSION_MISSING)
                val habit = database.habitDao().getHabitById(habitId)
                    ?: return@withReadScope miss(TimerTrustMaintenanceDiagnostic.Reason.HABIT_MISSING)
                if (!context.timerEligible(habit)) return@withReadScope miss(TimerTrustMaintenanceDiagnostic.Reason.NOT_ELIGIBLE)
                context.checked(CountTrustMembership.key("habit.uuid", habit.uuid), "habits", "uuid=?", arrayOf(habit.uuid)).single()
                val oldLog = context.checked(CountTrustMembership.key("timer.uuid", command.sessionUuid),
                    "timelogs", "uuid=?", arrayOf(command.sessionUuid)).singleOrNull()
                check(oldLog?.let(TimerTrustEvidence::log) == old) { "TIMER_TRUST_SESSION_CHANGED" }
                val oldSegments = context.checked(CountTrustMembership.key("timer.segments", command.sessionUuid),
                    "timer_segments", "sessionUuid=?", arrayOf(command.sessionUuid))
                val oldAllocations = context.checked(CountTrustMembership.key("timer.allocations", command.sessionUuid),
                    "timelog_day_allocations", "sessionUuid=?", arrayOf(command.sessionUuid))
                if (old != null) {
                    val audited = TimerHistoryReader(database, tokens, sessions).trustSessionInTransaction(habit, old.uuid).sessions.single()
                    check(audited.log == old && audited.segments == oldSegments.map(TimerTrustEvidence::segment).sortedBy { it.sequence })
                }
                if (old != null) {
                    if (command.sequence != old.timerNextCommandSequence)
                        return@withReadScope miss(TimerTrustMaintenanceDiagnostic.Reason.NEXT_SEQUENCE)
                    if (command.expectedControlGeneration != old.timerControlGeneration)
                        return@withReadScope miss(TimerTrustMaintenanceDiagnostic.Reason.CONTROL_GENERATION)
                    if (command.occurredAt <= (old.timerLastCommandAt ?: old.startTime))
                        return@withReadScope miss(TimerTrustMaintenanceDiagnostic.Reason.COMMAND_TIME)
                }
                val expectedType = when (transition) {
                    is Start -> "start"; is Pause -> if (transition.isPaused) "pause" else "resume"
                    is Stop -> "stop"; is Cancel -> "cancel"
                }
                if (command.commandType != expectedType) return@withReadScope miss(TimerTrustMaintenanceDiagnostic.Reason.COMMAND_TYPE)
                val effects = arrayListOf<CountTrustAck.Effect>()
                val edits = linkedMapOf<String, JsonElement?>()
                var next: TimeLogEntity?
                var segments = oldSegments.map(TimerTrustEvidence::segment).sortedBy { it.sequence }
                var allocations = emptyList<TimeLogDayAllocationEntity>()
                fun add(before: CountTrustRows.Row?, after: CountTrustRows.Row?) {
                    effects += CountTrustAck.Effect((before ?: requireNotNull(after)).table.name, before, after)
                }
                fun segment(value: TimerSegmentEntity) = row(context, "timer_segments", mapOf("id" to value.id,
                    "sessionUuid" to value.sessionUuid, "sequence" to value.sequence, "startedAt" to value.startedAt, "endedAt" to value.endedAt))
                when (transition) {
                    is Start -> {
                        if (old != null || transition.log.id != 0L || transition.firstSegment.id != 0L || oldSegments.isNotEmpty() || oldAllocations.isNotEmpty())
                            return@withReadScope miss(TimerTrustMaintenanceDiagnostic.Reason.START_INPUT)
                        check(context.checked(CountTrustMembership.key("timer.active.global"), "timelogs",
                            "endTime IS NULL", emptyArray()).isEmpty())
                        next = transition.log.copy(id = nextId(database, "timelogs"))
                        segments = listOf(transition.firstSegment.copy(id = nextId(database, "timer_segments")))
                        add(null, log(context, next)); add(null, segment(segments.single()))
                        edits[TimerTrustEvidence.birthKey(command.sessionUuid)] = TimerTrustEvidence.encodeBirth(context.head(habit.uuid))
                    }
                    is Pause -> {
                        val before = requireNotNull(old)
                        check(before.id == transition.id && before.endTime == null)
                        next = before.copy(isPaused = transition.isPaused, pausedAt = transition.pausedAt,
                            accumulatedPauseMillis = transition.accumulatedPauseMillis, timerNextCommandSequence = transition.nextSequence,
                            timerLastCommandAt = transition.commandAt, timerActiveElapsedMillis = transition.activeElapsedMillis,
                            timerElapsedRealtimeAnchor = transition.elapsedRealtimeAnchor, timerBootCount = transition.bootCount,
                            updatedAt = transition.commandAt)
                        add(oldLog, log(context, next))
                        if (transition.isPaused) {
                            segments = segments.map { value -> if (value.endedAt != null) value else value.copy(endedAt = transition.commandAt).also {
                                add(oldSegments.single { r -> r.long("id") == value.id }, segment(it))
                            } }
                        } else {
                            val supplied = requireNotNull(transition.resumedSegment)
                            if (supplied.id != 0L) return@withReadScope miss(TimerTrustMaintenanceDiagnostic.Reason.RESUME_INPUT)
                            val inserted = supplied.copy(id = nextId(database, "timer_segments"))
                            segments = segments + inserted; add(null, segment(inserted))
                        }
                    }
                    is Stop -> {
                        val before = requireNotNull(old)
                        check(before.id == transition.id && before.endTime == null && before.isPaused == transition.wasPaused)
                        next = before.copy(endTime = transition.endTime, durationSeconds = transition.durationSeconds,
                            isPaused = false, pausedAt = null, accumulatedPauseMillis = transition.accumulatedPauseMillis,
                            timerNextCommandSequence = transition.nextSequence, timerLastCommandAt = transition.endTime,
                            timerActiveElapsedMillis = transition.activeElapsedMillis, timerElapsedRealtimeAnchor = null, updatedAt = transition.endTime)
                        add(oldLog, log(context, next))
                        if (!transition.wasPaused) segments = segments.map { value -> if (value.endedAt != null) value else value.copy(endedAt = transition.endTime).also {
                            add(oldSegments.single { r -> r.long("id") == value.id }, segment(it))
                        } }
                        allocations = DurationDayAllocator.allocate(before.uuid, habit.id, requireNotNull(before.timerTimezone), segments,
                            transition.activeElapsedMillis)
                        oldAllocations.forEach { add(it, null) }
                        allocations.forEach { allocation -> add(null, row(context, "timelog_day_allocations", mapOf(
                            "sessionUuid" to allocation.sessionUuid, "habitId" to allocation.habitId, "localDate" to allocation.localDate,
                            "localDateEpoch" to allocation.localDateEpoch, "timezone" to allocation.timezone, "durationMillis" to allocation.durationMillis))) }
                        val encoded = requireNotNull(context.index.get(TimerTrustEvidence.birthKey(before.uuid))) {
                            "TIMER_TRUST_BIRTH_MISSING"
                        }
                        val head = TimerTrustEvidence.decodeBirth(encoded)
                        check(head in context.known(habit.uuid)) { "TIMER_ROUND_INVALID" }
                        val displayScope = TimerDisplayBlocks.Scope(habit.uuid, head)
                        val changes = allocations.groupBy { YearMonth.from(LocalDate.parse(it.localDate)) }.map { (month, added) ->
                            val previous = context.index.get(displayScope.monthKey(month))?.jsonObject?.let {
                                TimerDisplayBlocks.milliseconds(it, displayScope, month)
                            }.orEmpty().toMutableMap()
                            added.forEach { allocation -> val day = LocalDate.parse(allocation.localDate)
                                previous[day] = Math.addExact(previous[day] ?: 0L, allocation.durationMillis) }
                            TimerDisplayBlocks.Change(habit, head, month, TimerDisplayBlocks.monthBody(displayScope, month, previous))
                        }
                        edits.putAll(TimerDisplayBlocks.advance(changes, context.index::get))
                    }
                    is Cancel -> {
                        check(old == transition.log && old?.endTime == null)
                        next = null; segments = emptyList()
                        add(requireNotNull(oldLog), null); oldSegments.forEach { add(it, null) }; oldAllocations.forEach { add(it, null) }
                        edits[TimerTrustEvidence.birthKey(command.sessionUuid)] = null
                    }
                }
                val queue = row(context, "timer_command_outbox", mapOf("id" to nextId(database, "timer_command_outbox"),
                    "commandId" to command.commandId, "sessionUuid" to command.sessionUuid, "sequence" to command.sequence,
                    "commandType" to command.commandType, "occurredAt" to command.occurredAt,
                    "expectedControlGeneration" to command.expectedControlGeneration, "expectedRevision" to command.expectedRevision,
                    "activityUuid" to command.activityUuid, "timezone" to command.timezone, "activeElapsedMillis" to command.activeElapsedMillis,
                    "attemptCount" to command.attemptCount, "lastError" to command.lastError, "errorCode" to command.errorCode,
                    "deadLetteredAt" to command.deadLetteredAt, "createdAt" to command.createdAt))
                add(null, queue)
                Prepared(context, database, tokens, sessions, transition, habit, next, segments, allocations, effects, Collections.unmodifiableMap(edits))
            }

            private fun nextId(database: HabitDatabase, table: String): Long {
                require(table in setOf("timelogs", "timer_segments", "timer_command_outbox"))
                val sql = database.openHelper.writableDatabase
                // The journal owns temp.sqlite_sequence; persistent IDs must use main,
                // especially after ACK deletes the greatest retained queue row.
                val sequence = sql.query("SELECT seq FROM main.sqlite_sequence WHERE name=?", arrayOf(table)).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                val greatest = sql.query("SELECT COALESCE(MAX(id),0) FROM main.`$table`").use { check(it.moveToFirst()); it.getLong(0) }
                return Math.addExact(maxOf(sequence, greatest), 1L)
            }
            private fun row(context: CountTrustKernel.Context, table: String, values: Map<String, Any?>): CountTrustRows.Row {
                val layout = context.rows.table(table)
                check(layout.columns.toSet() == values.keys)
                return CountTrustRows.Row(layout, layout.columns.map { name -> when (val value = values.getValue(name)) {
                    null -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_NULL, null)
                    is String -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_STRING, value)
                    is Boolean -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_INTEGER, if (value) 1L else 0L)
                    is Int -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_INTEGER, value.toLong())
                    is Long -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_INTEGER, value)
                    else -> error("TIMER_TRUST_INPUT_TYPE")
                } })
            }
            private fun log(context: CountTrustKernel.Context, value: TimeLogEntity) = row(context, "timelogs", mapOf(
                "id" to value.id, "habitId" to value.habitId, "startTime" to value.startTime, "endTime" to value.endTime,
                "durationSeconds" to value.durationSeconds, "isPaused" to value.isPaused, "pausedAt" to value.pausedAt,
                "accumulatedPauseMillis" to value.accumulatedPauseMillis, "timerNextCommandSequence" to value.timerNextCommandSequence,
                "timerControlGeneration" to value.timerControlGeneration, "timerLastCommandAt" to value.timerLastCommandAt,
                "timerTimezone" to value.timerTimezone, "timerActiveElapsedMillis" to value.timerActiveElapsedMillis,
                "timerElapsedRealtimeAnchor" to value.timerElapsedRealtimeAnchor, "timerBootCount" to value.timerBootCount,
                "date" to value.date, "uuid" to value.uuid, "createdAt" to value.createdAt, "updatedAt" to value.updatedAt))
        }
    }
}
