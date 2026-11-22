package com.dayforge.data.local

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.entity.NextReplicaFenceEntity
import com.dayforge.data.repository.CountCommandNativeRows
import com.dayforge.data.repository.CountCommandRootAnchor
import com.dayforge.data.repository.NextReplicaSourceClosure
import com.dayforge.data.repository.NextReplicaStorageProof
import com.dayforge.data.repository.NextReplicaWriteFence
import com.dayforge.data.repository.ReplicaProjection
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real v18 file -> production v19 factory; no migration is allowed to bless old business bytes. */
@RunWith(AndroidJUnit4::class)
class CountTrustMigrationTest {
    private lateinit var context: Context
    private var room: HabitDatabase? = null
    private val oldIdentity = "ea4615ceb013fc2a9269d6ec9077adeb"
    private val schema by lazy {
        Json.parseToJsonElement(InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.dayforge.data.local.HabitDatabase/18.json").bufferedReader().use { it.readText() })
            .jsonObject.getValue("database").jsonObject
    }
    private val entities get() = schema.getValue("entities").jsonArray.map { it.jsonObject }

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        check(context.packageName == "com.dayforge.testbed")
        HabitDatabaseProvider.clearInstanceForTesting(); context.deleteDatabase("habit_database")
        assertEquals(oldIdentity, schema.getValue("identityHash").jsonPrimitive.content)
    }
    @After fun cleanup() {
        room?.close(); HabitDatabaseProvider.clearInstanceForTesting(); context.deleteDatabase("habit_database")
    }
    private fun open(): SupportSQLiteDatabase {
        room?.close(); HabitDatabaseProvider.clearInstanceForTesting()
        room = HabitDatabaseProvider.getInstance(context)
        return room!!.openHelper.writableDatabase
    }
    private fun rows(db: SupportSQLiteDatabase, query: String) = db.query(query).use { cursor ->
        buildList { while (cursor.moveToNext()) add(List(cursor.columnCount) { index -> when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_BLOB -> "blob:" + cursor.getBlob(index).joinToString("") { "%02x".format(it) }
            else -> cursor.getType(index).toString() + ":" + cursor.getString(index)
        } }) }
    }
    private fun snapshot(db: SupportSQLiteDatabase) = entities.associate { entity ->
        val table = entity.getValue("tableName").jsonPrimitive.content
        val columns = entity.getValue("fields").jsonArray.joinToString(",") { "`${it.jsonObject.getValue("columnName").jsonPrimitive.content}`" }
        table to rows(db, "SELECT $columns FROM `$table` ORDER BY rowid")
    }
    private fun oldDdl(db: SupportSQLiteDatabase) = rows(db,
        "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' " +
            "AND substr(name,1,${CountTrustSchema.DIRTY_PREFIX.length})<>'${CountTrustSchema.DIRTY_PREFIX}' " +
            "AND tbl_name NOT IN (${CountCommandV20Schema.currentLocalTables.joinToString(",") { "'$it'" }}) ORDER BY type,name")
    private fun seed(block: (SupportSQLiteDatabase) -> Unit = {}) {
        FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("habit_database").callback(object : SupportSQLiteOpenHelper.Callback(18) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    entities.forEach { entity ->
                        val table = entity.getValue("tableName").jsonPrimitive.content
                        fun sql(value: JsonElement) = value.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                        db.execSQL(sql(entity.getValue("createSql")))
                        entity["indices"]?.jsonArray.orEmpty().forEach { db.execSQL(sql(it.jsonObject.getValue("createSql"))) }
                    }
                    schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
            }).build()).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO sync_control(id,suppressOutbox) VALUES(1,0)")
            db.execSQL("INSERT INTO next_request_origins VALUES('sync_operation','original',1,5,'account',NULL,NULL,'source','{\"unicode\":\"家庭\\\\引用\"}')")
            db.execSQL("INSERT INTO next_transmissions VALUES('sync_operation','original',1,5,'account','server','epoch','device','wire',X'007FFF')")
            db.execSQL("INSERT INTO next_rejections VALUES('sync_operation','original','origin','transmission','result','untouched')")
            db.execSQL("INSERT INTO next_config_imports VALUES('import','account','server','epoch','device','archive','identities','replacement','prepared','network')")
            db.execSQL("INSERT INTO next_config_import_payloads VALUES('import','network',0,X'007FFF')")
            SyncSchemaCallback.onOpen(db)
            block(db)
        }
    }

    @Test fun allOldTablesDdlAndInflightWireSurviveWithoutIssuingRootAcrossReopen() {
        var before = emptyMap<String, List<List<String?>>>()
        var ddl = emptyList<List<String?>>()
        seed { before = snapshot(it); ddl = oldDdl(it) }
        assertEquals(36, entities.size)
        var control: List<List<String?>>? = null
        repeat(2) {
            val db = open()
            assertEquals(20, db.version); assertEquals(before, snapshot(db)); assertEquals(ddl, oldDdl(db))
            CountTrustSchema.validate(db)
            assertEquals(listOf(listOf("1:1")), rows(db, "SELECT dirty FROM count_trust_control"))
            val current = rows(db, "SELECT * FROM count_trust_control")
            if (control == null) control = current else assertEquals(control, current)
            for (table in CountTrustSchema.LOCAL_TABLES - "count_trust_control") assertTrue(rows(db, "SELECT * FROM `$table`").isEmpty())
            assertEquals(listOf(listOf("3:" + currentHabitIdentity())), rows(db, "SELECT identity_hash FROM room_master_table WHERE id=42"))
            assertTrue(rows(db, "PRAGMA foreign_key_check").isEmpty())
            assertEquals(listOf(listOf("3:ok")), rows(db, "PRAGMA integrity_check"))
        }
    }

    @Test fun occupiedLocalTableRollsBackMigrationWithoutOverwritingBytes() {
        for (occupied in CountTrustSchema.LOCAL_TABLES) {
            room?.close(); context.deleteDatabase("habit_database")
            seed { it.execSQL("CREATE TABLE `$occupied`(unproven TEXT)"); it.execSQL("INSERT INTO `$occupied` VALUES('keep')") }
            assertNotNull(runCatching { open() }.exceptionOrNull()); room!!.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
                assertEquals(18, raw.version)
                raw.rawQuery("SELECT unproven FROM `$occupied`", null).use { assertTrue(it.moveToFirst()); assertEquals("keep", it.getString(0)) }
                for (table in CountTrustSchema.LOCAL_TABLES - occupied) raw.rawQuery("SELECT name FROM sqlite_master WHERE name=?", arrayOf(table)).use { assertFalse(it.moveToFirst()) }
                raw.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use { assertTrue(it.moveToFirst()); assertEquals(oldIdentity, it.getString(0)) }
            }
        }
    }

    @Test fun falseVersion18IdentityCannotAcquireNewTrustStorage() {
        seed { it.execSQL("UPDATE room_master_table SET identity_hash='unproven' WHERE id=42") }
        assertNotNull(runCatching { open() }.exceptionOrNull()); room!!.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(18, raw.version)
            for (table in CountTrustSchema.LOCAL_TABLES) raw.rawQuery("SELECT name FROM sqlite_master WHERE name=?", arrayOf(table)).use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test fun missingDirtyTriggerOnReopenIsRejectedWithoutSilentRepair() {
        seed(); open(); room!!.close()
        val missing = CountTrustSchema.dirtyTriggers.keys.first()
        SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TRIGGER `$missing`")
        }
        assertNotNull(runCatching { open() }.exceptionOrNull()); room!!.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READONLY).use {
            it.rawQuery("SELECT name FROM sqlite_master WHERE name=?", arrayOf(missing)).use { cursor -> assertFalse(cursor.moveToFirst()) }
        }
    }

    @Test fun accountClearIsAtomicAndLeavesOnlyFreshUntrustedLocalControl() = runBlocking<Unit> {
        seed()
        CountCommandMigrationFixture.upgradeFrozen18To19(context) { previous ->
            previous.execSQL("INSERT INTO count_command_results VALUES('command','input','fact','request','result','committed')")
        }
        val sql = open(); val oldStorage = rows(sql, "SELECT storageId FROM count_trust_control")
        val db = room!!
        db.withTransaction { CountTrustRuntime.maintenance(db) {
            sql.execSQL("INSERT INTO count_trust_nodes(digest,body) VALUES(?,?)", arrayOf("a".repeat(64), byteArrayOf(1, 2)))
        } }
        db.clearAllData()
        assertNotEquals(oldStorage, rows(sql, "SELECT storageId FROM count_trust_control"))
        for (table in CountTrustSchema.BUSINESS_TABLES - "sync_control") assertTrue(table, rows(sql, "SELECT * FROM `$table`").isEmpty())
        for (table in CountTrustSchema.LOCAL_TABLES - "count_trust_control") assertTrue(table, rows(sql, "SELECT * FROM `$table`").isEmpty())
        assertEquals(listOf(listOf("1:1", "1:0")), rows(sql, "SELECT id,suppressOutbox FROM sync_control"))
        CountTrustSchema.validate(open())
    }

    @Test fun malformedMissingAndOverflowControlAbortBusinessWritesWithoutChangingFacts() = runBlocking<Unit> {
        seed(); val sql = open(); val db = room!!
        val before = snapshot(sql)
        val originalControl = CountCommandRootAnchor.read(sql)
        val originalAnchor = requireNotNull(originalControl.hash)
        val originalRoot = db.withTransaction { CountCommandStorageInitialization.verifyRootAnchor(sql).native() }
        val extraRow = "INSERT INTO count_trust_control(id,storageId,generation,dirty,commandRootHash) " +
            "VALUES(2,'00000000-0000-0000-0000-000000000001',0,1,?)"
        for (damage in listOf(
            "UPDATE count_trust_control SET generation=9223372036854775807",
            "UPDATE count_trust_control SET generation=0.5",
            "UPDATE count_trust_control SET dirty=2",
            "UPDATE count_trust_control SET storageId='unproven'",
            "DELETE FROM count_trust_control",
            extraRow
        )) {
            db.withTransaction { CountTrustRuntime.maintenance(db) {
                if (damage == extraRow) sql.execSQL(damage, arrayOf(originalAnchor)) else sql.execSQL(damage)
            } }
            if (damage == extraRow) {
                assertEquals(listOf(listOf("1:2", "3:00000000-0000-0000-0000-000000000001", "1:0", "1:1", "3:$originalAnchor")),
                    rows(sql, "SELECT id,storageId,generation,dirty,commandRootHash FROM main.count_trust_control WHERE id=2"))
                assertEquals(listOf(listOf("1:2")), rows(sql, "SELECT count(*) FROM main.count_trust_control"))
            }
            val failure = runCatching { db.withTransaction {
                sql.execSQL("UPDATE next_rejections SET resultJson='must-not-commit' WHERE requestId='original'")
            } }.exceptionOrNull()
            assertNotNull(damage, failure)
            assertTrue(damage, generateSequence(failure) { it.cause }.any {
                it.message?.contains("COUNT_TRUST_CONTROL_INVALID") == true
            })
            assertEquals(damage, before, snapshot(sql))
            // Undo only this test's fixed corruption; a real whole-storage reset must rotate
            // the primary dataset too. Keep the original valid v20 root/control binding.
            db.withTransaction { CountTrustRuntime.maintenance(db) {
                sql.execSQL("DELETE FROM count_trust_control")
                sql.execSQL("INSERT INTO count_trust_control(id,storageId,generation,dirty,commandRootHash) VALUES(1,?,?,?,?)",
                    arrayOf<Any>(originalControl.storage, originalControl.generation, originalControl.dirty, originalAnchor))
            } }
            CountTrustSchema.validate(sql)
            assertEquals(originalControl, CountCommandRootAnchor.read(sql))
            val restoredRoot = db.withTransaction { CountCommandStorageInitialization.verifyRootAnchor(sql).native() }
            assertTrue("Restoring only the fixture corruption must not replace or re-sign the primary root",
                CountCommandNativeRows.sameRoot(originalRoot, restoredRoot))
            assertEquals(damage, before, snapshot(sql))
        }
    }

    @Test fun frozenReplicaCanInvalidateTrustButCannotMutateOrCopyLocalQualification() = runBlocking<Unit> {
        seed(); val sql = open(); val db = room!!
        fun id() = UUID.randomUUID().toString()
        val fence = NextReplicaFenceEntity(id(), id(), id(), id(), id(), id(), id(), 1, "a".repeat(64))
        val before = db.withTransaction { NextReplicaStorageProof(db).readInTransaction() }
        val beforeControl = rows(sql, "SELECT storageId,generation FROM count_trust_control").single()
        NextReplicaWriteFence(db).acquire(fence)
        val during = db.withTransaction { NextReplicaStorageProof(db).readInTransaction() }
        assertEquals(NextReplicaSourceClosure.storage(before, null), NextReplicaSourceClosure.storage(during, fence))
        assertEquals(beforeControl[0], rows(sql, "SELECT storageId,generation FROM count_trust_control").single()[0])
        val beforeGeneration = beforeControl[1]!!.substringAfter(':').toLong()
        assertEquals(listOf(listOf("1:${beforeGeneration + 1}", "1:1")), rows(sql, "SELECT generation,dirty FROM count_trust_control"))
        for (statement in listOf("UPDATE count_trust_control SET dirty=0", "DELETE FROM count_trust_control",
            "UPDATE count_trust_control SET storageId='00000000-0000-0000-0000-000000000001'")) {
            val error = runCatching { db.withTransaction { CountTrustRuntime.maintenance(db) { sql.execSQL(statement) } } }.exceptionOrNull()
            assertNotNull(statement, error)
        }
        val closed = NextReplicaWriteFence(db).closeBeforeCommit(fence)
        val after = db.withTransaction { NextReplicaStorageProof(db).readInTransaction() }
        assertEquals(NextReplicaSourceClosure.storage(before, null), NextReplicaSourceClosure.storage(after, closed))
        val projection = ReplicaProjection.capture(db)
        assertEquals(35, projection.tables.size)
        assertTrue(projection.tables.keys.none { it in CountTrustSchema.LOCAL_TABLES })
        assertTrue(after.contents.getValue("tables").jsonObject.keys.containsAll(CountTrustSchema.LOCAL_TABLES))
        CountTrustSchema.validate(open())
    }
}
