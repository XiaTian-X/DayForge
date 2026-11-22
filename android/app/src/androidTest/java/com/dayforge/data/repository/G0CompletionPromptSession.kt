package com.dayforge.data.repository

import android.os.SystemClock
import com.dayforge.ui.components.LinkedMetricInfo
import com.dayforge.ui.components.MetricInputState
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** G0-only completion presentation. The backend owns fresh authentication and complete
 * source/dependency proof; this owner adds page/request/known-COMMIT lifetime, never SQL. */
internal class G0CompletionPromptSession(
    private val store:G0HouseholdCountStore,
    private val page:G0HouseholdCountPage,
    private val authority:G0AuthorityOwner,
    private val changes:StateFlow<Long>,
    private val control:CoroutineScope,
    val windowMode:WindowMode=WindowMode.RECREATE,
    private val pageCurrent:()->Boolean={page.live()}
) {
    enum class WindowMode { RECREATE, RETAIN_REQUEST }
    enum class Phase { IDLE, ARMED, PREPARING, SHOWN, SUPPRESSED, FAILED, CLOSED }
    class Request internal constructor(internal val issuer:G0CompletionPromptSession,val generation:Long,
        val habitUuid:String,val eligible:Boolean) {
        internal var command:G0HouseholdCountStore.Command?=null
        internal var known:Known?=null
        internal var attempts=0
        internal var started=false
        internal var scheduledHint:Long?=null
    }
    internal class Known(val command:G0HouseholdCountStore.Command,val saved:G0HouseholdCountStore.Saved,
        val source:WidgetFactSnapshot)
    class Expected internal constructor(internal val request:Request,internal val proof:G0CompletionPromptProof.Prepared,
        val identity:String,val version:Long) {
        val habitName:String get()=proof.habitName
        val metrics:List<LinkedMetricInfo> = Collections.unmodifiableList(proof.metrics.toList())
        val source:WidgetFactSnapshot get()=proof.source
    }
    /** Window lifetime only. It carries no Prepared, content, SQL or business permission. */
    class WindowTicket internal constructor(internal val issuer:G0CompletionPromptSession,internal val request:Request) {
        val identity="${System.identityHashCode(issuer)}:window:${request.generation}"
        val generation:Long get()=request.generation
    }
    data class State(val version:Long,val phase:Phase,val expected:Expected?=null,val at:Long=SystemClock.elapsedRealtimeNanos(),
        val window:WindowTicket?=null)
    data class Attempt(val generation:Long,val number:Int,val hint:Long,val started:Long,val ended:Long,
        val outcome:String,val publishedVersion:Long?,val errorClass:String?)
    data class HintObservation(val observedHint:Long,val hint:Long,val generation:Long?,val displayedVersion:Long?,val action:String,val at:Long)
    data class Callback(val operation:String,val version:Long,val at:Long,val outcome:String)
    data class WindowCancellation(val identity:String,val generation:Long,val version:Long,val at:Long,val accepted:Boolean)
    private data class Work(val request:Request,val known:Known,val hint:Long)
    private val monitor=Any()
    private val lease=authority.lease()
    private val ownerJob=SupervisorJob(control.coroutineContext[Job])
    private val owner=CoroutineScope(control.coroutineContext+ownerJob+Dispatchers.IO)
    private val mutable=MutableStateFlow(State(0,Phase.IDLE))
    val state=mutable.asStateFlow()
    val events=CopyOnWriteArrayList<State>()
    val attempts=CopyOnWriteArrayList<Attempt>()
    val hints=CopyOnWriteArrayList<HintObservation>()
    val callbacks=CopyOnWriteArrayList<Callback>()
    val windowCancellations=CopyOnWriteArrayList<WindowCancellation>()
    @Volatile var diagnosticsOverflow=false;private set
    @Volatile var error:Throwable?=null;private set
    @Volatile private var closed=false
    private var generation=0L
    private var sequence=0L
    private var request:Request?=null
    private var activeWindow:WindowTicket?=null
    private var running:Job?=null
    private var queued:Work?=null
    private var awaitingHint:Work?=null
    private var subscription:AutoCloseable?=null
    private val watchers=mutableListOf<Job>()
    @Volatile var traceForTest:G0PhaseTrace?=null
    var beforePrepareForTest:suspend ()->Unit={}

    init {
        val observation=authority.observeReservations {retire()}
        synchronized(monitor) {if(closed)observation.close() else subscription=observation}
        watchers+=owner.launch {page.state.collect {onPageState(it)}}
        watchers+=owner.launch {changes.collect {onDatabaseChange(it)}}
        ownerJob.invokeOnCompletion {retire()}
    }
    private fun live():Boolean = !closed && control.isActive && ownerJob.isActive && page.live() &&
        page.state.value.phase !in setOf(G0HouseholdCountPage.Phase.RECOVERY,G0HouseholdCountPage.Phase.CLOSED) &&
        runCatching {pageCurrent() && authority.checkLease(lease).let {true}}.getOrDefault(false)
    private fun owns(r:Request)=r.issuer===this && r.eligible && request===r && r.generation==generation && live()
    private fun emit(phase:Phase,expected:Expected?=null) {
        if(phase !in setOf(Phase.PREPARING,Phase.SHOWN))activeWindow=null
        val next=State(++sequence,phase,expected,window=activeWindow)
        if(events.size<4096)events+=next else diagnosticsOverflow=true
        mutable.value=next
    }
    /** Original capture rule: an existing prompt makes the new request ineligible without
     * clearing the old prompt. A newer eligible request supersedes only pending preparation. */
    fun capture(habitUuid:String):Request=synchronized(monitor) {
        val eligible=live() && mutable.value.expected==null && activeWindow==null && habitUuid in page.state.value.members
        if(!eligible)return@synchronized Request(this,generation,habitUuid,false)
        generation=Math.addExact(generation,1L)
        Request(this,generation,habitUuid,true).also {request=it;queued=null;awaitingHint=null;error=null;emit(Phase.ARMED)}
    }
    fun bind(r:Request,command:G0HouseholdCountStore.Command):Boolean=synchronized(monitor) {
        if(!owns(r) || r.command!=null || page.command!==command || command.habitUuid!=r.habitUuid)return@synchronized false
        r.command=command
        true
    }
    /** The actual button calls this once. No new input queue, command ID or business retry. */
    fun count(habitUuid:String,reportBusy:Boolean=false):Boolean=synchronized(monitor) {
        if(!live())return@synchronized false
        // A rejected tap must not supersede the previous accepted command's prompt.
        // Keep this monitor through admission and binding: the page observer cannot
        // consume a fast RESULT before its accepted request has been bound.
        if(!page.increment(habitUuid,reportBusy))return@synchronized false
        val captured=capture(habitUuid)
        val command=requireNotNull(page.command)
        if(bind(captured,command))onPageState(page.state.value)
        true
    }
    private fun knownCommit(r:Request,saved:G0HouseholdCountStore.Saved,source:WidgetFactSnapshot):Known? {
        val command=r.command ?: return null
        val current=page.state.value
        val notice=current.commitNotice ?: return null
        if(current.phase!=G0HouseholdCountPage.Phase.RESULT || notice.command!==command || page.command!==command ||
            notice.saved!==saved || page.saved!==saved || saved.requestId!=command.requestId ||
            saved.factUuid!=command.factUuid || saved.habitUuid!=command.habitUuid ||
            source.claim.completionUuid!=saved.factUuid || source.claim.habitUuid!=saved.habitUuid ||
            source.habit.uuid!=saved.habitUuid)return null
        // This is the actual Page's reliable result, not its timing/admission diagnostics.
        val card=current.view?.cards?.get(saved.habitUuid) ?: return null
        if(card.followUp!==source)return null
        return Known(command,saved,source)
    }
    fun prepare(r:Request,saved:G0HouseholdCountStore.Saved,source:WidgetFactSnapshot):Boolean=synchronized(monitor) {
        if(!owns(r) || r.started)return@synchronized false
        val known=knownCommit(r,saved,source) ?: return@synchronized false
        r.known=known;r.started=true
        enqueue(Work(r,known,changes.value));true
    }
    private fun onPageState(current:G0HouseholdCountPage.State) {
        if(!live() || current.phase in setOf(G0HouseholdCountPage.Phase.CLOSED,G0HouseholdCountPage.Phase.RECOVERY)) {
            retire();return
        }
        synchronized(monitor) {
            val r=request ?: return@synchronized
            if(!owns(r) || r.started || current.phase!=G0HouseholdCountPage.Phase.RESULT)return@synchronized
            val saved=current.commitNotice?.saved ?: return@synchronized
            val source=current.view?.cards?.get(saved.habitUuid)?.followUp ?: return@synchronized
            prepare(r,saved,source)
        }
    }
    /** Every distinct real commit hint may require a fresh read. One in-flight read and
     * one latest pending slot coalesce hints; failed reads never retry automatically. */
    private fun onDatabaseChange(observedHint:Long)=synchronized(monitor) {
        if(!live()) {retire();return@synchronized}
        val shown=mutable.value.expected
        if(shown==null) {
            val pending=awaitingHint
            val hint=changes.value
            val ready=pending!=null && owns(pending.request) && pending.request.scheduledHint?.let {hint>it}==true
            val action=if(ready)"refresh_pending" else if(pending!=null)"await_hint" else "ignored_no_prompt"
            if(hints.size<4096)hints+=HintObservation(observedHint,hint,request?.generation,null,action,SystemClock.elapsedRealtimeNanos())
            else diagnosticsOverflow=true
            if(ready) {awaitingHint=null;enqueue(requireNotNull(pending).copy(hint=hint))}
            return@synchronized
        }
        val valid=shown.proof.valid()
        // A collector may deliver an older value after a newer COMMIT. Also the gate
        // promotes its certificate before publishing its hint. Never mistake that
        // ordinary interval for a permanently failed proof or start an unhinted retry.
        val hint=changes.value
        val isNew=shown.request.scheduledHint?.let {hint>it} ?: true
        val action=if(valid)"still_current" else if(isNew)"refresh" else "await_hint"
        if(hints.size<4096)hints+=HintObservation(observedHint,hint,shown.request.generation,shown.version,action,SystemClock.elapsedRealtimeNanos())
        else diagnosticsOverflow=true
        if(!valid) {
            if(isNew)enqueue(Work(shown.request,requireNotNull(shown.request.known),hint))
            else {
                check(queued==null)
                awaitingHint=Work(shown.request,requireNotNull(shown.request.known),hint);emit(Phase.PREPARING)
            }
        }
    }
    /** Replays only the actual current hint; cannot invent an epoch or authorize data. */
    internal fun observeCurrentHintForTest()=onDatabaseChange(changes.value)
    private fun enqueue(work:Work) {
        if(!owns(work.request))return
        if(work.request.scheduledHint?.let {work.hint<=it}==true)return
        awaitingHint=null
        work.request.scheduledHint=work.hint
        emit(Phase.PREPARING)
        if(running?.isActive==true)queued=work else start(work)
    }
    private fun start(work:Work) {
        val r=work.request
        if(!owns(r))return
        r.attempts++
        val number=r.attempts
        val trace=traceForTest
        lateinit var task:Job
        task=owner.launch(start=CoroutineStart.LAZY) {
            val began=SystemClock.elapsedRealtimeNanos()
            var outcome="discarded";var failure:String?=null
            var publishedVersion:Long?=null
            try {
                beforePrepareForTest()
                suspend fun prepareBackend()=store.prepareCompletionPrompt(work.known.saved,work.known.source) {proof->
                    synchronized(monitor) {
                        if(owns(r) && r.known===work.known) {
                            if(proof==null) {emit(Phase.SUPPRESSED);outcome="suppressed"}
                            else if(proof.valid()) {
                                if(windowMode==WindowMode.RETAIN_REQUEST) {
                                    if(activeWindow==null)activeWindow=WindowTicket(this@G0CompletionPromptSession,r)
                                    check(activeWindow?.request===r)
                                }
                                val expected=Expected(r,proof,"${System.identityHashCode(this@G0CompletionPromptSession)}:${r.generation}:$number",sequence+1)
                                emit(Phase.SHOWN,expected);publishedVersion=expected.version;outcome="shown"
                            } else {
                                outcome="invalidated_before_publish"
                                error=IllegalStateException("G0_PROMPT_INVALID_AT_PUBLICATION");queued=null;emit(Phase.FAILED)
                            }
                        }
                    }
                }
                if(trace==null)prepareBackend() else trace.run {prepareBackend()}
            } catch(cancel:CancellationException) {
                failure=cancel.javaClass.simpleName
                synchronized(monitor) {if(owns(r)) {error=cancel;queued=null;emit(Phase.FAILED)}}
                throw cancel
            } catch(problem:Exception) {
                failure=problem.javaClass.simpleName;outcome="failed"
                synchronized(monitor) {if(owns(r)) {error=problem;queued=null;emit(Phase.FAILED)}}
            } finally {
                val record=Attempt(r.generation,number,work.hint,began,SystemClock.elapsedRealtimeNanos(),outcome,publishedVersion,failure)
                if(attempts.size<4096)attempts+=record else diagnosticsOverflow=true
            }
        }
        running=task
        // Completion also runs when the parent cancels the LAZY job before its body
        // starts; otherwise shutdown could join an already cancelled slot forever.
        task.invokeOnCompletion {
            synchronized(monitor) {
                if(running===task) {
                    running=null
                    val next=queued;queued=null
                    if(next!=null && owns(next.request))start(next)
                }
            }
        }
        task.start()
    }
    fun renderable(expected:Expected):Boolean=synchronized(monitor) {
        mutable.value.expected===expected && mutable.value.phase==Phase.SHOWN && owns(expected.request) && expected.proof.valid()
    }
    fun windowCurrent(ticket:WindowTicket):Boolean=synchronized(monitor) {
        windowMode==WindowMode.RETAIN_REQUEST && ticket.issuer===this && activeWindow===ticket &&
            mutable.value.window===ticket && mutable.value.phase in setOf(Phase.PREPARING,Phase.SHOWN) && owns(ticket.request)
    }
    fun waiting(ticket:WindowTicket,captured:State):Boolean=synchronized(monitor) {
        mutable.value===captured && windowCurrent(ticket) && captured.expected?.let(::renderable)!=true
    }
    /** Anonymous Back owns this request's lifetime, never a substitute Expected.
     * Do not interrupt an in-flight READ; shutdown/connection drainage still await it. */
    fun cancelWindow(ticket:WindowTicket):Boolean=synchronized(monitor) {
        val version=mutable.value.version
        val accepted=windowCurrent(ticket) && cancel(ticket.request)
        if(windowCancellations.size<4096)windowCancellations+=WindowCancellation(
            ticket.identity,ticket.generation,version,SystemClock.elapsedRealtimeNanos(),accepted)
        else diagnosticsOverflow=true
        accepted
    }
    private fun callback(operation:String,expected:Expected,outcome:String) {
        if(callbacks.size<4096)callbacks+=Callback(operation,expected.version,SystemClock.elapsedRealtimeNanos(),outcome)
        else diagnosticsOverflow=true
    }
    private fun requireCurrent(expected:Expected,operation:String) {
        if(!renderable(expected)) {callback(operation,expected,"stale");throw IllegalStateException("G0_PROMPT_STALE_CALLBACK")}
    }
    fun validateEmptyDraft(expected:Expected,inputs:List<MetricInputState>)=synchronized(monitor) {
        requireCurrent(expected,"draft")
        val supported=inputs.size==expected.metrics.size && inputs.zip(expected.metrics).all { (input,metric)->
            input.metricId==metric.metricId && input.name==metric.metricName && input.unit==metric.unit &&
                input.decimalPlaces==metric.decimalPlaces && input.inputValue.isEmpty() && input.note.isEmpty()
        }
        callback("draft",expected,if(supported)"accepted" else "unsupported")
        check(supported) {"G0_PROMPT_DRAFT_UNSUPPORTED"}
    }
    fun rejectUnsupported(expected:Expected,operation:String):Nothing=synchronized(monitor) {
        require(operation in setOf("record","skip"))
        requireCurrent(expected,operation);callback(operation,expected,"unsupported")
        throw IllegalStateException("G0_PROMPT_${operation.uppercase()}_UNSUPPORTED")
    }
    /** Identity-sensitive pure close. It never writes preferences or retracts a committed fact. */
    fun close(expected:Expected):Boolean=synchronized(monitor) {
        if(!renderable(expected)) {callback("close",expected,"stale");return@synchronized false}
        callback("close",expected,"accepted")
        generation=Math.addExact(generation,1L);request=null;queued=null;awaitingHint=null;emit(Phase.IDLE);true
    }
    fun cancel(r:Request):Boolean=synchronized(monitor) {
        if(!owns(r))return@synchronized false
        generation=Math.addExact(generation,1L);request=null;queued=null;awaitingHint=null;emit(Phase.IDLE);true
    }
    /** Immediate publication retirement, but ordinary screen departure does not cancel a
     * real SQL read mid-transaction. shutdown waits for that read before its caller closes DB. */
    fun retire()=synchronized(monitor) {
        if(closed)return@synchronized
        closed=true;generation=Math.addExact(generation,1L);request=null;queued=null;awaitingHint=null
        subscription?.close();subscription=null;emit(Phase.CLOSED)
    }
    suspend fun settled() {
        while(true) {
            val job=synchronized(monitor) {running} ?: return
            job.join()
        }
    }
    suspend fun shutdown() {
        retire();watchers.forEach {it.cancel()};settled()
        ownerJob.cancelAndJoin()
    }
}
