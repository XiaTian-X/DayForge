package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.NextReplicaFenceSql
import com.dayforge.data.local.SyncSchemaCallback
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.domain.model.CountHistory
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Two complete physical-device methods, each planned below 120s before execution.
 * Eight/eighty are accepted historical facts spread across two habits and earlier days.
 * One real +1/send/ACK chain is followed by FULL/ROOT then ROOT/FULL closed-image reads.
 * Each read arm has only two samples: no P95, UI, process death, annual, NAS or warm-cache
 * eviction claim. ROOT grants Q read-only output, not cold P-currentness or writable ready.
 */
@RunWith(AndroidJUnit4::class)
class G0CountRootCostTest:G0CountRootFixture() {
    private var rootSchemaInstalled=false
    override fun domainOpeningCallback():RoomDatabase.Callback =
        if(rootSchemaInstalled)super.domainOpeningCallback() else SyncSchemaCallback

    private enum class Arm { FULL, ROOT }
    private data class Day(val date:LocalDate,val habitId:Long,val habitUuid:String,val quantity:Long,
        val storedPolicy:CountDayPolicy?,val effectivePolicy:CountDayPolicy?,val unknown:Boolean,
        val facts:List<CompletionEntity>,val summary:G0CountBusinessBlocks.Summary)
    private data class Measured<T>(val value:T,val start:Long,val end:Long,val resource:Long,
        val trace:G0PhaseTrace,val ledger:G0ColdWorkLedger) {val elapsed:Long get()=end-start}
    private data class Cold(val day:Day,val readNanos:Long,val rootDay:G0CountBusinessBlocks.DayRead?,
        val source:G0CountBlockNodeSource.Stats?)
    private fun now()=SystemClock.elapsedRealtimeNanos()
    private fun log(body:JsonObjectBuilder.()->Unit) {
        val text=buildJsonObject(body).toString()
        check(text.toByteArray(Charsets.UTF_8).size<4000) {"G0_ROOT_MEASURE_LINE_TOO_LARGE"}
        Log.i("G0_ROOT_RECOVERY_MEASURE",text)
    }
    private fun day(history:CountHistory,id:Long,uuid:String,date:LocalDate)=Day(date,id,uuid,
        history.quantities[date] ?: 0L,history.policies[date],
        history.policies[date] ?: if(date==history.today)history.todayPolicy else null,date in history.unknownDates,
        history.completions.filter {it.recordedLocalDate==date.toString()},
        G0CountBusinessBlocks.Summary(history.today,history.todayQuantity,history.completedToday,
            history.firstDate,history.qualifiedDates.size,history.quantities.values.fold(0L,Math::addExact)))
    private fun day(value:G0CountBusinessBlocks.DayRead)=Day(value.date,value.habitId,value.habitUuid,value.quantity,
        value.storedPolicy,value.effectivePolicy,value.unknown,value.facts,value.summary)
    private fun budget(failures:MutableList<String>,label:String,actual:Long,limit:Long) {
        if(actual>limit)failures+="$label=${actual}ns>${limit}ns"
    }

