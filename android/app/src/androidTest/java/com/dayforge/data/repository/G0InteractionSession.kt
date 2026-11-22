package com.dayforge.data.repository

import android.os.SystemClock
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.local.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** P1-only typed boundary. The screen never receives a database, authority or write callback. */
internal interface G0InteractionPort {
    data class Publication(val value: G0ShortMetricStore.View, val valid: () -> Boolean)
    suspend fun open(): Publication
    data class Saved(val valid: () -> Boolean)
    suspend fun save(name: String, description: String, command: String): Saved
    suspend fun refresh(): Publication
    suspend fun sync(): Publication
    class StalePublication : IllegalStateException("G0_INTERACTION_STALE_PUBLICATION")
    fun closePresentation()
    fun observeContentChanges(changed: () -> Unit)
    fun observeInvalidation(clear: () -> Unit)
}

internal class G0MetricInteractionAdapter(
    private val tokens: TokenManager,
    private val access: LocalSyncAccess,
    private val authority: G0AuthorityOwner,
    private val gate: G0DomainSqlGate,
    private val http: NextSyncHttp,
    private val owner: CoroutineScope,
    private val runtime:G0DeviceBoundMetricRuntime
) : G0InteractionPort {
    data class Span(val stage:String,val started:Long,val ended:Long,val completed:Boolean)
    val spans=CopyOnWriteArrayList<Span>()
    var traceForTest:G0PhaseTrace?=null
    private suspend fun <T> measured(stage:String,block:suspend ()->T):T {
        val start=SystemClock.elapsedRealtimeNanos()
        var completed=false
        try {
            val trace=traceForTest
            return (if(trace==null) block() else trace.run(block)).also { completed=true }
        }
        finally { spans+=Span(stage,start,SystemClock.elapsedRealtimeNanos(),completed) }
    }
    private val initialization = Mutex()
    private var attached=false
    private val observers=mutableListOf<Job>()
    private var presentationClosed=false
    private var authorityObservation:AutoCloseable?=null
    private val displayRevision = AtomicLong()
    @Volatile private var clear: (() -> Unit)? = null
    var beforeReadForTest: suspend () -> Unit = {}
    var afterReadForTest: suspend () -> Unit = {}
    var afterSyncPlanForTest: suspend () -> Unit = {}
    private val invalidator = object : PersonalDataDisplayMemory.Listener {
        override fun transition(blocked: Boolean) { displayRevision.incrementAndGet(); clear?.invoke() }
    }
    override fun observeInvalidation(clear: () -> Unit) {
        check(!presentationClosed && this.clear == null)
        this.clear = clear
        tokens.registerPersonalDisplay(invalidator)
        authorityObservation=authority.observeReservations {
            displayRevision.incrementAndGet();this.clear?.invoke()
        }
        observers+=owner.launch {
            tokens.personalDisplayChanges.drop(1).collect { displayRevision.incrementAndGet(); clear() }
        }
    }
    override fun observeContentChanges(changed: () -> Unit) {
        val baseline=gate.changeSequence.value
        check(!presentationClosed)
        observers+=owner.launch { gate.changeSequence.collect { if(it!=baseline) changed() } }
    }
    override fun closePresentation() {
        presentationClosed=true;clear=null;observers.forEach { it.cancel() }
        authorityObservation?.close();authorityObservation=null
    }
    private suspend fun currentStore():G0ShortMetricStore = initialization.withLock {
        if(attached) runtime.acquire(access)
        else measured("initialize") { runtime.acquire(access) }.also { attached=true }
    }
    override suspend fun open(): G0InteractionPort.Publication = withContext(Dispatchers.IO) {
        currentStore(); refresh()
    }
    override suspend fun refresh(): G0InteractionPort.Publication = withContext(Dispatchers.IO) {
        val active = currentStore()
        beforeReadForTest()
        val revision = displayRevision.get()
        val lease = authority.lease()
        val databaseRevision = gate.revision
        val value = measured("read") { active.read(access) } // Original entry AND final fresh authentication.
        afterReadForTest()
        val valid = {
            try {
                authority.checkLease(lease); gate.requireSealedConnection()
                revision == displayRevision.get() && databaseRevision == gate.revision
            } catch (_: IllegalStateException) { false }
        }
        if(!valid()) {
            authority.checkLease(lease);gate.requireSealedConnection()
            check(revision==displayRevision.get()) { "G0_INTERACTION_AUTHORITY_CHANGED" }
            throw G0InteractionPort.StalePublication()
        }
        G0InteractionPort.Publication(value, valid)
    }
    override suspend fun save(name: String, description: String, command: String): G0InteractionPort.Saved = withContext(Dispatchers.IO) {
        val active = currentStore()
        val lease = authority.lease()
        val revision = displayRevision.get()
        measured("edit") { active.edit(name, description, command) }
        // A presentation fence, never authorization for a later operation or reuse of credentials.
        G0InteractionPort.Saved {
            try {
                authority.checkLease(lease);gate.requireSealedConnection()
                revision==displayRevision.get()
            } catch (_: IllegalStateException) { false }
        }
    }
    override suspend fun sync(): G0InteractionPort.Publication = withContext(Dispatchers.IO) {
        val active = currentStore()
        val plan = measured("sync_plan") { active.pendingSyncPlan(access) }
        afterSyncPlanForTest()
        for (operation in plan) {
            val delivery = measured("send") { requireNotNull(active.send(http, access, operation)) }
            measured("ack") { check(active.acknowledge(delivery) == NextOperationAcceptance.COMMITTED) }
        }
        refresh().also { check(it.value.pending==0) { "G0_INTERACTION_PENDING_REMAINS" } }
    }
}

