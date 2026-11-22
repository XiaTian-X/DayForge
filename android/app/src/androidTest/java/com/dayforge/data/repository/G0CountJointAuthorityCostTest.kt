package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Paired local-authority candidate versus the PRIOR G0 owner, not production versus G0.
 * Identical database bytes, real Keystore/Room/socket, same diagnostics in both arms.
 * No concurrent worker or screen: component chains and small samples, never UI or P95. */
@RunWith(AndroidJUnit4::class)
class G0CountJointAuthorityCostTest:G0SendAttributionFixture() {
    private fun now()=SystemClock.elapsedRealtimeNanos()
    private data class Timed<T>(val value:T,val ns:Long,val resource:Long)
    @Test(timeout=900_000) fun eightFactsPairedFreshAuthorityChains()=runBlocking<Unit> {measure(8)}
    @Test(timeout=900_000) fun eightyFactsPairedFreshAuthorityChains()=runBlocking<Unit> {measure(80)}

    private suspend fun <T> timed(history:Int,pair:Int,joint:Boolean,phase:String,body:suspend ()->T):Timed<T> {
        val trace=G0PhaseTrace(driverTiming=false);val decryptBefore=decryptCount.get();val start=now()
        val value=trace.run {sendTrace.capture(body)};val end=now();val sample=sendTrace.snapshot()
        assertTrue(sample.complete);assertTrue(sample.returned)
        val access=sample.events.count {it.kind=="decrypt_access"}
        val refresh=sample.events.count {it.kind=="decrypt_refresh"}
        assertEquals(decryptCount.get()-decryptBefore,access+refresh)
        assertEquals(0,sample.events.count {it.kind=="decrypt_unclassified"})
        assertEquals(when(phase) {
            "send"->if(joint)16 else 19
            "read","terminal_read"->if(joint)2 else 3
            else->if(joint)2 else 4
        },access)
        assertEquals(if(phase=="send")13 else 0,refresh)
        if(phase=="send") {
            assertEquals(1,sample.events.count {it.kind=="http_identity" && it.ok})
            assertEquals(1,sample.events.count {it.kind=="http_push" && it.ok})
        }
        val resource=transactionHold(trace)
        Log.i("G0JointAuthority",buildJsonObject {
            put("kind","phase");put("history",history);put("pair",pair);put("joint",joint);put("phase",phase)
            put("elapsed_ns",end-start);put("resource_ns",resource)
            put("access",access);put("refresh",refresh)
            put("legacy_access",trace.count("decrypt_access"));put("legacy_refresh",trace.count("decrypt_refresh"))
            put("reads",sample.events.count {it.kind=="datastore_first"})
            put("account_wait_ns",trace.nanos("count_account_wait"));put("account_hold_ns",trace.nanos("count_account_hold"))
            put("datastore_ns",trace.nanos("datastore_read"));put("decrypt_ns",trace.nanos("decrypt_access"))
            put("transaction_end_ns",trace.nanos("tx_end_outer"));put("complete",true)
        }.toString())
        return Timed(value,end-start,resource)
    }
    private suspend fun measure(history:Int) {
        seedAccepted(history);val image=storage.snapshotClosedDatabase()
        val failures=mutableListOf<String>();var complete=0
        repeat(3) {pair ->
            for(joint in if(pair%2==0)listOf(false,true)else listOf(true,false)) {
                freshGateForColdOpen();restoreForJourney(image)
                val coldStart=now();val store=openCount(jointAuthority=joint);val coldEnd=now()
                val (http,_)=channel {successReply(it)};val start=now()
                val write=timed(history,pair,joint,"write") {store.increase()}
                val read=timed(history,pair,joint,"read") {view(store,write.value.factId)}
                assertEquals((history+1).toLong(),read.value.history.todayQuantity)
                assertEquals(write.value.factUuid,read.value.followUp.claim.completionUuid)
                val localResult=now()
                val send=timed(history,pair,joint,"send") {requireNotNull(store.send(http,write.value.requestId))}
                val ack=timed(history,pair,joint,"ack") {store.acknowledge(send.value)}
                assertEquals(NextOperationAcceptance.COMMITTED,ack.value)
                val terminal=timed(history,pair,joint,"terminal_read") {view(store,write.value.factId)}
                val end=now();val state=pageNativeState()
                assertEquals(read.value.history.quantities,terminal.value.history.quantities)
                assertEquals(read.value.presentationRevision,terminal.value.presentationRevision)
                assertTrue(terminal.value.proofRevision>read.value.proofRevision)
                reopenReference();sameAsOriginal(terminal.value)
                assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(send.value))
                assertEquals(state,pageNativeState())
                assertEquals(history+1,count("completions"));assertEquals(history+1,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
                Log.i("G0JointAuthority",buildJsonObject {
                    put("kind","chain");put("history",history);put("pair",pair);put("joint",joint)
                    put("cold_ns",coldEnd-coldStart);put("local_result_ns",localResult-start);put("chain_ns",end-start)
                    put("facts",history+1);put("receipts",history+1);put("outbox",0)
                    put("original_reader_and_replay_verified",true);put("network_synthetic",true);put("ui",false)
                }.toString());complete++
                if(write.ns>500_000_000L)failures+="$pair/$joint write >500ms"
                if(read.ns>700_000_000L)failures+="$pair/$joint views >700ms"
                if(ack.ns>500_000_000L)failures+="$pair/$joint ACK >500ms"
                for((name,result) in listOf("write" to write,"read" to read,"send" to send,"ack" to ack,"terminal_read" to terminal))
                    if(result.resource>150_000_000L)failures+="$pair/$joint $name resource >150ms"
            }
        }
        assertEquals(6,complete)
        assertTrue("Original component budgets; paired experiment is NOT UI/P95: "+failures.joinToString(";"),failures.isEmpty())
    }
}
