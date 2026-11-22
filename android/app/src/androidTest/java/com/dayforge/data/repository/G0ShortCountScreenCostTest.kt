package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real taps and observed pixels for ONE count card + home quantity + saved hint.
 * Background ACK starts at foreground preparation. Two samples/size, not P95 or a
 * mixed-domain production page. Networking is synthetic; persistent rows are real. */
@RunWith(AndroidJUnit4::class)
class G0ShortCountScreenCostTest:G0ShortCountScreenFixture() {
    private fun now()=SystemClock.elapsedRealtimeNanos()
    @Test(timeout=900_000) fun eightFactsCompetingAckAndScreen()=runBlocking<Unit> {measure(8)}
    @Test(timeout=900_000) fun eightyFactsCompetingAckAndScreen()=runBlocking<Unit> {measure(80)}
    private suspend fun measure(history:Int)=coroutineScope {
        seedAccepted(history);seedFact();val image=storage.snapshotClosedDatabase();val failures=mutableListOf<String>()
        repeat(2) {sample ->
            freshGateForColdOpen();restoreForJourney(image)
            val oldRequest=db.syncOutboxDao().getAll().single().operationId
            val store=openCount();val (http,_)=channel {successReply(it)}
            val delivery=requireNotNull(store.send(http,oldRequest))
            val page=G0ShortCountPage(store,tokens,this);val seen=G0ScreenObservation()
            val trace=G0PhaseTrace(driverTiming=false);page.trace=trace
            val ackTrace=G0PhaseTrace(driverTiming=false);val prepared=CompletableDeferred<Unit>()
            store.afterPrepareForTest={store.afterPrepareForTest={};prepared.complete(Unit)}
            val background=async(Dispatchers.IO) {
                prepared.await();val start=now()
                assertEquals(NextOperationAcceptance.COMMITTED,ackTrace.run {store.acknowledge(delivery)})
                start to now()
            }
            try {
                page.open();install(page,seen);seen.await(page.state.value.version)
                val start=seen.tap("increment")
                val feedback=seen.awaitAny {page.events.filter {it.phase in setOf(G0ShortCountPage.Phase.SAVING,G0ShortCountPage.Phase.SAVED,G0ShortCountPage.Phase.RESULT)}.map {it.version}}
                phase(page,G0ShortCountPage.Phase.RESULT);page.settled();assertNull(page.error)
                val ack=background.await();page.refresh()
                val result=page.state.value;assertEquals(G0ShortCountPage.Phase.RESULT,result.phase)
                val rendered=seen.await(result.version);assertTrue(rendered.observed>=page.committedAt)
                assertEquals((history+2).toLong(),result.view!!.history.todayQuantity)
                assertEquals(page.saved!!.factUuid,result.view.followUp.claim.completionUuid)
                val drawn=seen.draws.filter {d->page.events.any {it.version==d.version && it.phase in setOf(G0ShortCountPage.Phase.SAVING,G0ShortCountPage.Phase.SAVED,G0ShortCountPage.Phase.RESULT)}}.minOf {it.at}
                val own=requireNotNull(store.send(http,page.saved!!.requestId));val ackStart=now()
                assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(own));val ackNs=now()-ackStart
                page.refresh();val terminal=seen.await(page.state.value.version)
                val resource=maxOf(transactionHold(trace),transactionHold(ackTrace))
                val overlap=maxOf(0L,minOf(ack.second,page.committedAt)-maxOf(ack.first,start))
                val state=pageNativeState();assertEquals(NextOperationAcceptance.REPLAYED,store.acknowledge(own));assertEquals(state,pageNativeState())
                val expected=view(store,page.saved!!.factId);page.close();page.settled();reopenReference();sameAsOriginal(expected)
                assertEquals(history+2,count("completions"));assertEquals(history+2,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
                assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(own));assertEquals(state,pageNativeState())
                Log.i("G0ShortCountMeasure",buildJsonObject {
                    put("kind","screen");put("history",history);put("sample",sample)
                    put("click_commit_ns",page.committedAt-start);put("click_feedback_draw_ns",drawn-start)
                    put("click_feedback_observed_ns",feedback.observed-start);put("click_result_observed_ns",rendered.observed-start)
                    put("click_terminal_observed_ns",terminal.observed-start);put("ack_ns",ackNs)
                    put("background_ack_ns",ack.second-ack.first);put("overlap_ns",overlap);put("resource_ns",resource)
                    put("exact_first_presentation",false);put("mixed_domain_page",false);put("network_synthetic",true)
                }.toString())
                if(page.committedAt-start>500_000_000L)failures+="$sample commit >500ms"
                if(drawn-start>100_000_000L)failures+="$sample feedback >100ms"
                if(rendered.observed-start>700_000_000L)failures+="$sample result >700ms"
                if(ackNs>500_000_000L)failures+="$sample ACK >500ms"
                if(resource>150_000_000L)failures+="$sample resource >150ms"
                if(overlap==0L)failures+="$sample missing competition"
            } finally {page.close();page.settled();background.cancelAndJoin()}
        }
        assertTrue("Exploratory screen budgets, NOT P95: "+failures.joinToString(";"),failures.isEmpty())
    }
}
