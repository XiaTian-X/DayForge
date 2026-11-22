package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.dayforge.data.api.dto.NextSyncPushResponse
import com.dayforge.data.local.NextReplicaFenceSql
import com.dayforge.data.local.SyncSchemaCallback
import com.dayforge.domain.model.CountDayPolicy
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*

/** G0 only: a complete cold first command plus two explicitly concurrent ACK/count windows.
 * Original producer and source validator establish the historical fixture; original full
 * Store validation issues the first Current root. Consumers outside this task's contract
 * are not repeatedly invoked as extra fixture preparation. A complete original reader and
 * original replay verify the final state. There is no UI/process-death/NAS/P95 claim. */
abstract class G0CountRootWriteCostFixture:G0CountRootWriteFixture() {
    private var rootSchemaInstalled=false
    override fun domainOpeningCallback():RoomDatabase.Callback =
        if(rootSchemaInstalled)super.domainOpeningCallback() else SyncSchemaCallback
    private data class Measured<T>(val value:T,val start:Long,val end:Long,val resource:Long,
        val trace:G0PhaseTrace,val ledger:G0ColdWorkLedger) {val elapsed:Long get()=end-start}
    private fun now()=SystemClock.elapsedRealtimeNanos()
    private fun log(body:JsonObjectBuilder.()->Unit) {
        val line=buildJsonObject(body).toString()
        check(line.toByteArray(Charsets.UTF_8).size<4000) {"G0_ROOT_WRITE_MEASURE_LINE_TOO_LARGE"}
        Log.i("G0_ROOT_WRITE_MEASURE",line)
    }
    private fun budget(failures:MutableList<String>,label:String,actual:Long,limit:Long) {
        if(actual>limit)failures+="$label=${actual}ns>${limit}ns"
    }
    private fun transactions(history:Int,index:Int,stage:String,trace:G0PhaseTrace,
        owner:G0RoomColdOwnerFactory?):Long {
        val spans=synchronized(trace.spans) {trace.spans.toList()}
        val begin=spans.filter {it.phase=="tx_begin_outer"}.sortedBy {it.start}
        val end=spans.filter {it.phase=="tx_end_outer"}.sortedBy {it.start}
        val ownedEnd=if(owner==null)emptyList() else end.filter {
            it.start>=owner.reservationStartedNs && it.end<=owner.endedNs
        }.also {assertEquals("one actual pre-Room reservation END",1,it.size)}
        val ordinary=end-ownedEnd.toSet()
        assertEquals("every observed ordinary BEGIN has its actual END",begin.size,ordinary.size)
        var maximum=0L
        begin.zip(ordinary).forEachIndexed {ordinal,(a,b)->
            assertTrue(a.ok && b.ok && b.end>a.start);maximum=maxOf(maximum,b.end-a.start)
            log {
                put("kind","transaction");put("history",history);put("index",index);put("stage",stage)
                put("ordinal",ordinal);put("source","traced_room_pair");put("start_ns",a.start);put("end_ns",b.end)
                put("hold_ns",b.end-a.start);put("begin_call_ns",a.end-a.start);put("end_call_ns",b.end-b.start)
            }
        }
        owner?.let {
            assertTrue(it.reservationStartedNs>0 && it.endedNs>=it.reservationStartedNs);assertTrue(ownedEnd.single().ok)
            val hold=it.endedNs-it.reservationStartedNs;maximum=maxOf(maximum,hold)
            log {
                put("kind","transaction");put("history",history);put("index",index);put("stage",stage)
                put("source","native_pre_room_reservation");put("start_ns",it.reservationStartedNs);put("end_ns",it.endedNs)
                put("hold_ns",hold);put("observed_actual_end_start_ns",ownedEnd.single().start)
                put("observed_actual_end_end_ns",ownedEnd.single().end)
            }
        }
        return maximum
    }
    private suspend fun <T> measure(history:Int,index:Int,stage:String,normalPath:Boolean=false,
        owner:()->G0RoomColdOwnerFactory?={null},sourceStats:()->G0CountBlockNodeSource.Stats?={null},
        body:suspend ()->T):Measured<T> {
        val trace=G0PhaseTrace(driverTiming=false);val ledger=G0ColdWorkLedger();val start=now()
        val value=ledger.run {trace.run(body)};val end=now()
        val resource=transactions(history,index,stage,trace,owner())
        if(normalPath)assertNoFullRebuild(trace,ledger)
        val spans=synchronized(trace.spans) {trace.spans.toList()};val last=sourceStats()
        spans.filter {it.phase=="root_account_hold" || it.phase=="root_account_wait" ||
            it.phase=="count_account_hold" || it.phase=="count_account_wait"}.forEachIndexed {ordinal,span->
            log {
                put("kind","account");put("history",history);put("index",index);put("stage",stage)
                put("ordinal",ordinal);put("phase",span.phase);put("start_ns",span.start);put("end_ns",span.end)
                put("elapsed_ns",span.end-span.start);put("returned",span.ok)
            }
        }
        log {
            put("kind","phase");put("history",history);put("index",index);put("stage",stage)
            put("start_ns",start);put("end_ns",end);put("elapsed_ns",end-start);put("known_return_ns",end)
            put("actual_transaction_record_count",spans.count {it.phase=="tx_end_outer"})
            put("account_record_count",spans.count {it.phase=="root_account_hold" || it.phase=="root_account_wait" ||
                it.phase=="count_account_hold" || it.phase=="count_account_wait"})
            put("transaction_max_ns",resource);put("root_account_wait_ns",trace.nanos("root_account_wait"))
            put("root_account_hold_ns",trace.nanos("root_account_hold"));put("root_account_hold_max_ns",
                spans.filter {it.phase=="root_account_hold"}.maxOfOrNull {it.end-it.start} ?: 0L)
            put("original_account_hold_ns",trace.nanos("count_account_hold"))
            put("sql_wait_ns",trace.nanos("domain_sql_wait"));put("sql_hold_ns",trace.nanos("domain_sql_hold"))
            put("decrypt_access",trace.count("decrypt_access"));put("decrypt_refresh",trace.count("decrypt_refresh"))
            put("datastore_ns",trace.nanos("datastore_read"));put("root_seal_prepare_ns",trace.nanos("root_seal_prepare"))
            put("root_seal_prepare_calls",trace.count("root_seal_prepare"));put("root_write_advance_ns",trace.nanos("root_write_advance"))
            put("root_header_match_ns",trace.nanos("root_header_match"));put("root_integrity_ns",trace.nanos("root_integrity"))
            put("node_decode_calls",trace.count("count_block_node_decode"));put("normal_path",normalPath)
            put("full_history_calls",trace.count("count_complete_history"));put("nested_phases_not_additive",true)
            put("last_source_queries",last?.queries?.let(::JsonPrimitive) ?: JsonNull)
            put("last_source_bytes",last?.loadedBytes?.let(::JsonPrimitive) ?: JsonNull)
            put("source_stats_last_call_only",true)
            put("shared_source_stats_omitted_during_competition",stage.endsWith("_competing"))
        }
        log {
            put("kind","sql_work");put("history",history);put("index",index);put("stage",stage)
            put("gate_query_attempts",ledger.queries);put("gate_write_attempts",ledger.writes);put("block_node_queries",ledger.blockNodeQueries)
            put("whole_table_queries",buildJsonObject {ledger.wholeTableQueries.forEach {(table,count)->put(table,count)}})
            put("counts_gate_attempts_including_framework",true);put("schema_journal_driver_work_in_wall_time",true)
        }
        return Measured(value,start,end,resource,trace,ledger)
    }
    private suspend fun installRootSchema() {
        assertFalse(rootSchemaInstalled);val before=pageNativeState()
        db.withTransaction {
            val sql=db.openHelper.writableDatabase
            sql.execSQL(G0CountBlockSql.createNodes);sql.execSQL(G0CountBlockSql.createRoot)
            sql.execSQL(G0CountRootSql.createSeal);sql.execSQL(G0CountRootSql.createMarker);sql.execSQL(G0CountRootSql.initializeMarker)
            G0CountRootSql.dirtyTriggers.values.forEach(sql::execSQL)
            G0CountSourceScopeSql.requireCapability(sql)
            G0CountNativeRows.createIndexes.forEach(sql::execSQL);NextReplicaFenceSql.install(sql)
        }
        rootSchemaInstalled=true
        assertEquals(before,pageNativeState().filterKeys {it !in G0CountBlockSql.tables && it !in G0CountRootSql.tables})
    }

