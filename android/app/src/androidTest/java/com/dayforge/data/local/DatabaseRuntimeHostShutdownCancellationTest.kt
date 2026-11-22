package com.dayforge.data.local

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.widget.BusinessWidgetIsolationRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** The real Host retains the same Room owner after its shutdown caller is cancelled.
 * A red implementation must keep the original fixture's physical-close refusal; this test
 * never repairs its outcome with a raw close, a replacement graph or an invented owner. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DatabaseRuntimeHostShutdownCancellationTest {
    @get:Rule(order = 0) val timeout = Timeout.seconds(150)
    @get:Rule(order = 1) val widgets: BusinessWidgetIsolationRule = BusinessWidgetIsolationRule { epochs.cleanupCompleted }
    @get:Rule(order = 2) val epochs: PhysicalDatabaseEpochFixture = PhysicalDatabaseEpochFixture().also {
        it.beforeCleanup = { widgets.owner.requireInstalled() }
    }
    @get:Rule(order = 3) val hilt = HiltAndroidRule(this)

    @Before fun start() = runBlocking<Unit> { hilt.inject(); epochs.connect(); epochs.login() }

    @Test fun cancelledShutdownRetainsOriginalRoomUntilAJoinedRetryActuallyClosesIt() = runBlocking<Unit> {
        val methodOwner = requireNotNull(currentCoroutineContext()[Job])
        val releaseObservation = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<Unit>()
        val observationCleanup = CompletableDeferred<Throwable?>()
        val observationReturned = AtomicBoolean(false)
        val cleanupFailure = AtomicReference<Throwable?>()
        var observer: Deferred<Throwable?>? = null
        var closing: Deferred<Unit>? = null
        var retry: Deferred<Unit>? = null
        var primary: Throwable? = null
        val previousBeforeCleanup = epochs.beforeCleanup
        epochs.beforeCleanup = {
            check(methodOwner.isCompleted) { "SHUTDOWN_CANCEL_METHOD_NOT_JOINED" }
            cleanupFailure.get()?.let { throw it }
            check(observer?.isCompleted != false && closing?.isCompleted != false && retry?.isCompleted != false) {
                "SHUTDOWN_CANCEL_ORIGINAL_JOB_NOT_JOINED"
            }
            previousBeforeCleanup?.invoke()
        }
        fun event(value: String) { Log.i("HostShutdownCancellation", value) }
        try {
            val old = epochs.current { it.database }
            val originalOwner = requireNotNull(CountTrustRuntime.forDatabase(old))
            val created = epochs.realRoomCreates
            val observing = async(Dispatchers.IO) {
                runCatching {
                    epochs.host.observe { graph ->
                        assertSame(old, graph.database)
                        graph.habitDao.getAllHabits().onEach { observed.complete(Unit) }.onCompletion { cause ->
                            withContext(NonCancellable) {
                                event("OBSERVATION_CLEANUP_HELD")
                                observationCleanup.complete(cause)
                                releaseObservation.await()
                                assertTrue("The original Room must remain open through its observer cleanup", old.isOpen)
                                observationReturned.set(true)
                                event("OBSERVATION_CLEANUP_RETURNED")
                            }
                        }
                    }.collect { }
                }.exceptionOrNull()
            }
            observer = observing
            withTimeout(5_000) { observed.await() }
            event("SHUTDOWN_REQUEST")
            val stopping = async(Dispatchers.IO) { epochs.host.shutdownWhenIdle() }
            closing = stopping
            val cause = withTimeout(5_000) { observationCleanup.await() }
            assertTrue(cause is CancellationException)
            assertTrue(generateSequence(cause) { it.cause }.any { it.message == "DATABASE_EPOCH_OBSERVATION_ENDED" })
            assertTrue(old.isOpen); assertFalse(epochs.host.lifecycleEnded); assertFalse(stopping.isCompleted)
            stopping.cancel(CancellationException("SHUTDOWN_TEST_CALLER_CANCELLED"))
            withTimeout(5_000) { stopping.join() }
            assertTrue(stopping.isCompleted && stopping.isCancelled)
            assertTrue(old.isOpen); assertFalse(epochs.host.lifecycleEnded)
            assertFalse(originalOwner.isGraphCloseConfirmed(old))
            event("CANCELLED_SHUTDOWN_JOINED_ORIGINAL_ROOM_OPEN")

            // Terminal observation still ends after a failed shutdown, both with a valid
            // display stamp and during the original account-memory transition fence.
            var lateEntries = 0
            assertNotNull(epochs.tokens.capturePersonalDisplayStamp())
            assertNull(withTimeout(5_000) { epochs.host.observe<Int> {
                lateEntries++; error("Cancelled shutdown admitted a late observation")
            }.firstOrNull() })
            assertEquals(0, lateEntries); assertEquals(created, epochs.realRoomCreates)
            epochs.tokens.withLocalReplicaDisplayTransition {
                assertNull(epochs.tokens.capturePersonalDisplayStamp())
                assertNull(withTimeout(5_000) { epochs.host.observe<Int> {
                    lateEntries++; error("Cancelled shutdown admitted an observation without a display stamp")
                }.firstOrNull() })
                assertEquals(0, lateEntries); assertEquals(created, epochs.realRoomCreates)
            }
            event("LATE_OBSERVERS_ENDED_WITH_AND_WITHOUT_DISPLAY_STAMP")

            releaseObservation.complete(Unit)
            assertNull("The original terminal observer must finish normally", withTimeout(5_000) { observing.await() })
            assertTrue(observing.isCompleted && observationReturned.get())
            assertFalse(originalOwner.hasUnknownGraphBoundary())
            event("ORIGINAL_OBSERVER_JOINED")
            val retrying = async(Dispatchers.IO) { shutdownTestHost(epochs.host) }
            retry = retrying
            retrying.await()
            assertTrue(retrying.isCompleted && !retrying.isCancelled)
            assertEquals("A shutdown retry must not construct a replacement Room", created, epochs.realRoomCreates)
            val physicallyClosed = !old.isOpen
            val confirmed = originalOwner.isGraphCloseConfirmed(old)
            val ended = epochs.host.lifecycleEnded
            event("RETRY_RETURNED ended=$ended original_closed=$physicallyClosed original_close_confirmed=$confirmed")
            assertTrue("Retry reported Host completion without actually closing its retained original Room",
                ended && physicallyClosed && confirmed)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            // Release and join only the original actors. PhysicalDatabaseEpochFixture still
            // owns real Host/Room cleanup and rejects any open/unknown original owner.
            withContext(NonCancellable) {
                try {
                    releaseObservation.complete(Unit)
                    withTimeout(5_000) {
                        closing?.cancelAndJoin()
                        retry?.cancelAndJoin()
                        observer?.cancelAndJoin()
                    }
                    check(observer?.isCompleted != false && closing?.isCompleted != false && retry?.isCompleted != false)
                    event("ORIGINAL_TEST_JOBS_JOINED")
                } catch (cleanup: Throwable) {
                    cleanupFailure.compareAndSet(null, cleanup)
                    PhysicalDatabaseRule.preserveProcessCleanupFailure(cleanup)
                    val failure = primary
                    if (failure == null) throw cleanup
                    if (failure !== cleanup && failure.suppressed.none { it === cleanup }) failure.addSuppressed(cleanup)
                }
            }
        }
    }
}
