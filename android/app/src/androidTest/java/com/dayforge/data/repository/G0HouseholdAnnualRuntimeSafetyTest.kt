package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.NextSyncPushResponse
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.api.encodeSyncRequest
import com.dayforge.data.model.CheckInResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fixed annual kernel admission, not an unrestricted capacity increase or a UI
 * latency claim. A single 730-row historical fixture is reused for the real six
 * operations and independent cold corruption branches. Historical terminal rows
 * are constructed and fully audited; only the six new requests use real HTTP.
 */
@RunWith(AndroidJUnit4::class)
class G0HouseholdAnnualRuntimeSafetyTest:G0HouseholdHistoryFixture() {
    private val annual=G0HouseholdCountStore.CapacityProfile.ANNUAL_730_PLUS_6
    private data class Cell(val type:Int,val value:Any?)
    private data class NativeTable(val columns:List<String>,val rows:List<List<Cell>>)
    private fun native():Map<String,NativeTable> = SQLiteDatabase.openDatabase(
        app.getDatabasePath("habit_database").path,null,SQLiteDatabase.OPEN_READONLY).use {raw->
        val tables=raw.rawQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name",null).use {c->
            buildList {while(c.moveToNext())add(c.getString(0))}
        }
        tables.associateWith {table->
            val quoted="\""+table.replace("\"","\"\"")+"\""
            raw.rawQuery("SELECT rowid AS __g0_native_rowid__, * FROM $quoted ORDER BY rowid",null).use {c->
                NativeTable(c.columnNames.toList(),buildList {while(c.moveToNext())add((0 until c.columnCount).map {i->
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
    private fun restore(image:ByteArray) {freshGateForColdOpen();restoreForJourney(image)}
    private suspend fun rejectedUnchanged(message:String,type:Class<out Exception> = IllegalArgumentException::class.java,
        body:suspend ()->Unit) {
        val before=native()
        val failure=try {body();null} catch(error:Exception) {
            if(error is CancellationException)throw error
            error
        }
        assertNotNull("Expected exact rejection: $message",failure)
        assertEquals(type,requireNotNull(failure).javaClass)
        assertEquals(message,failure.message)
        assertEquals(before,native())
    }

    @Test fun annualIsOneExplicitProfileRatherThanAnArbitraryLargerMaximum()=runBlocking<Unit> {
        val (http,_)=channel {successReply(it)}
        val normal=G0HouseholdCountStore.CapacityProfile.DEFAULT_128
        for((profile,limit) in listOf(normal to 0,normal to 129,normal to 736,
            annual to 128,annual to 730,annual to 735,annual to 737)) {
            rejectedUnchanged("G0_COUNT_CAPACITY_PROFILE_UNSUPPORTED") {
                G0HouseholdCountStore(db,tokens,sessions,authority,domainGate,sender(http),limit,
                    jointAuthority=true,capacityProfile=profile)
            }
        }
    }

    @Test fun annualRequiresProvenAcceptedBaseAndCannotEnlargeThePendingQueue()=runBlocking<Unit> {
        val seed=seedPendingHouseholdHistory(8);val (http,_)=channel {successReply(it)}
        rejectedUnchanged("G0_COUNT_ANNUAL_PENDING_LIMIT") {openHousehold(http,capacityProfile=annual)}
        reopenReference()
        val normal=openHousehold(http)
        val pending=householdView(normal)
        assertEquals(8,pending.cards.values.sumOf {it.history.completions.size})
        assertEquals(seed.entries.map {it.origin.requestId}.toSet(),normal.pendingRequestIds().toSet())
        reopenReference()
        constructAcceptedHouseholdHistory(seed,http);verifyAcceptedHouseholdHistory(seed,http)
        rejectedUnchanged("G0_COUNT_ANNUAL_ACCEPTED_BASE_REQUIRED") {openHousehold(http,capacityProfile=annual)}
        reopenReference()
        assertEquals(8,householdView(openHousehold(http)).cards.values.sumOf {it.history.completions.size})
    }

    @Test(timeout=900_000) fun annualHistorySixRealCommandsCapacityAndDeepEvidenceRemainVerified()=runBlocking<Unit> {
        val seed=seedPendingHouseholdHistory(730);val (http,server)=channel {successReply(it)}
        constructAcceptedHouseholdHistory(seed,http)
        val reference=verifyAcceptedHouseholdHistory(seed,http)
        for(row in seed.habits) {
            val history=reference.getValue(row.uuid)
            assertEquals(365,history.completions.size);assertEquals(365,history.policies.size)
            assertEquals((1L..365L).map {seed.today.minusDays(it)}.toSet(),history.quantities.keys)
            assertTrue(history.quantities.values.all {it==1L});assertEquals(0L,history.todayQuantity)
        }
        assertEquals(0,server.requests.count {it.path.endsWith("/push")})
        // Keep one complete accepted image for all cold branches; no repeated annual generation.
        val annualImage=storage.snapshotClosedDatabase();restore(annualImage)
        rejectedUnchanged("G0_COUNT_CAPACITY_UNSUPPORTED") {openHousehold(http)}
        reopenReference()
        val beforeOpen=native();val store=coldHousehold(http,capacityProfile=annual)
        val initial=householdView(store)
        assertEquals(beforeOpen,native());assertTrue(initial.valid())
        for(row in seed.habits)assertEquals(reference.getValue(row.uuid),initial.cards.getValue(row.uuid).history)

        val sources=linkedMapOf<String,Long>()
        val saved=mutableListOf<G0HouseholdCountStore.Saved>()
        val deliveries=mutableListOf<NextCoreDelivery<NextSyncPushResponse>>()
        repeat(6) {index->
            val row=seed.habits[index%2]
            val result=store.increase(row.uuid);saved+=result;sources[row.uuid]=result.factId
            assertEquals(row.uuid,result.habitUuid)
            val committed=householdView(store,sources)
            for((position,expected) in seed.habits.withIndex()) {
                val quantity=(index+2-position)/2
                val card=committed.cards.getValue(expected.uuid)
                assertEquals(quantity.toLong(),card.history.todayQuantity)
                assertEquals(365+quantity,card.history.completions.size)
                sources[expected.uuid]?.let {id->
                    val fact=card.history.completions.single {it.id==id}
                    assertEquals(expected.uuid,fact.habitUuid)
                    assertEquals(fact.uuid,card.followUp.claim.completionUuid)
                }
            }
            assertEquals(listOf(result.requestId),store.pendingRequestIds())
            val delivery=requireNotNull(store.send(http,result.requestId));deliveries+=delivery
            assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(delivery))
            val afterAck=native()
            assertEquals(NextOperationAcceptance.REPLAYED,store.acknowledge(delivery))
            assertEquals(afterAck,native());assertTrue(store.pendingRequestIds().isEmpty())
        }
        assertEquals(6,saved.map {it.factId}.distinct().size)
        assertEquals(6,saved.map {it.factUuid}.distinct().size)
        assertEquals(6,saved.map {it.requestId}.distinct().size)
        assertTrue(saved.zipWithNext().all {(a,b)->a.factId<b.factId})
        assertEquals(6,server.requests.count {it.path.endsWith("/push")})
        for(table in listOf("completions","next_request_origins","next_transmissions","next_acceptances","sync_entity_state"))
            assertEquals(736,pageRowCount(table))
        assertEquals(732,pageRowCount("count_days"));assertEquals(0,pageRowCount("sync_outbox"))
        val full=householdView(store,sources)
        for(row in seed.habits) {
            val history=full.cards.getValue(row.uuid).history
            assertEquals(reference.getValue(row.uuid).quantities+(seed.today to 3L),history.quantities)
            assertEquals(368,history.completions.size);assertEquals(366,history.policies.size)
        }
        for(row in seed.habits)rejectedUnchanged("G0_COUNT_CAPACITY_UNSUPPORTED") {store.increase(row.uuid)}
        assertTrue(full.valid())
        val terminal=native();reopenReference();sameHouseholdAsOriginal(full)
        for(delivery in deliveries)assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(delivery))
        assertEquals(terminal,native())
        val fullImage=storage.snapshotClosedDatabase();restore(fullImage)
        val reopened=coldHousehold(http,capacityProfile=annual)
        val restored=householdView(reopened,sources)
        assertEquals(full.cards,restored.cards);assertEquals(terminal,native())
        rejectedUnchanged("G0_COUNT_CAPACITY_UNSUPPORTED") {reopened.increase(seed.habits.first().uuid)}

        // An independently valid 737th original write must still fail annual cold admission.
        reopenReference()
        val service=actions().service
        assertTrue(service.incrementCount(app,seed.habits.first().id) is CheckInResult.Success)
        assertEquals(737,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
        rejectedUnchanged("G0_COUNT_CAPACITY_UNSUPPORTED") {openHousehold(http,capacityProfile=annual)}

        // Exact per-object DAO ordering crosses both real 128-fact pages. These facts
        // are not early sample rows, and day binding is repaired only in the source
        // rewrite case so the original source-to-fact audit must actually execute.
        restore(annualImage)
        val row=seed.habits.first()
        val ordered=db.completionDao().getCompletionsByHabit(row.id).first()
        assertEquals(365,ordered.size)
        for(position in listOf(128,256,364)) {
            restore(annualImage)
            val entry=seed.entries.single {it.fact.uuid==ordered[position].uuid}
            val operation=decodeNextOperationIntent(entry.origin.intentJson)
            val changed=operation.copy(payload=JsonObject(operation.payload+("value" to JsonPrimitive(2))))
            db.withTransaction {
                val sql=db.openHelper.writableDatabase
                sql.execSQL("UPDATE next_request_origins SET intentJson=? WHERE kind=? AND requestId=?",
                    arrayOf<Any>(encodeSyncRequest(SyncV2Operation.serializer(),changed).toString(Charsets.UTF_8),
                        NEXT_OPERATION,entry.origin.requestId))
                val hash=requireNotNull(NextRequestSql.rowHash(sql,"next_request_origins","kind=? AND requestId=?",
                    arrayOf<Any>(NEXT_OPERATION,entry.origin.requestId)))
                sql.execSQL("UPDATE count_days SET originHash=? WHERE habitId=? AND localDate=?",
                    arrayOf<Any>(hash,entry.day.habitId,entry.day.localDate))
                assertEquals(365,NextCountDayStore(db).readForHabit(row).size)
            }
            rejectedUnchanged("FACT_DIVERGED",NextFactMergeException::class.java) {CountHistoryReader(db,tokens,sessions).read(row)}
            rejectedUnchanged("FACT_DIVERGED",NextFactMergeException::class.java) {openHousehold(http,capacityProfile=annual)}
        }
        restore(annualImage)
        val late=seed.entries.single {it.fact.uuid==ordered[256].uuid}
        mutateHouseholdFile("DELETE FROM count_days WHERE habitId=? AND localDate=?",
            arrayOf(late.day.habitId,late.day.localDate))
        rejectedUnchanged("COUNT_DAY_INVALID") {
            openHousehold(http,capacityProfile=annual)
        }
        restore(annualImage)
        val extraId=id(49901)
        val extra=decodeNextOperationIntent(late.origin.intentJson).copy(operationId=extraId)
        db.nextRequestDao().insertOrigin(late.origin.copy(requestId=extraId,queueId=late.origin.queueId+10_000,
            intentJson=encodeSyncRequest(SyncV2Operation.serializer(),extra).toString(Charsets.UTF_8)))
        rejectedUnchanged("COUNT_FACT_INVALID",IllegalStateException::class.java) {
            openHousehold(http,capacityProfile=annual)
        }
    }
}
