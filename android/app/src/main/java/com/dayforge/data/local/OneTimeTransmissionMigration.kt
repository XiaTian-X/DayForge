package com.dayforge.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** No device/epoch can be inferred for previously attempted requests. */
object OneTimeTransmissionMigration : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (db.version == 4) {
            val identity = db.query("SELECT identity_hash FROM room_master_table WHERE id=42").use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            check(identity == "8ab28a1519468862873003932dd14762") { "Room cannot verify data integrity for version 4" }
        }
        db.execSQL("""CREATE TABLE one_time_transmissions (
            operationId TEXT NOT NULL PRIMARY KEY, accountId TEXT NOT NULL, serverInstanceId TEXT NOT NULL,
            syncEpoch TEXT NOT NULL, deviceId TEXT NOT NULL, operationJson TEXT NOT NULL, rejectionJson TEXT)""")
    }
}
