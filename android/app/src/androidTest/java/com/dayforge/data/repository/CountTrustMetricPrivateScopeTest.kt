package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real formal0/two-metric writes. Each refusal happens after actual log and source insertion;
 * all scope, original request checks, journal matching and actual transaction end remain active.
 */
@RunWith(AndroidJUnit4::class)
class CountTrustMetricPrivateScopeTest : CountTrustFormalProfileFixture() {
    private class AbortAfterRealBind : RuntimeException()

    @Test fun rolledBackRealTicketCannotBindAnotherRealCapture() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs()
        val producer = producer()
        lateinit var retired: CountTrustKernel.MetricInsertTicket
        var firstBound = false
        try {
            producer.writeRounds<Unit>(producer.captureRounds()) {
                val (ticket, ids) = registerAndInsertMetricScope(inputs)
                CountTrustKernel.bindMetricBatch(db, ticket, ids)
                retired = ticket; firstBound = true
                throw AbortAfterRealBind()
            }
            fail("The first real capture must roll back")
        } catch (_: AbortAfterRealBind) {
            // Only the deliberate test rollback is consumed; cancellation and other failures propagate.
        }
        assertTrue(firstBound); assertMetricScopeRolledBack(inputs)
        var secondInserted = false
        val failure = rejected { producer.writeRounds(producer.captureRounds()) {
            val (current, ids) = registerAndInsertMetricScope(inputs)
            assertNotSame(retired, current); secondInserted = true
            CountTrustKernel.bindMetricBatch(db, retired, ids)
        } }
        assertTrue(secondInserted)
        assertEquals("METRIC_LOCAL_TICKET_CHANGED", failure.message)
        assertMetricScopeRolledBack(inputs)
    }

    @Test fun childJobCannotBindParentRegisteredAndInsertedBatch() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val producer = producer()
        var childReachedBinding = false
        val failure = rejected { producer.writeRounds(producer.captureRounds()) {
            val (ticket, ids) = registerAndInsertMetricScope(inputs)
            val parentJob = requireNotNull(currentCoroutineContext()[Job])
            coroutineScope {
                async(start = CoroutineStart.UNDISPATCHED) {
                    assertNotSame(parentJob, currentCoroutineContext()[Job])
                    assertTrue("Child starts on the same real Room transaction thread", db.inTransaction())
                    childReachedBinding = true
                    CountTrustKernel.bindMetricBatch(db, ticket, ids)
                }.await()
            }
        } }
        assertTrue(childReachedBinding)
        assertEquals("METRIC_LOCAL_SCOPE_EXPIRED", failure.message)
        assertMetricScopeRolledBack(inputs)
    }

    @Test fun realTicketCannotBeBoundTwiceAfterActualInsertion() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val producer = producer()
        var firstBindingCompleted = false
        val failure = rejected { producer.writeRounds(producer.captureRounds()) {
            val (ticket, ids) = registerAndInsertMetricScope(inputs)
            CountTrustKernel.bindMetricBatch(db, ticket, ids)
            firstBindingCompleted = true
            assertEquals(inputs.header, CountTrustSeal.read(db.openHelper.writableDatabase))
            CountTrustKernel.bindMetricBatch(db, ticket, ids)
        } }
        assertTrue(firstBindingCompleted)
        assertEquals("METRIC_LOCAL_TICKET_CHANGED", failure.message)
        assertMetricScopeRolledBack(inputs)
    }

    @Test fun cancellationAfterActualBindingCannotPublishOrSealAndFreshGroupCanRetry() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val producer = producer()
        var bound = false
        var writeReturned = false
        var publicationReached = false
        supervisorScope {
            val child = async(start = CoroutineStart.UNDISPATCHED) {
                producer.writeRounds(producer.captureRounds()) {
                    val (ticket, ids) = registerAndInsertMetricScope(inputs)
                    CountTrustKernel.bindMetricBatch(db, ticket, ids)
                    bound = true
                    assertEquals(inputs.header, CountTrustSeal.read(db.openHelper.writableDatabase))
                    currentCoroutineContext().cancel(CancellationException("METRIC_SCOPE_CANCEL_AFTER_BIND"))
                    currentCoroutineContext().ensureActive()
                }
                writeReturned = true
                // The original writer must return after actual transaction end before a caller
                // can publish. Cancellation must never reach this post-return boundary.
                publicationReached = true
            }
            try {
                child.await()
                fail("Actual cancellation must propagate from the original writer")
            } catch (cancelled: CancellationException) {
                assertTrue(generateSequence<Throwable>(cancelled) { it.cause }.any {
                    it.message.orEmpty().contains("METRIC_SCOPE_CANCEL_AFTER_BIND")
                })
            }
            assertTrue(child.isCancelled)
        }
        currentCoroutineContext().ensureActive()
        assertTrue(bound); assertFalse(writeReturned); assertFalse(publicationReached)
        assertMetricScopeRolledBack(inputs)
        val ids = producer.writeRounds(producer.captureRounds()) {
            val (fresh, inserted) = registerAndInsertMetricScope(inputs)
            CountTrustKernel.bindMetricBatch(db, fresh, inserted)
            inserted
        }
        assertMetricScopeCommitted(inputs, ids)
    }
}
