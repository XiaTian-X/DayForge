package com.dayforge.data.repository

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.businessDate
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.domain.model.CountHistory
import com.dayforge.domain.model.CountSummary
import com.dayforge.domain.model.CountDisplayProjection
import java.time.ZoneId
import com.dayforge.data.history.CountCalculationBlocks
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.util.DateTimeUtils
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ensureActive

/** Effective count results require either the original proof or its continuously maintained device root. */
@Singleton
class CountHistoryReader @Inject constructor(private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator) {
    private val evidence = RecurringCompletionEvidenceReader(database, tokens, sessions)
    private val calculations = CountCalculationReuse(tokens)
    val changes = evidence.changes
    internal val reuseDiagnostics get() = calculations.diagnostics()

    /** A complete summary plus one explicit action source, never a partial CountHistory. */
    internal data class Selection(val summary: CountSummary,
        val fact: com.dayforge.data.local.entity.CompletionEntity?, val fullHistory: CountHistory? = null)

    internal suspend fun selectInTransaction(expected: HabitEntity, today: LocalDate,
        scope: RecurringDisplayReadScope? = null, sourceId: Long? = null, sourceUuid: String? = null,
        metricSourceRead: CountTrustKernel.MetricSourceRead? = null): Selection {
        check(scope == null || metricSourceRead == null)
        check(expected.habitType == HabitType.COUNTING && (sourceId == null || sourceUuid == null))
        val access = scope?.borrow(database, tokens)?.first ?: requireNotNull(tokens.localCoreWriteAccess())
        val kernel = CountTrustKernel(database, tokens, sessions)
        val summary = if (metricSourceRead != null) metricSourceRead.summary(database, tokens, access, expected, today)
            else if (scope == null) kernel.summary(expected, today, access) else scope.countSummary(kernel, expected, today)
        if (summary != null) {
            val id = if (sourceUuid == null) sourceId ?: summary.todayLatestCompletionId else null
            val fact = if (id != null || sourceUuid != null) {
                if (metricSourceRead != null) metricSourceRead.exactFact(database, tokens, access, expected, id, sourceUuid)
                else if (scope == null) kernel.exactFact(expected, access, id, sourceUuid)
                else scope.countFact(kernel, expected, id, sourceUuid)
            } else null
            check((id == null && sourceUuid == null) || fact != null) { "COMPLETION_SOURCE_CHANGED" }
            if (sourceId == null && sourceUuid == null && fact != null)
                check(fact.recordedLocalDate == today.toString()) { "COMPLETION_SOURCE_CHANGED" }
            if (scope == null) check(tokens.localCoreWriteAccess() == access) { "COUNT_SESSION_CHANGED" }
            else scope.borrow(database, tokens)
            return Selection(summary, fact)
        }
        val history = if (scope == null) readInTransaction(expected, today) else readInTransaction(expected, today, scope)
        val selected = when {
            sourceId != null -> history.completions.singleOrNull { it.id == sourceId }
            sourceUuid != null -> history.completions.singleOrNull { it.uuid == sourceUuid }
            else -> history.completions.singleOrNull { it.id == history.summary.todayLatestCompletionId }
        }
        check((sourceId == null && sourceUuid == null) || selected != null) { "COMPLETION_SOURCE_CHANGED" }
        return Selection(history.summary, selected, history)
    }

    internal data class ProjectedSelection(val projection: CountDisplayProjection,
        val fact: com.dayforge.data.local.entity.CompletionEntity?)

    /** The exact logical participant guards remain. The compact object cannot satisfy a
     * request for detail history or totalQuantity; those original entry points are unchanged. */
    internal suspend fun readProjectionInTransaction(expected: HabitEntity, today: LocalDate, zone: ZoneId,
        scope: RecurringDisplayReadScope, cardAtMillis: Long? = null): CountDisplayProjection {
        check(expected.habitType == HabitType.COUNTING) { "COUNT_ACTIVITY_CHANGED" }
        val kernel = CountTrustKernel(database, tokens, sessions)
        val compact = scope.countProjection(kernel, expected, today, zone, cardAtMillis)
        scope.borrow(database, tokens)
        return compact ?: projectCountDisplay(expected, readSummaryInTransaction(expected, today, scope), zone, cardAtMillis)
    }

    internal suspend fun selectProjectionInTransaction(expected: HabitEntity, today: LocalDate, zone: ZoneId,
        scope: RecurringDisplayReadScope? = null, sourceId: Long? = null, sourceUuid: String? = null,
        metricSourceRead: CountTrustKernel.MetricSourceRead? = null): ProjectedSelection {
        check(scope != null || metricSourceRead != null)
        check(scope == null || metricSourceRead == null)
        check(expected.habitType == HabitType.COUNTING && (sourceId == null || sourceUuid == null))
        val access = scope?.borrow(database, tokens)?.first ?: requireNotNull(tokens.localCoreWriteAccess())
        val kernel = CountTrustKernel(database, tokens, sessions)
        val projection = if (metricSourceRead != null)
            metricSourceRead.projection(database, tokens, access, expected, today, zone)
            else requireNotNull(scope).countProjection(kernel, expected, today, zone)
        if (projection == null) {
            val old = selectInTransaction(expected, today, scope, sourceId, sourceUuid, metricSourceRead)
            return ProjectedSelection(projectCountDisplay(expected, old.summary, zone), old.fact)
        }
        val id = if (sourceUuid == null) sourceId ?: projection.todayLatestCompletionId else null
        val fact = if (id != null || sourceUuid != null) {
            if (metricSourceRead != null) metricSourceRead.exactFact(database, tokens, access, expected, id, sourceUuid)
            else requireNotNull(scope).countFact(kernel, expected, id, sourceUuid)
        } else null
        check((id == null && sourceUuid == null) || fact != null) { "COMPLETION_SOURCE_CHANGED" }
        if (sourceId == null && sourceUuid == null && fact != null)
            check(fact.recordedLocalDate == today.toString()) { "COMPLETION_SOURCE_CHANGED" }
        if (scope == null) check(tokens.localCoreWriteAccess() == access) { "COUNT_SESSION_CHANGED" }
        else scope.borrow(database, tokens)
        return ProjectedSelection(projection, fact)
    }

