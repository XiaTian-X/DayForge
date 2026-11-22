package com.dayforge.data.repository

import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.HistoryBlockPlanner.OrderKey
import java.security.KeyStore
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Physical Android Keystore/SQLite primitives. The synthetic Ref authenticates format
 * only: no test here creates a business proof, committed root or writable cold owner.
 */
@RunWith(AndroidJUnit4::class)
class G0CountRootSealTest {
    private val alias=G0CountRootSeal.KEY_PREFIX+UUID.randomUUID()
    private val otherAlias=G0CountRootSeal.KEY_PREFIX+UUID.randomUUID()
    private val binding="a".repeat(64)
    private val digest="b".repeat(64)
    private fun root()=G0CountRootSeal.fixedRoot(binding,1,129,digest)
    private fun seal()=G0CountRootSeal(alias)
    private fun packet()=seal().prepare(binding,root(),3,17)
    private fun keyExists(name:String)=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.containsAlias(name)
    @After fun removeTestKeys() {
        KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(alias);deleteEntry(otherAlias)}
    }
    private fun captured(values:List<Any?>)=MatrixCursor(G0CountRootSql.headerColumns.toTypedArray()).use {
        it.addRow(values.toTypedArray());G0CountRootSql.captureSeal(it)
    }
    private fun assertRejected(block:()->Unit) {
        assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun packetRoundTripAcrossSignerInstancesOwnsConstructorAndGetterBytes() {
        val original=packet()
        val values=original.headerValues()
        val inputMac=(values[9] as ByteArray)
        val restored=requireNotNull(captured(values))
        assertEquals(original.root,restored.root)
        assertEquals(original.binding,restored.binding)
        assertEquals(original.revision,restored.revision)
        assertEquals(original.generation,restored.generation)
        assertTrue(G0CountRootSeal(alias).match(restored,binding))
        inputMac.fill(0)
        (restored.headerValues()[9] as ByteArray).fill(0)
        restored.authenticationBytes().fill(0)
        restored.bodyBytes().fill(0)
        assertTrue(seal().match(original,binding))
        assertTrue(seal().match(restored,binding))
        assertTrue(restored.retainedBytes in 33..G0CountRootSeal.MAX_HEADER_BYTES)
        assertArrayEquals(original.bodyBytes(),restored.bodyBytes())
        assertArrayEquals(original.authenticationBytes(),restored.authenticationBytes())
    }

    @Test fun macBindsAllStoredRootMetadataAndStableIdentity() {
        val original=packet()
        val tampered=listOf(3 to "c".repeat(64),4 to 2L,5 to 130L,6 to "d".repeat(64),
            7 to 4L,8 to 18L,9 to ByteArray(32))
        for((field,value) in tampered) {
            val row=original.headerValues().toMutableList().apply {set(field,value)}
            val changed=requireNotNull(captured(row))
            assertFalse("field $field must be authenticated",seal().match(changed,changed.binding))
        }
        assertFalse(seal().match(original,"c".repeat(64)))
        assertFalse(seal().match(original,"not-a-binding"))
        assertNull(captured(original.headerValues().toMutableList().apply {set(1,2L)}))
        assertNull(captured(original.headerValues().toMutableList().apply {set(2,"other-validator")}))
    }

    @Test fun matchNeverCreatesMissingOrDeletedKeysAndCannotUseAnotherDeviceAlias() {
        val original=packet()
        assertFalse(keyExists(otherAlias))
        assertFalse(G0CountRootSeal(otherAlias).match(original,binding))
        assertFalse(keyExists(otherAlias))
        KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(alias)}
        assertFalse(seal().match(original,binding))
        assertFalse(keyExists(alias))
    }

    @Test fun wrongFixedDomainRangeAndImpossibleTreeMetadataRejectBeforeKeyCreation() {
        val base=root();val range=base.range;val unit=range.unit;val partition=unit.partition
        val wrong=listOf(
            base.copy(range=range.copy(unit=unit.copy(partition=partition.copy(domain="other")))),
            base.copy(range=range.copy(unit=unit.copy(partition=partition.copy(objectId="other")))),
            base.copy(range=range.copy(unit=unit.copy(partition=partition.copy(roundId="round")))),
            base.copy(range=range.copy(unit=unit.copy(month=YearMonth.of(1970,2)))),
            base.copy(range=range.copy(lowerInclusive=OrderKey(LocalDate.of(1970,1,1),"a"))),
            base.copy(range=range.copy(upperExclusive=OrderKey(LocalDate.of(1970,1,1),"z"))),
            base.copy(height=0,count=129),base.copy(height=9),base.copy(count=0),base.copy(count=Long.MAX_VALUE))
        for(value in wrong)assertRejected {seal().prepare(binding,value,1,0)}
        assertRejected {seal().prepare(binding,base,0,0)}
        assertRejected {seal().prepare(binding,base,1,-1)}
        assertFalse(keyExists(alias))
    }

    @Test fun strictCaptureRejectsWrongTypesLimitsMissingAndDuplicateRows() {
        val good=packet().headerValues()
        val variants=listOf(0 to 2L,1 to "1",2 to "x".repeat(129),3 to "a".repeat(65),
            4 to -1L,4 to Long.MAX_VALUE,5 to 0L,5 to Long.MAX_VALUE,6 to "B".repeat(64),
            7 to 0L,8 to -1L,9 to ByteArray(31),9 to ByteArray(33),9 to "not-a-blob")
        for((field,value) in variants)assertNull("malformed field $field",
            captured(good.toMutableList().apply {set(field,value)}))
        MatrixCursor(G0CountRootSql.headerColumns.toTypedArray()).use {assertNull(G0CountRootSql.captureSeal(it))}
        MatrixCursor(G0CountRootSql.headerColumns.toTypedArray()).use {
            it.addRow(good.toTypedArray());it.addRow(good.toTypedArray())
            assertNull(G0CountRootSql.captureSeal(it))
        }
        fun marker(values:List<Any?>)=MatrixCursor(G0CountRootSql.markerColumns.toTypedArray()).use {
            it.addRow(values.toTypedArray());G0CountRootSql.captureMarker(it)
        }
        assertEquals(G0CountRootSql.Marker(0,true),marker(listOf(1L,0L,1L)))
        for(row in listOf(listOf(1L,-1L,1L),listOf(1L,0.5,1L),listOf(1L,0L,2L),listOf(2L,0L,1L)))
            assertNull(marker(row))
    }

    @Test fun boundedSqlProjectionRejectsLargeAndWrongTypeHeaderAndRootFields() {
        val original=packet()
        SQLiteDatabase.create(null).use {db->
            db.execSQL(G0CountRootSql.createSeal);db.execSQL(G0CountBlockSql.createRoot)
            db.execSQL(G0CountRootSql.insertHeader,original.headerValues().toTypedArray())
            db.execSQL(G0CountBlockSql.insertRoot,arrayOf<Any?>(1L,binding,1L,129L,digest,3L))
            assertTrue(db.rawQuery(G0CountRootSql.readSeal,null).use {seal().match(requireNotNull(G0CountRootSql.captureSeal(it)),binding)})
            val row=db.rawQuery(G0CountRootSql.readBoundedRoot,null).use {requireNotNull(G0CountRootSql.captureRootRow(it))}
            assertEquals(original.root,row.root);assertEquals(3L,row.revision)
            db.execSQL("UPDATE g0_count_root_seal SET mac=zeroblob(1048576)")
            db.rawQuery(G0CountRootSql.readSeal,null).use {c->
                assertTrue(c.moveToFirst());assertTrue(c.isNull(9));assertNull(G0CountRootSql.captureSeal(c))
            }
            db.execSQL("UPDATE g0_count_root_seal SET mac=?,validator=?",arrayOf(original.authenticationBytes(),"x".repeat(1024)))
            assertNull(db.rawQuery(G0CountRootSql.readSeal,null).use {G0CountRootSql.captureSeal(it)})
            db.execSQL("UPDATE g0_count_block_root SET binding=?",arrayOf("a".repeat(4096)))
            db.rawQuery(G0CountRootSql.readBoundedRoot,null).use {c->
                assertTrue(c.moveToFirst());assertTrue(c.isNull(1));assertNull(G0CountRootSql.captureRootRow(c))
            }
            db.execSQL("UPDATE g0_count_block_root SET binding=?,height='wrong'",arrayOf(binding))
            assertNull(db.rawQuery(G0CountRootSql.readBoundedRoot,null).use {G0CountRootSql.captureRootRow(it)})
        }
    }

    private fun markerDatabase():SQLiteDatabase=SQLiteDatabase.create(null).also {db->
        db.execSQL(G0CountRootSql.createMarker);db.execSQL(G0CountRootSql.initializeMarker)
        db.execSQL("CREATE TABLE completions (id INTEGER PRIMARY KEY,value INTEGER NOT NULL)")
        db.execSQL(G0CountBlockSql.createNodes);db.execSQL(G0CountBlockSql.createRoot)
        for((name,sql) in G0CountRootSql.dirtyTriggers)
            if(listOf("completions",G0CountBlockSql.nodes,G0CountBlockSql.root).any {name.startsWith("g0_count_root_dirty_${it}_")})
                db.execSQL(sql)
    }
    private fun marker(db:SQLiteDatabase)=db.rawQuery(G0CountRootSql.readMarker,null).use {
        requireNotNull(G0CountRootSql.captureMarker(it))
    }
    @Test fun permanentTriggersCountEveryPAndQMutationAndClearDoesNotChangeGeneration() {
        assertEquals(G0DomainSqlCatalog.columns.keys+G0CountBlockSql.tables.keys,G0CountRootSql.dirtySources)
        assertEquals(G0CountRootSql.dirtySources.size*3,G0CountRootSql.dirtyTriggers.size)
        markerDatabase().use {db->
            assertEquals(G0CountRootSql.Marker(0,true),marker(db))
            db.execSQL("INSERT INTO completions VALUES(1,1)")
            assertEquals(G0CountRootSql.Marker(1,true),marker(db))
            db.execSQL(G0CountRootSql.clearMarker,arrayOf(1L))
            assertEquals(G0CountRootSql.Marker(1,false),marker(db))
            db.execSQL("UPDATE completions SET value=2 WHERE id=1")
            db.execSQL("DELETE FROM completions WHERE id=1")
            assertEquals(G0CountRootSql.Marker(3,true),marker(db))
            db.execSQL(G0CountBlockSql.insertNode,arrayOf(digest,byteArrayOf(1)))
            db.execSQL("UPDATE g0_count_block_nodes SET body=? WHERE digest=?",arrayOf(byteArrayOf(2),digest))
            db.execSQL("DELETE FROM g0_count_block_nodes WHERE digest=?",arrayOf(digest))
            db.execSQL(G0CountBlockSql.insertRoot,arrayOf<Any?>(1L,binding,1L,129L,digest,1L))
            db.execSQL("UPDATE g0_count_block_root SET revision=2")
            db.execSQL("DELETE FROM g0_count_block_root")
            assertEquals(G0CountRootSql.Marker(9,true),marker(db))
            db.execSQL(G0CountRootSql.clearMarker,arrayOf(8L))
            assertEquals(G0CountRootSql.Marker(9,true),marker(db))
            db.execSQL(G0CountRootSql.clearMarker,arrayOf(9L))
            assertEquals(G0CountRootSql.Marker(9,false),marker(db))
        }
    }

    @Test fun missingMalformedAndOverflowMarkersAbortOriginalMutation() {
        markerDatabase().use {db->
            fun rejectedInsert() {
                val error=runCatching {db.execSQL("INSERT INTO completions VALUES(1,1)")}.exceptionOrNull()
                assertTrue(error is SQLiteException)
                assertTrue(requireNotNull(error).message.orEmpty().contains("G0_COUNT_ROOT_MARKER_INVALID"))
                db.rawQuery("SELECT count(*) FROM completions",null).use {assertTrue(it.moveToFirst());assertEquals(0L,it.getLong(0))}
            }
            db.execSQL("DELETE FROM g0_count_root_marker");rejectedInsert()
            db.execSQL("INSERT INTO g0_count_root_marker VALUES(1,?,0)",arrayOf(Long.MAX_VALUE));rejectedInsert()
            assertEquals(G0CountRootSql.Marker(Long.MAX_VALUE,false),marker(db))
            db.execSQL("UPDATE g0_count_root_marker SET generation=1.5");rejectedInsert()
            assertNull(db.rawQuery(G0CountRootSql.readMarker,null).use {G0CountRootSql.captureMarker(it)})
        }
    }
}
