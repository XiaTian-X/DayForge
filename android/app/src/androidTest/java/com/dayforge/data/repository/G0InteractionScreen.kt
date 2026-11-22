package com.dayforge.data.repository

import android.graphics.Bitmap
import android.util.Log
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Screen-pixel observation, not a claim of exact first presentation time. No private payload logged. */
internal class G0ScreenObservation {
    data class Draw(val version: Long,val at:Long)
    data class Seen(val version:Long,val captureStarted:Long,val observed:Long,val attempts:Int)
    data class Input(val key:String,val started:Long,val downReturned:Long,val upStarted:Long,val upReturned:Long)
    data class Click(val key:String,val at:Long)
    private val frameCodes=ConcurrentHashMap<Long,Long>()
    fun encodedVersion(version:Long):Long=frameCodes.computeIfAbsent(version) {
        nextFrameCode.incrementAndGet().also { code -> check(code<1L.shl(24)) { "G0_FRAME_CODE_EXHAUSTED" } }
    }
    val inputs=CopyOnWriteArrayList<Input>()
    val clicks=CopyOnWriteArrayList<Click>()
    fun clicked(key:String) { clicks+=Click(key,SystemClock.elapsedRealtimeNanos()) }
    val bounds=ConcurrentHashMap<String,Rect>()
    val draws=CopyOnWriteArrayList<Draw>()
    @Volatile private var marker:Rect?=null
    fun placed(rect:Rect) { marker=rect }
    /** Keep the pre-layout-change coordinates for a negative pixel assertion. A
     * marker moving to another row must not be mistaken for clearing old pixels. */
    fun frozenMarker(version:Long):(Bitmap)->Boolean {
        val rect=requireNotNull(marker)
        return {bitmap->matches(bitmap,rect,version)}
    }
    fun drawn(version:Long) { if(draws.none { it.version==version }) draws+=Draw(version,SystemClock.elapsedRealtimeNanos()) }
    fun tap(key:String):Long {
        val rect=requireNotNull(bounds[key]) { "G0_SCREEN_BUTTON_NOT_LAID_OUT:$key" }
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val down=SystemClock.uptimeMillis();val start=SystemClock.elapsedRealtimeNanos()
        val a=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,rect.center.x,rect.center.y,0)
        val b=MotionEvent.obtain(down,down+1,MotionEvent.ACTION_UP,rect.center.x,rect.center.y,0)
        try {
            check(automation.injectInputEvent(a,true))
            val downReturned=SystemClock.elapsedRealtimeNanos()
            val upStarted=SystemClock.elapsedRealtimeNanos()
            check(automation.injectInputEvent(b,true))
            inputs+=Input(key,start,downReturned,upStarted,SystemClock.elapsedRealtimeNanos())
        }
        finally { a.recycle();b.recycle() }
        return start
    }
    suspend fun await(version:Long):Seen = awaitAny { listOf(version) }
    // A brief intermediate state need not reach a frame; accept a later verified saved result as well.
    suspend fun awaitAny(versions:()->List<Long>):Seen = withTimeout(15_000) {
        var attempts=0
        while(true) {
            val rect=marker
            if(rect!=null) {
                val start=SystemClock.elapsedRealtimeNanos()
                val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                val end=SystemClock.elapsedRealtimeNanos();attempts++
                if(bitmap!=null) {
                    val version=try { versions().firstOrNull { matches(bitmap,rect,it) } } finally { bitmap.recycle() }
                    if(version!=null) {
                        Log.i("G0ScreenSeen","{\"version\":$version,\"capture_start_ns\":$start,\"observed_ns\":$end,\"attempts\":$attempts}")
                        return@withTimeout Seen(version,start,end,attempts)
                    }
                }
            }
            delay(16)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    data class Target(val observation:G0ScreenObservation,val version:Long,val valid:()->Boolean={true})
    companion object {
        private val nextFrameCode=java.util.concurrent.atomic.AtomicLong()
        internal fun matchesTogether(bitmap:Bitmap,targets:List<Target>):Boolean =
            targets.isNotEmpty() && targets.map { it.observation }.distinct().size==targets.size &&
                targets.all { target -> target.valid() && target.observation.marker?.let {
                    target.observation.matches(bitmap,it,target.version)
                }==true }
        suspend fun awaitTogether(targets:()->List<Target>?):Seen=withTimeout(15_000) {
            var attempts=0
            while(true) {
                val expected=targets()
                if(expected!=null) {
                    val start=SystemClock.elapsedRealtimeNanos()
                    val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    val end=SystemClock.elapsedRealtimeNanos();attempts++
                    val matched=bitmap?.let { try { matchesTogether(it,expected) } finally { it.recycle() } } ?: false
                    if(matched) {
                        Log.i("G0FrameSeen","{\"versions\":${expected.map { it.version }},\"capture_start_ns\":$start,\"observed_ns\":$end,\"attempts\":$attempts}")
                        return@withTimeout Seen(expected.first().version,start,end,attempts)
                    }
                }
                delay(16)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }
    private fun matches(bitmap:Bitmap,rect:Rect,version:Long):Boolean {
        fun pixel(index:Int):Int {
            val x=(rect.left+rect.width*(index+0.5f)/26).toInt();val y=(rect.top+rect.height/2).toInt()
            if(x !in 0 until bitmap.width || y !in 0 until bitmap.height) return 0
            return bitmap.getPixel(x,y) and 0xffffff
        }
        if(pixel(0)!=0xff00ff || pixel(25)!=0x00ffff) return false
        return (0 until 24).all { pixel(it+1)==if((encodedVersion(version) shr it) and 1L==1L) 0xffffff else 0x000000 }
    }
}

@Composable
internal fun G0InteractionScreen(session:G0InteractionSession, observation:G0ScreenObservation, stableFields:Boolean=false,
    onConfirmDecision:((Boolean)->Unit)?=null) {
    val state = session.state.collectAsState().value // Freeze the exact content version for its draw marker.
    val renderable=state.renderable()
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(20.dp).drawWithContent {
                // Recheck at DRAW as well as composition; stale authority cannot borrow a queued frame.
                if(state.renderable()) drawContent() else drawRect(Color.Black)
            }) {
                Box(Modifier.fillMaxWidth().height(12.dp).onGloballyPositioned { observation.placed(it.boundsInWindow()) }
                    .drawWithContent {
                        if(state.renderable()) {
                            val width=size.width/26
                            for(i in 0 until 26) {
                                val color=when(i) { 0->Color.Magenta;25->Color.Cyan
                                    else->if((observation.encodedVersion(state.version) shr (i-1)) and 1L==1L) Color.White else Color.Black }
                                drawRect(color,Offset(i*width,0f),Size(width+0.1f,size.height))
                            }
                            observation.drawn(state.version)
                        }
                    })
                Spacer(Modifier.height(16.dp))
                Text("指标编辑 · G0隔离验证",style=MaterialTheme.typography.titleLarge)
                Text(when(state.phase) {
                    G0InteractionSession.Phase.CLOSED->"尚未打开"
                    G0InteractionSession.Phase.LOADING->"正在验证，尚不可编辑"
                    G0InteractionSession.Phase.REFRESHING->"内容有更新，正在核验"
                    G0InteractionSession.Phase.VIEWING->"已验证内容"
                    G0InteractionSession.Phase.EDITING->"可编辑"
                    G0InteractionSession.Phase.SAVING->"正在保存"
                    G0InteractionSession.Phase.SAVED_REFRESHING->"已保存，正在更新显示"
                    G0InteractionSession.Phase.SAVED->"已保存，待同步"
                    G0InteractionSession.Phase.SYNCING->"已保存，正在同步"
                    G0InteractionSession.Phase.SYNCED->"已同步"
                    G0InteractionSession.Phase.SYNC_RECOVERY->"已保存，同步结果待恢复，请勿重复保存"
                    G0InteractionSession.Phase.CANCELLED->"已取消"
                    G0InteractionSession.Phase.RECOVERY->"需要重新验证或恢复，请勿重复保存"
                    G0InteractionSession.Phase.SAVED_REFRESH_FAILED->"已保存，显示更新失败，请勿重复保存"
                })
                if(stableFields || (renderable && state.publication!=null)) {
                    // Retain only the control structure. Busy/revoked frames never reuse personal values.
                    val showPersonal=renderable && state.publication!=null
                    OutlinedTextField(if(showPersonal) state.name else "",{session.draft(it,state.description)},label={Text("名称")},
                        enabled=showPersonal && state.phase==G0InteractionSession.Phase.EDITING,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(if(showPersonal) state.description else "",{session.draft(state.name,it)},label={Text("描述")},
                        enabled=showPersonal && state.phase==G0InteractionSession.Phase.EDITING,modifier=Modifier.fillMaxWidth())
                    Text(if(showPersonal) "待同步：${state.publication!!.value.pending}" else "待同步：—")
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    Button(onClick={observation.clicked("open");session.open()},enabled=state.phase in setOf(G0InteractionSession.Phase.CLOSED,G0InteractionSession.Phase.CANCELLED),
                        modifier=Modifier.onGloballyPositioned { observation.bounds["open"]=it.boundsInWindow() }) { Text("打开编辑") }
                    Button(onClick={observation.clicked("save");val accepted=session.confirm();onConfirmDecision?.invoke(accepted)},enabled=renderable && state.phase==G0InteractionSession.Phase.EDITING,
                        modifier=Modifier.onGloballyPositioned { observation.bounds["save"]=it.boundsInWindow() }) { Text("保存") }
                }
                Row {
                    Button(onClick={observation.clicked("cancel");session.cancel()},modifier=Modifier.onGloballyPositioned { observation.bounds["cancel"]=it.boundsInWindow() }) { Text("取消") }
                    Button(onClick={observation.clicked("sync");session.sync()},enabled=state.canSync(),
                        modifier=Modifier.onGloballyPositioned { observation.bounds["sync"]=it.boundsInWindow() }) { Text("同步") }
                }
            }
        }
    }
}
