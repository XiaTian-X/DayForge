package com.dayforge.data.local

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Never infer acceptance from missing old queues or mutable shadows. */
object NextAcceptanceMigration : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (db.version == 9) {
            val verified = db.query("SELECT id,identity_hash FROM room_master_table ORDER BY id").use {
                it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 42L &&
                    it.getType(1) == Cursor.FIELD_TYPE_STRING &&
                    it.getString(1) == "068446539bc550953ea657cbbea53f67" && !it.moveToNext()
            }
            check(verified) { "Room cannot verify data integrity for version 9" }
        }
        db.execSQL("""CREATE TABLE next_acceptances (
            kind TEXT NOT NULL, requestId TEXT NOT NULL, originHash TEXT NOT NULL,
            transmissionHash TEXT NOT NULL, resultHash TEXT NOT NULL, resultJson TEXT NOT NULL,
            PRIMARY KEY(kind, requestId))""")
    }
}
