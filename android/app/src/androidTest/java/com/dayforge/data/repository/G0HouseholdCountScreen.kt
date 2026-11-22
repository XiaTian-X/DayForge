package com.dayforge.data.repository

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp

/** Per-page diagnostic observer. Only an actually drawn, current command's anonymous
 * busy marker is recorded. Generic loading and saved/result frames use other observers.
 */
internal class G0HouseholdFeedbackObservation {
    data class Draw(val version:Long,val commandVersion:Long,val phase:G0HouseholdCountPage.Phase,
        val at:Long,val knownCommitAt:Long)
    val pixels=G0ScreenObservation()
    val draws=java.util.concurrent.CopyOnWriteArrayList<Draw>()
    internal fun drawn(state:G0HouseholdCountPage.State,timing:G0HouseholdCountPage.CommandTiming) {
        if(draws.none {it.version==state.version}) {
            pixels.drawn(state.version)
            val frame=pixels.draws.single {it.version==state.version}
            draws+=Draw(state.version,timing.firstStateVersion,state.phase,frame.at,timing.knownCommitAt)
        }
    }
}

/** Pixel witnesses for semantic feedback, never its authorization. Each draw binds
 * the current state and the independently identified accepted or refused command. */
internal class G0HouseholdCommandFeedbackObservation {
    enum class Kind { NOTICE, ATTEMPT }
    data class Draw(val kind:Kind,val stateVersion:Long,val firstStateVersion:Long,val requestId:String,
        val firstSequence:Long?,val attemptSequence:Long?,val count:Long?,val requestedHabitUuid:String?,val at:Long)
    val noticePixels=G0ScreenObservation()
    val attemptPixels=G0ScreenObservation()
    val draws=java.util.concurrent.CopyOnWriteArrayList<Draw>()
    fun noticeTarget(runtime:G0HouseholdCountRuntime,outer:G0HouseholdCountRuntime.State,
        state:G0HouseholdCountPage.State):G0ScreenObservation.Target? {
        val notice=state.commitNotice ?: return null
        return G0ScreenObservation.Target(noticePixels,state.version) {
            state.commitNotice===notice && runtime.commandFeedbackRenderable(outer,state)
        }
    }
    fun attemptTarget(runtime:G0HouseholdCountRuntime,outer:G0HouseholdCountRuntime.State,
        state:G0HouseholdCountPage.State):G0ScreenObservation.Target? {
        val attempt=state.attemptReceipt ?: return null
        return G0ScreenObservation.Target(attemptPixels,state.version) {
            state.attemptReceipt===attempt && runtime.commandFeedbackRenderable(outer,state)
        }
    }
    internal fun drawn(kind:Kind,state:G0HouseholdCountPage.State) {
        if(draws.any {it.kind==kind && it.stateVersion==state.version})return
        val pixels=if(kind==Kind.NOTICE)noticePixels else attemptPixels
        pixels.drawn(state.version)
        val at=pixels.draws.single {it.version==state.version}.at
        val active=requireNotNull(state.intent)
        val attempt=state.attemptReceipt.takeIf {kind==Kind.ATTEMPT}
        draws+=Draw(kind,state.version,active.firstStateVersion,active.command.requestId,
            attempt?.firstSequence,attempt?.sequence,attempt?.count,attempt?.requestedHabitUuid,at)
    }
}

/** Explicit G0 household view: two cards and the home summary share one verified
 * version. Generic progress has a fixed slot; it never claims a successful save. */
