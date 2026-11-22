package com.dayforge.domain.service

import com.dayforge.data.local.*
import com.dayforge.data.repository.*
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Stable recovery doorway. A physical graph failure must not remove reauthentication controls. */
@Singleton
internal class DatabaseRecoveryFacade @Inject constructor(
    private val host: DatabaseRuntimeHost,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val connection: ReplicaRecoveryConnection
) : ReplicaRecoveryActions {
    private val registrations = mutableListOf<Guard>()
    private inner class Guard(cache: AccountIconMemory.Cache) : AccountIconMemory.Cache, DatabaseRuntimeHost.MemoryListener {
        val target = WeakReference(cache)
        @Volatile private var accountBlocked = false
        private var graphRegistration: Any? = null
        fun attach() { tokens.registerIconCache(this); graphRegistration = host.observeMemory(this) }
        override fun authenticationTransition(blocked: Boolean) {
            accountBlocked = blocked
            target.get()?.authenticationTransition(blocked)
        }
        override fun transition(blocked: Boolean) {
            if (!blocked) return
            // Revoke prepared previews/consent immediately, without manufacturing account logout
            // or permanently disabling the existing DB-independent recovery doorway.
            target.get()?.let { cache ->
                cache.authenticationTransition(true)
                cache.authenticationTransition(accountBlocked)
            }
        }
    }
    override fun observeInvalidation(cache: AccountIconMemory.Cache) {
        val guard = Guard(cache)
        synchronized(registrations) {
            registrations.removeAll { it.target.get() == null }
            registrations += guard
        }
        guard.attach()
    }
    override fun entryChanges(): Flow<Unit> = host.controlHints { it.replicaRecoveryService.entryChanges() }
    override suspend fun control(): ReplicaRecoveryControl = try {
        host.withGraph(DatabaseRuntimeHost.Work.CONTROL) { it.replicaRecoveryService.control() }
    } catch (_: DatabaseRuntimeHost.Unavailable) {
        // Same original strict stage/account witness. No file, cache or failed graph becomes
        // recovery eligibility, and no broad storage/authentication exception is swallowed.
        sessions.exclusive {
            val stage = tokens.activeReplicaStage()
            val authentication = tokens.replicaUiAuthentication()
            if (stage != null) {
                val witness = tokens.replicaLoginWitness()
                check(witness.accountId == stage.accountId && witness.serverInstanceId == stage.serverInstanceId &&
                    witness.destinationEpoch == stage.newEpoch &&
                    (authentication == null || authentication.userId == stage.accountId)) {
                    "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED"
                }
                tokens.requireReplicaLoginWitness(witness)
            }
            ReplicaRecoveryControl(authentication, stage, entryAvailable = false)
        }
    }
    override suspend fun reauthenticate(username: String, password: String) = connection.reauthenticate(username, password)
    override suspend fun preview() = host.withGraph(DatabaseRuntimeHost.Work.CONTROL) { it.replicaRecoveryService.preview() }
    override suspend fun prepare(preview: ReplicaDifferencePreview) = host.withGraph(DatabaseRuntimeHost.Work.CONTROL) {
        it.replicaRecoveryService.prepare(preview)
    }
    override suspend fun confirmation(stage: ReplicaSwitchStage) = host.withGraph(DatabaseRuntimeHost.Work.CONTROL) {
        it.replicaRecoveryService.confirmation(stage)
    }
    override suspend fun confirm(evidence: ReplicaRecoveryConfirmationEvidence) = host.withGraph(DatabaseRuntimeHost.Work.CONTROL) {
        it.replicaRecoveryService.confirm(evidence)
    }
    override suspend fun resume(stage: ReplicaSwitchStage) = host.withGraph(DatabaseRuntimeHost.Work.CONTROL) {
        it.replicaRecoveryService.resume(stage)
    }
    override suspend fun cancel(stage: ReplicaSwitchStage) = host.withGraph(DatabaseRuntimeHost.Work.CONTROL) {
        it.replicaRecoveryService.cancel(stage)
    }
}
