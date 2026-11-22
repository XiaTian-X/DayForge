package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.NextReplicaFenceSql
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native-input tests only. Synthetic Q/H rows are deliberately NOT signed business
 * certificates. No staged index is activated, no Store is installed, and no witness is
 * issued here. Real whole-count continuation belongs to HouseholdCertificateSafetyTest.
 */
@RunWith(AndroidJUnit4::class)
class G0CountCertificateInputTest:G0SealedDomainFixture() {
    private data class Reference(val schema:List<List<String?>>,val identity:List<Pair<Long,String>>) {
        val main get()=schema.filter {it[0]=="main"}.map {
            G0SchemaRow(requireNotNull(it[1]),requireNotNull(it[2]),requireNotNull(it[3]),it[4])
        }
    }
    private data class NativeCell(val type:Int,val number:Long?,val bytes:List<Byte>?)
    private data class NativeTable(val columns:List<String>,val rows:List<List<NativeCell>>)
    private data class NativeState(val schema:List<List<String?>>,val tables:Map<String,NativeTable>)

    /** Independent code-created reference, never inferred from a damaged candidate. */
    private suspend fun reference():Reference {
        val value=Room.inMemoryDatabaseBuilder(app,HabitDatabase::class.java)
            .addCallback(domainOpeningCallback()).build()
        return try {
            value.openHelper.writableDatabase
            value.withTransaction {
                val sql=value.openHelper.writableDatabase
                sql.execSQL(G0CountCertificateSql.create)
                sql.execSQL(G0CountCertificateSql.chunkCreate)
                NextReplicaFenceSql.install(sql)
                Reference(G0DomainJournal.trustedSchema(sql),sql.query(
                    "SELECT id,identity_hash FROM room_master_table ORDER BY id"
                ).use {cursor->buildList {while(cursor.moveToNext())add(cursor.getLong(0) to cursor.getString(1))}})
            }
        } finally {value.close()}
    }
    private fun reopen(scope:Boolean=true,owner:G0RoomColdOwnerFactory?=null) {
        freshGateForColdOpen(recurringReads=true,countWrites=true,householdCountWrites=true,
            coldLifecycle=owner,countCertificateScope=scope)
        storage.reopen()
    }
    private suspend fun prepare():Reference {
        val expected=reference()
        db.withTransaction {
            val sql=db.openHelper.writableDatabase
            sql.execSQL(G0CountCertificateSql.create)
            sql.execSQL(G0CountCertificateSql.chunkCreate)
            NextReplicaFenceSql.install(sql)
        }
        reopen()
        return expected
    }
    private suspend fun capture(schema:List<List<String?>>,scope:Boolean=true,
        budget:Long=G0FrozenDomainInputs.DEFAULT_MAX_BYTES):G0FrozenDomainInputs =
        db.withTransaction {domainGate.coldRead(db) {
            G0FrozenDomainInputs.capture(db,domainGate,schema,budget,scope)
        }}
    private fun header(length:Long,count:Long):Array<Any?> = arrayOf<Any?>(
        1L,1L,"a".repeat(64),"b".repeat(64),"c".repeat(64),length,count,"d".repeat(64),ByteArray(32)
    )
    private fun putPacket(parts:List<ByteArray>) {
        val sql=db.openHelper.writableDatabase
        sql.execSQL(G0CountCertificateSql.insertHeader,header(parts.sumOf {it.size.toLong()},parts.size.toLong()))
        parts.forEachIndexed {index,bytes->sql.execSQL(G0CountCertificateSql.insertChunk,arrayOf<Any>(index.toLong(),bytes))}
    }
    private fun assertSameRoots(expected:G0FrozenDomainInputs,actual:G0FrozenDomainInputs) {
        assertArrayEquals(expected.digest(),actual.digest())
        assertNotNull(expected.textDigest());assertArrayEquals(expected.textDigest(),actual.textDigest())
    }
    private fun assertDifferentRoots(expected:G0FrozenDomainInputs,actual:G0FrozenDomainInputs) {
        assertFalse(expected.digest().contentEquals(actual.digest()))
        assertFalse(requireNotNull(expected.textDigest()).contentEquals(requireNotNull(actual.textDigest())))
    }
    private fun native():NativeState = SQLiteDatabase.openDatabase(
        app.getDatabasePath("habit_database").path,null,SQLiteDatabase.OPEN_READONLY
    ).use {sql->
        val schema=sql.rawQuery(G0PersistentCheckpoint.SCHEMA_SQL,null).use {cursor->buildList {
            while(cursor.moveToNext())add(listOf("main")+(0..3).map {if(cursor.isNull(it))null else cursor.getString(it)})
        }}
        val tables=schema.filter {it[1]=="table"}.associate {row->
            val name=requireNotNull(row[2])
            name to sql.rawQuery("SELECT * FROM main.`${name.replace("`","``")}`",null).use {cursor->
                NativeTable(cursor.columnNames.toList(),buildList {while(cursor.moveToNext())add(
                    (0 until cursor.columnCount).map {column->
                        val type=cursor.getType(column)
                        NativeCell(type,when(type) {
                            Cursor.FIELD_TYPE_INTEGER->cursor.getLong(column)
                            Cursor.FIELD_TYPE_FLOAT->java.lang.Double.doubleToRawLongBits(cursor.getDouble(column))
                            else->null
                        },if(type==Cursor.FIELD_TYPE_STRING || type==Cursor.FIELD_TYPE_BLOB)cursor.getBlob(column).toList() else null)
                    }
                )})
            }
        }
        NativeState(schema,tables)
    }
    private fun restore(image:ByteArray) {
        freshGateForColdOpen(recurringReads=true,countWrites=true,householdCountWrites=true,countCertificateScope=true)
        storage.restoreClosedDatabase(image)
        storage.reopen() // restore's production builder is never used for a capture capability.
    }

