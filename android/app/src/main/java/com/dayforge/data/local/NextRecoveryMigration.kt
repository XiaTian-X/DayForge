package com.dayforge.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Never derive v5 recovery identity or cursor from an older Room/DataStore replica. */
object NextRecoveryMigration : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (db.version == 7) {
            val identity = db.query("SELECT identity_hash FROM room_master_table WHERE id=42").use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            check(identity == "7d973ffb9326ec19373ebf4deefb7dd6") { "Room cannot verify data integrity for version 7" }
        }
        db.execSQL("""CREATE TABLE next_recovery_state (
            accountId TEXT NOT NULL, serverInstanceId TEXT NOT NULL, syncEpoch TEXT NOT NULL,
            deviceId TEXT NOT NULL, generation INTEGER NOT NULL, phase TEXT NOT NULL, minimumCursor INTEGER NOT NULL,
            candidateCursor INTEGER, snapshotHash TEXT, id INTEGER NOT NULL PRIMARY KEY)""")
    }
}
