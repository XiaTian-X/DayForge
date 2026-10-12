package com.dayforge.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.dayforge.data.local.*
import com.dayforge.domain.service.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** Real Room + disk DataStore. These are read/lifetime barriers, not final recovery activation. */
@RunWith(AndroidJUnit4::class)
class ReplicaPresentationTest : NextCoreRequestFixture() {
    private val controllers = mutableListOf<ReplicaPresentationController>()
    private fun repository(manager: TokenManager = tokens) = ReplicaPresentationRepository(db, manager, sessions)
    private fun controller(manager: TokenManager = tokens) = ReplicaPresentationController(manager, repository(manager)).also { controllers.add(it) }
    private suspend fun open(controller: ReplicaPresentationController): ReplicaDisplayPermit = withTimeout(10_000) {
        (controller.state.first { it is ReplicaPresentationState.Open } as ReplicaPresentationState.Open).permit
    }
    private suspend fun pending(controller: ReplicaPresentationController): ReplicaPresentationState.Pending = withTimeout(10_000) {
        controller.state.first { it is ReplicaPresentationState.Pending } as ReplicaPresentationState.Pending
    }
    @After fun closeControllers() = runBlocking {
        controllers.forEach { it.close() }; controllers.forEach { it.awaitClosed() }
    }

    @Test fun normalColdStartAndUnrelatedCursorAndAuthenticationUpdatesKeepSameLifetime() = runBlocking<Unit> {
        register(); val controller = controller(); val permit = open(controller)
        assertTrue(permit.canRender()); assertNull(repository().inspect().problem)
        tokens.saveSyncCursor(12); tokens.saveTokens("new-access", "new-refresh", "member", id(1), false)
        controller.retry(); delay(100)
        assertSame(permit, open(controller)); assertTrue(permit.canRender())
        controller.close(); controller.awaitClosed(); assertFalse(permit.canRender())
    }

    @Test fun freezingGapSqlFreezeAndVerifiedCancelRequireFreshLifetime() = runBlocking<Unit> {
        register(); val controller = controller(); val old = open(controller)
        val stages = NextReplicaStageStore(db, tokens, sessions)
        val stage = stages.begin(tokens.replicaStageFixture())
        assertFalse(old.canRender())
        assertEquals(ReplicaSwitchPhase.FREEZING, pending(controller).check.phase)
        assertNull(db.nextReplicaFenceDao().state())
        NextReplicaWriteFence(db).acquire(stage.fence)
        val frozen = stages.recordFrozen(stage)
        assertEquals(ReplicaSwitchPhase.FROZEN, withTimeout(10_000) {
            (controller.state.first { it is ReplicaPresentationState.Pending && it.check.phase == ReplicaSwitchPhase.FROZEN }
                as ReplicaPresentationState.Pending).check.phase
        })
        stages.cancel(frozen)
        val fresh = open(controller)
        assertTrue(fresh.canRender()); assertNotSame(old, fresh); assertTrue(fresh.lifetime > old.lifetime)
        assertFalse(old.canRender()); assertNull(repository().inspect().problem)
        assertEquals(3, db.habitDao().getAllHabits().first().size + db.metricDao().getAllMetrics().first().size)
    }

    @Test fun lostPointerDoesNotOpenFrozenSqlOrOrphanFreezingBody() = runBlocking<Unit> {
        register(); val stage = tokens.replicaStageFixture()
        tokens.persistReplicaStage(null, stage)
        dataStore.edit { it.remove(ReplicaSwitchGuard.POINTER) }
        assertEquals(ReplicaPresentationProblem.ORPHAN_STAGE, repository().inspect().problem)
        NextReplicaWriteFence(db).acquire(stage.fence)
        dataStore.edit { it.remove(ReplicaSwitchStage.key(stage.recoveryId)) }
        assertEquals(ReplicaPresentationProblem.ORPHAN_STAGE, repository().inspect().problem)
        val cold = controller()
        assertEquals(ReplicaPresentationProblem.ORPHAN_STAGE, pending(cold).check.problem)
    }

    @Test fun closedSqlNeedsMatchingTerminalCancellationNotJustClosedMode() = runBlocking<Unit> {
        register(); val stage = tokens.replicaStageFixture()
        val fence = NextReplicaWriteFence(db)
        fence.acquire(stage.fence); fence.closeBeforeCommit(stage.fence)
        assertEquals(ReplicaPresentationProblem.ORPHAN_STAGE, repository().inspect().problem)
        dataStore.edit { it[ReplicaSwitchStage.key(stage.recoveryId)] = ReplicaSwitchStage.encode(stage.transition(ReplicaSwitchPhase.ABORTED)) }
        assertNull(repository().inspect().problem)
        dataStore.edit { it.remove(ReplicaSwitchStage.key(stage.recoveryId)) }
        assertEquals(ReplicaPresentationProblem.ORPHAN_STAGE, repository().inspect().problem)
    }

