package com.dayforge.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.SyncConflictDao
import com.dayforge.data.local.dao.SyncOutboxDao
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.HabitTypeConverter
import com.dayforge.data.local.entity.SyncControlEntity
import com.dayforge.data.local.entity.SyncConflictEntity
import com.dayforge.data.local.entity.SyncEntityStateEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.local.entity.TimerSegmentEntity
import com.dayforge.data.local.entity.TimeLogDayAllocationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Database(
    entities = [HabitEntity::class, CompletionEntity::class, TimeLogEntity::class, MetricEntity::class, MetricLogEntity::class, HabitMetricLinkEntity::class, SyncOutboxEntity::class, SyncEntityStateEntity::class, SyncConflictEntity::class, SyncControlEntity::class, TimerCommandEntity::class, TimerSegmentEntity::class, TimeLogDayAllocationEntity::class],
    version = 24,
    exportSchema = true
)
@TypeConverters(HabitTypeConverter::class)
abstract class HabitDatabase : RoomDatabase() {

    abstract fun habitDao(): HabitDao

    abstract fun completionDao(): CompletionDao

    abstract fun timeLogDao(): TimeLogDao

    abstract fun metricDao(): MetricDao

    abstract fun metricLogDao(): MetricLogDao

    abstract fun habitMetricLinkDao(): HabitMetricLinkDao

    abstract fun syncOutboxDao(): SyncOutboxDao

    abstract fun syncConflictDao(): SyncConflictDao

    /**
     * Clears all data from the database.
     * Called when user logs out or a new user logs in.
     * Must be called from a coroutine - runs on IO dispatcher.
     */
    suspend fun clearAllData() {
        withContext(Dispatchers.IO) {
            clearAllTables()
            openHelper.writableDatabase.execSQL(
                "INSERT OR REPLACE INTO sync_control(id, suppressOutbox) VALUES(1, 0)"
            )
        }
    }

