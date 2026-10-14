package com.dayforge.data.local

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Add empty authority tables; never infer a birth from today's head or rewrite old journals. */
object NextChallengeMigration : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (db.version == 13) {
            check(db.query("SELECT id,identity_hash FROM room_master_table ORDER BY id").use {
                it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 42L &&
                    it.getType(1) == Cursor.FIELD_TYPE_STRING &&
                    it.getString(1) == "524307869b791fde05e3bd4497ef798b" && !it.moveToNext()
            }) { "Room cannot verify data integrity for version 13" }
        }
        db.execSQL("ALTER TABLE next_sync_state ADD COLUMN challengeContract INTEGER NOT NULL DEFAULT 0")
        db.execSQL("""CREATE TABLE next_challenge_rounds (
            roundUuid TEXT NOT NULL, activityUuid TEXT NOT NULL, generation INTEGER NOT NULL,
            accountId TEXT NOT NULL, serverInstanceId TEXT NOT NULL, syncEpoch TEXT NOT NULL,
            sourceDeviceUuid TEXT, operationUuid TEXT, recordJson TEXT NOT NULL, recordHash TEXT NOT NULL,
            PRIMARY KEY(roundUuid))""")
        db.execSQL("CREATE UNIQUE INDEX index_next_challenge_rounds_activityUuid_generation ON next_challenge_rounds(activityUuid,generation)")
        db.execSQL("CREATE UNIQUE INDEX index_next_challenge_rounds_sourceDeviceUuid_operationUuid ON next_challenge_rounds(sourceDeviceUuid,operationUuid)")
        db.execSQL("""CREATE TABLE next_challenge_births (
            entityType TEXT NOT NULL, entityUuid TEXT NOT NULL, roundUuid TEXT NOT NULL,
            PRIMARY KEY(entityType,entityUuid), FOREIGN KEY(roundUuid) REFERENCES next_challenge_rounds(roundUuid)
            ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("CREATE INDEX index_next_challenge_births_roundUuid ON next_challenge_births(roundUuid)")
        db.execSQL("""CREATE TABLE next_challenge_state (
            accountId TEXT NOT NULL, serverInstanceId TEXT NOT NULL, syncEpoch TEXT NOT NULL, deviceId TEXT NOT NULL,
            generation INTEGER NOT NULL, cursor INTEGER NOT NULL, batchHash TEXT NOT NULL, metadataHash TEXT NOT NULL,
            id INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(id) REFERENCES next_sync_state(id)
            ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)""")
    }
}
