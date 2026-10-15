package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CountActionIdentity
import com.dayforge.domain.service.AccountSessionCoordinator
import javax.inject.Inject
import javax.inject.Singleton

/** The retained legacy profile also carries original-account provenance; no ID-only fallback. */
@Singleton
class CountActionReader @Inject constructor(private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator) {
    internal suspend fun legacy(expected: HabitEntity): CountActionIdentity =
        RecurringDisplayReadScope.withSnapshot(database, tokens, sessions) { scope ->
            check(database.habitDao().getVisibleHabitById(expected.id) == expected) { "COUNT_ACTIVITY_CHANGED" }
            val core = scope.borrow(database, tokens).first
            legacyIdentity(expected, core)
        }

    internal suspend fun <T> writeLegacy(identity: CountActionIdentity, block: suspend () -> T): T = sessions.exclusive {
        database.withTransaction {
            val access = requireNotNull(tokens.localCoreWriteAccess()) { "COUNT_SESSION_CHANGED" }
            check(access.session == identity.session && access.capturedDeviceId == identity.deviceId &&
                (access.capabilities == null || "facts.append" in access.capabilities)) { "COUNT_SESSION_CHANGED" }
            val actual = requireNotNull(database.habitDao().getVisibleHabitById(identity.habitId))
            check(identity.legacy != null && actual.uuid == identity.habitUuid && actual.appearance == null &&
                identity.plan == SyncV2Mapper.planNode(actual)) { "COUNT_ACTIVITY_CHANGED" }
            NextChallengeStore(database).requirePlainInTransaction()
            val result = block()
            check(tokens.localCoreWriteAccess() == access) { "COUNT_SESSION_CHANGED" }
            result
        }
    }

    internal companion object {
        fun legacyIdentity(habit: HabitEntity, core: LocalCoreWriteAccess): CountActionIdentity {
            require(habit.habitType == HabitType.COUNTING && habit.appearance == null)
            return CountActionIdentity(core.session, habit.id, habit.uuid, core.capturedDeviceId,
                SyncV2Mapper.planNode(habit), null, habit)
        }
    }
}
