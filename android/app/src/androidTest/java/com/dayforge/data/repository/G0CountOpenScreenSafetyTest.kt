package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0CountOpenScreenSafetyTest:G0CountJourneyScreenFixture() {
    override fun domainOpeningCallback()=G0SchemaOpeningCallback(true)
    @Test fun actualOpenShowsVerifiedCountBeforeClickAndThenFreshCommittedResult()=runBlocking<Unit> {
        configureAction();increment(actions());ackOnlyOperation()
        val flow=journey(afterResult=true,loadOnOpen=true,shareInitialPure=true);val seen=G0ScreenObservation()
        install(flow,seen)
        val ready=withTimeout(20_000) { flow.state.first { it.phase==G0CountJourney.Phase.READY && it.view!=null } }
        val initialFrame=seen.await(ready.version);flow.awaitInitial()
        val initialDraw=seen.draws.single {it.version==ready.version}.at
        assertTrue(initialDraw>=ready.stateStartedAt);assertTrue(initialFrame.observed>=initialDraw);requireVisiblePageText(setOf("已验证计数：1","可以记录，操作时验证权限"))
        assertFalse(flow.events.any { it.kind=="committed" });seen.tap("increment")
        val result=phase(flow,G0CountJourney.Phase.RESULT);val resultFrame=seen.await(result.version);flow.awaitSettled()
        val resultDraw=seen.draws.single {it.version==result.version}.at
        assertTrue(resultDraw>=result.stateStartedAt);assertTrue(resultFrame.observed>=resultDraw)
        assertNull(flow.lastError);requireVisiblePageText(setOf("已验证计数：2","已保存，结果已核验"));assertFalse(ready.renderable())
        assertEquals(1,flow.events.count { it.kind=="prepare_shared" });ackOnlyOperation()
    }
    @Test fun actualImmediateClickIsEnabledWhileInitialReadIsPendingAndShowsSaving()=runBlocking<Unit> {
        configureAction();val flow=journey(afterResult=true,loadOnOpen=true,shareInitialPure=true);val seen=G0ScreenObservation()
        val initial=CompletableDeferred<Unit>();val writing=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        flow.beforeInitialReadForTest={initial.complete(Unit);awaitCancellation()}
        flow.beforeWriteForTest={writing.complete(Unit);release.await()}
        install(flow,seen);seen.await(0);initial.await();seen.tap("increment");writing.await()
        try {
            val saving=phase(flow,G0CountJourney.Phase.SAVING);seen.await(saving.version)
            requireVisiblePageText(setOf("正在保存"));assertNull(saving.view);assertEquals(0,count("completions"))
            assertFalse(flow.events.any { it.kind=="initial_published" })
        } finally {release.complete(Unit)}
        val result=phase(flow,G0CountJourney.Phase.RESULT);val resultFrame=seen.await(result.version);flow.awaitSettled()
        val resultDraw=seen.draws.single {it.version==result.version}.at
        assertTrue(resultDraw>=result.stateStartedAt);assertTrue(resultFrame.observed>=resultDraw)
        assertNull(flow.lastError);assertEquals(1,count("completions"))
    }
    @Test fun actualCloseCancelsTheOpenReadWithoutAWriteOrLaterPersonalContent()=runBlocking<Unit> {
        configureAction();val flow=journey(afterResult=true,loadOnOpen=true,shareInitialPure=true);val seen=G0ScreenObservation()
        val entered=CompletableDeferred<Unit>();flow.beforeInitialReadForTest={entered.complete(Unit);awaitCancellation()}
        install(flow,seen);seen.await(0);entered.await();seen.tap("close")
        val closed=phase(flow,G0CountJourney.Phase.CLOSED);seen.await(closed.version);flow.awaitSettled()
        assertNull(closed.view);assertFalse(flow.events.any { it.kind=="initial_published" });assertEquals(0,count("completions"))
    }
    @Test fun replacementPageCannotInheritPreviousClosedVersionOrItsDrawTimestamp()=runBlocking<Unit> {
        configureAction()
        val first=journey(afterResult=true);val firstSeen=G0ScreenObservation();install(first,firstSeen)
        firstSeen.await(0);firstSeen.tap("increment")
        val firstResult=phase(first,G0CountJourney.Phase.RESULT);firstSeen.await(firstResult.version);first.awaitSettled()
        assertNull(first.lastError);ackOnlyOperation();first.close()
        val closed=phase(first,G0CountJourney.Phase.CLOSED);assertEquals(4L,closed.version);firstSeen.await(closed.version)
        val next=journey(afterResult=true,loadOnOpen=true,shareInitialPure=true);val nextSeen=G0ScreenObservation()
        install(next,nextSeen)
        val ready=withTimeout(20_000) {next.state.first {it.phase==G0CountJourney.Phase.READY && it.view!=null}}
        nextSeen.await(ready.version);next.awaitInitial()
        assertEquals(1L,ready.version);requireVisiblePageText(setOf("已验证计数：1","可以记录，操作时验证权限"))
        assertTrue("replacement drew a version from the previous page: ${nextSeen.draws}",
            nextSeen.draws.all {it.version in setOf(0L,ready.version)})
        nextSeen.tap("increment");val result=phase(next,G0CountJourney.Phase.RESULT)
        val observed=nextSeen.await(result.version);next.awaitSettled();assertNull(next.lastError)
        // Deliberately reuse the old page's CLOSED number, as in the retained cost failure.
        assertEquals(closed.version,result.version)
        val draw=nextSeen.draws.single {it.version==result.version}.at
        assertTrue("draw=$draw state=${result.stateStartedAt}",draw>=result.stateStartedAt)
        assertTrue(observed.observed>=draw);requireVisiblePageText(setOf("已验证计数：2","已保存，结果已核验"))
        ackOnlyOperation()
    }
}
