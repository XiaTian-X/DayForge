package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
open class G0DeferredRefreshTest : G0InteractionFixture() {
    internal open val resumeAfterCommit=false
    override fun installScreen()=Unit
    internal suspend fun summary(flow:StateFlow<G0InteractionSession.State>):Pair<G0InteractionSession,G0MetricInteractionAdapter> {
        val port=additionalAdapter()
        val session=G0InteractionSession(port,operationOwner,editable=false,deferBackgroundReadsDuring=flow,resumeBackgroundReadsAfterCommit=resumeAfterCommit)
        withContext(Dispatchers.Main) { session.open() }
        viewing(session);session.awaitSettled()
        return session to port
    }
    internal suspend fun viewing(session:G0InteractionSession)=withTimeout(3_000) {
        session.state.first { it.phase==G0InteractionSession.Phase.VIEWING && it.renderable() }
    }
    private suspend fun deferred(session:G0InteractionSession)=withTimeout(3_000) {
        while(session.events.none { it.kind=="refresh_deferred" }) delay(2)
        assertEquals(G0InteractionSession.Phase.REFRESHING,session.state.value.phase)
        assertNull(session.state.value.publication)
    }
    internal suspend fun close(session:G0InteractionSession) {
        withContext(Dispatchers.Main) { session.detach() };session.awaitSettled()
    }
    @Test fun busyWindowCoalescesChangesButUsesTwoFreshAuthenticationsWhenResumed()=runBlocking<Unit> {
        setupInteraction();openReady()
        val foreground=MutableStateFlow(G0InteractionSession.State(phase=G0InteractionSession.Phase.SAVING))
        val (summary,port)=summary(foreground);val held=summary.state.value
        val trace=G0PhaseTrace(driverTiming=false);port.traceForTest=trace
        try {
            withContext(Dispatchers.IO) { activeStore.edit("First change","",id(5101)) }
            deferred(summary);assertFalse(held.renderable());assertEquals(0,trace.count("decrypt_access"))
            withContext(Dispatchers.IO) { activeStore.edit("Second change","",id(5102)) }
            foreground.value=G0InteractionSession.State(phase=G0InteractionSession.Phase.SAVED)
            val fresh=viewing(summary);summary.awaitSettled()
            assertEquals("Second change",fresh.publication!!.value.name)
            assertEquals(2,trace.count("decrypt_access"));assertEquals(1,trace.count("tx_begin_outer"))
            assertEquals(1,summary.events.count { it.kind=="refresh_deferred" })
        } finally { close(summary) }
        originalDatabase();assertEquals(2,count("next_request_origins"));assertEquals(2,count("sync_outbox"))
    }
    @Test fun stuckForegroundCannotRenewWindowAndStarveSummary()=runBlocking<Unit> {
        setupInteraction();openReady()
        val foreground=MutableStateFlow(G0InteractionSession.State(phase=G0InteractionSession.Phase.SAVING))
        val (summary,port)=summary(foreground);val trace=G0PhaseTrace(driverTiming=false);port.traceForTest=trace
        try {
            withContext(Dispatchers.IO) { activeStore.edit("Bounded update","",id(5103)) }
            deferred(summary)
            val fresh=viewing(summary);summary.awaitSettled()
            assertEquals(G0InteractionSession.Phase.SAVING,foreground.value.phase)
            assertEquals("Bounded update",fresh.publication!!.value.name)
            assertEquals(2,trace.count("decrypt_access"));assertEquals(1,summary.events.count { it.kind=="refresh_resumed" })
            val begin=summary.events.single { it.kind=="refresh_deferred" }.at
            val end=summary.events.single { it.kind=="refresh_resumed" }.at
            assertTrue(end-begin>=450_000_000L) // Verify the test exercised the bounded timeout, not an early idle transition.
        } finally { close(summary) }
    }
    @Test fun detachWhileDeferredCannotReadOrPublishLater()=runBlocking<Unit> {
        setupInteraction();openReady()
        val foreground=MutableStateFlow(G0InteractionSession.State(phase=G0InteractionSession.Phase.SAVING))
        val (summary,port)=summary(foreground);val held=summary.state.value
        val trace=G0PhaseTrace(driverTiming=false);port.traceForTest=trace
        withContext(Dispatchers.IO) { activeStore.edit("Detached update","",id(5104)) }
        deferred(summary);close(summary)
        foreground.value=G0InteractionSession.State(phase=G0InteractionSession.Phase.SAVED)
        assertFalse(held.renderable());assertEquals(0,trace.count("decrypt_access"))
        assertEquals(G0InteractionSession.Phase.CLOSED,summary.state.value.phase);assertNull(summary.state.value.publication)
    }
    @Test fun authorityChangeWhileDeferredCancelsPublicationInsteadOfReusingProof()=runBlocking<Unit> {
        setupInteraction();openReady()
        val foreground=MutableStateFlow(G0InteractionSession.State(phase=G0InteractionSession.Phase.SAVING))
        val (summary,port)=summary(foreground);val held=summary.state.value
        val trace=G0PhaseTrace(driverTiming=false);port.traceForTest=trace
        try {
            withContext(Dispatchers.IO) { activeStore.edit("Revoked update","",id(5105)) }
            deferred(summary)
            tokens.saveDeviceRegistration(id(4),setOf("sync.read"),true,2)
            withTimeout(3_000) { summary.state.first { it.phase==G0InteractionSession.Phase.RECOVERY } };summary.awaitSettled()
            foreground.value=G0InteractionSession.State(phase=G0InteractionSession.Phase.SAVED)
            assertFalse(held.renderable());assertNull(summary.state.value.publication)
            assertEquals(0,trace.count("decrypt_access"))
        } finally { close(summary) }
    }
    @Test(timeout=30_000) fun actualHandoffAndUnknownCommitCannotProduceFalseSummarySuccess()=runBlocking<Unit> {
        setupInteraction();openReady()
        val (summary,_)=summary(interaction.state)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        try {
            draft("Uncertain foreground")
            activeStore.afterPrepareForTest={ entered.complete(Unit);release.await() }
            assertTrue(interaction.confirm());withTimeout(5_000) { entered.await() }
            // Prepare a concurrent committed change while the UI owns the intent but no SQL resource.
            activeStore.afterPrepareForTest={}
            withTimeout(5_000) { withContext(Dispatchers.IO) { activeStore.edit("Background while saving","",id(5106)) } }
            deferred(summary)
            endFaults.failAfterNextEnd.set(true);release.complete(Unit)
            phase(G0InteractionSession.Phase.RECOVERY);interaction.awaitSettled()
            withTimeout(3_000) { summary.state.first { it.phase==G0InteractionSession.Phase.RECOVERY } };summary.awaitSettled()
            assertNull(summary.state.value.publication);assertFalse(interaction.confirm())
            assertTrue(interaction.events.none { it.kind=="committed" })
        } finally { release.complete(Unit);close(summary) }
        originalDatabase();assertEquals(2,count("next_request_origins"));assertEquals(2,count("sync_outbox"))
    }
}
