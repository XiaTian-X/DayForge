package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real household buttons and the original Dialog's independent window. No marker
 * in the Activity is accepted as proof that the Dialog appeared or was removed. */
@RunWith(AndroidJUnit4::class)
class G0CompletionPromptScreenSafetyTest:G0CompletionPromptScreenFixture() {
    private suspend fun nextShown(h:Harness,page:G0HouseholdCountPage,old:G0CompletionPromptSession.Expected):G0CompletionPromptSession.Expected {
        val session=h.prompt(page)
        withTimeout(20_000) {session.state.first {it.expected!=null && it.expected!==old && session.renderable(it.expected)}}
        return h.shown(page).first.also {assertNotSame(old,it);awaitDialogText()}
    }
    private suspend fun callback(session:G0CompletionPromptSession,version:Long,operation:String,outcome:String,after:Int=0) =
        withTimeout(10_000) {
            while(session.callbacks.drop(after).none {it.version==version && it.operation==operation && it.outcome==outcome})delay(16)
        }
    private fun assertStale(session:G0CompletionPromptSession,expected:G0CompletionPromptSession.Expected) {
        assertFalse(session.close(expected))
        assertEquals("G0_PROMPT_STALE_CALLBACK",runCatching {session.rejectUnsupported(expected,"skip")}.exceptionOrNull()?.message)
    }

    @Test fun cardHomeAxObservationCannotBorrowPixelsInvalidatedByAckAndUsesTheFreshSameCommand()=runBlocking<Unit> {
        val seed=seedPrompt();val prefs=dataStore.data.first();val image=storage.snapshotClosedDatabase()
        for(mode in G0CompletionPromptSession.WindowMode.entries) {
            freshGateForColdOpen();restoreForJourney(image);dataStore.updateData {prefs}
            val h=harness(seed,mode)
            val ackEntered=CompletableDeferred<Unit>();val ackRelease=CompletableDeferred<Unit>()
            val refreshEntered=CompletableDeferred<Unit>();val refreshRelease=CompletableDeferred<Unit>()
            val invalidObserved=CompletableDeferred<Unit>()
            var observing:Deferred<PageTextSeen>?=null
            h.runtime.beforeAcceptForTest={ackEntered.complete(Unit);ackRelease.await()}
            try {
                val page=h.open().second;val saved=h.count(page,currentHousehold[1].uuid).second
                val command=requireNotNull(page.command)
                withTimeout(20_000) {ackEntered.await()}
                val qualified=householdPixels(h.runtime,page,h.seen(page))
                val prior=page.events.single {it.version==qualified.version};val oldView=requireNotNull(prior.view)
                val quantities=currentHousehold.associate {it.uuid to if(it.uuid==saved.habitUuid)1L else 0L}
                assertTrue(oldView.valid());assertSame(command,prior.intent?.command)
                page.beforeRefreshForTest={refreshEntered.complete(Unit);refreshRelease.await()}
                ackRelease.complete(Unit);withTimeout(20_000) {refreshEntered.await()}
                assertFalse(oldView.valid());assertFalse(h.runtime.renderable())
                observing=async {householdResultText(h,page,command,saved,quantities) {reason->
                    if(reason==PageTextRetry.INVALID_VIEW)invalidObserved.complete(Unit)
                }}
                withTimeout(20_000) {invalidObserved.await()}
                assertFalse(requireNotNull(observing).isCompleted);assertSame(prior,page.state.value)
                assertSame(command,page.command);assertSame(saved,page.saved)
                refreshRelease.complete(Unit)
                val result=withTimeout(20_000) {requireNotNull(observing).await()}
                assertTrue(result.invalidViews>0);assertTrue(result.state.version>qualified.version)
                assertSame(command,result.state.intent?.command);assertSame(saved,result.state.commitNotice?.saved)
                assertTrue(requireNotNull(result.state.view).valid());assertTrue(result.observed>=result.started)
                assertEquals(result.passes,result.invalidViews+result.incompleteTexts+result.changedDuringRead+1)
                assertEquals(1,page.commandRecords.size);assertEquals(1,h.runtime.workerAttempts.size)
                assertEquals(3,pageRowCount("completions"))
                terminal(h,page,3,requireNotNull(result.state.view).cards.getValue(saved.habitUuid).followUp,null)
            } finally {
                refreshRelease.complete(Unit);ackRelease.complete(Unit);observing?.cancelAndJoin();h.close()
            }
        }
    }

