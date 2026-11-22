package com.dayforge.data.repository

import com.dayforge.domain.service.CountCommandCostTiming
import com.dayforge.domain.service.CompletionActionTiming
import com.dayforge.domain.service.CountCommandCostTiming.Step

import android.database.Cursor
import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.data.local.CountCommandJournal
import com.dayforge.data.local.CountCommandV20Schema
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.CountTrustSchema
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.LocalCommandRecoveryAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.service.AccountSessionCoordinator
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Primary native transactions, not a COUNT optimization. The only normal writes are typed
 * T0/T1/T2 plans. No public callback receives Scope, signer, native SQL or a mutable write set.
 * The producer keeps Participant private to its own local frame, never to the domain callback.
 */
internal class CountCommandStore(private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator) {
    data class Reference(val commandId: String, val inputHash: String, val namespaceHash: String, val datasetId: String) {
        init { require(com.dayforge.domain.model.isContractUuid(commandId) && com.dayforge.domain.model.isContractUuid(datasetId))
            require(listOf(inputHash, namespaceHash).all { it.length == 64 && it.all { c -> c in "0123456789abcdef" } }) }
    }
    enum class LocationScope { CURRENT, INITIAL_REGISTRATION_REVALIDATION_REQUIRED, ISOLATED }
    data class Located(val reference: Reference, val scope: LocationScope, val historical: CountCommandRowPlan.Aggregate)

    sealed interface Discovery
    private class IssuedDiscovery(val issuer: CountCommandStore, val rootIdentity: String,
        val datasetId: String, val frontier: CountCommandPendingRules.Frontier,
        val pager: CountCommandPendingRules.Pager) : Discovery { var busy = false }
    data class DiscoveryPage(val commands: List<Located>, val complete: Boolean, val continuation: Discovery?, val retainedCount: Long = 0L)

    class ProducerValue<T> internal constructor(val result: T, internal val candidate: Candidate?)
    sealed interface DurableCommit {
        val reference: Reference
        val outcome: CountCommandOutcome
    }
    class Committed private constructor(override val reference: Reference, override val outcome: CountCommandOutcome) : DurableCommit {
        companion object {
            internal fun <T> confirm(completed: CountTrustRuntime.PrimaryCompleted<ProducerValue<T>>): CommittedWrite<T> {
                completed.end.requireCandidate(completed.value)
                val candidate = completed.value.candidate
                candidate?.requireSameEnd(completed.end)
                return CommittedWrite(completed.value.result, candidate?.let { Committed(it.reference, it.outcome) })
            }
        }
    }
    class CommittedWrite<T> internal constructor(val result: T, val command: Committed?)
    sealed interface ExecutionTicket { val reference: Reference }
    private inner class FreshTicket(override val reference: Reference, val caller: Job?, val generation: String,
        val lease: CountCommandExecutionLeases.Lease, val recoveryGeneration: Long? = null,
        val fullNoEffects: FullNoEffectsWitness? = null, val freshRegistration: Boolean = false) : ExecutionTicket {
        val issuer: CountCommandStore = this@CountCommandStore
        var admitted = false
    }
    data class Registered(val reference: Reference, val execution: CountCommandRecords.Execution, val alreadyRegistered: Boolean,
        val ticket: ExecutionTicket? = null)
    private val owner get() = requireNotNull(CountTrustRuntime.forDatabase(database))
    private val sql get() = database.openHelper.writableDatabase

    internal data class IndependentRoot(val mode: CountCommandRootCodec.Mode, val format: Int, val initialRegistration: Boolean, val namespace: CountCommandRecords.Namespace)
    /** Authentication and raw unenrolled-content check precede ANY optional fallback. */
    internal suspend fun independentRoot(): IndependentRoot = ownTransaction { access ->
        owner.maintenance(database) {
            com.dayforge.data.local.CountCommandStorageInitialization.verifyMandatory(sql)
            val scope = open(access, requireBound = false)
            IndependentRoot(scope.header.mode, scope.header.format,
                CountCommandRootCodec.bound(scope.header.mode) && scope.header.namespace != scope.namespace, scope.namespace)
        }
    }

    /** T0 registration observation only. A complete authenticated catalog non-member plus
     * all native row absences can say no T0 exists, but cannot reconstruct an input or issue T1. */
    internal suspend fun independentRegistration(ref: Reference): Boolean? {
        val caller = requireNotNull(currentCoroutineContext()[Job])
        val lease = CountCommandExecutionLeases.acquire(owner, ref.commandId, caller) ?: return null
        try {
            return ownTransaction { access -> owner.maintenance(database) {
                com.dayforge.data.local.CountCommandStorageInitialization.verifyMandatory(sql)
                val scope = open(access, requireBound = false)
                scope.requireDataset(ref)
                val present = if (scope.header.mode in setOf(CountCommandRootCodec.Mode.EMPTY, CountCommandRootCodec.Mode.LEGACY_UNENROLLED)) {
                    require(scope.namespace.hash == ref.namespaceHash) { "COMMAND_CURRENT_NAMESPACE_ISOLATED" }
                    CountCommandRowPlan.Table.entries.forEach { CountCommandNativeRows(sql) { lease.requireCaller(caller) }.requireAbsent(it, ref.commandId) }
                    false
                } else scope.lookup(ref.commandId)?.let { row ->
                    require(scope.header.namespace == scope.namespace) { "COMMAND_CURRENT_NAMESPACE_ISOLATED" }
                    require(row.intent.inputHash == ref.inputHash && row.intent.namespace.hash == ref.namespaceHash &&
                        row.intent.action == CountCommandRecords.Action.METRIC_APPEND)
                    true
                } ?: run {
                    // A nonmember is only "not registered" in this CURRENT namespace.
                    // Old isolated inputs cannot use absence to manufacture a new T0.
                    require(scope.namespace.hash == ref.namespaceHash) { "COMMAND_CURRENT_NAMESPACE_ISOLATED" }
                    false
                }
                lease.requireCaller(caller)
                present
            } }
        } finally { lease.close() }
    }

    /** No catch/fallback after this point. Each old bind/registration and the private
     * format advance has its own actual END; retry reads the real header again. */
    internal suspend fun prepareIndependentRoot(intent: CountCommandRecords.Intent? = null) {
        var observed = independentRoot()
        require(intent == null || intent.namespace == observed.namespace) { "COMMAND_CAPTURE_AUTHORITY_EXPIRED" }
        if (observed.mode == CountCommandRootCodec.Mode.LEGACY_UNENROLLED) {
            ensureForwardEnrollment(intent)
            observed = independentRoot()
        }
        require(observed.mode != CountCommandRootCodec.Mode.LEGACY_UNENROLLED) { "COMMAND_LEGACY_NOT_ENROLLED" }
        // T0 re-authenticates format/namespace itself; a known bound v2 needs no empty
        // version-update transaction. The observation above has completed its actual END.
        if (CountCommandRootCodec.bound(observed.mode) && observed.format in setOf(2, CountCommandRootCodec.RETAINED_FORMAT) && !observed.initialRegistration) return
        if (observed.initialRegistration) acknowledgeInitialRegistration()
        if (observed.mode == CountCommandRootCodec.Mode.EMPTY) bindEmptyNamespace()
        ownTransaction { access ->
            owner.maintenance(database) {
                val scope = open(access)
                if (scope.header.format in setOf(2, CountCommandRootCodec.RETAINED_FORMAT)) {
                    require(CountCommandRootCodec.admits(scope.header, CountCommandRecords.METRIC_FORMAT))
                } else {
                    val boundary = owner.captureCommandBoundary()
                    scope.requireCompleteV1Catalog()
                    owner.verifyCommandBoundary(boundary, scope.upgradeMetric())
                }
            }
        }
    }

    /** Metadata only, with fresh authority and real END before any new T0 lease. Old raw
     * rows are never interpreted. Readers/recovery/discovery do not call this implicitly. */
    internal suspend fun ensureForwardEnrollment(intent: CountCommandRecords.Intent? = null) = ownTransaction { access ->
        val observed = owner.maintenance(database) {
            val scope = open(access, requireBound = false)
            if (intent != null) {
                require(intent.namespace == scope.namespace &&
                    CountCommandRecords.text(intent.body(), "original_auth_generation") == access.session.authentication.generation) {
                    "COMMAND_CAPTURE_AUTHORITY_EXPIRED"
                }
                CountCommandInputOwnership.requireForRegistration(intent, access)
            }
            scope.header
        }
        if (observed.mode == CountCommandRootCodec.Mode.LEGACY_UNENROLLED) {
            require(observed.source == CountCommandRootCodec.Source.MIGRATION_19_20 && observed.revision == 1L)
            require(ReplicaPresentationRepository(database, tokens, sessions).isOpenWhileAccountLocked()) { "COMMAND_REPLICA_NOT_OPEN" }
            owner.maintenance(database) { com.dayforge.data.local.CountCommandStorageInitialization.verifyMandatory(sql) }
            CountCommandCostTiming.measure(Step.SCHEMA_BRIDGE) { CountCommandSchemaBridge.advance(database, access) }
            require(!CountCommandSchemaBridge.pending(database, access)) { "COMMAND_LEGACY_SCHEMA_BRIDGE_REQUIRED" }
            owner.maintenance(database) {
                val scope = open(access, requireBound = false)
                require(scope.header == observed) { "COMMAND_ENROLLMENT_ROOT_CHANGED" }
                val boundary = owner.captureCommandBoundary()
                owner.verifyCommandBoundary(boundary, scope.enrollRetained())
            }
        }
    }

    /** One-time EMPTY -> ACCOUNT_BOUND, replacing the same mandatory root. This is not
     * initialization: it cannot create a key or accept a missing root. A legitimate legacy
     * COUNT schema transition must already have completed before any account binding.
     */
    suspend fun bindEmptyNamespace() = ownTransaction { access ->
        require(ReplicaPresentationRepository(database, tokens, sessions).isOpenWhileAccountLocked()) { "COMMAND_REPLICA_NOT_OPEN" }
        CountCommandCostTiming.measure(Step.SCHEMA_BRIDGE) { CountCommandSchemaBridge.advance(database, access) }
        owner.maintenance(database) {
            val scope = open(access, requireBound = false)
            if (CountCommandRootCodec.bound(scope.header.mode)) {
                require(scope.header.namespaceHash == scope.namespace.hash)
            } else {
                require(scope.header.mode == CountCommandRootCodec.Mode.EMPTY) { "COMMAND_LEGACY_NOT_ENROLLED" }
                // Primary command identity is independent of optional COUNT qualification.
                // The exact old envelope bridge above authenticated any migration material;
                // it did not grant current business/display authority or clear COUNT dirty.
                require(!CountCommandSchemaBridge.pending(database, access)) { "COMMAND_LEGACY_SCHEMA_BRIDGE_REQUIRED" }
                val boundary = owner.captureCommandBoundary()
                val expected = scope.bindEmpty()
                owner.verifyCommandBoundary(boundary, expected)
            }
        }
    }

    /** Preserve the original conditional FIRST registration transition. This does not admit
     * a non-null server/epoch/device replacement and does not re-authorize any old PREPARED.
     * The physical catalog is retained; the authenticated marker permits precise old-scope
     * location so original source/receipt/NoEffects validators can make a separate decision.
     */
    suspend fun acknowledgeInitialRegistration() = ownTransaction { access ->
        val registered = requireNotNull(tokens.localSyncAccess()) { "COMMAND_REGISTERED_AUTHORITY_REQUIRED" }
        val installation = requireNotNull(tokens.registeredInstallationId()) { "COMMAND_INSTALLATION_REQUIRED" }
        require(registered.session == access.session && registered.deviceId == access.capturedDeviceId && tokens.isCurrentSyncAuthority(registered))
        owner.maintenance(database) {
            val scope = open(access, requireBound = false)
            require(CountCommandRootCodec.bound(scope.header.mode))
            if (scope.header.namespace != scope.namespace) {
                require(requireNotNull(scope.header.namespace).permitsInitialRegistration(scope.namespace) && scope.header.registration == null) {
                    "COMMAND_REGISTERED_NAMESPACE_CHANGED_REQUIRES_RECOVERY"
                }
                val boundary = owner.captureCommandBoundary()
                val changes = scope.registerInitialNamespace(installation)
                owner.verifyCommandBoundary(boundary, changes)
            } else scope.header.registration?.let { require(it.installationId == installation) }
        }
        require(tokens.registeredInstallationId() == installation && tokens.isCurrentSyncAuthority(registered))
    }

    suspend fun register(intent: CountCommandRecords.Intent): Registered {
        val cost = CountCommandCostTiming.begin(CountCommandCostTiming.Phase.T0)
        try {
        CountCommandEntryBoundary.requireTopLevel(database)
        // Before a T0 process lease or account transaction; never recurse into this entry.
        ensureForwardEnrollment(intent)
        val caller = requireNotNull(currentCoroutineContext()[Job])
        val lease = CountCommandExecutionLeases.acquire(owner, intent.commandId, caller)
        var handedOff = false
        try {
        val result = ownTransaction { access ->
        require(CountCommandRecords.text(intent.body(), "original_auth_generation") == access.session.authentication.generation) {
            "COMMAND_CAPTURE_AUTHORITY_EXPIRED"
        }
        CountCommandInputOwnership.requireForRegistration(intent, access)
        require(ReplicaPresentationRepository(database, tokens, sessions).isOpenWhileAccountLocked()) { "COMMAND_REPLICA_NOT_OPEN" }
        CountCommandCostTiming.measure(Step.SCHEMA_BRIDGE) { CountCommandSchemaBridge.advance(database, access) }
        owner.maintenance(database) {
            var scope = open(access, requireBound = false)
            require(intent.namespace.hash == scope.namespace.hash)
            val boundary = owner.captureCommandBoundary()
            val initial = arrayListOf<CountCommandJournal.Expected>()
            if (scope.header.mode == CountCommandRootCodec.Mode.EMPTY) {
                initial += scope.bindEmpty(); scope = open(access)
            } else {
                require(CountCommandRootCodec.bound(scope.header.mode)) { "COMMAND_LEGACY_NOT_ENROLLED" }
                if (scope.header.namespace != scope.namespace) {
                    require(requireNotNull(scope.header.namespace).permitsInitialRegistration(scope.namespace) && scope.header.registration == null) {
                        "COMMAND_REGISTERED_NAMESPACE_CHANGED_REQUIRES_RECOVERY"
                    }
                    val registered = requireNotNull(tokens.localSyncAccess())
                    val installation = requireNotNull(tokens.registeredInstallationId())
                    require(registered.session == access.session && registered.deviceId == access.capturedDeviceId && tokens.isCurrentSyncAuthority(registered))
                    initial += scope.registerInitialNamespace(installation); scope = open(access)
                }
            }
            require(CountCommandRootCodec.admits(scope.header, intent.format)) { "COMMAND_FORMAT_NOT_ADMITTED" }
            val existing = scope.lookup(intent.commandId)
            if (existing != null) {
                require(existing.intent.inputHash == intent.inputHash && existing.intent.text() == intent.text()) { "COMMAND_INPUT_CONFLICT" }
                owner.verifyCommandBoundary(boundary, initial)
                Registered(reference(intent), existing.status.execution, true)
            } else {
                requireNotNull(lease) { "COMMAND_EXECUTION_IN_PROGRESS" }
                require(intent.action != CountCommandRecords.Action.LINKED_METRICS) { "COMMAND_CHILD_REQUIRES_PARENT_BATCH" }
                val tail = scope.header.pending.tail?.let { scope.required(it) }
                val prepared = CountCommandTransitionRules.prepared(intent, admissionGeneration())
                val plan = CountCommandPendingRules.append(scope.header.pending, prepared, tail?.status)
                val old = listOfNotNull(tail)
                val after = plan.changes.map { change ->
                    if (change.before == null) CountCommandRowPlan.Aggregate(intent, change.after, null)
                    else requireNotNull(tail).let { CountCommandRowPlan.Aggregate(it.intent, change.after, it.outcome) }
                }
                val written = scope.write(CountCommandRowPlan.Batch(old, after), plan.after)
                owner.verifyCommandBoundary(boundary, initial + written)
                Registered(reference(intent), prepared.execution, false)
            }
        }
    }
        currentCoroutineContext().ensureActive()
        return if (result.alreadyRegistered) result else result.copy(ticket = FreshTicket(result.reference,
            caller, CountCommandRecords.text(intent.body(), "original_auth_generation"), requireNotNull(lease), freshRegistration = true)).also { handedOff = true }
        } finally { if (!handedOff) lease?.close() }
        } finally { CountCommandCostTiming.end(cost) }
    }