    /** Same authoritative fields as the other cold-owner measurement. Initial native BEGIN
     * precedes Room; its actual END is traced but has no fabricated Room BEGIN partner.
     */
    private fun transactions(history:Int,arm:String,index:Int,stage:String,trace:G0PhaseTrace,
        owner:G0RoomColdOwnerFactory?):Long {
        val spans=synchronized(trace.spans) {trace.spans.toList()}
        val begin=spans.filter {it.phase=="tx_begin_outer"}.sortedBy {it.start}
        val end=spans.filter {it.phase=="tx_end_outer"}.sortedBy {it.start}
        val ownedEnd=if(owner==null)emptyList() else end.filter {
            it.start>=owner.reservationStartedNs && it.end<=owner.endedNs
        }.also {assertEquals("one actual pre-Room reservation END",1,it.size)}
        val ordinary=end-ownedEnd.toSet()
        assertEquals("every ordinary BEGIN has its actual END",begin.size,ordinary.size)
        var maximum=0L
        begin.zip(ordinary).forEachIndexed {ordinal,(a,b)->
            assertTrue(a.ok && b.ok && b.end>a.start)
            maximum=maxOf(maximum,b.end-a.start)
            log {
                put("kind","transaction");put("history",history);put("arm",arm);put("index",index);put("stage",stage)
                put("ordinal",ordinal);put("source","traced_room_pair");put("start_ns",a.start);put("end_ns",b.end)
                put("hold_ns",b.end-a.start);put("begin_call_ns",a.end-a.start);put("end_call_ns",b.end-b.start)
            }
        }
        owner?.let {
            assertTrue(it.reservationStartedNs>0 && it.endedNs>=it.reservationStartedNs)
            assertTrue(ownedEnd.single().ok)
            val hold=it.endedNs-it.reservationStartedNs;maximum=maxOf(maximum,hold)
            log {
                put("kind","transaction");put("history",history);put("arm",arm);put("index",index);put("stage",stage)
                put("source","native_pre_room_reservation");put("start_ns",it.reservationStartedNs);put("end_ns",it.endedNs)
                put("hold_ns",hold);put("observed_actual_end_start_ns",ownedEnd.single().start)
                put("observed_actual_end_end_ns",ownedEnd.single().end)
            }
        }
        return maximum
    }
    private suspend fun <T> measure(history:Int,arm:String,index:Int,stage:String,
        owner:()->G0RoomColdOwnerFactory?={null},body:suspend ()->T):Measured<T> {
        val trace=G0PhaseTrace(driverTiming=false);val ledger=G0ColdWorkLedger();val start=now()
        val result=ledger.run {trace.run(body)};val end=now()
        val resource=transactions(history,arm,index,stage,trace,owner())
        val spans=synchronized(trace.spans) {trace.spans.toList()}
        log {
            put("kind","phase");put("history",history);put("arm",arm);put("index",index);put("stage",stage)
            put("start_ns",start);put("end_ns",end);put("elapsed_ns",end-start);put("transaction_max_ns",resource)
            put("account_wait_ns",trace.nanos("count_account_wait"));put("account_hold_ns",trace.nanos("count_account_hold"))
            put("account_hold_max_ns",spans.filter {it.phase=="count_account_hold"}.maxOfOrNull {it.end-it.start} ?: 0L)
            put("sql_wait_ns",trace.nanos("domain_sql_wait"));put("sql_hold_ns",trace.nanos("domain_sql_hold"))
            put("decrypt_access",trace.count("decrypt_access"));put("decrypt_refresh",trace.count("decrypt_refresh"))
            put("datastore_ns",trace.nanos("datastore_read"))
            put("root_seal_prepare_ns",trace.nanos("root_seal_prepare"));put("root_seal_prepare_calls",trace.count("root_seal_prepare"))
            put("root_header_match_ns",trace.nanos("root_header_match"));put("root_integrity_ns",trace.nanos("root_integrity"))
            put("first_day_ns",trace.nanos("root_cost_first_day"))
            put("block_build_ns",trace.nanos("count_block_build"));put("block_advance_ns",trace.nanos("count_block_advance"))
            put("block_persist_ns",trace.nanos("count_block_persist"));put("node_decode_ns",trace.nanos("count_block_node_decode"))
            put("node_decode_calls",trace.count("count_block_node_decode"));put("nested_phases_not_additive",true)
            put("absolute_cost_not_net_overhead",true)
        }
        log {
            put("kind","sql_work");put("history",history);put("arm",arm);put("index",index);put("stage",stage)
            put("gate_query_attempts",ledger.queries);put("gate_write_attempts",ledger.writes);put("block_node_queries",ledger.blockNodeQueries)
            put("whole_table_queries",buildJsonObject {ledger.wholeTableQueries.forEach {(table,count)->put(table,count)}})
            put("counts_gate_attempts_including_framework",true);put("schema_journal_driver_work_in_wall_time",true)
        }
        return Measured(result,start,end,resource,trace,ledger)
    }
    private suspend fun <T> firstDay(body:suspend ()->T):T =
        requireNotNull(G0PhaseTrace.current.get()).stage("root_cost_first_day",body)