    suspend fun read(expected: HabitEntity, today: LocalDate = DateTimeUtils.today()): CountHistory =
        RecurringDisplayReadScope.withSnapshot(database, tokens, sessions,
            AccountSessionCoordinator.Work.FACT_HISTORY_READ) { readInTransaction(expected, today, it) }

    suspend fun readSummary(expected: HabitEntity, today: LocalDate = DateTimeUtils.today()): CountSummary =
        RecurringDisplayReadScope.withSnapshot(database, tokens, sessions,
            AccountSessionCoordinator.Work.FACT_HISTORY_READ) { readSummaryInTransaction(expected, today, it) }

    private suspend fun trusted(habit: HabitEntity, today: LocalDate, scope: RecurringDisplayReadScope?,
        details: Boolean): Pair<CountSummary, List<com.dayforge.data.local.entity.CompletionEntity>>? {
        if (com.dayforge.data.local.CountTrustRuntime.forDatabase(database) == null) return null
        val access = scope?.borrow(database, tokens)?.first ?: requireNotNull(tokens.localCoreWriteAccess())
        val kernel = CountTrustKernel(database, tokens, sessions)
        val summary = (if (scope == null) kernel.summary(habit, today, access)
            else scope.countSummary(kernel, habit, today)) ?: return null
        val facts = if (details) requireNotNull(if (scope == null) kernel.details(habit, access)
            else scope.countDetails(kernel, habit)) else emptyList()
        if (scope == null) check(tokens.localCoreWriteAccess() == access) { "COUNT_SESSION_CHANGED" }
        else scope.borrow(database, tokens)
        return summary to facts
    }

    internal suspend fun readInTransaction(expected: HabitEntity, today: LocalDate): CountHistory {
        check(expected.habitType == HabitType.COUNTING) { "COUNT_ACTIVITY_CHANGED" }
        trusted(expected, today, null, true)?.let { return CountHistory.fromSummary(it.first, it.second) }
        val ticket = calculations.begin()
        val source = evidence.readInTransaction(expected, today)
        // This may be an enclosing writer. Its result is a candidate, never a committed cache.
        return CountHistory.fromSummary(project(expected, source, ticket).first, source.completions)
    }

    internal suspend fun readInTransaction(expected: HabitEntity, today: LocalDate,
        scope: RecurringDisplayReadScope): CountHistory {
        check(expected.habitType == HabitType.COUNTING) { "COUNT_ACTIVITY_CHANGED" }
        trusted(expected, today, scope, true)?.let { return CountHistory.fromSummary(it.first, it.second) }
        val ticket = calculations.begin()
        val source = evidence.readInTransaction(expected, today, scope)
        val prepared = project(expected, source, ticket)
        scope.rememberCalculation(prepared.second)
        return CountHistory.fromSummary(prepared.first, source.completions)
    }

    internal suspend fun readSummaryInTransaction(expected: HabitEntity, today: LocalDate,
        scope: RecurringDisplayReadScope): CountSummary {
        check(expected.habitType == HabitType.COUNTING) { "COUNT_ACTIVITY_CHANGED" }
        trusted(expected, today, scope, false)?.let { return it.first }
        val ticket = calculations.begin()
        val source = evidence.readInTransaction(expected, today, scope)
        val prepared = project(expected, source, ticket)
        scope.rememberCalculation(prepared.second)
        return prepared.first
    }

    internal suspend fun readSummaryInTransaction(expected: HabitEntity, today: LocalDate): CountSummary {
        check(expected.habitType == HabitType.COUNTING) { "COUNT_ACTIVITY_CHANGED" }
        trusted(expected, today, null, false)?.let { return it.first }
        val ticket = calculations.begin()
        return project(expected, evidence.readInTransaction(expected, today), ticket).first
    }

    private fun project(habit: HabitEntity, source: RecurringCompletionEvidence,
        ticket: CountCalculationReuse.Ticket): Pair<CountSummary, CountCalculationReuse.Candidate> {
        val identity = requireNotNull(source.countIdentity)
        val candidate = calculations.prepare(ticket, CountCalculationReuse.Partition(identity.session,
            identity.deviceId, habit.id, habit.uuid, source.roundHead),
            source.completions.map { CountCalculationBlocks.Fact(it.uuid, it.id, it.businessDate, it.value.toLong()) },
            source.policies, source.unknownDates, source.today)
        val todayPolicy = source.policies[source.today] ?: if (source.today in source.unknownDates) null
            else CountDayPolicy(habit.targetValue, habit.isCountdown)
        return candidate.result.state.summary(source.today, todayPolicy, source.roundHead, identity) to candidate
    }
}
