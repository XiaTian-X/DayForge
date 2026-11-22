package com.dayforge.data.local

import android.database.Cursor
import com.dayforge.domain.model.isContractUuid

/** Current local dataset identity. A restriction only: never authentication or a commit proof.
 * Captured/compared only inside the caller's real existing Room transaction. The null path is
 * solely for original uninstrumented reference databases; production installs CountTrustRuntime.
 */
internal object LocalReplicaIdentity {
    fun readInTransaction(database: HabitDatabase): String? {
        check(database.inTransaction()) { "LOCAL_REPLICA_TRANSACTION_REQUIRED" }
        if (CountTrustRuntime.forDatabase(database) == null) return null
        return database.openHelper.writableDatabase.query(
            "SELECT CASE WHEN typeof(id)='integer' THEN id ELSE NULL END," +
                "CASE WHEN typeof(storageId)<>'text' THEN NULL WHEN length(CAST(storageId AS BLOB))<>36 THEN NULL ELSE storageId END," +
                "CASE WHEN typeof(generation)='integer' THEN generation ELSE NULL END," +
                "CASE WHEN typeof(dirty)='integer' THEN dirty ELSE NULL END FROM main.count_trust_control LIMIT 2"
        ).use { cursor ->
            check(cursor.moveToFirst() && cursor.columnCount == 4 &&
                cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER && cursor.getLong(0) == 1L &&
                cursor.getType(1) == Cursor.FIELD_TYPE_STRING &&
                cursor.getType(2) == Cursor.FIELD_TYPE_INTEGER && cursor.getLong(2) >= 0L &&
                cursor.getType(3) == Cursor.FIELD_TYPE_INTEGER && cursor.getLong(3) in 0L..1L) {
                "COUNT_TRUST_CONTROL_INVALID"
            }
            val storage = cursor.getString(1)
            check(isContractUuid(storage) && !cursor.moveToNext()) { "COUNT_TRUST_CONTROL_INVALID" }
            storage
        }
    }
    fun requireInTransaction(database: HabitDatabase, expected: String?, failure: String) {
        check(readInTransaction(database) == expected) { failure }
    }
}
