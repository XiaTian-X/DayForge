package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.businessDate
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.domain.model.CountHistory
import com.dayforge.domain.model.ChallengeRoundHead
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.util.DateTimeUtils
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** One read-only account/transaction snapshot for all v5 counting consumers. */
@Singleton
class CountHistoryReader @Inject constructor(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator
) {
    val changes: Flow<Unit> = combine(database.countDayDao().observeReadEvidenceChanges(), tokens.factAccessChanges) { _, _ -> Unit }

    suspend fun read(expected: HabitEntity, today: LocalDate = DateTimeUtils.today()): CountHistory = withContext(Dispatchers.IO) {
        sessions.exclusive {
            database.withTransaction {
                readInTransaction(expected, today)
            }
        }
    }

    /** Caller must hold AccountSessionCoordinator and an active Room transaction (no nested lock). */
    internal suspend fun readInTransaction(expected: HabitEntity, today: LocalDate): CountHistory {
        check(database.inTransaction()) { "COUNT_READ_TRANSACTION_REQUIRED" }
        val access = requireNotNull(tokens.localCoreWriteAccess()) { "COUNT_SESSION_CHANGED" }
        val rounds = NextCoreLocalIntentStore(database, tokens, sessions).captureDisplayedRoundsInTransaction()
        val habit = requireNotNull(database.habitDao().getHabitById(expected.id)) { "COUNT_NOT_FOUND" }
        check(habit == expected && habit.appearance != null && habit.habitType == HabitType.COUNTING &&
            habit.completionPolicy == "recurring") { "COUNT_ACTIVITY_CHANGED" }
        val store = NextCountDayStore(database)
        val policies = database.countDayDao().forHabit(habit.id).associate { candidate ->
            val day = requireNotNull(store.read(habit, candidate.localDate))
            day.originRequestId?.let { id ->
                check(database.nextRequestDao().origin(NEXT_OPERATION, id)?.accountId == access.session.authentication.userId) {
                    "COUNT_SESSION_CHANGED"
                }
            }
            LocalDate.parse(day.localDate) to day.policy
        }
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
        check(facts.map { it.uuid }.distinct().size == facts.size) { "COUNT_FACT_INVALID" }
        // Preserve the existing specific missing-day diagnostic, including hidden old dates.
        // A missing known rule is not an unknown legacy day or an unstarted new-round date.
        val unbound = facts.mapTo(linkedSetOf()) { it.businessDate }.filterTo(linkedSetOf()) { it !in policies }
        for (date in unbound + today) if (date !in policies) {
            if (store.hasUnboundHistory(habit, date.toString(), access.session.authentication.userId)) unbound += date
        }
        // Validate the entire retained history BEFORE selecting this round. Hidden old facts still
        // enforce their immutable quantities/day rules and cannot hide corruption behind a new head.
        val originals = CountFactEvidence(database).verify(habit, facts, policies, access.session.authentication.userId)
        val (head, visible) = selectRound(habit, facts, originals, rounds)
        val quantities = visible.groupBy { it.businessDate }.mapValues { (_, rows) ->
            rows.fold(0L) { sum, row -> Math.addExact(sum, row.value.toLong()) }
        }
        val unknown = quantities.keys.filterTo(linkedSetOf()) { it in unbound }
        // The shared habit/date rule survives undo and restart, even with no current-round facts.
        if (today in unbound) unknown += today
        val todayPolicy = policies[today] ?: if (today in unknown) null else CountDayPolicy(habit.targetValue, habit.isCountdown)
        check(tokens.localCoreWriteAccess() == access) { "COUNT_SESSION_CHANGED" }
        check(rounds == null || tokens.localSyncAccess() == rounds.access) { "COUNT_SESSION_CHANGED" }
        return CountHistory(today, policies, quantities, todayPolicy, unknown, visible, head)
    }

    /** Accepted births or the actual NEW source, never dates, appearance or the current target. */
    private suspend fun selectRound(habit: HabitEntity, facts: List<com.dayforge.data.local.entity.CompletionEntity>,
        originals: Map<String, CountFactOriginal>, scope: NextRoundWriteScope?):
        Pair<ChallengeRoundHead?, List<com.dayforge.data.local.entity.CompletionEntity>> {
        if (scope == null) return null to facts
        val metadata = kotlinx.serialization.json.Json.decodeFromString<com.dayforge.data.api.dto.ChallengeMetadata>(scope.metadataJson)
        val checkpoint = metadata.checkpoints.singleOrNull { it.head.activityUuid == habit.uuid }
        val head = checkpoint?.head ?: requireNotNull(scope.pendingInitials[habit.uuid]) { "COUNT_ROUND_REQUIRED" }.head
        val known = checkpoint?.records?.mapTo(hashSetOf()) { it.head }.orEmpty() +
            listOfNotNull(scope.pendingInitials[habit.uuid]?.head)
        val births = metadata.births.filter { it.entityType == "activity_event" }.associateBy { it.entityUuid }
        val sql = database.openHelper.writableDatabase
        val selected = ArrayList<com.dayforge.data.local.entity.CompletionEntity>()
        for (fact in facts) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val original = originals[fact.uuid]?.row
            val source = originals[fact.uuid]?.rounds
            val accepted = births[fact.uuid]?.head
            accepted?.let { require(it.activityUuid == habit.uuid && it in known) { "COUNT_ROUND_INVALID" } }
            source?.let {
                val origin = requireNotNull(original)
                require(origin.accountId == scope.access.session.authentication.userId &&
                    it.operation.entityUuid == fact.uuid &&
                    it.operation.operationId == origin.requestId && it.context.head?.activityUuid == habit.uuid &&
                    it.context.head in known) { "COUNT_ROUND_INVALID" }
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
                    NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(origin.queueId)) == origin.sourceHash) {
                    "COUNT_ROUND_SOURCE_CHANGED"
                }
                database.nextRequestDao().transmission(NEXT_OPERATION, origin.requestId)?.let { sent ->
                    requireNotNull(NextRequestSql.rowHash(sql, "next_transmissions", "kind=? AND requestId=?",
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
