package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class G0CommitSchedulerTest {
    @Test fun jointWaiterNeverHoldsConnectionWhileAuthenticationIsBusy() = runBlocking<Unit> {
        val scheduler = G0CommitScheduler()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val auth = launch(start = CoroutineStart.UNDISPATCHED) {
            scheduler.run(G0CommitScheduler.Kind.AUTH, G0CommitScheduler.Resources.AUTH_ONLY) { entered.complete(Unit); release.await() }
        }
        entered.await()
        val joint = async(start = CoroutineStart.UNDISPATCHED) { scheduler.run(G0CommitScheduler.Kind.WRITE) { 7 } }
        assertEquals(1, scheduler.pendingForTest())
        assertEquals(9, withTimeout(1000) { scheduler.run(G0CommitScheduler.Kind.MAINTENANCE,
            G0CommitScheduler.Resources.CONNECTION_ONLY) { 9 } })
        assertFalse(joint.isCompleted); release.complete(Unit); auth.join(); assertEquals(7, joint.await())
    }

    @Test fun jointWaiterNeverHoldsAuthenticationWhileConnectionIsBusy() = runBlocking<Unit> {
        val scheduler = G0CommitScheduler(); val release = CompletableDeferred<Unit>()
        val connection = launch(start = CoroutineStart.UNDISPATCHED) {
            scheduler.run(G0CommitScheduler.Kind.MAINTENANCE, G0CommitScheduler.Resources.CONNECTION_ONLY) { release.await() }
        }
        val joint = async(start = CoroutineStart.UNDISPATCHED) { scheduler.run(G0CommitScheduler.Kind.ACK) { 7 } }
        assertEquals(9, withTimeout(1000) { scheduler.run(G0CommitScheduler.Kind.AUTH,
            G0CommitScheduler.Resources.AUTH_ONLY) { 9 } })
        assertFalse(joint.isCompleted); release.complete(Unit); connection.join(); assertEquals(7, joint.await())
    }

    @Test fun ackGetsAdmissionAfterAtMostTwoOlderForegroundStages() = runBlocking<Unit> {
        val scheduler = G0CommitScheduler(); val release = CompletableDeferred<Unit>(); val order = mutableListOf<String>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) { scheduler.run(G0CommitScheduler.Kind.WRITE) { release.await() } }
        val writes = (1..6).map { n -> launch(start = CoroutineStart.UNDISPATCHED) {
            scheduler.run(G0CommitScheduler.Kind.WRITE) { order.add("write$n") }
        } }
        val ack = launch(start = CoroutineStart.UNDISPATCHED) { scheduler.run(G0CommitScheduler.Kind.ACK) { order.add("ack") } }
        release.complete(Unit); holder.join(); writes.joinAll(); ack.join()
        assertEquals(listOf("write1", "write2", "ack", "write3", "write4", "write5", "write6"), order)
    }

    @Test fun deadlineReservationStartsBeforeDeadlineAndDoesNotTakeHalfOfJointResources() = runBlocking<Unit> {
        // Deterministic policy clock only; not performance evidence.
        val now = AtomicLong(); val scheduler = G0CommitScheduler(now::get)
        val release = CompletableDeferred<Unit>(); val order = mutableListOf<String>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            scheduler.run(G0CommitScheduler.Kind.MAINTENANCE, G0CommitScheduler.Resources.CONNECTION_ONLY) { release.await() }
        }
        val ack = launch(start = CoroutineStart.UNDISPATCHED) { scheduler.run(G0CommitScheduler.Kind.ACK) { order.add("ack") } }
        now.set(350_000_000L)
        val auth = launch(start = CoroutineStart.UNDISPATCHED) {
            scheduler.run(G0CommitScheduler.Kind.AUTH, G0CommitScheduler.Resources.AUTH_ONLY) { order.add("auth") }
        }
        assertTrue(order.isEmpty()); assertEquals(2, scheduler.pendingForTest())
        release.complete(Unit); holder.join(); ack.join(); auth.join()
        assertEquals(listOf("ack", "auth"), order)
    }

    @Test fun queuedCancellationRemovesTicketAndActiveCancellationWaitsForCleanup() = runBlocking<Unit> {
        val scheduler = G0CommitScheduler(); val cleaning = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            scheduler.run(G0CommitScheduler.Kind.WRITE) {
                try { awaitCancellation() } finally { withContext(NonCancellable) { cleaning.complete(Unit); finish.await() } }
            }
        }
        val canceled = launch(start = CoroutineStart.UNDISPATCHED) { scheduler.run(G0CommitScheduler.Kind.ACK) { fail("Canceled queue entered") } }
        canceled.cancelAndJoin(); assertEquals(0, scheduler.pendingForTest())
        holder.cancel(); cleaning.await()
        val next = async(start = CoroutineStart.UNDISPATCHED) { scheduler.run(G0CommitScheduler.Kind.ACK) { 11 } }
        assertFalse(next.isCompleted); assertEquals(1, scheduler.pendingForTest())
        finish.complete(Unit); holder.join(); assertEquals(11, next.await())
        assertFalse(scheduler.samples().first().completed)
    }

    @Test fun exceptionsAndRecursiveAdmissionCannotLeakOrDeadlockResources() = runBlocking<Unit> {
        val scheduler = G0CommitScheduler()
        val error = runCatching { scheduler.run(G0CommitScheduler.Kind.WRITE) {
            scheduler.run(G0CommitScheduler.Kind.ACK) { fail("Recursive entry") }
        } }.exceptionOrNull()
        assertEquals("G0_REENTRANT_ADMISSION", error?.message)
        assertEquals(5, withTimeout(1000) { scheduler.run(G0CommitScheduler.Kind.ACK) { 5 } })
        assertEquals(0, scheduler.pendingForTest())
    }

    @Test fun quarantineRejectsQueuedAndFutureJobsWithoutPretendingActiveWorkEnded() = runBlocking<Unit> {
        val scheduler = G0CommitScheduler(); val finish = CompletableDeferred<Unit>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) { scheduler.run(G0CommitScheduler.Kind.WRITE) { finish.await() } }
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { scheduler.run(G0CommitScheduler.Kind.ACK) { fail("Quarantined ACK entered") } }.exceptionOrNull()
        }
        scheduler.quarantine()
        assertEquals("G0_DOMAIN_RECOVERY_REQUIRED", next.await()?.message)
        assertFalse(holder.isCompleted); assertTrue(scheduler.samples().isEmpty())
        assertEquals("G0_DOMAIN_RECOVERY_REQUIRED", runCatching { scheduler.run(G0CommitScheduler.Kind.WRITE) {} }.exceptionOrNull()?.message)
        finish.complete(Unit); holder.join(); assertEquals(1, scheduler.samples().size)
    }
}
