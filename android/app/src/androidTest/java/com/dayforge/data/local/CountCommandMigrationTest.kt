package com.dayforge.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.repository.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Complete new additive-edge gates; original older migration classes keep their assertions. */
@RunWith(AndroidJUnit4::class)
class CountCommandMigrationTest : CountCommandMigrationFixture() {
    @Test(timeout = 150_000) fun real19To20PreservesEveryOldNativeCellAndDoesNotReinitializeOnReopen() = runBlocking<Unit> {
        val before = source()
        val current = openCurrent()
        assertEquals(20, current.openHelper.writableDatabase.version)
        val after = current.withTransaction { NextReplicaStorageProof(current).readInTransaction() }
        val projected = requireNotNull(NextReplicaV19StorageView.project(after))
        assertEquals(before.contents, projected.contents); assertEquals(before.hash, projected.hash)
        val root = current.withTransaction { requireNotNull(CountTrustRuntime.forDatabase(current)).maintenance(current) {
            CountCommandNativeRows(current.openHelper.writableDatabase) {}.root()
        } }
        assertEquals("EMPTY", root.mode); assertEquals("MIGRATION_19_20", root.initializationSource)
        assertEquals(1L, root.revision); assertEquals(0L, root.pendingCount)
        assertFalse(requireNotNull(CountTrustRuntime.forDatabase(current)).isRetired)
        val reopened = openCurrent()
        val again = reopened.withTransaction { NextReplicaStorageProof(reopened).readInTransaction() }
        assertEquals(after.contents, again.contents)
        assertFalse(requireNotNull(CountTrustRuntime.forDatabase(reopened)).isRetired)
    }

    @Test(timeout = 150_000) fun real18To20RetainsTheExactOld18ComparisonAndWireBytes() = runBlocking<Unit> {
        val before = source(18)
        val current = openCurrent()
        val after = current.withTransaction { NextReplicaStorageProof(current).readInTransaction() }
        val previous = NextReplicaCompatibilityViews.projected(after).single { it.contents.getValue("user_version") == JsonPrimitive(18) }
        assertEquals(before.contents, previous.contents); assertEquals(before.hash, previous.hash)
        assertEquals(20, current.openHelper.writableDatabase.version)
        current.openHelper.writableDatabase.query("SELECT wireBytes FROM next_transmissions WHERE requestId='old-request'").use {
            assertTrue(it.moveToFirst()); assertArrayEquals(byteArrayOf(0, 127, -1), it.getBlob(0)); assertFalse(it.moveToNext())
        }
    }

    @Test(timeout = 150_000) fun oldUnenrolledOutcomeIsPreservedWithoutBecomingAnExecutableCommand() = runBlocking<Unit> {
        val before = source { it.execSQL("INSERT INTO count_command_results VALUES('untrusted-old-id','input','fact','request','家庭 old receipt','committed')") }
        val current = openCurrent()
        val after = current.withTransaction { NextReplicaStorageProof(current).readInTransaction() }
        assertEquals(before.contents, requireNotNull(NextReplicaV19StorageView.project(after)).contents)
        val root = CountCommandNativeRows(current.openHelper.writableDatabase) {}.root()
        assertEquals("LEGACY_UNENROLLED", root.mode); assertEquals(1L, root.legacyCount)
        assertNotNull(runCatching { CountCommandStore(current, tokens, sessions).bindEmptyNamespace() }.exceptionOrNull())
        assertEquals(after.contents, current.withTransaction { NextReplicaStorageProof(current).readInTransaction().contents })
        current.clearAllData()
        val reset = CountCommandNativeRows(current.openHelper.writableDatabase) {}.root()
        assertEquals("EMPTY", reset.mode); assertNotEquals(root.datasetId, reset.datasetId); assertNotEquals(root.storageId, reset.storageId)
        current.openHelper.writableDatabase.query("SELECT 1 FROM count_command_results").use { assertFalse(it.moveToFirst()) }
    }

