@file:kotlinx.serialization.UseSerializers(
    com.dayforge.domain.model.ContractStringSerializer::class,
    com.dayforge.domain.model.ContractLongSerializer::class
)

package com.dayforge.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.dayforge.data.api.decodeSyncReply
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.CheckInResult
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.domain.service.ScheduleValidator
import com.dayforge.domain.service.StructuralEditGuard
import com.dayforge.reminder.HabitReminderScheduler
import com.dayforge.widget.WidgetRefreshScheduler
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A rendered v4 fact, not credentials. A callback never upgrades it to the current account/day. */
@Serializable
@ConsistentCopyVisibility
data class LegacyWidgetFactClaim internal constructor(
    val identity: LegacyWidgetRestartClaim,
    val date: String,
    val timezone: String,
    val completionUuid: String?,
    val todayQuantity: Long,
    val action: String? = null
) {
    fun encode(): String = Json.encodeToString(this)
    fun forAction(action: String): LegacyWidgetFactClaim {
        require(this.action == null || this.action == action) { "LEGACY_WIDGET_ACTION_CHANGED" }
        require(action in setOf("toggle", "increment", "decrement"))
        return copy(action = action)
    }
    internal fun validate() {
        LegacyWidgetRestartClaim.decode(identity.encode())
        require(LocalDate.parse(date).toString() == date)
        require(timezone in ZoneId.getAvailableZoneIds())
        require(todayQuantity >= 0 && (completionUuid == null || isContractUuid(completionUuid)))
        require(action == null || action in setOf("toggle", "increment", "decrement"))
    }
    companion object {
        fun decode(value: String): LegacyWidgetFactClaim {
            require(value.length <= 65_536)
            return decodeSyncReply(value.toByteArray(Charsets.UTF_8), 65_536, serializer(), {}).also { it.validate() }
        }
    }
}

data class LegacyWidgetFactResult(val habit: HabitEntity, val result: CheckInResult.Success, val added: Boolean,
    val identity: LegacyWidgetRestartClaim)
data class LegacyWidgetGoalDisplay(val identity: LegacyWidgetRestartClaim, val habit: HabitEntity, val progress: Int)

