package com.dayforge.data.repository

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Twenty actual screen commands colliding with ready same-object ACKs. Not a steady mixed-page benchmark. */
@RunWith(AndroidJUnit4::class)
open class G0InteractionContentionScreenTest : G0InteractionFixture() {
    @get:Rule val activity=ActivityScenarioRule(ComponentActivity::class.java)
    override fun installScreen() { activity.scenario.onActivity { it.setContent { G0InteractionScreen(interaction,observation) } } }
    protected open val sampleCount=20
    protected open val seedHistory=8
    protected open val immediateFirstEdit=false
    private val immediateFailures=mutableListOf<String>()
    protected open suspend fun beforeSetupClock()=Unit
    protected open suspend fun beforeMeasuredSetup()=Unit
    protected open suspend fun beforeSample(sample:Int)=Unit
    protected open fun sampleMode(sample:Int)="default"
    protected fun p95(values:List<Long>):Long=values.sorted()[kotlin.math.ceil(values.size*0.95).toInt()-1]
    protected open suspend fun afterInitialScreen(setupAt:Long) = Unit
    protected open suspend fun afterSavedScreen(sample:Int,saveAt:Long) = Unit
    protected open suspend fun afterSyncedScreen(sample:Int) = Unit
    internal open suspend fun observeContent(state:G0InteractionSession.State,prior:G0ScreenObservation.Seen?):G0ScreenObservation.Seen =
        if(prior?.version==state.version) prior else observation.await(state.version)
    protected open suspend fun verifyDurable(expected:Int) {
        originalDatabase();assertEquals(expected,count("next_request_origins"));assertEquals(expected,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
    }
    protected open fun extraPerformanceFailures():List<String> = emptyList()
    internal fun traceJson(trace:G0PhaseTrace,origin:Long)=buildJsonArray {
        val selected=setOf("operation","short_account_wait","short_account_hold","domain_sql_wait","domain_sql_hold","tx_begin_outer","tx_end_outer","decrypt_access")
        val copy=synchronized(trace.spans) { trace.spans.toList() }
        copy.filter { it.phase in selected && it.end>=origin }.forEach { span -> add(buildJsonObject {
            put("phase",span.phase);put("start_ns",span.start-origin);put("end_ns",span.end-origin);put("ok",span.ok)
        }) }
    }
    private fun textNode(text:String):AccessibilityNodeInfo? {
        fun find(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
            if(node.text?.toString()==text && node.isVisibleToUser && node.isEditable) return node
            for(i in 0 until node.childCount) node.getChild(i)?.let { find(it)?.let { found->return found } }
            return null
        }
        return InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(::find)
    }
    private suspend fun typeName(old:String,new:String) {
        val node=withTimeout(5_000) { var found=textNode(old);while(found==null) { delay(16);found=textNode(old) };found }
        check(node.isEditable)
        check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,new)
        }))
        withTimeout(5_000) { interaction.state.first { it.name==new } }
        observation.await(interaction.state.value.version)
    }
    internal fun resource(trace:G0PhaseTrace):Long {
        val starts=trace.spans.filter { it.phase=="tx_begin_outer" }.sortedBy { it.start }
        val ends=trace.spans.filter { it.phase=="tx_end_outer" }.sortedBy { it.start }
        check(starts.size==ends.size)
        val tx=starts.zip(ends).maxOfOrNull { (a,b)->check(b.end>=a.start);b.end-a.start } ?: 0L
        return maxOf(tx,trace.spans.filter { it.phase in setOf("short_account_hold","domain_sql_hold") }.maxOfOrNull { it.end-it.start } ?: 0L)
    }
    private suspend fun immediateSave(initial:G0InteractionSession.State) {
        typeName(requireNotNull(initial.publication).value.name,"Immediate after cold")
        val before=interaction.state.value.version;val trace=G0PhaseTrace(driverTiming=false)
        adapter.traceForTest=trace
        try {
            val at=observation.tap("save")
            fun versions(kinds:Set<String>)=interaction.events.filter { it.version>before && it.kind in kinds }.map { it.version }
            val feedback=observation.awaitAny { versions(setOf("state_SAVING","state_SAVED_REFRESHING","state_SAVED")) }
            val known=if(interaction.events.single { it.version==feedback.version && it.kind.startsWith("state_") }.kind!="state_SAVING") feedback
                else observation.awaitAny { versions(setOf("state_SAVED_REFRESHING","state_SAVED")) }
            val saved=phase(G0InteractionSession.Phase.SAVED);val result=observeContent(saved,known)
            interaction.awaitSettled()
            assertEquals("Immediate after cold",saved.publication!!.value.name);assertEquals(1,saved.publication.value.pending)
            assertEquals(4,trace.count("decrypt_access"));assertEquals(2,trace.count("tx_begin_outer"));assertEquals(0,trace.count("preparation_retry"))
            val committed=interaction.events.single { it.kind=="committed" && it.version>before }.at
            val firstDraw=observation.draws.filter { it.version in versions(setOf("state_SAVING","state_SAVED_REFRESHING","state_SAVED")) }.minOf { it.at }
            val knownDraw=observation.draws.single { it.version==known.version }.at
            assertTrue(knownDraw>=committed && known.observed>=knownDraw)
            val hold=resource(trace)
            Log.i("G0FirstImmediate",buildJsonObject {
                put("history",seedHistory);put("commit_ns",committed-at);put("known_saved_observed_ns",known.observed-at)
                put("joint_result_observed_ns",result.observed-at);put("feedback_draw_ns",firstDraw-at);put("hold_max_ns",hold)
                put("access_decryptions",trace.count("decrypt_access"));put("transactions",trace.count("tx_begin_outer"))
                put("explicit_think_delay",false);put("exact_first_presentation",false)
            }.toString())
            if(committed-at>500_000_000L) immediateFailures+="first immediate commit=${committed-at} >500ms diagnostic"
            if(result.observed-at>700_000_000L) immediateFailures+="first immediate result=${result.observed-at} >700ms diagnostic"
            if(firstDraw-at>100_000_000L) immediateFailures+="first immediate feedback=${firstDraw-at} >100ms"
            if(hold>150_000_000L) immediateFailures+="first immediate resource=$hold >150ms"
        } finally { adapter.traceForTest=null }
        val syncAt=observation.tap("sync")
        val synced=phase(G0InteractionSession.Phase.SYNCED);val seen=observeContent(synced,null);interaction.awaitSettled()
        assertEquals(0,synced.publication!!.value.pending);assertEquals(1,builds)
        Log.i("G0FirstImmediateSync","{\"observed_ns\":${seen.observed-syncAt},\"history\":$seedHistory}")
    }
    @Test(timeout=900_000) fun twentyVisibleSavesCompeteWithReadyBackgroundAcks()=runBlocking<Unit> {
        register()
        val (seedHttp,_)=channel { input -> successReply(input,revision=(if(input.path.endsWith("/identity")) 0L else
            wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0L)+1) }
        repeat(seedHistory) { i ->
            val row=editMetric("Seed $i")
            val delivery=requireNotNull(sender(seedHttp).sendOperation(access(),row.operationId))
            assertEquals(NextOperationAcceptance.COMMITTED,sender(seedHttp).acceptOperation(delivery))
        }
        beforeSetupClock()
        val setupAt=SystemClock.elapsedRealtimeNanos();beforeMeasuredSetup();setupInteraction()
        observation.await(interaction.state.value.version)
        val coldOpen=observation.tap("open");val initial=phase(G0InteractionSession.Phase.EDITING)
        val initialSeen=observeContent(initial,null);interaction.awaitSettled()
        Log.i("G0ContentionInitial",buildJsonObject {
            put("history",seedHistory);put("setup_to_editable_observed_ns",initialSeen.observed-setupAt)
            put("open_to_editable_observed_ns",initialSeen.observed-coldOpen);put("exact_first_presentation",false)
        }.toString())
        afterInitialScreen(setupAt)
        if(immediateFirstEdit) immediateSave(initial)
        val commits=mutableListOf<Long>();val results=mutableListOf<Long>();val feedbacks=mutableListOf<Long>();val holds=mutableListOf<Long>()
        repeat(sampleCount) { sample ->
            beforeSample(sample)
            observation.tap("cancel");val cancelled=phase(G0InteractionSession.Phase.CANCELLED)
            observation.await(cancelled.version);interaction.awaitSettled()
            val cycleAt=SystemClock.elapsedRealtimeNanos()
            val row=withContext(Dispatchers.IO) { requireNotNull(activeStore.edit("Ready $sample","",id(3000+sample*2))) }
            val delivery=withContext(Dispatchers.IO) { requireNotNull(activeStore.send(interactionHttp,access(),row.operationId)) }
            val openAt=observation.tap("open");val ready=phase(G0InteractionSession.Phase.EDITING)
            val readySeen=observation.await(ready.version);interaction.awaitSettled()
            typeName("Ready $sample","Confirmed $sample")
            val oldVersion=interaction.state.value.version
            val prepared=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var attempts=0
            val foreground=G0PhaseTrace(driverTiming=false);val ack=G0PhaseTrace(driverTiming=false)
            activeStore.afterPrepareForTest={
                if(currentCoroutineContext()[CoroutineName]?.name!="screen_background_ack") {
                    attempts++;prepared.complete(Unit);release.await()
                }
            }
            adapter.traceForTest=foreground
            var ackAt=0L;var ackEnd=0L
            val background=async(Dispatchers.IO+CoroutineName("screen_background_ack")) {
                try {
                    prepared.await();ackAt=SystemClock.elapsedRealtimeNanos()
                    assertEquals(NextOperationAcceptance.COMMITTED,ack.run { activeStore.acknowledge(delivery) })
                    ackEnd=SystemClock.elapsedRealtimeNanos()
                } finally { release.complete(Unit) }
            }
            try {
                val saveAt=observation.tap("save")
                fun versions(kinds:Set<String>)=interaction.events.filter { it.version>oldVersion && it.kind in kinds }.map { it.version }
                val feedback=observation.awaitAny { versions(setOf("state_SAVING","state_SAVED_REFRESHING","state_SAVED")) }
                val kind=interaction.events.single { it.version==feedback.version && it.kind.startsWith("state_") }.kind
                val known=if(kind!="state_SAVING") feedback else observation.awaitAny { versions(setOf("state_SAVED_REFRESHING","state_SAVED")) }
                val saved=phase(G0InteractionSession.Phase.SAVED)
                val result=observeContent(saved,known)
                interaction.awaitSettled();background.await()
                assertEquals("Confirmed $sample",saved.publication!!.value.name);assertEquals(1,saved.publication.value.pending)
                assertEquals(2,attempts);assertEquals(1,foreground.count("preparation_retry"))
                assertEquals(5,foreground.count("decrypt_access"));assertEquals(2,ack.count("decrypt_access"))
                assertEquals(2,foreground.count("tx_begin_outer"));assertEquals(1,ack.count("tx_begin_outer"))
                val committed=interaction.events.single { it.kind=="committed" && it.version>oldVersion }.at
                val receipt=interaction.events.single { it.kind=="state_SAVED_REFRESHING" && it.version>oldVersion }
                val knownDraw=observation.draws.single { it.version==known.version }.at
                assertTrue(receipt.at>=committed && knownDraw>=committed && known.observed>=knownDraw)
                val feedbackVersions=versions(setOf("state_SAVING","state_SAVED_REFRESHING","state_SAVED")).toSet()
                val firstDraw=observation.draws.filter { it.version in feedbackVersions }.minOf { it.at }
                val hold=resource(foreground);val ackHold=resource(ack)
                commits+=committed-saveAt;results+=result.observed-saveAt;feedbacks+=firstDraw-saveAt;holds+=listOf(hold,ackHold)
                Log.i("G0CompetitionTrace",buildJsonObject {
                    put("sample",sample);put("mode",sampleMode(sample));put("foreground",traceJson(foreground,saveAt));put("ack",traceJson(ack,saveAt))
                }.toString())
                afterSavedScreen(sample,saveAt)
                adapter.traceForTest=null;activeStore.afterPrepareForTest={}
                observation.tap("sync") // Finish this foreground command through the existing UI path.
                val synced=phase(G0InteractionSession.Phase.SYNCED)
                val syncedSeen=observeContent(synced,null);interaction.awaitSettled()
                assertEquals(0,synced.publication!!.value.pending);assertEquals(1,builds)
                afterSyncedScreen(sample)
                Log.i("G0VisibleContention",buildJsonObject {
                    put("sample",sample);put("mode",sampleMode(sample));put("starting_history",seedHistory+(if(immediateFirstEdit) 1 else 0)+sample*2)
                    put("warm_open_observed_ns",readySeen.observed-openAt);put("commit_ns",committed-saveAt)
                    put("feedback_draw_ns",firstDraw-saveAt);put("feedback_observed_ns",feedback.observed-saveAt)
                    put("known_saved_observed_ns",known.observed-saveAt);put("known_saved_phase",interaction.events.single { it.version==known.version && it.kind.startsWith("state_") }.kind)
                    put("result_observed_ns",result.observed-saveAt);put("ack_start_ns",ackAt-saveAt);put("ack_wall_ns",ackEnd-ackAt)
                    put("foreground_hold_max_ns",hold);put("ack_hold_max_ns",ackHold)
                    put("foreground_account_wait_ns",foreground.nanos("short_account_wait"));put("ack_account_wait_ns",ack.nanos("short_account_wait"))
                    put("cycle_to_final_ack_observed_ns",syncedSeen.observed-cycleAt)
                    put("preparation_attempts",attempts);put("foreground_access_decryptions",foreground.count("decrypt_access"));put("ack_access_decryptions",ack.count("decrypt_access"))
                    put("exact_first_presentation",false);put("formal_latency_gate",false)
                }.toString())
            } finally { release.complete(Unit);adapter.traceForTest=null;activeStore.afterPrepareForTest={} }
        }
        val expected=seedHistory+(if(immediateFirstEdit) 1 else 0)+sampleCount*2
        verifyDurable(expected)
        Log.i("G0MixedDurable","{\"metric_operations\":$expected,\"samples\":$sampleCount}")
        val failures=(extraPerformanceFailures()+immediateFailures).toMutableList()
        if(holds.any { it>150_000_000L }) failures+="resource max=${holds.max()} >150ms"
        (0 until sampleCount).groupBy(::sampleMode).forEach { (mode,indices) ->
            check(indices.size>=20)
            val modeCommits=indices.map { commits[it] };val modeResults=indices.map { results[it] };val modeFeedback=indices.map { feedbacks[it] }
            if(p95(modeCommits)>500_000_000L) failures+="$mode commit P95=${p95(modeCommits)} >500ms"
            if(p95(modeResults)>700_000_000L || modeResults.max()>2_000_000_000L)
                failures+="$mode result P95=${p95(modeResults)},max=${modeResults.max()}"
            if(modeFeedback.any { it>100_000_000L }) failures+="$mode feedback draw max=${modeFeedback.max()} >100ms"
        }
        assertTrue(failures.joinToString(";"),failures.isEmpty())
    }
}
