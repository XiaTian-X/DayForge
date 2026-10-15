package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.api.dto.TimerStartPolicy
import com.dayforge.data.api.dto.ChallengeMetadata
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.domain.model.TimerActionAuthority
import com.dayforge.domain.model.TimerStartGuard
import com.dayforge.domain.model.ChallengeRoundHead
import kotlinx.serialization.json.Json
import com.dayforge.domain.service.AccountSessionCoordinator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** One account/Room read snapshot; an active typed timer never borrows the edited plan's policy. */
@ConsistentCopyVisibility
data class WidgetTimerReadSnapshot internal constructor(
    val habit: HabitEntity,
    val activeLog: TimeLogEntity?,
    val policy: TimerStartPolicy?,
    val authority: TimerActionAuthority,
    val startGuard: TimerStartGuard?,
    val history: com.dayforge.domain.model.TimerHistory,
    val activeLogHash: String?
)

data class WidgetTimerSwitchDisplay(val incumbentName: String?, val requestedName: String)

internal data class LegacyWidgetTimerReadSnapshot(val habit: HabitEntity, val activeLog: TimeLogEntity?)

/** Timer transaction boundary. Typed producers and original-scope legacy widget controls stay separate. */
@Singleton
class NextTimerWriter @Inject constructor(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val timerHistoryReader: TimerHistoryReader = TimerHistoryReader(database, tokens, sessions)
) {
    private val producer = NextCoreLocalIntentStore(database, tokens, sessions)
    private val replica = ReplicaPresentationRepository(database, tokens, sessions)
    private val legacyWidgets = LegacyWidgetIdentityVerifier(database, tokens, replica)
    internal val accessChanges = tokens.coreAccessChanges

    suspend fun capture(habitId: Long, expectedHabit: HabitEntity? = null): TimerActionAuthority? = sessions.exclusive {
        if (!replica.isOpenWhileAccountLocked()) return@exclusive null
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

    /** Capture from the widget's original publication scope, never from whichever account is now active. */
    internal suspend fun captureLegacyWidget(identity: LegacyWidgetRestartClaim,
        expectedActiveLog: TimeLogEntity?): LegacyWidgetTimerClaim = sessions.exclusive {
        database.withTransaction {
            val habit = legacyWidgets.requireIdentity(identity, requirePlan = true)
            require(habit.habitType == com.dayforge.data.model.HabitType.TIMER)
            val active = database.timeLogDao().getActiveTimeLogForHabit(habit.id)
            check(active == expectedActiveLog) { "TIMER_WIDGET_STALE_DISPLAY" }
            val incumbent = if (active == null) database.timeLogDao().getActiveTimeLog()?.let { log ->
                val row = requireNotNull(database.habitDao().getVisibleHabitById(log.habitId))
                val captured = LegacyWidgetRestartClaim.displayed(row, requireNotNull(tokens.localCoreWriteAccess()))
                legacyWidgets.requireIdentity(captured, requirePlan = false)
                require(captured.session() == identity.session() && captured.deviceId == identity.deviceId)
                LegacyWidgetTimerIncumbent(captured, log.uuid, log.timerNextCommandSequence)
            } else null
            LegacyWidgetTimerClaim(identity, active?.uuid, active?.timerNextCommandSequence, incumbent).also {
                validateLegacyWidget(it, control = false)
            }
        }
    }

    /** Ordinary App confirmation captures its scope ONCE before showing a dialog; no ID-only dialog fallback. */
    internal suspend fun captureLegacyConfirmation(expectedHabit: HabitEntity,
        expectedActiveLog: TimeLogEntity): LegacyWidgetTimerClaim = sessions.exclusive {
        database.withTransaction {
            require(expectedActiveLog.habitId == expectedHabit.id && expectedActiveLog.endTime == null)
            val identity = LegacyWidgetRestartClaim.displayed(expectedHabit, requireNotNull(tokens.localCoreWriteAccess()))
            legacyWidgets.requireIdentity(identity, requirePlan = true, permission = "timer.control")
            check(database.timeLogDao().getActiveTimeLogForHabit(expectedHabit.id) == expectedActiveLog) { "TIMER_WIDGET_STALE_DISPLAY" }
            LegacyWidgetTimerClaim(identity, expectedActiveLog.uuid, expectedActiveLog.timerNextCommandSequence).also {
                validateLegacyWidget(it)
            }
        }
    }

    internal suspend fun requireLegacyWidgetAction(claim: LegacyWidgetTimerClaim): LegacyWidgetTimerReadSnapshot = sessions.exclusive {
        database.withTransaction { validateLegacyWidget(claim) }
    }

    internal suspend fun requireLegacyWidgetStart(claim: LegacyWidgetTimerClaim): WidgetTimerSwitchDisplay = sessions.exclusive {
        database.withTransaction {
            require(claim.sessionUuid == null)
            validateLegacyWidget(claim)
            switchDisplayInTransaction(claim.identity.habitId)
        }
    }

    private suspend fun validateLegacyWidget(claim: LegacyWidgetTimerClaim, control: Boolean = true): LegacyWidgetTimerReadSnapshot {
        claim.validate()
        val habit = legacyWidgets.requireIdentity(claim.identity, requirePlan = claim.sessionUuid == null,
            permission = if (control) "timer.control" else null)
        require(habit.habitType == com.dayforge.data.model.HabitType.TIMER) { "TIMER_ACTIVITY_CHANGED" }
        val active = database.timeLogDao().getActiveTimeLogForHabit(habit.id)
        check(active?.uuid == claim.sessionUuid && active?.timerNextCommandSequence == claim.nextSequence) {
            "TIMER_ACTION_STALE_SESSION"
        }
        if (active == null) {
            val incumbent = database.timeLogDao().getActiveTimeLog()
            check(incumbent?.habitId == claim.incumbent?.identity?.habitId && incumbent?.uuid == claim.incumbent?.sessionUuid &&
                incumbent?.timerNextCommandSequence == claim.incumbent?.nextSequence) { "TIMER_SWITCH_CHANGED" }
            claim.incumbent?.let { legacyWidgets.requireIdentity(it.identity, requirePlan = false,
                permission = if (control) "timer.control" else null) }
        }
        return LegacyWidgetTimerReadSnapshot(habit, active)
    }

    /** Read-only production widget consumer. Null means stale/missing display, not legacy fallback. */
    suspend fun widgetSnapshot(expectedHabit: HabitEntity): WidgetTimerReadSnapshot? = readSnapshot(expectedHabit, true)

    /**
     * Publish a typed widget's display while still owning its account session. The Room read
     * transaction ends before renderer I/O; account cleanup cannot interleave with publication.
     * The callback must not re-enter this non-reentrant coordinator or perform business writes.
     */
    suspend fun renderWidgetSnapshot(expectedHabit: HabitEntity,
        beforeRead: suspend () -> Boolean = { true },
        render: suspend (WidgetTimerReadSnapshot) -> Unit): Boolean = sessions.exclusive {
        // An inactive habit has no original timer owner to reject a queued old lookup.
        // The caller's pre-source display scope must be checked under THIS strong lock.
        // Predicate must not reenter repositories/this lock or perform business writes.
        if (!replica.isOpenWhileAccountLocked() || !beforeRead()) return@exclusive false
        val snapshot = database.withTransaction { readSnapshotInTransaction(expectedHabit, true) }
            ?: return@exclusive false
        render(snapshot)
        true
    }

    /** List statistics and their action ticket must share the same displayed head and account. */
    suspend fun statusSnapshot(expectedHabit: HabitEntity): WidgetTimerReadSnapshot? = sessions.exclusive(AccountSessionCoordinator.Work.TIMER_DISPLAY_READ) {
        if (!replica.isOpenWhileAccountLocked()) return@exclusive null
        // Preserve the stale/missing-row null path before inspecting its challenge metadata.
        val current = database.habitDao().getVisibleHabitById(expectedHabit.id) ?: return@exclusive null
        if (current.appearance == null || current.uuid != expectedHabit.uuid ||
            NextStructureMapper.writePlan(current) != NextStructureMapper.writePlan(expectedHabit)) return@exclusive null
        RecurringDisplayReadScope.withSnapshotWhileAccountLocked(database, tokens, sessions,
            onMissingAccess = { null }) { scope ->
            // Recheck the row inside the owned read transaction; the first read grants nothing.
            readSnapshotInTransaction(expectedHabit, false, scope)
        }
    }

    /** Display-only invalidation hints, not write authorization or a replacement action claim. */
    suspend fun renderWidgetTick(habitId: Long, displayedPlan: String, displayedLogHash: String,
        displayedAuthority: TimerActionAuthority,
        render: suspend (TimeLogEntity, TimerStartPolicy) -> Unit): Boolean = sessions.exclusive {
        if (!replica.isOpenWhileAccountLocked()) return@exclusive false
        val current = database.withTransaction {
            val habit = database.habitDao().getVisibleHabitById(habitId) ?: return@withTransaction null
            if (habit.appearance == null || NextStructureMapper.writePlan(habit).toString() != displayedPlan)
                return@withTransaction null
            if (captureInReadTransaction(habit) != displayedAuthority) return@withTransaction null
            val log = database.timeLogDao().getActiveTimeLogForHabit(habitId) ?: return@withTransaction null
            if (log.uuid != displayedAuthority.sessionUuid || log.timerNextCommandSequence != displayedAuthority.nextSequence ||
                widgetTimerRowFingerprint(database, log.id) != displayedLogHash)
                return@withTransaction null
            val access = requireNotNull(tokens.localCoreWriteAccess())
            check(access.session == displayedAuthority.session() && access.capturedDeviceId == displayedAuthority.deviceId)
            val policy = NextTimerPolicyStore(database).policy(access, log.uuid)
            check(tokens.localCoreWriteAccess() == access) { "TIMER_WIDGET_STALE_ACCOUNT" }
            log to policy
        } ?: return@exclusive false
        // Keep the account lock, but end Room before display IO. No retained-history reread here.
        render(current.first, current.second)
        true
    }

    private suspend fun readSnapshot(expectedHabit: HabitEntity, includeStartGuard: Boolean): WidgetTimerReadSnapshot? = sessions.exclusive {
        if (!replica.isOpenWhileAccountLocked()) return@exclusive null
        database.withTransaction { readSnapshotInTransaction(expectedHabit, includeStartGuard) }
    }

    private suspend fun readSnapshotInTransaction(expectedHabit: HabitEntity, includeStartGuard: Boolean,
        displayScope: RecurringDisplayReadScope? = null): WidgetTimerReadSnapshot? {
        check(database.inTransaction()) { "TIMER_WIDGET_TRANSACTION_REQUIRED" }
        val habit = database.habitDao().getVisibleHabitById(expectedHabit.id) ?: return null
        if (habit.appearance == null || habit.uuid != expectedHabit.uuid ||
            NextStructureMapper.writePlan(habit) != NextStructureMapper.writePlan(expectedHabit)) return null
        val authority = captureInReadTransaction(habit, displayScope) ?: return null
        val access = displayScope?.borrow(database, tokens)?.first ?: requireNotNull(tokens.localCoreWriteAccess())
        check(access.session == authority.session() && access.capturedDeviceId == authority.deviceId)
        val log = database.timeLogDao().getActiveTimeLogForHabit(habit.id)
        check(log?.uuid == authority.sessionUuid && log?.timerNextCommandSequence == authority.nextSequence)
        val policy = log?.let { NextTimerPolicyStore(database).policy(access, it.uuid) }
        val startGuard = if (log == null && includeStartGuard) {
            val incumbentLog = database.timeLogDao().getActiveTimeLog()
            val incumbent = incumbentLog?.let {
                val row = requireNotNull(database.habitDao().getVisibleHabitById(it.habitId))
                check(row.appearance != null) { "TIMER_WIDGET_MIXED_PROTOCOL" }
                NextTimerPolicyStore(database).policy(access, it.uuid)
                requireNotNull(captureInReadTransaction(row, displayScope))
            }
            TimerStartGuard(incumbentLog?.habitId, incumbent)
        } else null
        if (displayScope != null) displayScope.borrow(database, tokens)
        else check(tokens.localCoreWriteAccess() == access) { "TIMER_WIDGET_STALE_ACCOUNT" }
        val history = if (displayScope == null)
            timerHistoryReader.readInTransaction(habit, com.dayforge.util.DateTimeUtils.today())
        else timerHistoryReader.readInTransaction(habit, com.dayforge.util.DateTimeUtils.today(), displayScope)
        val logHash = log?.let {
            requireNotNull(widgetTimerRowFingerprint(database, it.id))
        }
        return WidgetTimerReadSnapshot(habit, log, policy, authority, startGuard, history, logHash)
    }

    /** Caller already holds the non-reentrant account mutex and the Room read transaction. */
    private suspend fun captureInReadTransaction(habit: HabitEntity,
        displayScope: RecurringDisplayReadScope? = null): TimerActionAuthority? {
        val borrowed = displayScope?.borrow(database, tokens)
        val access = borrowed?.first ?: tokens.localCoreWriteAccess() ?: return null
        require(habit.habitType == com.dayforge.data.model.HabitType.TIMER && habit.completionPolicy == "recurring")
        val log = database.timeLogDao().getActiveTimeLogForHabit(habit.id)
        val rounds = if (borrowed != null) borrowed.second else producer.captureDisplayedRoundsInTransaction()
        val head = rounds?.let { displayedHead(habit, log, it, displayScope) }
        return TimerActionAuthority(access.session.authentication.userId, access.session.authentication.generation,
            access.session.serverInstanceId, access.session.syncEpoch, access.capturedDeviceId, habit.uuid, log?.uuid,
            log?.timerNextCommandSequence, if (log == null) NextStructureMapper.writePlan(habit).toString() else null,
            if (rounds == null) 0 else 1, head)
            .also {
                if (displayScope != null) displayScope.borrow(database, tokens)
                else check(tokens.localCoreWriteAccess() == access)
            }
    }

    /** Active controls retain the original local start, not a newer current challenge head. */
    private suspend fun displayedHead(habit: HabitEntity, log: TimeLogEntity?, scope: NextRoundWriteScope,
        displayScope: RecurringDisplayReadScope? = null): ChallengeRoundHead {
        val metadata = scope.completionRounds
        if (log == null) return scope.pendingRestarts[habit.uuid]?.head ?:
            metadata.head(habit.uuid)
            ?: scope.pendingInitials[habit.uuid]?.head ?: error("SYNC_CHALLENGE_HEAD_REQUIRED")
        val access = displayScope?.borrow(database, tokens)?.first ?: requireNotNull(tokens.localCoreWriteAccess())
        NextTimerPolicyStore(database).policy(access, log.uuid)
        val (origin, start) = NextTimerPolicyStore(database).starts(sessionUuid = log.uuid).single()
        val source = requireNotNull(roundTimerIntent(origin.intentJson)) { "SYNC_CHALLENGE_TIMER_START_REQUIRED" }
        require(source.timer == start && source.capturedDeviceId == scope.access.deviceId &&
            origin.accountId == scope.access.session.authentication.userId && origin.serverInstanceId == scope.access.session.serverInstanceId &&
            origin.syncEpoch == scope.access.session.syncEpoch)
        source.restartFrontier?.let { NextRestartBindingStore(database).captured(scope.access, it, start.planQueueWatermark) }
        return requireNotNull(source.context.head).also { head ->
            require(head.activityUuid == habit.uuid && (metadata.contains(habit.uuid, head) ||
                scope.pendingInitials[habit.uuid]?.head == head || source.restartFrontier?.head == head))
            metadata.timerBirth(log.uuid)?.let { require(it == head) }
        }
    }

    suspend fun requireAction(habitId: Long, ticket: TimerActionAuthority?, legacyWidget: LegacyWidgetTimerClaim? = null) = sessions.exclusive {
        database.withTransaction {
            if (legacyWidget == null) validate(habitId, ticket)
            else { require(ticket == null && legacyWidget.identity.habitId == habitId); validateLegacyWidget(legacyWidget); Unit }
        }
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

    private suspend fun switchDisplayInTransaction(habitId: Long): WidgetTimerSwitchDisplay {
        val requested = requireNotNull(database.habitDao().getVisibleHabitById(habitId))
        val active = database.timeLogDao().getActiveTimeLog()?.takeIf { it.habitId != habitId }
        return WidgetTimerSwitchDisplay(active?.let { database.habitDao().getVisibleHabitById(it.habitId)?.name }, requested.name)
    }

    suspend fun <T> write(habitId: Long, ticket: TimerActionAuthority?, legacyWidget: LegacyWidgetTimerClaim? = null,
        legacySwitchTarget: LegacyWidgetTimerClaim? = null,
        block: suspend () -> T): T {
        require(legacySwitchTarget == null || legacyWidget != null)
        if (legacyWidget != null) return sessions.exclusive { database.withTransaction {
            require(ticket == null && legacyWidget.identity.habitId == habitId)
            legacySwitchTarget?.let {
                require(it.sessionUuid == null && it.incumbentClaim() == legacyWidget)
                validateLegacyWidget(it)
            }
            validateLegacyWidget(legacyWidget)
            if (legacyWidget.sessionUuid == null) check(database.timeLogDao().getActiveTimeLog() == null) { "TIMER_SWITCH_CHANGED" }
            val sql = database.openHelper.writableDatabase
            val watermark = NextRequestSql.watermark(sql, "timer_command_outbox")
            val result = block()
            val ids = sql.query("SELECT id FROM timer_command_outbox WHERE id>? ORDER BY id LIMIT 2", arrayOf(watermark))
                .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
            check(ids.size == 1) { "TIMER_SINGLE_TRANSITION_REQUIRED" }
            val command = requireNotNull(database.timeLogDao().getTimerCommand(ids.single()))
            if (legacyWidget.sessionUuid == null) {
                check(command.commandType == "start" && command.sequence == 1 && command.activityUuid == legacyWidget.identity.habitUuid &&
                    database.timeLogDao().getActiveTimeLogForHabit(habitId)?.uuid == command.sessionUuid) { "TIMER_TRANSITION_STALE_SESSION" }
            } else check(command.commandType != "start" && command.sessionUuid == legacyWidget.sessionUuid &&
                command.sequence == legacyWidget.nextSequence) { "TIMER_TRANSITION_STALE_SESSION" }
            legacyWidgets.requireIdentity(legacyWidget.identity, requirePlan = false, permission = "timer.control")
            result
        } }
        if (ticket == null) return sessions.exclusive {
            database.withTransaction { validate(habitId, null); block() }
        }
        val bound = requireNotNull(ticket) { "TIMER_ACTION_TICKET_REQUIRED" }
        val rounds = sessions.exclusive { database.withTransaction {
            validate(habitId, bound)
            producer.captureDisplayedRoundsInTransaction()
        } }
        val commit: suspend () -> T = {
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
        return rounds?.let { producer.writeRounds(it, commit) } ?: producer.write(bound.session(), commit)
    }

    internal suspend fun policy(habitId: Long, sessionUuid: String): TimerStartPolicy? = sessions.exclusive {
        if (!replica.isOpenWhileAccountLocked()) return@exclusive null
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
        requirePublication(habitId, ticket)
        block()
    }

    /** Completion prompt publication/preferences retain the round; pending cards retain their start display. */
    internal suspend fun publishPrompt(habitId: Long, ticket: TimerActionAuthority, block: suspend () -> Unit) = sessions.exclusive {
        database.withTransaction {
            if (ticket.sessionUuid != null) completionInTransaction(habitId, ticket)
            else validate(habitId, ticket)
        }
        block()
    }

    /** Compute history in the already-owned account/Room snapshot, never reacquire the mutex.
     * The follow-up callback still owns the account lock; it must not call lock-owning readers.
     */
    suspend fun publishCompletion(habitId: Long, ticket: TimerActionAuthority?, legacyWidget: LegacyWidgetTimerClaim? = null,
        block: suspend (HabitEntity?, Int?) -> Unit) = sessions.exclusive {
        val (habit, progress) = database.withTransaction {
            if (ticket != null) {
                require(legacyWidget == null)
                val completed = completionInTransaction(habitId, ticket)
                return@withTransaction completed.habit to completed.progress
            }
            val habit = if (legacyWidget == null) requirePublication(habitId, ticket) else {
                require(habitId == legacyWidget.identity.habitId)
                legacyWidgets.requireIdentity(legacyWidget.identity, requirePlan = false)
            }
            val progress = if (habit?.targetCycles == null) null
                else if (habit.appearance != null) timerHistoryReader.readInTransaction(habit,
                    com.dayforge.util.DateTimeUtils.today()).qualifiedDates.size
                else database.timeLogDao().getTargetMetDayCount(habitId, habit.targetValue * 60)
            habit to progress
        }
        block(habit, progress)
    }

    /** Pure read participant: caller owns the account mutex AND the actual Room transaction. */
    internal suspend fun completionInTransaction(habitId: Long, ticket: TimerActionAuthority,
        expectedPlan: kotlinx.serialization.json.JsonObject? = null): TimerCompletionSnapshot {
        val access = requireCompletionAccessInTransaction(ticket)
        ticket.validate(); require(ticket.sessionUuid != null)
        val habit = requireNotNull(database.habitDao().getVisibleHabitById(habitId)) { "TIMER_COMPLETION_NOT_FOUND" }
        check(habit.uuid == ticket.habitUuid && habit.appearance != null &&
            habit.habitType == com.dayforge.data.model.HabitType.TIMER && habit.completionPolicy == "recurring") {
            "TIMER_COMPLETION_ACTIVITY_CHANGED"
        }
        check(expectedPlan == null || NextStructureMapper.writePlan(habit) == expectedPlan) { "TIMER_GOAL_PLAN_CHANGED" }
        val history = timerHistoryReader.completedInTransaction(habit, ticket)
        check(requireCompletionAccessInTransaction(ticket) == access) { "TIMER_FOLLOW_UP_STALE_ACCOUNT" }
        return TimerCompletionSnapshot(habit, history.qualifiedDates.size, TimerCompletionFollowUpClaim.displayed(habit, ticket))
    }

    /** No history scan: final credential/permission fence after the caller's writes in this transaction. */
    internal suspend fun requireCompletionAccessInTransaction(ticket: TimerActionAuthority,
        permission: String? = null): com.dayforge.data.local.LocalCoreWriteAccess {
        check(database.inTransaction())
        check(replica.isOpenWhileAccountLocked()) { com.dayforge.data.local.ReplicaSwitchGuard.PENDING }
        val access = requireNotNull(tokens.localCoreWriteAccess()) { "TIMER_FOLLOW_UP_STALE_ACCOUNT" }
        check(access.session == ticket.session() && access.capturedDeviceId == ticket.deviceId) { "TIMER_FOLLOW_UP_STALE_ACCOUNT" }
        check(permission == null || access.capabilities == null || permission in access.capabilities) { "TIMER_FOLLOW_UP_DENIED" }
        return access
    }

    internal suspend fun goalDisplay(claim: TimerCompletionFollowUpClaim): TimerCompletionSnapshot = sessions.exclusive {
        database.withTransaction {
            claim.validate()
            completionInTransaction(claim.habitId, claim.source, claim.displayedPlan).also {
                check(it.goalReached) { "TIMER_GOAL_CHANGED" }
            }
        }
    }

    internal suspend fun authorizeGoal(claim: TimerCompletionFollowUpClaim): ObjectEditSnapshot<HabitEntity> = sessions.exclusive {
        database.withTransaction {
            val view = completionInTransaction(claim.habitId, claim.source, claim.displayedPlan)
            check(view.goalReached) { "TIMER_GOAL_CHANGED" }
            val access = requireCompletionAccessInTransaction(claim.source, "structure.write")
            val rounds = producer.captureDisplayedRoundsInTransaction()
            check(requireCompletionAccessInTransaction(claim.source, "structure.write") == access) { "TIMER_FOLLOW_UP_STALE_ACCOUNT" }
            ObjectEditSnapshot(view.habit, ObjectEditAuthority(access.session, view.habit.uuid, "plan_node", claim.displayedPlan, rounds))
        }
    }

    /** Last UI publication fence, not a business writer and not another history traversal. */
    internal suspend fun publishGoal(claim: TimerCompletionFollowUpClaim, block: suspend () -> Unit) = sessions.exclusive {
        database.withTransaction {
            val access = requireCompletionAccessInTransaction(claim.source)
            val habit = requireNotNull(database.habitDao().getVisibleHabitById(claim.habitId))
            check(habit.uuid == claim.source.habitUuid && NextStructureMapper.writePlan(habit) == claim.displayedPlan)
            check(producer.captureDisplayedRoundsInTransaction()?.let { displayedHead(habit, null, it) } == claim.source.challengeHead)
            check(requireCompletionAccessInTransaction(claim.source) == access) { "TIMER_FOLLOW_UP_STALE_ACCOUNT" }
        }
        block()
    }

    private suspend fun requirePublication(habitId: Long, ticket: TimerActionAuthority?): HabitEntity? {
        check(replica.isOpenWhileAccountLocked()) { com.dayforge.data.local.ReplicaSwitchGuard.PENDING }
        if (ticket != null) {
            val access = tokens.localCoreWriteAccess()
            check(access?.session == ticket.session() && access?.capturedDeviceId == ticket.deviceId) { "TIMER_FOLLOW_UP_STALE_ACCOUNT" }
        }
        val habit = database.habitDao().getHabitById(habitId)
        if (ticket != null) {
            check(habit?.uuid == ticket.habitUuid) { "TIMER_FOLLOW_UP_STALE_ACTIVITY" }
        }
        return habit
    }

    /** A service dispatch is not a completed stop. Wait without holding the account/Room locks. */
    suspend fun afterCompletion(habitId: Long, ticket: TimerActionAuthority, block: suspend (TimerCompletionSnapshot) -> Unit) {
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
        sessions.exclusive {
            val completed = database.withTransaction { completionInTransaction(habitId, ticket) }
            block(completed)
        }
    }

    /** Observe the exact committed legacy stop, then leave the lock before the prompt's own guarded entry. */
    internal suspend fun afterLegacyWidgetCompletion(claim: LegacyWidgetTimerClaim, block: suspend (Boolean) -> Unit) {
        val uuid = requireNotNull(claim.sessionUuid)
        val sequence = requireNotNull(claim.nextSequence)
        val observed = withTimeoutOrNull(10_000L) {
            combine(database.timeLogDao().getTimeLogsByHabit(claim.identity.habitId), accessChanges) { logs, access ->
                check(access != null && access.session == claim.identity.session() && access.capturedDeviceId == claim.identity.deviceId) {
                    "TIMER_FOLLOW_UP_STALE_ACCOUNT"
                }
                logs.singleOrNull { it.uuid == uuid }
            }.first { it == null || it.endTime != null || it.timerNextCommandSequence != sequence }
        } ?: return
        if (observed.endTime == null || observed.timerNextCommandSequence != sequence + 1) return
        val goalReached = sessions.exclusive { database.withTransaction {
            val habit = legacyWidgets.requireIdentity(claim.identity, requirePlan = false)
            check(database.timeLogDao().getTimeLogByUuid(uuid)?.let { it.endTime != null && it.timerNextCommandSequence == sequence + 1 } == true)
            habit.targetCycles?.let { database.timeLogDao().getTargetMetDayCount(habit.id, habit.targetValue * 60) >= it } == true
        } }
        block(goalReached)
    }

    /** Ordinary v4 automatic prompt priority; never supplies authority to a widget callback. */
    internal suspend fun legacyGoalReached(habitId: Long): Boolean = sessions.exclusive { database.withTransaction {
        val habit = requireNotNull(database.habitDao().getVisibleHabitById(habitId))
        require(habit.appearance == null)
        habit.targetCycles?.let { database.timeLogDao().getTargetMetDayCount(habit.id, habit.targetValue * 60) >= it } == true
    } }

    private suspend fun validate(habitId: Long, ticket: TimerActionAuthority?) {
        check(replica.isOpenWhileAccountLocked()) { com.dayforge.data.local.ReplicaSwitchGuard.PENDING }
        val habit = requireNotNull(database.habitDao().getVisibleHabitById(habitId)) { "TIMER_ACTIVITY_NOT_FOUND" }
        if (habit.appearance == null) { require(ticket == null); NextChallengeStore(database).requirePlainInTransaction(); return }
        val bound = requireNotNull(ticket) { "TIMER_ACTION_TICKET_REQUIRED" }
        bound.validate()
        val access = requireNotNull(tokens.localCoreWriteAccess())
        check(access.session == bound.session() && habit.uuid == bound.habitUuid) { "TIMER_ACTION_STALE_ACCOUNT" }
        check(access.capturedDeviceId == bound.deviceId) { "TIMER_ACTION_STALE_DEVICE" }
        check(access.capabilities == null || "timer.control" in access.capabilities) { "TIMER_CONTROL_DENIED" }
        val log = database.timeLogDao().getActiveTimeLogForHabit(habitId)
        check(log?.uuid == bound.sessionUuid && log?.timerNextCommandSequence == bound.nextSequence) { "TIMER_ACTION_STALE_SESSION" }
        if (log == null) check(NextStructureMapper.writePlan(habit).toString() == bound.originalPlan) { "TIMER_START_CONFIG_CHANGED" }
        else NextTimerPolicyStore(database).policy(access, log.uuid)
        val rounds = producer.captureDisplayedRoundsInTransaction()
        check(bound.challengeContract == if (rounds == null) 0 else 1) { "TIMER_ACTION_PROFILE_CHANGED" }
        check(bound.challengeHead == rounds?.let { displayedHead(habit, log, it) }) { "TIMER_ACTION_CHALLENGE_CHANGED" }
        NextPlanDeletionStore(database).requireWritable(habit.uuid)
    }
}
