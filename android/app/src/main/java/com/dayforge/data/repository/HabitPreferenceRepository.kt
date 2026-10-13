package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.domain.service.AccountSessionCoordinator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Short-lived UI ownership proof. Unlike a stored binding it expires on login or replica change. */
class HabitPreferenceClaim internal constructor(internal val session: LocalDataSession,
    val habitId: Long, val habitUuid: String)

data class HabitPreferenceSnapshot(val claim: HabitPreferenceClaim, val notificationEnabled: Boolean,
    val neverAsk: Boolean)

/** Resolves current UUIDs under the existing account/read fence; never upgrades an old Long preference. */
@Singleton
class HabitPreferenceRepository @Inject constructor(private val database: HabitDatabase,
    private val tokens: TokenManager, private val sessions: AccountSessionCoordinator,
    private val preferences: PreferencesManager, private val replica: ReplicaPresentationRepository) {
    val changes: Flow<Unit> = combine(preferences.habitPreferenceChanges, replica.changes) { _, _ -> Unit }

    val pendingHabitIds: Flow<Set<Long>> = combine(changes, database.habitDao().getAllHabits()) { _, _ -> Unit }
        .map {
            try {
                withContext(Dispatchers.IO) { sessions.exclusive {
                    if (!replica.isOpenWhileAccountLocked()) return@exclusive emptySet()
                    val session = tokens.localFactAccess()?.session ?: return@exclusive emptySet()
                    val rows = database.withTransaction { database.habitDao().getVisibleHabitsOnce() }
                    val pending = preferences.pendingMetricHabitUuids(session, rows.map { it.uuid }.toSet()).first()
                    check(tokens.localFactAccess()?.session == session) { "HABIT_PREFERENCE_SESSION_CHANGED" }
                    rows.filter { it.uuid in pending }.mapTo(mutableSetOf()) { it.id }
                } }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                emptySet() // A hint is not permission; unavailable/frozen sources publish no identity.
            }
        }

    suspend fun read(expected: HabitEntity): HabitPreferenceSnapshot = withContext(Dispatchers.IO) {
        sessions.exclusive {
            check(replica.isOpenWhileAccountLocked()) { "HABIT_PREFERENCE_RECOVERY_PENDING" }
            val session = requireNotNull(tokens.localFactAccess()?.session) { "HABIT_PREFERENCE_LOGIN_REQUIRED" }
            requireRow(expected.id, expected.uuid)
            val notification = preferences.getHabitNotificationEnabled(session, expected.uuid).first()
            val neverAsk = preferences.getNeverAskAgain(session, expected.uuid).first()
            check(tokens.localFactAccess()?.session == session) { "HABIT_PREFERENCE_SESSION_CHANGED" }
            HabitPreferenceSnapshot(HabitPreferenceClaim(session, expected.id, expected.uuid), notification, neverAsk)
        }
    }

    suspend fun setNotification(claim: HabitPreferenceClaim, enabled: Boolean) = publish(claim) {
        preferences.setHabitNotificationEnabled(claim.session, claim.habitUuid, enabled)
    }

    suspend fun setNeverAskAgain(claim: HabitPreferenceClaim, value: Boolean) = publish(claim) {
        preferences.setNeverAskAgain(claim.session, claim.habitUuid, value)
    }

    suspend fun removePending(claim: HabitPreferenceClaim) = publish(claim) {
        preferences.removePendingMetricHabit(claim.session, claim.habitUuid)
    }

    /** Only for callers outside an existing account lock (UI/legacy prompt boundaries). */
    internal suspend fun <T> publish(claim: HabitPreferenceClaim, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        sessions.exclusive {
            check(replica.isOpenWhileAccountLocked()) { "HABIT_PREFERENCE_RECOVERY_PENDING" }
            check(tokens.localFactAccess()?.session == claim.session) { "HABIT_PREFERENCE_SESSION_CHANGED" }
            requireRow(claim.habitId, claim.habitUuid)
            block()
        }
    }

    private suspend fun requireRow(id: Long, uuid: String) = database.withTransaction {
        check(database.habitDao().getVisibleHabitByIdSync(id)?.uuid == uuid) { "HABIT_PREFERENCE_IDENTITY_CHANGED" }
    }
}
