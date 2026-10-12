package com.dayforge.data.local

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Empty capability only; migration never freezes an existing replica or changes business bytes. */
object NextReplicaFenceMigration : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (db.version == 17) check(db.query("SELECT id,identity_hash FROM room_master_table ORDER BY id").use {
            it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 42L &&
                it.getType(1) == Cursor.FIELD_TYPE_STRING &&
                it.getString(1) == "dba3a0b170547a938427cec11c066c9e" && !it.moveToNext()
        }) { "Room cannot verify data integrity for version 17" }
        db.execSQL("""CREATE TABLE next_replica_fence (
            recoveryId TEXT NOT NULL, accountId TEXT NOT NULL, serverInstanceId TEXT NOT NULL,
            oldEpoch TEXT NOT NULL, newEpoch TEXT NOT NULL, authenticationGeneration TEXT NOT NULL,
            deviceId TEXT NOT NULL, capabilityRevision INTEGER NOT NULL, sourceHash TEXT NOT NULL,
            mode TEXT NOT NULL, id INTEGER NOT NULL, PRIMARY KEY(id))""")
        NextReplicaFenceSql.install(db)
    }
}

/** SQLite enforces the fence below every DAO, callback and outbox suppression path. */
internal object NextReplicaFenceSql {
    private const val TABLE = "next_replica_fence"
    private const val ERROR = "SYNC_REPLICA_SWITCH_FENCED"

    fun install(db: SupportSQLiteDatabase) {
        // Older frozen migration fixtures do not own this capability.
        val tables = db.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) {
                check(c.getType(0) == Cursor.FIELD_TYPE_STRING)
                add(c.getString(0).also { check(it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*"))) })
            } }
        }
        if (TABLE !in tables) return
        // Unknown modes/extra rows fail closed even before the strict recovery reader runs.
        val active = "EXISTS(SELECT 1 FROM $TABLE WHERE id IS NOT 1 OR mode IS NOT 'closed')"
        for (table in tables.filterNot { it == TABLE || it.startsWith("sqlite_") ||
            it in setOf("android_metadata", "room_master_table") }) {
            for (action in listOf("INSERT", "UPDATE", "DELETE")) db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS replica_fence_${table}_${action.lowercase(java.util.Locale.ROOT)}
                BEFORE $action ON `$table` WHEN $active
                BEGIN SELECT RAISE(ABORT,'$ERROR'); END
            """.trimIndent())
        }
        // DELETE/REPLACE/clearAllTables cannot discard an active fence. Only an explicitly
        // matched pre-COMMIT close may change mode; its bound identity/source stay untouched.
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS replica_fence_control_insert
            BEFORE INSERT ON $TABLE WHEN $active OR NEW.id IS NOT 1 OR NEW.mode IS NOT 'frozen'
            BEGIN SELECT RAISE(ABORT,'$ERROR'); END""".trimIndent())
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS replica_fence_control_delete
            BEFORE DELETE ON $TABLE WHEN OLD.mode IS NOT 'closed' OR OLD.id IS NOT 1
            BEGIN SELECT RAISE(ABORT,'$ERROR'); END""".trimIndent())
        val bindings = listOf("id", "recoveryId", "accountId", "serverInstanceId", "oldEpoch", "newEpoch",
            "authenticationGeneration", "deviceId", "capabilityRevision", "sourceHash")
        val changed = bindings.joinToString(" OR ") { "OLD.$it IS NOT NEW.$it" }
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS replica_fence_control_update
            BEFORE UPDATE ON $TABLE WHEN NEW.id IS NOT 1 OR NEW.mode NOT IN ('frozen','closed')
                OR (OLD.mode IS NOT 'closed' AND (OLD.mode IS NOT 'frozen' OR NEW.mode IS NOT 'closed' OR $changed))
            BEGIN SELECT RAISE(ABORT,'$ERROR'); END""".trimIndent())
    }
}
