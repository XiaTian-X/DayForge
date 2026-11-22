package com.dayforge.data.repository

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.local.CountCommandStorageInitialization
import com.dayforge.data.local.CountCommandV20Schema
import com.dayforge.data.local.entity.CountCommandRecoveryRootEntity
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess

/** Transaction participants only. Never clear, classify or translate existing data. */
internal object NextProtocolAdmission {
    suspend fun requireLegacyCreation(database: HabitDatabase) {
        check(database.inTransaction())
        check(!hasNextState(database)) {
            "OBJECT_CREATE_REOPEN_REQUIRED"
        }
    }

    suspend fun hasNextState(database: HabitDatabase): Boolean {
        check(database.inTransaction())
        return database.habitDao().hasProtocolNextState() || database.metricDao().hasProtocolNextState() ||
            database.completionDao().hasProtocolNextIntents() || database.syncOutboxDao().hasOneTimeIntents() ||
            database.syncOutboxDao().hasProtocolNextRecovery() || database.syncOutboxDao().hasProtocolNextRequests()
    }

    suspend fun requireRoundsOrEmpty(database: HabitDatabase, access: LocalSyncAccess?) {
        check(database.inTransaction())
        val sql = database.openHelper.writableDatabase
        val contracts = sql.query("SELECT id, challengeContract FROM main.next_sync_state").use { c ->
            buildList { while (c.moveToNext()) {
                require(c.getType(0) == Cursor.FIELD_TYPE_INTEGER && c.getLong(0) == 1L &&
                    c.getType(1) == Cursor.FIELD_TYPE_INTEGER && c.getLong(1) == 1L) { "SYNC_CHALLENGE_PROFILE_REQUIRED" }
                add(c.getLong(1))
            } }
        }
        require(contracts.size <= 1) { "SYNC_CURSOR_INVALID" }
        if (contracts.isNotEmpty()) {
            NextChallengeStore(database).activeInTransaction(requireNotNull(access) { "SYNC_DEVICE_PROOF_REQUIRED" })
        } else {
            // The exact device-local invalidation control is schema bookkeeping, not a
            // business row. A valid control does not authorize any cache, command or history.
            val control = CountTrustRecoveryControl.read(database)
            val primary = emptyGenesis(sql, control)
            // Include orphaned queues, journals, recovery candidates, every derived cache
            // and command result, and future business tables in the original empty check.
            val names = sql.query("SELECT name FROM main.sqlite_master WHERE type='table'").use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }.filterNot { it.startsWith("sqlite_") || it in setOf("room_master_table", "android_metadata", "sync_control") ||
                (it == "count_trust_control" && control != null) ||
                (it == "count_command_recovery_roots" && primary != null) }
            for (table in names) {
                require(table.matches(Regex("[a-zA-Z_][a-zA-Z_0-9]*")))
                sql.query("SELECT 1 FROM main.`$table` LIMIT 1").use { c ->
                    check(!c.moveToFirst()) { "SYNC_CONTROLLED_REBUILD_REQUIRED" }
                }
            }
            require(CountTrustRecoveryControl.read(database) == control) { "SYNC_CONTROLLED_REBUILD_REQUIRED" }
            NextRequestSql.requireOutboxEnabled(sql)
            if (primary != null) {
                val after = requireNotNull(emptyGenesis(sql, CountTrustRecoveryControl.read(database))) {
                    "SYNC_CONTROLLED_REBUILD_REQUIRED"
                }
                require(primary.control == after.control && CountCommandNativeRows.sameRoot(primary.root, after.root)) {
                    "SYNC_CONTROLLED_REBUILD_REQUIRED"
                }
            }
        }
    }

    private data class EmptyGenesis(val root: CountCommandRecoveryRootEntity, val control: CountCommandRootAnchor.Control)

    /** One authenticated initial singleton is local schema bookkeeping, not an empty-command
     * permission. All other command tables/results and all business/cache tables still pass
     * the original empty scan. Unowned and older databases retain that original scan exactly.
     */
    private fun emptyGenesis(sql: SupportSQLiteDatabase, control: CountTrustRecoveryControl?): EmptyGenesis? {
        if (control == null || sql.version != CountCommandV20Schema.VERSION) return null
        val fullControl = CountCommandRootAnchor.read(sql)
        require(fullControl.storage == control.storageId && fullControl.generation == control.generation &&
            fullControl.dirty == control.dirty) { "SYNC_CONTROLLED_REBUILD_REQUIRED" }
        val authenticated = CountCommandStorageInitialization.verifyRootAnchor(sql)
        // MAC, exact approved schema and mandatory anchor errors propagate; they are never
        // misses, initialization requests or permission to bind an account/repair the root.
        require(CountCommandRootAnchor.read(sql) == fullControl) { "SYNC_CONTROLLED_REBUILD_REQUIRED" }
        if (authenticated.header.mode != CountCommandRootCodec.Mode.EMPTY || authenticated.header.revision != 1L) return null
        return EmptyGenesis(authenticated.native(), fullControl)
    }
}
