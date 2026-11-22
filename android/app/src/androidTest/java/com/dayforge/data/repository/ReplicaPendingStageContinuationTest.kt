package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.*
import com.dayforge.domain.service.AccountIconController
import io.mockk.confirmVerified
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Historical INPUT at the exact participant gap left by the old workflow. Root, T0,
 * candidate, raw source and file archive are all genuine. Only the obsolete OWNER sequence
 * is reproduced to persist a stage which the corrected new prepare now refuses. This is
 * never a positive approval/confirmation test or a new production bypass. */
@RunWith(AndroidJUnit4::class)
class ReplicaPendingStageContinuationTest : NextReplicaDestinationFixture() {
    private data class Existing(val archived: ReplicaSwitchStage, val candidate: ValidatedReplicaCandidate,
        val reference: CountCommandReference, val original: CountCommandRowPlan.Aggregate)
    private fun inputs() = NextReplicaArchiveInputReader(app, db, tokens, sessions, preferences, http,
        iconDb, icons, transfers, files, dataStore, themes, builtIns)
    private suspend fun raw() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction() }
    private suspend fun oldOwnerStage(): Existing {
        initialize(); materialReply = { materialResponse(it, emptyList()) }
        val original = access(); val installation = requireNotNull(tokens.registeredInstallationId())
        // This read/registration path does not use image rendering. Its real authority and
        // native command writer run unchanged; the strict unused collaborator must stay unused.
        val unusedImages = mockk<AccountIconController>()
        val editor = NextObjectEditor(db, tokens, sessions, unusedImages)
        val selected = editor.habit(habit.id)
        val attempt = editor.beginCountAdd(requireNotNull(selected.value), requireNotNull(selected.authority),
            Instant.now(), ZoneId.systemDefault(), 1)
        val reference = CountCommandReference(attempt.input.commandId, attempt.input.inputHash,
            attempt.input.namespace.hash, attempt.input.namespace.dataset)
        editor.releaseUnexecuted(attempt); confirmVerified(unusedImages)
        val command = CountCommandStore(db, tokens, sessions).resolve(reference.native()).historical
        assertEquals(CountCommandRecords.Execution.PREPARED, command.status.execution)
        assertTrue(command.status.pending); assertNull(command.outcome)
        assertEquals(0, count("completions")); assertEquals(0, count("sync_outbox"))
        return capturedStage(original, installation, reference, command)
    }
    private suspend fun capturedStage(original: LocalSyncAccess, installation: String,
        reference: CountCommandReference, command: CountCommandRowPlan.Aggregate): Existing {
        val candidate = NextReplicaCandidateValidator(app, tokens, sessions, preferences)
            .validate(original, installation, http.recoveryPreview(original, id(99), installation))
        val source = raw()
        val stage = inputs().inspect(original, source.hash) { input, _ ->
            assertFalse(input.hasReview)
            ReplicaSwitchStage(UUID.randomUUID().toString(), original.session.authentication.userId,
                requireNotNull(original.session.serverInstanceId), requireNotNull(original.session.syncEpoch), candidate.newEpoch,
                original.session.authentication.generation, requireNotNull(original.deviceId),
                requireNotNull(original.capabilityRevision), original.capabilities.sorted(), installation,
                NextReplicaSourceClosure.storage(input.storage, null), NextReplicaSourceClosure.input(input, null),
                candidate.contentHash, candidate.wireHash, candidate.cursor)
        }
        // Exact original participant sequence, intentionally NOT the corrected prepare gate.
        // Models a persisted current20 stage from a prior app version; no root or proof is signed here.
        sessions.exclusive { tokens.persistReplicaStage(null, stage) }
        NextReplicaWriteFence(db).acquire(stage.fence)
        val stages = NextReplicaStageStore(db, tokens, sessions)
        val frozen = stages.recordFrozen(stage)
        val service = NextReplicaArchiveService(inputs(), ReplicaPrivateArchiveStore(directory), tokens)
        val receipt = service.create(original, raw().hash)
        service.verify(original, receipt)
        val archived = stages.recordArchive(frozen, ReplicaStageArchive(receipt.archiveId, receipt.inputHash,
            receipt.byteLength, receipt.contentHash, receipt.entries.map {
                ReplicaStageArchiveEntry(it.name, it.byteLength, it.contentHash)
            }))
        db.withTransaction {
            assertEquals(archived.sourceHash, NextReplicaSourceClosure.storage(raw(), archived.fence))
            assertEquals(1L, CountCommandRecoveryInventory(db, tokens).pendingInTransaction(original))
        }
        return Existing(archived, candidate, reference, command)
    }
    private suspend fun rejectWithoutChange(stage: ReplicaSwitchStage, block: suspend () -> Unit) {
        val before = raw().contents; val prefs = dataStore.data.first().asMap(); val archiveBytes = archive(stage).readBytes()
        val error = try { block(); null } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failure }
        assertEquals("SYNC_RECOVERY_REVIEW_REQUIRED", error?.message)
        assertEquals(stage, tokens.activeReplicaStage()); assertEquals(stage.fence, db.nextReplicaFenceDao().state())
        assertEquals(before, raw().contents)
        assertTrue("Refusal changed complete preferences", prefs == dataStore.data.first().asMap())
        assertArrayEquals(archiveBytes, archive(stage).readBytes())
    }
    private suspend fun cancelAndColdVerify(existing: Existing, stage: ReplicaSwitchStage) {
        val cancelled = workflow().cancel(stage)
        assertEquals(ReplicaSwitchPhase.ABORTED, cancelled.phase); assertNull(tokens.activeReplicaStage())
        coldReopen()
        val fresh = CountCommandStore(db, tokens, sessions).resolve(existing.reference.native()).historical
        assertEquals(existing.original.member(), fresh.member()); assertTrue(fresh.status.pending)
        assertNull(fresh.outcome); assertEquals(0, count("completions")); assertTrue(db.syncOutboxDao().getAll().isEmpty())
        assertTrue(archive(stage).isFile)
    }
    @Test(timeout = 150_000) fun actualCurrent20PendingStageCannotGainFreshConfirmationAfterColdReopen() = recoveryRunBlocking<Unit> {
        val existing = oldOwnerStage(); coldReopen()
        rejectWithoutChange(existing.archived) { workflow().verifyForConfirmation(existing.archived) }
        cancelAndColdVerify(existing, existing.archived)
    }
    @Test(timeout = 150_000) fun actualOldFateRefusesPendingCommandBeforeAnyProjectionResetAndStillCancels() = recoveryRunBlocking<Unit> {
        val existing = oldOwnerStage(); val device = existing.candidate.deviceProof
        val target = ReplicaStageTarget(existing.candidate.projection.hash, ReplicaProjection.schemaHash(db),
            device.deviceId, device.capabilityRevision, device.capabilities.sorted(), device.isPrimaryEditor, device.structuralEditEnabled)
        val committing = NextReplicaStageStore(db, tokens, sessions).recordCommitting(existing.archived, target)
        assertEquals(ReplicaCommitFate.OLD, NextReplicaCommitStore(db, tokens, sessions).fate(committing))
        rejectWithoutChange(committing) { NextReplicaCommitStore(db, tokens, sessions).commit(committing, existing.candidate) }
        assertEquals(ReplicaCommitFate.OLD, NextReplicaCommitStore(db, tokens, sessions).fate(committing))
        cancelAndColdVerify(existing, committing)
    }
}
