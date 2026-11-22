package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.NextSyncPushResponse
import com.dayforge.data.local.HabitDatabase
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

/** The ordinary G0 Store is the main baseline: it does not rebuild B1 blocks after every write.
 * Eight-history arms restore one shared primary image. Eighty-history arms are separate test
 * methods with independently constructed and fully verified inputs of the same distribution;
 * their rows/clocks are not one image, and their timings cannot be reported as paired deltas.
 * Four A/B/A/B real commands per arm, sequential loopback send/ACK, no UI or background load.
 * Historical terminal rows use the previously differential-tested constructed fixture; all new
 * commands use real producer/kernel/send/ACK paths. Small samples are not P95 or cold recovery.
 */
@RunWith(AndroidJUnit4::class)
class G0CountIncrementalCostTest:G0HouseholdHistoryFixture() {
    private data class Timed<T>(val value:T,val start:Long,val end:Long,val resource:Long,
        val accountMax:Long,val sqlMax:Long) {val elapsed:Long get()=end-start}
    private data class Observed(val date:LocalDate,val habitUuid:String,val quantity:Long,
        val storedPolicy:CountDayPolicy?,val effectivePolicy:CountDayPolicy?,val facts:List<CompletionEntity>,
        val summary:G0CountBusinessBlocks.Summary,val sourceIds:Set<String>?,val root:String?,
        val work:G0CountBusinessBlocks.ReadStats?,val stillValid:()->Boolean)
    private data class Counters(val builds:Int,val captures:Int,val updates:Int,val nodes:Long,val bytes:Long)
    private data class Storage(val nodes:Long,val bytes:Long,val revision:Long?,val root:String?,
        val mainBytes:Long,val walBytes:Long)
    private fun now()=SystemClock.elapsedRealtimeNanos()
    private fun log(body:JsonObjectBuilder.()->Unit)=Log.i("G0_COUNT_INCREMENTAL_MEASURE",buildJsonObject(body).toString())
    private fun counters(store:G0HouseholdCountStore?)=store?.let {
        Counters(it.incrementalFullBuilds,it.incrementalImageCaptures,it.incrementalUpdates,
            it.incrementalWrittenNodes,it.incrementalWrittenBytes)
    } ?: Counters(0,0,0,0,0)

