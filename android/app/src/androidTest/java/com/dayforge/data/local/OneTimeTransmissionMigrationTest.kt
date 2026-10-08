package com.dayforge.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.entity.OneTimeTransmissionEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OneTimeTransmissionMigrationTest {
    private lateinit var context: Context
    private var room: HabitDatabase? = null
    private val name = "habit_database"
    private val identity = "8ab28a1519468862873003932dd14762"
    private val schema by lazy {
        val text = InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.dayforge.data.local.HabitDatabase/4.json").bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonObject.getValue("database").jsonObject
    }
    private val tables get() = schema.getValue("entities").jsonArray.map { it.jsonObject.getValue("tableName").jsonPrimitive.content }

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        check(context.packageName == "com.dayforge.testbed")
        HabitDatabaseProvider.clearInstanceForTesting()
        context.deleteDatabase(name)
        assertEquals(identity, schema.getValue("identityHash").jsonPrimitive.content)
    }

    @After fun cleanup() {
        room?.close()
        HabitDatabaseProvider.clearInstanceForTesting()
        context.deleteDatabase(name)
    }

    private fun open(): HabitDatabase {
        room?.close()
        HabitDatabaseProvider.clearInstanceForTesting()
        return HabitDatabaseProvider.getInstance(context).also { room = it }
    }

    private fun rows(db: SupportSQLiteDatabase, query: String): List<List<String?>> = db.query(query).use { cursor ->
        buildList { while (cursor.moveToNext()) add(List(cursor.columnCount) { if (cursor.isNull(it)) null else cursor.getString(it) }) }
    }

    private fun snapshot(db: SupportSQLiteDatabase) = tables.associateWith { table ->
        val columns = schema.getValue("entities").jsonArray.first {
            it.jsonObject.getValue("tableName").jsonPrimitive.content == table
        }.jsonObject.getValue("fields").jsonArray.joinToString(",") {
            "`${it.jsonObject.getValue("columnName").jsonPrimitive.content}`"
        }
        rows(db, "SELECT $columns FROM `$table` ORDER BY rowid")
    }
    private fun structure(db: SupportSQLiteDatabase) = rows(db,
        "SELECT type,name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND tbl_name NOT IN ('next_restart_materializations','next_restart_plan_proofs','next_challenge_state','next_challenge_rounds','next_challenge_births','count_days','next_sync_state','next_rejections','next_structural_dependencies','next_structural_supersessions','one_time_transmissions','next_recovery_state','next_request_origins','next_transmissions', 'next_acceptances') " +
            "AND NOT (type='table' AND name IN ('habits','metrics')) " +
            "AND name NOT IN ('sync_habits_update','sync_metrics_update') ORDER BY type,name")

    private fun seed(block: (SupportSQLiteDatabase) -> Unit = {}) {
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(4) {
                override fun onConfigure(db: SupportSQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
                override fun onCreate(db: SupportSQLiteDatabase) {
                    schema.getValue("entities").jsonArray.forEach { element ->
                        val entity = element.jsonObject
                        val table = entity.getValue("tableName").jsonPrimitive.content
                        fun sql(value: JsonElement) = value.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                        db.execSQL(sql(entity.getValue("createSql")))
                        entity["indices"]?.jsonArray.orEmpty().forEach { db.execSQL(sql(it.jsonObject.getValue("createSql"))) }
                    }
                    schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
            }).build())
        helper.use {
            val db = it.writableDatabase
            db.execSQL("INSERT INTO local_fact_submissions VALUES ('op','activity_event','event','activity','{\"frozen\":true}')")
            db.execSQL("INSERT INTO completion_metric_prompts VALUES ('event','activity','pending',3,'[]',123,'Asia/Shanghai')")
            SyncSchemaCallback.onOpen(db)
            block(db)
        }
    }

    @Test fun versionFourRowsSchemaAndFrozenLocalReceiptsSurviveWithoutInventingBindings() = runBlocking {
        var before = emptyMap<String, List<List<String?>>>()
        var ddl = emptyList<List<String?>>()
        seed { before = snapshot(it); ddl = structure(it) }
        assertEquals(15, tables.size)
        repeat(2) {
            val db = open()
            val sql = db.openHelper.writableDatabase
            assertEquals(15, sql.version)
            assertEquals(before, snapshot(sql))
            assertEquals(ddl, structure(sql))
            assertNull(db.completionFollowUpDao().transmission("op"))
            assertEquals(listOf(listOf("ok")), rows(sql, "PRAGMA integrity_check"))
            assertTrue(rows(sql, "PRAGMA foreign_key_check").isEmpty())
        }
    }

    @Test fun validationFailureAfterCreateRollsBackNewTableAndCanRetry() {
        seed { it.execSQL("ALTER TABLE completion_metric_prompts RENAME TO unavailable_metric_prompts") }
        val failure = runCatching { open().openHelper.writableDatabase }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("Migration didn't properly handle"))
        room!!.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            assertEquals(4, raw.version)
            raw.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use {
                assertTrue(it.moveToFirst()); assertEquals(identity, it.getString(0))
            }
            raw.rawQuery("SELECT name FROM sqlite_master WHERE name='one_time_transmissions'", null).use { assertFalse(it.moveToFirst()) }
            raw.rawQuery("SELECT payloadJson FROM local_fact_submissions WHERE operationId='op'", null).use {
                assertTrue(it.moveToFirst()); assertEquals("{\"frozen\":true}", it.getString(0))
            }
            raw.execSQL("ALTER TABLE unavailable_metric_prompts RENAME TO completion_metric_prompts")
        }
        assertEquals(15, open().openHelper.writableDatabase.version)
    }

    @Test fun forgedVersionFourIdentityIsRejectedWithoutDeletingRows() {
        seed { it.execSQL("UPDATE room_master_table SET identity_hash='not-the-baseline' WHERE id=42") }
        val failure = runCatching { open().openHelper.writableDatabase }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("integrity"))
        room!!.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(4, raw.version)
            raw.rawQuery("SELECT COUNT(*) FROM local_fact_submissions", null).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        }
    }

    @Test fun bindingUniquenessRejectionCasAndAccountClearSurviveRestart() = runBlocking {
        val db = open()
        val dao = db.completionFollowUpDao()
        val row = OneTimeTransmissionEntity("op", "account", "server", "epoch", "device", "{\"original\":true}")
        dao.insertTransmission(row)
        assertTrue(runCatching { dao.insertTransmission(row.copy(deviceId = "changed")) }.isFailure)
        assertEquals(1, dao.recordRejection("op", "{\"rejected\":true}"))
        assertEquals(0, dao.recordRejection("op", "{\"changed\":true}"))
        assertEquals(row.copy(rejectionJson = "{\"rejected\":true}"), open().completionFollowUpDao().transmission("op"))
        assertTrue(room!!.syncOutboxDao().hasOneTimeIntents())
        room!!.clearAllData()
        assertNull(open().completionFollowUpDao().transmission("op"))
        assertFalse(room!!.syncOutboxDao().hasOneTimeIntents())
    }
}
