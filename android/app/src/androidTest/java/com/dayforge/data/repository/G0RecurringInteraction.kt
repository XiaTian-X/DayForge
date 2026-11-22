package com.dayforge.data.repository

import android.content.Context
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.PersonalDataDisplayMemory
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.service.DeviceCalendar
import com.dayforge.ui.CalendarObservation
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Typed, read-only page boundary. No database handles or write grants cross into the screen. */
internal class G0RecurringInteractionAdapter(
    private val tokens:TokenManager,private val access:LocalSyncAccess,
    private val gate:G0DomainSqlGate,private val authority:G0AuthorityOwner,private val runtime:G0DeviceBoundMetricRuntime,
    internal val reader:G0RecurringReadOwner,private val ids:List<Long>,
    private val owner:CoroutineScope,private val context:Context
) {
    data class Publication(val content:G0RecurringReadOwner.Content,val valid:()->Boolean)
    private val epoch=AtomicLong()
    private val bindingLease=authority.lease()
    private var authorityObservation:AutoCloseable?=null
    @Volatile private var closed=false
    @Volatile private var revoked=false
    private val observers=mutableListOf<Job>()
    private var calendarObservation:CalendarObservation?=null
    private var invalidator:PersonalDataDisplayMemory.Listener?=null
    private val calendar=DeviceCalendar()
    internal var traceForTest:G0PhaseTrace?=null
    internal var beforeReadForTest:suspend ()->Unit={}
    internal var afterReadForTest:suspend ()->Unit={}
    internal var nowForTest:()->ZonedDateTime={ ZonedDateTime.now() }
    fun observe(changed:()->Unit,revoked:()->Unit) {
        check(invalidator==null && !closed)
        val listener=object:PersonalDataDisplayMemory.Listener {
            override fun transition(blocked:Boolean) { this@G0RecurringInteractionAdapter.revoked=true;epoch.incrementAndGet();revoked() }
        }
        invalidator=listener;tokens.registerPersonalDisplay(listener)
        authorityObservation=authority.observeReservations {
            this.revoked=true;epoch.incrementAndGet();revoked()
        }
        val baseline=gate.changeSequence.value
        observers+=owner.launch { gate.changeSequence.collect { if(it!=baseline) changed() } }
        observers+=owner.launch { tokens.personalDisplayChanges.drop(1).collect { this@G0RecurringInteractionAdapter.revoked=true;epoch.incrementAndGet();revoked() } }
        calendarObservation=CalendarObservation(context,calendar)
        observers+=owner.launch { calendar.changes.drop(1).collect { calendarChanged(changed) } }
        observers+=owner.launch { calendar.runMidnights() }
    }
    internal fun calendarChanged(changed:()->Unit) { epoch.incrementAndGet();changed() }
    suspend fun read():Publication=withContext(Dispatchers.IO) {
        check(!closed && !revoked) { "G0_RECURRING_PRESENTATION_REVOKED" };authority.checkLease(bindingLease);runtime.acquire(access)
        beforeReadForTest() // Test coordination only, before any read/account/database resource.
        suspend fun measured():Publication {
            // Two attempts maximum; repeated invalidation is visible recovery, not an infinite queue.
            repeat(2) {
                val before=epoch.get();reader.nowForTest=nowForTest
                val batch=reader.read(ids,nowForTest());afterReadForTest()
                var result:Publication?=null
                if(reader.publish(batch) { publication ->
                    result=Publication(publication.content) { !closed && !revoked && before==epoch.get() && runCatching { authority.checkLease(bindingLease) }.isSuccess && publication.valid() }
                }) result?.takeIf { it.valid() }?.let { return it }
                currentCoroutineContext().ensureActive()
            }
            error("G0_RECURRING_PRESENTATION_STALE")
        }
        traceForTest?.run { measured() } ?: measured()
    }
    fun close() {
        closed=true;epoch.incrementAndGet();reader.close()
        observers.forEach { it.cancel() };calendarObservation?.close();calendarObservation=null
        authorityObservation?.close();authorityObservation=null
    }
}