    private suspend fun installRootSchema() {
        assertFalse(rootSchemaInstalled)
        val before=pageNativeState()
        db.withTransaction {
            val sql=db.openHelper.writableDatabase
            sql.execSQL(G0CountBlockSql.createNodes);sql.execSQL(G0CountBlockSql.createRoot)
            sql.execSQL(G0CountRootSql.createSeal);sql.execSQL(G0CountRootSql.createMarker)
            sql.execSQL(G0CountRootSql.initializeMarker)
            G0CountRootSql.dirtyTriggers.values.forEach(sql::execSQL)
            NextReplicaFenceSql.install(sql)
        }
        rootSchemaInstalled=true
        assertEquals(before,pageNativeState().filterKeys {it !in G0CountBlockSql.tables && it !in G0CountRootSql.tables})
    }

    @Test fun eightCrossDayFactsSignedMaintenanceAndPairedColdRead()=runBlocking<Unit> {compare(8)}
    @Test fun eightyCrossDayFactsSignedMaintenanceAndPairedColdRead()=runBlocking<Unit> {compare(80)}

    private suspend fun compare(history:Int) {
        require(history==8 || history==80)
        // The original constructor's stableRows assertion includes every unrelated table.
        // Do not install Root marker until construction and the complete oracle have finished.
        val fixtureStart=now();val seed=seedPendingHouseholdHistory(history);val produced=now()
        val (http,server)=channel {successReply(it)}
        constructAcceptedHouseholdHistory(seed,http);val constructed=now()
        val beforeInitialOracle=pageNativeState()
        val initial=verifyAcceptedHouseholdHistory(seed,http);val verified=now()
        assertEquals(beforeInitialOracle,pageNativeState())
        assertEquals(0,server.requests.count {it.path.endsWith("/push")})
        installRootSchema();val schemaReady=now()
        log {
            put("kind","fixture");put("history",history);put("producer_ns",produced-fixtureStart)
            put("constructed_terminal_ns",constructed-produced);put("complete_original_oracle_ns",verified-constructed)
            put("schema_install_ns",schemaReady-verified);put("historical_http_pushes",0)
            put("past_days_per_object",history/2);put("constructed_fixture",true);put("real_new_commands",1)
        }
        val failures=mutableListOf<String>();val row=seed.habits.first()
        fun unchangedDate()=check(LocalDate.now(seed.zone)==seed.today && ZoneId.systemDefault()==seed.zone) {"G0_ROOT_COST_DATE_CHANGED"}
        unchangedDate()
        val opened=measure(history,"ISSUER",-1,"original_full_initialization") {openIssuer(http,install=false)}
        val store=opened.value
        val initialBuild=measure(history,"ISSUER",-1,"initial_build_and_seal") {store.installIncrementalCountBlocks()}
        val initialHead=head()
        assertEquals(1,store.incrementalFullBuilds);assertEquals(1,store.incrementalImageCaptures)
        assertEquals(1L,initialHead.root.revision);assertEquals(1,initialBuild.trace.count("root_seal_prepare"))
        val save=measure(history,"ISSUER",0,"save") {store.increase(row.uuid)}
        val local=measure(history,"ISSUER",0,"local_correct_day") {householdView(store)}
        assertTrue(local.value.valid());assertEquals(1L,local.value.cards.getValue(row.uuid).history.todayQuantity)
        val sent=measure(history,"ISSUER",0,"send") {requireNotNull(store.send(http,save.value.requestId))}
        val ack=measure(history,"ISSUER",0,"ACK") {store.acknowledge(sent.value)}
        assertEquals(NextOperationAcceptance.COMMITTED,ack.value)
        val terminal=measure(history,"ISSUER",0,"terminal_correct_day") {householdView(store)}
        assertTrue(terminal.value.valid())
        assertEquals(1,server.requests.count {it.path.endsWith("/push")})
        assertEquals(1,store.incrementalFullBuilds);assertEquals(1,store.incrementalImageCaptures);assertEquals(3,store.incrementalUpdates)
        val terminalHead=head();assertEquals(4L,terminalHead.root.revision)
        assertTrue(terminalHead.marker.generation>initialHead.marker.generation)
        val beforeReplay=pageNativeState();val beforeReplayRevision=domainGate.revision
        val replay=measure(history,"ISSUER",0,"replay") {store.acknowledge(sent.value)}
        assertEquals(NextOperationAcceptance.REPLAYED,replay.value)
        assertEquals(beforeReplay,pageNativeState())
        assertEquals("replay has no admitted actual domain changes",beforeReplayRevision,domainGate.revision)
        assertEquals(0,replay.trace.count("root_seal_prepare"))
        assertEquals(terminalHead.root,head().root)
        for(value in listOf(save,sent,ack))assertEquals(1,value.trace.count("root_seal_prepare"))
        budget(failures,"save",save.elapsed,500_000_000L);budget(failures,"ACK",ack.elapsed,500_000_000L)
        budget(failures,"local_read",local.elapsed,700_000_000L)
        budget(failures,"save_to_local_result_including_between_stage_logging",local.end-save.start,700_000_000L)
        budget(failures,"terminal_read",terminal.elapsed,700_000_000L)
        // The established 150ms resource target applies to the warm command/read path.
        // First construction, cold holds and replay are measured without a new budget.
        for((stage,value) in listOf("save" to save,"local" to local,"send" to sent,"ACK" to ack,"terminal" to terminal))
            budget(failures,"issuer/$stage/resource",value.resource,150_000_000L)
        log {
            put("kind","real_chain");put("history",history);put("save_ns",save.elapsed);put("send_ns",sent.elapsed)
            put("ack_ns",ack.elapsed);put("local_read_ns",local.elapsed);put("terminal_read_ns",terminal.elapsed)
            put("root_revision",terminalHead.root.revision);put("generation",terminalHead.marker.generation)
            put("facts",history+1);put("acceptances",history+1);put("outbox",0);put("actual_http_pushes",1)
            put("signature_stage_is_not_total_overhead",true);put("replay_zero_actual_domain_changes",true)
        }

        // Independent original readers and required native metadata run outside every cold
        // measurement. They cannot be supplied as the candidate's restored business state.
        reopenReference();unchangedDate()
        val originalReader=CountHistoryReader(db,tokens,sessions)
        val expected=seed.habits.associate {habit->
            val current=requireNotNull(db.habitDao().getHabitById(habit.id))
            val historyValue=originalReader.read(current,seed.today)
            val card=terminal.value.cards.getValue(habit.uuid)
            assertEquals(current,card.habit);assertEquals(historyValue,card.history)
            assertEquals(initial.getValue(habit.uuid).completions,
                historyValue.completions.filter {it.recordedLocalDate!=seed.today.toString()})
            habit.uuid to historyValue
        }
        val expectedDayRow=sessions.exclusive {db.withTransaction {
            val current=requireNotNull(db.habitDao().getHabitById(row.id))
            NextCountDayStore(db).readForHabit(current).single {it.localDate==seed.today.toString()}
        }}
        assertEquals(save.value.factUuid,expectedDayRow.firstEventUuid)
        assertEquals(save.value.requestId,expectedDayRow.originRequestId)
        val expectedDay=day(expected.getValue(row.uuid),row.id,row.uuid,seed.today)
        assertEquals(1L,expectedDay.quantity)
        val beforeOriginalReplay=pageNativeState()
        assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(sent.value))
        assertEquals(beforeOriginalReplay,pageNativeState())
        assertEquals(history+1,pageRowCount("completions"));assertEquals(history+1,pageRowCount("next_request_origins"))
        assertEquals(history+1,pageRowCount("next_acceptances"));assertEquals(0,pageRowCount("sync_outbox"))
        val identicalNative=pageNativeState();val image=storage.snapshotClosedDatabase()
        var completed=0
        val results=mutableListOf<Day>()
        for((pair,order) in listOf(listOf(Arm.FULL,Arm.ROOT),listOf(Arm.ROOT,Arm.FULL)).withIndex())for(arm in order) {
            unchangedDate();restoreRootImage(image) // Outside the cold window; does not replace key.
            var rootOpened:G0CountRootFixture.RootOpened?=null
            val measured=measure(history,arm.name,pair,"cold_first_correct_day",{rootOpened?.owner}) {
                if(arm==Arm.FULL) {
                    val full=openIssuer(http,install=false)
                    assertEquals(0,full.incrementalFullBuilds);assertEquals(0,full.incrementalImageCaptures)
                    val readRevision=domainGate.revision
                    val start=now();val view=firstDay {householdView(full)};val readNanos=now()-start
                    assertEquals("full READ journal has no admitted actual changes",readRevision,domainGate.revision)
                    assertTrue(view.valid())
                    assertEquals(expected,view.cards.mapValues {it.value.history})
                    Cold(day(view.cards.getValue(row.uuid).history,row.id,row.uuid,seed.today),readNanos,null,null)
                } else {
                    val openedRoot=openRootReader().also {rootOpened=it}
                    val readRevision=domainGate.revision
                    val start=now();val view=firstDay {rootDay(openedRoot.reader,row.uuid,seed.today)};val readNanos=now()-start
                    assertEquals("Q READ journals have no admitted actual changes",readRevision,domainGate.revision)
                    assertTrue(view.valid());assertEquals(terminalHead.root.root.digest,view.rootDigest)
                    Cold(day(view.day),readNanos,view.day,requireNotNull(openedRoot.reader.lastSourceStats))
                }
            }
            assertEquals(expectedDay,measured.value.day);results+=measured.value.day
            measured.value.rootDay?.let {
                assertRootDay(expected.getValue(row.uuid),it)
                assertEquals(expectedDayRow,it.day);assertEquals(setOf(save.value.requestId),it.sourceRequestIds)
            }
            // Ledger writes count SQL attempts, including opening idempotent schema SQL
            // and Room TEMP cleanup. Actual no-write evidence is the closed READ journal,
            // unchanged Gate revision above, and the full persistent native comparison below.
            assertEquals(identicalNative,pageNativeState())
            assertEquals(terminalHead.root,head().root)
            if(arm==Arm.ROOT) {
                assertTrue(measured.ledger.wholeTableQueries.isEmpty())
                val source=requireNotNull(measured.value.source)
                assertTrue(source.queries>0);assertEquals(source.queries,source.blobReads)
                assertEquals(source.queries,measured.ledger.blockNodeQueries)
                assertEquals(0,measured.trace.count("count_complete_history"))
                assertEquals(0,measured.trace.count("count_block_build"))
                assertEquals(0,measured.trace.count("count_block_snapshot"))
            } else {
                assertEquals(0,measured.ledger.blockNodeQueries)
                assertTrue(measured.trace.count("count_complete_history")>0)
            }
            budget(failures,"$pair/$arm/cold_first_correct_day",measured.elapsed,2_000_000_000L)
            budget(failures,"$pair/$arm/read",measured.value.readNanos,700_000_000L)
            completed++
            log {
                put("kind","cold_arm");put("history",history);put("pair",pair);put("arm",arm.name)
                put("cold_ns",measured.elapsed);put("first_day_ns",measured.value.readNanos);put("transaction_max_ns",measured.resource)
                put("same_closed_image",true);put("same_keystore_key",true);put("includes_reference_open_auth_and_day",true)
                put("snapshot_restore_outside_window",true);put("same_process_room_reopen",true);put("os_cache_cleared",false)
                put("source_queries",measured.value.source?.queries?.let(::JsonPrimitive) ?: JsonNull)
                put("source_blob_reads",measured.value.source?.blobReads?.let(::JsonPrimitive) ?: JsonNull)
                put("source_loaded_bytes",measured.value.source?.loadedBytes?.let(::JsonPrimitive) ?: JsonNull)
                put("correct_quantity",measured.value.day.quantity);put("root",terminalHead.root.root.digest)
                put("readonly_q_candidate",arm==Arm.ROOT);put("cold_write_ready_proven",false)
                put("output_scope",if(arm==Arm.ROOT)"one_day_plus_lifetime_summary_Q_readonly" else "two_object_full_view_then_day_projection")
                put("same_day_output",true);put("full_page_or_write_scope_equivalence",false)
            }
        }
        assertEquals(4,completed);assertEquals(4,results.size);assertTrue(results.all {it==expectedDay})
        assertEquals(1,server.requests.count {it.path.endsWith("/push")})
        log {
            put("kind","complete");put("history",history);put("planned_cold_arms",4);put("completed_cold_arms",completed)
            put("real_chains",1);put("real_chain_terminal_and_replayed",true);put("all_read_arms_native_unchanged",true)
            put("samples_per_arm",2);put("p95",false);put("ui",false);put("process_death",false)
            put("annual_history",false);put("background_competition",false);put("network","loopback_synthetic")
            put("performance_failures",JsonArray(failures.map(::JsonPrimitive)))
        }
        // Performance failures are deliberately delayed until every planned arm and terminal
        // replay has been exercised. Security/semantic assertions are never suppressed.
        assertTrue(failures.joinToString("; "),failures.isEmpty())
    }
}
