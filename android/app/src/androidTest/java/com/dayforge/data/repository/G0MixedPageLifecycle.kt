package com.dayforge.data.repository

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Trusted page owner. The renderer gets typed sessions only, never a connection factory. */
internal class G0MixedPageLifecycle(
    private val control:CoroutineScope,
    private val replaceConnection:suspend ()->Unit,
    private val closeConnection:suspend ()->Unit,
    private val create:suspend (CoroutineScope)->Page
) {
    class Page(val metric:G0InteractionSession,val facts:G0RecurringInteractionSession,private val owner:CoroutineScope) {
        fun detach() { metric.detach();facts.detach() }
        suspend fun drain() = withContext(NonCancellable) {
            detach()
            // A handed-off save/ACK finishes through its original durability/unknown-result rules.
            metric.awaitSettled();facts.awaitSettled();owner.coroutineContext.job.cancelAndJoin()
        }
        fun open() { metric.open();facts.open() }
    }
    enum class Phase { CLOSED, PREPARING, OPEN, RECOVERY }
    data class State(val generation:Long=0,val phase:Phase=Phase.CLOSED,val page:Page?=null)
    private val guard=Any()
    private val mutable=MutableStateFlow(State())
    val state=mutable.asStateFlow()
    private var closed=false
    private var busy=false
    private var owned:Page?=null
    private var retiring:Page?=null
    private var transition:Job?=null
    internal var failureForTest:Throwable?=null;private set
    /** Explicit page entry/recovery only. Duplicate taps cannot enqueue repeated reconnects. */
    fun reopen():Boolean=synchronized(guard) {
        if(closed || busy || !control.isActive) return@synchronized false
        busy=true
        val old=owned;retiring=old;owned=null;old?.detach()
        val generation=mutable.value.generation+1
        mutable.value=State(generation,Phase.PREPARING)
        transition=control.launch(start=CoroutineStart.LAZY) {
            var scope:CoroutineScope?=null;var candidate:Page?=null;var installed=false
            try {
                old?.drain()
                synchronized(guard) { if(retiring===old) retiring=null }
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.IO) { replaceConnection() }
                val nextOwner=CoroutineScope(SupervisorJob(control.coroutineContext.job)+Dispatchers.Main.immediate)
                scope=nextOwner
                withContext(Dispatchers.IO) { candidate=create(nextOwner) }
                synchronized(guard) {
                    if(!closed) {
                        owned=candidate;mutable.value=State(generation,Phase.OPEN,candidate);installed=true
                        candidate!!.open() // OPEN means sessions exist; their LOADING states remain honest.
                    }
                }
            } catch(cancel:CancellationException) { throw cancel }
            catch(failure:Exception) { failureForTest=failure;synchronized(guard) { if(!closed) mutable.value=State(generation,Phase.RECOVERY) } }
            finally {
                withContext(NonCancellable) {
                    try {
                        if(!installed) {
                            candidate?.drain();scope?.coroutineContext?.job?.cancelAndJoin()
                            withContext(Dispatchers.IO) { closeConnection() }
                        }
                    } finally { synchronized(guard) { busy=false } }
                }
            }
        }.also { it.start() }
        true
    }
    suspend fun awaitTransition() { synchronized(guard) { transition }?.join() }
    suspend fun shutdown() {
        val prior=synchronized(guard) {
            closed=true;owned?.detach();retiring?.detach();mutable.value=State(mutable.value.generation+1)
            transition
        }
        withContext(NonCancellable) {
            // Do not close Room while a previous replacement still owns cleanup.
            prior?.join()
            val old=synchronized(guard) { listOfNotNull(owned,retiring).distinct().also { owned=null;retiring=null } }
            old.forEach { it.drain() }
            withContext(Dispatchers.IO) { closeConnection() }
        }
    }
}
