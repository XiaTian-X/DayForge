package com.dayforge.data.repository

import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Controlled order: a real first Count/Check proof starts after the metric is editable,
 * then a real save arrives while that proof is still running. No delay holds a SQL resource. */
class G0EarlyMixedEntryCostTest:G0MixedRecoveryTest() {
    private suspend fun typeName(old:String,new:String) {
        fun find(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
            if(node.isVisibleToUser && node.isEditable && node.text?.toString()==old) return node
            for(i in 0 until node.childCount) node.getChild(i)?.let { find(it)?.let { value->return value } }
            return null
        }
        val node=withTimeout(5_000) {
            var value:AccessibilityNodeInfo?=null
            while(value==null) { value=InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(::find);if(value==null) delay(16) }
            requireNotNull(value)
        }
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,new)
        }))
        val p=page();withTimeout(5_000) { p.metric.state.first { it.name==new } };metricSeen.await(p.metric.state.value.version)
    }
    private fun spans(t:G0PhaseTrace)=synchronized(t.spans) { t.spans.toList() }
    private fun resource(t:G0PhaseTrace):Long {
        val all=spans(t);val starts=all.filter { it.phase=="tx_begin_outer" };val ends=all.filter { it.phase=="tx_end_outer" }
        val holds=all.filter { it.phase=="domain_sql_hold" }
        assertEquals(starts.size,ends.size);assertEquals(starts.size,holds.size)
        starts.zip(ends).forEach { (a,b)->assertTrue(holds.any { it.start<=a.start && b.end<=it.end }) }
        return holds.maxOfOrNull { it.end-it.start } ?: 0L
    }
    private fun p95(x:List<Long>)=x.sorted()[kotlin.math.ceil(x.size*.95).toInt()-1]
    @Test(timeout=900_000) fun twentyEarliestSavesCompeteWithActualFirstReads()=runBlocking<Unit> {
        setupManaged(80);val before=pageNativeState()
        val commits=mutableListOf<Long>();val results=mutableListOf<Long>();val feedbacks=mutableListOf<Long>();val holds=mutableListOf<Long>()
        repeat(20) { sample ->
            val acquired=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val validated=CompletableDeferred<Unit>()
            val reading=G0PhaseTrace(driverTiming=false);val foreground=G0PhaseTrace(driverTiming=false)
            configureReader={ it.afterReadForTest={ validated.complete(Unit) } }
            configureAdapter={ it.traceForTest=reading;it.beforeReadForTest={ acquired.complete(Unit);release.await() } }
            val generation=lifecycle.state.value.generation
            val entryAt=controls.tap("recover")
            withTimeout(5_000) { lifecycle.state.first { it.generation>generation } }
            val p=page()
            val editable=withTimeout(20_000) { p.metric.state.first { it.phase==G0InteractionSession.Phase.EDITING && it.renderable() } }
            val firstEditable=metricSeen.await(editable.version);acquired.await()
            assertEquals(0,spans(reading).count { it.phase=="tx_begin_outer" })
            assertEquals(G0RecurringInteractionSession.Phase.LOADING,p.facts.state.value.phase)
            assertNull(p.facts.state.value.publication)
            typeName(editable.name,"Early save $sample")
            metricAdapter.traceForTest=foreground
            val oldVersion=p.metric.state.value.version
            release.complete(Unit)
            withTimeout(10_000) { while(spans(reading).count { it.phase=="tx_begin_outer" }<2) delay(1) }
            assertFalse("First business proof completed before save trigger",validated.isCompleted)
            val saveAt=metricSeen.tap("save")
            fun versions(kinds:Set<String>)=p.metric.events.filter { it.version>oldVersion && it.kind in kinds }.map { it.version }
            val feedback=metricSeen.awaitAny { versions(setOf("state_SAVING","state_SAVED_REFRESHING","state_SAVED")) }
            val known=if(p.metric.events.single { it.version==feedback.version && it.kind.startsWith("state_") }.kind!="state_SAVING") feedback
                else metricSeen.awaitAny { versions(setOf("state_SAVED_REFRESHING","state_SAVED")) }
            withTimeout(20_000) { p.metric.state.first { it.phase==G0InteractionSession.Phase.SAVED } }
            withTimeout(20_000) { p.facts.state.first { it.phase==G0RecurringInteractionSession.Phase.READY && it.renderable() } }
            val joint=pixels(p);p.metric.awaitSettled();p.facts.awaitSettled()
            requireVisiblePageText(setOf("计数今日：3320","打卡今日：1"))
            assertEquals("Early save $sample",p.metric.state.value.publication!!.value.name)
            assertEquals(1,p.metric.state.value.publication!!.value.pending)
            val committed=p.metric.events.single { it.kind=="committed" && it.version>oldVersion }.at
            val confirm=p.metric.events.single { it.kind=="confirm" && it.version>oldVersion }.at
            val firstRead=spans(reading).filter { it.phase=="domain_sql_hold" }[1]
            assertTrue("Actual first proof did not overlap confirmation",firstRead.start<=confirm && confirm<firstRead.end)
            val feedbackDraw=metricSeen.draws.filter { it.version in versions(setOf("state_SAVING","state_SAVED_REFRESHING","state_SAVED")) }.minOf { it.at }
            assertTrue(known.observed>=committed);assertTrue("Prior owner draw borrowed",feedbackDraw>=saveAt)
            val readHold=resource(reading);val writeHold=resource(foreground)
            commits+=committed-saveAt;results+=joint.observed-saveAt;feedbacks+=feedbackDraw-saveAt;holds+=listOf(readHold,writeHold)
            Log.i("G0EarlyMixed",buildJsonObject {
                put("sample",sample);put("count_history",80);put("metric_history",sample)
                put("entry_to_metric_editable_observed_ns",firstEditable.observed-entryAt)
                put("save_to_commit_ns",committed-saveAt);put("known_saved_observed_ns",known.observed-saveAt)
                put("joint_result_observed_ns",joint.observed-saveAt);put("feedback_draw_ns",feedbackDraw-saveAt)
                put("first_read_overlaps_confirm",true);put("read_hold_max_ns",readHold);put("write_hold_max_ns",writeHold)
                put("foreground_account_wait_ns",foreground.nanos("short_account_wait"))
                put("full_reads",readAdapter.reader.fullReads);put("reuse_hits",readAdapter.reader.reuseHits)
                put("order_barrier_before_any_read_resource",true);put("exact_first_presentation",false)
            }.toString())
            metricAdapter.traceForTest=null;metricSeen.tap("sync")
            withTimeout(20_000) { p.metric.state.first { it.phase==G0InteractionSession.Phase.SYNCED } }
            p.metric.awaitSettled();p.facts.awaitSettled();pixels(p)
            assertEquals(0,p.metric.state.value.publication!!.value.pending)
        }
        val after=pageNativeState()
        for(table in listOf("habits","completions","count_days","sync_outbox")) assertEquals(table,before[table],after[table])
        lifecycle.shutdown();storage.isolatedBuilder=null;storage.reopen()
        assertEquals(102,count("next_request_origins"));assertEquals(20,count("next_acceptances"));assertEquals(82,count("sync_outbox"))
        Log.i("G0EarlyMixedDurable","{\"samples\":20,\"origins\":102,\"acceptances\":20,\"foreign_outbox\":82}")
        val failures=buildList {
            if(p95(commits)>500_000_000L) add("commit P95=${p95(commits)} >500ms")
            if(p95(results)>700_000_000L) add("joint P95=${p95(results)} >700ms")
            if(feedbacks.max()>100_000_000L) add("feedback max=${feedbacks.max()} >100ms")
            if(holds.max()>150_000_000L) add("resource max=${holds.max()} >150ms")
        }
        assertTrue(failures.joinToString(";"),failures.isEmpty())
    }
}
