package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.appearance.AccountIconNamespace
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.NextReplicaFenceEntity
import com.dayforge.domain.service.AccountSessionCoordinator
import java.util.UUID

/** Fresh evidence for a future explicit confirmation, NOT user consent or COMMIT authority. */
internal class ReplicaRecoveryConfirmationEvidence internal constructor(val stage: ReplicaSwitchStage,
    val candidate: ValidatedReplicaCandidate, val differences: ReplicaDifferencePreview) {
    val activationAuthorized: Boolean get() = false
}

/** Recovery owner through actual SQL COMMIT, not installed in the production UI or DI graph.
 * Shared sync serialization drains manual/worker requests; persistent gates stop new ordinary
 * work. Full input inspection takes the real icon/theme leases and rechecks every source byte.
 * Interrupted FREEZING/FROZEN stays blocked; no implicit cancel, orphan adoption or data rollback.
 * Scope/material publication and production consumer barriers remain required before activation.
 */
internal class NextReplicaRecoveryWorkflow(private val coordinator: BusinessSyncRepository,
    private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator, private val http: NextSyncHttp,
    private val candidates: NextReplicaCandidateValidator, private val differences: NextReplicaDifferenceReader,
    private val inputs: NextReplicaArchiveInputReader, private val archives: ReplicaPrivateArchiveStore) {
    private val stages = NextReplicaStageStore(database, tokens, sessions)
    private val commits = NextReplicaCommitStore(database, tokens, sessions)

    suspend fun prepare(previous: ReplicaDifferencePreview): ReplicaSwitchStage = coordinator.coordinateReplicaRecovery {
        check(stages.active() == null) { ReplicaSwitchGuard.PENDING }
        val fresh = candidate(previous.assessment.original, previous.candidate.newEpoch,
            previous.candidate.deviceProof.installationId)
        val preview = differences.revalidate(previous, fresh)
        require(!preview.hasUnfinishedTimer && preview.unconfirmedLocalRecords.isEmpty()) { "SYNC_RECOVERY_REVIEW_REQUIRED" }
        val stage = inputs.inspect(fresh.original, preview.oldStorage.hash) { input, _ ->
            require(!input.hasReview) { "SYNC_RECOVERY_REVIEW_REQUIRED" }
            val prior = sessions.exclusive { database.withTransaction { database.nextReplicaFenceDao().state() } }
            require(prior == null || prior.mode == NextReplicaFenceEntity.CLOSED) { "SYNC_REPLICA_FENCE_CHANGED" }
            val original = fresh.original
            ReplicaSwitchStage(UUID.randomUUID().toString(), original.session.authentication.userId,
                requireNotNull(original.session.serverInstanceId), requireNotNull(original.session.syncEpoch), fresh.newEpoch,
                original.session.authentication.generation, requireNotNull(original.deviceId),
                requireNotNull(original.capabilityRevision), original.capabilities.sorted(), fresh.deviceProof.installationId,
                NextReplicaSourceClosure.storage(input.storage, prior), NextReplicaSourceClosure.input(input, prior),
                fresh.contentHash, fresh.wireHash, fresh.cursor, previousFenceHash = NextReplicaSourceClosure.controlHash(prior))
        }
        stages.begin(stage)
        archiveFrozen(freeze(stage))
    }

    /** Cold continuation always re-reads authenticated server contents; saved hashes are not DTOs. */
    suspend fun resume(expected: ReplicaSwitchStage): ReplicaSwitchStage = coordinator.coordinateReplicaRecovery {
        if (expected.phase in setOf(ReplicaSwitchPhase.COMMITTING, ReplicaSwitchPhase.SQL_COMMITTED,
            ReplicaSwitchPhase.DESTINATION_BOUND))
            return@coordinateReplicaRecovery resumeCommit(expected)
        requireCurrent(expected)
        requireCandidate(expected, candidate(expected.original, expected.newEpoch, expected.installationId))
        when (expected.phase) {
            ReplicaSwitchPhase.FREEZING -> archiveFrozen(freeze(expected))
            ReplicaSwitchPhase.FROZEN -> archiveFrozen(expected)
            ReplicaSwitchPhase.ARCHIVE_VERIFIED -> { verifiedInput(expected) { _, _ -> Unit }; expected }
            ReplicaSwitchPhase.COMMITTING, ReplicaSwitchPhase.SQL_COMMITTED,
                ReplicaSwitchPhase.DESTINATION_BOUND -> error("SYNC_REPLICA_STAGE_CHANGED")
            ReplicaSwitchPhase.ABORTED -> error("SYNC_REPLICA_STAGE_TRANSITION_INVALID")
        }
    }

    /** No confirmation survives a changed source, auth, permission, candidate or archive file. */
    suspend fun verifyForConfirmation(expected: ReplicaSwitchStage): ReplicaRecoveryConfirmationEvidence =
        coordinator.coordinateReplicaRecovery { confirmationEvidence(expected) }

    private suspend fun confirmationEvidence(expected: ReplicaSwitchStage): ReplicaRecoveryConfirmationEvidence {
        require(expected.phase in setOf(ReplicaSwitchPhase.ARCHIVE_VERIFIED, ReplicaSwitchPhase.COMMITTING))
        requireCurrent(expected)
        if (expected.phase == ReplicaSwitchPhase.COMMITTING) require(commits.fate(expected) == ReplicaCommitFate.OLD) {
            "SYNC_REPLICA_ALREADY_COMMITTED"
        }
        val fresh = candidate(expected.original, expected.newEpoch, expected.installationId)
        requireCandidate(expected, fresh)
        expected.target?.let { require(it == target(fresh)) { "SYNC_RECOVERY_PREVIEW_CHANGED" } }
        val preview = verifiedInput(expected) { _, _ -> differences.capture(fresh) }
        require(!preview.hasUnfinishedTimer && preview.unconfirmedLocalRecords.isEmpty()) { "SYNC_RECOVERY_REVIEW_REQUIRED" }
        requireCurrent(expected)
        return ReplicaRecoveryConfirmationEvidence(expected, fresh, preview)
    }

    /** Only an explicit caller action invokes this, never discovery/startup/diagnostic GET.
     * Rebuild all evidence; no DTO, stage name or old displayed preview is COMMIT permission.
     * On SQL failure the durable COMMITTING pointer stays blocked, old data/DDL roll back together.
     */
    suspend fun confirm(evidence: ReplicaRecoveryConfirmationEvidence): ReplicaSwitchStage = coordinator.coordinateReplicaRecovery {
        val fresh = confirmationEvidence(evidence.stage)
        require(fresh.candidate.contentHash == evidence.candidate.contentHash &&
            fresh.candidate.projection.hash == evidence.candidate.projection.hash) { "SYNC_RECOVERY_PREVIEW_CHANGED" }
        val committing = if (fresh.stage.phase == ReplicaSwitchPhase.COMMITTING) fresh.stage
            else stages.recordCommitting(fresh.stage, target(fresh.candidate))
        commits.commit(committing, fresh.candidate)
        resumeCommit(committing)
    }

    /** Controlled forward publication, never cancel/rebootstrap/ordinary preferences writes.
     * A lost return after the ONE DS write is observed as destination-bound on cold resume.
     * Old namespace files/ready/selection do not become destination material proof.
     */
    suspend fun bindDestination(expected: ReplicaSwitchStage): ReplicaSwitchStage = coordinator.coordinateReplicaRecovery {
        require(expected.phase in setOf(ReplicaSwitchPhase.SQL_COMMITTED, ReplicaSwitchPhase.DESTINATION_BOUND))
        if (expected.phase == ReplicaSwitchPhase.DESTINATION_BOUND) return@coordinateReplicaRecovery resumeCommit(expected)
        val next = expected.transition(ReplicaSwitchPhase.DESTINATION_BOUND)
        if (stages.active() == next) return@coordinateReplicaRecovery resumeCommit(next)
        resumeCommit(expected)
        stages.bindDestination(expected)
    }

    /** Actual SQL fate distinguishes rollback from committed-but-undelivered. No automatic
     * pre-COMMIT retry: OLD is reverified and left awaiting another explicit confirm/cancel.
     * COMMITTED can only move forward, even after same-account reauthentication. Publication
     * and new namespace material verification still remain gated after SQL_COMMITTED.
     */
    private suspend fun resumeCommit(stage: ReplicaSwitchStage): ReplicaSwitchStage {
        requireRetainedOwner(stage)
        return when (commits.fate(stage)) {
            ReplicaCommitFate.OLD -> {
                require(stage.phase == ReplicaSwitchPhase.COMMITTING) { "SYNC_REPLICA_COMMIT_STATE_INVALID" }
                confirmationEvidence(stage)
                stage
            }
            ReplicaCommitFate.COMMITTED -> {
                archives.verify(receipt(stage, requireNotNull(stage.archive))) { requireRetainedOwner(stage) }
                if (stage.phase in setOf(ReplicaSwitchPhase.SQL_COMMITTED, ReplicaSwitchPhase.DESTINATION_BOUND)) stage
                else stages.recordSqlCommitted(stage)
            }
        }
    }

    private suspend fun target(candidate: ValidatedReplicaCandidate) = candidate.deviceProof.let {
        ReplicaStageTarget(candidate.projection.hash, sessions.exclusive { ReplicaProjection.schemaHash(database) },
            it.deviceId, it.capabilityRevision, it.capabilities.sorted(),
            it.isPrimaryEditor, it.structuralEditEnabled)
    }

    private suspend fun requireRetainedOwner(stage: ReplicaSwitchStage) {
        if (stage.phase == ReplicaSwitchPhase.DESTINATION_BOUND) {
            tokens.replicaDestinationAccess(stage)
            return
        }
        val actual = requireNotNull(tokens.localSyncAccess()) { "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED" }
        val sameOwner = stage.original.copy(session = stage.original.session.copy(authentication = actual.session.authentication))
        check(tokens.activeReplicaStage() == stage && actual.session.authentication.userId == stage.accountId && actual == sameOwner &&
            tokens.syncAuthenticationSnapshot(actual) != null && tokens.registeredInstallationId() == stage.installationId) {
            "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED"
        }
    }

    suspend fun cancel(expected: ReplicaSwitchStage): ReplicaSwitchStage = coordinator.coordinateReplicaRecovery {
        // Same-owner reauthentication can cancel pre-COMMIT but must not continue stale consent.
        stages.cancel(expected)
    }

    private suspend fun freeze(stage: ReplicaSwitchStage): ReplicaSwitchStage {
        sessions.exclusive { database.withTransaction {
            requireCurrent(stage)
            val actual = database.nextReplicaFenceDao().state()
            require(actual == stage.fence ||
                ((actual == null || actual.mode == NextReplicaFenceEntity.CLOSED && actual.recoveryId != stage.recoveryId) &&
                    NextReplicaSourceClosure.controlHash(actual) == stage.previousFenceHash)) { "SYNC_REPLICA_FENCE_CHANGED" }
            require(NextReplicaSourceClosure.storage(NextReplicaStorageProof(database).readInTransaction(), actual) ==
                stage.sourceHash) { "SYNC_RECOVERY_PREVIEW_CHANGED" }
            NextReplicaWriteFence(database).acquire(stage.fence)
        } }
        return stages.recordFrozen(stage)
    }

    private suspend fun archiveFrozen(stage: ReplicaSwitchStage): ReplicaSwitchStage {
        require(stage.phase == ReplicaSwitchPhase.FROZEN)
        val receipt = verifiedInput(stage) { input, access -> archives.create(input, access) { requireCurrent(stage) } }
        // Independent actual read through EOF, before retaining a durable receipt.
        archives.verify(receipt) { requireCurrent(stage) }
        return stages.recordArchive(stage, ReplicaStageArchive(receipt.archiveId, receipt.inputHash,
            receipt.byteLength, receipt.contentHash, receipt.entries.map {
                ReplicaStageArchiveEntry(it.name, it.byteLength, it.contentHash)
            }))
    }

    private suspend fun <T> verifiedInput(stage: ReplicaSwitchStage,
        block: suspend (ReplicaArchiveInput, ReplicaArchiveFileAccess) -> T): T {
        val raw = sessions.exclusive { database.withTransaction {
            requireCurrent(stage)
            require(database.nextReplicaFenceDao().state() == stage.fence) { "SYNC_REPLICA_FENCE_CHANGED" }
            NextReplicaStorageProof(database).readInTransaction().also {
                require(NextReplicaSourceClosure.storage(it, stage.fence) == stage.sourceHash) { "SYNC_RECOVERY_PREVIEW_CHANGED" }
            }
        } }
        val result = inputs.inspect(stage.original, raw.hash) { input, access ->
            requireCurrent(stage)
            require(!input.hasReview) { "SYNC_RECOVERY_REVIEW_REQUIRED" }
            require(NextReplicaSourceClosure.input(input, stage.fence) == stage.inputHash) { "SYNC_RECOVERY_PREVIEW_CHANGED" }
            stage.archive?.let { saved ->
                require(saved.inputHash == input.hash && saved.entries[1].contentHash == input.storage.hash) {
                    "SYNC_ARCHIVE_INPUT_CHANGED"
                }
                archives.verify(receipt(stage, saved)) { requireCurrent(stage) }
            }
            block(input, access)
        }
        requireCurrent(stage)
        return result
    }

    private suspend fun candidate(original: LocalSyncAccess, epoch: String, installation: String): ValidatedReplicaCandidate =
        candidates.validate(original, installation, http.recoveryPreview(original, epoch, installation))

    private fun requireCandidate(stage: ReplicaSwitchStage, fresh: ValidatedReplicaCandidate) {
        require(fresh.original == stage.original && fresh.newEpoch == stage.newEpoch &&
            fresh.deviceProof.installationId == stage.installationId && fresh.contentHash == stage.candidateHash &&
            fresh.cursor == stage.candidateCursor && !fresh.hasUnfinishedTimer) { "SYNC_RECOVERY_PREVIEW_CHANGED" }
    }

    private suspend fun requireCurrent(stage: ReplicaSwitchStage) {
        check(stages.active() == stage && tokens.localSyncAccess() == stage.original &&
            tokens.syncAuthenticationSnapshot(stage.original) != null &&
            tokens.registeredInstallationId() == stage.installationId) { "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED" }
    }

    private fun receipt(stage: ReplicaSwitchStage, saved: ReplicaStageArchive) = ReplicaArchiveReceipt(
        AccountIconNamespace(stage.accountId, stage.serverInstanceId, stage.oldEpoch), saved.archiveId, saved.inputHash,
        saved.byteLength, saved.contentHash, saved.entries.map { ReplicaArchiveEntry(it.name, it.byteLength, it.contentHash) })
}
