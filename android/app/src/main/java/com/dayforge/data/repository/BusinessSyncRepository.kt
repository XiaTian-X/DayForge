package com.dayforge.data.repository

import android.os.Build
import androidx.room.withTransaction
import com.dayforge.BuildConfig
import com.dayforge.data.api.EndpointResolver
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.DeviceRegisterRequest
import com.dayforge.data.api.dto.DeviceResponse
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.model.SyncProgress
import com.dayforge.domain.service.AccountSessionCoordinator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.asStateFlow
import com.dayforge.data.local.LocalDataSession

internal data class BusinessSyncCompletion(val session: LocalDataSession?, val sequence: Long)

/** Shared manual/background entry. Server metadata still defaults to v4 until coordinated release. */
@Singleton
class BusinessSyncRepository private constructor(
    private val fixed: EpochBusinessSyncRepository?,
    private val host: com.dayforge.data.local.DatabaseRuntimeHost?,
    coordination: com.dayforge.data.local.DatabaseSyncCoordination
) {
    internal constructor(legacy: IncrementalSyncRepository, endpoints: EndpointResolver, database: HabitDatabase,
        tokens: TokenManager, sessions: AccountSessionCoordinator, http: NextSyncHttp, next: NextSyncRuntime,
        coordination: com.dayforge.data.local.DatabaseSyncCoordination = com.dayforge.data.local.DatabaseSyncCoordination()) :
        this(EpochBusinessSyncRepository(legacy, endpoints, database, tokens, sessions, http, next, coordination), null, coordination)
    @Inject internal constructor(host: com.dayforge.data.local.DatabaseRuntimeHost,
        coordination: com.dayforge.data.local.DatabaseSyncCoordination) : this(null, host, coordination)
    internal val completions = coordination.completed.asStateFlow()
    private suspend fun <T> operation(block: suspend (EpochBusinessSyncRepository) -> T): T =
        if (fixed != null) block(fixed) else requireNotNull(host).withGraph { graph ->
            block(requireNotNull(graph.businessSyncRepository.fixed))
        }
    internal fun problemChanges() = fixed?.problemChanges() ?: requireNotNull(host).hints {
        requireNotNull(it.businessSyncRepository.fixed).problemChanges()
    }
    internal suspend fun readProblems() = operation { it.readProblems() }
    internal suspend fun readRecoveryPreview(root: NextRecoveryKey) = operation { it.readRecoveryPreview(root) }
    internal suspend fun <T> coordinateReplicaRecovery(block: suspend () -> T): T = operation { it.coordinateReplicaRecovery(block) }
    internal suspend fun prepareRecoveryConfirmation(preview: NextRecoveryDependencyPreview, selected: Set<NextRecoveryKey>) =
        operation { it.prepareRecoveryConfirmation(preview, selected) }
    internal suspend fun confirmRecovery(ticket: NextRecoveryConfirmation) = operation { it.confirmRecovery(ticket) }
    suspend fun makeCurrentDevicePrimary(): DeviceResponse = operation { it.makeCurrentDevicePrimary() }
    suspend fun setCurrentDeviceStructuralEditing(enabled: Boolean): DeviceResponse = operation { it.setCurrentDeviceStructuralEditing(enabled) }
    suspend fun sync(progress: (SyncProgress) -> Unit = {}, afterSync: (suspend () -> Unit)? = null): Unit =
        operation { it.sync(progress, afterSync) }
    suspend fun retrySync(progress: (SyncProgress) -> Unit = {}): Unit = operation { it.retrySync(progress) }
}

