package com.dayforge.data.repository

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.CountTrustSchema
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.NextReplicaFenceEntity
import com.dayforge.data.model.HabitType
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real production Room/SQLite callbacks and cold file reopen in the isolated testbed. */
@RunWith(AndroidJUnit4::class)
class NextReplicaWriteFenceTest {
    @get:Rule val fixture = PhysicalDatabaseRule()
    private val db get() = fixture.database
    private fun id() = UUID.randomUUID().toString()
    private fun binding() = NextReplicaFenceEntity(id(), id(), id(), id(), id(), id(), id(), 1, "a".repeat(64))
    private fun fence() = NextReplicaWriteFence(db)
    private suspend fun hash() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().hash }
    private fun denied(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertNotNull(error)
        assertTrue(error.toString(), error!!.message.orEmpty().contains("SYNC_REPLICA_SWITCH_FENCED"))
    }
    private fun tables() = db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { c ->
        buildList { while (c.moveToNext()) {
            val table = c.getString(0)
            if (!table.startsWith("sqlite_") && table !in setOf("room_master_table", "android_metadata", "next_replica_fence") &&
                table !in com.dayforge.data.local.CountTrustSchema.LOCAL_TABLES) add(table)
        } }
    }
    private fun installed() = db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'replica_fence_%' ORDER BY name").use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(0)) }
    }
    private fun assertAllGuards() {
        val expected = (tables() + com.dayforge.data.local.CountTrustSchema.LOCAL_TABLES).flatMap { table -> listOf("insert", "update", "delete").map { "replica_fence_${table}_$it" } }.toSet() +
            setOf("replica_fence_control_insert", "replica_fence_control_update", "replica_fence_control_delete")
        assertEquals(35, tables().size)
        assertEquals(expected, installed())
    }

    /** Deliberately schema-only sentinels. Only this isolated raw setup connection disables
     * its FK checks; it is closed before reopening the original controlled production Room. */
    private fun populateEveryGuardedTable() {
        val expectedTables = (CountTrustSchema.BUSINESS_TABLES - "next_replica_fence").sorted()
        assertEquals(expectedTables, tables())
        val context = isolatedContext()
        db.close()
        try {
            SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null,
                SQLiteDatabase.OPEN_READWRITE).use { native ->
                native.execSQL("PRAGMA foreign_keys=OFF")
                native.beginTransaction()
                try {
                    native.rawQuery("PRAGMA foreign_keys", null).use { c ->
                        assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0))
                    }
                    native.execSQL("UPDATE sync_control SET suppressOutbox=1")
                    for (table in expectedTables.filterNot { it == "sync_control" }) {
                        val values = ContentValues()
                        native.rawQuery("PRAGMA table_info(`$table`)", null).use { c -> while (c.moveToNext()) {
                            val name = c.getString(1)
                            check(name.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")))
                            if (c.getInt(3) == 1) when (c.getString(2)) {
                                "INTEGER" -> values.put(name, 1L)
                                "REAL" -> values.put(name, 1.25)
                                "TEXT" -> values.put(name, "original")
                                "BLOB" -> values.put(name, byteArrayOf(0, 127, -1))
                                else -> error("Unexpected fixture affinity")
                            }
                        } }
                        assertTrue(table, native.insertOrThrow(table, null, values) > 0)
                        native.rawQuery("SELECT * FROM main.`$table`", null).use { c ->
                            assertTrue(table, c.moveToFirst())
                            for ((column, value) in values.valueSet()) {
                                val index = c.getColumnIndexOrThrow(column)
                                when (value) {
                                    is Long -> { assertEquals(Cursor.FIELD_TYPE_INTEGER, c.getType(index)); assertEquals(value.toLong(), c.getLong(index)) }
                                    is Double -> { assertEquals(Cursor.FIELD_TYPE_FLOAT, c.getType(index)); assertEquals(value.toRawBits(), c.getDouble(index).toRawBits()) }
                                    is String -> { assertEquals(Cursor.FIELD_TYPE_STRING, c.getType(index)); assertEquals(value, c.getString(index)) }
                                    is ByteArray -> { assertEquals(Cursor.FIELD_TYPE_BLOB, c.getType(index)); assertArrayEquals(value, c.getBlob(index)) }
                                    else -> error("Unexpected native sentinel type")
                                }
                            }
                            assertFalse(table, c.moveToNext())
                        }
                    }
                    for (table in expectedTables) native.rawQuery("SELECT count(*) FROM main.`$table`", null).use { c ->
                        assertTrue(c.moveToFirst()); assertEquals("Every guard must have a real row: $table", 1L, c.getLong(0))
                    }
                    native.setTransactionSuccessful()
                } finally { native.endTransaction() }
            }
        } finally { fixture.reopen() }
        assertEquals(expectedTables, tables())
        for (table in expectedTables) db.openHelper.writableDatabase.query("SELECT count(*) FROM main.`$table`").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("The controlled production connection must see each sentinel: $table", 1L, c.getLong(0))
        }
    }

    private fun isolatedContext(): Context = ApplicationProvider.getApplicationContext<Context>().also {
        check(it.packageName == "com.dayforge.testbed")
    }
    private data class Cell(val type: Int, val value: Any?)
    private data class Image(val version: Int, val schemaVersion: Long, val schema: List<List<Cell>>,
        val columns: Map<String, List<String>>, val tables: Map<String, List<List<Cell>>>)
    private fun cells(cursor: Cursor): List<Cell> = (0 until cursor.columnCount).map { column ->
        val type = cursor.getType(column)
        Cell(type, when (type) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column).toRawBits()
            Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList()
            else -> error("Unexpected SQLite storage type")
        })
    }
    /** Includes optional/platform tables, original storage types, rowids and every main DDL;
     * stronger than the original business-only hash for a clear/rollback assertion. */
    private suspend fun image(database: HabitDatabase = db): Image = database.withTransaction {
        val sql = database.openHelper.writableDatabase
        val schema = sql.query("SELECT type,name,tbl_name,sql FROM main.sqlite_master ORDER BY type,name").use { c ->
            buildList { while (c.moveToNext()) add(cells(c)) }
        }
        val names = sql.query("SELECT name FROM main.sqlite_master WHERE type='table' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0).also { check(it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*"))) }) }
        }
        val columns = linkedMapOf<String, List<String>>()
        val tables = names.associateWith { table ->
            sql.query("SELECT rowid AS __fence_rowid,* FROM main.`$table` ORDER BY rowid").use { c ->
                columns[table] = c.columnNames.toList()
                buildList { while (c.moveToNext()) add(cells(c)) }
            }
        }
        val schemaVersion = sql.query("PRAGMA schema_version").use { c -> assertTrue(c.moveToFirst()); c.getLong(0) }
        Image(sql.version, schemaVersion, schema, columns, tables)
    }
    private suspend fun controlledClearAllTablesIsRejectedBeforeAnyDurableChange() {
        val before = image()
        val failure = try { db.clearAllTables(); null } catch (error: IllegalStateException) { error }
        assertNotNull(failure)
        assertEquals("COUNT_TRUST_MAINTENANCE_REQUIRED", failure!!.message)
        assertEquals("The earlier controlled refusal must leave ALL main content unchanged", before, image())
    }
    /** The production gate rejects generated clear earlier. Exercise the unchanged generated
     * Room clear against the SAME closed file without that extra gate, so SQLite's original
     * persisted FENCE triggers must still independently refuse and roll back every row. */
    private suspend fun generatedClearStillHitsTheActualSqliteFence() {
        val before = image()
        val context = isolatedContext()
        db.close()
        var reference: HabitDatabase? = null
        var primaryFailure: Throwable? = null
        try {
            val opened = Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database").build()
                .also { reference = it }
            assertNull(CountTrustRuntime.forDatabase(opened))
            assertEquals("Opening the unsealed reference must not change the saved file", before, image(opened))
            val failure = try { opened.clearAllTables(); null }
                catch (error: android.database.sqlite.SQLiteException) { error }
            assertNotNull(failure)
            assertTrue(failure.toString(), failure!!.message.orEmpty().contains("SYNC_REPLICA_SWITCH_FENCED"))
            assertEquals("The real SQLite refusal must preserve ALL main content", before, image(opened))
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            try { reference?.close() } catch (error: Throwable) { cleanupFailure = error }
            try { fixture.reopen() } catch (error: Throwable) {
                if (cleanupFailure == null) cleanupFailure = error else cleanupFailure.addSuppressed(error)
            }
            val cleanup = cleanupFailure
            if (cleanup != null) {
                val primary = primaryFailure
                if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
            }
        }
        assertEquals("Reopening the controlled owner must preserve the same rejected state", before, image())
    }
    private fun habit() = HabitEntity(name = "Unchanged normal habit", habitType = HabitType.CHECK_IN,
        iconResId = 0, colorHex = "#000000", schedule = com.dayforge.data.model.HabitSchedule.Daily)

    @Test fun emptyCapabilityDoesNotActivateOrDisableNormalBusinessAndAtomicOutbox() = runBlocking {
        assertAllGuards(); assertNull(db.nextReplicaFenceDao().state())
        val habit = habit(); val row = db.habitDao().insert(habit)
        assertEquals(habit.name, db.habitDao().getHabitById(row)!!.name)
        assertEquals(1, db.syncOutboxDao().count())
        fixture.reopen(); assertAllGuards(); assertNull(db.nextReplicaFenceDao().state())
        assertEquals(1, db.syncOutboxDao().count())
    }
    @Test fun everyActualTableRejectsAllWritesEvenWithOutboxSuppressedAndConflictIgnore() = runBlocking {
        populateEveryGuardedTable(); val expected = binding(); fence().acquire(expected)
        val before = hash(); val completeBefore = image(); val sql = db.openHelper.writableDatabase
        for (table in tables()) {
            val column = sql.query("PRAGMA table_info(`$table`)").use { assertTrue(it.moveToFirst()); it.getString(1) }
            denied { sql.execSQL("INSERT INTO `$table` DEFAULT VALUES") }
            denied { sql.execSQL("INSERT OR REPLACE INTO `$table` SELECT * FROM `$table`") }
            denied { sql.execSQL("UPDATE OR IGNORE `$table` SET `$column`=`$column`") }
            denied { sql.execSQL("DELETE FROM `$table`") }
        }
        assertEquals(before, hash()); assertEquals(completeBefore, image()); assertEquals(expected, db.nextReplicaFenceDao().state())
        assertEquals(1, sql.query("SELECT suppressOutbox FROM sync_control").use { assertTrue(it.moveToFirst()); it.getInt(0) })
    }
    @Test fun bothClearEntrypointsCannotRemoveFenceOrPartiallyClearAnyData() = runBlocking {
        db.habitDao().insert(habit()); val expected = binding(); fence().acquire(expected); val before = hash()
        val completeBefore = image()
        controlledClearAllTablesIsRejectedBeforeAnyDurableChange()
        val failure = try { db.clearAllData(); null } catch (error: android.database.sqlite.SQLiteException) { error }
        assertNotNull(failure)
        assertTrue(failure.toString(), failure!!.message.orEmpty().contains("SYNC_REPLICA_SWITCH_FENCED"))
        assertEquals(before, hash()); assertEquals(completeBefore, image()); assertEquals(expected, db.nextReplicaFenceDao().state())
        generatedClearStillHitsTheActualSqliteFence()
        assertEquals(before, hash()); assertEquals(completeBefore, image()); assertEquals(expected, db.nextReplicaFenceDao().state())
    }
    @Test fun deleteReplaceUpsertAndBindingMutationCannotBypassFrozenControl() = runBlocking {
        val expected = binding(); fence().acquire(expected); val before = hash(); val sql = db.openHelper.writableDatabase
        denied { sql.execSQL("DELETE FROM next_replica_fence") }
        denied { sql.execSQL("INSERT OR REPLACE INTO next_replica_fence SELECT * FROM next_replica_fence") }
        denied { sql.execSQL("UPDATE next_replica_fence SET recoveryId=?", arrayOf(id())) }
        denied { sql.execSQL("UPDATE next_replica_fence SET mode='closed',sourceHash=?", arrayOf("b".repeat(64))) }
        denied { sql.execSQL("UPDATE next_replica_fence SET mode='unproven'") }
        assertEquals(before, hash())
    }
    @Test fun coldRestartRetainsBindingAndRejectsLateBusinessWritesWithoutReinitializingSyncControl() = runBlocking {
        val habitId = db.habitDao().insert(habit()); val expected = binding(); fence().acquire(expected)
        val before = hash(); fixture.reopen(); assertAllGuards()
        assertEquals(expected, db.nextReplicaFenceDao().state()); assertEquals(before, hash())
        assertEquals(expected, fence().acquire(expected))
        denied { db.openHelper.writableDatabase.execSQL("UPDATE habits SET name='late callback' WHERE id=?", arrayOf(habitId)) }
        assertEquals(before, hash())
    }
    @Test fun mismatchedStageIdentityOrSourceCannotCloseOrReplaceAndMatchingCancelRestoresNormalWrites() = runBlocking {
        val expected = binding(); fence().acquire(expected); val before = hash()
        for (wrong in listOf(expected.copy(recoveryId = id()), expected.copy(accountId = id()),
            expected.copy(oldEpoch = id()), expected.copy(newEpoch = id()), expected.copy(deviceId = id()),
            expected.copy(authenticationGeneration = id()), expected.copy(capabilityRevision = 2), expected.copy(sourceHash = "b".repeat(64)))) {
            assertNotNull(runCatching { fence().closeBeforeCommit(wrong) }.exceptionOrNull())
            assertNotNull(runCatching { fence().acquire(wrong) }.exceptionOrNull())
        }
        assertEquals(before, hash())
        val closed = expected.copy(mode = NextReplicaFenceEntity.CLOSED)
        assertEquals(closed, fence().closeBeforeCommit(expected)); fixture.reopen()
        assertEquals(closed, fence().closeBeforeCommit(expected))
        assertNotNull(runCatching { fence().acquire(expected) }.exceptionOrNull())
        db.habitDao().insert(habit()); assertEquals(1, db.syncOutboxDao().count())
        val next = binding(); assertEquals(next, fence().acquire(next)); fixture.reopen()
        assertEquals(next, db.nextReplicaFenceDao().state())
    }
    @Test fun failedOuterTransactionDoesNotLeaveFalseFreezeOrFalseRelease() = runBlocking {
        val expected = binding(); val before = hash()
        assertNotNull(runCatching { db.withTransaction { fence().acquire(expected); error("owned failure") } }.exceptionOrNull())
        assertEquals(before, hash()); assertNull(db.nextReplicaFenceDao().state())
        fence().acquire(expected); val frozen = hash()
        assertNotNull(runCatching { db.withTransaction { fence().closeBeforeCommit(expected); throw CancellationException("owned cancel") } }.exceptionOrNull())
        fixture.reopen(); assertEquals(frozen, hash()); assertEquals(expected, db.nextReplicaFenceDao().state())
    }
    @Test fun corruptUnknownModeStaysBlockedOnColdOpenAndCannotBeSilentlyClosed() = runBlocking {
        val expected = binding(); fence().acquire(expected)
        val sql = db.openHelper.writableDatabase
        sql.execSQL("DROP TRIGGER replica_fence_control_update") // Only this owned adversarial fixture.
        sql.execSQL("UPDATE next_replica_fence SET mode='unknown'")
        fixture.reopen(); assertAllGuards()
        assertNotNull(runCatching { db.nextReplicaFenceDao().state() }.exceptionOrNull())
        assertNotNull(runCatching { fence().closeBeforeCommit(expected) }.exceptionOrNull())
        denied { db.openHelper.writableDatabase.execSQL("INSERT INTO sync_control VALUES(2,0)") }
        controlledClearAllTablesIsRejectedBeforeAnyDurableChange()
        generatedClearStillHitsTheActualSqliteFence()
    }
    @Test fun extraClosedControlRowAlsoFailsClosedRatherThanPretendingInactive() = runBlocking {
        val expected = binding(); fence().acquire(expected); fence().closeBeforeCommit(expected)
        val sql = db.openHelper.writableDatabase
        sql.execSQL("DROP TRIGGER replica_fence_control_insert")
        sql.execSQL("INSERT INTO next_replica_fence SELECT recoveryId,accountId,serverInstanceId,oldEpoch,newEpoch," +
            "authenticationGeneration,deviceId,capabilityRevision,sourceHash,mode,2 FROM next_replica_fence")
        fixture.reopen(); assertAllGuards()
        assertNotNull(runCatching { db.nextReplicaFenceDao().state() }.exceptionOrNull())
        denied { db.openHelper.writableDatabase.execSQL("INSERT INTO sync_control VALUES(2,0)") }
    }
    @Test fun closedFenceAllowsNormalAccountClearWithoutLeavingAFalseActiveStage() = runBlocking {
        val expected = binding(); fence().acquire(expected); fence().closeBeforeCommit(expected)
        db.habitDao().insert(habit()); db.clearAllData(); fixture.reopen()
        assertNull(db.nextReplicaFenceDao().state()); assertEquals(0, db.syncOutboxDao().count())
        assertTrue(db.habitDao().getAllHabitsOnce().isEmpty())
    }
}
