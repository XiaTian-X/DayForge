package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Pure data-adapter tests, never a trusted business baseline. The legacy index reader is
 * only an independent native-cell oracle here; no witness or active candidate is published.
 * Actual validation, fresh authority and known-END installation belong to the cold owner.
 */
@RunWith(AndroidJUnit4::class)
class G0FrozenIndexBoundaryTest:G0SealedDomainFixture() {
    private suspend fun snapshot(schema:List<List<String?>>):G0FrozenDomainInputs =
        db.withTransaction {domainGate.coldRead(db) {G0FrozenDomainInputs.capture(db,domainGate,schema)}}

    private fun metricKey()=G0DomainProofIndex.Key("metrics",
        listOf(G0DomainJournal.Cell(Cursor.FIELD_TYPE_INTEGER,metric.id)))

    @Test fun stagedRowsMembersAndCountsExactlyMatchTheLegacyNativeIndexReader()=runBlocking<Unit> {
        val schema=trustedDomainSchema()
        db.withTransaction {domainGate.coldRead(db) {
            val sql=db.openHelper.writableDatabase
            val captured=G0FrozenDomainInputs.capture(db,domainGate,schema)
            val stage=G0DomainProofIndex.stageFrozen(captured)
            assertSame(captured,stage.input)
            val original=G0DomainProofIndex.captureVerifiedBaseline(sql)
            for((table,columns) in G0PersistentCheckpoint.columns) {
                val keys=linkedSetOf<G0DomainProofIndex.Key>()
                var count=0
                sql.query(G0PersistentCheckpoint.select(table)).use { cursor ->
                    assertEquals(columns,cursor.columnNames.toList())
                    while(cursor.moveToNext()) {
                        count++
                        if(table !in G0DomainSqlCatalog.columns)continue
                        val row=columns.indices.map { i ->
                            G0DomainJournal.Cell(cursor.getType(i),when(cursor.getType(i)) {
                                Cursor.FIELD_TYPE_NULL->null
                                Cursor.FIELD_TYPE_INTEGER->cursor.getLong(i)
                                Cursor.FIELD_TYPE_FLOAT->java.lang.Double.doubleToRawLongBits(cursor.getDouble(i))
                                Cursor.FIELD_TYPE_STRING->cursor.getString(i)
                                Cursor.FIELD_TYPE_BLOB->cursor.getBlob(i).toList()
                                else->error("Unexpected cell")
                            })
                        }
                        val key=G0DomainProofIndex.key(table,row)
                        assertTrue(keys.add(key))
                        assertEquals(table,row,original.row(key))
                        assertEquals(table,original.row(key),stage.row(key))
                    }
                }
                assertEquals(table,count,stage.tableCounts.getValue(table))
                if(table in G0DomainSqlCatalog.columns)assertEquals(table,keys,stage.members(table).toSet())
            }
            assertEquals(G0PersistentCheckpoint.columns.keys,stage.tableCounts.keys)
            assertNull(stage.row(G0DomainProofIndex.Key("metrics",
                listOf(G0DomainJournal.Cell(Cursor.FIELD_TYPE_INTEGER,Long.MAX_VALUE)))))
        }}
    }

