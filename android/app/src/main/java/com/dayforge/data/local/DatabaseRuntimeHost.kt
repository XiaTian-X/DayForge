package com.dayforge.data.local

import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Lifetime coordination, never SQL/account/business authority. One operation captures one graph. */
@Singleton
internal class DatabaseRuntimeHost @Inject constructor(private val factory: DatabaseEpochGraphFactory,
    private val tokens: TokenManager) {
    internal enum class Work { COMMAND, CONTROL, OBSERVATION }
    internal class SessionPreparationUnavailable internal constructor(val preparation: SessionPreparationState) :
        IllegalStateException("LOCAL_SESSION_PREPARATION_REQUIRED", when (preparation) {
            is SessionPreparationState.RetryableFailure -> preparation.cause
            is SessionPreparationState.DeferredFence -> preparation.cause
            else -> null
        })
    internal interface MemoryListener { fun transition(blocked: Boolean) }
    internal class Unavailable internal constructor(cause: Throwable) : IllegalStateException("DATABASE_EPOCH_UNAVAILABLE", cause)
    private class ObservationRevoked : CancellationException("DATABASE_EPOCH_OBSERVATION_REVOKED")
    private class ObservationEnded : CancellationException("DATABASE_EPOCH_OBSERVATION_ENDED")
    private class Epoch(val graph: DatabaseEpochGraph) {
        var publishable = true
        var preparation: SessionPreparationState? = null
        var qualificationWatcher: Job? = null
        val leases: MutableSet<Lease> = Collections.newSetFromMap(IdentityHashMap<Lease, Boolean>())
    }
    private class Lease(val epoch: Epoch, val work: Work, val job: Job,
        val displayStamp: PersonalDataDisplayMemory.Stamp, val controlObservation: Boolean = false)
    private sealed interface State {
        data object Cold : State
        data object Closed : State
        class Active(val epoch: Epoch) : State
        class Reserving(val epoch: Epoch) : State
        class Changing(val previous: Epoch?) : State
        class Failed(val failure: Throwable, val retained: List<Epoch>,
            val retryColdCancellation: Boolean = false, val shutdown: Boolean = false) : State
    }
    private val monitor = Any()
    private val controlPublicationOwner = Any() // Control source is never a business source or Open grant.
    private var state: State = State.Cold
    private var sourceBlocked = false
    private val sourceChanges = Channel<Unit>(Channel.CONFLATED)
    private var revision = 0L
    private val changes = MutableStateFlow(0L)
    private val serial = Mutex()
    private val preparationSerial = Mutex()
    private val preparationState = MutableStateFlow<SessionPreparationState>(SessionPreparationState.Checking())
    internal val sessionPreparation = preparationState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rebuilds = Channel<Unit>(Channel.CONFLATED)
    private val rebuildFailure = MutableStateFlow<Throwable?>(null)
    internal val lastRebuildFailure = rebuildFailure.asStateFlow()
    @Volatile internal var lifecycleEnded: Boolean = false
        private set
    init {
        scope.launch {
            for (hint in rebuilds) {
                val retry = try { serial.withLock { replaceIfEligible() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    rebuildFailure.value = failure
                    false // Explicit failed attempt; no retry, replay, or reported command success.
                }
                if (retry) { delay(250); rebuilds.trySend(Unit) }
            }
        }
    }
    private val bound = ThreadLocal<Lease?>()
    private val listeners = mutableListOf<WeakReference<Listener>>()
    private class Listener(val callback: MemoryListener) {
        private var delivered = -1L
        @Synchronized fun deliver(revision: Long, blocked: Boolean) {
            if (revision <= delivered) return
            delivered = revision
            callback.transition(blocked)
        }
    }
    private inner class Binding(val lease: Lease) :
        ThreadContextElement<Lease?>, AbstractCoroutineContextElement(Key) {
        override fun updateThreadContext(context: CoroutineContext): Lease? = bound.get().also { bound.set(lease) }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Lease?) { bound.set(oldState) }
    }
    private companion object Key : CoroutineContext.Key<Binding>

    private val sourceListener = object : PersonalDataDisplayMemory.Listener {
        override fun transition(blocked: Boolean) {
            // Original registry callbacks remain memory-only; never cancel/join application Jobs here.
            synchronized(monitor) { sourceBlocked = blocked }
            // Do not wake an operation continuation while the original token registry is
            // still notifying listeners. The IO worker publishes its wait hint afterwards.
            announce(notifyWaiters = false)
            sourceChanges.trySend(Unit)
        }
    }
    init {
        tokens.registerPersonalDisplay(sourceListener)
        scope.launch {
            for (event in sourceChanges) {
                // Join the synchronous token-memory notification before waking any caller.
                tokens.capturePersonalDisplayStamp()
                val observedPreparation = preparationState.value
                val prepared = observedPreparation.attempt
                if (prepared != null && !tokens.isCurrentPersonalDisplayStamp(prepared.stamp)) synchronized(monitor) {
                    // A delayed begin hint cannot erase a newer completed preparation.
                    if (preparationState.value === observedPreparation)
                        preparationState.value = SessionPreparationState.Checking()
                }
                val currentRevision = synchronized(monitor) { revision }
                changes.update { maxOf(it, currentRevision) }
                val observations = synchronized(monitor) {
                    when (val current = state) {
                        is State.Active -> current.epoch.leases.filter { it.work == Work.OBSERVATION }
                        is State.Reserving -> current.epoch.leases.filter { it.work == Work.OBSERVATION }
                        else -> emptyList()
                    }
                }
                // Stamp checks happen outside Host/producer monitors; only old observations restart.
                observations.filterNot { tokens.isCurrentPersonalDisplayStamp(it.displayStamp) }
                    .forEach { it.job.cancel(ObservationRevoked()) }
                if (!synchronized(monitor) { sourceBlocked }) requestRebuild()
            }
        }
    }

    init { DatabaseRuntimeHosts.created(this) }

    /** The caller retains the returned registration; callback execution never holds the host lock. */
    internal fun observeMemory(listener: MemoryListener): Any {
        val entry = Listener(listener)
        val initial = synchronized(monitor) {
            listeners.removeAll { it.get() == null }; listeners += WeakReference(entry)
            revision to (sourceBlocked || state !is State.Active && state !is State.Reserving)
        }
        entry.deliver(initial.first, initial.second)
        return entry
    }
    private fun announce(notifyWaiters: Boolean = true) {
        val event = synchronized(monitor) {
            revision = Math.addExact(revision, 1L)
            Triple(revision, sourceBlocked || state !is State.Active && state !is State.Reserving,
                listeners.mapNotNull { it.get() })
        }
        if (notifyWaiters) changes.update { maxOf(it, event.first) }
        event.third.forEach { it.deliver(event.first, event.second) }
    }
    internal fun boundGraph(): DatabaseEpochGraph {
        val lease = checkNotNull(bound.get()) { "DATABASE_EPOCH_OPERATION_REQUIRED" }
        return synchronized(monitor) {
            check(lease in lease.epoch.leases) { "DATABASE_EPOCH_OPERATION_EXPIRED" }
            lease.epoch.graph
        }
    }
    /** Original TokenManager memory fence -> Host publication monitor. Neither path performs SQL. */
    private fun publication(epoch: Epoch, stamp: PersonalDataDisplayMemory.Stamp,
        controlStatus: Boolean = false): DatabaseEpochPublication {
        fun currentGraph(): Boolean = (controlStatus || epoch.publishable &&
            epoch.preparation is SessionPreparationState.Satisfied &&
            sameStamp(epoch.preparation?.attempt?.stamp, stamp)) && when (val s = state) {
            is State.Active -> s.epoch === epoch
            is State.Reserving -> s.epoch === epoch
            else -> false
        }
        return DatabaseEpochPublication(if (controlStatus) controlPublicationOwner else this,
            { tokens.isCurrentPersonalDisplayStamp(stamp) && synchronized(monitor) { currentGraph() } },
            { assign -> tokens.publishPersonalDisplayStamp(stamp) {
                synchronized(monitor) {
                    if (!currentGraph()) false else { assign(); true }
                }
            } },
            { assign -> tokens.publishPersonalDisplayStamp(stamp, assign) })
    }
    internal fun currentPublication(): DatabaseEpochPublication {
        val lease = checkNotNull(bound.get()) { "DATABASE_EPOCH_OPERATION_REQUIRED" }
        synchronized(monitor) { check(lease in lease.epoch.leases) { "DATABASE_EPOCH_OPERATION_EXPIRED" } }
        return publication(lease.epoch, lease.displayStamp)
    }
    /** Recovery/configuration control input only. Foreign to every business source factory. */
    internal fun currentControlPublication(): DatabaseEpochPublication {
        val lease = checkNotNull(bound.get()) { "DATABASE_EPOCH_OPERATION_REQUIRED" }
        synchronized(monitor) {
            check(lease in lease.epoch.leases && lease.work == Work.CONTROL) { "DATABASE_EPOCH_CONTROL_REQUIRED" }
        }
        return publication(lease.epoch, lease.displayStamp, controlStatus = true)
    }

    /** Pure memory lifecycle hints: no database open, handle capture or retained graph lease. */
    internal val controlChanges: Flow<Unit> get() = changes.map { Unit }

    private suspend fun acquire(work: Work, controlObservation: Boolean = false): Lease {
        while (true) {
            currentCoroutineContext().ensureActive()
            // Terminal observation must not wait for a future account stamp or initialize a
            // graph. Revocation is restartable; application shutdown is not.
            if (work == Work.OBSERVATION && synchronized(monitor) {
                    state === State.Closed || (state as? State.Failed)?.shutdown == true
                })
                throw ObservationEnded()
            val job = requireNotNull(currentCoroutineContext()[Job])
            // Registry -> Host is the only order. Admission and its source stamp are observed
            // together; otherwise an account callback could miss a just-added old observation.
            val stamp = tokens.capturePersonalDisplayStamp()
            if (stamp == null) {
                val observed = synchronized(monitor) {
                    // Closed may have won after the early check and before a missing stamp.
                    // Observe its terminal state with the wait revision to avoid a lost wake.
                    if (work == Work.OBSERVATION &&
                        (state === State.Closed || (state as? State.Failed)?.shutdown == true)) throw ObservationEnded()
                    revision
                }
                if (tokens.capturePersonalDisplayStamp() != null) continue
                changes.first { it > observed }
                continue
            }
            var lease: Lease? = null
            var retryCancelled: State.Failed? = null
            var observed = 0L
            val current = tokens.publishPersonalDisplayStamp(stamp) {
                synchronized(monitor) {
                    observed = revision
                    when (val selected = state) {
                        is State.Active -> if (!sourceBlocked && (selected.epoch.publishable || work == Work.CONTROL || controlObservation)) {
                            lease = Lease(selected.epoch, work, job, stamp, controlObservation).also { selected.epoch.leases += it }
                        }
                        is State.Failed -> when {
                            selected.shutdown && work == Work.OBSERVATION -> throw ObservationEnded()
                            selected.retryColdCancellation -> retryCancelled = selected
                            else -> throw Unavailable(selected.failure)
                        }
                        State.Closed -> if (work == Work.OBSERVATION) throw ObservationEnded()
                            else error("DATABASE_EPOCH_CLOSED")
                        else -> Unit
                    }
                }
                true
            }
            if (!current) continue
            lease?.let { return it }
            retryCancelled?.let { failed ->
                if (!retryCancelledInitialization(failed)) throw Unavailable(failed.failure)
            }
            if (synchronized(monitor) { state === State.Cold }) initialize()
            else changes.first { it > observed }
        }
    }
    private suspend fun initialize() = serial.withLock {
        val elected = synchronized(monitor) {
            if (state !== State.Cold) false else { state = State.Changing(null); true }
        }
        if (!elected) return@withLock
        announce()
        var candidate: Epoch? = null
        try {
            val created = Epoch(factory.create())
            candidate = created
            factory.validateAndPublish(created.graph) { preparation ->
                recordPreparation(created, preparation)
                synchronized(monitor) {
                    check((state as? State.Changing)?.previous == null)
                    state = State.Active(created)
                }
                // Finish publication bookkeeping on the publishing side of the cancellable
                // IO return. Another caller may already hold this active graph's lease.
                watchQualification(created)
                announce()
            }
        } catch (failure: Throwable) {
            val created = candidate
            val published = synchronized(monitor) { created != null && (state as? State.Active)?.epoch === created }
            if (published) throw failure // Original caller cancels; a published graph is never closed here.
            if (failure is CancellationException && created != null) {
                // Update state on the non-cancellable cleanup side too. The cancelled caller
                // may never receive an IO result; losing that return cannot strand a closed DB.
                try { withContext(NonCancellable + Dispatchers.IO) {
                    completeCancelledCleanup(created, failure)
                } } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                throw failure
            }
            synchronized(monitor) { created?.publishable = false; state = State.Failed(failure, listOfNotNull(created)) }
            announce(); throw failure
        }
    }
    /** Only unpublished initializer cancellation can enter this path. No validation or
     * business operation is replayed. Busy retains exactly that original candidate. */
    private fun completeCancelledCleanup(created: Epoch, failure: Throwable): DatabaseEpochGraphFactory.Closed {
        val result = try { factory.closeCancelledInitialization(created.graph) }
        catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup); DatabaseEpochGraphFactory.Closed.DEFERRED }
        synchronized(monitor) {
            created.publishable = false
            state = when (result) {
                DatabaseEpochGraphFactory.Closed.CLOSED -> State.Cold
                DatabaseEpochGraphFactory.Closed.BUSY -> State.Failed(failure, listOf(created), retryColdCancellation = true)
                DatabaseEpochGraphFactory.Closed.DEFERRED -> State.Failed(failure, listOf(created))
            }
        }
        announce()
        return result
    }
    private suspend fun retryCancelledInitialization(expected: State.Failed): Boolean {
        check(expected.retryColdCancellation && expected.failure is CancellationException && expected.retained.size == 1)
        val candidate = expected.retained.single()
        // This NEW one-second availability window applies only to a never-published,
        // cancelled initializer. It is not an operation/performance budget and never
        // retries validation or a business callback. A real close already begun must still
        // finish its physical END/receipt bookkeeping even if the waiting caller cancels.
        val resolved = withTimeoutOrNull<Boolean>(1_000) {
            var answer: Boolean? = null
            while (answer == null) {
                currentCoroutineContext().ensureActive()
                val attempted = serial.withLock {
                    currentCoroutineContext().ensureActive()
                    val selected = synchronized(monitor) {
                        (state as? State.Failed)?.takeIf {
                            it.retained.size == 1 && it.retained.single() === candidate
                        }
                    }
                    when {
                        selected == null -> null // Another caller/state transition won; acquire rechecks it.
                        !selected.retryColdCancellation -> DatabaseEpochGraphFactory.Closed.DEFERRED
                        else -> withContext(NonCancellable + Dispatchers.IO) {
                            completeCancelledCleanup(candidate, selected.failure)
                        }
                    }
                }
                answer = when (attempted) {
                    null, DatabaseEpochGraphFactory.Closed.CLOSED -> true
                    DatabaseEpochGraphFactory.Closed.DEFERRED -> false
                    DatabaseEpochGraphFactory.Closed.BUSY -> {
                        // No Host/serial/account/SQLite resource is acquired for this wait.
                        // Every next attempt rechecks known/unknown drain and exact sealing.
                        delay(25)
                        null
                    }
                }
            }
            requireNotNull(answer)
        }
        if (resolved != null) return resolved
        // Timeout cannot turn Busy into closed, nor discard a retained candidate. If a
        // different caller really completed cleanup/publication, normal acquire inspects it.
        return synchronized(monitor) {
            val selected = state as? State.Failed
            selected == null || selected.retained.none { it === candidate }
        }
    }

    private fun sameStamp(first: PersonalDataDisplayMemory.Stamp?, second: PersonalDataDisplayMemory.Stamp): Boolean =
        first != null && first.owner === second.owner && first.generation == second.generation

    private fun recordPreparation(epoch: Epoch, check: LegacySessionPreparationCheck): SessionPreparationState? {
        val stamp = check.snapshot?.stamp ?: tokens.capturePersonalDisplayStamp() ?: return null
        val attempt = SessionPreparationAttempt(this, epoch.graph, stamp, check.snapshot)
        val next = when {
            check.result == LegacySessionGenerationResult.Stale -> SessionPreparationState.RetryableFailure(
                attempt, check.cause ?: IllegalStateException("LEGACY_SESSION_PREPARATION_CHANGED"))
            check.fence != null -> SessionPreparationState.DeferredFence(attempt, check.fence, check.cause)
            check.cause != null -> SessionPreparationState.RetryableFailure(attempt, check.cause)
            check.result == LegacySessionGenerationResult.AuthenticationUnavailable -> SessionPreparationState.AuthenticationUnavailable(attempt)
            check.result is LegacySessionGenerationResult.CreatedGeneration -> SessionPreparationState.Satisfied(attempt, SessionPreparationDisposition.CREATED_GENERATION)
            check.result is LegacySessionGenerationResult.CurrentGeneration -> SessionPreparationState.Satisfied(attempt, SessionPreparationDisposition.CURRENT_GENERATION)
            check.result == LegacySessionGenerationResult.NotApplicable -> SessionPreparationState.Satisfied(attempt, SessionPreparationDisposition.NOT_APPLICABLE)
            else -> error("LEGACY_SESSION_PREPARATION_RESULT_REQUIRED")
        }
        val published = tokens.publishPersonalDisplayStamp(stamp) {
            synchronized(monitor) {
                epoch.preparation = next
                preparationState.value = next
            }
            true
        }
        if (published) announce()
        return next.takeIf { published }
    }

    /** A new unsourced entry may prepare current metadata; a failed SAME attempt is never
     * automatically retried. Existing sourced actions remain checked before and after entry.
     * Control leases pin the physical graph while metadata readiness is independently checked.
     */
    private suspend fun ensurePreparation(lease: Lease): Unit = preparationSerial.withLock {
        val current = synchronized(monitor) { lease.epoch.preparation }
        if (current?.attempt?.let { tokens.isCurrentPersonalDisplayStamp(it.stamp) } == true) {
            // An abandoned/cancelled retry may have left only the visible Checking state.
            // Restoring its completed disposition never repeats metadata or business work.
            preparationState.value = current
            return@withLock
        }
        synchronized(monitor) {
            check(lease in lease.epoch.leases) { "DATABASE_EPOCH_OPERATION_EXPIRED" }
            lease.epoch.preparation = null
            preparationState.value = SessionPreparationState.Checking()
        }
        val result = try { factory.prepareSession(lease.epoch.graph) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) {
            // Factory only propagates physical schema/source/actual-END failure. Preserve it;
            // never turn Unknown into session Ready, auto-close, or a usable control graph.
            synchronized(monitor) {
                lease.epoch.publishable = false
                state = State.Failed(failure, listOf(lease.epoch))
            }
            announce()
            throw Unavailable(failure)
        }
        recordPreparation(lease.epoch, result)
        Unit
    }

    private fun release(lease: Lease) {
        synchronized(monitor) { check(lease.epoch.leases.remove(lease)) }
        if (CountTrustRuntime.forDatabase(lease.epoch.graph.database)?.isRetired == true) requestRebuild()
    }

    private suspend fun acquirePrepared(work: Work, controlObservation: Boolean): Lease {
        while (true) {
            val lease = acquire(work, controlObservation)
            try {
                ensurePreparation(lease)
                if (tokens.isCurrentPersonalDisplayStamp(lease.displayStamp)) return lease
            } catch (failure: Throwable) { release(lease); throw failure }
            release(lease)
        }
    }

    private fun requirePrepared(lease: Lease) {
        val current = synchronized(monitor) { lease.epoch.preparation }
        if (current !is SessionPreparationState.Satisfied ||
            !sameStamp(current.attempt.stamp, lease.displayStamp) ||
            !tokens.isCurrentPersonalDisplayStamp(lease.displayStamp))
            throw SessionPreparationUnavailable(current ?: SessionPreparationState.Checking())
    }

    /** Retry carries ONLY an opaque preparation attempt. It cannot retain/replay an action,
     * bless an old source, or bind account A's failed attempt to a later account B login. */
    internal suspend fun retrySessionPreparation(expected: SessionPreparationAttempt): SessionPreparationRetry {
        if (expected.owner !== this || !tokens.isCurrentPersonalDisplayStamp(expected.stamp))
            return SessionPreparationRetry.Stale
        return withGraph(Work.CONTROL) {
            val lease = checkNotNull(bound.get())
            preparationSerial.withLock {
                val active = synchronized(monitor) { lease.epoch.preparation }
                if (lease.epoch.graph !== expected.graph || active?.attempt !== expected ||
                    !tokens.isCurrentPersonalDisplayStamp(expected.stamp)) return@withLock SessionPreparationRetry.Stale
                preparationState.value = SessionPreparationState.Checking(expected)
                val result = try { factory.prepareSession(lease.epoch.graph, expected.snapshot) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Throwable) {
                    synchronized(monitor) {
                        lease.epoch.publishable = false
                        state = State.Failed(failure, listOf(lease.epoch))
                    }
                    announce()
                    throw Unavailable(failure)
                }
                if (result.result == LegacySessionGenerationResult.Stale) {
                    synchronized(monitor) { lease.epoch.preparation = null; preparationState.value = SessionPreparationState.Checking() }
                    announce()
                    SessionPreparationRetry.Stale
                } else recordPreparation(lease.epoch, result)?.let { SessionPreparationRetry.Completed(it) }
                    ?: SessionPreparationRetry.Stale
            }
        }
    }

    internal suspend fun <T> withGraph(work: Work = Work.COMMAND, block: suspend (DatabaseEpochGraph) -> T): T =
        withGraphPurpose(work, controlObservation = false, block)

    private suspend fun <T> withGraphPurpose(work: Work, controlObservation: Boolean,
        block: suspend (DatabaseEpochGraph) -> T): T {
        val nested = currentCoroutineContext()[Key]?.lease
        if (nested != null) {
            synchronized(monitor) { check(nested in nested.epoch.leases) { "DATABASE_EPOCH_OPERATION_EXPIRED" } }
            currentCoroutineContext().ensureActive()
            // CONTROL/maintenance may contain an ordinary entry; inherited leases do not
            // upgrade that entry. No preparation writes while an unknown outer lock is held.
            if (work != Work.CONTROL && !controlObservation) requirePrepared(nested)
            return block(nested.epoch.graph)
        }
        return coroutineScope {
            val lease = acquirePrepared(work, controlObservation)
            try {
                if (work != Work.CONTROL && !controlObservation) requirePrepared(lease)
                withContext(Binding(lease)) { block(lease.epoch.graph) }
            } finally { release(lease) }
        }
    }
    /** Input belongs to the original rendered/edited result. Never obtain a fresh current
     * publication at method entry to bless old IDs. The source gate is lifetime only; the
     * original account, exact UUID/claim and domain checks must still execute in this graph.
     */
    internal suspend fun <T> withSourceGraph(source: DatabaseEpochPublication?,
        work: Work = Work.COMMAND, block: suspend (DatabaseEpochGraph) -> T): T {
        check(source != null && source.belongsTo(this)) { "DATABASE_EPOCH_INPUT_REQUIRED" }
        check(source.isCurrentInMemory()) { "DATABASE_EPOCH_INPUT_EXPIRED" }
        return withGraph(work) { graph ->
            // Admission may have waited while a reservation closed the old graph. An old
            // action never redirects into a new graph just because its numeric ID exists.
            check(source.isCurrentInMemory()) { "DATABASE_EPOCH_INPUT_EXPIRED" }
            DatabaseOperationSource.run(graph.sourceIdentity, source::isCurrentInMemory) { block(graph) }
        }
    }

    /** Original control input may remain usable while a durable stage suppresses business
     * publication. Original repository access/stage checks remain mandatory. It is constrained
     * to the same local replica and actual END just like a business source; a full reset must
     * instead follow the explicit unsourced withLocalDataReset control protocol. */
    internal suspend fun <T> withControlSourceGraph(source: DatabaseEpochPublication?,
        block: suspend (DatabaseEpochGraph) -> T): T {
        check(source != null && source.belongsTo(controlPublicationOwner)) { "DATABASE_EPOCH_INPUT_REQUIRED" }
        check(source.isCurrentInMemory()) { "DATABASE_EPOCH_INPUT_EXPIRED" }
        return withGraph(Work.CONTROL) { graph ->
            val lease = checkNotNull(bound.get()) { "DATABASE_EPOCH_OPERATION_REQUIRED" }
            check(lease.work == Work.CONTROL) { "DATABASE_EPOCH_CONTROL_REQUIRED" }
            check(source.isCurrentInMemory()) { "DATABASE_EPOCH_INPUT_EXPIRED" }
            DatabaseOperationSource.run(graph.sourceIdentity, source::isCurrentInMemory) { block(graph) }
        }
    }

    /** The caller is already inside its complete original control operation/account scope.
     * A reset retires before first DML. Even a later error cannot reopen old-source publication;
     * only complete actual drain/close/new graph publication can make ordinary input ready.
     * If original strict preflight rejected before retirement, fresh reads may keep this graph.
     */
    internal suspend fun <T> withLocalDataReset(block: suspend () -> T): T {
        val lease = checkNotNull(bound.get()) { "DATABASE_EPOCH_OPERATION_REQUIRED" }
        synchronized(monitor) { check(lease in lease.epoch.leases) }
        return tokens.withLocalReplicaDisplayTransition {
            try { block() }
            finally {
                if (CountTrustRuntime.forDatabase(lease.epoch.graph.database)?.isRetired == true) {
                    synchronized(monitor) { lease.epoch.publishable = false }
                    announce(); requestRebuild()
                }
            }
        }
    }

    /** The destination must also compare the freshly read object's original UUID/id via
     * selection.requireHabit/requireMetric before publishing or mutating it. */
    internal suspend fun <T> withSelection(selection: DatabaseObjectSelection?,
        kind: DatabaseObjectSelection.Kind, block: suspend (DatabaseEpochGraph) -> T): T {
        check(selection != null && selection.kind == kind) { "DATABASE_EPOCH_INPUT_REQUIRED" }
        return withSourceGraph(selection.publication, block = block)
    }

    /** Optional background work never initializes or waits for a graph merely to inspect it. */
    internal suspend fun <T> tryWithActiveGraph(block: suspend (DatabaseEpochGraph) -> T): T? = coroutineScope {
        currentCoroutineContext().ensureActive()
        val job = requireNotNull(currentCoroutineContext()[Job])
        val stamp = tokens.capturePersonalDisplayStamp() ?: return@coroutineScope null
        var captured: Lease? = null
        tokens.publishPersonalDisplayStamp(stamp) {
            synchronized(monitor) {
                if (!sourceBlocked) (state as? State.Active)?.epoch?.let { epoch ->
                    captured = Lease(epoch, Work.CONTROL, job, stamp).also { epoch.leases += it }
                }
            }
            true
        }
        val lease = captured ?: return@coroutineScope null
        try { withContext(Binding(lease)) { block(lease.epoch.graph) } }
        finally {
            synchronized(monitor) { check(lease.epoch.leases.remove(lease)) }
            if (CountTrustRuntime.forDatabase(lease.epoch.graph.database)?.isRetired == true) requestRebuild()
        }
    }
    internal suspend fun <T> snapshot(block: suspend (DatabaseEpochGraph) -> T): DatabaseEpochValue<T> =
        withGraph { graph -> DatabaseEpochValue(block(graph), currentPublication()) }

    /** Original recovery/control status remains readable while personal-data admission waits
     * for a replacement. Its publication owner is deliberately foreign to withSourceGraph;
     * callers may display Pending/Checking, never use it as an action source or an Open grant by itself.
     */
    internal suspend fun <T> controlSnapshot(block: suspend (DatabaseEpochGraph) -> T): DatabaseEpochValue<T> =
        withGraph(Work.CONTROL) { graph ->
            val value = block(graph)
            DatabaseEpochValue(value, currentControlPublication())
        }

    /** Each collection lifetime pins its graph. Revoked observation restarts only after joining;
     * original cancellation/errors are not classified as owner changes or silently retried.
     */
    internal fun <T> observe(block: (DatabaseEpochGraph) -> Flow<T>): Flow<DatabaseEpochValue<T>> =
        observePurpose(control = false, block)

    /** Recovery/status observation remains reachable with preparation attention. It retains
     * observation cancellation/drain semantics and gives no ordinary nested-work exemption. */
    internal fun controlHints(block: (DatabaseEpochGraph) -> Flow<Unit>): Flow<Unit> =
        observePurpose(control = true, block).map { Unit }

    private fun <T> observePurpose(control: Boolean, block: (DatabaseEpochGraph) -> Flow<T>): Flow<DatabaseEpochValue<T>> = channelFlow {
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                supervisorScope {
                    async {
                        withGraphPurpose(Work.OBSERVATION, controlObservation = control) { graph ->
                            val publication = currentPublication()
                            block(graph).collect { send(DatabaseEpochValue(it, publication)) }
                        }
                    }.await()
                }
                break
            } catch (_: ObservationEnded) {
                currentCoroutineContext().ensureActive()
                break
            } catch (_: ObservationRevoked) { currentCoroutineContext().ensureActive() }
            catch (attention: SessionPreparationUnavailable) {
                if (control) throw attention
                // Keep lifecycle observers (sync/reminder/widget/foreground) subscribed.
                // This waits for a NEW successful preparation; it never retries preparation
                // or replays a command/OS claim. The next loop captures a fresh source.
                changes.first {
                    synchronized(monitor) { state === State.Closed || state is State.Failed } ||
                        preparationState.value is SessionPreparationState.Satisfied
                }
            }
        }
    }
    internal fun hints(block: (DatabaseEpochGraph) -> Flow<Unit>): Flow<Unit> = observe(block).map { Unit }

    private fun watchQualification(epoch: Epoch) {
        val runtime = requireNotNull(CountTrustRuntime.forDatabase(epoch.graph.database))
        epoch.qualificationWatcher = scope.launch {
            runtime.qualificationChanges.collect {
                if (synchronized(monitor) { (state as? State.Active)?.epoch === epoch } && runtime.isRetired)
                    requestRebuild()
            }
        }
    }

    /** Coalesced optional maintenance. Accepted commands/HTTP are never cancelled to make room. */
    internal fun requestRebuild() { rebuilds.trySend(Unit) }
    private suspend fun replaceIfEligible(): Boolean {
        val old = synchronized(monitor) {
            (state as? State.Active)?.epoch?.takeIf { e ->
                !sourceBlocked && e.leases.none { it.work != Work.OBSERVATION } &&
                    CountTrustRuntime.forDatabase(e.graph.database)?.isRetired == true
            }
        } ?: return false
        when (factory.mayReplace(old.graph)) {
            DatabaseEpochGraphFactory.Eligibility.BUSY -> return true
            DatabaseEpochGraphFactory.Eligibility.DEFERRED -> return false
            DatabaseEpochGraphFactory.Eligibility.READY -> Unit
        }
        val observers = synchronized(monitor) {
            if ((state as? State.Active)?.epoch !== old || old.leases.any { it.work != Work.OBSERVATION }) return false
            state = State.Reserving(old)
            old.leases.map { it.job }
        }
        announce()
        observers.forEach { it.cancel(ObservationRevoked()) }
        observers.forEach { it.join() }
        check(synchronized(monitor) { old.leases.isEmpty() }) { "DATABASE_EPOCH_DRAIN_INCOMPLETE" }
        var revoked = false
        var candidate: Epoch? = null
        try {
            val closed = factory.closeIfStillEligible(old.graph) {
                synchronized(monitor) {
                    check((state as? State.Reserving)?.epoch === old && old.leases.isEmpty())
                    old.publishable = false; old.qualificationWatcher?.cancel()
                    state = State.Changing(old); revoked = true
                }
                announce()
            }
            if (closed != DatabaseEpochGraphFactory.Closed.CLOSED) {
                synchronized(monitor) { check(!revoked); state = State.Active(old) }
                announce(); return closed == DatabaseEpochGraphFactory.Closed.BUSY
            }
            val created = Epoch(factory.create())
            candidate = created
            factory.validateAndPublish(created.graph) { preparation ->
                recordPreparation(created, preparation)
                synchronized(monitor) {
                    check((state as? State.Changing)?.previous === old)
                    state = State.Active(created)
                }
            }
            watchQualification(created)
            announce()
        } catch (failure: Throwable) {
            synchronized(monitor) {
                if (!revoked) state = State.Active(old)
                else { candidate?.publishable = false; state = State.Failed(failure, listOfNotNull(old, candidate)) }
            }
            announce()
            throw failure
        }
        return false
    }

    /** End of the owning application/test lifecycle, never an automatic rebind or a way to
     * cancel accepted work. Callers must finish their own command/control jobs first. Real
     * native drain is checked independently before each actual close.
     */
    internal suspend fun shutdownWhenIdle() {
        check(currentCoroutineContext()[Key] == null) { "DATABASE_EPOCH_SHUTDOWN_INSIDE_OPERATION" }
        serial.withLock {
            if (lifecycleEnded) return@withLock
            val epochs = synchronized(monitor) {
                val selected = when (val current = state) {
                    is State.Active -> listOf(current.epoch)
                    is State.Failed -> current.retained.distinct()
                    State.Cold, State.Closed -> emptyList()
                    else -> error("DATABASE_EPOCH_SHUTDOWN_DURING_REPLACEMENT")
                }
                check(selected.all { epoch -> epoch.leases.none { it.work != Work.OBSERVATION } }) {
                    "DATABASE_EPOCH_SHUTDOWN_BUSY"
                }
                selected.forEach { it.publishable = false }
                state = State.Closed
                selected
            }
            var closeFailure: Throwable? = null
            try {
                announce()
                val jobs = synchronized(monitor) { epochs.flatMap { epoch -> epoch.leases.map { it.job } } }
                jobs.forEach { it.cancel(ObservationEnded()) }; jobs.forEach { it.join() }
                check(synchronized(monitor) { epochs.all { it.leases.isEmpty() } }) { "DATABASE_EPOCH_DRAIN_INCOMPLETE" }
                for (epoch in epochs) {
                    epoch.qualificationWatcher?.cancelAndJoin()
                    try { factory.closeDrained(epoch.graph) }
                    catch (error: Throwable) {
                        val previous = closeFailure
                        if (previous == null) closeFailure = error
                        else if (previous !== error && previous.suppressed.none { it === error }) previous.addSuppressed(error)
                    }
                }
                closeFailure?.let { throw it }
                // Keep serialization until the original Host workers have actually ended.
                // A second shutdown cannot report completion while this join is unfinished.
                requireNotNull(scope.coroutineContext[Job]).cancelAndJoin()
                rebuilds.close(); sourceChanges.close()
                lifecycleEnded = true
            } catch (error: Throwable) {
                val failure = closeFailure ?: error
                if (failure !== error && failure.suppressed.none { it === error }) failure.addSuppressed(error)
                // Closed seals admission, not resource completion. Retain every original
                // epoch even when cancellation interrupts an observer/watcher/scope join.
                // A later explicit shutdown rechecks these same owners; only the factory's
                // confirmed-close witness can skip an already completed physical close.
                synchronized(monitor) { state = State.Failed(failure, epochs, shutdown = true) }
                try { scope.cancel() } catch (cleanup: Throwable) {
                    if (failure !== cleanup && failure.suppressed.none { it === cleanup }) failure.addSuppressed(cleanup)
                }
                try { announce() } catch (notification: Throwable) {
                    if (failure !== notification && failure.suppressed.none { it === notification }) failure.addSuppressed(notification)
                }
                throw failure
            }
        }
    }

}
