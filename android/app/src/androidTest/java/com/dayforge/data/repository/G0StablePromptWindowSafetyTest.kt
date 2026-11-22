package com.dayforge.data.repository

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.ui.components.MetricInputState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual original Dialog windows, real count/send/ACK and fresh READ authority.
 * Held intervals expose races; they are safety fixtures, never latency samples. */
@RunWith(AndroidJUnit4::class)
class G0StablePromptWindowSafetyTest:G0CompletionPromptScreenFixture() {
    private val ui get()=InstrumentationRegistry.getInstrumentation().uiAutomation
    private var originalAccessibilityFlags:Int?=null

    @Before fun retrieveAllInteractiveWindows() {
        val info=ui.serviceInfo
        originalAccessibilityFlags=info.flags
        info.flags=info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        ui.serviceInfo=info
    }
    @After fun restoreAccessibilityFlags() {
        originalAccessibilityFlags?.let {flags->ui.serviceInfo=ui.serviceInfo.apply {this.flags=flags}}
    }

    private suspend fun until(predicate:()->Boolean)=withTimeout(20_000) {
        while(!predicate())delay(16)
    }
    private suspend fun awaitDetached(view:View,window:Window) {
        val decor=window.decorView
        until {
            var detached=false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                detached=!view.isAttachedToWindow && !decor.isAttachedToWindow
            }
            detached
        }
    }
    private fun nodes(root:AccessibilityNodeInfo):List<AccessibilityNodeInfo> {
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(node:AccessibilityNodeInfo) {
            result+=node
            for(index in 0 until node.childCount)node.getChild(index)?.let(::visit)
        }
        visit(root);return result
    }
    private fun fields(node:AccessibilityNodeInfo):List<String> = buildList {
        listOf(node.text,node.contentDescription,node.error).forEach {it?.toString()?.let(::add)}
        if(Build.VERSION.SDK_INT>=26)node.hintText?.toString()?.let(::add)
        if(Build.VERSION.SDK_INT>=28) {
            node.paneTitle?.toString()?.let(::add)
            node.tooltipText?.toString()?.let(::add)
        }
        if(Build.VERSION.SDK_INT>=30)node.stateDescription?.toString()?.let(::add)
    }
    private fun root(windowId:Int):AccessibilityNodeInfo? =
        ui.windows.singleOrNull {it.id==windowId}?.root
            ?: ui.rootInActiveWindow?.takeIf {it.windowId==windowId}
    private suspend fun dialogNodes():List<AccessibilityNodeInfo> {
        var found:List<AccessibilityNodeInfo>?=null
        until {
            val current=ui.rootInActiveWindow?.let(::nodes)
            found=current?.takeIf {all->all.any {it.isEditable && it.isEnabled}}
            found!=null
        }
        return requireNotNull(found)
    }
    private fun clickableText(all:List<AccessibilityNodeInfo>,text:String):AccessibilityNodeInfo {
        var node:AccessibilityNodeInfo?=all.first {it.text?.toString()==text}
        while(node!=null && !node.isClickable)node=node.parent
        return requireNotNull(node)
    }
    private suspend fun assertAnonymous(windowId:Int,forbidden:List<String>) {
        until {root(windowId)?.let {tree->nodes(tree).any {it.text?.toString()=="正在核对提示…"}}==true}
        val all=nodes(requireNotNull(root(windowId)))
        assertTrue(all.any {it.text?.toString()=="正在核对提示…"})
        // Inspect every returned node, including invisible nodes and non-text fields.
        val exported=all.flatMap(::fields)
        for(value in forbidden)assertFalse("Anonymous window exported $value",exported.any {value in it})
        assertFalse(all.any {it.isEditable || it.isCheckable})
        assertFalse(all.any {node->node.actionList.any {it.id==AccessibilityNodeInfo.ACTION_SET_TEXT}})
        assertFalse(exported.any {it==app.getString(R.string.post_checkin_skip) ||
            it==app.getString(R.string.post_checkin_record) || it==app.getString(R.string.post_checkin_dont_ask_again)})
    }
    private fun assertOldNodeUnavailable(node:AccessibilityNodeInfo,action:Int,forbidden:List<String>) {
        if(node.refresh()) {
            assertFalse(fields(node).any {field->forbidden.any {it in field}})
            assertFalse(node.isEditable)
        }
        val arguments=if(action==AccessibilityNodeInfo.ACTION_SET_TEXT)Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"999")
        } else null
        assertFalse("Removed personal node accepted its old action",node.performAction(action,arguments))
    }
    private fun assertStale(session:G0CompletionPromptSession,expected:G0CompletionPromptSession.Expected) {
        assertFalse(session.close(expected))
        for(operation in listOf("skip","record"))assertEquals("G0_PROMPT_STALE_CALLBACK",
            runCatching {session.rejectUnsupported(expected,operation)}.exceptionOrNull()?.message)
        val empty=expected.metrics.map {MetricInputState(it.metricId,it.metricName,it.unit,it.decimalPlaces)}
        assertEquals("G0_PROMPT_STALE_CALLBACK",
            runCatching {session.validateEmptyDraft(expected,empty)}.exceptionOrNull()?.message)
    }
    private suspend fun nextShown(h:Harness,page:G0HouseholdCountPage,old:G0CompletionPromptSession.Expected):
        G0CompletionPromptSession.Expected {
        val session=h.prompt(page)
        withTimeout(20_000) {session.state.first {it.expected!=null && it.expected!==old && session.renderable(it.expected)}}
        return h.shown(page).first.also {assertNotSame(old,it);awaitDialogText();session.settled()}
    }
    private suspend fun callback(session:G0CompletionPromptSession,version:Long,operation:String,outcome:String,after:Int=0) {
        until {session.callbacks.drop(after).any {it.version==version && it.operation==operation && it.outcome==outcome}}
    }
    private suspend fun closeShown(h:Harness,page:G0HouseholdCountPage,expected:G0CompletionPromptSession.Expected) {
        val session=h.prompt(page);val observation=h.observation(page)
        val ticket=requireNotNull(session.state.value.window)
        val view=requireNotNull(observation.actualView);val window=requireNotNull(observation.actualWindow)
        val frozen=observation.pixels.frozenMarker(expected.version)
        back();callback(session,expected.version,"draft","accepted");callback(session,expected.version,"close","accepted")
        removed(frozen);awaitDetached(view,window)
        assertNull(session.state.value.window);assertFalse(session.windowCurrent(ticket))
    }

    @Test fun realSendAndAckKeepOneWindowButReplaceProofAndRemoveAllPersonalAccessibilityDuringRefresh()=runBlocking<Unit> {
        val seed=seedPrompt();val h=harness(seed,G0CompletionPromptSession.WindowMode.RETAIN_REQUEST)
        val syncRelease=CompletableDeferred<Unit>();val ackEntered=CompletableDeferred<Unit>();val ackRelease=CompletableDeferred<Unit>()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val ackReadEntered=CompletableDeferred<Unit>();val ackReadRelease=CompletableDeferred<Unit>()
        h.runtime.beforeSyncBatchForTest={syncRelease.await()}
        h.runtime.beforeAcceptForTest={ackEntered.complete(Unit);ackRelease.await()}
        try {
            val page=h.open().second;val session=h.prompt(page);val observation=h.observation(page)
            val trace=G0PhaseTrace(driverTiming=false);session.traceForTest=trace
            h.count(page);val first=h.shown(page).first;awaitDialogText();session.settled()
            val ticket=requireNotNull(session.state.value.window)
            val window=requireNotNull(observation.actualWindow);val view=requireNotNull(observation.actualView)
            val beforeDraft=pageNativeState();val beforeDraftPrefs=dataStore.data.first()
            typeMetric("314159");callback(session,first.version,"draft","unsupported")
            assertTrue(dialogNodes().first {it.isCheckable}.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertEquals(beforeDraft,pageNativeState());assertEquals(beforeDraftPrefs,dataStore.data.first())
            val oldNodes=dialogNodes();val oldInput=oldNodes.first {it.isEditable}
            val oldSkip=clickableText(oldNodes,app.getString(R.string.post_checkin_skip))
            val windowId=oldInput.windowId;val forbidden=listOf(first.habitName,metric.name,"314159")
            session.beforePrepareForTest={entered.complete(Unit);release.await()}
            syncRelease.complete(Unit);withTimeout(20_000) {entered.await();ackEntered.await()}
            val waiting=h.waiting(page).first
            val anonymousTicket=requireNotNull(waiting.window)
            assertNull(waiting.expected);assertSame(ticket,waiting.window);assertTrue(session.waiting(ticket,waiting))
            assertSame(window,observation.actualWindow);assertSame(view,observation.actualView)
            assertFalse(session.capture(habit.uuid).eligible);assertSame(waiting,session.state.value)
            val native=pageNativeState();val prefs=dataStore.data.first()
            assertAnonymous(windowId,forbidden)
            assertOldNodeUnavailable(oldInput,AccessibilityNodeInfo.ACTION_SET_TEXT,forbidden)
            assertOldNodeUnavailable(oldSkip,AccessibilityNodeInfo.ACTION_CLICK,forbidden)
            assertStale(session,first);assertSame(waiting,session.state.value)
            session.beforePrepareForTest={};release.complete(Unit)
            val second=nextShown(h,page,first)
            assertSame(ticket,session.state.value.window);assertSame(window,observation.actualWindow)
            assertSame(view,observation.actualView);assertEquals(first.source.claim,second.source.claim)
            val refreshedNodes=dialogNodes()
            assertFalse(refreshedNodes.flatMap(::fields).any {"314159" in it})
            assertFalse(AccessibilityNodeInfoCompat.wrap(refreshedNodes.first {it.isCheckable}).isChecked)
            assertEquals(native,pageNativeState());assertEquals(prefs,dataStore.data.first())
            val attempts=session.attempts.size;val reads=trace.count("tx_begin_outer");val decrypts=trace.count("decrypt_access")
            repeat(5) {session.observeCurrentHintForTest()};session.settled()
            assertEquals(attempts,session.attempts.size);assertEquals(reads,trace.count("tx_begin_outer"))
            assertEquals(decrypts,trace.count("decrypt_access"))
            session.beforePrepareForTest={ackReadEntered.complete(Unit);ackReadRelease.await()}
            ackRelease.complete(Unit);withTimeout(20_000) {ackReadEntered.await()}
            val ackWaiting=h.waiting(page).first
            assertSame(ticket,ackWaiting.window);assertSame(window,observation.actualWindow);assertSame(view,observation.actualView)
            assertAnonymous(windowId,forbidden);assertStale(session,second)
            session.beforePrepareForTest={};ackReadRelease.complete(Unit)
            val third=nextShown(h,page,second)
            assertSame(ticket,session.state.value.window);assertSame(window,observation.actualWindow);assertSame(view,observation.actualView)
            assertEquals(first.source.claim,third.source.claim);assertStale(session,first);assertStale(session,second)
            assertEquals(3,session.attempts.count {it.outcome=="shown"})
            assertEquals(3,session.attempts.map {it.hint}.distinct().size)
            assertEquals(3,trace.count("tx_begin_outer"));assertEquals(3,trace.count("tx_end_outer"))
            assertEquals(12,trace.count("decrypt_access"));assertEquals(0,trace.count("decrypt_unclassified"))
            // These are Compose-effect events, not WindowManager attachment callbacks.
            // Effect reconstruction is harmless if every event still names the same native objects.
            val mounted=observation.windows.filter {it.mounted}
            assertEquals(setOf(System.identityHashCode(view)),mounted.map {it.viewIdentity}.toSet())
            assertEquals(setOf(System.identityHashCode(window)),mounted.map {it.windowIdentity}.toSet())
            assertNull(session.error);assertFalse(session.diagnosticsOverflow);assertFalse(observation.overflow)
            h.synced()
            val beforeCancel=pageNativeState();val beforeCancelPrefs=dataStore.data.first()
            val frozen=observation.pixels.frozenMarker(third.version)
            // A callback captured while anonymous owns this same request even after
            // fresh content returns; it never converts an old Expected into a new one.
            assertTrue(session.cancelWindow(anonymousTicket))
            removed(frozen);awaitDetached(view,window)
            assertEquals(G0CompletionPromptSession.Phase.IDLE,session.state.value.phase)
            assertNull(session.state.value.window);assertFalse(session.windowCurrent(anonymousTicket))
            assertTrue(session.windowCancellations.any {it.identity==anonymousTicket.identity && it.version==third.version && it.accepted})
            assertFalse(session.cancelWindow(anonymousTicket));assertStale(session,third)
            assertEquals(beforeCancel,pageNativeState());assertEquals(beforeCancelPrefs,dataStore.data.first())
            terminal(h,page,3,third.source,third.metrics)
        } finally {release.complete(Unit);ackReadRelease.complete(Unit);ackRelease.complete(Unit);syncRelease.complete(Unit);h.close()}
    }

    @Test fun anonymousBackCancelsOnlyItsRequestAndLateReadOrOldActionsCannotCloseTheNextPrompt()=runBlocking<Unit> {
        val seed=seedPrompt();val h=harness(seed,G0CompletionPromptSession.WindowMode.RETAIN_REQUEST)
        val syncRelease=CompletableDeferred<Unit>();val ackEntered=CompletableDeferred<Unit>();val ackRelease=CompletableDeferred<Unit>()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        h.runtime.beforeSyncBatchForTest={syncRelease.await()}
        h.runtime.beforeAcceptForTest={ackEntered.complete(Unit);ackRelease.await()}
        try {
            val page=h.open().second;val session=h.prompt(page);val observation=h.observation(page)
            h.count(page);val first=h.shown(page).first;awaitDialogText();session.settled()
            val firstTicket=requireNotNull(session.state.value.window);val firstWindow=requireNotNull(observation.actualWindow)
            val firstView=requireNotNull(observation.actualView)
            session.beforePrepareForTest={entered.complete(Unit);release.await()}
            syncRelease.complete(Unit);withTimeout(20_000) {entered.await();ackEntered.await()}
            val waiting=h.waiting(page).first;val frozen=observation.waitingPixels.frozenMarker(waiting.version)
            val native=pageNativeState();val prefs=dataStore.data.first();val beforeCallbacks=session.callbacks.size
            back();until {session.windowCancellations.any {it.identity==firstTicket.identity && it.accepted}}
            removed(frozen);awaitDetached(firstView,firstWindow);assertEquals(G0CompletionPromptSession.Phase.IDLE,session.state.value.phase)
            assertNull(session.state.value.window);assertEquals(beforeCallbacks,session.callbacks.size)
            assertEquals(native,pageNativeState());assertEquals(prefs,dataStore.data.first())
            assertFalse(session.cancelWindow(firstTicket));assertStale(session,first)
            householdPixels(h.runtime,page,h.seen(page))
            val secondSaved=h.count(page).second
            assertNull(session.state.value.window)
            assertFalse(session.cancelWindow(firstTicket))
            session.beforePrepareForTest={};release.complete(Unit)
            val second=h.shown(page).first;awaitDialogText();session.settled()
            val secondTicket=requireNotNull(session.state.value.window)
            assertNotSame(firstTicket,secondTicket);assertNotSame(firstWindow,observation.actualWindow)
            assertEquals(secondSaved.factUuid,second.source.claim.completionUuid)
            assertFalse(session.cancelWindow(firstTicket));assertStale(session,first)
            assertSame(second,session.state.value.expected);assertTrue(session.windowCurrent(secondTicket))
            assertEquals(1,session.events.count {it.expected?.request===first.request})
            assertTrue(session.attempts.any {it.generation==first.request.generation && it.outcome=="discarded"})
            closeShown(h,page,second);ackRelease.complete(Unit);terminal(h,page,4,second.source,second.metrics)
        } finally {release.complete(Unit);ackRelease.complete(Unit);syncRelease.complete(Unit);h.close()}
    }

    @Test fun anonymousCancellationRetiresImmediatelyButShutdownDrainsTheActualHeldReadBeforeClosingDatabase()=runBlocking<Unit> {
        val seed=seedPrompt();val h=harness(seed,G0CompletionPromptSession.WindowMode.RETAIN_REQUEST)
        val syncRelease=CompletableDeferred<Unit>();val ackRelease=CompletableDeferred<Unit>()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val closingStarted=CompletableDeferred<Unit>()
        var closing:Deferred<Unit>?=null
        h.runtime.beforeSyncBatchForTest={syncRelease.await()};h.runtime.beforeAcceptForTest={ackRelease.await()}
        try {
            val page=h.open().second;val session=h.prompt(page)
            h.count(page);val first=h.shown(page).first;session.settled()
            val ticket=requireNotNull(session.state.value.window)
            val view=requireNotNull(h.observation(page).actualView);val window=requireNotNull(h.observation(page).actualWindow)
            val trace=G0PhaseTrace(driverTiming=false);session.traceForTest=trace
            h.store.beforeCompletionPromptFinalForTest={entered.complete(Unit);release.await()}
            syncRelease.complete(Unit);withTimeout(20_000) {entered.await()}
            val waiting=h.waiting(page).first
            assertEquals(1,trace.count("tx_begin_outer"));assertEquals(0,trace.count("tx_end_outer"))
            back();until {session.windowCancellations.any {it.identity==ticket.identity && it.accepted}}
            removed(h.observation(page).waitingPixels.frozenMarker(waiting.version));awaitDetached(view,window);assertStale(session,first)
            closing=async(Dispatchers.IO) {closingStarted.complete(Unit);h.close()}
            withTimeout(20_000) {
                closingStarted.await()
                session.state.first {it.phase==G0CompletionPromptSession.Phase.CLOSED}
            }
            yield();delay(50)
            assertFalse(requireNotNull(closing).isCompleted);assertTrue(db.isOpen);assertEquals(0L,h.connectionClosedAt)
            assertEquals(0,trace.count("tx_end_outer"))
            release.complete(Unit);ackRelease.complete(Unit);withTimeout(20_000) {requireNotNull(closing).await()}
            val readEnd=synchronized(trace.spans) {trace.spans.single {it.phase=="tx_end_outer"}.end}
            assertTrue(h.connectionClosedAt>=readEnd);assertFalse(h.connection.busy())
            assertEquals(1,session.events.count {it.phase==G0CompletionPromptSession.Phase.SHOWN})
            assertNull(session.state.value.window);assertFalse(session.cancelWindow(ticket))
            assertEquals(3,pageRowCount("completions"));assertEquals(0,pageRowCount("metric_logs"))
        } finally {release.complete(Unit);ackRelease.complete(Unit);syncRelease.complete(Unit);closing?.join();h.close()}
    }

    @Test fun credentialRevocationRemovesStableWindowAndRejectsBothPersonalAndWindowCapabilities()=runBlocking<Unit> {
        val seed=seedPrompt();val h=harness(seed,G0CompletionPromptSession.WindowMode.RETAIN_REQUEST)
        val syncRelease=CompletableDeferred<Unit>();h.runtime.beforeSyncBatchForTest={syncRelease.await()}
        try {
            val page=h.open().second;h.count(page);val session=h.prompt(page)
            val expected=h.shown(page).first;awaitDialogText()
            val ticket=requireNotNull(session.state.value.window);val observation=h.observation(page)
            val view=requireNotNull(observation.actualView);val window=requireNotNull(observation.actualWindow)
            val oldNodes=dialogNodes();val oldInput=oldNodes.first {it.isEditable};val oldWindowId=oldInput.windowId
            val native=pageNativeState()
            tokens.clearAuthenticationTokens()
            withTimeout(20_000) {session.state.first {it.phase==G0CompletionPromptSession.Phase.CLOSED}}
            removed(observation.pixels.frozenMarker(expected.version))
            awaitDetached(view,window);until {root(oldWindowId)==null}
            assertOldNodeUnavailable(oldInput,AccessibilityNodeInfo.ACTION_SET_TEXT,listOf(metric.name))
            assertNull(session.state.value.window);assertFalse(session.cancelWindow(ticket));assertStale(session,expected)
            assertEquals(native,pageNativeState());assertEquals(3,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
        } finally {syncRelease.complete(Unit);h.close()}
    }

    @Test fun authorityAbaDuringRefreshCannotReviveWindowAndAutomaticDrainWaitsForActualReadEnd()=runBlocking<Unit> {
        val seed=seedPrompt();val h=harness(seed,G0CompletionPromptSession.WindowMode.RETAIN_REQUEST)
        val syncRelease=CompletableDeferred<Unit>();val ackRelease=CompletableDeferred<Unit>()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val reserved=CompletableDeferred<Unit>()
        var mutation:Job?=null
        h.runtime.beforeSyncBatchForTest={syncRelease.await()};h.runtime.beforeAcceptForTest={ackRelease.await()}
        try {
            val page=h.open().second;h.count(page);val session=h.prompt(page)
            val expected=h.shown(page).first;session.settled();val ticket=requireNotNull(session.state.value.window)
            val view=requireNotNull(h.observation(page).actualView);val window=requireNotNull(h.observation(page).actualWindow)
            val trace=G0PhaseTrace(driverTiming=false);session.traceForTest=trace
            h.store.beforeCompletionPromptFinalForTest={entered.complete(Unit);release.await()}
            syncRelease.complete(Unit);withTimeout(20_000) {entered.await()}
            val waiting=h.waiting(page).first;val native=pageNativeState();val prefs=dataStore.data.first()
            authority.onTransitionForTest={reserved.complete(Unit)}
            mutation=launch(Dispatchers.IO) {
                dataStore.edit {it[stringPreferencesKey("sync_device_capability_revision")]="2"}
                dataStore.updateData {prefs}
            }
            withTimeout(20_000) {reserved.await();h.connectionClosing.await()}
            removed(h.observation(page).waitingPixels.frozenMarker(waiting.version))
            awaitDetached(view,window)
            assertEquals(G0CompletionPromptSession.Phase.CLOSED,session.state.value.phase)
            assertNull(session.state.value.window);assertFalse(session.cancelWindow(ticket));assertStale(session,expected)
            assertFalse(requireNotNull(mutation).isCompleted);assertTrue(db.isOpen);assertEquals(0L,h.connectionClosedAt)
            assertEquals(1,trace.count("tx_begin_outer"));assertEquals(0,trace.count("tx_end_outer"))
            release.complete(Unit);ackRelease.complete(Unit);withTimeout(20_000) {requireNotNull(mutation).join()}
            until {h.runtime.drained && !h.connection.busy()}
            val readEnd=synchronized(trace.spans) {trace.spans.single {it.phase=="tx_end_outer"}.end}
            assertTrue(h.connectionClosedAt>=readEnd);assertEquals(prefs,dataStore.data.first());assertEquals(native,pageNativeState())
            assertEquals(1,session.events.count {it.phase==G0CompletionPromptSession.Phase.SHOWN})
        } finally {
            authority.onTransitionForTest={};release.complete(Unit);ackRelease.complete(Unit);syncRelease.complete(Unit)
            mutation?.join();h.close()
        }
    }

    @Test fun leavingAnonymousRefreshRemovesItsWindowWhileTheKnownCountStillSynchronizes()=runBlocking<Unit> {
        val seed=seedPrompt();val h=harness(seed,G0CompletionPromptSession.WindowMode.RETAIN_REQUEST)
        val syncRelease=CompletableDeferred<Unit>();val ackRelease=CompletableDeferred<Unit>()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        h.runtime.beforeSyncBatchForTest={syncRelease.await()};h.runtime.beforeAcceptForTest={ackRelease.await()}
        try {
            val page=h.open().second;val saved=h.count(page).second;val session=h.prompt(page)
            val expected=h.shown(page).first;session.settled();val ticket=requireNotNull(session.state.value.window)
            val view=requireNotNull(h.observation(page).actualView);val window=requireNotNull(h.observation(page).actualWindow)
            session.beforePrepareForTest={entered.complete(Unit);release.await()}
            syncRelease.complete(Unit);withTimeout(20_000) {entered.await()}
            val waiting=h.waiting(page).first;val native=pageNativeState();val prefs=dataStore.data.first()
            h.runtime.leavePage();h.runtime.awaitPageTransition()
            removed(h.observation(page).waitingPixels.frozenMarker(waiting.version))
            awaitDetached(view,window)
            assertFalse(page.live());assertNull(session.state.value.window);assertFalse(session.cancelWindow(ticket));assertStale(session,expected)
            assertEquals(native,pageNativeState());assertEquals(prefs,dataStore.data.first())
            release.complete(Unit);session.settled();ackRelease.complete(Unit);h.synced()
            assertEquals(1,session.events.count {it.phase==G0CompletionPromptSession.Phase.SHOWN})
            val result=householdView(h.store,mapOf(saved.habitUuid to saved.factId))
            val deliveries=h.runtime.workerAttempts.map {requireNotNull(it.delivery)}
            val terminal=pageNativeState()
            h.close();reopenReference();sameHouseholdAsOriginal(result)
            for(delivery in deliveries)assertEquals(NextOperationAcceptance.REPLAYED,sender(seed.http).acceptOperation(delivery))
            originalPrompt(expected.source,expected.metrics)
            assertEquals(terminal,pageNativeState());assertEquals(3,pageRowCount("next_acceptances"));assertEquals(0,pageRowCount("sync_outbox"))
        } finally {release.complete(Unit);ackRelease.complete(Unit);syncRelease.complete(Unit);h.close()}
    }

    @Test fun failedFinalAuthenticationAndUnknownReadEndRetireTheWindowWithoutRetryOrStaleSuccess()=runBlocking<Unit> {
        val seed=seedPrompt();val prefs=dataStore.data.first();val image=storage.snapshotClosedDatabase()
        for(unknown in listOf(false,true)) {
            freshGateForColdOpen();restoreForJourney(image);dataStore.updateData {prefs}
            val h=harness(seed,G0CompletionPromptSession.WindowMode.RETAIN_REQUEST)
            val syncRelease=CompletableDeferred<Unit>();val ackRelease=CompletableDeferred<Unit>()
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            var finalFaultReached=false
            h.runtime.beforeSyncBatchForTest={syncRelease.await()};h.runtime.beforeAcceptForTest={ackRelease.await()}
            try {
                val page=h.open().second;h.count(page);val session=h.prompt(page)
                val expected=h.shown(page).first;session.settled();val ticket=requireNotNull(session.state.value.window)
                val view=requireNotNull(h.observation(page).actualView);val window=requireNotNull(h.observation(page).actualWindow)
                val trace=G0PhaseTrace(driverTiming=false);session.traceForTest=trace
                h.store.beforeCompletionPromptFinalForTest={
                    entered.complete(Unit);release.await()
                    finalFaultReached=true
                    if(unknown)endFaults.failAfterNextEndIn(trace) else failDecrypt=true
                }
                syncRelease.complete(Unit);withTimeout(20_000) {entered.await()}
                val waiting=h.waiting(page).first;val native=pageNativeState()
                release.complete(Unit);session.settled();failDecrypt=false
                assertTrue(session.state.value.phase in setOf(G0CompletionPromptSession.Phase.FAILED,G0CompletionPromptSession.Phase.CLOSED))
                assertEquals("failed",session.attempts.last().outcome)
                assertNotNull(session.attempts.last().errorClass)
                assertTrue(finalFaultReached)
                if(unknown) {
                    assertFalse(endFaults.failAfterNextEnd.get())
                    assertTrue(synchronized(trace.spans) {trace.spans.any {it.phase=="engine_end_postcheck" && !it.ok}})
                }
                removed(h.observation(page).waitingPixels.frozenMarker(waiting.version))
                awaitDetached(view,window)
                assertNull(session.state.value.window);assertFalse(session.cancelWindow(ticket));assertStale(session,expected)
                val attempts=session.attempts.size
                repeat(5) {session.observeCurrentHintForTest()};session.settled()
                assertEquals(attempts,session.attempts.size);assertEquals(1,session.events.count {it.phase==G0CompletionPromptSession.Phase.SHOWN})
                assertEquals(1,trace.count("tx_begin_outer"));assertEquals(1,trace.count("tx_end_outer"))
                assertTrue(trace.count("decrypt_access")>=2);assertEquals(native,pageNativeState());assertEquals(prefs,dataStore.data.first())
            } finally {failDecrypt=false;release.complete(Unit);ackRelease.complete(Unit);syncRelease.complete(Unit);h.close()}
        }
    }

    @Test fun unknownBusinessCommitAndRefusalCannotCreateTheFirstWindow()=runBlocking<Unit> {
        val seed=seedPrompt();val prefs=dataStore.data.first();val image=storage.snapshotClosedDatabase()
        for(unknown in listOf(false,true)) {
            freshGateForColdOpen();restoreForJourney(image);dataStore.updateData {prefs}
            val h=harness(seed,G0CompletionPromptSession.WindowMode.RETAIN_REQUEST);val syncRelease=CompletableDeferred<Unit>()
            h.runtime.beforeSyncBatchForTest={syncRelease.await()}
            try {
                val page=h.open().second;val session=h.prompt(page);val trace=G0PhaseTrace(driverTiming=false);page.trace=trace
                h.store.beforeFinalForTest={
                    if(unknown)endFaults.failAfterNextEndIn(trace) else throw IllegalStateException("G0_STABLE_PROMPT_REFUSED")
                }
                h.seen(page).tap("increment:${habit.uuid}");page.settled()
                assertEquals(if(unknown)"G0_COMMIT_RESULT_LOST" else "G0_STABLE_PROMPT_REFUSED",page.error?.message)
                assertNull(page.saved);assertEquals(0L,page.commandTiming!!.knownCommitAt)
                assertTrue(session.events.none {it.window!=null || it.expected!=null})
                assertTrue(h.observation(page).windows.isEmpty());assertTrue(h.observation(page).draws.isEmpty())
                assertFalse(visibleText(metric.name));assertTrue(h.runtime.workerAttempts.isEmpty())
                assertEquals(if(unknown)3 else 2,pageRowCount("completions"));assertEquals(if(unknown)1 else 0,pageRowCount("sync_outbox"))
                assertEquals(prefs,dataStore.data.first())
            } finally {syncRelease.complete(Unit);h.close()}
        }
    }

    @Test fun unlinkedCardNeverCreatesAWindowAndOriginalUnsupportedActionsStillFailWithoutWrites()=runBlocking<Unit> {
        val seed=seedPrompt();val h=harness(seed,G0CompletionPromptSession.WindowMode.RETAIN_REQUEST)
        val syncRelease=CompletableDeferred<Unit>();h.runtime.beforeSyncBatchForTest={syncRelease.await()}
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        try {
            val page=h.open().second;val session=h.prompt(page);val observation=h.observation(page)
            session.beforePrepareForTest={entered.complete(Unit);release.await()}
            h.count(page,currentHousehold[1].uuid)
            withTimeout(20_000) {entered.await()}
            assertEquals(G0CompletionPromptSession.Phase.PREPARING,session.state.value.phase)
            assertNull(session.state.value.window);assertTrue(observation.windows.isEmpty())
            session.beforePrepareForTest={};release.complete(Unit)
            withTimeout(20_000) {session.state.first {it.phase==G0CompletionPromptSession.Phase.SUPPRESSED}};session.settled()
            assertNull(session.state.value.window);assertTrue(observation.windows.isEmpty())
            householdPixels(h.runtime,page,h.seen(page))
            h.count(page);val expected=h.shown(page).first;awaitDialogText();session.settled()
            val ticket=requireNotNull(session.state.value.window);val native=pageNativeState();val prefs=dataStore.data.first()
            assertFalse(session.capture(habit.uuid).eligible);assertSame(ticket,session.state.value.window)
            val checkbox=dialogNodes().first {it.isCheckable}
            assertTrue(checkbox.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            clickDialogText(app.getString(R.string.post_checkin_skip));callback(session,expected.version,"skip","unsupported")
            typeMetric("12");callback(session,expected.version,"draft","unsupported")
            val after=session.callbacks.size
            clickDialogText(app.getString(R.string.post_checkin_record));callback(session,expected.version,"draft","unsupported",after)
            assertTrue(session.callbacks.none {it.operation=="record"})
            assertEquals("G0_PROMPT_RECORD_UNSUPPORTED",runCatching {session.rejectUnsupported(expected,"record")}.exceptionOrNull()?.message)
            assertSame(expected,session.state.value.expected);assertSame(ticket,session.state.value.window)
            assertEquals(native,pageNativeState());assertEquals(prefs,dataStore.data.first());assertEquals(0,pageRowCount("metric_logs"))
            val beforeClear=session.callbacks.size;typeMetric("");callback(session,expected.version,"draft","accepted",beforeClear)
            closeShown(h,page,expected);assertEquals(native,pageNativeState());assertEquals(prefs,dataStore.data.first())
            syncRelease.complete(Unit);terminal(h,page,4,expected.source,expected.metrics)
        } finally {release.complete(Unit);syncRelease.complete(Unit);h.close()}
    }
}
