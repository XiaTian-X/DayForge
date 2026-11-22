package com.dayforge.data.repository

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.local.CountTrustSchema

/** Frozen pre-candidate installer (source SHA fe72c662286099c747a3daee319a24edc4a92980ee9a346573f74482697545ee).
 * Independent differential oracle only; declarations and existing-definition no-op are unchanged.
 */
internal object FrozenReplicaFenceInstall1487 {
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
            for (action in listOf("INSERT", "UPDATE", "DELETE")) {
                db.execSQL(guardSql(table, action, active))
            }
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

    /** Exact new device-local guards, also used by the strict previous-version recovery view. */
    fun localTrustDefinitions(): Map<String, String> = buildMap {
        val active = "EXISTS(SELECT 1 FROM $TABLE WHERE id IS NOT 1 OR mode IS NOT 'closed')"
        for (table in CountTrustSchema.LOCAL_TABLES) for (action in listOf("INSERT", "UPDATE", "DELETE")) {
            put("replica_fence_${table}_${action.lowercase(java.util.Locale.ROOT)}", guardSql(table, action, active))
        }
    }

    /** The exact guard set temporarily removed by the dedicated replica replacement transaction. */
    fun businessAndLocalDefinitions(): Map<String, String> = buildMap {
        val active = "EXISTS(SELECT 1 FROM $TABLE WHERE id IS NOT 1 OR mode IS NOT 'closed')"
        for (table in CountTrustSchema.BUSINESS_TABLES - TABLE + CountTrustSchema.LOCAL_TABLES) {
            for (action in listOf("INSERT", "UPDATE", "DELETE")) {
                put("replica_fence_${table}_${action.lowercase(java.util.Locale.ROOT)}", guardSql(table, action, active))
            }
        }
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
