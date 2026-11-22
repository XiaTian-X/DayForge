package com.dayforge.data.repository

import com.dayforge.data.api.NextSyncHttp
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*

abstract class G0CountIndependentFixture:G0CountDashboardFixture() {
    private val workers=mutableListOf<G0CountSyncWorker>()
    internal suspend fun worker(http:NextSyncHttp,capacity:Int=8)=G0CountSyncWorker(db,tokens,sessions,sender(http),access(),journeyOwner,capacity).also {workers+=it}
    internal suspend fun fact(id:Long):G0CountSyncWorker.Fact {
        val row=requireNotNull(db.completionDao().getCompletionById(id))
        return G0CountSyncWorker.Fact(row.id,row.uuid,requireNotNull(row.habitUuid))
    }
    internal suspend fun seedFact():G0CountSyncWorker.Fact {
        val result=increment(actions())
        val uuid=requireNotNull(result.followUp?.claim?.completionUuid)
        return fact(requireNotNull(db.completionDao().getCompletionByUuid(uuid)).id)
    }
    internal fun knownResult(flow:G0CountJourney):G0CountSyncWorker.Fact {
        check(flow.state.value.phase==G0CountJourney.Phase.RESULT && flow.lastError==null)
        val view=requireNotNull(flow.state.value.view)
        return G0CountSyncWorker.Fact(requireNotNull(flow.recordedId),requireNotNull(view.followUp.claim.completionUuid),view.habit.uuid)
    }
    internal suspend fun nextResult(flow:G0CountJourney,previous:Long) {
        withTimeout(20_000) {while(flow.state.value.version<=previous || flow.state.value.phase!=G0CountJourney.Phase.RESULT) delay(1)}
        flow.awaitSettled();assertNull(flow.lastError)
    }
    internal fun transactionHold(trace:G0PhaseTrace):Long {
        val spans=synchronized(trace.spans) {trace.spans.toList()}
        val begin=spans.filter {it.phase=="tx_begin_outer"};val end=spans.filter {it.phase=="tx_end_outer"}
        assertEquals(begin.size,end.size)
        return begin.zip(end).maxOfOrNull {(a,b)->b.end-a.start} ?: 0L
    }
    @After fun stopIndependentSync()=runBlocking {workers.forEach {it.shutdown()}}
}
