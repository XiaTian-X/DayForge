package com.dayforge.data.repository

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.businessDate
import com.dayforge.data.local.toDisplayMillis
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CheckHistory
import com.dayforge.domain.model.CountHistory
import com.dayforge.domain.model.TimerHistory
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.domain.service.ActivityRateCalculator
import com.dayforge.util.DateTimeUtils
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Derived presentation is separate from the unchanged authority-bearing plan row. */
class RecurringHabitSnapshot internal constructor(
    val habit: HabitEntity,
    val authority: ObjectEditAuthority,
    val activityRate: Int,
    val countHistory: CountHistory? = null,
    val checkHistory: CheckHistory? = null,
    val timerHistory: TimerHistory? = null
)

/** The original edit identity remains separate from optional, transaction-local display evidence. */
data class CompletionActionSnapshot(
    val edit: ObjectEditSnapshot<HabitEntity>,
    val evidence: RecurringCompletionSnapshot? = null
)

class RecurringCompletionSnapshot internal constructor(
    val habit: HabitEntity,
    val authority: ObjectEditAuthority,
    val checkHistory: CheckHistory?,
    val countHistory: CountHistory?,
    val followUp: WidgetFactSnapshot
)

/** Stats and edit/action scope from one account/Room snapshot, never two independently fresh reads. */
@Singleton
class RecurringHabitReader @Inject constructor(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val editor: NextObjectEditor,
    private val counts: CountHistoryReader = CountHistoryReader(database, tokens, sessions),
    private val checks: CheckHistoryReader = CheckHistoryReader(database, tokens, sessions),
    private val timers: TimerHistoryReader = TimerHistoryReader(database, tokens, sessions)
) {
    /** Only used after obtaining the habit-action lock, never before waiting for that lock. */
    internal suspend fun prepareCompletion(id: Long): CompletionActionSnapshot =
        RecurringDisplayReadScope.withSnapshot(database, tokens, sessions,
            AccountSessionCoordinator.Work.FACT_HISTORY_READ) { scope ->
            val edit = editor.habitInTransaction(id, scope)
            CompletionActionSnapshot(edit, completionInTransaction(edit, scope))
        }

    /** Fresh post-COMMIT read; a new account, device, plan or round cannot replace the click's owner. */
    internal suspend fun readCompletionAfter(captured: CompletionActionSnapshot,
        allowActivation: Boolean, recordedCompletionId: Long? = null): RecurringCompletionSnapshot =
        RecurringDisplayReadScope.withSnapshot(database, tokens, sessions,
            AccountSessionCoordinator.Work.FACT_HISTORY_READ) { scope ->
            val original = requireNotNull(captured.edit.value) { "COMPLETION_ORIGINAL_REQUIRED" }
            val ticket = requireNotNull(captured.edit.authority) { "COMPLETION_AUTHORITY_REQUIRED" }
            check(ticket.completionDeviceCaptured) { "COMPLETION_DEVICE_NOT_CAPTURED" }
            val edit = editor.habitInTransaction(original.id, scope)
            val current = requireNotNull(edit.value) { "COMPLETION_ACTIVITY_REMOVED" }
            val authority = requireNotNull(edit.authority) { "COMPLETION_ACTIVITY_CHANGED" }
            check(authority.session == ticket.session && authority.capturedDeviceId == ticket.capturedDeviceId &&
                current.uuid == original.uuid && ticket.uuid == original.uuid && ticket.type == "plan_node") {
                "COMPLETION_SCOPE_CHANGED"
            }
            check(ticket.original == NextStructureMapper.writePlan(original)) { "COMPLETION_ORIGINAL_CHANGED" }
            val allowed = if (allowActivation && !original.isActive) original.copy(isActive = true) else original
            check(authority.original == ticket.original || authority.original == NextStructureMapper.writePlan(allowed)) {
                "COMPLETION_PLAN_CHANGED"
            }
            check((authority.rounds == null) == (ticket.rounds == null) &&
                roundHead(authority, original.uuid) == roundHead(ticket, original.uuid)) { "COMPLETION_ROUND_CHANGED" }
            completionInTransaction(edit, scope, recordedCompletionId)
        }

    private suspend fun completionInTransaction(edit: ObjectEditSnapshot<HabitEntity>,
        scope: RecurringDisplayReadScope, recordedCompletionId: Long? = null): RecurringCompletionSnapshot {
        val habit = requireNotNull(edit.value) { "COMPLETION_ACTIVITY_REMOVED" }
        check(habit.appearance != null && habit.completionPolicy == "recurring" &&
            habit.habitType in setOf(HabitType.CHECK_IN, HabitType.COUNTING)) { "COMPLETION_ACTIVITY_CHANGED" }
        val authority = requireNotNull(edit.authority)
        val zone = ZoneId.systemDefault()
        val today = Instant.now().atZone(zone).toLocalDate()
        val count = if (habit.habitType == HabitType.COUNTING) counts.readInTransaction(habit, today, scope) else null
        val checkHistory = if (habit.habitType == HabitType.CHECK_IN) checks.readInTransaction(habit, today, scope) else null
        val (access, rounds) = scope.borrow(database, tokens)
        // The card remains today's projection. Follow-ups belong to this exact committed fact,
        // including a commit spanning midnight, not a later fact that happens to be newest today.
        val source = recordedCompletionId?.let { id ->
            requireNotNull((count?.completions ?: requireNotNull(checkHistory).completions).singleOrNull { it.id == id }) {
                "COMPLETION_SOURCE_CHANGED"
            }.also { fact -> check(fact.habitUuid == habit.uuid && fact.oneTimeAction == null) { "COMPLETION_SOURCE_CHANGED" } }
        }
        val sourceDay = source?.let { LocalDate.parse(it.recordedLocalDate) } ?: today
        val sourceZone = source?.let { ZoneId.of(it.recordedTimezone) } ?: zone
        val sourceCount = count?.let {
            if (sourceDay == today) it else it.copy(today = sourceDay, todayPolicy = it.policies[sourceDay])
        }
        val sourceChecks = checkHistory?.let { if (sourceDay == today) it else it.copy(today = sourceDay) }
        val followUp = recurringFactSnapshot(habit, access, rounds, sourceCount, sourceChecks, sourceDay, sourceZone, source)
        return RecurringCompletionSnapshot(habit, authority, checkHistory, count, followUp)
    }

    private fun roundHead(authority: ObjectEditAuthority, uuid: String) = authority.rounds?.let {
        it.pendingRestarts[uuid]?.head ?: it.completionRounds.head(uuid) ?: it.pendingInitials[uuid]?.head
    }

    suspend fun read(expected: HabitEntity, today: LocalDate = DateTimeUtils.today()): RecurringHabitSnapshot =
        RecurringDisplayReadScope.withSnapshot(database, tokens, sessions) { scope ->
            val snapshot = editor.habitInTransaction(expected.id, scope)
            if (snapshot.value?.uuid == expected.uuid && snapshot.value != expected)
                throw HabitReadSnapshotChanged("RECURRING_READ_ACTIVITY_CHANGED")
            check(snapshot.value == expected && expected.appearance != null &&
                expected.completionPolicy == "recurring") { "RECURRING_READ_ACTIVITY_CHANGED" }
            val authority = requireNotNull(snapshot.authority)
            val countHistory = if (expected.habitType == HabitType.COUNTING) counts.readInTransaction(expected, today, scope) else null
            val checkHistory = if (expected.habitType == HabitType.CHECK_IN) checks.readInTransaction(expected, today, scope) else null
            val timerHistory = if (expected.habitType == HabitType.TIMER) timers.readInTransaction(expected, today, scope) else null
            val dates = when {
                // Activity tracks actual participation; a partial count is NOT a qualified day.
                countHistory != null -> countHistory.completions.map { it.businessDate }
                checkHistory != null -> checkHistory.qualifiedDates.toList()
                timerHistory != null -> timerHistory.qualifiedDates.toList()
                else -> error("RECURRING_READ_TYPE_INVALID")
            }
            RecurringHabitSnapshot(expected, authority,
                ActivityRateCalculator.calculate(expected.schedule, expected.createdAt, dates, today.toDisplayMillis()),
                countHistory, checkHistory, timerHistory)
        }
}
