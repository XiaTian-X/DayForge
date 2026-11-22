package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.local.HabitDatabase
import com.dayforge.domain.model.CountHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The frozen object contains bytes, not an authorization or a replacement business proof.
 * The actual original readers still run inside the same cold read boundary. The separate
 * full Household initialization remains the membership/absence oracle on the same image.
 */
@RunWith(AndroidJUnit4::class)
class G0FrozenDomainRejectionTest : G0HouseholdCountFixture() {
    init {
        // This capability test needs the exact gate-owned Room connection. The inherited
        // query-cost probe is an observation wrapper, not part of this security boundary.
        storage.isolatedBuilder={ context ->
            Room.databaseBuilder(context,HabitDatabase::class.java,"habit_database")
                .addCallback(domainOpeningCallback()).openHelperFactory(domainGate.factory).build()
        }
    }
    private data class NativeCell(val type:Int,val value:Any?)
    private data class NativeTable(val columns:List<String>,val rows:List<List<NativeCell>>)
    private data class Captured(val input:G0FrozenDomainInputs,val histories:Map<String,CountHistory>)

    /** Independent read-only observer, never offered as a validator or SQL capability. */
    private fun native():Map<String,NativeTable> = SQLiteDatabase.openDatabase(
        app.getDatabasePath("habit_database").path,null,SQLiteDatabase.OPEN_READONLY
    ).use { raw ->
        G0PersistentCheckpoint.columns.keys.associateWith { table ->
            raw.rawQuery("SELECT * FROM main.`$table`",null).use { cursor ->
                NativeTable(cursor.columnNames.toList(),buildList {
                    while(cursor.moveToNext())add((0 until cursor.columnCount).map { column ->
                        val type=cursor.getType(column)
                        NativeCell(type,when(type) {
                            Cursor.FIELD_TYPE_NULL->null
                            Cursor.FIELD_TYPE_INTEGER->cursor.getLong(column)
                            Cursor.FIELD_TYPE_FLOAT->java.lang.Double.doubleToRawLongBits(cursor.getDouble(column))
                            Cursor.FIELD_TYPE_STRING,Cursor.FIELD_TYPE_BLOB->cursor.getBlob(column).toList()
                            else->error("Unexpected native cell")
                        })
                    })
                })
            }
        }
    }

    private fun restore(image:ByteArray) {
        freshGateForColdOpen(recurringReads=true,countWrites=true,householdCountWrites=true)
        restoreForJourney(image)
    }

    private suspend fun captureAndRead(http:NextSyncHttp,requests:List<String>,maxBytes:Long=32L*1024*1024,
        onCaptured:(G0FrozenDomainInputs)->Unit={}):Captured {
        val schema=trustedDomainSchema(batchedOpening=true)
        freshGateForColdOpen(recurringReads=true,countWrites=true,householdCountWrites=true)
        storage.reopen()
        return sessions.exclusive {
            val lease=authority.lease()
            val core=requireNotNull(tokens.localCoreWriteAccess())
            val registered=access()
            require(core.session==registered.session && core.capturedDeviceId==registered.deviceId)
            authority.checkLease(lease)
            authority.finalPermit(lease).use {
                val captured=db.withTransaction {
                    domainGate.coldRead(db) {
                        val input=G0FrozenDomainInputs.capture(db,domainGate,schema,maxBytes)
                        onCaptured(input)
                        val reader=CountHistoryReader(db,tokens,sessions)
                        val histories=currentHousehold.associate { expected ->
                            val current=requireNotNull(db.habitDao().getHabitById(expected.id))
                            current.uuid to reader.readInTransaction(current,java.time.LocalDate.now())
                        }
                        val original=sender(http)
                        for(request in requests)original.requireAcceptedCountInTransaction(registered,request)
                        require(tokens.isCurrentDisplayReadAuthority(core,registered))
                        authority.checkLease(lease)
                        Captured(input,histories)
                    }
                }
                authority.checkLease(lease)
                captured
            }
        }
    }

    private suspend fun fullHousehold(http:NextSyncHttp):G0HouseholdCountStore.View =
        householdView(openHousehold(http))

