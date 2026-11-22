package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Two fixed orders per history, identical closed image; complete original proof remains.
 * Cold = reference construction + open + complete initialization + first qualified view.
 * A reopened Room connection is cold; the OS page cache may already be warm.
 * No UI, parallel ACK, P95, persistent certificate, or total-heap assertion.
 */
@RunWith(AndroidJUnit4::class)
class G0HouseholdColdOwnerCostTest:G0HouseholdColdOwnerFixture() {
    private fun now()=SystemClock.elapsedRealtimeNanos()
    private enum class Arm { ORIGINAL, OWNED_FULL, OWNED_SHARED }
    private data class Cold(val arm:Arm,val ns:Long,val hold:Long)
    private fun ordinaryColdTransactions(trace:G0PhaseTrace,owner:G0RoomColdOwnerFactory?):Long {
        val spans=synchronized(trace.spans) {trace.spans.toList()}
        val begin=spans.filter {it.phase=="tx_begin_outer"}.sortedBy {it.start}
        val end=spans.filter {it.phase=="tx_end_outer"}.sortedBy {it.start}
        // Owner's initial BEGIN is pre-Room and measured directly. Its END is additionally
        // observed by the normal middle driver wrapper; do not invent a matching Room BEGIN.
        val ownedEnd=if(owner==null)emptyList() else end.filter {
            it.start>=owner.reservationStartedNs && it.end<=owner.endedNs
        }.also {assertEquals("Exactly one observed actual cold END",1,it.size)}
        val ordinary=end-ownedEnd.toSet()
        assertEquals("Other complete transaction pairs",begin.size,ordinary.size)
        return begin.zip(ordinary).maxOfOrNull {(a,b)->b.end-a.start} ?: 0L
    }
    @Test(timeout=1_800_000) fun eightCrossDayFactsColdRecoveryAndFirstRealCount()=runBlocking<Unit> {compare(8)}
    @Test(timeout=1_800_000) fun annualCrossDayFactsColdRecoveryAndFirstRealCount()=runBlocking<Unit> {compare(730)}

