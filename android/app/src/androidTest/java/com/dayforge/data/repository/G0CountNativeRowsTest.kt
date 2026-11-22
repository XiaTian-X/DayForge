package com.dayforge.data.repository

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.SYNC_REQUEST_LIMIT
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Physical SQLite primitive tests, deliberately without Room affinity/unique constraints so
 * malformed native types and duplicate members can be injected. This is not a business oracle,
 * authentication test, migration, or a writable-root qualification. Those use the real fixture. */
@RunWith(AndroidJUnit4::class)
class G0CountNativeRowsTest {
    private val first=HabitEntity(id=1,name="one",habitType=HabitType.COUNTING,iconResId=1,colorHex="#000000",
        schedule=HabitSchedule.Daily,uuid="00000000-0000-4000-8000-000000000001",completionPolicy="recurring",
        activityRateUpdatedAt=1000,createdAt=1000,updatedAt=1000)
    private val second=first.copy(id=2,name="two",uuid="00000000-0000-4000-8000-000000000002")
    private val fact=CompletionEntity(id=1,habitId=1,date=1000,value=1,actualCompletedAt=1000,
        uuid="00000000-0000-4000-8000-000000000003",habitUuid=first.uuid,createdAt=1000,
        recordedTimezone="UTC",recordedLocalDate="2026-10-01")
    private val queue=SyncOutboxEntity(id=1,operationId="00000000-0000-4000-8000-000000000004",
        recordType="completion",entityUuid=fact.uuid,wireEntityUuid=fact.uuid,action="upsert",referenceUuid=first.uuid,createdAt=1000)
    private val origin=NextRequestOriginEntity(NEXT_OPERATION,queue.operationId,1,5,"account","server","epoch","a".repeat(64),"{\"payload\":{\"activity_uuid\":\"${first.uuid}\",\"local_date\":\"${fact.recordedLocalDate}\"}}")
    private val source=G0CountBusinessBlockCodec.Source(origin,"b".repeat(64),pending=queue)
    private val day=CountDayEntity(1,first.uuid,fact.recordedLocalDate,1,false,fact.uuid,
        originRequestId=queue.operationId,originHash=source.originHash,planQueueWatermark=0,capturedDeviceId="device")

    @Test fun completeCurrentDaySourceAndAbsenceWitnessRoundTripWithoutWrites()=store { db->
        db.transaction {
            db.seed()
            val session=G0CountNativeRows.Session(db.observed)
            session.requireCurrent(listOf(first,second),listOf(queue),1,1)
            session.requireDay(1,first.uuid,day.localDate,day,listOf(fact))
            session.requireDayRow(day)
            session.requireSource(source,fact)
            session.requireAbsent(2,"unused-fact",2,"unused-request")
            val before=db.image()
            session.freeze().verify(db.observed)
            assertEquals(before,db.image())
        }
    }

    @Test fun nativeJsonSpellingIsRetainedAndRevalidationRejectsEquivalentRewrite()=store {db->
        db.transaction {
            db.seed()
            db.raw.execSQL("UPDATE habits SET schedule=? WHERE id=1",arrayOf(" { \"type\" : \"daily\" } "))
            val session=G0CountNativeRows.Session(db.observed)
            val row=session.requireHabit(first)
            assertEquals(" { \"type\" : \"daily\" } ",row[6].value)
            val witness=session.freeze()
            witness.verify(db.observed)
            db.raw.execSQL("UPDATE habits SET schedule=? WHERE id=1",arrayOf(HabitTypeConverter().fromSchedule(first.schedule)))
            failure("G0_COUNT_NATIVE_CHANGED") {witness.verify(db.observed)}
        }
    }

    @Test fun wrongStorageTypeNullAndOutOfRangeIntegersAreRejected() {
        val cases:List<Pair<String,Any?>> = listOf("targetValue" to 1.5,"targetValue" to "1","targetValue" to null,
            "targetValue" to (Int.MAX_VALUE.toLong()+1),"isActive" to 2L,"name" to byteArrayOf(65))
        for((column,value) in cases)store {db->db.transaction {
            db.seed();db.raw.execSQL("UPDATE habits SET `$column`=? WHERE id=1",arrayOf<Any?>(value))
            failure {G0CountNativeRows.Session(db.observed).requireHabit(first)}
        }}
    }

    @Test fun invalidUtf8CannotBecomeAnEquivalentReplacementCharacterString()=store {db->
        db.transaction {
            db.seed();db.raw.execSQL("UPDATE habits SET name=CAST(X'80' AS TEXT) WHERE id=1")
            failure("G0_COUNT_NATIVE_TEXT_BYTES") {G0CountNativeRows.Session(db.observed).rows(G0CountNativeRows.habitId,1L)}
        }
    }

