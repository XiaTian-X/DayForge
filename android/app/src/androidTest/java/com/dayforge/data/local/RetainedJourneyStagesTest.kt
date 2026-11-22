package com.dayforge.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.junit.runners.model.TestTimedOutException

/** Android-only deterministic lifecycle negatives. No real database/Keystore owner exists
 * here. The test clock advances fixed real-journey budgets; it never supplies J2 evidence. */
@RunWith(AndroidJUnit4::class)
class RetainedJourneyStagesTest {
    private class Scene {
        private val clock = AtomicLong(1)
        val preserved = AtomicReference<Throwable?>()
        val stages = RetainedJourneyStages.counterexample({ preserved.compareAndSet(null, it); Unit }, clock::get)
        fun advance(ms: Long) { clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms)) }
        fun evaluate(marked: Boolean = true, body: () -> Unit) {
            val annotations = if (marked) arrayOf<Annotation>(RetainedPreparedJourney()) else emptyArray()
            val description = Description.createTestDescription(RetainedJourneyStagesTest::class.java,
                "deterministic_stage_counterexample", *annotations)
            stages.apply(object : Statement() { override fun evaluate() = body() }, description).evaluate()
        }
        fun failure(body: () -> Unit): Throwable {
            var observed: Throwable? = null
            try { body() } catch (error: Throwable) { observed = error }
            return observed ?: throw AssertionError("Expected stage refusal")
        }
    }

    @Test(timeout = 150_000) fun initializationAndPreparationAreChargedBeforeTheCompleteBusinessPhase() {
        val scene = Scene()
        scene.evaluate {
            // Resource and Hilt equivalent work precedes the explicit prepare coroutine.
            scene.advance(40_000)
            scene.stages.prepare { scene.advance(30_000) }
            scene.stages.business { scene.advance(100_000) }
            scene.advance(10_000) // Same Statement's resource cleanup, not a free thread.
        }
        assertNull(scene.preserved.get()) // 180s aggregate, each phase independently bounded.
    }

    @Test(timeout = 150_000) fun preparationDeadlineIncludesWorkBeforeItsCoroutineAndSealsCleanup() {
        val scene = Scene()
        var entered = false
        val failure = scene.failure { scene.evaluate {
            scene.advance(150_001)
            scene.stages.prepare { entered = true }
        } }
        assertTrue(failure is TestTimedOutException); assertFalse(entered)
        assertSame(failure, scene.preserved.get())
        assertSame(failure, scene.failure { scene.stages.cleanup() })
        assertSame(failure, scene.failure { scene.stages.requireClear() })
    }

    @Test(timeout = 150_000) fun businessDeadlineCannotSpendUnusedPreparationBudget() {
        val scene = Scene()
        var tail = false
        val failure = scene.failure { scene.evaluate {
            scene.stages.prepare { scene.advance(1_000) }
            scene.stages.business { scene.advance(150_001) }
            tail = true
        } }
        assertTrue(failure is TestTimedOutException); assertFalse(tail)
        assertSame(failure, scene.preserved.get())
        assertSame(failure, scene.failure { scene.stages.cleanup() })
    }

    @Test(timeout = 150_000) fun cleanupDeadlineIsAlsoEnforcedWhenTheStatementHasAlreadyReturned() {
        val scene = Scene()
        val failure = scene.failure { scene.evaluate {
            scene.stages.prepare { }
            scene.stages.business { }
            scene.advance(95_001)
        } }
        assertTrue(failure is TestTimedOutException)
        assertSame(failure, scene.preserved.get())
    }

    @Test(timeout = 150_000) fun businessAssertionIsPreservedAndThePhaseCanOnlyBeEnteredOnce() {
        val scene = Scene()
        val original = AssertionError("original-business-rejection")
        var cleaned = false
        val failure = scene.failure { scene.evaluate {
            scene.stages.prepare { }
            try { scene.stages.business { throw original } }
            finally { scene.stages.cleanup(); cleaned = true }
        } }
        assertSame(original, failure); assertTrue(cleaned); assertNull(scene.preserved.get())
        var repeated = false
        assertTrue(scene.failure { scene.stages.business { repeated = true } } is IllegalStateException)
        assertFalse(repeated)
    }

    @Test(timeout = 150_000) fun preparationAssertionKeepsItsOriginalFailureAndCanCleanUp() {
        val scene = Scene()
        val original = AssertionError("original-preparation-rejection")
        var cleaned = false
        val failure = scene.failure { scene.evaluate {
            try { scene.stages.prepare { throw original } }
            finally { scene.stages.cleanup(); cleaned = true }
        } }
        assertSame(original, failure); assertTrue(cleaned); assertNull(scene.preserved.get())
    }

    @Test(timeout = 150_000) fun interruptedPreparationCannotCleanUpWhileItsOriginalChildIsStillAlive() {
        val scene = Scene()
        val started = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val owner = AtomicReference<Thread?>()
        val job = AtomicReference<Job?>()
        val threadFailure = AtomicReference<Throwable?>()
        val refusedCleanup = AtomicReference<Throwable?>()
        val interrupter = Thread({
            try {
                check(started.await(5, TimeUnit.SECONDS))
                requireNotNull(owner.get()).interrupt()
            } catch (error: Throwable) { threadFailure.set(error) }
        }, "retained-preparation-interrupt-counterexample")
        interrupter.start()
        try {
            val failure = scene.failure { scene.evaluate {
                try {
                    scene.stages.prepare {
                        owner.set(Thread.currentThread()); job.set(requireNotNull(currentCoroutineContext()[Job]))
                        coroutineScope {
                            launch(Dispatchers.Default) { withContext(NonCancellable) { started.countDown(); release.await() } }
                            awaitCancellation()
                        }
                    }
                } finally { refusedCleanup.set(scene.failure { scene.stages.cleanup() }) }
            } }
            assertTrue(failure is InterruptedException)
            assertSame(failure, scene.preserved.get()); assertSame(failure, refusedCleanup.get())
            assertFalse(requireNotNull(job.get()).isCompleted)
        } finally {
            release.complete(Unit)
            runBlocking { withTimeout(5_000) { job.get()?.join() } }
            interrupter.join(5_000)
            check(!interrupter.isAlive)
        }
        threadFailure.get()?.let { throw it }
        assertTrue(requireNotNull(job.get()).isCompleted)
    }

    @Test(timeout = 150_000) fun interruptedSupervisorSealsLatePreparationAndBusinessBeforeCancellingTheStatement() {
        val scene = Scene()
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val statementThread = AtomicReference<Thread?>()
        val supervisorFailure = AtomicReference<Throwable?>()
        val statementFailure = AtomicReference<Throwable?>()
        val refusedPreparation = AtomicReference<Throwable?>()
        val refusedBusiness = AtomicReference<Throwable?>()
        var prepared = false
        var executed = false
        val supervisor = Thread({
            try { scene.evaluate {
                statementThread.set(Thread.currentThread())
                try {
                    entered.countDown()
                    try { CountDownLatch(1).await() }
                    catch (_: InterruptedException) { cancelled.countDown() }
                    // Controlled counterexample for an initializer that catches interruption.
                    check(release.await(5, TimeUnit.SECONDS))
                    refusedPreparation.set(scene.failure { scene.stages.prepare { prepared = true } })
                    refusedBusiness.set(scene.failure { scene.stages.business { executed = true } })
                } catch (error: Throwable) { statementFailure.set(error); throw error }
            } } catch (error: Throwable) { supervisorFailure.set(error) }
        }, "retained-supervisor-interrupt-counterexample")
        supervisor.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            supervisor.interrupt()
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            supervisor.join(5_000)
            statementThread.get()?.join(5_000)
            check(!supervisor.isAlive && statementThread.get()?.isAlive == false)
        }
        statementFailure.get()?.let { throw it }
        val original = requireNotNull(supervisorFailure.get())
        assertTrue(original is InterruptedException); assertSame(original, scene.preserved.get())
        assertSame(original, refusedPreparation.get()); assertSame(original, refusedBusiness.get())
        assertFalse(prepared); assertFalse(executed)
    }

    @Test(timeout = 150_000) fun ordinaryUnmarkedStatementKeepsTheOriginalTimeoutRule() {
        val scene = Scene()
        scene.evaluate(marked = false) {
            assertFalse(scene.stages.requiresPreparation)
            scene.stages.cleanup(); scene.stages.requireClear()
        }
        assertNull(scene.preserved.get())
    }
}
