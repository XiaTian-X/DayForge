package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0FrozenDomainInputsTest : G0SealedDomainFixture() {
    private fun oldCapture() = G0PersistentCheckpoint(File(directory, "unused-native-proof"), "unused-native-key", "native")
    private suspend fun frozen(schema: List<List<String?>>): G0FrozenDomainInputs =
        db.withTransaction { domainGate.coldRead(db) { G0FrozenDomainInputs.capture(db, domainGate, schema) } }

    @Test fun exactOldNativeDigestAndNestedValuesRemainImmutableAfterScopeEnds() = runBlocking<Unit> {
        val schema = trustedDomainSchema()
        val old = oldCapture()
        val snapshot = db.withTransaction {
            domainGate.coldRead(db) {
                val value = G0FrozenDomainInputs.capture(db, domainGate, schema)
                assertArrayEquals(old.capture(db.openHelper.writableDatabase, schema), value.digest())
                assertEquals(old.scannedRows, value.scannedRows)
                value
            }
        }
        assertEquals(G0PersistentCheckpoint.columns.keys, snapshot.tables.keys)
        val digest = snapshot.digest()
        snapshot.digest().fill(0)
        assertArrayEquals(digest, snapshot.digest())
        val metrics = snapshot.tables.getValue("metrics")
        val name = metrics.columns.indexOf("name")
        assertEquals(metric.name, metrics.rows.single().cells[name].text)
        val bytes = requireNotNull(metrics.rows.single().cells[name].bytes())
        requireNotNull(metrics.rows.single().cells[name].bytes()).fill(0)
        assertArrayEquals(bytes, metrics.rows.single().cells[name].bytes())
        assertTrue(runCatching { (snapshot.tables as MutableMap).clear() }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching { (snapshot.schema as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching { (snapshot.schema.first() as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching { (metrics.columns as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching { (metrics.rows as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching { (metrics.rows.single().cells as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
        // Boot remains usable after a successful read. The old DATA value cannot become a new proof.
        db.openHelper.writableDatabase.execSQL("UPDATE metrics SET name='Later native value' WHERE id=?", arrayOf(metric.id))
        val newer = frozen(schema)
        assertFalse(newer.digest().contentEquals(digest))
        assertArrayEquals(bytes, metrics.rows.single().cells[name].bytes())
    }

    @Test fun rawTextBlobRealIntegerNullAndDuplicateMembersArePreservedWithoutDtoNormalization() = runBlocking<Unit> {
        val schema = trustedDomainSchema()
        val sql = db.openHelper.writableDatabase
        // sqlite_sequence has no column affinity: preserve the native types actually returned by SQLite.
        sql.execSQL("INSERT INTO sqlite_sequence(name,seq) VALUES ('native-integer',9223372036854775807)")
        sql.execSQL("INSERT INTO sqlite_sequence(name,seq) VALUES ('native-real',1.25)")
        sql.execSQL("INSERT INTO sqlite_sequence(name,seq) VALUES ('native-null',NULL)")
        sql.execSQL("INSERT INTO sqlite_sequence(name,seq) VALUES ('native-text',CAST(X'80ff0061' AS TEXT))")
        sql.execSQL("INSERT INTO sqlite_sequence(name,seq) VALUES ('native-blob',X'80ff0061')")
        sql.execSQL("INSERT INTO android_metadata(locale) VALUES ('same-native-member'),('same-native-member')")
        // getBlob(TEXT) is CursorWindow's internal representation, not necessarily bare UTF-8.
        // Only the independent cursor uses getString to locate our known fixture keys.
        data class Observed(val key: ByteArray, val type: Int, val number: Long?, val bytes: ByteArray?,
            val text: String?)
        val observed = sql.query("SELECT * FROM sqlite_sequence").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)
                    if (!name.startsWith("native-")) continue
                    val type = cursor.getType(1)
                    put(name, Observed(cursor.getBlob(0), type, when (type) {
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(1)
                        Cursor.FIELD_TYPE_FLOAT -> java.lang.Double.doubleToRawLongBits(cursor.getDouble(1))
                        else -> null
                    }, if (type == Cursor.FIELD_TYPE_STRING || type == Cursor.FIELD_TYPE_BLOB) cursor.getBlob(1) else null,
                        if (type == Cursor.FIELD_TYPE_STRING) cursor.getString(1) else null))
                }
            }
        }
        assertEquals(setOf("native-integer", "native-real", "native-null", "native-text", "native-blob"), observed.keys)
        sql.query("SELECT hex(CAST(seq AS BLOB)) FROM sqlite_sequence WHERE name='native-text'").use {
            assertTrue(it.moveToFirst()); assertEquals("80FF0061", it.getString(0))
        }
        val duplicateKeys = sql.query("SELECT * FROM android_metadata").use { cursor ->
            buildList { while (cursor.moveToNext())
                if (cursor.getString(0) == "same-native-member") add(cursor.getBlob(0))
            }
        }
        assertEquals(2, duplicateKeys.size)
        val value = frozen(schema)
        val sequence = value.tables.getValue("sqlite_sequence")
        fun cell(name: String) = sequence.rows.single {
            requireNotNull(it.cells[0].bytes()).contentEquals(observed.getValue(name).key)
        }.cells[1]
        for ((name, expected) in observed) {
            assertEquals(name, expected.type, cell(name).type)
            assertEquals(name, expected.number, cell(name).number)
            assertArrayEquals(name, expected.bytes, cell(name).bytes())
            assertEquals(name, expected.text, cell(name).text)
        }
        assertEquals(Cursor.FIELD_TYPE_INTEGER, cell("native-integer").type)
        assertEquals(Long.MAX_VALUE, cell("native-integer").number)
        assertEquals(Cursor.FIELD_TYPE_FLOAT, cell("native-real").type)
        assertEquals(java.lang.Double.doubleToRawLongBits(1.25), cell("native-real").number)
        assertEquals(Cursor.FIELD_TYPE_NULL, cell("native-null").type)
        assertNull(cell("native-null").number)
        assertNull(cell("native-null").bytes())
        assertEquals(Cursor.FIELD_TYPE_STRING, cell("native-text").type)
        assertEquals(Cursor.FIELD_TYPE_BLOB, cell("native-blob").type)
        val raw = byteArrayOf(0x80.toByte(), 0xff.toByte(), 0, 0x61)
        assertArrayEquals(observed.getValue("native-text").bytes, cell("native-text").bytes())
        assertArrayEquals(raw, cell("native-blob").bytes())
        assertEquals(2, value.tables.getValue("android_metadata").rows.count {
            requireNotNull(it.cells.single().bytes()).contentEquals(duplicateKeys.first())
        })
        db.withTransaction { domainGate.coldRead(db) {
            assertArrayEquals(oldCapture().capture(sql, schema), value.digest())
        } }
        sql.execSQL("DELETE FROM android_metadata WHERE rowid=(SELECT max(rowid) FROM android_metadata)")
        assertFalse(frozen(schema).digest().contentEquals(value.digest()))
    }

    @Test fun unexpectedSchemaCannotProduceFrozenEvidence() = runBlocking<Unit> {
        val schema = trustedDomainSchema()
        db.openHelper.writableDatabase.execSQL("CREATE TABLE untrusted_extra(value BLOB)")
        val failure = runCatching { frozen(schema) }.exceptionOrNull()
        assertEquals("G0_CHECKPOINT_SCHEMA", failure?.message)
    }

    @Test fun budgetMissCannotBeCaughtInsideScopeAndUsedAsSuccessfulCapture() = runBlocking<Unit> {
        val schema = trustedDomainSchema()
        var inner: Throwable? = null
        var escaped = false
        val failure = runCatching {
            db.withTransaction {
                domainGate.coldRead(db) {
                    inner = runCatching { G0FrozenDomainInputs.capture(db, domainGate, schema, 1L) }.exceptionOrNull()
                    "unqualified fallback"
                }
            }
            escaped = true
        }.exceptionOrNull()
        assertTrue(inner is G0FrozenDomainInputs.BudgetExceeded)
        assertNotNull(failure)
        assertFalse(escaped)
        val baselineBytes = frozen(schema).encodedBytes
        val payload = "x".repeat(256 * 1024)
        db.openHelper.writableDatabase.execSQL("UPDATE metrics SET description=? WHERE id=?", arrayOf<Any>(payload, metric.id))
        val largeField = runCatching {
            db.withTransaction {
                domainGate.coldRead(db) { G0FrozenDomainInputs.capture(db, domainGate, schema, baselineBytes + 128 * 1024) }
            }
        }.exceptionOrNull()
        assertTrue("A retained field also consumes the candidate budget", largeField is G0FrozenDomainInputs.BudgetExceeded)
        db.openHelper.writableDatabase.query("SELECT * FROM metrics WHERE id = ? LIMIT 1", arrayOf(metric.id)).use {
            assertTrue(it.moveToFirst())
            assertEquals(payload, it.getString(it.getColumnIndexOrThrow("description")))
        }
    }

    @Test fun ordinaryRoomTransactionDoesNotGrantFrozenCaptureCapability() = runBlocking<Unit> {
        val schema = trustedDomainSchema()
        val failure = runCatching {
            db.withTransaction { G0FrozenDomainInputs.capture(db, domainGate, schema) }
        }.exceptionOrNull()
        assertTrue(failure is G0DomainSqlGate.Denied)
    }

    @Test fun textObservationHasItsOwnBudgetWithoutChangingTheOriginalRawDigest() = runBlocking<Unit> {
        val schema = trustedDomainSchema()
        db.openHelper.writableDatabase.execSQL("UPDATE metrics SET description=? WHERE id=?",
            arrayOf<Any>("双观察\u0000".repeat(512), metric.id))
        val value = frozen(schema)
        val textBytes = value.tables.values.sumOf { table -> table.rows.sumOf { row ->
            row.cells.sumOf { cell -> cell.text?.let { 4L + it.length.toLong() * 2 } ?: 0L }
        } }
        assertTrue(textBytes > 0)
        assertTrue(value.encodedBytes > textBytes)
        val rawOnlyBudget = value.encodedBytes - textBytes
        val failed = runCatching {
            db.withTransaction { domainGate.coldRead(db) {
                G0FrozenDomainInputs.capture(db,domainGate,schema,rawOnlyBudget)
            } }
        }.exceptionOrNull()
        assertTrue(failed is G0FrozenDomainInputs.BudgetExceeded)
        db.withTransaction { domainGate.coldRead(db) {
            assertArrayEquals(oldCapture().capture(db.openHelper.writableDatabase,schema),value.digest())
        } }
    }
}
