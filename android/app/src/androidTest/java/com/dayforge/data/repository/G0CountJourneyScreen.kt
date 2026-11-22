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

/** Generic command control first; no unverified personal values while preparation is pending. */
@Composable
internal fun G0CountJourneyScreen(flow:G0CountJourney,seen:G0ScreenObservation) {
    // StateFlow collection remembers its last value across recomposition. A replacement page
    // must not briefly label the previous page's CLOSED/result version with the new observer.
    key(flow) { G0CountJourneyPage(flow,seen) }
}

@Composable
private fun G0CountJourneyPage(flow:G0CountJourney,seen:G0ScreenObservation) {
    DisposableEffect(flow) { flow.opened();onDispose { flow.close() } }
    val state=flow.state.collectAsState().value
    MaterialTheme { Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(24.dp).drawWithContent {
            if(state.renderable()) drawContent() else drawRect(Color.Black)
        }) {
            Box(Modifier.fillMaxWidth().height(12.dp).onGloballyPositioned { seen.placed(it.boundsInWindow()) }
                .drawWithContent {
                    if(state.renderable()) {
                        // Record callback entry, before recording any pixels for this version.
                        seen.drawn(state.version)
                        val width=size.width/26
                        for(i in 0 until 26) drawRect(when(i) {
                            0->Color.Magenta;25->Color.Cyan
                            else->if((seen.encodedVersion(state.version) shr(i-1)) and 1L==1L) Color.White else Color.Black
                        },Offset(i*width,0f),Size(width+0.1f,size.height))
                    }
                })
            Text("计数操作 · G0隔离验证")
            Text(when(state.phase) {
                G0CountJourney.Phase.READ_FAILED->"读取失败，操作前将重新核验"
                G0CountJourney.Phase.READY->"可以记录，操作时验证权限"
                G0CountJourney.Phase.SAVING->"正在保存"
                G0CountJourney.Phase.SAVED_REFRESHING->"已保存，正在核验结果"
                G0CountJourney.Phase.RESULT->"已保存，结果已核验"
                G0CountJourney.Phase.UNCHANGED->"未新增记录，结果已核验"
                G0CountJourney.Phase.RECOVERY->"需要核验操作结果，请勿重复记录"
                G0CountJourney.Phase.SAVED_REFRESH_FAILED->"已保存，结果更新失败，请勿重复记录"
                G0CountJourney.Phase.CLOSED->"已关闭"
            })
            // Keep the actual hit targets stationary when asynchronous initial content arrives.
            // Empty/invalid content reserves the same two rows and carries no personal value.
            val view=state.view?.takeIf { state.renderable() }
            Text(view?.let { "已验证计数：${it.countHistory!!.todayQuantity}" } ?: "计数核验中",maxLines=1)
            Text(view?.let { if(it.countHistory!!.completedToday) "目标已达到" else "目标尚未达到" } ?: " ",maxLines=1)
            Button(onClick={seen.clicked("increment");flow.increment()},
                enabled=state.renderable() && state.phase in setOf(G0CountJourney.Phase.READY,G0CountJourney.Phase.READ_FAILED,G0CountJourney.Phase.RESULT,G0CountJourney.Phase.UNCHANGED),
                modifier=Modifier.onGloballyPositioned { seen.bounds["increment"]=it.boundsInWindow() }) { Text("记录一次") }
            Button(onClick={seen.clicked("close");flow.close()},
                modifier=Modifier.onGloballyPositioned { seen.bounds["close"]=it.boundsInWindow() }) { Text("关闭") }
        }
    } }
}
