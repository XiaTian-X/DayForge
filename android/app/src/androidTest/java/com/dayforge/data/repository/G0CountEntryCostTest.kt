package com.dayforge.data.repository

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Bounded phase diagnosis, not a P95 acceptance batch. Every strategy uses the selected
 * result publisher. Preopen is a controlled attribution experiment; its time is NOT hidden. */
@RunWith(AndroidJUnit4::class)
class G0CountEntryCostTest:G0CountJourneyScreenFixture() {
    private enum class Mode { ORIGINAL,PREOPEN,BATCHED }
    private var mode=Mode.BATCHED
    override fun domainOpeningCallback()=G0SchemaOpeningCallback(mode==Mode.BATCHED)
    @Test(timeout=900_000) fun eightFactsThreeOpeningStrategies()=runBlocking<Unit> { measure(8) }
    @Test(timeout=900_000) fun eightyFactsThreeOpeningStrategies()=runBlocking<Unit> { measure(80) }
    private fun now()=android.os.SystemClock.elapsedRealtimeNanos()
    private fun resource(trace:G0PhaseTrace):Long {
        val spans=synchronized(trace.spans) { trace.spans.toList() }
        val begins=spans.filter { it.phase=="tx_begin_outer" };val ends=spans.filter { it.phase=="tx_end_outer" }
        assertEquals(3,begins.size);assertEquals(begins.size,ends.size)
        val normal=begins.zip(ends).maxOf { (a,b)->b.end-a.start }
        val open=spans.singleOrNull { it.phase=="schema_callback_begin" }?.let { begin ->
            spans.single { it.phase=="schema_callback_end" }.end-begin.start
        } ?: 0L
        return maxOf(normal,open)
    }
    private suspend fun measure(history:Int) {
        configureAction(target=1_000_000);val (http,_)=channel { successReply(it) };val seed=actions()
        repeat(history) {
            increment(seed);val row=db.syncOutboxDao().getAll().single();val requests=sender(http)
            val delivery=requireNotNull(requests.sendOperation(access(),row.operationId))
            assertEquals(NextOperationAcceptance.COMMITTED,requests.acceptOperation(delivery))
        }
        val image=storage.snapshotClosedDatabase();val failures=mutableListOf<String>()
        repeat(4) { pair ->
            val modes=Mode.entries.toList().let { it.drop(pair%3)+it.take(pair%3) }
            for(selected in modes) {
                mode=selected;restoreForJourney(image)
                val openTrace=G0PhaseTrace(driverTiming=false);val entry=now()
                if(mode==Mode.PREOPEN) openTrace.run { withContext(Dispatchers.IO) { db.openHelper.writableDatabase } }
                val opened=now();val flow=journey();val seen=G0ScreenObservation();val trace=G0PhaseTrace(driverTiming=false)
                flow.traceForTest=trace;install(flow,seen);seen.await(0);val clicked=seen.tap("increment")
                val state=phase(flow,G0CountJourney.Phase.RESULT);val pixels=seen.await(state.version);flow.awaitSettled()
                assertNull(flow.lastError);assertEquals((history+1).toLong(),state.view!!.countHistory!!.todayQuantity)
                assertEquals(db.completionDao().getByHabitOnce(habit.id).single { it.id==flow.recordedId }.uuid,state.view.followUp.claim.completionUuid)
                fun event(name:String)=flow.events.single { it.kind==name }.at
                val commit=event("committed");val prepare=event("prepared")-event("prepare_started")
                val write=event("write_returned")-event("write_started")
                val visibleVersions=flow.events.filter { it.kind in setOf("state_SAVING","state_SAVED_REFRESHING","state_RESULT") }.map { it.version }
                val feedback=seen.draws.filter { it.version in visibleVersions }.minOf { it.at }-clicked
                val hold=resource(trace)
                assertEquals(6,trace.count("decrypt_access"));assertEquals(0,trace.count("decrypt_refresh"))
                assertEquals(1,trace.count("schema_callback_total")+openTrace.count("schema_callback_total"))
                val row=db.syncOutboxDao().getAll().single();val requests=sender(http);val sendStart=now()
                val delivery=requireNotNull(requests.sendOperation(access(),row.operationId));val ackStart=now()
                assertEquals(NextOperationAcceptance.COMMITTED,requests.acceptOperation(delivery));val ack=now()-ackStart
                assertEquals(NextOperationAcceptance.REPLAYED,requests.acceptOperation(delivery));val terminal=now()
                assertEquals(history+1,count("completions"));assertEquals(history+1,count("next_request_origins"))
                assertEquals(history+1,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
                Log.i("G0CountEntryCost",buildJsonObject {
                    put("history",history);put("pair",pair);put("mode",mode.name)
                    put("preopen_ns",opened-entry);put("entry_to_commit_ns",commit-entry);put("entry_to_result_ns",pixels.observed-entry)
                    put("click_to_commit_ns",commit-clicked);put("click_to_result_ns",pixels.observed-clicked)
                    put("feedback_draw_ns",feedback);put("queue_to_prepare_ns",event("prepare_started")-clicked)
                    put("prepare_ns",prepare);put("current_row_ns",event("habit_loaded")-event("prepared"));put("write_ns",write)
                    put("commit_to_result_ns",pixels.observed-commit);put("transaction_max_ns",hold)
                    put("schema_callback_ns",trace.nanos("schema_callback_total")+openTrace.nanos("schema_callback_total"))
                    put("callback_trigger_ns",trace.nanos("schema_callback_trigger")+openTrace.nanos("schema_callback_trigger"))
                    put("callback_control_ns",trace.nanos("schema_callback_control")+openTrace.nanos("schema_callback_control"))
                    put("decrypt_ns",trace.nanos("decrypt_access"));put("datastore_ns",trace.nanos("datastore_read"))
                    put("send_ns",ackStart-sendStart);put("ack_ns",ack);put("click_to_terminal_ns",terminal-clicked)
                    put("facts",history+1);put("acceptances",history+1);put("outbox",0)
                    put("background_competition",false);put("sealed_fact_owner",false)
                }.toString())
                if(commit-clicked>500_000_000L || pixels.observed-clicked>700_000_000L || hold>150_000_000L ||
                    feedback>100_000_000L || ack>500_000_000L) failures+="$mode pair=$pair exceeds original per-operation budgets"
                flow.close();flow.awaitSettled()
            }
        }
        assertTrue(failures.joinToString(";"),failures.isEmpty())
    }
}
