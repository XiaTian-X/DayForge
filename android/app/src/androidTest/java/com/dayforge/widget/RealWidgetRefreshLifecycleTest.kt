package com.dayforge.widget

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.dayforge.data.local.DatabaseRuntimeHost
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.PhysicalDatabaseEpochFixture
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Complete real WorkManager lifecycle checks for the explicitly selected calibration host.
 * Every worker executes the production WidgetRefresher before a negative test's gate.
 * Gates observe cancellation/cleanup only; they never fabricate a successful refresh.
 * Empty installed widget types do not establish launcher/rendering acceptance.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RealWidgetRefreshLifecycleTest {
    private var session: RealWidgetRefreshRuntime.Session? = null
    private var connected = false
    @get:Rule(order = 1) val epochs = PhysicalDatabaseEpochFixture().also { fixture ->
        fixture.beforeCleanup = { session?.assertSafeForHostCleanup() }
    }
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 3) val name = TestName()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = WorkManager.getInstance(app)

    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        val prior = previous
        if (name.methodName.startsWith("a_")) assertNull(prior)
        else assertNotNull("Run the complete ordered lifecycle class, including the previous physical Host", prior)
        prior?.let {
            assertTrue("Previous case's actual Host did not close", it.host.lifecycleEnded)
            assertTrue("Previous case's physical Room remained open", it.databases.isNotEmpty() && it.databases.none { db -> db.isOpen })
        }
        hilt.inject()
        epochs.connect()
        connected = true
        prior?.let { assertNotSame("Next test must receive a distinct real Hilt Host", it.host, epochs.host) }
        epochs.login()
    }

    @After fun finish() = runBlocking<Unit> {
        try {
            session?.let { owner ->
                if (!owner.quiescent) owner.drainAfterProducersJoined()
                owner.assertSafeForHostCleanup()
            }
        } finally {
            // Preserve inspection evidence even when a safely exited cancelled worker is
            // correctly rejected as unhealthy. The original failure still propagates.
            // Unproven exit cannot authorize cleanup or a successor Host. The outer epoch
            // rule performs shutdown; the next method independently verifies actual closure.
            if (connected && session?.quiescent != false) {
                previous = Previous(epochs.host, epochs.createdRoomInspection())
            }
        }
    }

    private suspend fun open(
        afterRealRefresh: suspend () -> Unit = {},
        beforeBodyExit: suspend () -> Unit = {}
    ): RealWidgetRefreshRuntime.Session = RealWidgetRefreshRuntime.open(
        app, afterRealRefresh, beforeBodyExit
    ).also { session = it }

    /** The Operation acknowledges enqueue only. Session drain separately observes execution. */
    private suspend fun enqueue() {
        val operation = requireNotNull(WidgetRefreshScheduler.request(app))
        withContext(Dispatchers.IO) { operation.result.get(5, TimeUnit.SECONDS) }
    }

    private suspend fun rows(): List<WorkInfo> = withContext(Dispatchers.IO) {
        manager.getWorkInfosForUniqueWork(WidgetRefreshScheduler.WORK_NAME).get(5, TimeUnit.SECONDS)
    }

    private suspend fun assertHostAndAuthorityRemainOwned() {
        assertFalse(epochs.host.lifecycleEnded)
        assertNotNull(epochs.tokens.authenticationSnapshot())
        epochs.current { graph -> assertTrue(graph.database.isOpen) }
        assertTrue(app.getDatabasePath("habit_database").exists())
    }

    private fun assertComplete(owner: RealWidgetRefreshRuntime.Session, count: Int) {
        assertTrue(owner.quiescent)
        owner.assertSafeForHostCleanup()
        val attempts = owner.attempts()
        assertEquals(count, attempts.size)
        assertEquals(count, attempts.map { it.id }.distinct().size)
        assertTrue(attempts.all { it.attempt == 0 && it.bodyExited && it.jobCompleted && it.error == null })
    }

    @Test(timeout = 150_000)
    fun c_realQueuedSuccessCompletesEveryBodyAndJobBeforeHostCleanup() = runBlocking<Unit> {
        val owner = open()
        enqueue()
        enqueue()
        owner.drainAfterProducersJoined()
        assertComplete(owner, 2)
        val ids = owner.attempts().map { it.id }.toSet()
        val completed = rows().filter { it.id in ids }
        assertEquals(ids, completed.map { it.id }.toSet())
        assertTrue(completed.all { it.state == WorkInfo.State.SUCCEEDED &&
            it.outputData.getInt(WidgetRefreshWorker.FAILED_STEPS, -1) == 0 })
        assertHostAndAuthorityRemainOwned()
    }

    private class SyntheticBodyFailure : IllegalStateException("lifecycle-test-body-failure")

    @Test(timeout = 150_000)
    fun b_failedTestBodyStillDrainsItsActualQueuedWorkerAndPreservesOriginalFailure() = runBlocking<Unit> {
        val realReturned = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = open(afterRealRefresh = {
            realReturned.complete(Unit)
            release.await()
        })
        val original = SyntheticBodyFailure()
        var caught: Throwable? = null
        try {
            try {
                enqueue()
                withTimeout(45_000) { realReturned.await() }
                assertFalse(owner.quiescent)
                assertTrue(owner.attempts().single().let { !it.bodyExited && !it.jobCompleted })
                throw original
            } catch (error: Throwable) {
                caught = error
            } finally {
                release.complete(Unit)
                try { owner.drainAfterProducersJoined() }
                catch (cleanup: Throwable) {
                    if (caught == null) caught = cleanup else requireNotNull(caught).addSuppressed(cleanup)
                }
            }
            assertSame(original, caught)
            assertEquals(0, original.suppressed.size)
            assertComplete(owner, 1)
            assertHostAndAuthorityRemainOwned()
        } finally {
            release.complete(Unit)
        }
    }

    @Test(timeout = 150_000)
    fun a_cancellationCannotReleaseHostOrAdmitNextOwnerUntilActualFinallyAndJobExit() = runBlocking<Unit> {
        val realReturned = CompletableDeferred<Unit>()
        val finallyEntered = CompletableDeferred<Unit>()
        val releaseFinally = CompletableDeferred<Unit>()
        val owner = open(afterRealRefresh = {
            realReturned.complete(Unit)
            awaitCancellation()
        }, beforeBodyExit = {
            withContext(NonCancellable) {
                finallyEntered.complete(Unit)
                releaseFinally.await()
            }
        })
        try {
            enqueue()
            withTimeout(45_000) { realReturned.await() }
            val id = owner.attempts().single().id
            val authentication = requireNotNull(epochs.tokens.authenticationSnapshot())
            val cancellation = manager.cancelUniqueWork(WidgetRefreshScheduler.WORK_NAME)
            withContext(Dispatchers.IO) { cancellation.result.get(5, TimeUnit.SECONDS) }
            withTimeout(5_000) { finallyEntered.await() }
            assertEquals(WorkInfo.State.CANCELLED, rows().single { it.id == id }.state)
            assertFalse(owner.quiescent)
            val stillExecuting = owner.attempts().single()
            assertFalse(stillExecuting.bodyExited)
            assertFalse(stillExecuting.jobCompleted)
            var cleanupRejected = false
            try { owner.assertSafeForHostCleanup() }
            catch (error: IllegalStateException) {
                if (error is CancellationException) throw error
                assertEquals("Cannot release tokens/Host before all real widget bodies and jobs exit", error.message)
                cleanupRejected = true
            }
            assertTrue("Cancellation acknowledged before real execution exits must not release Host", cleanupRejected)
            var successorRejected = false
            try { withTimeout(5_000) { RealWidgetRefreshRuntime.open(app) } }
            catch (error: IllegalStateException) {
                if (error is CancellationException) throw error
                assertEquals("A previous real widget owner has not drained", error.message)
                successorRejected = true
            }
            assertTrue("An unfinished owner must not be replaced by the next case", successorRejected)
            val currentAuthentication = requireNotNull(epochs.tokens.authenticationSnapshot())
            // Each fresh read returns a distinct credential container without value equality.
            // Compare every field; boolean token assertions avoid printing credential values.
            assertEquals(authentication.session, currentAuthentication.session)
            assertTrue("Access token changed during rejected cleanup", authentication.accessToken == currentAuthentication.accessToken)
            assertTrue("Refresh token changed during rejected cleanup", authentication.refreshToken == currentAuthentication.refreshToken)
            assertHostAndAuthorityRemainOwned()

            releaseFinally.complete(Unit)
            var cancelledResultRejected = false
            try { owner.drainAfterProducersJoined() }
            catch (error: IllegalStateException) {
                if (error is CancellationException) throw error
                assertEquals("Real widget queue was terminal but did not SUCCEED with failed_steps=0", error.message)
                cancelledResultRejected = true
            }
            assertTrue("Safe cancellation cleanup must not be reported as a successful refresh", cancelledResultRejected)
            assertTrue(owner.quiescent)
            owner.assertSafeForHostCleanup()
            val exited = owner.attempts().single()
            assertTrue(exited.bodyExited)
            assertTrue(exited.jobCompleted)
            assertNotNull(exited.error)

            val successor = open()
            assertNotSame(owner, successor)
            enqueue()
            successor.drainAfterProducersJoined()
            assertComplete(successor, 1)
            assertNotEquals(id, successor.attempts().single().id)
            assertHostAndAuthorityRemainOwned()
        } finally {
            releaseFinally.complete(Unit)
        }
    }

    private data class Previous(val host: DatabaseRuntimeHost, val databases: List<HabitDatabase>)
    private companion object {
        @Volatile var previous: Previous? = null
    }
}
