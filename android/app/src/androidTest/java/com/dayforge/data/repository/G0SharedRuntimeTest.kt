package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0SharedRuntimeTest : G0InteractionFixture() {
    override fun installScreen() = Unit
    private suspend fun waiters(count:Int)=withTimeout(5_000) {
        while(runtime.waitersForTest()!=count) delay(5)
    }
    @Test fun simultaneousConsumersShareColdProofButAuthenticateEveryRead()=runBlocking<Unit> {
        setupInteraction()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        beforeInitialization={ entered.complete(Unit);release.await() }
        val other=additionalAdapter()
        val first=async(Dispatchers.IO) { adapter.open() }
        entered.await()
        val second=async(Dispatchers.IO) { other.open() }
        try { waiters(2) } finally { release.complete(Unit) }
        assertTrue(first.await().valid());assertTrue(second.await().valid());assertEquals(1,builds)
        val before=decryptCount.get()
        val reads=listOf(async(Dispatchers.IO) { adapter.refresh() },async(Dispatchers.IO) { other.refresh() }).awaitAll()
        assertTrue(reads.all { it.valid() });assertEquals(4,decryptCount.get()-before)
        assertEquals(0,runtime.waitersForTest())
    }
    @Test fun cancelledSubscriberDoesNotCancelOtherSubscriberAndCanReattach()=runBlocking<Unit> {
        setupInteraction()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        beforeInitialization={ entered.complete(Unit);release.await() }
        val other=additionalAdapter()
        val first=async(Dispatchers.IO) { adapter.open() };entered.await()
        val second=async(Dispatchers.IO) { other.open() }
        try {
            waiters(2);first.cancelAndJoin();waiters(1)
            assertFalse(second.isCompleted)
        } finally { release.complete(Unit) }
        assertTrue(second.await().valid());assertTrue(adapter.open().valid());assertEquals(1,builds)
    }
    @Test fun lastCancellationWaitsForRoomCleanupAndQuarantinesRuntime()=runBlocking<Unit> {
        setupInteraction()
        val entered=CompletableDeferred<Unit>();val unwinding=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        configureStoreForTest={ store -> store.afterCheckpointMatchForTest={
            entered.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) { unwinding.complete(Unit);release.await() } }
        } }
        val request=async(Dispatchers.IO) { adapter.open() }
        withTimeout(5_000) { entered.await() };request.cancel()
        try {
            withTimeout(5_000) { unwinding.await() }
            assertFalse(request.isCompleted)
            rejected { runtime.acquire(access()) }
        } finally { release.complete(Unit);request.join() }
        assertFalse(db.inTransaction());rejected { runtime.acquire(access()) };assertEquals(1,builds)
        originalDatabase();assertEquals(0,count("next_request_origins"));assertEquals(0,count("sync_outbox"))
    }
    @Test fun runtimeRejectsDifferentDeviceAndPermissionBindingBeforeStarting()=runBlocking<Unit> {
        setupInteraction();val current=access();val before=decryptCount.get()
        rejected { runtime.acquire(current.copy(deviceId=id(2701))) }
        rejected { runtime.acquire(current.copy(capabilityRevision=current.capabilityRevision!!+1)) }
        assertEquals(0,builds);assertEquals(before,decryptCount.get())
        assertTrue(adapter.open().valid());assertEquals(1,builds)
    }
    @Test fun revokedWarmRuntimeCannotAuthorizeOrPublishAnOldAccount()=runBlocking<Unit> {
        setupInteraction();val old=adapter.open();assertTrue(old.valid())
        tokens.saveDeviceRegistration(id(4),setOf("sync.read"),true,2)
        assertFalse(old.valid())
        rejected { runtime.acquire(access()) };rejected { adapter.refresh() };assertEquals(1,builds)
    }
    @Test fun closedOwnerCannotStartColdWork()=runBlocking<Unit> {
        setupInteraction();operationOwner.coroutineContext.job.cancelAndJoin()
        try { runtime.acquire(access());fail("Cancelled owner accepted work") }
        catch(expected:CancellationException) { assertTrue(operationOwner.coroutineContext.job.isCancelled) }
        assertEquals(0,builds)
    }
    @Test fun readonlyConsumerTracksCommittedSaveAndAckWithoutRebuilding()=runBlocking<Unit> {
        setupInteraction()
        val summary=G0InteractionSession(additionalAdapter(),operationOwner,editable=false)
        suspend fun summaryState(name:String,pending:Int)=withTimeout(5_000) { summary.state.first {
            it.phase==G0InteractionSession.Phase.VIEWING && it.renderable() &&
                it.publication?.value?.name==name && it.publication.value.pending==pending
        } }
        try {
            withContext(Dispatchers.Main) { interaction.open();summary.open() }
            phase(G0InteractionSession.Phase.EDITING)
            withTimeout(5_000) { summary.state.first { it.phase==G0InteractionSession.Phase.VIEWING } }
            interaction.awaitSettled();summary.awaitSettled();assertEquals(1,builds)
            val held=summary.state.value
            withContext(Dispatchers.Main) { summary.draft("Forbidden edit","");assertFalse(summary.confirm()) }
            assertEquals(held,summary.state.value)
            draft("Shared committed value")
            withContext(Dispatchers.Main) { assertTrue(interaction.confirm()) }
            phase(G0InteractionSession.Phase.SAVED);summaryState("Shared committed value",1)
            interaction.awaitSettled();summary.awaitSettled();assertFalse(held.renderable())
            withContext(Dispatchers.Main) { assertTrue(interaction.sync()) }
            phase(G0InteractionSession.Phase.SYNCED);summaryState("Shared committed value",0)
            interaction.awaitSettled();summary.awaitSettled();assertEquals(1,builds)
        } finally { withContext(Dispatchers.Main) { summary.detach() };summary.awaitSettled() }
        originalDatabase();assertEquals(1,count("next_request_origins"));assertEquals(1,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
    }
    @Test fun terminalInspectionWaitsForOldReadCleanupBeforeClosingRoom()=runBlocking<Unit> {
        setupInteraction();val previous=db
        val entered=CompletableDeferred<Unit>();val unwinding=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        adapter.afterReadForTest={
            entered.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) { unwinding.complete(Unit);release.await() } }
        }
        withContext(Dispatchers.Main) { interaction.open() };withTimeout(5_000) { entered.await() }
        val inspection=async(Dispatchers.IO) { originalDatabase() }
        try {
            withTimeout(5_000) { unwinding.await() }
            assertFalse(inspection.isCompleted);assertTrue(previous.isOpen)
        } finally { release.complete(Unit);inspection.await() }
        assertFalse(previous.isOpen);assertTrue(operationOwner.coroutineContext.job.isCancelled)
        assertEquals(0,count("sync_outbox"));assertEquals(0,count("next_request_origins"))
    }

}
