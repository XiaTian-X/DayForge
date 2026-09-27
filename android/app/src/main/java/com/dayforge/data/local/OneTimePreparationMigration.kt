package com.dayforge.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive preparation only: does not infer legacy task identity or activate protocol v5. */
object OneTimePreparationMigration : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE habits ADD COLUMN completionPolicy TEXT")
        db.execSQL("ALTER TABLE habits ADD COLUMN oneTimeConfirmedVersion INTEGER")
        db.execSQL("ALTER TABLE habits ADD COLUMN oneTimeConfirmedHeadEventUuid TEXT")
        db.execSQL("ALTER TABLE habits ADD COLUMN oneTimeConfirmedCompletionEventUuid TEXT")
        db.execSQL("ALTER TABLE completions ADD COLUMN oneTimeAction TEXT")
        db.execSQL("ALTER TABLE completions ADD COLUMN oneTimeExpectedVersion INTEGER")
        db.execSQL("ALTER TABLE completions ADD COLUMN oneTimeExpectedHeadEventUuid TEXT")
        db.execSQL("ALTER TABLE completions ADD COLUMN oneTimeRevertsEventUuid TEXT")
        // No UPDATE, trigger replacement, queue rewrite or default completion projection.
        // Existing v4 fields and trigger semantics remain unchanged until coordinated activation.
    }
}
