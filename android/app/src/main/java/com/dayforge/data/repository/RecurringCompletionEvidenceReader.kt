package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.businessDate
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.domain.model.ChallengeRoundHead
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.util.DateTimeUtils
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal data class RecurringCompletionEvidence(
    val today: LocalDate,
    val policies: Map<LocalDate, CountDayPolicy>,
    val unknownDates: Set<LocalDate>,
    val completions: List<com.dayforge.data.local.entity.CompletionEntity>,
    val roundHead: ChallengeRoundHead?,
    val countIdentity: com.dayforge.domain.model.CountActionIdentity? = null,
    val checkIdentity: com.dayforge.domain.model.CheckActionIdentity? = null
)

/** Audit all retained ordinary facts before selecting the displayed round. No write authority. */
internal class RecurringCompletionEvidenceReader(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator
) {
    val changes: Flow<Unit> = combine(database.countDayDao().observeReadEvidenceChanges(), tokens.factAccessChanges) { _, _ -> Unit }

    suspend fun read(expected: HabitEntity, today: LocalDate = DateTimeUtils.today()): RecurringCompletionEvidence = withContext(Dispatchers.IO) {
        sessions.exclusive(AccountSessionCoordinator.Work.FACT_HISTORY_READ) {
            database.withTransaction {
                readInTransaction(expected, today)
            }
        }
    }

    /** Caller must hold AccountSessionCoordinator and an active Room transaction (no nested lock). */
    internal suspend fun readInTransaction(expected: HabitEntity, today: LocalDate): RecurringCompletionEvidence =
        readEvidenceInTransaction(expected, today, false)

    internal suspend fun readInTransaction(expected: HabitEntity, today: LocalDate,
        scope: RecurringDisplayReadScope): RecurringCompletionEvidence =
        readEvidenceInTransaction(expected, today, false, displayScope = scope)

    /** Audit every retained round and historical tracking mode without changing the displayed consumer. */
    internal suspend fun recoveryInTransaction(expected: HabitEntity, today: LocalDate): RecurringCompletionEvidence =
        readEvidenceInTransaction(expected, today, true)

    internal suspend fun recoveryInTransaction(expected: HabitEntity, today: LocalDate,
        scope: ReplicaFactRecoveryReadScope): RecurringCompletionEvidence =
        readEvidenceInTransaction(expected, today, true, scope)

    private suspend fun readEvidenceInTransaction(expected: HabitEntity, today: LocalDate, recovery: Boolean,
        recoveryScope: ReplicaFactRecoveryReadScope? = null,
        displayScope: RecurringDisplayReadScope? = null): RecurringCompletionEvidence {
        check(database.inTransaction()) { "COUNT_READ_TRANSACTION_REQUIRED" }
        val timing = HistoryAuditTiming(HistoryAuditTiming.Kind.COMPLETION)
        check(recoveryScope == null || recovery)
        check(displayScope == null || !recovery && recoveryScope == null)
        val borrowed = recoveryScope?.borrow(database, tokens) ?: displayScope?.borrow(database, tokens)
        suspend fun observedAccess() = if (recovery) tokens.replicaObservationCoreAccess() else tokens.localCoreWriteAccess()
        val access = borrowed?.first ?: requireNotNull(observedAccess()) { "COUNT_SESSION_CHANGED" }
        val rounds = if (borrowed != null) borrowed.second
            else NextCoreLocalIntentStore(database, tokens, sessions).captureDisplayedRoundsInTransaction()
        timing.mark(HistoryAuditTiming.Stage.AUTHORITY)
        val habit = requireNotNull(database.habitDao().getHabitById(expected.id)) { "COUNT_NOT_FOUND" }
        if (!recovery && habit.uuid == expected.uuid && habit != expected)
            throw HabitReadSnapshotChanged("COUNT_ACTIVITY_CHANGED")
        check(habit == expected && habit.appearance != null &&
            (if (recovery) habit.habitType != HabitType.GOAL else habit.habitType in setOf(HabitType.COUNTING, HabitType.CHECK_IN)) &&
            habit.completionPolicy == "recurring") { "COUNT_ACTIVITY_CHANGED" }
        val store = NextCountDayStore(database)
        val policies = store.readForHabit(habit).associate { day ->
            day.originRequestId?.let { id ->
                check(database.nextRequestDao().origin(NEXT_OPERATION, id)?.accountId == access.session.authentication.userId) {
                    "COUNT_SESSION_CHANGED"
                }
            }
            LocalDate.parse(day.localDate) to day.policy
        }
        timing.mark(HistoryAuditTiming.Stage.DAY_RULES)
        // Room's Int/Boolean coercion must not hide malformed effective quantities.
        val zones = java.time.ZoneId.getAvailableZoneIds()
        database.openHelper.writableDatabase.query("""SELECT value,habitUuid,oneTimeAction,recordedLocalDate,
            date,actualCompletedAt,createdAt,recordedTimezone,timeMetadataSource,
            oneTimeExpectedVersion,oneTimeExpectedHeadEventUuid,oneTimeRevertsEventUuid,uuid FROM completions WHERE habitId=?""",
            arrayOf(habit.id)).use { cursor ->
            while (cursor.moveToNext()) {
                check(cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER && cursor.getLong(0) in 1..Int.MAX_VALUE.toLong() &&
                    cursor.getType(1) == Cursor.FIELD_TYPE_STRING && cursor.getString(1) == habit.uuid && cursor.isNull(2) &&
                    cursor.getType(3) == Cursor.FIELD_TYPE_STRING && LocalDate.parse(cursor.getString(3)).toString() == cursor.getString(3) &&
                    cursor.getType(4) == Cursor.FIELD_TYPE_INTEGER &&
                    (cursor.isNull(5) || cursor.getType(5) == Cursor.FIELD_TYPE_INTEGER) && cursor.getType(6) == Cursor.FIELD_TYPE_INTEGER &&
                    cursor.getType(7) == Cursor.FIELD_TYPE_STRING && cursor.getString(7) in zones &&
                    cursor.getType(8) == Cursor.FIELD_TYPE_STRING && cursor.getString(8) in
                        timeSources && (9..11).all { cursor.isNull(it) } &&
                    cursor.getType(12) == Cursor.FIELD_TYPE_STRING && isContractUuid(cursor.getString(12))) { "COUNT_FACT_INVALID" }
            }
        }
        val facts = database.completionDao().getCompletionsByHabit(habit.id).first()
        timing.mark(HistoryAuditTiming.Stage.FACT_ROWS)
        check(facts.map { it.uuid }.distinct().size == facts.size) { "COUNT_FACT_INVALID" }
        // Preserve the existing specific missing-day diagnostic, including hidden old dates.
        // A missing known rule is not an unknown legacy day or an unstarted new-round date.
        val unbound = facts.mapTo(linkedSetOf()) { it.businessDate }.filterTo(linkedSetOf()) { it !in policies }
        // Only COUNT needs the missing/unknown day-rule probe. CHECK still audits every fact and
        // retained count rule below, but must not rescan lifetime sources for every check-in date.
        for (date in if (habit.habitType == HabitType.COUNTING) unbound + today else emptySet()) if (date !in policies) {
            if (store.hasUnboundHistory(habit, date.toString(), access.session.authentication.userId)) unbound += date
        }
        // Validate the entire retained history BEFORE selecting this round. Hidden old facts still
        // enforce their immutable quantities/day rules and cannot hide corruption behind a new head.
        val originals = RecurringCompletionFactEvidence(database).verify(habit, facts, policies, access.session.authentication.userId)
        timing.mark(HistoryAuditTiming.Stage.FACT_PROOF)
        val (head, visible) = selectRound(habit, facts, originals, rounds)
        timing.mark(HistoryAuditTiming.Stage.SELECT_ROUND)
        val retained = if (recovery) facts else visible
        val unknown = retained.mapTo(linkedSetOf()) { it.businessDate }.filterTo(linkedSetOf()) { it in unbound }
        // The shared habit/date rule survives undo and restart, even with no current-round facts.
        if (today in unbound) unknown += today
        if (recoveryScope != null) recoveryScope.borrow(database, tokens)
        else if (displayScope != null) displayScope.borrow(database, tokens)
        else {
            check(observedAccess() == access) { "COUNT_SESSION_CHANGED" }
            check(rounds == null || tokens.localSyncAccess() == rounds.access) { "COUNT_SESSION_CHANGED" }
        }
        timing.mark(HistoryAuditTiming.Stage.EXIT_AUTH)
        return RecurringCompletionEvidence(today, policies, unknown, retained, head,
            if (!recovery && habit.habitType == HabitType.COUNTING)
                com.dayforge.domain.model.CountActionIdentity(access.session, habit.id, habit.uuid,
                    access.capturedDeviceId, NextStructureMapper.writePlan(habit), head) else null,
            if (!recovery && habit.habitType == HabitType.CHECK_IN)
                com.dayforge.domain.model.CheckActionIdentity(access.session, habit.id, habit.uuid,
                    access.capturedDeviceId, NextStructureMapper.writePlan(habit), head) else null)
    }

    /** Accepted births or the actual NEW source, never dates, appearance or the current target. */
    private suspend fun selectRound(habit: HabitEntity, facts: List<com.dayforge.data.local.entity.CompletionEntity>,
        originals: Map<String, RecurringCompletionOriginal>, scope: NextRoundWriteScope?):
        Pair<ChallengeRoundHead?, List<com.dayforge.data.local.entity.CompletionEntity>> {
        if (scope == null) return null to facts
        val index = scope.completionRounds
        val head = scope.pendingRestarts[habit.uuid]?.head ?: index.head(habit.uuid) ?:
            requireNotNull(scope.pendingInitials[habit.uuid]) { "COUNT_ROUND_REQUIRED" }.head
        fun known(head: ChallengeRoundHead?) = head != null &&
            (index.contains(habit.uuid, head) || head == scope.pendingInitials[habit.uuid]?.head)
        val selected = ArrayList<com.dayforge.data.local.entity.CompletionEntity>()
        for (fact in facts) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val original = originals[fact.uuid]?.row
            val source = originals[fact.uuid]?.rounds
            val accepted = index.birth(fact.uuid)
            val pendingHead = if (accepted == null) source?.restartFrontier?.let { ref ->
                NextRestartBindingStore(database).captured(scope.access, ref, requireNotNull(original).queueId - 1)
                ref.head
            } else null
            accepted?.let { require(it.activityUuid == habit.uuid && known(it)) { "COUNT_ROUND_INVALID" } }
            source?.let {
                val origin = requireNotNull(original)
                require(origin.accountId == scope.access.session.authentication.userId &&
                    it.operation.entityUuid == fact.uuid &&
                    it.operation.operationId == origin.requestId && it.context.head?.activityUuid == habit.uuid &&
                    (known(it.context.head) || it.context.head == pendingHead)) { "COUNT_ROUND_INVALID" }
                accepted?.let { birth -> require(birth == it.context.head) { "COUNT_ROUND_INVALID" } }
            }
            val birth = accepted ?: run {
                // A profile fact whose receipt already exists must have the original accepted birth.
                // An unbound/old plain source is not relabelled, even when the head is generation zero.
                requireNotNull(source) { "COUNT_ROUND_BIRTH_REQUIRED" }
                val origin = requireNotNull(original)
                require(origin.serverInstanceId == scope.access.session.serverInstanceId && origin.syncEpoch == scope.access.session.syncEpoch &&
                    source.capturedDeviceId == scope.access.deviceId &&
                    database.nextRequestDao().acceptance(NEXT_OPERATION, origin.requestId) == null &&
                    NextRequestSql.rowHash(database, "sync_outbox", "id=?", arrayOf(origin.queueId)) == origin.sourceHash) {
                    "COUNT_ROUND_SOURCE_CHANGED"
                }
                database.nextRequestDao().transmission(NEXT_OPERATION, origin.requestId)?.let { sent ->
                    requireNotNull(NextRequestSql.rowHash(database, "next_transmissions", "kind=? AND requestId=?",
                        arrayOf(NEXT_OPERATION, origin.requestId)))
                    require(sent.accountId == origin.accountId && sent.serverInstanceId == origin.serverInstanceId &&
                        sent.syncEpoch == origin.syncEpoch && sent.deviceId == scope.access.deviceId &&
                        sent.queueId == origin.queueId && sent.protocol == 5 && nextRequestHash(sent.wireBytes) == sent.wireHash)
                    require(validateNextOperationEnvelope(origin.intentJson, sent.wireBytes, sent.deviceId) == source.operation)
                }
                requireNotNull(source.context.head)
            }
            if (birth == head) selected += fact
        }
        return head to selected
    }

    private companion object {
        val timeSources = setOf("captured", "server", "legacy_sync", "legacy_device_fallback")
    }
}
