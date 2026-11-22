package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.TokenCipher
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class G0CountOpenSafetyTest:G0CountJourneyFixture() {
    override fun domainOpeningCallback()=G0SchemaOpeningCallback(true)
    private var blockTrace:G0PhaseTrace?=null
    private val authEntered=CountDownLatch(1)
    private val authRelease=CountDownLatch(1)
    override fun tokenCipher():TokenCipher {
        val actual=super.tokenCipher()
        return object:TokenCipher {
            override fun encrypt(value:String)=actual.encrypt(value)
            override fun decrypt(value:String):String? {
                if(blockTrace!=null && G0PhaseTrace.current.get()===blockTrace) {
                    authEntered.countDown();check(authRelease.await(10,TimeUnit.SECONDS)) { "G0_INITIAL_AUTH_RELEASE_TIMEOUT" }
                }
                return actual.decrypt(value)
            }
        }
    }
    private fun page(shared:Boolean=true)=journey(afterResult=true,loadOnOpen=true,shareInitialPure=shared)
    @Test fun initialSnapshotMatchesOriginalIsReadOnlyAndPublishesOnlyAfterTransactionEnd()=runBlocking<Unit> {
        configureAction();increment(actions());ackOnlyOperation();val before=pageNativeState()
        val original=actions().repository.prepareCompletion(habit.id).evidence!!
        for(shared in listOf(false,true)) {
            val trace=G0PhaseTrace(driverTiming=false);var published=false
            trace.run { resultReader().readInitialAndPublish(habit.id,shared) { view ->
                assertFalse(db.inTransaction());assertEquals(original.habit,view.habit)
                assertEquals(original.countHistory,view.countHistory);assertEquals(original.followUp,view.followUp);published=true
            } }
            assertTrue(published);assertEquals(1,trace.count("tx_end_outer"));assertEquals(2,trace.count("decrypt_access"))
            assertEquals(0,trace.count("decrypt_refresh"));assertEquals(before,pageNativeState())
        }
    }
    @Test fun opensOnlyOnceAndInitialResultIsNotASavedReceipt()=runBlocking<Unit> {
        configureAction();val flow=page();val trace=G0PhaseTrace(driverTiming=false);flow.initialTraceForTest=trace
        assertTrue(flow.opened());assertFalse(flow.opened());flow.awaitInitial()
        assertEquals(G0CountJourney.Phase.READY,flow.state.value.phase);assertEquals(0L,flow.state.value.view!!.countHistory!!.todayQuantity)
        assertEquals(2,trace.count("decrypt_access"));assertEquals(0,count("completions"));assertEquals(0,count("sync_outbox"))
        assertFalse(flow.events.any { it.kind=="committed" || it.kind=="state_SAVED_REFRESHING" })
        assertTrue(flow.increment());flow.awaitSettled();assertNull(flow.lastError)
        assertEquals(1,flow.events.count { it.kind=="prepare_shared" });assertEquals(1L,flow.state.value.view!!.countHistory!!.todayQuantity)
        ackOnlyOperation()
    }
    @Test fun referenceInitialReadDoesNotSelectColdSharedPreparation()=runBlocking<Unit> {
        configureAction();val flow=page(false);assertTrue(flow.opened());flow.awaitInitial()
        assertTrue(flow.increment());flow.awaitSettled();assertNull(flow.lastError)
        assertEquals(1,flow.events.count { it.kind=="prepare_original" });assertEquals(0,flow.events.count { it.kind=="prepare_shared" })
        ackOnlyOperation();assertTrue(flow.increment());flow.awaitSettled();assertNull(flow.lastError)
        assertEquals(1,flow.events.count { it.kind=="prepare_shared" })
    }
    @Test fun clickCancelsAQueuedInitialReadBeforeStartingFreshPreparation()=runBlocking<Unit> {
        configureAction();val flow=page();val entered=CompletableDeferred<Unit>();var left=false
        flow.beforeInitialReadForTest={entered.complete(Unit);try { awaitCancellation() } finally {left=true}}
        assertTrue(flow.opened());entered.await();flow.beforeWriteForTest={assertTrue(left)}
        assertTrue(flow.increment());assertEquals(G0CountJourney.Phase.SAVING,flow.state.value.phase)
        flow.awaitSettled();assertNull(flow.lastError);assertEquals(G0CountJourney.Phase.RESULT,flow.state.value.phase)
        assertFalse(flow.events.any { it.kind=="initial_published" });assertEquals(1,count("completions"))
    }
    @Test fun clickDuringRealSnapshotAuthenticationWaitsForReadCleanupAndCannotPublishItsOldResult()=runBlocking<Unit> {
        configureAction();storage.reopen();val flow=page();val initial=G0PhaseTrace(driverTiming=false);flow.initialTraceForTest=initial;blockTrace=initial
        assertTrue(flow.opened())
        try {
            assertTrue(withContext(Dispatchers.IO) { authEntered.await(10,TimeUnit.SECONDS) })
            assertTrue(flow.increment());assertEquals(G0CountJourney.Phase.SAVING,flow.state.value.phase)
            assertFalse(flow.events.any { it.kind=="write_started" });assertNull(flow.state.value.view)
        } finally { authRelease.countDown() }
        flow.awaitSettled();assertNull(flow.lastError);assertEquals(G0CountJourney.Phase.RESULT,flow.state.value.phase)
        assertFalse(flow.events.any { it.kind=="initial_published" })
        assertEquals(initial.count("tx_begin_outer"),initial.count("tx_end_outer"))
        assertEquals(1,count("completions"));assertEquals(1,count("sync_outbox"))
        assertTrue(flow.events.single { it.kind=="initial_finished" }.at<=flow.events.single { it.kind=="prepare_started" }.at)
    }
    @Test fun failedInitialTransactionEndNeverPublishesAndACommandMustRevalidate()=runBlocking<Unit> {
        configureAction();val flow=page();val trace=G0PhaseTrace(driverTiming=false);flow.initialTraceForTest=trace
        endFaults.failAfterNextEndIn(trace);assertTrue(flow.opened());flow.awaitInitial()
        assertEquals("G0_COMMIT_RESULT_LOST",flow.initialError?.message);assertNull(flow.state.value.view)
        assertEquals(G0CountJourney.Phase.READ_FAILED,flow.state.value.phase)
        assertFalse(flow.events.any { it.kind=="initial_published" });assertEquals(0,count("completions"))
        val command=G0PhaseTrace(driverTiming=false);flow.traceForTest=command
        assertTrue(flow.increment());flow.awaitSettled();assertNull(flow.lastError)
        assertEquals(6,command.count("decrypt_access"));assertEquals(1,flow.events.count { it.kind=="prepare_original" })
    }
    @Test fun oldInitialViewCannotAuthorizeAfterCredentialFailureOrHideCorruption()=runBlocking<Unit> {
        configureAction();increment(actions());ackOnlyOperation();val flow=page();assertTrue(flow.opened());flow.awaitInitial()
        assertNotNull(flow.state.value.view);val before=pageNativeState();failDecrypt=true
        try {
            assertTrue(flow.increment());flow.awaitSettled();assertNotNull(flow.lastError)
            assertEquals(G0CountJourney.Phase.RECOVERY,flow.state.value.phase);assertEquals(before,pageNativeState())
        } finally { failDecrypt=false }
        flow.close();val second=page();assertTrue(second.opened());second.awaitInitial()
        db.openHelper.writableDatabase.execSQL("UPDATE completions SET value=1.5 WHERE habitId=?",arrayOf(habit.id))
        val corrupt=pageNativeState();assertTrue(second.increment());second.awaitSettled()
        assertNotNull(second.lastError);assertEquals(G0CountJourney.Phase.RECOVERY,second.state.value.phase)
        assertEquals(corrupt,pageNativeState());assertFalse(second.events.any { it.kind=="committed" })
    }
    @Test fun closeAndAccountChangeCannotResurrectInitialContent()=runBlocking<Unit> {
        configureAction();val flow=page();val entered=CompletableDeferred<Unit>()
        flow.beforeInitialReadForTest={entered.complete(Unit);awaitCancellation()}
        assertTrue(flow.opened());entered.await();flow.close();flow.awaitSettled()
        tokens.saveLoginSession("synthetic-other","synthetic-refresh","member",id(2201),false)
        assertEquals(G0CountJourney.Phase.CLOSED,flow.state.value.phase);assertNull(flow.state.value.view)
        assertFalse(flow.opened());assertFalse(flow.increment());assertEquals(0,count("completions"))
    }
    @Test fun publishedInitialViewIsRevokedOnAccountTransition()=runBlocking<Unit> {
        configureAction();val flow=page();assertTrue(flow.opened());flow.awaitInitial();val old=flow.state.value
        assertNotNull(old.view);tokens.saveLoginSession("synthetic-other","synthetic-refresh","member",id(2202),false)
        assertFalse(old.renderable());assertNull(flow.state.value.view);assertFalse(flow.increment())
    }
}