    private suspend fun compare(history:Int) {
        val seed=seedPendingHouseholdHistory(history)
        val (http,server)=channel {successReply(it)}
        constructAcceptedHouseholdHistory(seed,http)
        val beforeOracle=pageNativeState()
        val expected=verifyAcceptedHouseholdHistory(seed,http)
        assertEquals(beforeOracle,pageNativeState())
        val image=storage.snapshotClosedDatabase()
        val capacity=if(history==730)G0HouseholdCountStore.CapacityProfile.ANNUAL_730_PLUS_6
            else G0HouseholdCountStore.CapacityProfile.DEFAULT_128
        val failures=mutableListOf<String>()
        val samples=mutableListOf<Cold>()
        for((round,order) in listOf(Arm.entries.toList(),Arm.entries.reversed()).withIndex()) {
            for(arm in order) {
                freshGateForColdOpen();restoreForJourney(image)
                val trace=G0PhaseTrace(driverTiming=false)
                val ledger=G0ColdWorkLedger()
                var owned:Opened?=null
                lateinit var store:G0HouseholdCountStore
                val started=now()
                val view=ledger.run { trace.run {
                    store=if(arm==Arm.ORIGINAL)openHousehold(http,capacityProfile=capacity)
                    else openOwnedHousehold(http,arm==Arm.OWNED_SHARED,capacity).also {owned=it}.store
                    householdView(store)
                }}
                val elapsed=now()-started

                // Include the first actual operation after cold so work is not merely deferred.
                suspend fun <T> phase(name:String,body:suspend ()->T):Pair<T,Long> {
                    val operation=G0PhaseTrace(driverTiming=false);val start=now()
                    val result=operation.run(body);val duration=now()-start
                    Log.i("G0ColdOwnerMeasure",buildJsonObject {
                        put("history",history);put("round",round);put("arm",arm.name);put("stage",name)
                        put("elapsed_ns",duration);put("transaction_max_ns",transactionHold(operation))
                        put("account_hold_ns",operation.nanos("count_account_hold"))
                    }.toString())
                    if(transactionHold(operation)>150_000_000L)failures+="$round/$arm/$name resource >150ms"
                    return result to duration
                }
                val localStart=now()
                val (saved,writeNs)=phase("first_write") {store.increase(seed.habits.first().uuid)}
                val (local,readNs)=phase("first_result") {householdView(store,mapOf(saved.habitUuid to saved.factId))}
                val localNs=now()-localStart
                val coldAndFirstResultNs=now()-started
                assertEquals(expected,view.cards.mapValues {it.value.history})
                val nativeHold=owned?.owner?.let { owner ->
                    check(owner.reservationStartedNs>0 && owner.endedNs>=owner.reservationStartedNs)
                    owner.endedNs-owner.reservationStartedNs
                }
                val legacyHold=ordinaryColdTransactions(trace,owned?.owner)
                val hold=nativeHold ?: legacyHold
                samples+=Cold(arm,elapsed,hold)
                Log.i("G0ColdOwnerMeasure",buildJsonObject {
                    put("history",history);put("round",round);put("arm",arm.name);put("stage","cold_ready")
                    put("elapsed_ns",elapsed);put("account_hold_ns",trace.nanos("count_account_hold"))
                    put("account_wait_ns",trace.nanos("count_account_wait"))
                    put("owner_reservation_ns",nativeHold?.let(::JsonPrimitive) ?: JsonNull)
                    put("owner_begin_wait_ns",owned?.owner?.let {
                        JsonPrimitive(it.reservationStartedNs-it.reservationRequestedNs)
                    } ?: JsonNull)
                    put("legacy_outer_span_max_ns",legacyHold)
                    put("driver_outer_begin_count",trace.count("tx_begin_outer"))
                    put("driver_outer_end_count",trace.count("tx_end_outer"))
                    put("owner_actual_ends",owned?.owner?.actualEnds ?: 0)
                    put("reference_ns",if(arm==Arm.ORIGINAL)
                        listOf("builder","open","snapshot","close","delete").sumOf {trace.nanos("reference_$it")}
                        else trace.nanos("cold_reference"))
                    put("frozen_capture_ns",trace.nanos("cold_frozen_capture"))
                    put("shared_input",store.sharedColdInputUsed);put("frozen_rows",store.coldCapturedRows)
                    put("encoded_bytes",store.coldEncodedBytes);put("budget_fallback",owned?.budgetFallback ?: false)
                    put("gate_query_attempts",ledger.queries);put("gate_write_attempts",ledger.writes)
                    put("exact_whole_table_queries",buildJsonObject {
                        ledger.wholeTableQueries.forEach {(table,count)->put(table,count)}
                    })
                    put("decryptions",trace.count("decrypt_access")+trace.count("decrypt_refresh")+trace.count("decrypt_unclassified"))
                    put("native_unchanged_checked_here",false);put("ui",false);put("parallel_ack",false)
                }.toString())

                assertEquals(1L,local.cards.getValue(saved.habitUuid).history.todayQuantity)
                val (delivery,_)=phase("send") {requireNotNull(store.send(http,saved.requestId))}
                val (ack,ackNs)=phase("ack") {store.acknowledge(delivery)}
                assertEquals(NextOperationAcceptance.COMMITTED,ack)
                val (terminal,_)=phase("after_ack") {householdView(store,mapOf(saved.habitUuid to saved.factId))}
                val endNative=pageNativeState()
                assertEquals(NextOperationAcceptance.REPLAYED,store.acknowledge(delivery))
                assertTrue(store.pendingRequestIds().isEmpty())
                assertEquals(endNative,pageNativeState())
                reopenReference()
                sameHouseholdAsOriginal(terminal)
                assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(delivery))
                assertEquals(endNative,pageNativeState())
                Log.i("G0ColdOwnerMeasure",buildJsonObject {
                    put("history",history);put("round",round);put("arm",arm.name);put("stage","terminal")
                    put("cold_and_first_result_ns",coldAndFirstResultNs);put("first_local_result_ns",localNs)
                    put("facts",history+1);put("outbox",0);put("original_reader_and_replay",true)
                    put("native_unchanged_by_oracle",true)
                }.toString())
                if(elapsed>2_000_000_000L)failures+="$round/$arm cold first qualified view >2s"
                if(writeNs>500_000_000L)failures+="$round/$arm first save >500ms"
                if(readNs>700_000_000L || localNs>700_000_000L)failures+="$round/$arm first local result >700ms"
                if(ackNs>500_000_000L)failures+="$round/$arm ACK >500ms"
            }
        }
        assertEquals(6,samples.size)
        assertEquals(6,server.requests.count {it.path.endsWith("/push")})
        assertTrue("Original budgets; complete two-order small sample is not P95: "+failures.joinToString(";"),
            failures.isEmpty())
    }
}
