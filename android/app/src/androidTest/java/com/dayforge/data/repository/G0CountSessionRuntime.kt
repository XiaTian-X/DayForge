package com.dayforge.data.repository

import android.os.SystemClock
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.local.TokenManager
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** A single G0 count/connection lifetime, started by an actual page open. Reusing the
 * continuously sealed store never reuses authentication or a page's publication.
 * This is still the current-day, single-object, <=128-fact profile, not cold recovery
 * for an outstanding transmission or a process-persistent background service. */
internal class G0CountSessionRuntime(
    private val control:CoroutineScope,
    private val connection:G0CountConnectionOwner,
    private val authority:G0AuthorityOwner,
    private val tokens:TokenManager,
    private val changes:()->StateFlow<Long>,
    private val http:NextSyncHttp,
    private val createStore:suspend ()->G0ShortCountStore
) {
    enum class Phase { NEW, BUSY, LOADING, OPEN, LEAVING, AWAY, RECOVERY, CLOSED }
    data class State(val version:Long,val phase:Phase,val page:G0ShortCountPage?=null,
        val publishedAt:Long=SystemClock.elapsedRealtimeNanos())
    private data class PageSlot(val page:G0ShortCountPage,val scope:CoroutineScope)
    private class Opening {
        var abandoned=false
        var slot:PageSlot?=null
    }
    private val guard=Any()
    private val mutable=MutableStateFlow(State(0,Phase.NEW))
    val state=mutable.asStateFlow()
    val events=CopyOnWriteArrayList<State>()
    val openTraces=CopyOnWriteArrayList<G0PhaseTrace>()
    private val syncChanges=MutableStateFlow(0L)
    private val wake=Channel<Unit>(Channel.CONFLATED)
    private val pending=linkedSetOf<String>()
    private val workers=CopyOnWriteArrayList<G0ShortCountSyncWorker>()
    private val observedFailures=CopyOnWriteArrayList<Throwable>()
    private var started=false
    @Volatile private var closed=false
    @Volatile private var lease:G0AuthorityOwner.Lease?=null
    private var lifetime:Job?=null
    private var workScope:CoroutineScope?=null
    private var transition:Job?=null
    private var store:G0ShortCountStore?=null
    private var owned:PageSlot?=null
    private var retiring:PageSlot?=null
    private var opening:Opening?=null
    private var deliveredPage=false
    private var reopenRequested=false
    private var activeWorker:G0ShortCountSyncWorker?=null
    private var batchInProgress=false
    private var subscription:AutoCloseable?=null
    @Volatile var retiredPage:G0ShortCountPage?=null;private set
    @Volatile var error:Throwable?=null;private set
    @Volatile var drained=false;private set
    @Volatile var drainedAt=0L;private set
    @Volatile var coldStarts=0;private set
    val pendingCount:Int get()=synchronized(guard) {pending.size}
    val knownPending:Set<String> get()=synchronized(guard) {pending.toSet()}
    val workerAttempts:List<G0ShortCountSyncWorker.Attempt> get()=workers.flatMap {it.attempts.toList()}
    var beforePublicationForTest:suspend ()->Unit={}
    var beforeSyncBatchForTest:suspend (List<String>)->Unit={}
    var beforeSendForTest:suspend (G0ShortCountSyncWorker.Attempt)->Unit={}
    var beforeAcceptForTest:suspend (G0ShortCountSyncWorker.Attempt)->Unit={}

    init {
        val registered=authority.observeReservations {retire(null)}
        synchronized(guard) {if(closed)registered.close() else subscription=registered}
    }

    private fun emit(phase:Phase,page:G0ShortCountPage?=null) {
        val next=State(mutable.value.version+1,phase,page);events+=next;mutable.value=next
    }
    private fun syncChanged() {syncChanges.value=Math.addExact(syncChanges.value,1L)}

    /** OPEN means a fresh page owner exists. A concurrent ACK can invalidate its view;
     * only this predicate plus the page's original draw guards admit personal pixels. */
    fun renderable():Boolean {
        val current=state.value
        return !closed && current.phase==Phase.OPEN &&
            runCatching {authority.checkLease(requireNotNull(lease))}.isSuccess &&
            current.page?.let {it.live() && it.state.value.view?.valid()!=false}==true
    }

    /** Only the installed page collects this read-only wake hint. It invalidates
     * cached drawing; neither its value nor receiving it grants display authority. */
    internal fun displayChanges():StateFlow<Long> = changes()

    @OptIn(ExperimentalCoroutinesApi::class,DelicateCoroutinesApi::class)
    fun openPage():Boolean=synchronized(guard) {
        if(closed || !control.isActive)return false
        if(retiring!=null || opening?.abandoned==true) {
            if(reopenRequested)return false
            reopenRequested=true;emit(Phase.LOADING);return true
        }
        if(started) {
            if(mutable.value.phase!=Phase.AWAY)return false
            val intent=Opening();opening=intent
            emit(Phase.LOADING)
            val scope=requireNotNull(workScope);val current=requireNotNull(store)
            transition=scope.launch(Dispatchers.IO,start=CoroutineStart.LAZY) {
                try {openFreshPage(current,intent)} catch(failure:Throwable) {failedWork(failure)}
            }.also {it.start()}
            return true
        }
        val ticket=connection.acquire() ?: run {emit(Phase.BUSY);return false}
        // The lifetime is NOT a child of this supervisor: its finally may join every
        // worker, page and transition without joining itself.
        val scope=CoroutineScope(SupervisorJob(control.coroutineContext.job)+Dispatchers.IO)
        val intent=Opening();opening=intent
        workScope=scope;started=true
        lifetime=control.launch(Dispatchers.IO,start=CoroutineStart.ATOMIC) {
            try {
                currentCoroutineContext().ensureActive()
                synchronized(guard) {
                    if(closed)return@launch
                    transition=scope.launch(Dispatchers.IO,start=CoroutineStart.LAZY) {
                        try {
                            val original=authority.lease();lease=original
                            currentCoroutineContext().ensureActive();coldStarts++
                            val current=createStore()
                            currentCoroutineContext().ensureActive();authority.checkLease(original)
                            val discovered=current.pendingRequestIds()
                            currentCoroutineContext().ensureActive();authority.checkLease(original)
                            synchronized(guard) {
                                check(!closed);store=current
                                discovered.forEach {remember(it)}
                            }
                            openFreshPage(current,intent)
                            startSyncActor(current,scope)
                        } catch(failure:Throwable) {failedWork(failure)}
                    }.also {it.start()}
                }
                awaitCancellation()
            } catch(cancel:CancellationException) {
                retire(null);throw cancel
            } finally {
                withContext(NonCancellable) {
                    // Closing the UI is not evidence that a SQLite transaction or
                    // socket has stopped. Join the actual owners before closing Room.
                    val pages=synchronized(guard) {listOfNotNull(owned,retiring,opening?.slot).distinct()}
                    pages.forEach {it.page.close()}
                    try {
                        scope.coroutineContext.job.cancelAndJoin()
                        ticket.drain()
                        drainedAt=SystemClock.elapsedRealtimeNanos();drained=true
                    } catch(failure:Throwable) {rememberFailure(failure)}
                    synchronized(guard) {
                        finishFailureDiagnostics()
                        subscription?.close();subscription=null;syncChanged()
                    }
                }
            }
        }
        // Publish only after shutdown can observe/join the ATOMIC lifetime. The
        // lifetime needs guard before it starts cold work, so it cannot overtake this.
        emit(Phase.LOADING)
        true
    }

    private suspend fun openFreshPage(current:G0ShortCountStore,intent:Opening) {
        if(synchronized(guard) {check(!closed && opening===intent);intent.abandoned}) {
            discardOpening(current,intent);return
        }
        currentCoroutineContext().ensureActive();authority.checkLease(requireNotNull(lease))
        val parent=synchronized(guard) {check(!closed);requireNotNull(workScope)}
        val child=CoroutineScope(SupervisorJob(parent.coroutineContext.job)+Dispatchers.IO)
        val page=G0ShortCountPage(current,tokens,child,onCommitted=::knownCommit)
        val slot=PageSlot(page,child)
        val abandoned=synchronized(guard) {
            check(!closed && opening===intent);check(owned==null && retiring==null)
            intent.slot=slot;intent.abandoned
        }
        if(abandoned) {discardOpening(current,intent);return}
        val trace=G0PhaseTrace(driverTiming=false);openTraces+=trace
        trace.run {page.open()}
        currentCoroutineContext().ensureActive();authority.checkLease(requireNotNull(lease))
        page.error?.let {throw it}
        val abandonedAfterRead=synchronized(guard) {
            check(!closed && opening===intent)
            if(!intent.abandoned)check(page.live() && page.state.value.phase==G0ShortCountPage.Phase.READY)
            intent.abandoned
        }
        if(abandonedAfterRead) {discardOpening(current,intent);return}
        beforePublicationForTest()
        currentCoroutineContext().ensureActive();authority.checkLease(requireNotNull(lease))
        page.error?.let {throw it}
        val installed=synchronized(guard) {
            check(!closed && opening===intent)
            if(intent.abandoned)false else {
                check(page.live());authority.checkLease(requireNotNull(lease))
                // No observer exists while a warm opening is still cancellable.
                // Cancellation then lets its one fresh read finish, rather than
                // cancelling a second observer read and invalidating the shared gate.
                page.watchChanges(changes())
                opening=null;owned=slot;deliveredPage=true;emit(Phase.OPEN,page);true
            }
        }
        if(!installed) {discardOpening(current,intent);return}
        // Detached work can fail without publishing a state; leavePage also inspects
        // error after settled. While attached, a RECOVERY state retires this runtime.
        child.launch {
            page.state.first {it.phase==G0ShortCountPage.Phase.RECOVERY}
            retire(page.error ?: IllegalStateException("G0_COUNT_SESSION_PAGE_RECOVERY"))
        }
    }

    private suspend fun discardOpening(current:G0ShortCountStore,intent:Opening) {
        val prior=synchronized(guard) {check(!closed && opening===intent);intent.slot}
        prior?.page?.detach()
        prior?.page?.settled()
        prior?.page?.error?.let {throw it}
        prior?.scope?.coroutineContext?.job?.cancelAndJoin()
        val next=synchronized(guard) {
            check(!closed && opening===intent)
            prior?.page?.let {retiredPage=it};opening=null
            if(reopenRequested) {
                reopenRequested=false;Opening().also {opening=it;emit(Phase.LOADING)}
            } else {emit(Phase.AWAY);null}
        }
        if(next!=null)openFreshPage(current,next)
    }

    /** Called synchronously after reliable COMMIT, before any result read/suspension.
     * It records an identity even if retirement raced with delivery; retirement never
     * turns an already committed fact into a retry or a claim that the outbox is empty. */
    private fun knownCommit(saved:G0ShortCountStore.Saved) {
        synchronized(guard) {remember(saved.requestId)}
        wake.trySend(Unit)
    }
    private fun remember(id:String) {
        if(id !in pending) {check(pending.size<128) {"G0_COUNT_SESSION_PENDING_CAPACITY"};pending+=id;syncChanged()}
    }

    private fun startSyncActor(current:G0ShortCountStore,scope:CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                for(ignored in wake) {
                    while(true) {
                        currentCoroutineContext().ensureActive();authority.checkLease(requireNotNull(lease))
                        val batch=synchronized(guard) {
                            check(!closed)
                            pending.take(8).also {if(it.isNotEmpty()) {batchInProgress=true;syncChanged()}}
                        }
                        if(batch.isEmpty())break
                        beforeSyncBatchForTest(batch.toList())
                        currentCoroutineContext().ensureActive();authority.checkLease(requireNotNull(lease))
                        val worker=G0ShortCountSyncWorker(current,http,scope)
                        worker.beforeSendForTest={beforeSendForTest(it)}
                        worker.beforeAcceptForTest={beforeAcceptForTest(it)}
                        synchronized(guard) {check(!closed);activeWorker=worker;workers+=worker;syncChanged()}
                        try {
                            batch.forEach {check(worker.offer(it)) {"G0_COUNT_SESSION_BATCH_REJECTED"}}
                            worker.finish()
                            currentCoroutineContext().ensureActive();authority.checkLease(requireNotNull(lease))
                            synchronized(guard) {
                                check(!closed);batch.forEach {pending.remove(it)}
                                activeWorker=null;batchInProgress=false;syncChanged()
                            }
                        } finally {
                            withContext(NonCancellable) {worker.shutdown()}
                        }
                    }
                }
            } catch(failure:Throwable) {failedWork(failure)}
        }
        wake.trySend(Unit)
    }

    /** UI departure alone does not cancel an already accepted command or a network
     * batch. Only one requested reopen can wait behind the old page's actual drain. */
    fun leavePage() {
        var abandon=false
        var old:PageSlot?=null
        var privatePage:G0ShortCountPage?=null
        var next:Job?=null
        synchronized(guard) {
            if(closed)return
            if(mutable.value.phase==Phase.LOADING) {
                if(retiring!=null) {
                    // Cancel only the queued next-page intent, not the prior command.
                    reopenRequested=false;emit(Phase.LEAVING)
                } else if(!deliveredPage)abandon=true
                else {
                    val intent=requireNotNull(opening)
                    intent.abandoned=true;reopenRequested=false;privatePage=intent.slot?.page
                    emit(Phase.LEAVING)
                }
            }
            else if(mutable.value.phase==Phase.OPEN) {
                val prior=requireNotNull(owned);old=prior;owned=null;retiring=prior
                retiredPage=prior.page;emit(Phase.LEAVING)
                val scope=requireNotNull(workScope)
                next=scope.launch(Dispatchers.IO,start=CoroutineStart.LAZY) {
                    try {
                        prior.page.settled()
                        prior.page.error?.let {throw it}
                        prior.scope.coroutineContext.job.cancelAndJoin()
                        val reopen=synchronized(guard) {
                            check(!closed && retiring===prior);retiring=null
                            if(reopenRequested) {
                                reopenRequested=false;Opening().also {opening=it;emit(Phase.LOADING)}
                            } else {emit(Phase.AWAY);null}
                        }
                        if(reopen!=null)openFreshPage(requireNotNull(store),reopen)
                    } catch(failure:Throwable) {failedWork(failure)}
                }
                transition=next
            }
        }
        if(abandon)retire(null)
        old?.page?.detach()
        privatePage?.detach()
        next?.start()
    }

    internal suspend fun awaitPageTransition() {synchronized(guard) {transition}?.join()}

    /** Only the currently known identities and active batch, never a claim that an
     * arbitrary persistent outbox has been exhaustively drained across restarts. */
    suspend fun awaitSynced() {
        syncChanges.first {
            synchronized(guard) {
                error?.let {throw it}
                check(!closed && store!=null) {"G0_COUNT_SESSION_NOT_READY"}
                pending.isEmpty() && !batchInProgress && activeWorker==null
            }
        }
    }

    private fun rememberFailure(failure:Throwable)=synchronized(guard) {
        if(observedFailures.none {it===failure})observedFailures+=failure
        if(error==null)error=failure
    }
    /** A failed sync may invalidate a view before its worker unwinds. After the actual
     * jobs have joined, prefer that worker's recorded non-cancellation exception to
     * a derived read refusal. No expected fault name or timing trace supplies a cause. */
    private fun finishFailureDiagnostics() {
        val syncFailures=workers.mapNotNull {it.failure?.takeUnless {failure->failure is CancellationException}}
        syncFailures.forEach {rememberFailure(it)}
        val primary=syncFailures.firstOrNull() ?: error ?: return
        error=primary
        fun contains(root:Throwable,target:Throwable,seen:MutableSet<Throwable>):Boolean {
            if(root===target)return true
            if(!seen.add(root))return false
            return root.cause?.let {contains(it,target,seen)}==true || root.suppressed.any {contains(it,target,seen)}
        }
        observedFailures.forEach {failure->
            // Avoid creating an exception graph cycle if an upstream API already
            // retained the primary exception in this secondary failure.
            if(failure!==primary && !contains(primary,failure,mutableSetOf()) &&
                !contains(failure,primary,mutableSetOf()))primary.addSuppressed(failure)
        }
    }
    private fun failedWork(failure:Throwable) {
        if(failure is CancellationException) {
            if(!closed)retire(failure)
            throw failure
        }
        retire(failure)
    }

    /** Reservation callback: only retire memory and cancel jobs; never join, decrypt,
     * acquire the account mutex, or close SQLite from a DataStore mutation callback. */
    private fun retire(failure:Throwable?) {
        var pages:List<PageSlot> = emptyList()
        var work:Job?=null;var life:Job?=null
        synchronized(guard) {
            if(failure!=null)rememberFailure(failure)
            if(closed)return
            closed=true;reopenRequested=false
            pages=listOfNotNull(owned,retiring,opening?.slot).distinct()
            (owned?.page ?: opening?.slot?.page)?.let {retiredPage=it}
            emit(if(failure==null)Phase.CLOSED else Phase.RECOVERY)
            subscription?.close();subscription=null
            work=workScope?.coroutineContext?.job;life=lifetime
            wake.close();syncChanged()
            if(!started) {drained=true;drainedAt=SystemClock.elapsedRealtimeNanos()}
        }
        // Avoid entry-guard/page-monitor inversion with a synchronous commit handoff.
        pages.forEach {it.page.close()}
        work?.cancel();life?.cancel()
    }

    suspend fun shutdown():Boolean {
        retire(null)
        withContext(NonCancellable) {synchronized(guard) {lifetime}?.join()}
        return drained
    }
}
