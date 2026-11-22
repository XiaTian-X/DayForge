package com.dayforge.data.repository

import com.dayforge.data.local.ReplicaSchemaSql

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.PlaintextTokenCipher
import com.dayforge.data.local.TokenCipher
import com.dayforge.data.local.entity.MetricLogEntity
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Compare the production schema against an owned reference, preserving physical old file/DS. */
@RunWith(AndroidJUnit4::class)
class NextReplicaSchemaValidatorTest : NextCoreRequestFixture() {
    private val nativeFault = RestartConstraintFault()
    init { storage.isolatedBuilder = { buildDiagnosticFaultDatabase(it, nativeFault) } }

    /** Exactly the two original empty-table constraint faults, below the unchanged gate. */
    private class RestartConstraintFault : SupportSQLiteOpenHelper.Factory {
        enum class Kind { LOST_DEFERRAL, NULLABLE_REVISION }
        private var native: SupportSQLiteDatabase? = null
        private var originalSql: String? = null
        private var originalIndices: List<String> = emptyList()
        private var originalTriggers: List<String> = emptyList()
        fun rewrite(kind: Kind): String {
            val raw = requireNotNull(native); check(raw.isOpen && !raw.inTransaction())
            raw.query("SELECT COUNT(*) FROM main.next_restart_plan_proofs").use { check(it.moveToFirst() && it.getLong(0) == 0L) }
            val ddl = raw.query("SELECT sql FROM main.sqlite_master WHERE type='table' AND name='next_restart_plan_proofs'").use {
                check(it.moveToFirst()); it.getString(0).also { _ -> check(!it.moveToNext()) }
            }
            fun definitions(type: String) = raw.query("SELECT sql FROM main.sqlite_master WHERE type=? AND tbl_name='next_restart_plan_proofs' AND sql IS NOT NULL ORDER BY name", arrayOf(type)).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            originalSql = ddl; originalIndices = definitions("index"); originalTriggers = definitions("trigger")
            val changed = when (kind) {
                Kind.LOST_DEFERRAL -> ddl.replace("DEFERRABLE INITIALLY DEFERRED", "NOT DEFERRABLE INITIALLY IMMEDIATE")
                Kind.NULLABLE_REVISION -> ddl.replace("`revision` INTEGER NOT NULL", "`revision` INTEGER")
            }
            check(changed != ddl)
            raw.beginTransaction()
            try {
                raw.execSQL("DROP TABLE next_restart_plan_proofs")
                raw.execSQL(changed); originalIndices.forEach(raw::execSQL); originalTriggers.forEach(raw::execSQL)
                raw.setTransactionSuccessful()
            } finally { raw.endTransaction() }
            return changed
        }
        fun restoreClosed(raw: SQLiteDatabase) {
            check(raw.inTransaction())
            raw.execSQL("DROP TABLE next_restart_plan_proofs")
            raw.execSQL(requireNotNull(originalSql)); originalIndices.forEach(raw::execSQL); originalTriggers.forEach(raw::execSQL)
        }
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            fun remember(db: SupportSQLiteDatabase) = db.also { native = it }
            val parent = configuration.callback
            val callback = object : SupportSQLiteOpenHelper.Callback(parent.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(remember(db))
                override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(remember(db))
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(remember(db), oldVersion, newVersion)
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onDowngrade(remember(db), oldVersion, newVersion)
                override fun onOpen(db: SupportSQLiteDatabase) = parent.onOpen(remember(db))
                override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(remember(db))
            }
            val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : SupportSQLiteOpenHelper by helper {
                override val writableDatabase get() = remember(helper.writableDatabase)
                override val readableDatabase get() = remember(helper.readableDatabase)
            }
        }
    }

    private val decryptCalls = AtomicInteger()
    private val failDecryptAt = AtomicInteger(Int.MAX_VALUE)
    override fun tokenCipher(): TokenCipher = object : TokenCipher {
        override fun encrypt(value: String) = PlaintextTokenCipher.encrypt(value)
        override fun decrypt(value: String): String? =
            if (decryptCalls.incrementAndGet() == failDecryptAt.get()) null else PlaintextTokenCipher.decrypt(value)
    }
    private suspend fun hash() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().hash }
    private suspend fun verify(hash: String, original: LocalSyncAccess) =
        NextReplicaSchemaValidator(app, db, tokens, sessions).validate(original, hash)
    private suspend fun unchanged(block: suspend () -> Unit) {
        val image = storage.snapshotClosedDatabase(); val prefs = dataStore.data.first(); val original = access()
        block()
        assertArrayEquals(image, storage.snapshotClosedDatabase()); assertEquals(prefs, dataStore.data.first())
        assertEquals(original, access()); assertSame(db, HabitDatabaseProvider.getInstance(app))
    }
    private suspend fun requireControlledRejection(statement: String, reason: String) {
        check(!db.inTransaction())
        val before = hash()
        val failure = runCatching { db.openHelper.writableDatabase.execSQL(statement) }.exceptionOrNull()
        assertTrue("The controlled entry must reject this physical fault before SQL: $failure", failure is IllegalStateException)
        assertEquals(reason, failure?.message)
        assertEquals("Rejected fault setup must not change any stored byte or DDL", before, hash())
    }

    /** Fault injection only: close the owned testbed connection, use one independent native
     * transaction, then reopen the real production provider. No production handle escape.
     */
    private fun corruptClosedTestbed(change: (SQLiteDatabase) -> Unit) {
        check(app.packageName == "com.dayforge.testbed" && !db.inTransaction())
        db.close(); HabitDatabaseProvider.clearInstanceForTesting()
        try {
            SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READWRITE).use { native ->
                check(!native.inTransaction())
                native.beginTransaction()
                try { change(native); native.setTransactionSuccessful() }
                finally { native.endTransaction() }
            }
        } finally { storage.reopen() }
    }

    private suspend fun rewriteRestartConstraint(kind: RestartConstraintFault.Kind) {
        check(!db.inTransaction())
        val sql = db.openHelper.writableDatabase
        val identity = sql.query("SELECT identity_hash FROM main.room_master_table WHERE id=42").use {
            check(it.moveToFirst()); it.getString(0)
        }
        val guard = sql.query("SELECT sql FROM main.sqlite_master WHERE type='trigger' AND tbl_name='next_restart_plan_proofs' AND name LIKE 'count_trust_dirty_%' ORDER BY name LIMIT 1").use {
            check(it.moveToFirst()); it.getString(0)
        }
        requireControlledRejection(guard, "COUNT_TRUST_SCHEMA_DDL_DENIED")
        val changed = nativeFault.rewrite(kind)
        sql.query("SELECT sql FROM main.sqlite_master WHERE type='table' AND name='next_restart_plan_proofs'").use {
            assertTrue(it.moveToFirst()); assertEquals("Actual fixed native constraint fault", changed, it.getString(0)); assertFalse(it.moveToNext())
        }
        sql.query("SELECT identity_hash FROM main.room_master_table WHERE id=42").use {
            assertTrue(it.moveToFirst()); assertEquals(identity, it.getString(0)); assertFalse(it.moveToNext())
        }
    }

    /** First reach the original validator while its actual owner is open. Then separately
     * prove the stricter cold mandatory Root rejects the same physical schema with zero change.
     */
    private suspend fun schemaRejectedWarmAndCold(original: LocalSyncAccess, restore: (SQLiteDatabase) -> Unit) {
        val before = db.withTransaction { NextReplicaStorageProof(db).readInTransaction() }
        val prefs = dataStore.data.first()
        assertEquals("SYNC_RECOVERY_SCHEMA_INVALID", rejected { verify(before.hash, original) }.message)
        val after = db.withTransaction { NextReplicaStorageProof(db).readInTransaction() }
        assertEquals(before.contents, after.contents); assertEquals(before.hash, after.hash)
        assertEquals(prefs, dataStore.data.first()); assertEquals(original, access())
        val image = storage.snapshotClosedDatabase()
        val cold = runCatching { db.openHelper.writableDatabase }.exceptionOrNull()
        assertTrue("Cold mandatory primary binding must reject before the validator", cold is IllegalArgumentException)
        assertEquals("COMMAND_PRIMARY_BINDING_CHANGED", cold?.message)
        db.close(); HabitDatabaseProvider.clearInstanceForTesting()
        val file = app.getDatabasePath("habit_database")
        assertArrayEquals("Cold rejection cannot alter the closed native image", image, file.readBytes())
        assertEquals(prefs, dataStore.data.first()); assertEquals(original, access())
        corruptClosedTestbed(restore)
        // Only restore the fixed test fault; a valid original root must verify on the real open.
        val repaired = hash()
        assertEquals(repaired, verify(repaired, original).storageHash)
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
            storage.restoreClosedDatabase(file.readBytes()) // Only the owned testbed DB; production migrates 1→19.
            assertEquals(20, db.openHelper.writableDatabase.version)
            val point = hash(); val original = access()
            unchanged { assertEquals(point, verify(point, original).storageHash) }
        } finally { assertTrue(app.deleteDatabase(name)) }
    }

    @Test fun unchangedRoomHashCannotHideLostDeferredForeignKeyOrNullableConstraint() = runBlocking<Unit> {
        register(); val original = access()
        for (fault in RestartConstraintFault.Kind.entries) {
            rewriteRestartConstraint(fault)
            schemaRejectedWarmAndCold(original, nativeFault::restoreClosed)
        }
    }

    @Test fun changedIndexCollationDirectionAndSameNamedTriggerCannotPass() = runBlocking<Unit> {
        register(); val original = access()
        val warm = hash()
        assertEquals(warm, verify(warm, original).storageHash)
        fun definition(type: String, name: String) = db.openHelper.writableDatabase.query(
            "SELECT sql FROM main.sqlite_master WHERE type=? AND name=?", arrayOf(type, name)).use {
            assertTrue(it.moveToFirst()); it.getString(0).also { _ -> assertFalse(it.moveToNext()) }
        }
        val originalIndex = definition("index", "index_metrics_uuid")
        db.openHelper.writableDatabase.execSQL("DROP INDEX index_metrics_uuid")
        db.openHelper.writableDatabase.execSQL("CREATE INDEX index_metrics_uuid ON metrics(uuid COLLATE NOCASE DESC)")
        assertTrue(definition("index", "index_metrics_uuid").contains("NOCASE DESC"))
        schemaRejectedWarmAndCold(original) { raw ->
            raw.execSQL("DROP INDEX index_metrics_uuid"); raw.execSQL(originalIndex)
        }
        val originalTrigger = definition("trigger", "sync_metrics_update")
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER sync_metrics_update")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER sync_metrics_update AFTER UPDATE ON metrics BEGIN SELECT 1; END")
        assertTrue(definition("trigger", "sync_metrics_update").contains("BEGIN SELECT 1; END"))
        schemaRejectedWarmAndCold(original) { raw ->
            raw.execSQL("DROP TRIGGER sync_metrics_update"); raw.execSQL(originalTrigger)
        }
    }

    @Test fun blobInIntegerBusinessFieldAndOrphanForeignKeyAreRejectedBeforeArchive() = runBlocking<Unit> {
        register(); val original = access()
        val warm = hash()
        assertEquals(warm, verify(warm, original).storageHash)
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
        db.openHelper.writableDatabase.query("SELECT sql FROM main.sqlite_master WHERE type='view' AND name='unreviewed_archive_data'").use {
            assertTrue(it.moveToFirst()); assertTrue(it.getString(0).contains("SELECT * FROM metrics")); assertFalse(it.moveToNext())
        }
        schemaRejectedWarmAndCold(original) { it.execSQL("DROP VIEW unreviewed_archive_data") }
        val corruptIdentity = "INSERT INTO room_master_table(id,identity_hash) VALUES(43,'unreviewed')"
        requireControlledRejection(corruptIdentity, "COUNT_TRUST_SYSTEM_WRITE_DENIED")
        corruptClosedTestbed { it.execSQL(corruptIdentity) }
        db.openHelper.writableDatabase.query("SELECT identity_hash FROM room_master_table WHERE id=43").use {
            assertTrue("The original extra-identity fault must reach the real validator", it.moveToFirst())
            assertEquals("unreviewed", it.getString(0)); assertFalse(it.moveToNext())
        }
        val identity = hash()
        unchanged { assertEquals("SYNC_RECOVERY_SCHEMA_INVALID", rejected { verify(identity, original) }.message) }
    }

    @Test fun changedContentsOrAccountAuthorityCannotReuseOldProof() = runBlocking<Unit> {
        register(); val original = access(); val point = hash()
        assertEquals(point, verify(point, original).storageHash)
        unchanged {
            // A warm expected schema must never cache source acceptance or final authority.
            // Fail only the second fresh access-token proof, after actual source verification.
            decryptCalls.set(0); failDecryptAt.set(2)
            try {
                assertEquals("SYNC_ACCOUNT_CHANGED", rejected { verify(point, original) }.message)
                assertEquals("Both entry and final authority must use current credentials", 2, decryptCalls.get())
            } finally { failDecryptAt.set(Int.MAX_VALUE) }
        }
        assertEquals(point, verify(point, original).storageHash)
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