    @Test fun optionalRowsAreFullyCapturedButNeitherChangePrimaryRootsNorEnterBusinessIndex()=runBlocking<Unit> {
        val expected=prepare();val empty=capture(expected.schema)
        val installedFences=expected.schema.filter {it[1]=="trigger" && it[3] in G0CountCertificateSql.tables.keys}
        assertEquals(6,installedFences.size)
        assertEquals(G0CountCertificateSql.replicaFenceTriggers,
            installedFences.associate {requireNotNull(it[2]) to requireNotNull(it[4])})
        assertEquals(expected.schema.filter {it[0]=="main"},empty.schema)
        val first=byteArrayOf(0,1,0x7f,0x80.toByte(),0xff.toByte())
        val second=byteArrayOf(6,5,4,3,2,1)
        putPacket(listOf(first,second))
        val full=capture(expected.schema)
        assertSameRoots(empty,full)
        assertEquals(empty.scannedRows+3,full.scannedRows)
        assertTrue(full.encodedBytes>empty.encodedBytes)
        assertEquals(G0PersistentCheckpoint.columns.keys+G0CountCertificateSql.tables.keys,full.tables.keys)
        assertEquals(1,full.tables.getValue(G0CountCertificateSql.table).rows.size)
        val chunks=full.tables.getValue(G0CountCertificateSql.chunkTable)
        assertEquals(G0CountCertificateSql.chunkColumns,chunks.columns)
        assertEquals(2,chunks.rows.size)
        fun bytes(index:Long)=chunks.rows.single {it.cells[0].number==index}.cells[1].bytes()
        assertArrayEquals(first,bytes(0));assertArrayEquals(second,bytes(1))
        requireNotNull(bytes(0)).fill(0);assertArrayEquals(first,bytes(0))
        val textRoot=requireNotNull(full.textDigest());requireNotNull(full.textDigest()).fill(0)
        assertArrayEquals(textRoot,full.textDigest())

        // Include an orphan chunk and a mistyped optional header. Capture is lossless;
        // the packet matcher, not a filtered SELECT or business index, must reject it.
        val sql=db.openHelper.writableDatabase
        sql.execSQL("UPDATE g0_count_certificate SET format=1.5 WHERE slot=1")
        sql.execSQL("UPDATE g0_count_image_chunks SET content=? WHERE chunkIndex=0",arrayOf(byteArrayOf(9,8,7)))
        sql.execSQL(G0CountCertificateSql.insertChunk,arrayOf<Any>(7L,byteArrayOf(42)))
        val changed=capture(expected.schema)
        assertSameRoots(full,changed);assertEquals(full.scannedRows+1,changed.scannedRows)
        assertEquals(Cursor.FIELD_TYPE_FLOAT,changed.tables.getValue(G0CountCertificateSql.table).rows.single().cells[1].type)
        assertEquals(3,changed.tables.getValue(G0CountCertificateSql.chunkTable).rows.size)
        assertArrayEquals(first,bytes(0)) // earlier captured value cannot track later SQLite writes.

        val staged=G0DomainProofIndex.stageFrozen(changed)
        assertSame(changed,staged.input)
        assertEquals(1,staged.tableCounts.getValue(G0CountCertificateSql.table))
        assertEquals(3,staged.tableCounts.getValue(G0CountCertificateSql.chunkTable))
        assertEquals(changed.scannedRows,staged.tableCounts.values.sumOf {it.toLong()})
        for(table in G0CountCertificateSql.tables.keys) {
            assertNull(staged.row(G0DomainProofIndex.Key(table,listOf(G0DomainJournal.Cell(Cursor.FIELD_TYPE_INTEGER,1L)))))
            assertTrue(runCatching {staged.members(table)}.exceptionOrNull() is NoSuchElementException)
        }
        db.withTransaction {domainGate.coldRead(db) {
            val original=G0DomainProofIndex.captureVerifiedBaseline(db.openHelper.writableDatabase)
            for(table in G0DomainSqlCatalog.columns.keys)for(key in staged.members(table))
                assertEquals(table,original.row(key),staged.row(key))
        }}
    }

