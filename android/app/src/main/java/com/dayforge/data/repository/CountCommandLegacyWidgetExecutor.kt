package com.dayforge.data.repository

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.Instant
import java.time.ZoneId

/** Mandatory primary T0/T1 for the existing Widget-v4 body. Native clock/last-target selection
 * is supplied only by that original private body, before its first DML. Full OLD/NEW effects
 * are checked before and after command stage, followed by fresh authority and actual END.
 */
internal class CountCommandLegacyWidgetExecutor(private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator) {
    private val commands = CountCommandStore(database, tokens, sessions)
    internal data class Selection(val habit: HabitEntity, val at: Instant, val zone: ZoneId,
        val target: CompletionEntity?, val added: Boolean, val quantity: Long)
    internal fun interface Original {
        suspend fun execute(around: suspend (Selection, suspend () -> LegacyWidgetFactResult) -> LegacyWidgetFactResult): LegacyWidgetFactResult
    }

    suspend fun begin(habit: HabitEntity, claim: LegacyWidgetFactClaim, commandId: String): Pair<CountCommandRecords.Intent, CountCommandStore.ExecutionTicket> {
        CountCommandEntryBoundary.requireTopLevel(database)
        val access = requireNotNull(tokens.localCoreWriteAccess()) { "LEGACY_WIDGET_STALE_ACCOUNT" }
        val input = CountCommandLegacyWidgetInput.create(database, access, habit, claim, commandId)
        val registered = commands.register(input)
        require(!registered.alreadyRegistered) { "COMMAND_REQUIRES_RESOLUTION" }
        return input to requireNotNull(registered.ticket)
    }

    sealed interface Resolution {
        data class Historical(val receipt: CountCommandReceipt) : Resolution
        data class Ready(val input: CountCommandRecords.Intent, val ticket: CountCommandStore.ExecutionTicket) : Resolution
        data class Unresolved(val commandId: String, val reason: String) : Resolution
    }
    suspend fun recover(claim: LegacyWidgetFactClaim, commandId: String): Resolution =
        recoverInternal(claim, commandId, completeProof = false)

    suspend fun recoverAfterCompleteProof(claim: LegacyWidgetFactClaim, commandId: String): Resolution =
        recoverInternal(claim, commandId, completeProof = true)

    private suspend fun recoverInternal(claim: LegacyWidgetFactClaim, commandId: String, completeProof: Boolean): Resolution {
        CountCommandEntryBoundary.requireTopLevel(database)
        val reference = commands.locateLegacyWidget(commandId, claim)
            ?: return Resolution.Unresolved(commandId, "NO_COMPLETE_PERSISTENT_INTENT")
        val inspected = if (completeProof) commands.recoverAfterCompleteProof(reference) else commands.recover(reference)
        return when (val recovered = inspected) {
            is CountCommandStore.Recovery.Committed -> Resolution.Historical(CountCommandReceipt(recovered.value))
            is CountCommandStore.Recovery.Unresolved -> Resolution.Unresolved(commandId, recovered.reason)
            is CountCommandStore.Recovery.Ready -> try {
                require(CountCommandLegacyWidgetInput.matches(recovered.intent) &&
                    CountCommandLegacyWidgetInput.claim(recovered.intent.body().getValue("input") as kotlinx.serialization.json.JsonObject) == claim)
                Resolution.Ready(recovered.intent, recovered.ticket)
            } catch (failure: Throwable) { commands.releaseUnadmitted(recovered.ticket); throw failure }
        }
    }

    suspend fun release(ticket: CountCommandStore.ExecutionTicket) = commands.releaseUnadmitted(ticket)

    suspend fun run(intent: CountCommandRecords.Intent, ticket: CountCommandStore.ExecutionTicket,
        original: Original): LegacyWidgetFactResult = try {
        CountCommandEntryBoundary.requireTopLevel(database)
        require(CountCommandLegacyWidgetInput.matches(intent))
        val claim = CountCommandLegacyWidgetInput.claim(intent.body().getValue("input").let { it as kotlinx.serialization.json.JsonObject })
        val done = sessions.exclusive(AccountSessionCoordinator.Work.NEXT_WRITE) {
            val access = requireNotNull(tokens.localCoreWriteAccess()) { "LEGACY_WIDGET_STALE_ACCOUNT" }
            require(access.session == claim.identity.session() && access.capturedDeviceId == claim.identity.deviceId)
            commands.producerTransaction(ticket, access) { participant ->
                require(participant.frozenIntent.text() == intent.text())
                var frame: CountCommandLegacyPlan? = null
                try {
                    val result = original.execute { selected, mutation ->
                        check(frame == null) { "COMMAND_WIDGET_SELECTION_REUSED" }
                        require(selected.habit.id == claim.identity.habitId && selected.habit.uuid == claim.identity.habitUuid &&
                            SyncV2Mapper.planNode(selected.habit) == claim.identity.plan) { "LEGACY_WIDGET_STALE_DISPLAY" }
                        val frozen = CountCommandLegacyPlan.captureWidget(database, intent, selected.at, selected.zone,
                            selected.target, selected.added)
                        frame = frozen
                        // Freeze the original no-effect predicate BEFORE running business.
                        // A callback's returned added flag never defines expected writes.
                        val changes = if (selected.added) !selected.habit.isCountdown || selected.quantity < selected.habit.targetValue
                            else selected.target != null
                        participant.beginBusiness()
                        CountCommandLegacyExecutor.withWidgetAllocation(database, frozen) {
                            val value = mutation()
                            require(value.added == (selected.added && changes)) { "COMMAND_WIDGET_RESULT_CHANGED" }
                            if (!changes) frozen.noEffect(if (selected.added) CountCommandRecords.NoEffectReason.COUNTDOWN_COMPLETE
                                else CountCommandRecords.NoEffectReason.NO_CURRENT_COUNT)
                            value
                        }
                    }
                    val actual = requireNotNull(frame) { "COMMAND_WIDGET_SELECTION_REQUIRED" }
                    participant.stage(actual.proven())
                    actual.verifyAfterStage()
                    val candidate = participant.verifyBeforeEnd()
                    check(tokens.localCoreWriteAccess() == access) { "LEGACY_WIDGET_STALE_ACCOUNT" }
                    CountCommandStore.ProducerValue(result, candidate)
                } finally { frame?.close() }
            }
        }
        val receipt = CountCommandReceipt(requireNotNull(done.command))
        done.result.copy(result = done.result.result.copy(command = receipt))
    } finally { release(ticket) }
}
