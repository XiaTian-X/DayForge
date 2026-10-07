package com.dayforge.data.local

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Append empty state; never promote a v4 cursor or an accepted recovery candidate. */
object NextSyncStateMigration : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (db.version == 11) {
            check(db.query("SELECT id,identity_hash FROM room_master_table ORDER BY id").use {
                it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 42L &&
                    it.getType(1) == Cursor.FIELD_TYPE_STRING &&
                    it.getString(1) == "f54898e086b51e1db05e90566c65aa33" && !it.moveToNext()
            }) { "Room cannot verify data integrity for version 11" }
        }
        db.execSQL("""CREATE TABLE next_sync_state (
            accountId TEXT NOT NULL, serverInstanceId TEXT NOT NULL, syncEpoch TEXT NOT NULL,
            deviceId TEXT NOT NULL, generation INTEGER NOT NULL, cursor INTEGER NOT NULL,
            bootstrapHash TEXT NOT NULL, batchHash TEXT NOT NULL, id INTEGER NOT NULL,
            PRIMARY KEY(id))""")
        db.execSQL("""CREATE TABLE next_rejections (
            kind TEXT NOT NULL, requestId TEXT NOT NULL, originHash TEXT NOT NULL,
            transmissionHash TEXT NOT NULL, resultHash TEXT NOT NULL, resultJson TEXT NOT NULL,
            PRIMARY KEY(kind,requestId))""")
    }
}
