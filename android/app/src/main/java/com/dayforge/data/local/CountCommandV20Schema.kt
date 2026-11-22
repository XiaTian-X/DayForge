package com.dayforge.data.local

import java.util.Collections

/** Additive v20 objects only. Deliberately does NOT change CountTrustSchema's frozen v19
 * createTables/install/LOCAL_TABLES or the historical 18->19 migration. No runtime install,
 * migration callback, root signing or unknown-schema repair is supplied by this pure manifest.
 */
internal object CountCommandV20Schema {
    const val VERSION = 20
    const val COUNT_NODE_OWNERSHIP = "count-command-v1/dedicated-BLOB-pool/no-shared-count-nodes"
    val tables: Set<String> = Collections.unmodifiableSet(linkedSetOf(
        "count_command_intents", "count_command_status", "count_command_recovery_roots", "count_command_nodes"))
    /** Current consumers opt into the exact new inventory; historical v19 migration/view
     * continue using the frozen CountTrustSchema.LOCAL_TABLES/definitions unchanged.
     */
    fun localTables(version: Int): Set<String> = when (version) {
        in 0..19 -> CountTrustSchema.LOCAL_TABLES
        20 -> CountTrustSchema.LOCAL_TABLES + tables
        else -> error("COMMAND_UNKNOWN_DATABASE_VERSION")
    }
    val currentLocalTables: Set<String> get() = CountTrustSchema.LOCAL_TABLES + tables
    data class Definition(val type: String, val owner: String, val declaredSql: String?)
    val definitions: Map<String, Definition> = Collections.unmodifiableMap(buildMap {
        put("count_command_intents", Definition("table", "count_command_intents",
            "CREATE TABLE `count_command_intents` (`commandId` TEXT NOT NULL, `namespaceHash` TEXT NOT NULL, `inputHash` TEXT NOT NULL, `format` INTEGER NOT NULL, `action` TEXT NOT NULL, `intentJson` TEXT NOT NULL, PRIMARY KEY(`commandId`))"))
        put("count_command_status", Definition("table", "count_command_status",
            "CREATE TABLE `count_command_status` (`commandId` TEXT NOT NULL, `inputHash` TEXT NOT NULL, `revision` INTEGER NOT NULL, `execution` TEXT NOT NULL, `delivery` TEXT NOT NULL, `parentCommandId` TEXT, `childCommandId` TEXT, `previousPendingId` TEXT, `nextPendingId` TEXT, `stateJson` TEXT NOT NULL, `businessGenerationAtAdmission` INTEGER NOT NULL, PRIMARY KEY(`commandId`))"))
        put("count_command_recovery_roots", Definition("table", "count_command_recovery_roots",
            "CREATE TABLE `count_command_recovery_roots` (`id` INTEGER NOT NULL, `mode` TEXT NOT NULL, `storageId` TEXT NOT NULL, `datasetId` TEXT NOT NULL, `initializationSource` TEXT NOT NULL, `namespaceHash` TEXT, `namespaceJson` TEXT, `accountBindingHash` TEXT, `registrationPreviousHash` TEXT, `registrationInstallationId` TEXT, `format` INTEGER NOT NULL, `validator` TEXT NOT NULL, `schemaHash` TEXT NOT NULL, `catalogHash` TEXT, `catalogHeight` INTEGER, `catalogCount` INTEGER, `pendingHeadId` TEXT, `pendingTailId` TEXT, `pendingCount` INTEGER NOT NULL, `legacyCount` INTEGER, `legacyHash` TEXT, `revision` INTEGER NOT NULL, `mac` BLOB NOT NULL, PRIMARY KEY(`id`))"))
        put("count_command_nodes", Definition("table", "count_command_nodes",
            "CREATE TABLE `count_command_nodes` (`digest` TEXT NOT NULL, `body` BLOB NOT NULL, PRIMARY KEY(`digest`))"))
        for (table in tables - "count_command_recovery_roots") {
            put("sqlite_autoindex_${table}_1", Definition("index", table, null))
        }
        val active = "EXISTS(SELECT 1 FROM next_replica_fence WHERE id IS NOT 1 OR mode IS NOT 'closed')"
        for (table in tables) for (action in listOf("INSERT", "UPDATE", "DELETE")) {
            val name = "replica_fence_${table}_${action.lowercase(java.util.Locale.ROOT)}"
            put(name, Definition("trigger", table, "CREATE TRIGGER IF NOT EXISTS $name\n" +
                "BEFORE $action ON `$table` WHEN $active\n" +
                "BEGIN SELECT RAISE(ABORT,'SYNC_REPLICA_SWITCH_FENCED'); END"))
        }
    })

    /** Exact four-field schemaHash comparison view. Authenticate the old MAC using its result
     * before accepting any old root. This is not NextReplica's five-field typed storage view:
     * that separate view must preserve rootpage/identity/content and require new tables empty.
     */
    fun previousSchema(current: List<List<String?>>): List<List<String?>> {
        val seen = hashSetOf<String>()
        val previous = arrayListOf<List<String?>>()
        for (row in current) {
            require(row.size == 4 && row.take(3).all { it != null }) { "COMMAND_SCHEMA_ROW" }
            val name = requireNotNull(row[1]); val owner = requireNotNull(row[2])
            val expected = definitions[name]
            if (name == CountCommandControlSchema.TABLE) {
                require(row[0] == "table" && owner == name) { "COMMAND_CONTROL_SCHEMA_CHANGED" }
                previous += row.take(3) + CountCommandControlSchema.previousTable(requireNotNull(row[3]))
            } else if (name == CountCommandControlSchema.GUARD) {
                require(row[0] == "trigger" && owner == CountCommandControlSchema.TABLE) { "COMMAND_CONTROL_GUARD_CHANGED" }
                previous += row.take(3) + CountCommandControlSchema.previousGuard(requireNotNull(row[3]))
            } else if (expected == null) {
                require(owner !in tables) { "COMMAND_SCHEMA_EXTRA_OWNED_OBJECT" }
                previous += Collections.unmodifiableList(row.toList())
            } else {
                require(seen.add(name) && row[0] == expected.type && owner == expected.owner) { "COMMAND_SCHEMA_OBJECT" }
                val declared = expected.declaredSql
                val stored = declared?.replaceFirst("CREATE TRIGGER IF NOT EXISTS ", "CREATE TRIGGER ")
                require(row[3] == declared || row[3] == stored) { "COMMAND_SCHEMA_SQL_CHANGED" }
            }
        }
        require(current.count { it[1] == CountCommandControlSchema.TABLE } == 1 &&
            current.count { it[1] == CountCommandControlSchema.GUARD } == 1) { "COMMAND_CONTROL_SCHEMA_MISSING" }
        require(seen == definitions.keys) { "COMMAND_SCHEMA_MISSING_OBJECT" }
        // Preserve original sqlite_master row order/bytes. The caller uses original v19
        // JsonArray framing and hash; no normalization of any retained old object is allowed.
        return Collections.unmodifiableList(previous)
    }
}
