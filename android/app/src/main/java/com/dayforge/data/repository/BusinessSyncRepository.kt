package com.dayforge.data.repository

import android.os.Build
import androidx.room.withTransaction
import com.dayforge.BuildConfig
import com.dayforge.data.api.EndpointResolver
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.DeviceRegisterRequest
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.model.SyncProgress
import com.dayforge.domain.service.AccountSessionCoordinator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared manual/background entry. Server metadata still defaults to v4 until coordinated release. */
@Singleton
class BusinessSyncRepository @Inject internal constructor(
    private val legacy: IncrementalSyncRepository,
    private val endpoints: EndpointResolver,
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val http: NextSyncHttp,
    private val next: NextSyncRuntime
) {
    private val mutex = Mutex()

    suspend fun sync(progress: (SyncProgress) -> Unit = {}, afterSync: (suspend () -> Unit)? = null): Unit = mutex.withLock {
        val (original, originalCore, originalSync) = sessions.exclusive {
            Triple(tokens.authenticationSnapshot()?.session, tokens.localCoreWriteAccess(), tokens.localSyncAccess())
        }
        val identity = endpoints.resolve()
        when (identity?.protocolVersion) {
            null, 4 -> legacy.syncForAuthentication(original, progress, afterSync)
            5 -> {
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
                next.syncRoundsCaptured(access, progress, afterSync)
            }
            else -> throw SyncProtocolException("SYNC_PROTOCOL_UNSUPPORTED")
        }
    }
}
