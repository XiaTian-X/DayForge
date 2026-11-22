package com.dayforge.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.domain.model.StableHabitBinding
import com.dayforge.domain.service.AccountSessionCoordinator
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses AndroidX's independent actor, real Okio file storage and official protobuf serializer.
 * The persistence gate is in writeTo, AFTER the complete DataStore transform has returned.
 * There is no production test hook and no fake store running writes in its caller's scope. */
@RunWith(AndroidJUnit4::class)
class PreferencesCancellationBoundaryTest {
    private lateinit var directory: File
    private lateinit var file: File
    private lateinit var actorJob: Job
    private lateinit var store: DataStore<Preferences>
    private lateinit var callerDispatcher: ExecutorCoroutineDispatcher
    private lateinit var tokens: TokenManager
    private lateinit var preferences: PreferencesManager
    private lateinit var session: LocalDataSession
    private val sessions = AccountSessionCoordinator()
    private val serializer = PausingSerializer()
    private val cipher = CancelInsideCipher()
    private val habit = "f1740000-0000-4000-8000-000000000004"

    @Before fun setup() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.dayforge.testbed")
        directory = File(context.cacheDir, "preferences_tail_${UUID.randomUUID()}").apply { check(mkdirs()) }
        file = File(directory, "settings.preferences_pb")
        callerDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        openStore()
        tokens.saveLoginSession("synthetic-access", "synthetic-refresh", "member",
            "f1740000-0000-4000-8000-000000000001", false)
        tokens.saveServerIdentity("f1740000-0000-4000-8000-000000000002", "f1740000-0000-4000-8000-000000000003")
        session = requireNotNull(tokens.localFactAccess()).session
    }

    private fun openStore() {
        actorJob = SupervisorJob()
        store = PreferenceDataStoreFactory.create(storage = OkioStorage(
            fileSystem = FileSystem.SYSTEM, serializer = serializer,
            producePath = { file.absolutePath.toPath() }),
            scope = CoroutineScope(actorJob + Dispatchers.IO))
        tokens = TokenManager(store, cipher)
        preferences = PreferencesManager(store, tokens = tokens)
    }

    @After fun cleanup() = runBlocking<Unit> {
        serializer.releaseAll(); cipher.onDecrypt.set(null)
        if (::actorJob.isInitialized) withTimeout(5_000) { actorJob.cancelAndJoin() }
        if (::callerDispatcher.isInitialized) callerDispatcher.close()
        if (::directory.isInitialized) check(directory.deleteRecursively())
    }

    private fun binding() = StableHabitBinding.capture(session, habit)
    private suspend fun image() = store.data.first().asMap()
    private suspend fun cancelled(task: kotlinx.coroutines.Deferred<Unit>) {
        withTimeout(5_000) { task.join() } // A cleanup deadline is not the expected caller cancellation.
        val failure = try { task.await(); null }
            catch (error: CancellationException) { error }
        assertNotNull("Cancellation must not be reported as success", failure)
        assertTrue(task.isCancelled); assertTrue(task.isCompleted)
    }

    @Test(timeout = 150_000) fun cancelledPendingWriteKeepsAccountLockUntilPersistenceAndStillPropagatesCancellation() = runBlocking<Unit> {
        val before = image(); val key = HabitDevicePreferences.key(binding())
        val expected = HabitDevicePreferences(binding(), pending = true, pendingEpoch = session.syncEpoch).encode()
        val gate = serializer.arm()
        var returned = false
        supervisorScope {
            val writer = async(callerDispatcher) {
                sessions.exclusive { preferences.addPendingMetricHabit(session, habit); returned = true }
            }
            try {
                val actualActor = withTimeout(5_000) { gate.entered.await() }
                assertNotSame("Persistence belongs to the independent DataStore actor", writer, actualActor)
                assertTrue("The serializer runs in the real DataStore actor scope", actorJob.children.any { it === actualActor })
                assertTrue(actorJob.isActive); assertEquals(expected, gate.value.get()?.get(key))
                writer.cancel(CancellationException("PREFERENCE_CALLER_CANCELLED"))
                // FIFO dispatcher barrier: process any queued caller cancellation before sampling.
                // Old editOrdinary releases the mutex here; fixed code is still joining the actor.
                withContext(callerDispatcher) {
                    assertTrue(writer.isCancelled); assertFalse(writer.isCompleted); assertTrue(actualActor.isActive)
                    assertTrue(sessions.tryMaintenance { error("Account lock released before persistence") }
                        is AccountSessionCoordinator.MaintenanceAttempt.Busy)
                }
                assertFalse(returned)
            } finally { gate.release.complete(Unit) }
            cancelled(writer)
        }
        assertFalse(returned)
        assertEquals(before.toMutableMap().apply { put(key, expected) }, sessions.exclusive { image() })
        // Actual cold read, after the original actor has closed its file connection.
        withTimeout(5_000) { actorJob.cancelAndJoin() }; openStore()
        assertEquals(before.toMutableMap().apply { put(key, expected) }, image())
        assertFalse(preferences.hasNoPendingHintForHistoricalCount(session, habit, tokens))
    }

    @Test(timeout = 150_000) fun cancellationBeforeAndInsideTransformPreservesEveryPreferenceAndAllowsRetry() = runBlocking<Unit> {
        val before = image(); val writes = serializer.writes.get()
        supervisorScope {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            // Occupy the REAL actor with an unchanged transform, so the tested update queues.
            val occupied = async(Dispatchers.IO) {
                store.updateData { entered.complete(Unit); withTimeout(5_000) { release.await() }; it }
            }
            withTimeout(5_000) { entered.await() }
            var returned = false
            // Undispatched start runs through offer/ack.await without a suspension in the
            // preference helper; the already occupied actor cannot enter this transform yet.
            val queued = async(callerDispatcher, start = CoroutineStart.UNDISPATCHED) {
                sessions.exclusive { preferences.addPendingMetricHabit(session, habit); returned = true }
            }
            try {
                queued.cancel(CancellationException("BEFORE_PREFERENCE_TRANSFORM"))
            } finally { release.complete(Unit) }
            withTimeout(5_000) { occupied.await() }; cancelled(queued)
            assertFalse(returned)
        }
        assertEquals(before, image()); assertEquals(writes, serializer.writes.get())
        val injected = AtomicInteger()
        supervisorScope {
            var returned = false
            val inside = async(callerDispatcher) {
                val caller = requireNotNull(currentCoroutineContext()[Job])
                sessions.exclusive {
                    cipher.onDecrypt.set { injected.incrementAndGet(); caller.cancel(CancellationException("INSIDE_PREFERENCE_TRANSFORM")) }
                    preferences.addPendingMetricHabit(session, habit); returned = true
                }
            }
            cancelled(inside); assertFalse(returned)
        }
        assertEquals(1, injected.get()); assertEquals(before, image()); assertEquals(writes, serializer.writes.get())
        sessions.exclusive { preferences.addPendingMetricHabit(session, habit) }
        assertEquals(before.toMutableMap().apply { put(HabitDevicePreferences.key(binding()),
            HabitDevicePreferences(binding(), pending = true, pendingEpoch = session.syncEpoch).encode()) }, image())
    }

    @Test(timeout = 150_000) fun persistenceFailureAfterTransformReleasesLockWithoutReportingSuccessOrChangingDisk() = runBlocking<Unit> {
        val before = image(); val gate = serializer.arm(fail = true)
        var returned = false
        supervisorScope {
            val writer = async(callerDispatcher) {
                sessions.exclusive { preferences.addPendingMetricHabit(session, habit); returned = true }
            }
            try {
                withTimeout(5_000) { gate.entered.await() }
                assertTrue(sessions.tryMaintenance { error("Account lock released during persistence") }
                    is AccountSessionCoordinator.MaintenanceAttempt.Busy)
            } finally { gate.release.complete(Unit) }
            val failure = try { withTimeout(5_000) { writer.await() }; null }
                catch (error: IOException) { error }
            assertEquals("PREFERENCE_WRITE_FAILURE", requireNotNull(failure).message)
        }
        assertFalse(returned); assertEquals(before, sessions.exclusive { image() })
        withTimeout(5_000) { actorJob.cancelAndJoin() }; openStore()
        assertEquals(before, image())
        sessions.exclusive { preferences.addPendingMetricHabit(session, habit) }
        assertFalse(preferences.hasNoPendingHintForHistoricalCount(session, habit, tokens))
    }

    private class Gate(val fail: Boolean) {
        val entered = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>()
        val value = AtomicReference<Preferences?>()
    }
    private class PausingSerializer : OkioSerializer<Preferences> {
        private val next = AtomicReference<Gate?>()
        private val active = AtomicReference<Gate?>()
        val writes = AtomicInteger()
        override val defaultValue get() = PreferencesSerializer.defaultValue
        override suspend fun readFrom(source: BufferedSource) = PreferencesSerializer.readFrom(source)
        fun arm(fail: Boolean = false) = Gate(fail).also { check(next.compareAndSet(null, it)) }
        fun releaseAll() { next.getAndSet(null)?.release?.complete(Unit); active.get()?.release?.complete(Unit) }
        override suspend fun writeTo(t: Preferences, sink: BufferedSink) {
            writes.incrementAndGet()
            val gate = next.getAndSet(null)
            if (gate != null) {
                check(active.compareAndSet(null, gate))
                try {
                    gate.value.set(t)
                    gate.entered.complete(requireNotNull(currentCoroutineContext()[Job]))
                    withTimeout(5_000) { gate.release.await() }
                    if (gate.fail) throw IOException("PREFERENCE_WRITE_FAILURE")
                } finally { active.set(null) }
            }
            PreferencesSerializer.writeTo(t, sink)
        }
    }
    private class CancelInsideCipher : TokenCipher {
        val onDecrypt = AtomicReference<(() -> Unit)?>()
        override fun encrypt(value: String) = "encrypted:$value"
        override fun decrypt(value: String): String {
            onDecrypt.getAndSet(null)?.invoke()
            return value.removePrefix("encrypted:")
        }
    }
}