    companion object {
        @Volatile private var INSTANCE: HabitDatabase? = null

        private fun uuidSql(): String =
            "lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4' || " +
                "substr(hex(randomblob(2)),2) || '-' || " +
                "substr('89ab',abs(random()) % 4 + 1,1) || substr(hex(randomblob(2)),2) || '-' || " +
                "hex(randomblob(6)))"

        private fun seedV2Outbox(
            database: SupportSQLiteDatabase,
            table: String,
            recordType: String,
            uuidColumn: String,
            referenceExpression: String?,
            condition: String? = null
        ) {
            val reference = referenceExpression ?: "NULL"
            val where = condition?.let { " WHERE $it" } ?: ""
            database.execSQL("""
                INSERT INTO sync_outbox(
                    operationId, recordType, entityUuid, wireEntityUuid, action,
                    referenceUuid, payloadJson, baseRevision, attemptedAt,
                    attemptCount, lastError, createdAt
                )
                SELECT ${uuidSql()}, '$recordType', $uuidColumn, $uuidColumn, 'upsert',
                       $reference, NULL, NULL, NULL, 0, NULL,
                       CAST(strftime('%s','now') AS INTEGER) * 1000
                FROM $table$where
            """.trimIndent())
        }

        private fun createV2Triggers(
            database: SupportSQLiteDatabase,
            table: String,
            recordType: String,
            uuidColumn: String,
            newReferenceExpression: String?,
            updateCondition: String,
            insertCondition: String? = null,
            deleteCondition: String? = null
        ) {
            val newReference = newReferenceExpression ?: "NULL"
            val oldReference = newReferenceExpression?.replace("NEW.", "OLD.") ?: "NULL"
            val insertWhen = buildString {
                append("(SELECT suppressOutbox FROM sync_control WHERE id = 1) = 0")
                insertCondition?.let { append(" AND ($it)") }
            }
            val updateWhen =
                "(SELECT suppressOutbox FROM sync_control WHERE id = 1) = 0 AND ($updateCondition)"
            val deleteWhen = buildString {
                append("(SELECT suppressOutbox FROM sync_control WHERE id = 1) = 0")
                deleteCondition?.let { append(" AND ($it)") }
            }
            val createdAt = "CAST(strftime('%s','now') AS INTEGER) * 1000"

            database.execSQL("""
                CREATE TRIGGER IF NOT EXISTS sync_${table}_insert
                AFTER INSERT ON $table
                WHEN $insertWhen
                BEGIN
                    INSERT INTO sync_outbox(
                        operationId, recordType, entityUuid, wireEntityUuid, action,
                        referenceUuid, payloadJson, baseRevision, attemptedAt,
                        attemptCount, lastError, createdAt
                    ) VALUES(
                        ${uuidSql()}, '$recordType', NEW.$uuidColumn, NEW.$uuidColumn, 'upsert',
                        $newReference, NULL, NULL, NULL, 0, NULL, $createdAt
                    );
                END
            """.trimIndent())
            database.execSQL("""
                CREATE TRIGGER IF NOT EXISTS sync_${table}_update
                AFTER UPDATE ON $table
                WHEN $updateWhen
                BEGIN
                    INSERT INTO sync_outbox(
                        operationId, recordType, entityUuid, wireEntityUuid, action,
                        referenceUuid, payloadJson, baseRevision, attemptedAt,
                        attemptCount, lastError, createdAt
                    ) VALUES(
                        ${uuidSql()}, '$recordType', NEW.$uuidColumn, NEW.$uuidColumn, 'upsert',
                        $newReference, NULL, NULL, NULL, 0, NULL, $createdAt
                    );
                END
            """.trimIndent())
            database.execSQL("""
                CREATE TRIGGER IF NOT EXISTS sync_${table}_delete
                AFTER DELETE ON $table
                WHEN $deleteWhen
                BEGIN
                    INSERT INTO sync_outbox(
                        operationId, recordType, entityUuid, wireEntityUuid, action,
                        referenceUuid, payloadJson, baseRevision, attemptedAt,
                        attemptCount, lastError, createdAt
                    ) VALUES(
                        ${uuidSql()}, '$recordType', OLD.$uuidColumn, OLD.$uuidColumn, 'delete',
                        $oldReference, NULL, NULL, NULL, 0, NULL, $createdAt
                    );
                END
            """.trimIndent())
        }

        private fun installV2Triggers(database: SupportSQLiteDatabase) {
            createV2Triggers(database, "habits", "habit", "uuid", "NEW.habitType",
                "OLD.name IS NOT NEW.name OR OLD.description IS NOT NEW.description OR OLD.habitType IS NOT NEW.habitType OR OLD.iconResId IS NOT NEW.iconResId OR OLD.colorHex IS NOT NEW.colorHex OR OLD.schedule IS NOT NEW.schedule OR OLD.targetValue IS NOT NEW.targetValue OR OLD.isCountdown IS NOT NEW.isCountdown OR OLD.isActive IS NOT NEW.isActive OR OLD.parentHabitId IS NOT NEW.parentHabitId OR OLD.targetCycles IS NOT NEW.targetCycles OR OLD.failMode IS NOT NEW.failMode OR OLD.goalSuccess IS NOT NEW.goalSuccess OR OLD.bestTime IS NOT NEW.bestTime")
            createV2Triggers(database, "completions", "completion", "uuid", "COALESCE(NEW.habitUuid, (SELECT uuid FROM habits WHERE id = NEW.habitId))", "OLD.date IS NOT NEW.date OR OLD.value IS NOT NEW.value OR OLD.actualCompletedAt IS NOT NEW.actualCompletedAt")
            createV2Triggers(database, "metrics", "metric", "uuid", null,
                "OLD.name IS NOT NEW.name OR OLD.description IS NOT NEW.description OR OLD.unit IS NOT NEW.unit OR OLD.decimalPlaces IS NOT NEW.decimalPlaces OR OLD.aggregationType IS NOT NEW.aggregationType OR OLD.targetDirection IS NOT NEW.targetDirection OR OLD.targetValue IS NOT NEW.targetValue OR OLD.targetValueUpper IS NOT NEW.targetValueUpper OR OLD.iconResId IS NOT NEW.iconResId OR OLD.colorHex IS NOT NEW.colorHex OR OLD.isActive IS NOT NEW.isActive")
            createV2Triggers(database, "metric_logs", "metric_log", "uuid", "(SELECT uuid FROM metrics WHERE id = NEW.metricId)", "OLD.date IS NOT NEW.date OR OLD.value IS NOT NEW.value OR OLD.unit IS NOT NEW.unit OR OLD.note IS NOT NEW.note")
            createV2Triggers(database, "habit_metric_links", "link", "uuid", "NEW.habitUuid",
                "OLD.habitUuid IS NOT NEW.habitUuid OR OLD.metricUuid IS NOT NEW.metricUuid OR OLD.coefficient IS NOT NEW.coefficient OR OLD.showInHabitDetail IS NOT NEW.showInHabitDetail OR OLD.promptOnComplete IS NOT NEW.promptOnComplete OR OLD.isActive IS NOT NEW.isActive")
        }

        private val SYNC_CALLBACK = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                db.execSQL("INSERT OR IGNORE INTO sync_control(id, suppressOutbox) VALUES(1, 0)")
                installV2Triggers(db)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                // Also repairs callback/triggers after a destructive test database rebuild.
                db.execSQL("INSERT OR IGNORE INTO sync_control(id, suppressOutbox) VALUES(1, 0)")
                installV2Triggers(db)
            }
        }

        /**
         * Migration from version 3 to 4: Add unique index on name column
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create unique index on name column
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_habits_name ON habits (name)")
            }
        }

        /**
         * Migration from version 4 to 5: Remove isArchived column
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create new table without isArchived column
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS habits_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        habitType TEXT NOT NULL,
                        iconResId INTEGER NOT NULL,
                        colorHex TEXT NOT NULL,
                        schedule TEXT NOT NULL,
                        targetValue INTEGER NOT NULL,
                        freezeCount INTEGER NOT NULL,
                        serverId INTEGER,
                        uuid TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """)

                // Copy data (excluding isArchived)
                database.execSQL("""
                    INSERT INTO habits_new
                    SELECT id, name, description, habitType, iconResId, colorHex,
                           schedule, targetValue, freezeCount, serverId, uuid, createdAt, updatedAt
                    FROM habits
                """)

                // Drop old table
                database.execSQL("DROP TABLE habits")

                // Rename new table
                database.execSQL("ALTER TABLE habits_new RENAME TO habits")

                // Recreate indexes
                database.execSQL("CREATE INDEX IF NOT EXISTS index_habits_serverId ON habits(serverId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_habits_uuid ON habits(uuid)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_habits_name ON habits(name)")
            }
        }

        /**
         * Migration from version 5 to 6: Add isActive column
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE habits ADD COLUMN isActive INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        /**
         * Migration from version 6 to 7: Add TimeLogEntity table
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS timelogs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        habitId INTEGER NOT NULL,
                        startTime INTEGER NOT NULL,
                        endTime INTEGER,
                        durationSeconds INTEGER NOT NULL,
                        isPaused INTEGER NOT NULL DEFAULT 0,
                        pausedAt INTEGER,
                        date INTEGER NOT NULL,
                        serverId INTEGER,
                        uuid TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(habitId) REFERENCES habits(id) ON DELETE CASCADE
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_timelogs_habitId ON timelogs(habitId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_timelogs_date ON timelogs(date)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_timelogs_serverId ON timelogs(serverId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_timelogs_uuid ON timelogs(uuid)")
            }
        }

        /**
         * Migration from version 7 to 8: Add MetricEntity and MetricLogEntity tables
         * Part of v2.1 量化指标 feature.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create metrics table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS metrics (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        unit TEXT NOT NULL,
                        decimalPlaces INTEGER NOT NULL DEFAULT 0,
                        targetDirection TEXT,
                        targetValue REAL,
                        targetValueUpper REAL,
                        iconResId INTEGER NOT NULL,
                        colorHex TEXT NOT NULL,
                        isActive INTEGER NOT NULL DEFAULT 1,
                        serverId INTEGER,
                        uuid TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_metrics_serverId ON metrics(serverId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_metrics_uuid ON metrics(uuid)")

                // Create metric_logs table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS metric_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        metricId INTEGER NOT NULL,
                        date INTEGER NOT NULL,
                        value REAL NOT NULL,
                        unit TEXT NOT NULL,
                        note TEXT NOT NULL DEFAULT '',
                        serverId INTEGER,
                        uuid TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY (metricId) REFERENCES metrics(id) ON DELETE CASCADE
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_metric_logs_metricId ON metric_logs(metricId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_metric_logs_date ON metric_logs(date)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_metric_logs_serverId ON metric_logs(serverId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_metric_logs_uuid ON metric_logs(uuid)")

                // Create habit_metric_links table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS habit_metric_links (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        habitId INTEGER NOT NULL,
                        habitUuid TEXT NOT NULL,
                        metricId INTEGER NOT NULL,
                        metricUuid TEXT NOT NULL,
                        coefficient REAL NOT NULL DEFAULT 1.0,
                        showInHabitDetail INTEGER NOT NULL DEFAULT 1,
                        promptOnComplete INTEGER NOT NULL DEFAULT 0,
                        isActive INTEGER NOT NULL DEFAULT 1,
                        serverId INTEGER,
                        uuid TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY (habitId) REFERENCES habits(id) ON DELETE CASCADE,
                        FOREIGN KEY (metricId) REFERENCES metrics(id) ON DELETE CASCADE
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_habit_metric_links_habitId ON habit_metric_links(habitId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_habit_metric_links_metricId ON habit_metric_links(metricId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_habit_metric_links_serverId ON habit_metric_links(serverId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_habit_metric_links_uuid ON habit_metric_links(uuid)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_habit_metric_links_unique ON habit_metric_links(habitId, metricId)")
            }
        }

        /**
         * Migration from version 8 to 9: Add aggregationType column to metrics
         * Part of v2.1 量化指标 trend visualization feature.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE metrics ADD COLUMN aggregationType TEXT NOT NULL DEFAULT 'average'"
                )
            }
        }

        /**
         * Migration from version 9 to 10: Add isCountdown column to habits
         * Part of v2.2 计时计数模式优化 feature.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE habits ADD COLUMN isCountdown INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * Migration from version 10 to 11: Add actual time columns to timelogs
         * Part of 实际时间记录 feature.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE timelogs ADD COLUMN actualStartTime INTEGER"
                )
                database.execSQL(
                    "ALTER TABLE timelogs ADD COLUMN actualEndTime INTEGER"
                )
            }
        }

        /**
         * Migration from version 11 to 12: Add actualCompletedAt column to completions
         * Part of 实际时间记录 feature.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE completions ADD COLUMN actualCompletedAt INTEGER"
                )
            }
        }

        /**
         * Migration from version 12 to 13: Remove actualStartTime and actualEndTime from timelogs
         * Part of v2.3 数据模型清理 feature - simplifying TimeLog fields.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // SQLite doesn't support DROP COLUMN, so we need to recreate the table
                // Create new table without actualStartTime and actualEndTime
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS timelogs_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        habitId INTEGER NOT NULL,
                        startTime INTEGER NOT NULL,
                        endTime INTEGER,
                        durationSeconds INTEGER NOT NULL,
                        isPaused INTEGER NOT NULL DEFAULT 0,
                        pausedAt INTEGER,
                        date INTEGER NOT NULL,
                        serverId INTEGER,
                        uuid TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(habitId) REFERENCES habits(id) ON DELETE CASCADE
                    )
                """)

                // Copy data (excluding actualStartTime and actualEndTime)
                database.execSQL("""
                    INSERT INTO timelogs_new
                    SELECT id, habitId, startTime, endTime, durationSeconds, isPaused, pausedAt, date, serverId, uuid, createdAt, updatedAt
                    FROM timelogs
                """)

                // Drop old table
                database.execSQL("DROP TABLE timelogs")

                // Rename new table
                database.execSQL("ALTER TABLE timelogs_new RENAME TO timelogs")

                // Recreate indexes
                database.execSQL("CREATE INDEX IF NOT EXISTS index_timelogs_habitId ON timelogs(habitId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_timelogs_date ON timelogs(date)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_timelogs_serverId ON timelogs(serverId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_timelogs_uuid ON timelogs(uuid)")
            }
        }

        /**
         * Migration from version 13 to 14: Add parentHabitId column to habits
         * Part of v2.4 组合习惯（父子结构） feature.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE habits ADD COLUMN parentHabitId TEXT"
                )
            }
        }

        /**
         * Migration from version 14 to 15: Add targetCycles column to habits
         * Part of v2.6 限时习惯 feature.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE habits ADD COLUMN targetCycles INTEGER"
                )
            }
        }

        /**
         * Migration from version 15 to 16: Add failMode column to habits
         * Part of v2.6 失败判定 feature.
         * Default value is 'STRICT' (断签即失败).
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE habits ADD COLUMN failMode TEXT NOT NULL DEFAULT 'STRICT'"
                )
            }
        }

        /**
         * Migration from version 16 to 17: Add goalSuccess column to habits
         * Part of v2.7 目标习惯类型 feature.
         * Column is nullable Boolean for GOAL type completion status.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE habits ADD COLUMN goalSuccess INTEGER"
                )
            }
        }

        /**
         * Migration from version 17 to 18: Add activityRate columns to habits
         * Part of 活跃度计算 feature.
         * activityRate: 活跃度 0-100，默认100（满分）
         * activityRateUpdatedAt: 上次更新活跃度的时间
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE habits ADD COLUMN activityRate INTEGER NOT NULL DEFAULT 100"
                )
                database.execSQL(
                    "ALTER TABLE habits ADD COLUMN activityRateUpdatedAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * Migration from version 18 to 19: Add bestTime column to habits
         * Part of v3.7 聚焦打卡功能 feature.
         * bestTime: nullable Long for best execution time (minutes since midnight)
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE habits ADD COLUMN bestTime INTEGER"
                )
            }
        }

        /**
         * Migration 19 -> 20: durable v2 outbox and revision state.
         * SQLite triggers cover every existing write path, including widgets and services.
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_outbox (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        operationId TEXT NOT NULL,
                        recordType TEXT NOT NULL,
                        entityUuid TEXT NOT NULL,
                        wireEntityUuid TEXT NOT NULL,
                        action TEXT NOT NULL,
                        referenceUuid TEXT,
                        payloadJson TEXT,
                        baseRevision INTEGER,
                        attemptedAt INTEGER,
                        attemptCount INTEGER NOT NULL,
                        lastError TEXT,
                        createdAt INTEGER NOT NULL
                    )
                """)
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sync_outbox_operationId ON sync_outbox(operationId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outbox_recordType_entityUuid ON sync_outbox(recordType, entityUuid)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outbox_attemptedAt ON sync_outbox(attemptedAt)")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_entity_state (
                        entityType TEXT NOT NULL,
                        entityUuid TEXT NOT NULL,
                        revision INTEGER NOT NULL,
                        deleted INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(entityType, entityUuid)
                    )
                """)
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_control (
                        id INTEGER NOT NULL PRIMARY KEY,
                        suppressOutbox INTEGER NOT NULL
                    )
                """)
                database.execSQL("INSERT OR IGNORE INTO sync_control(id, suppressOutbox) VALUES(1, 0)")
                database.execSQL("""
                    UPDATE completions
                    SET habitUuid = (SELECT uuid FROM habits WHERE habits.id = completions.habitId)
                    WHERE habitUuid IS NULL
                """)

                // Existing installations have never been acknowledged by v2. Seed every
                // complete record as a create so an empty/reset v2 backend can adopt it.
                seedV2Outbox(database, "habits", "habit", "uuid", null)
                seedV2Outbox(database, "completions", "completion", "uuid", "(SELECT uuid FROM habits WHERE id = completions.habitId)")
                seedV2Outbox(database, "timelogs", "timelog", "uuid", "(SELECT uuid FROM habits WHERE id = timelogs.habitId)", "endTime IS NOT NULL")
                seedV2Outbox(database, "metrics", "metric", "uuid", null)
                seedV2Outbox(database, "metric_logs", "metric_log", "uuid", "(SELECT uuid FROM metrics WHERE id = metric_logs.metricId)")
                seedV2Outbox(database, "habit_metric_links", "link", "uuid", "habitUuid")

                installV2Triggers(database)
            }
        }

        /** Migration 20 -> 21: isolate permanently rejected operations as dead letters. */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE sync_outbox ADD COLUMN errorCode TEXT")
                database.execSQL("ALTER TABLE sync_outbox ADD COLUMN deadLetteredAt INTEGER")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_sync_outbox_deadLetteredAt ON sync_outbox(deadLetteredAt)"
                )
            }
        }

        /** Migration 21 -> 22: durable active-timer command queue and pause recovery. */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE timelogs ADD COLUMN accumulatedPauseMillis INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE timelogs ADD COLUMN timerSyncEnabled INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE timelogs ADD COLUMN timerNextCommandSequence INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE timelogs ADD COLUMN timerControlGeneration INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE timelogs ADD COLUMN timerLastCommandAt INTEGER")
                database.execSQL("ALTER TABLE timelogs ADD COLUMN timerTimezone TEXT")
                database.execSQL("ALTER TABLE timelogs ADD COLUMN timerActiveElapsedMillis INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE timelogs ADD COLUMN timerElapsedRealtimeAnchor INTEGER")
                database.execSQL("ALTER TABLE timelogs ADD COLUMN timerBootCount INTEGER")
                database.execSQL("DROP INDEX IF EXISTS index_timelogs_uuid")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_timelogs_uuid ON timelogs(uuid)")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS timer_command_outbox (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        commandId TEXT NOT NULL,
                        sessionUuid TEXT NOT NULL,
                        sequence INTEGER NOT NULL,
                        commandType TEXT NOT NULL,
                        occurredAt INTEGER NOT NULL,
                        expectedControlGeneration INTEGER NOT NULL,
                        expectedRevision INTEGER,
                        activityUuid TEXT,
                        timezone TEXT,
                        activeElapsedMillis INTEGER,
                        attemptCount INTEGER NOT NULL,
                        lastError TEXT,
                        errorCode TEXT,
                        deadLetteredAt INTEGER,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_timer_command_outbox_commandId ON timer_command_outbox(commandId)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_timer_command_outbox_sessionUuid_sequence ON timer_command_outbox(sessionUuid, sequence)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_timer_command_outbox_deadLetteredAt ON timer_command_outbox(deadLetteredAt)")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS timer_segments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionUuid TEXT NOT NULL,
                        sequence INTEGER NOT NULL,
                        startedAt INTEGER NOT NULL,
                        endedAt INTEGER,
                        FOREIGN KEY(sessionUuid) REFERENCES timelogs(uuid) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_timer_segments_sessionUuid_sequence ON timer_segments(sessionUuid, sequence)")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS timelog_day_allocations (
                        sessionUuid TEXT NOT NULL,
                        habitId INTEGER NOT NULL,
                        localDate TEXT NOT NULL,
                        localDateEpoch INTEGER NOT NULL,
                        timezone TEXT NOT NULL,
                        durationMillis INTEGER NOT NULL,
                        PRIMARY KEY(sessionUuid, localDate, timezone),
                        FOREIGN KEY(sessionUuid) REFERENCES timelogs(uuid) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_timelog_day_allocations_habitId ON timelog_day_allocations(habitId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_timelog_day_allocations_localDateEpoch ON timelog_day_allocations(localDateEpoch)")
                database.execSQL("DROP TRIGGER IF EXISTS sync_timelogs_insert")
                database.execSQL("DROP TRIGGER IF EXISTS sync_timelogs_update")
                database.execSQL("DROP TRIGGER IF EXISTS sync_timelogs_delete")
                installV2Triggers(database)
            }
        }

        /**
         * Migration 22 -> 23: remove the retired per-row sync flags and manual timer path.
         *
         * Sync V2 revisions live in sync_entity_state. Timer facts are now produced only
         * by the durable timer command protocol. Existing complete, internally consistent
         * timer facts are preserved; malformed zero-interval manual entries are discarded.
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP TRIGGER IF EXISTS sync_habits_insert")
                database.execSQL("DROP TRIGGER IF EXISTS sync_habits_update")
                database.execSQL("DROP TRIGGER IF EXISTS sync_habits_delete")
                database.execSQL("DROP TRIGGER IF EXISTS sync_completions_insert")
                database.execSQL("DROP TRIGGER IF EXISTS sync_completions_update")
                database.execSQL("DROP TRIGGER IF EXISTS sync_completions_delete")
                database.execSQL("DROP TRIGGER IF EXISTS sync_timelogs_insert")
                database.execSQL("DROP TRIGGER IF EXISTS sync_timelogs_update")
                database.execSQL("DROP TRIGGER IF EXISTS sync_timelogs_delete")
                database.execSQL("DROP TRIGGER IF EXISTS sync_metrics_insert")
                database.execSQL("DROP TRIGGER IF EXISTS sync_metrics_update")
                database.execSQL("DROP TRIGGER IF EXISTS sync_metrics_delete")
                database.execSQL("DROP TRIGGER IF EXISTS sync_metric_logs_insert")
                database.execSQL("DROP TRIGGER IF EXISTS sync_metric_logs_update")
                database.execSQL("DROP TRIGGER IF EXISTS sync_metric_logs_delete")
                database.execSQL("DROP TRIGGER IF EXISTS sync_habit_metric_links_insert")
                database.execSQL("DROP TRIGGER IF EXISTS sync_habit_metric_links_update")
                database.execSQL("DROP TRIGGER IF EXISTS sync_habit_metric_links_delete")

                database.execSQL("""
                    CREATE TABLE habits_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        habitType TEXT NOT NULL,
                        iconResId INTEGER NOT NULL,
                        colorHex TEXT NOT NULL,
                        schedule TEXT NOT NULL,
                        targetValue INTEGER NOT NULL,
                        isCountdown INTEGER NOT NULL,
                        isActive INTEGER NOT NULL,
                        uuid TEXT NOT NULL,
                        parentHabitId TEXT,
                        targetCycles INTEGER,
                        failMode TEXT NOT NULL,
                        goalSuccess INTEGER,
                        activityRate INTEGER NOT NULL,
                        activityRateUpdatedAt INTEGER NOT NULL,
                        bestTime INTEGER,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO habits_new(
                        id, name, description, habitType, iconResId, colorHex, schedule,
                        targetValue, isCountdown, isActive, uuid, parentHabitId, targetCycles,
                        failMode, goalSuccess, activityRate, activityRateUpdatedAt, bestTime,
                        createdAt, updatedAt
                    )
                    SELECT id, name, description, habitType, iconResId, colorHex, schedule,
                           targetValue, isCountdown, isActive, uuid, parentHabitId, targetCycles,
                           failMode, goalSuccess, activityRate, activityRateUpdatedAt, bestTime,
                           createdAt, updatedAt
                    FROM habits
                """.trimIndent())

                database.execSQL("""
                    CREATE TABLE metrics_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        unit TEXT NOT NULL,
                        decimalPlaces INTEGER NOT NULL,
                        aggregationType TEXT NOT NULL,
                        targetDirection TEXT,
                        targetValue REAL,
                        targetValueUpper REAL,
                        iconResId INTEGER NOT NULL,
                        colorHex TEXT NOT NULL,
                        isActive INTEGER NOT NULL,
                        uuid TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO metrics_new(
                        id, name, description, unit, decimalPlaces, aggregationType,
                        targetDirection, targetValue, targetValueUpper, iconResId, colorHex,
                        isActive, uuid, createdAt, updatedAt
                    )
                    SELECT id, name, description, unit, decimalPlaces, aggregationType,
                           targetDirection, targetValue, targetValueUpper, iconResId, colorHex,
                           isActive, uuid, createdAt, updatedAt
                    FROM metrics
                """.trimIndent())

                database.execSQL("""
                    CREATE TABLE timelogs_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        habitId INTEGER NOT NULL,
                        startTime INTEGER NOT NULL,
                        endTime INTEGER,
                        durationSeconds INTEGER NOT NULL,
                        isPaused INTEGER NOT NULL,
                        pausedAt INTEGER,
                        accumulatedPauseMillis INTEGER NOT NULL,
                        timerNextCommandSequence INTEGER NOT NULL,
                        timerControlGeneration INTEGER NOT NULL,
                        timerLastCommandAt INTEGER,
                        timerTimezone TEXT,
                        timerActiveElapsedMillis INTEGER NOT NULL,
                        timerElapsedRealtimeAnchor INTEGER,
                        timerBootCount INTEGER,
                        date INTEGER NOT NULL,
                        uuid TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(habitId) REFERENCES habits(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO timelogs_new(
                        id, habitId, startTime, endTime, durationSeconds, isPaused, pausedAt,
                        accumulatedPauseMillis, timerNextCommandSequence, timerControlGeneration,
                        timerLastCommandAt, timerTimezone, timerActiveElapsedMillis,
                        timerElapsedRealtimeAnchor, timerBootCount, date, uuid, createdAt, updatedAt
                    )
                    SELECT id, habitId, startTime, endTime, durationSeconds, isPaused, pausedAt,
                           accumulatedPauseMillis, timerNextCommandSequence, timerControlGeneration,
                           timerLastCommandAt, timerTimezone, timerActiveElapsedMillis,
                           timerElapsedRealtimeAnchor, timerBootCount, date, uuid, createdAt, updatedAt
                    FROM timelogs
                    WHERE timerSyncEnabled = 1
                       OR (endTime IS NOT NULL
                           AND endTime > startTime
                           AND durationSeconds >= 0
                           AND durationSeconds * 1000 <= endTime - startTime)
                """.trimIndent())

                database.execSQL("""
                    CREATE TABLE completions_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        habitId INTEGER NOT NULL,
                        date INTEGER NOT NULL,
                        value INTEGER NOT NULL,
                        actualCompletedAt INTEGER,
                        uuid TEXT NOT NULL,
                        habitUuid TEXT,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(habitId) REFERENCES habits(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO completions_new(id, habitId, date, value, actualCompletedAt, uuid, habitUuid, createdAt)
                    SELECT id, habitId, date, value, actualCompletedAt, uuid, habitUuid, createdAt
                    FROM completions
                """.trimIndent())

                database.execSQL("""
                    CREATE TABLE metric_logs_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        metricId INTEGER NOT NULL,
                        date INTEGER NOT NULL,
                        value REAL NOT NULL,
                        unit TEXT NOT NULL,
                        note TEXT NOT NULL,
                        uuid TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(metricId) REFERENCES metrics(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO metric_logs_new(id, metricId, date, value, unit, note, uuid, createdAt, updatedAt)
                    SELECT id, metricId, date, value, unit, note, uuid, createdAt, updatedAt
                    FROM metric_logs
                """.trimIndent())

                database.execSQL("""
                    CREATE TABLE habit_metric_links_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        habitId INTEGER NOT NULL,
                        habitUuid TEXT NOT NULL,
                        metricId INTEGER NOT NULL,
                        metricUuid TEXT NOT NULL,
                        coefficient REAL NOT NULL,
                        showInHabitDetail INTEGER NOT NULL,
                        promptOnComplete INTEGER NOT NULL,
                        isActive INTEGER NOT NULL,
                        uuid TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(habitId) REFERENCES habits(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(metricId) REFERENCES metrics(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO habit_metric_links_new(
                        id, habitId, habitUuid, metricId, metricUuid, coefficient,
                        showInHabitDetail, promptOnComplete, isActive, uuid, createdAt, updatedAt
                    )
                    SELECT id, habitId, habitUuid, metricId, metricUuid, coefficient,
                           showInHabitDetail, promptOnComplete, isActive, uuid, createdAt, updatedAt
                    FROM habit_metric_links
                """.trimIndent())

                database.execSQL("""
                    CREATE TABLE timer_segments_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionUuid TEXT NOT NULL,
                        sequence INTEGER NOT NULL,
                        startedAt INTEGER NOT NULL,
                        endedAt INTEGER,
                        FOREIGN KEY(sessionUuid) REFERENCES timelogs(uuid) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO timer_segments_new(id, sessionUuid, sequence, startedAt, endedAt)
                    SELECT id, sessionUuid, sequence, startedAt, endedAt
                    FROM timer_segments
                    WHERE sessionUuid IN (SELECT uuid FROM timelogs_new)
                """.trimIndent())

                database.execSQL("""
                    CREATE TABLE timelog_day_allocations_new (
                        sessionUuid TEXT NOT NULL,
                        habitId INTEGER NOT NULL,
                        localDate TEXT NOT NULL,
                        localDateEpoch INTEGER NOT NULL,
                        timezone TEXT NOT NULL,
                        durationMillis INTEGER NOT NULL,
                        PRIMARY KEY(sessionUuid, localDate, timezone),
                        FOREIGN KEY(sessionUuid) REFERENCES timelogs(uuid) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO timelog_day_allocations_new(
                        sessionUuid, habitId, localDate, localDateEpoch, timezone, durationMillis
                    )
                    SELECT sessionUuid, habitId, localDate, localDateEpoch, timezone, durationMillis
                    FROM timelog_day_allocations
                    WHERE sessionUuid IN (SELECT uuid FROM timelogs_new)
                """.trimIndent())

                database.execSQL("DROP TABLE timer_segments")
                database.execSQL("DROP TABLE timelog_day_allocations")
                database.execSQL("DROP TABLE completions")
                database.execSQL("DROP TABLE metric_logs")
                database.execSQL("DROP TABLE habit_metric_links")
                database.execSQL("DROP TABLE timelogs")
                database.execSQL("DROP TABLE metrics")
                database.execSQL("DROP TABLE habits")

                database.execSQL("ALTER TABLE habits_new RENAME TO habits")
                database.execSQL("ALTER TABLE metrics_new RENAME TO metrics")
                database.execSQL("ALTER TABLE timelogs_new RENAME TO timelogs")
                database.execSQL("ALTER TABLE completions_new RENAME TO completions")
                database.execSQL("ALTER TABLE metric_logs_new RENAME TO metric_logs")
                database.execSQL("ALTER TABLE habit_metric_links_new RENAME TO habit_metric_links")
                database.execSQL("ALTER TABLE timer_segments_new RENAME TO timer_segments")
                database.execSQL("ALTER TABLE timelog_day_allocations_new RENAME TO timelog_day_allocations")

                database.execSQL("CREATE INDEX index_habits_uuid ON habits(uuid)")
                database.execSQL("CREATE UNIQUE INDEX index_habits_name ON habits(name)")
                database.execSQL("CREATE INDEX index_completions_habitId ON completions(habitId)")
                database.execSQL("CREATE INDEX index_completions_date ON completions(date)")
                database.execSQL("CREATE INDEX index_completions_uuid ON completions(uuid)")
                database.execSQL("CREATE INDEX index_timelogs_habitId ON timelogs(habitId)")
                database.execSQL("CREATE INDEX index_timelogs_date ON timelogs(date)")
                database.execSQL("CREATE UNIQUE INDEX index_timelogs_uuid ON timelogs(uuid)")
                database.execSQL("CREATE INDEX index_metrics_uuid ON metrics(uuid)")
                database.execSQL("CREATE INDEX index_metric_logs_metricId ON metric_logs(metricId)")
                database.execSQL("CREATE INDEX index_metric_logs_date ON metric_logs(date)")
                database.execSQL("CREATE INDEX index_metric_logs_uuid ON metric_logs(uuid)")
                database.execSQL("CREATE INDEX index_habit_metric_links_habitId ON habit_metric_links(habitId)")
                database.execSQL("CREATE INDEX index_habit_metric_links_metricId ON habit_metric_links(metricId)")
                database.execSQL("CREATE INDEX index_habit_metric_links_uuid ON habit_metric_links(uuid)")
                database.execSQL("CREATE UNIQUE INDEX index_habit_metric_links_habitId_metricId ON habit_metric_links(habitId, metricId)")
                database.execSQL("CREATE UNIQUE INDEX index_timer_segments_sessionUuid_sequence ON timer_segments(sessionUuid, sequence)")
                database.execSQL("CREATE INDEX index_timelog_day_allocations_habitId ON timelog_day_allocations(habitId)")
                database.execSQL("CREATE INDEX index_timelog_day_allocations_localDateEpoch ON timelog_day_allocations(localDateEpoch)")

                // Retired Android timelog facts cannot be prepared by the command-only client.
                database.execSQL("DELETE FROM sync_outbox WHERE recordType = 'timelog'")
                installV2Triggers(database)
            }
        }

        /** Migration 23 -> 24: retain merge bases and all sides of user conflicts. */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE sync_outbox ADD COLUMN basePayloadJson TEXT")
                database.execSQL("ALTER TABLE sync_entity_state ADD COLUMN payloadJson TEXT")
                database.execSQL("ALTER TABLE sync_entity_state ADD COLUMN payloadHash TEXT")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_conflicts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        operationId TEXT NOT NULL,
                        recordType TEXT NOT NULL,
                        localEntityUuid TEXT NOT NULL,
                        wireEntityUuid TEXT NOT NULL,
                        entityType TEXT NOT NULL,
                        action TEXT NOT NULL,
                        referenceUuid TEXT,
                        baseRevision INTEGER,
                        serverRevision INTEGER NOT NULL,
                        basePayloadJson TEXT,
                        localPayloadJson TEXT NOT NULL,
                        serverPayloadJson TEXT NOT NULL,
                        conflictingFieldsJson TEXT NOT NULL,
                        conflictKind TEXT,
                        errorCode TEXT,
                        message TEXT,
                        status TEXT NOT NULL,
                        resolution TEXT,
                        createdAt INTEGER NOT NULL,
                        resolvedAt INTEGER
                    )
                """.trimIndent())
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_sync_conflicts_operationId " +
                        "ON sync_conflicts(operationId)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_sync_conflicts_status ON sync_conflicts(status)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_sync_conflicts_recordType_localEntityUuid " +
                        "ON sync_conflicts(recordType, localEntityUuid)"
                )
            }
        }

        fun getInstance(context: Context): HabitDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    HabitDatabase::class.java,
                    "habit_database"
                )
                .fallbackToDestructiveMigrationOnDowngrade()
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24)
                .addCallback(SYNC_CALLBACK)
                .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * Sets the database instance for testing purposes.
         * This allows tests to inject an in-memory database.
         * Should only be used in test code.
         */
        @Suppress("unused")
        fun setInstanceForTesting(database: HabitDatabase) {
            INSTANCE = database
        }

        /**
         * Clears the singleton instance. For testing only.
         */
        @Suppress("unused")
        fun clearInstanceForTesting() {
            INSTANCE = null
        }
    }
}
