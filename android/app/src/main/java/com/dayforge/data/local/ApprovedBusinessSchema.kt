package com.dayforge.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/** Fixed structural approval, not business, account, root-MAC or row-content authority.
 * Optional platform objects may be absent. An object that exists is never exempt from its
 * full definition comparison, because inbound foreign keys can otherwise add DELETE effects.
 */
internal object ApprovedBusinessSchema {
    internal val optionalStatistics = setOf("sqlite_stat1", "sqlite_stat4")
    private val optional = optionalStatistics.map { "table.$it" }.toSet() + "table.android_metadata"

    /** Same present-object comparison used by matches, restricted to platform statistics.
     * A reserved name/owner with another object type is never an optional object.
     */
    internal fun statisticsMatch(actual: Map<String, ReplicaSqlSchema.SchemaObject>,
        expected: Map<String, ReplicaSqlSchema.SchemaObject>): Boolean =
        actual.all { (key, value) -> value.type == "table" && value.name in optionalStatistics &&
            value.owner == value.name && key == "table.${value.name}" && expected[key] == value }


    fun matches(actual: Map<String, ReplicaSqlSchema.SchemaObject>,
        expected: Map<String, ReplicaSqlSchema.SchemaObject>): Boolean =
        actual.keys - optional == expected.keys - optional && actual.all { (key, value) -> expected[key] == value }

    /** Caller must obtain the pure reference before opening its source write transaction.
     * Current schema/version are always checked in that transaction before its first DML.
     * No root, control-row, business-history or Room identity-row grant is inferred here.
     */
    suspend fun requireCleanupInTransaction(sql: SupportSQLiteDatabase, expected: ReplicaReferenceSchema) {
        check(sql.inTransaction()) { "COUNT_TRUST_CLEANUP_TRANSACTION_REQUIRED" }
        check(sql.version == expected.version && matches(ReplicaSqlSchema.read(sql), expected.objects)) {
            "COUNT_TRUST_CLEANUP_SCHEMA_CHANGED"
        }
    }
}
