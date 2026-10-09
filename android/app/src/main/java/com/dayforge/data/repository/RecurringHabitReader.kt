package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.businessDate
import com.dayforge.data.local.toDisplayMillis
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CheckHistory
import com.dayforge.domain.model.CountHistory
import com.dayforge.domain.model.TimerHistory
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.domain.service.ActivityRateCalculator
import com.dayforge.util.DateTimeUtils
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Derived presentation is separate from the unchanged authority-bearing plan row. */
class RecurringHabitSnapshot internal constructor(
    val habit: HabitEntity,
    val authority: ObjectEditAuthority,
    val activityRate: Int,
    val countHistory: CountHistory? = null,
    val checkHistory: CheckHistory? = null,
    val timerHistory: TimerHistory? = null
)

/** Stats and edit/action scope from one account/Room snapshot, never two independently fresh reads. */
@Singleton
class RecurringHabitReader @Inject constructor(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val editor: NextObjectEditor,
    private val counts: CountHistoryReader = CountHistoryReader(database, tokens, sessions),
    private val checks: CheckHistoryReader = CheckHistoryReader(database, tokens, sessions),
    private val timers: TimerHistoryReader = TimerHistoryReader(database, tokens, sessions)
) {
    suspend fun read(expected: HabitEntity, today: LocalDate = DateTimeUtils.today()): RecurringHabitSnapshot =
        withContext(Dispatchers.IO) {
            sessions.exclusive {
                database.withTransaction {
                    val access = requireNotNull(tokens.localCoreWriteAccess()) { "RECURRING_READ_ACCESS_REQUIRED" }
                    val snapshot = editor.habitInTransaction(expected.id)
                    check(snapshot.value == expected && expected.appearance != null &&
                        expected.completionPolicy == "recurring") { "RECURRING_READ_ACTIVITY_CHANGED" }
                    val authority = requireNotNull(snapshot.authority)
                    val countHistory = if (expected.habitType == HabitType.COUNTING) counts.readInTransaction(expected, today) else null
                    val checkHistory = if (expected.habitType == HabitType.CHECK_IN) checks.readInTransaction(expected, today) else null
                    val timerHistory = if (expected.habitType == HabitType.TIMER) timers.readInTransaction(expected, today) else null
                    val dates = when {
                        // Activity tracks actual participation; a partial count is NOT a qualified day.
                        countHistory != null -> countHistory.completions.map { it.businessDate }
                        checkHistory != null -> checkHistory.qualifiedDates.toList()
                        timerHistory != null -> timerHistory.qualifiedDates.toList()
                        else -> error("RECURRING_READ_TYPE_INVALID")
                    }
                    check(tokens.localCoreWriteAccess() == access) { "RECURRING_READ_SESSION_CHANGED" }
                    RecurringHabitSnapshot(expected, authority,
                        ActivityRateCalculator.calculate(expected.schedule, expected.createdAt, dates, today.toDisplayMillis()),
                        countHistory, checkHistory, timerHistory)
                }
            }
        }
}