    @Test fun wrongTypedPointerOrMissingOrDamagedBodyNeverLooksOpenAndErrorsAreSanitized() = runBlocking<Unit> {
        register(); val controller = controller(); val old = open(controller)
        dataStore.edit { it[booleanPreferencesKey(ReplicaSwitchGuard.POINTER.name)] = true }
        assertEquals(ReplicaPresentationProblem.DAMAGED_STATE, pending(controller).check.problem)
        assertFalse(old.canRender())
        dataStore.edit { it.remove(booleanPreferencesKey(ReplicaSwitchGuard.POINTER.name)); it[ReplicaSwitchGuard.POINTER] = id(100) }
        controller.retry(); assertEquals(ReplicaPresentationProblem.DAMAGED_STATE, pending(controller).check.problem)
        dataStore.edit { it[ReplicaSwitchStage.key(id(100))] = "synthetic-secret/private/path" }
        controller.retry(); assertEquals(ReplicaPresentationProblem.DAMAGED_STATE, pending(controller).check.problem)
        assertFalse(controller.state.value.toString().contains("synthetic-secret"))
    }

    @Test fun actualSqliteBlobAndMalformedFenceFailClosedWithoutTypedCoercion() = runBlocking<Unit> {
        register(); val stage = tokens.replicaStageFixture()
        NextReplicaWriteFence(db).acquire(stage.fence)
        val sql = db.openHelper.writableDatabase
        sql.execSQL("DROP TRIGGER replica_fence_control_update")
        sql.execSQL("UPDATE next_replica_fence SET sourceHash=X'61' WHERE id=1")
        val cold = controller()
        assertEquals(ReplicaPresentationProblem.DAMAGED_STATE, pending(cold).check.problem)
        assertFalse(cold.state.value is ReplicaPresentationState.Open)
    }

    @Test fun actualPersistenceCancellationRevokesDrawBeforeReturnAndColdReadKeepsFreeze() = runBlocking<Unit> {
        register()
        val wait = AtomicBoolean(false); val committed = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val delayed = object : DataStore<Preferences> {
            override val data = dataStore.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                val actual = dataStore.updateData(transform)
                if (wait.compareAndSet(true, false)) { committed.complete(Unit); release.await() }
                return actual
            }
        }
        val manager = TokenManager(delayed)
        val controller = controller(manager); val old = open(controller)
        val stage = manager.replicaStageFixture(); wait.set(true)
        val owned = async { manager.persistReplicaStage(null, stage) }
        try {
            withTimeout(10_000) { committed.await() }
            assertFalse(old.canRender()); assertFalse(controller.state.value is ReplicaPresentationState.Open)
            owned.cancel(); assertFalse(owned.isCompleted)
        } finally { release.complete(Unit); owned.cancelAndJoin() }
        assertEquals(stage, manager.activeReplicaStage())
        assertEquals(ReplicaSwitchPhase.FREEZING, pending(controller).check.phase)
        val cold = controller(manager); assertEquals(ReplicaSwitchPhase.FREEZING, pending(cold).check.phase)
        NextReplicaStageStore(db, manager, sessions).cancel(stage)
        assertTrue(open(controller).canRender()); assertFalse(old.canRender())
    }

    @Test fun failedDataStoreCommitDoesNotResurrectOldPermitAndLeavesActualOldState() = runBlocking<Unit> {
        register(); val failWrite = AtomicBoolean(false)
        val failing = object : DataStore<Preferences> {
            override val data = dataStore.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = dataStore.updateData { old ->
                val next = transform(old)
                if (failWrite.compareAndSet(true, false)) throw IOException("synthetic write failure")
                next
            }
        }
        val manager = TokenManager(failing); val controller = controller(manager); val old = open(controller)
        val stage = manager.replicaStageFixture(); failWrite.set(true)
        assertTrue(runCatching { manager.persistReplicaStage(null, stage) }.exceptionOrNull() is IOException)
        assertNull(manager.activeReplicaStage()); assertNull(manager.replicaStage(stage.recoveryId))
        val fresh = open(controller); assertTrue(fresh.canRender()); assertNotSame(old, fresh); assertFalse(old.canRender())
    }
}