    protected suspend fun rootWriteJourney(history:Int) {
        require(history in setOf(8,80,730))
        val caseStart=now();val failures=mutableListOf<String>()
        val produced=measure(history,-1,"historical_original_producer") {seedPendingHouseholdHistory(history)}
        val seed=produced.value;val (http,server)=channel {successReply(it)}
        val constructed=measure(history,-1,"historical_construct_and_original_sources") {constructAcceptedHouseholdHistory(seed,http)}
        assertEquals(0,server.requests.count {it.path.endsWith("/push")})
        fun sameDate()=check(LocalDate.now(seed.zone)==seed.today && ZoneId.systemDefault()==seed.zone) {"G0_ROOT_WRITE_COST_DATE_CHANGED"}
        sameDate()
        measure(history,-1,"schema_install") {installRootSchema()}
        val capacity=if(history==730)G0HouseholdCountStore.CapacityProfile.ANNUAL_730_PLUS_6 else G0HouseholdCountStore.CapacityProfile.DEFAULT_128
        val initialized=measure(history,-1,"initial_complete_original_validation") {issueWriteRoot(http,capacity=capacity,install=false)}
        val issuer=initialized.value
        // Complete original Store validation just ran. Check all original histories against
        // the independent producer's prescribed cross-day fixture; don't reread unrelated UI consumers.
        val initialView=householdView(issuer)
        assertTrue(initialView.valid())
        for(habit in seed.habits) {
            val expected=seed.entries.filter {it.fact.habitUuid==habit.uuid};val read=initialView.cards.getValue(habit.uuid).history
            val dates=expected.map {LocalDate.parse(it.fact.recordedLocalDate)}.toSet()
            assertEquals(expected.map {it.fact.uuid}.toSet(),read.completions.map {it.uuid}.toSet())
            assertEquals(expected.size,read.completions.size);assertEquals(dates.associateWith {1L},read.quantities)
            assertEquals(dates.associateWith {CountDayPolicy(habit.targetValue,false)},read.policies)
            assertEquals(0L,read.todayQuantity);assertTrue(read.unknownDates.isEmpty());assertNull(read.roundHead)
        }
        assertEquals(history,pageRowCount("completions"));assertEquals(history,pageRowCount("next_request_origins"))
        assertEquals(history,pageRowCount("next_acceptances"));assertEquals(0,pageRowCount("sync_outbox"))
        val built=measure(history,-1,"initial_build_and_root_issue") {issuer.installIncrementalCountBlocks()}
        assertEquals(1,issuer.incrementalFullBuilds);assertEquals(1,issuer.incrementalImageCaptures)
        val initialHead=head();assertEquals(1L,initialHead.root.revision)
        assertEquals(1,built.trace.count("root_seal_prepare"))
        log {
            put("kind","fixture");put("history",history);put("objects",2);put("past_days_per_object",history/2)
            put("historical_http_pushes",0);put("constructed_history",true);put("complete_original_validation",true)
            put("original_producer_ns",produced.elapsed);put("constructed_sources_ns",constructed.elapsed)
            put("initial_validation_ns",initialized.elapsed);put("first_build_ns",built.elapsed)
            put("all_first_issue_work_outside_normal_cold",true)
        }
        sameDate()
        var opened:WriterOpened?=null
        val cold=measure(history,0,"normal_cold_ready",normalPath=true,owner={opened?.owner},
            sourceStats={opened?.store?.lastSourceStats}) {openWriter().also {opened=it}}
        val writer=cold.value.store
        assertEquals(0,writer.commits)
        val saved=mutableListOf<G0CountRootStore.Saved>();val deliveries=mutableListOf<NextCoreDelivery<NextSyncPushResponse>>()
        val firstSave=measure(history,0,"save",normalPath=true,sourceStats={writer.lastSourceStats}) {writer.increase(seed.habits.first().uuid)}
        saved+=firstSave.value
        val firstRead=measure(history,0,"correct_result",normalPath=true,sourceStats={writer.lastSourceStats}) {
            writerDay(writer,firstSave.value.habitUuid,seed.today)
        }
        assertEquals(1L,firstRead.value.day.quantity);assertTrue(firstSave.value.factUuid in firstRead.value.day.facts.map {it.uuid})
        budget(failures,"cold/ready",cold.elapsed,2_000_000_000L)
        budget(failures,"cold/to_first_correct_result",firstRead.end-cold.start,2_000_000_000L)
        budget(failures,"0/save",firstSave.elapsed,500_000_000L)
        budget(failures,"0/save_to_result",firstRead.end-firstSave.start,700_000_000L)
        budget(failures,"0/read",firstRead.elapsed,700_000_000L)
        budget(failures,"0/save/resource",firstSave.resource,150_000_000L)
        budget(failures,"0/read/resource",firstRead.resource,150_000_000L)
        log {
            put("kind","cold_first_command");put("history",history);put("ready_ns",cold.elapsed)
            put("cold_to_saved_ns",firstSave.end-cold.start);put("cold_to_result_ns",firstRead.end-cold.start)
            put("saved_request",firstSave.value.requestId);put("quantity",firstRead.value.day.quantity)
            put("normal_close_reopen",true);put("os_cache_cleared",false);put("full_history_on_first_click",false)
        }
        var pendingSend=measure(history,0,"send",normalPath=true,sourceStats={writer.lastSourceStats}) {
            requireNotNull(writer.send(http,firstSave.value.requestId))
        }
        budget(failures,"0/send/resource",pendingSend.resource,150_000_000L)
        deliveries+=pendingSend.value
        var overlappingWindows=0
        repeat(2) {window->
            sameDate();val index=window+1;val prior=deliveries.last()
            val release=CompletableDeferred<Unit>()
            lateinit var foreground:Measured<G0CountRootStore.Saved>
            lateinit var background:Measured<NextOperationAcceptance>
            val arrival=coroutineScope {
                val front=async(Dispatchers.Default) {
                    release.await()
                    measure(history,index,"save_competing",normalPath=true) {
                        writer.increase(seed.habits.first().uuid)
                    }
                }
                val back=async(Dispatchers.Default) {
                    release.await()
                    measure(history,window,"ACK_competing",normalPath=true) {writer.acknowledge(prior)}
                }
                val started=now();release.complete(Unit)
                foreground=front.await();background=back.await();started
            }
            assertEquals(NextOperationAcceptance.COMMITTED,background.value);saved+=foreground.value
            // A result after BOTH commits is an explicit joint-completion contract. There
            // is no string-caught stale publication and no hidden retry of readDay.
            val result=measure(history,index,"joint_correct_result",normalPath=true,sourceStats={writer.lastSourceStats}) {
                writerDay(writer,foreground.value.habitUuid,seed.today)
            }
            assertEquals((index+1).toLong(),result.value.day.quantity)
            assertEquals(saved.map {it.factUuid}.toSet(),result.value.day.facts.map {it.uuid}.toSet())
            budget(failures,"$index/arrival_to_saved",foreground.end-arrival,500_000_000L)
            budget(failures,"$index/arrival_to_joint_result",result.end-arrival,700_000_000L)
            budget(failures,"$window/ACK",background.elapsed,500_000_000L)
            budget(failures,"$index/read",result.elapsed,700_000_000L)
            for((stage,value) in listOf("save" to foreground,"ACK" to background,"read" to result))
                budget(failures,"$index/$stage/resource",value.resource,150_000_000L)
            val overlapped=maxOf(foreground.start,background.start)<minOf(foreground.end,background.end)
            if(overlapped)overlappingWindows++
            log {
                put("kind","competition");put("history",history);put("window",window);put("arrival_ns",arrival)
                put("save_start_ns",foreground.start);put("save_end_ns",foreground.end)
                put("ack_start_ns",background.start);put("ack_end_ns",background.end);put("result_end_ns",result.end)
                put("arrival_to_saved_ns",foreground.end-arrival);put("arrival_to_result_ns",result.end-arrival)
                put("call_windows_overlap",overlapped)
                put("same_owner",true);put("result_intentionally_waits_for_both",true);put("quantity",result.value.day.quantity)
            }
            pendingSend=measure(history,index,"send",normalPath=true,sourceStats={writer.lastSourceStats}) {
                requireNotNull(writer.send(http,foreground.value.requestId))
            }
            budget(failures,"$index/send/resource",pendingSend.resource,150_000_000L);deliveries+=pendingSend.value
        }
        val drain=measure(history,2,"ACK_final_drain",normalPath=true,sourceStats={writer.lastSourceStats}) {writer.acknowledge(deliveries.last())}
        assertEquals(NextOperationAcceptance.COMMITTED,drain.value)
        budget(failures,"2/ACK",drain.elapsed,500_000_000L);budget(failures,"2/ACK/resource",drain.resource,150_000_000L)
        val terminal=measure(history,2,"terminal_correct_result",normalPath=true,sourceStats={writer.lastSourceStats}) {
            writerDay(writer,seed.habits.first().uuid,seed.today)
        }
        assertEquals(3L,terminal.value.day.quantity);assertTrue(terminal.value.day.summary.completedToday)
        budget(failures,"terminal/read",terminal.elapsed,700_000_000L);budget(failures,"terminal/resource",terminal.resource,150_000_000L)
        assertEquals(9,writer.commits);assertTrue(writer.pendingRequestIds().isEmpty());assertEquals(10L,head().root.revision)
        val native=pageNativeState();val beforeReplayRevision=domainGate.revision
        deliveries.forEachIndexed {index,delivery->
            val replay=measure(history,index,"replay",normalPath=true) {writer.acknowledge(delivery)}
            assertEquals(NextOperationAcceptance.REPLAYED,replay.value)
            assertEquals(0,replay.trace.count("root_seal_prepare"));assertEquals(beforeReplayRevision,domainGate.revision)
            assertEquals(native,pageNativeState())
        }
        assertEquals(history+3,pageRowCount("completions"));assertEquals(history+3,pageRowCount("next_request_origins"))
        assertEquals(history+3,pageRowCount("next_acceptances"));assertEquals(0,pageRowCount("sync_outbox"))
        assertEquals(3,server.requests.count {it.path.endsWith("/push")})
        sameDate()
        measure(history,-1,"final_complete_original_reader") {assertWriterMatchesOriginal(writer,seed.today)}
        deliveries.forEach {assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(it))}
        assertEquals(native,pageNativeState());assertEquals(10L,head().root.revision)
        log {
            put("kind","complete");put("history",history);put("real_saved",3);put("real_sent",3);put("real_acks",3)
            put("candidate_replays",3);put("original_replays",3);put("attempted_concurrent_windows",2)
            put("overlapping_call_windows",overlappingWindows)
            put("facts",history+3);put("origins",history+3);put("acceptances",history+3);put("outbox",0)
            put("original_full_reader_equal",true);put("native_terminal_unchanged_by_oracles",true)
            put("whole_case_to_terminal_ns",now()-caseStart);put("p95",false);put("ui",false);put("process_death",false)
            put("network","loopback_synthetic");put("steady_state_backlog_claim",false)
            put("performance_failures",JsonArray(failures.map(::JsonPrimitive)))
        }
        // Preserve the entire terminal and all timing records even if the scheduler did
        // not actually overlap both call windows; such a run cannot pass competition quality.
        assertEquals("both same-owner count/ACK call windows must actually overlap",2,overlappingWindows)
        // All operation, replay and independent final oracle assertions precede this gate.
        assertTrue(failures.joinToString("; "),failures.isEmpty())
    }
}
