package com.dayforge.widget

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class WidgetRefresherTest {
    @Test fun refresh_is_sequential_and_visits_every_instance_once() = runTest {
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

    @Test fun discovery_and_instance_failures_do_not_suppress_other_widgets() = runTest {
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

    @Test fun cancellation_during_discovery_or_refresh_stops_later_work_without_error_logging() = runTest {
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
