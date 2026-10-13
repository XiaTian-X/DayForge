package com.dayforge.domain.service

import com.dayforge.data.local.AccountIconMemory
import com.dayforge.data.local.AuthenticationSession
import com.dayforge.data.local.ReplicaSwitchStage
import com.dayforge.data.local.TokenManager
import com.dayforge.data.repository.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

internal data class ReplicaRecoveryControl(val authentication: AuthenticationSession?, val stage: ReplicaSwitchStage?,
    val entryAvailable: Boolean = false)

/** UI actions, not a second coordinator or a persistence/activation authority. */
internal interface ReplicaRecoveryActions {
    fun observeInvalidation(cache: AccountIconMemory.Cache)
    fun entryChanges(): Flow<Unit>
    suspend fun control(): ReplicaRecoveryControl
    suspend fun reauthenticate(username: String, password: String): AuthenticationSession
    suspend fun preview(): ReplicaDifferencePreview
    suspend fun prepare(preview: ReplicaDifferencePreview): ReplicaSwitchStage
    suspend fun confirmation(stage: ReplicaSwitchStage): ReplicaRecoveryConfirmationEvidence
    suspend fun confirm(evidence: ReplicaRecoveryConfirmationEvidence): ReplicaCompletionOutcome
    suspend fun resume(stage: ReplicaSwitchStage): ReplicaRecoveryContinuation
    suspend fun cancel(stage: ReplicaSwitchStage): ReplicaSwitchStage
}

@Singleton
internal class ReplicaRecoveryService @Inject constructor(private val repository: ReplicaRecoveryRepository,
    private val tokens: TokenManager, private val sessions: AccountSessionCoordinator) : ReplicaRecoveryActions {
    override fun observeInvalidation(cache: AccountIconMemory.Cache) = tokens.registerIconCache(cache)
    override fun entryChanges(): Flow<Unit> = repository.entryChanges()
    override suspend fun control() = sessions.exclusive {
        val stage = repository.pending()
        val authentication = tokens.replicaUiAuthentication()
        if (stage != null) {
            val witness = tokens.replicaLoginWitness()
            check(witness.accountId == stage.accountId && witness.serverInstanceId == stage.serverInstanceId &&
                witness.destinationEpoch == stage.newEpoch &&
                (authentication == null || authentication.userId == stage.accountId)) { "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED" }
            tokens.requireReplicaLoginWitness(witness)
        }
        ReplicaRecoveryControl(authentication, stage, repository.entryAvailableWhileAccountLocked())
    }
    override suspend fun reauthenticate(username: String, password: String) = repository.reauthenticate(username, password)
    override suspend fun preview() = repository.preview()
    override suspend fun prepare(preview: ReplicaDifferencePreview) = repository.prepare(preview)
    override suspend fun confirmation(stage: ReplicaSwitchStage) = repository.confirmation(stage)
    override suspend fun confirm(evidence: ReplicaRecoveryConfirmationEvidence) = repository.confirm(evidence)
    override suspend fun resume(stage: ReplicaSwitchStage) = repository.resume(stage)
    override suspend fun cancel(stage: ReplicaSwitchStage) = repository.cancel(stage)
}