    private suspend fun <T> timed(history:Int,arm:String,index:Int,stage:String,
        store:G0HouseholdCountStore?=null,body:suspend ()->T):Timed<T> {
        val trace=G0PhaseTrace(driverTiming=false);val before=counters(store);val started=now()
        var ended=0L;var returned=false;var observedWork:G0CountBusinessBlocks.ReadStats?=null
        try {
            val result=trace.run(body);ended=now();returned=true
            observedWork=(result as? Observed)?.work
            val rows=synchronized(trace.spans) {trace.spans.toList()}
            return Timed(result,started,ended,transactionHold(trace),
                rows.filter {it.phase=="count_account_hold"}.maxOfOrNull {it.end-it.start} ?: 0L,
                rows.filter {it.phase=="domain_sql_hold"}.maxOfOrNull {it.end-it.start} ?: 0L)
        } finally {
            if(ended==0L)ended=now()
            val after=counters(store);val spans=synchronized(trace.spans) {trace.spans.toList()}
            val begins=spans.filter {it.phase=="tx_begin_outer"}.sortedBy {it.start}
            val ends=spans.filter {it.phase=="tx_end_outer"}.sortedBy {it.start}
            val update=if(after.updates>before.updates)store?.incrementalLastUpdateStats else null
            log {
                put("kind","phase");put("history",history);put("arm",arm);put("index",index);put("stage",stage)
                put("paired_primary_image",history==8);put("independent_distribution_only",history==80)
                put("start_ns",started);put("end_ns",ended);put("elapsed_ns",ended-started);put("returned",returned)
                put("outer_begin_count",begins.size);put("outer_end_count",ends.size)
                put("transactions",JsonArray(begins.zip(ends).map {(a,b)->buildJsonObject {
                    put("start_ns",a.start);put("end_ns",b.end);put("begin_through_end_ns",b.end-a.start)
                    put("begin_call_ns",a.end-a.start);put("end_call_ns",b.end-b.start);put("ok",a.ok && b.ok)
                }}))
                put("transaction_max_ns",begins.zip(ends).maxOfOrNull {(a,b)->b.end-a.start} ?: 0L)
                put("account_wait_ns",trace.nanos("count_account_wait"));put("account_hold_total_ns",trace.nanos("count_account_hold"))
                put("account_hold_max_ns",spans.filter {it.phase=="count_account_hold"}.maxOfOrNull {it.end-it.start} ?: 0L)
                put("sql_wait_ns",trace.nanos("domain_sql_wait"));put("sql_hold_total_ns",trace.nanos("domain_sql_hold"))
                put("sql_hold_max_ns",spans.filter {it.phase=="domain_sql_hold"}.maxOfOrNull {it.end-it.start} ?: 0L)
                put("decrypt_access",trace.count("decrypt_access"));put("decrypt_refresh",trace.count("decrypt_refresh"))
                put("datastore_ns",trace.nanos("datastore_read"))
                for(phase in listOf("count_block_snapshot","count_block_build","count_block_advance","count_block_persist","count_block_map_copy"))
                    put(phase+"_ns",trace.nanos(phase))
                put("send_prepare_commit_ns",trace.nanos("count_send_prepare_commit"));put("send_push_ns",trace.nanos("count_send_push"))
                put("send_after_response_ns",trace.nanos("count_send_after_response"))
                put("full_build_delta",after.builds-before.builds);put("image_capture_delta",after.captures-before.captures)
                put("update_delta",after.updates-before.updates);put("written_nodes_delta",after.nodes-before.nodes)
                put("written_bytes_delta",after.bytes-before.bytes)
                put("delta_stats",update?.let {s->buildJsonObject {
                    put("loaded_nodes",s.loadedNodes);put("loaded_bytes",s.loadedBytes);put("node_requests",s.nodeRequests)
                    put("processed_node_bytes",s.processedNodeBytes);put("new_nodes",s.newNodes);put("new_bytes",s.newBytes)
                    put("unit_edits",s.unitEdits);put("top_level_edits",s.topLevelEdits)
                    put("changed_units",JsonArray(s.changedUnits.sorted().map(::JsonPrimitive)))
                }} ?: JsonNull)
                put("read_work",observedWork?.let {w->buildJsonObject {
                    put("loaded_nodes",w.loadedNodes);put("loaded_bytes",w.loadedBytes);put("node_requests",w.nodeRequests)
                    put("processed_node_bytes",w.processedNodeBytes);put("decoded_facts",w.decodedFacts);put("decoded_sources",w.decodedSources)
                    put("loader","in_memory_content_map");put("cold_recovery",false)
                }} ?: JsonNull)
                put("ui",false);put("background_competition",false);put("network","loopback_synthetic");put("p95",false)
            }
            val detail=spans.filter {it.phase.startsWith("count_block_")}
            if(detail.isNotEmpty())log {
                put("kind","block_detail");put("history",history);put("arm",arm);put("index",index);put("stage",stage)
                put("nested_not_additive",true)
                put("phases",buildJsonObject {
                    for((name,values) in detail.groupBy {it.phase})put(name,buildJsonObject {
                        put("calls",values.size);put("wall_ns",values.sumOf {it.end-it.start})
                        put("cpu_ns",if(values.all {it.cpuNanos!=null})JsonPrimitive(values.sumOf {requireNotNull(it.cpuNanos)}) else JsonNull)
                        put("ok",values.all {it.ok})
                    })
                })
            }
            if(returned) {
                assertEquals("A measured stage lost an actual outer END",begins.size,ends.size)
                assertTrue(begins.zip(ends).all {(a,b)->a.ok && b.ok && b.end>a.start})
            }
        }
    }