/** Main/UI calls and synchronous revocation share one short memory boundary, never SQL. */
internal class G0InteractionSession(private val port: G0InteractionPort, private val owner: CoroutineScope, private val editable:Boolean=true,
    private val deferBackgroundReadsDuring:StateFlow<State>?=null,
    private val resumeBackgroundReadsAfterCommit:Boolean=false) {
    enum class Phase { CLOSED, LOADING, REFRESHING, VIEWING, EDITING, SAVING, SAVED_REFRESHING, SAVED, SYNCING, SYNCED, SYNC_RECOVERY, CANCELLED, RECOVERY, SAVED_REFRESH_FAILED }
    data class State(val session: Long = 0, val version: Long = 0, val inputVersion: Long = 0,
        val phase: Phase = Phase.CLOSED, val name: String = "", val description: String = "",
        val publication: G0InteractionPort.Publication? = null, val presentationValid: (() -> Boolean)? = null) {
        fun canSync() = renderable() && (phase==Phase.SAVED || (phase==Phase.EDITING &&
            publication!=null && publication.value.pending>0 &&
            name==publication.value.name && description==publication.value.description))
        fun renderable() = presentationValid?.invoke()!=false &&
            (publication?.valid?.invoke() ?: (name.isEmpty() && description.isEmpty()))
    }
    data class Event(val kind: String, val session: Long, val version: Long, val at: Long)
    val events = CopyOnWriteArrayList<Event>()
    internal var deferBackgroundReadsForTest=true // Changed only between settled benchmark journeys.
    private val monitor = Any()
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var serial = 0L
    private var preparation: Job? = null
    private var operation: Job? = null
    private var handedOff = false
    private var closed = false
    init {
        require(deferBackgroundReadsDuring==null || !editable)
        port.observeInvalidation(::invalidate);port.observeContentChanges(::contentChanged)
    }
    private fun sessionPresentation(id:Long):()->Boolean = { synchronized(monitor) {
        !closed && mutable.value.session==id
    } }
    private suspend fun latestPublication():G0InteractionPort.Publication {
        try { return port.refresh() }
        catch (_:G0InteractionPort.StalePublication) { return port.refresh() } // At most one changed-snapshot retry.
    }
    private fun contentChanged() = synchronized(monitor) {
        val old=mutable.value
        if(closed || handedOff || old.phase !in setOf(Phase.EDITING,Phase.VIEWING,Phase.SAVED,Phase.SYNCED) ||
            old.publication==null || old.renderable()) return@synchronized
        val previous=preparation
        publish(State(session=old.session,inputVersion=old.inputVersion,phase=Phase.REFRESHING))
        preparation=owner.launch {
            try {
                previous?.join()
                deferBackgroundReadsDuring?.takeIf { deferBackgroundReadsForTest }?.let { foreground ->
                    val busy=if(resumeBackgroundReadsAfterCommit) setOf(Phase.SAVING) else setOf(Phase.SAVING,Phase.SAVED_REFRESHING)
                    if(foreground.value.phase in busy) {
                        event("refresh_deferred")
                        // No SQL/authority resource is held. One notification window cannot renew itself.
                        withTimeoutOrNull(500) { foreground.first { it.phase !in busy } }
                        event("refresh_resumed")
                    }
                }
                currentCoroutineContext().ensureActive()
                val fresh=latestPublication()
                synchronized(monitor) {
                    if(!closed && mutable.value.session==old.session && mutable.value.phase==Phase.REFRESHING) {
                        val sameInputBase=old.publication.value.name==fresh.value.name &&
                            old.publication.value.description==fresh.value.description
                        if(!fresh.valid() || (old.phase==Phase.EDITING && !sameInputBase))
                            publish(State(session=old.session,phase=Phase.RECOVERY))
                        else {
                            val editing=old.phase==Phase.EDITING
                            publish(old.copy(phase=if(editing) Phase.EDITING else if(old.phase==Phase.VIEWING) Phase.VIEWING else if(fresh.value.pending==0) Phase.SYNCED else Phase.SAVED,
                                name=if(editing) old.name else fresh.value.name,
                                description=if(editing) old.description else fresh.value.description,
                                publication=fresh,presentationValid=old.presentationValid ?: sessionPresentation(old.session)))
                        }
                    }
                }
            } catch(cancel:CancellationException) { throw cancel }
            catch(_:Exception) { synchronized(monitor) {
                if(!closed && mutable.value.session==old.session && mutable.value.phase==Phase.REFRESHING)
                    publish(State(session=old.session,phase=Phase.RECOVERY))
            } }
        }
    }
    private fun event(kind: String, session: Long = mutable.value.session) { val s = mutable.value; events += Event(kind,session,s.version,SystemClock.elapsedRealtimeNanos()) }
    private fun publish(next: State) { mutable.value = next.copy(version = ++serial); event("state_${next.phase}") }
    fun open() = synchronized(monitor) {
        if(closed || handedOff) return@synchronized
        val previous = preparation
        previous?.cancel()
        val id = mutable.value.session + 1
        publish(State(session=id,phase=Phase.LOADING)); event("open")
        preparation = owner.launch {
            try {
                previous?.join() // No fake release while old Room work is still unwinding.
                val result = port.open()
                synchronized(monitor) {
                    if(!closed && mutable.value.session==id && mutable.value.phase==Phase.LOADING ) {
                        if(!result.valid()) publish(State(session=id,phase=Phase.RECOVERY))
                        else publish(mutable.value.copy(phase=if(editable) Phase.EDITING else Phase.VIEWING,name=result.value.name,
                            description=result.value.description,publication=result,presentationValid=sessionPresentation(id)))
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { synchronized(monitor) {
                if(mutable.value.session==id && mutable.value.phase==Phase.LOADING) publish(State(session=id,phase=Phase.RECOVERY))
            } }
        }
    }
    fun draft(name: String, description: String) = synchronized(monitor) {
        val s = mutable.value
        if(closed || handedOff || s.phase!=Phase.EDITING || !s.renderable()) return@synchronized
        publish(s.copy(name=name,description=description,inputVersion=s.inputVersion+1))
    }
    fun confirm(): Boolean = synchronized(monitor) {
        val s = mutable.value
        if(closed || handedOff || s.phase!=Phase.EDITING || !s.renderable()) return@synchronized false
        handedOff = true // Conservative UI cancellation boundary, not a database commit claim.
        val command = UUID.randomUUID().toString()
        publish(State(session=s.session,inputVersion=s.inputVersion,phase=Phase.SAVING)); event("confirm")
        operation = owner.launch {
            var committed = false
            try {
                val receipt = port.save(s.name,s.description,command)
                committed = true
                synchronized(monitor) {
                    event("committed",s.session)
                    if(!closed && mutable.value.session==s.session && mutable.value.phase==Phase.SAVING) {
                        if(!receipt.valid()) publish(State(session=s.session,phase=Phase.RECOVERY))
                        else publish(State(session=s.session,inputVersion=s.inputVersion,phase=Phase.SAVED_REFRESHING,
                            presentationValid={ synchronized(monitor) {
                                !closed && mutable.value.session==s.session && receipt.valid()
                            } }))
                    }
                }
                val result = port.refresh()
                synchronized(monitor) {
                    if(!closed && mutable.value.session==s.session && mutable.value.phase==Phase.SAVED_REFRESHING ) {
                        if(!result.valid()) failure(s.session,true)
                        else publish(mutable.value.copy(phase=Phase.SAVED,name=result.value.name,
                            description=result.value.description,publication=result))
                    }
                }
            } catch (cancel: CancellationException) {
                synchronized(monitor) { failure(s.session,committed) }; throw cancel
            } catch (_: Exception) { synchronized(monitor) { failure(s.session,committed) } }
            finally { synchronized(monitor) { handedOff=false } }
        }
        true
    }
    private fun failure(id: Long, committed: Boolean) {
        event(if(committed) "refresh_failed" else "result_unresolved",id)
        if(!closed && mutable.value.session==id && mutable.value.phase in setOf(Phase.SAVING,Phase.SAVED_REFRESHING)) {
            val current=mutable.value
            val known=committed && current.renderable()
            publish(State(session=id,phase=if(known) Phase.SAVED_REFRESH_FAILED else Phase.RECOVERY,
                presentationValid=if(known) current.presentationValid else null))
        }
    }
    fun sync(): Boolean = synchronized(monitor) {
        val s=mutable.value
        if(closed || handedOff || !s.canSync()) return@synchronized false
        handedOff=true;publish(State(session=s.session,inputVersion=s.inputVersion,phase=Phase.SYNCING,
            presentationValid=s.presentationValid));event("sync")
        operation=owner.launch {
            try {
                val result=port.sync()
                synchronized(monitor) { if(!closed && mutable.value.session==s.session && mutable.value.phase==Phase.SYNCING ) {
                    if(!result.valid()) syncFailure(s.session)
                    else publish(mutable.value.copy(phase=Phase.SYNCED,name=result.value.name,description=result.value.description,publication=result)) } }
            } catch(cancel:CancellationException) { syncFailure(s.session);throw cancel }
            catch(_:Exception) { syncFailure(s.session) }
            finally { synchronized(monitor) { handedOff=false } }
        }
        true
    }
    private fun syncFailure(id:Long) = synchronized(monitor) {
        event("sync_result_unresolved",id)
        if(!closed && mutable.value.session==id && mutable.value.phase==Phase.SYNCING) {
            val current=mutable.value
            val known=current.renderable()
            publish(State(session=id,phase=if(known) Phase.SYNC_RECOVERY else Phase.RECOVERY,
                presentationValid=if(known) current.presentationValid else null))
        }
    }
    fun cancel(): Boolean = synchronized(monitor) {
        if(closed || handedOff) { event("cancel_not_admitted");return@synchronized false }
        preparation?.cancel()
        publish(State(session=mutable.value.session+1,phase=Phase.CANCELLED));event("cancel")
        true
    }
    private fun invalidate() = synchronized(monitor) {
        if(!closed) {
            preparation?.cancel()
            publish(State(session=mutable.value.session+1,phase=Phase.RECOVERY));event("invalidate")
        }
    }
    fun detach() = synchronized(monitor) {
        closed=true;preparation?.cancel();publish(State(session=mutable.value.session+1));event("detach");port.closePresentation()
        // The managed owner finishes a handed-off command; a page cannot cancel that owner.
    }
    suspend fun awaitSettled() { preparation?.join();operation?.join() }
}
