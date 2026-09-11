package com.dayforge.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HabitDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        HabitDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migration19To24PreservesValidDataAndRemovesRetiredSyncColumns() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(TEST_DATABASE)
        helper.createDatabase(TEST_DATABASE, 19).apply {
            execSQL(
                """
                INSERT INTO habits(
                    id, name, description, habitType, iconResId, colorHex, schedule,
                    targetValue, isCountdown, isActive, freezeCount, serverId, uuid,
                    parentHabitId, targetCycles, failMode, goalSuccess, activityRate,
                    activityRateUpdatedAt, bestTime, createdAt, updatedAt
                ) VALUES(
                    1, 'Water', '', 'CHECK_IN', 1, '#2196F3', 'daily',
                    1, 0, 1, 0, NULL, 'habit-uuid-1', NULL, NULL, 'STRICT',
                    NULL, 100, 0, NULL, 1000, 1000
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO habits(
                    id, name, description, habitType, iconResId, colorHex, schedule,
                    targetValue, isCountdown, isActive, freezeCount, serverId, uuid,
                    parentHabitId, targetCycles, failMode, goalSuccess, activityRate,
                    activityRateUpdatedAt, bestTime, createdAt, updatedAt
                ) VALUES(
                    2, 'Focus', '', 'TIMER', 1, '#2196F3', 'daily',
                    1, 0, 1, 0, 99, 'timer-habit-uuid', NULL, NULL, 'STRICT',
                    NULL, 100, 0, NULL, 1000, 1000
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO completions(
                    id, habitId, date, value, actualCompletedAt, serverId,
                    uuid, habitUuid, createdAt
                ) VALUES(1, 1, 1000, 1, 1000, NULL, 'completion-uuid-1', NULL, 1000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO timelogs(
                    id, habitId, startTime, endTime, durationSeconds, isPaused, pausedAt,
                    date, serverId, uuid, createdAt, updatedAt
                ) VALUES(1, 2, 1000, 61000, 60, 0, NULL, 0, 55, 'valid-timer', 1000, 61000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO timelogs(
                    id, habitId, startTime, endTime, durationSeconds, isPaused, pausedAt,
                    date, serverId, uuid, createdAt, updatedAt
                ) VALUES(2, 2, 70000, 70000, 60, 0, NULL, 0, NULL, 'manual-timer', 70000, 70000)
                """.trimIndent()
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            24,
            true,
            HabitDatabase.MIGRATION_19_20,
            HabitDatabase.MIGRATION_20_21,
            HabitDatabase.MIGRATION_21_22,
            HabitDatabase.MIGRATION_22_23,
            HabitDatabase.MIGRATION_23_24
        )

        migrated.query("SELECT habitUuid FROM completions WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            assertEquals("habit-uuid-1", cursor.getString(0))
        }
        migrated.query("SELECT COUNT(*) FROM sync_outbox WHERE deadLetteredAt IS NULL").use { cursor ->
            cursor.moveToFirst()
            assertEquals(3, cursor.getInt(0))
        }

        migrated.execSQL("UPDATE habits SET isActive = 0 WHERE id = 1")
        migrated.query("SELECT COUNT(*) FROM sync_outbox WHERE recordType = 'habit'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(3, cursor.getInt(0))
        }

        migrated.execSQL(
            "UPDATE sync_outbox SET errorCode = 'INVALID_PAYLOAD', deadLetteredAt = 2000 WHERE id = (SELECT MIN(id) FROM sync_outbox)"
        )
        migrated.query("SELECT COUNT(*) FROM sync_outbox WHERE deadLetteredAt IS NOT NULL").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        migrated.query("PRAGMA table_info(sync_outbox)").use { cursor ->
            val columns = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            assertEquals(true, "basePayloadJson" in columns)
        }
        migrated.query("PRAGMA table_info(sync_entity_state)").use { cursor ->
            val columns = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            assertEquals(true, "payloadJson" in columns)
            assertEquals(true, "payloadHash" in columns)
        }
        migrated.query("SELECT COUNT(*) FROM sync_conflicts").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM timer_command_outbox").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        migrated.query("SELECT uuid FROM timelogs ORDER BY id").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("valid-timer", cursor.getString(0))
        }
        migrated.query("PRAGMA table_info(habits)").use { cursor ->
            val columns = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            assertFalse("serverId" in columns)
            assertFalse("freezeCount" in columns)
        }
        migrated.query("PRAGMA table_info(timelogs)").use { cursor ->
            val columns = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            assertFalse("serverId" in columns)
            assertFalse("timerSyncEnabled" in columns)
        }
        migrated.close()
    }

    @Test
    fun migration22To24PreservesActiveOfflineTimerAndPendingStartCommand() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(ACTIVE_TIMER_DATABASE)
        helper.createDatabase(ACTIVE_TIMER_DATABASE, 22).apply {
            execSQL(
                """
                INSERT INTO habits(
                    id, name, description, habitType, iconResId, colorHex, schedule,
                    targetValue, isCountdown, isActive, freezeCount, serverId, uuid,
                    parentHabitId, targetCycles, failMode, goalSuccess, activityRate,
                    activityRateUpdatedAt, bestTime, createdAt, updatedAt
                ) VALUES(
                    1, 'Offline focus', '', 'TIMER', 1, '#2196F3', 'daily',
                    1, 0, 1, 0, NULL, 'offline-habit', NULL, NULL, 'STRICT',
                    NULL, 100, 0, NULL, 1000, 1000
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO timelogs(
                    id, habitId, startTime, endTime, durationSeconds, isPaused, pausedAt,
                    accumulatedPauseMillis, timerSyncEnabled, timerNextCommandSequence,
                    timerControlGeneration, timerLastCommandAt, timerTimezone,
                    timerActiveElapsedMillis, timerElapsedRealtimeAnchor, timerBootCount,
                    date, serverId, uuid, createdAt, updatedAt
                ) VALUES(
                    1, 1, 1000, NULL, 0, 0, NULL,
                    0, 1, 2, 1, 1000, 'Asia/Shanghai',
                    0, 500, 3, 0, NULL, 'offline-session', 1000, 1000
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO timer_command_outbox(
                    id, commandId, sessionUuid, sequence, commandType, occurredAt,
                    expectedControlGeneration, expectedRevision, activityUuid, timezone,
                    activeElapsedMillis, attemptCount, lastError, errorCode,
                    deadLetteredAt, createdAt
                ) VALUES(
                    1, 'start-command', 'offline-session', 1, 'start', 1000,
                    0, NULL, 'offline-habit', 'Asia/Shanghai',
                    NULL, 0, NULL, NULL, NULL, 1000
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO timer_segments(id, sessionUuid, sequence, startedAt, endedAt)
                VALUES(1, 'offline-session', 1, 1000, NULL)
                """.trimIndent()
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            ACTIVE_TIMER_DATABASE,
            24,
            true,
            HabitDatabase.MIGRATION_22_23,
            HabitDatabase.MIGRATION_23_24
        )

        migrated.query(
            "SELECT uuid, endTime, timerNextCommandSequence, timerControlGeneration " +
                "FROM timelogs WHERE id = 1"
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("offline-session", cursor.getString(0))
            assertEquals(true, cursor.isNull(1))
            assertEquals(2, cursor.getInt(2))
            assertEquals(1, cursor.getInt(3))
        }
        migrated.query(
            "SELECT commandId, commandType, sequence FROM timer_command_outbox WHERE id = 1"
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("start-command", cursor.getString(0))
            assertEquals("start", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
        }
        migrated.query(
            "SELECT sessionUuid, sequence, endedAt FROM timer_segments WHERE id = 1"
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("offline-session", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals(true, cursor.isNull(2))
        }
        migrated.close()
    }

    private companion object {
        const val TEST_DATABASE = "habit-migration-test"
        const val ACTIVE_TIMER_DATABASE = "habit-active-timer-migration-test"
    }
}
