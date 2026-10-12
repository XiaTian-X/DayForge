package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.entity.MetricLogEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Compare the production schema against an owned reference, preserving physical old file/DS. */
@RunWith(AndroidJUnit4::class)
class NextReplicaSchemaValidatorTest : NextCoreRequestFixture() {
    private suspend fun hash() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().hash }
    private suspend fun verify(hash: String, original: LocalSyncAccess) =
        NextReplicaSchemaValidator(app, db, tokens, sessions).validate(original, hash)
    private suspend fun unchanged(block: suspend () -> Unit) {
        val image = storage.snapshotClosedDatabase(); val prefs = dataStore.data.first(); val original = access()
        block()
        assertArrayEquals(image, storage.snapshotClosedDatabase()); assertEquals(prefs, dataStore.data.first())
        assertEquals(original, access()); assertSame(db, HabitDatabaseProvider.getInstance(app))
    }
    private fun rewriteEmptyTable(table: String, edit: (String) -> String) {
        require(table.matches(Regex("[a-z_]+")))
        val sql = db.openHelper.writableDatabase
        sql.query("SELECT COUNT(*) FROM $table").use { require(it.moveToFirst() && it.getLong(0) == 0L) }
        val original = sql.query("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use {
            require(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_STRING); it.getString(0)
        }
        val indices = sql.query("SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name=? AND sql IS NOT NULL", arrayOf(table)).use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
        val changed = edit(original); require(changed != original)
        sql.execSQL("DROP TABLE $table") // This empty, isolated testbed table only.
        sql.execSQL(changed); indices.forEach(sql::execSQL)
    }

    @Test fun freshAndAnalyzedSchemaAreReadOnlyAndRemainValidAfterColdReopen() = runBlocking<Unit> {
        register(); val original = access()
        for (analyzed in listOf(false, true)) {
            if (analyzed) db.openHelper.writableDatabase.execSQL("ANALYZE")
            val point = hash()
            unchanged {
                val verified = verify(point, original)
                assertEquals(point, verified.storageHash); assertFalse(verified.activationAuthorized)
                storage.reopen()
                assertEquals(point, verify(point, original).storageHash)
            }
        }
    }

    @Test fun actualVersionOneToCurrentMigrationAcceptsDifferentColumnOrderWithoutChangingOldFile() = runBlocking<Unit> {
        register()
        val name = "replica-schema-${UUID.randomUUID()}"
        val schema = Json.parseToJsonElement(InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.dayforge.data.local.HabitDatabase/1.json").bufferedReader().use { it.readText() })
            .jsonObject.getValue("database").jsonObject
        try {
            FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(app)
                .name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        schema.getValue("entities").jsonArray.forEach { value ->
                            val entity = value.jsonObject; val table = entity.getValue("tableName").jsonPrimitive.content
                            fun statement(value: JsonElement) = value.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                            db.execSQL(statement(entity.getValue("createSql")))
                            entity["indices"]?.jsonArray.orEmpty().forEach { db.execSQL(statement(it.jsonObject.getValue("createSql"))) }
                        }
                        schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                        db.execSQL("INSERT INTO sync_control(id,suppressOutbox) VALUES(1,0)")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        error("Unexpected fixture upgrade")
                }).build()).use { it.writableDatabase }
            val file = app.getDatabasePath(name)
            require(file.isFile && file.length() in 1..67_108_864L)
            storage.restoreClosedDatabase(file.readBytes()) // Only the owned testbed DB; production migrates 1→17.
            assertEquals(17, db.openHelper.writableDatabase.version)
            val point = hash(); val original = access()
            unchanged { assertEquals(point, verify(point, original).storageHash) }
        } finally { assertTrue(app.deleteDatabase(name)) }
    }

    @Test fun unchangedRoomHashCannotHideLostDeferredForeignKeyOrNullableConstraint() = runBlocking<Unit> {
        register(); val original = access()
        rewriteEmptyTable("next_restart_plan_proofs") { it.replace("DEFERRABLE INITIALLY DEFERRED", "NOT DEFERRABLE INITIALLY IMMEDIATE") }
        val deferral = hash()
        unchanged { assertEquals("SYNC_RECOVERY_SCHEMA_INVALID", rejected { verify(deferral, original) }.message) }
        rewriteEmptyTable("next_restart_plan_proofs") {
            it.replace("NOT DEFERRABLE INITIALLY IMMEDIATE", "DEFERRABLE INITIALLY DEFERRED")
                .replace("`revision` INTEGER NOT NULL", "`revision` INTEGER")
        }
        val nullable = hash()
        unchanged { assertEquals("SYNC_RECOVERY_SCHEMA_INVALID", rejected { verify(nullable, original) }.message) }
    }

    @Test fun changedIndexCollationDirectionAndSameNamedTriggerCannotPass() = runBlocking<Unit> {
        register(); val original = access()
        db.openHelper.writableDatabase.execSQL("DROP INDEX index_metrics_uuid")
        db.openHelper.writableDatabase.execSQL("CREATE INDEX index_metrics_uuid ON metrics(uuid COLLATE NOCASE DESC)")
        val index = hash()
        unchanged { assertEquals("SYNC_RECOVERY_SCHEMA_INVALID", rejected { verify(index, original) }.message) }
        db.openHelper.writableDatabase.execSQL("DROP INDEX index_metrics_uuid")
        db.openHelper.writableDatabase.execSQL("CREATE INDEX index_metrics_uuid ON metrics(uuid)")
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER sync_metrics_update")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER sync_metrics_update AFTER UPDATE ON metrics BEGIN SELECT 1; END")
        val trigger = hash()
        unchanged { assertEquals("SYNC_RECOVERY_SCHEMA_INVALID", rejected { verify(trigger, original) }.message) }
    }

    @Test fun blobInIntegerBusinessFieldAndOrphanForeignKeyAreRejectedBeforeArchive() = runBlocking<Unit> {
        register(); val original = access()
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            sql.execSQL("UPDATE habits SET targetValue=CAST('10' AS BLOB) WHERE uuid=?", arrayOf(habit.uuid))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val damaged = hash()
        unchanged { assertEquals("SYNC_RECOVERY_SQL_TYPE_INVALID", rejected { verify(damaged, original) }.message) }
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            sql.execSQL("UPDATE habits SET targetValue=10 WHERE uuid=?", arrayOf(habit.uuid))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val row = db.metricLogDao().insert(MetricLogEntity(metricId = metric.id, date = millis, value = 1.0, unit = "kg"))
        // Room may configure a separate connection for a DAO transaction. Corrupt ONLY
        // the closed testbed file, then reopen via the actual production provider.
        check(app.packageName == "com.dayforge.testbed")
        db.close(); HabitDatabaseProvider.clearInstanceForTesting()
        SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.setForeignKeyConstraintsEnabled(false)
            it.execSQL("UPDATE metric_logs SET metricId=9999 WHERE id=?", arrayOf(row))
        }
        storage.reopen()
        val orphan = hash()
        unchanged { assertEquals("SYNC_RECOVERY_FOREIGN_KEY_INVALID", rejected { verify(orphan, original) }.message) }
    }

    @Test fun unknownViewAndExtraIdentityRowCannotBeApprovedAsCurrentSchema() = runBlocking<Unit> {
        register(); val original = access()
        db.openHelper.writableDatabase.execSQL("CREATE VIEW unreviewed_archive_data AS SELECT * FROM metrics")
        val view = hash()
        unchanged { assertEquals("SYNC_RECOVERY_SCHEMA_INVALID", rejected { verify(view, original) }.message) }
        db.openHelper.writableDatabase.execSQL("DROP VIEW unreviewed_archive_data")
        db.openHelper.writableDatabase.execSQL("INSERT INTO room_master_table(id,identity_hash) VALUES(43,'unreviewed')")
        val identity = hash()
        unchanged { assertEquals("SYNC_RECOVERY_SCHEMA_INVALID", rejected { verify(identity, original) }.message) }
    }

    @Test fun changedContentsOrAccountAuthorityCannotReuseOldProof() = runBlocking<Unit> {
        register(); val original = access(); val point = hash()
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            sql.execSQL("UPDATE metrics SET name='New value' WHERE uuid=?", arrayOf(metric.uuid))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        unchanged { assertEquals("SYNC_RECOVERY_PREVIEW_CHANGED", rejected { verify(point, original) }.message) }
        register(revision = 2)
        unchanged { assertEquals("SYNC_ACCOUNT_CHANGED", rejected { verify(point, original) }.message) }
    }

    @Test fun strictLexerPreservesLiteralAndConstraintMeaningAndOnlyNormalizesOwnedFormatting() {
        fun table(sql: String) = ReplicaSchemaSql.table(ReplicaSchemaSql.tokens(sql), "example")
        val quoted = table("CREATE TABLE IF NOT EXISTS `example` (`id` INTEGER NOT NULL, `name` TEXT DEFAULT 'UTC', PRIMARY KEY(`id`))")
        assertEquals(quoted, table("create table example(name TEXT DEFAULT 'UTC', PRIMARY KEY(id), id INTEGER NOT NULL)"))
        assertEquals(quoted, table("CREATE TABLE example(id INTEGER NOT NULL PRIMARY KEY, name TEXT DEFAULT 'UTC')"))
        assertNotEquals(quoted, table("CREATE TABLE example(id INTEGER NOT NULL PRIMARY KEY DESC, name TEXT DEFAULT 'UTC')"))
        assertNotEquals(quoted, table("CREATE TABLE example(id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, name TEXT DEFAULT 'UTC')"))
        assertNotEquals(quoted, table("CREATE TABLE example(id INTEGER NOT NULL, name TEXT DEFAULT 'utc', PRIMARY KEY(id))"))
        assertNotEquals(table("CREATE TABLE example(id INTEGER, FOREIGN KEY(id) REFERENCES example(id) DEFERRABLE INITIALLY DEFERRED)"),
            table("CREATE TABLE example(id INTEGER, FOREIGN KEY(id) REFERENCES example(id) NOT DEFERRABLE INITIALLY IMMEDIATE)"))
        for (sql in listOf("CREATE TABLE example(id INTEGER /* comment */)", "CREATE TABLE example(\"id\" INTEGER)",
            "CREATE TABLE example(id INTEGER) WITHOUT ROWID", "CREATE TABLE example(id TEXT DEFAULT 'unterminated)")) {
            assertTrue(runCatching { table(sql) }.isFailure)
        }
    }
}
