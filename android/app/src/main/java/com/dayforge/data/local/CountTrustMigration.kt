package com.dayforge.data.local

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Add device-local, initially untrusted storage; preserve every existing business and wire byte. */
object CountTrustMigration : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (db.version == 18) check(db.query("SELECT id,identity_hash FROM room_master_table ORDER BY id").use {
            it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 42L &&
                it.getType(1) == Cursor.FIELD_TYPE_STRING &&
                it.getString(1) == "ea4615ceb013fc2a9269d6ec9077adeb" && !it.moveToNext()
        }) { "Room cannot verify data integrity for version 18" }
        CountTrustSchema.createTables(db)
        CountTrustSchema.install(db)
        NextReplicaFenceSql.install(db)
    }
}
