package com.dayforge.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.appearance.*
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.NextReplicaFenceEntity
import com.dayforge.domain.service.*
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** Focused real-fault continuations, not substitutes for the original Completion6 scenarios.
 * Every method includes initialize, all five original preparation steps and actual binding.
 * No durable stage, archive, projection, material-ready state or authority is fabricated.
 */
@RunWith(AndroidJUnit4::class)
class NextReplicaCompletionContinuationTest : NextReplicaDestinationFixture() {
    private val history = ReplicaPreparedHistoryRule(storage)
    @get:Rule(order = -3) val preparedHistory: TestRule = history
    private enum class PreparedKind { RELEASE_CANCELLATION, FILE_DAMAGE, FINAL_DS_FAILURE }
    private data class PreparedHistory(
        val kind: PreparedKind,
        val stage: ReplicaSwitchStage,
        val bytes: ByteArray,
        val requireSameOwner: () -> Unit
    )
    private val preparedInput = AtomicReference<PreparedHistory?>()

    @Before fun prepareExistingHistory() {
        if (!history.selected) return
        val kind = when (history.methodName) {
            "actualReleaseIntentCancellationColdContinuesSameStageToCompleteAndUsable" -> PreparedKind.RELEASE_CANCELLATION
            "actualReleasedFileDamageColdRepairCompletesOnlyAfterFreshMaterialProof" -> PreparedKind.FILE_DAMAGE
            "actualFinalDataStoreFailureColdReauthenticationAndNewServerStateCompleteForward" -> PreparedKind.FINAL_DS_FAILURE
            else -> error("UNDECLARED_REPLICA_PREPARED_HISTORY")
        }
        val input = history.prepare {
            timed(Phase.INITIALIZE) { initialize() }
            if (kind != PreparedKind.FILE_DAMAGE) materialReply = { materialResponse(it, emptyList()) }
            val initial = bound()
            val bytes = archive(initial).readBytes()
            freezeHistory(kind, initial, bytes)
        }
        check(history.actualPreparationOwner?.isCompleted == true)
        check(preparedInput.compareAndSet(null, input))
    }

    private fun freezeHistory(kind: PreparedKind, stage: ReplicaSwitchStage, bytes: ByteArray): PreparedHistory {
        val originalDb = db; val originalTokens = tokens; val originalDataStore = dataStore
        val originalPreferences = preferences; val originalScope = scope; val originalPeer = peer
        val originalClient = client; val originalDirectory = directory; val originalIconDb = iconDb
        // This single-use handoff preserves the actual preparation owner and archive bytes.
        // Later coldReopen keeps its original replacement of DB/preferences/token/client.
        return PreparedHistory(kind, stage, bytes.copyOf()) {
            check(db === originalDb && tokens === originalTokens && dataStore === originalDataStore)
            check(preferences === originalPreferences && scope === originalScope && peer === originalPeer)
            check(client === originalClient && directory === originalDirectory && iconDb === originalIconDb)
        }
    }

    private fun takeHistory(kind: PreparedKind): PreparedHistory = requireNotNull(preparedInput.getAndSet(null)).also {
        check(it.kind == kind)
        it.requireSameOwner()
    }

    @After fun finishPreparedHistory() {
        history.beforeInheritedCleanup()
    }

    private enum class Phase { INITIALIZE, PREPARE_COMMIT, BIND, MATERIAL_FIRST, MATERIAL_SECOND,
        FAULT_FINISH, COLD_REOPEN, RESUME, FINAL_FINISH, VERIFY_COMPLETED }

    private suspend fun <T> timed(phase: Phase, block: suspend () -> T): T {
        val started = android.os.SystemClock.elapsedRealtime()
        android.util.Log.d("ReplicaContinuationPhase", "phase=$phase event=start")
        var returned = false
        try { return block().also { returned = true } }
        finally {
            android.util.Log.d("ReplicaContinuationPhase", "phase=$phase event=end returned=$returned " +
                "elapsed_ms=${android.os.SystemClock.elapsedRealtime() - started}")
        }
    }

