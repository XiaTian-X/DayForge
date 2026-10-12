package com.dayforge.data.repository

import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dayforge.data.local.*
import com.dayforge.domain.service.*
import com.dayforge.sync.AccountIconWorker
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Default material-worker entry helper + actual primary Room/DataStore. No real material HTTP claim. */
@RunWith(AndroidJUnit4::class)
class ReplicaBackgroundBarrierTest : NextCoreRequestFixture() {
    private fun dependencies(): AccountIconWorker.Dependencies = mockk<AccountIconWorker.Dependencies>().also {
        every { it.replica() } returns ReplicaPresentationRepository(db, tokens, sessions)
        every { it.materials() } answers { throw AssertionError("Must not initialize the material runtime") }
    }
    private fun worker(dependencies: AccountIconWorker.Dependencies) =
        AccountIconWorker(app, mockk<WorkerParameters>(relaxed = true)) { AccountIconWorker.synchronize(dependencies) }
    private val attention get() = Result.failure(workDataOf(AccountIconWorker.STATUS to "ATTENTION"))

    @Test fun orphanSqlRejectsMaterialWorkerBeforeInitializingMaterialDependenciesAcrossColdReopen() = runBlocking<Unit> {
        register(); val stage = tokens.replicaStageFixture()
        NextReplicaWriteFence(db).acquire(stage.fence)
        assertFalse(tokens.replicaSwitchPending())
        val first = dependencies(); assertEquals(attention, worker(first).doWork())
        verify(exactly = 0) { first.materials() }
        storage.reopen()
        val cold = dependencies(); assertEquals(attention, worker(cold).doWork())
        verify(exactly = 0) { cold.materials() }
        assertEquals(stage.fence, db.nextReplicaFenceDao().state())
    }

    @Test fun lostPointerOrDamagedJournalWithoutSqlIsAttentionNotSuccessOrNetworkRetry() = runBlocking<Unit> {
        register(); val stage = tokens.replicaStageFixture(); tokens.persistReplicaStage(null, stage)
        dataStore.edit { it.remove(ReplicaSwitchGuard.POINTER) }
        val entry = dependencies(); assertEquals(attention, worker(entry).doWork())
        dataStore.edit { it[ReplicaSwitchStage.key(stage.recoveryId)] = "synthetic-secret/path" }
        assertEquals(attention, worker(entry).doWork())
        verify(exactly = 0) { entry.materials() }; assertNull(db.nextReplicaFenceDao().state())
    }

    @Test fun actualMatchedPreCommitCancellationReopensWorkerWithoutUnfreezingCommittedData() = runBlocking<Unit> {
        register(); val stages = NextReplicaStageStore(db, tokens, sessions)
        val stage = stages.begin(tokens.replicaStageFixture())
        NextReplicaWriteFence(db).acquire(stage.fence)
        val frozen = stages.recordFrozen(stage)
        val entry = dependencies(); assertEquals(attention, worker(entry).doWork())
        stages.cancel(frozen)
        val material = mockk<AccountIconTransferService>()
        every { entry.materials() } returns material
        coEvery { material.synchronize() } returns IconMaterialOutcome.COMPLETE
        assertEquals(Result.success(workDataOf(AccountIconWorker.STATUS to "COMPLETE")), worker(entry).doWork())
        coVerify(exactly = 1) { material.synchronize() }
        assertEquals(frozen.fence.copy(mode = com.dayforge.data.local.entity.NextReplicaFenceEntity.CLOSED),
            db.nextReplicaFenceDao().state())
    }

    @Test fun cancelledWorkerWaitingOnActualAccountLockPropagatesCancellationWithoutInitializingMaterials() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) { sessions.exclusive { entered.complete(Unit); release.await() } }
        entered.await()
        val entry = dependencies(); val owned = async(Dispatchers.IO) { worker(entry).doWork() }
        try {
            yield(); withTimeout(5_000) { owned.cancelAndJoin() }
            assertTrue(owned.isCancelled); verify(exactly = 0) { entry.materials() }
        } finally { owned.cancelAndJoin(); release.complete(Unit); holder.join() }
    }
}