    @Test fun textUsesTheSameCursorStringObservationWhileRawBytesRemainIndependentlyPreserved()=runBlocking<Unit> {
        val schema=trustedDomainSchema()
        val sql=db.openHelper.writableDatabase
        sql.execSQL("UPDATE metrics SET description=CAST(X'80ff0061' AS TEXT),unit=X'80ff0061',targetValue=1.25 WHERE id=?",
            arrayOf(metric.id))
        val observed=sql.query("SELECT * FROM metrics WHERE id = ? LIMIT 1",arrayOf(metric.id)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            val description=cursor.getColumnIndexOrThrow("description")
            Triple(cursor.getString(description),cursor.getBlob(description),
                cursor.getBlob(cursor.getColumnIndexOrThrow("unit")))
        }
        val captured=snapshot(schema)
        val staged=G0DomainProofIndex.stageFrozen(captured)
        val table=captured.tables.getValue("metrics")
        val description=table.columns.indexOf("description")
        val unit=table.columns.indexOf("unit")
        val target=table.columns.indexOf("targetValue")
        val source=table.rows.single().cells
        val row=requireNotNull(staged.row(metricKey()))
        assertEquals(Cursor.FIELD_TYPE_STRING,source[description].type)
        assertEquals(observed.first,source[description].text)
        assertArrayEquals(observed.second,source[description].bytes())
        assertEquals(observed.first,row[description].value)
        assertEquals(Cursor.FIELD_TYPE_BLOB,source[unit].type)
        assertNull(source[unit].text)
        assertArrayEquals(observed.third,source[unit].bytes())
        assertEquals(observed.third.toList(),row[unit].value)
        assertEquals(Cursor.FIELD_TYPE_FLOAT,row[target].type)
        assertEquals(java.lang.Double.doubleToRawLongBits(1.25),row[target].value)
        db.withTransaction {domainGate.coldRead(db) {
            val legacy=G0DomainProofIndex.captureVerifiedBaseline(db.openHelper.writableDatabase)
            assertEquals(legacy.row(metricKey()),row)
        }}
    }

    @Test fun stagedCollectionsAndBlobCellsCannotBeMutatedThroughPublishedDataReferences()=runBlocking<Unit> {
        val schema=trustedDomainSchema()
        db.openHelper.writableDatabase.execSQL("UPDATE metrics SET unit=X'0001ff' WHERE id=?",arrayOf(metric.id))
        val input=snapshot(schema)
        val stage=G0DomainProofIndex.stageFrozen(input)
        val key=stage.members("metrics").single()
        val row=requireNotNull(stage.row(key))
        val unit=G0DomainSqlCatalog.columns.getValue("metrics").indexOf("unit")
        assertTrue(runCatching {(stage.tableCounts as MutableMap).clear()}.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching {(stage.members("metrics") as MutableList).clear()}.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching {(key.values as MutableList).clear()}.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching {(row as MutableList).clear()}.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching {(row[unit].value as MutableList<*>).clear()}.exceptionOrNull() is UnsupportedOperationException)
        requireNotNull(input.tables.getValue("metrics").rows.single().cells[unit].bytes()).fill(0)
        assertEquals(listOf(0.toByte(),1.toByte(),(-1).toByte()),requireNotNull(stage.row(key))[unit].value)
    }

    @Test fun duplicateKeysInDeliberatelyUntrustedScratchLayoutCannotBecomeAStagedIndex()=runBlocking<Unit> {
        // Deliberately NOT a trusted cold preflight: this scratch table has all the same
        // columns but no PK constraint. Its locally observed schema is supplied solely to
        // reach the pure stage duplicate-key guard. No owner or business proof adopts it.
        val sql=db.openHelper.writableDatabase
        val columns=G0DomainSqlCatalog.columns.getValue("next_rejections")
        sql.execSQL("DROP TABLE next_rejections")
        sql.execSQL("CREATE TABLE next_rejections(${columns.joinToString(",") {"`$it`"}})")
        repeat(2) {
            sql.execSQL("INSERT INTO next_rejections VALUES(${columns.joinToString(",") {"?"}})",
                columns.map {"duplicate-native-member"}.toTypedArray())
        }
        val scratch=G0DomainJournal.trustedSchema(sql)
        val input=snapshot(scratch)
        assertEquals(2,input.tables.getValue("next_rejections").rows.size)
        val failure=runCatching {G0DomainProofIndex.stageFrozen(input)}.exceptionOrNull()
        assertEquals("G0_FROZEN_INDEX_DUPLICATE_KEY",failure?.message)
        assertEquals(2,input.tables.getValue("next_rejections").rows.size)
        db.withTransaction {domainGate.coldRead(db) {
            assertNotNull(runCatching {G0DomainProofIndex.captureVerifiedBaseline(db.openHelper.writableDatabase)}.exceptionOrNull())
        }}
    }
}
