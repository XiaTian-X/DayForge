package com.dayforge.data.repository

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Original service baseline with real Keystore, facts and loopback ACKs. No screen or sealed
 * incremental writer is claimed. All history is created by the original command service.
 */
@RunWith(AndroidJUnit4::class)
class G0RecurringActionCostTest:G0RecurringActionFixture() {
    private fun spans(trace:G0PhaseTrace)=synchronized(trace.spans) { trace.spans.toList() }
    private fun transactionMax(trace:G0PhaseTrace):Long {
        val all=spans(trace);val starts=all.filter { it.phase=="tx_begin_outer" }
        val ends=all.filter { it.phase=="tx_end_outer" }
        assertEquals(starts.size,ends.size)
        return starts.zip(ends).maxOfOrNull { (start,end)->
            assertTrue(end.end>=start.start);end.end-start.start
        } ?: 0L
    }
    private fun p95(values:List<Long>)=values.sorted()[kotlin.math.ceil(values.size*.95).toInt()-1]
    @Test(timeout=900_000) fun eightAcceptedFactsThenTwentyOriginalIncrements()=runBlocking<Unit> { measure(8) }
    @Test(timeout=900_000) fun eightyAcceptedFactsThenTwentyOriginalIncrements()=runBlocking<Unit> { measure(80) }
    private suspend fun measure(history:Int) {
        configureAction(target=1_000_000);val actions=actions()
        val (http,_)=channel { successReply(it) };val requests=sender(http)
        suspend fun accept(trace:G0PhaseTrace?=null):List<Long> {
            val row=db.syncOutboxDao().getAll().single()
            assertEquals("completion",row.recordType)
            val sendAt=android.os.SystemClock.elapsedRealtimeNanos()
            val delivery=requireNotNull(requests.sendOperation(access(),row.operationId))
            val sent=android.os.SystemClock.elapsedRealtimeNanos()
            val started=android.os.SystemClock.elapsedRealtimeNanos()
            val accepted=if(trace==null) requests.acceptOperation(delivery) else trace.run { requests.acceptOperation(delivery) }
            val elapsed=android.os.SystemClock.elapsedRealtimeNanos()-started
            assertEquals(NextOperationAcceptance.COMMITTED,accepted)
            val replayAt=android.os.SystemClock.elapsedRealtimeNanos()
            assertEquals(NextOperationAcceptance.REPLAYED,requests.acceptOperation(delivery))
            val replayNanos=android.os.SystemClock.elapsedRealtimeNanos()-replayAt
            assertTrue(db.syncOutboxDao().getAll().isEmpty())
            return listOf(sent-sendAt,elapsed,replayNanos)
        }
        repeat(history) { increment(actions);accept() }
        assertEquals(history,count("completions"));assertEquals(history,count("next_acceptances"))
        val actionTimes=mutableListOf<Long>();val ackTimes=mutableListOf<Long>();val holds=mutableListOf<Long>()
        repeat(20) { sample ->
            val action=G0PhaseTrace(driverTiming=false);val ack=G0PhaseTrace(driverTiming=false)
            val started=android.os.SystemClock.elapsedRealtimeNanos()
            val result=action.run { increment(actions) }
            val returned=android.os.SystemClock.elapsedRealtimeNanos()
            assertTrue(result.recorded);assertFalse(result.completed);assertFalse(result.goalReached)
            val fact=db.completionDao().getByHabitOnce(habit.id).maxBy { it.id }
            assertEquals(fact.uuid,requireNotNull(result.followUp).claim.completionUuid)
            assertEquals(history+sample+1,count("completions"))
            val deliveryTimes=accept(ack);val ackNanos=deliveryTimes[1]
            val terminalAt=android.os.SystemClock.elapsedRealtimeNanos()
            val actionHold=transactionMax(action);val ackHold=transactionMax(ack)
            actionTimes+=returned-started;ackTimes+=ackNanos;holds+=maxOf(actionHold,ackHold)
            Log.i("G0FactActionCost",buildJsonObject {
                put("history",history);put("sample",sample);put("accepted_before",history+sample)
                put("original_reference",true);put("sealed_candidate",false);put("screen_observation",false)
                put("command_to_verified_result_ns",returned-started);put("ack_ns",ackNanos)
                put("send_ns",deliveryTimes[0]);put("replay_ns",deliveryTimes[2]);put("command_to_ack_terminal_ns",terminalAt-started)
                put("command_tx_max_ns",actionHold);put("ack_tx_max_ns",ackHold)
                put("command_access_decryptions",action.count("decrypt_access"));put("command_refresh_decryptions",action.count("decrypt_refresh"))
                put("ack_access_decryptions",ack.count("decrypt_access"));put("ack_refresh_decryptions",ack.count("decrypt_refresh"))
                put("command_outer_transactions",action.count("tx_begin_outer"));put("ack_outer_transactions",ack.count("tx_begin_outer"))
                put("send_and_replay_included_in_ack",false)
            }.toString())
        }
        storage.reopen();val restored=actions()
        assertEquals((history+20).toLong(),currentCount(restored))
        assertEquals(history+20,count("completions"));assertEquals(history+20,count("next_request_origins"))
        assertEquals(history+20,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
        Log.i("G0FactActionDurable","{\"history\":$history,\"facts\":${history+20},\"origins\":${history+20},\"acceptances\":${history+20},\"outbox\":0}")
        val failures=buildList {
            if(p95(actionTimes)>700_000_000L) add("original command/result P95 >700ms (no pixels)")
            if(p95(ackTimes)>500_000_000L) add("original ACK P95 >500ms")
            if(holds.max()>150_000_000L) add("original transaction max >150ms")
        }
        assertTrue(failures.joinToString(";"),failures.isEmpty())
    }
}