    @Test fun embeddedNulAndMultibyteTextRoundTripWithoutTruncation()=store {db->
        db.transaction {
            db.seed();val changed=first.copy(description="汉字\u0000é")
            db.raw.execSQL("UPDATE habits SET description=? WHERE id=1",arrayOf(changed.description))
            val row=G0CountNativeRows.Session(db.observed).requireHabit(changed)
            assertEquals(changed.description,row[2].value)
        }
    }

    @Test fun oversizedRowsFailBeforeAnyValueProjectionMaterializesTheContent()=store {db->
        db.transaction {
            db.seed();db.raw.execSQL("UPDATE habits SET description=? WHERE id=1",arrayOf("é".repeat(SYNC_REQUEST_LIMIT)))
            db.valueProjections=0
            failure("G0_COUNT_NATIVE_ROW_BYTES") {G0CountNativeRows.Session(db.observed).requireHabit(first)}
            assertEquals(0,db.valueProjections)
        }
    }

    @Test fun duplicateUuidOutsideTouchedDayStillRejectsTheTouchedFact()=store {db->
        db.transaction {
            db.seed();db.insert("completions",G0CountNativeRows.factValues(fact.copy(id=9,recordedLocalDate="2026-09-01")))
            failure("G0_COUNT_NATIVE_ROW_LIMIT") {
                G0CountNativeRows.Session(db.observed).requireDay(1,first.uuid,day.localDate,day,listOf(fact))
            }
        }
    }

    @Test fun bothOwnershipRangesDetectExtraFactsAndWrongOwnerIds()=store {db->
        db.transaction {
            db.seed();db.insert("completions",G0CountNativeRows.factValues(fact.copy(id=9,habitId=2,uuid="other-fact")))
            failure("G0_COUNT_NATIVE_MISMATCH") {
                G0CountNativeRows.Session(db.observed).requireDay(1,first.uuid,day.localDate,day,listOf(fact))
            }
        }
    }

    @Test fun currentMembershipDoesNotHideAdditionalHabitsOrDeadLetteredQueues() {
        for(extraHabit in listOf(true,false))store {db->db.transaction {
            db.seed()
            if(extraHabit)db.insert("habits",habitValues(first.copy(id=3,name="three",uuid="third-habit")))
            else db.insert("sync_outbox",G0CountNativeRows.queueValues(queue.copy(id=2,operationId="extra-request",entityUuid="extra-fact",deadLetteredAt=1001)))
            failure {G0CountNativeRows.Session(db.observed).requireCurrent(listOf(first,second),listOf(queue),1,1)}
        }}
    }

    @Test fun currentQueueSentinelRejectsOverBudgetWithoutLoadingRowValues()=store {db->
        db.transaction {
            db.seed()
            repeat(128) {i->db.insert("sync_outbox",G0CountNativeRows.queueValues(queue.copy(id=i+2L,operationId="extra-$i",entityUuid="fact-$i")))}
            db.valueProjections=0
            failure("G0_COUNT_NATIVE_ROW_LIMIT") {G0CountNativeRows.Session(db.observed).rows(G0CountNativeRows.currentQueues)}
            assertEquals(0,db.valueProjections)
        }
    }

    @Test fun sequenceRegressionDuplicatesNegativeAndWrongTypeAreRejected() {
        for(mode in 0..3)store {db->db.transaction {
            db.seed()
            when(mode) {
                0->db.raw.execSQL("UPDATE sqlite_sequence SET seq=0 WHERE name='completions'")
                1->db.raw.execSQL("INSERT INTO sqlite_sequence(name,seq) VALUES ('completions',1)")
                2->db.raw.execSQL("UPDATE sqlite_sequence SET seq=-1 WHERE name='completions'")
                else->db.raw.execSQL("UPDATE sqlite_sequence SET seq='bad' WHERE name='completions'")
            }
            failure {G0CountNativeRows.Session(db.observed).requireCurrent(listOf(first,second),listOf(queue),1,1)}
        }}
    }

    @Test fun changedControlAndRecoveryPresenceCannotBeSilentlyCleared() {
        for(recovery in listOf(false,true))store {db->db.transaction {
            db.seed()
            if(recovery)db.raw.execSQL("INSERT INTO next_recovery_state DEFAULT VALUES")
            else db.raw.execSQL("UPDATE sync_control SET suppressOutbox=1")
            val before=db.image()
            failure {G0CountNativeRows.Session(db.observed).requireCurrent(listOf(first,second),listOf(queue),1,1)}
            assertEquals(before,db.image())
        }}
    }

