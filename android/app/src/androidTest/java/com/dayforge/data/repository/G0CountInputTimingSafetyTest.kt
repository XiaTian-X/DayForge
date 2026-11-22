package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostics only, against the actual joint-authority owner. No extra command, cached
 * permission or promise of a rendered intermediate state is introduced by timestamps. */
@RunWith(AndroidJUnit4::class)
class G0CountInputTimingSafetyTest:G0ShortCountScreenFixture() {
    override val jointCountAuthority=true

    private fun observedState(page:G0ShortCountPage,seen:G0ScreenObservation,
        observation:G0ScreenObservation.Seen):G0ShortCountPage.State {
        val state=page.events.single {it.version==observation.version}
        val draw=seen.draws.single {it.version==state.version}
        assertTrue(draw.at>=state.publishedAt)
        assertTrue(observation.observed>=draw.at)
        assertTrue(observation.observed>=observation.captureStarted)
        return state
    }

    @Test fun oneRealTapBindsVersionTimesAndRefreshCannotReplaceItsCommandTiming()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount();val page=G0ShortCountPage(store,tokens,this);val seen=G0ScreenObservation()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        page.afterCommitForTest={entered.complete(Unit);release.await()}
        try {
            val loading=page.state.value
            page.open();install(page,seen);val ready=seen.await(page.state.value.version)
            assertTrue(observedState(page,seen,ready).publishedAt>=loading.publishedAt)
            val eventStart=page.events.size
            val start=seen.tap("increment");entered.await()
            val savedState=phase(page,G0ShortCountPage.Phase.SAVED)
            val observedSaved=seen.await(savedState.version)
            assertEquals(savedState,observedState(page,seen,observedSaved))
            val timing=requireNotNull(page.commandTiming)
            val first=page.events.drop(eventStart).first()
            val input=seen.inputs.single();val click=seen.clicks.single()
            assertEquals(start,input.started);assertEquals("increment",click.key)
            assertTrue(input.started<=input.downReturned && input.downReturned<=input.upStarted && input.upStarted<=input.upReturned)
            assertTrue(click.at>=input.upStarted) // No claim that onClick follows UP's return.
            assertEquals(G0ShortCountPage.Phase.SAVING,first.phase)
            assertEquals(first.version,timing.firstStateVersion)
            assertTrue(first.publishedAt>=click.at && timing.ioStartedAt>=first.publishedAt)
            assertTrue(timing.knownCommitAt>=timing.ioStartedAt)
            assertEquals(page.committedAt,timing.knownCommitAt)
            assertTrue(savedState.publishedAt>=timing.knownCommitAt)
            assertEquals(page.saved!!.requestId,timing.command.requestId)
            assertFalse(page.increment());assertEquals(timing,page.commandTiming)
            assertEquals(2,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
            page.refresh();assertEquals(savedState,page.state.value);assertEquals(timing,page.commandTiming)
            release.complete(Unit);page.settled();assertNull(page.error)
            val result=phase(page,G0ShortCountPage.Phase.RESULT)
            assertEquals(result,observedState(page,seen,seen.await(result.version)))
            page.refresh();assertEquals(timing,page.commandTiming)
            assertEquals(timing.command.factUuid,page.state.value.view!!.followUp.claim.completionUuid)
            assertEquals(1,page.events.count {it.phase==G0ShortCountPage.Phase.SAVING})
            assertEquals(1,page.events.count {it.phase==G0ShortCountPage.Phase.SAVED})
            for((before,after) in page.events.zipWithNext()) {
                assertTrue(after.version>before.version);assertTrue(after.publishedAt>=before.publishedAt)
            }
            val last=page.state.value;page.close();page.settled()
            assertEquals(G0ShortCountPage.Phase.CLOSED,page.state.value.phase)
            assertTrue(page.state.value.publishedAt>=last.publishedAt)
            assertEquals(timing,page.commandTiming)
            reopenReference();sameAsOriginal(result.view!!)
        } finally {release.complete(Unit);page.close();page.settled()}
    }

    @Test fun cancelledSecondIntentCannotBorrowThePreviousCommitTimestamp()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount();val page=G0ShortCountPage(store,tokens,this)
        val entered=CompletableDeferred<Unit>()
        try {
            page.open();assertTrue(page.increment());page.settled();assertNull(page.error)
            val previous=requireNotNull(page.commandTiming);assertTrue(previous.knownCommitAt>0)
            val before=pageNativeState();val eventStart=page.events.size
            store.afterPrepareForTest={entered.complete(Unit);awaitCancellation()}
            assertTrue(page.increment());entered.await()
            val pending=requireNotNull(page.commandTiming)
            assertNotSame(previous.command,pending.command);assertTrue(pending.ioStartedAt>0)
            assertEquals(0L,pending.knownCommitAt)
            // The legacy last-known global timestamp remains unchanged; only the exact
            // new intent's diagnostic record can describe this attempted command.
            assertEquals(previous.knownCommitAt,page.committedAt)
            val saving=page.state.value;page.close();page.settled()
            assertTrue(page.error is CancellationException)
            assertEquals(G0ShortCountPage.Phase.CLOSED,page.state.value.phase)
            assertTrue(page.state.value.publishedAt>=saving.publishedAt)
            assertEquals(pending,page.commandTiming);assertEquals(before,pageNativeState())
            assertFalse(page.events.drop(eventStart).any {it.phase in setOf(G0ShortCountPage.Phase.SAVED,G0ShortCountPage.Phase.RESULT)})
        } finally {page.close();page.settled()}
    }

    @Test fun finalAuthenticationAndUnknownCommitFailuresNeverInventKnownCommitTimes()=runBlocking<Unit> {
        seedAccepted(1);val image=storage.snapshotClosedDatabase()
        for(unknownCommit in listOf(false,true)) {
            freshGateForColdOpen();restoreForJourney(image)
            val store=openCount();val page=G0ShortCountPage(store,tokens,this)
            try {
                page.open();val before=pageNativeState();val eventStart=page.events.size
                if(unknownCommit) {
                    val trace=G0PhaseTrace(driverTiming=false);page.trace=trace;endFaults.failAfterNextEndIn(trace)
                } else store.beforeFinalForTest={failDecrypt=true}
                assertTrue(page.increment());page.settled()
                val timing=requireNotNull(page.commandTiming)
                assertTrue(timing.ioStartedAt>0);assertEquals(0L,timing.knownCommitAt)
                assertEquals(0L,page.committedAt);assertNotNull(page.error)
                assertEquals(G0ShortCountPage.Phase.RECOVERY,page.state.value.phase)
                assertTrue(page.state.value.publishedAt>=timing.ioStartedAt)
                assertFalse(page.increment());assertEquals(timing,page.commandTiming)
                assertFalse(page.events.drop(eventStart).any {it.phase in setOf(G0ShortCountPage.Phase.SAVED,G0ShortCountPage.Phase.RESULT)})
                if(unknownCommit) {
                    assertEquals("G0_COMMIT_RESULT_LOST",page.error?.message)
                    assertEquals(2,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
                } else assertEquals(before,pageNativeState())
            } finally {failDecrypt=false;page.close();page.settled()}
        }
    }
}
