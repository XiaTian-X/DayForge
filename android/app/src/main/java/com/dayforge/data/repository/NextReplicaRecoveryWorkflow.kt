package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.appearance.AccountIconNamespace
import com.dayforge.data.appearance.AccountIconHttp
import com.dayforge.data.appearance.ReplicaMaterialAccess
import com.dayforge.data.appearance.ConfigIconUse
import com.dayforge.domain.model.ObjectAppearance
import kotlinx.serialization.json.*
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.NextReplicaFenceEntity
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.domain.service.AccountIconRuntime
import com.dayforge.domain.service.IconMaterialOutcome
import java.util.UUID

/** Fresh evidence for a future explicit confirmation, NOT user consent or COMMIT authority. */
internal class ReplicaRecoveryConfirmationEvidence internal constructor(val stage: ReplicaSwitchStage,
    val candidate: ValidatedReplicaCandidate, val differences: ReplicaDifferencePreview,
    val materials: ReplicaMaterialPreflight) {
    val activationAuthorized: Boolean get() = false
}

internal sealed interface ReplicaCompletionOutcome {
    data class Waiting(val stage: ReplicaSwitchStage, val material: IconMaterialOutcome) : ReplicaCompletionOutcome
    data class Changed(val stage: ReplicaSwitchStage) : ReplicaCompletionOutcome
    data class Completed(val stage: ReplicaSwitchStage) : ReplicaCompletionOutcome
}

