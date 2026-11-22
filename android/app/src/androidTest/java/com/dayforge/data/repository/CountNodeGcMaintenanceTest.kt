package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real production idle worker/cadence. The Hilt test application does not call production
 * Application.onCreate; this explicitly invokes its same worker without a clock/test switch. */
@RunWith(AndroidJUnit4::class)
class CountNodeGcMaintenanceTest : CountNodeGcFixture() {
    @Test fun actualIdleWorkerEventuallyCollectsWithoutOwningAnyBusinessResult() = runBlocking<Unit> {
        ready(); orphan(800)
        val live = reachable(); val before = nativeAssertions().capture()
        val gc = collector(); val worker = CountTrustNodeMaintenance(gc)
        val owned = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            assertTrue(gc.requested)
            worker.start(owned); worker.start(owned)
            // Includes the unchanged real 30s startup yield; ordinary test limit remains 150s.
            withTimeout(100_000) { while (gc.requested) delay(500) }
            assertOnlyUnreachableRemoved(before, live)
        } finally { owned.cancel(); owned.coroutineContext[Job]?.join() }
    }
}