/** Original protocol implementation, scoped to one eagerly constructed physical graph. */
internal class EpochBusinessSyncRepository(
    private val legacy: IncrementalSyncRepository,
    private val endpoints: EndpointResolver,
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val http: NextSyncHttp,
    private val next: NextSyncRuntime,
    coordination: com.dayforge.data.local.DatabaseSyncCoordination = com.dayforge.data.local.DatabaseSyncCoordination()
) {
    private val mutex = coordination.business
    private val replica = ReplicaPresentationRepository(database, tokens, sessions)
    private val completed = coordination.completed
    internal val completions = completed.asStateFlow()
    internal fun problemChanges() = next.problemChanges()
    internal suspend fun readProblems() = next.readProblems()
    internal suspend fun readRecoveryPreview(root: NextRecoveryKey) = next.readRecoveryPreview(root)

    /** Same coordinator as manual/background sync; no account or Room lock spans network/file I/O. */
    internal suspend fun <T> coordinateReplicaRecovery(block: suspend () -> T): T = mutex.withLock { block() }

    /** Local recovery shares the manual/worker/device coordinator. No HTTP under account/Room locks. */
    internal suspend fun prepareRecoveryConfirmation(preview: NextRecoveryDependencyPreview,
        selected: Set<NextRecoveryKey>) = mutex.withLock {
        replica.requireOrdinaryAccess()
        next.prepareRecoveryConfirmation(preview, selected)
    }

    internal suspend fun confirmRecovery(ticket: NextRecoveryConfirmation) = mutex.withLock {
        replica.requireOrdinaryAccess()
        next.confirmRecovery(ticket)
    }

    /** Existing settings actions share sync serialization, but never hold the account lock over v5 HTTP. */
    suspend fun makeCurrentDevicePrimary(): DeviceResponse = changeDevice(null)

    suspend fun setCurrentDeviceStructuralEditing(enabled: Boolean): DeviceResponse = changeDevice(enabled)

    private suspend fun changeDevice(editing: Boolean?): DeviceResponse = mutex.withLock {
        replica.requireOrdinaryAccess()
        val (original, installation) = sessions.exclusive {
            Triple(tokens.authenticationSnapshot()?.session, tokens.localCoreWriteAccess(), tokens.localSyncAccess()) to
                tokens.registeredInstallationId()
        }
        val (authentication, core, access) = original
        requireNotNull(authentication) { "SYNC_ACCOUNT_PROOF_REQUIRED" }
        val identity = endpoints.resolve()
        when (identity?.protocolVersion) {
            null, 4 -> legacy.changeDeviceForAuthentication(authentication, editing)
            5 -> {
                val expectedCore = requireNotNull(core) { "SYNC_ACCOUNT_PROOF_REQUIRED" }
                val expected = requireNotNull(access) { "SYNC_DEVICE_PROOF_REQUIRED" }
                val installed = requireNotNull(installation) { "SYNC_INSTALLATION_PROOF_REQUIRED" }
                check(identity.serverInstanceId == expected.session.serverInstanceId &&
                    identity.syncEpoch == expected.session.syncEpoch) { "SYNC_REPLICA_CHANGED" }
                sessions.exclusive {
                    check(tokens.localCoreWriteAccess() == expectedCore && tokens.localSyncAccess() == expected &&
                        tokens.registeredInstallationId() == installed) { "SYNC_ACCOUNT_CHANGED" }
                    database.withTransaction {
                        NextProtocolAdmission.requireRoundsOrEmpty(database, expected)
                        NextChallengeStore(database).activeInTransaction(expected)
                    }
                }
                val response = http.session(expected) {
                    if (editing == null) it.makePrimary(installed) else it.updateEditing(installed, editing)
                } ?: throw SyncProtocolException("SYNC_PROTOCOL_V5_REQUIRED")
                sessions.exclusive {
                    database.withTransaction {
                        NextProtocolAdmission.requireRoundsOrEmpty(database, expected)
                        NextChallengeStore(database).activeInTransaction(expected)
                        tokens.saveNextRegistration(expectedCore, expected, installed,
                            requireNotNull(expected.session.serverInstanceId), requireNotNull(expected.session.syncEpoch),
                            response.deviceId, response.capabilities.toSet(), response.isPrimaryEditor, response.capabilityRevision)
                    }
                }
                response
            }
            else -> throw SyncProtocolException("SYNC_PROTOCOL_UNSUPPORTED")
        }
    }

    suspend fun sync(progress: (SyncProgress) -> Unit = {}, afterSync: (suspend () -> Unit)? = null): Unit =
        synchronize(progress, afterSync, retryRejected = false)

    /** A user retry shares discovery/account capture; v5 always retains original requests and rejection proofs. */
    suspend fun retrySync(progress: (SyncProgress) -> Unit = {}): Unit =
        synchronize(progress, null, retryRejected = true)

    private suspend fun synchronize(progress: (SyncProgress) -> Unit, afterSync: (suspend () -> Unit)?,
        retryRejected: Boolean): Unit = mutex.withLock {
        replica.requireOrdinaryAccess()
        val (original, originalCore, originalSync) = sessions.exclusive {
            Triple(tokens.authenticationSnapshot()?.session, tokens.localCoreWriteAccess(), tokens.localSyncAccess())
        }
        val identity = endpoints.resolve()
        when (identity?.protocolVersion) {
            null, 4 -> if (retryRejected) legacy.retrySyncForAuthentication(original, progress)
                else legacy.syncForAuthentication(original, progress, afterSync)
            5 -> {
                // A changed epoch needs an explicit recovery preview, not a registration
                // with relabelled old work. This diagnostic never publishes new authority.
                if (originalSync != null && identity.serverInstanceId == originalSync.session.serverInstanceId &&
                    identity.syncEpoch != originalSync.session.syncEpoch) {
                    throw NextReplicaRecoveryRequired(NextReplicaRecoveryReader(database, tokens, sessions)
                        .read(originalSync, identity))
                }
                val (core, oldSync) = sessions.exclusive {
                    check(tokens.authenticationSnapshot()?.session == original) { "SYNC_ACCOUNT_CHANGED" }
                    val core = requireNotNull(tokens.localCoreWriteAccess()) { "SYNC_ACCOUNT_PROOF_REQUIRED" }
                    val registered = tokens.localSyncAccess()
                    check(core == originalCore && registered == originalSync) { "SYNC_ACCOUNT_CHANGED" }
                    check(core.capturedDeviceId == null || registered != null) { "SYNC_DEVICE_PROOF_REQUIRED" }
                    database.withTransaction { NextProtocolAdmission.requireRoundsOrEmpty(database, registered) }
                    core to registered
                }
                val registration = sessions.exclusive {
                    check(tokens.localCoreWriteAccess() == core && tokens.localSyncAccess() == oldSync) { "SYNC_ACCOUNT_CHANGED" }
                    requireNotNull(tokens.registrationSyncAccess(identity.serverInstanceId, identity.syncEpoch)) { "SYNC_REPLICA_CHANGED" }
                }
                val installation = tokens.getOrCreateInstallationId()
                val response = http.session(registration) {
                    it.register(DeviceRegisterRequest(installation, 5, "android", "interactive", BuildConfig.VERSION_NAME, Build.MODEL))
                } ?: throw SyncProtocolException("SYNC_PROTOCOL_V5_REQUIRED")
                val access = sessions.exclusive {
                    check(tokens.localCoreWriteAccess() == core && tokens.localSyncAccess() == oldSync) { "SYNC_ACCOUNT_CHANGED" }
                    database.withTransaction {
                        NextProtocolAdmission.requireRoundsOrEmpty(database, oldSync)
                        tokens.saveNextRegistration(core, oldSync, installation, identity.serverInstanceId, identity.syncEpoch,
                            response.deviceId, response.capabilities.toSet(), response.isPrimaryEditor, response.capabilityRevision)
                    }
                }
                // Never invoke plain v5 or recapture a subsequent login after registration.
                try {
                    next.syncRoundsCaptured(access, progress) {
                        // This callback already owns the account lock after the whole successful round.
                        // Cleanup is allowed to log out; never turn that successful cleanup into failure,
                        // nor let it clear another authentication's material-wait display.
                        afterSync?.invoke()
                        if (tokens.localSyncAccess() == access)
                            completed.value = BusinessSyncCompletion(access.session, Math.addExact(completed.value.sequence, 1))
                    }
                } catch (pending: NextMaterialPending) {
                    // Fence under this coordinator's mutex: an earlier queued success cannot
                    // clear this wait, while a later success racing the UI catch still can.
                    throw NextMaterialPending(pending.session, completed.value.sequence)
                }
            }
            else -> throw SyncProtocolException("SYNC_PROTOCOL_UNSUPPORTED")
        }
    }
}