/** One bounded read at a time. Change signals never certify data; every result is freshly published. */
internal class G0RecurringInteractionSession(
    private val port:G0RecurringInteractionAdapter,private val owner:CoroutineScope,
    private val foreground:StateFlow<G0InteractionSession.State>
) {
    enum class Phase { CLOSED, LOADING, READY, REFRESHING, CANCELLED, RECOVERY }
    data class State(val version:Long=0,val phase:Phase=Phase.CLOSED,
        val publication:G0RecurringInteractionAdapter.Publication?=null,val current:()->Boolean={true}) {
        fun renderable()=current() && (publication?.valid?.invoke() ?: true)
    }
    private val monitor=Any()
    private val mutable=MutableStateFlow(State())
    val state=mutable.asStateFlow()
    private var serial=0L
    private var generation=0L
    private var closed=false
    private var active=false
    private var dirty=false
    private var work:Job?=null
    internal var deferDuringSaveForTest=true
    init { port.observe(::changed,::revoke) }
    private fun publish(phase:Phase,result:G0RecurringInteractionAdapter.Publication?=null) {
        val issued=generation
        mutable.value=State(++serial,phase,result) { synchronized(monitor) { !closed && issued==generation } }
    }
    fun open()=synchronized(monitor) {
        if(closed) return@synchronized
        generation++;active=true;dirty=true
        val previous=work;previous?.cancel();publish(Phase.LOADING)
        launchRead(previous)
    }
    internal fun changed()=synchronized(monitor) {
        if(closed || !active) return@synchronized
        dirty=true
        if(mutable.value.publication?.valid?.invoke()==false) publish(Phase.REFRESHING)
        if(work?.isActive!=true) launchRead(null)
    }
    private fun launchRead(previous:Job?) {
        val issued=generation
        // LAZY establishes the job before a synchronous Main dispatcher can complete it.
        work=owner.launch(start=CoroutineStart.LAZY) {
            previous?.join()
            try {
                if(deferDuringSaveForTest && foreground.value.phase==G0InteractionSession.Phase.SAVING)
                    withTimeoutOrNull(500) { foreground.first { it.phase!=G0InteractionSession.Phase.SAVING } }
                repeat(2) {
                    synchronized(monitor) { if(closed || !active || generation!=issued) return@launch;dirty=false }
                    val result=port.read()
                    synchronized(monitor) {
                        if(closed || !active || generation!=issued) return@launch
                        if(result.valid()) { dirty=false;publish(Phase.READY,result);return@launch }
                        dirty=true
                    }
                }
                synchronized(monitor) { if(!closed && generation==issued) { active=false;publish(Phase.RECOVERY) } }
            } catch(cancel:CancellationException) { throw cancel }
            catch(_:Exception) { synchronized(monitor) {
                if(!closed && generation==issued) { active=false;publish(Phase.RECOVERY) }
            } }
            finally {
                // A commit between publication and job completion cannot lose its wakeup.
                synchronized(monitor) {
                    if(!closed && active && generation==issued && dirty) { dirty=false;launchRead(null) }
                }
            }
        }.also { it.start() }
    }
    fun cancel()=synchronized(monitor) {
        if(closed) return@synchronized
        generation++;active=false;dirty=false;work?.cancel();publish(Phase.CANCELLED)
    }
    private fun revoke()=synchronized(monitor) {
        if(closed) return@synchronized
        generation++;active=false;dirty=false;work?.cancel();publish(Phase.RECOVERY)
    }
    fun detach()=synchronized(monitor) {
        if(!closed) { generation++;active=false;dirty=false;work?.cancel();publish(Phase.CLOSED);closed=true;port.close() }
    }
    suspend fun awaitSettled() { while(true) { val latest=synchronized(monitor) { work };latest?.join();if(latest===synchronized(monitor) { work }) return } }
}
