package com.dayforge.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.repository.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountCommandInitialRootTest : CountCommandMigrationFixture() {
    private fun external(statement: String) {
        room?.close(); HabitDatabaseProvider.clearInstanceForTesting()
        SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL(statement) }
    }
    @Test(timeout = 150_000) fun schema20MissingMandatoryRootCannotBeReinitializedFromEmptyTablesOrKey() {
        source(); openCurrent(); external("DELETE FROM count_command_recovery_roots")
        assertNotNull(runCatching { openCurrent() }.exceptionOrNull()); room?.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READONLY).use { sql ->
            assertEquals(20, sql.version)
            sql.rawQuery("SELECT 1 FROM count_command_recovery_roots", null).use { assertFalse(it.moveToFirst()) }
        }
    }
    @Test(timeout = 150_000) fun schema20BadMandatoryMacNeverBecomesAFreshEmptyAnchor() {
        source(); openCurrent(); external("UPDATE count_command_recovery_roots SET mac=zeroblob(32)")
        assertNotNull(runCatching { openCurrent() }.exceptionOrNull()); room?.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READONLY).use { sql ->
            sql.rawQuery("SELECT mac,revision FROM count_command_recovery_roots", null).use {
                assertTrue(it.moveToFirst()); assertArrayEquals(ByteArray(32), it.getBlob(0)); assertEquals(1L, it.getLong(1)); assertFalse(it.moveToNext())
            }
        }
    }
    @Test(timeout = 150_000) fun emptyAnchorWithUnexpectedCommandContentIsRejectedWithoutDeletingIt() {
        source(); openCurrent()
        external("INSERT INTO count_command_nodes(digest,body) VALUES('unbound',X'0102')")
        assertNotNull(runCatching { openCurrent() }.exceptionOrNull()); room?.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READONLY).use { sql ->
            sql.rawQuery("SELECT digest,body FROM count_command_nodes", null).use {
                assertTrue(it.moveToFirst()); assertEquals("unbound", it.getString(0)); assertArrayEquals(byteArrayOf(1, 2), it.getBlob(1)); assertFalse(it.moveToNext())
            }
        }
    }
    @Test(timeout = 150_000) fun emptyAnchorWithOriginalLegacyOutcomeRefusesReopenWithoutRepairingEitherRow() {
        source(); val current = openCurrent()
        assertEquals("EMPTY", CountCommandNativeRows(current.openHelper.writableDatabase) {}.root().mode)
        current.close(); HabitDatabaseProvider.clearInstanceForTesting()
        val path = context.getDatabasePath("habit_database").path
        val outcomeColumns = listOf("commandId", "inputHash", "factUuid", "requestId", "resultJson", "state")
        // Preserve SQL types, row identity and the exact SQLite bytes of every column.
        fun image(sql: SQLiteDatabase, table: String, columns: List<String>): List<List<String?>> =
            sql.rawQuery("SELECT rowid,${columns.joinToString(",") { "typeof(`$it`),hex(CAST(`$it` AS BLOB))" }} " +
                "FROM `$table` ORDER BY rowid", null).use { cursor -> buildList {
                while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index ->
                    if (cursor.isNull(index)) null else cursor.getString(index)
                })
            } }
        val before = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { sql ->
            assertEquals(20, sql.version)
            val root = image(sql, "count_command_recovery_roots", CountCommandNativeRows.ROOT_COLUMNS)
            assertEquals(1, root.size)
            sql.rawQuery("SELECT 1 FROM count_command_results", null).use { assertFalse(it.moveToFirst()) }
            // Original failed fixture bytes: legal maintenance is not primary enrollment.
            sql.execSQL("INSERT INTO count_command_results VALUES('recovery','input','fact',NULL,'result','committed')")
            sql.execSQL("UPDATE count_command_results SET resultJson='restored' WHERE commandId='recovery'")
            assertEquals(root, image(sql, "count_command_recovery_roots", CountCommandNativeRows.ROOT_COLUMNS))
            sql.rawQuery("SELECT commandId,inputHash,factUuid,requestId,resultJson,state FROM count_command_results", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("recovery", it.getString(0)); assertEquals("input", it.getString(1)); assertEquals("fact", it.getString(2))
                assertTrue(it.isNull(3)); assertEquals("restored", it.getString(4)); assertEquals("committed", it.getString(5))
                assertFalse(it.moveToNext())
            }
            root to image(sql, "count_command_results", outcomeColumns).also { assertEquals(1, it.size) }
        }
        val failure = runCatching { openCurrent() }.exceptionOrNull()
        assertNotNull("An EMPTY primary root cannot acquire a legacy Outcome on reopening", failure)
        assertTrue("The original mandatory-root validator must reject the retained Outcome", generateSequence(failure) { it.cause }.any {
            it is IllegalArgumentException && it.stackTrace.any { frame ->
                frame.className == CountCommandStorageInitialization::class.java.name && frame.methodName == "verifyMandatory"
            }
        })
        room?.close()
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { sql ->
            assertEquals(20, sql.version)
            assertEquals(before.first, image(sql, "count_command_recovery_roots", CountCommandNativeRows.ROOT_COLUMNS))
            assertEquals(before.second, image(sql, "count_command_results", outcomeColumns))
            sql.rawQuery("SELECT mode FROM count_command_recovery_roots", null).use {
                assertTrue(it.moveToFirst()); assertEquals("EMPTY", it.getString(0)); assertFalse(it.moveToNext())
            }
        }
    }

    @Test(timeout = 150_000) fun frozen19ReplicaMigratesWithoutOpeningItsFenceOrAdmittingCommands() = runBlocking<Unit> {
        val before = source { sql -> sql.execSQL("INSERT INTO next_replica_fence(recoveryId,accountId,serverInstanceId,oldEpoch,newEpoch,authenticationGeneration,deviceId,capabilityRevision,sourceHash,mode,id) VALUES(?,?,?,?,?,?,?,1,?,'frozen',1)",
            arrayOf(id(10), id(1), id(2), id(3), id(5), id(6), id(4), "a".repeat(64))) }
        val current = openCurrent(); val sql = current.openHelper.writableDatabase
        val after = current.withTransaction { NextReplicaStorageProof(current).readInTransaction() }
        assertEquals(before.contents, requireNotNull(NextReplicaV19StorageView.project(after)).contents)
        assertEquals(JsonPrimitive(20), after.contents.getValue("user_version"))
        assertNotNull(runCatching { CountCommandStore(current, tokens, sessions).bindEmptyNamespace() }.exceptionOrNull())
        assertNotNull(runCatching { current.withTransaction { CountTrustRuntime.maintenance(current) {
            sql.execSQL("INSERT INTO count_command_nodes(digest,body) VALUES('not-permitted',X'00')")
        } } }.exceptionOrNull())
        assertEquals(after.contents, current.withTransaction { NextReplicaStorageProof(current).readInTransaction().contents })
    }
    @Test(timeout = 150_000) fun schema20MissingPrimaryKeyIsNotInitializationPermission() {
        source(); val current = openCurrent()
        val root = CountCommandNativeRows(current.openHelper.writableDatabase) {}.root()
        current.close(); HabitDatabaseProvider.clearInstanceForTesting()
        val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.deleteEntry("dayforge.count.command.primary.v1")
        assertFalse(keys.containsAlias("dayforge.count.command.primary.v1"))
        assertNotNull(runCatching { openCurrent() }.exceptionOrNull()); room?.close()
        assertFalse(keys.containsAlias("dayforge.count.command.primary.v1"))
        SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READONLY).use { sql ->
            sql.rawQuery("SELECT storageId,datasetId,mac FROM count_command_recovery_roots", null).use {
                assertTrue(it.moveToFirst()); assertEquals(root.storageId, it.getString(0)); assertEquals(root.datasetId, it.getString(1))
                assertArrayEquals(root.mac, it.getBlob(2)); assertFalse(it.moveToNext())
            }
        }
    }

}
