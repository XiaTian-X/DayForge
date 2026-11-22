package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Two reverse-order pairs per size. Component timings, NOT frames or P95. The reference
 * keeps its original writer/read/ACK; the candidate supplies both count views from one proof. */
@RunWith(AndroidJUnit4::class)
class G0ShortCountCostTest:G0ShortCountFixture() {
    private fun now()=SystemClock.elapsedRealtimeNanos()
    private data class Timed<T>(val value:T,val ns:Long,val hold:Long)
    private suspend fun <T> timed(label:String,body:suspend ()->T):Timed<T> {
        val trace=G0PhaseTrace(driverTiming=false);val start=now();val value=trace.run(body);val end=now()
        val hold=transactionHold(trace)
        Log.i("G0ShortCountMeasure",buildJsonObject {
            put("kind","phase");put("label",label);put("elapsed_ns",end-start);put("resource_ns",hold)
            put("access",trace.count("decrypt_access"));put("refresh",trace.count("decrypt_refresh"))
            put("transactions",trace.count("tx_end_outer"))
        }.toString())
        return Timed(value,end-start,hold)
    }
    @Test(timeout=900_000) fun eightFactsCountOutboxAckAndReadModels()=runBlocking<Unit> {measure(8)}
    @Test(timeout=900_000) fun eightyFactsCountOutboxAckAndReadModels()=runBlocking<Unit> {measure(80)}
    private suspend fun measure(historySize:Int)=coroutineScope {
        seedAccepted(historySize);seedFact();val image=storage.snapshotClosedDatabase()
        val failures=mutableListOf<String>();var completed=0
        repeat(2) {pair->for(candidate in if(pair==0)listOf(false,true)else listOf(true,false)) {
            freshGateForColdOpen();restoreForJourney(image)
            val oldRequest=db.syncOutboxDao().getAll().single().operationId
            val label="h${historySize}_p${pair}_c$candidate"
            val entryStart=now()
            val store=if(candidate)timed(label+"_cold") {openCount()}.value else null
            val (http,_)=channel {successReply(it)};val requests=sender(http)
            val repo=if(candidate)null else actions().repository
            val resultReader=if(candidate)null else resultReader()
            var captured:CompletionActionSnapshot?=null
            val oldDelivery=timed(label+"_prepare_background") {
                requireNotNull(if(store!=null)store.send(http,oldRequest) else requests.sendOperation(access(),oldRequest))
            }.value
            if(store!=null)assertEquals((historySize+1).toLong(),view(store).history.todayQuantity)
            else assertEquals((historySize+1).toLong(),readHome().counts.getValue(habit.id).todayQuantity)
            val started=CompletableDeferred<Long>();val chainStart=now()
            val background=async(Dispatchers.IO) {
                val start=now();started.complete(start)
                val result=timed(label+"_background_ack") {store?.acknowledge(oldDelivery) ?: requests.acceptOperation(oldDelivery)}
                assertEquals(NextOperationAcceptance.COMMITTED,result.value)
                Triple(start,now(),result.hold)
            }
            started.await();val foregroundStart=now()
            val command=timed(label+"_command") {
                if(store!=null)store.increase() else {
                    captured=requireNotNull(repo).prepareCompletion(habit.id)
                    val id=repo.logCompletion(app,habit.id,1,requireNotNull(captured).edit.authority,habit.uuid)
                    val fact=requireNotNull(db.completionDao().getCompletionById(id))
                    val request=db.syncOutboxDao().getEntityIntents("completion",fact.uuid).single()
                    G0ShortCountStore.Saved(id,fact.uuid,request.operationId)
                }
            }
            val saved=command.value
            suspend fun readBoth() {
                if(store!=null) {
                    val value=view(store,saved.factId)
                    assertEquals((historySize+2).toLong(),value.history.todayQuantity)
                    assertEquals(saved.factUuid,value.followUp.claim.completionUuid)
                } else {
                    var card:RecurringCompletionSnapshot?=null
                    requireNotNull(resultReader).readAndPublish(requireNotNull(captured),true,saved.factId) {card=it}
                    val home=readHome()
                    assertEquals((historySize+2).toLong(),requireNotNull(card).countHistory!!.todayQuantity)
                    assertEquals(card!!.countHistory!!.quantities,home.counts.getValue(habit.id).quantities)
                    assertEquals(saved.factUuid,card!!.followUp.claim.completionUuid)
                }
            }
            val reads=timed(label+"_read_models") {readBoth()}
            val foregroundEnd=now();val ack=background.await();val overlap=maxOf(0L,minOf(ack.second,foregroundEnd)-maxOf(ack.first,foregroundStart))
            val own=timed(label+"_send") {requireNotNull(if(store!=null)store.send(http,saved.requestId) else requests.sendOperation(access(),saved.requestId))}
            val ownAck=timed(label+"_ack") {store?.acknowledge(own.value) ?: requests.acceptOperation(own.value)}
            assertEquals(NextOperationAcceptance.COMMITTED,ownAck.value)
            val finalRead=timed(label+"_terminal_read") {readBoth()}
            val end=now();val state=pageNativeState()
            assertEquals(NextOperationAcceptance.REPLAYED,store?.acknowledge(own.value) ?: requests.acceptOperation(own.value))
            assertEquals(state,pageNativeState())
            val candidateView=store?.let {view(it,saved.factId)}
            if(store!=null)reopenReference()
            candidateView?.let {sameAsOriginal(it)}
            assertEquals(historySize+2,count("completions"));assertEquals(historySize+2,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
            assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(own.value));assertEquals(state,pageNativeState())
            Log.i("G0ShortCountMeasure",buildJsonObject {
                put("kind","chain");put("history",historySize);put("pair",pair);put("candidate",candidate)
                put("command_ns",command.ns);put("read_models_ns",reads.ns);put("send_ns",own.ns);put("ack_ns",ownAck.ns)
                put("terminal_read_ns",finalRead.ns);put("chain_ns",end-chainStart);put("overlap_ns",overlap)
                put("entry_chain_ns",end-entryStart);put("startup_ns",chainStart-entryStart)
                put("resource_ns",maxOf(command.hold,reads.hold,ack.third,own.hold,ownAck.hold,finalRead.hold))
                put("facts",historySize+2);put("receipts",historySize+2);put("outbox",0)
            }.toString());completed++
            if(command.ns>500_000_000L)failures+="$label command >500ms"
            if(reads.ns>700_000_000L)failures+="$label views >700ms"
            if(ownAck.ns>500_000_000L)failures+="$label ACK >500ms"
            if(maxOf(command.hold,reads.hold,ack.third,own.hold,ownAck.hold,finalRead.hold)>150_000_000L)failures+="$label resource >150ms"
            if(overlap==0L)failures+="$label missing overlap"
        }}
        assertEquals(4,completed)
        assertTrue("Exploratory component budgets, not UI/P95: "+failures.joinToString(";"),failures.isEmpty())
    }
}