    suspend fun registerChild(parentRef: Reference, intent: CountCommandRecords.Intent): Registered {
        val cost = CountCommandCostTiming.begin(CountCommandCostTiming.Phase.T0)
        try {
        CountCommandEntryBoundary.requireTopLevel(database)
        val caller = requireNotNull(currentCoroutineContext()[Job])
        val lease = CountCommandExecutionLeases.acquire(owner, intent.commandId, caller)
        var handedOff = false
        val retiredChildLeases = arrayListOf<CountCommandExecutionLeases.Lease>()
        try {
        val result = ownTransaction { access ->
        require(intent.action == CountCommandRecords.Action.LINKED_METRICS &&
            CountCommandRecords.text(intent.body(), "original_auth_generation") == access.session.authentication.generation)
        CountCommandInputOwnership.requireForRegistration(intent, access)
        owner.maintenance(database) {
            var scope = open(access); scope.requireReference(parentRef)
            require(intent.namespace.hash == scope.namespace.hash &&
                CountCommandRecords.uuid(intent.body().getValue("input").jsonObject, "parent_command_id") == parentRef.commandId)
            val boundary = owner.captureCommandBoundary()
            var parent = scope.required(parentRef.commandId)
            require(parent.intent.inputHash == parentRef.inputHash)
            val parentResult = requireNotNull(parent.outcome) { "COMMAND_PARENT_NOT_COMMITTED" }
            val childInput = intent.body().getValue("input").jsonObject
            require(parentResult.action == CountCommandRecords.Action.COUNT_ADD &&
                parentResult.kind == CountCommandOutcome.Kind.APPLIED &&
                parentResult.primaryFactUuid == CountCommandRecords.uuid(childInput, "source_fact_uuid") &&
                parentResult.effects.single().objectUuid == CountCommandRecords.uuid(childInput, "habit_uuid")) { "COMMAND_CHILD_SOURCE_CHANGED" }
            val existing = scope.lookup(intent.commandId)
            if (existing != null) {
                require(existing.intent.text() == intent.text() && existing.status.parent == parentRef.commandId && parent.status.child == intent.commandId)
                owner.verifyCommandBoundary(boundary, emptyList())
                Registered(reference(intent), existing.status.execution, true)
            } else {
                requireNotNull(lease) { "COMMAND_EXECUTION_IN_PROGRESS" }
                val replacementExpected = arrayListOf<CountCommandJournal.Expected>()
                parent.status.child?.let { oldId ->
                    val retiredLease = requireNotNull(CountCommandExecutionLeases.acquire(owner, oldId, caller)) {
                        "COMMAND_CHILD_EXECUTION_STILL_RUNNING"
                    }
                    retiredChildLeases += retiredLease
                    val oldChild = scope.required(oldId)
                    require(oldChild.status.parent == parentRef.commandId && oldChild.outcome == null &&
                        oldChild.status.execution == CountCommandRecords.Execution.PREPARED) { "COMMAND_CHILD_REQUIRES_RESOLUTION" }
                    require(admissionGeneration() == oldChild.status.businessGenerationAtAdmission) {
                        "COMMAND_CHILD_NO_EFFECTS_UNRESOLVED"
                    }
                    // The root/member/native rows and complete dirty-trigger schema are already
                    // authenticated by open. Unchanged generation proves no committed business
                    // DML since this child's T0; it is never inferred from absent outbox/Outcome.
                    val previous = oldChild.status.previous?.let { scope.required(it) }
                    val next = oldChild.status.next?.let { scope.required(it) }
                    val terminal = CountCommandTransitionRules.replaceProvenUncommittedChild(oldChild.status, oldChild.intent)
                    val removal = CountCommandPendingRules.remove(scope.header.pending, oldChild.status, terminal, previous?.status, next?.status)
                    val old = listOfNotNull(oldChild, previous, next).associateBy { it.intent.commandId }
                    val after = removal.changes.map { change -> old.getValue(change.after.commandId).let {
                        CountCommandRowPlan.Aggregate(it.intent, change.after, it.outcome)
                    } }
                    replacementExpected += scope.write(CountCommandRowPlan.Batch(old.values.toList(), after), removal.after)
                    scope = open(access)
                    parent = scope.required(parentRef.commandId)
                    val detached = CountCommandTransitionRules.detachUncommittedChild(parent.status, scope.required(oldId).status)
                    val parentPlan = CountCommandPendingRules.retain(scope.header.pending, parent.status, detached)
                    replacementExpected += scope.write(CountCommandRowPlan.Batch(listOf(parent),
                        listOf(CountCommandRowPlan.Aggregate(parent.intent, detached, parent.outcome))), parentPlan.after)
                    scope = open(access); parent = scope.required(parentRef.commandId)
                }
                val child = CountCommandTransitionRules.prepared(intent, admissionGeneration(), parentRef.commandId)
                val attached = CountCommandTransitionRules.attachChild(parent.status, intent)
                val tail = scope.required(requireNotNull(scope.header.pending.tail))
                val list = CountCommandPendingRules.appendChild(scope.header.pending, parent.status, attached, child, tail.status)
                val old = listOf(parent, tail).associateBy { it.intent.commandId }
                val after = list.changes.map { c -> if (c.before == null) CountCommandRowPlan.Aggregate(intent, c.after, null)
                    else old.getValue(c.after.commandId).let { CountCommandRowPlan.Aggregate(it.intent, c.after, it.outcome) } }
                val written = scope.write(CountCommandRowPlan.Batch(old.values.toList(), after), list.after)
                owner.verifyCommandBoundary(boundary, replacementExpected + written)
                Registered(reference(intent), child.execution, false)
            }
        }
    }
        currentCoroutineContext().ensureActive()
        return if (result.alreadyRegistered) result else result.copy(ticket = FreshTicket(result.reference,
            caller, CountCommandRecords.text(intent.body(), "original_auth_generation"), requireNotNull(lease))).also { handedOff = true }
        } finally { retiredChildLeases.forEach { it.close() }; if (!handedOff) lease?.close() }
        } finally { CountCommandCostTiming.end(cost) }
    }

    /** Resolve an OS-known widget ID through its complete immutable captured claim, never
     * a numeric ID or pending-list absence. This read cannot register or execute a command.
     * A miss is explicitly unresolved. Actual execution/replay still goes through recover().
     */
    suspend fun locateTypedWidget(commandId: String, claim: WidgetFactClaim, action: String): Reference? {
        require(com.dayforge.domain.model.isContractUuid(commandId) && claim.countPolicy != null &&
            action in setOf("increment", "undo")) { "COMMAND_WIDGET_COUNT_REQUIRED" }
        return ownTransaction { access -> owner.maintenance(database) {
            require(claim.session() == access.session && claim.deviceId == access.capturedDeviceId) {
                "COMMAND_WIDGET_CAPTURE_AUTHORITY_EXPIRED"
            }
            val scope = open(access, requireBound = false)
            require(claim.storageId != null && claim.storageId == scope.namespace.storage) { "COMMAND_WIDGET_STORAGE_CHANGED" }
            require(scope.header.mode != CountCommandRootCodec.Mode.LEGACY_UNENROLLED) { "COMMAND_LEGACY_NOT_ENROLLED" }
            if (scope.header.mode == CountCommandRootCodec.Mode.EMPTY) return@maintenance null
            val row = scope.lookup(commandId) ?: return@maintenance null
            require(row.intent.namespace == scope.namespace && row.intent.action ==
                if (action == "increment") CountCommandRecords.Action.COUNT_ADD else CountCommandRecords.Action.COUNT_UNDO) {
                "COMMAND_WIDGET_INPUT_CHANGED"
            }
            val input = row.intent.body().getValue("input").jsonObject
            require(CountCommandInputs.restoredWidget(input) == claim) { "COMMAND_WIDGET_INPUT_CHANGED" }
            reference(row.intent)
        } }
    }

    suspend fun locateLegacyWidget(commandId: String, claim: LegacyWidgetFactClaim): Reference? {
        claim.validate()
        require(com.dayforge.domain.model.isContractUuid(commandId) && claim.action in setOf("increment", "decrement"))
        return ownTransaction { access -> owner.maintenance(database) {
            require(claim.identity.session() == access.session && claim.identity.deviceId == access.capturedDeviceId) {
                "COMMAND_WIDGET_CAPTURE_AUTHORITY_EXPIRED"
            }
            val scope = open(access, requireBound = false)
            require(claim.identity.storageId != null && claim.identity.storageId == scope.namespace.storage) { "COMMAND_WIDGET_STORAGE_CHANGED" }
            require(scope.header.mode != CountCommandRootCodec.Mode.LEGACY_UNENROLLED) { "COMMAND_LEGACY_NOT_ENROLLED" }
            if (scope.header.mode == CountCommandRootCodec.Mode.EMPTY) return@maintenance null
            val row = scope.lookup(commandId) ?: return@maintenance null
            require(row.intent.namespace == scope.namespace && CountCommandLegacyWidgetInput.matches(row.intent) &&
                CountCommandLegacyWidgetInput.claim(row.intent.body().getValue("input").jsonObject) == claim) { "COMMAND_WIDGET_INPUT_CHANGED" }
            reference(row.intent)
        } }
    }

    /** Exact-ID historical resolution. A miss is UNRESOLVED, never NeverCommitted. Current
     * page/prompt permission and the complete known-ref NoEffects/origin/receipt recovery are
     * separate original-domain steps; this method neither retries nor invents input.
     */
    suspend fun resolve(ref: Reference): Located = ownTransaction { access ->
        val captured = owner.maintenance(database) {
            val scope = open(access, requireBound = false)
            // Compare the authenticated dataset before EMPTY/absence. Replacement is never
            // NeverCommitted, and locating history never emits an execution permission.
            scope.requireDataset(ref)
            require(CountCommandRootCodec.bound(scope.header.mode)) { "COMMAND_RESOLUTION_UNBOUND" }
            val old = scope.lookup(ref.commandId) ?: error("COMMAND_RESOLUTION_REQUIRES_COMPLETE_NO_EFFECTS")
            require(old.intent.inputHash == ref.inputHash && old.intent.namespace.hash == ref.namespaceHash)
            val kind = when {
                old.intent.namespace == scope.namespace -> LocationScope.CURRENT
                scope.header.registration?.previousNamespaceHash == old.intent.namespace.hash -> LocationScope.INITIAL_REGISTRATION_REVALIDATION_REQUIRED
                else -> LocationScope.ISOLATED
            }
            Located(ref, kind, old) to scope.authenticated.native()
        }
        // Original Room DAO/read-proof adapters can use their legitimate child Job. Keep
        // them outside maintenance; the primary PREPARING phase still forbids business DML.
        val located = captured.first
        require(located.scope != LocationScope.ISOLATED) { "COMMAND_CURRENT_NAMESPACE_ISOLATED" }
        located.historical.outcome?.let { result ->
            CountCommandCurrentFate(database, tokens).read(access, located.historical.intent, result)
        }
        owner.maintenance(database) {
            val scope = open(access, requireBound = false); scope.requireDataset(ref)
            require(CountCommandNativeRows.sameRoot(scope.authenticated.native(), captured.second) &&
                scope.required(ref.commandId).member() == located.historical.member()) { "COMMAND_RESOLUTION_CHANGED" }
        }
        located
    }

    /** Read-only historical result for a persisted follow-up locator. This never enters the
     * recovery execution issuer; a PREPARED/missing result stays unresolved. */
    suspend fun historicalCountResult(ref: Reference, habitUuid: String): DurableCommit {
        val located = resolve(ref)
        require(located.historical.intent.action in setOf(CountCommandRecords.Action.COUNT_ADD, CountCommandRecords.Action.COUNT_UNDO) &&
            CountCommandRecords.uuid(located.historical.intent.body().getValue("input").jsonObject, "habit_uuid") == habitUuid) {
            "COMMAND_FOLLOWUP_SOURCE_CHANGED"
        }
        return RecoveredCommit(ref, requireNotNull(located.historical.outcome) { "COMMAND_RESULT_UNRESOLVED" })
    }

    private class RecoveredCommit(override val reference: Reference, override val outcome: CountCommandOutcome) : DurableCommit
    sealed interface Recovery {
        class Committed internal constructor(val value: DurableCommit) : Recovery
        class Ready internal constructor(val intent: CountCommandRecords.Intent, val ticket: ExecutionTicket) : Recovery
        data class Unresolved(val reason: String) : Recovery
    }
    private data class RecoveryObservation(val located: Located?, val generation: String,
        val unchanged: Boolean, val terminal: Boolean)

    /** Same-ID cold/native recovery. Executability is issued only after the entire read
     * transaction, final fresh authorization and actual END returned. A signed PREPARED
     * member AND the same-dataset unchanged business generation establish the conditional
     * NoEffects case. Advanced generation deliberately remains unresolved pending the full
     * domain/source/fate route; an Outcome/outbox miss alone never grants execution.
     */
    suspend fun recover(ref: Reference): Recovery {
        val caller = requireNotNull(currentCoroutineContext()[Job])
        val lease = CountCommandExecutionLeases.acquire(owner, ref.commandId, caller)
            ?: return Recovery.Unresolved("ORIGINAL_OPERATION_STILL_RUNNING")
        var handedOff = false
        try {
            val observed = ownTransaction { access ->
                val captured = owner.maintenance(database) {
                    val scope = open(access, requireBound = false); scope.requireDataset(ref)
                    require(CountCommandRootCodec.bound(scope.header.mode)) { "COMMAND_RESOLUTION_UNBOUND" }
                    val row = scope.lookup(ref.commandId)
                        ?: return@maintenance Triple(null, scope.authenticated.native(), false)
                    require(row.intent.inputHash == ref.inputHash && row.intent.namespace.hash == ref.namespaceHash)
                    val location = when {
                        row.intent.namespace == scope.namespace -> LocationScope.CURRENT
                        scope.header.registration?.previousNamespaceHash == row.intent.namespace.hash -> LocationScope.INITIAL_REGISTRATION_REVALIDATION_REQUIRED
                        else -> LocationScope.ISOLATED
                    }
                    val generation = admissionGeneration()
                    require(generation >= row.status.businessGenerationAtAdmission) { "COMMAND_BUSINESS_COUNTER_REVERSED" }
                    Triple(Located(ref, location, row), scope.authenticated.native(), generation == row.status.businessGenerationAtAdmission)
                }
                val located = captured.first
                if (located != null) {
                    require(located.scope != LocationScope.ISOLATED) { "COMMAND_CURRENT_NAMESPACE_ISOLATED" }
                    located.historical.outcome?.let { outcome ->
                        CountCommandCurrentFate(database, tokens).read(access, located.historical.intent, outcome)
                    }
                }
                owner.maintenance(database) {
                    val scope = open(access, requireBound = false); scope.requireDataset(ref)
                    require(CountCommandNativeRows.sameRoot(scope.authenticated.native(), captured.second)) { "COMMAND_RESOLUTION_CHANGED" }
                    require(scope.lookup(ref.commandId)?.member() == located?.historical?.member()) { "COMMAND_RESOLUTION_CHANGED" }
                }
                RecoveryObservation(located, access.session.authentication.generation, captured.third,
                    located?.historical?.status?.execution in setOf(CountCommandRecords.Execution.CANCELLED, CountCommandRecords.Execution.INVALIDATED))
            }
            currentCoroutineContext().ensureActive()
            val located = observed.located ?: return Recovery.Unresolved("MISSING_COMPLETE_ORIGINAL_COMMAND")
            located.historical.outcome?.let { return Recovery.Committed(RecoveredCommit(ref, it)) }
            if (observed.terminal) return Recovery.Unresolved("ORIGINAL_COMMAND_TERMINAL_WITHOUT_BUSINESS_RESULT")
            if (located.scope != LocationScope.CURRENT) return Recovery.Unresolved("INITIAL_REGISTRATION_EXECUTION_PROOF_REQUIRED")
            require(located.historical.status.execution == CountCommandRecords.Execution.PREPARED)
            if (!observed.unchanged) return Recovery.Unresolved("ADVANCED_BUSINESS_REQUIRES_COMPLETE_NO_EFFECTS_PROOF")
            if (CountCommandRecords.text(located.historical.intent.body(), "original_auth_generation") != observed.generation)
                return Recovery.Unresolved("ORIGINAL_EXECUTION_SESSION_REVALIDATION_REQUIRED")
            lease.requireCaller(caller)
            // Authentication generation does not partition lookup/catalog identity. It still
            // participates in the ORIGINAL execution contract until a separate explicit
            // revalidation establishes an allowed resumed session. Locating old outcomes is
            // always separate from issuing permission to run an old pending action.
            return Recovery.Ready(located.historical.intent, FreshTicket(ref, caller, observed.generation, lease,
                located.historical.status.businessGenerationAtAdmission)).also { handedOff = true }
        } finally { if (!handedOff) lease.close() }
    }

    /** Separate complete recovery route. The existing recover()/unchanged-g fast contract
     * stays intact. No UI/ordinary caller can supply a witness or claim that absence is safe.
     * Current controlled Root+anchor continuity and original atomic T1/Outcome closure are
     * REQUIRED alongside the independent domain absence scan. Legacy DELETE absence alone
     * cannot satisfy that first part. This path never changes the immutable admission g.
     */
    suspend fun recoverAfterCompleteProof(ref: Reference): Recovery = recoverWithCompleteProof(ref, CompletePurpose.SAME_SESSION)

    /** Only an explicitly initiated fresh-session generic COUNT action may use this entry.
     * The old task/ticket/T0 APIs remain unchanged. Full namespace/dataset and original
     * business inputs must match; no old display claim is rebound to the current session. */
    suspend fun recoverAfterReauthentication(ref: Reference): Recovery =
        recoverWithCompleteProof(ref, CompletePurpose.EXPLICIT_GENERIC_REAUTH)

    private enum class CompletePurpose { SAME_SESSION, EXPLICIT_GENERIC_REAUTH }

