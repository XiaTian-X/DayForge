package com.dayforge.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Cancellation brackets the real DataStore actor and real persisted return, never a fake commit. */
@RunWith(AndroidJUnit4::class)
class LegacySessionGenerationPersistenceTest : LegacySessionGenerationFixture() {
    // The production registry is weak; this test owns its observer across every suspension.
    @Volatile private var retainedDisplayListener: PersonalDataDisplayMemory.Listener? = null

    private suspend fun cancelled(job: Deferred<*>) {
        try { withTimeout(5_000) { job.await() }; fail("Cancelled caller cannot receive preparation success") }
        catch (_: CancellationException) { assertTrue(job.isCancelled) }
    }

    private suspend fun withJobCleanup(job: Job, release: CompletableDeferred<Unit>, block: suspend () -> Unit) {
        var failure: Throwable? = null
        try { block() } catch (error: Throwable) { failure = error }
        try { release.complete(Unit); withTimeout(5_000) { job.cancelAndJoin() } }
        catch (error: Throwable) { failure = preserveLegacySessionMethodFailure(failure, error) }
        failure?.let { throw it }
    }

    @Test fun cancellationWhileQueuedBeforeTransformCannotCreateGeneration() = legacyGenerationTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val before = raw()
        store.beforeTransform.set { entered.complete(Unit); release.await() }
        val job = scope.async { prepare() }
        withJobCleanup(job, release) {
            withTimeout(45_000) { entered.await() }; job.cancel(); release.complete(Unit); cancelled(job)
            assertEquals(before, raw()); assertNull(generation()); assertEquals(0, store.committedChanges.get())
        }
    }

    @Test fun cancellationInsideCurrentKeystoreProofRollsBackTheWholeTransform() = legacyGenerationTest {
        val before = raw(); lateinit var job: Deferred<LegacySessionGenerationResult>
        cipher.beforeDecrypt.set { job.cancel(CancellationException("synthetic caller cancellation")) }
        job = scope.async(start = CoroutineStart.LAZY) { prepare() }
        job.start(); cancelled(job)
        assertEquals(before, raw()); assertNull(generation()); assertEquals(0, store.committedChanges.get())
        assertEquals(1, cipher.decryptions.get())
    }

    @Test fun callerCancellationAfterTransformKeepsMemoryClosedUntilActualPersistenceCompletes() = legacyGenerationTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val transitions = CopyOnWriteArrayList<Boolean>()
        val listener = object : PersonalDataDisplayMemory.Listener {
            override fun transition(blocked: Boolean) { transitions += blocked }
        }
        retainedDisplayListener = listener
        try {
            tokens.registerPersonalDisplay(listener)
            val old = requireNotNull(tokens.capturePersonalDisplayStamp())
            store.afterTransform.set { entered.complete(Unit); release.await() }
            val job = scope.async { prepare() }
            withJobCleanup(job, release) {
                withTimeout(45_000) { entered.await() }
                assertNull(tokens.capturePersonalDisplayStamp()); assertFalse(tokens.isCurrentPersonalDisplayStamp(old))
                job.cancel(); yield()
                assertFalse("Caller cancellation must not finish the actual write join", job.isCompleted)
                assertNull(tokens.capturePersonalDisplayStamp())
                release.complete(Unit); cancelled(job)
                val saved = requireNotNull(generation()); assertEquals(1, store.committedChanges.get())
                assertNotNull(tokens.capturePersonalDisplayStamp()); assertFalse(tokens.isCurrentPersonalDisplayStamp(old))
                assertTrue(transitions.contains(true)); assertEquals(false, transitions.last())
                cold(); assertEquals(LegacySessionGenerationResult.CurrentGeneration(saved), prepare())
                assertEquals(saved, generation()); assertEquals(0, store.committedChanges.get())
            }
        } finally {
            retainedDisplayListener = null
        }
    }

    @Test fun committedWriteWithCancelledReturnIsReusedAfterColdPreferencesReopen() = legacyGenerationTest {
        val persisted = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        store.afterPersistence.set { persisted.complete(Unit); release.await() }
        val job = scope.async { prepare() }
        withJobCleanup(job, release) {
            withTimeout(45_000) { persisted.await() }
            val saved = requireNotNull(generation())
            assertEquals(1, store.committedChanges.get()); assertNull(tokens.capturePersonalDisplayStamp())
            job.cancel(); release.complete(Unit); cancelled(job)
            cold(); assertEquals(LegacySessionGenerationResult.CurrentGeneration(saved), prepare())
            assertEquals(saved, generation()); assertEquals(0, store.committedChanges.get())
        }
    }

    @Test fun actualTransformFailureHasNoPersistedKeyAndExplicitPreparationCanRetry() = legacyGenerationTest {
        val before = raw()
        store.afterTransform.set { throw IOException("synthetic transform persistence failure") }
        val failure = runCatching { prepare() }.exceptionOrNull()
        assertTrue(failure is IOException); assertEquals(before, raw()); assertNull(generation())
        assertNotNull(tokens.capturePersonalDisplayStamp()); assertEquals(0, store.committedChanges.get())
        val retry = prepare() as LegacySessionGenerationResult.CreatedGeneration
        assertEquals(retry.generation, generation()); assertEquals(1, store.committedChanges.get())
    }

    @Test fun actualCommittedDataSurvivesLostIoReturnWithoutRollbackOrSecondRotation() = legacyGenerationTest {
        val original = raw().asMap()
        store.afterPersistence.set { throw IOException("synthetic lost persisted return") }
        assertTrue(runCatching { prepare() }.exceptionOrNull() is IOException)
        val saved = requireNotNull(generation())
        assertEquals(original, raw().asMap().filterKeys { it.name != generationKey.name })
        assertEquals(1, store.committedChanges.get()); assertNotNull(tokens.capturePersonalDisplayStamp())
        cold(); assertEquals(LegacySessionGenerationResult.CurrentGeneration(saved), prepare())
        assertEquals(saved, generation()); assertEquals(0, store.committedChanges.get())
    }
}
