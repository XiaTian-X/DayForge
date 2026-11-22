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

/** No read-order barrier. Same image, interleaved strategies, immediate or paced actual text
 * actions. Synthetic cadence is not a measured human population distribution. */
class G0NaturalMixedEntryCostTest:G0MixedRecoveryTest() {
    override var retainRecurringResults=true
    private suspend fun typeName(old:String,new:String):Pair<Long,Long> {
        val lookupAt=android.os.SystemClock.elapsedRealtimeNanos()
        fun editable(node:AccessibilityNodeInfo)=node.isVisibleToUser && node.isEnabled && node.isEditable &&
            node.text?.toString()==old && node.actionList.any { it.id==AccessibilityNodeInfo.ACTION_SET_TEXT }
        fun find(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
            if(editable(node) && node.refresh() && editable(node)) return node
            for(i in 0 until node.childCount) node.getChild(i)?.let { find(it)?.let { value->return value } }
            return null
        }
        val node=withTimeout(5_000) {
            var value:AccessibilityNodeInfo?=null
            while(value==null) { value=InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(::find);if(value==null) delay(16) }
            requireNotNull(value)
        }
        val at=android.os.SystemClock.elapsedRealtimeNanos()
        val accepted=node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,new)
        })
        Log.i("G0NaturalInputDriver","{\"lookup_ns\":${at-lookupAt},\"accepted\":$accepted,\"enabled\":${node.isEnabled},\"refresh_checked\":true}")
        assertTrue("Fresh enabled text node rejected action; phase=${page().metric.state.value.phase}",accepted)
        val p=page();val typed=withTimeout(5_000) { p.metric.state.first { it.name==new } }
        val seen=metricSeen.await(typed.version)
        val draw=metricSeen.draws.first { it.version==typed.version }.at
        assertTrue(draw>=at);return (draw-at) to (seen.observed-at)
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
    @Test(timeout=900_000) fun immediateEditingWithoutReadBarrier()=runBlocking<Unit> { compare(false) }
    @Test(timeout=900_000) fun pacedEditingWithoutReadBarrier()=runBlocking<Unit> { compare(true) }
    private suspend fun compare(paced:Boolean) {
        setupManaged(80);val before=pageNativeState();val image=storage.snapshotClosedDatabase()
        val samples=mutableListOf<Pair<Boolean,List<Long>>>()
        val order=List(10) { if(it%2==0) listOf(false,true,true,false) else listOf(true,false,false,true) }.flatten()
        order.forEachIndexed { sample,candidate ->
            // Restore the identical closed synthetic fixture BEFORE timing the user's entry.
            lifecycle.state.value.page?.drain();storage.restoreClosedDatabase(image)
            retainRecurringResults=candidate
            val reading=G0PhaseTrace(driverTiming=false);val foreground=G0PhaseTrace(driverTiming=false)
            configureAdapter={ it.traceForTest=reading }
            val generation=lifecycle.state.value.generation
            val entryAt=controls.tap("recover")
            withTimeout(5_000) { lifecycle.state.first { it.generation>generation } }
            val p=page()
            val editable=withTimeout(20_000) { p.metric.state.first { it.phase==G0InteractionSession.Phase.EDITING && it.renderable() } }
            val firstEditable=metricSeen.await(editable.version)
            val typing=mutableListOf<Pair<Long,Long>>()
            val name="Natural metric"
            if(paced) {
                typing+=typeName(editable.name,"N");delay(400)
                typing+=typeName("N","Natural");delay(400)
                typing+=typeName("Natural",name)
            } else typing+=typeName(editable.name,name)
            metricAdapter.traceForTest=foreground
            val oldVersion=p.metric.state.value.version
            val saveAt=metricSeen.tap("save")
            fun versions(kinds:Set<String>)=p.metric.events.filter { it.version>oldVersion && it.kind in kinds }.map { it.version }
            val feedback=metricSeen.awaitAny { versions(setOf("state_SAVING","state_SAVED_REFRESHING","state_SAVED")) }
            val known=if(p.metric.events.single { it.version==feedback.version && it.kind.startsWith("state_") }.kind!="state_SAVING") feedback
                else metricSeen.awaitAny { versions(setOf("state_SAVED_REFRESHING","state_SAVED")) }
            withTimeout(20_000) { p.metric.state.first { it.phase==G0InteractionSession.Phase.SAVED } }
            withTimeout(20_000) { p.facts.state.first { it.phase==G0RecurringInteractionSession.Phase.READY && it.renderable() } }
            val joint=pixels(p);p.metric.awaitSettled();p.facts.awaitSettled()
            requireVisiblePageText(setOf("计数今日：3320","打卡今日：1"))
            assertEquals(name,p.metric.state.value.publication!!.value.name)
            assertEquals(1,p.metric.state.value.publication!!.value.pending)
            val committed=p.metric.events.single { it.kind=="committed" && it.version>oldVersion }.at
            val confirm=p.metric.events.single { it.kind=="confirm" && it.version>oldVersion }.at
            val overlaps=spans(reading).any { it.phase=="domain_sql_hold" && it.start<=confirm && confirm<it.end }
            assertEquals(if(candidate) 0 else 1,readAdapter.reader.fullReads)
            assertEquals(if(candidate) 1 else 0,readAdapter.reader.coldHits)
            val feedbackDraw=metricSeen.draws.filter { it.version in versions(setOf("state_SAVING","state_SAVED_REFRESHING","state_SAVED")) }.minOf { it.at }
            assertTrue(known.observed>=committed);assertTrue("Prior owner draw borrowed",feedbackDraw>=saveAt)
            val readHold=resource(reading);val writeHold=resource(foreground)
            samples+=candidate to listOf(committed-saveAt,joint.observed-saveAt,feedbackDraw-saveAt,readHold,writeHold,firstEditable.observed-entryAt,typing.maxOf { it.first })
            Log.i("G0NaturalMixed",buildJsonObject {
                put("sample",sample);put("candidate",candidate);put("paced",paced);put("count_history",80);put("metric_history",0)
                put("entry_to_metric_editable_observed_ns",firstEditable.observed-entryAt)
                put("entry_to_joint_result_observed_ns",joint.observed-entryAt)
                put("save_to_commit_ns",committed-saveAt);put("known_saved_observed_ns",known.observed-saveAt)
                put("joint_result_observed_ns",joint.observed-saveAt);put("feedback_draw_ns",feedbackDraw-saveAt)
                put("first_read_overlaps_confirm",overlaps);put("read_hold_max_ns",readHold);put("write_hold_max_ns",writeHold)
                put("foreground_account_wait_ns",foreground.nanos("short_account_wait"))
                put("proof_queries",readAdapter.reader.proofQueries);put("cold_hits",readAdapter.reader.coldHits);put("full_reads",readAdapter.reader.fullReads);put("reuse_hits",readAdapter.reader.reuseHits)
                put("read_order_barrier",false);put("explicit_input_pacing_ms",if(paced) 800 else 0)
                put("input_draw_max_ns",typing.maxOf { it.first });put("input_observed_max_ns",typing.maxOf { it.second })
                put("exact_first_presentation",false)
            }.toString())
            metricAdapter.traceForTest=null;val syncAt=metricSeen.tap("sync")
            withTimeout(20_000) { p.metric.state.first { it.phase==G0InteractionSession.Phase.SYNCED } }
            p.metric.awaitSettled();p.facts.awaitSettled();val synced=pixels(p)
            assertEquals(0,p.metric.state.value.publication!!.value.pending)
            val after=pageNativeState()
            for(table in listOf("habits","completions","count_days","sync_outbox")) assertEquals(table,before[table],after[table])
            assertEquals(83,after.getValue("next_request_origins").size);assertEquals(1,after.getValue("next_acceptances").size)
            Log.i("G0NaturalMixedDurable","{\"sample\":$sample,\"candidate\":$candidate,\"paced\":$paced,\"sync_to_joint_observed_ns\":${synced.observed-syncAt},\"origins\":83,\"acceptances\":1,\"foreign_outbox\":82}")
        }
        lifecycle.shutdown()
        val failures=buildList {
            for(candidate in listOf(false,true)) {
                val group=samples.filter { it.first==candidate }.map { it.second };assertEquals(20,group.size)
                if(p95(group.map { it[5] })>2_000_000_000L) add("candidate=$candidate editable P95 >2s")
                if(group.maxOf { it[6] }>100_000_000L) add("candidate=$candidate input draw max >100ms")
                if(p95(group.map { it[0] })>500_000_000L) add("candidate=$candidate commit P95 >500ms")
                if(p95(group.map { it[1] })>700_000_000L) add("candidate=$candidate joint P95 >700ms")
                if(group.maxOf { it[2] }>100_000_000L) add("candidate=$candidate feedback max >100ms")
                if(group.maxOf { maxOf(it[3],it[4]) }>150_000_000L) add("candidate=$candidate resource max >150ms")
            }
        }
        assertTrue(failures.joinToString(";"),failures.isEmpty())
    }
}
