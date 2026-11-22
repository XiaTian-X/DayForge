package com.dayforge.data.repository

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** One predeclared finite pair per history. Setup is shared; each arm restores the
 * same closed image and preferences only after the preceding owner/oracle has ended.
 * Adoption diagnostics do not introduce new latency limits or require perfect deltas.
 */
@RunWith(AndroidJUnit4::class)
class G0StablePromptCostTest:G0CompletionPromptCostFixture() {
    @Test(timeout=1_800_000) fun eightHistoryPairedPromptWindowStrategies()=runBlocking<Unit> {pair(8)}
    @Test(timeout=1_800_000) fun annualHistoryPairedPromptWindowStrategies()=runBlocking<Unit> {pair(730)}

    private fun compare(history:Int,strategy:String,index:Int,stage:String,body:JsonObjectBuilder.()->Unit) {
        Log.i("G0CompletionPromptCompare",buildJsonObject {
            put("history",history);put("strategy",strategy);put("index",index);put("stage",stage);body()
        }.toString())
    }

    private suspend fun pair(history:Int) {
        val modes=if(history==8)listOf(G0CompletionPromptSession.WindowMode.RECREATE,G0CompletionPromptSession.WindowMode.RETAIN_REQUEST)
            else listOf(G0CompletionPromptSession.WindowMode.RETAIN_REQUEST,G0CompletionPromptSession.WindowMode.RECREATE)
        val results=linkedMapOf<G0CompletionPromptSession.WindowMode,Measurement>()
        var stage="seed";var activeStrategy="PAIR";var probeRunning=false
        try {
            val seedAt=now();val seed=seedPrompt(history);val seedNs=now()-seedAt
            val zone=ZoneId.systemDefault();val date=LocalDate.now(zone)
            val native=pageNativeState();val preferences=dataStore.data.first()
            val image=storage.snapshotClosedDatabase();val imageHash=nextRequestHash(image)
            val nativeHash=nextRequestHash(JsonObject(native.toSortedMap().mapValues {(_,rows)->
                JsonArray(rows.map(::JsonPrimitive))
            }).toString().toByteArray(Charsets.UTF_8))
            compare(history,"PAIR",-1,"seed") {
                put("seed_ns",seedNs);put("seed_builds",1);put("seed_cost_shared",true)
                put("image_hash",imageHash);put("native_hash",nativeHash);put("image_bytes",image.size)
                put("same_business_date_required",true)
                put("order",JsonArray(modes.map {JsonPrimitive(it.name)}))
                put("adoption_rule","Four paired prompt observations across both histories must improve; all signed endpoint and real ACK drainage tradeoffs remain visible; no new hard time limits.")
            }
            for((order,mode) in modes.withIndex()) {
                activeStrategy=mode.name;stage="restore"
                val restoreAt=now()
                freshGateForColdOpen();restoreForJourney(image)
                dataStore.updateData {preferences}
                assertFalse("The restored Room must remain unopened for byte comparison",db.isOpen)
                val file=app.getDatabasePath("habit_database");val wal=java.io.File(file.path+"-wal")
                check(!wal.exists() || wal.length()==0L)
                assertEquals(imageHash,nextRequestHash(file.readBytes()))
                assertEquals(native,pageNativeState());assertEquals(preferences,dataStore.data.first())
                assertEquals(zone,ZoneId.systemDefault());assertEquals(date,LocalDate.now(zone))
                val restoreNs=now()-restoreAt
                compare(history,mode.name,order,"restored") {
                    put("image_hash",imageHash);put("native_hash",nativeHash);put("restore_ns",restoreNs)
                    put("image_bytes_equal",true);put("native_equal",true);put("preferences_equal",true)
                    put("previous_arms_completed",results.size);put("prior_owner_drained_before_restore",true)
                }
                stage="probe";probeRunning=true
                val result=measure(seed,seedNs,mode,mode.name,SharedSetup(imageHash,restoreNs,order))
                probeRunning=false;results[mode]=result
                stage="arm_terminal"
                assertEquals(zone,ZoneId.systemDefault());assertEquals(date,LocalDate.now(zone))
                compare(history,mode.name,order,"arm_complete") {
                    put("image_hash",imageHash);put("operations",3);put("syncs",3)
                    put("owner_drained",true);put("original_verified",true);put("replayed",true)
                    put("budget_failures",JsonArray(result.failures.map(::JsonPrimitive)))
                }
            }
            stage="comparison";activeStrategy="PAIR"
            val baseline=results.getValue(G0CompletionPromptSession.WindowMode.RECREATE)
            val candidate=results.getValue(G0CompletionPromptSession.WindowMode.RETAIN_REQUEST)
            assertEquals(listOf(0,1,2),baseline.samples.map {it.index})
            assertEquals(listOf(0,1,2),candidate.samples.map {it.index})
            for(index in 0..2) {
                val old=baseline.samples[index];val new=candidate.samples[index]
                assertEquals(old.promptNs!=null,new.promptNs!=null);assertEquals(old.backNs!=null,new.backNs!=null)
                compare(history,"PAIR",index,"paired_operation") {
                    fun metric(name:String,previous:Long,current:Long) {
                        put("${name}_recreate_ns",previous);put("${name}_retain_ns",current)
                        put("${name}_delta_ns",current-previous)
                    }
                    metric("save",old.saveNs,new.saveNs);metric("page",old.pageNs,new.pageNs)
                    metric("ack",old.ackNs,new.ackNs)
                    metric("known_commit_to_send",old.commitToSendNs,new.commitToSendNs)
                    metric("known_commit_to_ack",old.commitToAckNs,new.commitToAckNs)
                    old.promptNs?.let {metric("prompt",it,requireNotNull(new.promptNs))}
                    old.backNs?.let {metric("back",it,requireNotNull(new.backNs))}
                    put("delta_scope","retain_minus_recreate; signed observed pair, not population statistic")
                }
            }
            val promptsEarlier=baseline.samples.zip(candidate.samples).filter {it.first.promptNs!=null}.all {
                requireNotNull(it.second.promptNs)<requireNotNull(it.first.promptNs)
            }
            val tradeoffs=buildList {
                baseline.samples.zip(candidate.samples).forEach {(old,new)->
                    if(new.saveNs>old.saveNs)add("${old.index}:save")
                    if(new.pageNs>old.pageNs)add("${old.index}:page")
                    if(new.ackNs>old.ackNs)add("${old.index}:ack")
                    if(old.backNs!=null && requireNotNull(new.backNs)>old.backNs)add("${old.index}:back")
                    if(new.commitToSendNs>old.commitToSendNs)add("${old.index}:known_commit_to_send")
                    if(new.commitToAckNs>old.commitToAckNs)add("${old.index}:known_commit_to_ack")
                }
                if(candidate.firstInputToLastAckNs>baseline.firstInputToLastAckNs)add("first_input_to_last_ack")
                if(candidate.peakKnownUnacked>baseline.peakKnownUnacked)add("peak_known_committed_unacked")
            }
            compare(history,"PAIR",2,"pair_summary") {
                put("image_hash",imageHash);put("arms_completed",2);put("actual_inputs",6);put("actual_syncs",6)
                put("prompt_observations_compared",2);put("both_prompt_observations_earlier",promptsEarlier)
                put("cold_recreate_ns",baseline.coldNs);put("cold_retain_ns",candidate.coldNs)
                put("first_input_to_last_ack_recreate_ns",baseline.firstInputToLastAckNs)
                put("first_input_to_last_ack_retain_ns",candidate.firstInputToLastAckNs)
                put("first_input_to_last_ack_delta_ns",candidate.firstInputToLastAckNs-baseline.firstInputToLastAckNs)
                put("last_known_commit_to_last_ack_recreate_ns",baseline.lastCommitToLastAckNs)
                put("last_known_commit_to_last_ack_retain_ns",candidate.lastCommitToLastAckNs)
                put("peak_known_committed_unacked_recreate",baseline.peakKnownUnacked)
                put("peak_known_committed_unacked_retain",candidate.peakKnownUnacked)
                put("positive_endpoint_deltas",JsonArray(tradeoffs.map(::JsonPrimitive)))
                put("adoption_scope","One history only; require both complete histories and assess recorded tradeoffs. Positive deltas are not added test failures.")
                put("same_image_ui_strategies",true);put("original_coordinator_is_component_reference_only",true)
                put("back_removal_scope","Both paired arms include actual current Dialog View detach; legacy standalone wrapper does not.")
            }
            val failures=results.flatMap {(mode,result)->result.failures.map {"${mode.name}:$it"}}
            // A budget failure in the first arm never suppresses the second arm.
            // Only the preserved budgets and already-declared observation targets fail here.
            stage="pair_budget"
            try {
                assertTrue("Preserved paired operation budgets and prompt observation targets: $failures",failures.isEmpty())
            } catch(problem:Throwable) {
                failure(history,stage,problem,"PAIR",6,6,6,failures);throw problem
            }
        } catch(problem:Throwable) {
            // The probe records its own exact functional boundary; do not duplicate it.
            if(!probeRunning && stage!="pair_budget")
                failure(history,stage,problem,activeStrategy,results.size*3,results.size*3,results.size*3,
                    results.flatMap {(mode,result)->result.failures.map {"${mode.name}:$it"}})
            throw problem
        }
    }
}
