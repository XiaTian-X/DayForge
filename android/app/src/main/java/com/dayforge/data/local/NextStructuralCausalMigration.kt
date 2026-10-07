package com.dayforge.data.local

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Empty append only: no ancestry, send state or acceptance is guessed from old rows. */
object NextStructuralCausalMigration : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (db.version == 10) {
            val verified = db.query("SELECT id,identity_hash FROM room_master_table ORDER BY id").use {
                it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 42L &&
                    it.getType(1) == Cursor.FIELD_TYPE_STRING &&
                    it.getString(1) == "f47163958305f39520eb16c042502aba" && !it.moveToNext()
            }
            check(verified) { "Room cannot verify data integrity for version 10" }
        }
        db.execSQL("""CREATE TABLE next_structural_dependencies (
            operationId TEXT NOT NULL, logicalOrder INTEGER NOT NULL, originHash TEXT NOT NULL,
            predecessorId TEXT, predecessorOriginHash TEXT, predecessorDependencyHash TEXT, capturedDeviceId TEXT,
            kind TEXT NOT NULL, PRIMARY KEY(operationId),
            FOREIGN KEY(kind, operationId) REFERENCES next_request_origins(kind, requestId) ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(predecessorId) REFERENCES next_structural_dependencies(operationId) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("CREATE UNIQUE INDEX index_next_structural_dependencies_kind_operationId ON next_structural_dependencies(kind, operationId)")
        db.execSQL("CREATE INDEX index_next_structural_dependencies_predecessorId ON next_structural_dependencies(predecessorId)")
        db.execSQL("CREATE UNIQUE INDEX index_next_structural_dependencies_logicalOrder ON next_structural_dependencies(logicalOrder)")
        db.execSQL("""CREATE TABLE next_structural_supersessions (
            originalId TEXT NOT NULL, replacementId TEXT NOT NULL, originalQueueId INTEGER NOT NULL,
            replacementQueueId INTEGER NOT NULL, originalOriginHash TEXT NOT NULL, originalDependencyHash TEXT NOT NULL,
            originalSourceHash TEXT NOT NULL, sourceSnapshotJson TEXT NOT NULL, sourceSnapshotHash TEXT NOT NULL,
            predecessorAcceptedRequestId TEXT NOT NULL, predecessorAcceptanceHash TEXT NOT NULL,
            replacementOriginHash TEXT NOT NULL, accountId TEXT NOT NULL, serverInstanceId TEXT NOT NULL,
            syncEpoch TEXT NOT NULL, deviceId TEXT NOT NULL, kind TEXT NOT NULL, PRIMARY KEY(originalId),
            FOREIGN KEY(originalId) REFERENCES next_structural_dependencies(operationId) ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(kind, replacementId) REFERENCES next_request_origins(kind, requestId) ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(kind, predecessorAcceptedRequestId) REFERENCES next_acceptances(kind, requestId) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("CREATE UNIQUE INDEX index_next_structural_supersessions_replacementId ON next_structural_supersessions(replacementId)")
        db.execSQL("CREATE UNIQUE INDEX index_next_structural_supersessions_kind_replacementId ON next_structural_supersessions(kind, replacementId)")
        db.execSQL("CREATE INDEX index_next_structural_supersessions_kind_predecessorAcceptedRequestId ON next_structural_supersessions(kind, predecessorAcceptedRequestId)")
        db.execSQL("CREATE UNIQUE INDEX index_next_structural_supersessions_originalQueueId ON next_structural_supersessions(originalQueueId)")
        db.execSQL("CREATE UNIQUE INDEX index_next_structural_supersessions_replacementQueueId ON next_structural_supersessions(replacementQueueId)")
    }
}
