package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.ZonedDateTime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0RecurringInteractionTest:G0InteractionFixture() {
    override val admitRecurringReads=true
    private lateinit var recurring:G0RecurringInteractionSession
    private lateinit var readAdapter:G0RecurringInteractionAdapter
    private lateinit var ids:List<Long>
    override fun installScreen() {
        val reader=G0RecurringReadOwner(db,tokens,sessions,authority,domainGate,sharedScope=true,reuseEvidence=true)
        readAdapter=runBlocking { G0RecurringInteractionAdapter(tokens,access(),domainGate,authority,runtime,reader,ids,operationOwner,app) }
        recurring=G0RecurringInteractionSession(readAdapter,operationOwner,interaction.state)
    }
    private suspend fun setupRecurring() { register();ids=seedPageFacts(2);setupInteraction() }
    private suspend fun ready(after:Long=0)=withTimeout(20_000) { recurring.state.first {
        it.version>after && it.phase==G0RecurringInteractionSession.Phase.READY && it.renderable()
    } }.also { assertEquals(5L,it.publication!!.content.counts.getValue(ids[0]).todayQuantity) }
    private suspend fun open() { withContext(Dispatchers.Main) { recurring.open() };ready();recurring.awaitSettled() }
    @After fun closeRecurring()=runBlocking<Unit> {
        if(::recurring.isInitialized) { recurring.detach();recurring.awaitSettled() }
    }
    @Test fun visibleConsumersShareColdAdmissionAndRealAckRefreshesBoth()=runBlocking<Unit> {
        setupRecurring();val before=pageNativeState()
        withContext(Dispatchers.Main) { recurring.open();interaction.open() }
        val initial=ready();phase(G0InteractionSession.Phase.EDITING)
        recurring.awaitSettled();interaction.awaitSettled();assertEquals(1,builds)
        draft("Mixed page saved");assertTrue(interaction.confirm());phase(G0InteractionSession.Phase.SAVED)
        val saved=ready(initial.version);assertFalse(initial.renderable())
        assertTrue(interaction.sync());phase(G0InteractionSession.Phase.SYNCED)
        ready(saved.version);recurring.awaitSettled();interaction.awaitSettled()
        assertEquals(1,builds);assertEquals(1,readAdapter.reader.fullReads);assertTrue(readAdapter.reader.reuseHits>=2)
        assertEquals(0,interaction.state.value.publication!!.value.pending)
        val after=pageNativeState()
        for(table in listOf("habits","completions","count_days","sync_outbox")) assertEquals(table,before[table],after[table])
    }
    @Test fun cancelLastColdReaderWaitsForCleanupAndNeverPublishes()=runBlocking<Unit> {
        setupRecurring();val before=pageNativeState();val entered=CompletableDeferred<Unit>();val unwinding=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        configureStoreForTest={ store -> store.afterCheckpointMatchForTest={
            entered.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) { unwinding.complete(Unit);release.await() } }
        } }
        recurring.open();withTimeout(10_000) { entered.await() };recurring.cancel()
        val settled=async { recurring.awaitSettled() }
        try { withTimeout(5_000) { unwinding.await() };assertFalse(settled.isCompleted);assertNull(recurring.state.value.publication) }
        finally { release.complete(Unit);settled.await() }
        assertEquals(G0RecurringInteractionSession.Phase.CANCELLED,recurring.state.value.phase)
        assertFalse(db.inTransaction());rejected { runtime.acquire(access()) };assertEquals(before,pageNativeState())
    }
    @Test fun cancelledColdReaderDoesNotCancelMetricSubscriber()=runBlocking<Unit> {
        setupRecurring();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        beforeInitialization={ entered.complete(Unit);release.await() }
        recurring.open();entered.await();interaction.open()
        withTimeout(5_000) { while(runtime.waitersForTest()!=2) delay(5) }
        try { recurring.cancel();recurring.awaitSettled();assertEquals(1,runtime.waitersForTest()) }
        finally { release.complete(Unit) }
        phase(G0InteractionSession.Phase.EDITING);interaction.awaitSettled()
        assertNull(recurring.state.value.publication);open();assertEquals(1,builds)
    }
    @Test fun cancelAfterReadRejectsLatePublicationAndReopenAuthenticatesAgain()=runBlocking<Unit> {
        setupRecurring();open();val old=recurring.state.value
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        readAdapter.afterReadForTest={ entered.complete(Unit);release.await() }
        recurring.open();entered.await();recurring.cancel();release.complete(Unit);recurring.awaitSettled()
        assertFalse(old.renderable());assertNull(recurring.state.value.publication)
        readAdapter.afterReadForTest={};val count=decryptCount.get();open()
        assertEquals(4,decryptCount.get()-count);assertEquals(1,builds)
    }
    @Test fun authorityReservationRevokesPaintAndCannotRepublish()=runBlocking<Unit> {
        setupRecurring();open();val old=recurring.state.value
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        authority.onTransitionForTest={ entered.complete(Unit);release.await() }
        val changing=async(Dispatchers.IO) { tokens.saveDeviceRegistration(id(4),setOf("sync.read"),true,2) }
        try { entered.await();assertFalse(old.renderable());assertNull(recurring.state.value.publication) }
        finally { release.complete(Unit);changing.await() }
        recurring.awaitSettled();assertEquals(G0RecurringInteractionSession.Phase.RECOVERY,recurring.state.value.phase)
        recurring.open();recurring.awaitSettled();assertNull(recurring.state.value.publication)
    }
    @Test fun clockChangeCannotRelabelOldDayAsCurrent()=runBlocking<Unit> {
        setupRecurring();open();val old=recurring.state.value;val later=ZonedDateTime.now().plusDays(1)
        readAdapter.nowForTest={later};readAdapter.calendarChanged(recurring::changed)
        assertFalse(old.renderable())
        val fresh=withTimeout(10_000) { recurring.state.first { it.phase==G0RecurringInteractionSession.Phase.READY && it.version>old.version } }
        assertTrue(fresh.renderable());assertEquals(later.toLocalDate(),fresh.publication!!.content.counts.getValue(ids[0]).today)
        assertEquals(0L,fresh.publication.content.counts.getValue(ids[0]).todayQuantity)
        assertEquals(2,readAdapter.reader.fullReads)
    }
    @Test fun changeDuringPublicationIsNotLostAndUnknownCommitCannotDisplay()=runBlocking<Unit> {
        setupRecurring();open();val held=recurring.state.value
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var first=true
        readAdapter.afterReadForTest={ if(first) { first=false;entered.complete(Unit);release.await() } }
        recurring.open();entered.await()
        try { withContext(Dispatchers.IO) { activeStore.edit("Changed during publication","",id(8401)) } }
        finally { release.complete(Unit) }
        ready(held.version);recurring.awaitSettled();assertFalse(held.renderable())
        val fresh=recurring.state.value
        activeStore.beforeFinalForTest={ endFaults.failAfterNextEnd.set(true) }
        rejected { withContext(Dispatchers.IO) { activeStore.edit("Unknown result","",id(8402)) } }
        assertFalse(fresh.renderable());recurring.changed();recurring.awaitSettled()
        assertEquals(G0RecurringInteractionSession.Phase.RECOVERY,recurring.state.value.phase)
        assertNull(recurring.state.value.publication)
    }
}