    private suspend fun recoverWithCompleteProof(ref: Reference, purpose: CompletePurpose): Recovery {
        val caller = requireNotNull(currentCoroutineContext()[Job])
        val lease = CountCommandExecutionLeases.acquire(owner, ref.commandId, caller)
            ?: return Recovery.Unresolved("ORIGINAL_OPERATION_STILL_RUNNING")
        var handedOff = false
        try {
            val observed = ownTransaction { access ->
                val authority = requireNotNull(tokens.localCommandRecoveryAccess()) {
                    "COMMAND_COMPLETE_RECOVERY_REVALIDATION_REQUIRED"
                }
                require(authority.core == access) { "COMMAND_COMPLETE_RECOVERY_REVALIDATION_REQUIRED" }
                val captured = owner.maintenance(database) {
                    val scope = open(access, requireBound = false); scope.requireDataset(ref)
                    require(CountCommandRootCodec.bound(scope.header.mode)) { "COMMAND_RESOLUTION_UNBOUND" }
                    val row = scope.lookup(ref.commandId)
                        ?: return@maintenance Triple(null, scope.authenticated.native(), admissionGeneration())
                    require(row.intent.inputHash == ref.inputHash && row.intent.namespace.hash == ref.namespaceHash)
                    val location = when {
                        row.intent.namespace == scope.namespace -> LocationScope.CURRENT
                        scope.header.registration?.previousNamespaceHash == row.intent.namespace.hash -> LocationScope.INITIAL_REGISTRATION_REVALIDATION_REQUIRED
                        else -> LocationScope.ISOLATED
                    }
                    val current = admissionGeneration()
                    require(current >= row.status.businessGenerationAtAdmission) { "COMMAND_BUSINESS_COUNTER_REVERSED" }
                    Triple(Located(ref, location, row), scope.authenticated.native(), current)
                }
                val located = captured.first
                var domain: CountCommandDomainNoEffects.Checked? = null
                if (located != null) {
                    require(located.scope != LocationScope.ISOLATED) { "COMMAND_CURRENT_NAMESPACE_ISOLATED" }
                    val row = located.historical
                    if (row.outcome != null) CountCommandCurrentFate(database, tokens).read(access, row.intent, row.outcome)
                    else if (located.scope == LocationScope.CURRENT && row.status.execution == CountCommandRecords.Execution.PREPARED) {
                        val original = CountCommandRecords.text(row.intent.body(), "original_auth_generation")
                        val sameSession = original == access.session.authentication.generation
                        val admittedPurpose = when (purpose) {
                            CompletePurpose.SAME_SESSION -> sameSession
                            CompletePurpose.EXPLICIT_GENERIC_REAUTH -> !sameSession && CountCommandReauthentication.supports(row.intent)
                        }
                        if (admittedPurpose) domain = CountCommandDomainNoEffects.capture(database, row.intent)
                    }
                }
                val observation = owner.maintenance(database) {
                    val scope = open(access, requireBound = false); scope.requireDataset(ref)
                    require(CountCommandNativeRows.sameRoot(scope.authenticated.native(), captured.second) &&
                        admissionGeneration() == captured.third) { "COMMAND_RESOLUTION_CHANGED" }
                    require(scope.lookup(ref.commandId)?.member() == located?.historical?.member()) { "COMMAND_RESOLUTION_CHANGED" }
                    // The authenticated root contains this exact P/member and has no matching
                    // Outcome. Its MAC and current protected control anchor were checked at
                    // both ends. Ordinary/restore/GC paths may not downgrade APPLIED to P;
                    // the real private producer commits business+Outcome+root/anchor atomically.
                    CompleteRecoveryObservation(located, access.session.authentication.generation, domain?.let { proof ->
                        requireNotNull(located)
                        require(proof.inputHash == ref.inputHash)
                        FullNoEffectsWitness(ref, located.historical.member(), located.historical.status.businessGenerationAtAdmission,
                            captured.third, CountCommandRootAnchor.digest(captured.second), proof.storageHash, authority, purpose,
                            CountCommandRecords.text(located.historical.intent.body(), "original_auth_generation"))
                    })
                }
                require(tokens.localCommandRecoveryAccess() == authority) {
                    "COMMAND_COMPLETE_RECOVERY_REVALIDATION_REQUIRED"
                }
                observation
            }
            currentCoroutineContext().ensureActive()
            val located = observed.located ?: return Recovery.Unresolved("MISSING_COMPLETE_ORIGINAL_COMMAND")
            located.historical.outcome?.let { return Recovery.Committed(RecoveredCommit(ref, it)) }
            if (located.historical.status.execution != CountCommandRecords.Execution.PREPARED)
                return Recovery.Unresolved("ORIGINAL_COMMAND_TERMINAL_WITHOUT_BUSINESS_RESULT")
            if (located.scope != LocationScope.CURRENT) return Recovery.Unresolved("INITIAL_REGISTRATION_EXECUTION_PROOF_REQUIRED")
            val original = CountCommandRecords.text(located.historical.intent.body(), "original_auth_generation")
            when (purpose) {
                CompletePurpose.SAME_SESSION -> if (original != observed.generation)
                    return Recovery.Unresolved("ORIGINAL_EXECUTION_SESSION_REVALIDATION_REQUIRED")
                CompletePurpose.EXPLICIT_GENERIC_REAUTH -> {
                    if (!CountCommandReauthentication.supports(located.historical.intent))
                        return Recovery.Unresolved("COMMAND_REAUTHENTICATION_REQUIRES_ORIGINAL_DOMAIN_HANDLER")
                    if (original == observed.generation) return Recovery.Unresolved("COMMAND_REAUTHENTICATION_NOT_REQUIRED")
                }
            }
            val proof = requireNotNull(observed.proof) { "COMMAND_COMPLETE_NO_EFFECTS_REQUIRED" }
            lease.requireCaller(caller)
            // ownTransaction has proved final fresh authority and actual END of the complete
            // read. Retain only immutable observations, no SQL context/token across END.
            return Recovery.Ready(located.historical.intent,
                FreshTicket(ref, caller, observed.generation, lease, fullNoEffects = proof)).also { handedOff = true }
        } finally { if (!handedOff) lease.close() }
    }

    private data class FullNoEffectsWitness(val reference: Reference, val member: CountCommandRecords.CatalogMember,
        val admissionGeneration: Long, val currentGeneration: Long, val rootAnchor: String, val domainHash: String,
        val authority: LocalCommandRecoveryAccess, val purpose: CompletePurpose, val originalAuthenticationGeneration: String)
    private data class CompleteRecoveryObservation(val located: Located?, val generation: String, val proof: FullNoEffectsWitness?)

    /** Locate an existing child through the authenticated exact parent edge. A null return
     * is only an observation: it never issues an execution ticket or proves NeverCommitted.
     * Registration, if attempted, is a separate operation with its own checks.
     */
    suspend fun linkedChild(parentRef: Reference): Located? = ownTransaction { access ->
        val captured = owner.maintenance(database) {
            val scope = open(access); scope.requireReference(parentRef)
            val parent = scope.required(parentRef.commandId)
            val result = requireNotNull(parent.outcome) { "COMMAND_PARENT_NOT_COMMITTED" }
            require(parent.intent.inputHash == parentRef.inputHash && result.kind == CountCommandOutcome.Kind.APPLIED &&
                result.action == CountCommandRecords.Action.COUNT_ADD) { "COMMAND_PARENT_NOT_COMMITTED" }
            val child = parent.status.child?.let { id -> scope.required(id).also { row ->
                val input = row.intent.body().getValue("input").jsonObject
                require(row.status.parent == parentRef.commandId && row.intent.action == CountCommandRecords.Action.LINKED_METRICS &&
                    CountCommandRecords.uuid(input, "parent_command_id") == parentRef.commandId &&
                    CountCommandRecords.uuid(input, "source_fact_uuid") == result.primaryFactUuid &&
                    CountCommandRecords.uuid(input, "habit_uuid") == result.effects.single().objectUuid) { "COMMAND_CHILD_SOURCE_CHANGED" }
                scope.requireReference(reference(row.intent))
            } }
            Triple(parent, child, scope.authenticated.native())
        }
        CountCommandCurrentFate(database, tokens).read(access, captured.first.intent, requireNotNull(captured.first.outcome))
        captured.second?.let { child -> child.outcome?.let { result ->
            CountCommandCurrentFate(database, tokens).read(access, child.intent, result)
        } }
        owner.maintenance(database) {
            val scope = open(access); scope.requireReference(parentRef)
            require(CountCommandNativeRows.sameRoot(scope.authenticated.native(), captured.third) &&
                scope.required(parentRef.commandId).member() == captured.first.member() &&
                captured.second?.let { scope.required(it.intent.commandId).member() == it.member() } != false) {
                "COMMAND_RESOLUTION_CHANGED"
            }
        }
        captured.second?.let { Located(reference(it.intent), LocationScope.CURRENT, it) }
    }

    /** Authenticate the actual parent relation and immutable outcome for a known child.
     * No new command/input is derived from numeric IDs or from a pending-list search.
     */
    suspend fun recoverParent(childRef: Reference): DurableCommit {
        val historical = ownTransaction { access ->
            val captured = owner.maintenance(database) {
                val scope = open(access); scope.requireReference(childRef)
                val child = scope.required(childRef.commandId)
                require(child.intent.inputHash == childRef.inputHash && child.intent.action == CountCommandRecords.Action.LINKED_METRICS)
                val parent = scope.required(requireNotNull(child.status.parent))
                val result = requireNotNull(parent.outcome)
                val input = child.intent.body().getValue("input").jsonObject
                require(parent.status.child == childRef.commandId && parent.status.execution == CountCommandRecords.Execution.APPLIED &&
                    result.action == CountCommandRecords.Action.COUNT_ADD && result.primaryFactUuid == CountCommandRecords.uuid(input, "source_fact_uuid") &&
                    result.effects.single().objectUuid == CountCommandRecords.uuid(input, "habit_uuid")) { "COMMAND_CHILD_SOURCE_CHANGED" }
                Triple(parent, child, scope.authenticated.native())
            }
            CountCommandCurrentFate(database, tokens).read(access, captured.first.intent, requireNotNull(captured.first.outcome))
            owner.maintenance(database) {
                val scope = open(access); scope.requireReference(childRef)
                require(CountCommandNativeRows.sameRoot(scope.authenticated.native(), captured.third) &&
                    scope.required(captured.first.intent.commandId).member() == captured.first.member() &&
                    scope.required(childRef.commandId).member() == captured.second.member()) { "COMMAND_RESOLUTION_CHANGED" }
            }
            captured.first
        }
        return RecoveredCommit(reference(historical.intent), requireNotNull(historical.outcome))
    }

    sealed interface GenericInvalidationPlan { val reference: Reference }
    private inner class IssuedInvalidation(override val reference: Reference, val row: CountCommandRowPlan.Aggregate,
        val root: com.dayforge.data.local.entity.CountCommandRecoveryRootEntity, val generation: Long,
        val domain: CountCommandInvalidationDomain.Changed, val authority: LocalCommandRecoveryAccess,
        val caller: Job, val lease: CountCommandExecutionLeases.Lease) : GenericInvalidationPlan {
        val issuer = this@CountCommandStore
        var admitted = false
    }
    class InvalidatedGeneric internal constructor(val reference: Reference,
        val reason: CountCommandInvalidationDomain.Reason, val pending: Boolean)

    /** An existing terminal status is a historical failure result, not a fabricated NO_EFFECT
     * Outcome or an execution permission. Only this exact limited generic reason is handled. */
    suspend fun invalidatedGeneric(ref: Reference): InvalidatedGeneric? = ownTransaction { access -> owner.maintenance(database) {
        val scope = open(access); scope.requireReference(ref)
        val row = scope.required(ref.commandId)
        if (!CountCommandReauthentication.supportsCountInvalidation(row.intent) || row.intent.capture != CountCommandRecords.Capture.EXECUTION_INPUT ||
            row.status.execution != CountCommandRecords.Execution.INVALIDATED || row.status.parent != null || row.status.child != null) return@maintenance null
        require(row.intent.inputHash == ref.inputHash && row.outcome == null)
        val reason = CountCommandInvalidationDomain.Reason.entries.singleOrNull { it.name == row.status.reason } ?: return@maintenance null
        InvalidatedGeneric(ref, reason, row.status.pending)
    } }

    /** New explicit current-page decision only. A structured original-domain mismatch is
     * necessary but insufficient: the complete current NoEffects proof must also succeed. */
    suspend fun prepareGenericInvalidation(ref: Reference): GenericInvalidationPlan? {
        val caller = requireNotNull(currentCoroutineContext()[Job])
        val lease = CountCommandExecutionLeases.acquire(owner, ref.commandId, caller) ?: return null
        var handedOff = false
        try {
            val observed = ownTransaction { access ->
                val authority = requireNotNull(tokens.localCommandRecoveryAccess()) { "COMMAND_INVALIDATION_AUTHORITY_CHANGED" }
                require(authority.core == access) { "COMMAND_INVALIDATION_AUTHORITY_CHANGED" }
                if (access.capabilities?.contains("facts.append") == false) return@ownTransaction null
                val captured = owner.maintenance(database) {
                    val scope = open(access); scope.requireReference(ref)
                    val row = scope.required(ref.commandId)
                    require(row.intent.inputHash == ref.inputHash)
                    if (!CountCommandReauthentication.supportsCountInvalidation(row.intent) || row.intent.capture != CountCommandRecords.Capture.EXECUTION_INPUT ||
                        row.status.execution != CountCommandRecords.Execution.PREPARED || row.outcome != null ||
                        row.status.parent != null || row.status.child != null) return@maintenance null
                    val currentGeneration = admissionGeneration()
                    require(currentGeneration >= row.status.businessGenerationAtAdmission) { "COMMAND_BUSINESS_COUNTER_REVERSED" }
                    // Under the existing complete dirty-trigger/control contract, no business
                    // DML means no stored plan change. Keep ordinary same-g recovery on its old path.
                    if (currentGeneration == row.status.businessGenerationAtAdmission) return@maintenance null
                    Triple(row, scope.authenticated.native(), currentGeneration)
                } ?: return@ownTransaction null
                // This bounded preflight is only a hint to avoid a second complete scan for
                // unchanged advanced-g recovery. It cannot issue a terminal decision.
                if (!CountCommandInvalidationDomain.potentialChange(database, tokens, sessions, captured.first.intent, access)) return@ownTransaction null
                val noEffects = CountCommandDomainNoEffects.capture(database, captured.first.intent)
                require(noEffects.inputHash == ref.inputHash)
                val changed = CountCommandInvalidationDomain.validated(database, tokens, sessions, captured.first.intent, access)
                    ?: return@ownTransaction null
                owner.maintenance(database) {
                    val scope = open(access); scope.requireReference(ref)
                    require(CountCommandNativeRows.sameRoot(scope.authenticated.native(), captured.second) &&
                        admissionGeneration() == captured.third && scope.required(ref.commandId).member() == captured.first.member()) {
                        "COMMAND_INVALIDATION_REVALIDATION_REQUIRED"
                    }
                }
                require(tokens.localCommandRecoveryAccess() == authority) { "COMMAND_INVALIDATION_AUTHORITY_CHANGED" }
                Triple(captured, changed, authority)
            } ?: return null
            currentCoroutineContext().ensureActive(); lease.requireCaller(caller)
            return IssuedInvalidation(ref, observed.first.first, observed.first.second, observed.first.third,
                observed.second, observed.third, caller, lease).also { handedOff = true }
        } finally { if (!handedOff) lease.close() }
    }

    suspend fun releaseGenericInvalidation(plan: GenericInvalidationPlan) {
        val issued = plan as? IssuedInvalidation ?: error("COMMAND_INVALIDATION_FOREIGN_PROOF")
        require(issued.issuer === this && issued.caller === currentCoroutineContext()[Job]) { "COMMAND_INVALIDATION_CALLER_EXPIRED" }
        val release = synchronized(issued) { if (issued.admitted) false else { issued.admitted = true; true } }
        if (release) issued.lease.close()
    }