    /** Both table declarations and fence SQL come from the trusted test profile, never from disk. */
    private fun blockCallback()=object:RoomDatabase.Callback() {
        override fun onCreate(db:SupportSQLiteDatabase) {
            SyncSchemaCallback.onCreate(db)
            db.execSQL(G0CountBlockSql.createNodes);db.execSQL(G0CountBlockSql.createRoot)
            NextReplicaFenceSql.install(db)
        }
        override fun onOpen(db:SupportSQLiteDatabase)=SyncSchemaCallback.onOpen(db)
    }
    private suspend fun prepareBlockSchema() {
        val before=pageNativeState()
        db.withTransaction {
            val sql=db.openHelper.writableDatabase
            sql.execSQL(G0CountBlockSql.createNodes);sql.execSQL(G0CountBlockSql.createRoot)
            NextReplicaFenceSql.install(sql)
        }
        assertEquals(before,pageNativeState().filterKeys {it !in G0CountBlockSql.tables})
    }
    private suspend fun blockSchema():List<List<String?>> {
        val reference=Room.inMemoryDatabaseBuilder(app,HabitDatabase::class.java).addCallback(blockCallback()).build()
        return try {
            reference.openHelper.writableDatabase
            reference.withTransaction {G0DomainJournal.trustedSchema(reference.openHelper.writableDatabase)}
        } finally {reference.close()}
    }
    private suspend fun openIncremental(http:NextSyncHttp):G0HouseholdCountStore {
        val schema=blockSchema()
        freshGateForColdOpen(recurringReads=true,countWrites=true,householdCountWrites=true,countBlockScope=true)
        storage.reopen()
        val expected=currentHousehold.map {requireNotNull(db.habitDao().getHabitById(it.id))}
        return G0HouseholdCountStore(db,tokens,sessions,authority,domainGate,sender(http),
            jointAuthority=true,incrementalBlockScope=true).also {it.initialize(expected,schema)}
    }
    private fun storageState(candidate:Boolean):Storage {
        val file=app.getDatabasePath("habit_database")
        if(!candidate)return Storage(0,0,null,null,file.length(),java.io.File(file.path+"-wal").length())
        return SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use {raw->
            val size=raw.rawQuery("SELECT count(*),COALESCE(sum(length(body)),0) FROM g0_count_block_nodes",null).use {c->
                assertTrue(c.moveToFirst());c.getLong(0) to c.getLong(1)
            }
            val root=raw.rawQuery("SELECT digest,revision FROM g0_count_block_root WHERE slot=1",null).use {c->
                assertTrue(c.moveToFirst());assertEquals(Cursor.FIELD_TYPE_STRING,c.getType(0));assertEquals(Cursor.FIELD_TYPE_INTEGER,c.getType(1))
                (c.getString(0) to c.getLong(1)).also {assertFalse(c.moveToNext())}
            }
            Storage(size.first,size.second,root.second,root.first,file.length(),java.io.File(file.path+"-wal").length())
        }
    }
    private suspend fun observed(store:G0HouseholdCountStore,candidate:Boolean,uuid:String,date:LocalDate):Observed {
        if(candidate) {
            var result:G0HouseholdCountStore.IncrementalView?=null
            store.readIncrementalCountDay(uuid,date) {result=it}
            val view=requireNotNull(result);val day=view.day;assertTrue(view.valid())
            return Observed(date,uuid,day.quantity,day.storedPolicy,day.effectivePolicy,day.facts,day.summary,
                day.sourceRequestIds,view.rootDigest,day.stats,view::valid)
        }
        val view=householdView(store);assertTrue(view.valid());val history=view.cards.getValue(uuid).history
        val summary=G0CountBusinessBlocks.Summary(history.today,history.todayQuantity,history.completedToday,
            history.firstDate,history.qualifiedDates.size,history.quantities.values.fold(0L,Math::addExact))
        return Observed(date,uuid,history.quantities[date] ?: 0L,history.policies[date],
            history.policies[date] ?: if(date==history.today)history.todayPolicy else null,
            history.completions.filter {it.recordedLocalDate==date.toString()},summary,null,null,null,view::valid)
    }
    private fun assertDay(expected:CountHistory,actual:Observed) {
        assertEquals(expected.quantities[actual.date] ?: 0L,actual.quantity)
        assertEquals(expected.policies[actual.date],actual.storedPolicy)
        assertEquals(expected.policies[actual.date] ?: if(actual.date==expected.today)expected.todayPolicy else null,actual.effectivePolicy)
        assertEquals(expected.completions.filter {it.recordedLocalDate==actual.date.toString()},actual.facts)
        assertEquals(G0CountBusinessBlocks.Summary(expected.today,expected.todayQuantity,expected.completedToday,
            expected.firstDate,expected.qualifiedDates.size,expected.quantities.values.fold(0L,Math::addExact)),actual.summary)
    }
    private fun budgets(failures:MutableList<String>,label:String,write:Timed<*>,read:Timed<*>,
        send:Timed<*>,ack:Timed<*>,terminal:Timed<*>,localResult:Long) {
        if(write.elapsed>500_000_000L)failures+="$label save >500ms"
        if(read.elapsed>700_000_000L)failures+="$label local read >700ms"
        if(localResult>700_000_000L)failures+="$label save-to-correct-day >700ms"
        if(ack.elapsed>500_000_000L)failures+="$label ACK >500ms"
        if(terminal.elapsed>700_000_000L)failures+="$label terminal read >700ms"
        for((name,value) in listOf("save" to write,"read" to read,"send" to send,"ACK" to ack,"terminal" to terminal)) {
            if(value.resource>150_000_000L)failures+="$label $name transaction resource >150ms"
        }
        // Account and SQL-owner maxima are recorded separately, without redefining
        // the established begin-through-actual-END 150ms comparison here.
    }