@Composable
internal fun G0HouseholdCountScreen(page:G0HouseholdCountPage,seen:G0ScreenObservation,
    arrival:G0HouseholdArrivalObservation?=null,commandFeedback:Boolean=false,
    busyAllowed:(G0HouseholdCountPage.State)->Boolean={false},countAction:((String,Boolean)->Boolean)?=null) {
    key(page) {
        val state=page.state.collectAsState().value
        DisposableEffect(page) {onDispose {page.detach()}}
        fun current()=page.live() && page.state.value.version==state.version &&
            (state.view==null || state.view.valid())
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            HouseholdMarker(seen,state.version,::current)
            Text("家庭计数 · G0隔离验证")
            val view=state.view?.takeIf {current()}
            state.members.forEachIndexed {index,id->
                val card=view?.cards?.get(id)
                Box(Modifier.fillMaxWidth().height(40.dp).drawWithContent {if(current())drawContent()}) {
                    Text(card?.let {"${it.habit.name}：${it.history.todayQuantity} / ${it.history.todayPolicy!!.targetValue}"}
                        ?: "计数${index+1}核验中")
                }
                val enabled=page.live() && (state.phase in setOf(G0HouseholdCountPage.Phase.READY,G0HouseholdCountPage.Phase.RESULT) ||
                    (commandFeedback && state.phase in setOf(G0HouseholdCountPage.Phase.VERIFYING,G0HouseholdCountPage.Phase.SAVING,
                        G0HouseholdCountPage.Phase.SAVED) && busyAllowed(state)))
                Button(onClick={seen.clicked("increment:$id");if(countAction==null)page.increment(id,reportBusy=commandFeedback)
                    else countAction(id,commandFeedback)},enabled=enabled,
                    modifier=Modifier.onGloballyPositioned {
                        seen.bounds["increment:$id"]=it.boundsInWindow()
                        seen.bounds["viewport:increment:$id"]=Rect(it.positionInWindow(),Size(it.size.width.toFloat(),it.size.height.toFloat()))
                    }
                        .then(if(arrival==null)Modifier else Modifier.drawWithContent {
                            drawContent()
                            arrival.buttonDrawn(id,state,page.commandTiming?.firstStateVersion,enabled,current())
                        })) {
                    Text("记录${index+1}一次")
                }
            }
            Box(Modifier.fillMaxWidth().height(40.dp).onGloballyPositioned {
                    seen.bounds["viewport:home"]=Rect(it.positionInWindow(),Size(it.size.width.toFloat(),it.size.height.toFloat()))
                }
                .drawWithContent {if(current())drawContent()}) {
                Text(view?.let {v->"首页："+state.members.joinToString("，") {id->
                    v.cards.getValue(id).let {"${it.habit.name}=${it.history.todayQuantity}"}
                }} ?: "首页核验中")
            }
            Box(Modifier.fillMaxWidth().height(32.dp)) {
                Text(when(state.phase) {
                    G0HouseholdCountPage.Phase.SAVED,G0HouseholdCountPage.Phase.RESULT->if(commandFeedback)" " else "已保存"
                    G0HouseholdCountPage.Phase.RECOVERY->"需要核验结果，请勿重复记录"
                    else->" "
                })
            }
        }
    }
}

