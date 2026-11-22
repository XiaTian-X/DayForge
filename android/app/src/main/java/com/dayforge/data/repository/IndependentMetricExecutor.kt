package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.ZoneId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Closed single-value producer. Neither an arbitrary SQL callback nor a proof writer
 * leaves this object. The unchanged repository batch/prompt entries remain separate. */
internal class IndependentMetricExecutor(private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator, private val commands: CountCommandStore,
    private val producer: NextCoreLocalIntentStore, private val editor: NextObjectEditor) {
    suspend fun capture(metric: MetricEntity, authority: ObjectEditAuthority?, value: Double,
        note: String, at: Long): IndependentMetricAttempt {
        CountCommandEntryBoundary.requireTopLevel(database)
        require(value.isFinite()) { "Metric value must be finite" }
        val zone = ZoneId.systemDefault().id
        val opaque = tokens.localOpaqueMetricAccess()
        if (opaque != null) {
            require(metric.appearance == null && authority == null) { "OBJECT_WRITE_ACCESS_DENIED" }
            require(ReplicaPresentationRepository(database, tokens, sessions).isOrdinaryOpen()) { "COMMAND_REPLICA_NOT_OPEN" }
            require(tokens.localOpaqueMetricAccess() == opaque) { "COMMAND_CAPTURE_AUTHORITY_EXPIRED" }
            return IndependentMetricAttempt(this, null, metric, null, opaque.facts.session, value, note, at, zone,
                opaque = opaque)
        }
        val access = requireNotNull(tokens.localCoreWriteAccess()) { "COMMAND_FRESH_AUTHORITY_REQUIRED" }
        val state = commands.independentRoot()
        val intent = IndependentMetricInput.capture(database, access, metric, authority, value, note, at, zone)
        require(intent.namespace == state.namespace && tokens.localCoreWriteAccess() == access) { "COMMAND_CAPTURE_AUTHORITY_EXPIRED" }
        return IndependentMetricAttempt(this, intent, metric, authority, access.session, value, note, at, zone)
    }

    suspend fun execute(attempt: IndependentMetricAttempt): IndependentMetricSaved {
        require(attempt.owner === this)
        synchronized(attempt) { check(!attempt.started) { "COMMAND_REQUIRES_RESOLUTION" }; attempt.started = true }
        // The opaque compatibility lane is an explicit authenticated capture choice before
        // any T0/upgrade. Every actual writer rechecks fresh authority. No error or
        // UNKNOWN outcome can select a different lane.
        return withAttemptOwner(attempt.executionIdentity, { error("ORIGINAL_OPERATION_STILL_RUNNING") }) {
            if (attempt.opaque != null) opaqueLegacy(attempt)
            else registerAndRun(attempt)
        }
    }

    suspend fun resolve(reference: CountCommandReference, continueIfProvenUnexecuted: Boolean): IndependentMetricResolution =
        withAttemptOwner(reference.commandId, { IndependentMetricResolution.Unresolved(reference, "ORIGINAL_OPERATION_STILL_RUNNING") }) {
            resolveOwned(reference, continueIfProvenUnexecuted)
        }

    suspend fun resolveAttempt(attempt: IndependentMetricAttempt, continueIfProvenUnexecuted: Boolean): IndependentMetricResolution {
        require(attempt.owner === this && attempt.opaque == null)
        val reference = requireNotNull(attempt.reference)
        return withAttemptOwner(reference.commandId, { IndependentMetricResolution.Unresolved(reference, "ORIGINAL_OPERATION_STILL_RUNNING") }) {
            val observed = resolveOwned(reference, continueIfProvenUnexecuted)
            if (observed is IndependentMetricResolution.Unregistered && continueIfProvenUnexecuted)
                IndependentMetricResolution.Saved(registerAndRun(attempt))
            else observed
        }
    }

    private suspend fun registerAndRun(attempt: IndependentMetricAttempt): IndependentMetricSaved {
        require(attempt.opaque == null)
        val intent = requireNotNull(attempt.intent)
        commands.prepareIndependentRoot(intent)
        val registered = commands.register(intent)
        require(!registered.alreadyRegistered) { "COMMAND_REQUIRES_RESOLUTION" }
        return run(intent, requireNotNull(registered.ticket), attempt.authority, attempt.session)
    }

    private suspend fun resolveOwned(reference: CountCommandReference, continueIfProvenUnexecuted: Boolean): IndependentMetricResolution {
        val ref = reference.native()
        when (commands.independentRegistration(ref)) {
            null -> return IndependentMetricResolution.Unresolved(reference, "ORIGINAL_OPERATION_STILL_RUNNING")
            false -> return IndependentMetricResolution.Unregistered(reference)
            true -> Unit
        }
        val located = commands.resolve(ref)
        require(located.historical.intent.action == CountCommandRecords.Action.METRIC_APPEND) { "COMMAND_NOT_INDEPENDENT_METRIC" }
        if (located.historical.status.execution == CountCommandRecords.Execution.CANCELLED)
            commands.endedIndependent(ref)?.let { return IndependentMetricResolution.Ended(IndependentMetricEnded(it)) }
        var recovered = commands.recover(ref)
        if (continueIfProvenUnexecuted && recovered is CountCommandStore.Recovery.Unresolved) {
            recovered = when (recovered.reason) {
                "ADVANCED_BUSINESS_REQUIRES_COMPLETE_NO_EFFECTS_PROOF" -> commands.recoverAfterCompleteProof(ref)
                "ORIGINAL_EXECUTION_SESSION_REVALIDATION_REQUIRED" -> commands.recoverAfterReauthentication(ref)
                else -> recovered
            }
        }
        return when (val result = recovered) {
            is CountCommandStore.Recovery.Committed -> IndependentMetricResolution.Saved(IndependentMetricEvidence.saved(result.value,
                deliveryAllowed = located.scope == CountCommandStore.LocationScope.CURRENT))
            is CountCommandStore.Recovery.Unresolved -> IndependentMetricResolution.Unresolved(reference, result.reason)
            is CountCommandStore.Recovery.Ready -> {
                if (!continueIfProvenUnexecuted) {
                    commands.releaseUnadmitted(result.ticket)
                    IndependentMetricResolution.Unresolved(reference, "COMMAND_READY_REQUIRES_EXPLICIT_CONTINUE")
                } else {
                    try {
                        val input = IndependentMetricInput.input(result.intent)
                        val access = requireNotNull(tokens.localCoreWriteAccess()) { "COMMAND_FRESH_AUTHORITY_REQUIRED" }
                        val authority = if (IndependentMetricInput.isLegacy(result.intent)) null else {
                            val current = editor.metric(CountCommandRecords.integer(input, "metric_id"))
                            val row = requireNotNull(current.value) { "OBJECT_WRITE_NOT_FOUND" }
                            requireNotNull(current.authority).also { IndependentMetricInput.requireTypedSelection(result.intent, row, it) }
                        }
                        IndependentMetricResolution.Saved(run(result.intent, result.ticket, authority, access.session))
                    } finally { commands.releaseUnadmitted(result.ticket) }
                }
            }
        }
    }

    suspend fun acknowledge(saved: IndependentMetricSaved, isConsumerCurrent: () -> Boolean) {
        val commit = saved.commit ?: return
        require(commit.outcome.action == CountCommandRecords.Action.METRIC_APPEND && saved.factUuid == commit.outcome.primaryFactUuid &&
            saved.metricUuid == commit.outcome.effects.single().objectUuid)
        commands.deliverIndependent(commit.reference, isConsumerCurrent)
    }

    private suspend fun run(intent: CountCommandRecords.Intent, ticket: CountCommandStore.ExecutionTicket,
        authority: ObjectEditAuthority?, session: com.dayforge.data.local.LocalDataSession): IndependentMetricSaved = try {
        commands.requireIndependentExecutionTicket(ticket, intent)
        if (IndependentMetricInput.isLegacy(intent)) runLegacy(intent, ticket, session)
        else {
            val original = requireNotNull(authority) { "OBJECT_WRITE_TICKET_REQUIRED" }
            require(original.session == session)
            val completed = producer.writeCommand(session, original.rounds, commands, ticket, false) {
                val current = current(intent, original)
                val log = construct(intent, current)
                val trust = CountTrustKernel.beginIndependentMetricBatch(database, listOf(log), mapOf(current.id to current))
                // Store-issued same-generation/full recovery evidence is revalidated by
                // the actual participant. Optional derived-root eligibility is not
                // execution authority and cannot add a second global UUID policy.
                requireReservedFactAbsent(intent)
                val allocation = NextCoreLocalIntentStore.prepareCommandMetrics(database, listOf(log), mapOf(current.id to current))
                val id = database.metricLogDao().insertAll(listOf(log)).single()
                NextCoreLocalIntentStore.bindCommandIds(database, allocation, listOf(id))
                CountTrustKernel.bindMetricBatch(database, trust, listOf(id))
                id
            }
            IndependentMetricEvidence.saved(requireNotNull(completed.command) { "COMMAND_END_NOT_CONFIRMED" })
        }
    } finally { commands.releaseUnadmitted(ticket) }

    private suspend fun runLegacy(intent: CountCommandRecords.Intent, ticket: CountCommandStore.ExecutionTicket,
        session: com.dayforge.data.local.LocalDataSession): IndependentMetricSaved =
        sessions.exclusive(AccountSessionCoordinator.Work.NEXT_WRITE) {
            require(ReplicaPresentationRepository(database, tokens, sessions).isOpenWhileAccountLocked()) { "COMMAND_REPLICA_NOT_OPEN" }
            val access = requireNotNull(tokens.localCoreWriteAccess()) { "COMMAND_FRESH_AUTHORITY_REQUIRED" }
            require(access.session == session)
            val completed = commands.producerTransaction(ticket, access) { participant ->
                require(participant.frozenIntent.text() == intent.text())
                val row = current(intent, null)
                // New legacy input retains its original native identity and T1 current unit.
                // The Store's sealed recovery boundary, not a second global scan here,
                // decides whether an UNKNOWN command is safely executable.
                requireReservedFactAbsent(intent)
                val plan = IndependentMetricLegacyPlan.capture(database, intent, row)
                try {
                    val log = construct(intent, row)
                    plan.constructed(listOf(log))
                    participant.beginBusiness()
                    val id = database.metricLogDao().insertAll(listOf(log)).single()
                    plan.inserted(listOf(id))
                    participant.stage(CountCommandProducerPlan.Proven.verifiedIndependentMetric(plan))
                    plan.verifyAfterStage()
                    val candidate = participant.verifyBeforeEnd()
                    require(tokens.localCoreWriteAccess() == access) { "COMMAND_FINAL_AUTHORITY_CHANGED" }
                    CountCommandStore.ProducerValue(id, candidate)
                } finally { plan.close() }
            }
            IndependentMetricEvidence.saved(requireNotNull(completed.command) { "COMMAND_END_NOT_CONFIRMED" })
        }

    private fun requireReservedFactAbsent(intent: CountCommandRecords.Intent) {
        check(database.inTransaction())
        val fact = CountCommandRecords.uuid(IndependentMetricInput.input(intent), "reserved_fact_uuid")
        database.openHelper.writableDatabase.query("SELECT 1 FROM metric_logs WHERE uuid=? LIMIT 1", arrayOf(fact)).use {
            require(!it.moveToFirst()) { "COMMAND_EFFECT_OR_SOURCE_ALREADY_PRESENT" }
        }
    }

    private suspend fun current(intent: CountCommandRecords.Intent, authority: ObjectEditAuthority?): MetricEntity {
        check(database.inTransaction())
        val input = IndependentMetricInput.input(intent)
        val row = requireNotNull(database.metricDao().getMetricById(CountCommandRecords.integer(input, "metric_id"))) { "OBJECT_WRITE_NOT_FOUND" }
        require(row.uuid == CountCommandRecords.text(input, "metric_uuid")) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
        if (IndependentMetricInput.isLegacy(intent)) require(row.appearance == null) { "OBJECT_EDIT_REQUIRES_COORDINATED_SWITCH" }
        else IndependentMetricInput.requireTypedSelection(intent, row, requireNotNull(authority))
        NextMetricDeletionStore(database).requireWritable(row.uuid)
        return row
    }

    private fun construct(intent: CountCommandRecords.Intent, metric: MetricEntity): MetricLogEntity {
        val input = IndependentMetricInput.input(intent)
        return MetricLogEntity(metricId = metric.id, date = CountCommandRecords.integer(input, "recorded_at"),
            value = IndependentMetricInput.value(input), unit = metric.unit, note = CountCommandRecords.text(input, "note"),
            recordedTimezone = CountCommandRecords.text(input, "timezone"), uuid = CountCommandRecords.uuid(input, "reserved_fact_uuid"))
    }

    /** Explicit original opaque-v4 lane. No canonical namespace or durable command is
     * invented, and no core-null/error/UNKNOWN catch ever selects this branch. Original
     * legacy recordValue has no facts.append gate: preserve that policy while checking
     * the complete fresh identity/capability snapshot before and at final commit. */
    private suspend fun opaqueLegacy(attempt: IndependentMetricAttempt): IndependentMetricSaved =
        sessions.exclusive(AccountSessionCoordinator.Work.NEXT_WRITE) {
            val expected = requireNotNull(attempt.opaque)
            require(attempt.intent == null && attempt.authority == null)
            require(ReplicaPresentationRepository(database, tokens, sessions).isOpenWhileAccountLocked()) { "COMMAND_REPLICA_NOT_OPEN" }
            val access = requireNotNull(tokens.localOpaqueMetricAccess()) { "METRIC_OPAQUE_AUTHORITY_EXPIRED" }
            require(access == expected) { "METRIC_OPAQUE_AUTHORITY_EXPIRED" }
            val log = database.withTransaction {
                val row = requireNotNull(database.metricDao().getMetricById(attempt.metric.id)) { "OBJECT_WRITE_NOT_FOUND" }
                require(row.uuid == attempt.metric.uuid && row.appearance == null) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
                NextMetricDeletionStore(database).requireWritable(row.uuid)
                val draft = MetricLogEntity(metricId = row.id, date = attempt.recordedAt, value = attempt.value,
                    unit = row.unit, note = attempt.note, recordedTimezone = attempt.timezone)
                val value = draft.copy(id = database.metricLogDao().insertAll(listOf(draft)).single())
                require(tokens.localOpaqueMetricAccess() == access) { "METRIC_OPAQUE_FINAL_AUTHORITY_CHANGED" }
                value
            }
            currentCoroutineContext().ensureActive()
            IndependentMetricSaved(log.id, log.uuid, attempt.metric.uuid, log.value, log.unit, null, deliveryAllowed = false)
        }

    suspend fun prepareEnd(reference: CountCommandReference): IndependentMetricEnd? =
        commands.prepareIndependentEnd(reference.native())?.let(::IndependentMetricEnd)
    suspend fun end(plan: IndependentMetricEnd, isConsumerCurrent: () -> Boolean): IndependentMetricEnded =
        IndependentMetricEnded(commands.endIndependent(plan.plan, isConsumerCurrent))
    suspend fun acknowledgeEnd(ended: IndependentMetricEnded, isConsumerCurrent: () -> Boolean) =
        commands.deliverIndependentEnd(ended.result, isConsumerCurrent)

    /** Process-local exclusion only, covering the pre-registration upgrade gap. It never
     * proves absence; the Store still checks its own lease, native root and actual END. */
    private suspend fun <T> withAttemptOwner(id: String, busy: () -> T, block: suspend () -> T): T {
        val owner = requireNotNull(CountTrustRuntime.forDatabase(database))
        val acquired = synchronized(active) { active.getOrPut(owner) { hashSetOf() }.add(id) }
        if (!acquired) return busy()
        try { return block() }
        finally { synchronized(active) {
            check(active[owner]?.remove(id) == true)
            if (active[owner]?.isEmpty() == true) active.remove(owner)
        } }
    }
    companion object {
        private val active = java.util.WeakHashMap<CountTrustRuntime, MutableSet<String>>()
    }

}
