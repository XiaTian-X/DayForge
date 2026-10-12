package com.dayforge.data.repository

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
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
            if (!table.startsWith("sqlite_") && table !in setOf("room_master_table", "android_metadata", "next_replica_fence")) add(table)
        } }
    }
    private fun installed() = db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'replica_fence_%' ORDER BY name").use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(0)) }
    }
    private fun assertAllGuards() {
        val expected = tables().flatMap { table -> listOf("insert", "update", "delete").map { "replica_fence_${table}_$it" } }.toSet() +
            setOf("replica_fence_control_insert", "replica_fence_control_update", "replica_fence_control_delete")
        assertEquals(35, tables().size)
        assertEquals(expected, installed())
    }

    /** Deliberately schema-only sentinels: every table gets a row so UPDATE/DELETE triggers really
     * execute. This fixture is not a fabricated valid business/request proof or an accepted ACK. */
    private fun populateEveryGuardedTable() {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("PRAGMA foreign_keys=OFF")
        try {
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1")
            for (table in tables().filterNot { it == "sync_control" }) {
                val values = ContentValues()
                sql.query("PRAGMA table_info(`$table`)").use { c -> while (c.moveToNext()) {
                    val name = c.getString(1)
                    if (c.getInt(3) == 1) when (c.getString(2)) {
                        "INTEGER" -> values.put(name, 1L)
                        "REAL" -> values.put(name, 1.25)
                        "TEXT" -> values.put(name, "original")
                        "BLOB" -> values.put(name, byteArrayOf(0, 127, -1))
                        else -> error("Unexpected fixture affinity")
                    }
                } }
                assertTrue(table, sql.insert(table, SQLiteDatabase.CONFLICT_ABORT, values) > 0)
            }
        } finally { sql.execSQL("PRAGMA foreign_keys=ON") }
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
        val before = hash(); val sql = db.openHelper.writableDatabase
        for (table in tables()) {
            val column = sql.query("PRAGMA table_info(`$table`)").use { assertTrue(it.moveToFirst()); it.getString(1) }
            denied { sql.execSQL("INSERT INTO `$table` DEFAULT VALUES") }
            denied { sql.execSQL("INSERT OR REPLACE INTO `$table` SELECT * FROM `$table`") }
            denied { sql.execSQL("UPDATE OR IGNORE `$table` SET `$column`=`$column`") }
            denied { sql.execSQL("DELETE FROM `$table`") }
        }
        assertEquals(before, hash()); assertEquals(expected, db.nextReplicaFenceDao().state())
        assertEquals(1, sql.query("SELECT suppressOutbox FROM sync_control").use { assertTrue(it.moveToFirst()); it.getInt(0) })
    }
    @Test fun bothClearEntrypointsCannotRemoveFenceOrPartiallyClearAnyData() = runBlocking {
        db.habitDao().insert(habit()); val expected = binding(); fence().acquire(expected); val before = hash()
        denied { db.clearAllTables() }
        assertNotNull(runCatching { db.clearAllData() }.exceptionOrNull())
        assertEquals(before, hash()); assertEquals(expected, db.nextReplicaFenceDao().state())
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
        denied { db.clearAllTables() }
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
