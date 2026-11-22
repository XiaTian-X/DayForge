package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.encodeSyncRequest
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.model.CheckInResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The construction is a fixture, not HTTP acceptance or a new trusted application
 * writer. Its small differential uses the SAME original pending file. Only each real
 * ACK's observed local shadow clock is replayed as an explicit construction input;
 * no payload, receipt, hash, wire bytes or row is copied from the reference output.
 * All persistent native columns, rowids and sqlite_sequence remain in the comparison.
 */
@RunWith(AndroidJUnit4::class)
class G0HouseholdHistorySafetyTest:G0HouseholdHistoryFixture() {
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
                NativeTable(c.columnNames.toList(),buildList {while(c.moveToNext())add((0 until c.columnCount).map {index->
                    Cell(c.getType(index),when(c.getType(index)) {
                        Cursor.FIELD_TYPE_NULL->null
                        Cursor.FIELD_TYPE_INTEGER->c.getLong(index)
                        Cursor.FIELD_TYPE_FLOAT->java.lang.Double.doubleToRawLongBits(c.getDouble(index))
                        Cursor.FIELD_TYPE_BLOB->c.getBlob(index).toList()
                        else->c.getString(index)
                    })
                })})
            }
        }
    }
    private fun restore(image:ByteArray) {freshGateForColdOpen();restoreForJourney(image)}
    private suspend fun rejectedNativeUnchanged(body:suspend ()->Unit):Throwable {
        val before=native();val failure=runCatching {body()}.exceptionOrNull()
        assertNotNull("Complete original-source fixture oracle unexpectedly accepted",failure)
        assertEquals(before,native());return requireNotNull(failure)
    }
    private suspend fun assertOriginalSources(seed:G0HouseholdHistoryFixture.HistorySeed,pending:Boolean) {
        for(entry in seed.entries) {
            assertEquals(entry.origin,db.nextRequestDao().origin(NEXT_OPERATION,entry.queue.operationId))
            assertEquals(entry.day,db.countDayDao().get(entry.fact.habitId,entry.fact.recordedLocalDate))
            assertEquals(entry.sourceHash,entry.origin.sourceHash)
            assertEquals(entry.sourceHash,NextRequestSql.sourceHash(entry.queue))
            assertEquals(entry.originHash,NextRequestSql.rowHash(db.openHelper.writableDatabase,"next_request_origins",
                "kind=? AND requestId=?",arrayOf<Any>(NEXT_OPERATION,entry.queue.operationId)))
            if(pending) {
                assertEquals(entry.queue,db.syncOutboxDao().getById(entry.queue.id))
                assertEquals(entry.fact,db.completionDao().getCompletionById(entry.fact.id))
            } else assertNull(db.syncOutboxDao().getById(entry.queue.id))
        }
    }

    @Test fun eightCrossDayOriginalHttpAndConstructedAcceptanceMatchEveryNativeColumnAndContinue()=runBlocking<Unit> {
        val seed=seedPendingHouseholdHistory(8);val (http,server)=channel {successReply(it)}
        assertOriginalSources(seed,true);val image=storage.snapshotClosedDatabase();restore(image)
        val initial=native();assertTrue("sqlite_sequence must remain in the native contract",initial.containsKey("sqlite_sequence"))
        val clocks=linkedMapOf<String,Long>();val reference=sender(http)
        for(entry in seed.entries) {
            val delivery=requireNotNull(reference.sendOperation(access(),entry.queue.operationId))
            val started=System.currentTimeMillis()
            assertEquals(NextOperationAcceptance.COMMITTED,reference.acceptOperation(delivery))
            val finished=System.currentTimeMillis()
            val shadow=requireNotNull(db.syncOutboxDao().getState("activity_event",entry.fact.uuid))
            assertTrue(started<=finished && shadow.updatedAt in started..finished)
            assertNull(clocks.put(entry.queue.operationId,shadow.updatedAt))
            val before=native();assertEquals(NextOperationAcceptance.REPLAYED,reference.acceptOperation(delivery));assertEquals(before,native())
        }
        assertEquals(8,server.requests.count {it.path.endsWith("/push")})
        val original=verifyAcceptedHouseholdHistory(seed,http)
        assertOriginalSources(seed,false);val expected=native()
        restore(image);assertEquals(initial,native());assertOriginalSources(seed,true)
        val calls=server.requests.size
        constructAcceptedHouseholdHistory(seed,http,shadowUpdatedAt=clocks)
        assertEquals(calls,server.requests.size)
        assertEquals(expected,native())
        assertEquals(original,verifyAcceptedHouseholdHistory(seed,http));assertOriginalSources(seed,false)
        assertEquals(expected,native())
        Log.i("G0HouseholdHistorySafety","case=eight_same_pending_image tables=${expected.size} native_equal=true rowids=true sequence=true only_shadow_clock_input_replayed=true")

        // The constructed history must support actual business work, not just be readable.
        val service=actions().service;val counts=CountHistoryReader(db,tokens,sessions)
        for(row in seed.habits) {
            val result=service.incrementCount(app,row.id)
            assertTrue(result is CheckInResult.Success)
            val saved=result as CheckInResult.Success;assertTrue(saved.recorded);assertNotNull(saved.followUp)
            val claim=requireNotNull(saved.followUp).claim
            assertEquals(row.uuid,claim.habitUuid)
            val queue=db.syncOutboxDao().getEntityIntents("completion",requireNotNull(claim.completionUuid)).single()
            val requests=sender(http);val delivery=requireNotNull(requests.sendOperation(access(),queue.operationId))
            assertEquals(NextOperationAcceptance.COMMITTED,requests.acceptOperation(delivery))
            val before=native();assertEquals(NextOperationAcceptance.REPLAYED,requests.acceptOperation(delivery));assertEquals(before,native())
            val current=requireNotNull(db.habitDao().getHabitById(row.id));val history=counts.read(current)
            assertEquals(1L,history.todayQuantity);assertEquals(5,history.completions.size)
            assertEquals(claim.completionUuid,history.completions.single {it.recordedLocalDate==seed.today.toString()}.uuid)
        }
        val home=readHome();assertEquals(listOf(1L,1L),seed.habits.map {home.counts.getValue(it.id).todayQuantity})
        assertEquals(10,count("completions"));assertEquals(10,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
        assertEquals(10,server.requests.count {it.path.endsWith("/push")} )
    }

    @Test fun legitimateSameDaySecondFactsAndForeignMembersAreUnsupportedWithoutPartialAcceptance()=runBlocking<Unit> {
        val seed=seedPendingHouseholdHistory(8);val (http,_)=channel {successReply(it)}
        val image=storage.snapshotClosedDatabase();restore(image)
        val additional=seed.habits.map {row->
            val prior=seed.entries.first {it.fact.habitUuid==row.uuid}
            captureHouseholdHistoryEntry(originalHouseholdFact(row,java.time.LocalDate.parse(prior.fact.recordedLocalDate),seed.zone))
        }
        // These are legal original business sources. Include their real snapshots so
        // rejection cannot be attributed merely to a missing member or odd count.
        val unsupported=seed.copy(entries=seed.entries+additional)
        assertEquals(10,unsupported.entries.size)
        for(row in seed.habits)assertEquals(5,CountHistoryReader(db,tokens,sessions).read(row).completions.size)
        rejectedNativeUnchanged {constructAcceptedHouseholdHistory(unsupported,http)}
        assertEquals(0,count("next_acceptances"));assertEquals(10,count("sync_outbox"))
        restore(image)
        observation() // A real, unrelated metric-observation source/outbox is not in this fixture profile.
        rejectedNativeUnchanged {constructAcceptedHouseholdHistory(seed,http)}
        assertEquals(0,count("next_acceptances"));assertEquals(9,count("sync_outbox"))
    }

    @Test fun explicitShadowClockInputMustExactlyCoverOriginalRequests()=runBlocking<Unit> {
        val seed=seedPendingHouseholdHistory(8);val (http,_)=channel {successReply(it)}
        val clocks=seed.entries.associate {it.queue.operationId to System.currentTimeMillis()}
        val missing=clocks-seed.entries.first().queue.operationId
        val extra=clocks+(id(4901) to System.currentTimeMillis())
        val negative=clocks+(seed.entries.last().queue.operationId to -1L)
        for(input in listOf(missing,extra,negative))rejectedNativeUnchanged {
            constructAcceptedHouseholdHistory(seed,http,shadowUpdatedAt=input)
        }
        assertOriginalSources(seed,true);assertEquals(0,count("next_acceptances"))
    }

    @Test fun midConstructionFailureAndRealCoroutineCancellationRollbackTheEntireFixture()=runBlocking<Unit> {
        val seed=seedPendingHouseholdHistory(8);val (http,_)=channel {successReply(it)}
        val initial=native()
        historyAfterEntryForTest={index->if(index==3)throw IllegalStateException("G0_HISTORY_CONSTRUCTION_FAULT")}
        try {
            assertEquals("G0_HISTORY_CONSTRUCTION_FAULT",rejectedNativeUnchanged {constructAcceptedHouseholdHistory(seed,http)}.message)
        } finally {historyAfterEntryForTest={}}
        assertEquals(initial,native());assertOriginalSources(seed,true)
        val entered=CompletableDeferred<Unit>()
        historyAfterEntryForTest={index->if(index==3) {entered.complete(Unit);awaitCancellation()}}
        val operation=launch(Dispatchers.IO) {constructAcceptedHouseholdHistory(seed,http)}
        try {
            withTimeout(20_000) {entered.await()};operation.cancelAndJoin();assertTrue(operation.isCancelled)
        } finally {operation.cancelAndJoin();historyAfterEntryForTest={}}
        assertEquals(initial,native());assertOriginalSources(seed,true)
        constructAcceptedHouseholdHistory(seed,http);verifyAcceptedHouseholdHistory(seed,http)
    }

    @Test fun freshAuthorityIsRequiredAtEntryAndBeforeConstructedCommit()=runBlocking<Unit> {
        val seed=seedPendingHouseholdHistory(8);val (http,_)=channel {successReply(it)}
        val credentials=dataStore.data.first()
        tokens.saveLoginSession("synthetic-history-other","synthetic-history-refresh","member",id(4903),false)
        try {rejectedNativeUnchanged {constructAcceptedHouseholdHistory(seed,http)}} finally {dataStore.updateData {credentials}}
        failDecrypt=true
        try {rejectedNativeUnchanged {constructAcceptedHouseholdHistory(seed,http)}} finally {failDecrypt=false}
        historyAfterEntryForTest={index->if(index==3)failDecrypt=true}
        try {rejectedNativeUnchanged {constructAcceptedHouseholdHistory(seed,http)}} finally {failDecrypt=false;historyAfterEntryForTest={}}
        assertOriginalSources(seed,true);assertEquals(0,count("next_acceptances"))
    }

    @Test fun completeOracleRejectsEarlySourceMiddleReceiptLateDayAndExtraMembers()=runBlocking<Unit> {
        val seed=seedPendingHouseholdHistory(8);val (http,_)=channel {successReply(it)}
        constructAcceptedHouseholdHistory(seed,http);verifyAcceptedHouseholdHistory(seed,http)
        val image=storage.snapshotClosedDatabase()
        for(kind in listOf("early_source","middle_receipt","late_day","extra_receipt")) {
            restore(image)
            when(kind) {
                "early_source"->mutateHouseholdFile("UPDATE next_request_origins SET sourceHash=? WHERE kind=? AND requestId=?",
                    arrayOf("0".repeat(64),NEXT_OPERATION,seed.entries.first().queue.operationId))
                "middle_receipt"->mutateHouseholdFile("UPDATE next_acceptances SET resultHash=? WHERE kind=? AND requestId=?",
                    arrayOf("0".repeat(64),NEXT_OPERATION,seed.entries[3].queue.operationId))
                "late_day"->seed.entries.last().day.let {day->mutateHouseholdFile(
                    "UPDATE count_days SET targetValue=targetValue+1 WHERE habitId=? AND localDate=?",arrayOf<Any>(day.habitId,day.localDate))}
                else->mutateHouseholdFile("INSERT INTO next_acceptances(kind,requestId,originHash,transmissionHash,resultHash,resultJson) "+
                    "SELECT kind,?,originHash,transmissionHash,resultHash,resultJson FROM next_acceptances WHERE kind=? AND requestId=?",
                    arrayOf(id(4902),NEXT_OPERATION,seed.entries.first().queue.operationId))
            }
            // This complete fixture oracle includes retained original source snapshots,
            // exact member closure, original accepted proofs and original full readers.
            rejectedNativeUnchanged {verifyAcceptedHouseholdHistory(seed,http)}
        }
    }

    @Test fun selfConsistentOriginHashRewriteCannotReplaceTheRetainedOriginalSourceProof()=runBlocking<Unit> {
        val seed=seedPendingHouseholdHistory(8);val (http,_)=channel {successReply(it)}
        constructAcceptedHouseholdHistory(seed,http);verifyAcceptedHouseholdHistory(seed,http)
        val entry=seed.entries[3]
        db.withTransaction {
            val sql=db.openHelper.writableDatabase
            sql.execSQL("UPDATE next_request_origins SET sourceHash=? WHERE kind=? AND requestId=?",
                arrayOf<Any>("0".repeat(64),NEXT_OPERATION,entry.queue.operationId))
            val rewritten=requireNotNull(NextRequestSql.rowHash(sql,"next_request_origins","kind=? AND requestId=?",
                arrayOf<Any>(NEXT_OPERATION,entry.queue.operationId)))
            sql.execSQL("UPDATE next_acceptances SET originHash=? WHERE kind=? AND requestId=?",
                arrayOf<Any>(rewritten,NEXT_OPERATION,entry.queue.operationId))
            sql.execSQL("UPDATE count_days SET originHash=? WHERE habitId=? AND localDate=?",
                arrayOf<Any>(rewritten,entry.day.habitId,entry.day.localDate))
        }
        // Queue absence is not the old sourceHash proof. Do not regenerate the seed
        // from this now self-consistent database and call it an independent oracle.
        rejectedNativeUnchanged {verifyAcceptedHouseholdHistory(seed,http)}
        assertEquals(entry.sourceHash,NextRequestSql.sourceHash(entry.queue))
        assertEquals(entry.origin.sourceHash,entry.sourceHash)
    }

    @Test fun originalFullReaderChecksBothSidesOfItsActual128FactBatchBoundary()=runBlocking<Unit> {
        // One 258-fact fixture: 129 per object. This is NOT a 730-fact measurement and
        // does not raise or bypass the application's separate 128-fact G0 Store guard.
        val seed=seedPendingHouseholdHistory(258);val (http,_)=channel {successReply(it)}
        constructAcceptedHouseholdHistory(seed,http)
        val histories=verifyAcceptedHouseholdHistory(seed,http)
        assertTrue(seed.habits.all {histories.getValue(it.uuid).completions.size==129})
        val row=seed.habits.first()
        // This is the exact DAO ordering consumed by RecurringCompletionFactEvidence's
        // facts.chunked(128), not an invented sixteen-row boundary or global entry order.
        val ordered=db.completionDao().getCompletionsByHabit(row.id).first();assertEquals(129,ordered.size)
        val boundary=listOf(ordered[127],ordered[128]);val image=storage.snapshotClosedDatabase()
        for(fact in boundary) {
            restore(image)
            val entry=seed.entries.single {it.fact.uuid==fact.uuid}
            val original=decodeNextOperationIntent(entry.origin.intentJson)
            val changed=original.copy(payload=JsonObject(original.payload+("value" to JsonPrimitive(2))))
            val body=encodeSyncRequest(SyncV2Operation.serializer(),changed).toString(Charsets.UTF_8)
            db.withTransaction {
                val sql=db.openHelper.writableDatabase
                sql.execSQL("UPDATE next_request_origins SET intentJson=? WHERE kind=? AND requestId=?",
                    arrayOf<Any>(body,NEXT_OPERATION,entry.queue.operationId))
                val hash=requireNotNull(NextRequestSql.rowHash(sql,"next_request_origins","kind=? AND requestId=?",
                    arrayOf<Any>(NEXT_OPERATION,entry.queue.operationId)))
                sql.execSQL("UPDATE count_days SET originHash=? WHERE habitId=? AND localDate=?",
                    arrayOf<Any>(hash,entry.day.habitId,entry.day.localDate))
                // Keep the complete day binding valid, so an early stale day hash is
                // not substituted for checking the bad immutable source/fact relation.
                assertEquals(129,NextCountDayStore(db).readForHabit(row).size)
            }
            rejectedNativeUnchanged {CountHistoryReader(db,tokens,sessions).read(row)}
        }
    }
}
