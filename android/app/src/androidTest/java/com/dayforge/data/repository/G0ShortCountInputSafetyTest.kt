package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic regressions for an input arriving after its displayed proof expired. */
@RunWith(AndroidJUnit4::class)
open class G0ShortCountInputSafetyTest:G0ShortCountScreenFixture() {
    @Test fun clickAfterAckGetsVisibleFeedbackAndExactlyOneBoundCommand()=runBlocking<Unit> {
        seedAccepted(2);val store=openCount();val prior=store.increase()
        val page=G0ShortCountPage(store,tokens,this);val seen=G0ScreenObservation()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        page.beforeAdmissionReadForTest={entered.complete(Unit);release.await()}
        try {
            page.open();install(page,seen);seen.await(page.state.value.version)
            val old=page.state.value.view!!;val (http,_)=channel {successReply(it)}
            val sent=requireNotNull(store.send(http,prior.requestId));store.acknowledge(sent)
            assertFalse(old.valid());val before=pageNativeState()
            seen.tap("increment");entered.await()
            val waiting=phase(page,G0ShortCountPage.Phase.VERIFYING);seen.await(waiting.version)
            requireVisiblePageText(setOf("正在核验，请稍候"))
            val command=requireNotNull(page.command)
            assertNull(waiting.view);assertFalse(page.increment());assertSame(command,page.command)
            assertEquals(before,pageNativeState());assertEquals(0L,page.committedAt)
            // A late refresh may read, but cannot replace the pending input with old RESULT.
            page.refresh();assertEquals(waiting,page.state.value)
            release.complete(Unit);page.settled();assertNull(page.error)
            val result=phase(page,G0ShortCountPage.Phase.RESULT);seen.await(result.version)
            assertEquals(command.requestId,page.saved!!.requestId);assertEquals(command.factUuid,page.saved!!.factUuid)
            assertEquals(1,page.events.count {it.phase==G0ShortCountPage.Phase.SAVING})
            assertEquals(4,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
            assertEquals(command.factUuid,result.view!!.followUp.claim.completionUuid)
            reopenReference();sameAsOriginal(result.view!!)
        } finally {release.complete(Unit);page.close();page.settled()}
    }

    @Test fun staleInputCannotUseOldViewWhenFreshReadDecryptionFails()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount();val page=G0ShortCountPage(store,tokens,this)
        try {
            page.open();store.increase();val before=pageNativeState();failDecrypt=true
            assertTrue(page.increment());page.settled()
            assertEquals(G0ShortCountPage.Phase.RECOVERY,page.state.value.phase);assertNotNull(page.error)
            assertNull(page.state.value.view);assertFalse(page.increment());assertEquals(before,pageNativeState())
            assertFalse(page.events.any {it.phase in setOf(G0ShortCountPage.Phase.SAVED,G0ShortCountPage.Phase.RESULT)})
        } finally {failDecrypt=false;page.close();page.settled()}
    }

    @Test fun freshReadDoesNotSubstituteForWriteEntryAuthentication()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount();val page=G0ShortCountPage(store,tokens,this)
        try {
            page.open();store.increase();val before=pageNativeState();var finalReached=false
            page.afterAdmissionReadForTest={failDecrypt=true}
            store.beforeFinalForTest={finalReached=true}
            assertTrue(page.increment());page.settled()
            assertFalse(finalReached);assertNotNull(page.error);assertEquals(before,pageNativeState())
            assertEquals(G0ShortCountPage.Phase.RECOVERY,page.state.value.phase)
            assertEquals(0L,page.committedAt);assertFalse(page.increment())
            assertFalse(page.events.any {it.phase in setOf(G0ShortCountPage.Phase.SAVED,G0ShortCountPage.Phase.RESULT)})
        } finally {failDecrypt=false;page.close();page.settled()}
    }

    @Test fun revalidatedInputStillRequiresFinalFreshAuthentication()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount();val page=G0ShortCountPage(store,tokens,this)
        try {
            page.open();store.increase();val before=pageNativeState()
            var finalReached=false
            store.beforeFinalForTest={finalReached=true;failDecrypt=true}
            assertTrue(page.increment());page.settled()
            assertTrue(finalReached);assertNotNull(page.error);assertEquals(before,pageNativeState())
            assertEquals(G0ShortCountPage.Phase.RECOVERY,page.state.value.phase)
            assertEquals(0L,page.committedAt);assertFalse(page.increment())
        } finally {failDecrypt=false;page.close();page.settled()}
    }

    @Test fun accountChangeAfterFreshReadCancelsBoundIntentBeforeWrite()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount();val page=G0ShortCountPage(store,tokens,this)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        page.afterAdmissionReadForTest={entered.complete(Unit);release.await()}
        try {
            page.open();store.increase();val before=pageNativeState();assertTrue(page.increment());entered.await()
            val command=page.command
            tokens.saveLoginSession("synthetic-other","synthetic-refresh","member",id(2990),false)
            release.complete(Unit);page.settled()
            assertEquals(G0ShortCountPage.Phase.CLOSED,page.state.value.phase);assertNull(page.state.value.view)
            assertEquals(before,pageNativeState());assertSame(command,page.command);assertFalse(page.increment())
            assertEquals(0L,page.committedAt)
        } finally {release.complete(Unit);page.close();page.settled()}
    }

    @Test fun closingPendingInputDoesNotCommitOrPublishLater()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount();val page=G0ShortCountPage(store,tokens,this)
        val entered=CompletableDeferred<Unit>()
        page.beforeAdmissionReadForTest={entered.complete(Unit);awaitCancellation()}
        try {
            page.open();store.increase();val before=pageNativeState();assertTrue(page.increment());entered.await()
            page.close();page.settled();val closed=page.state.value
            page.refresh();assertEquals(closed,page.state.value);assertEquals(before,pageNativeState())
            assertTrue(page.error is CancellationException);assertFalse(page.increment());assertEquals(0L,page.committedAt)
        } finally {page.close();page.settled()}
    }

    @Test fun cancellingPendingOwnerLeavesAnObservableRecoveryWithoutAWrite()=runBlocking<Unit> {
        seedAccepted(1);val store=openCount();val owner=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val page=G0ShortCountPage(store,tokens,owner);val entered=CompletableDeferred<Unit>()
        page.beforeAdmissionReadForTest={entered.complete(Unit);awaitCancellation()}
        try {
            page.open();store.increase();val before=pageNativeState();assertTrue(page.increment());entered.await()
            owner.cancel();page.settled()
            assertEquals(G0ShortCountPage.Phase.RECOVERY,page.state.value.phase);assertNull(page.state.value.view)
            assertTrue(page.error is CancellationException);assertEquals(before,pageNativeState());assertFalse(page.increment())
        } finally {owner.cancel();page.close();page.settled()}
    }
}
