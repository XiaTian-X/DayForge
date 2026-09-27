package com.dayforge.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Preserve old rows/requests verbatim. Resource IDs cannot determine a new role or task kind. */
object ObjectAppearanceMigration : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (db.version == 5) {
            val identity = db.query("SELECT identity_hash FROM room_master_table WHERE id=42").use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            check(identity == "74783c04d46cbc429e24583d7d075c18") { "Room cannot verify data integrity for version 5" }
        }
        db.execSQL("ALTER TABLE habits ADD COLUMN appearance TEXT")
        db.execSQL("ALTER TABLE metrics ADD COLUMN appearance TEXT")
        // Reinstalled by the production callback with the additional structural fields.
        db.execSQL("DROP TRIGGER IF EXISTS sync_habits_update")
        db.execSQL("DROP TRIGGER IF EXISTS sync_metrics_update")
    }
}
