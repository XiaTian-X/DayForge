package com.dayforge.data.repository

import com.dayforge.data.local.*
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CheckHistory
import com.dayforge.domain.model.CountHistory
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** No scope or credential escapes the transaction. These witnesses only support a fresh recheck. */
internal class DashboardReadBatch internal constructor(
    val habits: List<HabitEntity>,
    val completions: List<CompletionEntity>,
    val counts: Map<Long, CountHistory>,
    val checks: Map<Long, CheckHistory>,
    val time: ZonedDateTime,
    internal val core: LocalCoreWriteAccess,
    internal val registered: LocalSyncAccess?
)

@Singleton
class DashboardReadRepository @Inject constructor(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val replica: ReplicaPresentationRepository,
    private val counts: CountHistoryReader,
    private val checks: CheckHistoryReader
) {
    /** A screen interaction may finish its committed follow-up before its background reread.
     * This carries no data/authority and does not suppress invalidations or final publication checks.
     */
    internal suspend fun <T> withInteractiveReadPriority(block: suspend () -> T): T =
        sessions.withInteractiveReadPriority(block)

    // Read invalidations, not versions: same row counts may represent changed contents.
    // One observer for the union of the original four sources. A multi-table commit must not
    // fan out into independent COUNT queries/refreshes. Keep same-row updates and receipt-only
    // changes: the emitted set is a pulse, never a content version or a cached authority.
    internal val changes get() = database.invalidationTracker.createFlow(
        "count_days", "completions", "habits", "next_sync_state",
        "next_challenge_state", "next_challenge_rounds", "next_challenge_births",
        "next_request_origins", "next_transmissions", "next_acceptances",
        "next_restart_materializations", "next_restart_plan_proofs", "sync_outbox", "sync_entity_state",
        "timelogs", "timer_segments", "timelog_day_allocations", "timer_command_outbox",
        "completion_metric_prompts", "one_time_transmissions", "next_replica_fence"
    ).map { Unit }
    internal val authorityChanges get() = tokens.personalDisplayChanges
    internal fun register(listener: PersonalDataDisplayMemory.Listener) = tokens.registerPersonalDisplay(listener)

    internal suspend fun read(time: ZonedDateTime): DashboardReadBatch =
        RecurringDisplayReadScope.withSnapshot(database, tokens, sessions, AccountSessionCoordinator.Work.DASHBOARD_READ) { scope ->
            check(replica.isOpenWhileAccountLocked()) { "DASHBOARD_REPLICA_CLOSED" }
            val (core, rounds) = scope.borrow(database, tokens)
            check(core.capabilities == null || "sync.read" in core.capabilities) { "DASHBOARD_READ_DENIED" }
            // Plain registered replicas also need the capability revision witness, even
            // when there is no challenge round. This is captured once for the whole batch.
            val registered = rounds?.access ?: tokens.localSyncAccess()
            check(registered == null || registered.session == core.session &&
                registered.deviceId == core.capturedDeviceId && registered.capabilities == core.capabilities) {
                "RECURRING_READ_SESSION_CHANGED"
            }
            val rows = database.habitDao().getVisibleHabitsOnce()
            val countRows = linkedMapOf<Long, CountHistory>()
            val checkRows = linkedMapOf<Long, CheckHistory>()
            val costs = LongArray(2) // Fixed categories only; no object identities in diagnostics.
            for (habit in rows) {
                val started = android.os.SystemClock.elapsedRealtime()
                if (habit.appearance == null || habit.completionPolicy == "one_and_done") continue
                when (habit.habitType) {
                    HabitType.COUNTING -> countRows[habit.id] = counts.readInTransaction(habit, time.toLocalDate(), scope)
                    HabitType.CHECK_IN -> checkRows[habit.id] = checks.readInTransaction(habit, time.toLocalDate(), scope)
                    else -> Unit // Keep independent timer/once reads: full-history batching held the lock too long.
                }
                val category = when (habit.habitType) {
                    HabitType.COUNTING -> 0
                    HabitType.CHECK_IN -> 1
                    else -> continue
                }
                costs[category] += android.os.SystemClock.elapsedRealtime() - started
            }
            if (com.dayforge.BuildConfig.DEBUG) android.util.Log.d("DashboardReadTiming",
                "phase=primary count_ms=${costs[0]} check_ms=${costs[1]}")
            DashboardReadBatch(rows, database.completionDao().getAllCompletionsOnce(),
                countRows.toMap(), checkRows.toMap(), time, core, registered)
        }

    /** The final callback is memory-only and non-suspending. Caller also fences its own lifetime. */
    internal suspend fun publish(batch: DashboardReadBatch, publish: () -> Unit): Boolean =
        withContext(Dispatchers.IO) { sessions.exclusive(AccountSessionCoordinator.Work.DASHBOARD_PUBLISH) {
            if (!replica.isOpenWhileAccountLocked() ||
                !tokens.isCurrentDisplayReadAuthority(batch.core, batch.registered)) return@exclusive false
            currentCoroutineContext().ensureActive()
            val now = ZonedDateTime.now()
            if (now.zone != batch.time.zone || now.toLocalDate() != batch.time.toLocalDate()) return@exclusive false
            publish()
            true
        } }
}