    @Test fun completeNativeCaptureKeepsOriginalReadersAndFullHouseholdProofReadOnly()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted()
        val (http,_)=channel {successReply(it)}
        val before=native()
        val decryptBefore=decryptCount.get()
        val captured=captureAndRead(http,seed.requestIds)
        assertTrue("Original fresh checks must actually decrypt",decryptCount.get()>decryptBefore)
        assertEquals(before.keys,captured.input.tables.keys)
        assertEquals(before.values.sumOf {it.rows.size.toLong()},captured.input.scannedRows)
        for((table,rows) in before) {
            val frozen=captured.input.tables.getValue(table)
            assertEquals(table,rows.columns,frozen.columns)
            assertEquals(table,rows.rows.size,frozen.rows.size)
            rows.rows.forEachIndexed { rowIndex,originalRow ->
                val frozenRow=frozen.rows[rowIndex]
                assertEquals("$table row $rowIndex",originalRow.size,frozenRow.cells.size)
                originalRow.forEachIndexed { column,original ->
                    val actual=frozenRow.cells[column]
                    val label="$table row $rowIndex column ${rows.columns[column]}"
                    assertEquals(label,original.type,actual.type)
                    when(original.type) {
                        Cursor.FIELD_TYPE_NULL->{
                            assertNull(label,actual.number)
                            assertNull(label,actual.bytes())
                        }
                        Cursor.FIELD_TYPE_INTEGER,Cursor.FIELD_TYPE_FLOAT->{
                            assertEquals(label,original.value,actual.number)
                            assertNull(label,actual.bytes())
                        }
                        Cursor.FIELD_TYPE_STRING,Cursor.FIELD_TYPE_BLOB->{
                            assertNull(label,actual.number)
                            assertEquals(label,original.value,actual.bytes()?.toList())
                        }
                        else->error("Unexpected native type")
                    }
                }
            }
        }
        assertTrue(captured.input.encodedBytes>0)
        assertEquals(seed.references,captured.histories)
        assertEquals(before,native())
        val view=fullHousehold(http)
        assertEquals(captured.histories,view.cards.mapValues {it.value.history})
        reopenReference()
        sameHouseholdAsOriginal(view)
        assertEquals(before,native())
    }

    @Test fun corruptedEarlyMiddleLateAndExtraMembersRetainOriginalFullRejectionsAfterCapture()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted()
        val (http,_)=channel {successReply(it)}
        val good=captureAndRead(http,seed.requestIds).input.digestHex
        val image=storage.snapshotClosedDatabase()
        for(kind in listOf("early_source","middle_receipt","late_day","extra_receipt","native_type")) {
            restore(image)
            when(kind) {
                "early_source"->mutateHouseholdFile(
                    "UPDATE next_request_origins SET sourceHash=? WHERE requestId=?",
                    arrayOf("0".repeat(64),seed.requestIds.first()))
                "middle_receipt"->mutateHouseholdFile(
                    "UPDATE next_acceptances SET resultHash=? WHERE requestId=?",
                    arrayOf("0".repeat(64),seed.requestIds[3]))
                "late_day"->mutateHouseholdFile(
                    "UPDATE count_days SET targetValue=targetValue+1 WHERE habitId=? AND localDate=?",
                    arrayOf<Any>(seed.habits.last().id,seed.datesByObject.getValue(seed.habits.last().uuid).last().toString()))
                "extra_receipt"->mutateHouseholdFile(
                    "INSERT INTO next_acceptances(kind,requestId,originHash,transmissionHash,resultHash,resultJson) "+
                        "SELECT kind,?,originHash,transmissionHash,resultHash,resultJson FROM next_acceptances WHERE requestId=?",
                    arrayOf(id(6101),seed.requestIds.first()))
                else->mutateHouseholdFile(
                    "UPDATE next_request_origins SET protocol=5.5 WHERE requestId=?",
                    arrayOf(seed.requestIds[3]))
            }
            val before=native()
            val corrupt=storage.snapshotClosedDatabase()
            restore(corrupt)
            val original=rejected {fullHousehold(http)}
            assertEquals(kind,before,native())

            restore(corrupt)
            var observed:G0FrozenDomainInputs?=null
            val readFailure=try {
                captureAndRead(http,seed.requestIds,onCaptured={observed=it});null
            } catch(error:Exception) {
                if(error is CancellationException)throw error
                error
            }
            val frozen=requireNotNull(observed) {"$kind must reach the native capture, not be hidden by coercion"}
            assertNotEquals(kind,good,frozen.digestHex)
            assertEquals(kind,before,native())
            if(kind!="extra_receipt") {
                assertNotNull("$kind must be rejected by the actual original readers inside coldRead",readFailure)
            }
            if(readFailure!=null) {
                assertFalse("A missing SQL capability is not a business rejection: $kind",readFailure is G0DomainSqlGate.Denied)
                assertEquals(kind,original.javaClass,readFailure.javaClass)
                assertEquals(kind,original.message,readFailure.message)
            }
            // An orphan outside a requested reader range still needs the ORIGINAL complete
            // membership audit. A captured digest or successful partial reader grants nothing.
            val afterCapture=rejected {fullHousehold(http)}
            assertEquals(kind,original.javaClass,afterCapture.javaClass)
            assertEquals(kind,original.message,afterCapture.message)
            assertEquals(kind,before,native())
        }
    }

    @Test fun legitimateUnrelatedContentMetadataAndWatermarkChangesAreCapturedWithoutNewBusinessRefusal()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted()
        val (http,_)=channel {successReply(it)}
        val good=captureAndRead(http,seed.requestIds).input.digestHex
        val image=storage.snapshotClosedDatabase()
        for(kind in listOf("unrelated","watermark","metadata_member")) {
            restore(image)
            when(kind) {
                "unrelated"->db.withTransaction {
                    val sql=db.openHelper.writableDatabase
                    sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                    sql.execSQL("UPDATE habits SET description=? WHERE id=?",arrayOf<Any>("Valid unrelated description",seed.habits.first().id))
                    sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
                }
                "watermark"->mutateHouseholdFile("UPDATE sqlite_sequence SET seq=seq+1 WHERE name='sync_outbox'")
                else->mutateHouseholdFile("INSERT INTO android_metadata(locale) VALUES('second')")
            }
            val before=native()
            val captured=captureAndRead(http,seed.requestIds)
            assertNotEquals(kind,good,captured.input.digestHex)
            assertEquals(kind,before.values.sumOf {it.rows.size.toLong()},captured.input.scannedRows)
            val result=fullHousehold(http)
            assertEquals(kind,captured.histories,result.cards.mapValues {it.value.history})
            assertEquals(kind,before,native())
        }
    }

    @Test fun candidateBudgetFailureExitsItsBoundaryBeforeOriginalFullValidationCanContinue()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted()
        val (http,_)=channel {successReply(it)}
        val before=native()
        var offered=false
        val failure=rejected {captureAndRead(http,seed.requestIds,maxBytes=1L,onCaptured={offered=true})}
        assertTrue(failure is G0FrozenDomainInputs.BudgetExceeded)
        assertFalse(offered)
        assertFalse(db.inTransaction())
        assertEquals(before,native())
        val result=fullHousehold(http)
        assertEquals(seed.references,result.cards.mapValues {it.value.history})
        assertEquals(before,native())
    }
}