    @Test fun authorityRevocationFailsPendingCardHomeAxObservationInsteadOfAcceptingItsOldSnapshot()=runBlocking<Unit> {
        val seed=seedPrompt();val prefs=dataStore.data.first();val image=storage.snapshotClosedDatabase()
        for(mode in G0CompletionPromptSession.WindowMode.entries) {
            freshGateForColdOpen();restoreForJourney(image);dataStore.updateData {prefs}
            val h=harness(seed,mode)
            val ackEntered=CompletableDeferred<Unit>();val ackRelease=CompletableDeferred<Unit>()
            val refreshEntered=CompletableDeferred<Unit>();val refreshRelease=CompletableDeferred<Unit>()
            val invalidObserved=CompletableDeferred<Unit>()
            var observing:Deferred<Result<PageTextSeen>>?=null
            h.runtime.beforeAcceptForTest={ackEntered.complete(Unit);ackRelease.await()}
            try {
                val page=h.open().second;val saved=h.count(page,currentHousehold[1].uuid).second
                val command=requireNotNull(page.command)
                withTimeout(20_000) {ackEntered.await()};householdPixels(h.runtime,page,h.seen(page))
                val oldView=requireNotNull(page.state.value.view)
                page.beforeRefreshForTest={refreshEntered.complete(Unit);refreshRelease.await()}
                ackRelease.complete(Unit);withTimeout(20_000) {refreshEntered.await()};h.synced()
                assertFalse(oldView.valid())
                val native=pageNativeState()
                val quantities=currentHousehold.associate {it.uuid to if(it.uuid==saved.habitUuid)1L else 0L}
                observing=async {runCatching {householdResultText(h,page,command,saved,quantities) {reason->
                    if(reason==PageTextRetry.INVALID_VIEW)invalidObserved.complete(Unit)
                }}}
                withTimeout(20_000) {invalidObserved.await()};assertFalse(requireNotNull(observing).isCompleted)
                tokens.clearAuthenticationTokens()
                val rejected=withTimeout(20_000) {requireNotNull(observing).await()}
                assertTrue(rejected.exceptionOrNull() is G0AuthorityOwner.Denied)
                assertFalse(h.runtime.renderable());assertFalse(oldView.valid())
                assertEquals(native,pageNativeState());assertEquals(3,pageRowCount("completions"))
                assertEquals(3,pageRowCount("next_acceptances"));assertEquals(0,pageRowCount("sync_outbox"))
                assertEquals(1,page.commandRecords.size);assertEquals(1,h.runtime.workerAttempts.size)
                assertTrue(h.prompt(page).events.none {it.phase==G0CompletionPromptSession.Phase.SHOWN})
            } finally {
                refreshRelease.complete(Unit);ackRelease.complete(Unit);observing?.cancelAndJoin();h.close()
            }
        }
    }

