package com.dayforge.data.repository

import android.content.Context
import android.os.SystemClock
import com.dayforge.data.local.PersonalDataDisplayMemory
import com.dayforge.data.local.TokenManager
import com.dayforge.data.model.HabitType
import com.dayforge.domain.service.afterCommittedAction
import com.dayforge.domain.service.readCurrentHabitSnapshot
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Isolated actual count command with explicit commit/result phases. Original repository owns
 * every write and fresh authorization. One admitted command per page; no authority cache,
 * automatic write retry, or claim that the existing metric SQL owner admits fact writes. */
internal class G0CountJourney(private val repository:HabitRepository,private val results:G0CountResultReader,
    tokens:TokenManager,private val context:Context,private val owner:CoroutineScope,private val habitId:Long,
    private val reusePreparation:Boolean=false,private val reuseAfterResult:Boolean=false,
    private val loadOnOpen:Boolean=false,private val shareInitialPure:Boolean=false,
    private val interactive:suspend (suspend ()->Unit)->Unit={it()}) {
    enum class Phase { READY,READ_FAILED,SAVING,SAVED_REFRESHING,RESULT,UNCHANGED,RECOVERY,SAVED_REFRESH_FAILED,CLOSED }
    data class State(val version:Long,val phase:Phase,val view:RecurringCompletionSnapshot?=null,
        val stateStartedAt:Long=SystemClock.elapsedRealtimeNanos(),
        val current:()->Boolean={true}) { fun renderable()=current() }
    data class Event(val kind:String,val at:Long,val version:Long)
    private val monitor=Any()
    private var serial=0L
    @Volatile private var retired=false
    private var busy=false
    private var work:Job?=null
    private var initialWork:Job?=null
    private var initialRequested=false
    private val mutable=MutableStateFlow(State(0,Phase.READY))
    val state=mutable.asStateFlow()
    val events=CopyOnWriteArrayList<Event>()
    internal var beforeWriteForTest:suspend ()->Unit={}
    internal var afterCommitForTest:suspend ()->Unit={}
    internal var beforePublishForTest:suspend ()->Unit={}
    internal var traceForTest:G0PhaseTrace?=null
    internal var initialTraceForTest:G0PhaseTrace?=null
    internal var beforeInitialReadForTest:suspend ()->Unit={}
    internal var initialError:Exception?=null;private set
    internal var lastError:Exception?=null;private set
    internal var recordedId:Long?=null;private set
    internal val factBlockHits:Long get()=results.factBlockHits
    private val listener=object:PersonalDataDisplayMemory.Listener {
        override fun transition(blocked:Boolean)=synchronized(monitor) {
            if(retired) return@synchronized
            retired=true;mutable.value=State(++serial,Phase.RECOVERY);initialWork?.cancel()
        }
    }
    init { tokens.registerPersonalDisplay(listener) }
    private fun event(kind:String) { events+=Event(kind,SystemClock.elapsedRealtimeNanos(),serial) }
    private fun emit(phase:Phase,view:RecurringCompletionSnapshot?=null)=synchronized(monitor) {
        if(!retired) {
            val version=++serial
            mutable.value=State(version,phase,view) { !retired && mutable.value.version==version }
            event("state_$phase")
        }
    }
    /** Called by the actual screen's open lifecycle, once. Never blocks controls or waits
     * for user input under an account/database lock. A click cancels and joins this read. */
    fun opened():Boolean=synchronized(monitor) {
        if(!loadOnOpen || initialRequested || retired || busy || mutable.value.phase!=Phase.READY) return false
        initialRequested=true;event("initial_requested")
        fun failInitial(error:Exception)=synchronized(monitor) {
            initialError=error
            if(!retired && !busy && mutable.value.phase==Phase.READY) emit(Phase.READ_FAILED)
        }
        initialWork=owner.launch(Dispatchers.IO,start=CoroutineStart.LAZY) {
            try {
                suspend fun read() {
                    event("initial_started");beforeInitialReadForTest()
                    currentCoroutineContext().ensureActive()
                    results.readInitialAndPublish(habitId,shareInitialPure) { view -> synchronized(monitor) {
                        // A late initial snapshot cannot overwrite SAVING or a later result.
                        if(!retired && !busy && mutable.value.phase==Phase.READY) {
                            emit(Phase.READY,view);event("initial_published")
                        }
                    } }
                    event("initial_returned")
                }
                initialTraceForTest?.run { read() } ?: read()
            } catch(error:Exception) {
                failInitial(error)
                if(error is CancellationException) throw error
            } finally { event("initial_finished") }
        }.also { job ->
            job.invokeOnCompletion { error -> if(error is Exception) failInitial(error) }
            job.start()
        }
        true
    }
    suspend fun awaitInitial() { synchronized(monitor) { initialWork }?.join() }
    /** Scheduling only. Capture one already admitted command; never wait for a future click. */
    internal fun pendingCommand():Job?=synchronized(monitor) { work?.takeUnless {it.isCompleted} }
    fun increment():Boolean=synchronized(monitor) {
        if(retired || busy || mutable.value.phase !in setOf(Phase.READY,Phase.READ_FAILED,Phase.RESULT,Phase.UNCHANGED)) return false
        val identity=mutable.value.view?.countHistory?.actionIdentity
        // Strategy selection only: a published result populated this page's pure cache.
        // Never carry its authority, SQL observations or whole-history validity into the click.
        val sharePreparation=reusePreparation || reuseAfterResult && mutable.value.view!=null &&
            (mutable.value.phase!=Phase.READY || shareInitialPure)
        val initial=initialWork
        busy=true;recordedId=null;lastError=null;emit(Phase.SAVING)
        event(if(identity!=null) "count_identity_present" else "count_identity_absent")
        work=owner.launch(Dispatchers.IO,start=CoroutineStart.LAZY) {
            var committed=false
            try {
                suspend fun command() {
                    event("initial_handoff_started")
                    initial?.cancelAndJoin()
                    currentCoroutineContext().ensureActive()
                    event("initial_handoff_finished")
                    event("prepare_started")
                    event(if(sharePreparation) "prepare_shared" else "prepare_original")
                    val captured=if(sharePreparation) results.prepareCompletion(habitId) else repository.prepareCompletion(habitId)
                    event("prepared")
                    val original=requireNotNull(captured.edit.value) { "HABIT_NOT_FOUND" }
                    val current=requireNotNull(repository.getHabitById(original.id)) { "HABIT_NOT_FOUND" }
                    event("habit_loaded")
                    check(current.uuid==original.uuid && current.habitType==original.habitType &&
                        current.completionPolicy==original.completionPolicy) { "HABIT_IDENTITY_CHANGED" }
                    // This G0 surface is explicitly recurring COUNT only.
                    check(current.habitType==HabitType.COUNTING && current.appearance!=null &&
                        current.completionPolicy=="recurring") { "G0_COUNT_SCOPE" }
                    beforeWriteForTest();currentCoroutineContext().ensureActive()
                    check(!retired) { "G0_COUNT_RETIRED" }
                    event("write_started")
                    val id=repository.logCompletion(context,habitId,1,captured.edit.authority,current.uuid,countIdentity=identity)
                    event("write_returned")
                    if(id!=0L) {
                        recordedId=id;committed=true;event("committed");emit(Phase.SAVED_REFRESHING)
                        afterCommitForTest()
                    }
                    suspend fun finish()=readCurrentHabitSnapshot {
                        if(results.publishesTogether) {
                            beforePublishForTest() // Outside all read/account resources.
                            results.readAndPublish(captured,id!=0L,id.takeIf { it!=0L }) { view ->
                                emit(if(id!=0L) Phase.RESULT else Phase.UNCHANGED,view)
                            }
                        } else {
                            val view=results.readCompletionAfter(captured,id!=0L,id.takeIf { it!=0L })
                            beforePublishForTest()
                            results.publish(captured,view,id!=0L,id.takeIf { it!=0L }) {
                                emit(if(id!=0L) Phase.RESULT else Phase.UNCHANGED,view)
                            }
                        }
                    }
                    if(committed) afterCommittedAction { finish() } else finish()
                }
                traceForTest?.run { interactive { command() } } ?: interactive { command() }
            } catch(error:Exception) {
                lastError=error
                emit(if(committed) Phase.SAVED_REFRESH_FAILED else Phase.RECOVERY)
                if(error is CancellationException) throw error
            } finally { synchronized(monitor) { busy=false } }
        }.also { job ->
            job.invokeOnCompletion { failure -> synchronized(monitor) {
                busy=false
                if(failure!=null && mutable.value.phase in setOf(Phase.SAVING,Phase.SAVED_REFRESHING))
                    emit(if(recordedId!=null) Phase.SAVED_REFRESH_FAILED else Phase.RECOVERY)
            } }
            job.start()
        }
        true
    }
    suspend fun awaitSettled() { synchronized(monitor) { work }?.join();awaitInitial() }
    fun close()=synchronized(monitor) {
        retired=true;mutable.value=State(++serial,Phase.CLOSED);work?.cancel();initialWork?.cancel();results.close()
    }
}
