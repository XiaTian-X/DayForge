package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Two complete classes keep annual setup/oracles out of the short-history command.
 * Each size has two reversed orders, not enough observations to estimate P95.
 * This measures repository DTO availability, not touch/animation/prompt pixels, parallel
 * ACK load, process death, or natural post-write certificate maintenance (P3).
 */
@RunWith(AndroidJUnit4::class)
class G0HouseholdCertificateCostTest:G0HouseholdCertificateCostFixture() {
    @Test(timeout=1_800_000) fun eightFactsThreeScopesFirstSaveAndActualSync()=runBlocking<Unit> {compareCertificate(8)}
}

@RunWith(AndroidJUnit4::class)
class G0HouseholdAnnualCertificateCostTest:G0HouseholdCertificateCostFixture() {
    @Test(timeout=1_800_000) fun annualFactsThreeScopesFirstSaveAndActualSync()=runBlocking<Unit> {compareCertificate(730)}
}

abstract class G0HouseholdCertificateCostFixture:G0HouseholdCertificateFixture() {
    private enum class Arm { ORIGINAL_SCOPE, EXPANDED_FULL_V, EXPANDED_CERTIFICATE_HIT }
    private data class Timed<T>(val value:T,val start:Long,val end:Long,val trace:G0PhaseTrace) {
        val nanos:Long get()=end-start
    }
    private data class Cold(val round:Int,val arm:Arm,val ready:Long,val firstResult:Long)
    private data class Observation(val round:Int,val arm:String,val stage:String,val start:Long,val end:Long,
        val ok:Boolean,val trace:G0PhaseTrace)
    private fun now()=SystemClock.elapsedRealtimeNanos()
    private fun spans(trace:G0PhaseTrace)=synchronized(trace.spans) {trace.spans.toList()}
    private fun calls(trace:G0PhaseTrace,phase:String)=spans(trace).count {it.phase==phase}
    private fun nanos(trace:G0PhaseTrace,phase:String)=spans(trace).filter {it.phase==phase}.sumOf {it.end-it.start}
    private fun log(body:JsonObjectBuilder.()->Unit)=Log.i("G0CountCertificateMeasure",buildJsonObject(body).toString())
    private fun ordinaryColdTransactions(trace:G0PhaseTrace,owner:G0RoomColdOwnerFactory?):Long {
        val rows=spans(trace)
        val begin=rows.filter {it.phase=="tx_begin_outer"}.sortedBy {it.start}
        val end=rows.filter {it.phase=="tx_end_outer"}.sortedBy {it.start}
        // Owned BEGIN precedes Room. Its real END is also observed by the middle
        // driver; it must not be paired with the reference/first-read Room BEGIN.
        val ownerEnd=if(owner==null)emptyList() else end.filter {
            it.start>=owner.reservationStartedNs && it.end<=owner.endedNs
        }.also {assertEquals("Exactly one actual owned cold END",1,it.size)}
        val ordinary=end-ownerEnd.toSet()
        assertEquals("Complete other BEGIN/END pairs",begin.size,ordinary.size)
        return begin.zip(ordinary).maxOfOrNull {(a,b)->
            assertTrue(a.ok && b.ok && b.end>=a.start);b.end-a.start
        } ?: 0L
    }
    private fun ownerHold(owner:G0RoomColdOwnerFactory?):Long?=owner?.let {
        assertEquals(1,it.actualEnds)
        assertTrue(it.reservationStartedNs>0 && it.endedNs>=it.reservationStartedNs)
        it.endedNs-it.reservationStartedNs
    }
    private fun packetRows(state:Map<String,List<String>>)=state.filterKeys {it in G0CountCertificateSql.tables}
    private fun requireWarm(trace:G0PhaseTrace) {
        assertEquals(0,calls(trace,"count_complete_history"))
        assertEquals(0,calls(trace,"cold_reference"));assertEquals(0,calls(trace,"cold_frozen_capture"))
        assertTrue(spans(trace).none {it.phase.startsWith("reference_")})
        assertTrue("Every real entry keeps fresh access decryption",calls(trace,"decrypt_access")>=2)
    }

