package com.dayforge.data.repository

import android.os.SystemClock
import android.view.View
import android.view.Window
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.dayforge.ui.components.PostCheckInDialog
import com.dayforge.ui.components.PostCheckInDialogPresentation
import java.util.concurrent.CopyOnWriteArrayList

/** The marker belongs to the actual Dialog window. Never borrow Activity coordinates
 * or infer visibility from a StateFlow transition. Blocked and permitted draws are distinct. */
internal class G0CompletionPromptObservation {
    data class Draw(val version:Long,val generation:Long,val identity:String,val allowed:Boolean,val at:Long,val screenRect:Rect?,
        val viewIdentity:Int,val windowIdentity:Int)
    /** Compose effect lifecycle with actual View/Window identities; not WindowManager callbacks. */
    data class WindowEvent(val identity:String,val viewIdentity:Int,val windowIdentity:Int,val mounted:Boolean,val at:Long)
    data class WaitingDraw(val version:Long,val identity:String,val allowed:Boolean,val viewIdentity:Int,val windowIdentity:Int,val at:Long)
    val pixels=G0ScreenObservation()
    val waitingPixels=G0ScreenObservation()
    val draws=CopyOnWriteArrayList<Draw>()
    val windows=CopyOnWriteArrayList<WindowEvent>()
    val waitingDraws=CopyOnWriteArrayList<WaitingDraw>()
    @Volatile var overflow=false;private set
    @Volatile var dialogViewIdentity:Int?=null;private set
    @Volatile var screenRect:Rect?=null;private set
    @Volatile var actualView:View?=null;private set
    @Volatile var actualWindow:Window?=null;private set
    private var issuer:G0CompletionPromptSession?=null
    @Synchronized internal fun attach(session:G0CompletionPromptSession) {
        check(issuer==null || issuer===session) {"G0_PROMPT_OBSERVER_REUSED"};issuer=session
    }
    internal fun placed(rect:Rect,view:View,window:Window) {
        pixels.placed(rect);waitingPixels.placed(rect);dialogViewIdentity=System.identityHashCode(view)
        actualView=view;actualWindow=window;screenRect=rect
    }
    internal fun window(identity:String,view:View,window:Window,mounted:Boolean) {
        if(windows.size>=4096) {overflow=true;return}
        windows+=WindowEvent(identity,System.identityHashCode(view),System.identityHashCode(window),mounted,SystemClock.elapsedRealtimeNanos())
    }
    internal fun drawn(expected:G0CompletionPromptSession.Expected,allowed:Boolean,view:View,window:Window) {
        if(draws.any {it.version==expected.version && it.allowed==allowed})return
        if(draws.size>=4096) {overflow=true;return}
        if(allowed)pixels.drawn(expected.version)
        draws+=Draw(expected.version,expected.request.generation,expected.identity,allowed,SystemClock.elapsedRealtimeNanos(),screenRect,
            System.identityHashCode(view),System.identityHashCode(window))
    }
    internal fun waitingDrawn(state:G0CompletionPromptSession.State,ticket:G0CompletionPromptSession.WindowTicket,
        allowed:Boolean,view:View,window:Window) {
        if(waitingDraws.any {it.version==state.version && it.allowed==allowed})return
        if(waitingDraws.size>=4096) {overflow=true;return}
        if(allowed)waitingPixels.drawn(state.version)
        waitingDraws+=WaitingDraw(state.version,ticket.identity,allowed,System.identityHashCode(view),
            System.identityHashCode(window),SystemClock.elapsedRealtimeNanos())
    }
    fun target(session:G0CompletionPromptSession,expected:G0CompletionPromptSession.Expected)=
        G0ScreenObservation.Target(pixels,expected.version) {session.renderable(expected)}
    fun waitingTarget(session:G0CompletionPromptSession,ticket:G0CompletionPromptSession.WindowTicket,state:G0CompletionPromptSession.State)=
        G0ScreenObservation.Target(waitingPixels,state.version) {session.waiting(ticket,state)}
}

private fun DrawScope.promptMarker(pixels:G0ScreenObservation,version:Long,markerHeight:Float) {
    val width=size.width/26
    val height=minOf(markerHeight,size.height)
    for(index in 0 until 26) {
        val color=when(index) {
            0->Color.Magenta;25->Color.Cyan
            else->if((pixels.encodedVersion(version) shr (index-1)) and 1L==1L)Color.White else Color.Black
        }
        drawRect(color,Offset(index*width,0f),Size(width+0.1f,height))
    }
}

/** composed resolves LocalView INSIDE AlertDialog's independent window when its root
 * modifier is materialized. position/bounds are translated from that root to SCREEN pixels. */
