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
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.util.DateTimeUtils
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
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
    val changes: Flow<Unit> = combine(database.countDayDao().observeAll(), tokens.factAccessChanges) { _, _ -> Unit }

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
        val quantities = facts.groupBy { it.businessDate }.mapValues { (_, rows) ->
            rows.fold(0L) { sum, row -> Math.addExact(sum, row.value.toLong()) }
        }
        val unknown = quantities.keys.filterTo(linkedSetOf()) { it !in policies }
        // Also inspect today when all facts were undone: an original still fixes the day.
        for (date in unknown + today) if (date !in policies) {
            if (store.hasUnboundHistory(habit, date.toString(), access.session.authentication.userId)) unknown += date
        }
        CountFactEvidence(database).verify(habit, facts, policies, access.session.authentication.userId)
        val todayPolicy = policies[today] ?: if (today in unknown) null else CountDayPolicy(habit.targetValue, habit.isCountdown)
        check(tokens.localCoreWriteAccess() == access) { "COUNT_SESSION_CHANGED" }
        return CountHistory(today, policies, quantities, todayPolicy, unknown, facts)
    }

    private companion object {
        val timeSources = setOf("captured", "server", "legacy_sync", "legacy_device_fallback")
    }
}
