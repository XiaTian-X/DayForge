package com.dayforge.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Add wire planning metadata without deriving dates or timezone from this device. */
object PlanMetadataMigration : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (db.version == 6) {
            val identity = db.query("SELECT identity_hash FROM room_master_table WHERE id=42").use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            check(identity == "57fe775bbdfa092a98a5e2f3a8db03d4") { "Room cannot verify data integrity for version 6" }
        }
        db.execSQL("ALTER TABLE habits ADD COLUMN planMetadata TEXT")
        db.execSQL("DROP TRIGGER IF EXISTS sync_habits_update")
    }
}