    /** Metadata-only actual T1: no business callback and no Outcome. The old input and ID
     * remain in the complete catalog. A failed/unknown END is never returned as invalidated. */
    suspend fun finishGenericInvalidation(plan: GenericInvalidationPlan): InvalidatedGeneric {
        val issued = plan as? IssuedInvalidation ?: error("COMMAND_INVALIDATION_FOREIGN_PROOF")
        require(issued.issuer === this && issued.caller === currentCoroutineContext()[Job] && issued.caller.isActive) {
            "COMMAND_INVALIDATION_CALLER_EXPIRED"
        }
        issued.lease.requireCaller(currentCoroutineContext()[Job])
        synchronized(issued) { require(!issued.admitted) { "COMMAND_INVALIDATION_ALREADY_ADMITTED" }; issued.admitted = true }
        try {
            return ownTransaction { access ->
                require(issued.authority.core == access && tokens.localCommandRecoveryAccess() == issued.authority) { "COMMAND_INVALIDATION_AUTHORITY_CHANGED" }
                owner.maintenance(database) {
                    val scope = open(access); scope.requireReference(issued.reference)
                    require(CountCommandNativeRows.sameRoot(scope.authenticated.native(), issued.root) && admissionGeneration() == issued.generation &&
                        scope.required(issued.reference.commandId).member() == issued.row.member()) { "COMMAND_INVALIDATION_REVALIDATION_REQUIRED" }
                }
                val current = CountCommandInvalidationDomain.validated(database, tokens, sessions, issued.row.intent, access)
                require(current != null && current.reason == issued.domain.reason && current.hash == issued.domain.hash) { "COMMAND_INVALIDATION_REVALIDATION_REQUIRED" }
                owner.maintenance(database) {
                    val scope = open(access); scope.requireReference(issued.reference)
                    val row = scope.required(issued.reference.commandId)
                    require(CountCommandNativeRows.sameRoot(scope.authenticated.native(), issued.root) && admissionGeneration() == issued.generation &&
                        row.member() == issued.row.member()) { "COMMAND_INVALIDATION_REVALIDATION_REQUIRED" }
                    require(row.status.execution == CountCommandRecords.Execution.PREPARED && row.outcome == null &&
                        row.status.parent == null && row.status.child == null)
                    val boundary = owner.captureCommandBoundary()
                    val terminal = CountCommandTransitionRules.abandon(row.status, row.intent, cancelled = false, reason = issued.domain.reason.name)
                    val retained = CountCommandPendingRules.retain(scope.header.pending, row.status, terminal)
                    val expected = scope.write(CountCommandRowPlan.Batch(listOf(row),
                        listOf(CountCommandRowPlan.Aggregate(row.intent, terminal, null))), retained.after)
                    owner.verifyCommandBoundary(boundary, expected)
                }
                require(tokens.localCommandRecoveryAccess() == issued.authority) { "COMMAND_INVALIDATION_AUTHORITY_CHANGED" }
                InvalidatedGeneric(issued.reference, issued.domain.reason, pending = true)
            }
        } finally { issued.lease.close() }
    }

    /** Existing failed result delivery; it neither cancels a P nor closes an applied follow-up. */
    suspend fun deliverInvalidatedGeneric(result: InvalidatedGeneric, isConsumerCurrent: () -> Boolean) = ownTransaction { access ->
        require(isConsumerCurrent()) { "COMMAND_RECOVERY_CONSUMER_CLOSED" }
        owner.maintenance(database) {
            val scope = open(access); scope.requireReference(result.reference)
            val row = scope.required(result.reference.commandId)
            require(row.intent.inputHash == result.reference.inputHash && CountCommandReauthentication.supportsCountInvalidation(row.intent) &&
                row.intent.capture == CountCommandRecords.Capture.EXECUTION_INPUT && row.outcome == null &&
                row.status.execution == CountCommandRecords.Execution.INVALIDATED && row.status.reason == result.reason.name &&
                row.status.parent == null && row.status.child == null) { "COMMAND_INVALIDATION_RESULT_CHANGED" }
            val boundary = owner.captureCommandBoundary()
            if (!row.status.pending) {
                require(row.status.delivery == CountCommandRecords.Delivery.DELIVERED)
                owner.verifyCommandBoundary(boundary, emptyList())
            } else {
                val previous = row.status.previous?.let { scope.required(it) }
                val next = row.status.next?.let { scope.required(it) }
                val terminal = CountCommandTransitionRules.delivered(row.status)
                val removal = CountCommandPendingRules.remove(scope.header.pending, row.status, terminal, previous?.status, next?.status)
                val old = listOfNotNull(row, previous, next).associateBy { it.intent.commandId }
                val after = removal.changes.map { change -> old.getValue(change.after.commandId).let {
                    CountCommandRowPlan.Aggregate(it.intent, change.after, it.outcome)
                } }
                val expected = scope.write(CountCommandRowPlan.Batch(old.values.toList(), after), removal.after)
                owner.verifyCommandBoundary(boundary, expected)
            }
        }
        require(isConsumerCurrent()) { "COMMAND_RECOVERY_CONSUMER_CLOSED" }
    }

    /** Historical metric result, not an executable/form authority. Its private implementation
     * binds both members and the exact current primary/business version until a fresh T2.
     */
    sealed interface CompletedMetricGroup {
        val parent: Reference
        val child: Reference
        val count: Int
        val pending: Boolean
        val preferenceReady: Boolean
    }
    private inner class IssuedMetricGroup(val parentRow: CountCommandRowPlan.Aggregate,
        val childRow: CountCommandRowPlan.Aggregate,
        val root: com.dayforge.data.local.entity.CountCommandRecoveryRootEntity,
        val generation: Long, val authority: LocalCommandRecoveryAccess,
        override val preferenceReady: Boolean) : CompletedMetricGroup {
        val issuer = this@CountCommandStore
        override val parent = reference(parentRow.intent)
        override val child = reference(childRow.intent)
        override val count = requireNotNull(childRow.outcome).effects.size
        override val pending = parentRow.status.pending || childRow.status.pending
    }

    /** Read a completed whole group without rebuilding its old form or changing preferences.
     * A PREPARED child is deliberately left to the original explicit form/recovery route.
     */
    suspend fun completedMetricGroup(ref: Reference,
        preferences: com.dayforge.data.local.PreferencesManager): CompletedMetricGroup? = ownTransaction { access ->
        val authority = requireNotNull(tokens.localCommandRecoveryAccess()) { "COMMAND_METRIC_DELIVERY_REVALIDATION_REQUIRED" }
        require(authority.core == access) { "COMMAND_METRIC_DELIVERY_REVALIDATION_REQUIRED" }
        val captured = owner.maintenance(database) {
            val scope = open(access); scope.requireReference(ref)
            val selected = scope.required(ref.commandId)
            require(selected.intent.inputHash == ref.inputHash)
            val parent = if (selected.intent.action == CountCommandRecords.Action.LINKED_METRICS)
                scope.required(requireNotNull(selected.status.parent)) else selected
            val child = parent.status.child?.let { scope.required(it) } ?: return@maintenance null
            if (selected.intent.action == CountCommandRecords.Action.LINKED_METRICS &&
                (selected.status.execution != CountCommandRecords.Execution.APPLIED ||
                    selected.intent.commandId != child.intent.commandId || selected.member() != child.member())) return@maintenance null
            if (child.status.execution != CountCommandRecords.Execution.APPLIED) return@maintenance null
            requireCompletedMetricGroup(parent, child)
            scope.requireReference(reference(parent.intent)); scope.requireReference(reference(child.intent))
            Triple(parent, child, scope.authenticated.native()) to admissionGeneration()
        } ?: return@ownTransaction null
        val (rows, generation) = captured
        CountCommandCurrentFate(database, tokens).read(access, rows.first.intent, requireNotNull(rows.first.outcome))
        CountCommandCurrentFate(database, tokens).read(access, rows.second.intent, requireNotNull(rows.second.outcome))
        val ready = preferences.hasNoPendingHintForHistoricalCount(access.session,
            requireNotNull(rows.first.outcome).effects.single().objectUuid, tokens)
        owner.maintenance(database) {
            val scope = open(access)
            require(CountCommandNativeRows.sameRoot(scope.authenticated.native(), rows.third) && admissionGeneration() == generation &&
                scope.required(rows.first.intent.commandId).member() == rows.first.member() &&
                scope.required(rows.second.intent.commandId).member() == rows.second.member()) { "COMMAND_METRIC_DELIVERY_REVALIDATION_REQUIRED" }
        }
        require(tokens.localCommandRecoveryAccess() == authority) { "COMMAND_METRIC_DELIVERY_REVALIDATION_REQUIRED" }
        IssuedMetricGroup(rows.first, rows.second, rows.third, generation, authority, ready)
    }

    /** Only the already accepted current result model uses this path. No form is republished,
     * no metrics are written, and an unbound preference hint is never cleared or treated absent.
     */
    suspend fun deliverCompletedMetricGroup(proof: CompletedMetricGroup,
        preferences: com.dayforge.data.local.PreferencesManager, isConsumerCurrent: () -> Boolean) {
        val issued = proof as? IssuedMetricGroup ?: error("COMMAND_METRIC_DELIVERY_FOREIGN_PROOF")
        require(issued.issuer === this) { "COMMAND_METRIC_DELIVERY_FOREIGN_PROOF" }
        ownTransaction { access ->
            require(isConsumerCurrent()) { "COMMAND_RECOVERY_CONSUMER_CLOSED" }
            require(issued.authority.core == access && tokens.localCommandRecoveryAccess() == issued.authority) {
                "COMMAND_METRIC_DELIVERY_REVALIDATION_REQUIRED"
            }
            val rows = owner.maintenance(database) {
                val scope = open(access); scope.requireReference(issued.parent); scope.requireReference(issued.child)
                val parent = scope.required(issued.parent.commandId); val child = scope.required(issued.child.commandId)
                require(CountCommandNativeRows.sameRoot(scope.authenticated.native(), issued.root) &&
                    admissionGeneration() == issued.generation && parent.member() == issued.parentRow.member() &&
                    child.member() == issued.childRow.member()) { "COMMAND_METRIC_DELIVERY_REVALIDATION_REQUIRED" }
                requireCompletedMetricGroup(parent, child)
                parent to child
            }
            CountCommandCurrentFate(database, tokens).read(access, rows.first.intent, requireNotNull(rows.first.outcome))
            CountCommandCurrentFate(database, tokens).read(access, rows.second.intent, requireNotNull(rows.second.outcome))
            require(issued.preferenceReady && preferences.hasNoPendingHintForHistoricalCount(access.session,
                requireNotNull(rows.first.outcome).effects.single().objectUuid, tokens)) { "COMMAND_METRIC_PREFERENCE_UNRESOLVED" }
            require(isConsumerCurrent()) { "COMMAND_RECOVERY_CONSUMER_CLOSED" }
            owner.maintenance(database) {
                var scope = open(access)
                require(CountCommandNativeRows.sameRoot(scope.authenticated.native(), issued.root) &&
                    admissionGeneration() == issued.generation) { "COMMAND_METRIC_DELIVERY_REVALIDATION_REQUIRED" }
                val boundary = owner.captureCommandBoundary()
                val expected = arrayListOf<CountCommandJournal.Expected>()
                for (ref in listOf(issued.child, issued.parent)) {
                    val current = scope.required(ref.commandId)
                    if (current.status.delivery == CountCommandRecords.Delivery.DELIVERED) continue
                    val previous = current.status.previous?.let { scope.required(it) }
                    val next = current.status.next?.let { scope.required(it) }
                    val terminal = CountCommandTransitionRules.delivered(current.status)
                    val removal = CountCommandPendingRules.remove(scope.header.pending, current.status, terminal, previous?.status, next?.status)
                    val old = listOfNotNull(current, previous, next).associateBy { it.intent.commandId }
                    val after = removal.changes.map { change -> old.getValue(change.after.commandId).let {
                        CountCommandRowPlan.Aggregate(it.intent, change.after, it.outcome)
                    } }
                    expected += scope.write(CountCommandRowPlan.Batch(old.values.toList(), after), removal.after)
                    scope = open(access)
                }
                owner.verifyCommandBoundary(boundary, expected)
            }
            require(preferences.hasNoPendingHintForHistoricalCount(access.session,
                requireNotNull(rows.first.outcome).effects.single().objectUuid, tokens)) { "COMMAND_METRIC_PREFERENCE_UNRESOLVED" }
            require(tokens.localCommandRecoveryAccess() == issued.authority) { "COMMAND_METRIC_DELIVERY_REVALIDATION_REQUIRED" }
            require(isConsumerCurrent()) { "COMMAND_RECOVERY_CONSUMER_CLOSED" }
        }
    }

    /** A value-only read observation for an explicit current Panel decision. It retains no
     * Job/Tx/account/execution lease across the user's wait and cannot authorize a callback. */
    sealed interface ObsoleteFollowUp {
        val reference: Reference
        val reason: CountCommandObsoleteFollowUp.Reason
        val preferenceReady: Boolean
    }
    private inner class IssuedObsoleteFollowUp(val parentRow: CountCommandRowPlan.Aggregate,
        val childRow: CountCommandRowPlan.Aggregate?,
        val root: com.dayforge.data.local.entity.CountCommandRecoveryRootEntity,
        val generation: Long, val authority: LocalCommandRecoveryAccess,
        val domain: CountCommandObsoleteFollowUp.Checked,
        override val preferenceReady: Boolean) : ObsoleteFollowUp {
        val issuer = this@CountCommandStore
        override val reference = reference(parentRow.intent)
        override val reason = domain.reason
    }
    class EndedFollowUp internal constructor(val reference: Reference)

    /** Same-ID resolution after an unknown closing END. This only reports a persisted
     * terminal member; it never infers CLOSED from a vanished source or an old observation. */
    suspend fun closedFollowUp(ref: Reference): EndedFollowUp? = ownTransaction { access ->
        val row = owner.maintenance(database) {
            val scope = open(access); scope.requireReference(ref)
            val selected = scope.required(ref.commandId)
            require(selected.intent.inputHash == ref.inputHash && selected.intent.namespace.hash == ref.namespaceHash) {
                "COMMAND_FOLLOWUP_REFERENCE_CHANGED"
            }
            val parent = if (selected.intent.action == CountCommandRecords.Action.LINKED_METRICS) {
                if (selected.status.execution != CountCommandRecords.Execution.INVALIDATED ||
                    selected.status.delivery != CountCommandRecords.Delivery.CLOSED ||
                    selected.status.reason != CountCommandTransitionRules.OBSOLETE_FOLLOWUP_CHILD_REASON) return@maintenance null
                scope.required(requireNotNull(selected.status.parent))
            } else selected
            if (parent.intent.action != CountCommandRecords.Action.COUNT_ADD ||
                parent.status.execution != CountCommandRecords.Execution.APPLIED ||
                parent.status.delivery != CountCommandRecords.Delivery.CLOSED) return@maintenance null
            val child = parent.status.child?.let { scope.required(it) }
            if (selected.intent.action == CountCommandRecords.Action.LINKED_METRICS)
                require(child != null && child.member() == selected.member() && child.intent.commandId == selected.intent.commandId) {
                    "COMMAND_FOLLOWUP_CHILD_UNRESOLVED"
                }
            requireClosedObsoleteFollowUp(parent, child)
            Triple(parent, child, scope.authenticated.native()) to admissionGeneration()
        } ?: return@ownTransaction null
        val (rows, generation) = row
        CountCommandCurrentFate(database, tokens).read(access, rows.first.intent, requireNotNull(rows.first.outcome))
        owner.maintenance(database) {
            val scope = open(access); scope.requireReference(ref)
            require(CountCommandNativeRows.sameRoot(scope.authenticated.native(), rows.third) && admissionGeneration() == generation &&
                scope.required(rows.first.intent.commandId).member() == rows.first.member() &&
                (rows.second?.let { scope.required(it.intent.commandId).member() == it.member() } != false)) {
                "COMMAND_FOLLOWUP_REVALIDATION_REQUIRED"
            }
        }
        EndedFollowUp(ref)
    }

    suspend fun obsoleteFollowUp(ref: Reference,
        preferences: com.dayforge.data.local.PreferencesManager): ObsoleteFollowUp? {
        val caller = requireNotNull(currentCoroutineContext()[Job])
        val leases = arrayListOf<CountCommandExecutionLeases.Lease>()
        leases += CountCommandExecutionLeases.acquire(owner, ref.commandId, caller) ?: return null
        try {
            return ownTransaction { access ->
                val authority = requireNotNull(tokens.localCommandRecoveryAccess()) { "COMMAND_FOLLOWUP_AUTHORITY_CHANGED" }
                require(authority.core == access) { "COMMAND_FOLLOWUP_AUTHORITY_CHANGED" }
                val captured = owner.maintenance(database) {
                    val scope = open(access); scope.requireReference(ref)
                    val parent = scope.required(ref.commandId)
                    require(parent.intent.inputHash == ref.inputHash && parent.intent.namespace.hash == ref.namespaceHash) {
                        "COMMAND_FOLLOWUP_REFERENCE_CHANGED"
                    }
                    if (!isPendingCountFollowUp(parent)) return@maintenance null
                    val child = parent.status.child?.let { scope.required(it) }
                    if (child?.status?.execution == CountCommandRecords.Execution.APPLIED) return@maintenance null // B only.
                    requireUncommittedFollowUpChild(parent, child)
                    Triple(parent, child, scope.authenticated.native()) to admissionGeneration()
                } ?: return@ownTransaction null
                val (rows, generation) = captured
                rows.second?.let { child ->
                    val lease = CountCommandExecutionLeases.acquire(owner, child.intent.commandId, caller)
                        ?: return@ownTransaction null
                    leases += lease
                }
                CountCommandCurrentFate(database, tokens).read(access, rows.first.intent, requireNotNull(rows.first.outcome))
                val domain = CountCommandObsoleteFollowUp.read(database, tokens, sessions, rows.first.intent,
                    requireNotNull(rows.first.outcome), access) ?: return@ownTransaction null
                rows.second?.let { child ->
                    require(generation >= child.status.businessGenerationAtAdmission) { "COMMAND_BUSINESS_COUNTER_REVERSED" }
                    require(CountCommandDomainNoEffects.capture(database, child.intent).inputHash == child.intent.inputHash)
                }
                val ready = preferences.hasNoPendingHintForHistoricalCount(access.session,
                    requireNotNull(rows.first.outcome).effects.single().objectUuid, tokens)
                owner.maintenance(database) {
                    val scope = open(access)
                    require(CountCommandNativeRows.sameRoot(scope.authenticated.native(), rows.third) && admissionGeneration() == generation &&
                        scope.required(rows.first.intent.commandId).member() == rows.first.member() &&
                        (rows.second?.let { scope.required(it.intent.commandId).member() == it.member() } != false)) {
                        "COMMAND_FOLLOWUP_REVALIDATION_REQUIRED"
                    }
                }
                require(tokens.localCommandRecoveryAccess() == authority) { "COMMAND_FOLLOWUP_AUTHORITY_CHANGED" }
                IssuedObsoleteFollowUp(rows.first, rows.second, rows.third, generation, authority, domain, ready)
            }
        } finally { leases.asReversed().forEach { it.close() } }
    }

