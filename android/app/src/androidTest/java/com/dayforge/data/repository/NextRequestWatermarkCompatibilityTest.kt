package com.dayforge.data.repository

import android.content.Context
import android.database.Cursor
import android.util.Log
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountCommandV20Schema
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.model.PlanStructureMetadata
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real production Room/schema/clear path; never writes sqlite_sequence. Reference SQLite
 * observations explain a platform difference but cannot satisfy the production assertions.
 * The same positive watermarks and allocator contract must pass on both physical devices. */
@RunWith(AndroidJUnit4::class)
class NextRequestWatermarkCompatibilityTest {
    @get:Rule val storage = PhysicalDatabaseRule()
    private val db get() = storage.database
    private val tables = listOf("sync_outbox", "timer_command_outbox")

    private fun owned(block: suspend () -> Unit) = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        block()
    }

    @Test(timeout = 150_000) fun freshProductionEmptyClearKeepsOriginalWatermarksAndColdReadValid() = owned {
        observeEmptyDeleteReference()
        requireNotNull(CountTrustRuntime.forDatabase(db))
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            assertEquals(CountCommandV20Schema.VERSION, sql.version)
            observe(sql, "production_fresh")
            assertEmptyWatermarks(sql)
        }
        Log.i(TAG, "stage=production_empty_clear_begin")
        db.clearAllData() // Original production cleanup, including original trust reset.
        Log.i(TAG, "stage=production_empty_clear_returned")
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            observe(sql, "production_empty_clear")
            assertEmptyWatermarks(sql)
            assertTrue(db.habitDao().getAllHabitsOnce().isEmpty())
        }
        storage.reopen()
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            observe(sql, "production_empty_cold")
            assertEmptyWatermarks(sql)
        }
    }

    @Test(timeout = 150_000) fun realAllocatedOutboxIdSurvivesClearAndNextAllocationAdvances() = owned {
        requireNotNull(CountTrustRuntime.forDatabase(db))
        val before = db.withTransaction {
            val sql = db.openHelper.writableDatabase
            observe(sql, "production_before_allocation")
            assertEmptyWatermarks(sql)
            val habit = habit("Before clear")
            db.habitDao().insert(habit) // Real business row and its real sync trigger.
            val row = db.syncOutboxDao().getAll().single()
            assertEquals(habit.uuid, row.entityUuid); assertEquals("habit", row.recordType)
            assertTrue(row.id > 0)
            observe(sql, "production_allocated")
            val mark = NextRequestSql.watermark(sql, "sync_outbox")
            assertEquals(row.id, mark)
            assertEquals(setOf(row.id), NextRequestSql.sources(sql, "sync_outbox").keys)
            mark
        }
        Log.i(TAG, "stage=production_allocated_clear_begin")
        db.clearAllData()
        Log.i(TAG, "stage=production_allocated_clear_returned")
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            observe(sql, "production_allocated_clear")
            assertTrue(db.habitDao().getAllHabitsOnce().isEmpty())
            assertTrue(NextRequestSql.sources(sql, "sync_outbox").isEmpty())
            assertEquals(before, NextRequestSql.watermark(sql, "sync_outbox"))
        }
        storage.reopen()
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            observe(sql, "production_allocated_cold")
            assertEquals(before, NextRequestSql.watermark(sql, "sync_outbox"))
            val habit = habit("After clear")
            db.habitDao().insert(habit)
            val row = db.syncOutboxDao().getAll().single()
            assertEquals(habit.uuid, row.entityUuid)
            assertTrue("Cleared historical allocation must not be reused", row.id > before)
            observe(sql, "production_next_allocation")
            assertEquals(row.id, NextRequestSql.watermark(sql, "sync_outbox"))
            assertEquals(setOf(row.id), NextRequestSql.sources(sql, "sync_outbox").keys)
        }
    }

    @Test(timeout = 150_000) fun firstSuppressedProductionStructureMergeKeepsOriginalWatermarksValid() = owned {
        val at = "2026-10-06T00:00:00Z"
        val millis = Instant.parse(at).toEpochMilli()
        val plan = habit("First remote habit").copy(createdAt = millis, updatedAt = millis,
            appearance = ObjectAppearance(IconReference.Role("habit.custom"), "#123456", "object"),
            planMetadata = PlanStructureMetadata(at, 0, null, null, "Etc/UTC", null, null, null))
        val metric = MetricEntity(name = "First remote metric", unit = "kg", decimalPlaces = 3,
            iconResId = 0, colorHex = "#123456", createdAt = millis, updatedAt = millis,
            appearance = ObjectAppearance(IconReference.Role("metric.custom"), "#123456", "object"))
        fun change(kind: String, uuid: String, payload: JsonObject) = SyncV2Change(0, kind, uuid, "upsert", 1,
            JsonObject(payload + mapOf("public_id" to JsonPrimitive(uuid), "revision" to JsonPrimitive(1),
                "created_at" to JsonPrimitive(at), "updated_at" to JsonPrimitive(at), "deleted_at" to JsonNull)), at)
        val changes = listOf(change("plan_node", plan.uuid, NextStructureMapper.writePlan(plan)),
            change("metric", metric.uuid, NextStructureMapper.writeMetric(metric)))
        requireNotNull(CountTrustRuntime.forDatabase(db))
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            observe(sql, "production_first_merge_before")
            assertTrue(db.habitDao().getAllHabitsOnce().isEmpty())
            assertTrue(db.metricDao().getAllMetricsOnce().isEmpty())
            // Fresh physical file and empty real queues with the original strict zero
            // watermark contract; either absence or INTEGER 0 is a valid representation.
            assertEmptyWatermarks(sql)
            // Real structural-merge participant used by bootstrap. Its original method
            // validates all wire fields, toggles suppression, uses actual DAO upserts and
            // writes shadows. Like the original bootstrap caller, use the real Room
            // transaction without adding a native maintenance lease around suspend DAOs.
            // This component test grants no account authority or full bootstrap admission.
            requireNotNull(CountTrustRuntime.forDatabase(db)).prepareOutboxAllocatorsForWrite(db)
            NextStructureStore(db).restoreInTransaction(changes)
            observe(sql, "production_first_merge_written")
        }
        Log.i(TAG, "stage=production_first_merge_committed")
        suspend fun assertMerged(stage: String) = db.withTransaction {
            val sql = db.openHelper.writableDatabase
            observe(sql, stage)
            val storedPlan = db.habitDao().getAllHabitsOnce().single()
            val storedMetric = db.metricDao().getAllMetricsOnce().single()
            assertEquals(plan.uuid, storedPlan.uuid); assertEquals(plan.name, storedPlan.name)
            assertEquals(metric.uuid, storedMetric.uuid); assertEquals(metric.name, storedMetric.name)
            assertEquals(metric.unit, storedMetric.unit); assertEquals(metric.decimalPlaces, storedMetric.decimalPlaces)
            for (change in changes) {
                val shadow = requireNotNull(db.syncOutboxDao().getState(change.entityType, change.entityUuid))
                assertEquals(change.revision, shadow.revision)
                assertEquals(change.payload.toString(), shadow.payloadJson)
            }
            sql.query("SELECT suppressOutbox FROM sync_control WHERE id=1").use {
                assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0)); assertFalse(it.moveToNext())
            }
            // This component transaction really committed. Full bootstrap additionally
            // checks pendingSources inside its outer transaction and can roll it back;
            // this test does not claim that bootstrap would commit an invalid watermark.
            assertEmptyWatermarks(sql)
        }
        assertMerged("production_first_merge_after_commit")
        storage.reopen()
        assertMerged("production_first_merge_cold")
    }

    private suspend fun assertEmptyWatermarks(sql: SupportSQLiteDatabase) {
        for (table in tables) {
            assertEquals(0L, NextRequestSql.watermark(sql, table))
            assertTrue(NextRequestSql.sources(sql, table).isEmpty())
        }
    }

    private fun habit(name: String) = HabitEntity(name = name, habitType = HabitType.COUNTING,
        iconResId = 0, colorHex = "#123456", schedule = HabitSchedule.Daily, targetValue = 1,
        completionPolicy = "recurring", createdAt = 1_791_244_800_000L, updatedAt = 1_791_244_800_000L)

    /** Only fixed allocator metadata, never payloads or account/credential values. No casts,
     * defaults or Cursor replacement: original validation executes on the original SQL next. */
    private fun observe(sql: SupportSQLiteDatabase, stage: String) {
        sql.query("SELECT sqlite_version(),sqlite_source_id()").use { c ->
            check(c.moveToFirst())
            Log.i(TAG, "stage=$stage sqlite_version=${c.getString(0)} sqlite_source_id=${c.getString(1)}")
        }
        for (table in tables) {
            var count = 0
            sql.query("SELECT rowid,seq,typeof(seq),length(seq) FROM main.sqlite_sequence WHERE name=? ORDER BY rowid LIMIT 3",
                arrayOf(table)).use { c ->
                while (c.moveToNext()) {
                    count++
                    val type = c.getType(1)
                    val scalar = if (type == Cursor.FIELD_TYPE_INTEGER) c.getLong(1).toString() else "unread"
                    val length = if (c.isNull(3)) "null" else c.getLong(3).toString()
                    Log.i(TAG, "stage=$stage table=$table rowid=${c.getLong(0)} cursor_type=$type sqlite_type=${c.getString(2)} integer=$scalar length=$length")
                }
            }
            Log.i(TAG, "stage=$stage table=$table observed_rows_up_to_3=$count")
        }
    }

    /** Independent framework control only: zero-row DELETE and an actual DELETE whose
     * trigger WHEN is false. Both have zero real outbox inserts on every platform. */
    private fun observeEmptyDeleteReference() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.dayforge.testbed")
        val name = "watermark-reference-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE sync_outbox(id INTEGER PRIMARY KEY AUTOINCREMENT)")
                    db.execSQL("CREATE TABLE source(id INTEGER PRIMARY KEY)")
                    db.execSQL("CREATE TRIGGER suppressed_outbox AFTER DELETE ON source WHEN 0 BEGIN INSERT INTO sync_outbox(id) VALUES(NULL); END")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("REFERENCE_UPGRADE_NOT_EXPECTED")
            }).build())
        var opened: SupportSQLiteDatabase? = null
        var failure: Throwable? = null
        try {
            val sql = helper.writableDatabase.also { opened = it }
            observe(sql, "reference_fresh")
            sql.execSQL("DELETE FROM source")
            observe(sql, "reference_zero_row_delete")
            sql.execSQL("INSERT INTO source(id) VALUES(1)")
            sql.execSQL("DELETE FROM source")
            observe(sql, "reference_false_when_delete")
            sql.query("SELECT count(*) FROM sync_outbox").use { c -> check(c.moveToFirst()); assertEquals(0L, c.getLong(0)) }
            sql.query("SELECT count(*) FROM source").use { c -> check(c.moveToFirst()); assertEquals(0L, c.getLong(0)) }
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            try {
                check(opened != null) { "REFERENCE_OPEN_OUTCOME_UNKNOWN" }
                helper.close(); check(opened?.isOpen == false)
                check(context.deleteDatabase(name) || !context.getDatabasePath(name).exists())
            } catch (error: Throwable) {
                PhysicalDatabaseRule.preserveProcessCleanupFailure(error)
                if (failure == null) throw error
                if (failure !== error) failure.addSuppressed(error)
            }
        }
    }

    private companion object { const val TAG = "WatermarkCompatibility" }
}
