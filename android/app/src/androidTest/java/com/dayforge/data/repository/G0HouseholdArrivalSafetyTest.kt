package com.dayforge.data.repository

import androidx.activity.compose.setContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.api.NextSyncHttp
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostics preserve the single slot: a handler refusal and a disabled touch
 * without a handler are different events. Draw records alone are not pixel evidence. */
@RunWith(AndroidJUnit4::class)
class G0HouseholdArrivalSafetyTest:G0HouseholdCountPageFixture() {
    private inner class Harness(http:NextSyncHttp) {
        val job=SupervisorJob();val control=CoroutineScope(job+Dispatchers.IO)
        lateinit var store:G0HouseholdCountStore
        val runtime=G0HouseholdCountRuntime(control,G0CountConnectionOwner {db.close()},authority,tokens,
            {domainGate.changeSequence},http) {coldHousehold(http).also {store=it}}
        val outer=G0ScreenObservation()
        private val screens=ConcurrentHashMap<G0HouseholdCountPage,G0ScreenObservation>()
        private val busy=ConcurrentHashMap<G0HouseholdCountPage,G0HouseholdFeedbackObservation>()
        private val arrivals=ConcurrentHashMap<G0HouseholdCountPage,G0HouseholdArrivalObservation>()
        fun screen(page:G0HouseholdCountPage)=screens.computeIfAbsent(page) {G0ScreenObservation()}
        fun feedback(page:G0HouseholdCountPage)=busy.computeIfAbsent(page) {G0HouseholdFeedbackObservation()}
        fun arrival(page:G0HouseholdCountPage)=arrivals.computeIfAbsent(page) {G0HouseholdArrivalObservation()}
        suspend fun open():G0HouseholdCountPage {
            activity.scenario.onActivity {it.setContent {G0HouseholdCountRuntimeScreen(runtime,outer,::screen,::feedback,::arrival)}}
            outer.await(runtime.state.value.version);outer.tap("open")
            return householdPage(runtime).also {householdPixels(runtime,it,screen(it))}
        }
        suspend fun close()=withContext(NonCancellable) {runtime.shutdown();job.cancelAndJoin()}
    }
    private suspend fun withHarness(http:NextSyncHttp,body:suspend (Harness)->Unit) {
        val h=Harness(http);try {body(h)} finally {h.close()}
    }
    private suspend fun completed(page:G0HouseholdCountPage,id:String):G0HouseholdCountPage.CommandRecord = withTimeout(20_000) {
        while((page.commandRecords[id]?.completedAt ?: 0L)==0L)delay(1)
        requireNotNull(page.commandRecords[id])
    }
    private fun assertRetainedRecord(before:G0HouseholdCountPage.CommandRecord,after:G0HouseholdCountPage.CommandRecord) {
        // A legitimate fresh observer read may append another RESULT publication.
        // It may not alter the command's own identity, work, known result or old events.
        assertEquals(before,after.copy(publications=before.publications))
        assertEquals(before.publications,after.publications.take(before.publications.size))
    }
    private suspend fun result(h:Harness,page:G0HouseholdCountPage) {
        householdPagePhase(page,G0HouseholdCountPage.Phase.RESULT);page.settled();householdPixels(h.runtime,page,h.screen(page))
    }
    private suspend fun finish(h:Harness,http:NextSyncHttp,page:G0HouseholdCountPage,total:Int) {
        withTimeout(20_000) {h.runtime.awaitSynced()}
        val pixels=householdPixels(h.runtime,page,h.screen(page))
        val view=requireNotNull(page.events.single {it.version==pixels.version}.view)
        val state=pageNativeState();val deliveries=h.runtime.workerAttempts.map {requireNotNull(it.delivery)}
        assertTrue(h.runtime.shutdown());reopenReference();sameHouseholdAsOriginal(view)
        deliveries.forEach {assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(it))}
        assertEquals(state,pageNativeState());assertEquals(total,pageRowCount("completions"))
        assertEquals(total,pageRowCount("next_acceptances"));assertEquals(0,pageRowCount("sync_outbox"))
        assertFalse(page.diagnosticsOverflow);assertFalse(h.arrival(page).overflow)
    }

    @Test fun explicitBusyHandlerRequestsDoNotReplaceTheAcceptedCommandOrCreateAnotherWrite()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(2);val (http,_)=channel {successReply(it)}
        withHarness(http) {h->
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            try {
                val page=h.open();val firstTrace=G0PhaseTrace(driverTiming=false);page.trace=firstTrace
                val before=pageNativeState();h.store.afterPrepareForTest={entered.complete(Unit);release.await()}
                h.screen(page).tap("increment:${seed.habits[0].uuid}");withTimeout(20_000) {entered.await()}
                val intent=requireNotNull(page.command);val first=page.admissions.single()
                val replacement=G0PhaseTrace(driverTiming=false);page.trace=replacement
                // These are explicit API/handler requests, not disabled Button callbacks.
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    assertFalse(page.increment(seed.habits[0].uuid));assertFalse(page.increment(seed.habits[1].uuid))
                }
                assertEquals(listOf(G0HouseholdCountPage.AdmissionOutcome.ACCEPTED,G0HouseholdCountPage.AdmissionOutcome.BUSY,
                    G0HouseholdCountPage.AdmissionOutcome.BUSY),page.admissions.map {it.outcome})
                assertTrue(page.admissions.all {it.requestId==intent.requestId && it.firstStateVersion==first.firstStateVersion})
                assertSame(intent,page.command);assertEquals(1,page.commandRecords.size)
                assertSame(firstTrace,page.commandRecords.getValue(intent.requestId).trace)
                assertEquals(0L,page.commandRecords.getValue(intent.requestId).knownCommitAt);assertEquals(before,pageNativeState())
                release.complete(Unit);result(h,page)
                val record=completed(page,intent.requestId)
                assertSame(firstTrace,record.trace);assertTrue(firstTrace.spans.isNotEmpty());assertTrue(replacement.spans.isEmpty())
                assertEquals(intent.requestId,record.saved!!.requestId);assertTrue(record.knownCommitAt>0)
                assertEquals(1,page.admissions.count {it.outcome==G0HouseholdCountPage.AdmissionOutcome.ACCEPTED})
                finish(h,http,page,3)
            } finally {release.complete(Unit)}
        }
    }

    @Test fun successiveCommandsRetainSeparateTimesResultsAndUnknownCommitDoesNotInheritSuccess()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(2);val (http,_)=channel {successReply(it)}
        withHarness(http) {h->
            val syncRelease=CompletableDeferred<Unit>();h.runtime.beforeSyncBatchForTest={syncRelease.await()}
            try {
                val page=h.open();val retained=linkedMapOf<String,G0HouseholdCountPage.CommandRecord>()
                for(row in seed.habits) {
                    val trace=G0PhaseTrace(driverTiming=false);page.trace=trace
                    h.screen(page).tap("increment:${row.uuid}");result(h,page)
                    val intent=requireNotNull(page.command);val record=completed(page,intent.requestId)
                    assertSame(trace,record.trace);assertEquals(row.uuid,record.saved!!.habitUuid)
                    assertTrue(record.acceptedAt<=record.ioStartedAt && record.ioStartedAt<=record.knownCommitAt && record.knownCommitAt<=record.completedAt)
                    assertTrue(record.publications.any {it.phase==G0HouseholdCountPage.Phase.RESULT})
                    assertTrue(record.publications.all {it.at>=record.knownCommitAt})
                    val published=record.publications.last {it.phase==G0HouseholdCountPage.Phase.RESULT}
                    assertEquals(record.saved.factUuid,page.events.single {it.version==published.version}.view!!.cards.getValue(row.uuid).followUp.claim.completionUuid)
                    retained[intent.requestId]=record
                }
                assertEquals(2,page.commandRecords.size);retained.forEach {(id,record)->assertRetainedRecord(record,page.commandRecords.getValue(id))}
                assertEquals(2,page.commandRecords.values.map {it.saved!!.factId}.distinct().size)
                assertEquals(2,page.commandRecords.values.map {it.firstStateVersion}.distinct().size)
                syncRelease.complete(Unit);withTimeout(20_000) {h.runtime.awaitSynced()}
                householdPixels(h.runtime,page,h.screen(page))
                val beforeThird=page.commandRecords
                val unknown=G0PhaseTrace(driverTiming=false);page.trace=unknown;endFaults.failAfterNextEndIn(unknown)
                h.screen(page).tap("increment:${seed.habits[0].uuid}")
                withTimeout(20_000) {page.state.first {page.error!=null || it.phase==G0HouseholdCountPage.Phase.RECOVERY}}
                page.settled();assertTrue(h.runtime.shutdown())
                assertEquals("G0_COMMIT_RESULT_LOST",page.error?.message)
                val third=page.commandRecords.getValue(requireNotNull(page.command).requestId)
                assertSame(unknown,third.trace);assertEquals(0L,third.knownCommitAt);assertNull(third.saved)
                assertTrue(third.publications.isEmpty());assertNotNull(third.errorClass);assertTrue(third.completedAt>0)
                beforeThird.forEach {(id,record)->assertRetainedRecord(record,page.commandRecords.getValue(id))}
                assertEquals(3,page.admissions.count {it.outcome==G0HouseholdCountPage.AdmissionOutcome.ACCEPTED})
                reopenReference();assertEquals(5,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
                val recovered=openHousehold(http);val saved=requireNotNull(recovered.recover(third.command))
                assertEquals(third.command.requestId,saved.requestId)
                assertEquals(0L,page.commandRecords.getValue(third.command.requestId).knownCommitAt)
                assertFalse(page.diagnosticsOverflow)
            } finally {syncRelease.complete(Unit)}
        }
    }

    @Test fun notReadyForeignMissingCommandAndClosedRequestsHaveExplicitRefusalsWithoutWrites()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(2);val (http,_)=channel {successReply(it)}
        withHarness(http) {h->
            val page=h.open();val before=pageNativeState()
            val unused=G0HouseholdCountPage(h.store,tokens,h.control)
            try {
                assertFalse(unused.increment(seed.habits[0].uuid))
                assertEquals(G0HouseholdCountPage.AdmissionOutcome.NOT_READY,unused.admissions.single().outcome)
                assertTrue(unused.commandRecords.isEmpty())
            } finally {unused.close();unused.settled()}
            val foreign=id(4920)
            assertFalse(page.increment(foreign))
            val refused=page.admissions.single()
            assertEquals(foreign,refused.requestedHabitUuid);assertEquals(G0HouseholdCountPage.AdmissionOutcome.MISSING_COMMAND,refused.outcome)
            assertNull(refused.requestId);assertNull(refused.firstStateVersion);assertTrue(page.commandRecords.isEmpty())
            assertEquals(before,pageNativeState())
            // A foreign lookup did not consume either legitimate input slot.
            h.screen(page).tap("increment:${seed.habits[0].uuid}");result(h,page)
            withTimeout(20_000) {h.runtime.awaitSynced()}
            h.outer.tap("leave");h.runtime.awaitPageTransition();assertFalse(page.live())
            val after=pageNativeState();val size=page.commandRecords.size
            assertFalse(page.increment(seed.habits[1].uuid))
            assertEquals(G0HouseholdCountPage.AdmissionOutcome.CLOSED,page.admissions.last().outcome)
            assertEquals(size,page.commandRecords.size);assertEquals(after,pageNativeState())
            assertFalse(page.diagnosticsOverflow)
        }
    }

    @Test fun aRealDisabledTouchHasBusyPixelsAndDrawEvidenceButNoInventedHandlerAdmission()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(2);val (http,_)=channel {successReply(it)}
        withHarness(http) {h->
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            try {
                val page=h.open();h.store.afterPrepareForTest={entered.complete(Unit);release.await()}
                val before=pageNativeState();val screen=h.screen(page)
                screen.tap("increment:${seed.habits[0].uuid}");withTimeout(20_000) {entered.await()}
                val version=page.state.value.version;val commandVersion=page.commandTiming!!.firstStateVersion
                val feedback=h.feedback(page);val firstSeen=feedback.pixels.await(version)
                val drawing=withTimeout(15_000) {
                    while(h.arrival(page).buttonDraws.none {it.habitUuid==seed.habits[1].uuid && it.stateVersion==version && !it.enabled})delay(1)
                    h.arrival(page).buttonDraws.last {it.habitUuid==seed.habits[1].uuid && it.stateVersion==version}
                }
                assertFalse(drawing.enabled);assertTrue(drawing.current);assertEquals(commandVersion,drawing.commandVersion)
                assertTrue(h.arrival(page).privateDraws.any {it.stateVersion==version && it.allowed})
                val handlers=page.admissions.size;val clicks=screen.clicks.size
                val input=screen.tap("increment:${seed.habits[1].uuid}")
                val afterSeen=feedback.pixels.await(version)
                assertTrue(firstSeen.observed<=input && afterSeen.captureStarted>=screen.inputs.last().upReturned)
                assertEquals(version,page.state.value.version);assertEquals(handlers,page.admissions.size);assertEquals(clicks,screen.clicks.size)
                assertEquals(1,page.commandRecords.size);assertEquals(before,pageNativeState())
                assertEquals(commandVersion,feedback.draws.single {it.version==version}.commandVersion)
                assertTrue(feedback.draws.single {it.version==version}.phase==G0HouseholdCountPage.Phase.SAVING)
                // This controlled state remained held across both actual screenshots.
                // General arrival costs must label missing contemporaneous pixels unexplained.
                release.complete(Unit);result(h,page);finish(h,http,page,3)
            } finally {release.complete(Unit)}
        }
    }
}
