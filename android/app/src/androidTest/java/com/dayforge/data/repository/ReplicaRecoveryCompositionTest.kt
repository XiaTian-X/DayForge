package com.dayforge.data.repository

import android.content.ContextWrapper
import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.*
import com.dayforge.data.appearance.AccountIconHttp
import com.dayforge.data.local.ReplicaSwitchPhase
import com.dayforge.data.local.ReplicaSwitchGuard
import com.dayforge.data.local.replicaStageFixture
import com.dayforge.data.local.entity.NextReplicaFenceEntity
import com.dayforge.domain.service.AccountIconController
import io.mockk.coEvery
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual production composition with sole shared runtime/locks, real phone Room/archive/files
 * and TCP; isolated fixture root, not an online FastAPI restore or production APK/UI acceptance.
 */
@RunWith(AndroidJUnit4::class)
class ReplicaRecoveryCompositionTest : NextReplicaDestinationFixture() {
    private val initialized = AtomicInteger()
    private fun repository(): ReplicaRecoveryRepository {
        val context = object : ContextWrapper(app) { override fun getFilesDir() = directory }
        val networks = mockk<NetworkMonitor>(); coEvery { networks.localNetworks() } returns emptyList()
        val coordinator = BusinessSyncRepository(mockk(), mockk(), db, tokens, sessions, http,
            NextSyncRuntime(db, tokens, sessions, http, preferences))
        val controller = AccountIconController({ initialized.incrementAndGet(); runtime() }, tokens)
        val connection = ReplicaRecoveryConnection(EndpointResolver(networks, preferences, tokens, Json, SelectedNetworkTransport()),
            tokens, sessions, client, http, AccountIconHttp(client, tokens, peer.origin))
        return ReplicaRecoveryRepository(context, db, tokens, sessions, preferences, dataStore, coordinator,
            NextReplicaCandidateValidator(context, tokens, sessions, preferences), controller, connection)
    }
    private fun emptyCatalog() { materialReply = { materialResponse(it, emptyList()) } }

    @Test fun explicitPreviewArchiveConfirmationAndCompletionUseOnlyCanonicalProductionParticipants() = runBlocking<Unit> {
        initialize(); emptyCatalog(); val repository = repository()
        val oldTheme = com.dayforge.data.appearance.DeviceThemeStore(dataStore, themes).read()
        val preview = repository.preview()
        assertEquals(0, initialized.get()); assertNull(repository.pending()); assertFalse(preview.activationAuthorized)
        val stage = repository.prepare(preview); val retained = archive(stage).readBytes()
        assertEquals(ReplicaSwitchPhase.ARCHIVE_VERIFIED, stage.phase); assertEquals(stage, repository.pending())
        assertTrue(tokens.replicaSwitchPending()); assertEquals(1, initialized.get())
        val completed = repository.confirm(repository.confirmation(stage)) as ReplicaCompletionOutcome.Completed
        assertEquals(ReplicaSwitchPhase.COMPLETED, completed.stage.phase); assertEquals(1, initialized.get())
        assertNull(repository.pending()); assertFalse(tokens.replicaSwitchPending()); assertEquals(id(99), tokens.syncEpoch.first())
        assertArrayEquals(retained, archive(completed.stage).readBytes())
        assertEquals(oldTheme, com.dayforge.data.appearance.DeviceThemeStore(dataStore, themes).read())
        assertEquals(completed.stage.fence.copy(mode = NextReplicaFenceEntity.CLOSED), db.nextReplicaFenceDao().state())
        assertNull(ReplicaPresentationRepository(db, tokens, sessions).inspect().problem)
        assertEquals(setOf(habit.uuid, timerHabit.uuid), db.habitDao().getAllHabitsOnce().map { it.uuid }.toSet())
        assertEquals(metric.uuid, db.metricDao().getAllMetricsOnce().single().uuid)
        assertFalse(db.nextRequestDao().hasAny()); assertTrue(db.syncOutboxDao().getAll().isEmpty())
        val calls = peer.requests.size
        assertTrue((repository.resume(completed.stage) as ReplicaRecoveryContinuation.Destination).outcome is ReplicaCompletionOutcome.Completed)
        assertEquals(calls, peer.requests.size); assertEquals(1, initialized.get())
        assertTrue(peer.requests.all { it.method == "GET" })
    }

