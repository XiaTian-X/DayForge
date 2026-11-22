package com.dayforge.data.repository

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

/** Inherits the complete cancellation/revocation/timeout/unknown-commit matrix for this boundary. */
class G0CommitWindowSafetyTest : G0DeferredRefreshTest() {
    internal override val resumeAfterCommit=true
    @Test fun committedButRefreshingForegroundDoesNotDelayFreshSummaryRead()=runBlocking<Unit> {
        setupInteraction();openReady()
        val foreground=MutableStateFlow(G0InteractionSession.State(phase=G0InteractionSession.Phase.SAVED_REFRESHING))
        val (summary,port)=summary(foreground)
        val old=summary.state.value;val trace=G0PhaseTrace(driverTiming=false);port.traceForTest=trace
        try {
            withContext(Dispatchers.IO) { activeStore.edit("Known committed value","",id(5201)) }
            val fresh=viewing(summary);summary.awaitSettled()
            assertFalse(old.renderable());assertEquals("Known committed value",fresh.publication!!.value.name)
            assertEquals(G0InteractionSession.Phase.SAVED_REFRESHING,foreground.value.phase)
            assertEquals(0,summary.events.count { it.kind=="refresh_deferred" })
            assertEquals(2,trace.count("decrypt_access"));assertEquals(1,trace.count("tx_begin_outer"))
        } finally { close(summary) }
        originalDatabase();assertEquals(1,count("next_request_origins"));assertEquals(1,count("sync_outbox"))
    }
}
