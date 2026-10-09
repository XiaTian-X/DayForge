package com.dayforge.widget.timer

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TimerWidgetTickerTest {
    @Test fun slowDisplayConflatesPendingTicksAndServiceCancellationJoinsItsOnlyConsumer() = runTest {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val calls = mutableListOf<Long>()
        val ticker = TimerWidgetTicker(scope, { id -> calls.add(id); if (calls.size == 1) gate.await(); true },
            { fail("No full refresh expected") }, { throw AssertionError(it) })
        try {
            ticker.tick(1); runCurrent()
            repeat(100) { ticker.tick(1) }; ticker.tick(2); runCurrent()
            assertEquals(listOf(1L), calls)
            gate.complete(Unit); runCurrent()
            assertEquals(listOf(1L, 2L), calls)
            owner.cancelAndJoin(); ticker.tick(3); runCurrent()
            assertEquals(listOf(1L, 2L), calls); assertTrue(owner.children.none())
        } finally { gate.complete(Unit); owner.cancelAndJoin() }
    }

    @Test fun cacheMissRequestsOneFullRefreshUntilHealthyOrAnotherHabit() = runTest {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + StandardTestDispatcher(testScheduler))
        var healthy = false
        var full = 0
        val ticker = TimerWidgetTicker(scope, { healthy }, { full++ }, { throw AssertionError(it) })
        try {
            repeat(10) { ticker.tick(1); runCurrent() }; assertEquals(1, full)
            healthy = true; ticker.tick(1); runCurrent()
            healthy = false; ticker.tick(1); runCurrent(); assertEquals(2, full)
            ticker.tick(2); runCurrent(); assertEquals(3, full)
        } finally { owner.cancelAndJoin() }
    }

    @Test fun displayFailureIsBoundedAndCancellationNeverSchedulesRecovery() = runTest {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + StandardTestDispatcher(testScheduler))
        var cancelled = false
        var full = 0
        var errors = 0
        val ticker = TimerWidgetTicker(scope, {
            if (cancelled) throw CancellationException("service destroyed")
            throw IOException("display IO")
        }, { full++ }, { errors++ })
        try {
            repeat(5) { ticker.tick(1); runCurrent() }
            assertEquals(1, full); assertEquals(1, errors)
            cancelled = true; ticker.tick(2); runCurrent()
            ticker.tick(3); runCurrent()
            assertEquals(1, full); assertEquals(1, errors)
        } finally { owner.cancelAndJoin() }
    }
}
