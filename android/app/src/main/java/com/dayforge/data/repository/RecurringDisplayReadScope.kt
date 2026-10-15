package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.service.AccountSessionCoordinator
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One ordinary, read-only display transaction. Never a cached credential or write grant.
 * Participants borrow the same account/round evidence; the owner reauthenticates before
 * publishing any result. Standalone readers and every writer keep their existing guards.
 */
internal class RecurringDisplayReadScope private constructor(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val owner: Job,
    private val core: LocalCoreWriteAccess,
    private val rounds: NextRoundWriteScope?
) {
    @Volatile private var open = true

    suspend fun borrow(database: HabitDatabase, tokens: TokenManager): Pair<LocalCoreWriteAccess, NextRoundWriteScope?> {
        currentCoroutineContext().ensureActive()
        check(open && this.database === database && this.tokens === tokens &&
            currentCoroutineContext()[Job] === owner && database.inTransaction()) { "DISPLAY_READ_SCOPE_CLOSED" }
        return core to rounds
    }

    companion object {
        suspend fun <T> withSnapshot(database: HabitDatabase, tokens: TokenManager,
            sessions: AccountSessionCoordinator,
            work: AccountSessionCoordinator.Work = AccountSessionCoordinator.Work.GENERAL,
            block: suspend (RecurringDisplayReadScope) -> T): T {
            // Never borrow an enclosing writer's transaction or acquire its account lock again.
            check(!database.inTransaction()) { "DISPLAY_READ_SCOPE_NESTED" }
            return withContext(Dispatchers.IO) { sessions.exclusive(work) {
                withSnapshotWhileAccountLocked(database, tokens, sessions, block = block)
            } }
        }

        /** Caller retains the account lock; never reuse an enclosing writer's transaction. */
        suspend fun <T> withSnapshotWhileAccountLocked(database: HabitDatabase, tokens: TokenManager,
            sessions: AccountSessionCoordinator,
            onMissingAccess: () -> T = { error("RECURRING_READ_ACCESS_REQUIRED") },
            block: suspend (RecurringDisplayReadScope) -> T): T {
            check(!database.inTransaction()) { "DISPLAY_READ_SCOPE_NESTED" }
            return withContext(Dispatchers.IO) { database.withTransaction { NextEvidenceReadSession.read(database) {
                currentCoroutineContext().ensureActive()
                val access = tokens.localCoreWriteAccess() ?: return@read onMissingAccess()
                val captured = NextCoreLocalIntentStore(database, tokens, sessions).captureDisplayedRoundsInTransaction()
                check(captured == null || captured.access.session == access.session &&
                    captured.access.deviceId == access.capturedDeviceId && captured.access.capabilities == access.capabilities) {
                    "RECURRING_READ_SESSION_CHANGED"
                }
                val rounds = captured?.let { NextRoundWriteScope(
                    it.access.copy(capabilities = Collections.unmodifiableSet(it.access.capabilities.toSet())),
                    it.metadataJson, Collections.unmodifiableMap(it.pendingInitials.toMap()),
                    Collections.unmodifiableMap(it.pendingRestarts.toMap())) }
                val scope = RecurringDisplayReadScope(database, tokens, requireNotNull(currentCoroutineContext()[Job]),
                    access.copy(capabilities = access.capabilities?.let { Collections.unmodifiableSet(it.toSet()) }), rounds)
                val changes = totalChanges(database)
                try {
                    val result = block(scope)
                    scope.borrow(database, tokens)
                    check(totalChanges(database) == changes) { "DISPLAY_READ_SCOPE_WROTE_DATA" }
                    check(tokens.isCurrentDisplayReadAuthority(access, rounds?.access)) { "RECURRING_READ_SESSION_CHANGED" }
                    currentCoroutineContext().ensureActive()
                    result
                } finally { scope.open = false }
            } } }
        }

        private fun totalChanges(database: HabitDatabase): Long =
            database.openHelper.writableDatabase.query("SELECT total_changes()").use {
                check(it.moveToFirst()); it.getLong(0)
            }
    }
}