@Composable
internal fun G0HouseholdCountRuntimeScreen(runtime:G0HouseholdCountRuntime,seen:G0ScreenObservation,
    pageSeen:(G0HouseholdCountPage)->G0ScreenObservation,
    feedbackSeen:((G0HouseholdCountPage)->G0HouseholdFeedbackObservation)?=null,
    arrivalSeen:((G0HouseholdCountPage)->G0HouseholdArrivalObservation)?=null,
    commandFeedback:Boolean=false,commandSeen:((G0HouseholdCountPage)->G0HouseholdCommandFeedbackObservation)?=null,
    countAction:((G0HouseholdCountPage,String,Boolean)->Boolean)?=null) {
    val state=runtime.state.collectAsState().value
    val page=state.page
    val pageState=page?.let {key(it) {it.state.collectAsState().value}}
    val changes=page?.let {runtime.displayChanges()}
    val observedChange=changes?.let {key(page) {it.collectAsState().value}}
    val feedback=page?.let {key(it) {remember(it,feedbackSeen) {feedbackSeen?.invoke(it) ?: G0HouseholdFeedbackObservation()}}}
    val arrival=page?.let {key(it) {remember(it,arrivalSeen) {arrivalSeen?.invoke(it)}}}
    val commandObservation=page?.let {key(it) {remember(it,commandSeen) {
        commandSeen?.invoke(it) ?: G0HouseholdCommandFeedbackObservation()
    }}}
    fun currentFrame():Boolean = page!=null && pageState!=null &&
        runtime.state.value.version==state.version && runtime.state.value.page===page &&
        page.state.value.version==pageState.version && changes?.value==observedChange && runtime.renderable()
    DisposableEffect(runtime) {onDispose {runtime.leavePage()}}
    MaterialTheme {Surface(Modifier.fillMaxSize()) {Column(Modifier.padding(16.dp)) {
        HouseholdMarker(seen,state.version) {state.phase!=G0HouseholdCountRuntime.Phase.OPEN || currentFrame()}
        when(state.phase) {
            G0HouseholdCountRuntime.Phase.NEW,G0HouseholdCountRuntime.Phase.BUSY->{
                if(state.phase==G0HouseholdCountRuntime.Phase.BUSY)Text("正在结束上一次准备")
                HouseholdButton("open","打开家庭页面",seen) {runtime.openPage()}
            }
            G0HouseholdCountRuntime.Phase.LOADING->{
                Text("正在准备家庭页面")
                HouseholdButton("leave","取消",seen) {runtime.leavePage()}
            }
            G0HouseholdCountRuntime.Phase.OPEN->{
                HouseholdButton("leave","返回",seen) {runtime.leavePage()}
                // This fixed, anonymous slot is outside every private-content guard.
                // Its own draw check retains runtime/page/command identity and the
                // continuous authority lifetime; no gate hint authorizes it.
                // Precommit busy and postcommit notice are mutually exclusive and
                // share one slot. The separate refusal slot never implies a save.
                Box(Modifier.fillMaxWidth().height(44.dp)) {
                    HouseholdBusyFeedback(runtime,state,pageState,feedback)
                    HouseholdCommandFeedback(runtime,state,pageState,commandObservation,commandFeedback,
                        G0HouseholdCommandFeedbackObservation.Kind.NOTICE)
                }
                // Both strategies reserve this space; baseline keeps it empty.
                HouseholdCommandFeedback(runtime,state,pageState,commandObservation,commandFeedback,
                    G0HouseholdCommandFeedbackObservation.Kind.ATTEMPT)
                Box(Modifier.fillMaxWidth().height(24.dp)) {if(!currentFrame())Text("正在核验页面")}
                page?.let {currentPage->
                    // Keep the page composed while invalid, so its owned observer
                    // can publish a new version. The gate flow only wakes drawing.
                    Box(Modifier.drawWithContent {
                        val allowed=currentFrame()
                        pageState?.let {arrival?.privateDrawn(state.version,it,currentPage.commandTiming?.firstStateVersion,allowed)}
                        if(allowed)drawContent()
                    }) {
                        G0HouseholdCountScreen(currentPage,pageSeen(currentPage),arrival,commandFeedback,
                            busyAllowed={runtime.commandFeedbackRenderable(state,it)},
                            countAction=countAction?.let {action->{id,reportBusy->action(currentPage,id,reportBusy)}})
                    }
                }
            }
            G0HouseholdCountRuntime.Phase.LEAVING,G0HouseholdCountRuntime.Phase.AWAY->{
                Text(if(state.phase==G0HouseholdCountRuntime.Phase.LEAVING)"正在结束页面操作" else "已离开家庭页面")
                HouseholdButton("reopen","重新打开",seen) {runtime.openPage()}
            }
            G0HouseholdCountRuntime.Phase.RECOVERY->Text("需要核验结果，请勿重复记录")
            G0HouseholdCountRuntime.Phase.CLOSED->Text("已关闭家庭会话")
        }
    }}}
}