    /** Explicit decision only. Fresh leases are acquired parent first, child second; a
     * second acquisition failure releases the first. No existing prompt close locator can
     * manufacture this proof. Immutable rows remain catalogued after pending removal. */
    suspend fun endObsoleteFollowUp(proof: ObsoleteFollowUp,
        preferences: com.dayforge.data.local.PreferencesManager, isConsumerCurrent: () -> Boolean): EndedFollowUp {
        val issued = proof as? IssuedObsoleteFollowUp ?: error("COMMAND_FOLLOWUP_FOREIGN_PROOF")
        require(issued.issuer === this) { "COMMAND_FOLLOWUP_FOREIGN_PROOF" }
        val caller = requireNotNull(currentCoroutineContext()[Job])
        val leases = arrayListOf<CountCommandExecutionLeases.Lease>()
        try {
            for (id in listOfNotNull(issued.reference.commandId, issued.childRow?.intent?.commandId))
                leases += CountCommandExecutionLeases.acquire(owner, id, caller) ?: error("COMMAND_EXECUTION_IN_PROGRESS")
            return ownTransaction { access ->
                require(isConsumerCurrent()) { "COMMAND_RECOVERY_CONSUMER_CLOSED" }
                require(issued.authority.core == access && tokens.localCommandRecoveryAccess() == issued.authority) {
                    "COMMAND_FOLLOWUP_AUTHORITY_CHANGED"
                }
                owner.maintenance(database) {
                    val scope = open(access); scope.requireReference(issued.reference)
                    val parent = scope.required(issued.reference.commandId)
                    val child = parent.status.child?.let { scope.required(it) }
                    require(CountCommandNativeRows.sameRoot(scope.authenticated.native(), issued.root) &&
                        admissionGeneration() == issued.generation && parent.member() == issued.parentRow.member() &&
                        child?.member() == issued.childRow?.member()) { "COMMAND_FOLLOWUP_REVALIDATION_REQUIRED" }
                    require(isPendingCountFollowUp(parent)) { "COMMAND_FOLLOWUP_REVALIDATION_REQUIRED" }
                    requireUncommittedFollowUpChild(parent, child)
                }
                CountCommandCurrentFate(database, tokens).read(access, issued.parentRow.intent, requireNotNull(issued.parentRow.outcome))
                val current = CountCommandObsoleteFollowUp.read(database, tokens, sessions, issued.parentRow.intent,
                    requireNotNull(issued.parentRow.outcome), access)
                require(current != null && current.reason == issued.domain.reason && current.hash == issued.domain.hash) {
                    "COMMAND_FOLLOWUP_REVALIDATION_REQUIRED"
                }
                issued.childRow?.let { child ->
                    require(issued.generation >= child.status.businessGenerationAtAdmission) { "COMMAND_BUSINESS_COUNTER_REVERSED" }
                    require(CountCommandDomainNoEffects.capture(database, child.intent).inputHash == child.intent.inputHash)
                }
                val habit = requireNotNull(issued.parentRow.outcome).effects.single().objectUuid
                require(issued.preferenceReady && preferences.hasNoPendingHintForHistoricalCount(access.session, habit, tokens)) {
                    "COMMAND_FOLLOWUP_PREFERENCE_UNRESOLVED"
                }
                require(isConsumerCurrent()) { "COMMAND_RECOVERY_CONSUMER_CLOSED" }
                owner.maintenance(database) {
                    var scope = open(access)
                    require(CountCommandNativeRows.sameRoot(scope.authenticated.native(), issued.root) &&
                        admissionGeneration() == issued.generation) { "COMMAND_FOLLOWUP_REVALIDATION_REQUIRED" }
                    val boundary = owner.captureCommandBoundary()
                    val expected = arrayListOf<CountCommandJournal.Expected>()
                    for (id in listOfNotNull(issued.childRow?.intent?.commandId, issued.reference.commandId)) {
                        val row = scope.required(id)
                        val terminal = if (row.intent.action == CountCommandRecords.Action.LINKED_METRICS)
                            CountCommandTransitionRules.endObsoleteUncommittedChild(row.status, row.intent)
                        else CountCommandTransitionRules.closeObsoleteFollowUp(row.status,
                            row.status.child?.let { scope.required(it).status })
                        val previous = row.status.previous?.let { scope.required(it) }
                        val next = row.status.next?.let { scope.required(it) }
                        val removal = CountCommandPendingRules.remove(scope.header.pending, row.status, terminal, previous?.status, next?.status)
                        val old = listOfNotNull(row, previous, next).associateBy { it.intent.commandId }
                        val after = removal.changes.map { change -> old.getValue(change.after.commandId).let {
                            CountCommandRowPlan.Aggregate(it.intent, change.after, it.outcome)
                        } }
                        expected += scope.write(CountCommandRowPlan.Batch(old.values.toList(), after), removal.after)
                        scope = open(access)
                    }
                    requireClosedObsoleteFollowUp(scope.required(issued.reference.commandId),
                        issued.childRow?.let { scope.required(it.intent.commandId) })
                    owner.verifyCommandBoundary(boundary, expected)
                }
                require(preferences.hasNoPendingHintForHistoricalCount(access.session, habit, tokens)) { "COMMAND_FOLLOWUP_PREFERENCE_UNRESOLVED" }
                require(tokens.localCommandRecoveryAccess() == issued.authority) { "COMMAND_FOLLOWUP_AUTHORITY_CHANGED" }
                require(isConsumerCurrent()) { "COMMAND_RECOVERY_CONSUMER_CLOSED" }
                EndedFollowUp(issued.reference)
            }
        } finally { leases.asReversed().forEach { it.close() } }
    }

    private fun isPendingCountFollowUp(parent: CountCommandRowPlan.Aggregate): Boolean {
        val result = parent.outcome ?: return false
        return parent.intent.action == CountCommandRecords.Action.COUNT_ADD && parent.status.parent == null &&
            parent.status.execution == CountCommandRecords.Execution.APPLIED &&
            parent.status.delivery == CountCommandRecords.Delivery.FOLLOW_UP_PENDING &&
            result.kind == CountCommandOutcome.Kind.APPLIED &&
            result.followUp == CountCommandOutcome.FollowUp.RECHECK_ORIGINAL_RULES
    }

    private fun requireUncommittedFollowUpChild(parent: CountCommandRowPlan.Aggregate, child: CountCommandRowPlan.Aggregate?) {
        if (child == null) { require(parent.status.child == null); return }
        val input = child.intent.body().getValue("input").jsonObject
        require(child.intent.action == CountCommandRecords.Action.LINKED_METRICS && child.status.parent == parent.intent.commandId &&
            parent.status.child == child.intent.commandId && child.status.execution == CountCommandRecords.Execution.PREPARED &&
            child.status.delivery == CountCommandRecords.Delivery.PENDING_RESOLUTION && child.status.child == null && child.outcome == null &&
            child.intent.namespace == parent.intent.namespace &&
            CountCommandRecords.uuid(input, "parent_command_id") == parent.intent.commandId &&
            CountCommandRecords.uuid(input, "source_fact_uuid") == requireNotNull(parent.outcome).primaryFactUuid &&
            CountCommandRecords.uuid(input, "habit_uuid") == requireNotNull(parent.outcome).effects.single().objectUuid) {
            "COMMAND_FOLLOWUP_CHILD_UNRESOLVED"
        }
    }
    private fun requireClosedObsoleteFollowUp(parent: CountCommandRowPlan.Aggregate, child: CountCommandRowPlan.Aggregate?) {
        require(parent.status.execution == CountCommandRecords.Execution.APPLIED && parent.status.delivery == CountCommandRecords.Delivery.CLOSED &&
            !parent.status.pending && parent.outcome != null)
        if (child == null) require(parent.status.child == null) else {
            require(parent.status.child == child.intent.commandId && child.status.parent == parent.intent.commandId &&
                child.intent.action == CountCommandRecords.Action.LINKED_METRICS && child.intent.namespace == parent.intent.namespace &&
                child.status.execution == CountCommandRecords.Execution.INVALIDATED && child.status.delivery == CountCommandRecords.Delivery.CLOSED &&
                child.status.reason == CountCommandTransitionRules.OBSOLETE_FOLLOWUP_CHILD_REASON && child.outcome == null && !child.status.pending)
            val input = child.intent.body().getValue("input").jsonObject
            require(CountCommandRecords.uuid(input, "parent_command_id") == parent.intent.commandId &&
                CountCommandRecords.uuid(input, "source_fact_uuid") == requireNotNull(parent.outcome).primaryFactUuid &&
                CountCommandRecords.uuid(input, "habit_uuid") == requireNotNull(parent.outcome).effects.single().objectUuid)
        }
    }

    private fun requireCompletedMetricGroup(parent: CountCommandRowPlan.Aggregate, child: CountCommandRowPlan.Aggregate) {
        val result = requireNotNull(parent.outcome); val childResult = requireNotNull(child.outcome)
        val input = child.intent.body().getValue("input").jsonObject
        require(parent.intent.action == CountCommandRecords.Action.COUNT_ADD &&
            parent.status.execution == CountCommandRecords.Execution.APPLIED && result.kind == CountCommandOutcome.Kind.APPLIED &&
            result.followUp == CountCommandOutcome.FollowUp.RECHECK_ORIGINAL_RULES &&
            parent.status.delivery in setOf(CountCommandRecords.Delivery.METRICS_COMMITTED, CountCommandRecords.Delivery.DELIVERED) &&
            child.intent.action == CountCommandRecords.Action.LINKED_METRICS && child.status.execution == CountCommandRecords.Execution.APPLIED &&
            childResult.kind == CountCommandOutcome.Kind.APPLIED && childResult.effects.isNotEmpty() &&
            child.status.delivery in setOf(CountCommandRecords.Delivery.RESULT_PENDING, CountCommandRecords.Delivery.DELIVERED) &&
            parent.status.child == child.intent.commandId && child.status.parent == parent.intent.commandId &&
            CountCommandRecords.uuid(input, "parent_command_id") == parent.intent.commandId &&
            CountCommandRecords.uuid(input, "source_fact_uuid") == result.primaryFactUuid &&
            CountCommandRecords.uuid(input, "habit_uuid") == result.effects.single().objectUuid &&
            parent.intent.namespace == child.intent.namespace) { "COMMAND_CHILD_SOURCE_CHANGED" }
    }

    /** Native cold discovery reads only the complete authenticated pending chain, never a
     * state-filter scan of all delivered history. Cursor progress is memory-only and is
     * promoted only after the fresh-authorized real transaction has completed. Enumerating
     * a command does not grant execution, publication or automatic T2 delivery.
     */
    suspend fun discover(maximumRows: Int, continuation: Discovery? = null): DiscoveryPage {
        require(maximumRows in 1..256)
        val oldCursor = continuation?.let { it as? IssuedDiscovery ?: error("COMMAND_DISCOVERY_FOREIGN_OWNER") }
        oldCursor?.let { old -> synchronized(old) { require(old.issuer === this && !old.busy); old.busy = true } }
        return try {
            val result = ownTransaction { access -> owner.maintenance(database) {
                val scope = open(access, requireBound = false)
                if (scope.header.mode == CountCommandRootCodec.Mode.LEGACY_UNENROLLED)
                    com.dayforge.data.local.CountCommandStorageInitialization.verifyMandatory(sql)
                val page = if (scope.header.mode in setOf(CountCommandRootCodec.Mode.EMPTY, CountCommandRootCodec.Mode.LEGACY_UNENROLLED)) {
                    require(continuation == null) { "COMMAND_DISCOVERY_DATASET_CHANGED" }
                    Triple(emptyList<Located>(), true, null)
                } else {
                    val cursor = oldCursor ?: IssuedDiscovery(this@CountCommandStore, scope.rootIdentity(),
                        scope.header.datasetId, scope.header.pending, CountCommandPendingRules.Pager(scope.rootIdentity(), scope.header.pending))
                    require(cursor.datasetId == scope.header.datasetId && cursor.frontier == scope.header.pending &&
                        cursor.rootIdentity == scope.rootIdentity()) { "COMMAND_DISCOVERY_ROOT_CHANGED" }
                    val fork = cursor.pager.fork()
                    val page = scope.readPendingPage(fork, maximumRows)
                    Triple(page.first, page.second, IssuedDiscovery(this@CountCommandStore, cursor.rootIdentity,
                        cursor.datasetId, cursor.frontier, fork))
                }
                page to (scope.header.legacyCount ?: 0L)
            } }
            // ownTransaction has checked the whole returned value against actual outer END.
            currentCoroutineContext().ensureActive()
            DiscoveryPage(result.first.first, result.first.second, result.first.third.takeUnless { result.first.second }, result.second)
        } finally { oldCursor?.let { synchronized(it) { it.busy = false } } }
    }

    /** A conditional NoEffects observation, NOT an execution ticket. The baseline belongs
     * to the authenticated T0 Status and normal metadata/list writes cannot change it.
     * Advanced generation is unresolved here; the complete slower source/fate path must not
     * be replaced with a missing Outcome/outbox check. Original input/permission/domain
     * revalidation and old-writer retirement still precede any recovery execution issuer.
     */
    data class PreparedObservation(val reference: Reference, val unchangedBusinessSinceAdmission: Boolean)
    suspend fun inspectPrepared(ref: Reference): PreparedObservation = ownTransaction { access ->
        owner.maintenance(database) {
            val scope = open(access); scope.requireReference(ref)
            val row = scope.required(ref.commandId)
            require(row.intent.inputHash == ref.inputHash && row.status.execution == CountCommandRecords.Execution.PREPARED && row.outcome == null)
            val current = admissionGeneration()
            require(current >= row.status.businessGenerationAtAdmission) { "COMMAND_BUSINESS_COUNTER_REVERSED" }
            PreparedObservation(ref, current == row.status.businessGenerationAtAdmission)
        }
    }

    sealed interface IndependentEndPlan { val reference: Reference }
    private inner class IssuedIndependentEnd(override val reference: Reference,
        val row: CountCommandRowPlan.Aggregate, val proof: FullNoEffectsWitness) : IndependentEndPlan {
        val issuer = this@CountCommandStore
    }
    internal class EndedIndependent internal constructor(val reference: Reference,
        val pending: Boolean, val deliveryAllowed: Boolean)

    /** A complete unexecuted independent command may be explicitly cancelled. Neither
     * typed definition equality nor a surviving legacy target is required: an obsolete
     * input is exactly why a user may need to end it. This is explicit CANCELLED, not a
     * typed INVALIDATED assertion, and no live execution lease escapes. */
    internal suspend fun prepareIndependentEnd(ref: Reference): IndependentEndPlan? {
        val located = resolve(ref)
        if (located.scope != LocationScope.CURRENT || located.historical.intent.action != CountCommandRecords.Action.METRIC_APPEND ||
            located.historical.status.execution != CountCommandRecords.Execution.PREPARED ||
            located.historical.outcome != null) return null
        val current = requireNotNull(tokens.localCoreWriteAccess()) { "COMMAND_FRESH_AUTHORITY_REQUIRED" }
        // Match the original protocol-specific writer. Ordinary legacy recordValue
        // never required facts.append; typed execution retains its original requirement.
        if (!IndependentMetricInput.isLegacy(located.historical.intent) && current.capabilities?.contains("facts.append") == false) return null
        val same = CountCommandRecords.text(located.historical.intent.body(), "original_auth_generation") == current.session.authentication.generation
        val result = if (same) recoverAfterCompleteProof(ref) else recoverAfterReauthentication(ref)
        if (result !is Recovery.Ready) return null
        try {
            val issued = result.ticket as? FreshTicket ?: error("COMMAND_FOREIGN_EXECUTION_TICKET")
            require(issued.issuer === this && issued.caller === currentCoroutineContext()[Job])
            issued.lease.requireCaller(currentCoroutineContext()[Job])
            val proof = requireNotNull(issued.fullNoEffects) { "COMMAND_COMPLETE_NO_EFFECTS_REQUIRED" }
            require(proof.reference == ref && proof.member == located.historical.member() &&
                result.intent.text() == located.historical.intent.text())
            return IssuedIndependentEnd(ref, located.historical, proof)
        } finally { releaseUnadmitted(result.ticket) }
    }

