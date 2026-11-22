package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run the complete actual-command matrix with the existing batched initialization callback. */
@RunWith(AndroidJUnit4::class)
class G0CountEntrySafetyTest:G0CountJourneySafetyTest() {
    private var failOpening=false
    override fun domainOpeningCallback()=G0SchemaOpeningCallback(true,
        beforeSuccess={check(!failOpening) { "G0_COUNT_OPEN_FAILURE" }})

    @Test fun coldOpeningFailureNeverStartsTheBusinessWriteOrReportsSaved()=runBlocking<Unit> {
        configureAction();failOpening=true;storage.reopen()
        val flow=journey();val trace=G0PhaseTrace(driverTiming=false);flow.traceForTest=trace
        assertTrue(flow.increment());flow.awaitSettled()
        assertNotNull(flow.lastError);assertEquals(G0CountJourney.Phase.RECOVERY,flow.state.value.phase)
        assertFalse(flow.events.any { it.kind in setOf("write_started","committed","state_RESULT") })
        assertFalse(flow.increment());assertEquals(1,trace.count("schema_callback_body"))
        flow.close();failOpening=false;storage.reopen()
        assertEquals(0,count("completions"));assertEquals(0,count("sync_outbox"))
    }
}