@Composable
private fun HouseholdCommandFeedback(runtime:G0HouseholdCountRuntime,outer:G0HouseholdCountRuntime.State,
    state:G0HouseholdCountPage.State?,seen:G0HouseholdCommandFeedbackObservation?,enabled:Boolean,
    kind:G0HouseholdCommandFeedbackObservation.Kind) {
            val target=if(!enabled || state==null || seen==null)null else when(kind) {
                G0HouseholdCommandFeedbackObservation.Kind.NOTICE->seen.noticeTarget(runtime,outer,state)
                G0HouseholdCommandFeedbackObservation.Kind.ATTEMPT->seen.attemptTarget(runtime,outer,state)
            }
            fun allowed()=target?.valid?.invoke()==true
            Column(Modifier.fillMaxWidth().height(44.dp).drawWithContent {if(allowed())drawContent()}) {
                val pixels=target?.observation
                Box(Modifier.fillMaxWidth().height(12.dp).onGloballyPositioned {pixels?.placed(it.boundsInWindow())}
                    .drawWithContent {
                        if(allowed()) {
                            val current=requireNotNull(state);val observation=requireNotNull(seen);val marker=requireNotNull(pixels)
                            observation.drawn(kind,current);val width=size.width/26
                            for(i in 0 until 26)drawRect(when(i) {0->Color.Magenta;25->Color.Cyan
                                else->if((marker.encodedVersion(current.version) shr(i-1)) and 1L==1L)Color.White else Color.Black},
                                Offset(i*width,0f),Size(width+0.1f,size.height))
                        }
                    })
                Text(if(!allowed())" " else when(kind) {
                    G0HouseholdCommandFeedbackObservation.Kind.NOTICE->if(state?.phase==G0HouseholdCountPage.Phase.SAVED)
                        "上一笔已保存在此设备，正在更新" else "上一笔已保存在此设备"
                    G0HouseholdCommandFeedbackObservation.Kind.ATTEMPT->state?.attemptReceipt?.let {
                        if(it.count==1L)"本次点击未记录，请稍后再试" else "刚才${it.count}次点击未记录，请稍后再试"
                    } ?: " "
                })
            }
}

@Composable
private fun HouseholdBusyFeedback(runtime:G0HouseholdCountRuntime,outer:G0HouseholdCountRuntime.State,
    state:G0HouseholdCountPage.State?,seen:G0HouseholdFeedbackObservation?) {
    val timing=outer.page?.commandTiming
    fun allowed()=state!=null && timing!=null && seen!=null && runtime.feedbackRenderable(outer,state,timing)
    Column(Modifier.fillMaxWidth().height(44.dp).drawWithContent {if(allowed())drawContent()}) {
        Box(Modifier.fillMaxWidth().height(12.dp).onGloballyPositioned {seen?.pixels?.placed(it.boundsInWindow())}.drawWithContent {
            if(allowed()) {
                val current=requireNotNull(state);val observation=requireNotNull(seen)
                observation.drawn(current,requireNotNull(timing));val width=size.width/26
                for(i in 0 until 26)drawRect(when(i) {0->Color.Magenta;25->Color.Cyan
                    else->if((observation.pixels.encodedVersion(current.version) shr(i-1)) and 1L==1L)Color.White else Color.Black},
                    Offset(i*width,0f),Size(width+0.1f,size.height))
            }
        })
        Text(if(allowed())when(state?.phase) {
            G0HouseholdCountPage.Phase.VERIFYING->"正在核验，请稍候"
            G0HouseholdCountPage.Phase.SAVING->"正在保存"
            else->" "
        } else " ")
    }
}

@Composable
private fun HouseholdButton(key:String,label:String,seen:G0ScreenObservation,action:()->Unit) {
    Button(onClick={seen.clicked(key);action()},modifier=Modifier.onGloballyPositioned {
        seen.bounds[key]=it.boundsInWindow()
    }) {Text(label)}
}

@Composable
private fun HouseholdMarker(seen:G0ScreenObservation,version:Long,allowed:()->Boolean) {
    Box(Modifier.fillMaxWidth().height(12.dp).onGloballyPositioned {seen.placed(it.boundsInWindow())}.drawWithContent {
        if(allowed()) {
            seen.drawn(version);val width=size.width/26
            for(i in 0 until 26)drawRect(when(i) {0->Color.Magenta;25->Color.Cyan
                else->if((seen.encodedVersion(version) shr(i-1)) and 1L==1L)Color.White else Color.Black},
                Offset(i*width,0f),Size(width+0.1f,size.height))
        }
    })
}