    @Test fun protectedValuesNativeTypesMembersAndWatermarksInvalidateBothRoots()=runBlocking<Unit> {
        val expected=prepare();var previous=capture(expected.schema)
        val sql=db.openHelper.writableDatabase
        for(statement in listOf(
            "UPDATE metrics SET description='new protected description' WHERE id=${metric.id}",
            "INSERT INTO sqlite_sequence(name,seq) VALUES ('g0-certificate-type',1.0)",
            "UPDATE sqlite_sequence SET seq=1 WHERE name='g0-certificate-type'",
            "INSERT INTO android_metadata(locale) VALUES ('g0-duplicate'),('g0-duplicate')",
            "DELETE FROM android_metadata WHERE rowid=(SELECT MAX(rowid) FROM android_metadata WHERE locale='g0-duplicate')",
            "UPDATE sqlite_sequence SET seq=seq+1 WHERE name='metrics'")) {
            sql.execSQL(statement)
            val current=capture(expected.schema)
            assertDifferentRoots(previous,current);previous=current
        }
        assertEquals(1,previous.tables.getValue("android_metadata").rows.count {it.cells.single().text=="g0-duplicate"})
        val type=previous.tables.getValue("sqlite_sequence").rows.single {it.cells[0].text=="g0-certificate-type"}.cells[1]
        assertEquals(Cursor.FIELD_TYPE_INTEGER,type.type);assertEquals(1L,type.number)
    }

