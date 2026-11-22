package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.domain.model.CountHistory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Cost of preparing synthetic test history, NOT a user operation benchmark.
 * One pending image, original HTTP/ACK then constructed acceptance, with the complete
 * independent original oracle included in both costs. No P95 or order-effect claim.
 * The 15-minute whole-case bound matches existing bulk G0 comparisons; it does not
 * relax any foreground, ACK, feedback, or resource budget. */
@RunWith(AndroidJUnit4::class)
class G0HouseholdHistoryCostTest:G0HouseholdHistoryFixture() {
    private fun now()=SystemClock.elapsedRealtimeNanos()

    @Test(timeout=900_000) fun thirtyTwoPastFactsOriginalAcceptanceVersusVerifiedConstruction()=runBlocking<Unit> {
        val pendingStarted=now()
        val seed=seedPendingHouseholdHistory(32)
        val pendingElapsed=now()-pendingStarted
        val (http,server)=channel {successReply(it)}
        val image=storage.snapshotClosedDatabase()
        val elapsed=linkedMapOf<String,Long>()
        var reference:Map<String,CountHistory>?=null
        for(strategy in listOf("original","constructed")) {
            freshGateForColdOpen();restoreForJourney(image)
            val beforeRequests=server.requests.count {it.path.endsWith("/push")}
            val started=now()
            if(strategy=="original") {
                val original=sender(http)
                for(entry in seed.entries) {
                    val delivery=requireNotNull(original.sendOperation(access(),entry.queue.operationId))
                    assertEquals(NextOperationAcceptance.COMMITTED,original.acceptOperation(delivery))
                }
            } else constructAcceptedHouseholdHistory(seed,http)
            val prepared=now()
            val native=pageNativeState()
            val histories=verifyAcceptedHouseholdHistory(seed,http)
            assertEquals(native,pageNativeState())
            val verified=now()
            if(reference==null)reference=histories else assertEquals(reference,histories)
            assertEquals(32,pageRowCount("completions"))
            assertEquals(32,pageRowCount("next_acceptances"))
            assertEquals(0,pageRowCount("sync_outbox"))
            val pushes=server.requests.count {it.path.endsWith("/push")}-beforeRequests
            assertEquals(if(strategy=="original")32 else 0,pushes)
            elapsed[strategy]=verified-started
            Log.i("G0HistoryFixtureMeasure",buildJsonObject {
                put("history",32);put("strategy",strategy);put("stage","accepted_history")
                put("pending_producer_ns",pendingElapsed)
                put("preparation_ns",prepared-started);put("complete_oracle_ns",verified-prepared)
                put("total_ns",verified-started);put("real_historical_ack_calls",pushes)
                put("original_verified",true);put("source_snapshots",seed.entries.size)
                put("outbox",0);put("ui",false);put("network_synthetic",true)
            }.toString())
        }
        assertEquals(setOf("original","constructed"),elapsed.keys)
        assertTrue("Fixture construction plus complete verification must save preparation work; not a user latency claim",
            elapsed.getValue("constructed")<elapsed.getValue("original"))
    }
}