    @Test(timeout = 150_000) fun everyNewOwnedNameCollisionRejectsBeforeReplacingTheOldFile() {
        for ((index, name) in CountCommandV20Schema.tables.withIndex()) {
            room?.close(); HabitDatabaseProvider.clearInstanceForTesting(); context.deleteDatabase("habit_database")
            source { it.execSQL("CREATE TABLE `$name`(keep TEXT)"); it.execSQL("INSERT INTO `$name` VALUES('unchanged-$index')") }
            assertNotNull(runCatching { openCurrent() }.exceptionOrNull()); room?.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
                assertEquals(19, raw.version)
                raw.rawQuery("SELECT keep FROM `$name`", null).use { assertTrue(it.moveToFirst()); assertEquals("unchanged-$index", it.getString(0)); assertFalse(it.moveToNext()) }
                for (other in CountCommandV20Schema.tables - name) raw.rawQuery("SELECT name FROM sqlite_master WHERE name=?", arrayOf(other)).use { assertFalse(it.moveToFirst()) }
            }
        }
    }

    @Test(timeout = 150_000) fun wrong19IdentityAndMissingOldDirtyGuardNeverAcquire20Storage() {
        val modes = listOf("identity", "dirty-trigger")
        for (mode in modes) {
            room?.close(); HabitDatabaseProvider.clearInstanceForTesting(); context.deleteDatabase("habit_database")
            source { if (mode == "identity") it.execSQL("UPDATE room_master_table SET identity_hash='not-room19'")
                else it.execSQL("DROP TRIGGER `${CountTrustSchema.dirtyTriggers.keys.first()}`") }
            assertNotNull(runCatching { openCurrent() }.exceptionOrNull()); room?.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
                assertEquals(19, raw.version)
                for (table in CountCommandV20Schema.tables) raw.rawQuery("SELECT name FROM sqlite_master WHERE name=?", arrayOf(table)).use { assertFalse(it.moveToFirst()) }
            }
        }
    }

    @Test(timeout = 150_000) fun actualKsp20AssetMatchesAllDeclaredTablesIdentityAndNativeGuards() {
        val old = asset(19); val generated = asset(20)
        assertEquals(19, old.getValue("version").jsonPrimitive.int)
        assertEquals(20, generated.getValue("version").jsonPrimitive.int)
        assertEquals(NextReplicaV19StorageView.PREVIOUS_ROOM_IDENTITY, old.getValue("identityHash").jsonPrimitive.content)
        assertEquals("Must be actual KSP output; update a predicted constant only after inspecting that output",
            NextReplicaV19StorageView.CURRENT_ROOM_IDENTITY, generated.getValue("identityHash").jsonPrimitive.content)
        val oldEntities = old.getValue("entities").jsonArray.map { it.jsonObject }.associateBy { it.getValue("tableName").jsonPrimitive.content }
        val newEntities = generated.getValue("entities").jsonArray.map { it.jsonObject }.associateBy { it.getValue("tableName").jsonPrimitive.content }
        assertEquals(41, oldEntities.size); assertEquals(45, newEntities.size)
        assertEquals(oldEntities.keys + CountCommandV20Schema.tables, newEntities.keys)
        oldEntities.filterKeys { it != CountCommandControlSchema.TABLE }.forEach { (name, original) ->
            assertEquals("Old Room entity changed: $name", original, newEntities.getValue(name))
        }
        val control = newEntities.getValue(CountCommandControlSchema.TABLE)
        val oldControl = oldEntities.getValue(CountCommandControlSchema.TABLE)
        val fields = control.getValue("fields").jsonArray
        val originalFields = oldControl.getValue("fields").jsonArray
        assertEquals(originalFields, JsonArray(fields.dropLast(1)))
        // Real KSP omits the default false flag. Normalize only that omission; explicit
        // true, a malformed flag, extra keys or any changed field value still fail.
        val addedField = fields.last().jsonObject
        val nullableField = JsonObject(mapOf("notNull" to JsonPrimitive(false)) + addedField)
        assertEquals(buildJsonObject { put("fieldPath", "commandRootHash"); put("columnName", "commandRootHash");
            put("affinity", "TEXT"); put("notNull", false); put("defaultValue", "NULL") }, nullableField)
        assertEquals(oldControl.filterKeys { it !in setOf("createSql", "fields") }, control.filterKeys { it !in setOf("createSql", "fields") })
        val controlSql = control.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", CountCommandControlSchema.TABLE)
        assertEquals(oldControl.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", CountCommandControlSchema.TABLE),
            CountCommandControlSchema.previousTable(controlSql))
        for (name in CountCommandV20Schema.tables) {
            val ddl = newEntities.getValue(name).getValue("createSql").jsonPrimitive.content
                .replace("\${TABLE_NAME}", name).replaceFirst("CREATE TABLE IF NOT EXISTS ", "CREATE TABLE ")
            assertEquals(CountCommandV20Schema.definitions.getValue(name).declaredSql, ddl)
        }
        val current = openCurrent(); val sql = current.openHelper.writableDatabase
        assertEquals(20, sql.version)
        sql.query("PRAGMA main.table_info(count_trust_control)").use { cursor ->
            var found = 0
            while (cursor.moveToNext()) if (cursor.getString(1) == "commandRootHash") {
                found++
                assertEquals("TEXT", cursor.getString(2)); assertEquals(0, cursor.getInt(3))
                assertEquals("NULL", cursor.getString(4)); assertEquals(0, cursor.getInt(5))
            }
            assertEquals(1, found)
        }
        sql.query("SELECT identity_hash FROM room_master_table WHERE id=42").use { assertTrue(it.moveToFirst()); assertEquals(currentHabitIdentity(), it.getString(0)); assertFalse(it.moveToNext()) }
        sql.query("SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'replica_fence_%'").use {
            assertTrue(it.moveToFirst()); assertEquals(135, it.getInt(0)); assertFalse(it.moveToNext())
        }
        CountCommandStorageInitialization.verifyMandatory(sql); CountTrustSchema.validate(sql)
    }
}