    @Test fun sourceRequiresBothRequestAndQueueIdentitiesAndNoExtraShadow() {
        for(mode in 0..2)store {db->db.transaction {
            db.seed()
            when(mode) {
                0->db.insert("next_request_origins",G0CountNativeRows.originValues(origin.copy(requestId="other-request")))
                1->db.insert("sync_outbox",G0CountNativeRows.queueValues(queue.copy(id=2,operationId="other-request")))
                else->db.insert("sync_entity_state",G0CountNativeRows.shadowValues(SyncEntityStateEntity("activity_event",fact.uuid,1,updatedAt=1000)))
            }
            failure {G0CountNativeRows.Session(db.observed).requireSource(source,fact)}
        }}
    }

    @Test fun newCommandAbsenceCoversFactSourceQueueTransmissionReceiptAndShadow() {
        for(table in listOf("completions","sync_outbox","next_request_origins","next_transmissions","next_acceptances","sync_entity_state"))store {db->db.transaction {
            db.seed();val f=fact.copy(id=2,uuid="new-fact");val q=queue.copy(id=2,operationId="new-request",entityUuid=f.uuid,wireEntityUuid=f.uuid)
            val o=origin.copy(requestId=q.operationId,queueId=2)
            val values=when(table) {
                "completions"->G0CountNativeRows.factValues(f)
                "sync_outbox"->G0CountNativeRows.queueValues(q)
                "next_request_origins"->G0CountNativeRows.originValues(o)
                "next_transmissions"->G0CountNativeRows.transmissionValues(NextTransmissionEntity(NEXT_OPERATION,q.operationId,2,5,"account","server","epoch","device","c".repeat(64),byteArrayOf(1)))
                "next_acceptances"->G0CountNativeRows.receiptValues(NextAcceptanceEntity(NEXT_OPERATION,q.operationId,"a","b","c","{}"))
                else->G0CountNativeRows.shadowValues(SyncEntityStateEntity("activity_event",f.uuid,1,updatedAt=1000))
            }
            db.insert(table,values)
            failure {G0CountNativeRows.Session(db.observed).requireAbsent(2,f.uuid,2,q.operationId)}
        }}
    }

    @Test fun witnessRechecksAbsenceMembershipValuesAndRecoveryAfterPreparation() {
        for(mode in 0..3)store {db->db.transaction {
            db.seed();val session=G0CountNativeRows.Session(db.observed)
            session.requireCurrent(listOf(first,second),listOf(queue),1,1)
            session.requireAbsent(2,"new-fact",2,"new-request")
            val witness=session.freeze()
            when(mode) {
                0->db.raw.execSQL("UPDATE habits SET name='changed' WHERE id=1")
                1->db.raw.execSQL("DELETE FROM sync_outbox")
                2->db.insert("completions",G0CountNativeRows.factValues(fact.copy(id=2,uuid="new-fact")))
                else->db.raw.execSQL("INSERT INTO next_recovery_state DEFAULT VALUES")
            }
            failure {witness.verify(db.observed)}
        }}
    }

    @Test fun witnessesFreezeBlobCellsAndRespectCancellationAndTransactionLifetime()=store {db->
        db.transaction {
            db.seed()
            val wire=byteArrayOf(1,2,3)
            db.insert("next_transmissions",G0CountNativeRows.transmissionValues(NextTransmissionEntity(NEXT_OPERATION,queue.operationId,1,5,"account","server","epoch","device","c".repeat(64),wire)))
            val session=G0CountNativeRows.Session(db.observed)
            val rows=session.rows(G0CountNativeRows.transmission,NEXT_OPERATION,queue.operationId)
            wire[0]=99
            assertEquals(listOf<Byte>(1,2,3),rows.single()[9].value)
            failure {
                @Suppress("UNCHECKED_CAST")
                val retained=rows.single()[9].value as MutableList<Byte>
                retained[0]=99
            }
            val witness=session.freeze()
            failure {session.rows(G0CountNativeRows.transmission,NEXT_OPERATION,queue.operationId)}
            val cancelled=CancellationException("native poll")
            assertSame(cancelled,failure {witness.verify(db.observed) {throw cancelled}})
            witness.verify(db.observed)
        }
        val idle=G0CountNativeRows.Session(db.observed)
        failure("G0_COUNT_NATIVE_TRANSACTION_REQUIRED") {idle.rows(G0CountNativeRows.habitId,1L)}
    }

