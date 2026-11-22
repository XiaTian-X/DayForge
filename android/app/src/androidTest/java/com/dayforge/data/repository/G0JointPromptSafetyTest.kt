package com.dayforge.data.repository

import android.graphics.Bitmap
import android.view.View
import android.view.Window
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.ui.components.MetricInputState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Functional integration of explicit command feedback and the selected RECREATE
 * prompt. Barriers establish safety interleavings, not operation latency samples. */
@RunWith(AndroidJUnit4::class)
class G0JointPromptSafetyTest:G0CompletionPromptScreenFixture() {
    private suspend fun semantic(h:Harness,page:G0HouseholdCountPage,notice:Boolean,attempt:Boolean):G0HouseholdCountPage.State {
        val frame=G0ScreenObservation.awaitTogether {
            val outer=h.runtime.state.value;val state=page.state.value;val seen=h.commandFeedback(page)
            val targets=listOfNotNull(if(notice)seen.noticeTarget(h.runtime,outer,state) else null,
                if(attempt)seen.attemptTarget(h.runtime,outer,state) else null)
            if(targets.size!=(if(notice)1 else 0)+(if(attempt)1 else 0))null else targets
        }
        return page.events.single {it.version==frame.version}
    }
    private suspend fun hidden(frozen:(Bitmap)->Boolean)=withTimeout(15_000) {
        while(true) {
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if(bitmap!=null) {
                val shown=try {frozen(bitmap)} finally {bitmap.recycle()}
                if(!shown)return@withTimeout
            }
            delay(16)
        }
    }
    private suspend fun busyTap(h:Harness,page:G0HouseholdCountPage,id:String):G0HouseholdCountPage.Admission {
        val before=page.admissions.size;val clicks=h.seen(page).clicks.size
        h.seen(page).tap("increment:$id")
        val result=withTimeout(5_000) {
            while(page.admissions.size==before)delay(1)
            page.admissions.drop(before).single()
        }
        assertEquals(clicks+1,h.seen(page).clicks.size)
        assertEquals(G0HouseholdCountPage.AdmissionOutcome.BUSY,result.outcome)
        assertEquals(id,result.requestedHabitUuid);assertNotNull(result.attemptSequence)
        return result
    }
    private suspend fun closeShown(h:Harness,page:G0HouseholdCountPage):G0CompletionPromptSession.Expected {
        val session=h.prompt(page);val expected=h.shown(page).first;awaitDialogText();session.settled()
        val frozen=h.observation(page).pixels.frozenMarker(expected.version)
        val native=pageNativeState();val prefs=dataStore.data.first();val at=back()
        withTimeout(10_000) {while(session.callbacks.none {
            it.version==expected.version && it.operation=="close" && it.outcome=="accepted" && it.at>=at
        })delay(16)}
        removed(frozen)
        assertEquals(G0CompletionPromptSession.Phase.IDLE,session.state.value.phase)
        assertFalse(session.close(expected));assertEquals(native,pageNativeState());assertEquals(prefs,dataStore.data.first())
        return expected
    }
    private fun assertSingleKnown(page:G0HouseholdCountPage,command:G0HouseholdCountStore.Command) {
        assertEquals(1,page.commandRecords.size);assertSame(command,page.command)
        assertEquals(1,page.admissions.count {it.outcome==G0HouseholdCountPage.AdmissionOutcome.ACCEPTED})
        assertEquals(command.requestId,page.saved?.requestId)
        assertEquals(command.factUuid,page.saved?.factUuid)
    }
    private fun assertStaleActions(session:G0CompletionPromptSession,expected:G0CompletionPromptSession.Expected) {
        assertFalse(session.close(expected))
        for(operation in listOf("record","skip"))assertEquals("G0_PROMPT_STALE_CALLBACK",
            runCatching {session.rejectUnsupported(expected,operation)}.exceptionOrNull()?.message)
        val empty=expected.metrics.map {MetricInputState(it.metricId,it.metricName,it.unit,it.decimalPlaces)}
        assertEquals("G0_PROMPT_STALE_CALLBACK",
            runCatching {session.validateEmptyDraft(expected,empty)}.exceptionOrNull()?.message)
    }
    private suspend fun removedWindow(view:View,window:Window,frozen:(Bitmap)->Boolean) {
        removed(frozen)
        val decor=window.decorView
        withTimeout(10_000) {
            var detached=false
            while(!detached) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    detached=!view.isAttachedToWindow && !decor.isAttachedToWindow
                }
                if(!detached)delay(16)
            }
        }
    }

    @Test fun realPrecommitBusyClicksPreserveR1AndNeverPublishAnEarlySaveOrSecondCommand()=runBlocking<Unit> {
        val seed=seedPrompt();val h=harness(seed,commandFeedback=true)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val syncRelease=CompletableDeferred<Unit>()
        h.runtime.beforeSyncBatchForTest={syncRelease.await()}
        try {
            val page=h.open().second;val session=h.prompt(page);val before=pageNativeState()
            assertEquals(G0CompletionPromptSession.WindowMode.RECREATE,session.windowMode)
            h.store.beforeFinalForTest={entered.complete(Unit);release.await()}
            h.seen(page).tap("increment:${habit.uuid}");withTimeout(20_000) {entered.await()}
            h.seen(page).await(page.state.value.version)
            val pending=session.state.value;val command=requireNotNull(page.command)
            assertEquals(G0CompletionPromptSession.Phase.ARMED,pending.phase)
            val a=busyTap(h,page,habit.uuid);val b=busyTap(h,page,currentHousehold[1].uuid)
            val refused=semantic(h,page,notice=false,attempt=true)
            assertSame(pending,session.state.value);assertSame(command,refused.attemptReceipt?.command)
            assertEquals(listOf(1L,2L),listOf(a.attemptSequence,b.attemptSequence))
            assertEquals(2L,refused.attemptReceipt?.count);assertNull(refused.commitNotice)
            assertNull(page.saved);assertEquals(0L,page.commandTiming!!.knownCommitAt)
            assertTrue(session.attempts.isEmpty());assertTrue(h.observation(page).draws.isEmpty())
            requireVisiblePageText(setOf("刚才2次点击未记录，请稍后再试"))
            assertEquals(before,pageNativeState())
            release.complete(Unit);householdPagePhase(page,G0HouseholdCountPage.Phase.RESULT);page.settled()
            val expected=closeShown(h,page)
            assertEquals(command.factUuid,expected.source.claim.completionUuid)
            assertEquals(1,session.attempts.count {it.outcome=="shown"});assertSingleKnown(page,command)
            assertEquals(3,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
            syncRelease.complete(Unit);terminal(h,page,3,expected.source,expected.metrics)
        } finally {release.complete(Unit);syncRelease.complete(Unit);h.close()}
    }

    @Test fun realSavedBusyClicksExplainRefusalsSeparatelyAndKeepTheOriginalPromptIntent()=runBlocking<Unit> {
        val seed=seedPrompt();val h=harness(seed,commandFeedback=true)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val syncRelease=CompletableDeferred<Unit>()
        h.runtime.beforeSyncBatchForTest={syncRelease.await()}
        try {
            val page=h.open().second;val session=h.prompt(page)
            page.afterCommitForTest={entered.complete(Unit);release.await()}
            h.seen(page).tap("increment:${habit.uuid}");withTimeout(20_000) {entered.await()}
            val initial=semantic(h,page,notice=true,attempt=false)
            val notice=requireNotNull(initial.commitNotice);val pending=session.state.value
            assertEquals(G0HouseholdCountPage.Phase.SAVED,initial.phase)
            assertEquals(G0CompletionPromptSession.Phase.ARMED,pending.phase)
            assertSame(page.saved,notice.saved);assertTrue(page.commandTiming!!.knownCommitAt>0L)
            busyTap(h,page,currentHousehold[1].uuid);busyTap(h,page,habit.uuid)
            val visible=semantic(h,page,notice=true,attempt=true)
            assertSame(notice,visible.commitNotice);assertSame(pending,session.state.value)
            assertEquals(2L,visible.attemptReceipt?.count)
            requireVisiblePageText(setOf("上一笔已保存在此设备，正在更新","刚才2次点击未记录，请稍后再试"))
            assertTrue(session.attempts.isEmpty());assertFalse(visibleText("${metric.name} (${metric.unit})"))
            assertEquals(3,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
            release.complete(Unit);householdPagePhase(page,G0HouseholdCountPage.Phase.RESULT);page.settled()
            val expected=closeShown(h,page)
            assertEquals(notice.saved.factUuid,expected.source.claim.completionUuid);assertSingleKnown(page,notice.command)
            assertEquals(1,session.attempts.count {it.outcome=="shown"})
            syncRelease.complete(Unit);terminal(h,page,3,expected.source,expected.metrics)
        } finally {release.complete(Unit);syncRelease.complete(Unit);h.close()}
    }

    @Test fun newlyAcceptedBRetiresOldNoticeReceiptAndPendingAWithoutLatePromptOrAutomaticWrite()=runBlocking<Unit> {
        val seed=seedPrompt();val h=harness(seed,commandFeedback=true)
        val savedEntered=CompletableDeferred<Unit>();val savedRelease=CompletableDeferred<Unit>()
        val prepareEntered=CompletableDeferred<Unit>();val prepareRelease=CompletableDeferred<Unit>()
        val secondEntered=CompletableDeferred<Unit>();val secondRelease=CompletableDeferred<Unit>()
        val syncRelease=CompletableDeferred<Unit>();h.runtime.beforeSyncBatchForTest={syncRelease.await()}
        try {
            val page=h.open().second;val session=h.prompt(page)
            session.beforePrepareForTest={prepareEntered.complete(Unit);prepareRelease.await()}
            page.afterCommitForTest={savedEntered.complete(Unit);savedRelease.await()}
            h.seen(page).tap("increment:${habit.uuid}");withTimeout(20_000) {savedEntered.await()}
            semantic(h,page,notice=true,attempt=false);busyTap(h,page,currentHousehold[1].uuid)
            savedRelease.complete(Unit);withTimeout(20_000) {prepareEntered.await()};page.settled()
            val old=semantic(h,page,notice=true,attempt=true);val prior=session.state.value
            val outer=h.runtime.state.value;val oldNotice=requireNotNull(h.commandFeedback(page).noticeTarget(h.runtime,outer,old))
            val oldReceipt=requireNotNull(h.commandFeedback(page).attemptTarget(h.runtime,outer,old))
            val a=h.commandFeedback(page).noticePixels.frozenMarker(old.version)
            val b=h.commandFeedback(page).attemptPixels.frozenMarker(old.version)
            assertEquals(G0CompletionPromptSession.Phase.PREPARING,prior.phase)
            h.store.afterPrepareForTest={secondEntered.complete(Unit);secondRelease.await()}
            h.seen(page).tap("increment:${currentHousehold[1].uuid}");withTimeout(20_000) {secondEntered.await()}
            val second=requireNotNull(page.command)
            assertNotSame(old.intent?.command,second);assertNull(page.state.value.commitNotice);assertNull(page.state.value.attemptReceipt)
            assertFalse(oldNotice.valid());assertFalse(oldReceipt.valid());hidden {a(it)||b(it)}
            assertEquals(G0CompletionPromptSession.Phase.ARMED,session.state.value.phase)
            assertTrue(session.state.value.version>prior.version)
            secondRelease.complete(Unit);householdPagePhase(page,G0HouseholdCountPage.Phase.RESULT);page.settled()
            prepareRelease.complete(Unit);session.settled()
            withTimeout(20_000) {session.state.first {it.phase==G0CompletionPromptSession.Phase.SUPPRESSED}}
            assertTrue(session.events.none {it.phase==G0CompletionPromptSession.Phase.SHOWN})
            assertTrue(h.observation(page).draws.none {it.allowed})
            assertEquals(listOf("discarded","suppressed"),session.attempts.map {it.outcome})
            assertEquals(2,page.commandRecords.size);assertEquals(2,page.admissions.count {it.outcome==G0HouseholdCountPage.AdmissionOutcome.ACCEPTED})
            assertEquals(1,page.admissions.count {it.outcome==G0HouseholdCountPage.AdmissionOutcome.BUSY})
            val current=semantic(h,page,notice=true,attempt=false)
            assertSame(second,current.commitNotice?.command);assertNull(current.attemptReceipt)
            assertEquals(4,pageRowCount("completions"));assertEquals(2,pageRowCount("sync_outbox"))
            syncRelease.complete(Unit)
            terminal(h,page,4,requireNotNull(current.view).cards.getValue(second.habitUuid).followUp,null)
        } finally {
            savedRelease.complete(Unit);prepareRelease.complete(Unit);secondRelease.complete(Unit);syncRelease.complete(Unit);h.close()
        }
    }

    @Test fun finalFreshAuthenticationAndUnknownCommitCannotBorrowPriorSuccessForNewA()=runBlocking<Unit> {
        val seed=seedPrompt();val prefs=dataStore.data.first();val image=storage.snapshotClosedDatabase()
        for(unknown in listOf(false,true)) {
            freshGateForColdOpen();restoreForJourney(image);dataStore.updateData {prefs}
            val h=harness(seed,commandFeedback=true)
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            try {
                val page=h.open().second;val session=h.prompt(page)
                h.count(page,currentHousehold[1].uuid);h.synced()
                withTimeout(20_000) {session.state.first {it.phase==G0CompletionPromptSession.Phase.SUPPRESSED}}
                householdPixels(h.runtime,page,h.seen(page))
                val old=semantic(h,page,notice=true,attempt=false);val notice=requireNotNull(old.commitNotice)
                val target=requireNotNull(h.commandFeedback(page).noticeTarget(h.runtime,h.runtime.state.value,old))
                val frozen=h.commandFeedback(page).noticePixels.frozenMarker(old.version)
                val before=pageNativeState();val trace=G0PhaseTrace(driverTiming=false);page.trace=trace
                var finalDecryptCount=-1
                h.store.afterPrepareForTest={entered.complete(Unit);release.await()}
                h.store.beforeFinalForTest={
                    finalDecryptCount=decryptCount.get()
                    if(unknown)endFaults.failAfterNextEndIn(trace) else failDecrypt=true
                }
                h.seen(page).tap("increment:${habit.uuid}");withTimeout(20_000) {entered.await()}
                val second=requireNotNull(page.command)
                assertNotSame(notice.command,second);assertNull(page.state.value.commitNotice);assertNull(page.state.value.attemptReceipt)
                assertFalse(target.valid());hidden(frozen)
                release.complete(Unit);page.settled();assertTrue(h.runtime.shutdown())
                assertTrue(finalDecryptCount>=0);assertTrue(decryptCount.get()>finalDecryptCount)
                assertEquals(if(unknown)"G0_COMMIT_RESULT_LOST" else "G0_COUNT_AUTH_CHANGED",page.error?.message)
                assertEquals(0L,page.commandTiming!!.knownCommitAt);assertSame(notice.saved,page.saved)
                assertNull(page.state.value.commitNotice);assertNull(page.state.value.attemptReceipt)
                assertTrue(page.events.filter {it.intent?.command===second}.all {it.commitNotice==null})
                assertTrue(session.events.none {it.phase==G0CompletionPromptSession.Phase.SHOWN})
                assertTrue(h.observation(page).draws.none {it.allowed})
                assertFalse(visibleText("${metric.name} (${metric.unit})"));assertFalse(h.runtime.openPage())
                assertEquals(if(unknown)4 else 3,pageRowCount("completions"))
                assertEquals(if(unknown)1 else 0,pageRowCount("sync_outbox"));assertEquals(3,pageRowCount("next_acceptances"))
                assertEquals(1,h.runtime.workerAttempts.size);assertEquals(2,page.commandRecords.size)
                if(!unknown)assertEquals(before,pageNativeState())
            } finally {failDecrypt=false;release.complete(Unit);h.close()}
        }
    }

    @Test fun realSendAndAckRetireOpenPromptActionsWhileAnonymousNoticeSurvivesUntilFreshContentAndBack()=runBlocking<Unit> {
        val seed=seedPrompt();val h=harness(seed,commandFeedback=true)
        val syncRelease=CompletableDeferred<Unit>();val ackEntered=CompletableDeferred<Unit>();val ackRelease=CompletableDeferred<Unit>()
        val sendRefreshEntered=CompletableDeferred<Unit>();val sendRefreshRelease=CompletableDeferred<Unit>()
        val sendPrepareEntered=CompletableDeferred<Unit>();val sendPrepareRelease=CompletableDeferred<Unit>()
        val ackRefreshEntered=CompletableDeferred<Unit>();val ackRefreshRelease=CompletableDeferred<Unit>()
        val ackPrepareEntered=CompletableDeferred<Unit>();val ackPrepareRelease=CompletableDeferred<Unit>()
        h.runtime.beforeSyncBatchForTest={syncRelease.await()}
        h.runtime.beforeAcceptForTest={ackEntered.complete(Unit);ackRelease.await()}
        try {
            val page=h.open().second;val session=h.prompt(page);val observation=h.observation(page)
            val trace=G0PhaseTrace(driverTiming=false);session.traceForTest=trace
            val saved=h.count(page).second
            val first=h.shown(page).first;awaitDialogText();session.settled()
            val command=requireNotNull(page.command);val old=page.state.value;val outer=h.runtime.state.value
            val notice=requireNotNull(h.commandFeedback(page).noticeTarget(h.runtime,outer,old))
            val privatePixels=h.seen(page).frozenMarker(old.version)
            val firstView=requireNotNull(observation.actualView);val firstWindow=requireNotNull(observation.actualWindow)
            val firstPixels=observation.pixels.frozenMarker(first.version)
            assertSame(saved,old.commitNotice?.saved);assertTrue(notice.valid())
            assertEquals(saved.factUuid,first.source.claim.completionUuid)
            page.beforeRefreshForTest={sendRefreshEntered.complete(Unit);sendRefreshRelease.await()}
            session.beforePrepareForTest={sendPrepareEntered.complete(Unit);sendPrepareRelease.await()}
            syncRelease.complete(Unit)
            withTimeout(20_000) {ackEntered.await();sendRefreshEntered.await();sendPrepareEntered.await()}
            assertFalse(requireNotNull(old.view).valid());assertFalse(h.runtime.renderable());assertTrue(notice.valid())
            assertEquals(G0CompletionPromptSession.Phase.PREPARING,session.state.value.phase)
            assertNull(session.state.value.expected);assertStaleActions(session,first)
            removedWindow(firstView,firstWindow,firstPixels)
            hidden(privatePixels);val visible=semantic(h,page,notice=true,attempt=false)
            assertSame(old.commitNotice,visible.commitNotice)
            requireVisiblePageText(setOf("上一笔已保存在此设备"))
            assertFalse(visibleText("${metric.name} (${metric.unit})"))
            sendRefreshRelease.complete(Unit);householdPixels(h.runtime,page,h.seen(page))
            val afterSend=semantic(h,page,notice=true,attempt=false)
            assertFalse(notice.valid());assertSame(old.commitNotice,afterSend.commitNotice)
            session.beforePrepareForTest={};sendPrepareRelease.complete(Unit)
            val second=h.shown(page).first;awaitDialogText();session.settled()
            assertNotSame(first,second);assertSame(first.request,second.request)
            assertEquals(first.source.claim,second.source.claim);assertStaleActions(session,first)

            val beforeAck=page.state.value
            val ackNotice=requireNotNull(h.commandFeedback(page).noticeTarget(h.runtime,h.runtime.state.value,beforeAck))
            val secondView=requireNotNull(observation.actualView);val secondWindow=requireNotNull(observation.actualWindow)
            val secondPixels=observation.pixels.frozenMarker(second.version)
            val beforeAckPixels=h.seen(page).frozenMarker(beforeAck.version)
            page.beforeRefreshForTest={ackRefreshEntered.complete(Unit);ackRefreshRelease.await()}
            session.beforePrepareForTest={ackPrepareEntered.complete(Unit);ackPrepareRelease.await()}
            ackRelease.complete(Unit)
            withTimeout(20_000) {ackRefreshEntered.await();ackPrepareEntered.await()}
            assertFalse(requireNotNull(beforeAck.view).valid());assertFalse(h.runtime.renderable());assertTrue(ackNotice.valid())
            assertEquals(G0CompletionPromptSession.Phase.PREPARING,session.state.value.phase)
            assertNull(session.state.value.expected);assertStaleActions(session,first);assertStaleActions(session,second)
            removedWindow(secondView,secondWindow,secondPixels);hidden(beforeAckPixels)
            val duringAck=semantic(h,page,notice=true,attempt=false)
            assertSame(old.commitNotice,duringAck.commitNotice);assertNull(duringAck.attemptReceipt)
            requireVisiblePageText(setOf("上一笔已保存在此设备"))
            assertFalse(visibleText("${metric.name} (${metric.unit})"))
            ackRefreshRelease.complete(Unit);householdPixels(h.runtime,page,h.seen(page))
            val afterAck=semantic(h,page,notice=true,attempt=false)
            assertFalse(ackNotice.valid());assertSame(old.commitNotice,afterAck.commitNotice)
            session.beforePrepareForTest={};ackPrepareRelease.complete(Unit)
            val third=h.shown(page).first;awaitDialogText();session.settled()
            assertNotSame(second,third);assertSame(first.request,third.request);assertEquals(first.source.claim,third.source.claim)
            assertStaleActions(session,first);assertStaleActions(session,second)
            assertSame(third,session.state.value.expected)
            assertEquals(3,session.attempts.count {it.outcome=="shown"})
            assertEquals(3,trace.count("tx_begin_outer"));assertEquals(3,trace.count("tx_end_outer"))
            assertEquals(12,trace.count("decrypt_access"))
            assertSame(third,closeShown(h,page));assertStaleActions(session,third)
            val finalNotice=semantic(h,page,notice=true,attempt=false)
            assertSame(old.commitNotice,finalNotice.commitNotice);assertSingleKnown(page,command)
            terminal(h,page,3,third.source,third.metrics)
        } finally {
            sendRefreshRelease.complete(Unit);sendPrepareRelease.complete(Unit)
            ackRefreshRelease.complete(Unit);ackPrepareRelease.complete(Unit)
            ackRelease.complete(Unit);syncRelease.complete(Unit);h.close()
        }
    }

    @Test fun leavingRetiresFeedbackAndDrainsTheActualPromptReadBeforeOwnerClose()=runBlocking<Unit> {
        val seed=seedPrompt();val h=harness(seed,commandFeedback=true)
        val syncRelease=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        var closing:Deferred<Boolean>?=null
        h.runtime.beforeSyncBatchForTest={syncRelease.await()}
        try {
            val page=h.open().second;val session=h.prompt(page);val trace=G0PhaseTrace(driverTiming=false);session.traceForTest=trace
            h.store.beforeCompletionPromptFinalForTest={entered.complete(Unit);release.await()}
            h.count(page);withTimeout(20_000) {entered.await()}
            val current=semantic(h,page,notice=true,attempt=false)
            val target=requireNotNull(h.commandFeedback(page).noticeTarget(h.runtime,h.runtime.state.value,current))
            val frozen=h.commandFeedback(page).noticePixels.frozenMarker(current.version)
            val native=pageNativeState();val prefs=dataStore.data.first()
            fun spans()=synchronized(trace.spans) {trace.spans.toList()}
            assertEquals(1,spans().count {it.phase=="tx_begin_outer"});assertEquals(0,spans().count {it.phase=="tx_end_outer"})
            h.seen.tap("leave");assertFalse(target.valid());hidden(frozen)
            closing=async(Dispatchers.IO) {h.runtime.shutdown()}
            withTimeout(20_000) {h.connectionClosing.await()}
            assertFalse(requireNotNull(closing).isCompleted);assertTrue(h.connection.busy())
            assertEquals(0L,h.connectionClosedAt);assertTrue(db.isOpen)
            assertEquals(0,spans().count {it.phase=="tx_end_outer"})
            release.complete(Unit);assertTrue(withTimeout(20_000) {requireNotNull(closing).await()})
            val end=spans().single {it.phase=="tx_end_outer"}
            assertTrue(h.connectionClosedAt>=end.end);assertTrue(h.runtime.drained);assertFalse(h.connection.busy())
            assertTrue(session.events.none {it.phase==G0CompletionPromptSession.Phase.SHOWN})
            assertTrue(h.observation(page).draws.none {it.allowed})
            assertNull(page.state.value.commitNotice);assertNull(page.state.value.attemptReceipt)
            assertEquals(native,pageNativeState());assertEquals(prefs,dataStore.data.first())
            assertEquals(3,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
            reopenReference();sameHouseholdAsOriginal(requireNotNull(current.view));assertEquals(native,pageNativeState())
        } finally {release.complete(Unit);syncRelease.complete(Unit);closing?.join();h.close()}
    }

    @Test fun authorityReservationRetiresFeedbackBeforePersistenceAndWaitsForActualPromptRead()=runBlocking<Unit> {
        val seed=seedPrompt();val h=harness(seed,commandFeedback=true)
        val syncRelease=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        var mutation:Deferred<Unit>?=null
        h.runtime.beforeSyncBatchForTest={syncRelease.await()}
        try {
            val user=requireNotNull(tokens.localCoreWriteAccess()).session.authentication.userId
            val page=h.open().second;val session=h.prompt(page);val trace=G0PhaseTrace(driverTiming=false);session.traceForTest=trace
            h.store.beforeCompletionPromptFinalForTest={entered.complete(Unit);release.await()}
            h.count(page);withTimeout(20_000) {entered.await()}
            val current=semantic(h,page,notice=true,attempt=false)
            val target=requireNotNull(h.commandFeedback(page).noticeTarget(h.runtime,h.runtime.state.value,current))
            val frozen=h.commandFeedback(page).noticePixels.frozenMarker(current.version);val native=pageNativeState()
            fun spans()=synchronized(trace.spans) {trace.spans.toList()}
            assertEquals(1,spans().count {it.phase=="tx_begin_outer"});assertEquals(0,spans().count {it.phase=="tx_end_outer"})
            mutation=async(Dispatchers.IO) {tokens.saveTokens("joint-new-access","joint-new-refresh",userId=user,isAdmin=false)}
            withTimeout(20_000) {h.connectionClosing.await()}
            assertFalse(target.valid());hidden(frozen);assertNull(page.state.value.commitNotice)
            assertFalse(requireNotNull(mutation).isCompleted);assertTrue(h.connection.busy())
            assertEquals(0L,h.connectionClosedAt);assertTrue(db.isOpen)
            assertEquals(0,spans().count {it.phase=="tx_end_outer"})
            release.complete(Unit);withTimeout(20_000) {requireNotNull(mutation).await()}
            withTimeout(20_000) {while(!h.runtime.drained || h.connection.busy())delay(16)}
            assertTrue(h.connectionClosedAt>=spans().single {it.phase=="tx_end_outer"}.end)
            assertTrue(session.events.none {it.phase==G0CompletionPromptSession.Phase.SHOWN})
            assertTrue(h.observation(page).draws.none {it.allowed})
            assertNull(page.state.value.attemptReceipt);assertEquals(native,pageNativeState())
            assertEquals(3,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
        } finally {release.complete(Unit);syncRelease.complete(Unit);mutation?.join();h.close()}
    }
}
