package com.dayforge.domain.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.repository.NextCoreRequestFixture
import com.dayforge.domain.model.CountActionIdentity
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CountActionGateTest : NextCoreRequestFixture() {
    private var now = 0L
    private val callbacks = mutableListOf<() -> Unit>()
    private fun gate() = CountActionGate(tokens, { now }, { _, work -> callbacks += work })
    private suspend fun identity() = CountActionIdentity(local(), habit.id, habit.uuid, null, null, null)
    private fun tick(time: Long) {
        now = time
        val ready = callbacks.toList(); callbacks.clear(); ready.forEach { it() }
    }

    @Test fun firstClickIsImmediateAndRejectedClicksNeverQueueOrExtendCooldown() = runTest {
        val gate = gate(); val claim = identity(); var writes = 0
        assertNotNull(gate.launch(backgroundScope, claim) { writes++ })
        repeat(20) { assertNull(gate.launch(backgroundScope, claim) { fail("Duplicate write") }) }
        assertEquals(setOf(habit.id), gate.busyHabitIds.value)
        runCurrent(); assertEquals(1, writes)
        tick(299); assertEquals(setOf(habit.id), gate.busyHabitIds.value)
        assertNull(gate.launch(backgroundScope, claim) { fail("Early click") })
        tick(300); assertTrue(gate.busyHabitIds.value.isEmpty())
        assertNotNull(gate.launch(backgroundScope, claim) { writes++ })
        runCurrent(); assertEquals(2, writes)
    }

    @Test fun longActionKeepsLeaseAndAnotherHabitDoesNotShareItsCooldown() = runTest {
        val gate = gate(); val claim = identity(); val release = CompletableDeferred<Unit>()
        val first = gate.launch(backgroundScope, claim) { release.await() }!!
        runCurrent(); tick(2000)
        assertNull(gate.tryAcquire(claim))
        val other = gate.tryAcquire(claim.copy(habitId = 999, habitUuid = id(999)))!!
        gate.release(other); assertEquals(setOf(habit.id, 999L), gate.busyHabitIds.value)
        release.complete(Unit); first.join()
        assertFalse(habit.id in gate.busyHabitIds.value)
        tick(2300); assertTrue(gate.busyHabitIds.value.isEmpty())
    }

    @Test fun cancellationBeforeBodyAndDuringBodyBothReleaseWithoutRunningExtraWork() = runTest {
        val gate = gate(); val claim = identity()
        val cancelled = CoroutineScope(Job().also { it.cancel() } + StandardTestDispatcher(testScheduler))
        gate.launch(cancelled, claim) { fail("Cancelled scope cannot write") }!!.join()
        tick(300); assertTrue(gate.busyHabitIds.value.isEmpty())
        val job = gate.launch(backgroundScope, claim) { awaitCancellation() }!!
        runCurrent(); job.cancelAndJoin(); tick(600)
        assertTrue(gate.busyHabitIds.value.isEmpty())
    }

    @Test fun thrownOperationReleasesAndChangingPlanDoesNotBypassBusy() = runTest {
        val gate = gate(); val claim = identity(); val errors = mutableListOf<Throwable>()
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) +
            CoroutineExceptionHandler { _, error -> errors += error })
        try {
            gate.launch(owner, claim) { throw IllegalStateException("write denied") }
            assertNull(gate.tryAcquire(claim.copy(plan = kotlinx.serialization.json.JsonObject(emptyMap()))))
            runCurrent(); assertEquals(1, errors.size)
            tick(300); assertTrue(gate.busyHabitIds.value.isEmpty())
        } finally { owner.cancel() }
    }

    @Test fun authorityTransitionClearsOldLeaseAndOldCompletionCannotReleaseNewLease() = runBlocking {
        val gate = gate(); val claim = identity(); val old = gate.tryAcquire(claim)!!
        register()
        assertTrue(gate.busyHabitIds.value.isEmpty())
        val current = identity(); val fresh = gate.tryAcquire(current)!!
        gate.release(old); tick(500)
        assertEquals(setOf(habit.id), gate.busyHabitIds.value)
        assertNull(gate.tryAcquire(current))
        gate.release(fresh); assertTrue(gate.busyHabitIds.value.isEmpty())
    }

    @Test fun sameSessionPermissionRefreshCannotReopenAnInflightCount() = runBlocking {
        register()
        val gate = gate(); val claim = identity(); val old = gate.tryAcquire(claim)!!
        register(revision = 2)
        assertEquals(claim.session, identity().session)
        assertFalse(gate.isCurrent(claim))
        assertNull(gate.tryAcquire(identity()))
        now = 1000; gate.release(old)
        assertNotNull(gate.tryAcquire(identity()))
    }
}
