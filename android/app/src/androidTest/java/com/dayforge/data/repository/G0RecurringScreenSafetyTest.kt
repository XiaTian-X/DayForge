package com.dayforge.data.repository

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
open class G0RecurringScreenSafetyTest:G0InteractionFixture() {
    @get:Rule val activity=ActivityScenarioRule(ComponentActivity::class.java)
    override val admitRecurringReads=true
    private lateinit var ids:List<Long>
    private lateinit var recurring:G0RecurringInteractionSession
    private lateinit var readOwner:G0RecurringReadOwner
    private val seen=G0ScreenObservation()
    override fun installScreen() {
        val reader=G0RecurringReadOwner(db,tokens,sessions,authority,domainGate,sharedScope=true,reuseEvidence=true,coldSeed={activeStore.recurringReadSeed()})
        readOwner=reader
        val port=runBlocking { G0RecurringInteractionAdapter(tokens,access(),domainGate,authority,runtime,reader,ids,operationOwner,app) }
        recurring=G0RecurringInteractionSession(port,operationOwner,interaction.state)
        activity.scenario.onActivity { it.setContent { Column(Modifier.fillMaxSize()) {
            G0RecurringPane(recurring,seen)
            Box(Modifier.weight(1f)) { G0InteractionScreen(interaction,observation) }
        } } }
    }
    private suspend fun setupPage() { register();ids=seedPageFacts(2);setupInteraction();seen.await(recurring.state.value.version) }
    @After fun closeRecurring()=runBlocking<Unit> { if(::recurring.isInitialized) { recurring.detach();recurring.awaitSettled() } }
    @Test fun physicalCancelClearsPixelsBeforeLastColdCleanupFinishes()=runBlocking<Unit> {
        setupPage();val before=pageNativeState()
        val entered=CompletableDeferred<Unit>();val unwinding=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        configureStoreForTest={ store -> store.afterCheckpointMatchForTest={
            entered.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) { unwinding.complete(Unit);release.await() } }
        } }
        seen.tap("open");withTimeout(10_000) { entered.await() }
        val at=seen.tap("cancel")
        val cancelled=withTimeout(5_000) { recurring.state.first { it.phase==G0RecurringInteractionSession.Phase.CANCELLED } }
        val observed=seen.await(cancelled.version)
        val settled=async { recurring.awaitSettled() }
        try {
            unwinding.await();assertFalse(settled.isCompleted);assertNull(cancelled.publication)
            Log.i("G0RecurringCancel","{\"cancel_observed_ns\":${observed.observed-at},\"cleanup_still_pending\":true,\"exact_first_presentation\":false}")
        } finally { release.complete(Unit);settled.await() }
        assertEquals(before,pageNativeState());assertFalse(db.inTransaction())
    }
    @Test fun physicalCancelOfOneColdConsumerLetsOtherScreenBecomeEditable()=runBlocking<Unit> {
        setupPage();val before=pageNativeState();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        beforeInitialization={ entered.complete(Unit);release.await() }
        seen.tap("open");entered.await();observation.tap("open")
        try {
            withTimeout(5_000) { while(runtime.waitersForTest()!=2) delay(5) }
            seen.tap("cancel")
            val cancelled=withTimeout(5_000) { recurring.state.first { it.phase==G0RecurringInteractionSession.Phase.CANCELLED } }
            seen.await(cancelled.version);recurring.awaitSettled();assertEquals(1,runtime.waitersForTest())
        } finally { release.complete(Unit) }
        val editable=phase(G0InteractionSession.Phase.EDITING);observation.await(editable.version);interaction.awaitSettled()
        assertTrue(editable.renderable());assertEquals(1,builds);assertEquals(before,pageNativeState())
    }
    @Test fun originalFactsAppearAsVisibleTextAndJointPixels()=runBlocking<Unit> {
        setupPage();seen.tap("open");observation.tap("open")
        val metricState=phase(G0InteractionSession.Phase.EDITING)
        val facts=withTimeout(20_000) { recurring.state.first { it.phase==G0RecurringInteractionSession.Phase.READY && it.renderable() } }
        G0ScreenObservation.awaitTogether { listOf(
            G0ScreenObservation.Target(observation,metricState.version,metricState::renderable),
            G0ScreenObservation.Target(seen,facts.version,facts::renderable)) }
        requireVisiblePageText(setOf("计数今日：5","打卡今日：1"))
        assertEquals(5L,facts.publication!!.content.counts.getValue(ids[0]).todayQuantity)
        assertEquals(1L,facts.publication.content.checks.getValue(ids[1]).todayQuantity)
        assertEquals(1,builds)
        assertEquals(if(retainRecurringResults) 1 else 0,readOwner.coldHits)
        assertEquals(if(retainRecurringResults) 0 else 1,readOwner.fullReads)
    }

    @Test fun reservedAuthorityClearsBothRegionsBeforeCredentialPersistence()=runBlocking<Unit> {
        setupPage();seen.tap("open");observation.tap("open")
        val oldMetric=phase(G0InteractionSession.Phase.EDITING)
        val oldFacts=withTimeout(20_000) { recurring.state.first { it.phase==G0RecurringInteractionSession.Phase.READY && it.renderable() } }
        interaction.awaitSettled();recurring.awaitSettled()
        assertEquals(if(retainRecurringResults) 1 else 0,readOwner.coldHits)
        assertEquals(if(retainRecurringResults) 0 else 1,readOwner.fullReads)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        authority.onTransitionForTest={ entered.complete(Unit);release.await() }
        val mutation=async(Dispatchers.IO) { tokens.saveDeviceRegistration(id(4),setOf("sync.read"),true,2) }
        try {
            withTimeout(5_000) { entered.await() }
            assertFalse(oldMetric.renderable());assertFalse(oldFacts.renderable())
            assertNull("Metric region retained revoked content",interaction.state.value.publication)
            assertNull("Fact region retained revoked content",recurring.state.value.publication)
            val m=interaction.state.value;val r=recurring.state.value
            G0ScreenObservation.awaitTogether { listOf(G0ScreenObservation.Target(observation,m.version,m::renderable),G0ScreenObservation.Target(seen,r.version,r::renderable)) }
            requireVisiblePageText(setOf("需要重新核验","需要重新验证或恢复，请勿重复保存"))
            assertFalse(interaction.confirm());assertFalse(interaction.sync())
        } finally { release.complete(Unit);mutation.await() }
    }

}
