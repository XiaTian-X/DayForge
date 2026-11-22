package com.dayforge.data.repository

import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.local.CountCommandV20Schema
import com.dayforge.data.local.CountTrustSchema
import com.dayforge.data.local.ReplicaSchemaSql
import java.util.Collections

/** The isolated metric store owns no production trust or command state. Validate the exact
 * table declarations, capture all effects, and refuse a baseline that needs a production
 * row validator. This inventory is private to the native journal and never authorizes SQL. */
internal object G0ProductionWitnessColumns {
    val tableNames: Set<String> = Collections.unmodifiableSet(CountCommandV20Schema.currentLocalTables)
    private val historical by lazy { CountTrustSchema.ownedDefinitions() }

    fun rejectChanges(changes: List<G0DomainJournal.Change>) {
        check(changes.none { it.table in tableNames }) { "G0_DOMAIN_PRODUCTION_STATE_CHANGED" }
    }

    fun read(sql: SupportSQLiteDatabase, expected: List<List<String?>>): Map<String, List<String>> {
        val version = sql.version
        val names = when (version) {
            CountTrustSchema.VERSION, CountCommandV20Schema.VERSION -> CountCommandV20Schema.localTables(version)
            // Older synthetic journal fixtures use their own helper version and the original catalog.
            else -> emptySet()
        }
        val definitions = expected.filter { it[0] == "main" && it[1] == "table" }.associateBy { it[2] }
        return names.associateWith { name ->
            val row = definitions[name]
            check(row != null && row[3] == name && matches(name, requireNotNull(row[4]), version)) {
                "G0_DOMAIN_PRODUCTION_SCHEMA"
            }
            // The caller has already matched the complete live schema to its separate reference.
            // Column order therefore comes from the actual, independently approved declaration.
            sql.query("SELECT * FROM main.`$name` LIMIT 1").use { cursor ->
                check(!cursor.moveToFirst()) { "G0_DOMAIN_PRODUCTION_STATE_REQUIRES_VALIDATOR" }
                cursor.columnNames.toList()
            }
        }
    }

    private fun matches(name: String, actual: String, version: Int): Boolean {
        if (name == "count_trust_control") return CountTrustSchema.matchesControlDefinition(actual, version)
        val declaration = if (name in CountTrustSchema.LOCAL_TABLES) historical.getValue(name).second
            else requireNotNull(CountCommandV20Schema.definitions.getValue(name).declaredSql)
        return ReplicaSchemaSql.tokens(actual) == ReplicaSchemaSql.tokens(declaration)
    }
}
