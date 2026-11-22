package com.dayforge.data.repository

import com.dayforge.data.local.PersonalDataDisplayMemory
import com.dayforge.data.local.TokenManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.takeWhile

/** G0 household renderer of ONE committed two-object model. Saved is emitted only after reliable COMMIT. The model
 * supplies card/home inputs and the original follow-up claim, not linked-metric prompt UI. */
internal class G0HouseholdCountPage(private val store:G0HouseholdCountStore,tokens:TokenManager,private val owner:CoroutineScope,
    private val onCommitted:(G0HouseholdCountStore.Saved)->Unit={}) {
    enum class Phase { LOADING, READY, VERIFYING, SAVING, SAVED, RESULT, RECOVERY, CLOSED }
    class CommandIdentity internal constructor(val command:G0HouseholdCountStore.Command,val firstStateVersion:Long)
    class CommitNotice internal constructor(val command:G0HouseholdCountStore.Command,
        val saved:G0HouseholdCountStore.Saved,val firstStateVersion:Long)
    class AttemptReceipt internal constructor(val command:G0HouseholdCountStore.Command,val firstStateVersion:Long,
        val requestedHabitUuid:String,val createdStateVersion:Long,val firstSequence:Long,val sequence:Long,val count:Long)
    data class State(val version:Long,val phase:Phase,val view:G0HouseholdCountStore.View?,
        val members:List<String> = view?.cards?.keys?.toList() ?: emptyList(),
        val publishedAt:Long=android.os.SystemClock.elapsedRealtimeNanos(),val intent:CommandIdentity?=null,
        val commitNotice:CommitNotice?=null,val attemptReceipt:AttemptReceipt?=null)
    /** Per-intent diagnostics only. A prior command's known result must never supply the
     * commit timestamp for a new cancelled/failed intent; refreshes do not update this. */
    data class CommandTiming(val command:G0HouseholdCountStore.Command,val firstStateVersion:Long,
        val ioStartedAt:Long=0L,val knownCommitAt:Long=0L)
    enum class AdmissionOutcome { ACCEPTED, CLOSED, BUSY, NOT_READY, MISSING_COMMAND }
    data class Admission(val at:Long,val decidedAt:Long,val requestedHabitUuid:String,val stateVersion:Long,
        val phase:Phase,val outcome:AdmissionOutcome,val requestId:String?,val firstStateVersion:Long?,val attemptSequence:Long?=null)
    data class Publication(val version:Long,val phase:Phase,val at:Long)
    data class CommandRecord(val command:G0HouseholdCountStore.Command,val acceptedAt:Long,val firstStateVersion:Long,
        val ioStartedAt:Long=0L,val knownCommitAt:Long=0L,val saved:G0HouseholdCountStore.Saved?=null,
        val trace:G0PhaseTrace?=null,val publications:List<Publication> = emptyList(),
        val completedAt:Long=0L,val errorClass:String?=null)
    private val mutable=MutableStateFlow(State(0,Phase.LOADING,null))
    val state=mutable.asStateFlow()
    private val monitor=Any()
    private val admissionLog=mutableListOf<Admission>()
    private val commandLog=linkedMapOf<String,CommandRecord>()
    private var publicationCount=0
    private var currentIntent:CommandIdentity?=null
    private var currentNotice:CommitNotice?=null
    private var currentAttempt:AttemptReceipt?=null
    private var attemptSequence=0L
    @Volatile var diagnosticsOverflow=false;private set
    val admissions:List<Admission> get()=synchronized(monitor) {java.util.Collections.unmodifiableList(admissionLog.toList())}
    val commandRecords:Map<String,CommandRecord> get()=synchronized(monitor) {java.util.Collections.unmodifiableMap(commandLog.toMap())}
    @Volatile private var closed=false
    @Volatile private var cancelled=false
    @Volatile var error:Throwable?=null;private set
    @Volatile var committedAt=0L;private set
    @Volatile var saved:G0HouseholdCountStore.Saved?=null;private set
    // Identity is retained even when COMMIT's result is unknown. It is not a permission.
    @Volatile var command:G0HouseholdCountStore.Command?=null;private set
    @Volatile var commandTiming:CommandTiming?=null;private set
    private var nextCommands:Map<String,G0HouseholdCountStore.Command> = emptyMap()
    private val savedSources=linkedMapOf<String,Long>()
    private fun sources()=synchronized(monitor) {savedSources.toMap()}
    @Volatile var trace:G0PhaseTrace?=null
    var refreshTrace:G0PhaseTrace?=null
    private var observer:Job?=null
    private val observerStop=MutableStateFlow(false)
    var afterCommitForTest:suspend ()->Unit={}
    var beforeRefreshForTest:suspend ()->Unit={}
    var beforeAdmissionReadForTest:suspend ()->Unit={}
    var afterAdmissionReadForTest:suspend ()->Unit={}
    val events=java.util.concurrent.CopyOnWriteArrayList<State>()
    private var work:Job?=null
    private val listener=object:PersonalDataDisplayMemory.Listener {
        override fun transition(blocked:Boolean) { close() }
    }
    init {tokens.registerPersonalDisplay(listener)}
    fun live()=!closed
    private fun diagnosticTime()=android.os.SystemClock.elapsedRealtimeNanos()
    private fun recordAdmission(at:Long,id:String,before:State,outcome:AdmissionOutcome,
        intent:G0HouseholdCountStore.Command?=null,version:Long?=null,attempt:Long?=null) {
        if(admissionLog.size<4096)admissionLog+=Admission(at,diagnosticTime(),id,before.version,before.phase,outcome,intent?.requestId,version,attempt)
        else diagnosticsOverflow=true
    }
    private fun updateCommand(intent:G0HouseholdCountStore.Command,change:(CommandRecord)->CommandRecord)=synchronized(monitor) {
        commandLog[intent.requestId]?.let {commandLog[intent.requestId]=change(it)}
    }
    private fun emit(phase:Phase,view:G0HouseholdCountStore.View?=null,recordPublication:Boolean=true)=synchronized(monitor) {
        if(!closed) {
            if(phase==Phase.RECOVERY) {currentIntent=null;currentNotice=null;currentAttempt=null}
            val next=State(mutable.value.version+1,phase,view,view?.cards?.keys?.toList() ?: mutable.value.members,
                intent=currentIntent,commitNotice=currentNotice,attemptReceipt=currentAttempt)
            if(recordPublication && phase in setOf(Phase.SAVED,Phase.RESULT))command?.let {intent->
                if(publicationCount<4096)updateCommand(intent) {record->
                    publicationCount++
                    record.copy(publications=java.util.Collections.unmodifiableList(record.publications+Publication(next.version,phase,next.publishedAt)))
                } else diagnosticsOverflow=true
            }
            events+=next;mutable.value=next}
    }
    // Called inside the store's fresh publication boundary. Do not create identities in
    // the click callback while a concurrent COMMIT may be promoting its certificate.
    private fun publishReady(phase:Phase,view:G0HouseholdCountStore.View)=synchronized(monitor) {
        if(!closed) {nextCommands=view.cards.keys.associateWith {store.newCommand(it)};emit(phase,view)}
    }
    suspend fun open() {
        try {store.readAndPublish {publishReady(Phase.READY,it)}} catch(e:Exception) {error=e;emit(Phase.RECOVERY)}
    }
    fun increment(habitUuid:String,reportBusy:Boolean=false):Boolean {
      val requestedAt=diagnosticTime()
      return synchronized(monitor) {
        val before=mutable.value
        if(closed) {recordAdmission(requestedAt,habitUuid,before,AdmissionOutcome.CLOSED);return@synchronized false}
        if(before.phase !in setOf(Phase.READY,Phase.RESULT)) {
            val outcome=if(before.phase in setOf(Phase.VERIFYING,Phase.SAVING,Phase.SAVED))AdmissionOutcome.BUSY else AdmissionOutcome.NOT_READY
            var rejectedSequence:Long?=null
            val active=currentIntent
            if(reportBusy && outcome==AdmissionOutcome.BUSY && habitUuid in before.members && active!=null) {
                attemptSequence=Math.addExact(attemptSequence,1L);rejectedSequence=attemptSequence
                val prior=currentAttempt
                currentAttempt=AttemptReceipt(active.command,active.firstStateVersion,habitUuid,before.version+1,
                    prior?.firstSequence ?: attemptSequence,attemptSequence,Math.addExact(prior?.count ?: 0L,1L))
                emit(before.phase,before.view,recordPublication=false)
            }
            recordAdmission(requestedAt,habitUuid,before,outcome,command,active?.firstStateVersion ?: commandTiming?.firstStateVersion,rejectedSequence)
            return@synchronized false
        }
        val intent=nextCommands[habitUuid] ?: run {
            recordAdmission(requestedAt,habitUuid,before,AdmissionOutcome.MISSING_COMMAND);return@synchronized false
        }
        nextCommands=emptyMap();command=intent
        val identity=CommandIdentity(intent,before.version+1)
        currentIntent=identity;currentNotice=null;currentAttempt=null
        val needsRead=mutable.value.view?.valid()!=true
        // One bounded input slot; accepting intent never authorizes stale data or a write.
        emit(if(needsRead)Phase.VERIFYING else Phase.SAVING)
        var timing=CommandTiming(intent,identity.firstStateVersion)
        commandTiming=timing
        // Freeze diagnostics once at actual admission. A later rejected tap may replace
        // page.trace for its own attempt but must never relabel an in-flight command.
        val operationTrace=trace
        if(commandLog.size<1024)commandLog[intent.requestId]=CommandRecord(intent,diagnosticTime(),timing.firstStateVersion,trace=operationTrace)
        else diagnosticsOverflow=true
        recordAdmission(requestedAt,habitUuid,before,AdmissionOutcome.ACCEPTED,intent,timing.firstStateVersion)
        work=owner.launch(Dispatchers.IO,start=CoroutineStart.LAZY) {
            timing=timing.copy(ioStartedAt=android.os.SystemClock.elapsedRealtimeNanos())
            commandTiming=timing
            updateCommand(intent) {it.copy(ioStartedAt=timing.ioStartedAt)}
            try {
                suspend fun command() {
                    if(needsRead) {
                        beforeAdmissionReadForTest()
                        store.readAndPublish { /* Fresh read only; no nested write or publication. */ }
                        afterAdmissionReadForTest()
                        currentCoroutineContext().ensureActive()
                        check(synchronized(monitor) {!cancelled && (closed || mutable.value.phase==Phase.VERIFYING)})
                        emit(Phase.SAVING)
                    }
                    val value=store.increase(intent)
                    synchronized(monitor) {
                        check(value.requestId==intent.requestId && value.factUuid==intent.factUuid && value.habitUuid==intent.habitUuid)
                        saved=value;committedAt=android.os.SystemClock.elapsedRealtimeNanos();savedSources[value.habitUuid]=value.factId
                        if(!closed && currentIntent?.command===intent)
                            currentNotice=CommitNotice(intent,value,requireNotNull(currentIntent).firstStateVersion)
                    }
                    timing=timing.copy(knownCommitAt=committedAt);commandTiming=timing
                    updateCommand(intent) {it.copy(knownCommitAt=timing.knownCommitAt,saved=value)}
                    // Trusted runtime handoff, before result reading or rendering. This
                    // is a wake hint for the durable outbox, never a sync-success claim.
                    onCommitted(value)
                    emit(Phase.SAVED);afterCommitForTest()
                    if(!closed)store.readAndPublish(sources()) {publishReady(Phase.RESULT,it)}
                }
                operationTrace?.run {command()} ?: command()
            } catch(e:Exception) {error=e;updateCommand(intent) {it.copy(errorClass=e.javaClass.simpleName)}
                emit(Phase.RECOVERY);if(e is CancellationException)throw e}
        }.also {job ->
            job.invokeOnCompletion {failure ->
              updateCommand(intent) {it.copy(completedAt=diagnosticTime(),errorClass=it.errorClass ?: failure?.javaClass?.simpleName)}
              if(failure is CancellationException) {
                // A runtime can cancel siblings after RECOVERY is emitted. Keep the
                // original unknown-commit/auth error instead of masking it with cleanup.
                synchronized(monitor) {if(error==null)error=failure;emit(Phase.RECOVERY)}
            }}
            job.start()
        }
        true
      }
    }
    suspend fun refresh() {
        val source=sources()
        beforeRefreshForTest()
        store.readAndPublish(source) {view ->synchronized(monitor) {
            // A pending refresh of the prior command cannot replace the next command's
            // claim, even when both snapshots contain the same up-to-date total.
            if(savedSources==source && mutable.value.phase in setOf(Phase.READY,Phase.RESULT))publishReady(mutable.value.phase,view)
        }}
    }
    /** Commit notifications are only wake hints. A fresh authenticated read supplies the
     * replacement; no sequence value or cached view grants publication authority. */
    fun watchChanges(changes:StateFlow<Long>)=synchronized(monitor) {
        check(observer==null && !closed)
        observer=owner.launch(Dispatchers.IO) {
            try {
                combine(changes,observerStop) {_,stop->stop}.takeWhile {!it}.collect {
                    val shouldRead=synchronized(monitor) {
                        !closed && mutable.value.phase in setOf(Phase.READY,Phase.RESULT) && mutable.value.view?.valid()!=true
                    }
                    if(shouldRead) {refreshTrace?.run {refresh()} ?: refresh()}
                }
            } catch(e:Exception) {
                if(e is CancellationException)throw e
                error=e;emit(Phase.RECOVERY)
            }
        }
    }
    suspend fun settled() {
        synchronized(monitor) {work}?.join()
        if(closed)synchronized(monitor) {observer}?.join()
    }
    /** Semantic identity only; the runtime independently checks its continuous
     * authority lifetime. Diagnostics and an old saved value grant no permission. */
    internal fun commandFeedbackCurrent(expected:State):Boolean=synchronized(monitor) {
        val active=currentIntent ?: return@synchronized false
        if(closed || mutable.value!==expected || expected.intent!==active || command!==active.command ||
            expected.phase !in setOf(Phase.VERIFYING,Phase.SAVING,Phase.SAVED,Phase.RESULT) ||
            expected.commitNotice!==currentNotice || expected.attemptReceipt!==currentAttempt)return@synchronized false
        currentNotice?.let {notice->
            if(notice.command!==active.command || notice.firstStateVersion!=active.firstStateVersion ||
                notice.saved.requestId!=active.command.requestId || notice.saved.factUuid!=active.command.factUuid ||
                notice.saved.habitUuid!=active.command.habitUuid || expected.phase !in setOf(Phase.SAVED,Phase.RESULT))return@synchronized false
        }
        currentAttempt?.let {attempt->
            if(attempt.command!==active.command || attempt.firstStateVersion!=active.firstStateVersion ||
                attempt.requestedHabitUuid !in expected.members || attempt.count!=attempt.sequence-attempt.firstSequence+1)return@synchronized false
        }
        true
    }
    /** Leaving a page does not retract an already admitted business intent. Its trusted
     * runtime retains ownership until settled; revocation/shutdown still calls close. */
    fun detach()=synchronized(monitor) {
        if(!closed) {closed=true;nextCommands=emptyMap();currentIntent=null;currentNotice=null;currentAttempt=null
            observerStop.value=true;mutable.value=State(mutable.value.version+1,Phase.CLOSED,null)}
    }
    fun close()=synchronized(monitor) {
        cancelled=true;detach();work?.cancel();observer?.cancel()
    }
}

