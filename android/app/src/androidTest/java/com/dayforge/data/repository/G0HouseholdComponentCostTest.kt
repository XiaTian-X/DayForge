package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.NextSyncPushResponse
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.CheckInResult
import com.dayforge.domain.model.CountHistory
import com.dayforge.domain.service.CompletionActionTiming
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Same closed image, two objects and six A/B/A/B/A/B commands per arm. The reference
 * calls the real CheckInService, including its own post-commit read; the candidate calls
 * the isolated owner. No screen, concurrent ACK, fixed arrival rate or P95 claim.
 * Each scale has ONE arm of each strategy; reversing order across scales does not remove
 * order/thermal effects. Diagnostics and the original budgets are retained in both arms.
 */
@RunWith(AndroidJUnit4::class)
class G0HouseholdComponentCostTest:G0HouseholdCountFixture() {
    private data class Timed<T>(val value:T,val elapsed:Long,val resource:Long)
    private data class Saved(val factId:Long,val factUuid:String,val requestId:String,val habitUuid:String)
    private data class Observed(val histories:Map<String,CountHistory>,val facts:Set<CompletionEntity>,
        val candidate:G0HouseholdCountStore.View?=null)
    private fun now()=SystemClock.elapsedRealtimeNanos()

    @Test(timeout=900_000) fun eightPastFactsTwoObjectsOriginalVersusHouseholdOwner()=runBlocking<Unit> {compare(8)}
    @Test(timeout=900_000) fun eightyPastFactsTwoObjectsOriginalVersusHouseholdOwner()=runBlocking<Unit> {compare(80)}

    private suspend fun <T> timed(history:Int,strategy:String,index:Int,stage:String,body:suspend ()->T):Timed<T> {
        val trace=G0PhaseTrace(driverTiming=false);val start=now();var returned=false;var stopped=0L
        try {
            val value=trace.run(body);stopped=now();val elapsed=stopped-start;returned=true
            return Timed(value,elapsed,transactionHold(trace))
        } finally {
            val end=stopped.takeIf {it!=0L} ?: now();val spans=synchronized(trace.spans) {trace.spans.toList()}
            val begins=spans.filter {it.phase=="tx_begin_outer"}.sortedBy {it.start}
            val ends=spans.filter {it.phase=="tx_end_outer"}.sortedBy {it.start}
            // Begin-through-end includes driver begin waiting. It is not an account-lock
            // or pure SQL execution estimate, and nested spans must not be summed.
            Log.i("G0HouseholdCompare",buildJsonObject {
                put("history",history);put("strategy",strategy);put("index",index);put("stage",stage)
                put("elapsed_ns",end-start);put("returned",returned)
                put("outer_begin_count",begins.size);put("outer_end_count",ends.size)
                put("transactions",buildJsonArray {begins.zip(ends).forEach {(a,b)->add(buildJsonObject {
                    put("start_offset_ns",a.start-start);put("end_offset_ns",b.end-start)
                    put("begin_through_end_ns",b.end-a.start);put("begin_call_ns",a.end-a.start)
                    put("end_call_ns",b.end-b.start);put("ok",a.ok && b.ok)
                })}})
                put("transaction_max_ns",begins.zip(ends).maxOfOrNull {(a,b)->b.end-a.start} ?: 0L)
                put("access_decryptions",trace.count("decrypt_access"));put("refresh_decryptions",trace.count("decrypt_refresh"))
                put("datastore_ns",trace.nanos("datastore_read"));put("source_lookup_ns",trace.nanos("household_source_lookup"))
                put("service_return_ns",trace.nanos("household_service"))
                put("account_wait_ns",trace.nanos("count_account_wait"));put("account_hold_ns",trace.nanos("count_account_hold"))
                put("ui",false);put("background_competition",false)
            }.toString())
            if(returned)assertEquals("Completed stage lost an instrumented transaction boundary",begins.size,ends.size)
        }
    }

