package com.dayforge.data.repository

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.dayforge.data.local.PersonalDataDisplayMemory
import com.dayforge.domain.service.readCurrentHabitSnapshot
import java.time.Duration
import java.time.ZonedDateTime
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** Actual original Dashboard reader, unsealed like the original fact writer. One active read
 * and one conflated dirty signal. No shared authorization or metric certificate is borrowed. */
internal class G0CountDashboard(private val context:Context,private val repository:G0DetailedDashboardReadRepository,owner:CoroutineScope,
    private val coalesceBeforeRead:Boolean=false) {
    data class State(val version:Long,val batch:G0DetailedDashboardReadBatch?=null,val error:Exception?=null,
        val valid:()->Boolean={false})
    private val monitor=Any()
    @Volatile private var generation=0L
    private var version=0L
    @Volatile private var retired=false
    private var started=false
    private val job=SupervisorJob(owner.coroutineContext[Job])
    private val scope=CoroutineScope(owner.coroutineContext+job)
    private val wake=Channel<Unit>(Channel.CONFLATED)
    private val mutable=MutableStateFlow(State(0))
    val state=mutable.asStateFlow()
    internal var trace:G0PhaseTrace?=null
    internal var beforeReadForTest:suspend ()->Unit={}
    internal var beforePublishForTest:suspend ()->Unit={}
    internal var clockForTest:()->ZonedDateTime=ZonedDateTime::now
    internal val reads=java.util.concurrent.atomic.AtomicInteger()
    internal val publications=java.util.concurrent.atomic.AtomicInteger()
    internal val invalidations=java.util.concurrent.atomic.AtomicInteger()
    internal data class Event(val kind:String,val at:Long,val expected:Long,val current:Long)
    internal val events=java.util.concurrent.CopyOnWriteArrayList<Event>()
    private fun event(kind:String,expected:Long)=events.add(Event(kind,android.os.SystemClock.elapsedRealtimeNanos(),expected,generation))
    private val listener=object:PersonalDataDisplayMemory.Listener {
        override fun transition(blocked:Boolean) { close() }
    }
    init {
        repository.register(listener)
        job.invokeOnCompletion { synchronized(monitor) {
            retired=true;generation++;mutable.value=State(++version);wake.close()
        } }
    }
    fun start(command:G0CountJourney?=null):Boolean=synchronized(monitor) {
        if(started || retired) return false
        started=true
        scope.launch(Dispatchers.IO) {
            try { coroutineScope {
                val receiver=object:BroadcastReceiver() {
                    override fun onReceive(context:Context?,intent:Intent?) { refresh() }
                }
                ContextCompat.registerReceiver(context,receiver,IntentFilter().apply {
                    addAction(Intent.ACTION_DATE_CHANGED);addAction(Intent.ACTION_TIME_CHANGED);addAction(Intent.ACTION_TIMEZONE_CHANGED)
                },ContextCompat.RECEIVER_NOT_EXPORTED)
                try {
                launch { command?.state?.collect {
                    if(it.phase in setOf(G0CountJourney.Phase.CLOSED,G0CountJourney.Phase.RECOVERY,G0CountJourney.Phase.SAVED_REFRESH_FAILED)) close()
                } }
                launch {
                    while(true) {
                        val now=clockForTest()
                        delay(Duration.between(now.toInstant(),now.toLocalDate().plusDays(1).atStartOfDay(now.zone).toInstant()).toMillis().coerceAtLeast(1))
                        refresh()
                    }
                }
                // Initial emission is the initial read; register dependencies before reading.
                launch { repository.changes.collect { refresh() } }
                for(ignored in wake) {
                    // No account/SQL resource has been acquired. Join only the captured job.
                    // Signals arriving during the wait still revoke visible content immediately.
                    if(coalesceBeforeRead) {
                        val captured=command?.pendingCommand()
                        if(captured!=null) {
                            val requested=synchronized(monitor) {generation}
                            event("admission_wait",requested)
                            captured.join()
                            event("admission_resumed",requested)
                        }
                        if(command?.state?.value?.phase in setOf(G0CountJourney.Phase.CLOSED,G0CountJourney.Phase.RECOVERY,G0CountJourney.Phase.SAVED_REFRESH_FAILED)) close()
                    }
                    currentCoroutineContext().ensureActive()
                    val expected=synchronized(monitor) {
                        // Conflated channel has at most one pending wake. Atomic with refresh:
                        // later invalidations retain their wake and still invalidate this read.
                        if(coalesceBeforeRead) wake.tryReceive()
                        generation.also {event("admitted",it)}
                    }
                    try {
                        suspend fun read() {
                            reads.incrementAndGet();beforeReadForTest()
                            event("read_invoked",expected)
                            val batch=readCurrentHabitSnapshot { repository.read(clockForTest()) }
                            event("read_returned",expected)
                            beforePublishForTest();currentCoroutineContext().ensureActive()
                            val allowed=repository.publish(batch) { synchronized(monitor) {
                                if(!retired && generation==expected) {
                                    val current=++version
                                    publications.incrementAndGet() // Commit diagnostics before observable state.
                                    event("published",expected)
                                    mutable.value=State(current,batch) {
                                        val now=clockForTest()
                                        !retired && generation==expected && mutable.value.version==current &&
                                            now.zone==batch.time.zone && now.toLocalDate()==batch.time.toLocalDate()
                                    }
                                } else event("discarded",expected)
                            } }
                            check(allowed) { "G0_DASHBOARD_PUBLICATION_REJECTED" }
                        }
                        trace?.run { read() } ?: read()
                    } catch(error:Exception) {
                        if(error is CancellationException) throw error
                        synchronized(monitor) { if(!retired && generation==expected) mutable.value=State(++version,error=error) }
                    }
                }
                } finally { context.unregisterReceiver(receiver) }
            } } catch(error:Exception) {
                if(error is CancellationException) throw error
                synchronized(monitor) { if(!retired) { generation++;mutable.value=State(++version,error=error) } }
            }
        }
        true
    }
    /** A signal has no content or authority. Clearing old evidence is synchronous. */
    fun refresh()=synchronized(monitor) {
        if(!retired) { generation++;invalidations.incrementAndGet();mutable.value=State(++version);wake.trySend(Unit) }
        Unit
    }
    fun close()=synchronized(monitor) {
        if(!retired) { retired=true;generation++;mutable.value=State(++version);job.cancel();wake.close() }
    }
    suspend fun shutdown() { close();job.join() }
    companion object {
        /** Card compatibility only, after EACH reader's complete proof and fresh publication.
         * Original ACK canonicalizes CompletionEntity.createdAt from the accepted payload.
         * This card never displays storage creation metadata. Keep membership, order, source,
         * occurrence time/zone, quantities, policies, round and action identity exact.
         * Never use this comparison to authorize a read/write or normalize stored evidence. */
        internal fun sameCountCardEvidence(a:com.dayforge.domain.model.CountHistory?,
            b:com.dayforge.domain.model.CountHistory?):Boolean {
            if(a==null || b==null) return false
            if(a.copy(completions=b.completions)!=b || a.completions.size!=b.completions.size) return false
            return a.completions.indices.all {index->
                val left=a.completions[index];val right=b.completions[index]
                left==right || left.copy(createdAt=right.createdAt)==right
            }
        }
        fun compatible(command:G0CountJourney.State,home:State):Boolean {
            val view=command.view ?: return false
            val batch=home.batch ?: return false
            return command.renderable() && home.valid() &&
                command.phase in setOf(G0CountJourney.Phase.READY,G0CountJourney.Phase.RESULT,G0CountJourney.Phase.UNCHANGED) &&
                batch.habits.singleOrNull { it.id==view.habit.id }==view.habit &&
                sameCountCardEvidence(batch.counts[view.habit.id],view.countHistory)
        }
    }
}
