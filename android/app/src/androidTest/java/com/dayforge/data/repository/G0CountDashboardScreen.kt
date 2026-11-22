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

@Composable
internal fun G0CountDashboardScreen(command:G0CountJourney,home:G0CountDashboard,
    commandSeen:G0ScreenObservation,homeSeen:G0ScreenObservation) = key(command,home) {
    DisposableEffect(home) { home.start(command);onDispose { home.close() } }
    val current=command.state.collectAsState().value
    val dashboard=home.state.collectAsState().value
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(2f).onGloballyPositioned {commandSeen.bounds["command-pane"]=it.boundsInWindow()}) {
            G0CountJourneyScreen(command,commandSeen)
        }
        Column(Modifier.weight(1f).padding(24.dp)) {
            Text(if(dashboard.error!=null) "首页读取失败" else if(G0CountDashboard.compatible(current,dashboard)) "首页结果已核验" else "首页核验中")
            Column(Modifier.drawWithContent {
                if(G0CountDashboard.compatible(current,dashboard)) drawContent() else drawRect(Color.Black)
            }) {
            Box(Modifier.fillMaxWidth().height(12.dp).onGloballyPositioned {homeSeen.placed(it.boundsInWindow())}
                .drawWithContent {
                    if(G0CountDashboard.compatible(current,dashboard)) {
                        homeSeen.drawn(dashboard.version)
                        val width=size.width/26
                        for(i in 0 until 26) drawRect(when(i) {
                            0->Color.Magenta;25->Color.Cyan
                            else->if((homeSeen.encodedVersion(dashboard.version) shr(i-1)) and 1L==1L) Color.White else Color.Black
                        },Offset(i*width,0f),Size(width+0.1f,size.height))
                    }
                })
            Text("首页原读取 · G0隔离验证")
            if(G0CountDashboard.compatible(current,dashboard)) {
                Text("首页已验证计数：${dashboard.batch!!.counts.getValue(current.view!!.habit.id).todayQuantity}")
            }
            }
        }
    }
}
