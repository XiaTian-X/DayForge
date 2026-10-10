package com.dayforge.data.local

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Empty audit capability only: never infer a recovery or change any old intent/business bytes. */
object NextAbandonmentMigration : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (db.version == 16) check(db.query("SELECT id,identity_hash FROM room_master_table ORDER BY id").use {
            it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 42L &&
                it.getType(1) == Cursor.FIELD_TYPE_STRING &&
                it.getString(1) == "267d74a4ef2cc6e97696a0035789dc9d" && !it.moveToNext()
        }) { "Room cannot verify data integrity for version 16" }
        db.execSQL("""CREATE TABLE next_recovery_audits (
            recoveryId TEXT NOT NULL, accountId TEXT NOT NULL, serverInstanceId TEXT NOT NULL,
            syncEpoch TEXT NOT NULL, deviceId TEXT NOT NULL, rootKind TEXT NOT NULL, rootRequestId TEXT NOT NULL,
            graphHash TEXT NOT NULL, checkpointHash TEXT NOT NULL, manifestHash TEXT NOT NULL,
            selectedCount INTEGER NOT NULL, PRIMARY KEY(recoveryId))""")
        db.execSQL("""CREATE TABLE next_abandoned_requests (
            kind TEXT NOT NULL, requestId TEXT NOT NULL, recoveryId TEXT NOT NULL, disposition TEXT NOT NULL,
            sourceHash TEXT NOT NULL, sourceSnapshotHash TEXT NOT NULL, originHash TEXT NOT NULL,
            transmissionHash TEXT, rejectionHash TEXT, PRIMARY KEY(kind,requestId),
            FOREIGN KEY(kind,requestId) REFERENCES next_request_origins(kind,requestId) ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(recoveryId) REFERENCES next_recovery_audits(recoveryId) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("CREATE INDEX index_next_abandoned_requests_recoveryId ON next_abandoned_requests(recoveryId)")
        db.execSQL("""CREATE TABLE next_recovery_audit_payloads (
            recoveryId TEXT NOT NULL, payloadKey TEXT NOT NULL, part INTEGER NOT NULL, bytes BLOB NOT NULL,
            PRIMARY KEY(recoveryId,payloadKey,part), FOREIGN KEY(recoveryId) REFERENCES next_recovery_audits(recoveryId)
            ON UPDATE NO ACTION ON DELETE NO ACTION)""")
    }
}