    @Test fun textRootPreservesCursorObservationAndExactUtf16RatherThanReplacementEncoding()=runBlocking<Unit> {
        val expected=prepare();val sql=db.openHelper.writableDatabase
        sql.execSQL("UPDATE metrics SET description=CAST(X'80ff0061' AS TEXT),unit=X'80ff0061' WHERE id=?",arrayOf(metric.id))
        val observed=sql.query("SELECT * FROM metrics WHERE id = ? LIMIT 1",arrayOf(metric.id)).use {cursor->
            assertTrue(cursor.moveToFirst());val d=cursor.getColumnIndexOrThrow("description")
            Triple(cursor.getString(d),cursor.getBlob(d),cursor.getBlob(cursor.getColumnIndexOrThrow("unit")))
        }
        val input=capture(expected.schema);val row=input.tables.getValue("metrics").rows.single().cells
        val columns=G0DomainSqlCatalog.columns.getValue("metrics")
        assertEquals(observed.first,row[columns.indexOf("description")].text)
        assertArrayEquals(observed.second,row[columns.indexOf("description")].bytes())
        assertArrayEquals(observed.third,row[columns.indexOf("unit")].bytes())
        assertNull(row[columns.indexOf("unit")].text);assertNotNull(input.textDigest())

        fun encoded(cell:G0FrozenDomainInputs.Cell,semantic:Boolean):ByteArray=ByteArrayOutputStream().also {bytes->
            DataOutputStream(bytes).use {out->if(semantic)cell.encodeWithText(out) {} else cell.encode(out)}
        }.toByteArray()
        // Exact vector: type=TEXT, raw bytes 00ff, String code units 0041 0000 d800.
        val a=G0FrozenDomainInputs.Cell(Cursor.FIELD_TYPE_STRING,null,byteArrayOf(0,-1),"A\u0000\uD800")
        val b=G0FrozenDomainInputs.Cell(Cursor.FIELD_TYPE_STRING,null,byteArrayOf(0,-1),"A\u0000\uD801")
        assertArrayEquals(encoded(a,false),encoded(b,false))
        assertArrayEquals(a.text!!.toByteArray(Charsets.UTF_8),b.text!!.toByteArray(Charsets.UTF_8))
        assertArrayEquals(byteArrayOf(0,0,0,3,0,0,0,2,0,-1,0,0,0,3,0,65,0,0,-40,0),encoded(a,true))
        assertFalse(encoded(a,true).contentEquals(encoded(b,true)))
        val blob=G0FrozenDomainInputs.Cell(Cursor.FIELD_TYPE_BLOB,null,byteArrayOf(0,-1))
        assertArrayEquals(encoded(blob,false),encoded(blob,true))
        assertFalse(encoded(a,true).contentEquals(encoded(blob,true)))
    }

    @Test fun legacyCaptureRemainsExactAndCertificateScopeRequiresBothExplicitAdmissions()=runBlocking<Unit> {
        val legacySchema=trustedDomainSchema()
        val old=G0PersistentCheckpoint(File(directory,"unused-count-input"),"unused-count-key","input")
        db.withTransaction {domainGate.coldRead(db) {
            val legacy=G0FrozenDomainInputs.capture(db,domainGate,legacySchema)
            assertFalse(legacy.countCertificateScope);assertNull(legacy.textDigest());assertNull(legacy.textDigestHex)
            assertArrayEquals(old.capture(db.openHelper.writableDatabase,legacySchema),legacy.digest())
        }}
        val expected=prepare()
        val implicit=runCatching {capture(expected.schema,scope=false)}.exceptionOrNull()
        assertEquals("G0_CHECKPOINT_TABLES",implicit?.message)
        reopen(scope=false)
        val noSqlCapability=runCatching {capture(expected.schema)}.exceptionOrNull()
        assertTrue(noSqlCapability is G0DomainSqlGate.Denied)
        reopen()
        assertTrue(capture(expected.schema).countCertificateScope)
    }

    @Test fun observedCandidateSchemaCannotExpandTheFixedTwoTableScopeOrHideMalformedDefinitions()=runBlocking<Unit> {
        val expected=prepare();val clean=storage.snapshotClosedDatabase()
        for(kind in listOf("extra","column","index","trigger","missing")) {
            restore(clean)
            val sql=db.openHelper.writableDatabase
            when(kind) {
                "extra"->sql.execSQL("CREATE TABLE g0_unregistered_certificate(value BLOB)")
                "column"->{
                    sql.execSQL("DROP TABLE g0_count_image_chunks")
                    sql.execSQL(G0CountCertificateSql.chunkCreate.replace("content BLOB","content TEXT"))
                }
                "index"->sql.execSQL("CREATE INDEX g0_certificate_extra_index ON g0_count_certificate(format)")
                "trigger"->sql.execSQL("CREATE TRIGGER g0_certificate_extra_trigger AFTER INSERT ON g0_count_image_chunks BEGIN UPDATE sync_control SET suppressOutbox=1 WHERE id=1; END")
                else->sql.execSQL("DROP TABLE g0_count_certificate")
            }
            val before=native()
            assertEquals("G0_CHECKPOINT_SCHEMA",runCatching {capture(expected.schema)}.exceptionOrNull()?.message)
            // Deliberately untrusted observed schema reaches the adapter's fixed-scope
            // check. It is NEVER supplied to a cold owner or used as a trusted baseline.
            val observed=G0DomainJournal.trustedSchema(sql)
            val rejected=runCatching {capture(observed)}.exceptionOrNull()
            assertEquals(kind,if(kind=="extra")"G0_CHECKPOINT_TABLES" else "G0_COUNT_CERTIFICATE_SCHEMA",rejected?.message)
            assertEquals(kind,before,native())
        }
    }

