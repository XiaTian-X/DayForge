package com.dayforge.data.repository

import android.annotation.SuppressLint
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.HabitDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Negative admission only, not a grant of independence, authority or commit success.
 * Pinned Room2.8.5 + the application's SupportSQLite compatibility implementation preserves
 * the inherited suspending-transaction marker across withContext. Its public restricted
 * assertion rejects that marker on a foreign dispatcher, where inTransaction() alone is
 * false. The second assertion rejects the native transaction thread itself.
 *
 * Keep this check before account-mutex acquisition / T0. Do not catch it and start the
 * ordinary API after any command side effect. Review this adapter on a Room/driver upgrade;
 * primary actual-Tx identity, caller checks, fresh authorization and actual END stay required.
 */
internal object CountCommandEntryBoundary {
    @SuppressLint("RestrictedApi") // Single explicit Room2.8.5 compatibility-mode adapter.
    suspend fun requireTopLevel(database: HabitDatabase) {
        currentCoroutineContext().ensureActive()
        requireNotNull(CountTrustRuntime.forDatabase(database)) { "COMMAND_CONTROLLED_OWNER_REQUIRED" }
        database.assertNotSuspendingTransaction()
        check(!database.inTransaction()) { "COMMAND_TOP_LEVEL_TRANSACTION_REQUIRED" }
    }
}
