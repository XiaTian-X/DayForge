package com.dayforge.data.repository

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*

/** Original household screen/buttons plus original Dialog in its independent window.
 * All candidate jobs drain before original oracle connections are opened. */
abstract class G0CompletionPromptScreenFixture:G0CompletionPromptFixture() {
    private val harnesses=mutableListOf<Harness>()
    internal fun now()=SystemClock.elapsedRealtimeNanos()
    internal inner class Harness(val seed:Seed,val windowMode:G0CompletionPromptSession.WindowMode,
        val commandFeedback:Boolean=false) {
        val job=SupervisorJob()
        val control=CoroutineScope(job+Dispatchers.IO)
        val cold=G0PhaseTrace(driverTiming=false)
        @Volatile var openReady:G0ScreenObservation.Seen?=null;private set
        lateinit var store:G0HouseholdCountStore
        private val promptRegistry=Any()
        private val prompts=linkedMapOf<G0HouseholdCountPage,G0CompletionPromptSession>()
        private var registryClosed=false
        val connectionClosing=CompletableDeferred<Unit>()
        @Volatile var connectionClosedAt=0L;private set
        val connection=G0CountConnectionOwner {
            connectionClosing.complete(Unit)
            drainPrompts()
            db.close();connectionClosedAt=now()
        }
        val runtime=G0HouseholdCountRuntime(control,connection,authority,tokens,{domainGate.changeSequence},seed.http) {
            cold.run {openPromptStore(seed).also {store=it}}
        }
        val seen=G0ScreenObservation()
        private val pagePixels=ConcurrentHashMap<G0HouseholdCountPage,G0ScreenObservation>()
        private val feedbackPixels=ConcurrentHashMap<G0HouseholdCountPage,G0HouseholdFeedbackObservation>()
        private val commandPixels=ConcurrentHashMap<G0HouseholdCountPage,G0HouseholdCommandFeedbackObservation>()
        private val promptPixels=ConcurrentHashMap<G0HouseholdCountPage,G0CompletionPromptObservation>()
        fun seen(page:G0HouseholdCountPage)=pagePixels.computeIfAbsent(page) {G0ScreenObservation()}
        fun feedback(page:G0HouseholdCountPage)=feedbackPixels.computeIfAbsent(page) {G0HouseholdFeedbackObservation()}
        fun commandFeedback(page:G0HouseholdCountPage)=commandPixels.computeIfAbsent(page) {G0HouseholdCommandFeedbackObservation()}
        fun observation(page:G0HouseholdCountPage)=promptPixels.computeIfAbsent(page) {G0CompletionPromptObservation()}
        private fun promptIfOpen(page:G0HouseholdCountPage):G0CompletionPromptSession?=synchronized(promptRegistry) {
            prompts[page]?.let {return@synchronized it}
            val outer=runtime.state.value
            if(registryClosed || outer.phase!=G0HouseholdCountRuntime.Phase.OPEN || outer.page!==page || !page.live())
                return@synchronized null
            val prompt=try {G0CompletionPromptSession(store,page,authority,domainGate.changeSequence,control,windowMode) {
                val outer=runtime.state.value
                outer.phase==G0HouseholdCountRuntime.Phase.OPEN && outer.page===page && page.live()
            }} catch(revoked:G0AuthorityOwner.Denied) {return@synchronized null}
            prompts[page]=prompt;prompt
        }
        fun prompt(page:G0HouseholdCountPage)=requireNotNull(promptIfOpen(page)) {"G0_PROMPT_PAGE_RETIRED"}
        /** Registry closure and reader drainage belong to the connection ticket, so
         * automatic Runtime retirement has the same ordering as explicit teardown. */
        private suspend fun drainPrompts() {
            val closing=synchronized(promptRegistry) {registryClosed=true;prompts.values.toList()}
            closing.forEach {it.retire()};closing.forEach {it.shutdown()}
        }
        fun install() {
            activity.scenario.onActivity {it.setContent {MaterialTheme {
                val outer=runtime.state.collectAsState().value
                G0HouseholdCountRuntimeScreen(runtime,seen,::seen,feedbackSeen=::feedback,
                    commandFeedback=commandFeedback,commandSeen={page->commandFeedback(page)},
                    countAction={page,id,busy->promptIfOpen(page)?.count(id,busy) ?: false})
                outer.page?.let {page->promptIfOpen(page)?.let {prompt->G0CompletionPromptScreen(prompt,observation(page))}}
            }}}
        }
        suspend fun open():Pair<Long,G0HouseholdCountPage> {
            install();seen.await(runtime.state.value.version)
            val at=seen.tap("open")
            val page=withTimeout(90_000) {requireNotNull(runtime.state.first {
                it.phase==G0HouseholdCountRuntime.Phase.OPEN
            }.page)}
            withTimeout(20_000) {page.state.first {it.view?.valid()==true}}
            openReady=householdPixels(runtime,page,seen(page))
            return at to page
        }
        suspend fun count(page:G0HouseholdCountPage,id:String=habit.uuid):Pair<Long,G0HouseholdCountStore.Saved> {
            val previous=page.saved
            val at=seen(page).tap("increment:$id")
            val state=withTimeout(20_000) {page.state.first {
                (it.phase==G0HouseholdCountPage.Phase.RESULT && it.commitNotice?.saved?.let {saved->saved!==previous}==true) ||
                    it.phase==G0HouseholdCountPage.Phase.RECOVERY
            }}
            assertEquals(G0HouseholdCountPage.Phase.RESULT,state.phase);page.settled();assertNull(page.error)
            return at to requireNotNull(page.saved)
        }
        suspend fun shown(page:G0HouseholdCountPage):Pair<G0CompletionPromptSession.Expected,G0ScreenObservation.Seen> {
            val session=prompt(page);val pixels=observation(page)
            var chosen:G0CompletionPromptSession.Expected?=null
            val seen=G0ScreenObservation.awaitTogether {
                session.state.value.expected?.takeIf {session.renderable(it)}?.let {
                    chosen=it;listOf(pixels.target(session,it))
                }
            }
            val expected=requireNotNull(chosen);assertEquals(expected.version,seen.version)
            assertNotNull(pixels.dialogViewIdentity)
            return expected to seen
        }
        suspend fun waiting(page:G0HouseholdCountPage):Pair<G0CompletionPromptSession.State,G0ScreenObservation.Seen> {
            val session=prompt(page);val pixels=observation(page)
            var chosen:G0CompletionPromptSession.State?=null
            val seen=G0ScreenObservation.awaitTogether {
                val state=session.state.value
                state.window?.takeIf {session.waiting(it,state)}?.let {
                    chosen=state;listOf(pixels.waitingTarget(session,it,state))
                }
            }
            return requireNotNull(chosen) to seen
        }
        suspend fun synced()=withTimeout(90_000) {runtime.awaitSynced()}
        suspend fun close()=withContext(NonCancellable) {
            drainPrompts()
            runtime.shutdown();job.cancelAndJoin()
        }
    }
    internal fun harness(seed:Seed,windowMode:G0CompletionPromptSession.WindowMode=G0CompletionPromptSession.WindowMode.RECREATE,
        commandFeedback:Boolean=false)=Harness(seed,windowMode,commandFeedback).also {harnesses+=it}

