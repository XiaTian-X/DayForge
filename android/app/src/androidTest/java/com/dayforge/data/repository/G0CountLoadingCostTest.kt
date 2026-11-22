package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real open/loading/ready/count/save/ACK pixels. Loading is feedback, not a reduction
 * in cold proof cost. Two observations per history are exploratory, never P95. */
@RunWith(AndroidJUnit4::class)
class G0CountLoadingCostTest:G0CountLoadingFixture() {
    private fun now()=SystemClock.elapsedRealtimeNanos()
    @Test(timeout=900_000) fun eightFactsColdEntryAndCancellation()=runBlocking<Unit> {measureLoading(8)}
    @Test(timeout=900_000) fun eightyFactsColdEntryAndCancellation()=runBlocking<Unit> {measureLoading(80)}
    private suspend fun measureLoading(history:Int)=coroutineScope {
        seedAccepted(history);val image=storage.snapshotClosedDatabase();val failures=mutableListOf<String>()
        repeat(3) {sample->
            freshGateForColdOpen();restoreForJourney(image)
            val before=pageNativeState();val (http,_)=channel {successReply(it)}
            var store:G0ShortCountStore?=null
            val factoryStart=java.util.concurrent.atomic.AtomicLong();val factoryEnd=java.util.concurrent.atomic.AtomicLong()
            val trace=G0PhaseTrace(driverTiming=false)
            val owner=G0CountConnectionOwner {db.close()}
            val entry=G0CountLoadingEntry(this,owner,authority,tokens,{domainGate.changeSequence}) {
                factoryStart.set(now())
                try {trace.run {coldCount(http)}.also {store=it}} finally {factoryEnd.set(now())}
            }
            val seen=G0ScreenObservation();val pageSeen=G0ScreenObservation()
            try {
                installLoading(entry,seen,pageSeen);seen.await(entry.state.value.version)
                val start=seen.tap("open")
                val loading=entryPhase(entry,G0CountLoadingEntry.Phase.LOADING)
                val feedback=seen.await(loading.version)
                val draw=seen.draws.single {it.version==loading.version}.at
                if(draw-start>100_000_000L)failures+="h=$history sample=$sample loading_draw=${draw-start}"
                if(sample==2) {
                    // Cancel the actual cold operation; no artificial sleep in the factory.
                    val hit=entry.state.value.phase==G0CountLoadingEntry.Phase.LOADING && factoryStart.get()>0 && factoryEnd.get()==0L
                    if(!hit)Log.i("G0LoadingMeasure",buildJsonObject {put("kind","cancel_window_miss");put("history",history);put("sample",sample)}.toString())
                    assertTrue("Cold cancellation window was not hit; no cancellation latency conclusion",hit)
                    val cancel=seen.tap("cancel")
                    val closed=entryPhase(entry,G0CountLoadingEntry.Phase.CLOSED)
                    val pixels=seen.await(closed.version)
                    entry.shutdown();assertTrue(entry.drained);assertFalse(owner.busy())
                    assertFalse(entry.events.any {it.phase==G0CountLoadingEntry.Phase.READY})
                    reopenReference();assertEquals(before,pageNativeState())
                    val closedDraw=seen.draws.single {it.version==closed.version}.at
                    Log.i("G0LoadingMeasure",buildJsonObject {
                        put("kind","cancel");put("history",history);put("sample",sample)
                        put("loading_draw_ns",draw-start);put("loading_observed_ns",feedback.observed-start)
                        put("cancel_ui_draw_ns",closedDraw-cancel);put("cancel_ui_observed_ns",pixels.observed-cancel)
                        put("cancel_drain_ns",entry.drainedAt-cancel);put("factory_ns",factoryEnd.get()-factoryStart.get())
                        put("ready_published",false);put("unchanged",true)
                    }.toString())
                    if(closedDraw-cancel>100_000_000L)failures+="h=$history cancel_ui=${closedDraw-cancel}"
                    if(entry.drainedAt-cancel>100_000_000L)failures+="h=$history cancel_drain=${entry.drainedAt-cancel}"
                } else {
                    val ready=entryPhase(entry,G0CountLoadingEntry.Phase.READY)
                    val page=requireNotNull(ready.page)
                    val readyPixels=pageSeen.await(page.state.value.version)
                    assertTrue(entry.renderable());assertEquals(history.toLong(),page.state.value.view!!.history.todayQuantity)
                    val writeTrace=G0PhaseTrace(driverTiming=false);page.trace=writeTrace
                    val input=pageSeen.tap("increment")
                    val result=phase(page,G0ShortCountPage.Phase.RESULT);page.settled();assertNull(page.error)
                    val resultPixels=pageSeen.await(result.version)
                    val saved=page.saved!!;val live=requireNotNull(store)
                    assertEquals(history+1,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
                    val delivered=requireNotNull(live.send(http,saved.requestId))
                    assertEquals(NextOperationAcceptance.COMMITTED,live.acknowledge(delivered))
                    withTimeout(20_000) {while(page.state.value.view?.valid()!=true) {page.error?.let {throw it};delay(5)}}
                    pageSeen.awaitAny {listOf(page.state.value.version)}
                    val final=page.state.value.view!!;val terminal=pageNativeState()
                    assertEquals(NextOperationAcceptance.REPLAYED,live.acknowledge(delivered));assertEquals(terminal,pageNativeState())
                    entry.shutdown();assertTrue(entry.drained);assertFalse(owner.busy())
                    reopenReference();sameAsOriginal(final)
                    assertEquals(history+1,pageRowCount("next_acceptances"));assertEquals(0,pageRowCount("sync_outbox"))
                    assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(delivered));assertEquals(terminal,pageNativeState())
                    Log.i("G0LoadingMeasure",buildJsonObject {
                        put("kind","entry");put("history",history);put("sample",sample)
                        put("loading_publish_ns",loading.publishedAt-start);put("loading_draw_ns",draw-start)
                        put("loading_capture_start_ns",feedback.captureStarted-start);put("loading_observed_ns",feedback.observed-start)
                        put("ready_publish_ns",ready.publishedAt-start);put("ready_observed_ns",readyPixels.observed-start)
                        put("ready_capture_start_ns",readyPixels.captureStarted-start);put("factory_ns",factoryEnd.get()-factoryStart.get())
                        put("cold_transaction_ns",transactionHold(trace));put("click_commit_ns",page.committedAt-input)
                        put("click_result_observed_ns",resultPixels.observed-input);put("write_transaction_ns",transactionHold(writeTrace))
                        put("terminal_facts",history+1);put("terminal_outbox",0);put("original_reader",true);put("replay",true)
                    }.toString())
                    if(readyPixels.observed-start>2_000_000_000L)failures+="h=$history sample=$sample ready=${readyPixels.observed-start}"
                    if(page.committedAt-input>500_000_000L)failures+="h=$history sample=$sample save=${page.committedAt-input}"
                    if(resultPixels.observed-input>700_000_000L)failures+="h=$history sample=$sample result=${resultPixels.observed-input}"
                    if(transactionHold(writeTrace)>150_000_000L)failures+="h=$history sample=$sample write_resource=${transactionHold(writeTrace)}"
                }
            } finally {entry.shutdown()}
        }
        assertTrue("Original loading/ready/save/result/resource/cancellation budgets: $failures",failures.isEmpty())
    }
}

/** One selected-arm stress and one paced window isolate input timing. Original
 * pre-DOWN origin, six taps/seven syncs, terminal checks and budgets stay unchanged. */
@RunWith(AndroidJUnit4::class)
class G0CountInputTimingCostTest:G0ShortCountBacklogCostFixture() {
    override val jointCountAuthority=true
    @Test(timeout=900_000) fun selectedContinuousInputTiming()=runBlocking<Unit> {
        measure(8,concurrentArms=listOf(true))
    }
    @Test(timeout=900_000) fun selectedPacedInputTiming()=runBlocking<Unit> {
        measure(8,interResultDelayMs=5_000,concurrentArms=listOf(true))
    }
}
