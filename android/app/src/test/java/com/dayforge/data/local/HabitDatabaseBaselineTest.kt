package com.dayforge.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class HabitDatabaseBaselineTest {

    private lateinit var context: Context
    private lateinit var database: HabitDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TEST_DATABASE)
        HabitDatabase.clearInstanceForTesting()
        database = HabitDatabase.getInstance(context)
    }

    @After
    fun tearDown() {
        database.close()
        HabitDatabase.clearInstanceForTesting()
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun freshDatabaseMatchesVersionOneBaseline() {
        val sqlite = database.openHelper.writableDatabase
        val tables = sqlite.query(
            "SELECT name FROM sqlite_master WHERE type = 'table'"
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        val expectedTables = setOf(
            "habits",
            "completions",
            "timelogs",
            "metrics",
            "metric_logs",
            "habit_metric_links",
            "sync_outbox",
            "sync_entity_state",
            "sync_conflicts",
            "sync_control",
            "timer_command_outbox",
            "timer_segments",
            "timelog_day_allocations"
        )

        assertEquals(1, sqlite.version)
        assertTrue(tables.containsAll(expectedTables))
        sqlite.query("SELECT suppressOutbox FROM sync_control WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun freshDatabaseInstallsSyncTriggers() = runBlocking {
        val habit = HabitEntity(
            name = "Baseline habit",
            description = "",
            habitType = HabitType.CHECK_IN,
            iconResId = 0,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        )
        val id = database.habitDao().insert(habit)
        val stored = habit.copy(id = id)
        database.habitDao().update(stored.copy(name = "Updated baseline habit"))
        database.habitDao().delete(stored.copy(name = "Updated baseline habit"))

        val sqlite = database.openHelper.writableDatabase
        val triggers = sqlite.query(
            "SELECT name FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'sync_%'"
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        val expectedTriggers = listOf(
            "habits",
            "completions",
            "metrics",
            "metric_logs",
            "habit_metric_links"
        ).flatMap { table ->
            listOf("insert", "update", "delete").map { action -> "sync_${table}_$action" }
        }.toSet()
        assertEquals(expectedTriggers, triggers)

        sqlite.query(
            "SELECT action FROM sync_outbox WHERE recordType = 'habit' ORDER BY id"
        ).use { cursor ->
            val actions = buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
            assertEquals(listOf("upsert", "upsert", "delete"), actions)
        }
    }

    private companion object {
        const val TEST_DATABASE = "habit_database"
    }
}
