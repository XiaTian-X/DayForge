package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real ACK invalidation must naturally hide old content and fresh state must
 * naturally redraw it. The test never invalidates or relayouts the host view. */
@RunWith(AndroidJUnit4::class)
class G0CountSessionRefreshSafetyTest:G0CountSessionRuntimeFixture() {
    private data class HostDraw(val at:Long,val runtimeVersion:Long,val runtimePhase:String,
        val pageVersion:Long?,val pagePhase:String?,val live:Boolean,val valid:Boolean)

    @Test fun warmReadyPageNaturallyReappearsAfterAnAckInvalidFrame()=runBlocking<Unit> {
        seedAccepted(8);val (http,server)=channel {successReply(it)}
        val job=SupervisorJob();val control=CoroutineScope(job+Dispatchers.IO)
        val connection=G0CountConnectionOwner {db.close()}
        val runtime=G0CountSessionRuntime(control,connection,authority,tokens,{domainGate.changeSequence},http) {coldCount(http)}
        val seen=G0ScreenObservation();val pageSeen=ConcurrentHashMap<G0ShortCountPage,G0ScreenObservation>()
        fun observe(page:G0ShortCountPage)=pageSeen.computeIfAbsent(page) {G0ScreenObservation()}
        val ackEntered=CompletableDeferred<Unit>();val ackRelease=CompletableDeferred<Unit>()
        val refreshEntered=CompletableDeferred<Unit>();val refreshRelease=CompletableDeferred<Unit>()
        val hostDraws=CopyOnWriteArrayList<HostDraw>()
        var host:View?=null;var watched:G0ShortCountPage?=null;var stage="setup";var failure:Throwable?=null
        val listener=ViewTreeObserver.OnDrawListener {
            val state=runtime.state.value;val page=state.page;val content=page?.state?.value
            hostDraws+=HostDraw(SystemClock.elapsedRealtimeNanos(),state.version,state.phase.name,
                content?.version,content?.phase?.name,page?.live()==true,content?.view?.valid()==true)
        }
        runtime.beforeAcceptForTest={ackEntered.complete(Unit);ackRelease.await()}
        try {
            activity.scenario.onActivity {
                host=it.window.decorView;it.window.decorView.viewTreeObserver.addOnDrawListener(listener)
            }
            installRuntime(runtime,seen,::observe);seen.await(runtime.state.value.version)
            stage="cold_open";seen.tap("open")
            val first=runtimePage(runtime);watched=first
            observe(first).awaitAny {listOf(first.state.value.version)}
            stage="save_before_ack";observe(first).tap("increment")
            phase(first,G0ShortCountPage.Phase.RESULT);first.settled();assertNull(first.error)
            withTimeout(15_000) {ackEntered.await()}
            withTimeout(15_000) {first.state.first {it.view?.valid()==true}}
            val saved=requireNotNull(first.saved)
            assertEquals(9,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
            stage="leave_and_warm_reopen";seen.tap("leave");runtime.awaitPageTransition()
            val away=runtimePhase(runtime,G0CountSessionRuntime.Phase.AWAY);seen.await(away.version)
            seen.tap("reopen")
            val warm=runtimePage(runtime);watched=warm;assertNotSame(first,warm)
            observe(warm).awaitAny {listOf(warm.state.value.version)}
            val prior=warm.state.value;val priorView=requireNotNull(prior.view)
            val oldMarker=observe(warm).frozenMarker(prior.version)
            assertEquals(G0ShortCountPage.Phase.READY,prior.phase);assertTrue(priorView.valid())
            requireVisiblePageText(setOf("卡片：9","首页：9 / 1000000"))
            warm.beforeRefreshForTest={refreshEntered.complete(Unit);refreshRelease.await()}
            stage="ack_invalidates_ready"
            val ackReleasedAt=SystemClock.elapsedRealtimeNanos();ackRelease.complete(Unit)
            withTimeout(15_000) {refreshEntered.await()}
            assertFalse(priorView.valid());assertEquals(prior.version,warm.state.value.version)
            assertEquals(G0CountSessionRuntime.Phase.OPEN,runtime.state.value.phase)
            assertFalse(runtime.renderable());assertTrue(warm.live())

            // No invalidate, requestLayout or extra input: the real change hint
            // must wake the guard independently of the paused business refresh.
            stage="natural_invalid_draw"
            withTimeout(15_000) {while(hostDraws.none {
                it.at>=ackReleasedAt && it.pageVersion==prior.version && it.live && !it.valid
            })delay(16)}
            val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
            withTimeout(15_000) {
                while(true) {
                    val bitmap=automation.takeScreenshot()
                    if(bitmap!=null) {
                        // The default Target predicate is TRUE. This must inspect actual
                        // pixels, not pass merely because the model's valid() is false.
                        val containsPersonalMarker=try {oldMarker(bitmap) || G0ScreenObservation.matchesTogether(bitmap,
                            listOf(G0ScreenObservation.Target(observe(warm),prior.version)))} finally {bitmap.recycle()}
                        if(!containsPersonalMarker)break
                    }
                    delay(16)
                }
            }
            assertFalse(priorView.valid());assertEquals(prior.version,warm.state.value.version)
            stage="natural_refresh_after_release";refreshRelease.complete(Unit)
            val refreshed=withTimeout(15_000) {warm.state.first {
                it.version>prior.version && it.phase==G0ShortCountPage.Phase.READY && it.view?.valid()==true
            }}
            // await only observes screenshots; it cannot schedule a Compose draw.
            observe(warm).await(refreshed.version)
            assertTrue(runtime.renderable());assertTrue(warm.live());assertNull(warm.error);assertNull(runtime.error)
            assertTrue(observe(warm).draws.any {it.version==refreshed.version})
            requireVisiblePageText(setOf("卡片：9","首页：9 / 1000000"))
            stage="terminal";withTimeout(15_000) {runtime.awaitSynced()}
            assertEquals(1,runtime.coldStarts);assertEquals(0,runtime.pendingCount)
            assertEquals(9,pageRowCount("completions"));assertEquals(9,pageRowCount("next_acceptances"))
            assertEquals(0,pageRowCount("sync_outbox"))
            assertEquals(listOf(saved.requestId),runtime.workerAttempts.map {it.requestId})
            assertEquals(1,server.requests.count {it.path.endsWith("/push")})
            val terminal=pageNativeState();val delivery=requireNotNull(runtime.workerAttempts.single().delivery)
            assertTrue(runtime.shutdown());reopenReference();sameAsOriginal(refreshed.view!!)
            assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(delivery))
            assertEquals(terminal,pageNativeState())
        } catch(error:Throwable) {
            failure=error
            val page=watched;val current=page?.state?.value;val runtimeState=runtime.state.value
            Log.i("G0SessionFailure",buildJsonObject {
                put("kind","refresh_draw_regression");put("stage",stage)
                put("runtime_phase",runtimeState.phase.name);put("runtime_version",runtimeState.version)
                put("runtime_current",runtimeState.page===page);put("runtime_renderable",runtime.renderable())
                put("phase",current?.phase?.name);put("version",current?.version)
                put("live",page?.live()==true);put("valid",current?.view?.valid()==true)
                put("error_type",error.javaClass.name);put("page_error",page?.error?.javaClass?.name)
                put("runtime_error",runtime.error?.javaClass?.name)
                put("event_count",page?.events?.size ?: 0);put("draw_count",page?.let(::observe)?.draws?.size ?: 0)
                put("events",JsonArray(page?.events?.takeLast(12)?.map {buildJsonObject {
                    put("version",it.version);put("phase",it.phase.name);put("published_ns",it.publishedAt)
                }} ?: emptyList()))
                put("draws",JsonArray(page?.let(::observe)?.draws?.takeLast(12)?.map {buildJsonObject {
                    put("version",it.version);put("at_ns",it.at)
                }} ?: emptyList()))
                put("host_draw_count",hostDraws.size)
                put("host_invalid_draw_count",hostDraws.count {it.live && !it.valid})
                put("host_draws",JsonArray(hostDraws.takeLast(8).map {buildJsonObject {
                    put("at_ns",it.at);put("runtime_version",it.runtimeVersion);put("runtime_phase",it.runtimePhase)
                    put("page_version",it.pageVersion);put("phase",it.pagePhase);put("live",it.live);put("valid",it.valid)
                }}))
            }.toString())
            throw error
        } finally {
            ackRelease.complete(Unit);refreshRelease.complete(Unit)
            val cleanup=runCatching {withContext(NonCancellable) {
                runtime.shutdown();job.cancelAndJoin()
                activity.scenario.onActivity {host?.viewTreeObserver?.takeIf {it.isAlive}?.removeOnDrawListener(listener)}
            }}.exceptionOrNull()
            if(cleanup!=null) {failure?.let {it.addSuppressed(cleanup)} ?: throw cleanup}
        }
    }
}
