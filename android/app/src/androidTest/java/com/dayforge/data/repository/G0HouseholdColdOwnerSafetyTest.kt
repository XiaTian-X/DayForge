package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Existing production-created and HTTP-accepted household facts. Cold input sharing is
 * compared with the unchanged full-read branch; neither successful capture nor END alone
 * is treated as permission to publish. All persistent comparisons use an independent handle.
 */
@RunWith(AndroidJUnit4::class)
class G0HouseholdColdOwnerSafetyTest:G0HouseholdColdOwnerFixture() {
    private data class Cell(val type:Int,val value:Any?)
    private data class Table(val columns:List<String>,val rows:List<List<Cell>>)
    private enum class Arm { ORIGINAL, OWNED_FULL, OWNED_SHARED }

    private fun native():Map<String,Table> = SQLiteDatabase.openDatabase(
        app.getDatabasePath("habit_database").path,null,SQLiteDatabase.OPEN_READONLY
    ).use { raw -> G0PersistentCheckpoint.columns.keys.associateWith { table ->
        raw.rawQuery("SELECT * FROM main.`$table`",null).use { cursor ->
            Table(cursor.columnNames.toList(),buildList {
                while(cursor.moveToNext())add((0 until cursor.columnCount).map { column ->
                    val type=cursor.getType(column)
                    Cell(type,when(type) {
                        Cursor.FIELD_TYPE_NULL->null
                        Cursor.FIELD_TYPE_INTEGER->cursor.getLong(column)
                        Cursor.FIELD_TYPE_FLOAT->java.lang.Double.doubleToRawLongBits(cursor.getDouble(column))
                        Cursor.FIELD_TYPE_STRING,Cursor.FIELD_TYPE_BLOB->cursor.getBlob(column).toList()
                        else->error("Unknown native cell")
                    })
                })
            })
        }
    }}

    private fun restore(image:ByteArray) {
        freshGateForColdOpen(recurringReads=true,countWrites=true,householdCountWrites=true)
        restoreForJourney(image)
    }
    private suspend fun open(arm:Arm,http:NextSyncHttp):G0HouseholdCountStore = when(arm) {
        Arm.ORIGINAL->openHousehold(http)
        else->openOwnedHousehold(http,shareInput=arm==Arm.OWNED_SHARED).also {
            assertEquals(G0RoomColdOwnerFactory.Phase.READY,it.owner.phase)
            assertEquals(1,it.owner.actualEnds)
            assertEquals(arm==Arm.OWNED_SHARED,it.store.sharedColdInputUsed)
        }.store
    }
    private suspend fun failure(block:suspend ()->Unit):Throwable {
        var rejected:Throwable?=null
        try {block()} catch(problem:Throwable) {rejected=problem}
        return requireNotNull(rejected) {"Expected rejection"}
    }
    private fun causes(problem:Throwable)=generateSequence(problem) {it.cause}.toList()
    private fun carries(problem:Throwable,target:Throwable,seen:MutableSet<Throwable> = mutableSetOf()):Boolean {
        if(!seen.add(problem))return false
        return problem===target || problem.cause?.let {carries(it,target,seen)}==true ||
            problem.suppressed.any {carries(it,target,seen)}
    }
    private fun noSqlCapabilityFailure(problem:Throwable) {
        assertFalse("SQL capability failure is not business equivalence: $problem",
            causes(problem).any {it is G0DomainSqlGate.Denied})
    }
    private suspend fun cannotPublish(store:G0HouseholdCountStore) {
        var published=false
        val problem=failure {store.readAndPublish {published=true}}
        assertFalse(published)
        assertEquals("G0_COUNT_RECOVERY_REQUIRED",problem.message)
    }

    @Test fun threeColdArmsPreserveEveryNativeCellAndPublishTheOriginalSharedHouseholdView()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted()
        val changedDescription="A legitimate description after the fixture captured its original objects"
        assertNotEquals(changedDescription,currentHousehold.first().description)
        db.withTransaction {
            val sql=db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            sql.execSQL("UPDATE habits SET description=? WHERE id=?",
                arrayOf<Any>(changedDescription,seed.habits.first().id))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        // Keep currentHousehold's old cached row deliberately. The original and owned paths
        // must select current rows by ID inside their protected snapshot, not reject/borrow it.
        assertNotEquals(changedDescription,currentHousehold.first().description)
        val reference=seed.habits.associate { row ->
            val current=requireNotNull(db.habitDao().getHabitById(row.id))
            current.uuid to CountHistoryReader(db,tokens,sessions).read(current,seed.today)
        }
        val image=storage.snapshotClosedDatabase()
        val (http,_)=channel {successReply(it)}
        var expected:G0HouseholdCountStore.View?=null
        for(arm in Arm.entries) {
            restore(image)
            val before=native()
            val store=open(arm,http)
            val actual=householdView(store)
            assertEquals(reference,actual.cards.mapValues {it.value.history})
            assertEquals(changedDescription,actual.cards.getValue(seed.habits.first().uuid).habit.description)
            expected?.let {assertEquals(it.cards,actual.cards)} ?: run {expected=actual}
            assertEquals(arm.toString(),before,native())
            reopenReference()
            sameHouseholdAsOriginal(actual)
            assertEquals(arm.toString(),before,native())
        }
    }