    internal enum class PageTextRetry { INVALID_VIEW, INCOMPLETE_TEXT, CHANGED_DURING_READ }
    internal data class PageTextSeen(val state:G0HouseholdCountPage.State,val runtimeVersion:Long,
        val started:Long,val observed:Long,val passes:Int,val invalidViews:Int,
        val incompleteTexts:Int,val changedDuringRead:Int)
    /** Observation only: a prior qualified screenshot does not authorize a later AX
     * snapshot. Wait for one current, authenticated version's complete card/home text.
     * This does not refresh the page, repeat an input or acquire a new permission. */
    internal suspend fun householdResultText(h:Harness,page:G0HouseholdCountPage,
        command:G0HouseholdCountStore.Command,saved:G0HouseholdCountStore.Saved,counts:Map<String,Long>,
        onRetryForTest:((PageTextRetry)->Unit)?=null):PageTextSeen=withTimeout(20_000) {
        val started=now();val lease=authority.lease()
        val expectedCounts=counts.toMap()
        check(saved.requestId==command.requestId && saved.factUuid==command.factUuid &&
            saved.habitUuid==command.habitUuid)
        assertEquals(currentHousehold.map {it.uuid}.toSet(),expectedCounts.keys)
        val homeText="首页："+currentHousehold.joinToString("，") {"${it.name}=${expectedCounts.getValue(it.uuid)}"}
        val expectedText=currentHousehold.map {"${it.name}：${expectedCounts.getValue(it.uuid)} / ${it.targetValue}"}+homeText
        var passes=0;var invalid=0;var incomplete=0;var changed=0
        while(true) {
            currentCoroutineContext().ensureActive();authority.checkLease(lease)
            h.runtime.error?.let {throw it};page.error?.let {throw it}
            val outer=h.runtime.state.value;val state=page.state.value;val view=state.view
            check(outer.phase==G0HouseholdCountRuntime.Phase.OPEN && outer.page===page && page.live())
            val intent=requireNotNull(state.intent)
            check(page.command===command && page.saved===saved && intent.command===command)
            val notice=requireNotNull(state.commitNotice)
            check(notice.command===command && notice.saved===saved &&
                notice.firstStateVersion==intent.firstStateVersion)
            fun current()=h.runtime.state.value===outer && outer.page===page && h.runtime.renderable() &&
                page.live() && page.command===command && page.saved===saved && page.state.value===state &&
                state.phase==G0HouseholdCountPage.Phase.RESULT && state.view===view && view?.valid()==true &&
                state.intent?.command===command && state.commitNotice===notice
            passes++
            val reason=if(!current())PageTextRetry.INVALID_VIEW else {
                val currentView=requireNotNull(view)
                assertEquals(expectedCounts,currentView.cards.mapValues {it.value.history.todayQuantity})
                val source=currentView.cards.getValue(saved.habitUuid).followUp
                assertEquals(saved.habitUuid,source.claim.habitUuid);assertEquals(saved.factUuid,source.claim.completionUuid)
                // All three strings must come from one fresh root traversal, not
                // independent queries which could each observe a different version.
                val text=mutableListOf<String>()
                fun collect(node:AccessibilityNodeInfo) {
                    if(node.isVisibleToUser)node.text?.toString()?.let {text+=it}
                    for(i in 0 until node.childCount)node.getChild(i)?.let(::collect)
                }
                val root=InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                if(root?.refresh()==true)collect(root)
                authority.checkLease(lease)
                when {
                    !current()->PageTextRetry.CHANGED_DURING_READ
                    expectedText.all {expected->text.any {it.contains(expected)}}->
                        return@withTimeout PageTextSeen(state,outer.version,started,now(),passes,invalid,incomplete,changed)
                    else->PageTextRetry.INCOMPLETE_TEXT
                }
            }
            when(reason) {
                PageTextRetry.INVALID_VIEW->invalid++
                PageTextRetry.INCOMPLETE_TEXT->incomplete++
                PageTextRetry.CHANGED_DURING_READ->changed++
            }
            onRetryForTest?.invoke(reason)
            delay(16)
        }
        @Suppress("UNREACHABLE_CODE") error("No current complete card/home AX snapshot")
    }