private fun Modifier.promptWindowGuard(session:G0CompletionPromptSession,expected:G0CompletionPromptSession.Expected,
    observation:G0CompletionPromptObservation):Modifier=composed {
    val view=LocalView.current
    check(view.parent is DialogWindowProvider) {"G0_PROMPT_NOT_DIALOG_WINDOW"}
    val window=(view.parent as DialogWindowProvider).window
    DisposableEffect(view,window) {
        observation.window(expected.identity,view,window,true)
        onDispose {observation.window(expected.identity,view,window,false)}
    }
    val markerHeight=with(LocalDensity.current) {12.dp.toPx()}
    this.onGloballyPositioned {coordinates->
        val location=IntArray(2);view.getLocationOnScreen(location)
        val root=coordinates.boundsInRoot()
        observation.placed(Rect(root.left+location[0],root.top+location[1],root.right+location[0],
            root.top+location[1]+minOf(markerHeight,root.height)),view,window)
    }.drawWithContent {
        val allowed=session.renderable(expected)
        if(allowed) {
            drawContent()
            promptMarker(observation.pixels,expected.version,markerHeight)
        } else drawRect(Color.Black)
        observation.drawn(expected,allowed,view,window)
    }
}

private fun Modifier.stablePromptWindowGuard(session:G0CompletionPromptSession,ticket:G0CompletionPromptSession.WindowTicket,
    state:G0CompletionPromptSession.State,content:G0CompletionPromptSession.Expected?,
    observation:G0CompletionPromptObservation):Modifier=composed {
    val view=LocalView.current
    check(view.parent is DialogWindowProvider) {"G0_PROMPT_NOT_DIALOG_WINDOW"}
    val window=(view.parent as DialogWindowProvider).window
    DisposableEffect(view,window) {
        observation.window(ticket.identity,view,window,true)
        onDispose {observation.window(ticket.identity,view,window,false)}
    }
    val markerHeight=with(LocalDensity.current) {12.dp.toPx()}
    this.onGloballyPositioned {coordinates->
        val location=IntArray(2);view.getLocationOnScreen(location)
        val root=coordinates.boundsInRoot()
        observation.placed(Rect(root.left+location[0],root.top+location[1],root.right+location[0],
            root.top+location[1]+minOf(markerHeight,root.height)),view,window)
    }.drawWithContent {
        // The decision must match the subtree actually composed. Never draw old
        // personal content merely because the current session permits an anonymous shell.
        val allowed=if(content!=null)session.windowCurrent(ticket) && session.renderable(content)
            else session.waiting(ticket,state)
        if(allowed) {
            drawContent()
            if(content!=null)promptMarker(observation.pixels,content.version,markerHeight)
            else promptMarker(observation.waitingPixels,state.version,markerHeight)
        } else drawRect(Color.Black)
        if(content!=null)observation.drawn(content,allowed,view,window)
        else observation.waitingDrawn(state,ticket,allowed,view,window)
    }
}

/** Only original Dialog UI is rendered. This owns neither a database nor a window
 * manager; the trusted fixture/runtime must await session.shutdown before closing its DB. */
@Composable
internal fun G0CompletionPromptScreen(session:G0CompletionPromptSession,observation:G0CompletionPromptObservation) {
    observation.attach(session)
    val state=session.state.collectAsState().value
    DisposableEffect(session) {onDispose {session.retire()}}
    if(session.windowMode==G0CompletionPromptSession.WindowMode.RECREATE) {
      val expected=state.expected
      if(expected!=null && session.renderable(expected))key(session,expected) {
        PostCheckInDialog(
            habitName=expected.habitName,linkedMetrics=expected.metrics,promptIdentity=expected.identity,
            onRecord={_,_->session.rejectUnsupported(expected,"record")},
            onSkip={_->session.rejectUnsupported(expected,"skip")},
            onDismiss={check(session.close(expected)) {"G0_PROMPT_STALE_CALLBACK"}},
            onDraftChange={inputs->session.validateEmptyDraft(expected,inputs)},
            modifier=Modifier.promptWindowGuard(session,expected,observation)
        )
      }
    } else {
      val ticket=state.window
      if(ticket!=null && session.windowCurrent(ticket))key(session,ticket) {
        val content=state.expected?.takeIf(session::renderable)
        PostCheckInDialog(
            habitName=content?.habitName.orEmpty(),linkedMetrics=content?.metrics ?: emptyList(),
            promptIdentity=content?.identity ?: "${ticket.identity}:waiting",
            onRecord={_,_->session.rejectUnsupported(requireNotNull(content) {"G0_PROMPT_WAITING"},"record")},
            onSkip={_->session.rejectUnsupported(requireNotNull(content) {"G0_PROMPT_WAITING"},"skip")},
            onDismiss={check(session.close(requireNotNull(content) {"G0_PROMPT_WAITING"})) {"G0_PROMPT_STALE_CALLBACK"}},
            onDraftChange={inputs->session.validateEmptyDraft(requireNotNull(content) {"G0_PROMPT_WAITING"},inputs)},
            modifier=Modifier.stablePromptWindowGuard(session,ticket,state,content,observation),
            presentation=PostCheckInDialogPresentation(content!=null,"正在核对提示…") {session.cancelWindow(ticket);Unit}
        )
      }
    }
}
