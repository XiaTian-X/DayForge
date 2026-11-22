package com.dayforge.data.repository

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.ZonedDateTime
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real metric edit/outbox/send/ACK with COUNT/CHECK consumers; component data, not UI frames. */
@RunWith(AndroidJUnit4::class)
class G0MixedMetricReadCostTest : G0RecurringReadFixture() {
    private suspend fun compare(history:Int) {
        val ids=seed(history);val now=ZonedDateTime.now();val before=state();val image=storage.snapshotClosedDatabase()
        val candidateHolds=mutableListOf<Long>();val candidateEdits=mutableListOf<Long>()
        val (http,_)=channel { input -> successReply(input,revision=(if(input.path.endsWith("/identity")) 0L else
            wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0L)+1) }
        listOf(false,true,true,false).forEachIndexed { block,reuse ->
            storage.restoreClosedDatabase(image);freshGateForColdOpen(recurringReads=true);storage.reopen()
            val store=G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid,queueClock={millis},admitRecurringReads=true)
            val reader=G0RecurringReadOwner(db,tokens,sessions,authority,domainGate,sharedScope=true,reuseEvidence=reuse)
            lateinit var expected:G0RecurringReadOwner.Content
            val cold=G0PhaseTrace(driverTiming=false)
            cold.run {
                store.initialize(access(),trustedDomainSchema(true))
                val first=reader.read(ids,now);expected=first.content
                assertTrue(reader.publish(first) { assertTrue(it.valid()) })
            }
            Log.i("G0MixedMetricReadCold",buildJsonObject {
                put("history",history);put("block",block);put("reuse",reuse);put("initialize_and_first_content_ns",cold.nanos("operation"))
                put("proof_queries",reader.proofQueries)
            }.toString())
            repeat(10) { offset ->
                val index=block*10+offset;val name="Mixed $index";val requestId=id(8000+index)
                val trace=G0PhaseTrace(driverTiming=false);val startHits=reader.reuseHits;val startFull=reader.fullReads
                var publication:G0RecurringReadOwner.Publication?=null;var attempts=0
                suspend fun readAndPublish() {
                    attempts++
                    val batch=trace.stage("mixed_read") { reader.read(ids,now) }
                    assertEquals(expected,batch.content)
                    trace.stage("mixed_publish") { reader.publish(batch) { publication=it;assertTrue(it.valid()) } }
                }
                trace.run {
                    trace.stage("mixed_edit") { requireNotNull(store.edit(name,"",requestId,millis+index)) }
                    val delivery=trace.stage("mixed_send") { requireNotNull(store.send(http,access(),requestId)) }
                    trace.stage("mixed_joint") {
                        coroutineScope {
                            val go=CompletableDeferred<Unit>()
                            val ack=async(Dispatchers.IO) { go.await();trace.stage("mixed_ack") { store.acknowledge(delivery) } }
                            val consumer=async(Dispatchers.IO) { go.await();readAndPublish() }
                            go.complete(Unit)
                            assertEquals(NextOperationAcceptance.COMMITTED,ack.await());consumer.await()
                        }
                        // A pre-ACK publication may have been revoked by the real commit. Re-read,
                        // never re-label its version. No writes remain once both tasks complete.
                        if(publication?.valid()!=true) readAndPublish()
                        assertTrue(requireNotNull(publication).valid());assertTrue(attempts in 1..2)
                        val view=store.read(access());assertEquals(name,view.name);assertEquals(0,view.pending);assertEquals((offset+1).toLong(),view.serverRevision)
                    }
                }
                val owned=trace.spans.filter { it.phase=="domain_sql_hold" }.sortedBy { it.start }
                val begins=trace.spans.filter { it.phase=="tx_begin_outer" }.sortedBy { it.start }
                val ends=trace.spans.filter { it.phase=="tx_end_outer" }.sortedBy { it.start }
                assertTrue(owned.isNotEmpty());assertEquals(owned.size,begins.size);assertEquals(owned.size,ends.size)
                for(i in owned.indices) assertTrue("Timer must contain actual engine begin/end",owned[i].start<=begins[i].start && owned[i].end>=ends[i].end)
                val hold=owned.maxOf { it.end-it.start }
                if(reuse) { candidateHolds+=hold;candidateEdits+=trace.nanos("mixed_edit") }
                Log.i("G0MixedMetricReadCost",buildJsonObject {
                    put("history",history);put("block",block);put("offset",offset);put("reuse",reuse);put("attempts",attempts)
                    put("reuse_hits",reader.reuseHits-startHits);put("full_reads",reader.fullReads-startFull)
                    put("edit_ns",trace.nanos("mixed_edit"));put("send_ns",trace.nanos("mixed_send"));put("ack_ns",trace.nanos("mixed_ack"))
                    put("joint_ns",trace.nanos("mixed_joint"));put("total_ns",trace.nanos("operation"));put("read_ns",trace.nanos("mixed_read"));put("publish_ns",trace.nanos("mixed_publish"))
                    put("max_sql_hold_ns",hold);put("engine_intervals_covered",true);put("owned_intervals",owned.size)
                    put("fresh_decrypt_calls",trace.spans.count { it.phase.startsWith("decrypt_") })
                }.toString())
            }
            reader.close();val after=state()
            for(table in listOf("habits","completions","count_days","timelogs","metric_logs","habit_metric_links","sync_outbox")) assertEquals(table,before[table],after[table])
            for(table in listOf("next_request_origins","next_structural_dependencies","next_transmissions","next_acceptances")) {
                assertTrue(table,requireNotNull(after[table]).containsAll(requireNotNull(before[table])))
                assertEquals(table,requireNotNull(before[table]).size+10,requireNotNull(after[table]).size)
            }
        }
        Log.i("G0MixedMetricReadTerminal",buildJsonObject {
            put("history",history);put("full_samples",20);put("reuse_samples",20);put("all_foreign_rows_preserved",true)
            put("all_metric_requests_accepted",true);put("all_current_results_equal",true)
        }.toString())
        val p95=candidateEdits.sorted()[18]
        assertTrue("Metric edit P95 exceeded original 500 ms",p95<=500_000_000)
        assertTrue("Mixed resources exceeded original 150 ms",candidateHolds.max()<=150_000_000)
    }
    @Test(timeout=300_000) fun eightFactsWithRealMetricAckCompetition()=runBlocking<Unit> { compare(8) }
    @Test(timeout=300_000) fun eightyFactsWithRealMetricAckCompetition()=runBlocking<Unit> { compare(80) }
}
