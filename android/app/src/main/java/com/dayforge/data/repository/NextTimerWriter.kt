package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.api.dto.TimerStartPolicy
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.domain.model.TimerActionAuthority
import com.dayforge.domain.model.TimerStartGuard
import com.dayforge.domain.service.AccountSessionCoordinator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** One account/Room read snapshot; an active typed timer never borrows the edited plan's policy. */
@ConsistentCopyVisibility
data class WidgetTimerReadSnapshot internal constructor(
    val habit: HabitEntity,
    val activeLog: TimeLogEntity?,
    val policy: TimerStartPolicy?,
    val authority: TimerActionAuthority,
    val startGuard: TimerStartGuard?
)

data class WidgetTimerSwitchDisplay(val incumbentName: String?, val requestedName: String)

/** Production typed timer boundary. Only local state/segments/allocations belong in its callback. */
@Singleton
class NextTimerWriter @Inject constructor(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator
) {
    private val producer = NextCoreLocalIntentStore(database, tokens, sessions)
    internal val accessChanges = combine(tokens.factAccessChanges, tokens.iconAccessChanges) { _, _ ->
        tokens.localCoreWriteAccess()
    }.distinctUntilChanged()

    suspend fun capture(habitId: Long, expectedHabit: HabitEntity? = null): TimerActionAuthority? = sessions.exclusive {
        database.withTransaction {
            val habit = database.habitDao().getVisibleHabitById(habitId) ?: return@withTransaction null
            if (habit.appearance == null) return@withTransaction null
            // A Room display snapshot can legitimately lag behind an edit/account transition.
            // Do not terminate the list Flow; omit its action ticket until fresh data arrives.
            if (expectedHabit != null && (habit.uuid != expectedHabit.uuid ||
                NextStructureMapper.writePlan(habit) != NextStructureMapper.writePlan(expectedHabit))) return@withTransaction null
            captureInReadTransaction(habit)
        }
    }

    /** Read-only production widget consumer. Null means stale/missing display, not legacy fallback. */
    suspend fun widgetSnapshot(expectedHabit: HabitEntity): WidgetTimerReadSnapshot? = sessions.exclusive {
        database.withTransaction {
            val habit = database.habitDao().getVisibleHabitById(expectedHabit.id) ?: return@withTransaction null
            if (habit.appearance == null || habit.uuid != expectedHabit.uuid ||
                NextStructureMapper.writePlan(habit) != NextStructureMapper.writePlan(expectedHabit)) return@withTransaction null
            val authority = captureInReadTransaction(habit) ?: return@withTransaction null
            val access = requireNotNull(tokens.localCoreWriteAccess())
            check(access.session == authority.session() && access.capturedDeviceId == authority.deviceId)
            val log = database.timeLogDao().getActiveTimeLogForHabit(habit.id)
            check(log?.uuid == authority.sessionUuid && log?.timerNextCommandSequence == authority.nextSequence)
            val policy = log?.let { NextTimerPolicyStore(database).policy(access, it.uuid) }
            val startGuard = if (log == null) {
                val incumbentLog = database.timeLogDao().getActiveTimeLog()
                val incumbent = incumbentLog?.let {
                    val row = requireNotNull(database.habitDao().getVisibleHabitById(it.habitId))
                    check(row.appearance != null) { "TIMER_WIDGET_MIXED_PROTOCOL" }
                    NextTimerPolicyStore(database).policy(access, it.uuid)
                    requireNotNull(captureInReadTransaction(row))
                }
                TimerStartGuard(incumbentLog?.habitId, incumbent)
            } else null
            check(tokens.localCoreWriteAccess() == access) { "TIMER_WIDGET_STALE_ACCOUNT" }
            WidgetTimerReadSnapshot(habit, log, policy, authority, startGuard)
        }
    }

    /** Caller already holds the non-reentrant account mutex and the Room read transaction. */
    private suspend fun captureInReadTransaction(habit: HabitEntity): TimerActionAuthority? {
        val access = tokens.localCoreWriteAccess() ?: return null
        require(habit.habitType == com.dayforge.data.model.HabitType.TIMER && habit.completionPolicy == "recurring")
        val log = database.timeLogDao().getActiveTimeLogForHabit(habit.id)
        return TimerActionAuthority(access.session.authentication.userId, access.session.authentication.generation,
            access.session.serverInstanceId, access.session.syncEpoch, access.capturedDeviceId, habit.uuid, log?.uuid,
            log?.timerNextCommandSequence, if (log == null) NextStructureMapper.writePlan(habit).toString() else null)
            .also { check(tokens.localCoreWriteAccess() == access) }
    }

    suspend fun requireAction(habitId: Long, ticket: TimerActionAuthority?) = sessions.exclusive {
        database.withTransaction { validate(habitId, ticket) }
    }

    /** Validate both ends of a widget switch in one account/read transaction; never adopt a new incumbent. */
    suspend fun requireWidgetStart(habitId: Long, ticket: TimerActionAuthority, guard: TimerStartGuard) = sessions.exclusive {
        database.withTransaction {
            require(ticket.sessionUuid == null)
            ticket.validate(); guard.validate()
            validate(habitId, ticket)
            val active = database.timeLogDao().getActiveTimeLog()
            check(active?.habitId == guard.incumbentHabitId && active?.uuid == guard.incumbent?.sessionUuid &&
                active?.timerNextCommandSequence == guard.incumbent?.nextSequence) { "TIMER_SWITCH_CHANGED" }
            guard.incumbent?.let {
                check(it.session() == ticket.session() && it.deviceId == ticket.deviceId) { "TIMER_SWITCH_STALE_ACCOUNT" }
                validate(requireNotNull(guard.incumbentHabitId), it)
            }
            switchDisplayInTransaction(habitId)
        }
    }

    /** Legacy dialog reads stay repository-owned; a typed object may never fall back to this path. */
    suspend fun legacyWidgetSwitchDisplay(habitId: Long): WidgetTimerSwitchDisplay = sessions.exclusive {
        database.withTransaction { validate(habitId, null); switchDisplayInTransaction(habitId) }
    }

    private suspend fun switchDisplayInTransaction(habitId: Long): WidgetTimerSwitchDisplay {
        val requested = requireNotNull(database.habitDao().getVisibleHabitById(habitId))
        val active = database.timeLogDao().getActiveTimeLog()?.takeIf { it.habitId != habitId }
        return WidgetTimerSwitchDisplay(active?.let { database.habitDao().getVisibleHabitById(it.habitId)?.name }, requested.name)
    }

    suspend fun <T> write(habitId: Long, ticket: TimerActionAuthority?, block: suspend () -> T): T {
        if (ticket == null) return sessions.exclusive {
            database.withTransaction { validate(habitId, null); block() }
        }
        val bound = requireNotNull(ticket) { "TIMER_ACTION_TICKET_REQUIRED" }
        return producer.write(bound.session()) {
            validate(habitId, bound)
            if (bound.sessionUuid == null) check(database.timeLogDao().getActiveTimeLog() == null) {
                "TIMER_START_ACTIVE_SESSION_CHANGED"
            }
            val sql = database.openHelper.writableDatabase
            val watermark = NextRequestSql.watermark(sql, "timer_command_outbox")
            val result = block()
            val ids = sql.query("SELECT id FROM timer_command_outbox WHERE id>? ORDER BY id LIMIT 2", arrayOf(watermark))
                .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
            check(ids.size == 1) { "TIMER_SINGLE_TRANSITION_REQUIRED" }
            val command = requireNotNull(database.timeLogDao().getTimerCommand(ids.single()))
            if (bound.sessionUuid == null) {
                check(command.commandType == "start" && command.sequence == 1 && command.activityUuid == bound.habitUuid)
                check(database.timeLogDao().getActiveTimeLogForHabit(habitId)?.uuid == command.sessionUuid)
            } else {
                check(command.commandType != "start" && command.sessionUuid == bound.sessionUuid &&
                    command.sequence == bound.nextSequence) { "TIMER_TRANSITION_STALE_SESSION" }
            }
            result
        }
    }

    internal suspend fun policy(habitId: Long, sessionUuid: String): TimerStartPolicy? = sessions.exclusive {
        database.withTransaction {
            val habit = database.habitDao().getHabitById(habitId) ?: return@withTransaction null
            if (habit.appearance == null) return@withTransaction null
            val access = requireNotNull(tokens.localCoreWriteAccess())
            NextTimerPolicyStore(database).policy(access, sessionUuid)
                .also { check(tokens.localCoreWriteAccess() == access) }
        }
    }

    /** Follow-up preferences/UI belong to the same account, but occur strictly after business COMMIT. */
    suspend fun publish(habitId: Long, ticket: TimerActionAuthority?, block: suspend () -> Unit) = sessions.exclusive {
        if (ticket != null) {
            check(tokens.localCoreWriteAccess()?.session == ticket.session()) { "TIMER_FOLLOW_UP_STALE_ACCOUNT" }
            check(database.habitDao().getHabitById(habitId)?.uuid == ticket.habitUuid) { "TIMER_FOLLOW_UP_STALE_ACTIVITY" }
        }
        block()
    }

    /** A service dispatch is not a completed stop. Wait without holding the account/Room locks. */
    suspend fun afterCompletion(habitId: Long, ticket: TimerActionAuthority, block: suspend () -> Unit) {
        val uuid = requireNotNull(ticket.sessionUuid)
        val sequence = requireNotNull(ticket.nextSequence)
        val observed = withTimeoutOrNull(10_000L) {
            combine(database.timeLogDao().getTimeLogsByHabit(habitId), accessChanges) { logs, access ->
                check(access != null && access.session == ticket.session() && access.capturedDeviceId == ticket.deviceId) {
                    "TIMER_FOLLOW_UP_STALE_ACCOUNT"
                }
                logs.singleOrNull { it.uuid == uuid }
            }.first { it == null || it.endTime != null || it.timerNextCommandSequence != sequence }
        } ?: return // A failed/cancelled stop never prompts; the persisted pending card remains available.
        if (observed.endTime == null || observed.timerNextCommandSequence != sequence + 1) return
        publish(habitId, ticket) {
            val access = requireNotNull(tokens.localCoreWriteAccess())
            check(access.capturedDeviceId == ticket.deviceId) { "TIMER_FOLLOW_UP_STALE_DEVICE" }
            val completed = database.timeLogDao().getTimeLogByUuid(uuid) ?: return@publish
            if (completed.endTime == null || completed.timerNextCommandSequence != sequence + 1) return@publish
            val policy = database.withTransaction { NextTimerPolicyStore(database).policy(access, uuid) }
            check(completed.durationSeconds >= policy.targetSeconds &&
                completed.durationSeconds <= policy.maxDurationSeconds) { "TIMER_COMPLETION_REQUIRED" }
            block()
        }
    }

    private suspend fun validate(habitId: Long, ticket: TimerActionAuthority?) {
        val habit = requireNotNull(database.habitDao().getVisibleHabitById(habitId)) { "TIMER_ACTIVITY_NOT_FOUND" }
        if (habit.appearance == null) { require(ticket == null); return }
        val bound = requireNotNull(ticket) { "TIMER_ACTION_TICKET_REQUIRED" }
        val access = requireNotNull(tokens.localCoreWriteAccess())
        check(access.session == bound.session() && habit.uuid == bound.habitUuid) { "TIMER_ACTION_STALE_ACCOUNT" }
        check(access.capturedDeviceId == bound.deviceId) { "TIMER_ACTION_STALE_DEVICE" }
        check(access.capabilities == null || "timer.control" in access.capabilities) { "TIMER_CONTROL_DENIED" }
        val log = database.timeLogDao().getActiveTimeLogForHabit(habitId)
        check(log?.uuid == bound.sessionUuid && log?.timerNextCommandSequence == bound.nextSequence) { "TIMER_ACTION_STALE_SESSION" }
        if (log == null) check(NextStructureMapper.writePlan(habit).toString() == bound.originalPlan) { "TIMER_START_CONFIG_CHANGED" }
        else NextTimerPolicyStore(database).policy(access, log.uuid)
        NextPlanDeletionStore(database).requireWritable(habit.uuid)
    }
}
