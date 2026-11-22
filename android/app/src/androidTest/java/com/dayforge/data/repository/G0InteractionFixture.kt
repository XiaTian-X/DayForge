package com.dayforge.data.repository

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.After

abstract class G0InteractionFixture : G0SealedDomainFixture() {
    override fun domainOpeningCallback() = G0SchemaOpeningCallback(true)
    internal val operationOwner = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    internal lateinit var interaction:G0InteractionSession
    internal lateinit var interactionHttp:com.dayforge.data.api.NextSyncHttp
    internal lateinit var runtime:G0DeviceBoundMetricRuntime
    internal lateinit var adapter:G0MetricInteractionAdapter
    internal lateinit var activeStore:G0ShortMetricStore
    internal val observation=G0ScreenObservation()
    internal var beforeInitialization:suspend ()->Unit = {}
    internal var builds=0
    internal open val admitRecurringReads=false
    internal open val retainRecurringResults=false
    internal var configureStoreForTest:(G0ShortMetricStore)->Unit = {}
    internal suspend fun setupInteraction() {
        register()
        val (http,_)=channel { input -> successReply(input,revision=(if(input.path.endsWith("/identity")) 0L else
            wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0L)+1) }
        interactionHttp=http
        val current=access()
        runtime=G0DeviceBoundMetricRuntime(current,operationOwner) {
            beforeInitialization();builds++
            G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid,prepareColdInputs=false,admitRecurringReads=admitRecurringReads,retainRecurringResults=retainRecurringResults).also {
                configureStoreForTest(it)
                it.initialize(current,trustedDomainSchema(true));activeStore=it
            }
        }
        adapter=G0MetricInteractionAdapter(tokens,current,authority,domainGate,http,operationOwner,runtime)
        interaction=G0InteractionSession(adapter,operationOwner)
        installScreen()
    }
    internal suspend fun additionalAdapter()=G0MetricInteractionAdapter(tokens,access(),authority,domainGate,interactionHttp,operationOwner,runtime)
    internal abstract fun installScreen()
    internal suspend fun phase(phase:G0InteractionSession.Phase)=withTimeout(20_000) {
        interaction.state.first { it.phase==phase }
    }
    internal suspend fun openReady() {
        withContext(Dispatchers.Main) { interaction.open() }
        phase(G0InteractionSession.Phase.EDITING);interaction.awaitSettled()
    }
    internal suspend fun draft(value:String) = withContext(Dispatchers.Main) { interaction.draft(value,"P1 description") }
    internal suspend fun originalDatabase() {
        stopOwnedWork()
        storage.isolatedBuilder=null;storage.reopen()
    }
    @After fun stopInteraction() = runBlocking<Unit> { stopOwnedWork() }
    private suspend fun stopOwnedWork() {
        if(::interaction.isInitialized) {
            withContext(Dispatchers.Main) { interaction.detach() }
            withTimeout(20_000) { interaction.awaitSettled() }
        }
        operationOwner.coroutineContext.job.cancelAndJoin()
    }
}
