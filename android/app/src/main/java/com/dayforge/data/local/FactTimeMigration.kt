package com.dayforge.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Adds capture metadata without rewriting UTC instants or prepared/idempotent operations. */
object FactTimeMigration : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Read once: changing the device zone during a large migration must not split its policy.
        val fallbackZone = ZoneId.systemDefault()
        for ((table, recordType, entityType, occurrence) in listOf(
            listOf("completions", "completion", "activity_event", "COALESCE(actualCompletedAt, date)"),
            listOf("metric_logs", "metric_log", "metric_observation", "date")
        )) {
            db.execSQL("ALTER TABLE $table ADD COLUMN recordedTimezone TEXT NOT NULL DEFAULT 'UTC'")
            db.execSQL("ALTER TABLE $table ADD COLUMN recordedLocalDate TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE $table ADD COLUMN timeMetadataSource TEXT NOT NULL DEFAULT 'captured'")
            // onOpen reinstalls the new trigger definition. Backfill must not enqueue mutations.
            db.execSQL("DROP TRIGGER IF EXISTS sync_${table}_update")
            db.query("SELECT id, uuid, $occurrence FROM $table").use { rows ->
                while (rows.moveToNext()) {
                    val id = rows.getLong(0)
                    val uuid = rows.getString(1)
                    val occurredAt = rows.getLong(2)
                    val known = recover(db, recordType, entityType, uuid, occurredAt)
                    val timezone = known?.first ?: fallbackZone.id
                    val localDate = known?.second ?: Instant.ofEpochMilli(occurredAt)
                        .atZone(fallbackZone).toLocalDate().toString()
                    db.execSQL("UPDATE $table SET recordedTimezone = ?, recordedLocalDate = ?, timeMetadataSource = ? WHERE id = ?",
                        arrayOf(timezone, localDate, if (known != null) "legacy_sync" else "legacy_device_fallback", id))
                }
            }
        }
    }

    private fun recover(db: SupportSQLiteDatabase, recordType: String, entityType: String,
                        uuid: String, occurredAt: Long): Pair<String, String>? {
        db.query("SELECT payloadJson FROM sync_outbox WHERE recordType = ? AND entityUuid = ? " +
            "AND action = 'upsert' AND payloadJson IS NOT NULL ORDER BY id DESC", arrayOf(recordType, uuid)).use { cursor ->
            while (cursor.moveToNext()) metadata(cursor.getString(0), occurredAt)?.let { return it }
        }
        db.query("SELECT payloadJson FROM sync_entity_state WHERE entityType = ? AND entityUuid = ? " +
            "AND payloadJson IS NOT NULL", arrayOf(entityType, uuid)).use { cursor ->
            while (cursor.moveToNext()) metadata(cursor.getString(0), occurredAt)?.let { return it }
        }
        return null
    }

    private fun metadata(json: String, occurredAt: Long): Pair<String, String>? = runCatching {
        val payload = Json.parseToJsonElement(json) as JsonObject
        require(payload["event_type"]?.jsonPrimitive?.content != "revert")
        val instant = Instant.parse(payload.getValue("occurred_at").jsonPrimitive.content)
        require(instant.toEpochMilli() == occurredAt) // A stale shadow may predate a local timestamp edit.
        val timezone = payload.getValue("timezone").jsonPrimitive.content
        require(timezone in ZoneId.getAvailableZoneIds())
        val date = LocalDate.parse(payload.getValue("local_date").jsonPrimitive.content)
        require(instant.atZone(ZoneId.of(timezone)).toLocalDate() == date)
        timezone to date.toString()
    }.getOrNull()
}
