package com.dayforge.data.repository

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.SyncEntityStateEntity

/** One bounded chunk in the caller's transaction, never a cross-read proof cache. */
internal suspend fun acceptedFactRows(database: HabitDatabase, ids: List<String>): Map<String, SyncEntityStateEntity>? {
    check(database.inTransaction())
    val hashes = NextRequestSql.boundedRowHashes(database.openHelper.writableDatabase,
        "sync_entity_state", "entityUuid", ids, entityType = "activity_event") ?: return null
    // Null means the chunk exceeds the allocation budget: caller keeps the original per-row path.
    // Missing keys in a non-null map mean proven absence, not a skipped audit.
    val rows = database.syncOutboxDao().getStates("activity_event", ids)
    check(rows.map { it.entityUuid }.toSet() == hashes.filterValues { it != null }.keys &&
        rows.map { it.entityUuid }.distinct().size == rows.size)
    return rows.associateBy { row ->
        check(row.entityType == "activity_event" && NextRequestSql.entityStateHash(row) == hashes[row.entityUuid])
        row.entityUuid
    }
}
