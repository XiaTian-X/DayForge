package com.dayforge.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual disk DataStore, production Room SQL fence, shared account mutex and publication.
 * This tests participants, not full archive/confirmed COMMIT or a production activation entry.
 */
@RunWith(AndroidJUnit4::class)
class NextReplicaStageStoreTest : NextCoreRequestFixture() {
    private fun stages() = NextReplicaStageStore(db, tokens, sessions)
    private suspend fun hash() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().hash }
    private suspend fun begin(): ReplicaSwitchStage { register(); return stages().begin(tokens.replicaStageFixture(hash())) }
    private suspend fun reject(block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertNotNull(error); assertFalse(error is CancellationException)
    }
    private suspend fun freeze(stage: ReplicaSwitchStage): ReplicaSwitchStage {
        NextReplicaWriteFence(db).acquire(stage.fence)
        return stages().recordFrozen(stage)
    }

    @Test fun pointerAndCompleteBindingCommitTogetherWithoutSourceOrCredentialChanges() = runBlocking<Unit> {
        register(); val input = tokens.replicaStageFixture(hash()); val before = hash(); val auth = tokens.authenticationSnapshot()!!
        val original = tokens.localCoreWriteAccess()!!; val saved = stages().begin(input)
        assertEquals(input, saved); assertEquals(saved, tokens.replicaStage(saved.recoveryId)); assertEquals(before, hash())
        assertNull(db.nextReplicaFenceDao().state()); assertEquals(auth.session, tokens.authenticationSnapshot()!!.session)
        assertEquals(auth.accessToken, tokens.accessToken.first()); assertEquals(input.original, tokens.localSyncAccess())
        assertNull(tokens.localCoreWriteAccess()); assertEquals(original, tokens.replicaObservationCoreAccess())
        assertFalse(tokens.localFactAccess()!!.canAppend); assertFalse(tokens.canEditStructure.first())
        assertNull(tokens.iconAccessChanges.first()); assertNotNull(tokens.localIconAccess())
        assertNotNull(tokens.syncAuthenticationSnapshot(input.original))
        val raw = dataStore.data.first()[ReplicaSwitchStage.key(saved.recoveryId)]!!
        assertFalse(raw.contains("synthetic-first")); assertFalse(raw.contains("synthetic-refresh"))
        reject { (saved.capabilities as MutableList<String>).add("invented") }
        assertEquals(saved, stages().begin(saved)); assertEquals(before, hash())
    }

    @Test fun frozenStageRequiresActualMatchingSqlAndNormalMetadataCannotReleaseIt() = runBlocking<Unit> {
        val stage = begin(); reject { stages().recordFrozen(stage) }; val frozen = freeze(stage)
        assertEquals(frozen, stages().recordFrozen(stage)); val data = dataStore.data.first(); val before = hash()
        for (write in listOf<suspend () -> Unit>(
            { tokens.saveSyncCursor(99) }, { tokens.saveSyncDeviceId(id(90)) }, { tokens.prepareSyncAccount(id(91)) },
            { tokens.saveServerIdentity(id(2), id(92)) }, { tokens.resetReplicaForEpoch(id(2), id(92)) },
            { tokens.saveDeviceRegistration(id(4), caps, true, 2) }, { tokens.markStructuralEditingDenied() },
            { tokens.requireSyncBootstrap() }, { tokens.clearSyncState() }, { tokens.clearTokens() },
            { preferences.setServerUrl("http://example.invalid/") }, { preferences.setLastSyncTimestamp(999) },
            { preferences.addPendingMetricHabit(habit.id) }, { preferences.clearAccountScopedPreferences() })) reject(write)
        assertEquals(data, dataStore.data.first()); assertEquals(before, hash()); assertEquals(frozen, stages().active())
        val ended = stages().cancel(frozen)
        assertEquals(ReplicaSwitchPhase.ABORTED, ended.phase); assertEquals(ended, tokens.replicaStage(ended.recoveryId))
        assertFalse(tokens.replicaSwitchPending()); assertNotNull(tokens.localCoreWriteAccess())
        tokens.saveSyncCursor(99); preferences.setLastSyncTimestamp(999)
        assertEquals(99L, tokens.syncCursor.first()); assertEquals(999L, preferences.lastSyncTimestamp.first())
        assertEquals(ended, stages().cancel(frozen)); reject { stages().begin(stage) }
    }

    @Test fun coldReopenAtEachPersistedPreCommitGapKeepsOrdinaryWorkBlockedAndCanCancel() = runBlocking<Unit> {
        for (phase in 0..2) {
            val stage = begin()
            if (phase > 0) NextReplicaWriteFence(db).acquire(stage.fence)
            val expected = if (phase == 2) stages().recordFrozen(stage) else stage
            val before = hash()
            scope.coroutineContext[Job]!!.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(directory, "auth.preferences_pb") })
            tokens = TokenManager(dataStore); preferences = PreferencesManager(dataStore); storage.reopen()
            assertEquals(expected, stages().active()); assertTrue(tokens.replicaSwitchPending()); assertNull(tokens.localCoreWriteAccess())
            assertEquals(before, hash()); reject { preferences.setLastSyncTimestamp(999) }
            stages().cancel(expected); assertFalse(tokens.replicaSwitchPending()); assertNotNull(tokens.localCoreWriteAccess())
            assertEquals(habit.name, db.habitDao().getHabitById(habit.id)!!.name)
        }
    }

    @Test fun closingSqlBeforeJournalDeliveryRemainsBlockedAndFinishesExactCancelForward() = runBlocking<Unit> {
        val frozen = freeze(begin()); val auth = tokens.authenticationSnapshot()!!.session
        NextReplicaWriteFence(db).closeBeforeCommit(frozen.fence)
        assertNull(tokens.localCoreWriteAccess()); assertEquals(frozen, stages().active())
        reject { tokens.saveSyncCursor(888) }
        val ended = stages().cancel(frozen)
        assertEquals(ended, stages().cancel(frozen)); assertEquals(auth, tokens.authenticationSnapshot()!!.session)
        assertEquals(habit.name, db.habitDao().getHabitById(habit.id)!!.name)
    }

    @Test fun concurrentBeginAndStaleBindingsNeverReplaceOrReleaseTheWinningStage() = runBlocking<Unit> {
        register(); val first = tokens.replicaStageFixture(hash()); val second = tokens.replicaStageFixture(hash())
        val outcomes = listOf(first, second).map { stage -> async(Dispatchers.IO) { runCatching { stages().begin(stage) } } }.awaitAll()
        assertEquals(1, outcomes.count { it.isSuccess }); val winner = stages().active()!!; val frozen = freeze(winner)
        val before = hash(); val data = dataStore.data.first()
        for (wrong in listOf(frozen.copy(recoveryId = id(70)), frozen.copy(accountId = id(71)),
            frozen.copy(authenticationGeneration = id(72)), frozen.copy(oldEpoch = id(73)), frozen.copy(newEpoch = id(74)),
            frozen.copy(deviceId = id(75)), frozen.copy(capabilityRevision = 2), frozen.copy(sourceHash = "e".repeat(64)),
            frozen.copy(inputHash = "f".repeat(64)), frozen.copy(candidateCursor = 21), frozen.copy(installationId = id(76))))
            reject { stages().cancel(wrong) }
        assertEquals(data, dataStore.data.first()); assertEquals(before, hash()); assertEquals(frozen, stages().active())
    }

    @Test fun changedAuthenticationInvalidatesContinuationButSameOwnerCanCancelWithoutRestoringOldLogin() = runBlocking<Unit> {
        val stage = begin(); NextReplicaWriteFence(db).acquire(stage.fence)
        val originalAuth = stage.authenticationGeneration; val owner = stage.accountId
        val persisted = dataStore.data.first()
        reject { tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(99), false) }
        reject { tokens.saveTokens("synthetic-other", "synthetic-refresh", "other", id(99), false) }
        assertEquals(persisted, dataStore.data.first())
        tokens.clearAuthenticationTokens(); assertTrue(tokens.replicaSwitchPending()); assertNull(tokens.localSyncAccess())
        reject { stages().cancel(stage) }
        tokens.saveLoginSession("synthetic-relogin", "synthetic-refresh", "member", owner, false)
        assertNotEquals(originalAuth, tokens.authenticationSnapshot()!!.session.generation)
        reject { stages().recordFrozen(stage) }; assertEquals(stage, stages().active())
        stages().cancel(stage); assertEquals("synthetic-relogin", tokens.accessToken.first())
        assertEquals(stage.oldEpoch, tokens.syncEpoch.first()); assertEquals(stage.deviceId, tokens.syncDeviceId.first())
    }

    @Test fun completeAuthorityAndInstallationAreRequiredBeforeAnyPointerPersists() = runBlocking<Unit> {
        register(); val good = tokens.replicaStageFixture(hash()); val data = dataStore.data.first()
        for (wrong in listOf(good.copy(accountId = id(80)), good.copy(authenticationGeneration = id(81)),
            good.copy(serverInstanceId = id(82)), good.copy(oldEpoch = id(83)), good.copy(deviceId = id(84)),
            good.copy(capabilityRevision = 2), good.copy(capabilities = listOf("sync.read")), good.copy(installationId = id(85))))
            reject { stages().begin(wrong) }
        assertEquals(data, dataStore.data.first()); assertNull(stages().active()); assertNull(db.nextReplicaFenceDao().state())
    }

    @Test fun actualSqlRollbackDoesNotManufactureFrozenJournalOrDiscardNormalSourceData() = runBlocking<Unit> {
        val stage = begin(); val before = hash()
        reject { db.withTransaction { NextReplicaWriteFence(db).acquire(stage.fence); error("simulated actual SQL rollback") } }
        assertEquals(before, hash()); assertNull(db.nextReplicaFenceDao().state()); assertEquals(stage, stages().active())
        reject { stages().recordFrozen(stage) }; stages().cancel(stage)
        assertEquals(before, hash()); assertFalse(tokens.replicaSwitchPending())
    }

    @Test fun damagedMissingOrUnknownJournalNeverLiftsPresenceBasedGates() = runBlocking<Unit> {
        register(); val good = tokens.replicaStageFixture(hash()); val raw = ReplicaSwitchStage.encode(good)
        for (damaged in listOf<String?>(null, raw.replace("\"FREEZING\"", "\"COMMITTED\""),
            raw.replace("\"formatVersion\":1", "\"formatVersion\":2"),
            raw.replace("\"candidateCursor\":20", "\"candidateCursor\":\"20\""),
            raw.dropLast(1) + ",\"recoveryId\":\"${good.recoveryId}\"}", "x".repeat(ReplicaSwitchStage.ROW_LIMIT + 1))) {
            dataStore.edit {
                it[ReplicaSwitchGuard.POINTER] = good.recoveryId
                if (damaged == null) it.remove(ReplicaSwitchStage.key(good.recoveryId)) else it[ReplicaSwitchStage.key(good.recoveryId)] = damaged
            }
            reject { stages().active() }; assertTrue(tokens.replicaSwitchPending()); assertNull(tokens.localCoreWriteAccess())
            reject { preferences.clearAccountScopedPreferences() }; reject { tokens.clearTokens() }
            assertNull(WidgetDisplayPublisher(tokens, sessions).capturePublication())
        }
        dataStore.edit { it.remove(ReplicaSwitchGuard.POINTER); it[booleanPreferencesKey(ReplicaSwitchGuard.POINTER.name)] = false }
        assertTrue(tokens.replicaSwitchPending()); assertNull(tokens.localCoreWriteAccess()); reject { stages().active() }
    }

    @Test fun preCapturedOrdinaryWriterAndWidgetPublicationCannotSurviveFreezeAbortAba() = runBlocking<Unit> {
        register(); val writer = tokens.localCoreWriteAccess()!!; val publisher = WidgetDisplayPublisher(tokens, sessions)
        val old = publisher.capturePublication()!!; val before = hash(); val stage = stages().begin(tokens.replicaStageFixture(before))
        reject { producer().write(writer.session) { db.habitDao().update(habit.copy(name = "late")) } }
        assertEquals(before, hash()); assertNull(publisher.capturePublication())
        assertFalse(old { fail("Frozen publication must not execute") })
        stages().cancel(stage); assertFalse(old { fail("ABA publication must not execute") })
        assertTrue(publisher.capturePublication()!! {}); assertEquals(before, hash())
    }

    @Test fun cancellationAfterTransformJoinsActualJournalPersistenceBeforeReturningAndLeavesRecoverableStage() = runBlocking<Unit> {
        register(); val input = tokens.replicaStageFixture(hash()); val before = hash(); val armed = AtomicBoolean(true)
        val writing = async(start = CoroutineStart.LAZY) { stages().begin(input) }
        val cache = object : AccountIconMemory.Cache {
            override fun authenticationTransition(blocked: Boolean) {
                // Actual DataStore transform has finished; cancel its caller, not a fake write.
                if (blocked && armed.compareAndSet(true, false)) writing.cancel()
            }
        }
        tokens.registerIconCache(cache)
        try {
            writing.start(); assertTrue(runCatching { writing.await() }.exceptionOrNull() is CancellationException)
            assertTrue(writing.isCompleted); assertFalse(armed.get()); assertEquals(input, stages().active())
            assertEquals(before, hash()); assertTrue(tokens.replicaSwitchPending()); assertNull(tokens.localCoreWriteAccess())
            stages().cancel(input); assertFalse(tokens.replicaSwitchPending()); assertEquals(before, hash())
        } finally { writing.cancelAndJoin() }
    }

    @Test fun impossiblePhaseCountersAndHistoryExhaustionDoNotPersistPointersOrEraseRetainedRows() = runBlocking<Unit> {
        register(); val good = tokens.replicaStageFixture(hash()); val raw = ReplicaSwitchStage.encode(good)
        reject { ReplicaSwitchStage.decode(raw.replace("\"FREEZING\"", "\"FROZEN\"").replace("\"generation\":1", "\"generation\":3")) }
        reject { good.copy(phase = ReplicaSwitchPhase.ABORTED, generation = 4) }
        val retained = ReplicaSwitchStage.key(id(88))
        // A byte-budget sentinel, not an adopted valid history or archive proof.
        dataStore.edit { it[retained] = "x".repeat(ReplicaSwitchStage.HISTORY_LIMIT + 1) }
        val before = dataStore.data.first(); val source = hash()
        reject { stages().begin(good) }
        assertEquals(before, dataStore.data.first()); assertEquals(source, hash()); assertFalse(tokens.replicaSwitchPending())
        assertNull(tokens.replicaStage(good.recoveryId)); assertNotNull(tokens.localCoreWriteAccess())
    }
}
