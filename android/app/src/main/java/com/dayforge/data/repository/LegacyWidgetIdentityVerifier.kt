package com.dayforge.data.repository

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Original rendered v4 identity, never an authorization obtained from a later callback. */
@Singleton
class LegacyWidgetIdentityVerifier @Inject constructor(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val replica: ReplicaPresentationRepository
) {
    /** Caller owns the account mutex and Room transaction; this method never refreshes credentials. */
    internal suspend fun requireIdentity(
        identity: LegacyWidgetRestartClaim,
        requirePlan: Boolean = true,
        permission: String? = null
    ): HabitEntity {
        check(database.inTransaction()) { "LEGACY_WIDGET_TRANSACTION_REQUIRED" }
        check(replica.isOpenWhileAccountLocked()) { com.dayforge.data.local.ReplicaSwitchGuard.PENDING }
        val access = requireNotNull(tokens.localCoreWriteAccess()) { "LEGACY_WIDGET_STALE_ACCOUNT" }
        require(access.session == identity.session() && access.capturedDeviceId == identity.deviceId) {
            "LEGACY_WIDGET_STALE_ACCOUNT"
        }
        require(permission == null || access.capabilities == null || permission in access.capabilities) {
            "LEGACY_WIDGET_PERMISSION_DENIED"
        }
        check(!NextProtocolAdmission.hasNextState(database)) { "LEGACY_WIDGET_PROFILE_CHANGED" }
        NextChallengeStore(database).requirePlainInTransaction()
        val habit = requireNotNull(database.habitDao().getVisibleHabitById(identity.habitId)) {
            "LEGACY_WIDGET_NOT_FOUND"
        }
        require(habit.uuid == identity.habitUuid && habit.appearance == null &&
            (!requirePlan || SyncV2Mapper.planNode(habit) == identity.plan)) {
            "LEGACY_WIDGET_STALE_DISPLAY"
        }
        check(tokens.localCoreWriteAccess() == access) { "LEGACY_WIDGET_STALE_ACCOUNT" }
        return habit
    }
}
