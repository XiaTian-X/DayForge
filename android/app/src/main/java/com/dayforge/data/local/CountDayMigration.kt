package com.dayforge.data.local

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Empty additive policy state: old counts cannot acquire a guessed original target. */
object CountDayMigration : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (db.version == 12) {
            check(db.query("SELECT id,identity_hash FROM room_master_table ORDER BY id").use {
                it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 42L &&
                    it.getType(1) == Cursor.FIELD_TYPE_STRING &&
                    it.getString(1) == "b1550ba4b5c0f0bb60bb638e6ad1a168" && !it.moveToNext()
            }) { "Room cannot verify data integrity for version 12" }
        }
        db.execSQL("""CREATE TABLE count_days (
            habitId INTEGER NOT NULL, habitUuid TEXT NOT NULL, localDate TEXT NOT NULL,
            targetValue INTEGER NOT NULL, isCountdown INTEGER NOT NULL, firstEventUuid TEXT NOT NULL,
            originRequestId TEXT, originHash TEXT, planPredecessorId TEXT,
            planPredecessorOriginHash TEXT, planPredecessorDependencyHash TEXT,
            planQueueWatermark INTEGER, capturedDeviceId TEXT, originKind TEXT NOT NULL,
            PRIMARY KEY(habitId,localDate),
            FOREIGN KEY(habitId) REFERENCES habits(id) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(originKind,originRequestId) REFERENCES next_request_origins(kind,requestId) ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(planPredecessorId) REFERENCES next_structural_dependencies(operationId) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("CREATE INDEX index_count_days_habitId ON count_days(habitId)")
        db.execSQL("CREATE INDEX index_count_days_originKind_originRequestId ON count_days(originKind,originRequestId)")
        db.execSQL("CREATE INDEX index_count_days_planPredecessorId ON count_days(planPredecessorId)")
    }
}