    internal suspend fun endIndependent(plan: IndependentEndPlan, isConsumerCurrent: () -> Boolean): EndedIndependent {
        val issued = plan as? IssuedIndependentEnd ?: error("COMMAND_INDEPENDENT_END_FOREIGN_PROOF")
        require(issued.issuer === this)
        val caller = requireNotNull(currentCoroutineContext()[Job])
        val lease = CountCommandExecutionLeases.acquire(owner, issued.reference.commandId, caller)
            ?: error("ORIGINAL_OPERATION_STILL_RUNNING")
        try {
            return ownTransaction { access ->
                require(isConsumerCurrent()) { "COMMAND_RECOVERY_CONSUMER_CLOSED" }
                val proof = issued.proof
                require(proof.authority.core == access && tokens.localCommandRecoveryAccess() == proof.authority &&
                    (IndependentMetricInput.isLegacy(issued.row.intent) || access.capabilities?.contains("facts.append") != false)) {
                    "COMMAND_INDEPENDENT_END_AUTHORITY_CHANGED"
                }
                owner.maintenance(database) {
                    val scope = open(access); scope.requireReference(issued.reference)
                    val row = scope.required(issued.reference.commandId)
                    require(row.member() == proof.member && row.member() == issued.row.member() &&
                        CountCommandRootAnchor.digest(scope.authenticated.native()) == proof.rootAnchor &&
                        admissionGeneration() == proof.currentGeneration &&
                        row.status.businessGenerationAtAdmission == proof.admissionGeneration &&
                        row.intent.action == CountCommandRecords.Action.METRIC_APPEND &&
                        row.status.execution == CountCommandRecords.Execution.PREPARED && row.outcome == null) {
                        "COMMAND_INDEPENDENT_END_REVALIDATION_REQUIRED"
                    }
                    lease.requireCaller(caller)
                    val boundary = owner.captureCommandBoundary()
                    val status = CountCommandTransitionRules.abandon(row.status, row.intent, true, IndependentMetricInput.CANCELLED)
                    val retained = CountCommandPendingRules.retain(scope.header.pending, row.status, status)
                    val expected = scope.write(CountCommandRowPlan.Batch(listOf(row),
                        listOf(CountCommandRowPlan.Aggregate(row.intent, status, null))), retained.after)
                    owner.verifyCommandBoundary(boundary, expected)
                }
                require(tokens.localCommandRecoveryAccess() == proof.authority && isConsumerCurrent()) {
                    "COMMAND_INDEPENDENT_END_AUTHORITY_CHANGED"
                }
                EndedIndependent(issued.reference, pending = true, deliveryAllowed = true)
            }
        } finally { lease.close() }
    }

    /** Historical cancellation has no Saved/Outcome. First registration may display it,
     * while current-scope T2 remains a separate fresh permission. */
    internal suspend fun endedIndependent(ref: Reference): EndedIndependent? = ownTransaction { access -> owner.maintenance(database) {
        val scope = open(access, requireBound = false); scope.requireDataset(ref)
        require(CountCommandRootCodec.bound(scope.header.mode))
        val row = scope.lookup(ref.commandId) ?: return@maintenance null
        require(scope.header.namespace == scope.namespace) { "COMMAND_CURRENT_NAMESPACE_ISOLATED" }
        require(row.intent.inputHash == ref.inputHash && row.intent.namespace.hash == ref.namespaceHash &&
            row.intent.action == CountCommandRecords.Action.METRIC_APPEND)
        val current = row.intent.namespace == scope.namespace
        require(current || scope.header.registration?.previousNamespaceHash == row.intent.namespace.hash) {
            "COMMAND_CURRENT_NAMESPACE_ISOLATED"
        }
        if (row.status.execution != CountCommandRecords.Execution.CANCELLED ||
            row.status.reason != IndependentMetricInput.CANCELLED || row.outcome != null) return@maintenance null
        EndedIndependent(ref, row.status.pending, current)
    } }

    internal suspend fun deliverIndependentEnd(result: EndedIndependent, isConsumerCurrent: () -> Boolean) = ownTransaction { access ->
        require(isConsumerCurrent()) { "COMMAND_RECOVERY_CONSUMER_CLOSED" }
        owner.maintenance(database) {
            val scope = open(access); scope.requireReference(result.reference)
            val row = scope.required(result.reference.commandId)
            require(row.intent.inputHash == result.reference.inputHash && row.intent.action == CountCommandRecords.Action.METRIC_APPEND &&
                row.outcome == null && row.status.execution == CountCommandRecords.Execution.CANCELLED && row.status.reason == IndependentMetricInput.CANCELLED)
            val boundary = owner.captureCommandBoundary()
            if (!row.status.pending) {
                require(row.status.delivery == CountCommandRecords.Delivery.DELIVERED)
                owner.verifyCommandBoundary(boundary, emptyList())
            } else {
                val previous = row.status.previous?.let { scope.required(it) }
                val next = row.status.next?.let { scope.required(it) }
                val terminal = CountCommandTransitionRules.delivered(row.status)
                val removal = CountCommandPendingRules.remove(scope.header.pending, row.status, terminal, previous?.status, next?.status)
                val old = listOfNotNull(row, previous, next).associateBy { it.intent.commandId }
                val after = removal.changes.map { c -> old.getValue(c.after.commandId).let {
                    CountCommandRowPlan.Aggregate(it.intent, c.after, it.outcome)
                } }
                owner.verifyCommandBoundary(boundary, scope.write(CountCommandRowPlan.Batch(old.values.toList(), after), removal.after))
            }
        }
        require(isConsumerCurrent()) { "COMMAND_RECOVERY_CONSUMER_CLOSED" }
    }

    internal suspend fun deliverIndependent(ref: Reference, isConsumerCurrent: () -> Boolean) = ownTransaction { access ->
        require(isConsumerCurrent()) { "COMMAND_RECOVERY_CONSUMER_CLOSED" }
        val captured = owner.maintenance(database) {
            val scope = open(access); scope.requireReference(ref)
            val row = scope.required(ref.commandId)
            require(row.intent.action == CountCommandRecords.Action.METRIC_APPEND && row.intent.inputHash == ref.inputHash)
            row to scope.authenticated.native()
        }
        val current = captured.first
        CountCommandCurrentFate(database, tokens).read(access, current.intent, requireNotNull(current.outcome))
        owner.maintenance(database) {
            val scope = open(access); scope.requireReference(ref)
            require(CountCommandNativeRows.sameRoot(scope.authenticated.native(), captured.second) &&
                scope.required(ref.commandId).member() == current.member()) { "COMMAND_RESOLUTION_CHANGED" }
            require(isConsumerCurrent()) { "COMMAND_RECOVERY_CONSUMER_CLOSED" }
            val boundary = owner.captureCommandBoundary()
            if (!current.status.pending) {
                require(current.status.delivery == CountCommandRecords.Delivery.DELIVERED)
                owner.verifyCommandBoundary(boundary, emptyList())
            } else {
                val previous = current.status.previous?.let { scope.required(it) }
                val next = current.status.next?.let { scope.required(it) }
                val terminal = CountCommandTransitionRules.delivered(current.status)
                val plan = CountCommandPendingRules.remove(scope.header.pending, current.status, terminal, previous?.status, next?.status)
                val old = listOfNotNull(current, previous, next).associateBy { it.intent.commandId }
                val after = plan.changes.map { c -> old.getValue(c.after.commandId).let { CountCommandRowPlan.Aggregate(it.intent, c.after, it.outcome) } }
                owner.verifyCommandBoundary(boundary, scope.write(CountCommandRowPlan.Batch(old.values.toList(), after), plan.after))
            }
            require(isConsumerCurrent()) { "COMMAND_RECOVERY_CONSUMER_CLOSED" }
        }
    }

    /** T2 only accepts an already terminal result/committed metric group. Closing a required
     * follow-up and invalidating a child are deliberately distinct APIs needing their original
     * domain permission, not a generic delivered flag supplied by a UI.
     */
    suspend fun markDelivered(ref: Reference) = ownTransaction { access ->
        owner.maintenance(database) {
            val scope = open(access); scope.requireReference(ref)
            val boundary = owner.captureCommandBoundary()
            val current = scope.required(ref.commandId)
            require(current.intent.inputHash == ref.inputHash)
            if (!current.status.pending) {
                require(current.status.delivery == CountCommandRecords.Delivery.DELIVERED)
                owner.verifyCommandBoundary(boundary, emptyList())
            } else {
                val previous = current.status.previous?.let { scope.required(it) }
                val next = current.status.next?.let { scope.required(it) }
                val terminal = CountCommandTransitionRules.delivered(current.status)
                val plan = CountCommandPendingRules.remove(scope.header.pending, current.status, terminal, previous?.status, next?.status)
                val old = listOfNotNull(current, previous, next).associateBy { it.intent.commandId }
                val after = plan.changes.map { c -> old.getValue(c.after.commandId).let { CountCommandRowPlan.Aggregate(it.intent, c.after, it.outcome) } }
                val written = scope.write(CountCommandRowPlan.Batch(old.values.toList(), after), plan.after)
                owner.verifyCommandBoundary(boundary, written)
            }
        }
    }

    /** Only after an original permitted prompt skip or a complete current no-required-prompt
     * decision has returned. This records that completed action; it neither publishes old facts
     * nor permits cancelling an unresolved/applied metric child. Close-window alone never
     * calls this method. A repeat after unknown T2 END is read-only and verifies the same ID.
     */
    suspend fun markFollowUpClosed(ref: Reference, factUuid: String, habitUuid: String) = ownTransaction { access ->
        owner.maintenance(database) {
            val scope = open(access); scope.requireReference(ref)
            val current = scope.required(ref.commandId)
            val outcome = requireNotNull(current.outcome)
            require(current.intent.inputHash == ref.inputHash && outcome.action == CountCommandRecords.Action.COUNT_ADD &&
                outcome.kind == CountCommandOutcome.Kind.APPLIED && outcome.primaryFactUuid == factUuid &&
                outcome.effects.single().objectUuid == habitUuid &&
                outcome.followUp == CountCommandOutcome.FollowUp.RECHECK_ORIGINAL_RULES && current.status.child == null) {
                "COMMAND_FOLLOWUP_SOURCE_CHANGED"
            }
            val boundary = owner.captureCommandBoundary()
            if (current.status.delivery == CountCommandRecords.Delivery.CLOSED) {
                require(!current.status.pending)
                owner.verifyCommandBoundary(boundary, emptyList())
            } else {
                val previous = current.status.previous?.let { scope.required(it) }
                val next = current.status.next?.let { scope.required(it) }
                val terminal = CountCommandTransitionRules.closeFollowUp(current.status)
                val removal = CountCommandPendingRules.remove(scope.header.pending, current.status, terminal, previous?.status, next?.status)
                val old = listOfNotNull(current, previous, next).associateBy { it.intent.commandId }
                val after = removal.changes.map { change -> old.getValue(change.after.commandId).let {
                    CountCommandRowPlan.Aggregate(it.intent, change.after, it.outcome)
                } }
                val expected = scope.write(CountCommandRowPlan.Batch(old.values.toList(), after), removal.after)
                owner.verifyCommandBoundary(boundary, expected)
            }
        }
    }

    /** Read bridge only. The sole implementation stays in this store's lexical frame;
     * neither UI nor an arbitrary callback receives a transaction/write capability. */
    sealed interface FormalMetricDeliveryScope

    private inner class IssuedFormalMetricDelivery(private val caller: Job) : FormalMetricDeliveryScope {
        private var sourcePhase = true
        private var active = true
        private suspend fun requireCaller() {
            currentCoroutineContext().ensureActive()
            check(active && currentCoroutineContext()[Job] === caller) { "COMMAND_METRIC_SEQUENCE_EXPIRED" }
        }
        suspend fun requireSourceOwner(database: HabitDatabase, tokens: TokenManager, sessions: AccountSessionCoordinator) {
            requireCaller()
            check(sourcePhase && database === this@CountCommandStore.database && tokens === this@CountCommandStore.tokens &&
                sessions === this@CountCommandStore.sessions) { "COMMAND_METRIC_SEQUENCE_FOREIGN_SOURCE" }
        }
        suspend fun beginDelivery() {
            requireCaller()
            check(sourcePhase) { "COMMAND_METRIC_SEQUENCE_EXPIRED" }
            sourcePhase = false
        }
        fun close() { active = false }
    }

    /** T1 is already complete. One finite FIFO account turn covers fresh source proof,
     * original preference persistence, and one separate original T2. No retry loop, user/
     * network wait or nested Room transaction can extend this turn. All T2 authority checks
     * remain fresh; the source read proof has ended before preferences or T2 begin. */
    suspend fun deliverFormalMetricResult(parent: CountCommandReceipt, child: CountCommandReceipt,
        snapshot: WidgetFactSnapshot, reader: WidgetFactReader, isConsumerCurrent: () -> Boolean) {
        if (CompletionActionTiming.enabled) CompletionActionTiming.mark(CompletionActionTiming.Stage.METRIC_DELIVERY_ENTER)
        require(parent.recorded && parent.requiresMetricFollowUp && parent.sourceHabitUuid == snapshot.habit.uuid &&
            parent.factUuid == snapshot.claim.completionUuid && snapshot.claim.habitUuid == snapshot.habit.uuid &&
            parent.committed.outcome.action == CountCommandRecords.Action.COUNT_ADD &&
            child.recorded && child.committed.outcome.action == CountCommandRecords.Action.LINKED_METRICS &&
            parent.committed.outcome.evidence.profile == CountCommandEvidence.Profile.FORMAL_V5 &&
            child.committed.outcome.evidence.profile == CountCommandEvidence.Profile.FORMAL_V5 &&
            parent.namespaceHash == child.namespaceHash && parent.datasetId == child.datasetId) { "COMMAND_FOLLOWUP_SOURCE_CHANGED" }
        CountCommandEntryBoundary.requireTopLevel(database)
        val schemaReference = com.dayforge.data.local.ReplicaBusinessReferenceSchema.read(owner.context)
        sessions.exclusive {
            if (CompletionActionTiming.enabled) CompletionActionTiming.mark(CompletionActionTiming.Stage.METRIC_DELIVERY_ACCOUNT_ACQUIRED)
            val scope = IssuedFormalMetricDelivery(requireNotNull(currentCoroutineContext()[Job]))
            try {
                reader.publishFormalMetricPreference(scope, snapshot, isConsumerCurrent)
                scope.beginDelivery()
                deliverMetricWhileAccountLocked(schemaReference, parent.committed.reference, child.committed.reference)
            } finally { scope.close() }
        }
    }

    /** Closed operation, not an arbitrary while-locked write callback. Keep the original
     * T2 entry/seal/final checks and actual-END confirmation in the same order as ownTransaction. */
    private suspend fun deliverMetricWhileAccountLocked(schemaReference: com.dayforge.data.local.ReplicaReferenceSchema,
        parent: Reference, child: Reference) {
        val cost = CountCommandCostTiming.begin(CountCommandCostTiming.Phase.T2)
        try {
        CountCommandEntryBoundary.requireTopLevel(database)
        val access = requireNotNull(tokens.localCoreWriteAccess()) { "COMMAND_FRESH_AUTHORITY_REQUIRED" }
        val installation = tokens.registeredInstallationId()
        val completed = owner.primaryTransaction(database) {
            CountCommandCostTiming.measure(Step.APPROVED_SCHEMA) {
                com.dayforge.data.local.ApprovedBusinessSchema.requireCleanupInTransaction(sql, schemaReference)
            }
            markMetricDeliveredInTransaction(parent, child, access)
            owner.maintenance(database) { owner.sealCommandOperation() }
            require(tokens.localCoreWriteAccess() == access) { "COMMAND_FINAL_AUTHORITY_CHANGED" }
            require(tokens.registeredInstallationId() == installation) { "COMMAND_INSTALLATION_CHANGED" }
        }
        completed.end.requireCandidate(completed.value)
        } finally { CountCommandCostTiming.end(cost) }
    }

    /** One metadata-only T2 after the original current prompt/preference delivery succeeds.
     * Child business and the parent transition were already atomic in T1. Both removals
     * share this transaction and final fresh authority; a failed T2 changes neither result.
     */
    suspend fun markMetricDelivered(parentRef: Reference, childRef: Reference) = ownTransaction { access ->
        markMetricDeliveredInTransaction(parentRef, childRef, access)
    }

    private suspend fun markMetricDeliveredInTransaction(parentRef: Reference, childRef: Reference,
        access: LocalCoreWriteAccess) {
        owner.maintenance(database) {
            val scope = open(access)
            // Cover every baseline/overlay read in the new pure preparation, not merely
            // the later physical writes; extra metadata writes can never become baseline.
            val boundary = owner.captureCommandBoundary()
            val plan = CountCommandCostTiming.measure(Step.T2_PLAN_PREPARE) {
                CountCommandMetricDeliveryPlan.prepare(database, tokens, owner, scope.authenticated,
                    scope.namespace, parentRef, childRef)
            }
            try {
                val expected = if (plan.steps == 0) emptyList() else CountCommandCostTiming.measure(Step.T2_PLAN_PUBLISH) {
                    scope.publishMetricDelivery(plan)
                }.also {
                    // Actual persisted H2, not the pure planned header. Retain complete schema,
                    // root MAC/anchor, namespace and registration verification after publication.
                    open(access)
                }
                owner.verifyCommandBoundary(boundary, expected)
            } finally { plan.close() }
        }
    }