    @Test fun busyRefusalPreservesTheAcceptedCountsPendingPromptAndCreatesNoSecondWrite()=runBlocking<Unit> {
        val seed=seedPrompt();val h=harness(seed)
        val syncRelease=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        h.runtime.beforeSyncBatchForTest={syncRelease.await()}
        try {
            val page=h.open().second;val session=h.prompt(page)
            h.store.beforeFinalForTest={entered.complete(Unit);release.await()}
            val first=async {h.count(page)}
            withTimeout(20_000) {entered.await()}
            val pending=session.state.value;val command=requireNotNull(page.command)
            assertEquals(G0CompletionPromptSession.Phase.ARMED,pending.phase)
            assertNull(page.saved);assertTrue(session.attempts.isEmpty())
            assertFalse(session.count(currentHousehold[1].uuid,reportBusy=true))
            assertEquals(G0HouseholdCountPage.AdmissionOutcome.BUSY,page.admissions.last().outcome)
            assertSame(command,page.command);assertSame(pending,session.state.value)
            assertNotNull(page.state.value.attemptReceipt)
            release.complete(Unit)
            val saved=withTimeout(20_000) {first.await().second}
            val expected=h.shown(page).first;awaitDialogText();session.settled()
            assertEquals(saved.factUuid,expected.source.claim.completionUuid)
            assertEquals(1,page.commandRecords.size)
            assertEquals(1,session.attempts.count {it.outcome=="shown"})
            assertEquals(3,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
            val frozen=h.observation(page).pixels.frozenMarker(expected.version)
            back();callback(session,expected.version,"close","accepted");removed(frozen)
            syncRelease.complete(Unit);terminal(h,page,3,expected.source,expected.metrics)
        } finally {release.complete(Unit);syncRelease.complete(Unit);h.close()}
    }

    @Test fun actualCountPublishesCardsHomeAndOriginalDialogThenBackClosesWithoutWrites()=runBlocking<Unit> {
        val seed=seedPrompt();val h=harness(seed)
        val syncRelease=CompletableDeferred<Unit>();val prepareEntered=CompletableDeferred<Unit>();val prepareRelease=CompletableDeferred<Unit>()
        h.runtime.beforeSyncBatchForTest={syncRelease.await()}
        try {
            val page=h.open().second;val session=h.prompt(page)
            session.beforePrepareForTest={prepareEntered.complete(Unit);prepareRelease.await()}
            val saved=h.count(page).second
            withTimeout(20_000) {prepareEntered.await()}
            householdPixels(h.runtime,page,h.seen(page))
            requireVisiblePageText(setOf("Household A：1 / 3","Household B：0 / 7","首页：Household A=1，Household B=0"))
            assertNull(session.state.value.expected);assertFalse(visibleText("${metric.name} (${metric.unit})"))
            assertEquals(3,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
            prepareRelease.complete(Unit)
            val expected=h.shown(page).first;awaitDialogText();session.settled()
            assertEquals(saved.factUuid,expected.source.claim.completionUuid)
            assertFalse(expected.source.completed)
            val ineligible=session.capture(habit.uuid)
            assertFalse(ineligible.eligible);assertSame(expected,session.state.value.expected)
            val frozen=h.observation(page).pixels.frozenMarker(expected.version)
            val native=pageNativeState();val prefs=dataStore.data.first()
            back();callback(session,expected.version,"draft","accepted");callback(session,expected.version,"close","accepted")
            removed(frozen)
            assertEquals(G0CompletionPromptSession.Phase.IDLE,session.state.value.phase)
            assertStale(session,expected)
            assertEquals(native,pageNativeState());assertEquals(prefs,dataStore.data.first())
            syncRelease.complete(Unit);terminal(h,page,3,expected.source,expected.metrics)
        } finally {prepareRelease.complete(Unit);syncRelease.complete(Unit);h.close()}
    }

    @Test fun realSendThenAckProduceFreshDialogsWhileRepeatedHintAndOldCloseCannotReplaceThem()=runBlocking<Unit> {
        val seed=seedPrompt();val h=harness(seed)
        val syncRelease=CompletableDeferred<Unit>();val ackEntered=CompletableDeferred<Unit>();val ackRelease=CompletableDeferred<Unit>()
        h.runtime.beforeSyncBatchForTest={syncRelease.await()}
        h.runtime.beforeAcceptForTest={ackEntered.complete(Unit);ackRelease.await()}
        try {
            val page=h.open().second;val session=h.prompt(page);val trace=G0PhaseTrace(driverTiming=false);session.traceForTest=trace
            h.count(page);val first=h.shown(page).first;awaitDialogText();session.settled()
            syncRelease.complete(Unit);withTimeout(20_000) {ackEntered.await()}
            val second=nextShown(h,page,first);session.settled()
            assertFalse(session.renderable(first));assertEquals(first.source.claim,second.source.claim)
            val attempts=session.attempts.size;val reads=trace.count("tx_begin_outer")
            repeat(5) {session.observeCurrentHintForTest()};session.settled()
            assertEquals(attempts,session.attempts.size);assertEquals(reads,trace.count("tx_begin_outer"))
            assertSame(second,session.state.value.expected)
            ackRelease.complete(Unit)
            val third=nextShown(h,page,second);session.settled()
            assertFalse(session.close(first));assertFalse(session.close(second));assertSame(third,session.state.value.expected)
            assertEquals(first.source.claim,third.source.claim)
            assertEquals(3,session.attempts.count {it.outcome=="shown"})
            assertEquals(3,session.attempts.map {it.hint}.distinct().size)
            assertNull(session.error);assertFalse(session.diagnosticsOverflow)
            val frozen=h.observation(page).pixels.frozenMarker(third.version)
            back();callback(session,third.version,"close","accepted");removed(frozen)
            terminal(h,page,3,third.source,third.metrics)
        } finally {ackRelease.complete(Unit);syncRelease.complete(Unit);h.close()}
    }

    @Test fun realCredentialRevocationRemovesDialogPixelsAndRejectsItsOldCallbacks()=runBlocking<Unit> {
        val seed=seedPrompt();val h=harness(seed);val syncRelease=CompletableDeferred<Unit>()
        h.runtime.beforeSyncBatchForTest={syncRelease.await()}
        try {
            val page=h.open().second;h.count(page);val session=h.prompt(page)
            val expected=h.shown(page).first;awaitDialogText()
            val frozen=h.observation(page).pixels.frozenMarker(expected.version);val native=pageNativeState()
            tokens.clearAuthenticationTokens()
            withTimeout(20_000) {session.state.first {it.phase==G0CompletionPromptSession.Phase.CLOSED}}
            removed(frozen);assertFalse(session.renderable(expected))
            val revoked=dataStore.data.first();assertStale(session,expected)
            assertEquals(native,pageNativeState());assertEquals(revoked,dataStore.data.first())
            assertEquals(3,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
        } finally {syncRelease.complete(Unit);h.close()}
    }

    @Test fun leavingThePageClearsTheIndependentWindowWhileKnownCountStillSynchronizes()=runBlocking<Unit> {
        val seed=seedPrompt();val h=harness(seed);val syncRelease=CompletableDeferred<Unit>()
        h.runtime.beforeSyncBatchForTest={syncRelease.await()}
        try {
            val page=h.open().second;val saved=h.count(page).second;val session=h.prompt(page)
            val expected=h.shown(page).first;awaitDialogText()
            val frozen=h.observation(page).pixels.frozenMarker(expected.version)
            val native=pageNativeState();val prefs=dataStore.data.first()
            h.runtime.leavePage();h.runtime.awaitPageTransition()
            removed(frozen);assertFalse(page.live());assertStale(session,expected)
            assertEquals(native,pageNativeState());assertEquals(prefs,dataStore.data.first())
            syncRelease.complete(Unit);h.synced()
            val result=householdView(h.store,mapOf(saved.habitUuid to saved.factId))
            assertEquals(3,pageRowCount("completions"));assertEquals(3,pageRowCount("next_acceptances"));assertEquals(0,pageRowCount("sync_outbox"))
            val terminal=pageNativeState();val deliveries=h.runtime.workerAttempts.map {requireNotNull(it.delivery)}
            h.close();reopenReference();sameHouseholdAsOriginal(result)
            for(delivery in deliveries)assertEquals(NextOperationAcceptance.REPLAYED,sender(seed.http).acceptOperation(delivery))
            originalPrompt(expected.source,expected.metrics);assertEquals(terminal,pageNativeState())
        } finally {syncRelease.complete(Unit);h.close()}
    }

    @Test fun leavingHeldPreparationDiscardsLatePublicationAndDrainWaitsForItsRealTask()=runBlocking<Unit> {
        val seed=seedPrompt();val h=harness(seed)
        val syncRelease=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val closingStarted=CompletableDeferred<Unit>()
        var closing:Deferred<Unit>?=null
        h.runtime.beforeSyncBatchForTest={syncRelease.await()}
        try {
            val page=h.open().second;val session=h.prompt(page)
            session.beforePrepareForTest={entered.complete(Unit);release.await()}
            val saved=h.count(page).second;withTimeout(20_000) {entered.await()}
            val native=pageNativeState();val prefs=dataStore.data.first()
            h.seen.tap("leave");h.runtime.awaitPageTransition()
            closing=async(Dispatchers.IO) {closingStarted.complete(Unit);h.close()}
            withTimeout(20_000) {closingStarted.await()};yield();delay(50)
            assertFalse(requireNotNull(closing).isCompleted);assertTrue(h.connection.busy())
            assertEquals(0L,h.connectionClosedAt)
            release.complete(Unit);withTimeout(20_000) {requireNotNull(closing).await()}
            assertTrue(session.events.none {it.phase==G0CompletionPromptSession.Phase.SHOWN})
            assertTrue(h.observation(page).draws.none {it.allowed})
            assertFalse(visibleText("${metric.name} (${metric.unit})"))
            assertEquals(saved.requestId,page.saved?.requestId)
            assertEquals(native,pageNativeState());assertEquals(prefs,dataStore.data.first())
            assertEquals(3,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"));assertFalse(h.connection.busy())
        } finally {release.complete(Unit);syncRelease.complete(Unit);closing?.join();h.close()}
    }

    @Test fun actualSkipAndRecordInputRefuseUnsupportedWorkWithoutLosingTheDialog()=runBlocking<Unit> {
        val seed=seedPrompt();val h=harness(seed);val syncRelease=CompletableDeferred<Unit>()
        h.runtime.beforeSyncBatchForTest={syncRelease.await()}
        try {
            val page=h.open().second;h.count(page);val session=h.prompt(page)
            val expected=h.shown(page).first;awaitDialogText()
            val native=pageNativeState();val prefs=dataStore.data.first()
            clickDialogText(app.getString(R.string.post_checkin_skip))
            callback(session,expected.version,"skip","unsupported")
            assertSame(expected,h.shown(page).first);assertEquals(native,pageNativeState());assertEquals(prefs,dataStore.data.first())
            typeMetric("12")
            callback(session,expected.version,"draft","unsupported")
            val prior=session.callbacks.size
            clickDialogText(app.getString(R.string.post_checkin_record))
            callback(session,expected.version,"draft","unsupported",prior)
            // Original Record flushes the visible draft first. The nonempty draft
            // is rejected before onRecord; do not claim the latter was UI-executed.
            assertTrue(session.callbacks.none {it.operation=="record"})
            assertSame(expected,h.shown(page).first);assertEquals(native,pageNativeState());assertEquals(prefs,dataStore.data.first())
            val beforeClear=session.callbacks.size;typeMetric("")
            callback(session,expected.version,"draft","accepted",beforeClear)
            val frozen=h.observation(page).pixels.frozenMarker(expected.version)
            back();callback(session,expected.version,"close","accepted");removed(frozen)
            syncRelease.complete(Unit);terminal(h,page,3,expected.source,expected.metrics)
        } finally {syncRelease.complete(Unit);h.close()}
    }

    @Test fun unknownCommitAndActualBusinessRefusalNeverBorrowAPromptSuccess()=runBlocking<Unit> {
        val seed=seedPrompt();val prefs=dataStore.data.first();val image=storage.snapshotClosedDatabase()
        for(unknown in listOf(false,true)) {
            freshGateForColdOpen();restoreForJourney(image);dataStore.updateData {prefs}
            val h=harness(seed);val syncRelease=CompletableDeferred<Unit>();h.runtime.beforeSyncBatchForTest={syncRelease.await()}
            try {
                val page=h.open().second;val session=h.prompt(page);val trace=G0PhaseTrace(driverTiming=false);page.trace=trace
                h.store.beforeFinalForTest={
                    if(unknown)endFaults.failAfterNextEndIn(trace)
                    else throw IllegalStateException("G0_PROMPT_TEST_BUSINESS_REFUSED")
                }
                h.seen(page).tap("increment:${habit.uuid}");page.settled()
                assertEquals(if(unknown)"G0_COMMIT_RESULT_LOST" else "G0_PROMPT_TEST_BUSINESS_REFUSED",page.error?.message)
                assertNull(page.saved);assertEquals(0L,page.commandTiming!!.knownCommitAt)
                assertTrue(session.events.none {it.phase==G0CompletionPromptSession.Phase.SHOWN})
                assertTrue(h.observation(page).draws.none {it.allowed});assertFalse(visibleText("${metric.name} (${metric.unit})"))
                assertEquals(if(unknown)3 else 2,pageRowCount("completions"))
                assertEquals(if(unknown)1 else 0,pageRowCount("sync_outbox"));assertEquals(2,pageRowCount("next_acceptances"))
                assertNotNull(page.command);assertTrue(h.runtime.workerAttempts.isEmpty())
            } finally {syncRelease.complete(Unit);h.close()}
        }
    }

    @Test fun automaticAuthorityRetirementDrainsTheRealPromptReadBeforeClosingItsConnection()=runBlocking<Unit> {
        val seed=seedPrompt();val h=harness(seed)
        val syncRelease=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        var mutation:Deferred<Unit>?=null
        h.runtime.beforeSyncBatchForTest={syncRelease.await()}
        try {
            val user=requireNotNull(tokens.localCoreWriteAccess()).session.authentication.userId
            val page=h.open().second;val session=h.prompt(page);val trace=G0PhaseTrace(driverTiming=false);session.traceForTest=trace
            h.store.beforeCompletionPromptFinalForTest={entered.complete(Unit);release.await()}
            h.count(page);withTimeout(20_000) {entered.await()}
            val native=pageNativeState()
            fun spans()=synchronized(trace.spans) {trace.spans.toList()}
            assertEquals(1,spans().count {it.phase=="tx_begin_outer"});assertEquals(0,spans().count {it.phase=="tx_end_outer"})
            mutation=async(Dispatchers.IO) {tokens.saveTokens("prompt-new-access","prompt-new-refresh",userId=user,isAdmin=false)}
            withTimeout(20_000) {h.connectionClosing.await()}
            assertTrue(h.connection.busy());assertEquals(0L,h.connectionClosedAt);assertTrue(db.isOpen)
            assertFalse(requireNotNull(mutation).isCompleted)
            assertEquals(0,spans().count {it.phase=="tx_end_outer"})
            release.complete(Unit);withTimeout(20_000) {requireNotNull(mutation).await()}
            withTimeout(20_000) {while(!h.runtime.drained || h.connection.busy())delay(16)}
            val actualEnd=spans().single {it.phase=="tx_end_outer"}
            assertTrue(h.connectionClosedAt>=actualEnd.end);assertTrue(h.connectionClosedAt>0L)
            assertTrue(session.events.none {it.phase==G0CompletionPromptSession.Phase.SHOWN})
            assertTrue(h.observation(page).draws.none {it.allowed});assertFalse(visibleText("${metric.name} (${metric.unit})"))
            assertEquals(native,pageNativeState());assertEquals(3,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
            // Above assertions depend on automatic retirement only. Explicit cleanup
            // is intentionally deferred to finally and cannot make that proof pass.
        } finally {release.complete(Unit);syncRelease.complete(Unit);mutation?.join();h.close()}
    }
}
