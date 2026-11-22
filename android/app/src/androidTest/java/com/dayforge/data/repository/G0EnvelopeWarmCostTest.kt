package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Forced same-object preparation collision. Real ACK/write/read, no UI or steady-load claim. */
@RunWith(AndroidJUnit4::class)
class G0EnvelopeWarmCostTest : G0SealedDomainFixture() {
    @Test(timeout=900_000) fun backgroundAckInvalidatesPreparedForegroundCommandTwentyTimes() = runBlocking<Unit> {
        register()
        val (http,_)=channel { input -> successReply(input,revision=(if(input.path.endsWith("/identity")) 0L else
            wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0L)+1) }
        repeat(8) { i ->
            val row=editMetric("Seed $i")
            val delivery=requireNotNull(sender(http).sendOperation(access(),row.operationId))
            assertEquals(NextOperationAcceptance.COMMITTED,sender(http).acceptOperation(delivery))
        }
        val image=storage.snapshotClosedDatabase()
        val control=mutableListOf<Long>()
        val candidate=mutableListOf<Long>()
        for (batched in listOf(false,true)) {
        storage.restoreClosedDatabase(image);freshGateForColdOpen(compactJournal=true)
        storage.isolatedBuilder={ context -> androidx.room.Room.databaseBuilder(context,com.dayforge.data.local.HabitDatabase::class.java,"habit_database")
            .addCallback(G0SchemaOpeningCallback(true)).openHelperFactory(domainGate.factory).build() }
        storage.reopen()
        val store=G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid,memoryPreparation=true,batchColdNegatives=true,reuseColdPureValues=true,reuseColdEnvelopeText=batched)
        val current=access();store.initialize(current,trustedDomainSchema())
        val commits=mutableListOf<Long>();val holds=mutableListOf<Long>()
        repeat(20) { sample ->
            val first=requireNotNull(store.edit("First $sample","",id(1400+sample*2),millis+sample*2))
            val delivery=requireNotNull(store.send(http,current,first.operationId))
            val prepared=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var attempts=0
            val preparations=mutableListOf<Long>()
            store.afterPrepareForTest={ if(currentCoroutineContext()[CoroutineName]?.name=="foreground") {
                attempts++;preparations+=SystemClock.elapsedRealtimeNanos();prepared.complete(Unit);release.await()
            } }
            val writeTrace=G0PhaseTrace(driverTiming=false);val ackTrace=G0PhaseTrace(driverTiming=false)
            val readTrace=G0PhaseTrace(driverTiming=false)
            val started=SystemClock.elapsedRealtimeNanos()
            val write=async(CoroutineName("foreground")) {
                writeTrace.run { requireNotNull(store.edit("Second $sample","",id(1401+sample*2),millis+sample*2+1)) }
            }
            prepared.await()
            val ackStarted=SystemClock.elapsedRealtimeNanos()
            assertEquals(NextOperationAcceptance.COMMITTED,ackTrace.run { store.acknowledge(delivery) })
            val ackEnded=SystemClock.elapsedRealtimeNanos()
            release.complete(Unit);val second=write.await()
            val committed=SystemClock.elapsedRealtimeNanos();commits+=committed-started
            val view=readTrace.run { store.read(current) }
            val read=SystemClock.elapsedRealtimeNanos()
            assertEquals("Second $sample",view.name);assertEquals(1,view.pending);assertEquals(2,attempts)
            assertEquals(1,writeTrace.count("preparation_retry"))
            assertEquals(3,writeTrace.count("decrypt_access"));assertEquals(2,ackTrace.count("decrypt_access"))
            assertEquals(1,writeTrace.count("tx_begin_outer"))
            assertEquals(1,ackTrace.count("tx_begin_outer"))
            fun resource(trace:G0PhaseTrace):Long {
                val starts=trace.spans.filter { it.phase=="tx_begin_outer" }.sortedBy { it.start }
                val ends=trace.spans.filter { it.phase=="tx_end_outer" }.sortedBy { it.start }
                check(starts.size==ends.size)
                val tx=starts.zip(ends).maxOfOrNull { (a,b)->check(b.end>=a.start);b.end-a.start } ?: 0L
                return maxOf(tx,trace.spans.filter { it.phase in setOf("short_account_hold","domain_sql_hold") }.maxOfOrNull { it.end-it.start } ?: 0L)
            }
            for(t in listOf(writeTrace,ackTrace,readTrace)) {
                assertEquals(1,t.count("engine_end_call"));assertEquals(1,t.count("engine_end_postcheck"));assertEquals(1,t.count("tx_end_boundary_probe"))
            }
            val writeHold=resource(writeTrace);val ackHold=resource(ackTrace);val readHold=resource(readTrace)
            holds+=listOf(writeHold,ackHold,readHold)
            Log.i("G0EnvelopeWarmCost",buildJsonObject {
                put("batched",batched);put("write_transactions",writeTrace.count("tx_begin_outer"));put("ack_transactions",ackTrace.count("tx_begin_outer"));put("sample",sample);put("starting_history",8+sample*2);put("attempts",attempts)
                put("before_ack_ns",ackStarted-started);put("ack_wall_ns",ackEnded-ackStarted);put("after_ack_ns",committed-ackEnded)
                put("first_prepared_ns",preparations.first()-started);put("second_prepared_ns",preparations.last()-started)
                put("foreground_commit_ns",committed-started);put("committed_read_ns",read-started)
                put("ack_return_ns",ackTrace.nanos("operation"));put("write_hold_max_ns",writeHold)
                put("ack_hold_max_ns",ackHold);put("read_hold_max_ns",readHold)
                put("write_account_wait_ns",writeTrace.nanos("short_account_wait"))
                put("write_sql_wait_ns",writeTrace.nanos("domain_sql_wait"))
                put("write_access_decryptions",writeTrace.count("decrypt_access"))
                put("ack_access_decryptions",ackTrace.count("decrypt_access"))
                put("actual_frame_measured",false);put("prompt_measured",false)
            }.toString())
            // Emit only phase names and relative intervals, after all measured endpoints.
            // One short JSON line per span avoids logcat truncation; no SQL/input/credential data.
            for((role,trace) in listOf("write" to writeTrace,"ack" to ackTrace,"read" to readTrace)) {
                val spans=synchronized(trace.spans) { trace.spans.toList() }
                spans.forEachIndexed { index,span -> Log.i("G0EnvelopeWarmSpan",buildJsonObject {
                    put("batched",batched);put("sample",sample);put("role",role);put("index",index)
                    span.cpuNanos?.let { put("cpu_ns",it) };put("phase",span.phase);put("start_ns",span.start-started);put("end_ns",span.end-started);put("ok",span.ok)
                }.toString()) }
            }
            store.afterPrepareForTest={}
            val next=requireNotNull(store.send(http,current,second.operationId))
            assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(next))
        }
        storage.isolatedBuilder=null;storage.reopen()
        assertEquals(48,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
        assertEquals(20,commits.size);assertEquals(60,holds.size)
        if(batched) {
        assertTrue("G0 contended resource max=${holds.max()} exceeds 150ms",holds.all { it<=150_000_000L })
        assertTrue("G0 contended commit P95=${commits.sorted()[18]} exceeds 500ms",commits.sorted()[18]<=500_000_000L)
        candidate+=commits
        } else control+=commits
        }
        assertEquals(20,control.size);assertEquals(20,candidate.size)
        assertTrue("G0 envelope witness warm regressed",candidate.sorted()[18]<=control.sorted()[18])
    }
}
