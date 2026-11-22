package com.dayforge.data.repository

import android.content.Context
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.domain.service.AccountSessionCoordinator
import java.util.Collections
import kotlinx.serialization.json.*

/** Actual bounded pending-directory consumer. Summary/cursor objects are read observations;
 * resume always resolves the original immutable ID anew before using the original writer. */
internal class CountCommandPendingRepository(database: HabitDatabase, private val tokens: TokenManager,
    sessions: AccountSessionCoordinator, private val habits: HabitRepository,
    private val legacy: LegacyWidgetFactWorkflow, private val facts: WidgetFactReader,
    private val preferences: com.dayforge.data.local.PreferencesManager, private val metrics: MetricRepository? = null) {
    private val store = CountCommandStore(database, tokens, sessions)
    data class Entry(val reference: CountCommandReference, val action: CountCommandRecords.Action,
        val execution: CountCommandRecords.Execution, val delivery: CountCommandRecords.Delivery,
        val scope: CountCommandStore.LocationScope)
    class Page internal constructor(val entries: List<Entry>, val complete: Boolean,
        internal val cursor: CountCommandStore.Discovery?, val retainedCount: Long = 0L)
    sealed class Admission protected constructor(val reference: CountCommandReference, val session: LocalDataSession,
        val subjectUuid: String, val subjectId: Long) {
        class Habit internal constructor(reference: CountCommandReference, session: LocalDataSession, uuid: String, id: Long) :
            Admission(reference, session, uuid, id)
        class Metric internal constructor(reference: CountCommandReference, session: LocalDataSession, uuid: String, id: Long,
            val scope: CountCommandStore.LocationScope) : Admission(reference, session, uuid, id)
    }
    sealed interface Result {
        data class Saved(val receipt: CountCommandReceipt, val pending: Boolean) : Result
        data class Unresolved(val reason: String) : Result
        data class MetricParent(val reference: CountCommandReference) : Result
        class StandaloneMetricSaved internal constructor(internal val saved: IndependentMetricSaved, val pending: Boolean) : Result
        class IndependentEndAvailable internal constructor(internal val plan: IndependentMetricEnd) : Result {
            val reference: CountCommandReference get() = plan.reference
        }
        class IndependentEnded internal constructor(internal val ended: IndependentMetricEnded) : Result {
            val reference: CountCommandReference get() = ended.reference
            val pending: Boolean get() = ended.pendingDelivery && ended.deliveryAllowed
        }
        class Invalidated internal constructor(internal val result: CountCommandStore.InvalidatedGeneric) : Result {
            val reference: CountCommandReference get() = CountCommandReference.from(result.reference)
            val reason: CountCommandInvalidationDomain.Reason get() = result.reason
            val pending: Boolean get() = result.pending
        }
        class EndAvailable internal constructor(internal val proof: CountCommandStore.ObsoleteFollowUp) : Result {
            val reference: CountCommandReference get() = CountCommandReference.from(proof.reference)
            val reason: CountCommandObsoleteFollowUp.Reason get() = proof.reason
            val preferenceReady: Boolean get() = proof.preferenceReady
        }
        class FollowUpEnded internal constructor(val reference: CountCommandReference) : Result
        class MetricSaved internal constructor(internal val proof: CountCommandStore.CompletedMetricGroup) : Result {
            val count: Int get() = proof.count
            val pending: Boolean get() = proof.pending
            val preferenceReady: Boolean get() = proof.preferenceReady
        }
    }
    data class FollowUp(val reference: CountCommandReference, val habit: HabitEntity,
        val goalProgress: Int?, val current: WidgetFactSnapshot?, val completion: WidgetFactSnapshot?,
        val legacyIdentity: LegacyWidgetRestartClaim?)

    suspend fun page(previous: Page? = null, size: Int = 32): Page {
        require(previous == null || !previous.complete && previous.cursor != null) { "COMMAND_DISCOVERY_COMPLETE" }
        val page = store.discover(size, previous?.cursor)
        return Page(Collections.unmodifiableList(page.commands.map { located ->
            Entry(CountCommandReference.from(located.reference), located.historical.intent.action,
                located.historical.status.execution, located.historical.status.delivery, located.scope)
        }), page.complete, page.continuation, page.retainedCount)
    }

    suspend fun isPending(reference: CountCommandReference): Boolean = store.resolve(reference.native()).historical.status.pending

    suspend fun prepare(reference: CountCommandReference): Admission {
        val located = store.resolve(reference.native())
        val intent = located.historical.intent
        val historicalMetric = intent.action == CountCommandRecords.Action.METRIC_APPEND &&
            located.scope == CountCommandStore.LocationScope.INITIAL_REGISTRATION_REVALIDATION_REQUIRED &&
            (located.historical.outcome != null || located.historical.status.execution == CountCommandRecords.Execution.CANCELLED)
        require(located.scope == CountCommandStore.LocationScope.CURRENT || historicalMetric) { "COMMAND_EXECUTION_SCOPE_REVALIDATION_REQUIRED" }
        val access = requireNotNull(tokens.localCoreWriteAccess()) { "COMMAND_FRESH_AUTHORITY_REQUIRED" }
        require(access.session.authentication.userId == intent.namespace.account &&
            (historicalMetric || access.session.serverInstanceId == intent.namespace.server && access.session.syncEpoch == intent.namespace.epoch &&
                access.capturedDeviceId == intent.namespace.device)) { "COMMAND_CURRENT_NAMESPACE_ISOLATED" }
        val input = intent.body().getValue("input").jsonObject
        if (intent.action == CountCommandRecords.Action.METRIC_APPEND)
            return Admission.Metric(reference, access.session, CountCommandRecords.text(input, "metric_uuid"),
                CountCommandRecords.integer(input, "metric_id"), located.scope)
        // An explicit missing target has no habit ID; it still shares the UUID admission key.
        val id = input["habit_id"]?.jsonPrimitive?.longOrNull
            ?: input["source_claim"]?.takeUnless { it == JsonNull }?.let { CountCommandInputs.restoredWidget(input, "source_claim")?.habitId } ?: 0L
        return Admission.Habit(reference, access.session, CountCommandRecords.uuid(input, "habit_uuid"), id)
    }

    suspend fun resume(context: Context, admission: Admission): Result {
        val located = store.resolve(admission.reference.native())
        val row = located.historical
        if (row.intent.action == CountCommandRecords.Action.METRIC_APPEND) {
            require(admission is Admission.Metric)
            val input = IndependentMetricInput.input(row.intent)
            require(CountCommandRecords.text(input, "metric_uuid") == admission.subjectUuid &&
                CountCommandRecords.integer(input, "metric_id") == admission.subjectId)
            val current = located.scope == CountCommandStore.LocationScope.CURRENT
            require(admission.scope == located.scope && (current ||
                located.scope == CountCommandStore.LocationScope.INITIAL_REGISTRATION_REVALIDATION_REQUIRED &&
                    (row.outcome != null || row.status.execution == CountCommandRecords.Execution.CANCELLED))) {
                "COMMAND_EXECUTION_SCOPE_REVALIDATION_REQUIRED"
            }
            val repository = requireNotNull(metrics)
            val observed = try {
                repository.resolveIndependentValue(admission.reference, continueIfProvenUnexecuted = current)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (current) repository.prepareIndependentEnd(admission.reference)?.let { return Result.IndependentEndAvailable(it) }
                throw failure
            }
            return when (val result = observed) {
                is IndependentMetricResolution.Unregistered -> Result.Unresolved("COMMAND_NOT_REGISTERED_ORIGINAL_INPUT_UNAVAILABLE")
                is IndependentMetricResolution.Unresolved -> {
                    val plan = if (current) repository.prepareIndependentEnd(admission.reference) else null
                    if (plan == null) Result.Unresolved(result.reason) else Result.IndependentEndAvailable(plan)
                }
                is IndependentMetricResolution.Ended -> Result.IndependentEnded(result.value)
                is IndependentMetricResolution.Saved -> Result.StandaloneMetricSaved(result.value,
                    current && result.value.deliveryAllowed && store.resolve(admission.reference.native()).historical.status.pending)
            }
        }
        require(located.scope == CountCommandStore.LocationScope.CURRENT) { "COMMAND_EXECUTION_SCOPE_REVALIDATION_REQUIRED" }
        require(admission is Admission.Habit &&
            CountCommandRecords.uuid(row.intent.body().getValue("input").jsonObject, "habit_uuid") == admission.subjectUuid)
        if (row.status.delivery == CountCommandRecords.Delivery.CLOSED)
            store.closedFollowUp(admission.reference.native())?.let { return Result.FollowUpEnded(CountCommandReference.from(it.reference)) }
        if (row.status.execution == CountCommandRecords.Execution.INVALIDATED)
            store.invalidatedGeneric(admission.reference.native())?.let { return Result.Invalidated(it) }
        if (row.status.execution == CountCommandRecords.Execution.PREPARED && CountCommandReauthentication.supports(row.intent)) {
            store.prepareGenericInvalidation(admission.reference.native())?.let { plan ->
                try { return Result.Invalidated(store.finishGenericInvalidation(plan)) }
                finally { store.releaseGenericInvalidation(plan) }
            }
        }
        if (row.status.execution == CountCommandRecords.Execution.APPLIED &&
            (row.intent.action == CountCommandRecords.Action.LINKED_METRICS || row.status.delivery in setOf(
                CountCommandRecords.Delivery.METRICS_COMMITTED, CountCommandRecords.Delivery.DELIVERED)))
            store.completedMetricGroup(admission.reference.native(), preferences)?.let { return Result.MetricSaved(it) }
        if (row.intent.action == CountCommandRecords.Action.COUNT_ADD && row.status.execution == CountCommandRecords.Execution.APPLIED) {
            store.obsoleteFollowUp(admission.reference.native(), preferences)?.let { return Result.EndAvailable(it) }
        }
        if (row.intent.action == CountCommandRecords.Action.LINKED_METRICS) {
            // Form Save remains explicit. Do not treat discovery as consent to submit values.
            val parent = store.recoverParent(located.reference)
            return Result.MetricParent(CountCommandReference.from(parent.reference))
        }
        val recovered = if (CountCommandLegacyWidgetInput.matches(row.intent)) {
            val claim = CountCommandLegacyWidgetInput.claim(row.intent.body().getValue("input").jsonObject)
            when (val result = legacy.resumeCountCommand(context, claim, row.intent.commandId)) {
                is WidgetCountCommandRecovery.Committed -> CountCommandRecovery.HistoricalCommitted(result.receipt)
                is WidgetCountCommandRecovery.Unresolved -> CountCommandRecovery.Unresolved(admission.reference, result.reason)
            }
        } else if (CountCommandReauthentication.supports(row.intent) &&
            CountCommandRecords.text(row.intent.body(), "original_auth_generation") != admission.session.authentication.generation) {
            // This is the freshly qualified Panel's explicit Continue, not an old task's
            // automatic restore or a new T0. The original handler keeps every input field.
            habits.resumeCountCommandAfterReauthentication(context, admission.reference)
        } else {
            val fast = habits.resumeCountCommand(context, admission.reference)
            if (fast is CountCommandRecovery.Unresolved && fast.reason == "ADVANCED_BUSINESS_REQUIRES_COMPLETE_NO_EFFECTS_PROOF")
                habits.resumeCountCommandAfterCompleteProof(context, admission.reference) else fast
        }
        return when (recovered) {
            is CountCommandRecovery.Unresolved -> Result.Unresolved(recovered.reason)
            is CountCommandRecovery.HistoricalCommitted -> {
                val current = store.resolve(admission.reference.native())
                Result.Saved(recovered.receipt, current.historical.status.pending)
            }
        }
    }

    suspend fun acknowledge(receipt: CountCommandReceipt) {
        require(!receipt.requiresMetricFollowUp) { "COMMAND_REQUIRED_FOLLOWUP_NOT_DELIVERED" }
        val row = store.resolve(receipt.reference.native())
        if (row.historical.status.pending) habits.acknowledgeCountResultDelivery(receipt)
    }

    suspend fun acknowledgeInvalidated(result: Result.Invalidated, isConsumerCurrent: () -> Boolean) {
        if (result.pending) store.deliverInvalidatedGeneric(result.result, isConsumerCurrent)
    }

    suspend fun endIndependent(plan: Result.IndependentEndAvailable, isConsumerCurrent: () -> Boolean): Result.IndependentEnded =
        Result.IndependentEnded(requireNotNull(metrics).endIndependentValue(plan.plan, isConsumerCurrent))

    suspend fun acknowledgeIndependentEnd(result: Result.IndependentEnded, isConsumerCurrent: () -> Boolean) {
        if (result.pending) requireNotNull(metrics).acknowledgeIndependentEnd(result.ended, isConsumerCurrent)
    }

    suspend fun acknowledgeIndependent(result: Result.StandaloneMetricSaved, isConsumerCurrent: () -> Boolean) {
        if (result.pending) requireNotNull(metrics).acknowledgeIndependentValue(result.saved, isConsumerCurrent)
    }

    suspend fun acknowledgeMetrics(result: Result.MetricSaved, isConsumerCurrent: () -> Boolean) {
        if (result.pending) store.deliverCompletedMetricGroup(result.proof, preferences, isConsumerCurrent)
    }

    suspend fun endFollowUp(result: Result.EndAvailable, isConsumerCurrent: () -> Boolean): Result.FollowUpEnded {
        val ended = store.endObsoleteFollowUp(result.proof, preferences, isConsumerCurrent)
        return Result.FollowUpEnded(CountCommandReference.from(ended.reference))
    }

    suspend fun followUp(reference: CountCommandReference): FollowUp? {
        val located = store.resolve(reference.native())
        require(located.scope == CountCommandStore.LocationScope.CURRENT) { "COMMAND_EXECUTION_SCOPE_REVALIDATION_REQUIRED" }
        val row = located.historical
        // Current authenticated delivery, never the immutable Outcome flag alone.
        if (!row.status.pending) return null
        val outcome = requireNotNull(row.outcome) { "COMMAND_RESULT_STILL_UNRESOLVED" }
        require(outcome.action == CountCommandRecords.Action.COUNT_ADD &&
            outcome.followUp == CountCommandOutcome.FollowUp.RECHECK_ORIGINAL_RULES) { "COMMAND_REQUIRED_FOLLOWUP_NOT_DELIVERED" }
        val input = row.intent.body().getValue("input").jsonObject
        val habitId = CountCommandRecords.integer(input, "habit_id")
        val habitUuid = CountCommandRecords.uuid(input, "habit_uuid")
        val receipt = habits.historicalCountResult(reference, habitUuid)
        if (CountCommandLegacyInput.isLegacy(row.intent)) {
            val identity = legacy.recoveredDisplayIdentity(row.intent.namespace, habitId, habitUuid)
            val read = legacy.afterResolvedCount(identity, "increment", receipt)
            return FollowUp(reference, read.habit, read.result.progress.takeIf { read.result.goalReached }, null, null, read.identity)
        }
        val read = facts.afterRecoveredCount(row.intent.namespace, habitId, habitUuid, requireNotNull(receipt.factUuid))
        val current = read.current
        val reached = current.habit.targetCycles?.let { current.targetProgress >= it } == true
        return FollowUp(reference, current.habit, current.targetProgress.takeIf { reached }, current,
            requireNotNull(read.completionSource), null)
    }
}