/** Recovery owner composed by ReplicaRecoveryRepository for explicit production actions.
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
            materialPreflight(fresh, input).requireAvailable()
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
        if (expected.phase == ReplicaSwitchPhase.COMPLETED) return@coordinateReplicaRecovery completed(expected)
        if (expected.phase == ReplicaSwitchPhase.RELEASING) {
            commits.releaseFate(expected); verifyRetainedArchive(expected)
            return@coordinateReplicaRecovery expected // Fresh material/full finalization remains mandatory.
        }
        if (expected.phase == ReplicaSwitchPhase.CATCHUP_COMMITTING) return@coordinateReplicaRecovery catchUpOwned(expected)
        if (expected.phase in setOf(ReplicaSwitchPhase.COMMITTING, ReplicaSwitchPhase.SQL_COMMITTED,
            ReplicaSwitchPhase.DESTINATION_BOUND, ReplicaSwitchPhase.CATCHUP_BOUND))
            return@coordinateReplicaRecovery resumeCommit(expected)
        requireCurrent(expected)
        requireCandidate(expected, candidate(expected.original, expected.newEpoch, expected.installationId))
        when (expected.phase) {
            ReplicaSwitchPhase.FREEZING -> archiveFrozen(freeze(expected))
            ReplicaSwitchPhase.FROZEN -> archiveFrozen(expected)
            ReplicaSwitchPhase.ARCHIVE_VERIFIED -> { verifiedInput(expected) { _, _ -> Unit }; expected }
            ReplicaSwitchPhase.COMMITTING, ReplicaSwitchPhase.SQL_COMMITTED,
                ReplicaSwitchPhase.DESTINATION_BOUND, ReplicaSwitchPhase.CATCHUP_COMMITTING,
                ReplicaSwitchPhase.CATCHUP_BOUND, ReplicaSwitchPhase.RELEASING,
                ReplicaSwitchPhase.COMPLETED -> error("SYNC_REPLICA_STAGE_CHANGED")
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
        val (preview, materials) = verifiedInput(expected) { input, _ ->
            val material = materialPreflight(fresh, input).also { it.requireAvailable() }
            differences.capture(fresh) to material
        }
        require(!preview.hasUnfinishedTimer && preview.unconfirmedLocalRecords.isEmpty()) { "SYNC_RECOVERY_REVIEW_REQUIRED" }
        requireCurrent(expected)
        return ReplicaRecoveryConfirmationEvidence(expected, fresh, preview, materials)
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

    /** Reuse the sole material owner, real catalog/checkpoint and native download journal.
     * A completed round is NOT activation/selection proof. The stage, SQL fence and all ordinary
     * consumer gates remain retained; interruptions reacquire fresh authority, never old requests.
     */
    suspend fun recoverMaterials(expected: ReplicaSwitchStage, material: AccountIconRuntime,
        transport: AccountIconHttp): IconMaterialOutcome = coordinator.coordinateReplicaRecovery {
        require(expected.phase in setOf(ReplicaSwitchPhase.DESTINATION_BOUND, ReplicaSwitchPhase.CATCHUP_BOUND))
        require(material.metadata.usesAccess(tokens, sessions) &&
            inputs.usesDestinationMaterial(material.metadata, material.transfers, material.store)) { "ICON_RECOVERY_STORE_MISMATCH" }
        ReplicaMaterialAccess.verified(database, tokens, sessions, archives, expected) { context ->
            material.synchronize(context, transport)
        }
    }

    /** Read actual current server business after binding, without bypassing the old-scope
     * preview guard. A changed candidate needs durable catch-up, never optimistic release.
     */
    suspend fun verifyDestinationSnapshot(expected: ReplicaSwitchStage): ReplicaDestinationPreview =
        coordinator.coordinateReplicaRecovery {
            ReplicaDestinationReadAccess.verified(database, tokens, sessions, archives, expected) { read ->
                val fresh = candidates.validateDestination(read, read.read(http))
                ReplicaDestinationPreview(fresh, fresh.contentHash == expected.publishedSnapshot.contentHash &&
                    fresh.projection.hash == expected.publishedSnapshot.target.projectionHash && !fresh.hasUnfinishedTimer)
            }
        }

    /** Persist the whole fresh intent before replacing SQL. No old request is retargeted or
     * replayed; the original confirmation/archive/target are immutable. A lost SQL return is
     * recognized by actual full fate and only completes DS forward. The gates remain closed.
     */
    suspend fun catchUp(expected: ReplicaSwitchStage): ReplicaSwitchStage =
        coordinator.coordinateReplicaRecovery { catchUpOwned(expected) }

    private suspend fun catchUpOwned(expected: ReplicaSwitchStage): ReplicaSwitchStage {
        require(expected.destinationPending)
        tokens.replicaDestinationAccess(expected)
        if (expected.phase == ReplicaSwitchPhase.CATCHUP_COMMITTING &&
            commits.catchupFate(expected) == ReplicaCatchupFate.COMMITTED) {
            verifyRetainedArchive(expected)
            return stages.bindCatchup(expected)
        }
        val fresh = ReplicaDestinationReadAccess.verified(database, tokens, sessions, archives, expected) { read ->
            candidates.validateDestination(read, read.read(http))
        }
        require(!fresh.hasUnfinishedTimer) { "SYNC_RECOVERY_REVIEW_REQUIRED" }
        val snapshot = ReplicaStageSnapshot(fresh.contentHash, fresh.wireHash, fresh.cursor, target(fresh))
        // Fresh same-device grants may advance; same-version drift/schema/cursor regression may not.
        val intent = ReplicaStageCatchup(expected.publishedSnapshot, snapshot, fresh.original.session.authentication.generation)
        val retained = expected.catchup
        val committing = if (expected.phase == ReplicaSwitchPhase.CATCHUP_COMMITTING &&
            retained != null && retained.authenticationGeneration == intent.authenticationGeneration &&
            retained.next.copy(wireHash = snapshot.wireHash) == snapshot) expected
        else if (expected.phase != ReplicaSwitchPhase.CATCHUP_COMMITTING &&
            snapshot.copy(wireHash = expected.publishedSnapshot.wireHash) == expected.publishedSnapshot) return expected
        else {
            if (expected.phase == ReplicaSwitchPhase.RELEASING) commits.refreezeForCatchup(expected)
            stages.recordCatchup(expected, intent)
        }
        commits.catchup(committing, fresh)
        verifyRetainedArchive(committing)
        return stages.bindCatchup(committing)
    }

    private suspend fun verifyRetainedArchive(stage: ReplicaSwitchStage) {
        archives.verify(receipt(stage, requireNotNull(stage.archive))) { tokens.replicaDestinationAccess(stage) }
    }

    /** One bounded final attempt. No cached preview/proof is accepted as input. Shared business
     * and material owners prevent ordinary/late publication; actual native file leases close
     * and revalidate before any stage mutation. Only after complete proof do SQL close and DS
     * terminal CAS happen, in that order. A crash between them remains gated and resumes forward.
     */
    suspend fun finishRecovery(expected: ReplicaSwitchStage, material: AccountIconRuntime,
        transport: AccountIconHttp): ReplicaCompletionOutcome = coordinator.coordinateReplicaRecovery {
        if (expected.phase == ReplicaSwitchPhase.COMPLETED)
            return@coordinateReplicaRecovery ReplicaCompletionOutcome.Completed(completed(expected))
        require(material.metadata.usesAccess(tokens, sessions) &&
            inputs.usesDestinationMaterial(material.metadata, material.transfers, material.store)) { "ICON_RECOVERY_STORE_MISMATCH" }
        val current = catchUpOwned(expected)
        material.coordinateReplicaCompletion { round ->
            suspend fun inspectComplete(stage: ReplicaSwitchStage,
                context: com.dayforge.data.appearance.AccountIconContext): ReplicaMaterialProof {
                val uses = destinationUses(stage)
                material.store.restoreReplicaSelection(context)
                return inputs.inspectDestinationIcons(context) { metadata, files ->
                    ReplicaMaterialProof.checked(stage, context, uses, metadata, files)
                }
            }
            val checked = ReplicaMaterialAccess.verified(database, tokens, sessions, archives, current) { context ->
                val outcome = round(context, transport)
                if (outcome != IconMaterialOutcome.COMPLETE) {
                    // Diagnose only under the existing verified lease; no ordinary-access bypass,
                    // credentials, object identifiers, names, URLs or material contents in logs.
                    val jobs = material.transfers.jobs(context).groupingBy {
                        "${it.kind}/${it.state}/${it.failure}"
                    }.eachCount().toSortedMap()
                    val checkpoint = material.catalog.next(context).checkpoint
                    android.util.Log.i("ReplicaMaterials", "Waiting outcome=$outcome jobs=$jobs " +
                        "catalog=${checkpoint.cursor}/${checkpoint.through}")
                    outcome to null
                }
                else outcome to inspectComplete(current, context)
            }
            val proof = checked.second ?: return@coordinateReplicaCompletion ReplicaCompletionOutcome.Waiting(current, checked.first)
            // Server may have changed during catalog/file IO. Never release the older SQL as
            // current; next bounded call catches up using a new durable intent.
            val fresh = ReplicaDestinationReadAccess.verified(database, tokens, sessions, archives, current) { read ->
                candidates.validateDestination(read, read.read(http))
            }
            if (fresh.hasUnfinishedTimer || fresh.contentHash != current.publishedSnapshot.contentHash ||
                fresh.projection.hash != current.publishedSnapshot.target.projectionHash)
                return@coordinateReplicaCompletion ReplicaCompletionOutcome.Changed(current)
            check(proof.access.session.authentication == fresh.original.session.authentication) { "SYNC_REPLICA_RELEASE_PROOF_CHANGED" }
            verifyRetainedArchive(current)
            val releasing = if (current.phase == ReplicaSwitchPhase.RELEASING) current else stages.recordRelease(current, proof)
            commits.closeForRelease(releasing)
            // Reacquire actual closed-state leases, not the previous object whose phase changed.
            // Rebuild all files/metadata and archive after SQL close before removing the pointer.
            val finalProof = ReplicaMaterialAccess.verified(database, tokens, sessions, archives, releasing) { context ->
                inspectComplete(releasing, context)
            }
            check(finalProof.hash == proof.hash && finalProof.access == proof.access) { "SYNC_REPLICA_RELEASE_PROOF_CHANGED" }
            // Actual full SQL and current authentication are checked again by final DS CAS.
            ReplicaCompletionOutcome.Completed(stages.publishCompletion(releasing, finalProof))
        }
    }

    private suspend fun completed(stage: ReplicaSwitchStage): ReplicaSwitchStage = sessions.exclusive {
        check(stage.phase == ReplicaSwitchPhase.COMPLETED && tokens.activeReplicaStage() == null &&
            tokens.replicaStage(stage.recoveryId) == stage) { "SYNC_REPLICA_STAGE_CHANGED" }
        val access = requireNotNull(tokens.localSyncAccess()) { "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED" }
        check(access.session.authentication.userId == stage.accountId && access.session.serverInstanceId == stage.serverInstanceId &&
            access.session.syncEpoch == stage.newEpoch && access.deviceId == stage.publishedSnapshot.target.deviceId &&
            tokens.registeredInstallationId() == stage.installationId) { "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED" }
        val control = ReplicaPresentationRepository(database, tokens, sessions).inspectWhileAccountLocked()
        check(control.problem == null) { "SYNC_REPLICA_RELEASE_STATE_INVALID" }
        // Completed replicas may have ordinary new writes/cursors, and cannot re-enter recovery.
        stage
    }

    /** Fresh authenticated directory/transfer round, exact archived preference and ALL owned
     * metadata/receipts/files + current/retained authority references. Still no activation.
     */
    suspend fun verifyMaterials(expected: ReplicaSwitchStage, material: AccountIconRuntime,
        transport: AccountIconHttp): ReplicaMaterialVerification = coordinator.coordinateReplicaRecovery {
        require(material.metadata.usesAccess(tokens, sessions) &&
            inputs.usesDestinationMaterial(material.metadata, material.transfers, material.store)) { "ICON_RECOVERY_STORE_MISMATCH" }
        ReplicaMaterialAccess.verified(database, tokens, sessions, archives, expected) { context ->
            val (outcome, proof) = material.recoverAndInspect(context, transport) {
                val uses = destinationUses(expected)
                material.store.restoreReplicaSelection(context)
                inputs.inspectDestinationIcons(context) { metadata, files ->
                    ReplicaMaterialProof.checked(expected, context, uses, metadata, files)
                }
            }
            if (proof == null) ReplicaMaterialVerification.Waiting(outcome)
            else ReplicaMaterialVerification.Verified(proof)
        }
    }

    private suspend fun destinationUses(stage: ReplicaSwitchStage): List<ConfigIconUse> = sessions.exclusive {
        database.withTransaction {
            if (stage.phase == ReplicaSwitchPhase.RELEASING) commits.releaseFateInTransaction(stage)
            else check(commits.fateInTransaction(stage) == ReplicaCommitFate.COMMITTED) { "SYNC_REPLICA_COMMIT_STATE_INVALID" }
            val projection = ReplicaProjection.capture(database)
            val uses = mutableListOf<ConfigIconUse>()
            fun add(appearance: JsonElement, once: Boolean) {
                uses += ConfigIconUse(Json.decodeFromJsonElement<ObjectAppearance>(appearance).icon, once)
            }
            for (table in listOf("habits", "metrics")) for (value in projection.tables.getValue(table).jsonArray) {
                val row = value.jsonObject
                val appearance = row.getValue("appearance").jsonObject.getValue("value").jsonPrimitive.content
                val once = table == "habits" && row.getValue("completionPolicy").jsonObject.getValue("value") == JsonPrimitive("one_and_done")
                add(Json.parseToJsonElement(appearance), once)
            }
            NextAuthorityShadowReader(database).readInTransaction { change ->
                if (change.operation == "upsert" && change.entityType in setOf("plan_node", "metric")) {
                    val activity = change.payload["activity"] as? JsonObject
                    add(change.payload.getValue("appearance"), change.entityType == "plan_node" &&
                        activity?.get("completion_policy") == JsonPrimitive("one_and_done"))
                }
            }
            java.util.Collections.unmodifiableList(uses.distinct().sortedBy { Json.encodeToJsonElement(it.reference).toString() + it.oneTime })
        }
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
                if (stage.phase in setOf(ReplicaSwitchPhase.SQL_COMMITTED, ReplicaSwitchPhase.DESTINATION_BOUND,
                    ReplicaSwitchPhase.CATCHUP_BOUND)) stage
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
        if (stage.destinationPending) {
            tokens.replicaDestinationAccess(stage)
            return
        }
        val actual = requireNotNull(tokens.localSyncAccess()) { "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED" }
        val sameOwner = stage.original.copy(session = stage.original.session.copy(authentication = actual.session.authentication))
        check(tokens.activeReplicaStage() == stage && actual.session.authentication.userId == stage.accountId && actual == sameOwner &&
            tokens.isCurrentSyncAuthority(actual) && tokens.registeredInstallationId() == stage.installationId) {
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

    private suspend fun materialPreflight(candidate: ValidatedReplicaCandidate, input: ReplicaArchiveInput): ReplicaMaterialPreflight {
        val selected = input.icons.metadata.selection.pack
        val response = http.recoveryPreview(candidate.original, candidate.newEpoch, candidate.deviceProof.installationId,
            includeMaterials = true, requestedPack = selected?.let { com.dayforge.data.api.dto.RecoveryPackVersion(it.packId, it.revision) })
        val fresh = candidates.validate(candidate.original, candidate.deviceProof.installationId, response)
        require(fresh.contentHash == candidate.contentHash && fresh.projection.hash == candidate.projection.hash) {
            "SYNC_RECOVERY_PREVIEW_CHANGED"
        }
        return ReplicaMaterialPreflight.checked(response, input.icons.metadata)
    }

    private fun requireCandidate(stage: ReplicaSwitchStage, fresh: ValidatedReplicaCandidate) {
        require(fresh.original == stage.original && fresh.newEpoch == stage.newEpoch &&
            fresh.deviceProof.installationId == stage.installationId && fresh.contentHash == stage.candidateHash &&
            fresh.cursor == stage.candidateCursor && !fresh.hasUnfinishedTimer) { "SYNC_RECOVERY_PREVIEW_CHANGED" }
    }

    private suspend fun requireCurrent(stage: ReplicaSwitchStage) {
        check(stages.active() == stage && tokens.isCurrentSyncAuthority(stage.original) &&
            tokens.registeredInstallationId() == stage.installationId) { "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED" }
    }

    private fun receipt(stage: ReplicaSwitchStage, saved: ReplicaStageArchive) = ReplicaArchiveReceipt(
        AccountIconNamespace(stage.accountId, stage.serverInstanceId, stage.oldEpoch), saved.archiveId, saved.inputHash,
        saved.byteLength, saved.contentHash, saved.entries.map { ReplicaArchiveEntry(it.name, it.byteLength, it.contentHash) })
}