    protected suspend fun compareCertificate(history:Int) {
        var stage="fixture";var activeRound=-1;var activeArm="SETUP";var completed=0
        val failures=mutableListOf<String>();val samples=mutableListOf<Cold>()
        val maintenance=linkedMapOf<Int,Long>()
        val observations=mutableListOf<Observation>();var reported=0
        suspend fun <T> measured(name:String,body:suspend ()->T):Timed<T> {
            stage=name;val trace=G0PhaseTrace(driverTiming=false);val start=now();var ok=false
            return try {
                val value=trace.run(body);ok=true;Timed(value,start,now(),trace)
            } finally {observations+=Observation(activeRound,activeArm,name,start,now(),ok,trace)}
        }
        fun reportObservations() {
            // Emit small JSON records only outside the first-save/result/send/ACK
            // chain. A large nested phase map can exceed logcat's record size.
            while(reported<observations.size) {
                val value=observations[reported++]
                log {put("stage","attempt");put("history",history);put("round",value.round);put("arm",value.arm)
                    put("operation",value.stage);put("elapsed_ns",value.end-value.start);put("ok",value.ok)}
                spans(value.trace).groupBy {it.phase}.forEach {(name,rows)->
                    log {put("stage","phase");put("history",history);put("round",value.round);put("arm",value.arm)
                        put("operation",value.stage);put("phase",name);put("count",rows.size)
                        put("ns",rows.sumOf {it.end-it.start});put("max_ns",rows.maxOf {it.end-it.start})
                        put("all_ok",rows.all {it.ok});put("nested_phases_additive",false)}
                }
            }
        }
        fun budget(label:String,value:Long,limit:Long) {
            if(value>limit)failures+="$label ${value/1_000_000.0}ms >${limit/1_000_000}ms"
        }
        try {
            val fixtureStart=now()
            val seed=seedPendingHouseholdHistory(history)
            val (http,server)=channel {successReply(it)}
            constructAcceptedHouseholdHistory(seed,http)
            val source=pageNativeState()
            val expected=verifyAcceptedHouseholdHistory(seed,http)
            assertEquals(source,pageNativeState())
            assertEquals(0,server.requests.count {it.path.endsWith("/push")})
            assertTrue(G0CountCertificateSql.tables.keys.none {it in source})
            val originalImage=storage.snapshotClosedDatabase()
            reopenReference();prepareCertificateSchema()
            assertEquals(source,primaryState())
            val expandedEmpty=pageNativeState()
            assertEquals(G0CountCertificateSql.tables.keys,packetRows(expandedEmpty).keys)
            assertTrue(packetRows(expandedEmpty).values.all {it.isEmpty()})
            val expandedEmptyImage=storage.snapshotClosedDatabase()
            val capacity=if(history==730)G0HouseholdCountStore.CapacityProfile.ANNUAL_730_PLUS_6
                else G0HouseholdCountStore.CapacityProfile.DEFAULT_128
            log {put("stage","fixture");put("history",history);put("elapsed_ns",now()-fixtureStart)
                put("historical_http_pushes",0);put("history_constructed",true);put("past_span_days",history/2)
                put("original_bytes",originalImage.size);put("expanded_empty_bytes",expandedEmptyImage.size)
                put("primary_rows_identical",true);put("primary_table_count",source.size)
                put("schema_scope_identical_to_original",false);put("qh_tables_added",2)
                put("actual_process_death",false);put("natural_certificate_maintenance",false)}

            val orders=listOf(Arm.entries.toList(),Arm.entries.reversed())
            for((round,order) in orders.withIndex()) {
                activeRound=round;activeArm="FIRST_SIGN";stage="first sign full cold"
                // Restore the exact same clean expanded input, not a previously used
                // arm. No view/command/HTTP is allowed before the optional first sign.
                freshGateForColdOpen();restoreForJourney(expandedEmptyImage)
                val issuanceCold=measured("first_sign_full_cold") {openCertificate(http,lookup=false,capacity=capacity)}
                val issuing=issuanceCold.value.store
                assertFalse(issuing.certificateHit)
                assertEquals(2,calls(issuanceCold.trace,"count_complete_history"))
                assertTrue(calls(issuanceCold.trace,"decrypt_access")>=2)
                stage="first sign maintenance"
                val issue=measured("first_sign_maintenance") {issuing.persistColdCertificate()}
                assertTrue("Initial certificate must be issued before any view or P change",issue.value)
                val firstQualified=measured("first_sign_first_result") {householdView(issuing)}
                val firstSignReady=firstQualified.end-issuanceCold.start
                assertEquals(expected,firstQualified.value.cards.mapValues {it.value.history})
                assertTrue(firstQualified.value.valid());requireWarm(firstQualified.trace)
                for(name in listOf("certificate_snapshot","certificate_encode","certificate_export",
                    "certificate_sign","certificate_commit"))assertEquals("One $name phase",1,calls(issue.trace,name))
                assertTrue(calls(issue.trace,"decrypt_access")>=2)
                assertEquals(0,calls(issue.trace,"count_complete_history"))
                assertEquals(source,primaryState())
                val signed=pageNativeState();val packet=packetRows(signed)
                assertEquals(1,packet.getValue(G0CountCertificateSql.table).size)
                assertTrue(packet.getValue(G0CountCertificateSql.chunkTable).isNotEmpty())
                val issueResource=transactionHold(issue.trace)
                maintenance[round]=issue.nanos
                log {put("stage","first_sign");put("history",history);put("round",round)
                    put("full_cold_prerequisite_ns",issuanceCold.nanos)
                    put("full_cold_owner_reservation_ns",ownerHold(issuanceCold.value.owner))
                    put("maintenance_ns",issue.nanos);put("full_cold_plus_maintenance_ns",issue.end-issuanceCold.start)
                    put("first_result_read_ns",firstQualified.nanos);put("first_sign_cold_ready_ns",firstSignReady)
                    put("maintenance_transaction_max_ns",issueResource)
                    put("maintenance_account_hold_ns",nanos(issue.trace,"count_account_hold"))
                    put("maintenance_account_wait_ns",nanos(issue.trace,"count_account_wait"))
                    put("captured_input_bytes",issuing.coldEncodedBytes)
                    put("packet_retained_bytes",issuing.certificateMaintenanceBytes)
                    put("jvm_peak_measured",false);put("keystore_alias_previously_signed",round>0)
                    put("qh_rows",packet.values.sumOf {it.size});put("primary_unchanged",true)
                    put("publication_before_sign",false);put("business_change_before_sign",false)
                    put("natural_certificate_maintenance",false)}
                reportObservations()
                budget("$round first-sign cold first qualified result",firstSignReady,2_000_000_000L)
                budget("$round first-sign writer resource",issueResource,150_000_000L)
                budget("$round first-sign first result resource",transactionHold(firstQualified.trace),150_000_000L)
                // snapshotClosedDatabase requires an ordinary unsealed helper for its
                // checkpoint; reopening here is fixture work, outside the cold sample.
                reopenReference();val signedImage=storage.snapshotClosedDatabase()

                for(arm in order) {
                    activeArm=arm.name;stage="restore arm"
                    freshGateForColdOpen()
                    restoreForJourney(if(arm==Arm.ORIGINAL_SCOPE)originalImage else signedImage)
                    var owned:Opened?=null
                    lateinit var store:G0HouseholdCountStore
                    val ledger=G0ColdWorkLedger()
                    stage="cold first qualified DTO"
                    val cold=measured("cold_ready") {ledger.run {
                        store=if(arm==Arm.ORIGINAL_SCOPE)openHousehold(http,capacityProfile=capacity)
                        else openCertificate(http,lookup=arm==Arm.EXPANDED_CERTIFICATE_HIT,capacity=capacity)
                            .also {owned=it}.store
                        householdView(store)
                    }}
                    val expectedHit=arm==Arm.EXPANDED_CERTIFICATE_HIT
                    assertEquals(expectedHit,store.certificateHit)
                    assertEquals(if(expectedHit)0 else 2,calls(cold.trace,"count_complete_history"))
                    assertTrue(calls(cold.trace,"decrypt_access")>=4)
                    assertEquals(expected,cold.value.cards.mapValues {it.value.history})
                    assertTrue(cold.value.valid())
                    assertEquals(arm!=Arm.ORIGINAL_SCOPE,store.sharedColdInputUsed)

                    // No full-native oracle/copy/log between cold and first real use.
                    // Both scopes must perform the same new logical operation and sync.
                    stage="first increase"
                    val write=measured("first_write") {store.increase(seed.habits.first().uuid)}
                    val saved=write.value
                    stage="first local result"
                    val local=measured("first_result") {householdView(store,mapOf(saved.habitUuid to saved.factId))}
                    val localChain=local.end-write.start
                    val coldAndFirstResult=local.end-cold.start
                    assertEquals(1L,local.value.cards.getValue(saved.habitUuid).history.todayQuantity)
                    assertEquals(0L,local.value.cards.getValue(seed.habits.last().uuid).history.todayQuantity)
                    assertEquals(saved.factUuid,local.value.cards.getValue(saved.habitUuid).followUp.claim.completionUuid)
                    stage="actual send"
                    val send=measured("send") {requireNotNull(store.send(http,saved.requestId))}
                    stage="actual ACK"
                    val ack=measured("ack") {store.acknowledge(send.value)}
                    assertEquals(NextOperationAcceptance.COMMITTED,ack.value)
                    stage="ACK result"
                    val terminal=measured("after_ack") {householdView(store,mapOf(saved.habitUuid to saved.factId))}
                    val syncChain=ack.end-write.start
                    val settledChain=terminal.end-write.start
                    assertEquals(1L,terminal.value.cards.getValue(saved.habitUuid).history.todayQuantity)
                    assertTrue(store.pendingRequestIds().isEmpty())
                    val terminalNative=pageNativeState()
                    assertEquals(history+1,terminalNative.getValue("completions").size)
                    assertEquals(0,terminalNative.getValue("sync_outbox").size)
                    if(arm==Arm.ORIGINAL_SCOPE)assertTrue(packetRows(terminalNative).isEmpty())
                    else assertEquals("P3 is absent: ordinary writes must not silently re-sign Q/H",packet,packetRows(terminalNative))
                    assertEquals(NextOperationAcceptance.REPLAYED,store.acknowledge(send.value))
                    assertEquals(terminalNative,pageNativeState())
                    reportObservations()
                    stage="independent original consumers and replay"
                    val oracleStart=now()
                    reopenReference();sameHouseholdAsOriginal(terminal.value)
                    assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(send.value))
                    assertEquals(terminalNative,pageNativeState())
                    val oracleNs=now()-oracleStart

                    val coldOrdinaryHold=ordinaryColdTransactions(cold.trace,owned?.owner)
                    val reserved=ownerHold(owned?.owner)
                    samples+=Cold(round,arm,cold.nanos,coldAndFirstResult)
                    log {put("stage","cold_ready");put("history",history);put("round",round);put("arm",arm.name)
                        put("elapsed_ns",cold.nanos);put("cold_and_first_result_ns",coldAndFirstResult)
                        put("account_hold_ns",nanos(cold.trace,"count_account_hold"))
                        put("account_wait_ns",nanos(cold.trace,"count_account_wait"))
                        put("owner_reservation_ns",reserved?.let(::JsonPrimitive) ?: JsonNull)
                        put("owner_begin_wait_ns",owned?.owner?.let {JsonPrimitive(it.reservationStartedNs-it.reservationRequestedNs)} ?: JsonNull)
                        put("ordinary_outer_span_max_ns",coldOrdinaryHold)
                        put("shared_input",store.sharedColdInputUsed);put("frozen_rows",store.coldCapturedRows)
                        put("captured_input_bytes",store.coldEncodedBytes);put("image_encoded_bytes",store.certificateEncodedBytes)
                        put("jvm_peak_measured",false)
                        put("persistent_certificate_hit",store.certificateHit)
                        put("complete_history_calls",calls(cold.trace,"count_complete_history"))
                        put("gate_query_attempts",ledger.queries);put("gate_write_attempts",ledger.writes)
                        put("whole_table_queries",buildJsonObject {ledger.wholeTableQueries.forEach {(table,count)->put(table,count)}})
                        put("same_primary_content",true);put("expanded_pair_same_closed_image",arm!=Arm.ORIGINAL_SCOPE)
                        put("original_and_expanded_schema_equal",false);put("ui",false);put("parallel_ack",false)
                        put("actual_process_death",false);put("natural_certificate_maintenance",false)}
                    for((name,value) in listOf("first_write" to write,"first_result" to local,
                        "send" to send,"ack" to ack,"after_ack" to terminal)) {
                        requireWarm(value.trace)
                        val resource=transactionHold(value.trace)
                        log {put("stage",name);put("history",history);put("round",round);put("arm",arm.name)
                            put("elapsed_ns",value.nanos);put("transaction_max_ns",resource)
                            put("account_hold_ns",nanos(value.trace,"count_account_hold"))
                            put("account_wait_ns",nanos(value.trace,"count_account_wait"))
                            put("access",calls(value.trace,"decrypt_access"));put("refresh",calls(value.trace,"decrypt_refresh"))}
                        budget("$round/$arm/$name writer or read resource",resource,150_000_000L)
                    }
                    completed++
                    log {put("stage","terminal");put("history",history);put("round",round);put("arm",arm.name)
                        put("first_local_result_ns",localChain);put("save_to_ack_end_ns",ack.end-write.end)
                        put("first_input_to_ack_ns",syncChain);put("first_input_to_settled_result_ns",settledChain)
                        put("original_oracle_ns",oracleNs);put("facts",history+1);put("outbox",0)
                        put("actual_new_http_pushes",server.requests.count {it.path.endsWith("/push")})
                        put("original_reader_and_replay",true);put("native_unchanged_by_oracle",true)
                        put("certificate_updated_by_business",false);put("completed",completed)}
                    budget("$round/$arm cold first qualified result",cold.nanos,2_000_000_000L)
                    budget("$round/$arm first save",write.nanos,500_000_000L)
                    budget("$round/$arm first local result",localChain,700_000_000L)
                    budget("$round/$arm local result read",local.nanos,700_000_000L)
                    budget("$round/$arm ACK",ack.nanos,500_000_000L)
                }
            }
            assertEquals(6,completed);assertEquals(6,samples.size)
            assertEquals(6,server.requests.count {it.path.endsWith("/push")})
            for(round in 0..1) {
                val batch=samples.filter {it.round==round}.associateBy {it.arm}
                log {put("stage","paired_cost");put("history",history);put("round",round)
                    put("expanded_full_minus_hit_cold_ns",batch.getValue(Arm.EXPANDED_FULL_V).ready-batch.getValue(Arm.EXPANDED_CERTIFICATE_HIT).ready)
                    put("expanded_full_minus_hit_cold_first_result_ns",batch.getValue(Arm.EXPANDED_FULL_V).firstResult-batch.getValue(Arm.EXPANDED_CERTIFICATE_HIT).firstResult)
                    put("original_minus_hit_cold_ns",batch.getValue(Arm.ORIGINAL_SCOPE).ready-batch.getValue(Arm.EXPANDED_CERTIFICATE_HIT).ready)
                    put("first_sign_maintenance_ns",maintenance.getValue(round));put("p95",false)
                    put("first_sign_is_not_natural_maintenance",true)}
            }
            stage="original budget assertion"
            failures.forEachIndexed {index,message->log {
                put("stage","budget_failure");put("history",history);put("index",index);put("message",message)
            }}
            log {put("stage","budgets");put("history",history);put("planned_arms",6);put("completed",completed)
                put("failure_count",failures.size);put("p95",false)
                put("budgets_passed",failures.isEmpty())}
            assertTrue("Original budgets; two reversed orders are not P95: "+failures.joinToString(";"),failures.isEmpty())
        } catch(failure:Throwable) {
            reportObservations()
            log {put("stage","failure");put("history",history);put("round",activeRound);put("arm",activeArm)
                put("active_stage",stage);put("planned_arms",6);put("completed",completed)
                put("exception_class",failure.javaClass.name);put("message",failure.message?.take(512))
                put("accumulated_budget_failure_count",failures.size)}
            throw failure
        }
    }
}
