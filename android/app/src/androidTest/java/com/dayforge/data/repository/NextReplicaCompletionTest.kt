package com.dayforge.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.appearance.*
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.NextReplicaFenceEntity
import com.dayforge.domain.service.*
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual archive, Room, disk DataStore, native material and TCP. Completion does not enable
 * default v5 or install a production UI entry; no cached proof is finalization input.
 */
@RunWith(AndroidJUnit4::class)
class NextReplicaCompletionTest : NextReplicaDestinationFixture() {
    private fun transport() = AccountIconHttp(client, tokens, peer.origin)
    private fun emptyCatalog() { materialReply = { materialResponse(it, emptyList()) } }
    private suspend fun finished(stage: ReplicaSwitchStage): ReplicaSwitchStage {
        val outcome = workflow().finishRecovery(stage, runtime(), transport())
        assertTrue("Expected completed, got $outcome", outcome is ReplicaCompletionOutcome.Completed)
        return (outcome as ReplicaCompletionOutcome.Completed).stage
    }
    private suspend fun assertComplete(stage: ReplicaSwitchStage) {
        assertEquals(ReplicaSwitchPhase.COMPLETED, stage.phase)
        assertFalse(tokens.replicaSwitchPending()); assertNull(tokens.activeReplicaStage())
        assertEquals(stage, tokens.replicaStage(stage.recoveryId))
        assertEquals(stage.fence.copy(mode = NextReplicaFenceEntity.CLOSED), db.nextReplicaFenceDao().state())
        assertNull(ReplicaPresentationRepository(db, tokens, sessions).inspect().problem)
        assertTrue(tokens.localFactAccess()!!.canAppend)
        assertNotNull(tokens.localCoreWriteAccess()); assertNotNull(tokens.iconAccessChanges.first())
        assertNotNull(WidgetDisplayPublisher(tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions)).capturePublication())
        assertFalse(tokens.canEditStructure.first()) // Actual readonly structure grants stay readonly.
        assertTrue(db.syncOutboxDao().getAll().isEmpty()); assertFalse(db.nextRequestDao().hasAny())
    }
    private fun wrapWrites(effect: suspend (Preferences) -> Unit) {
        val actual = dataStore
        dataStore = object : DataStore<Preferences> by actual {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = actual.updateData { before ->
                transform(before).also { effect(it) }
            }
        }
        tokens = TokenManager(dataStore); preferences = PreferencesManager(dataStore); materialParticipants()
        reconnectHttp() // Every HTTP boundary must share the wrapped actual TokenManager too.
    }

    @Test fun completeActualReplicaIssuesNewDisplayLifetimePreservesArchiveThemeAndDoesNotReenterRecovery() = runBlocking<Unit> {
        initialize(); emptyCatalog()
        val controller = ReplicaPresentationController(tokens, ReplicaPresentationRepository(db, tokens, sessions))
        try {
            val old = withTimeout(10_000) { (controller.state.first { it is ReplicaPresentationState.Open } as ReplicaPresentationState.Open).permit }
            val bound = workflow().bindDestination(committed()); val bytes = archive(bound).readBytes()
            val theme = DeviceThemeStore(dataStore, themes).read()
            assertFalse(old.canRender()); assertFrozen(bound)
            val proof = (workflow().verifyMaterials(bound, runtime(), transport()) as ReplicaMaterialVerification.Verified).proof
            val releasing = NextReplicaStageStore(db, tokens, sessions).recordRelease(bound, proof)
            NextReplicaCommitStore(db, tokens, sessions).closeForRelease(releasing)
            assertEquals("SYNC_REPLICA_RELEASE_PROOF_CHANGED", rejected {
                NextReplicaStageStore(db, tokens, sessions).publishCompletion(releasing, proof)
            }.message) // An actual prior-phase proof is still not a finalization ticket.
            assertTrue(tokens.replicaSwitchPending()); assertFalse(old.canRender())
            val completed = finished(releasing)
            assertEquals(8L, completed.generation); assertEquals(bound.target, completed.target)
            assertEquals(theme, DeviceThemeStore(dataStore, themes).read())
            assertArrayEquals(bytes, archive(completed).readBytes())
            assertEquals(completed.release!!.materialHash, completed.completion!!.materialHash)
            assertEquals(ReplicaSwitchStage.encodeRelease(completed), dataStore.data.first()[ReplicaSwitchStage.releaseKey(completed)])
            assertComplete(completed)
            val durable = dataStore.data.first()
            assertEquals(ReplicaSwitchStage.completionWitness(requireNotNull(durable[ReplicaSwitchStage.key(completed.recoveryId)])),
                durable[ReplicaSwitchStage.completionKey(completed.recoveryId)])
            dataStore.edit { it[ReplicaSwitchStage.key(completed.recoveryId)] =
                ReplicaSwitchStage.encode(completed.copy(candidateHash = "0".repeat(64))) }
            assertEquals("SYNC_REPLICA_COMPLETION_WITNESS_CHANGED", rejected {
                tokens.replicaStage(completed.recoveryId)
            }.message)
            assertNotNull(rejected { ReplicaPresentationRepository(db, tokens, sessions).inspect() })
            assertNull(WidgetDisplayPublisher(tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions)).capturePublication())
            dataStore.updateData { durable } // Restore only this injected fixture fault.
            assertComplete(completed)
            val fresh = withTimeout(10_000) { (controller.state.first { it is ReplicaPresentationState.Open } as ReplicaPresentationState.Open).permit }
            assertFalse(old.canRender()); assertTrue(fresh.canRender()); assertNotEquals(old.lifetime, fresh.lifetime)
            val sql = ReplicaProjection.capture(db).hash; val calls = peer.requests.size
            assertEquals(completed, workflow().resume(completed)); assertEquals(completed, finished(completed))
            assertEquals(calls, peer.requests.size); assertEquals(sql, ReplicaProjection.capture(db).hash)
            assertNotNull(rejected { workflow().cancel(completed) })
            tokens.saveSyncCursor(21) // Normal progress is allowed, not tied forever to bootstrap hash/cursor.
            assertNull(ReplicaPresentationRepository(db, tokens, sessions).inspect().problem)
            coldReopen(); assertEquals(completed, workflow().resume(completed)); assertEquals(21L, tokens.syncCursor.first())
            tokens.clearAuthenticationTokens(); assertNotNull(rejected { workflow().resume(completed) })
            assertTrue(peer.requests.all { it.method == "GET" })
        } finally { controller.close(); controller.awaitClosed() }
    }

    @Test fun failedFinalDsWriteKeepsClosedSqlGatedAndColdFreshOwnerCanOnlyCompleteForward() = runBlocking<Unit> {
        initialize(); emptyCatalog(); val initial = workflow().bindDestination(committed())
        val bytes = archive(initial).readBytes(); val armed = AtomicBoolean(true)
        wrapWrites { after ->
            val stage = after[ReplicaSwitchStage.key(initial.recoveryId)]?.let { ReplicaSwitchStage.decode(it) }
            if (stage?.phase == ReplicaSwitchPhase.COMPLETED && armed.compareAndSet(true, false)) throw IOException("synthetic actual final DS failure")
        }
        assertNotNull(rejected { finished(initial) }); assertFalse(armed.get())
        val releasing = tokens.activeReplicaStage()!!
        assertEquals(ReplicaSwitchPhase.RELEASING, releasing.phase)
        assertEquals(ReplicaReleaseFate.CLOSED, NextReplicaCommitStore(db, tokens, sessions).releaseFate(releasing))
        assertNull(tokens.localCoreWriteAccess()); assertNull(WidgetDisplayPublisher(tokens, sessions,
            ReplicaPresentationRepository(db, tokens, sessions)).capturePublication())
        assertNotNull(rejected { tokens.saveSyncCursor(22) }); assertNotNull(rejected { workflow().cancel(releasing) })
        coldReopen(); assertEquals(releasing, workflow().resume(releasing))
        destinationFailure = 503; assertNotNull(rejected { finished(releasing) }); destinationFailure = null
        assertEquals(releasing, tokens.activeReplicaStage()); assertTrue(tokens.replicaSwitchPending())
        tokens.clearAuthenticationTokens(); assertNotNull(rejected { finished(releasing) })
        tokens.saveLoginSession("synthetic-final-fresh", "synthetic-final-refresh", "member", id(1), false)
        changedDestination("Forward after closed SQL", 21)
        val completed = finished(releasing)
        assertEquals(12L, completed.generation)
        assertNotEquals(releasing.release!!.authenticationGeneration, completed.completion!!.authenticationGeneration)
        assertEquals(ReplicaSwitchStage.encodeRelease(releasing), dataStore.data.first()[ReplicaSwitchStage.releaseKey(releasing)])
        assertEquals("Forward after closed SQL", db.habitDao().getHabitByUuid(habit.uuid)!!.name)
        assertEquals(21L, tokens.syncCursor.first())
        assertArrayEquals(bytes, archive(completed).readBytes()); assertComplete(completed)
        assertTrue(peer.requests.all { it.method == "GET" })
    }

    @Test fun actualFinalDsCancellationJoinsTerminalPersistenceAndColdRepeatDoesNotReapplyOrRequest() = runBlocking<Unit> {
        initialize(); emptyCatalog(); val initial = workflow().bindDestination(committed())
        val armed = AtomicBoolean(true); lateinit var writing: Deferred<ReplicaSwitchStage>
        wrapWrites { after ->
            val stage = after[ReplicaSwitchStage.key(initial.recoveryId)]?.let { ReplicaSwitchStage.decode(it) }
            if (stage?.phase == ReplicaSwitchPhase.COMPLETED && armed.compareAndSet(true, false)) writing.cancel()
        }
        writing = async(start = CoroutineStart.LAZY) { finished(initial) }; writing.start()
        assertTrue(runCatching { writing.await() }.exceptionOrNull() is CancellationException)
        writing.cancelAndJoin(); assertFalse(armed.get())
        val completed = tokens.replicaStage(initial.recoveryId)!!; assertComplete(completed)
        val sql = ReplicaProjection.capture(db).hash; val calls = peer.requests.size
        coldReopen(); assertEquals(completed, finished(completed)); assertEquals(sql, ReplicaProjection.capture(db).hash)
        assertEquals(calls, peer.requests.size); assertComplete(completed)
    }

    @Test fun serverChangeDuringMaterialRoundRemainsFrozenThenCatchesUpAndCompletesLatestProjection() = runBlocking<Unit> {
        initialize(); val initial = workflow().bindDestination(committed()); val bytes = archive(initial).readBytes()
        val change = AtomicBoolean(true)
        materialReply = { input ->
            if (input.path.endsWith("/catalog") && change.compareAndSet(true, false)) changedDestination("Final current", 21)
            materialResponse(input, emptyList())
        }
        assertTrue(workflow().finishRecovery(initial, runtime(), transport()) is ReplicaCompletionOutcome.Changed)
        assertEquals(initial, tokens.activeReplicaStage()); assertFrozen(initial)
        val completed = finished(initial)
        assertEquals(10L, completed.generation); assertEquals(initial.target, completed.target)
        assertEquals("Final current", db.habitDao().getHabitByUuid(habit.uuid)!!.name)
        assertEquals(21L, tokens.syncCursor.first()); assertArrayEquals(bytes, archive(completed).readBytes())
        assertNotNull(dataStore.data.first()[ReplicaSwitchStage.catchupKey(completed)]); assertComplete(completed)
    }

    @Test fun cancellationAfterReleaseIntentLeavesFrozenSqlAndMissingPointerOrBadAuditCannotOpenIt() = runBlocking<Unit> {
        initialize(); emptyCatalog(); val initial = workflow().bindDestination(committed())
        val armed = AtomicBoolean(true); lateinit var writing: Deferred<ReplicaSwitchStage>
        wrapWrites { after ->
            val stage = after[ReplicaSwitchStage.key(initial.recoveryId)]?.let { ReplicaSwitchStage.decode(it) }
            if (stage?.phase == ReplicaSwitchPhase.RELEASING && armed.compareAndSet(true, false)) writing.cancel()
        }
        writing = async(start = CoroutineStart.LAZY) { finished(initial) }; writing.start()
        assertTrue(runCatching { writing.await() }.exceptionOrNull() is CancellationException)
        writing.cancelAndJoin(); assertFalse(armed.get())
        val releasing = tokens.activeReplicaStage()!!
        assertEquals(ReplicaReleaseFate.FROZEN, NextReplicaCommitStore(db, tokens, sessions).releaseFate(releasing))
        coldReopen(); val saved = dataStore.data.first()
        dataStore.edit { it.remove(ReplicaSwitchGuard.POINTER) }
        assertEquals(ReplicaPresentationProblem.ORPHAN_STAGE, ReplicaPresentationRepository(db, tokens, sessions).inspect().problem)
        assertNotNull(rejected { finished(releasing) }); dataStore.updateData { saved }
        dataStore.edit { it[ReplicaSwitchStage.releaseKey(releasing)] = "{}" }
        val calls = peer.requests.size
        assertNotNull(rejected { finished(releasing) }); assertEquals(calls, peer.requests.size)
        assertNotNull(rejected { ReplicaPresentationRepository(db, tokens, sessions).inspect() })
        dataStore.updateData { saved }; assertComplete(finished(releasing))
    }

    @Test fun actualNativeFileDamageAfterReleaseIntentBlocksFinalPublicationUntilFreshColdProof() = runBlocking<Unit> {
        initialize(); val initial = workflow().bindDestination(committed()); materialReply = { materialResponse(it) }
        assertTrue(workflow().finishRecovery(initial, runtime(), transport()) is ReplicaCompletionOutcome.Waiting)
        assertEquals(IconMaterialOutcome.COMPLETE, workflow().recoverMaterials(initial, runtime(), transport()))
        val native = File(directory, "account-icons-v1/${id(1)}/${id(2)}/${id(99)}/$hash")
        assertArrayEquals(svg, native.readBytes())
        val armed = AtomicBoolean(true)
        wrapWrites { after ->
            val stage = after[ReplicaSwitchStage.key(initial.recoveryId)]?.let { ReplicaSwitchStage.decode(it) }
            if (stage?.phase == ReplicaSwitchPhase.RELEASING && armed.compareAndSet(true, false))
                native.writeBytes(svg.copyOf().also { it[0] = '!'.code.toByte() })
        }
        assertNotNull(rejected { finished(initial) }); assertFalse(armed.get())
        val releasing = tokens.activeReplicaStage()!!
        assertEquals(ReplicaSwitchPhase.RELEASING, releasing.phase)
        assertEquals(ReplicaReleaseFate.CLOSED, NextReplicaCommitStore(db, tokens, sessions).releaseFate(releasing))
        assertTrue(tokens.replicaSwitchPending()); assertNull(tokens.localCoreWriteAccess())
        assertNull(WidgetDisplayPublisher(tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions)).capturePublication())
        coldReopen(); assertNotNull(rejected { finished(releasing) }); assertEquals(releasing, tokens.activeReplicaStage())
        native.writeBytes(svg); assertComplete(finished(releasing))
    }
}