    /** Read participant in the original Goal display/writer transaction. It signs nothing
     * and cannot start/nest a primary writer. SQLite keeps this current pending decision in
     * the same actual transaction as the following original Goal mutation. */
    suspend fun requirePendingGoalInTransaction(ref: Reference, habitUuid: String, factUuid: String) {
        require(database.inTransaction()) { "COMMAND_GOAL_TRANSACTION_REQUIRED" }
        val access = requireNotNull(tokens.localCoreWriteAccess()) { "COMMAND_FRESH_AUTHORITY_REQUIRED" }
        owner.maintenance(database) {
            val scope = open(access); scope.requireReference(ref)
            val row = scope.required(ref.commandId)
            val outcome = requireNotNull(row.outcome) { "COMMAND_GOAL_NOT_PENDING" }
            require(row.intent.inputHash == ref.inputHash && row.status.pending &&
                row.status.execution == CountCommandRecords.Execution.APPLIED &&
                outcome.action == CountCommandRecords.Action.COUNT_ADD &&
                outcome.kind == CountCommandOutcome.Kind.APPLIED &&
                outcome.followUp == CountCommandOutcome.FollowUp.RECHECK_ORIGINAL_RULES &&
                outcome.primaryFactUuid == factUuid && outcome.effects.single().objectUuid == habitUuid) {
                "COMMAND_GOAL_NOT_PENDING"
            }
        }
        require(tokens.localCoreWriteAccess() == access) { "COMMAND_FINAL_AUTHORITY_CHANGED" }
    }

    /** The private lexical bridge owns Room's legitimate child Job itself. Admission never
     * escapes as a transferable value: another Job cannot start an arbitrary transaction with
     * a still-active old caller. The domain callback must not receive Participant.
     */
    suspend fun <T> producerTransaction(ticket: ExecutionTicket, access: LocalCoreWriteAccess,
        producer: suspend (Participant) -> ProducerValue<T>): CommittedWrite<T> {
        val cost = CountCommandCostTiming.begin(CountCommandCostTiming.Phase.T1)
        try {
        currentCoroutineContext().ensureActive()
        require(!database.inTransaction()) { "COMMAND_OUTER_TRANSACTION_REQUIRED" }
        val issued = ticket as? FreshTicket ?: error("COMMAND_FOREIGN_EXECUTION_TICKET")
        require(issued.issuer === this && issued.caller === currentCoroutineContext()[Job] && issued.caller?.isActive != false &&
            issued.generation == access.session.authentication.generation) { "COMMAND_TICKET_CALLER_EXPIRED" }
        issued.lease.requireCaller(currentCoroutineContext()[Job])
        synchronized(issued) { require(!issued.admitted) { "COMMAND_TICKET_ALREADY_ADMITTED" }; issued.admitted = true }
        try {
        val installation = tokens.registeredInstallationId()
        val schemaReference = com.dayforge.data.local.ReplicaBusinessReferenceSchema.read(owner.context)
        val completed = owner.primaryTransaction(database) {
            CountCommandCostTiming.measure(Step.APPROVED_SCHEMA) {
                com.dayforge.data.local.ApprovedBusinessSchema.requireCleanupInTransaction(sql, schemaReference)
            }
            val participant = beginParticipant(issued, access)
            try {
                producer(participant).also { value ->
                    requireNotNull(value.candidate) { "COMMAND_OUTCOME_NOT_STAGED" }.requireOwnedBy(participant)
                    currentCoroutineContext().ensureActive()
                    require(tokens.registeredInstallationId() == installation) { "COMMAND_INSTALLATION_CHANGED" }
                    owner.maintenance(database) { owner.sealCommandOperation() }
                }
            } finally { participant.close() }
        }
        return committed(completed)
        } finally { issued.lease.close() }
        } finally { CountCommandCostTiming.end(cost) }
    }

    /** Admit only this Store's unused first-execution or sealed recovery ticket.
     * Actual T1 repeats its same-generation/full-witness boundary in beginParticipant;
     * optional COUNT qualification and caller flags cannot issue this authority. */
    internal suspend fun requireIndependentExecutionTicket(ticket: ExecutionTicket, intent: CountCommandRecords.Intent) {
        val issued = ticket as? FreshTicket ?: error("COMMAND_FOREIGN_EXECUTION_TICKET")
        require(issued.issuer === this && issued.caller === currentCoroutineContext()[Job] &&
            issued.reference == reference(intent) && intent.action == CountCommandRecords.Action.METRIC_APPEND)
        issued.lease.requireCaller(currentCoroutineContext()[Job])
        synchronized(issued) { require(!issued.admitted) { "COMMAND_TICKET_ALREADY_ADMITTED" } }
        // The Store's original private recovery witness is the absence authority. The
        // participant rechecks its exact current boundary in the actual T1 transaction.
        // Do not add a second global UUID/type/size policy after a valid same-g Ready.
        require(issued.freshRegistration || issued.recoveryGeneration != null || issued.fullNoEffects != null) {
            "COMMAND_INDEPENDENT_EXECUTION_PROOF_REQUIRED"
        }
    }

    /** Discard only the unconsumed process-local execution capability. Persistent PREPARED
     * remains available for a new complete resolution; this is not cancellation/delivery.
     */
    suspend fun releaseUnadmitted(ticket: ExecutionTicket) {
        val issued = ticket as? FreshTicket ?: error("COMMAND_FOREIGN_EXECUTION_TICKET")
        require(issued.issuer === this && issued.caller === currentCoroutineContext()[Job])
        val release = synchronized(issued) { if (issued.admitted) false else { issued.admitted = true; true } }
        if (release) issued.lease.close()
    }

    /** Metadata-only T1 for an original already-verified no-effect decision. No domain
     * callback runs and PREPARING remains active, so native business DML is forbidden for
     * the entire transaction. This must never be used for a missing/unresolved Outcome.
     */
    suspend fun completeCapturedNoEffect(ticket: ExecutionTicket): Committed = try {
        CountCommandEntryBoundary.requireTopLevel(database)
        sessions.exclusive(AccountSessionCoordinator.Work.NEXT_WRITE) {
            val access = requireNotNull(tokens.localCoreWriteAccess()) { "COMMAND_FRESH_AUTHORITY_REQUIRED" }
            val result = producerTransaction(ticket, access) { participant ->
                val intent = participant.frozenIntent
                require(intent.capture == CountCommandRecords.Capture.VERIFIED_NO_EFFECT)
                val reason = CountCommandRecords.NoEffectReason.valueOf(
                    CountCommandRecords.text(intent.body().getValue("input").jsonObject, "reason"))
                val plan = CountCommandProducerPlan.noEffect(database, intent, reason)
                participant.stage(plan.finish(emptyList(), emptySet()))
                val candidate = participant.verifyBeforeEnd()
                require(tokens.localCoreWriteAccess() == access) { "COMMAND_FINAL_AUTHORITY_CHANGED" }
                ProducerValue(Unit, candidate)
            }
            requireNotNull(result.command)
        }
    } finally { releaseUnadmitted(ticket) }

    private suspend fun beginParticipant(ticket: FreshTicket, access: LocalCoreWriteAccess): Participant {
        currentCoroutineContext().ensureActive(); require(database.inTransaction() && ticket.admitted)
        val ref = ticket.reference
        val caller = currentCoroutineContext()[Job]
        synchronized(participants) { require(participants[database] == null) { "COMMAND_PARTICIPANT_ALREADY_ACTIVE" } }
        return owner.maintenance(database) {
            val scope = open(access); scope.requireReference(ref)
            val aggregate = scope.required(ref.commandId)
            ticket.recoveryGeneration?.let { baseline ->
                require(aggregate.status.businessGenerationAtAdmission == baseline && admissionGeneration() == baseline) {
                    "COMMAND_RECOVERY_REVALIDATION_REQUIRED"
                }
            }
            require(aggregate.intent.inputHash == ref.inputHash && aggregate.status.execution == CountCommandRecords.Execution.PREPARED && aggregate.outcome == null) {
                "COMMAND_REQUIRES_RESOLUTION"
            }
            ticket.fullNoEffects?.let { proof ->
                val original = CountCommandRecords.text(aggregate.intent.body(), "original_auth_generation")
                val sameSession = original == access.session.authentication.generation
                val purposeValid = proof.originalAuthenticationGeneration == original && when (proof.purpose) {
                    CompletePurpose.SAME_SESSION -> sameSession
                    CompletePurpose.EXPLICIT_GENERIC_REAUTH -> !sameSession && CountCommandReauthentication.supports(aggregate.intent)
                }
                require(purposeValid && ticket.recoveryGeneration == null && proof.reference == ref && proof.member == aggregate.member() &&
                    proof.admissionGeneration == aggregate.status.businessGenerationAtAdmission &&
                    proof.currentGeneration >= proof.admissionGeneration && admissionGeneration() == proof.currentGeneration &&
                    CountCommandRootAnchor.digest(scope.authenticated.native()) == proof.rootAnchor &&
                    proof.domainHash.length == 64 && proof.domainHash.all { it in "0123456789abcdef" }) {
                    "COMMAND_COMPLETE_RECOVERY_REVALIDATION_REQUIRED"
                }
            }
            ticket.fullNoEffects?.let { proof ->
                // This new proof must expire before the domain callback on any registration
                // metadata change, even if auth generation and the permission set are stable.
                // Retain the original producer/final fresh checks; ordinary tickets do not
                // acquire this extra recovery-only observation.
                require(proof.authority.core == access && tokens.localCommandRecoveryAccess() == proof.authority) {
                    "COMMAND_COMPLETE_RECOVERY_REVALIDATION_REQUIRED"
                }
            }
            IssuedParticipant(this@CountCommandStore, ref, access, caller, scope.token, scope.authenticated,
                aggregate, owner.captureCommandBoundary()).also { issued ->
                synchronized(participants) { require(participants[database] == null); participants[database] = issued }
            }
        }
    }

    sealed interface Participant {
        val reference: Reference
        val frozenIntent: CountCommandRecords.Intent
        suspend fun beginBusiness()
        suspend fun stage(proof: CountCommandProducerPlan.Proven)
        suspend fun verifyBeforeEnd(): Candidate
        fun requireIssuedCandidate(ref: Reference, result: CountCommandOutcome)
        fun requireSameEnd(end: CountTrustRuntime.PrimaryEndReceipt)
        fun close()
    }
    private inner class IssuedParticipant(private val store: CountCommandStore, override val reference: Reference,
        private val access: LocalCoreWriteAccess, private val job: Job?, private val token: Any,
        private val beforeRoot: CountCommandSeal.Authenticated, private val before: CountCommandRowPlan.Aggregate,
        private val boundary: CountCommandJournal.Boundary) : Participant {
        override val frozenIntent: CountCommandRecords.Intent get() = before.intent
        private var staged: CountCommandOutcome? = null
        private var verifiedFooter: CountCommandOutcome? = null
        private var expected: List<CountCommandJournal.Expected>? = null
        private var active = true
        private suspend fun checkOwner() {
            currentCoroutineContext().ensureActive()
            require(active && currentCoroutineContext()[Job] === job && job?.isActive != false)
            synchronized(participants) { require(participants[store.database] === this) }
            store.owner.requirePrimaryReadToken(store.database, token)
        }

        override suspend fun beginBusiness() {
            checkOwner(); require(staged == null)
            require(before.intent.capture != CountCommandRecords.Capture.VERIFIED_NO_EFFECT) { "COMMAND_NO_EFFECT_CANNOT_ENTER_BUSINESS" }
            store.owner.maintenance(store.database) { store.owner.beginCommandBusiness(boundary) }
        }

        /** ProducerEffectProof has no public constructor: the separately frozen domain plan
         * binds original SQL allocation IDs and complete producer origins before making it.
         * Merely parsing an Outcome or reading the journal cannot create this capability.
         */
        override suspend fun stage(proof: CountCommandProducerPlan.Proven) {
            checkOwner(); require(staged == null)
            val outcome = proof.consume(store.database, before.intent)
            outcome.requireIntent(before.intent)
            store.owner.maintenance(store.database) {
                store.owner.verifyCommandBoundary(boundary, emptyList()) // Include ALL pre-stage writes, not just local tail.
                val scope = store.open(access)
                require(CountCommandNativeRows.sameRoot(scope.authenticated.native(), beforeRoot.native())) { "COMMAND_ROOT_CHANGED_DURING_BUSINESS" }
                val current = scope.required(reference.commandId)
                require(current.member() == before.member())
                val status = CountCommandTransitionRules.execution(current.status, current.intent, outcome)
                val parent = current.status.parent?.let { scope.required(it) }
                val changes = arrayListOf(CountCommandPendingRules.Change(current.status, status))
                if (parent != null) changes += CountCommandPendingRules.Change(parent.status,
                    CountCommandTransitionRules.childApplied(parent.status, current.status, status, outcome))
                val plan = CountCommandPendingRules.retainGroup(scope.header.pending, changes)
                val old = listOfNotNull(current, parent).associateBy { it.intent.commandId }
                val after = plan.changes.map { c -> old.getValue(c.after.commandId).let {
                    CountCommandRowPlan.Aggregate(it.intent, c.after, if (it === current) outcome else it.outcome)
                } }
                expected = scope.write(CountCommandRowPlan.Batch(old.values.toList(), after), plan.after)
                staged = outcome
            }
        }

        /** SELECT-only final command footer after original full producer/domain/COUNT footer.
         * Returns only a candidate. Actual outer END and final fresh auth remain the producer's
         * necessary publication gate; no caller can claim commit from this return alone.
         */
        override suspend fun verifyBeforeEnd(): Candidate {
            checkOwner()
            val result = requireNotNull(staged) { "COMMAND_OUTCOME_NOT_STAGED" }
            store.owner.maintenance(store.database) {
                store.owner.verifyCommandBoundary(boundary, requireNotNull(expected))
                val scope = store.open(access)
                val actual = scope.required(reference.commandId)
                require(actual.outcome?.text() == result.text() && actual.intent.inputHash == reference.inputHash)
            }
            verifiedFooter = result
            return Candidate.issue(reference, result, this)
        }
        override fun requireIssuedCandidate(ref: Reference, result: CountCommandOutcome) {
            require(ref == reference && verifiedFooter === result && staged === result) { "COMMAND_CANDIDATE_NOT_VERIFIED" }
        }
        override fun requireSameEnd(end: CountTrustRuntime.PrimaryEndReceipt) { require(verifiedFooter != null); end.requireEvidenceToken(token) }
        override fun close() { active = false; synchronized(participants) { if (participants[store.database] === this) participants.remove(store.database) } }

    }

    class Candidate private constructor(val reference: Reference, val outcome: CountCommandOutcome, private val owner: Participant) {
        internal fun requireOwnedBy(participant: Participant) {
            require(owner === participant) { "COMMAND_CANDIDATE_FOREIGN_TRANSACTION" }
            owner.requireIssuedCandidate(reference, outcome)
        }
        internal fun requireSameEnd(end: CountTrustRuntime.PrimaryEndReceipt) = owner.requireSameEnd(end)
        companion object {
            internal fun issue(ref: Reference, outcome: CountCommandOutcome, owner: Participant): Candidate {
                require(owner is IssuedParticipant) { "COMMAND_FOREIGN_PARTICIPANT" }
                owner.requireIssuedCandidate(ref, outcome); return Candidate(ref, outcome, owner)
            }
        }
    }

    /** Called after the real producer outer END, while its account coordination still holds.
     * Candidate and the entire returned envelope must belong to this exact committed Tx. The
     * emitted historical reference contains no retained Tx/Job/Context/read proof capability.
     */
    fun <T> committed(completed: CountTrustRuntime.PrimaryCompleted<ProducerValue<T>>): CommittedWrite<T> = Committed.confirm(completed)

    private suspend fun <T> ownTransaction(block: suspend (LocalCoreWriteAccess) -> T): T {
        CountCommandEntryBoundary.requireTopLevel(database)
        val schemaReference = com.dayforge.data.local.ReplicaBusinessReferenceSchema.read(owner.context)
        return sessions.exclusive(AccountSessionCoordinator.Work.NEXT_WRITE) {
            val access = requireNotNull(tokens.localCoreWriteAccess()) { "COMMAND_FRESH_AUTHORITY_REQUIRED" }
            val installation = tokens.registeredInstallationId()
            val completed = owner.primaryTransaction(database) {
                CountCommandCostTiming.measure(Step.APPROVED_SCHEMA) {
                    com.dayforge.data.local.ApprovedBusinessSchema.requireCleanupInTransaction(sql, schemaReference)
                }
                block(access).also {
                    owner.maintenance(database) { owner.sealCommandOperation() }
                    require(tokens.localCoreWriteAccess() == access) { "COMMAND_FINAL_AUTHORITY_CHANGED" }
                    require(tokens.registeredInstallationId() == installation) { "COMMAND_INSTALLATION_CHANGED" }
                }
            }
            completed.end.requireCandidate(completed.value)
            completed.value
        }
    }

    private fun admissionGeneration(): Long {
        CountTrustSchema.requireControl(sql)
        return sql.query("SELECT generation FROM count_trust_control WHERE id=1 LIMIT 2").use {
            require(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER)
            it.getLong(0).also { value -> require(value >= 0 && !it.moveToNext()) }
        }
    }

