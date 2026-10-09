package com.dayforge.data.repository

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.businessDate
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.domain.model.CountHistory
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.util.DateTimeUtils
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Effective count rules are projected only after the shared complete-history audit. */
@Singleton
class CountHistoryReader @Inject constructor(database: HabitDatabase, tokens: TokenManager,
    sessions: AccountSessionCoordinator) {
    private val evidence = RecurringCompletionEvidenceReader(database, tokens, sessions)
    val changes = evidence.changes

    suspend fun read(expected: HabitEntity, today: LocalDate = DateTimeUtils.today()): CountHistory {
        check(expected.habitType == HabitType.COUNTING) { "COUNT_ACTIVITY_CHANGED" }
        return project(expected, evidence.read(expected, today))
    }

    internal suspend fun readInTransaction(expected: HabitEntity, today: LocalDate): CountHistory {
        check(expected.habitType == HabitType.COUNTING) { "COUNT_ACTIVITY_CHANGED" }
        return project(expected, evidence.readInTransaction(expected, today))
    }

    private fun project(habit: HabitEntity, source: RecurringCompletionEvidence): CountHistory {
        val quantities = source.completions.groupBy { it.businessDate }.mapValues { (_, rows) ->
            rows.fold(0L) { sum, row -> Math.addExact(sum, row.value.toLong()) }
        }
        return CountHistory(source.today, source.policies, quantities,
            source.policies[source.today] ?: if (source.today in source.unknownDates) null
                else CountDayPolicy(habit.targetValue, habit.isCountdown),
            source.unknownDates, source.completions, source.roundHead)
    }
}
