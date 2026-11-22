package com.dayforge.data.repository

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.dayforge.data.local.TokenManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Trusted shared connection owner, never given to the renderer. A failed close keeps
 * admission quarantined; a CLOSED screen alone does not permit another cold factory. */
internal class G0CountConnectionOwner(private val closeConnection:suspend ()->Unit) {
    private val occupied=AtomicReference<Any?>()
    fun acquire():Ticket? {
        val identity=Any()
        return if(occupied.compareAndSet(null,identity))Ticket(identity) else null
    }
    inner class Ticket internal constructor(private val identity:Any) {
        private val attempted=AtomicBoolean()
        suspend fun drain() {
            check(occupied.get()===identity && attempted.compareAndSet(false,true))
            closeConnection()
            check(occupied.compareAndSet(identity,null))
        }
    }
    fun busy()=occupied.get()!=null
}

/** One explicit entry. The control scope outlives composition; shutdown joins actual
 * work even when its caller is cancelled. Loading never contains personal data. */
internal class G0CountLoadingEntry(
    private val control:CoroutineScope,
    private val connection:G0CountConnectionOwner,
    private val authority:G0AuthorityOwner,
    private val tokens:TokenManager,
    private val changes:()->StateFlow<Long>,
    private val createStore:suspend ()->G0ShortCountStore
) {
    enum class Phase { NEW, BUSY, LOADING, READY, RECOVERY, CLOSED }
    data class State(val version:Long,val phase:Phase,val page:G0ShortCountPage?=null,
        val publishedAt:Long=android.os.SystemClock.elapsedRealtimeNanos())
    private val guard=Any()
    private val mutable=MutableStateFlow(State(0,Phase.NEW))
    val state=mutable.asStateFlow()
    val events=java.util.concurrent.CopyOnWriteArrayList<State>()
    private var started=false
    @Volatile private var closed=false
    @Volatile private var lease:G0AuthorityOwner.Lease?=null
    private var lifetime:Job?=null
    private var subscription:AutoCloseable?=null
    // Recovery identity is retained after closing, never used as publication authority.
    @Volatile var retiredPage:G0ShortCountPage?=null;private set
    @Volatile var error:Throwable?=null;private set
    @Volatile var drained=false;private set
    @Volatile var drainedAt=0L;private set
    var afterPageOpenForTest:suspend ()->Unit={}
    init {
        val registered=authority.observeReservations {close()}
        synchronized(guard) {if(closed)registered.close() else subscription=registered}
    }
    private fun emit(phase:Phase,page:G0ShortCountPage?=null) {
        val next=State(mutable.value.version+1,phase,page);events+=next;mutable.value=next
    }
    fun renderable():Boolean=!closed && state.value.phase==Phase.READY &&
        runCatching {authority.checkLease(requireNotNull(lease))}.isSuccess &&
        state.value.page?.let {it.live() && (it.state.value.view?.valid()!=false)}==true

    @OptIn(ExperimentalCoroutinesApi::class,DelicateCoroutinesApi::class)
    fun open():Boolean=synchronized(guard) {
        if(started || closed || !control.isActive)return false
        val ticket=connection.acquire() ?: run {emit(Phase.BUSY);return false}
        started=true;emit(Phase.LOADING)
        // ATOMIC installs try/finally even if cancellation wins before dispatch. All
        // cold work runs on IO; open() never performs SQLite/decryption on the caller.
        lifetime=control.launch(Dispatchers.IO,start=CoroutineStart.ATOMIC) {
            var page:G0ShortCountPage?=null
            var pageScope:CoroutineScope?=null
            try {
                currentCoroutineContext().ensureActive()
                val original=authority.lease();lease=original
                val store=createStore()
                currentCoroutineContext().ensureActive();authority.checkLease(original)
                val child=CoroutineScope(SupervisorJob(coroutineContext.job)+Dispatchers.IO);pageScope=child
                val opened=G0ShortCountPage(store,tokens,child);page=opened
                retiredPage=opened
                opened.open()
                // Page.open catches Exception including cancellation. Normal return is
                // not evidence that startup succeeded or that this task remains live.
                currentCoroutineContext().ensureActive();authority.checkLease(original)
                check(opened.error==null && opened.live() && opened.state.value.phase==G0ShortCountPage.Phase.READY &&
                    opened.state.value.view?.valid()==true) {"G0_COUNT_ENTRY_NOT_READY"}
                afterPageOpenForTest()
                currentCoroutineContext().ensureActive()
                opened.watchChanges(changes())
                synchronized(guard) {
                    check(!closed);authority.checkLease(original)
                    check(opened.live() && opened.state.value.view?.valid()==true)
                    emit(Phase.READY,opened)
                }
                awaitCancellation()
            } catch(cancel:CancellationException) {
                synchronized(guard) {if(!closed) {closed=true;emit(Phase.CLOSED)}}
                throw cancel
            } catch(failure:Exception) {
                error=failure
                synchronized(guard) {if(!closed)emit(Phase.RECOVERY)}
            } finally {
                withContext(NonCancellable) {
                    page?.close()
                    try {
                        page?.settled();pageScope?.coroutineContext?.job?.cancelAndJoin()
                        ticket.drain()
                        drainedAt=android.os.SystemClock.elapsedRealtimeNanos();drained=true
                    } catch(failure:Exception) {error=failure}
                    synchronized(guard) {subscription?.close();subscription=null}
                }
            }
        }
        true
    }
    /** Memory-only reservation/exit callback. It does not wait or close SQLite. */
    fun close()=synchronized(guard) {
        if(!closed) {
            closed=true;retiredPage?.close();emit(Phase.CLOSED);lifetime?.cancel()
            subscription?.close();subscription=null
            if(!started) {drained=true;drainedAt=android.os.SystemClock.elapsedRealtimeNanos()}
        }
    }
    suspend fun shutdown():Boolean {
        close()
        withContext(NonCancellable) {synchronized(guard) {lifetime}?.join()}
        return drained
    }
}

