package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.domain.service.CompletionActionTiming
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
    private val rounds: NextRoundWriteScope?,
    private val countRead: CountTrustKernel.DisplayRead?
) {
    @Volatile private var open = true
    private val calculations = LinkedHashMap<Pair<CountCalculationReuse, CountCalculationReuse.Partition>,
        CountCalculationReuse.Candidate>()

    /** Pure arithmetic candidates only. They carry no credential or SQL capability. */
    internal suspend fun rememberCalculation(candidate: CountCalculationReuse.Candidate) {
        borrow(database, tokens)
        val key = candidate.owner to candidate.partition
        // Optional memory reuse is bounded independently of how many objects a page may read.
        if (calculations.containsKey(key) || calculations.size < 32) calculations[key] = candidate
    }

    private fun promoteCalculations() {
        check(!open)
        calculations.values.forEach { it.owner.promote(it) }
        calculations.clear()
    }

    suspend fun borrow(database: HabitDatabase, tokens: TokenManager): Pair<LocalCoreWriteAccess, NextRoundWriteScope?> {
        currentCoroutineContext().ensureActive()
        check(open && this.database === database && this.tokens === tokens &&
            currentCoroutineContext()[Job] === owner && database.inTransaction()) { "DISPLAY_READ_SCOPE_CLOSED" }
        return core to rounds
    }

    internal suspend fun onceProjection(activityUuid: String, session: com.dayforge.data.local.LocalDataSession): OnceTrustProjection.Read? {
        val access = borrow(database, tokens).first
        check(access.session == session) { "ONE_TIME_SESSION_CHANGED" }
        // A missing participant is an unsupported/cold read, never permission to reopen a
        // maintenance scope inside this original SELECT-only phase.
        return countRead?.onceSnapshot(database, tokens, activityUuid, session)
    }

    internal suspend fun countSummary(kernel: CountTrustKernel,
        habit: com.dayforge.data.local.entity.HabitEntity, today: java.time.LocalDate): com.dayforge.domain.model.CountSummary? {
        val access = borrow(database, tokens).first
        return if (countRead == null) kernel.summary(habit, today, access)
            else countRead.summary(database, tokens, habit, today)
    }

    internal suspend fun countProjection(kernel: CountTrustKernel,
        habit: com.dayforge.data.local.entity.HabitEntity, today: java.time.LocalDate, zone: java.time.ZoneId,
        cardAtMillis: Long? = null): com.dayforge.domain.model.CountDisplayProjection? {
        val access = borrow(database, tokens).first
        return if (countRead == null) kernel.projection(habit, today, zone, access, cardAtMillis)
            else countRead.projection(database, tokens, habit, today, zone, cardAtMillis)
    }

    internal suspend fun checkProjection(kernel: CountTrustKernel,
        habit: com.dayforge.data.local.entity.HabitEntity, today: java.time.LocalDate, zone: java.time.ZoneId,
        cardAtMillis: Long? = null): com.dayforge.domain.model.CheckDisplayProjection? {
        val access = borrow(database, tokens).first
        return if (countRead == null) kernel.checkProjection(habit, today, zone, access, cardAtMillis)
            else countRead.checkProjection(database, tokens, habit, today, zone, cardAtMillis)
    }

    internal suspend fun timerProjection(kernel: CountTrustKernel,
        habit: com.dayforge.data.local.entity.HabitEntity, today: java.time.LocalDate, zone: java.time.ZoneId,
        now: Long): com.dayforge.domain.model.TimerDisplayProjection? {
        val access = borrow(database, tokens).first
        return if (countRead == null) kernel.timerProjection(habit, today, zone, access, now)
            else countRead.timerProjection(database, tokens, habit, today, zone, now)
    }

    internal suspend fun countFact(kernel: CountTrustKernel,
        habit: com.dayforge.data.local.entity.HabitEntity, id: Long?, uuid: String?): com.dayforge.data.local.entity.CompletionEntity? {
        val access = borrow(database, tokens).first
        return if (countRead == null) kernel.exactFact(habit, access, id, uuid)
            else countRead.exactFact(database, tokens, habit, id, uuid)
    }

    internal suspend fun countDetails(kernel: CountTrustKernel,
        habit: com.dayforge.data.local.entity.HabitEntity): List<com.dayforge.data.local.entity.CompletionEntity>? {
        val access = borrow(database, tokens).first
        return if (countRead == null) kernel.details(habit, access)
            else countRead.details(database, tokens, habit)
    }

    internal suspend fun metricSnapshot(metric: com.dayforge.data.local.entity.MetricEntity,
        start: Long, end: Long): MetricWindowSnapshot? {
        borrow(database, tokens)
        return countRead?.metricSnapshot(database, tokens, metric, start, end)
    }
    internal suspend fun metricHistory(metric: com.dayforge.data.local.entity.MetricEntity): List<com.dayforge.data.local.entity.MetricLogEntity>? {
        borrow(database, tokens)
        return countRead?.metricHistory(database, tokens, metric)
    }
    internal suspend fun metricPage(metric: com.dayforge.data.local.entity.MetricEntity,
        upper: Long? = null, target: Int = 10, expectedRevision: Long? = null): MetricTrustProjection.Page {
        borrow(database, tokens)
        return countRead?.metricPage(database, tokens, metric, upper, target, expectedRevision)
            ?: MetricTrustProjection(database).page(null, metric, upper, target, expectedRevision)
    }
    internal suspend fun requireMetricRoster() {
        borrow(database, tokens)
        countRead?.metricRoster(database, tokens)
    }

    /** Metric-only readers never invent a formal-round dependency for an overview or
     * legacy object. A typed detail acquires the original edit rounds in this same native
     * snapshot. Other domains retain their original mandatory round capture. */
    internal class MetricReadSelection(val detailMetricId: Long?)

    companion object {
        suspend fun <T> withSnapshot(database: HabitDatabase, tokens: TokenManager,
            sessions: AccountSessionCoordinator,
            work: AccountSessionCoordinator.Work = AccountSessionCoordinator.Work.GENERAL,
            prepareCountProof: Boolean = true,
            prepareTimerProofFor: com.dayforge.data.local.entity.HabitEntity? = null,
            diagnosticCaller: CompletionActionTiming.ReadCaller? = null,
            openOnceProof: Boolean = false,
            prepareMetricProof: Boolean = false,
            metricReadSelection: MetricReadSelection? = null,
            block: suspend (RecurringDisplayReadScope) -> T): T {
            // Never borrow an enclosing writer's transaction or acquire its account lock again.
            check(!database.inTransaction()) { "DISPLAY_READ_SCOPE_NESTED" }
            return withContext(Dispatchers.IO) { sessions.exclusive(work) {
                withSnapshotWhileAccountLocked(database, tokens, sessions,
                    prepareCountProof = prepareCountProof, prepareTimerProofFor = prepareTimerProofFor, prepareMetricProof = prepareMetricProof, metricReadSelection = metricReadSelection,
                    diagnosticCaller = diagnosticCaller, openOnceProof = openOnceProof, block = block)
            } }
        }

        /** Caller retains the account lock; never reuse an enclosing writer's transaction. */
        suspend fun <T> withSnapshotWhileAccountLocked(database: HabitDatabase, tokens: TokenManager,
            sessions: AccountSessionCoordinator,
            onMissingAccess: () -> T = { error("RECURRING_READ_ACCESS_REQUIRED") },
            prepareCountProof: Boolean = true,
            prepareTimerProofFor: com.dayforge.data.local.entity.HabitEntity? = null,
            diagnosticCaller: CompletionActionTiming.ReadCaller? = null,
            openOnceProof: Boolean = false,
            prepareMetricProof: Boolean = false,
            metricReadSelection: MetricReadSelection? = null,
            block: suspend (RecurringDisplayReadScope) -> T): T {
            check(!database.inTransaction()) { "DISPLAY_READ_SCOPE_NESTED" }
            return withContext(Dispatchers.IO) {
                var completedScope: RecurringDisplayReadScope? = null
                try {
                    val result = database.withTransaction {
                        CompletionActionTiming.markReadCaller(diagnosticCaller)
                        val entryAccess = tokens.localCoreWriteAccess()
                        // Known non-recurring reads keep optional preparation disabled. For a
                        // warm COUNT batch, one lexical API hands the authenticated root to
                        // the immediately following SELECT-only phase of this same transaction.
                        CountTrustKernel(database, tokens, sessions).withPreparedDisplayRead(
                            entryAccess, prepareCountProof || prepareTimerProofFor != null || prepareMetricProof,
                            prepareTimerFor = prepareTimerProofFor, openExistingProof = openOnceProof, prepareMetrics = prepareMetricProof) { countRead ->
                        currentCoroutineContext().ensureActive()
                        // One fresh entry check spans this same account-owned transaction.
                        // Final authority is still freshly checked before any publication.
                        val access = entryAccess ?: return@withPreparedDisplayRead onMissingAccess()
                        val needsRounds = metricReadSelection == null || metricReadSelection.detailMetricId?.let {
                            database.metricDao().getVisibleMetricById(it)?.appearance != null
                        } == true
                        val captured = if (needsRounds) NextCoreLocalIntentStore(database, tokens, sessions).captureDisplayedRoundsInTransaction(
                            preferTrusted = true, countRead = countRead) else null
                        check(captured == null || captured.access.session == access.session &&
                            captured.access.deviceId == access.capturedDeviceId && captured.access.capabilities == access.capabilities) {
                            "RECURRING_READ_SESSION_CHANGED"
                        }
                        val rounds = captured?.let { NextRoundWriteScope(
                            it.access.copy(capabilities = Collections.unmodifiableSet(it.access.capabilities.toSet())),
                            it.metadataJson, Collections.unmodifiableMap(it.pendingInitials.toMap()),
                            Collections.unmodifiableMap(it.pendingRestarts.toMap())) }
                        val scope = RecurringDisplayReadScope(database, tokens, requireNotNull(currentCoroutineContext()[Job]),
                            access.copy(capabilities = access.capabilities?.let { Collections.unmodifiableSet(it.toSet()) }), rounds, countRead)
                        val changes = totalChanges(database)
                        var qualified = false
                        try {
                            val value = block(scope)
                            scope.borrow(database, tokens)
                            check(totalChanges(database) == changes) { "DISPLAY_READ_SCOPE_WROTE_DATA" }
                            check(tokens.isCurrentDisplayReadAuthority(access, rounds?.access)) { "RECURRING_READ_SESSION_CHANGED" }
                            currentCoroutineContext().ensureActive()
                            completedScope = scope
                            qualified = true
                            value
                        } finally {
                            scope.open = false
                            if (!qualified) scope.calculations.clear()
                        }
                    } }
                    // The Room call has returned from actual END. Failure/cancellation/unknown
                    // results skip promotion; a finally inside the transaction would be too early.
                    currentCoroutineContext().ensureActive()
                    completedScope?.promoteCalculations()
                    result
                } finally { completedScope?.calculations?.clear() }
            }
        }

        private fun totalChanges(database: HabitDatabase): Long =
            database.openHelper.writableDatabase.query("SELECT total_changes()").use {
                check(it.moveToFirst()); it.getLong(0)
            }
    }
}
