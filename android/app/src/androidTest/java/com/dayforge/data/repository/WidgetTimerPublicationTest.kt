package com.dayforge.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real account mutex, file Room and display DataStore; this is not a launcher pixel test. */
@RunWith(AndroidJUnit4::class)
class WidgetTimerPublicationTest : NextCoreRequestFixture() {
    private fun writer() = NextTimerWriter(db, tokens, sessions)
    private suspend fun proof() = db.withTransaction { nextRestartDatabaseProof(db) }
    private val label = stringPreferencesKey("timer-publication-label")
    private fun display() = PreferenceDataStoreFactory.create(scope = scope,
        produceFile = { File(directory, "timer-display.preferences_pb") })

    @Test fun publicationUsesOriginalSnapshotOutsideRoomAndNeverWritesBusinessData() = runBlocking<Unit> {
        start()
        producer().write(local()) { db.habitDao().update(timerHabit.copy(targetValue = 3, isCountdown = true)) }
        storage.reopen()
        val current = requireNotNull(db.habitDao().getVisibleHabitById(timerHabit.id))
        val before = proof()
        val state = display()
        assertTrue(writer().renderWidgetSnapshot(current) { read ->
            assertFalse(db.inTransaction())
            assertEquals(current, read.habit)
            assertEquals(id(20), read.activeLog!!.uuid)
            val policy = requireNotNull(read.policy)
            assertEquals(60, policy.targetSeconds)
            assertFalse(policy.isCountdown)
            assertEquals(180, policy.maxDurationSeconds)
            assertEquals(id(1), read.authority.accountId)
            assertTrue(read.history.qualifiedDates.isEmpty())
            state.edit { it[label] = read.habit.name }
        })
        assertEquals(current.name, state.data.first()[label])
        assertEquals(before, proof())
    }

    @Test fun accountCleanupCannotInterleaveWithDisplayPublication() = runBlocking<Unit> {
        start()
        val before = proof()
        val state = display()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val attempting = CompletableDeferred<Unit>()
        val order = mutableListOf<String>() // All mutations serialized by the real account mutex.
        val rendering = async(Dispatchers.IO) {
            writer().renderWidgetSnapshot(timerHabit) { read ->
                assertFalse(db.inTransaction())
                order.add("read")
                entered.complete(Unit)
                release.await()
                state.edit { it[label] = read.habit.name }
                order.add("publish")
            }
        }
        val cleanup = async(Dispatchers.IO) {
            entered.await(); attempting.complete(Unit)
            sessions.exclusive {
                state.edit { it.clear() }
                tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(50), false)
                order.add("clear")
            }
        }
        try {
            withTimeout(5000) { attempting.await() }
            assertFalse(cleanup.isCompleted)
            release.complete(Unit)
            assertTrue(withTimeout(5000) { rendering.await() })
            withTimeout(5000) { cleanup.await() }
            assertEquals(listOf("read", "publish", "clear"), order)
            assertNull(state.data.first()[label])
            assertEquals(id(50), tokens.authenticationSnapshot()!!.session.userId)
            assertEquals(before, proof())
        } finally {
            release.complete(Unit); rendering.cancelAndJoin(); cleanup.cancelAndJoin()
        }
    }

    @Test fun queuedOldPublicationCannotAdoptRetainedDataAfterAccountSwitch() = runBlocking<Unit> {
        start()
        val before = proof()
        val state = display()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val attempted = CompletableDeferred<Unit>()
        val published = AtomicBoolean(false)
        val transition = launch(Dispatchers.IO) {
            sessions.exclusive {
                entered.complete(Unit); release.await()
                tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(50), false)
                state.edit { it[label] = "new account" }
            }
        }
        val rendering = async(Dispatchers.IO) {
            entered.await(); attempted.complete(Unit)
            rejected { writer().renderWidgetSnapshot(timerHabit) {
                published.set(true); state.edit { it[label] = "old account" }
            } }
        }
        try {
            withTimeout(5000) { attempted.await() }
            release.complete(Unit)
            withTimeout(5000) { transition.join(); rendering.await() }
            assertFalse(published.get())
            assertEquals("new account", state.data.first()[label])
            assertEquals(before, proof())
        } finally {
            release.complete(Unit); rendering.cancelAndJoin(); transition.cancelAndJoin()
        }
    }

    @Test fun rendererFailureAndCancellationReleaseAccountLockWithoutRepairingFacts() = runBlocking<Unit> {
        start()
        val before = proof()
        val error = rejected { writer().renderWidgetSnapshot(timerHabit) { throw IOException("display storage failed") } }
        assertTrue(error is IOException)
        val entered = CompletableDeferred<Unit>()
        val rendering = launch(Dispatchers.IO) {
            writer().renderWidgetSnapshot(timerHabit) { entered.complete(Unit); awaitCancellation() }
        }
        try {
            withTimeout(5000) { entered.await(); rendering.cancelAndJoin() }
            assertTrue(rendering.isCancelled)
            withTimeout(5000) { sessions.exclusive { assertEquals(before, proof()) } }
            assertNotNull(writer().widgetSnapshot(timerHabit))
        } finally { rendering.cancelAndJoin() }
    }

    @Test fun staleAndCorruptOriginalNeverEnterRendererAndColdRetryKeepsOriginalIdentity() = runBlocking<Unit> {
        start()
        var calls = 0
        assertFalse(writer().renderWidgetSnapshot(timerHabit.copy(uuid = id(90))) { calls++ })
        db.openHelper.writableDatabase.execSQL("UPDATE timer_command_outbox SET occurredAt=occurredAt+1 WHERE commandId=?", arrayOf(id(21)))
        val corrupt = proof()
        rejected { writer().renderWidgetSnapshot(timerHabit) { calls++ } }
        assertEquals(0, calls); assertEquals(corrupt, proof())
        db.openHelper.writableDatabase.execSQL("UPDATE timer_command_outbox SET occurredAt=? WHERE commandId=?", arrayOf<Any>(millis, id(21)))
        storage.reopen()
        val healthy = proof()
        assertTrue(writer().renderWidgetSnapshot(timerHabit) { assertEquals(id(20), it.authority.sessionUuid); calls++ })
        assertEquals(1, calls); assertEquals(healthy, proof())
    }
}