    private fun transport() = AccountIconHttp(client, tokens, peer.origin)
    private suspend fun bound(): ReplicaSwitchStage {
        val committed = timed(Phase.PREPARE_COMMIT) { committed() }
        return timed(Phase.BIND) { workflow().bindDestination(committed) }
    }
    private suspend fun finish(stage: ReplicaSwitchStage, phase: Phase): ReplicaSwitchStage = timed(phase) {
        val outcome = workflow().finishRecovery(stage, runtime(), transport())
        assertTrue("Expected actual Completed, got $outcome", outcome is ReplicaCompletionOutcome.Completed)
        (outcome as ReplicaCompletionOutcome.Completed).stage
    }
    private suspend fun resumeAndFinish(stage: ReplicaSwitchStage): ReplicaSwitchStage {
        val resumed = timed(Phase.RESUME) { workflow().resume(stage) }
        assertEquals(stage, resumed)
        return finish(resumed, Phase.FINAL_FINISH)
    }
    private suspend fun coldOwner() = timed(Phase.COLD_REOPEN) {
        val previousDatabase = db
        val previousRuntime = requireNotNull(CountTrustRuntime.forDatabase(db))
        val previousTokens = tokens
        val previousDataStore = dataStore
        coldReopen()
        assertFalse(previousDatabase.isOpen)
        assertNotSame(previousDatabase, db)
        assertNotSame(previousRuntime, CountTrustRuntime.forDatabase(db))
        assertNotSame(previousTokens, tokens)
        assertNotSame(previousDataStore, dataStore)
    }
    private fun wrapWrites(effect: suspend (Preferences) -> Unit) {
        val actual = dataStore
        dataStore = object : DataStore<Preferences> by actual {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                actual.updateData { before -> transform(before).also { effect(it) } }
        }
        tokens = TokenManager(dataStore)
        preferences = PreferencesManager(dataStore, tokens = tokens)
        materialParticipants()
        reconnectHttp()
    }
    private suspend fun assertBlocked(stage: ReplicaSwitchStage, fate: ReplicaReleaseFate) {
        assertEquals(ReplicaSwitchPhase.RELEASING, stage.phase)
        assertEquals(stage, tokens.activeReplicaStage())
        assertTrue(tokens.replicaSwitchPending())
        assertEquals(fate, NextReplicaCommitStore(db, tokens, sessions).releaseFate(stage))
        assertNull(tokens.localCoreWriteAccess())
        assertFalse(requireNotNull(tokens.localFactAccess()).canAppend)
        assertNull(WidgetDisplayPublisher(tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions)).capturePublication())
    }
    private suspend fun assertCompleted(initial: ReplicaSwitchStage, completed: ReplicaSwitchStage,
        archivedBytes: ByteArray) = timed(Phase.VERIFY_COMPLETED) {
        assertEquals(initial.recoveryId, completed.recoveryId)
        assertEquals(initial.accountId, completed.accountId)
        assertEquals(initial.serverInstanceId, completed.serverInstanceId)
        assertEquals(initial.oldEpoch, completed.oldEpoch)
        assertEquals(initial.newEpoch, completed.newEpoch)
        assertEquals(initial.archive, completed.archive)
        assertEquals(ReplicaSwitchPhase.COMPLETED, completed.phase)
        assertFalse(tokens.replicaSwitchPending())
        assertNull(tokens.activeReplicaStage())
        assertEquals(completed, tokens.replicaStage(completed.recoveryId))
        assertEquals(completed.fence.copy(mode = NextReplicaFenceEntity.CLOSED), db.nextReplicaFenceDao().state())
        assertEquals(completed.publishedSnapshot.target.projectionHash, ReplicaProjection.capture(db).hash)
        assertFalse(db.inTransaction())
        assertFalse(requireNotNull(CountTrustRuntime.forDatabase(db)).hasUnknownGraphBoundary())
        assertArrayEquals(archivedBytes, archive(completed).readBytes())
        val durable = dataStore.data.first()
        assertEquals(ReplicaSwitchStage.completionWitness(requireNotNull(durable[ReplicaSwitchStage.key(completed.recoveryId)])),
            durable[ReplicaSwitchStage.completionKey(completed.recoveryId)])
        assertEquals(requireNotNull(completed.release).materialHash, requireNotNull(completed.completion).materialHash)
        assertNull(ReplicaPresentationRepository(db, tokens, sessions).inspect().problem)
        assertTrue(requireNotNull(tokens.localFactAccess()).canAppend)
        assertNotNull(tokens.localCoreWriteAccess())
        assertNotNull(tokens.iconAccessChanges.first())
        assertNotNull(WidgetDisplayPublisher(tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions)).capturePublication())
        assertFalse(tokens.canEditStructure.first())
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        assertFalse(db.nextRequestDao().hasAny())
        assertTrue(peer.requests.all { it.method == "GET" })
    }

    @RetainedPreparedJourney
    @Test(timeout = 150_000)
    fun actualReleaseIntentCancellationColdContinuesSameStageToCompleteAndUsable() = history.business {
        recoveryRunBlocking<Unit> {
            val input = takeHistory(PreparedKind.RELEASE_CANCELLATION); val initial = input.stage; val bytes = input.bytes
            val armed = AtomicBoolean(true)
            lateinit var writing: Deferred<ReplicaSwitchStage>
            wrapWrites { after ->
                val stage = after[ReplicaSwitchStage.key(initial.recoveryId)]?.let { ReplicaSwitchStage.decode(it) }
                if (stage?.phase == ReplicaSwitchPhase.RELEASING && armed.compareAndSet(true, false)) writing.cancel()
            }
            writing = async(start = CoroutineStart.LAZY) { finish(initial, Phase.FAULT_FINISH) }
            writing.start()
            try {
                try { writing.await(); fail("The actual release-intent write must cancel delivery") }
                catch (_: CancellationException) { assertTrue(writing.isCancelled) }
            } finally {
                withContext(NonCancellable) { withTimeout(5_000) { writing.cancelAndJoin() } }
            }
            assertFalse("The real RELEASING DataStore transform was reached", armed.get())
            val releasing = requireNotNull(tokens.activeReplicaStage())
            assertBlocked(releasing, ReplicaReleaseFate.FROZEN)
            coldOwner()
            assertEquals(releasing, tokens.activeReplicaStage())
            val completed = resumeAndFinish(releasing)
            assertEquals(8L, completed.generation)
            assertEquals(initial.target, completed.target)
            assertCompleted(initial, completed, bytes)
        }
    }

    @RetainedPreparedJourney
    @Test(timeout = 150_000)
    fun actualReleasedFileDamageColdRepairCompletesOnlyAfterFreshMaterialProof() = history.business {
        recoveryRunBlocking<Unit> {
            val input = takeHistory(PreparedKind.FILE_DAMAGE); val initial = input.stage; val bytes = input.bytes
            materialReply = { materialResponse(it) }
            // This existing public workflow accepts a bound destination directly. Its first real
            // round queues the download; the second performs it. No ready metadata is injected.
            assertEquals(IconMaterialOutcome.RETRY, timed(Phase.MATERIAL_FIRST) {
                workflow().recoverMaterials(initial, runtime(), transport())
            })
            val pending = destinationJobs().single()
            assertEquals("download", pending.kind)
            assertEquals("pending", pending.state)
            assertEquals(IconMaterialOutcome.COMPLETE, timed(Phase.MATERIAL_SECOND) {
                workflow().recoverMaterials(initial, runtime(), transport())
            })
            val downloaded = destinationJobs().single()
            assertEquals(pending.operationId, downloaded.operationId)
            assertEquals("complete", downloaded.state)
            val native = File(directory, "account-icons-v1/${id(1)}/${id(2)}/${id(99)}/$hash")
            assertArrayEquals(svg, native.readBytes())
            val corrupted = svg.copyOf().also { it[0] = '!'.code.toByte() }
            val armed = AtomicBoolean(true)
            wrapWrites { after ->
                val stage = after[ReplicaSwitchStage.key(initial.recoveryId)]?.let { ReplicaSwitchStage.decode(it) }
                if (stage?.phase == ReplicaSwitchPhase.RELEASING && armed.compareAndSet(true, false)) native.writeBytes(corrupted)
            }
            val failure = rejected { finish(initial, Phase.FAULT_FINISH) }
            assertTrue("The actual native file hash check must reject the damaged bytes",
                generateSequence(failure as Throwable?) { it.cause }.any { it is ImageInputException && it.code == "IMAGE_HASH" })
            assertFalse("The real RELEASING write injected native file damage", armed.get())
            assertArrayEquals(corrupted, native.readBytes())
            val releasing = requireNotNull(tokens.activeReplicaStage())
            assertBlocked(releasing, ReplicaReleaseFate.CLOSED)
            coldOwner()
            assertEquals(releasing, tokens.activeReplicaStage())
            assertArrayEquals(corrupted, native.readBytes())
            assertNull(tokens.localCoreWriteAccess())
            // Restore only the known bytes damaged by this case. The production continuation
            // must still acquire and verify actual files; this is not a forged proof or ready flag.
            native.writeBytes(svg)
            assertArrayEquals(svg, native.readBytes())
            val completed = resumeAndFinish(releasing)
            assertEquals(8L, completed.generation)
            assertEquals(initial.target, completed.target)
            assertArrayEquals(svg, native.readBytes())
            assertEquals(1, peer.requests.count { it.path.endsWith("/content/light") })
            assertCompleted(initial, completed, bytes)
        }
    }

    @RetainedPreparedJourney
    @Test(timeout = 150_000)
    fun actualFinalDataStoreFailureColdReauthenticationAndNewServerStateCompleteForward() = history.business {
        recoveryRunBlocking<Unit> {
            val input = takeHistory(PreparedKind.FINAL_DS_FAILURE); val initial = input.stage; val bytes = input.bytes
            val armed = AtomicBoolean(true)
            val injected = IOException("synthetic actual focused final DS failure")
            wrapWrites { after ->
                val stage = after[ReplicaSwitchStage.key(initial.recoveryId)]?.let { ReplicaSwitchStage.decode(it) }
                if (stage?.phase == ReplicaSwitchPhase.COMPLETED && armed.compareAndSet(true, false)) throw injected
            }
            val failure = rejected { finish(initial, Phase.FAULT_FINISH) }
            assertFalse("The actual final DataStore transform attempted COMPLETED", armed.get())
            assertTrue("Preserve the actual final write failure", generateSequence(failure as Throwable?) { it.cause }.any { it === injected })
            val releasing = requireNotNull(tokens.activeReplicaStage())
            assertBlocked(releasing, ReplicaReleaseFate.CLOSED)
            coldOwner()
            assertEquals(releasing, tokens.activeReplicaStage())
            tokens.saveLoginSession("synthetic-final-fresh", "synthetic-final-refresh", "member", id(1), false)
            changedDestination("Forward after closed SQL", 21)
            val completed = resumeAndFinish(releasing)
            assertEquals(12L, completed.generation)
            assertNotEquals(requireNotNull(releasing.release).authenticationGeneration,
                requireNotNull(completed.completion).authenticationGeneration)
            assertEquals(ReplicaSwitchStage.encodeRelease(releasing), dataStore.data.first()[ReplicaSwitchStage.releaseKey(releasing)])
            assertEquals("Forward after closed SQL", db.habitDao().getHabitByUuid(habit.uuid)!!.name)
            assertEquals(21L, tokens.syncCursor.first())
            assertCompleted(initial, completed, bytes)
        }
    }
}