/** The page retains its original fresh-read and draw guards. The outer lease is an
 * extra lifetime check, never a cached credential or a promise made by READY. */
@Composable
internal fun G0CountLoadingScreen(entry:G0CountLoadingEntry,seen:G0ScreenObservation,pageSeen:G0ScreenObservation) {
    val state=entry.state.collectAsState().value
    DisposableEffect(entry) {onDispose {entry.close()}}
    MaterialTheme {Surface(Modifier.fillMaxSize()) {Column(Modifier.padding(16.dp)) {
        Box(Modifier.fillMaxWidth().height(12.dp).onGloballyPositioned {seen.placed(it.boundsInWindow())}.drawWithContent {
            if(state.phase!=G0CountLoadingEntry.Phase.READY || entry.renderable()) {
                seen.drawn(state.version);val width=size.width/26
                for(i in 0 until 26)drawRect(when(i) {0->Color.Magenta;25->Color.Cyan
                    else->if((seen.encodedVersion(state.version) shr(i-1)) and 1L==1L)Color.White else Color.Black},
                    Offset(i*width,0f),Size(width+0.1f,size.height))
            }
        })
        when(state.phase) {
            G0CountLoadingEntry.Phase.NEW,G0CountLoadingEntry.Phase.BUSY->{
                if(state.phase==G0CountLoadingEntry.Phase.BUSY)Text("正在结束上一次准备，请稍后重试")
                Button(onClick={seen.clicked("open");entry.open()},
                    modifier=Modifier.onGloballyPositioned {seen.bounds["open"]=it.boundsInWindow()}) {Text("打开计数页面")}
            }
            G0CountLoadingEntry.Phase.LOADING->{
                Text("正在准备页面，请稍候")
                Button(onClick={seen.clicked("cancel");entry.close()},modifier=Modifier.onGloballyPositioned {
                    seen.bounds["cancel"]=it.boundsInWindow()}) {Text("取消")}
            }
            G0CountLoadingEntry.Phase.READY->Box(Modifier.drawWithContent {if(entry.renderable())drawContent()}) {
                state.page?.let {G0ShortCountScreen(it,pageSeen)}
            }
            G0CountLoadingEntry.Phase.RECOVERY->Text("需要重新核验，请重新打开页面")
            G0CountLoadingEntry.Phase.CLOSED->Text("已关闭页面")
        }
    }}}
}
