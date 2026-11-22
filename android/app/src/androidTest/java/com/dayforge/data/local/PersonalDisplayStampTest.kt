package com.dayforge.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Device-only tests of the actual memory invalidation primitive. No fake owner, root,
 * SQL commit, credentials, whole graph rebuild or UI latency is claimed by these tests.
 */
@RunWith(AndroidJUnit4::class)
class PersonalDisplayStampTest {
    @get:Rule val timeout = Timeout.seconds(150)
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(5, TimeUnit.SECONDS))

    @Test fun completedTransitionNeverRevivesAnOldSourceStamp() {
        val memory = PersonalDataDisplayMemory()
        val old = requireNotNull(memory.capture())
        var published = 0
        assertTrue(memory.publish(old) { published++; true })
        memory.begin()
        assertNull(memory.capture()); assertFalse(memory.isCurrent(old))
        assertFalse(memory.publish(old) { fail("Old source must never publish"); true })
        memory.end()
        assertFalse(memory.publish(old) { fail("ABA must not revive a source"); true })
        val fresh = requireNotNull(memory.capture())
        assertTrue(memory.publish(fresh) { published++; true })
        assertEquals(2, published)
    }

    @Test fun nestedTransitionsRemainBlockedUntilAllOriginalTransitionsEnd() {
        val memory = PersonalDataDisplayMemory(); val old = requireNotNull(memory.capture())
        memory.begin(); memory.begin()
        assertNull(memory.capture()); memory.end(); assertNull(memory.capture())
        assertFalse(memory.publish(old) { fail("Nested transition still active"); true })
        memory.end(); val fresh = requireNotNull(memory.capture())
        assertTrue(memory.isCurrent(fresh)); assertFalse(memory.isCurrent(old))
    }

    @Test fun anotherRegistryCannotAuthorizeThisSourcesAssignment() {
        val first = PersonalDataDisplayMemory(); val second = PersonalDataDisplayMemory()
        val source = requireNotNull(first.capture())
        assertFalse(second.isCurrent(source))
        assertFalse(second.publish(source) { fail("Foreign source"); true })
        assertTrue(first.publish(source) { true })
    }

    @Test fun actualAssignmentAndTransitionCannotInterleave() {
        val memory = PersonalDataDisplayMemory(); val source = requireNotNull(memory.capture())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val attempted = CountDownLatch(1); val transitioned = CountDownLatch(1)
        val assigned = AtomicBoolean(); val failure = AtomicReference<Throwable?>()
        val publisher = Thread {
            try { assertTrue(memory.publish(source) {
                entered.countDown(); await(release); assigned.set(true); true
            }) } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }
        val changer = Thread {
            attempted.countDown()
            try {
                memory.begin(); assertTrue(assigned.get()); transitioned.countDown(); memory.end()
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }
        publisher.start()
        try {
            await(entered); changer.start(); await(attempted)
            assertFalse("Transition cannot finish inside the assignment", transitioned.await(100, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown(); publisher.join(5_000)
            if (changer.state != Thread.State.NEW) changer.join(5_000)
        }
        assertFalse(publisher.isAlive); assertFalse(changer.isAlive)
        failure.get()?.let { throw it }
        await(transitioned); assertFalse(memory.isCurrent(source))
    }

    @Test fun synchronousInvalidationAlreadyBlocksOldAssignmentsBeforeNotificationReturns() {
        val memory = PersonalDataDisplayMemory(); val source = requireNotNull(memory.capture())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val done = CountDownLatch(1); val attempted = CountDownLatch(1)
        val accepted = AtomicReference<Boolean?>(); val failure = AtomicReference<Throwable?>()
        val listener = object : PersonalDataDisplayMemory.Listener {
            override fun transition(blocked: Boolean) { if (blocked) { entered.countDown(); await(release) } }
        }
        memory.register(listener)
        val changer = Thread { try { memory.begin() } catch (error: Throwable) { failure.compareAndSet(null, error) } }
        val publisher = Thread {
            attempted.countDown()
            try { accepted.set(memory.publish(source) { fail("Invalid old source"); true }) }
            catch (error: Throwable) { failure.compareAndSet(null, error) }
            finally { done.countDown() }
        }
        changer.start()
        try {
            await(entered); publisher.start(); await(attempted)
            assertFalse(done.await(100, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown(); changer.join(5_000)
            if (publisher.state != Thread.State.NEW) publisher.join(5_000)
        }
        assertFalse(changer.isAlive); assertFalse(publisher.isAlive)
        failure.get()?.let { throw it }
        await(done); assertEquals(false, accepted.get()); memory.end()
    }
}
