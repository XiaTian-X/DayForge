package com.dayforge.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Empty journals: even an old zero-attempt timer can have a lost, committed response. */
object NextRequestMigration : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (db.version == 8) {
            val verified = db.query("SELECT id,identity_hash FROM room_master_table ORDER BY id").use {
                it.moveToFirst() && it.getType(0) == android.database.Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 42L &&
                    it.getType(1) == android.database.Cursor.FIELD_TYPE_STRING &&
                    it.getString(1) == "58e389f6a5e03849fe4f8ab17808d266" && !it.moveToNext()
            }
            check(verified) { "Room cannot verify data integrity for version 8" }
        }
        db.execSQL("""CREATE TABLE next_request_origins (
            kind TEXT NOT NULL, requestId TEXT NOT NULL, queueId INTEGER NOT NULL, protocol INTEGER NOT NULL,
            accountId TEXT NOT NULL, serverInstanceId TEXT, syncEpoch TEXT, sourceHash TEXT NOT NULL,
            intentJson TEXT NOT NULL, PRIMARY KEY(kind, requestId))""")
        db.execSQL("CREATE UNIQUE INDEX index_next_request_origins_kind_queueId ON next_request_origins(kind, queueId)")
        db.execSQL("""CREATE TABLE next_transmissions (
            kind TEXT NOT NULL, requestId TEXT NOT NULL, queueId INTEGER NOT NULL, protocol INTEGER NOT NULL,
            accountId TEXT NOT NULL, serverInstanceId TEXT NOT NULL, syncEpoch TEXT NOT NULL, deviceId TEXT NOT NULL,
            wireHash TEXT NOT NULL, wireBytes BLOB NOT NULL, PRIMARY KEY(kind, requestId))""")
        db.execSQL("CREATE UNIQUE INDEX index_next_transmissions_kind_queueId ON next_transmissions(kind, queueId)")
    }
}