/** Only widget-origin v4 operations use this boundary; normal app and typed writers are unchanged. */
@Singleton
class LegacyWidgetFactWorkflow @Inject constructor(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val verifier: LegacyWidgetIdentityVerifier,
    private val habits: HabitRepository,
    private val structural: StructuralEditGuard
) {
    internal val accessChanges get() = tokens.factAccessChanges

    suspend fun capture(identity: LegacyWidgetRestartClaim): LegacyWidgetFactClaim = sessions.exclusive {
        database.withTransaction {
            val habit = verifier.requireIdentity(identity)
            require(habit.habitType in setOf(HabitType.CHECK_IN, HabitType.COUNTING))
            val zone = ZoneId.systemDefault()
            val date = LocalDate.now(zone)
            val rows = database.completionDao().getCompletionsInRange(habit.id, date, date.plusDays(1))
            LegacyWidgetFactClaim(identity, date.toString(), zone.id, rows.maxByOrNull { it.id }?.uuid,
                rows.sumOf { it.value.toLong() })
        }
    }

    suspend fun perform(context: Context, claim: LegacyWidgetFactClaim): LegacyWidgetFactResult {
        claim.validate()
        requireNotNull(claim.action) { "LEGACY_WIDGET_ACTION_REQUIRED" }
        // This refresh can sync. It must run outside the non-reentrant account mutex and Room.
        val before = requireCurrent(claim.identity, requirePlan = true)
        val willAdd = claim.action == "increment" || (claim.action == "decrement" && before.isCountdown) ||
            (claim.action == "toggle" && claim.completionUuid == null)
        if (!before.isActive && willAdd) structural.requireAllowed()
        val result = sessions.exclusive {
            database.withTransaction { performInTransaction(claim) }
        }
        WidgetRefreshScheduler.request(context)
        return result
    }

    /** Stable-ID COUNT entrance. CHECK/toggle retains perform() and the original transaction
     * participant contract. This does not admit/retry a tap; the foreground entry owns that.
     */
    internal suspend fun performCountWithReceipt(context: Context, claim: LegacyWidgetFactClaim,
        commandId: String): LegacyWidgetFactResult {
        CountCommandEntryBoundary.requireTopLevel(database)
        claim.validate(); require(claim.action in setOf("increment", "decrement"))
        val before = requireCurrent(claim.identity, requirePlan = true)
        require(before.habitType == HabitType.COUNTING)
        val willAdd = claim.action == "increment" || before.isCountdown
        if (!before.isActive && willAdd) structural.requireAllowed()
        val executor = CountCommandLegacyWidgetExecutor(database, tokens, sessions)
        val (input, ticket) = executor.begin(before, claim, commandId)
        val result = executor.run(input, ticket, CountCommandLegacyWidgetExecutor.Original { around ->
            performInTransaction(claim, around)
        })
        WidgetRefreshScheduler.request(context)
        return result
    }

    /** Known entry only. Absence remains unresolved; an existing PREPARED is executed only
     * after the original Store's same-ID complete recovery issuer returns Ready.
     */
    internal suspend fun resumeCountCommand(context: Context, claim: LegacyWidgetFactClaim,
        commandId: String): WidgetCountCommandRecovery = resumeCountCommandInternal(context, claim, commandId, false)

    internal suspend fun resumeCountCommandAfterCompleteProof(context: Context, claim: LegacyWidgetFactClaim,
        commandId: String): WidgetCountCommandRecovery = resumeCountCommandInternal(context, claim, commandId, true)

    private suspend fun resumeCountCommandInternal(context: Context, claim: LegacyWidgetFactClaim,
        commandId: String, completeProof: Boolean): WidgetCountCommandRecovery {
        CountCommandEntryBoundary.requireTopLevel(database)
        val executor = CountCommandLegacyWidgetExecutor(database, tokens, sessions)
        val inspected = if (completeProof) executor.recoverAfterCompleteProof(claim, commandId) else executor.recover(claim, commandId)
        val resolved = if (!completeProof && inspected is CountCommandLegacyWidgetExecutor.Resolution.Unresolved &&
            inspected.reason == "ADVANCED_BUSINESS_REQUIRES_COMPLETE_NO_EFFECTS_PROOF")
            executor.recoverAfterCompleteProof(claim, commandId) else inspected
        return when (val recovered = resolved) {
            is CountCommandLegacyWidgetExecutor.Resolution.Historical -> WidgetCountCommandRecovery.Committed(recovered.receipt)
            is CountCommandLegacyWidgetExecutor.Resolution.Unresolved -> WidgetCountCommandRecovery.Unresolved(commandId, recovered.reason)
            is CountCommandLegacyWidgetExecutor.Resolution.Ready -> try {
                // Preserve the original pre-transaction profile/structure gate, but never
                // choose a different displayed target or replace the frozen input.
                val before = requireCurrent(claim.identity, requirePlan = true)
                require(before.habitType == HabitType.COUNTING)
                val willAdd = claim.action == "increment" || before.isCountdown
                if (!before.isActive && willAdd) structural.requireAllowed()
                val result = executor.run(recovered.input, recovered.ticket, CountCommandLegacyWidgetExecutor.Original { around ->
                    performInTransaction(claim, around)
                })
                WidgetRefreshScheduler.request(context)
                WidgetCountCommandRecovery.Committed(requireNotNull(result.result.command))
            } finally { executor.release(recovered.ticket) }
        }
    }

    private suspend fun performInTransaction(claim: LegacyWidgetFactClaim,
        around: suspend (CountCommandLegacyWidgetExecutor.Selection, suspend () -> LegacyWidgetFactResult) -> LegacyWidgetFactResult =
            { _, mutation -> mutation() }): LegacyWidgetFactResult {
        check(database.inTransaction())
        val habit = verifier.requireIdentity(claim.identity, permission = "facts.append")
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val date = now.atZone(zone).toLocalDate()
        check(claim.timezone == zone.id && claim.date == date.toString()) { "LEGACY_WIDGET_DAY_CHANGED" }
        check(ScheduleValidator.isCheckInAllowedToday(habit.schedule, habit.createdAt)) { "LEGACY_WIDGET_NOT_SCHEDULED" }
        val rows = database.completionDao().getCompletionsInRange(habit.id, date, date.plusDays(1))
        val last = rows.maxByOrNull { it.id }
        val quantity = rows.sumOf { it.value.toLong() }
        val added = when (claim.action) {
            "toggle" -> {
                require(habit.habitType == HabitType.CHECK_IN)
                check(last?.uuid == claim.completionUuid) { "LEGACY_WIDGET_FACT_CHANGED" }
                claim.completionUuid == null
            }
            "increment" -> { require(habit.habitType == HabitType.COUNTING); true }
            "decrement" -> {
                require(habit.habitType == HabitType.COUNTING)
                if (!habit.isCountdown) check(last?.uuid == claim.completionUuid) { "LEGACY_WIDGET_FACT_CHANGED" }
                habit.isCountdown
            }
            else -> error("LEGACY_WIDGET_ACTION_REQUIRED")
        }
        return around(CountCommandLegacyWidgetExecutor.Selection(habit, now, zone, last, added, quantity)) {
            val changed = if (added) {
                // Countdown's minus button records one completion; at zero it remains a no-op.
                if (habit.habitType == HabitType.COUNTING && habit.isCountdown && quantity >= habit.targetValue) false
                else {
                    if (!habit.isActive) verifier.requireIdentity(claim.identity, permission = "structure.write")
                    habits.insertLegacyWidgetCompletionInTransaction(habit, now, zone)
                    true
                }
            } else if (last != null) {
                habits.undoLegacyWidgetCompletionInTransaction(last)
                true
            } else false
            // The original owner is checked again before committing facts and their trigger outbox.
            val current = verifier.requireIdentity(claim.identity, requirePlan = false, permission = "facts.append")
            val progress = progress(current)
            val after = database.completionDao().getCompletionsInRange(habit.id, date, date.plusDays(1)).sumOf { it.value.toLong() }
            val completed = if (habit.habitType == HabitType.COUNTING) after >= habit.targetValue else after > 0
            LegacyWidgetFactResult(current, CheckInResult.Success(completed, progress,
                added && changed && habit.targetCycles != null && progress >= habit.targetCycles), added && changed,
                claim.identity.copy(plan = SyncV2Mapper.planNode(current)))
        }
    }
    /** Current read after a known command was resolved. This does not replay the old T1
     * clock or choose a new mutation target. Original ordinary perform() is unchanged. */
    internal suspend fun afterResolvedCount(identity: LegacyWidgetRestartClaim, action: String,
        receipt: CountCommandReceipt): LegacyWidgetFactResult = sessions.exclusive {
        require(action in setOf("increment", "decrement"))
        database.withTransaction {
            val habit = verifier.requireIdentity(identity, requirePlan = false)
            require(habit.habitType == HabitType.COUNTING)
            val zone = ZoneId.systemDefault(); val date = Instant.now().atZone(zone).toLocalDate()
            val quantity = database.completionDao().getCompletionsInRange(habit.id, date, date.plusDays(1)).sumOf { it.value.toLong() }
            val progress = progress(habit)
            val added = receipt.recorded && (action == "increment" || habit.isCountdown)
            verifier.requireIdentity(identity, requirePlan = false)
            LegacyWidgetFactResult(habit, CheckInResult.Success(quantity >= habit.targetValue, progress,
                added && habit.targetCycles?.let { progress >= it } == true, recorded = receipt.recorded, command = receipt),
                added, identity.copy(plan = SyncV2Mapper.planNode(habit)))
        }
    }

    /** Fresh read provenance for a resolved historical command; never a recovered writer
     * identity. The exact namespace/dataset reference was authenticated by the caller. */
    internal suspend fun recoveredDisplayIdentity(namespace: CountCommandRecords.Namespace,
        habitId: Long, habitUuid: String): LegacyWidgetRestartClaim = sessions.exclusive {
        database.withTransaction {
            val access = requireNotNull(tokens.localCoreWriteAccess()) { "LEGACY_WIDGET_STALE_ACCOUNT" }
            val storage = com.dayforge.data.local.LocalReplicaIdentity.readInTransaction(database)
            require(access.session.authentication.userId == namespace.account &&
                access.session.serverInstanceId == namespace.server && access.session.syncEpoch == namespace.epoch &&
                access.capturedDeviceId == namespace.device && storage == namespace.storage) { "COMMAND_CURRENT_NAMESPACE_ISOLATED" }
            val habit = requireNotNull(database.habitDao().getVisibleHabitById(habitId)) { "LEGACY_WIDGET_NOT_FOUND" }
            require(habit.uuid == habitUuid && habit.appearance == null && habit.habitType == HabitType.COUNTING) { "LEGACY_WIDGET_HABIT_CHANGED" }
            LegacyWidgetRestartClaim.displayed(habit, access, storage).also { verifier.requireIdentity(it) }
        }
    }

    suspend fun requireCurrent(identity: LegacyWidgetRestartClaim, requirePlan: Boolean = false): HabitEntity = sessions.exclusive {
        database.withTransaction { verifier.requireIdentity(identity, requirePlan) }
    }

    /** Follow-ups may observe this operation's committed plan, but never capture another owner. */
    suspend fun publish(identity: LegacyWidgetRestartClaim, block: suspend (HabitEntity) -> Unit) = sessions.exclusive {
        val habit = database.withTransaction { verifier.requireIdentity(identity, requirePlan = false) }
        block(habit)
    }

    suspend fun goalDisplay(identity: LegacyWidgetRestartClaim): LegacyWidgetGoalDisplay = goalDisplayInternal(identity, null)

    internal suspend fun goalDisplayForCommand(identity: LegacyWidgetRestartClaim, command: CountCommandReceipt): LegacyWidgetGoalDisplay =
        goalDisplayInternal(identity, command)

    private suspend fun goalDisplayInternal(identity: LegacyWidgetRestartClaim, command: CountCommandReceipt?): LegacyWidgetGoalDisplay = sessions.exclusive {
        database.withTransaction {
            command?.let { CountCommandStore(database, tokens, sessions).requirePendingGoalInTransaction(
                it.committed.reference, identity.habitUuid, requireNotNull(it.factUuid)) }
            val habit = verifier.requireIdentity(identity)
            require(habit.habitType != HabitType.GOAL && habit.targetCycles != null)
            val progress = progress(habit)
            check(progress >= habit.targetCycles) { "LEGACY_WIDGET_GOAL_CHANGED" }
            LegacyWidgetGoalDisplay(identity.copy(plan = SyncV2Mapper.planNode(habit)), habit, progress)
        }
    }

    suspend fun applyGoal(context: Context, identity: LegacyWidgetRestartClaim, complete: Boolean) =
        applyGoalInternal(context, identity, complete, null)

    internal suspend fun applyGoalWithCommand(context: Context, identity: LegacyWidgetRestartClaim, complete: Boolean,
        command: CountCommandReceipt) = applyGoalInternal(context, identity, complete, command)

    private suspend fun applyGoalInternal(context: Context, identity: LegacyWidgetRestartClaim, complete: Boolean,
        command: CountCommandReceipt?) {
        requireCurrent(identity, requirePlan = true)
        structural.requireAllowed()
        val habit = sessions.exclusive {
            database.withTransaction {
                val current = verifier.requireIdentity(identity, permission = "structure.write")
                command?.let { CountCommandStore(database, tokens, sessions).requirePendingGoalInTransaction(
                    it.committed.reference, current.uuid, requireNotNull(it.factUuid)) }
                check(current.targetCycles != null && progress(current) >= current.targetCycles) { "LEGACY_WIDGET_GOAL_CHANGED" }
                if (complete) database.habitDao().updateIsActive(current.id, false)
                else if (current.failMode == FailMode.STRICT) database.habitDao().updateFailMode(current.id, FailMode.LOOSE)
                verifier.requireIdentity(identity, requirePlan = false, permission = "structure.write")
            }
        }
        if (complete && habit.bestTime != null) HabitReminderScheduler.cancelReminder(context, habit.id)
        WidgetRefreshScheduler.request(context)
    }

    internal suspend fun publishGoalForCommand(identity: LegacyWidgetRestartClaim, command: CountCommandReceipt,
        block: suspend () -> Unit) = sessions.exclusive {
        database.withTransaction {
            verifier.requireIdentity(identity, requirePlan = false)
            CountCommandStore(database, tokens, sessions).requirePendingGoalInTransaction(command.committed.reference,
                identity.habitUuid, requireNotNull(command.factUuid))
        }
        block()
    }

    private suspend fun progress(habit: HabitEntity): Int = when (habit.habitType) {
        HabitType.COUNTING -> database.completionDao().getTargetMetDayCount(habit.id, habit.targetValue)
        HabitType.TIMER -> database.timeLogDao().getTargetMetDayCount(habit.id, habit.targetValue * 60)
        else -> database.completionDao().getDistinctDayCount(habit.id)
    }
}
