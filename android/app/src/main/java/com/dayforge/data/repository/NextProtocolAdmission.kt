package com.dayforge.data.repository

import android.database.Cursor
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
        val contracts = sql.query("SELECT id, challengeContract FROM next_sync_state").use { c ->
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
            // Include orphaned queues, journals, recovery candidates and future business tables.
            // Only schema bookkeeping and the outbox trigger control are not business data.
            val names = sql.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }.filterNot { it.startsWith("sqlite_") || it in setOf("room_master_table", "android_metadata", "sync_control") }
            for (table in names) {
                require(table.matches(Regex("[a-zA-Z_][a-zA-Z_0-9]*")))
                sql.query("SELECT 1 FROM \"$table\" LIMIT 1").use { c ->
                    check(!c.moveToFirst()) { "SYNC_CONTROLLED_REBUILD_REQUIRED" }
                }
            }
            NextRequestSql.requireOutboxEnabled(sql)
        }
    }
}
