package com.dayforge.data.local

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Add empty journal tables only. Never assign an old object/request to a configuration import. */
object NextConfigImportMigration : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (db.version == 15) check(db.query("SELECT id,identity_hash FROM room_master_table ORDER BY id").use {
            it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 42L &&
                it.getType(1) == Cursor.FIELD_TYPE_STRING &&
                it.getString(1) == "397822d3b6f86f9d2b73a636c05287d9" && !it.moveToNext()
        }) { "Room cannot verify data integrity for version 15" }
        db.execSQL("""CREATE TABLE next_config_imports (
            importId TEXT NOT NULL, accountId TEXT NOT NULL, serverInstanceId TEXT NOT NULL,
            syncEpoch TEXT NOT NULL, deviceId TEXT NOT NULL, archiveHash TEXT NOT NULL,
            identitiesHash TEXT NOT NULL, mode TEXT NOT NULL, state TEXT NOT NULL, receiptHash TEXT,
            PRIMARY KEY(importId))""")
        db.execSQL("""CREATE TABLE next_config_import_payloads (
            importId TEXT NOT NULL, payloadKind TEXT NOT NULL, part INTEGER NOT NULL, bytes BLOB NOT NULL,
            PRIMARY KEY(importId,payloadKind,part), FOREIGN KEY(importId) REFERENCES next_config_imports(importId)
            ON UPDATE NO ACTION ON DELETE NO ACTION)""")
    }
}
