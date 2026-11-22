package com.dayforge.data.repository

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CheckHistory
import com.dayforge.domain.model.CheckDisplayProjection
import com.dayforge.data.local.entity.CompletionEntity
import java.time.ZoneId
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.util.DateTimeUtils
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** CHECK and COUNT use identical account, original-source, birth and hidden-history proofs. */
@Singleton
class CheckHistoryReader @Inject constructor(private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator) {
    private val evidence = RecurringCompletionEvidenceReader(database, tokens, sessions)
    val changes = evidence.changes

    suspend fun read(expected: HabitEntity, today: LocalDate = DateTimeUtils.today()): CheckHistory {
        check(expected.habitType == HabitType.CHECK_IN) { "CHECK_ACTIVITY_CHANGED" }
        return project(evidence.read(expected, today))
    }

    internal suspend fun readInTransaction(expected: HabitEntity, today: LocalDate): CheckHistory {
        check(expected.habitType == HabitType.CHECK_IN) { "CHECK_ACTIVITY_CHANGED" }
        return project(evidence.readInTransaction(expected, today))
    }

    internal suspend fun readInTransaction(expected: HabitEntity, today: LocalDate,
        scope: RecurringDisplayReadScope): CheckHistory {
        check(expected.habitType == HabitType.CHECK_IN) { "CHECK_ACTIVITY_CHANGED" }
        return project(evidence.readInTransaction(expected, today, scope))
    }

    /** Finite display result; explicit detail history stays on read(), never a synthetic list. */
    suspend fun readProjection(expected: HabitEntity, today: LocalDate = DateTimeUtils.today(),
        zone: ZoneId = ZoneId.systemDefault(), cardAtMillis: Long? = null): CheckDisplayProjection =
        RecurringDisplayReadScope.withSnapshot(database, tokens, sessions,
            AccountSessionCoordinator.Work.FACT_HISTORY_READ) { scope ->
            readProjectionInTransaction(expected, today, zone, scope, cardAtMillis)
        }

    internal suspend fun readProjectionInTransaction(expected: HabitEntity, today: LocalDate, zone: ZoneId,
        scope: RecurringDisplayReadScope, cardAtMillis: Long? = null): CheckDisplayProjection {
        check(expected.habitType == HabitType.CHECK_IN) { "CHECK_ACTIVITY_CHANGED" }
        val kernel = CountTrustKernel(database, tokens, sessions)
        val compact = scope.checkProjection(kernel, expected, today, zone, cardAtMillis)
        scope.borrow(database, tokens)
        return compact ?: projectCheckDisplay(expected, readInTransaction(expected, today, scope), zone, cardAtMillis)
    }

    internal data class ProjectedSelection(val projection: CheckDisplayProjection, val fact: CompletionEntity?)

    /** One exact current-round action/follow-up source, including an explicit commit across midnight. */
    internal suspend fun selectProjectionInTransaction(expected: HabitEntity, today: LocalDate, zone: ZoneId,
        scope: RecurringDisplayReadScope? = null, sourceId: Long? = null,
        sourceUuid: String? = null): ProjectedSelection {
        check(expected.habitType == HabitType.CHECK_IN && (sourceId == null || sourceUuid == null))
        val access = scope?.borrow(database, tokens)?.first ?: requireNotNull(tokens.localCoreWriteAccess())
        val kernel = CountTrustKernel(database, tokens, sessions)
        val projection = if (scope == null) kernel.checkProjection(expected, today, zone, access)
            else scope.checkProjection(kernel, expected, today, zone)
        if (projection == null) {
            val history = if (scope == null) readInTransaction(expected, today) else readInTransaction(expected, today, scope)
            val fact = when {
                sourceId != null -> history.completions.singleOrNull { it.id == sourceId }
                sourceUuid != null -> history.completions.singleOrNull { it.uuid == sourceUuid }
                else -> history.todayCompletions.maxByOrNull { it.id }
            }
            check((sourceId == null && sourceUuid == null) || fact != null) { "COMPLETION_SOURCE_CHANGED" }
            return ProjectedSelection(projectCheckDisplay(expected, history, zone), fact)
        }
        val id = if (sourceUuid == null) sourceId ?: projection.todayLatestCompletionId else null
        val fact = if (id != null || sourceUuid != null) {
            if (scope == null) kernel.exactFact(expected, access, id, sourceUuid)
            else scope.countFact(kernel, expected, id, sourceUuid)
        } else null
        check((id == null && sourceUuid == null) || fact != null) { "COMPLETION_SOURCE_CHANGED" }
        if (sourceId == null && sourceUuid == null && fact != null)
            check(fact.recordedLocalDate == today.toString()) { "COMPLETION_SOURCE_CHANGED" }
        if (scope == null) check(tokens.localCoreWriteAccess() == access) { "CHECK_SESSION_CHANGED" }
        else scope.borrow(database, tokens)
        return ProjectedSelection(projection, fact)
    }

    private fun project(source: RecurringCompletionEvidence) =
        CheckHistory(source.today, source.completions, source.roundHead, source.checkIdentity)
}
