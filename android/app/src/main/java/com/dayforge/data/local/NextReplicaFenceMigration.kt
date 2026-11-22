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

    fun install(db: SupportSQLiteDatabase, version: Int = db.version) {
        // A fresh main-schema inventory and its missing DDL share one real write transaction.
        // Never commit a migration/caller's transaction, or reuse this inventory after END.
        if (db.inTransaction()) {
            installInTransaction(db, version)
            return
        }
        db.beginTransaction()
        try {
            installInTransaction(db, version)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun installInTransaction(db: SupportSQLiteDatabase, version: Int) {
        // Older frozen migration fixtures do not own this capability.
        val tables = db.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) {
                check(c.getType(0) == Cursor.FIELD_TYPE_STRING)
                add(c.getString(0).also { check(it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*"))) })
            } }
        }
        if (TABLE !in tables) return
        val existing = db.query("SELECT name FROM main.sqlite_master WHERE type='trigger'").use { c ->
            buildSet { while (c.moveToNext()) {
                check(c.getType(0) == Cursor.FIELD_TYPE_STRING)
                add(c.getString(0))
            } }
        }
        fun missing(name: String, definition: () -> String) {
            // Presence only replaces SQLite's IF NOT EXISTS no-op, never validates a definition.
            // TEMP, other object kinds and differently cased names still take original CREATE.
            if (name !in existing) db.execSQL(definition())
        }
        // Unknown modes/extra rows fail closed even before the strict recovery reader runs.
        val active = "EXISTS(SELECT 1 FROM $TABLE WHERE id IS NOT 1 OR mode IS NOT 'closed')"
        for (table in tables.filterNot { it == TABLE || it.startsWith("sqlite_") ||
            it in setOf("android_metadata", "room_master_table") }) {
            for (action in listOf("INSERT", "UPDATE", "DELETE")) {
                missing("replica_fence_${table}_${action.lowercase(java.util.Locale.ROOT)}") {
                    currentGuardSql(table, action, active, version)
                }
            }
        }
        // DELETE/REPLACE/clearAllTables cannot discard an active fence. Only an explicitly
        // matched pre-COMMIT close may change mode; its bound identity/source stay untouched.
        missing("replica_fence_control_insert") { """CREATE TRIGGER IF NOT EXISTS replica_fence_control_insert
            BEFORE INSERT ON $TABLE WHEN $active OR NEW.id IS NOT 1 OR NEW.mode IS NOT 'frozen'
            BEGIN SELECT RAISE(ABORT,'$ERROR'); END""".trimIndent() }
        missing("replica_fence_control_delete") { """CREATE TRIGGER IF NOT EXISTS replica_fence_control_delete
            BEFORE DELETE ON $TABLE WHEN OLD.mode IS NOT 'closed' OR OLD.id IS NOT 1
            BEGIN SELECT RAISE(ABORT,'$ERROR'); END""".trimIndent() }
        val bindings = listOf("id", "recoveryId", "accountId", "serverInstanceId", "oldEpoch", "newEpoch",
            "authenticationGeneration", "deviceId", "capabilityRevision", "sourceHash")
        val changed = bindings.joinToString(" OR ") { "OLD.$it IS NOT NEW.$it" }
        missing("replica_fence_control_update") { """CREATE TRIGGER IF NOT EXISTS replica_fence_control_update
            BEFORE UPDATE ON $TABLE WHEN NEW.id IS NOT 1 OR NEW.mode NOT IN ('frozen','closed')
                OR (OLD.mode IS NOT 'closed' AND (OLD.mode IS NOT 'frozen' OR NEW.mode IS NOT 'closed' OR $changed))
            BEGIN SELECT RAISE(ABORT,'$ERROR'); END""".trimIndent() }
    }

    /** Exact new device-local guards, also used by the strict previous-version recovery view. */
    fun localTrustDefinitions(): Map<String, String> = buildMap {
        val active = "EXISTS(SELECT 1 FROM $TABLE WHERE id IS NOT 1 OR mode IS NOT 'closed')"
        for (table in CountTrustSchema.LOCAL_TABLES) for (action in listOf("INSERT", "UPDATE", "DELETE")) {
            put("replica_fence_${table}_${action.lowercase(java.util.Locale.ROOT)}", guardSql(table, action, active))
        }
    }

    /** The exact guard set temporarily removed by the dedicated replica replacement transaction. */
    fun businessAndLocalDefinitions(version: Int = CountCommandV20Schema.VERSION): Map<String, String> = buildMap {
        val active = "EXISTS(SELECT 1 FROM $TABLE WHERE id IS NOT 1 OR mode IS NOT 'closed')"
        for (table in CountTrustSchema.BUSINESS_TABLES - TABLE + CountCommandV20Schema.currentLocalTables) {
            for (action in listOf("INSERT", "UPDATE", "DELETE")) {
                put("replica_fence_${table}_${action.lowercase(java.util.Locale.ROOT)}", currentGuardSql(table, action, active, version))
            }
        }
    }

    private fun currentGuardSql(table: String, action: String, active: String, version: Int): String {
        require(version <= CountCommandV20Schema.VERSION)
        val original = guardSql(table, action, active)
        return if (version == CountCommandV20Schema.VERSION && table == CountCommandControlSchema.TABLE && action == "UPDATE")
            CountCommandControlSchema.upgradeGuard(original) else original
    }

    private fun guardSql(table: String, action: String, active: String): String {
        // The fence's own AFTER trigger may only invalidate, never grant count proof authority.
        val blocked = if (table == "count_trust_control" && action == "UPDATE") {
            val invalidation = "typeof(OLD.id)='integer' AND OLD.id=1 AND NEW.id IS OLD.id AND " +
                "typeof(NEW.id)='integer' AND typeof(OLD.storageId)='text' AND " +
                "typeof(NEW.storageId)='text' AND NEW.storageId IS OLD.storageId AND " +
                "typeof(OLD.generation)='integer' AND OLD.generation>=0 AND OLD.generation<9223372036854775807 AND " +
                "typeof(NEW.generation)='integer' AND NEW.generation=OLD.generation+1 AND " +
                "typeof(NEW.dirty)='integer' AND NEW.dirty=1"
            "($active) AND NOT ($invalidation)"
        } else active
        return """
                CREATE TRIGGER IF NOT EXISTS replica_fence_${table}_${action.lowercase(java.util.Locale.ROOT)}
                BEFORE $action ON `$table` WHEN $blocked
                BEGIN SELECT RAISE(ABORT,'$ERROR'); END
            """.trimIndent()
    }

}
