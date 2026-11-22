package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Component selection, not UI/P95 acceptance. Frozen history restored for each indexed trial.
 * No index build time is hidden in command results: maintenance is reported separately. */
@RunWith(AndroidJUnit4::class)
class G0IndexCostTest:G0IndexFixture() {
    @Test(timeout=900_000) fun eightHistoryIndexNetCost()=runBlocking<Unit> {run(8)}
    @Test(timeout=900_000) fun eightyHistoryIndexNetCost()=runBlocking<Unit> {run(80)}
    @Test(timeout=900_000) fun eightObjectsWithEightPendingFactsEachIndexNetCost()=runBlocking<Unit> {run(8,8)}
    private suspend fun run(history:Int,objects:Int=1) {
        val pending=objects>1
        if(!pending) seedAccepted(history) else {
            configureAction(target=1_000_000);val primary=habit;val rows=mutableListOf(primary)
            // Synthetic plan fixtures only; every pending fact/source is made by the original service.
            db.withTransaction {
                db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                repeat(objects-1) {i->
                    val row=primary.copy(id=0,uuid=id(3000+i),name="G0 index object $i")
                    rows+=row.copy(id=db.habitDao().insert(row))
                }
                db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            }
            val (http,_)=channel {successReply(it)};val requests=sender(http)
            for(row in rows) {habit=row;repeat(history) {position->
                val fact=seedFact()
                // The original count protocol requires the day's first receipt before successors.
                if(row.id==primary.id && position==0) {
                    val intent=db.syncOutboxDao().getEntityIntents("completion",fact.uuid).single()
                    val sent=requireNotNull(requests.sendOperation(access(),intent.operationId))
                    assertEquals(NextOperationAcceptance.COMMITTED,requests.acceptOperation(sent))
                }
            }}
            habit=primary
        }
        val total=history*objects;val acceptedBefore=if(pending)1 else history
        val backlog=db.syncOutboxDao().getAll()
        assertEquals(if(pending)total-1 else 0,backlog.size)
        val image=storage.snapshotClosedDatabase();val failures=mutableListOf<String>()
        val (http,_)=channel {successReply(it)}
        // This experiment keeps the original reference sender, not the specialized ACK adapter.
        val auth=mapOf("read" to (4 to 0),"command" to (6 to 0),"send" to (14 to 13),
            "ack" to (2 to 2),"competing" to (4 to 0),"final" to (4 to 0))
        repeat(2) {pair->
            val modes=G0CandidateIndexes.Mode.entries.let {if(pair==0)it.toList() else it.reversed()}
            for(mode in modes) {
                restoreForJourney(image);val sql=db.openHelper.writableDatabase
                fun size()=sql.query("PRAGMA page_count").use {c->check(c.moveToFirst());c.getLong(0)}
                val pagesBefore=size();val install=SystemClock.elapsedRealtimeNanos();G0CandidateIndexes.apply(sql,mode)
                val installNanos=SystemClock.elapsedRealtimeNanos()-install;val pagesAfter=size()
                if(pair==0)plans("h${history}_o${objects}_$mode")
                val label="h${history}_o${objects}_p${pair}_$mode";val start=SystemClock.elapsedRealtimeNanos()
                val before=pageNativeState();val read=measure("${label}_read",false) {readHome()};assertEquals(before,pageNativeState())
                assertEquals(history.toLong(),read.value.counts.getValue(habit.id).todayQuantity)
                val command=measure("${label}_command",false) {increment(actions())}
                val uuid=requireNotNull(command.value.followUp?.claim?.completionUuid)
                val row=db.syncOutboxDao().getEntityIntents("completion",uuid).single();val request=sender(http)
                val send=measure("${label}_send",false) {requireNotNull(request.sendOperation(access(),row.operationId))}
                var ack:Measurement<NextOperationAcceptance>?=null
                var competing:Measurement<G0DetailedDashboardReadBatch>?=null
                coroutineScope {
                    val ready=CompletableDeferred<Unit>()
                    val a=async(Dispatchers.IO) {ready.await();measure("${label}_ack",false) {request.acceptOperation(send.value)}}
                    val b=async(Dispatchers.IO) {ready.await();measure("${label}_competing_read",false) {readHome()}}
                    ready.complete(Unit);ack=a.await();competing=b.await()
                }
                assertEquals(NextOperationAcceptance.COMMITTED,ack!!.value)
                assertEquals((history+1).toLong(),competing!!.value.counts.getValue(habit.id).todayQuantity)
                val final=measure("${label}_final_read",false) {readHome()}
                assertEquals((history+1).toLong(),final.value.counts.getValue(habit.id).todayQuantity)
                val elapsed=SystemClock.elapsedRealtimeNanos()-start
                val state=pageNativeState();assertEquals(NextOperationAcceptance.REPLAYED,request.acceptOperation(send.value));assertEquals(state,pageNativeState())
                assertEquals(total+1,count("completions"));assertEquals(acceptedBefore+1,count("next_acceptances"));assertEquals(total+1,count("next_request_origins"));assertEquals(backlog,db.syncOutboxDao().getAll())
                for((stage,m) in listOf("read" to read,"command" to command,"send" to send,"ack" to ack!!,"competing" to competing!!,"final" to final)) {
                    val counts=m.trace.count("decrypt_access") to m.trace.count("decrypt_refresh")
                    assertEquals("$label $stage fresh credential calls",auth.getValue(stage),counts)
                    if(transactionHold(m.trace)>150_000_000)failures+="$label $stage resource >150ms"
                }
                if(read.elapsed>300_000_000)failures+="$label home read >300ms"
                if(command.elapsed>700_000_000)failures+="$label verified command >700ms"
                if(ack!!.elapsed>500_000_000)failures+="$label ACK >500ms"
                Log.i("G0IndexNet",buildJsonObject {
                    put("history",history);put("objects",objects);put("pending",pending);put("pair",pair);put("mode",mode.name);put("maintenance_ns",installNanos)
                    put("pages_before",pagesBefore);put("pages_after",pagesAfter);put("chain_ns",elapsed)
                    put("read_ns",read.elapsed);put("command_ns",command.elapsed);put("send_ns",send.elapsed)
                    put("ack_ns",ack!!.elapsed);put("competing_read_ns",competing!!.elapsed);put("final_read_ns",final.elapsed)
                    put("facts",total+1);put("acceptances",acceptedBefore+1);put("outbox",backlog.size)
                }.toString())
            }
        }
        assertTrue("Exploratory component budgets, not UI/P95: "+failures.joinToString(";"),failures.isEmpty())
    }
}
