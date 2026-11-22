package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.HabitDatabase
import com.dayforge.ui.InFlightActions
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Four paired observations, not a replacement for the unchanged 20-pair latency gate.
 * The prepared arm assumes enough visible-page lead time; total work is measured separately.
 */
@RunWith(AndroidJUnit4::class)
class G0PreparedInteractionCostTest : G0SealedDomainFixture() {
    @Test(timeout=900_000) fun visiblePreparationSeparatesClickWaitFromTotalWork() = runBlocking<Unit> {
        register()
        val (http,_)=channel { input -> successReply(input,revision=(if(input.path.endsWith("/identity")) 0L else
            wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0L)+1) }
        repeat(80) { i ->
            val row=editMetric("Seed $i")
            val delivery=requireNotNull(sender(http).sendOperation(access(),row.operationId))
            assertEquals(NextOperationAcceptance.COMMITTED,sender(http).acceptOperation(delivery))
        }
        val image=storage.snapshotClosedDatabase()
        repeat(4) { sample ->
            for(prewarm in if(sample%2==0) listOf(false,true) else listOf(true,false)) {
                storage.restoreClosedDatabase(image);freshGateForColdOpen()
                storage.isolatedBuilder={ context -> Room.databaseBuilder(context,HabitDatabase::class.java,"habit_database")
                    .addCallback(G0SchemaOpeningCallback(true)).openHelperFactory(domainGate.factory).build() }
                storage.reopen()
                val cold=G0PhaseTrace(false);val write=G0PhaseTrace(false);val ack=G0PhaseTrace(false)
                var builds=0
                val totalStart=SystemClock.elapsedRealtimeNanos()
                val preparation=async(Dispatchers.IO,start=CoroutineStart.LAZY) {
                    builds++
                    cold.run {
                        G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid).also {
                            it.initialize(access(),trustedDomainSchema(true))
                        }
                    }
                }
                var readyBeforeClick:Long?=null
                if(prewarm) { preparation.start();preparation.await();readyBeforeClick=SystemClock.elapsedRealtimeNanos() }
                val actions=InFlightActions()
                val done=CompletableDeferred<Triple<Long,Long,G0ShortMetricStore.View>>()
                var click=0L;var feedback=0L
                val pageScope=CoroutineScope(currentCoroutineContext()+Dispatchers.IO)
                withContext(Dispatchers.Main) {
                    click=SystemClock.elapsedRealtimeNanos()
                    actions.launch(pageScope,metric.id) {
                        val store=preparation.await()
                        write.run { requireNotNull(store.edit("Measured","",id(1900),millis+100)) }
                        val committed=SystemClock.elapsedRealtimeNanos()
                        val view=store.read(access())
                        done.complete(Triple(committed,SystemClock.elapsedRealtimeNanos(),view))
                    }
                    assertEquals(setOf(metric.id),actions.active.value)
                    feedback=SystemClock.elapsedRealtimeNanos()
                }
                val (committed,read,view)=done.await()
                assertEquals("Measured",view.name);assertEquals(1,view.pending);assertEquals(1,builds)
                assertEquals(80,cold.count("cold_origin_proof"));assertEquals(240,cold.count("cold_codec_syntax"))
                assertEquals(80,cold.count("cold_proof_dependency_binding"));assertEquals(80,cold.count("cold_proof_source_absence"))
                assertEquals(2,cold.count("cold_entry_auth")+cold.count("cold_final_auth"))
                assertEquals(2,write.count("decrypt_access"));assertEquals(1,write.count("tx_begin_outer"))
                val store=preparation.await()
                val delivery=requireNotNull(store.send(http,access(),id(1900)))
                assertEquals(NextOperationAcceptance.COMMITTED,ack.run { store.acknowledge(delivery) })
                assertEquals(2,ack.count("decrypt_access"))
                assertEquals(0,store.read(access()).pending)
                Log.i("G0PreparedInteractionCost",buildJsonObject {
                    put("sample",sample);put("prewarm",prewarm);put("history",80)
                    put("feedback_state_ns",feedback-click);put("click_commit_ns",committed-click)
                    put("click_read_ns",read-click);put("total_commit_ns",committed-totalStart)
                    put("total_read_ns",read-totalStart);put("preparation_lead_ns",click-totalStart)
                    readyBeforeClick?.let { put("prepared_ready_ns",it-totalStart) }
                    put("cold_account_hold_ns",cold.nanos("short_account_hold"))
                    put("write_account_hold_ns",write.nanos("short_account_hold"))
                    put("actual_frame_measured",false);put("success_prompt_measured",false)
                    put("full_history_proofs",cold.count("cold_origin_proof"))
                }.toString())
                storage.isolatedBuilder=null;storage.reopen()
                assertEquals(81,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
                assertEquals(81,count("next_request_origins"))
            }
        }
    }
}
