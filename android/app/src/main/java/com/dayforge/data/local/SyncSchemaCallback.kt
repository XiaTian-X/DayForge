package com.dayforge.data.local

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/** Installs schema objects that Room does not represent in exported entity metadata. */
internal object SyncSchemaCallback : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        installSyncObjects(db)
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        // Repair sync objects after a destructive rebuild or interrupted development install.
        installSyncObjects(db)
    }

    private fun installSyncObjects(database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT OR IGNORE INTO sync_control(id, suppressOutbox) VALUES(1, 0)"
        )
        createSyncTrigger(
            database = database,
            table = "habits",
            recordType = "habit",
            uuidColumn = "uuid",
            newReferenceExpression = "NEW.habitType",
            updateCondition =
                "OLD.name IS NOT NEW.name OR OLD.description IS NOT NEW.description OR " +
                    "OLD.habitType IS NOT NEW.habitType OR OLD.iconResId IS NOT NEW.iconResId OR " +
                    "OLD.colorHex IS NOT NEW.colorHex OR OLD.schedule IS NOT NEW.schedule OR " +
                    "OLD.targetValue IS NOT NEW.targetValue OR OLD.isCountdown IS NOT NEW.isCountdown OR " +
                    "OLD.isActive IS NOT NEW.isActive OR OLD.parentHabitId IS NOT NEW.parentHabitId OR " +
                    "OLD.targetCycles IS NOT NEW.targetCycles OR OLD.failMode IS NOT NEW.failMode OR " +
                    "OLD.goalSuccess IS NOT NEW.goalSuccess OR OLD.bestTime IS NOT NEW.bestTime"
        )
        createSyncTrigger(
            database = database,
            table = "completions",
            recordType = "completion",
            uuidColumn = "uuid",
            newReferenceExpression =
                "COALESCE(NEW.habitUuid, (SELECT uuid FROM habits WHERE id = NEW.habitId))",
            updateCondition =
                "OLD.date IS NOT NEW.date OR OLD.value IS NOT NEW.value OR " +
                    "OLD.actualCompletedAt IS NOT NEW.actualCompletedAt"
        )
        createSyncTrigger(
            database = database,
            table = "metrics",
            recordType = "metric",
            uuidColumn = "uuid",
            newReferenceExpression = null,
            updateCondition =
                "OLD.name IS NOT NEW.name OR OLD.description IS NOT NEW.description OR " +
                    "OLD.unit IS NOT NEW.unit OR OLD.decimalPlaces IS NOT NEW.decimalPlaces OR " +
                    "OLD.aggregationType IS NOT NEW.aggregationType OR " +
                    "OLD.targetDirection IS NOT NEW.targetDirection OR " +
                    "OLD.targetValue IS NOT NEW.targetValue OR " +
                    "OLD.targetValueUpper IS NOT NEW.targetValueUpper OR " +
                    "OLD.iconResId IS NOT NEW.iconResId OR OLD.colorHex IS NOT NEW.colorHex OR " +
                    "OLD.isActive IS NOT NEW.isActive"
        )
        createSyncTrigger(
            database = database,
            table = "metric_logs",
            recordType = "metric_log",
            uuidColumn = "uuid",
            newReferenceExpression = "(SELECT uuid FROM metrics WHERE id = NEW.metricId)",
            updateCondition =
                "OLD.date IS NOT NEW.date OR OLD.value IS NOT NEW.value OR " +
                    "OLD.unit IS NOT NEW.unit OR OLD.note IS NOT NEW.note"
        )
        createSyncTrigger(
            database = database,
            table = "habit_metric_links",
            recordType = "link",
            uuidColumn = "uuid",
            newReferenceExpression = "NEW.habitUuid",
            updateCondition =
                "OLD.habitUuid IS NOT NEW.habitUuid OR OLD.metricUuid IS NOT NEW.metricUuid OR " +
                    "OLD.coefficient IS NOT NEW.coefficient OR " +
                    "OLD.showInHabitDetail IS NOT NEW.showInHabitDetail OR " +
                    "OLD.promptOnComplete IS NOT NEW.promptOnComplete OR " +
                    "OLD.isActive IS NOT NEW.isActive"
        )
    }

    private fun createSyncTrigger(
        database: SupportSQLiteDatabase,
        table: String,
        recordType: String,
        uuidColumn: String,
        newReferenceExpression: String?,
        updateCondition: String
    ) {
        val newReference = newReferenceExpression ?: "NULL"
        val oldReference = newReferenceExpression?.replace("NEW.", "OLD.") ?: "NULL"
        val outboxEnabled = "(SELECT suppressOutbox FROM sync_control WHERE id = 1) = 0"
        val createdAt = "CAST(strftime('%s','now') AS INTEGER) * 1000"

        database.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS sync_${table}_insert
            AFTER INSERT ON $table
            WHEN $outboxEnabled
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
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS sync_${table}_update
            AFTER UPDATE ON $table
            WHEN $outboxEnabled AND ($updateCondition)
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
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS sync_${table}_delete
            AFTER DELETE ON $table
            WHEN $outboxEnabled
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
            """.trimIndent()
        )
    }

    private fun uuidSql(): String =
        "lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4' || " +
            "substr(hex(randomblob(2)),2) || '-' || " +
            "substr('89ab',abs(random()) % 4 + 1,1) || substr(hex(randomblob(2)),2) || '-' || " +
            "hex(randomblob(6)))"
}
