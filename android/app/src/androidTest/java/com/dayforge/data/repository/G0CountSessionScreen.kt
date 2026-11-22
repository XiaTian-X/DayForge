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

/** Screen departure retires only its page. A trusted outer owner explicitly shuts
 * down the runtime. Each page gets separate pixel codes, including its initial v1. */
@Composable
internal fun G0CountSessionScreen(runtime:G0CountSessionRuntime,seen:G0ScreenObservation,
    pageSeen:(G0ShortCountPage)->G0ScreenObservation) {
    val state=runtime.state.collectAsState().value
    val page=state.page
    val pageState=page?.let {key(it) {it.state.collectAsState().value}}
    // Observe the existing gate only after a page is installed. A hint wakes the
    // guard even while fresh reading is still in progress; it never admits pixels.
    val changes=page?.let {runtime.displayChanges()}
    val observedChange=changes?.let {key(page) {it.collectAsState().value}}
    fun currentFrame():Boolean = page!=null && pageState!=null &&
        runtime.state.value.version==state.version && runtime.state.value.page===page &&
        page.state.value.version==pageState.version && changes?.value==observedChange &&
        runtime.renderable()
    DisposableEffect(runtime) {onDispose {runtime.leavePage()}}
    MaterialTheme {Surface(Modifier.fillMaxSize()) {Column(Modifier.padding(16.dp)) {
        Box(Modifier.fillMaxWidth().height(12.dp).onGloballyPositioned {seen.placed(it.boundsInWindow())}.drawWithContent {
            if(state.phase!=G0CountSessionRuntime.Phase.OPEN || currentFrame()) {
                seen.drawn(state.version);val width=size.width/26
                for(i in 0 until 26)drawRect(when(i) {0->Color.Magenta;25->Color.Cyan
                    else->if((seen.encodedVersion(state.version) shr(i-1)) and 1L==1L)Color.White else Color.Black},
                    Offset(i*width,0f),Size(width+0.1f,size.height))
            }
        })
        when(state.phase) {
            G0CountSessionRuntime.Phase.NEW,G0CountSessionRuntime.Phase.BUSY->{
                if(state.phase==G0CountSessionRuntime.Phase.BUSY)Text("正在结束上一次准备，请稍后重试")
                SessionButton("open","打开计数页面",seen) {runtime.openPage()}
            }
            G0CountSessionRuntime.Phase.LOADING->{
                Text("正在准备页面，请稍候")
                SessionButton("leave","取消",seen) {runtime.leavePage()}
            }
            G0CountSessionRuntime.Phase.OPEN->{
                SessionButton("leave","返回",seen) {runtime.leavePage()}
                page?.let {currentPage->
                    val visible=currentFrame()
                    if(!visible)Text("正在核验页面，请稍候")
                    // Keep the screen composed during an ACK invalidation. Removing it
                    // would run onDispose and detach the observer needed for fresh data.
                    Box(Modifier.drawWithContent {if(currentFrame())drawContent()}) {
                        G0ShortCountScreen(currentPage,pageSeen(currentPage),detachOnDispose=true)
                    }
                }
            }
            G0CountSessionRuntime.Phase.LEAVING->{
                Text("正在结束页面操作，请稍候")
                SessionButton("reopen","重新打开",seen) {runtime.openPage()}
            }
            G0CountSessionRuntime.Phase.AWAY->{
                Text("已离开计数页面")
                SessionButton("reopen","重新打开",seen) {runtime.openPage()}
            }
            G0CountSessionRuntime.Phase.RECOVERY->Text("需要核验结果，请勿重复记录")
            G0CountSessionRuntime.Phase.CLOSED->Text("已关闭计数会话")
        }
    }}}
}

@Composable
private fun SessionButton(key:String,label:String,seen:G0ScreenObservation,action:()->Unit) {
    Button(onClick={seen.clicked(key);action()},modifier=Modifier.onGloballyPositioned {
        seen.bounds[key]=it.boundsInWindow()
    }) {Text(label)}
}
