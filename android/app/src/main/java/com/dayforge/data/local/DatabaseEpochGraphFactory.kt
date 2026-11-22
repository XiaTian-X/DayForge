package com.dayforge.data.local

import androidx.room.withTransaction
import com.dayforge.data.appearance.DeviceThemeRepository
import com.dayforge.data.repository.ReplicaPresentationRepository
import com.dayforge.data.repository.ReplicaPresentationCheck
import com.dayforge.data.repository.ReplicaPresentationProblem
import kotlinx.coroutines.CancellationException
import com.dayforge.domain.service.AccountSessionCoordinator
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Construction/closure only. Original readers, writers and recovery workflows retain authority. */
@Singleton
internal class DatabaseEpochGraphFactory @Inject constructor(
    private val services: Provider<DatabaseEpochServices>,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val rooms: DatabaseEpochRoomFactory
) {
    internal enum class Eligibility { READY, DEFERRED, BUSY }
    internal enum class Closed { CLOSED, DEFERRED, BUSY }

    internal fun create(): DatabaseEpochGraph {
        val database = rooms.create()
        return try { DatabaseEpochGraph(database, services.get()) }
        catch (failure: Throwable) {
            try { database.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    /** Application/test lifecycle termination only, after the Host has drained every operation.
     * It closes a healthy graph too; it never publishes a replacement or resolves a command.
     * Unknown or still-busy native work is an explicit failure, never forced close.
     */
    internal fun closeDrained(graph: DatabaseEpochGraph) {
        val owner = requireNotNull(CountTrustRuntime.forDatabase(graph.database))
        if (owner.isGraphCloseConfirmed(graph.database)) return
        val unopened = owner.trySealUnopenedGraphClose(graph.database)
        if (unopened != null) {
            graph.database.close(); owner.requireUnopenedGraphClosed(graph.database, unopened)
            return
        }
        when (val drain = owner.captureGraphDrain(graph.database)) {
            CountTrustOpenHelperFactory.GraphDrain.Busy -> error("DATABASE_EPOCH_SHUTDOWN_BUSY")
            CountTrustOpenHelperFactory.GraphDrain.Unknown -> error("DATABASE_EPOCH_SHUTDOWN_UNKNOWN")
            is CountTrustOpenHelperFactory.GraphDrain.Ready -> {
                check(owner.trySealGraphClose(graph.database, drain.witness)) { "DATABASE_EPOCH_SHUTDOWN_BUSY" }
                graph.database.close(); owner.requireGraphClosed(graph.database, drain.witness)
            }
        }
    }

    /** The unpublished initializer has already returned from its original coroutine/Room
     * cleanup. Cancellation may dispose only a never-opened sealed owner or a known native
     * END. Unknown outcomes remain retained; this method never opens or retries validation. */
    internal fun closeCancelledInitialization(graph: DatabaseEpochGraph): Closed {
        val owner = requireNotNull(CountTrustRuntime.forDatabase(graph.database))
        if (owner.hasUnknownGraphBoundary()) return Closed.DEFERRED
        val unopened = owner.trySealUnopenedGraphClose(graph.database)
        if (unopened != null) {
            graph.database.close(); owner.requireUnopenedGraphClosed(graph.database, unopened)
            return Closed.CLOSED
        }
        return when (val drained = owner.captureGraphDrain(graph.database)) {
            CountTrustOpenHelperFactory.GraphDrain.Unknown -> Closed.DEFERRED
            CountTrustOpenHelperFactory.GraphDrain.Busy -> if (owner.hasUnknownGraphBoundary()) Closed.DEFERRED else Closed.BUSY
            is CountTrustOpenHelperFactory.GraphDrain.Ready -> {
                if (!owner.trySealGraphClose(graph.database, drained.witness, knownOnly = true)) {
                    if (owner.hasUnknownGraphBoundary()) Closed.DEFERRED else Closed.BUSY
                } else {
                    graph.database.close(); owner.requireGraphClosed(graph.database, drained.witness)
                    Closed.CLOSED
                }
            }
        }
    }

    /** Never perform SQL to find whether a busy/unknown native connection can be drained. */
    private fun physicalEligibility(graph: DatabaseEpochGraph): Eligibility =
        when (requireNotNull(CountTrustRuntime.forDatabase(graph.database)).captureGraphDrain(graph.database)) {
            is CountTrustOpenHelperFactory.GraphDrain.Ready -> Eligibility.READY
            CountTrustOpenHelperFactory.GraphDrain.Busy -> Eligibility.BUSY
            CountTrustOpenHelperFactory.GraphDrain.Unknown -> Eligibility.DEFERRED
        }

    private suspend fun eligibleWhileAccountLocked(graph: DatabaseEpochGraph): Boolean {
        val database = graph.database
        val owner = requireNotNull(CountTrustRuntime.forDatabase(database))
        if (!owner.isRetired) return false
        val authority = tokens.localSyncAccess()
        if (authority != null && !tokens.isCurrentSyncAuthority(authority)) return false
        val allowed = database.withTransaction {
            // Durable stage, orphan fence and damaged witness are original recovery barriers.
            if (graph.replicaPresentationRepository.inspectInTransactionWhileAccountLocked().problem != null)
                return@withTransaction false
            owner.maintenance(database) {
                val sql = database.openHelper.writableDatabase
                CountTrustSchema.validate(sql)
                val storage = sql.query("SELECT storageId FROM main.count_trust_control WHERE id=1").use {
                    check(it.moveToFirst() && it.getType(0) == android.database.Cursor.FIELD_TYPE_STRING)
                    val value = it.getString(0)
                    check(!it.moveToNext()); value
                }
                !owner.recoveryPreviewSuppressed(storage)
            }
        }
        currentCoroutineContext().ensureActive()
        return allowed && tokens.localSyncAccess() == authority &&
            (authority == null || tokens.isCurrentSyncAuthority(authority))
    }

    /** Preliminary admission must remain memory-only: a new legal foreground operation
     * can enter before host reservation. Taking account then queuing Room here could invert
     * that operation's existing outer-transaction/account order. The complete fresh SQL/hold
     * check therefore runs only after reservation and all application operations have drained.
     */
    internal fun mayReplace(graph: DatabaseEpochGraph): Eligibility {
        val owner = requireNotNull(CountTrustRuntime.forDatabase(graph.database))
        if (!owner.isRetired) return Eligibility.DEFERRED
        return physicalEligibility(graph)
    }

    /** The account barrier stays held from final hold/fence check through actual Room close.
     * The host has already closed admission and joined its observation/app operations.
     * revoke() does memory bookkeeping only. Nothing resets a retired owner or reports commit.
     */
    internal suspend fun closeIfStillEligible(graph: DatabaseEpochGraph, revoke: () -> Unit): Closed =
        withContext(Dispatchers.IO) {
            when (val attempt = sessions.tryMaintenance {
                val physical = physicalEligibility(graph)
                if (physical == Eligibility.BUSY) return@tryMaintenance Closed.BUSY
                if (physical != Eligibility.READY || !eligibleWhileAccountLocked(graph)) {
                    // Fixed categories only; diagnostic failure cannot alter the original refusal.
                    if (com.dayforge.BuildConfig.DEBUG) try {
                        android.util.Log.d("DatabaseEpochReplacement", "physical=$physical result=DEFERRED")
                    } catch (_: Throwable) { /* Best-effort diagnostic, never a readiness grant. */ }
                    return@tryMaintenance Closed.DEFERRED
                }
                val owner = requireNotNull(CountTrustRuntime.forDatabase(graph.database))
                when (val drain = owner.captureGraphDrain(graph.database)) {
                    CountTrustOpenHelperFactory.GraphDrain.Busy -> Closed.BUSY
                    CountTrustOpenHelperFactory.GraphDrain.Unknown -> Closed.DEFERRED
                    is CountTrustOpenHelperFactory.GraphDrain.Ready -> {
                        currentCoroutineContext().ensureActive()
                        if (!owner.trySealGraphClose(graph.database, drain.witness)) return@tryMaintenance Closed.BUSY
                        revoke()
                        graph.database.close()
                        owner.requireGraphClosed(graph.database, drain.witness)
                        Closed.CLOSED
                    }
                }
            }) {
                AccountSessionCoordinator.MaintenanceAttempt.Busy -> Closed.BUSY
                is AccountSessionCoordinator.MaintenanceAttempt.Completed -> attempt.value
            }
        }

    /** Only the explicit preparation path catches preferences failures. Physical schema,
     * source binding, transaction cleanup and actual native END retain the original failures.
     * There is no DataStore work inside Room and no second acquisition of the account lock. */
    private suspend fun prepareWhileAccountLocked(graph: DatabaseEpochGraph,
        bindSource: Boolean, expected: LegacySessionPreparationSnapshot? = null): LegacySessionPreparationCheck {
        var check = try {
            val captured = tokens.captureLegacySessionPreparationWhileAccountLocked()
            if (expected != null && !tokens.isCurrentLegacySessionPreparationWhileAccountLocked(expected))
                LegacySessionPreparationCheck(result = LegacySessionGenerationResult.Stale)
            else LegacySessionPreparationCheck(snapshot = captured)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { LegacySessionPreparationCheck(cause = failure) }
        if (!bindSource && check.result == LegacySessionGenerationResult.Stale) return check
        val snapshot = check.snapshot
        val witness = if (snapshot?.needsGeneration == true) try { snapshot.recovery() }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                check = check.copy(fence = ReplicaPresentationCheck(ReplicaPresentationProblem.DAMAGED_STATE), cause = failure)
                null
            } else null
        if (bindSource || witness != null) graph.database.withTransaction {
            if (bindSource) {
                CountTrustSchema.validate(graph.database.openHelper.writableDatabase)
                graph.bindSource(requireNotNull(CountTrustRuntime.forDatabase(graph.database)).captureGraphSource(graph.database))
            }
            if (witness != null) {
                val inspection = graph.replicaPresentationRepository.inspectLegacySessionInTransactionWhileAccountLocked(witness)
                if (inspection.presentation.problem != null)
                    check = check.copy(fence = inspection.presentation, cause = inspection.cause)
            }
        }
        // withTransaction has completed its REAL END, not merely returned from its body.
        currentCoroutineContext().ensureActive()
        kotlin.check(snapshot == null || !graph.database.inTransaction()) { "LEGACY_SESSION_ROOM_TRANSACTION" }
        if (snapshot != null && check.fence == null && check.cause == null) {
            check = try { check.copy(result = tokens.prepareLegacySessionGenerationWhileAccountLocked(snapshot)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { check.copy(cause = failure) }
        }
        if (snapshot != null && check.result != LegacySessionGenerationResult.Stale) {
            check = try {
                val completed = tokens.completedLegacySessionPreparationWhileAccountLocked(snapshot, check.result)
                if (completed == null) LegacySessionPreparationCheck(result = LegacySessionGenerationResult.Stale, cause = check.cause)
                else check.copy(snapshot = completed)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { check.copy(snapshot = null, cause = check.cause ?: failure) }
        }
        return check
    }

    /** The callback may inspect session metadata and record its outcome. It must never run a
     * business operation or call a theme loader while owning this boundary. */
    internal suspend fun <T : Any> withSessionPreparation(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { DeviceThemeRepository.withSessionPreparation(sessions, block) }

    /** Physical publication preserves original recovery/login access even when local session
     * preparation needs attention. Ordinary admission is separately gated by the Host. */
    internal suspend fun validateAndPublish(graph: DatabaseEpochGraph,
        publish: (LegacySessionPreparationCheck) -> Unit) = withSessionPreparation {
        val preparation = prepareWhileAccountLocked(graph, bindSource = true)
        currentCoroutineContext().ensureActive()
        publish(preparation)
    }

    /** Only within withSessionPreparation, without re-entering Host, Room or the account lock. */
    internal suspend fun checkSessionWhileAccountLocked(graph: DatabaseEpochGraph,
        expected: LegacySessionPreparationSnapshot? = null): LegacySessionPreparationCheck =
        prepareWhileAccountLocked(graph, bindSource = false, expected = expected)
}
