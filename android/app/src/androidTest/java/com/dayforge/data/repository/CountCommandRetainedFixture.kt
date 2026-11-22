package com.dayforge.data.repository

import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import com.dayforge.data.local.CountCommandMigrationFixture
import com.dayforge.data.local.CountCommandStorageInitialization
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.domain.service.AccountIconController
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*

/** Uses the unchanged historical KSP19 file and real production20 builder. */
abstract class CountCommandRetainedFixture : CountCommandMigrationFixture() {
    @get:org.junit.Rule(order = -2) val widgetRefresh = com.dayforge.widget.IsolatedWidgetRefreshRule()
    internal fun seed(utf16: Boolean = false, extra: (androidx.sqlite.db.SupportSQLiteDatabase) -> Unit = {}) {
        source(19, utf16) { old ->
            old.execSQL("DELETE FROM next_transmissions"); old.execSQL("DELETE FROM next_rejections")
            old.execSQL("INSERT INTO count_command_results VALUES('legacy-native-id','old-input','old-fact',NULL,'retained old result','committed')")
            extra(old)
        }
    }
    internal fun faultRoom(fault: CountCommandRetainedNativeFault): HabitDatabase {
        room?.close(); HabitDatabaseProvider.clearInstanceForTesting()
        return fault.build(context).also { room = it; check(it.openHelper.writableDatabase.isOpen) }
    }
    internal fun store(database: HabitDatabase) = CountCommandStore(database, tokens, sessions)
    internal fun repository(database: HabitDatabase) = MetricRepository(database, database.metricDao(), database.metricLogDao(), database.habitDao(),
        database.habitMetricLinkDao(), nextObjectEditor = NextObjectEditor(database, tokens, sessions, mockk<AccountIconController>()))
    internal suspend fun metric(database: HabitDatabase): MetricEntity {
        val value = MetricEntity(name = "Retained value", unit = "kg", iconResId = 0, colorHex = "#123456", uuid = "legacy-metric")
        return value.copy(id = database.metricDao().insert(value))
    }
    internal suspend fun image(database: HabitDatabase): JsonObject = database.withTransaction { snapshot(database.openHelper.writableDatabase).contents }
    internal fun root(database: HabitDatabase) = CountCommandNativeRows(database.openHelper.writableDatabase) {}.root()
    internal fun count(database: HabitDatabase, table: String): Long {
        require(table in setOf("metric_logs", "sync_outbox", "count_command_intents", "count_command_status", "count_command_results", "count_command_nodes"))
        return database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM `$table`").use {
            check(it.moveToFirst()); it.getLong(0).also { _ -> check(!it.moveToNext()) }
        }
    }
    internal fun assertLegacy(database: HabitDatabase) {
        CountCommandStorageInitialization.verifyMandatory(database.openHelper.writableDatabase)
        database.openHelper.writableDatabase.query("SELECT inputHash,factUuid,requestId,resultJson,state FROM count_command_results WHERE commandId='legacy-native-id'").use {
            assertTrue(it.moveToFirst()); assertEquals("old-input", it.getString(0)); assertEquals("old-fact", it.getString(1)); assertTrue(it.isNull(2))
            assertEquals("retained old result", it.getString(3)); assertEquals("committed", it.getString(4)); assertFalse(it.moveToNext())
        }
    }
    internal fun external(statement: String, args: Array<out Any?> = emptyArray()) {
        room?.close(); HabitDatabaseProvider.clearInstanceForTesting()
        SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL(statement, args) }
    }
}
