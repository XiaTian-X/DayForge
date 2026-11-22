package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.service.AccountIconController
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray

/** An in-memory edit ticket, never a saved-state credential or a caller-selected owner. */
abstract class ObjectAppearanceAuthority internal constructor(internal val session: LocalDataSession)

class ObjectEditAuthority internal constructor(
    session: LocalDataSession,
    internal val uuid: String,
    internal val type: String,
    internal val original: JsonObject,
    internal val rounds: NextRoundWriteScope? = null,
    internal val goalChildUuids: List<String>? = null,
    internal val capturedDeviceId: String? = null,
    internal val completionDeviceCaptured: Boolean = false
) : ObjectAppearanceAuthority(session)

data class ObjectEditSnapshot<T>(val value: T?, val authority: ObjectEditAuthority?)

/**
 * Formal typed editors use the existing v5 producer. Legacy editors remain on their original
 * transaction path until the coordinated switch. There is no protocol inference from an icon.
 */
@Singleton
class NextObjectEditor @Inject constructor(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val icons: AccountIconController
) {
    private val producer = NextCoreLocalIntentStore(database, tokens, sessions)
    private val commands = CountCommandStore(database, tokens, sessions)
    private val legacyCommands = CountCommandLegacyExecutor(database, tokens, sessions, commands)
    private val independentMetrics by lazy { IndependentMetricExecutor(database, tokens, sessions, commands, producer, this) }
    internal suspend fun beginIndependentValue(metric: MetricEntity, authority: ObjectEditAuthority?, value: Double,
        note: String, at: Long): IndependentMetricAttempt {
        val actual = if (metric.appearance != null) authority ?: requireNotNull(this.metric(metric.id).authority) { "OBJECT_WRITE_TICKET_REQUIRED" } else authority
        return independentMetrics.capture(metric, actual, value, note, at)
    }
    internal suspend fun executeIndependentValue(attempt: IndependentMetricAttempt) = independentMetrics.execute(attempt)
    internal suspend fun resolveIndependentValue(reference: CountCommandReference, continueIfProvenUnexecuted: Boolean) =
        independentMetrics.resolve(reference, continueIfProvenUnexecuted)
    internal suspend fun resolveIndependentAttempt(attempt: IndependentMetricAttempt, continueIfProvenUnexecuted: Boolean) =
        independentMetrics.resolveAttempt(attempt, continueIfProvenUnexecuted)
    internal suspend fun acknowledgeIndependentValue(saved: IndependentMetricSaved, isConsumerCurrent: () -> Boolean) =
        independentMetrics.acknowledge(saved, isConsumerCurrent)

    internal suspend fun prepareIndependentEnd(reference: CountCommandReference): IndependentMetricEnd? = independentMetrics.prepareEnd(reference)
    internal suspend fun endIndependentValue(plan: IndependentMetricEnd, isConsumerCurrent: () -> Boolean): IndependentMetricEnded =
        independentMetrics.end(plan, isConsumerCurrent)
    internal suspend fun acknowledgeIndependentEnd(ended: IndependentMetricEnded, isConsumerCurrent: () -> Boolean) =
        independentMetrics.acknowledgeEnd(ended, isConsumerCurrent)

    private val legacyMetrics = CountCommandLegacyMetricExecutor(database, tokens, sessions, commands)
    internal suspend fun locateMetricChild(parent: CountCommandReceipt): CountCommandStore.Located? =
        commands.linkedChild(parent.committed.reference)

    internal suspend fun beginLegacyMetricChild(parent: CountCommandReceipt, claim: HabitPreferenceClaim,
        preferences: HabitPreferenceRepository, habit: HabitEntity, metrics: List<MetricEntity>, values: List<MetricValueDraft>, recordedAt: Long) =
        legacyMetrics.begin(parent, claim, preferences, habit, metrics, values, recordedAt)
    internal suspend fun <T> mutateLegacyMetrics(attempt: CountCommandLegacyMetricExecutor.Attempt,
        preferences: HabitPreferenceRepository, original: suspend () -> T): T = legacyMetrics.run(attempt, preferences, original)
    internal suspend fun releaseLegacyMetrics(attempt: CountCommandLegacyMetricExecutor.Attempt) = legacyMetrics.release(attempt)


    internal suspend fun beginLegacyCountAdd(expected: HabitEntity, identity: com.dayforge.domain.model.CountActionIdentity?,
        at: java.time.Instant, zone: java.time.ZoneId, quantity: Int) = legacyCommands.add(expected, identity, at, zone, quantity)
    internal suspend fun beginLegacyCountUndo(expected: HabitEntity, identity: com.dayforge.domain.model.CountActionIdentity?,
        target: com.dayforge.data.local.entity.CompletionEntity) = legacyCommands.undo(expected, identity, target)
    internal suspend fun <T> mutateLegacyCount(command: CountCommandLegacyExecutor.Attempt, expected: HabitEntity,
        original: suspend (HabitEntity) -> T): T = legacyCommands.run(command, expected, original)
    internal suspend fun releaseLegacyCount(command: CountCommandLegacyExecutor.Attempt) = legacyCommands.release(command)
    internal suspend fun recordLegacyNoCurrentCount(expected: HabitEntity, identity: com.dayforge.domain.model.CountActionIdentity,
        selection: LegacyCountNoTarget) = legacyCommands.noCurrent(expected, identity, selection)



    internal sealed interface CountAttempt {
        val input: CountCommandRecords.Intent
        val authority: ObjectEditAuthority
        fun committedReceipt(): CountCommandReceipt
    }
    private class IssuedCountAttempt(val owner: NextObjectEditor, override val input: CountCommandRecords.Intent,
        override val authority: ObjectEditAuthority, val ticket: CountCommandStore.ExecutionTicket) : CountAttempt {
        var committed: CountCommandStore.Committed? = null
        override fun committedReceipt(): CountCommandReceipt = CountCommandReceipt(requireNotNull(committed) { "COMMAND_END_NOT_CONFIRMED" })
    }

    /** One T0, including initial empty-root account binding when necessary. No current UI
     * result is read here and no token/authority is serialized into the immutable input.
     */
    internal suspend fun beginCountAdd(expected: HabitEntity, authority: ObjectEditAuthority?,
        occurredAt: java.time.Instant, zone: java.time.ZoneId, quantity: Int,
        preparedInput: CountCommandRecords.Intent? = null, widgetClaim: WidgetFactClaim? = null,
        commandId: String? = null): CountAttempt {
        CountCommandEntryBoundary.requireTopLevel(database)
        require(commandId == null || preparedInput == null && widgetClaim != null && com.dayforge.domain.model.isContractUuid(commandId)) { "COMMAND_WIDGET_ID_REQUIRED" }
        val original = authority ?: requireNotNull(habit(expected.id).authority)
        val input = preparedInput ?: CountCommandInputs.add(database, commandId ?: java.util.UUID.randomUUID().toString(), expected,
            original, occurredAt, zone, quantity, widgetClaim)
        require(input.action == CountCommandRecords.Action.COUNT_ADD)
        CountCommandInputs.requireOriginalSelection(input, original)
        val registered = commands.register(input)
        require(!registered.alreadyRegistered) { "COMMAND_REQUIRES_RESOLUTION" }
        return IssuedCountAttempt(this, input, original, requireNotNull(registered.ticket))
    }

    internal suspend fun beginCountUndo(expected: HabitEntity, authority: ObjectEditAuthority?, target: com.dayforge.data.local.entity.CompletionEntity,
        preparedInput: CountCommandRecords.Intent? = null, widgetClaim: WidgetFactClaim? = null,
        commandId: String? = null): CountAttempt {
        CountCommandEntryBoundary.requireTopLevel(database)
        require(commandId == null || preparedInput == null && widgetClaim != null && com.dayforge.domain.model.isContractUuid(commandId)) { "COMMAND_WIDGET_ID_REQUIRED" }
        val original = authority ?: requireNotNull(habit(expected.id).authority)
        val input = preparedInput ?: CountCommandInputs.undo(database, commandId ?: java.util.UUID.randomUUID().toString(), expected, original, target, widgetClaim)
        require(input.action == CountCommandRecords.Action.COUNT_UNDO)
        CountCommandInputs.requireOriginalSelection(input, original)
        require(CountCommandRecords.canonical(input.body().getValue("input").jsonObject.getValue("target_native")) ==
            CountCommandRecords.canonical(CountCommandProducerPlan.frozenCompletion(target))) { "COMMAND_UNDO_TARGET_CHANGED" }
        val registered = commands.register(input)
        require(!registered.alreadyRegistered) { "COMMAND_REQUIRES_RESOLUTION" }
        return IssuedCountAttempt(this, input, original, requireNotNull(registered.ticket))
    }


    internal suspend fun locateWidgetCommand(commandId: String, claim: WidgetFactClaim, action: String): CountCommandReference? =
        commands.locateTypedWidget(commandId, claim, action)?.let(CountCommandReference::from)

    internal sealed interface RecoveryAttempt {
        class Historical(val receipt: CountCommandReceipt) : RecoveryAttempt
        class Execute(val command: CountAttempt) : RecoveryAttempt
        class LegacyExecute(val command: CountCommandLegacyExecutor.Attempt) : RecoveryAttempt
        class LegacyMetricExecute(val command: CountCommandLegacyMetricExecutor.Attempt) : RecoveryAttempt
        data class Unresolved(val reason: String) : RecoveryAttempt
    }

    /** Re-establish original domain inputs using fresh current read authorities. The frozen
     * Intent is never treated as a credential and no old in-memory authority is restored.
     * Field/round mismatches preserve the original rejection, not a retargeted new action.
     */
    internal suspend fun recoverCommand(reference: CountCommandReference,
        actions: Set<CountCommandRecords.Action>, legacyPreferences: HabitPreferenceRepository? = null): RecoveryAttempt =
        recoverCommandInternal(reference, actions, legacyPreferences, completeProof = false)

    internal suspend fun recoverCommandAfterCompleteProof(reference: CountCommandReference,
        actions: Set<CountCommandRecords.Action>, legacyPreferences: HabitPreferenceRepository? = null): RecoveryAttempt =
        recoverCommandInternal(reference, actions, legacyPreferences, completeProof = true)

    internal suspend fun recoverCountCommandAfterReauthentication(reference: CountCommandReference): RecoveryAttempt =
        recoverCommandInternal(reference, setOf(CountCommandRecords.Action.COUNT_ADD, CountCommandRecords.Action.COUNT_UNDO),
            null, completeProof = true, reauthenticate = true)

    private suspend fun recoverCommandInternal(reference: CountCommandReference,
        actions: Set<CountCommandRecords.Action>, legacyPreferences: HabitPreferenceRepository?, completeProof: Boolean,
        reauthenticate: Boolean = false): RecoveryAttempt {
        CountCommandEntryBoundary.requireTopLevel(database)
        val inspected = if (reauthenticate) commands.recoverAfterReauthentication(reference.native())
            else if (completeProof) commands.recoverAfterCompleteProof(reference.native()) else commands.recover(reference.native())
        return when (val recovery = inspected) {
            is CountCommandStore.Recovery.Committed -> RecoveryAttempt.Historical(CountCommandReceipt(recovery.value))
            is CountCommandStore.Recovery.Unresolved -> RecoveryAttempt.Unresolved(recovery.reason)
            is CountCommandStore.Recovery.Ready -> {
                val frozen = recovery.intent
                if (frozen.action !in actions) {
                    commands.releaseUnadmitted(recovery.ticket)
                    return RecoveryAttempt.Unresolved("COMMAND_REQUIRES_ORIGINAL_DOMAIN_HANDLER")
                }
                if (frozen.capture == CountCommandRecords.Capture.VERIFIED_NO_EFFECT)
                    return RecoveryAttempt.Historical(CountCommandReceipt(commands.completeCapturedNoEffect(recovery.ticket)))
                if (CountCommandLegacyWidgetInput.matches(frozen)) {
                    commands.releaseUnadmitted(recovery.ticket)
                    return RecoveryAttempt.Unresolved("COMMAND_REQUIRES_ORIGINAL_WIDGET_HANDLER")
                }
                if (CountCommandLegacyInput.isLegacy(frozen)) {
                    if (frozen.action == CountCommandRecords.Action.LINKED_METRICS) {
                        if (legacyPreferences == null) {
                            commands.releaseUnadmitted(recovery.ticket)
                            return RecoveryAttempt.Unresolved("COMMAND_LEGACY_METRIC_PREFERENCE_REQUIRED")
                        }
                        return RecoveryAttempt.LegacyMetricExecute(legacyMetrics.recovered(recovery, legacyPreferences))
                    }
                    return RecoveryAttempt.LegacyExecute(legacyCommands.recovered(recovery))
                }
                try {
                    val input = frozen.body().getValue("input").jsonObject
                    val authority = if (frozen.action == CountCommandRecords.Action.LINKED_METRICS) {
                        val first = input.getValue("metrics").jsonArray.first().jsonObject
                        val selected = metric(CountCommandRecords.integer(first, "metric_id"))
                        require(selected.value?.uuid == CountCommandRecords.uuid(first, "metric_uuid") &&
                            selected.authority?.original == first.getValue("definition")) { "COMMAND_METRIC_DEFINITION_CHANGED" }
                        requireNotNull(selected.authority)
                    } else {
                        val selected = habit(CountCommandRecords.integer(input, "habit_id"))
                        require(selected.value?.uuid == CountCommandRecords.uuid(input, "habit_uuid") &&
                            selected.value?.habitType == com.dayforge.data.model.HabitType.COUNTING &&
                            selected.value?.completionPolicy == "recurring") { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
                        requireNotNull(selected.authority).also { CountCommandInputs.requireOriginalSelection(frozen, it) }
                    }
                    RecoveryAttempt.Execute(IssuedCountAttempt(this, frozen, authority, recovery.ticket))
                } catch (error: Throwable) {
                    commands.releaseUnadmitted(recovery.ticket)
                    throw error
                }
            }
        }
    }

    internal suspend fun recoverMetricParent(command: CountAttempt): CountCommandReceipt {
        val issued = command as? IssuedCountAttempt ?: error("COMMAND_FOREIGN_ATTEMPT")
        require(issued.owner === this && issued.input.action == CountCommandRecords.Action.LINKED_METRICS)
        return CountCommandReceipt(commands.recoverParent(issued.ticket.reference))
    }

    internal suspend fun releaseUnexecuted(command: CountAttempt) {
        val issued = command as? IssuedCountAttempt ?: error("COMMAND_FOREIGN_ATTEMPT")
        require(issued.owner === this)
        commands.releaseUnadmitted(issued.ticket)
    }

    internal suspend fun recordNoCurrentCount(expected: HabitEntity, authority: ObjectEditAuthority,
        observed: com.dayforge.domain.model.CountSummary): CountCommandReceipt {
        CountCommandEntryBoundary.requireTopLevel(database)
        val input = CountCommandInputs.noCurrentCount(database, java.util.UUID.randomUUID().toString(), expected, authority, observed)
        val registered = commands.register(input)
        require(!registered.alreadyRegistered) { "COMMAND_REQUIRES_RESOLUTION" }
        return CountCommandReceipt(commands.completeCapturedNoEffect(requireNotNull(registered.ticket)))
    }

    internal suspend fun recordExplicitMissingCount(requestedId: Long,
        identity: com.dayforge.domain.model.CountActionIdentity): CountCommandReceipt {
        CountCommandEntryBoundary.requireTopLevel(database)
        val input = CountCommandInputs.explicitTargetAbsent(database, java.util.UUID.randomUUID().toString(), requestedId, identity)
        val registered = commands.register(input)
        require(!registered.alreadyRegistered) { "COMMAND_REQUIRES_RESOLUTION" }
        return CountCommandReceipt(commands.completeCapturedNoEffect(requireNotNull(registered.ticket)))
    }

    internal suspend fun beginMetricChild(parent: CountCommandReceipt, authority: ObjectEditAuthority,
        habit: HabitEntity, source: WidgetFactClaim, values: List<MetricValueDraft>, expected: List<MetricEntity>,
        links: List<com.dayforge.data.local.entity.HabitMetricLinkEntity>, recordedAt: Long, timezone: String): CountAttempt {
        CountCommandEntryBoundary.requireTopLevel(database)
        val access = requireNotNull(tokens.localCoreWriteAccess()) { "COMMAND_FRESH_AUTHORITY_REQUIRED" }
        require(access.session == authority.session)
        val input = CountCommandInputs.metrics(database, java.util.UUID.randomUUID().toString(), parent, authority,
            access.capturedDeviceId, habit, source, values, expected, links, recordedAt, timezone)
        val registered = commands.registerChild(parent.committed.reference, input)
        require(!registered.alreadyRegistered) { "COMMAND_REQUIRES_RESOLUTION" }
        return IssuedCountAttempt(this, input, authority, requireNotNull(registered.ticket))
    }

    internal suspend fun acknowledgeExplicitFollowUpSkip(parent: CountCommandReceipt, factUuid: String, habitUuid: String) =
        commands.markFollowUpClosed(parent.committed.reference, factUuid, habitUuid)

    internal suspend fun acknowledgeNoRequiredFollowUp(parent: CountCommandReceipt, factUuid: String, habitUuid: String) =
        commands.markFollowUpClosed(parent.committed.reference, factUuid, habitUuid)

    internal suspend fun historicalCountResult(reference: CountCommandReference, habitUuid: String): CountCommandReceipt {
        val value = commands.historicalCountResult(reference.native(), habitUuid)
        require(value.outcome.action in setOf(CountCommandRecords.Action.COUNT_ADD, CountCommandRecords.Action.COUNT_UNDO))
        return CountCommandReceipt(value)
    }

    internal suspend fun acknowledgeCountResultDelivery(receipt: CountCommandReceipt) {
        require(receipt.committed.outcome.action in setOf(CountCommandRecords.Action.COUNT_ADD, CountCommandRecords.Action.COUNT_UNDO) &&
            receipt.committed.outcome.followUp == CountCommandOutcome.FollowUp.NONE) { "COMMAND_REQUIRED_FOLLOWUP_NOT_DELIVERED" }
        commands.markDelivered(receipt.committed.reference)
    }

    internal suspend fun requirePendingGoalInTransaction(command: CountCommandReceipt, habitUuid: String) =
        commands.requirePendingGoalInTransaction(command.committed.reference, habitUuid, requireNotNull(command.factUuid))

    internal suspend fun acknowledgeMetricDelivery(parent: CountCommandReceipt, child: CountCommandReceipt) =
        commands.markMetricDelivered(parent.committed.reference, child.committed.reference)

    internal suspend fun deliverFormalMetricResult(parent: CountCommandReceipt, child: CountCommandReceipt,
        snapshot: WidgetFactSnapshot, reader: WidgetFactReader, isConsumerCurrent: () -> Boolean) =
        commands.deliverFormalMetricResult(parent, child, snapshot, reader, isConsumerCurrent)

    internal suspend fun habit(id: Long): ObjectEditSnapshot<HabitEntity> = sessions.exclusive(AccountSessionCoordinator.Work.HABIT_EDIT_READ) {
        database.withTransaction { habitInTransaction(id) }
    }

    /** Read participant only: caller owns the account lock and Room snapshot. */
    internal suspend fun habitInTransaction(id: Long, displayScope: RecurringDisplayReadScope? = null): ObjectEditSnapshot<HabitEntity> {
        check(database.inTransaction()) { "OBJECT_EDIT_TRANSACTION_REQUIRED" }
        val borrowed = displayScope?.borrow(database, tokens)
        val access = borrowed?.first ?: tokens.localCoreWriteAccess()
        val row = database.habitDao().getVisibleHabitById(id)
        val ticket = row?.takeIf { it.appearance != null }?.let {
            val rounds = if (borrowed != null) borrowed.second else producer.captureDisplayedRoundsInTransaction()
            ObjectEditAuthority(requireNotNull(access) { "OBJECT_EDIT_ACCESS_DENIED" }.session,
                it.uuid, "plan_node", NextStructureMapper.writePlan(it), rounds,
                if (rounds != null && it.habitType == com.dayforge.data.model.HabitType.GOAL)
                    database.habitDao().getChildrenByParentUuidOnce(it.uuid).map { child -> child.uuid }.sorted() else null,
                capturedDeviceId = access.capturedDeviceId, completionDeviceCaptured = true)
        }
        if (displayScope != null) displayScope.borrow(database, tokens)
        else check(tokens.localCoreWriteAccess() == access) { "OBJECT_EDIT_SESSION_CHANGED" }
        return ObjectEditSnapshot(row, ticket)
    }

    internal suspend fun metric(id: Long): ObjectEditSnapshot<MetricEntity> = metrics(listOf(id)).single()

    /** One displayed prompt owns one snapshot, not one account/round audit per metric.
     * Tickets still carry each original structure; writers always revalidate them separately.
     */
    internal suspend fun metrics(ids: List<Long>): List<ObjectEditSnapshot<MetricEntity>> = sessions.exclusive(AccountSessionCoordinator.Work.METRIC_EDIT_READ) {
        val access = tokens.localCoreWriteAccess()
        database.withTransaction {
            val rows = ids.distinct().associateWith { database.metricDao().getVisibleMetricById(it) }
            val rounds = if (rows.values.any { it?.appearance != null }) {
                requireNotNull(access) { "OBJECT_EDIT_ACCESS_DENIED" }
                producer.captureDisplayedRoundsInTransaction().also {
                    check(it == null || it.access.session == access.session &&
                        it.access.deviceId == access.capturedDeviceId && it.access.capabilities == access.capabilities) {
                        "OBJECT_EDIT_SESSION_CHANGED"
                    }
                }
            } else null
            val snapshots = rows.mapValues { (_, row) ->
                ObjectEditSnapshot(row, row?.takeIf { it.appearance != null }?.let {
                    ObjectEditAuthority(requireNotNull(access).session, it.uuid, "metric",
                        NextStructureMapper.writeMetric(it), rounds)
                })
            }
            check(tokens.localCoreWriteAccess() == access) { "OBJECT_EDIT_SESSION_CHANGED" }
            ids.map { snapshots.getValue(it) }
        }
    }

    /** Participant in one completion-prompt snapshot; never acquires the account lock again. */
    internal suspend fun metricsInTransaction(ids: List<Long>, scope: RecurringDisplayReadScope): List<ObjectEditSnapshot<MetricEntity>> {
        val (access, rounds) = scope.borrow(database, tokens)
        val rows = ids.distinct().associateWith { database.metricDao().getVisibleMetricById(it) }
        val snapshots = rows.mapValues { (_, row) ->
            ObjectEditSnapshot(row, row?.takeIf { it.appearance != null }?.let {
                ObjectEditAuthority(access.session, it.uuid, "metric", NextStructureMapper.writeMetric(it), rounds)
            })
        }
        scope.borrow(database, tokens)
        return ids.map { snapshots.getValue(it) }
    }

    internal suspend fun <T> editHabit(edited: HabitEntity, ticket: ObjectEditAuthority,
        commit: suspend (HabitEntity) -> T): T {
        require(ticket.type == "plan_node" && ticket.uuid == edited.uuid)
        val before = requireNotNull(database.habitDao().getHabitById(edited.id)) { "OBJECT_EDIT_NOT_FOUND" }
        require(edited.completionPolicy == before.completionPolicy) { "OBJECT_EDIT_POLICY_CHANGE_REQUIRES_HISTORY_CHECK" }
        authorize(ticket, requireNotNull(edited.appearance), requireNotNull(before.appearance),
            before.completionPolicy == "one_and_done")
        return write(ticket) {
            NextPlanDeletionStore(database).requireWritable(ticket.uuid)
            val current = requireNotNull(database.habitDao().getHabitById(edited.id)) { "OBJECT_EDIT_NOT_FOUND" }
            check(current.uuid == ticket.uuid && NextStructureMapper.writePlan(current) == ticket.original) {
                "OBJECT_EDIT_CHANGED_RELOAD_REQUIRED"
            }
            val metadata = requireNotNull(current.planMetadata)
            val preferred = if (edited.bestTime == current.bestTime) metadata.preferredLocalTime else
                edited.bestTime?.let { LocalTime.of((it / 60).toInt(), (it % 60).toInt()).toString() }
            // Display-only editors never overwrite the authoritative projection or unseen planning fields.
            val saved = edited.copy(createdAt = current.createdAt, activityRate = current.activityRate,
                activityRateUpdatedAt = current.activityRateUpdatedAt,
                oneTimeConfirmedVersion = current.oneTimeConfirmedVersion,
                oneTimeConfirmedHeadEventUuid = current.oneTimeConfirmedHeadEventUuid,
                oneTimeConfirmedCompletionEventUuid = current.oneTimeConfirmedCompletionEventUuid,
                planMetadata = metadata.copy(preferredLocalTime = preferred), updatedAt = System.currentTimeMillis())
            NextStructureMapper.writePlan(saved)
            commit(saved)
        }
    }

    internal suspend fun <T> editMetric(edited: MetricEntity, ticket: ObjectEditAuthority,
        commit: suspend (MetricEntity) -> T): T {
        require(ticket.type == "metric" && ticket.uuid == edited.uuid)
        val before = requireNotNull(database.metricDao().getMetricById(edited.id)) { "OBJECT_EDIT_NOT_FOUND" }
        authorize(ticket, requireNotNull(edited.appearance), requireNotNull(before.appearance), false)
        return write(ticket) {
            NextMetricDeletionStore(database).requireWritable(ticket.uuid)
            val current = requireNotNull(database.metricDao().getMetricById(edited.id)) { "OBJECT_EDIT_NOT_FOUND" }
            check(current.uuid == ticket.uuid && NextStructureMapper.writeMetric(current) == ticket.original) {
                "OBJECT_EDIT_CHANGED_RELOAD_REQUIRED"
            }
            val saved = edited.copy(createdAt = current.createdAt, updatedAt = System.currentTimeMillis())
            NextStructureMapper.writeMetric(saved)
            commit(saved)
        }
    }

    /** Existing-object business mutations preserve appearance; no file work or nested account lock. */
    /** Closed typed COUNT path. Callers cannot supply SQL work or a proof/authority callback. */
    internal suspend fun addCountPrepared(expected: HabitEntity, ticket: ObjectEditAuthority,
        identity: com.dayforge.domain.model.CountActionIdentity?, occurredAt: java.time.Instant,
        zone: java.time.ZoneId): CountAddExecutor.Result =
        CountAddExecutor(database, tokens, sessions).run(expected, ticket, identity, occurredAt, zone)

    internal suspend fun <T> mutateHabit(expected: HabitEntity, authority: ObjectEditAuthority? = null,
        countIdentity: com.dayforge.domain.model.CountActionIdentity? = null,
        checkIdentity: com.dayforge.domain.model.CheckActionIdentity? = null, command: CountAttempt? = null,
        commit: suspend (HabitEntity) -> T): T {
        try {
        val issued = command?.let { it as? IssuedCountAttempt ?: error("COMMAND_FOREIGN_ATTEMPT") }
        issued?.let { require(it.owner === this && it.committed == null); CountCommandInputs.requireOriginalSelection(it.input, it.authority) }
        val ticket = issued?.authority ?: authority ?: requireNotNull(habit(expected.id).authority) { "OBJECT_WRITE_TICKET_REQUIRED" }
        check(ticket.type == "plan_node" && ticket.uuid == expected.uuid &&
            ticket.original == NextStructureMapper.writePlan(expected)) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
        return write(ticket, command = issued) { originalHabitMutation(expected, ticket, countIdentity, checkIdentity, commit) }
        } finally { command?.let { releaseUnexecuted(it) } }
    }

    private suspend fun <T> originalHabitMutation(expected: HabitEntity, ticket: ObjectEditAuthority,
        countIdentity: com.dayforge.domain.model.CountActionIdentity?, checkIdentity: com.dayforge.domain.model.CheckActionIdentity?,
        commit: suspend (HabitEntity) -> T): T {
            countIdentity?.let {
                it.requireAuthority(ticket)
                check(tokens.localCoreWriteAccess()?.capturedDeviceId == it.deviceId) { "COUNT_SESSION_CHANGED" }
            }
            checkIdentity?.let {
                it.requireAuthority(ticket)
                check(tokens.localCoreWriteAccess()?.capturedDeviceId == it.deviceId) { "CHECK_SESSION_CHANGED" }
            }
            NextPlanDeletionStore(database).requireWritable(ticket.uuid)
            val current = requireNotNull(database.habitDao().getHabitById(expected.id)) { "OBJECT_WRITE_NOT_FOUND" }
            check(current.uuid == ticket.uuid && NextStructureMapper.writePlan(current) == ticket.original) {
                "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED"
            }
            return commit(current)
    }

    /** Whole private command execution; no UI callback or participant escapes to the caller.
     * A/B values may be discarded, but every C/fallback still enters the same durable T1.
     */
    internal suspend fun mutateCountAddCommand(expected: HabitEntity,
        identity: com.dayforge.domain.model.CountActionIdentity?, command: CountAttempt,
        occurredAt: java.time.Instant, zone: java.time.ZoneId, allowPrepared: Boolean,
        commit: suspend (HabitEntity) -> Long): Long {
        try {
        val issued = command as? IssuedCountAttempt ?: error("COMMAND_FOREIGN_ATTEMPT")
        require(issued.owner === this && issued.committed == null && issued.input.action == CountCommandRecords.Action.COUNT_ADD)
        CountCommandInputs.requireOriginalSelection(issued.input, issued.authority)
        val ticket = issued.authority
        check(ticket.type == "plan_node" && ticket.uuid == expected.uuid && ticket.original == NextStructureMapper.writePlan(expected)) {
            "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED"
        }
        val frozen = issued.input.body().getValue("input").jsonObject
        val executor = CountAddExecutor(database, tokens, sessions)
        val prepared = if (allowPrepared && ticket.rounds != null && CountCommandRecords.integer(frozen, "quantity") == 1L &&
            frozen.getValue("widget_claim") == kotlinx.serialization.json.JsonNull)
            executor.prepareForCommand(expected, ticket, identity, occurredAt, zone) else null
        val original: suspend () -> Long = { originalHabitMutation(expected, ticket, identity, null, commit) }
        val result = if (prepared == null) producer.writeCommand(ticket.session, ticket.rounds, commands, issued.ticket,
            writeInTransaction = original)
        else producer.writePreparedCountCommand(ticket.session, requireNotNull(ticket.rounds), commands, issued.ticket,
            NextCoreLocalIntentStore.PreparedCount(executor, prepared), original)
        issued.committed = requireNotNull(result.command) { "COMMAND_END_NOT_CONFIRMED" }
        return result.result
        } finally { releaseUnexecuted(command) }
    }

    /** Retain pending facts, release names and freeze explicit child policy in one original transaction. */
    internal suspend fun deleteHabit(expected: HabitEntity, childPolicy: String?, authority: ObjectEditAuthority? = null): List<HabitEntity> {
        val ticket = authority ?: requireNotNull(habit(expected.id).authority) { "OBJECT_WRITE_TICKET_REQUIRED" }
        check(ticket.type == "plan_node" && ticket.uuid == expected.uuid &&
            ticket.original == NextStructureMapper.writePlan(expected)) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
        return write(ticket) {
            val current = requireNotNull(database.habitDao().getHabitById(expected.id)) { "OBJECT_WRITE_NOT_FOUND" }
            check(current.uuid == ticket.uuid) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
            if (database.habitDao().hasPendingNextDeletion(current.uuid)) {
                val existing = database.syncOutboxDao().getEntityIntents("habit", current.uuid).single { it.action == "delete" }
                check(NextPlanDeletionStore.payload(existing)?.get("child_policy")?.let { it.toString().trim('"') } == childPolicy)
                return@write emptyList() // Exact same deletion is already durable; do not append another.
            }
            check(current.uuid == ticket.uuid && NextStructureMapper.writePlan(current) == ticket.original) {
                "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED"
            }
            val children = database.habitDao().getChildrenByParentUuidOnce(current.uuid)
            if (ticket.rounds != null && current.habitType == com.dayforge.data.model.HabitType.GOAL)
                check(ticket.goalChildUuids == children.map { it.uuid }.sorted()) { "OBJECT_DELETE_CHILDREN_CHANGED_RELOAD_REQUIRED" }
            require(current.habitType == com.dayforge.data.model.HabitType.GOAL || children.isEmpty())
            require(children.all { it.appearance != null && it.habitType != com.dayforge.data.model.HabitType.GOAL }) {
                "OBJECT_WRITE_MIXED_PROTOCOL_OR_INVALID_PARENT"
            }
            val deletion = NextPlanDeletionStore(database)
            if (childPolicy == "cascade_children") children.forEach {
                if (!database.habitDao().hasPendingNextDeletion(it.uuid)) deletion.stage(it, null)
            }
            else children.forEach {
                if (!database.habitDao().hasPendingNextDeletion(it.uuid))
                    database.habitDao().updateParentHabitId(it.id, null)
            }
            deletion.stage(current, childPolicy, children.map { it.uuid })
            if (childPolicy == "cascade_children") listOf(current) + children else listOf(current)
        }
    }

    /** Batch observations/links share one owner and transaction, including all their original intents. */
    internal suspend fun <T> mutateMetrics(expected: List<MetricEntity>, authority: ObjectEditAuthority? = null,
        deleting: Boolean = false, prepareMetricSourceRead: Boolean = false, command: CountAttempt? = null,
        commit: suspend (Map<Long, MetricEntity>) -> T): T {
        try {
        require(expected.isNotEmpty() && expected.all { it.appearance != null }) { "OBJECT_WRITE_MIXED_PROTOCOL" }
        val first = expected.first()
        val issued = command?.let { it as? IssuedCountAttempt ?: error("COMMAND_FOREIGN_ATTEMPT") }
        issued?.let { require(it.owner === this && it.committed == null && it.input.action == CountCommandRecords.Action.LINKED_METRICS) }
        val ticket = issued?.authority ?: authority ?: requireNotNull(metric(first.id).authority) { "OBJECT_WRITE_TICKET_REQUIRED" }
        check(ticket.type == "metric" && ticket.uuid == first.uuid &&
            ticket.original == NextStructureMapper.writeMetric(first)) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
        return write(ticket, prepareMetricSourceRead, issued) {
            val currentRows = expected.associate { row ->
                if (!deleting) NextMetricDeletionStore(database).requireWritable(row.uuid)
                val current = requireNotNull(CountTrustKernel.loadMetricForT1(database, row.id)) { "OBJECT_WRITE_NOT_FOUND" }
                check(current.uuid == row.uuid && CountTrustKernel.metricDefinitionForT1(database, current) == NextStructureMapper.writeMetric(row)) {
                    "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED"
                }
                row.id to current
            }
            commit(currentRows)
        }
        } finally { command?.let { releaseUnexecuted(it) } }
    }

    private suspend fun <T> write(ticket: ObjectEditAuthority, prepareMetricSourceRead: Boolean = false,
        command: IssuedCountAttempt? = null, commit: suspend () -> T): T {
        if (command != null) {
            require(command.owner === this && command.authority === ticket && command.committed == null)
            val result = producer.writeCommand(ticket.session, ticket.rounds, commands, command.ticket, prepareMetricSourceRead, commit)
            command.committed = requireNotNull(result.command) { "COMMAND_END_NOT_CONFIRMED" }
            return result.result
        }
        return ticket.rounds?.let {
            if (prepareMetricSourceRead) producer.writeRoundsWithMetricSource(it, commit) else producer.writeRounds(it, commit)
        } ?: producer.write(ticket.session, commit)
    }

    private suspend fun authorize(ticket: ObjectEditAuthority, appearance: ObjectAppearance,
        previous: ObjectAppearance, oneTime: Boolean) {
        // Immutable asset metadata is checked BEFORE entering the producer's non-reentrant account lock.
        // The producer rechecks the captured session and the transaction proves the original structure.
        icons.authorizeEditReference(ticket.session, appearance.icon, previous.icon, oneTime)
    }
}