    @Test fun everyCertificateWriteIsBlockedByTheOriginalActiveReplicaFence()=runBlocking<Unit> {
        prepare();val clean=storage.snapshotClosedDatabase()
        for(action in listOf("INSERT","UPDATE","DELETE")) {
            restore(clean)
            // An INSERT has an empty target; UPDATE/DELETE have an actual existing row.
            // No uniqueness violation or zero-row statement can masquerade as fence rejection.
            if(action!="INSERT")putPacket(listOf(byteArrayOf(1,2,3)))
            val sql=db.openHelper.writableDatabase
            sql.execSQL("INSERT INTO next_replica_fence (recoveryId,accountId,serverInstanceId,oldEpoch,newEpoch,authenticationGeneration,deviceId,capabilityRevision,sourceHash,mode,id) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any>(id(201),id(1),id(2),id(3),id(202),id(203),id(4),1L,"a".repeat(64),"frozen",1L))
            val before=native()
            val attempts:List<Pair<String,Array<out Any?>>> = when(action) {
                "INSERT"->listOf(G0CountCertificateSql.insertHeader to header(3L,1L),
                    G0CountCertificateSql.insertChunk to arrayOf<Any>(0L,byteArrayOf(1,2,3)))
                "UPDATE"->listOf("UPDATE g0_count_certificate SET format=2 WHERE slot=1" to emptyArray<Any?>(),
                    "UPDATE g0_count_image_chunks SET content=X'0405' WHERE chunkIndex=0" to emptyArray<Any?>())
                else->listOf(G0CountCertificateSql.deleteHeader to emptyArray<Any?>(),
                    G0CountCertificateSql.deleteChunks to emptyArray<Any?>())
            }
            for((statement,args) in attempts) {
                val rejected=runCatching {sql.execSQL(statement,args)}.exceptionOrNull()
                assertTrue("$action must reach SQLite's original replica fence: $rejected",rejected is SQLiteConstraintException)
                assertTrue(rejected?.message.orEmpty().contains("SYNC_REPLICA_SWITCH_FENCED"))
                assertEquals("$action may not alter any native row or schema",before,native())
            }
        }
    }

    @Test fun missingOrChangedReplicaFencesFailPreRoomAndCannotBeRepairedOnOpen()=runBlocking<Unit> {
        val expected=prepare();val clean=storage.snapshotClosedDatabase()
        for((name,definition) in G0CountCertificateSql.replicaFenceTriggers)for(kind in listOf("missing","changed")) {
            restore(clean)
            val sql=db.openHelper.writableDatabase
            sql.execSQL("DROP TRIGGER `$name`")
            if(kind=="changed")sql.execSQL(definition.replace("mode IS NOT 'closed'","mode IS NOT 'frozen'"))
            val observed=G0DomainJournal.trustedSchema(sql).filter {it[0]=="main"}
            assertEquals("$kind/$name fixed scope rejects the observed schema","G0_COUNT_CERTIFICATE_SCHEMA",
                runCatching {G0CountCertificateSql.requireSchema(observed)}.exceptionOrNull()?.message)
            val before=native();var callbackEntered=false;var bodyEntered=false
            val owner=G0RoomColdOwnerFactory(expected.main,expected.identity,
                delegateFactory=G0TimingFactory(endFaults),onFailure={domainGate.invalidateConnection()},
                beforeRoomOpen={callbackEntered=true})
            reopen(owner=owner)
            val failure=runCatching {domainGate.ownedColdTransaction(db,expected.schema) {
                bodyEntered=true
                G0FrozenDomainInputs.capture(db,domainGate,expected.schema,countCertificateScope=true)
            }}.exceptionOrNull()
            assertEquals("$kind/$name","G0_COLD_OWNER_SCHEMA_REJECTED",failure?.message)
            assertFalse(callbackEntered);assertFalse(bodyEntered);assertEquals(0,owner.callbacks)
            assertTrue(owner.hasFailed);assertFalse(owner.knownRollback)
            assertEquals("$kind/$name must not be repaired by opening Room",before,native())
        }
    }

    @Test fun certificatePayloadConsumesTheSameBudgetAndCaughtMissCannotEscapeItsReadBoundary()=runBlocking<Unit> {
        val expected=prepare();val empty=capture(expected.schema)
        putPacket(listOf(ByteArray(G0CountCertificateSql.maxChunkBytes) {it.toByte()},byteArrayOf(1)))
        val before=native();val small=empty.encodedBytes+G0CountCertificateSql.maxChunkBytes/2
        var caught:Throwable?=null;var escaped=false
        val failure=runCatching {db.withTransaction {domainGate.coldRead(db) {
            caught=runCatching {G0FrozenDomainInputs.capture(db,domainGate,expected.schema,small,true)}.exceptionOrNull()
            assertTrue(caught is G0FrozenDomainInputs.BudgetExceeded)
            assertSame(caught,runCatching {domainGate.requireColdRead(db)}.exceptionOrNull())
            "not a successful fallback"
        }};escaped=true}.exceptionOrNull()
        assertSame(caught,failure);assertFalse(escaped);assertEquals(before,native())
        // A new read boundary owns a complete capture; the failed boundary is not reused.
        reopen()
        val complete=capture(expected.schema)
        assertSameRoots(empty,complete);assertTrue(complete.encodedBytes>small)
        assertEquals(before,native())
        assertEquals(complete.encodedBytes,capture(expected.schema,budget=complete.encodedBytes).encodedBytes)
        assertTrue(runCatching {capture(expected.schema,budget=complete.encodedBytes-1)}.exceptionOrNull() is G0FrozenDomainInputs.BudgetExceeded)
        assertEquals(before,native())
    }

    @Test fun actualKnownRollbackAllowsNewCaptureButLostEndIsNeverABudgetFallback()=runBlocking<Unit> {
        val expected=prepare();val before=native()
        val lost=IllegalStateException("G0_CERTIFICATE_INPUT_ROLLBACK_END_LOST")
        fun contains(root:Throwable,target:Throwable,seen:MutableSet<Throwable> = mutableSetOf()):Boolean {
            if(!seen.add(root))return false
            return root===target || root.cause?.let {contains(it,target,seen)}==true || root.suppressed.any {contains(it,target,seen)}
        }
        for(loseEnd in listOf(false,true)) {
            val owner=G0RoomColdOwnerFactory(expected.main,expected.identity,
                delegateFactory=G0TimingFactory(endFaults),onFailure={domainGate.invalidateConnection()},
                afterActualEnd={if(loseEnd)throw lost})
            reopen(owner=owner)
            val failure=requireNotNull(runCatching {domainGate.ownedColdTransaction(db,expected.schema) {
                G0FrozenDomainInputs.capture(db,domainGate,expected.schema,1L,true)
            }}.exceptionOrNull())
            assertEquals(1,owner.actualEnds)
            assertEquals(!loseEnd,owner.knownRollback)
            assertEquals(before,native())
            if(loseEnd) {
                assertTrue(contains(failure,lost))
                assertFalse(owner.knownRollback && failure is G0FrozenDomainInputs.BudgetExceeded && failure.suppressed.isEmpty())
                var reentered=false
                assertNotNull(runCatching {domainGate.ownedColdTransaction(db,expected.schema) {
                    reentered=true
                    G0FrozenDomainInputs.capture(db,domainGate,expected.schema,countCertificateScope=true)
                }}.exceptionOrNull())
                assertFalse(reentered);assertEquals(1,owner.actualEnds)
            } else {
                assertTrue(failure is G0FrozenDomainInputs.BudgetExceeded);assertTrue(failure.suppressed.isEmpty())
                reopen()
                val fallback=capture(expected.schema)
                assertTrue(fallback.countCertificateScope)
                assertEquals(G0PersistentCheckpoint.columns.keys+G0CountCertificateSql.tables.keys,fallback.tables.keys)
                assertEquals(before,native())
            }
        }
    }
}
