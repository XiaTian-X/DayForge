package com.dayforge.data.local

import android.database.Cursor
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.repository.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Original frozen KSP files and actual Keystore-backed proof issuance; no MockK or
 * replacement cipher beyond the existing migration fixture's authentication storage. */
@RunWith(AndroidJUnit4::class)
class CountTrustAllocatorMigrationTest : CountCommandMigrationFixture() {
    @Test(timeout = 150_000) fun empty18And19MigrationPreserveAbsenceUntilFirstAuthorizedWriteAndColdReopen() = runBlocking<Unit> {
        for (version in listOf(18, 19)) {
            room?.close(); room = null; HabitDatabaseProvider.clearInstanceForTesting()
            assertTrue(context.deleteDatabase("habit_database") || !context.getDatabasePath("habit_database").exists())
            val source = emptySource(version)
            val current = openCurrent()
            val migrated = current.withTransaction { NextReplicaStorageProof(current).readInTransaction() }
            val old = NextReplicaCompatibilityViews.projected(migrated).single { it.contents.getValue("user_version") == JsonPrimitive(version) }
            assertEquals(source.contents, old.contents); assertEquals(source.hash, old.hash)
            val cold = openCurrent()
            assertEquals(migrated.contents, cold.withTransaction { NextReplicaStorageProof(cold).readInTransaction().contents })
            cold.withTransaction {
                val sql = cold.openHelper.writableDatabase
                assertTrue(sequenceEmpty(sql))
                val before = NextReplicaStorageProof(cold).readInTransaction().contents
                requireNotNull(CountTrustRuntime.forDatabase(cold)).prepareOutboxAllocatorsForWrite(cold)
                assertZeroQueues(sql)
                val after = NextReplicaStorageProof(cold).readInTransaction().contents
                assertEquals(before - "tables", after - "tables")
                assertEquals(before.getValue("tables").jsonObject - "sqlite_sequence", after.getValue("tables").jsonObject - "sqlite_sequence")
            }
            // The original whole-storage write still executes all its DELETE/reset/END work.
            cold.clearAllData()
            val reopened = openCurrent()
            reopened.withTransaction { assertZeroQueues(reopened.openHelper.writableDatabase) }
        }
    }

    @Test(timeout = 150_000) fun realEmptyMetricSealAndTempStateSurvivePreparationButActiveDisplayRejectsIt() = runBlocking<Unit> {
        val current = openCurrent()
        val runtime = requireNotNull(CountTrustRuntime.forDatabase(current))
        val header = sessions.exclusive { current.withTransaction {
            val kernel = CountTrustKernel(current, tokens, sessions)
            val access = access()
            kernel.withPreparedDisplayRead(access, prepare = true, prepareMetrics = true) { display ->
                assertNotNull("The original empty metric qualification must really issue a proof", display)
                val error = runCatching { runtime.prepareOutboxAllocatorsForWrite(current) }.exceptionOrNull()
                assertEquals("EVIDENCE_READ_WRITE_FORBIDDEN", error?.message)
            }
            val sql = current.openHelper.writableDatabase
            assertTrue(sequenceEmpty(sql))
            val original = requireNotNull(CountTrustSeal.read(sql))
            kernel.withOpen(access) { oldContext ->
                val retained = requireNotNull(oldContext)
                assertTrue(retained.metricOnly); assertTrue(retained.metricRanges); assertFalse(retained.once)
                val before = NextReplicaStorageProof(current).readInTransaction().contents
                val temp = tempState(sql)
                runtime.prepareOutboxAllocatorsForWrite(current)
                assertEquals(temp, tempState(sql))
                assertEquals(original, CountTrustSeal.read(sql))
                assertTrue(retained.metricRanges) // The same original business proof is still valid.
                val after = NextReplicaStorageProof(current).readInTransaction().contents
                assertEquals(before - "tables", after - "tables")
                assertEquals(before.getValue("tables").jsonObject - "sqlite_sequence", after.getValue("tables").jsonObject - "sqlite_sequence")
                assertZeroQueues(sql)
            }
            kernel.withPreparedDisplayRead(access, prepare = false, openExistingProof = true) { assertNotNull(it) }
            original
        } }
        val reopened = openCurrent()
        sessions.exclusive { reopened.withTransaction {
            assertEquals(header, CountTrustSeal.read(reopened.openHelper.writableDatabase))
            CountTrustKernel(reopened, tokens, sessions).withOpen(access()) { assertTrue(requireNotNull(it).metricRanges) }
            assertZeroQueues(reopened.openHelper.writableDatabase)
        } }
    }

    private fun emptySource(version: Int): ReplicaStorageProof {
        val schema = asset(version)
        return FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name("habit_database")
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    schema.getValue("entities").jsonArray.map { it.jsonObject }.forEach { entity ->
                        val table = entity.getValue("tableName").jsonPrimitive.content
                        fun ddl(value: JsonElement) = value.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                        db.execSQL(ddl(entity.getValue("createSql")))
                        entity["indices"]?.jsonArray.orEmpty().forEach { db.execSQL(ddl(it.jsonObject.getValue("createSql"))) }
                    }
                    schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                    if (version == 19) CountTrustSchema.install(db)
                    SyncSchemaCallback.onOpen(db)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected source upgrade")
            }).build()).use { helper ->
                val db = helper.writableDatabase
                assertEquals(version, db.version); assertTrue(sequenceEmpty(db))
                snapshot(db)
            }
    }

    private fun sequenceEmpty(sql: SupportSQLiteDatabase) = sql.query("SELECT 1 FROM main.sqlite_sequence LIMIT 1").use { !it.moveToFirst() }
    private suspend fun assertZeroQueues(sql: SupportSQLiteDatabase) {
        for (table in listOf("sync_outbox", "timer_command_outbox")) {
            assertEquals(0L, NextRequestSql.watermark(sql, table)); assertTrue(NextRequestSql.sources(sql, table).isEmpty())
        }
    }
    private fun tempState(sql: SupportSQLiteDatabase): Map<String, List<List<String>>> {
        fun rows(query: String) = sql.query(query).use { c -> buildList { while (c.moveToNext()) add((0 until c.columnCount).map { i ->
            val value = when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> ""
                Cursor.FIELD_TYPE_BLOB -> replicaStorageBlobHex(c.getBlob(i))
                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i).toRawBits().toString(16)
                else -> c.getString(i)
            }
            "${c.getType(i)}:$value"
        }) } }
        val tables = sql.query("SELECT name FROM temp.sqlite_master WHERE type='table' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0).also { check(it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*"))) }) }
        }
        return buildMap {
            put("schema", rows("SELECT type,name,tbl_name,rootpage,sql FROM temp.sqlite_master ORDER BY type,name"))
            tables.forEach { put(it, rows("SELECT rowid,* FROM temp.`$it` ORDER BY rowid")) }
        }
    }
}