    internal fun visibleText(fragment:String):Boolean {
        fun find(node:AccessibilityNodeInfo):Boolean {
            if(node.isVisibleToUser && node.text?.toString()?.contains(fragment)==true)return true
            for(i in 0 until node.childCount)node.getChild(i)?.let {if(find(it))return true}
            return false
        }
        return InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(::find)==true
    }
    internal suspend fun awaitDialogText()=withTimeout(5_000) {
        while(!visibleText("${metric.name} (${metric.unit})"))delay(16)
    }
    internal fun back():Long {
        val at=now();InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);return at
    }
    internal suspend fun removed(matches:(Bitmap)->Boolean,requireNoMetricText:Boolean=true):Long=withTimeout(10_000) {
        while(true) {
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val gone=try {bitmap!=null && !matches(bitmap) &&
                (!requireNoMetricText || !visibleText("${metric.name} (${metric.unit})"))} finally {bitmap?.recycle()}
            if(gone)return@withTimeout now()
            delay(16)
        }
        @Suppress("UNREACHABLE_CODE") error("No removal")
    }
    internal suspend fun clickDialogText(text:String):Long {
        fun find(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
            if(node.isVisibleToUser && node.text?.toString()==text && node.refresh()) {
                var target:AccessibilityNodeInfo?=node
                while(target!=null) {
                    if(target.isClickable && target.isEnabled && target.isVisibleToUser && target.refresh())return target
                    target=target.parent
                }
            }
            for(i in 0 until node.childCount)node.getChild(i)?.let {find(it)?.let {found->return found}}
            return null
        }
        val node=withTimeout(5_000) {
            var found:AccessibilityNodeInfo?=null
            while(found==null) {
                found=InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(::find)
                if(found==null)delay(16)
            }
            requireNotNull(found)
        }
        val at=now();check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));return at
    }
    internal suspend fun typeMetric(value:String) {
        fun eligible(node:AccessibilityNodeInfo)=node.isEditable && node.isEnabled && node.isVisibleToUser &&
            node.actionList.any {it.id==AccessibilityNodeInfo.ACTION_SET_TEXT}
        fun find(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
            if(eligible(node) && node.refresh() && eligible(node))return node
            for(i in 0 until node.childCount)node.getChild(i)?.let {find(it)?.let {found->return found}}
            return null
        }
        val node=withTimeout(5_000) {
            var found:AccessibilityNodeInfo?=null
            while(found==null) {
                found=InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(::find)
                if(found==null)delay(16)
            }
            requireNotNull(found)
        }
        check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)
        }))
    }
    internal suspend fun terminal(h:Harness,page:G0HouseholdCountPage,total:Int,source:WidgetFactSnapshot,
        expected:List<com.dayforge.ui.components.LinkedMetricInfo>?):Long {
        h.synced();val result=withTimeout(20_000) {requireNotNull(page.state.first {it.view?.valid()==true}.view)}
        assertEquals(total,pageRowCount("completions"));assertEquals(total,pageRowCount("next_acceptances"))
        assertEquals(0,pageRowCount("sync_outbox"));assertEquals(0,h.runtime.pendingCount)
        val native=pageNativeState();val prefs=dataStore.data.first()
        val deliveries=h.runtime.workerAttempts.map {requireNotNull(it.delivery)}
        h.close();reopenReference();sameHouseholdAsOriginal(result)
        for(delivery in deliveries)assertEquals(NextOperationAcceptance.REPLAYED,sender(h.seed.http).acceptOperation(delivery))
        val referencePromptNs=originalPrompt(source,expected)
        assertEquals(native,pageNativeState());assertEquals(prefs,dataStore.data.first())
        return referencePromptNs
    }
    @After fun closePromptHarnesses()=runBlocking<Unit> {harnesses.forEach {it.close()}}
}
