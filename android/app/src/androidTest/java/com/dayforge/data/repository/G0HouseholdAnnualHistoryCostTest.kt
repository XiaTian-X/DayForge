package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fixed annual test-history construction, not annual application admission or UI cost.
 * Two objects each have one original-producer fact on every past day for 365 days.
 * Historical acceptance is constructed and fully checked by the original oracle; no
 * historical HTTP ACK is executed. The 15-minute bulk-case ceiling does not alter any
 * user feedback, commit, result, ACK, or warm-resource performance threshold.
 */
@RunWith(AndroidJUnit4::class)
class G0HouseholdAnnualHistoryCostTest:G0HouseholdHistoryFixture() {
    private data class Cell(val type:Int,val value:Any?)
    private data class Table(val columns:List<String>,val rows:List<List<Cell>>)
    private data class Timed<T>(val value:T,val ns:Long)
    private fun now()=SystemClock.elapsedRealtimeNanos()

    // Test-only native observer: all persistent columns, rowids, BLOB bytes and
    // sqlite_sequence are retained. Nothing here grants a runtime SQL capability.
    private fun native():Map<String,Table> = SQLiteDatabase.openDatabase(
        app.getDatabasePath("habit_database").path,null,SQLiteDatabase.OPEN_READONLY).use {raw->
        val names=raw.rawQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name",null).use {c->
            buildList {while(c.moveToNext())add(c.getString(0))}
        }
        names.associateWith {name->
            val quoted="\""+name.replace("\"","\"\"")+"\""
            raw.rawQuery("SELECT rowid AS __g0_native_rowid__, * FROM $quoted ORDER BY rowid",null).use {c->
                Table(c.columnNames.toList(),buildList {while(c.moveToNext())add((0 until c.columnCount).map {i->
                    Cell(c.getType(i),when(c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL->null
                        Cursor.FIELD_TYPE_INTEGER->c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT->java.lang.Double.doubleToRawLongBits(c.getDouble(i))
                        Cursor.FIELD_TYPE_BLOB->c.getBlob(i).toList()
                        else->c.getString(i)
                    })
                })})
            }
        }
    }
    private suspend fun <T> measured(stage:String,body:suspend ()->T):Timed<T> {
        val started=now();var returned=false;var finished=0L
        try {
            val value=body();finished=now();returned=true
            return Timed(value,finished-started)
        } finally {
            val end=finished.takeIf {it!=0L} ?: now()
            Log.i("G0HistoryFixtureMeasure",buildJsonObject {
                put("history",730);put("strategy","constructed");put("stage",stage)
                put("elapsed_ns",end-started);put("returned",returned);put("ui",false)
                put("real_historical_ack_calls",0)
            }.toString())
        }
    }

    @Test(timeout=900_000) fun twoObjectsEachWith365PastDaysConstructVerifyAndRetainRuntimeCapacityGuard()=runBlocking<Unit> {
        val started=now();var phase="pending";var originalVerified=false;var runtimeAdmitted=false
        try {
            val pending=measured(phase) {seedPendingHouseholdHistory(730)}
            val seed=pending.value;assertEquals(730,seed.entries.size)
            val (http,server)=channel {successReply(it)}
            phase="construct"
            val constructed=measured(phase) {constructAcceptedHouseholdHistory(seed,http)}
            val beforeOracle=native();assertTrue(beforeOracle.containsKey("sqlite_sequence"))
            phase="complete_original_oracle"
            val verified=measured(phase) {verifyAcceptedHouseholdHistory(seed,http)}
            assertEquals(beforeOracle,native());originalVerified=true
            val dates=(1L..365L).mapTo(linkedSetOf()) {seed.today.minusDays(it)}
            assertEquals(seed.habits.map {it.uuid}.toSet(),verified.value.keys)
            for(row in seed.habits) {
                val history=verified.value.getValue(row.uuid)
                assertEquals(365,history.completions.size)
                assertEquals(dates.associateWith {1L},history.quantities)
                assertEquals(dates,history.policies.keys)
                assertEquals(0L,history.todayQuantity);assertFalse(seed.today in history.policies)
                assertTrue(history.unknownDates.isEmpty())
            }
            val totals=listOf("completions","count_days","next_request_origins","next_transmissions","next_acceptances","sync_entity_state")
            for(table in totals)assertEquals(730,beforeOracle.getValue(table).rows.size)
            assertEquals(0,beforeOracle.getValue("sync_outbox").rows.size)
            assertEquals(0,server.requests.count {it.path.endsWith("/push")})

            phase="default_runtime_capacity_rejection"
            val beforeRejection=native()
            val rejected=measured(phase) {
                val failure=try {openHousehold(http).also {runtimeAdmitted=true};null}
                    catch(expected:IllegalArgumentException) {expected}
                assertNotNull("Default runtime must reject by its unchanged capacity guard",failure)
                assertEquals("G0_COUNT_CAPACITY_UNSUPPORTED",failure?.message)
            }
            assertFalse(runtimeAdmitted);assertEquals(beforeRejection,native())
            assertEquals(beforeOracle,native());assertEquals(0,server.requests.count {it.path.endsWith("/push")})
            phase="terminal"
            Log.i("G0HistoryFixtureMeasure",buildJsonObject {
                put("history",730);put("strategy","constructed");put("stage",phase)
                put("pending_ns",pending.ns);put("construct_ns",constructed.ns);put("complete_original_oracle_ns",verified.ns)
                put("capacity_rejection_ns",rejected.ns);put("whole_case_to_terminal_ns",now()-started)
                put("objects",2);put("facts_per_object",365);put("past_days_per_object",365);put("today_quantity_per_object",0)
                put("facts",730);put("days",730);put("origins",730);put("transmissions",730);put("receipts",730);put("shadows",730)
                put("outbox",0);put("source_snapshots",seed.entries.size);put("real_historical_ack_calls",0)
                put("original_verified",originalVerified);put("oracle_native_unchanged",true);put("rejection_native_unchanged",true)
                put("native_tables",beforeOracle.size);put("rowids_and_sequence_checked",true)
                put("runtime_annual_admitted",runtimeAdmitted);put("runtime_fact_cap",128)
                put("rejection_reason","G0_COUNT_CAPACITY_UNSUPPORTED");put("ui",false)
            }.toString())
        } catch(failure:Throwable) {
            Log.e("G0HistoryFixtureMeasure",buildJsonObject {
                put("history",730);put("strategy","constructed");put("stage","failure");put("failed_phase",phase)
                put("error",failure.javaClass.simpleName);put("original_verified",originalVerified)
                put("runtime_annual_admitted",runtimeAdmitted);put("ui",false);put("elapsed_ns",now()-started)
            }.toString())
            throw failure
        }
    }
}
