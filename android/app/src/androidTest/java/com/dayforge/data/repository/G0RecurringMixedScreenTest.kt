package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*

/** Actual metric editor plus independently validated Count/Check pixels, with real ready ACKs. */
open class G0RecurringMixedScreenTest:G0InteractionContentionScreenTest() {
    override val admitRecurringReads=true
    override fun sampleMode(sample:Int)=if(retainRecurringResults) "cold_reuse" else "original"
    override val seedHistory=80
    override val immediateFirstEdit=true
    private val recurringHistory=80
    private lateinit var ids:List<Long>
    private lateinit var before:Map<String,List<String>>
    private lateinit var recurring:G0RecurringInteractionSession
    private lateinit var readAdapter:G0RecurringInteractionAdapter
    private val recurringSeen=G0ScreenObservation()
    private val readTrace=G0PhaseTrace(driverTiming=false)
    private lateinit var joint:G0ScreenObservation.Seen
    private var warmStart=Long.MAX_VALUE
    override suspend fun beforeSetupClock() {
        ids=seedPageFacts(recurringHistory);before=pageNativeState()
    }
    override suspend fun beforeMeasuredSetup() { storage.reopen() }
    override fun installScreen() {
        val reader=G0RecurringReadOwner(db,tokens,sessions,authority,domainGate,sharedScope=true,reuseEvidence=true,coldSeed={activeStore.recurringReadSeed()})
        readAdapter=runBlocking { G0RecurringInteractionAdapter(tokens,access(),domainGate,authority,runtime,reader,ids,operationOwner,app) }
        readAdapter.traceForTest=readTrace
        recurring=G0RecurringInteractionSession(readAdapter,operationOwner,interaction.state)
        activity.scenario.onActivity { screen ->
            screen.setContent { Column(Modifier.fillMaxSize()) {
                G0RecurringPane(recurring,recurringSeen)
                Box(Modifier.weight(1f)) { G0InteractionScreen(interaction,observation) }
            } }
            recurring.open() // Real page entry starts this visible consumer, never fixture prewarming.
        }
    }
    internal override suspend fun observeContent(state:G0InteractionSession.State,prior:G0ScreenObservation.Seen?):G0ScreenObservation.Seen =
        G0ScreenObservation.awaitTogether {
            val other=recurring.state.value
            if(other.phase!=G0RecurringInteractionSession.Phase.READY || !other.renderable()) null
            else listOf(G0ScreenObservation.Target(observation,state.version,state::renderable),
                G0ScreenObservation.Target(recurringSeen,other.version,other::renderable))
        }.also { joint=it;assertContent() }
    private suspend fun assertContent() {
        val content=recurring.state.value.publication!!.content
        assertEquals(3320L,content.counts.getValue(ids[0]).todayQuantity)
        assertEquals(1L,content.checks.getValue(ids[1]).todayQuantity)
        requireVisiblePageText(setOf("计数今日：3320","打卡今日：1"))
    }
    override suspend fun afterInitialScreen(setupAt:Long) {
        recurring.awaitSettled();assertEquals(1,builds)
        Log.i("G0RecurringInitial",buildJsonObject {
            put("candidate",retainRecurringResults);put("metric_history",seedHistory);put("count_history",recurringHistory)
            put("setup_to_both_observed_ns",joint.observed-setupAt);put("full_reads",readAdapter.reader.fullReads)
            put("exact_first_presentation",false);put("process_death",false)
        }.toString())
        synchronized(readTrace.spans) { readTrace.spans.clear() };warmStart=SystemClock.elapsedRealtimeNanos()
    }
    override suspend fun afterSavedScreen(sample:Int,saveAt:Long) {
        recurring.awaitSettled()
        Log.i("G0RecurringScreen",buildJsonObject {
            put("sample",sample);put("save_to_joint_observed_ns",joint.observed-saveAt)
            put("full_reads",readAdapter.reader.fullReads);put("reuse_hits",readAdapter.reader.reuseHits)
            put("spans",traceJson(readTrace,saveAt));put("exact_first_presentation",false)
        }.toString())
    }
    override suspend fun afterSyncedScreen(sample:Int) { recurring.awaitSettled();assertContent() }
    override suspend fun verifyDurable(expected:Int) {
        recurring.detach();recurring.awaitSettled();originalDatabase()
        val after=pageNativeState()
        for(table in listOf("habits","completions","count_days","timelogs","metric_logs","habit_metric_links","sync_outbox"))
            assertEquals(table,before[table],after[table])
        val foreign=recurringHistory+2 // check plan plus count/check facts, all remain unsent.
        assertEquals(expected+foreign,count("next_request_origins"));assertEquals(expected,count("next_acceptances"))
        assertEquals(foreign,count("sync_outbox"));assertEquals(if(retainRecurringResults) 0 else 1,readAdapter.reader.fullReads)
        assertEquals(if(retainRecurringResults) 1 else 0,readAdapter.reader.coldHits)
        Log.i("G0RecurringScreenDurable",buildJsonObject {
            put("candidate",retainRecurringResults);put("cold_hits",readAdapter.reader.coldHits);put("metric_acceptances",expected);put("foreign_origins",foreign);put("foreign_outbox",foreign)
            put("total_origins",expected+foreign);put("full_reads",readAdapter.reader.fullReads)
            put("reuse_hits",readAdapter.reader.reuseHits);put("warm_read_resource_max_ns",resource(readTrace))

        }.toString())
        traceJson(readTrace,warmStart).chunked(20).forEachIndexed { index,spans ->
            Log.i("G0RecurringWarmTrace",buildJsonObject {
                put("chunk",index);put("spans",JsonArray(spans))
            }.toString())
        }
    }
    override fun extraPerformanceFailures():List<String> = buildList {
        val hold=resource(readTrace);if(hold>150_000_000L) add("recurring resource max=$hold >150ms")
    }
    @After fun closeRecurring()=runBlocking<Unit> {
        if(::recurring.isInitialized) { recurring.detach();recurring.awaitSettled() }
    }
}
