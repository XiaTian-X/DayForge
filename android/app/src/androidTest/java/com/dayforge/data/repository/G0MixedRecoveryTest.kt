package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
open class G0MixedRecoveryTest:G0SealedDomainFixture() {
    @get:Rule val activity=ActivityScenarioRule(ComponentActivity::class.java)
    override fun domainOpeningCallback()=G0SchemaOpeningCallback(true)
    internal open val retainRecurringResults=false
    internal val control=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    internal lateinit var lifecycle:G0MixedPageLifecycle
    internal lateinit var ids:List<Long>
    internal lateinit var store:G0ShortMetricStore
    internal lateinit var metricAdapter:G0MetricInteractionAdapter
    internal lateinit var readAdapter:G0RecurringInteractionAdapter
    internal var metricSeen=G0ScreenObservation()
    internal var factsSeen=G0ScreenObservation()
    internal val controls=G0ScreenObservation()
    internal var builds=0
    internal var connections=0
    internal var closes=0
    internal var initialize:suspend (G0ShortMetricStore)->Unit={}
    internal var configureAdapter:(G0RecurringInteractionAdapter)->Unit={}
    internal var configureReader:(G0RecurringReadOwner)->Unit={}
    internal var beforeReplace:suspend ()->Unit={}
    internal suspend fun setupManaged(history:Int=2) {
        register();ids=seedPageFacts(history)
        val (http,_)=channel { input -> successReply(input,revision=(if(input.path.endsWith("/identity")) 0L else
            wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0L)+1) }
        lifecycle=G0MixedPageLifecycle(control,replaceConnection={
            beforeReplace();freshGateForColdOpen(recurringReads=true);storage.reopen();connections++
        },closeConnection={ closes++;db.close() }) { owner ->
            metricSeen=G0ScreenObservation();factsSeen=G0ScreenObservation()
            val bound=access();val connection=db;val gate=domainGate
            var boundStore:G0ShortMetricStore?=null
            val runtime=G0DeviceBoundMetricRuntime(bound,owner) {
                builds++
                G0ShortMetricStore(connection,tokens,sessions,authority,gate,metric.uuid,
                    prepareColdInputs=false,admitRecurringReads=true,retainRecurringResults=retainRecurringResults).also {
                    initialize(it);it.initialize(bound,trustedDomainSchema(true));store=it;boundStore=it
                }
            }
            metricAdapter=G0MetricInteractionAdapter(tokens,bound,authority,gate,http,owner,runtime)
            val metricSession=G0InteractionSession(metricAdapter,owner)
            val reader=G0RecurringReadOwner(connection,tokens,sessions,authority,gate,sharedScope=true,reuseEvidence=true,coldSeed={boundStore?.recurringReadSeed()})
            configureReader(reader)
            readAdapter=G0RecurringInteractionAdapter(tokens,bound,gate,authority,runtime,reader,ids,owner,app)
            configureAdapter(readAdapter)
            val facts=G0RecurringInteractionSession(readAdapter,owner,metricSession.state)
            G0MixedPageLifecycle.Page(metricSession,facts,owner)
        }
        activity.scenario.onActivity { it.setContent {
            val state=lifecycle.state.collectAsState().value
            MaterialTheme { Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Button(onClick={controls.clicked("recover");lifecycle.reopen()},
                    modifier=Modifier.onGloballyPositioned { controls.bounds["recover"]=it.boundsInWindow() }) { Text("打开或恢复页面") }
                val page=state.page
                if(page==null) Text(if(state.phase==G0MixedPageLifecycle.Phase.PREPARING) "正在清理并重新核验" else "页面未就绪")
                else {
                    G0RecurringPane(page.facts,factsSeen)
                    Box(Modifier.weight(1f)) { G0InteractionScreen(page.metric,metricSeen) }
                }
            } }
        } }
        withTimeout(5_000) { while(!controls.bounds.containsKey("recover")) delay(16) }
        requireVisiblePageText(setOf("打开或恢复页面"))
    }
    internal suspend fun page():G0MixedPageLifecycle.Page {
        val state=withTimeoutOrNull(15_000) { lifecycle.state.first { it.page!=null || it.phase==G0MixedPageLifecycle.Phase.RECOVERY } }
        return requireNotNull(state?.page) { "G0_PAGE_NOT_CREATED phase=${lifecycle.state.value.phase} clicks=${controls.clicks.size} cause=${lifecycle.failureForTest}" }
    }
    internal suspend fun ready():G0MixedPageLifecycle.Page {
        val p=page()
        withTimeout(20_000) { p.metric.state.first { it.phase==G0InteractionSession.Phase.EDITING && it.renderable() } }
        withTimeout(20_000) { p.facts.state.first { it.phase==G0RecurringInteractionSession.Phase.READY && it.renderable() } }
        p.metric.awaitSettled();p.facts.awaitSettled();return p
    }
    internal suspend fun pixels(p:G0MixedPageLifecycle.Page)=G0ScreenObservation.awaitTogether {
        val m=p.metric.state.value;val f=p.facts.state.value
        if(m.publication==null || f.publication==null) null else listOf(
            G0ScreenObservation.Target(metricSeen,m.version,m::renderable),G0ScreenObservation.Target(factsSeen,f.version,f::renderable))
    }
    @After fun cleanupManaged()=runBlocking<Unit> {
        if(::lifecycle.isInitialized) lifecycle.shutdown()
        control.coroutineContext.job.cancelAndJoin()
    }
    @Test fun lastColdCancellationCanRecoverThroughFreshConnectionAndBothScreens()=runBlocking<Unit> {
        setupManaged();val before=pageNativeState()
        val entered=CompletableDeferred<Unit>();val unwind=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        initialize={ s -> s.afterCheckpointMatchForTest={
            entered.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) { unwind.complete(Unit);release.await() } }
        } }
        controls.tap("recover");val old=page();entered.await();val oldConnection=db
        // Actual cancel controls for both subscribers, then actual recovery control.
        factsSeen.tap("cancel");metricSeen.tap("cancel");unwind.await()
        val generation=lifecycle.state.value.generation
        val started=controls.tap("recover")
        withTimeout(5_000) { lifecycle.state.first { it.generation>generation } }
        try {
            assertFalse(lifecycle.reopen());assertEquals(1,connections);assertTrue(oldConnection.isOpen)
            assertNull(lifecycle.state.value.page)
        } finally { initialize={};release.complete(Unit) }
        val recovered=ready();val seen=pixels(recovered)
        assertFalse(oldConnection.isOpen);assertNotSame(oldConnection,db);assertEquals(2,connections);assertEquals(2,builds)
        assertNull(old.metric.state.value.publication);assertNull(old.facts.state.value.publication)
        requireVisiblePageText(setOf("计数今日：5","打卡今日：1"));assertEquals(before,pageNativeState())
        Log.i("G0ManagedRecovery","{\"recovery_to_joint_observed_ns\":${seen.observed-started},\"fresh_connections\":$connections,\"exact_first_presentation\":false}")
    }
    @Test fun unknownSaveRecoversOnceAndCanSyncWithoutRepeatingBusinessWrite()=runBlocking<Unit> {
        setupManaged();controls.tap("recover");val p=ready();pixels(p)
        store.beforeFinalForTest={ endFaults.failAfterNextEnd.set(true) }
        withContext(Dispatchers.Main) { p.metric.draft("Unknown recovered","description");assertTrue(p.metric.confirm()) }
        withTimeout(10_000) { p.metric.state.first { it.phase==G0InteractionSession.Phase.RECOVERY } };p.metric.awaitSettled()
        val durable=pageNativeState();controls.tap("recover");val fresh=ready();pixels(fresh)
        assertEquals(durable,pageNativeState());assertEquals("Unknown recovered",fresh.metric.state.value.publication!!.value.name)
        assertEquals(1,fresh.metric.state.value.publication!!.value.pending)
        metricSeen.tap("sync")
        withTimeout(15_000) { fresh.metric.state.first { it.phase==G0InteractionSession.Phase.SYNCED } }
        fresh.metric.awaitSettled();fresh.facts.awaitSettled();pixels(fresh)
        assertEquals(0,fresh.metric.state.value.publication!!.value.pending)
        lifecycle.shutdown();storage.isolatedBuilder=null;storage.reopen()
        assertEquals(5,count("next_request_origins"));assertEquals(1,count("next_acceptances"));assertEquals(4,count("sync_outbox"))
    }
    @Test fun recoveryWaitsForHandedOffCommitAndDoesNotDuplicateIt()=runBlocking<Unit> {
        setupManaged();controls.tap("recover");val p=ready();pixels(p)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        store.beforeFinalForTest={ entered.complete(Unit);release.await() }
        withContext(Dispatchers.Main) { p.metric.draft("Committed across recovery","");assertTrue(p.metric.confirm()) }
        entered.await();val old=db
        val generation=lifecycle.state.value.generation;controls.tap("recover")
        withTimeout(5_000) { lifecycle.state.first { it.generation>generation } }
        try { assertEquals(1,connections);assertTrue(old.isOpen);assertNull(lifecycle.state.value.page) }
        finally { release.complete(Unit) }
        val fresh=ready();pixels(fresh);assertFalse(old.isOpen)
        assertEquals("Committed across recovery",fresh.metric.state.value.publication!!.value.name)
        assertEquals(1,fresh.metric.state.value.publication!!.value.pending)
        lifecycle.shutdown();storage.isolatedBuilder=null;storage.reopen();assertEquals(5,count("next_request_origins"));assertEquals(5,count("sync_outbox"))
    }
    @Test fun revokedCredentialsCannotBeReactivatedByRecoveryButton()=runBlocking<Unit> {
        setupManaged();controls.tap("recover");val p=ready();pixels(p)
        tokens.clearAuthenticationTokens();assertFalse(p.metric.confirm())
        val before=pageNativeState();val generation=lifecycle.state.value.generation;controls.tap("recover")
        withTimeout(5_000) { lifecycle.state.first { it.generation>generation } };lifecycle.awaitTransition()
        assertEquals(G0MixedPageLifecycle.Phase.RECOVERY,lifecycle.state.value.phase);assertNull(lifecycle.state.value.page)
        assertEquals(before,pageNativeState())
    }
    @Test fun outerCancellationCannotSkipPendingSqlCleanupOnShutdown()=runBlocking<Unit> {
        setupManaged();controls.tap("recover");val p=ready();pixels(p);val before=pageNativeState()
        val entered=CompletableDeferred<Unit>();val unwind=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        store.beforeFinalForTest={
            entered.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) { unwind.complete(Unit);release.await() } }
        }
        withContext(Dispatchers.Main) { p.metric.draft("Cancelled outer task","");assertTrue(p.metric.confirm()) }
        entered.await();control.cancel();unwind.await()
        assertFalse(lifecycle.reopen())
        val stopping=async { lifecycle.shutdown() }
        try {
            withTimeout(5_000) { lifecycle.state.first { it.phase==G0MixedPageLifecycle.Phase.CLOSED } }
            assertFalse(stopping.isCompleted);assertEquals(0,closes);assertTrue(db.isOpen)
        } finally { release.complete(Unit);stopping.await() }
        assertFalse(db.isOpen);assertEquals(before,pageNativeState())
    }

}
