package com.dayforge.data.repository

import android.content.Context
import android.database.Cursor
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.withTransaction
import com.dayforge.data.appearance.BuiltInThemes
import com.dayforge.data.appearance.ThemeFileRepository
import com.dayforge.data.local.*
import com.dayforge.domain.service.AccountIconController
import com.dayforge.domain.service.AccountIconRuntime
import com.dayforge.domain.service.AccountSessionCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.withContext

internal sealed interface ReplicaRecoveryContinuation {
    class Confirmation(val evidence: ReplicaRecoveryConfirmationEvidence) : ReplicaRecoveryContinuation
    class Destination(val outcome: ReplicaCompletionOutcome) : ReplicaRecoveryContinuation
}

/** Production composition for explicit user actions. Startup observes local control only;
 * discovery/workers never invoke recovery work. The business coordinator, account lock, icon runtime and DS remain canonical.
 * Each operation rebuilds transport/proofs; no singleton retains consent, passwords or HTTP leases.
 */
@Singleton
internal class ReplicaRecoveryRepository @Inject constructor(@param:ApplicationContext private val context: Context,
    private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator, private val preferences: PreferencesManager,
    private val values: DataStore<Preferences>, private val business: BusinessSyncRepository,
    private val candidates: NextReplicaCandidateValidator, private val icons: AccountIconController,
    private val connection: ReplicaRecoveryConnection) {
    private val differences get() = NextReplicaDifferenceReader(database, tokens, sessions, preferences)

    /** Local entry hints only. Neither observation nor availability authorizes recovery work. */
    fun entryChanges() = merge(tokens.replicaPresentationChanges,
        database.invalidationTracker.createFlow("next_sync_state").map { Unit }).retryWhen { error, _ ->
        if (error is CancellationException) throw error
        emit(Unit); delay(1_000); true
    }

    /** Called under the account lock. Read only the active profile's scope, without decrypting
     * old credentials or scanning business/problem queues. Full proofs remain at each action.
     */
    suspend fun entryAvailableWhileAccountLocked(): Boolean = try {
        val witness = tokens.replicaLoginWitness()
        val device = tokens.syncDeviceId.first()
        val available = database.withTransaction {
            database.openHelper.writableDatabase.query(
                "SELECT id,accountId,serverInstanceId,syncEpoch,deviceId,challengeContract FROM next_sync_state LIMIT 2"
            ).use { row ->
                row.count == 1 && row.moveToFirst() &&
                    row.getType(0) == Cursor.FIELD_TYPE_INTEGER && row.getLong(0) == 1L &&
                    (1..4).all { row.getType(it) == Cursor.FIELD_TYPE_STRING } &&
                    row.getType(5) == Cursor.FIELD_TYPE_INTEGER && row.getLong(5) == 1L &&
                    row.getString(1) == witness.accountId && row.getString(2) == witness.serverInstanceId &&
                    row.getString(3) == witness.currentEpoch && row.getString(4) == device
            }
        }
        tokens.requireReplicaLoginWitness(witness)
        available
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        false
    }

    suspend fun pending(): ReplicaSwitchStage? = withContext(Dispatchers.IO) { tokens.replicaPresentationWitness().stage }

    suspend fun reauthenticate(username: String, password: String): AuthenticationSession =
        connection.reauthenticate(username, password)

    suspend fun preview(): ReplicaDifferencePreview {
        requireOpenSource()
        return connection.use { connected ->
            check(pending() == null) { ReplicaSwitchGuard.PENDING }
            val (original, installation) = sessions.exclusive {
                requireNotNull(tokens.localSyncAccess()) { "SYNC_RECOVERY_AUTHENTICATION_REQUIRED" } to
                    requireNotNull(tokens.registeredInstallationId()) { "SYNC_RECOVERY_INSTALLATION_UNKNOWN" }
            }
            val response = connected.businessHttp.recoveryPreview(original, connected.epoch, installation)
            differences.capture(candidates.validate(original, installation, response))
        }
    }

    suspend fun prepare(preview: ReplicaDifferencePreview): ReplicaSwitchStage {
        requireOpenSource()
        return owned { connected, runtime, workflow ->
            check(connected.epoch == preview.candidate.newEpoch) { "SYNC_RECOVERY_ENDPOINT_CHANGED" }
            check(runtime.metadata.usesAccess(tokens, sessions)) { "SYNC_ARCHIVE_STORE_MISMATCH" }
            workflow.prepare(preview)
        }
    }

    private suspend fun requireOpenSource() = withContext(Dispatchers.IO) {
        ReplicaPresentationRepository(database, tokens, sessions).requireOrdinaryAccess()
    }

    suspend fun confirmation(expected: ReplicaSwitchStage): ReplicaRecoveryConfirmationEvidence = owned { connected, _, workflow ->
        requireEpoch(connected, expected)
        workflow.verifyForConfirmation(expected)
    }

    suspend fun confirm(evidence: ReplicaRecoveryConfirmationEvidence): ReplicaCompletionOutcome = owned { connected, runtime, workflow ->
        requireEpoch(connected, evidence.stage)
        forward(workflow.confirm(evidence), connected, runtime, workflow)
    }

    /** Cold retry before COMMIT only returns fresh evidence; it MUST NOT manufacture consent.
     * Once real SQL has committed, continue forward in one bounded attempt, retaining gates
     * on material wait/server changes/failure. No unbounded retry loop in a screen coroutine.
     */
    suspend fun resume(expected: ReplicaSwitchStage): ReplicaRecoveryContinuation {
        if (expected.phase == ReplicaSwitchPhase.COMPLETED) return completed(expected)
        return owned { connected, runtime, workflow ->
            requireEpoch(connected, expected)
            val stage = workflow.resume(expected)
            if (stage.phase in setOf(ReplicaSwitchPhase.ARCHIVE_VERIFIED, ReplicaSwitchPhase.COMMITTING))
                ReplicaRecoveryContinuation.Confirmation(workflow.verifyForConfirmation(stage))
            else ReplicaRecoveryContinuation.Destination(forward(stage, connected, runtime, workflow))
        }
    }

    private suspend fun completed(stage: ReplicaSwitchStage): ReplicaRecoveryContinuation = withContext(Dispatchers.IO) { sessions.exclusive {
        val access = requireNotNull(tokens.localSyncAccess()) { "SYNC_RECOVERY_AUTHENTICATION_REQUIRED" }
        check(tokens.activeReplicaStage() == null && tokens.replicaStage(stage.recoveryId) == stage &&
            access.session.authentication.userId == stage.accountId && access.session.serverInstanceId == stage.serverInstanceId &&
            access.session.syncEpoch == stage.newEpoch && access.deviceId == stage.publishedSnapshot.target.deviceId &&
            tokens.registeredInstallationId() == stage.installationId &&
            ReplicaPresentationRepository(database, tokens, sessions).inspectWhileAccountLocked().problem == null) {
            "SYNC_REPLICA_RELEASE_STATE_INVALID"
        }
        ReplicaRecoveryContinuation.Destination(ReplicaCompletionOutcome.Completed(stage))
    } }

    /** Offline same-owner cancellation is possible only when actual SQL still proves OLD.
     * No network/runtime initialization, rollback-after-COMMIT, archive removal or queue replay.
     */
    suspend fun cancel(expected: ReplicaSwitchStage): ReplicaSwitchStage = withContext(Dispatchers.IO) {
        business.coordinateReplicaRecovery { NextReplicaStageStore(database, tokens, sessions).cancel(expected) }
    }

    private suspend fun forward(stage: ReplicaSwitchStage, connected: ConnectedReplicaRecovery,
        runtime: AccountIconRuntime, workflow: NextReplicaRecoveryWorkflow): ReplicaCompletionOutcome {
        val bound = if (stage.phase == ReplicaSwitchPhase.SQL_COMMITTED) workflow.bindDestination(stage) else stage
        require(bound.destinationPending) { "SYNC_REPLICA_STAGE_TRANSITION_INVALID" }
        return workflow.finishRecovery(bound, runtime, connected.materialHttp)
    }

    private fun requireEpoch(connected: ConnectedReplicaRecovery, stage: ReplicaSwitchStage) {
        check(connected.epoch == stage.newEpoch) { "SYNC_RECOVERY_ENDPOINT_CHANGED" }
    }

    private suspend fun <T> owned(block: suspend (ConnectedReplicaRecovery, AccountIconRuntime,
        NextReplicaRecoveryWorkflow) -> T): T = withContext(Dispatchers.IO) {
        connection.use { connected -> icons.withReplicaRuntime { runtime ->
            val inputs = runtime.metadata.replicaInputReader(context, database, preferences, connected.businessHttp,
                runtime.transfers, runtime.store, values, ThemeFileRepository(context.filesDir), BuiltInThemes(context.assets))
            val workflow = NextReplicaRecoveryWorkflow(business, database, tokens, sessions, connected.businessHttp,
                candidates, differences, inputs, ReplicaPrivateArchiveStore(context.filesDir))
            block(connected, runtime, workflow)
        } }
    }
}