    @Test fun dateRangeQueriesUseTheDedicatedIndexesAndMissingIndexFailsClosed()=store {db->
        db.transaction {
            db.seed()
            for((query,args) in listOf(G0CountNativeRows.factsDayId to arrayOf<Any?>(1L,day.localDate),
                G0CountNativeRows.factsDayUuid to arrayOf<Any?>(first.uuid,day.localDate))) {
                val plan=db.raw.query("EXPLAIN QUERY PLAN ${query.select}",args).use {c->buildList {while(c.moveToNext())add(c.getString(3))}.joinToString(" ")}
                assertTrue(plan.uppercase().contains("SEARCH"));assertFalse(plan.uppercase().contains("SCAN"))
            }
            db.raw.execSQL("DROP INDEX ${G0CountNativeRows.DAY_ID_INDEX}")
            failure {G0CountNativeRows.Session(db.observed).requireDay(1,first.uuid,day.localDate,day,listOf(fact))}
        }
    }

    private fun failure(message:String?=null,body:()->Unit):Throwable {
        val error=try {body();null} catch(error:Throwable) {error}
        assertNotNull("expected native rejection",error)
        if(message!=null)assertTrue("expected $message, got $error",requireNotNull(error).message.orEmpty().contains(message))
        return requireNotNull(error)
    }
    private fun store(body:(Store)->Unit) {Store().use(body)}
    private fun habitValues(h:HabitEntity):List<Any?> {
        val c=HabitTypeConverter()
        return listOf(h.id,h.name,h.description,c.fromHabitType(h.habitType),h.iconResId,h.colorHex,c.fromSchedule(h.schedule),h.targetValue,
            if(h.isCountdown)1L else 0L,if(h.isActive)1L else 0L,h.uuid,h.parentHabitId,h.targetCycles,c.fromFailMode(h.failMode),
            h.goalSuccess?.let {if(it)1L else 0L},h.activityRate,h.activityRateUpdatedAt,h.bestTime,h.createdAt,h.updatedAt,
            h.completionPolicy,h.oneTimeConfirmedVersion,h.oneTimeConfirmedHeadEventUuid,h.oneTimeConfirmedCompletionEventUuid,
            c.fromAppearance(h.appearance),c.fromPlanMetadata(h.planMetadata))
    }
    private inner class Store:AutoCloseable {
        private val app=ApplicationProvider.getApplicationContext<Context>()
        private val helper=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(app)
            .name(null).callback(object:SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db:SupportSQLiteDatabase) {
                    for((name,columns) in G0DomainSqlCatalog.columns)db.execSQL("CREATE TABLE `$name` ("+columns.joinToString(",") {"`$it`"}+")")
                    db.execSQL("CREATE TABLE native_sequence_anchor (id INTEGER PRIMARY KEY AUTOINCREMENT)")
                    G0CountSourceScopeSql.requireCapability(db)
                    G0CountNativeRows.createIndexes.forEach(db::execSQL)
                }
                override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int)=error("no migration")
            }).build())
        val raw=helper.writableDatabase
        var valueProjections=0
        val observed:SupportSQLiteDatabase=object:SupportSQLiteDatabase by raw {
            override fun query(query:String,bindArgs:Array<out Any?>):Cursor {
                if(query.startsWith("SELECT `"))valueProjections++
                return raw.query(query,bindArgs)
            }
        }
        fun transaction(body:()->Unit) {
            raw.beginTransaction()
            try {body()} finally {raw.endTransaction()}
        }
        fun insert(table:String,values:List<Any?>) {
            val columns=G0DomainSqlCatalog.columns.getValue(table)
            assertEquals(columns.size,values.size)
            raw.execSQL("INSERT INTO `$table` ("+columns.joinToString(",") {"`$it`"}+") VALUES ("+values.joinToString(",") {"?"}+")",values.toTypedArray())
        }
        fun seed() {
            insert("habits",habitValues(first));insert("habits",habitValues(second))
            insert("completions",G0CountNativeRows.factValues(fact));insert("count_days",G0CountNativeRows.dayValues(day))
            insert("sync_outbox",G0CountNativeRows.queueValues(queue));insert("next_request_origins",G0CountNativeRows.originValues(origin))
            insert("sync_control",listOf(1L,0L))
            raw.execSQL("INSERT INTO sqlite_sequence(name,seq) VALUES ('completions',1),('sync_outbox',1)")
        }
        fun image():List<String> = (G0DomainSqlCatalog.columns.keys+"sqlite_sequence").sorted().flatMap {table->
            raw.query("SELECT * FROM `$table` ORDER BY rowid").use {c->buildList {
                while(c.moveToNext())add(table+":"+(0 until c.columnCount).joinToString("|") {i->
                    "${c.getType(i)}:"+when(c.getType(i)) {Cursor.FIELD_TYPE_NULL->"null";Cursor.FIELD_TYPE_BLOB->c.getBlob(i).joinToString(",");else->c.getString(i)}
                })
            }}
        }
        override fun close()=helper.close()
    }
}