    @Test fun eightAcceptedFactsOrdinaryStoreVersusAtomicIncrementalRoots()=runBlocking<Unit> {
        compare(8,listOf(false,true))
    }
    @Test fun eightyAcceptedFactsOrdinaryStoreOnIndependentlyVerifiedHistory()=runBlocking<Unit> {
        compare(80,listOf(false))
    }
    @Test fun eightyAcceptedFactsAtomicIncrementalRootsOnIndependentlyVerifiedHistory()=runBlocking<Unit> {
        compare(80,listOf(true))
    }

    private suspend fun compare(history:Int,arms:List<Boolean>) {
        check((history==8 && arms==listOf(false,true)) || (history==80 && arms.size==1))
        val paired=history==8
        val fixtureStart=now();val seed=seedPendingHouseholdHistory(history);val produced=now()
        val (historyHttp,historyServer)=channel {successReply(it)}
        constructAcceptedHouseholdHistory(seed,historyHttp);val constructed=now()
        val beforeOracle=pageNativeState();val initial=verifyAcceptedHouseholdHistory(seed,historyHttp);val verified=now()
        assertEquals(beforeOracle,pageNativeState());assertEquals(0,historyServer.requests.count {it.path.endsWith("/push")})
        val image=storage.snapshotClosedDatabase();val sourceImageReady=now()
        log {
            put("kind","fixture");put("history",history);put("fixture",seed.label)
            put("producer_ns",produced-fixtureStart);put("construct_ns",constructed-produced);put("oracle_ns",verified-constructed)
            put("snapshot_ns",sourceImageReady-verified);put("total_ns",sourceImageReady-fixtureStart)
            put("historical_http_pushes",0);put("past_days_per_object",history/2);put("same_primary_image",paired)
            put("independent_distribution_only",!paired);put("planned_arms",JsonArray(arms.map {JsonPrimitive(if(it)"atomic_blocks" else "ordinary_store")}))
            put("today",seed.today.toString());put("zone",seed.zone.id)
        }
        val failures=mutableListOf<String>();var completedArms=0
        for(candidate in arms) {
            val arm=if(candidate)"atomic_blocks" else "ordinary_store"
            val setup=timed(history,arm,-1,"restore_and_schema") {
                freshGateForColdOpen();restoreForJourney(image)
                if(candidate)prepareBlockSchema()
                channel {successReply(it)}
            }
            val (http,server)=setup.value
            val cold=timed(history,arm,-1,"original_cold_validation") {
                if(candidate)openIncremental(http) else openHousehold(http)
            }
            val store=cold.value
            val install=if(candidate)timed(history,arm,-1,"first_block_build_and_install",store) {store.installIncrementalCountBlocks()} else null
            if(candidate) {assertEquals(1,store.incrementalFullBuilds);assertEquals(1,store.incrementalImageCaptures);assertEquals(0,store.incrementalUpdates)}
            val opening=timed(history,arm,-1,"first_correct_day",store) {observed(store,candidate,seed.habits[0].uuid,seed.today)}
            assertDay(initial.getValue(seed.habits[0].uuid),opening.value)
            val storedBefore=storageState(candidate)
            val deliveries=mutableListOf<NextCoreDelivery<NextSyncPushResponse>>()
            val saved=mutableListOf<G0HouseholdCountStore.Saved>();val finalDays=linkedMapOf<String,Observed>()
            repeat(4) {index->
                check(LocalDate.now(seed.zone)==seed.today && ZoneId.systemDefault()==seed.zone) {"G0_INCREMENTAL_COST_DATE_CHANGED"}
                val row=seed.habits[index%2];val input=now()
                val write=timed(history,arm,index,"save",store) {store.increase(row.uuid)}
                saved+=write.value
                val read=timed(history,arm,index,"local_correct_day",store) {observed(store,candidate,row.uuid,seed.today)}
                val localEnd=now()
                val send=timed(history,arm,index,"send",store) {requireNotNull(store.send(http,write.value.requestId))}
                assertFalse(read.value.stillValid())
                val ack=timed(history,arm,index,"ACK",store) {store.acknowledge(send.value)}
                assertEquals(NextOperationAcceptance.COMMITTED,ack.value)
                val terminal=timed(history,arm,index,"terminal_correct_day",store) {observed(store,candidate,row.uuid,seed.today)}
                val chainEnd=now();deliveries+=send.value;finalDays[row.uuid]=terminal.value
                val expectedQuantity=(index/2+1).toLong()
                assertEquals(expectedQuantity,read.value.quantity);assertEquals(expectedQuantity,terminal.value.quantity)
                assertEquals(read.value.storedPolicy,terminal.value.storedPolicy);assertEquals(read.value.effectivePolicy,terminal.value.effectivePolicy)
                assertEquals(read.value.summary,terminal.value.summary)
                assertEquals(read.value.facts.map {it.uuid},terminal.value.facts.map {it.uuid})
                for(prior in read.value.facts) {
                    val accepted=terminal.value.facts.single {it.uuid==prior.uuid}
                    assertEquals(prior.copy(createdAt=accepted.createdAt),accepted)
                }
                assertEquals(write.value.factUuid,terminal.value.facts.single {it.id==write.value.factId}.uuid)
                if(candidate) {
                    assertNotEquals(read.value.root,terminal.value.root)
                    assertEquals(saved.filter {it.habitUuid==row.uuid}.map {it.requestId}.toSet(),terminal.value.sourceIds)
                    assertEquals(1,store.incrementalFullBuilds);assertEquals(1,store.incrementalImageCaptures)
                    assertEquals((index+1)*3,store.incrementalUpdates)
                }
                log {
                    put("kind","chain");put("history",history);put("arm",arm);put("index",index);put("object_slot",index%2)
                    put("paired_primary_image",paired);put("independent_distribution_only",!paired)
                    put("request_id",write.value.requestId);put("fact_uuid",write.value.factUuid)
                    put("save_ns",write.elapsed);put("local_read_ns",read.elapsed);put("local_result_ns",localEnd-input)
                    put("send_ns",send.elapsed);put("ack_ns",ack.elapsed);put("terminal_read_ns",terminal.elapsed);put("chain_ns",chainEnd-input)
                    put("input_ns",input);put("local_result_at_ns",localEnd);put("terminal_result_at_ns",chainEnd)
                    put("before_ack_root",read.value.root?.let(::JsonPrimitive) ?: JsonNull)
                    put("after_ack_root",terminal.value.root?.let(::JsonPrimitive) ?: JsonNull)
                    put("account_hold_max_ns",listOf(write,read,send,ack,terminal).maxOf {it.accountMax})
                    put("sql_hold_max_ns",listOf(write,read,send,ack,terminal).maxOf {it.sqlMax})
                    put("quantity",terminal.value.quantity);put("phase_logs_between_stages_included",true)
                    put("ui",false);put("background_competition",false)
                }
                budgets(failures,"$arm/$index",write,read,send,ack,terminal,localEnd-input)
            }
            assertEquals(4,server.requests.count {it.path.endsWith("/push")})
            val full=householdView(store);val native=pageNativeState();val storedAfter=storageState(candidate)
            for((index,delivery) in deliveries.withIndex()) {
                val replay=timed(history,arm,index,"replay",store) {store.acknowledge(delivery)}
                assertEquals(NextOperationAcceptance.REPLAYED,replay.value)
            }
            assertEquals(native,pageNativeState())
            val afterReplay=storageState(candidate)
            assertEquals(storedAfter.copy(mainBytes=afterReplay.mainBytes,walBytes=afterReplay.walBytes),afterReplay)
            if(candidate) {
                assertEquals(1,store.incrementalFullBuilds);assertEquals(1,store.incrementalImageCaptures);assertEquals(12,store.incrementalUpdates)
                assertEquals(1L,storedBefore.revision);assertEquals(13L,storedAfter.revision)
                assertEquals(finalDays.getValue(seed.habits[1].uuid).root,storedAfter.root)
                assertTrue(storedAfter.nodes>=storedBefore.nodes);assertTrue(storedAfter.bytes>=storedBefore.bytes)
                assertEquals(storedAfter.nodes,store.incrementalWrittenNodes);assertEquals(storedAfter.bytes,store.incrementalWrittenBytes)
            }
            // All independent original readers/replay audits run after the measured warm
            // sequence. They cannot prewarm or replace each operation's candidate work.
            val oracleStart=now();reopenReference()
            check(LocalDate.now(seed.zone)==seed.today && ZoneId.systemDefault()==seed.zone) {"G0_INCREMENTAL_COST_DATE_CHANGED"}
            // These are all original sameHouseholdAsOriginal comparisons, sharing each
            // independent CountHistory result with the additional day/retained-history
            // assertions below instead of reading that same history a second time.
            val home=readHome()
            val memberIds=seed.habits.map {it.uuid}.toSet()
            assertEquals(memberIds,full.cards.keys);assertEquals(memberIds,home.habits.map {it.uuid}.toSet())
            val counts=CountHistoryReader(db,tokens,sessions)
            val widgets=WidgetFactReader(db,tokens,sessions,preferences,counts,CheckHistoryReader(db,tokens,sessions))
            for(row in seed.habits) {
                val current=requireNotNull(db.habitDao().getHabitById(row.id));val card=full.cards.getValue(row.uuid)
                assertEquals(current,card.habit);assertEquals(home.counts.getValue(row.id),card.history)
                val expected=counts.read(current,seed.today);assertEquals(expected,card.history)
                val widget=widgets.read(current)
                assertEquals(widget.count,card.followUp.count);assertEquals(widget.claim.completionUuid,card.followUp.claim.completionUuid)
                assertDay(expected,finalDays.getValue(row.uuid))
                assertEquals(2L,expected.todayQuantity)
                assertEquals(initial.getValue(row.uuid).completions.toSet(),expected.completions.filter {it.recordedLocalDate!=seed.today.toString()}.toSet())
            }
            assertEquals(home.completions.toSet(),full.cards.values.flatMap {it.history.completions}.toSet())
            for(delivery in deliveries)assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(delivery))
            assertEquals(native,pageNativeState());val oracleEnd=now()
            assertEquals(history+4,pageRowCount("completions"));assertEquals(history+4,pageRowCount("next_request_origins"))
            assertEquals(history+4,pageRowCount("next_acceptances"));assertEquals(0,pageRowCount("sync_outbox"))
            assertEquals(history+2,pageRowCount("count_days"));completedArms++
            log {
                put("kind","terminal");put("history",history);put("arm",arm);put("commands",4);put("pushes",4)
                put("facts",history+4);put("receipts",history+4);put("outbox",0);put("same_primary_image",paired)
                put("independent_distribution_only",!paired);put("today",seed.today.toString());put("zone",seed.zone.id)
                put("setup_ns",setup.elapsed);put("original_cold_ns",cold.elapsed)
                put("first_block_install_ns",install?.elapsed ?: 0L);put("first_correct_day_ns",opening.elapsed)
                put("first_ready_total_ns",opening.end-setup.start)
                put("first_ready_selected_stages_ns",setup.elapsed+cold.elapsed+(install?.elapsed ?: 0L)+opening.elapsed)
                put("terminal_original_oracle_ns",oracleEnd-oracleStart)
                put("retained_nodes_before",storedBefore.nodes);put("retained_nodes_after",storedAfter.nodes)
                put("retained_node_bytes_before",storedBefore.bytes);put("retained_node_bytes_after",storedAfter.bytes)
                put("main_file_bytes_before",storedBefore.mainBytes);put("main_file_bytes_after",storedAfter.mainBytes)
                put("wal_bytes_before",storedBefore.walBytes);put("wal_bytes_after",storedAfter.walBytes)
                put("root_revision_before",storedBefore.revision?.let(::JsonPrimitive) ?: JsonNull)
                put("root_revision_after",storedAfter.revision?.let(::JsonPrimitive) ?: JsonNull)
                put("full_builds",store.incrementalFullBuilds);put("image_captures",store.incrementalImageCaptures);put("incremental_updates",store.incrementalUpdates)
                put("node_gc",false);put("full_map_copy_remains",candidate);put("primary_history_work_remains",true)
                put("original_reader_and_replay_verified",true);put("ui",false);put("background_competition",false);put("p95",false)
            }
        }
        assertEquals(arms.size,completedArms)
        log {
            put("kind","budgets");put("history",history);put("completed_arms",completedArms);put("planned_arms",arms.size)
            put("paired_primary_image",paired);put("independent_distribution_only",!paired)
            put("failures",JsonArray(failures.map(::JsonPrimitive)))
        }
        assertTrue("Original 500/700/150ms component budgets; all planned arms completed, not UI/P95: "+failures.joinToString(";"),failures.isEmpty())
    }
}
