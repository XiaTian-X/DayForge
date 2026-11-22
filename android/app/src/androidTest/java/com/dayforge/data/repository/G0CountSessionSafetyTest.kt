package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Selects a computation strategy, never a cached proof or authentication capability. */
@RunWith(AndroidJUnit4::class)
class G0CountSessionSafetyTest:G0CountJourneySafetyTest() {
    override val reuseCountPreparationAfterResult=true
    override fun domainOpeningCallback()=G0SchemaOpeningCallback(true)
    private fun route(flow:G0CountJourney)=flow.events.filter { it.kind in setOf("prepare_original","prepare_shared") }.map { it.kind }
    @Test fun firstCommandUsesOriginalThenEachCurrentResultAllowsPureReuseWithFreshChecks()=runBlocking<Unit> {
        configureAction(target=1000);val flow=journey()
        repeat(3) { index ->
            val offset=flow.events.size;val trace=G0PhaseTrace(driverTiming=false);flow.traceForTest=trace
            assertTrue(flow.increment());flow.awaitSettled();assertNull(flow.lastError)
            assertEquals(G0CountJourney.Phase.RESULT,flow.state.value.phase)
            assertEquals((index+1).toLong(),flow.state.value.view!!.countHistory!!.todayQuantity)
            val events=flow.events.drop(offset)
            fun at(kind:String)=events.single { it.kind==kind }.at
            fun decryptions(from:Long,to:Long)=trace.spans.count { it.phase=="decrypt_access" && it.start>=from && it.end<=to }
            assertEquals(2,decryptions(at("prepare_started"),at("prepared")))
            assertEquals(if(index==0) 2 else 3,decryptions(at("write_started"),at("write_returned")))
            assertEquals(2,decryptions(at("committed"),at("state_RESULT")))
            assertEquals(if(index==0) 6 else 7,trace.count("decrypt_access"));assertEquals(0,trace.count("decrypt_refresh"))
            assertEquals(3,trace.count("tx_end_outer"))
            ackOnlyOperation()
        }
        assertEquals(listOf("prepare_original","prepare_shared","prepare_shared"),route(flow))
        assertTrue(flow.factBlockHits>0);assertEquals(3,count("completions"));assertEquals(3,count("next_acceptances"))
    }
    @Test fun aNewPageNeverInheritsTheClosedPagesWarmSelection()=runBlocking<Unit> {
        configureAction();val old=journey();assertTrue(old.increment());old.awaitSettled();ackOnlyOperation()
        val displayed=old.state.value;old.close();old.awaitSettled();assertFalse(displayed.renderable())
        val fresh=journey();assertTrue(fresh.increment());fresh.awaitSettled();assertNull(fresh.lastError)
        assertEquals(listOf("prepare_original"),route(fresh));assertEquals(2L,fresh.state.value.view!!.countHistory!!.todayQuantity)
        assertFalse(old.increment());ackOnlyOperation();assertEquals(2,count("completions"))
    }
    @Test fun aVerifiedNoOpCanReusePureWorkButCannotInventACommit()=runBlocking<Unit> {
        configureAction(countdown=true,target=1);increment(actions());ackOnlyOperation()
        val before=pageNativeState();val flow=journey()
        repeat(2) {
            assertTrue(flow.increment());flow.awaitSettled();assertNull(flow.lastError)
            assertEquals(G0CountJourney.Phase.UNCHANGED,flow.state.value.phase)
            assertEquals(before,pageNativeState());assertNull(flow.recordedId)
        }
        assertEquals(listOf("prepare_original","prepare_shared"),route(flow))
        assertFalse(flow.events.any { it.kind=="committed" })
    }
    @Test fun changedOldFactAfterAResultCannotReuseThatResultsValidity()=runBlocking<Unit> {
        configureAction();val flow=journey();assertTrue(flow.increment());flow.awaitSettled();ackOnlyOperation()
        db.openHelper.writableDatabase.execSQL("UPDATE completions SET value=1.5 WHERE habitId=?",arrayOf(habit.id))
        val before=pageNativeState();assertTrue(flow.increment());flow.awaitSettled()
        assertEquals(listOf("prepare_original","prepare_shared"),route(flow))
        assertEquals(G0CountJourney.Phase.RECOVERY,flow.state.value.phase);assertNull(flow.state.value.view)
        assertNotNull(flow.lastError);assertEquals(before,pageNativeState())
        assertEquals(1,flow.events.count { it.kind=="committed" });assertFalse(flow.increment())
    }
}
