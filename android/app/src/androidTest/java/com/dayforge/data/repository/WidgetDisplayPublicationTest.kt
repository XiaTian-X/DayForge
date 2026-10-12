package com.dayforge.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.withTransaction
import com.dayforge.data.local.replicaStageFixture
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real mutex, token transitions, Room readers and file display storage; no launcher claim. */
@RunWith(AndroidJUnit4::class)
class WidgetDisplayPublicationTest : NextCoreRequestFixture() {
    private val label = stringPreferencesKey("fact-publication-label")
    private fun display() = PreferenceDataStoreFactory.create(scope = scope,
        produceFile = { File(directory, "fact-display.preferences_pb") })
    private fun reader() = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions))
    private suspend fun proof() = db.withTransaction { nextRestartDatabaseProof(db) }

    @Test fun orphanSqlBlocksCapturePreparationLatePublicationAndForegroundClaims() = runBlocking<Unit> {
        register()
        val publisher = WidgetDisplayPublisher(tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions))
        val original = requireNotNull(publisher.capturePublication())
        val view = reader().read(habit)
        val stage = tokens.replicaStageFixture()
        NextReplicaWriteFence(db).acquire(stage.fence)
        assertFalse(tokens.replicaSwitchPending())
        assertNull(publisher.capturePublication())
        assertFalse(original { fail("A late display cannot use pointer loss as admission") })
        assertFalse(publisher.renderPrepared({ fail("Must not publish a mixed source failure") }) {
            fail("Must not read business rows"); suspend { }
        })
        for (read in listOf<suspend () -> Unit>({ reader().read(habit) }, { reader().authorize(view.claim) },
            { reader().afterWrite(view.claim) }, { reader().publish(view.claim) { fail("No foreground publication") } }))
            assertEquals(com.dayforge.data.local.ReplicaSwitchGuard.PENDING, rejected(read).message)
        assertEquals(stage.fence, db.nextReplicaFenceDao().state())
        assertEquals(habit, db.habitDao().getHabitById(habit.id))
    }

    @Test fun orphanAndDamagedJournalBlockNewCaptureWithoutSqlAndNeverPublishAnError() = runBlocking<Unit> {
        register()
        val publisher = WidgetDisplayPublisher(tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions))
        val stage = tokens.replicaStageFixture()
        tokens.persistReplicaStage(null, stage)
        dataStore.edit { it.remove(com.dayforge.data.local.ReplicaSwitchGuard.POINTER) }
        assertNull(db.nextReplicaFenceDao().state()); assertNull(publisher.capturePublication())
        dataStore.edit { it[com.dayforge.data.local.ReplicaSwitchStage.key(stage.recoveryId)] = "private/path/synthetic-secret" }
        assertNull(publisher.capturePublication())
        assertFalse(publisher.renderPrepared({ fail("No error publication") }) { fail("No prepare"); suspend { } })
    }

    @Test fun verifiedPreCommitCancelAllowsFreshPublicationAndLockedChecksDoNotReenterMutex() = runBlocking<Unit> {
        register()
        val publisher = WidgetDisplayPublisher(tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions))
        val original = requireNotNull(publisher.capturePublication())
        withTimeout(5_000) { sessions.exclusive { db.withTransaction { assertTrue(original.isCurrentWhileAccountLocked()) } } }
        val stages = NextReplicaStageStore(db, tokens, sessions)
        val stage = stages.begin(tokens.replicaStageFixture())
        NextReplicaWriteFence(db).acquire(stage.fence)
        val frozen = stages.recordFrozen(stage)
        assertNull(publisher.capturePublication())
        stages.cancel(frozen)
        assertFalse(original { fail("No freeze/abort ABA") })
        val fresh = requireNotNull(publisher.capturePublication())
        withTimeout(5_000) { sessions.exclusive { db.withTransaction { assertTrue(fresh.isCurrentWhileAccountLocked()) } } }
        assertTrue(fresh { assertFalse(db.inTransaction()) })
    }

    @Test fun legacyClaimUsesOriginalPublicationScopeAndTypedPlansNeverGetLegacyProof() = runBlocking<Unit> {
        val publisher = WidgetDisplayPublisher(tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions))
        val publication = requireNotNull(publisher.capturePublication())
        assertNull(publication.legacyRestartClaim(habit))
        val legacy = habit.copy(appearance = null, planMetadata = null, completionPolicy = null)
        val captured = requireNotNull(publication.legacyRestartClaim(legacy))
        sessions.exclusive { tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "member", id(50), false) }
        val stillOriginal = requireNotNull(publication.legacyRestartClaim(legacy))
        assertEquals(captured, stillOriginal)
        assertEquals(id(1), captured.accountId)
        assertFalse(publication { throw AssertionError("Old display must not publish") })
        assertNotEquals(captured, requireNotNull(publisher.capturePublication()).legacyRestartClaim(legacy))
    }

    @Test fun preparationCanUseRealAccountReadersAndDisplayRunsOutsideRoomWithoutBusinessWrites() = runBlocking<Unit> {
        val publisher = WidgetDisplayPublisher(tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions))
        val state = display()
        val before = proof()
        assertTrue(withTimeout(5000) {
            publisher.renderPrepared(onReadFailure = { throw AssertionError("Unexpected source failure", it) }) {
                val view = reader().read(habit)
                assertEquals(id(1), view.claim.accountId)
                suspend {
                    assertFalse(db.inTransaction())
                    state.edit { it[label] = view.habit.name }
                }
            }
        })
        assertEquals(habit.name, state.data.first()[label])
        assertEquals(before, proof())
    }

    @Test fun oldPreparationAndItsFailureCannotOverwriteNewAccountDisplay() = runBlocking<Unit> {
        val publisher = WidgetDisplayPublisher(tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions))
        val state = display()
        val before = proof()
        for (failRead in listOf(false, true)) {
            sessions.exclusive { tokens.saveLoginSession("synthetic-first", "synthetic-refresh", "member", id(1), false) }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val reading = async(Dispatchers.IO) {
                publisher.renderPrepared(onReadFailure = { calls.incrementAndGet(); state.edit { it[label] = "old error" } }) {
                    val view = reader().read(habit)
                    entered.complete(Unit); release.await()
                    if (failRead) throw IOException("old source failed")
                    suspend { calls.incrementAndGet(); state.edit { it[label] = view.habit.name } }
                }
            }
            try {
                withTimeout(5000) { entered.await() }
                sessions.exclusive {
                    tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(50), false)
                    state.edit { it[label] = "new account" }
                }
                release.complete(Unit)
                assertFalse(withTimeout(5000) { reading.await() })
                assertEquals(0, calls.get())
                assertEquals("new account", state.data.first()[label])
                assertEquals(before, proof())
            } finally { release.complete(Unit); reading.cancelAndJoin() }
        }
    }

    @Test fun accountCleanupWaitsForActualDisplayWriteThenClearsIt() = runBlocking<Unit> {
        val publisher = WidgetDisplayPublisher(tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions))
        val state = display()
        val before = proof()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val attempting = CompletableDeferred<Unit>()
        val order = mutableListOf<String>() // All mutations under the actual account mutex.
        val rendering = async(Dispatchers.IO) {
            publisher.renderPrepared(onReadFailure = { throw it }) {
                val view = reader().read(habit)
                suspend {
                    assertFalse(db.inTransaction()); entered.complete(Unit); release.await()
                    state.edit { it[label] = view.habit.name }; order.add("publish")
                }
            }
        }
        val cleanup = async(Dispatchers.IO) {
            entered.await(); attempting.complete(Unit)
            sessions.exclusive {
                tokens.clearTokens(); state.edit { it.clear() }; order.add("clear")
            }
        }
        try {
            withTimeout(5000) { attempting.await() }
            assertFalse(cleanup.isCompleted)
            release.complete(Unit)
            assertTrue(withTimeout(5000) { rendering.await() })
            withTimeout(5000) { cleanup.await() }
            assertEquals(listOf("publish", "clear"), order)
            assertNull(state.data.first()[label]); assertEquals(before, proof())
        } finally { release.complete(Unit); rendering.cancelAndJoin(); cleanup.cancelAndJoin() }
    }

    @Test fun anonymousAbaAndReplicaRegistrationChangesDiscardPreparedDisplay() = runBlocking<Unit> {
        val publisher = WidgetDisplayPublisher(tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions))
        val before = proof()
        for (anonymous in listOf(true, false)) {
            sessions.exclusive {
                tokens.clearTokens()
                if (!anonymous) tokens.saveLoginSession("synthetic-first", "synthetic-refresh", "member", id(1), false)
            }
            val originalAccess = tokens.localCoreWriteAccess()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val reading = async(Dispatchers.IO) {
                publisher.renderPrepared(onReadFailure = { calls.incrementAndGet() }) {
                    entered.complete(Unit); release.await()
                    suspend { calls.incrementAndGet(); Unit }
                }
            }
            try {
                withTimeout(5000) { entered.await() }
                sessions.exclusive {
                    if (anonymous) {
                        tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(50), false)
                        tokens.clearTokens()
                    } else register()
                }
                if (anonymous) assertEquals(originalAccess, tokens.localCoreWriteAccess())
                else assertNotEquals(originalAccess, tokens.localCoreWriteAccess())
                release.complete(Unit)
                assertFalse(withTimeout(5000) { reading.await() })
                assertEquals(0, calls.get()); assertEquals(before, proof())
            } finally { release.complete(Unit); reading.cancelAndJoin() }
        }
    }

    @Test fun sourceFailureIsPublishedOnlyInScopeAndRendererFailureIsNotMisclassified() = runBlocking<Unit> {
        val publisher = WidgetDisplayPublisher(tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions))
        val state = display()
        val before = proof()
        val source = IOException("source unavailable")
        var readErrors = 0
        assertFalse(publisher.renderPrepared(onReadFailure = {
            assertSame(source, it); assertFalse(db.inTransaction()); readErrors++
            state.edit { prefs -> prefs[label] = "read failed" }
        }) { throw source })
        val renderer = IOException("display unavailable")
        val failure = rejected { publisher.renderPrepared(onReadFailure = { readErrors++ }) {
            reader().read(habit)
            suspend { throw renderer }
        } }
        assertSame(renderer, failure); assertEquals(1, readErrors)
        assertEquals("read failed", state.data.first()[label])
        withTimeout(5000) { sessions.exclusive { assertEquals(before, proof()) } }
    }

    @Test fun cancellationInEitherPhaseReleasesResourcesWithoutPublishingReadFailure() = runBlocking<Unit> {
        val publisher = WidgetDisplayPublisher(tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions))
        val before = proof()
        val calls = AtomicInteger()
        for (cancelPreparation in listOf(true, false)) {
            val entered = CompletableDeferred<Unit>()
            val rendering = launch(Dispatchers.IO) {
                publisher.renderPrepared(onReadFailure = { calls.incrementAndGet() }) {
                    reader().read(habit)
                    if (cancelPreparation) { entered.complete(Unit); awaitCancellation() }
                    suspend { entered.complete(Unit); awaitCancellation() }
                }
            }
            try {
                withTimeout(5000) { entered.await(); rendering.cancelAndJoin() }
                assertTrue(rendering.isCancelled)
                withTimeout(5000) { sessions.exclusive { assertEquals(before, proof()) } }
            } finally { rendering.cancelAndJoin() }
        }
        assertEquals(0, calls.get())
    }
}
