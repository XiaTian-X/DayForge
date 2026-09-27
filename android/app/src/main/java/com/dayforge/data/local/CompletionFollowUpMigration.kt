package com.dayforge.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Add local receipts/drafts without guessing old prompt identities or rewriting business rows. */
object CompletionFollowUpMigration : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // When chaining 1/2 -> 4, Room updates the header/hash only after ALL migrations.
        if (db.version == 3) {
            val identity = db.query("SELECT identity_hash FROM room_master_table WHERE id=42").use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            check(identity == "f44a5f0edbbc247af2f98079d03ecbc6") { "Room cannot verify data integrity for version 3" }
        }
        db.execSQL("""CREATE TABLE local_fact_submissions (
            operationId TEXT NOT NULL PRIMARY KEY, entityType TEXT NOT NULL, entityUuid TEXT NOT NULL,
            referenceUuid TEXT NOT NULL, payloadJson TEXT NOT NULL)""")
        db.execSQL("CREATE UNIQUE INDEX index_local_fact_submissions_entityType_entityUuid ON local_fact_submissions(entityType, entityUuid)")
        db.execSQL("""CREATE TABLE completion_metric_prompts (
            eventUuid TEXT NOT NULL PRIMARY KEY, activityUuid TEXT NOT NULL, state TEXT NOT NULL,
            revision INTEGER NOT NULL, entriesJson TEXT NOT NULL, recordedAtMillis INTEGER, timezone TEXT)""")
        db.execSQL("CREATE INDEX index_completion_metric_prompts_activityUuid ON completion_metric_prompts(activityUuid)")
        db.execSQL("CREATE INDEX index_completion_metric_prompts_state ON completion_metric_prompts(state)")
    }
}
