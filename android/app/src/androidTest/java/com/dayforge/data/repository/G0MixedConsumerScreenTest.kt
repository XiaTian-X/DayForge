package com.dayforge.data.repository

import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*

/** Two real consumers of one metric; not coverage of other mixed business domains. */
open class G0MixedConsumerScreenTest : G0InteractionContentionScreenTest() {
    internal open val deferReads=false
    internal open val resumeAfterCommit=false
    internal lateinit var summary:G0InteractionSession
    private lateinit var summaryAdapter:G0MetricInteractionAdapter
    private val summaryTrace=G0PhaseTrace(driverTiming=false)
    private lateinit var joint:G0ScreenObservation.Seen
    private val seen=G0ScreenObservation()
    private val summaryResults=mutableListOf<Long>()
    override fun installScreen() {
        summaryAdapter=runBlocking { additionalAdapter() };summaryAdapter.traceForTest=summaryTrace
        summary=G0InteractionSession(summaryAdapter,operationOwner,editable=false,
            deferBackgroundReadsDuring=if(deferReads) interaction.state else null,resumeBackgroundReadsAfterCommit=resumeAfterCommit)
        activity.scenario.onActivity {
            it.setContent {
                Column(Modifier.fillMaxSize()) {
                    SummaryPane(summary,seen)
                    Box(Modifier.weight(1f)) { G0InteractionScreen(interaction,observation) }
                }
            }
            summary.open() // Actual page entry, not hidden work before the measured setup timestamp.
        }
    }
    internal override suspend fun observeContent(state:G0InteractionSession.State,prior:G0ScreenObservation.Seen?):G0ScreenObservation.Seen {
        val value=requireNotNull(state.publication).value
        return G0ScreenObservation.awaitTogether {
            val other=summary.state.value
            if(other.phase!=G0InteractionSession.Phase.VIEWING || !other.renderable() ||
                other.publication?.value!=value) null
            else listOf(G0ScreenObservation.Target(observation,state.version,state::renderable),
                G0ScreenObservation.Target(seen,other.version,other::renderable))
        }.also { joint=it }
    }
    override suspend fun afterInitialScreen(setupAt:Long) {
        val observed=joint
        summary.awaitSettled();assertEquals(1,builds)
        Log.i("G0MixedInitial",buildJsonObject {
            put("setup_to_both_consumers_observed_ns",observed.observed-setupAt)
            put("builds",builds);put("exact_first_presentation",false)
        }.toString())
    }
    override suspend fun afterSavedScreen(sample:Int,saveAt:Long) {
        val fresh=summary.state.value
        assertEquals("Confirmed $sample",fresh.publication!!.value.name);assertEquals(1,fresh.publication.value.pending)
        val observed=joint;summary.awaitSettled()
        Log.i("G0SummaryTrace",buildJsonObject {
            put("sample",sample);put("mode",sampleMode(sample));put("spans",traceJson(summaryTrace,saveAt))
            put("read_count",summaryAdapter.spans.count { it.stage=="read" && it.ended>=saveAt })
        }.toString())
        val elapsed=observed.observed-saveAt;summaryResults+=elapsed
        Log.i("G0MixedSummary",buildJsonObject {
            put("sample",sample);put("mode",sampleMode(sample));put("save_to_summary_observed_ns",elapsed)
            put("pending",fresh.publication!!.value.pending);put("builds",builds)
            put("exact_first_presentation",false)
        }.toString())
    }
    override suspend fun afterSyncedScreen(sample:Int) {
        val fresh=summary.state.value;summary.awaitSettled()
        assertTrue(fresh.renderable());assertEquals(0,fresh.publication!!.value.pending)
        assertEquals("Confirmed $sample",fresh.publication.value.name)
    }
    override fun extraPerformanceFailures():List<String> = buildList {
        (0 until sampleCount).groupBy(::sampleMode).forEach { (mode,indices) ->
            val values=indices.map { summaryResults[it] }
            if(p95(values)>700_000_000L || values.max()>2_000_000_000L)
                add("$mode summary result P95=${p95(values)},max=${values.max()}")
        }
        val hold=resource(summaryTrace)
        if(hold>150_000_000L) add("summary resource max=$hold >150ms")
    }
    @After fun closeSummary()=runBlocking<Unit> {
        if(::summary.isInitialized) { withContext(Dispatchers.Main) { summary.detach() };summary.awaitSettled() }
    }
}

@Composable
private fun SummaryPane(session:G0InteractionSession,observation:G0ScreenObservation) {
    val state=session.state.collectAsState().value
    MaterialTheme {
        Column(Modifier.fillMaxWidth().height(94.dp).padding(horizontal=20.dp).drawWithContent {
            if(state.renderable()) drawContent() else drawRect(Color.Black)
        }) {
            Box(Modifier.fillMaxWidth().height(12.dp).onGloballyPositioned { observation.placed(it.boundsInWindow()) }
                .drawWithContent {
                    if(state.renderable()) {
                        val width=size.width/26
                        for(i in 0 until 26) {
                            val color=when(i) { 0->Color.Magenta;25->Color.Cyan
                                else->if((observation.encodedVersion(state.version) shr(i-1)) and 1L==1L) Color.White else Color.Black }
                            drawRect(color,Offset(i*width,0f),Size(width+0.1f,size.height))
                        }
                        observation.drawn(state.version)
                    }
                })
            Text("只读摘要 · G0隔离验证")
            if(state.renderable() && state.publication!=null) {
                Text(state.publication.value.name)
                Text("待同步：${state.publication.value.pending}")
            } else Text("正在核验摘要")
        }
    }
}