    private suspend fun open(access: LocalCoreWriteAccess, requireBound: Boolean = true): Scope {
        currentCoroutineContext().ensureActive()
        val token = owner.capturePrimaryReadToken(database)
        val pollContext = currentCoroutineContext()
        val lifetime = { owner.requirePrimaryReadToken(database, token); pollContext.ensureActive() }
        CountTrustSchema.requireControl(sql)
        val storage = sql.query("SELECT storageId FROM count_trust_control WHERE id=1 LIMIT 2").use {
            require(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_STRING)
            it.getString(0).also { _ -> require(!it.moveToNext()) }
        }
        com.dayforge.data.local.CountCommandPrimarySchema.requireStatisticsPreflight(sql)
        val schema = CountCommandCostTiming.measure(Step.OPEN_SCHEMA_NATIVE) { owner.schemaSnapshot() }
        // Exact new objects; retained old bytes enter full hash/MAC.
        CountCommandCostTiming.measure(Step.OPEN_SCHEMA_SHAPE) { CountCommandV20Schema.previousSchema(schema) }
        val hash = CountCommandCostTiming.measure(Step.OPEN_SCHEMA_HASH) { com.dayforge.data.local.CountCommandPrimarySchema.hash(schema) }
        val rows = CountCommandNativeRows(sql, lifetime)
        val nativeRoot = CountCommandCostTiming.measure(Step.OPEN_ROOT_NATIVE) { rows.root() }
        val root = CountCommandCostTiming.measure(Step.OPEN_ROOT_AUTH) {
            CountCommandSeal().authenticate(nativeRoot, storage, hash) {
                CountCommandCostTiming.measure(Step.OPEN_ANCHOR_NATIVE) { CountCommandRootAnchor.read(sql).hash }
            }
        }
        val ns = CountCommandRecords.Namespace(access.session.authentication.userId, access.session.serverInstanceId,
            access.session.syncEpoch, access.capturedDeviceId, storage, root.header.datasetId)
        require(!requireBound || CountCommandRootCodec.bound(root.header.mode) && root.header.namespace == ns) {
            "COMMAND_NAMESPACE_NOT_BOUND"
        }
        if (CountCommandRootCodec.bound(root.header.mode)) require(root.header.accountBinding == ns.catalogBinding) {
            "COMMAND_STORAGE_ACCOUNT_CHANGED"
        }
        root.header.registration?.let { require(tokens.registeredInstallationId() == it.installationId) { "COMMAND_INSTALLATION_CHANGED" } }
        if (root.header.mode == CountCommandRootCodec.Mode.ACCOUNT_BOUND_RETAINED)
            CountCommandRetainedPartition.verify(sql, root, lifetime)
        return Scope(token, ns, root, rows, lifetime)
    }

    private inner class Scope(val token: Any, val namespace: CountCommandRecords.Namespace,
        val authenticated: CountCommandSeal.Authenticated, private val rows: CountCommandNativeRows, private val lifetime: () -> Unit) {
        val header get() = authenticated.header
        fun enrollRetained(): List<CountCommandJournal.Expected> {
            lifetime()
            val plan = CountCommandRetainedEnrollmentPlan.create(authenticated, namespace)
            val expected = arrayListOf<CountCommandJournal.Expected>()
            for ((digest, bytes) in plan.blocks) {
                require(persistNode(digest, bytes.toByteArray()))
                expected += CountCommandJournal.Expected("count_command_nodes", "INSERT", digest)
            }
            expected += replaceRootAndAnchor(plan.header, enrollment = plan)
            return Collections.unmodifiableList(expected)
        }
        fun bindEmpty(): List<CountCommandJournal.Expected> {
            lifetime(); require(header.mode == CountCommandRootCodec.Mode.EMPTY)
            for (table in listOf("count_command_intents", "count_command_status", "count_command_results", "count_command_nodes")) {
                sql.query("SELECT 1 FROM `$table` LIMIT 1").use { require(!it.moveToFirst()) { "COMMAND_EMPTY_ANCHOR_NATIVE_CHANGED" } }
            }
            val built = CountCommandDirectory.empty(namespace.catalogBinding)
            val expected = arrayListOf<CountCommandJournal.Expected>()
            for ((digest, bytes) in built.blocks) {
                require(persistNode(digest, bytes.toByteArray()))
                expected += CountCommandJournal.Expected("count_command_nodes", "INSERT", digest)
            }
            val fresh = header.copy(mode = CountCommandRootCodec.Mode.ACCOUNT_BOUND, namespace = namespace,
                catalog = built.root, revision = Math.addExact(header.revision, 1L))
            expected += replaceRootAndAnchor(fresh)
            return Collections.unmodifiableList(expected)
        }
        suspend fun requireCompleteV1Catalog() {
            lifetime()
            require(header.mode == CountCommandRootCodec.Mode.ACCOUNT_BOUND && header.format == 1)
            var count = 0L; var outcomes = 0L
            sql.query("SELECT commandId FROM count_command_intents ORDER BY commandId").use { cursor ->
                while (cursor.moveToNext()) {
                    lifetime(); require(cursor.getType(0) == Cursor.FIELD_TYPE_STRING)
                    val row = required(cursor.getString(0))
                    require(row.intent.format == 1 && row.status.format == 1 && (row.outcome == null || row.outcome.format == 1))
                    count = Math.addExact(count, 1L)
                    if (row.outcome != null) outcomes = Math.addExact(outcomes, 1L)
                }
            }
            require(count == requireNotNull(header.catalog).count)
            for ((table, expected) in listOf("count_command_status" to count, "count_command_results" to outcomes))
                sql.query("SELECT COUNT(*) FROM " + table).use { c ->
                    require(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_INTEGER && c.getLong(0) == expected && !c.moveToNext())
                }
            // Also read an empty root, rather than assuming count=0 makes its node optional.
            if (count == 0L) require(lookup("00000000-0000-4000-8000-000000000000") == null)
            val pager = CountCommandPendingRules.Pager(rootIdentity(), header.pending)
            while (true) { if (readPendingPage(pager, 32).second) break; lifetime() }
        }
        fun upgradeMetric(): List<CountCommandJournal.Expected> {
            lifetime()
            return replaceRootAndAnchor(header.copy(format = 2, validator = CountCommandRecords.METRIC_VALIDATOR,
                revision = Math.addExact(header.revision, 1)), metricUpgrade = true)
        }
        fun registerInitialNamespace(installation: String): List<CountCommandJournal.Expected> {
            lifetime()
            val fresh = header.copy(namespace = namespace, registration = CountCommandRootCodec.Registration(
                requireNotNull(header.namespaceHash), installation), revision = Math.addExact(header.revision, 1L))
            return replaceRootAndAnchor(fresh)
        }
        suspend fun lookup(id: String): CountCommandRowPlan.Aggregate? = CountCommandCostTiming.measure(Step.MEMBER) { directory().read { reader ->
            val member = reader.member(id)
            if (member == null) {
                CountCommandRowPlan.Table.entries.forEach { rows.requireAbsent(it, id) }; null
            } else rows.aggregate(member, lifetime).also {
                require(it.intent.namespace.catalogBinding == namespace.catalogBinding && CountCommandRootCodec.admits(header, it.intent.format)) { "COMMAND_FORMAT_NOT_ADMITTED" }
            }
        } }
        suspend fun required(id: String) = requireNotNull(lookup(id)) { "COMMAND_NATIVE_MEMBER_MISSING" }
        fun rootIdentity(): String = CountCommandRecords.hash("pending-root", CountCommandRootCodec.body(header).toString(Charsets.UTF_8))
        suspend fun readPendingPage(pager: CountCommandPendingRules.Pager, maximumRows: Int): Pair<List<Located>, Boolean> = directory().read { reader ->
            val aggregates = linkedMapOf<String, CountCommandRowPlan.Aggregate>()
            val page = pager.page(rootIdentity(), maximumRows, { id ->
                val member = requireNotNull(reader.member(id)) { "COMMAND_PENDING_MEMBER_MISSING" }
                val aggregate = rows.aggregate(member, lifetime)
                require(aggregate.intent.namespace.catalogBinding == namespace.catalogBinding && CountCommandRootCodec.admits(header, aggregate.intent.format)) { "COMMAND_FORMAT_NOT_ADMITTED" }
                aggregates[id] = aggregate; aggregate.status
            }, lifetime)
            val located = page.rows.map { status ->
                val aggregate = aggregates.getValue(status.commandId)
                val kind = when {
                    aggregate.intent.namespace == namespace -> LocationScope.CURRENT
                    header.registration?.previousNamespaceHash == aggregate.intent.namespace.hash -> LocationScope.INITIAL_REGISTRATION_REVALIDATION_REQUIRED
                    else -> LocationScope.ISOLATED
                }
                Located(reference(aggregate.intent), kind, aggregate)
            }
            Collections.unmodifiableList(located) to page.complete
        }
        fun requireDataset(ref: Reference) { require(ref.datasetId == header.datasetId) { "COMMAND_DATASET_REPLACED" } }
        fun requireReference(ref: Reference) { requireDataset(ref); require(ref.namespaceHash == namespace.hash) { "COMMAND_REFERENCE_NAMESPACE_CHANGED" } }
        private fun directory() = CountCommandDirectory(sql, namespace.catalogBinding, requireNotNull(header.catalog), lifetime, lifetime)

        suspend fun write(batch: CountCommandRowPlan.Batch, pending: CountCommandPendingRules.Frontier): List<CountCommandJournal.Expected> {
            lifetime()
            require(batch.after.all { CountCommandRootCodec.admits(header, it.intent.format) }) { "COMMAND_FORMAT_NOT_ADMITTED" }
            val patch = CountCommandCostTiming.measure(Step.COMMAND_DIRECTORY_PATCH) { directory().read { it.replace(batch.catalogEdits) } }
            val expected = arrayListOf<CountCommandJournal.Expected>()
            for (mutation in batch.mutations) {
                rows.apply(mutation)
                expected += CountCommandJournal.Expected(mutation.after.table.sqlName, if (mutation.before == null) "INSERT" else "UPDATE", mutation.after.commandId)
            }
            for ((digest, bytes) in patch.newBlocks) if (persistNode(digest, bytes.toByteArray())) {
                expected += CountCommandJournal.Expected("count_command_nodes", "INSERT", digest)
            }
            val fresh = header.copy(catalog = patch.root, pending = pending, revision = Math.addExact(header.revision, 1L))
            expected += replaceRootAndAnchor(fresh)
            return Collections.unmodifiableList(expected)
        }
        suspend fun publishMetricDelivery(plan: CountCommandMetricDeliveryPlan): List<CountCommandJournal.Expected> {
            plan.requireOriginal(authenticated)
            require(plan.steps in 1..2)
            val expected = arrayListOf<CountCommandJournal.Expected>()
            for (mutation in plan.mutations) {
                rows.apply(mutation)
                expected += CountCommandJournal.Expected(mutation.after.table.sqlName, "UPDATE", mutation.after.commandId)
            }
            // Ordered P1 then P2 union, including now-unreachable intermediate COW nodes.
            // Memory overlay never bypasses the original real-SQL collision check here.
            for ((digest, bytes) in plan.newBlocks) if (persistNode(digest, bytes.toByteArray())) {
                expected += CountCommandJournal.Expected("count_command_nodes", "INSERT", digest)
            }
            val before = CountCommandRootAnchor.read(sql)
            CountCommandRootAnchor.requireMatches(header, authenticated.native().mac, before.hash)
            require(before.storage == header.storageId)
            val signed = CountCommandCostTiming.measure(Step.COMMAND_SIGN) {
                CountCommandSeal().advanceMetricDelivery(authenticated, plan)
            }
            val after = before.copy(hash = CountCommandCostTiming.measure(Step.COMMAND_ANCHOR_DIGEST) { CountCommandRootAnchor.digest(signed) })
            expected += CountCommandJournal.Expected(CountCommandJournal.ROOT, "UPDATE", "1")
            expected += CountCommandJournal.Expected(CountCommandJournal.CONTROL, "UPDATE", "1",
                CountCommandJournal.AnchorState(before.storage, before.generation, before.dirty, before.hash),
                CountCommandJournal.AnchorState(after.storage, after.generation, after.dirty, after.hash))
            CountCommandCostTiming.measure(Step.COMMAND_ROOT_CAS) {
                rows.replaceRoot(authenticated.native(), signed)
                require(CountCommandRootAnchor.update(sql, before, requireNotNull(after.hash)) == after)
            }
            if (CountCommandRootCodec.fromEntity(signed).mode == CountCommandRootCodec.Mode.ACCOUNT_BOUND_RETAINED)
                CountCommandRetainedPartition.verify(sql, CountCommandSeal().authenticate(signed, header.storageId, header.schemaHash) {
                    CountCommandRootAnchor.read(sql).hash
                }, lifetime)
            return Collections.unmodifiableList(expected)
        }

        private fun replaceRootAndAnchor(fresh: CountCommandRootCodec.Header, metricUpgrade: Boolean = false,
            enrollment: CountCommandRetainedEnrollmentPlan? = null): List<CountCommandJournal.Expected> {
            lifetime()
            val before = CountCommandRootAnchor.read(sql)
            CountCommandRootAnchor.requireMatches(header, authenticated.native().mac, before.hash)
            require(before.storage == header.storageId)
            val signed = CountCommandCostTiming.measure(Step.COMMAND_SIGN) {
                if (enrollment != null) {
                    require(!metricUpgrade && enrollment.header == fresh)
                    CountCommandSeal().enrollRetained(authenticated, enrollment)
                } else if (metricUpgrade) CountCommandSeal().upgradeMetric(authenticated, fresh) else CountCommandSeal().advance(authenticated, fresh)
            }
            val after = before.copy(hash = CountCommandCostTiming.measure(Step.COMMAND_ANCHOR_DIGEST) { CountCommandRootAnchor.digest(signed) })
            // Expected contents are fixed from the authenticated old envelope and pure new
            // header before either mutation; actual journal rows never define this plan.
            val expected = listOf(CountCommandJournal.Expected(CountCommandJournal.ROOT, "UPDATE", "1"),
                CountCommandJournal.Expected(CountCommandJournal.CONTROL, "UPDATE", "1",
                    CountCommandJournal.AnchorState(before.storage, before.generation, before.dirty, before.hash),
                    CountCommandJournal.AnchorState(after.storage, after.generation, after.dirty, after.hash)))
            CountCommandCostTiming.measure(Step.COMMAND_ROOT_CAS) {
                rows.replaceRoot(authenticated.native(), signed)
                require(CountCommandRootAnchor.update(sql, before, requireNotNull(after.hash)) == after)
            }
            if (CountCommandRootCodec.fromEntity(signed).mode == CountCommandRootCodec.Mode.ACCOUNT_BOUND_RETAINED)
                CountCommandRetainedPartition.verify(sql, CountCommandSeal().authenticate(signed, header.storageId, header.schemaHash) {
                    CountCommandRootAnchor.read(sql).hash
                }, lifetime)
            return expected
        }
        private fun persistNode(digest: String, bytes: ByteArray): Boolean {
            lifetime(); require(bytes.size in 1..HistoryBlockCodec.MAX_NODE_BYTES && HistoryBlockCodec.hash(bytes) == digest)
            var existed = false
            sql.query("SELECT typeof(body),length(body),CASE WHEN typeof(body)='blob' AND length(body)=? THEN body END " +
                "FROM count_command_nodes WHERE digest=? LIMIT 2", arrayOf<Any>(bytes.size, digest)).use { c ->
                if (c.moveToFirst()) {
                    require(c.getType(0) == Cursor.FIELD_TYPE_STRING && c.getString(0) == "blob" && c.getType(1) == Cursor.FIELD_TYPE_INTEGER &&
                        c.getLong(1) == bytes.size.toLong() && c.getType(2) == Cursor.FIELD_TYPE_BLOB && c.getBlob(2).contentEquals(bytes) && !c.moveToNext())
                    existed = true
                }
            }
            if (!existed) {
                sql.execSQL("INSERT INTO count_command_nodes(digest,body) VALUES(?,?)", arrayOf(digest, bytes.copyOf()))
                sql.query("SELECT CASE WHEN typeof(body)='blob' AND length(body)=? THEN body END FROM count_command_nodes WHERE digest=? LIMIT 2",
                    arrayOf<Any>(bytes.size, digest)).use { require(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_BLOB &&
                        it.getBlob(0).contentEquals(bytes) && !it.moveToNext()) { "COMMAND_NODE_WRITE_CHANGED" } }
            }
            return !existed
        }
    }
    private fun reference(intent: CountCommandRecords.Intent) = Reference(intent.commandId, intent.inputHash, intent.namespace.hash, intent.namespace.dataset)
    companion object {
        private val participants = WeakHashMap<HabitDatabase, Participant>()
        internal suspend fun requireFormalMetricSource(scope: FormalMetricDeliveryScope,
            database: HabitDatabase, tokens: TokenManager, sessions: AccountSessionCoordinator) {
            val issued = scope as? IssuedFormalMetricDelivery ?: error("COMMAND_METRIC_SEQUENCE_FOREIGN_SOURCE")
            issued.requireSourceOwner(database, tokens, sessions)
        }
    }
}