    @Test fun coldPrecommitResumeNeedsNewConfirmationAndExplicitOfflineCancelKeepsOriginalBusinessAndArchive() = runBlocking<Unit> {
        initialize(); emptyCatalog(); val first = repository()
        val stage = first.prepare(first.preview()); val retained = archive(stage).readBytes()
        coldReopen(); val cold = repository()
        val continuation = cold.resume(requireNotNull(cold.pending()))
        assertTrue(continuation is ReplicaRecoveryContinuation.Confirmation)
        assertEquals(stage, tokens.activeReplicaStage())
        db.withTransaction {
            assertEquals(stage.fence, db.nextReplicaFenceDao().state())
            assertEquals(stage.sourceHash, NextReplicaSourceClosure.storage(NextReplicaStorageProof(db).readInTransaction(), stage.fence))
            assertEquals(stage.oldEpoch, db.nextSyncStateDao().rows().single().syncEpoch)
        } // ARCHIVE_VERIFIED is not a valid input to the post-intent COMMIT fate API.
        assertEquals(id(3), tokens.syncEpoch.first()); assertTrue(tokens.replicaSwitchPending())
        tokens.saveReplicaLogin(tokens.replicaLoginWitness(), "fresh-synthetic-access", "fresh-synthetic-refresh", "member", id(1), false)
        assertNotNull(rejected { cold.confirm((continuation as ReplicaRecoveryContinuation.Confirmation).evidence) })
        val calls = peer.requests.size
        val cancelled = cold.cancel(stage)
        assertEquals(ReplicaSwitchPhase.ABORTED, cancelled.phase); assertFalse(tokens.replicaSwitchPending())
        assertEquals(calls, peer.requests.size); assertArrayEquals(retained, archive(stage).readBytes())
        assertEquals(id(3), tokens.syncEpoch.first()); assertEquals(habit.name, db.habitDao().getAllHabitsOnce().single { it.uuid == habit.uuid }.name)
        assertNull(ReplicaPresentationRepository(db, tokens, sessions).inspect().problem)
    }

    @Test fun coldCommittedResumeFinishesForwardWithFreshMaterialAndNeverReconfirmsOrReplaysOldWork() = runBlocking<Unit> {
        initialize(); emptyCatalog(); val stage = committed()
        assertEquals(ReplicaSwitchPhase.SQL_COMMITTED, stage.phase)
        val retained = archive(stage).readBytes()
        tokens.saveReplicaLogin(tokens.replicaLoginWitness(), "fresh-synthetic-access", "fresh-synthetic-refresh", "member", id(1), false)
        coldReopen(); val repository = repository()
        val outcome = (repository.resume(requireNotNull(repository.pending())) as ReplicaRecoveryContinuation.Destination).outcome
        assertTrue(outcome is ReplicaCompletionOutcome.Completed)
        val completed = (outcome as ReplicaCompletionOutcome.Completed).stage
        assertEquals(id(99), tokens.syncEpoch.first()); assertNull(repository.pending()); assertEquals(1, initialized.get())
        assertArrayEquals(retained, archive(completed).readBytes()); assertTrue(peer.requests.all { it.method == "GET" })
        assertNull(ReplicaPresentationRepository(db, tokens, sessions).inspect().problem)
    }

    @Test fun orphanDurableHistoryCannotStartAnotherPreviewOrArchiveAndDoesNotInitializeRuntime() = runBlocking<Unit> {
        initialize(); emptyCatalog(); val repository = repository(); val preview = repository.preview()
        val stage = tokens.replicaStageFixture().copy(newEpoch = id(99)); tokens.persistReplicaStage(null, stage)
        dataStore.edit { it.remove(ReplicaSwitchGuard.POINTER) } // Inject only this isolated lost-pointer fault.
        val calls = peer.requests.size; val before = dataStore.data.first()
        val projection = ReplicaProjection.capture(db).hash
        assertNotNull(rejected { repository.preview() }); assertNotNull(rejected { repository.prepare(preview) })
        assertEquals(calls, peer.requests.size); assertEquals(0, initialized.get()); assertEquals(before, dataStore.data.first())
        assertEquals(projection, ReplicaProjection.capture(db).hash)
        assertEquals(ReplicaPresentationProblem.ORPHAN_STAGE, ReplicaPresentationRepository(db, tokens, sessions).inspect().problem)
    }

    @Test fun recoveryServiceCannotExposeAnotherOwnersArchiveReceiptFromMismatchedPublicLoginMetadata() = runBlocking<Unit> {
        initialize(); emptyCatalog(); val stage = committed()
        dataStore.edit { it[androidx.datastore.preferences.core.stringPreferencesKey("user_public_id")] = id(88) }
        val before = dataStore.data.first(); val calls = peer.requests.size
        val service = com.dayforge.domain.service.ReplicaRecoveryService(repository(), tokens, sessions)
        assertNotNull(rejected { service.control() })
        assertEquals(before, dataStore.data.first()); assertEquals(calls, peer.requests.size)
        assertEquals(0, initialized.get()); assertEquals(stage, tokens.activeReplicaStage())
    }
}
