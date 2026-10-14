package com.dayforge.data.local

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Add empty private proof tables; preserve every old row, accepted round and frozen request. */
object NextRestartMigration : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (db.version == 14) check(db.query("SELECT id,identity_hash FROM room_master_table ORDER BY id").use {
            it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 42L &&
                it.getType(1) == Cursor.FIELD_TYPE_STRING &&
                it.getString(1) == "1e4ec9cd8102513240ee379e3d30d1aa" && !it.moveToNext()
        }) { "Room cannot verify data integrity for version 14" }
        db.execSQL("""CREATE TABLE next_restart_materializations (
            operationId TEXT NOT NULL, kind TEXT NOT NULL, originHash TEXT NOT NULL, frontierHash TEXT NOT NULL,
            operationJson TEXT NOT NULL, operationHash TEXT NOT NULL, PRIMARY KEY(operationId),
            FOREIGN KEY(kind,operationId) REFERENCES next_request_origins(kind,requestId)
            ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)""")
        db.execSQL("CREATE INDEX index_next_restart_materializations_kind_operationId ON next_restart_materializations(kind,operationId)")
        db.execSQL("""CREATE TABLE next_restart_plan_proofs (
            operationId TEXT NOT NULL, originHash TEXT NOT NULL, transmissionHash TEXT NOT NULL, revision INTEGER NOT NULL,
            logSequence INTEGER NOT NULL, deviceId TEXT NOT NULL, planJson TEXT NOT NULL, planHash TEXT NOT NULL,
            PRIMARY KEY(operationId), FOREIGN KEY(operationId) REFERENCES next_restart_materializations(operationId)
            ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)""")
    }
}
