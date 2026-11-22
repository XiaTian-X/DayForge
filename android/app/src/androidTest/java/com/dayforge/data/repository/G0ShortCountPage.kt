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
import com.dayforge.data.local.PersonalDataDisplayMemory
import com.dayforge.data.local.TokenManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.takeWhile

/** G0 renderer of ONE count model. Saved is emitted only after reliable COMMIT. The model
 * supplies card/home inputs and the original follow-up claim, not linked-metric prompt UI. */
internal class G0ShortCountPage(private val store:G0ShortCountStore,tokens:TokenManager,private val owner:CoroutineScope,
    private val onCommitted:(G0ShortCountStore.Saved)->Unit={}) {
    enum class Phase { LOADING, READY, VERIFYING, SAVING, SAVED, RESULT, RECOVERY, CLOSED }
    data class State(val version:Long,val phase:Phase,val view:G0ShortCountStore.View?,
        val publishedAt:Long=android.os.SystemClock.elapsedRealtimeNanos())
    /** Per-intent diagnostics only. A prior command's known result must never supply the
     * commit timestamp for a new cancelled/failed intent; refreshes do not update this. */
    data class CommandTiming(val command:G0ShortCountStore.Command,val firstStateVersion:Long,
        val ioStartedAt:Long=0L,val knownCommitAt:Long=0L)
    private val mutable=MutableStateFlow(State(0,Phase.LOADING,null))
    val state=mutable.asStateFlow()
    private val monitor=Any()
    @Volatile private var closed=false
    @Volatile private var cancelled=false
    @Volatile var error:Throwable?=null;private set
    @Volatile var committedAt=0L;private set
    @Volatile var saved:G0ShortCountStore.Saved?=null;private set
    // Identity is retained even when COMMIT's result is unknown. It is not a permission.
    @Volatile var command:G0ShortCountStore.Command?=null;private set
    @Volatile var commandTiming:CommandTiming?=null;private set
    private var nextCommand:G0ShortCountStore.Command?=null
    var trace:G0PhaseTrace?=null
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
    private fun emit(phase:Phase,view:G0ShortCountStore.View?=null)=synchronized(monitor) {
        if(!closed) {val next=State(mutable.value.version+1,phase,view);events+=next;mutable.value=next}
    }
    // Called inside the store's fresh publication boundary. Do not create identities in
    // the click callback while a concurrent COMMIT may be promoting its certificate.
    private fun publishReady(phase:Phase,view:G0ShortCountStore.View)=synchronized(monitor) {
        if(!closed) {nextCommand=store.newCommand();emit(phase,view)}
    }
    suspend fun open() {
        try {store.readAndPublish {publishReady(Phase.READY,it)}} catch(e:Exception) {error=e;emit(Phase.RECOVERY)}
    }
    fun increment():Boolean=synchronized(monitor) {
        if(closed || mutable.value.phase !in setOf(Phase.READY,Phase.RESULT))return false
        val intent=requireNotNull(nextCommand);nextCommand=null;command=intent
        val needsRead=mutable.value.view?.valid()!=true
        // One bounded input slot; accepting intent never authorizes stale data or a write.
        emit(if(needsRead)Phase.VERIFYING else Phase.SAVING)
        var timing=CommandTiming(intent,mutable.value.version)
        commandTiming=timing
        work=owner.launch(Dispatchers.IO,start=CoroutineStart.LAZY) {
            timing=timing.copy(ioStartedAt=android.os.SystemClock.elapsedRealtimeNanos())
            commandTiming=timing
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
                    val value=store.increase(intent);saved=value;committedAt=android.os.SystemClock.elapsedRealtimeNanos()
                    timing=timing.copy(knownCommitAt=committedAt);commandTiming=timing
                    // Trusted runtime handoff, before result reading or rendering. This
                    // is a wake hint for the durable outbox, never a sync-success claim.
                    onCommitted(value)
                    emit(Phase.SAVED);afterCommitForTest()
                    if(!closed)store.readAndPublish(value.factId) {publishReady(Phase.RESULT,it)}
                }
                trace?.run {command()} ?: command()
            } catch(e:Exception) {error=e;emit(Phase.RECOVERY);if(e is CancellationException)throw e}
        }.also {job ->
            job.invokeOnCompletion {failure ->if(failure is CancellationException) {
                // A runtime can cancel siblings after RECOVERY is emitted. Keep the
                // original unknown-commit/auth error instead of masking it with cleanup.
                synchronized(monitor) {if(error==null)error=failure;emit(Phase.RECOVERY)}
            }}
            job.start()
        }
        true
    }
    suspend fun refresh() {
        val source=synchronized(monitor) {saved}
        beforeRefreshForTest()
        store.readAndPublish(source?.factId) {view ->synchronized(monitor) {
            // A pending refresh of the prior command cannot replace the next command's
            // claim, even when both snapshots contain the same up-to-date total.
            if(saved==source && mutable.value.phase in setOf(Phase.READY,Phase.RESULT))publishReady(mutable.value.phase,view)
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
    /** Leaving a page does not retract an already admitted business intent. Its trusted
     * runtime retains ownership until settled; revocation/shutdown still calls close. */
    fun detach()=synchronized(monitor) {
        if(!closed) {closed=true;nextCommand=null;observerStop.value=true;mutable.value=State(mutable.value.version+1,Phase.CLOSED,null)}
    }
    fun close()=synchronized(monitor) {
        cancelled=true;detach();work?.cancel();observer?.cancel()
    }
}

@Composable
internal fun G0ShortCountScreen(page:G0ShortCountPage,seen:G0ScreenObservation,detachOnDispose:Boolean=false) {
    key(page) {
        val state=page.state.collectAsState().value
        DisposableEffect(page,detachOnDispose) {onDispose {if(detachOnDispose)page.detach() else page.close()}}
        MaterialTheme {Surface(Modifier.fillMaxSize()) {Column(Modifier.padding(24.dp)) {
            Box(Modifier.fillMaxWidth().height(12.dp).onGloballyPositioned {seen.placed(it.boundsInWindow())}.drawWithContent {
                if(state.phase==G0ShortCountPage.Phase.CLOSED || page.live() && (state.view==null || state.view.valid())) {
                    seen.drawn(state.version);val width=size.width/26
                    for(i in 0 until 26)drawRect(when(i) {0->Color.Magenta;25->Color.Cyan
                        else->if((seen.encodedVersion(state.version) shr(i-1)) and 1L==1L)Color.White else Color.Black},
                        Offset(i*width,0f),Size(width+0.1f,size.height))
                }
            })
            Text("计数读模型 · G0隔离验证")
            val view=state.view?.takeIf {page.live() && it.valid()}
            Column(Modifier.drawWithContent {if(page.live() && (state.view==null || state.view.valid()))drawContent()}) {
                Text(view?.let {"卡片：${it.history.todayQuantity}"} ?: "卡片核验中")
                Text(view?.let {"首页：${it.history.todayQuantity} / ${it.history.todayPolicy!!.targetValue}"} ?: "首页核验中")
                Text(when(state.phase) {
                    G0ShortCountPage.Phase.SAVED,G0ShortCountPage.Phase.RESULT->"已保存"
                    G0ShortCountPage.Phase.VERIFYING->"正在核验，请稍候"
                    G0ShortCountPage.Phase.SAVING->"正在保存"
                    G0ShortCountPage.Phase.RECOVERY->"需要核验结果，请勿重复记录"
                    else->" "
                })
            }
            Button(onClick={seen.clicked("increment");page.increment()},
                enabled=page.live() && state.phase in setOf(G0ShortCountPage.Phase.READY,G0ShortCountPage.Phase.RESULT) && view!=null,
                modifier=Modifier.onGloballyPositioned {seen.bounds["increment"]=it.boundsInWindow()}) {Text("记录一次")}
        }}}
    }
}