    @Test fun everyColdArmContinuesRealCountOutboxAckReplayAndOriginalHomeWithoutDuplicatingFacts()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted()
        val image=storage.snapshotClosedDatabase()
        val (http,_)=channel {successReply(it)}
        for(arm in Arm.entries) {
            restore(image)
            val store=open(arm,http)
            val saved=seed.habits.map {store.increase(it.uuid)}
            assertEquals(2,store.pendingRequestIds().size)
            val delivered=saved.map {requireNotNull(store.send(http,it.requestId))}
            delivered.forEach {assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(it))}
            assertTrue(store.pendingRequestIds().isEmpty())
            val after=native()
            delivered.forEach {assertEquals(NextOperationAcceptance.REPLAYED,store.acknowledge(it))}
            assertEquals(after,native())
            val result=householdView(store,saved.associate {it.habitUuid to it.factId})
            for(row in seed.habits)assertEquals(1L,result.cards.getValue(row.uuid).history.todayQuantity)
            assertEquals(10,after.getValue("completions").rows.size)
            assertEquals(10,after.getValue("next_request_origins").rows.size)
            assertEquals(10,after.getValue("next_acceptances").rows.size)
            assertEquals(0,after.getValue("sync_outbox").rows.size)
            reopenReference()
            sameHouseholdAsOriginal(result)
            val original=sender(http)
            delivered.forEach {assertEquals(NextOperationAcceptance.REPLAYED,original.acceptOperation(it))}
            assertEquals(after,native())
        }
    }

    @Test fun earlyMiddleLateExtraTypeAndWatermarkDamageKeepOriginalRejectionAndNoPublication()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted()
        val image=storage.snapshotClosedDatabase()
        val (http,_)=channel {successReply(it)}
        for(kind in listOf("early_source","middle_receipt","late_day","extra_receipt","native_type","sequence")) {
            restore(image)
            when(kind) {
                "early_source"->mutateHouseholdFile("UPDATE next_request_origins SET sourceHash=? WHERE requestId=?",
                    arrayOf("0".repeat(64),seed.requestIds.first()))
                "middle_receipt"->mutateHouseholdFile("UPDATE next_acceptances SET resultHash=? WHERE requestId=?",
                    arrayOf("0".repeat(64),seed.requestIds[3]))
                "late_day"->mutateHouseholdFile(
                    "UPDATE count_days SET targetValue=targetValue+1 WHERE habitId=? AND localDate=?",
                    arrayOf<Any>(seed.habits.last().id,seed.datesByObject.getValue(seed.habits.last().uuid).last().toString()))
                "extra_receipt"->mutateHouseholdFile(
                    "INSERT INTO next_acceptances(kind,requestId,originHash,transmissionHash,resultHash,resultJson) "+
                        "SELECT kind,?,originHash,transmissionHash,resultHash,resultJson FROM next_acceptances WHERE requestId=?",
                    arrayOf(id(6201),seed.requestIds.first()))
                "native_type"->mutateHouseholdFile("UPDATE next_request_origins SET protocol=5.5 WHERE requestId=?",
                    arrayOf(seed.requestIds[3]))
                else->mutateHouseholdFile("UPDATE sqlite_sequence SET seq=0 WHERE name='sync_outbox'")
            }
            val before=native()
            val damaged=storage.snapshotClosedDatabase()
            restore(damaged)
            val original=failure {openHousehold(http)}
            noSqlCapabilityFailure(original)
            assertEquals(kind,before,native())
            for(shared in listOf(false,true)) {
                restore(damaged)
                lateinit var candidate:G0HouseholdCountStore
                val rejected=failure {openOwnedHousehold(http,shared,configure={candidate=it})}
                noSqlCapabilityFailure(rejected)
                assertEquals("$kind/$shared",original.javaClass,rejected.javaClass)
                assertEquals("$kind/$shared",original.message,rejected.message)
                cannotPublish(candidate)
                assertEquals("$kind/$shared",before,native())
            }
        }
    }

    @Test fun finalFreshDecryptFailureCannotActivateEitherOwnedBaseline()=runBlocking<Unit> {
        seedHouseholdAccepted()
        val image=storage.snapshotClosedDatabase()
        val (http,_)=channel {successReply(it)}
        for(shared in listOf(false,true)) {
            restore(image)
            val before=native()
            lateinit var candidate:G0HouseholdCountStore
            var reached=false
            val decryptBefore=decryptCount.get()
            val rejected=try {failure {
                openOwnedHousehold(http,shared,configure={candidate=it;it.beforeColdFinalForTest={
                    reached=true;failDecrypt=true
                }})
            }} finally {failDecrypt=false}
            assertTrue(reached)
            assertTrue(decryptCount.get()>decryptBefore)
            noSqlCapabilityFailure(rejected)
            cannotPublish(candidate)
            assertEquals(before,native())
        }
    }

    @Test fun cancellationAtFreezeOrAfterActualEndDrainsBeforeAReplacementCanRecover()=runBlocking<Unit> {
        seedHouseholdAccepted()
        val image=storage.snapshotClosedDatabase()
        val (http,_)=channel {successReply(it)}
        for(shared in listOf(false,true))for(point in listOf("freeze","after_end")) {
            restore(image)
            val before=native()
            val reached=CompletableDeferred<Unit>()
            var actualEnds=0
            lateinit var candidate:G0HouseholdCountStore
            val work=launch {
                openOwnedHousehold(http,shared,afterActualEnd={actualEnds++},configure={
                    candidate=it
                    val wait:suspend ()->Unit={reached.complete(Unit);awaitCancellation()}
                    if(point=="freeze")it.afterColdFreezeForTest=wait else it.afterColdEndForTest=wait
                })
            }
            try {withTimeout(30_000) {reached.await()}} finally {work.cancelAndJoin()}
            assertEquals("$point/$shared",1,actualEnds)
            cannotPublish(candidate)
            assertEquals(before,native())
            restore(image)
            val recovered=openOwnedHousehold(http,shared)
            householdView(recovered.store)
            assertEquals(before,native())
        }
    }

    @Test fun anExceptionAfterTheActualColdEndKeepsTheOriginalDataButNeverMakesReady()=runBlocking<Unit> {
        seedHouseholdAccepted()
        val image=storage.snapshotClosedDatabase()
        val (http,_)=channel {successReply(it)}
        for(shared in listOf(false,true)) {
            restore(image)
            val before=native()
            val sentinel=IllegalStateException("G0_HOUSEHOLD_COLD_END_RESULT_LOST")
            var actualEnds=0
            lateinit var candidate:G0HouseholdCountStore
            val rejected=failure {openOwnedHousehold(http,shared,
                afterActualEnd={actualEnds++;throw sentinel},configure={candidate=it})}
            assertTrue(causes(rejected).any {it===sentinel})
            assertEquals(1,actualEnds)
            cannotPublish(candidate)
            assertEquals(before,native())
            restore(image)
            householdView(openOwnedHousehold(http,shared).store)
            assertEquals(before,native())
        }
    }

    @Test fun caughtWritesAfterFreezeOrAtFinalValidationCannotChangeTheBaselineOrEscape()=runBlocking<Unit> {
        seedHouseholdAccepted()
        val image=storage.snapshotClosedDatabase()
        val (http,_)=channel {successReply(it)}
        for(shared in listOf(false,true))for(point in listOf("freeze","final")) {
            restore(image)
            val before=native()
            lateinit var candidate:G0HouseholdCountStore
            var denied:Throwable?=null
            val rejected=failure {openOwnedHousehold(http,shared,configure={
                candidate=it
                val attack:suspend ()->Unit={
                    denied=runCatching {db.openHelper.writableDatabase.execSQL(
                        "UPDATE metrics SET name=?,description=?,updatedAt=? WHERE id=?",
                        arrayOf<Any>("Must not mutate","",millis,metric.id))}.exceptionOrNull()
                    assertTrue(denied is G0DomainSqlGate.Denied)
                }
                if(point=="freeze")it.afterColdFreezeForTest=attack else it.beforeColdFinalForTest=attack
            })}
            assertTrue(causes(rejected).any {it is G0DomainSqlGate.Denied})
            assertNotNull(denied)
            cannotPublish(candidate)
            assertEquals(before,native())
        }
    }

    @Test fun lateSqlAfterKnownEndCannotBeCaughtAndUsedToInstallAReadyStore()=runBlocking<Unit> {
        seedHouseholdAccepted()
        val image=storage.snapshotClosedDatabase()
        val (http,_)=channel {successReply(it)}
        for(shared in listOf(false,true)) {
            restore(image)
            val before=native()
            lateinit var candidate:G0HouseholdCountStore
            var denied:Throwable?=null
            val rejected=failure {openOwnedHousehold(http,shared,configure={
                candidate=it
                it.afterColdEndForTest={
                    denied=runCatching {db.openHelper.writableDatabase.execSQL(
                        "UPDATE metrics SET name=?,description=?,updatedAt=? WHERE id=?",
                        arrayOf<Any>("Forbidden after END","",millis,metric.id))}.exceptionOrNull()
                }
            })}
            assertNotNull(denied)
            assertTrue(causes(requireNotNull(denied)).any {
                it is G0DomainSqlGate.Denied || it.message=="G0_COLD_OWNER_ACCESS_DENIED"
            })
            assertTrue(causes(rejected).any {
                it is G0DomainSqlGate.Denied || it.message=="G0_COLD_OWNER_ACCESS_DENIED"
            })
            cannotPublish(candidate)
            assertEquals(before,native())
        }
    }

    @Test fun externalCommitInTheEndToSealGapMustBlockTheFirstPersonalPublication()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted()
        val image=storage.snapshotClosedDatabase()
        val (http,_)=channel {successReply(it)}
        for(shared in listOf(false,true)) {
            restore(image)
            var externalCommitted=false
            var opened:Opened?=null
            val initialization=try {
                opened=openOwnedHousehold(http,shared,configure={it.afterColdEndForTest={
                    mutateHouseholdFile("UPDATE next_acceptances SET resultHash=? WHERE requestId=?",
                        arrayOf("changed-after-actual-end",seed.requestIds.first()))
                    externalCommitted=true
                }})
                null
            } catch(problem:Exception) {
                if(problem is CancellationException)throw problem
                problem
            }
            assertTrue(externalCommitted)
            val afterExternal=native()
            var published=false
            val rejected=initialization ?: failure {requireNotNull(opened).store.readAndPublish {published=true}}
            assertFalse(published)
            assertTrue(causes(rejected).any {it.message=="G0_DOMAIN_EXTERNAL_CHANGE"})
            assertEquals(afterExternal,native())
        }
    }

    @Test fun authorityReservationDuringColdValidationCannotRebaseItsCapturedLease()=runBlocking<Unit> {
        seedHouseholdAccepted()
        val image=storage.snapshotClosedDatabase()
        val (http,_)=channel {successReply(it)}
        for(shared in listOf(false,true)) {
            register()
            restore(image)
            val before=native()
            val reserved=CompletableDeferred<Unit>()
            authority.onTransitionForTest={reserved.complete(Unit)}
            var mutation:Deferred<Unit>?=null
            lateinit var candidate:G0HouseholdCountStore
            val rejected=try {failure {
                openOwnedHousehold(http,shared,configure={
                    candidate=it
                    it.beforeColdFinalForTest={
                        mutation=async(Dispatchers.IO) {tokens.saveDeviceRegistration(id(4),caps,true,2)}
                        withTimeout(10_000) {reserved.await()}
                        assertFalse(requireNotNull(mutation).isCompleted)
                    }
                })
            }} finally {
                authority.onTransitionForTest={}
                mutation?.await()
            }
            assertTrue(causes(rejected).any {it is G0AuthorityOwner.Denied})
            cannotPublish(candidate)
            assertEquals(before,native())
        }
    }

    @Test fun onlyAKnownBudgetRollbackCanStartOneFullReadFallbackAndUnknownEndCannotRetry()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted()
        val image=storage.snapshotClosedDatabase()
        val (http,_)=channel {successReply(it)}
        restore(image)
        val before=native()
        var actualEnds=0
        val attempts=mutableListOf<G0HouseholdCountStore>()
        val opened=openOwnedHousehold(http,true,maxInputBytes=1L,
            afterActualEnd={actualEnds++},configure={attempts+=it})
        assertTrue(opened.budgetFallback)
        assertFalse(opened.store.sharedColdInputUsed)
        assertEquals(2,attempts.size)
        assertSame(attempts.last(),opened.store)
        assertEquals(2,actualEnds)
        assertEquals(1,opened.owner.actualEnds)
        assertEquals(G0RoomColdOwnerFactory.Phase.READY,opened.owner.phase)
        cannotPublish(attempts.first())
        assertEquals(seed.references,householdView(opened.store).cards.mapValues {it.value.history})
        assertEquals(before,native())

        restore(image)
        actualEnds=0
        attempts.clear()
        val sentinel=IllegalStateException("G0_BUDGET_ROLLBACK_END_RESULT_LOST")
        val rejected=failure {openOwnedHousehold(http,true,maxInputBytes=1L,
            afterActualEnd={actualEnds++;throw sentinel},configure={attempts+=it})}
        assertTrue("The actual END failure must remain in the error graph",carries(rejected,sentinel))
        assertEquals("Unknown END cannot enter the fallback constructor",1,attempts.size)
        assertEquals(1,actualEnds)
        cannotPublish(attempts.single())
        assertEquals(before,native())
    }
}
