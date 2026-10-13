package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.service.AccountSessionCoordinator
import java.util.Collections
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Borrowed evidence for one read-only recovery transaction, never an ordinary action ticket.
 * Authentication and ALL displayed round sources are captured once, not once per fact kind/habit.
 * No credentials are retained. The owning callback, Job, database and TokenManager cannot change;
 * a fresh full registered authority check is still required before returning any result.
 */
internal class ReplicaFactRecoveryReadScope private constructor(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val owner: Job,
    private val core: LocalCoreWriteAccess,
    private val rounds: NextRoundWriteScope?
) {
    @Volatile private var open = true

    internal suspend fun borrow(database: HabitDatabase, tokens: TokenManager): Pair<LocalCoreWriteAccess, NextRoundWriteScope?> {
        currentCoroutineContext().ensureActive()
        check(open && this.database === database && this.tokens === tokens &&
            currentCoroutineContext()[Job] === owner && database.inTransaction()) { "SYNC_RECOVERY_READ_SCOPE_CLOSED" }
        return core to rounds
    }

    companion object {
        suspend fun <T> withSnapshot(database: HabitDatabase, tokens: TokenManager,
            sessions: AccountSessionCoordinator, expected: LocalSyncAccess,
            block: suspend (ReplicaFactRecoveryReadScope) -> T): T {
            val original = expected.copy(capabilities = Collections.unmodifiableSet(expected.capabilities.toSet()))
            require(original.deviceId != null) { "SYNC_RECOVERY_READ_SCOPE_UNREGISTERED" }
            return sessions.exclusive { database.withTransaction {
                currentCoroutineContext().ensureActive()
                check(tokens.isCurrentSyncAuthority(original)) { "SYNC_ACCOUNT_CHANGED" }
                val core = requireNotNull(tokens.replicaObservationCoreAccess()) { "SYNC_ACCOUNT_CHANGED" }
                check(core.session == original.session && core.capturedDeviceId == original.deviceId &&
                    core.capabilities == original.capabilities) { "SYNC_ACCOUNT_CHANGED" }
                val captured = NextCoreLocalIntentStore(database, tokens, sessions).captureDisplayedRoundsInTransaction()
                check(captured == null || captured.access == original) { "SYNC_ACCOUNT_CHANGED" }
                val rounds = captured?.let { NextRoundWriteScope(original, it.metadataJson,
                    Collections.unmodifiableMap(it.pendingInitials.toMap()),
                    Collections.unmodifiableMap(it.pendingRestarts.toMap())) }
                val scope = ReplicaFactRecoveryReadScope(database, tokens, requireNotNull(currentCoroutineContext()[Job]),
                    core.copy(capabilities = original.capabilities), rounds)
                try {
                    val result = block(scope)
                    scope.borrow(database, tokens)
                    check(tokens.isCurrentSyncAuthority(original)) { "SYNC_ACCOUNT_CHANGED" }
                    currentCoroutineContext().ensureActive()
                    result
                } finally { scope.open = false }
            } }
        }
    }
}
