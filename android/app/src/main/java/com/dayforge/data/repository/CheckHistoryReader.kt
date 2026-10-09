package com.dayforge.data.repository

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CheckHistory
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.util.DateTimeUtils
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** CHECK and COUNT use identical account, original-source, birth and hidden-history proofs. */
@Singleton
class CheckHistoryReader @Inject constructor(database: HabitDatabase, tokens: TokenManager,
    sessions: AccountSessionCoordinator) {
    private val evidence = RecurringCompletionEvidenceReader(database, tokens, sessions)
    val changes = evidence.changes

    suspend fun read(expected: HabitEntity, today: LocalDate = DateTimeUtils.today()): CheckHistory {
        check(expected.habitType == HabitType.CHECK_IN) { "CHECK_ACTIVITY_CHANGED" }
        return project(evidence.read(expected, today))
    }

    internal suspend fun readInTransaction(expected: HabitEntity, today: LocalDate): CheckHistory {
        check(expected.habitType == HabitType.CHECK_IN) { "CHECK_ACTIVITY_CHANGED" }
        return project(evidence.readInTransaction(expected, today))
    }

    private fun project(source: RecurringCompletionEvidence) = CheckHistory(source.today, source.completions, source.roundHead)
}