    private suspend fun originalIncrease(actions:G0RecurringActionFixture.Actions,row:HabitEntity):Saved {
        val trace=requireNotNull(G0PhaseTrace.current.get())
        val result=trace.stage("household_service") {
            CompletionActionTiming.withOperation(CompletionActionTiming.Operation.COUNT_INCREMENT) {
                actions.service.incrementCount(app,row.id)
            }
        }
        assertTrue(result is CheckInResult.Success)
        val success=result as CheckInResult.Success
        assertTrue(success.recorded);assertNotNull(success.followUp)
        val followUp=requireNotNull(success.followUp)
        val factUuid=requireNotNull(followUp.claim.completionUuid)
        assertEquals(row.uuid,followUp.claim.habitUuid);assertEquals(row.id,followUp.claim.habitId)
        // No fabricated command identity. This extra original-path query is part of the
        // measured write stage and separately labelled, never hidden in test setup.
        return trace.stage("household_source_lookup") {sessions.exclusive {db.withTransaction {
            val fact=requireNotNull(db.completionDao().getCompletionByUuid(factUuid))
            require(fact.habitId==row.id && fact.habitUuid==row.uuid)
            val queued=db.syncOutboxDao().getEntityIntents("completion",factUuid).single()
            require(queued.referenceUuid==row.uuid && queued.entityUuid==factUuid && queued.wireEntityUuid==factUuid)
            val origin=requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION,queued.operationId))
            require(origin.queueId==queued.id)
            Saved(fact.id,fact.uuid,queued.operationId,row.uuid)
        }}}
    }

    private suspend fun originalView():Observed {
        val home=readHome()
        assertEquals(householdHabits.map {it.uuid}.toSet(),home.habits.map {it.uuid}.toSet())
        return Observed(home.habits.associate {it.uuid to home.counts.getValue(it.id)},home.completions.toSet())
    }
    private suspend fun candidateView(store:G0HouseholdCountStore,sources:Map<String,Long>):Observed {
        val view=householdView(store,sources);assertTrue(view.valid())
        return Observed(view.cards.mapValues {it.value.history},view.cards.values.flatMap {it.history.completions}.toSet(),view)
    }
    private fun projection(seed:G0HouseholdCountFixture.HouseholdSeed,result:Observed,completed:Int,sources:Map<String,Saved>) {
        assertEquals(seed.habits.map {it.uuid}.toSet(),result.histories.keys)
        assertEquals(seed.factIds.size+completed,result.facts.size)
        for((objectIndex,row) in seed.habits.withIndex()) {
            val added=(completed+if(objectIndex==0)1 else 0)/2
            val history=result.histories.getValue(row.uuid);val before=seed.references.getValue(row.uuid)
            assertEquals(added.toLong(),history.todayQuantity)
            assertEquals(before.quantities+if(added==0)emptyMap() else mapOf(seed.today to added.toLong()),history.quantities)
            assertEquals(before.completions.toSet(),history.completions.filter {it.recordedLocalDate!=seed.today.toString()}.toSet())
            assertEquals(before.completions.size+added,history.completions.size)
            assertEquals(before.todayPolicy,history.todayPolicy)
            assertEquals(added>0,seed.today in history.policies)
            sources[row.uuid]?.let {saved->
                assertEquals(saved.factUuid,history.completions.single {it.id==saved.factId}.uuid)
                result.candidate?.let {assertEquals(saved.factUuid,it.cards.getValue(row.uuid).followUp.claim.completionUuid)}
            }
        }
    }
    private fun checkBudgets(failures:MutableList<String>,strategy:String,index:Int,
        write:Timed<*>,read:Timed<*>,send:Timed<*>,ack:Timed<*>,terminal:Timed<*>,localResult:Long) {
        val key="$strategy/$index"
        if(write.elapsed>500_000_000L)failures+="$key write >500ms"
        if(read.elapsed>700_000_000L)failures+="$key after-read >700ms"
        if(localResult>700_000_000L)failures+="$key local result >700ms"
        if(ack.elapsed>500_000_000L)failures+="$key ACK >500ms"
        if(terminal.elapsed>700_000_000L)failures+="$key terminal read >700ms"
        for((stage,value) in listOf("write" to write,"after_read" to read,"send" to send,"ack" to ack,"terminal_read" to terminal))
            if(value.resource>150_000_000L)failures+="$key $stage resource >150ms"
    }

    private suspend fun compare(history:Int) {
        val seed=seedHouseholdAccepted(history);val image=storage.snapshotClosedDatabase()
        val failures=mutableListOf<String>();var completedArms=0
        for(candidate in if(history==8)listOf(false,true) else listOf(true,false)) {
            val strategy=if(candidate)"household" else "original"
            val setup=timed(history,strategy,-1,"setup") {
                freshGateForColdOpen();restoreForJourney(image)
                channel {successReply(it)}
            }
            val (http,server)=setup.value
            var owner:G0HouseholdCountStore?=null
            var originalActions:G0RecurringActionFixture.Actions?=null
            val cold=timed(history,strategy,-1,"cold_ready") {
                if(candidate) {
                    owner=openHousehold(http)
                    candidateView(requireNotNull(owner),emptyMap())
                } else {
                    originalActions=actions();originalView()
                }
            }
            projection(seed,cold.value,0,emptyMap())
            val deliveries=mutableListOf<NextCoreDelivery<NextSyncPushResponse>>()
            val latest=linkedMapOf<String,Saved>();var final=cold.value
            repeat(6) {index->
                check(LocalDate.now(seed.zone)==seed.today && ZoneId.systemDefault()==seed.zone) {"G0_HOUSEHOLD_COST_DATE_CHANGED"}
                val row=seed.habits[index%2];val started=now()
                val write=timed(history,strategy,index,"write") {
                    if(candidate)requireNotNull(owner).increase(row.uuid).let {Saved(it.factId,it.factUuid,it.requestId,it.habitUuid)}
                    else originalIncrease(requireNotNull(originalActions),row)
                }
                val saved=write.value;latest[row.uuid]=saved
                val sources=latest.mapValues {it.value.factId}
                val read=timed(history,strategy,index,"after_read") {
                    if(candidate)candidateView(requireNotNull(owner),sources) else originalView()
                }
                val localReturned=now();projection(seed,read.value,index+1,latest)
                val send=timed(history,strategy,index,"send") {
                    if(candidate)requireNotNull(requireNotNull(owner).send(http,saved.requestId))
                    else requireNotNull(sender(http).sendOperation(access(),saved.requestId))
                }
                val ack=timed(history,strategy,index,"ack") {
                    if(candidate)requireNotNull(owner).acknowledge(send.value) else sender(http).acceptOperation(send.value)
                }
                assertEquals(NextOperationAcceptance.COMMITTED,ack.value)
                val terminal=timed(history,strategy,index,"terminal_read") {
                    if(candidate)candidateView(requireNotNull(owner),sources) else originalView()
                }
                val finished=now();projection(seed,terminal.value,index+1,latest)
                assertEquals(read.value.histories.mapValues {it.value.quantities},terminal.value.histories.mapValues {it.value.quantities})
                val native=pageNativeState()
                val replay=timed(history,strategy,index,"replay") {
                    if(candidate)requireNotNull(owner).acknowledge(send.value) else sender(http).acceptOperation(send.value)
                }
                assertEquals(NextOperationAcceptance.REPLAYED,replay.value);assertEquals(native,pageNativeState())
                assertEquals(history+index+1,native.getValue("completions").size)
                assertEquals(history+index+1,native.getValue("next_request_origins").size)
                assertEquals(history+index+1,native.getValue("next_acceptances").size)
                assertEquals(0,native.getValue("sync_outbox").size)
                assertEquals(history+minOf(index+1,2),native.getValue("count_days").size)
                deliveries+=send.value;final=terminal.value
                Log.i("G0HouseholdCompare",buildJsonObject {
                    put("history",history);put("strategy",strategy);put("index",index);put("stage","chain")
                    put("object_slot",index%2);put("write_ns",write.elapsed);put("after_read_ns",read.elapsed)
                    put("local_result_ns",localReturned-started);put("send_ns",send.elapsed);put("ack_ns",ack.elapsed)
                    put("terminal_read_ns",terminal.elapsed);put("chain_ns",finished-started);put("replay_ns",replay.elapsed)
                    put("facts",history+index+1);put("outbox",0);put("replay_native_unchanged",true)
                    put("network_synthetic",true);put("ui",false);put("background_competition",false)
                }.toString())
                checkBudgets(failures,strategy,index,write,read,send,ack,terminal,localReturned-started)
            }
            assertEquals(6,server.requests.count {it.path.endsWith("/push")})
            val native=pageNativeState()
            // Oracle/replay validation is deliberately after all measured commands, so
            // it cannot replace the candidate's warm owner or prewarm each timed step.
            reopenReference()
            if(candidate)sameHouseholdAsOriginal(requireNotNull(final.candidate))
            else {
                val original=originalView();assertEquals(final.histories,original.histories);assertEquals(final.facts,original.facts)
                val counts=CountHistoryReader(db,tokens,sessions)
                val widgets=WidgetFactReader(db,tokens,sessions,preferences,counts,CheckHistoryReader(db,tokens,sessions))
                for(row in seed.habits) {
                    val current=requireNotNull(db.habitDao().getHabitById(row.id))
                    assertEquals(final.histories.getValue(row.uuid),counts.read(current))
                    val widget=widgets.read(current)
                    assertEquals(final.histories.getValue(row.uuid),widget.count)
                    assertEquals(latest.getValue(row.uuid).factUuid,widget.claim.completionUuid)
                }
            }
            for(delivery in deliveries)assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(delivery))
            assertEquals(native,pageNativeState());projection(seed,final,6,latest);completedArms++
            Log.i("G0HouseholdCompare",buildJsonObject {
                put("history",history);put("strategy",strategy);put("index",6);put("stage","terminal")
                put("setup_ns",setup.elapsed);put("cold_ready_ns",cold.elapsed);put("cold_transaction_max_ns",cold.resource)
                put("commands",6);put("pushes",6);put("facts",history+6);put("receipts",history+6);put("outbox",0)
                put("objects",2);put("past_dates_per_object",history/2);put("original_reader_and_replay_verified",true)
                put("all_native_tables_unchanged_by_oracle",true);put("ui",false);put("background_competition",false)
            }.toString())
        }
        assertEquals(2,completedArms)
        assertTrue("Original 500/700/150ms budgets; component comparison is not UI/P95: "+failures.joinToString(";"),failures.isEmpty())
    }
}
