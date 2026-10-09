package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.businessDate
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.domain.service.ActivityRateCalculator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** v4 display cache only. Typed/current-round consumers derive their rate without rewriting plans. */
@Singleton
class LegacyActivityRateRefresher @Inject constructor(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator
) {
    suspend fun refresh(): Unit = withContext(Dispatchers.IO) {
        sessions.exclusive {
            // A stale worker after logout or an unbound authentication must not touch personal rows.
            val access = tokens.localFactAccess() ?: return@exclusive
            database.withTransaction {
                for (habit in database.habitDao().getVisibleHabitsOnce()) {
                    currentCoroutineContext().ensureActive()
                    // Partially initialized v5 rows also cannot acquire a lifetime cache baseline.
                    if (habit.appearance != null || habit.planMetadata != null || habit.completionPolicy != null ||
                        habit.oneTimeConfirmedVersion != null || habit.oneTimeConfirmedHeadEventUuid != null ||
                        habit.oneTimeConfirmedCompletionEventUuid != null) continue
                    val dates = database.completionDao().getByHabitOnce(habit.id).map { it.businessDate }
                    val rate = ActivityRateCalculator.calculate(habit.schedule, habit.createdAt, dates)
                    database.habitDao().updateActivityRate(habit.id, rate)
                }
                currentCoroutineContext().ensureActive()
                check(tokens.localFactAccess() == access) { "ACTIVITY_REFRESH_SESSION_CHANGED" }
            }
        }
    }
}
