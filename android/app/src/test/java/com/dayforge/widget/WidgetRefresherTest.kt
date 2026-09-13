package com.dayforge.widget

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetRefresherTest {
    @Test fun `refresh is sequential and visits every instance once`() = runTest {
        val events = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val refresher = WidgetRefresher(listOf(
            WidgetRefresher.Target("checkin") { listOf(
                suspend { events.add("first start"); gate.await(); events.add("first end"); Unit },
                suspend { events.add("second"); Unit }
            ) },
            WidgetRefresher.Target("timer") { listOf(suspend { events.add("timer"); Unit }) }
        ), { _, error -> throw AssertionError(error) })
        val job = launch { assertEquals(0, refresher.refresh()) }
        runCurrent()
        assertEquals(listOf("first start"), events)
        gate.complete(Unit)
        job.join()
        assertEquals(listOf("first start", "first end", "second", "timer"), events)
    }

    @Test fun `discovery and instance failures do not suppress other widgets`() = runTest {
        val visited = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val refresher = WidgetRefresher(listOf(
            WidgetRefresher.Target("discovery") { throw IOException("unavailable") },
            WidgetRefresher.Target("instances") { listOf(
                suspend { throw IOException("removed during refresh") },
                suspend { visited.add("second instance"); Unit }
            ) },
            WidgetRefresher.Target("empty") { emptyList() },
            WidgetRefresher.Target("alarm") { listOf(suspend { visited.add("alarm"); Unit }) }
        ), { name, _ -> failures.add(name) })
        assertEquals(2, refresher.refresh())
        assertEquals(listOf("discovery", "instances"), failures)
        assertEquals(listOf("second instance", "alarm"), visited)
    }

    @Test fun `cancellation during discovery or refresh stops later work without error logging`() = runTest {
        for (duringDiscovery in listOf(true, false)) {
            var later = false
            val cancelled = WidgetRefresher.Target("cancelled") {
                if (duringDiscovery) throw CancellationException("cancelled")
                listOf(suspend { throw CancellationException("cancelled") })
            }
            val refresher = WidgetRefresher(listOf(cancelled,
                WidgetRefresher.Target("later") { later = true; emptyList() }
            ), { _, _ -> fail("Cancellation must not be logged as a refresh failure") })
            try {
                refresher.refresh()
                fail("Expected cancellation")
            } catch (_: CancellationException) {
                assertFalse(later)
            }
        }
    }
}
