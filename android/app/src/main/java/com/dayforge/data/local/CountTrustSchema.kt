package com.dayforge.data.local

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.Collections
import java.util.Locale
import java.util.UUID

/** Installation and validation of device-local count proof storage, never a root issuer.
 * Permanent business-row invalidation does not depend on the outbox suppression switch.
 */
internal object CountTrustSchema {
    const val VERSION = 19
    const val DIRTY_PREFIX = "count_trust_dirty_"
    val BUSINESS_TABLES: Set<String> = Collections.unmodifiableSet(linkedSetOf(
        "habits", "completions", "count_days", "timelogs", "metrics", "metric_logs",
        "habit_metric_links", "sync_outbox", "sync_entity_state", "sync_conflicts", "sync_control",
        "timer_command_outbox", "timer_segments", "timelog_day_allocations", "local_fact_submissions",
        "completion_metric_prompts", "one_time_transmissions", "next_recovery_state", "next_request_origins",
        "next_transmissions", "next_acceptances", "next_structural_dependencies", "next_structural_supersessions",
        "next_sync_state", "next_rejections", "next_challenge_rounds", "next_challenge_births", "next_challenge_state",
        "next_restart_materializations", "next_restart_plan_proofs", "next_config_imports", "next_config_import_payloads",
        "next_recovery_audits", "next_abandoned_requests", "next_recovery_audit_payloads", "next_replica_fence"
    ))
    val LOCAL_TABLES: Set<String> = Collections.unmodifiableSet(linkedSetOf("count_trust_control", "count_trust_root", "count_trust_nodes",
        "count_trust_routes", "count_command_results"))

    private val tableSql = linkedMapOf(
        "count_trust_control" to "CREATE TABLE `count_trust_control` (`id` INTEGER NOT NULL, `storageId` TEXT NOT NULL, `generation` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "count_trust_root" to "CREATE TABLE `count_trust_root` (`id` INTEGER NOT NULL, `binding` TEXT NOT NULL, `format` INTEGER NOT NULL, `validator` TEXT NOT NULL, `schemaHash` TEXT NOT NULL, `rootHash` TEXT NOT NULL, `rootHeight` INTEGER NOT NULL, `rootCount` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `generation` INTEGER NOT NULL, `mac` BLOB NOT NULL, PRIMARY KEY(`id`))",
        "count_trust_nodes" to "CREATE TABLE `count_trust_nodes` (`digest` TEXT NOT NULL, `body` BLOB NOT NULL, PRIMARY KEY(`digest`))",
        "count_trust_routes" to "CREATE TABLE `count_trust_routes` (`sourceTable` TEXT NOT NULL, `sourceKey` TEXT NOT NULL, `ownerUuid` TEXT, `entityUuid` TEXT, `requestId` TEXT, `businessDate` TEXT, `role` TEXT NOT NULL, `payloadHash` TEXT NOT NULL, PRIMARY KEY(`sourceTable`, `sourceKey`))",
        "count_command_results" to "CREATE TABLE `count_command_results` (`commandId` TEXT NOT NULL, `inputHash` TEXT NOT NULL, `factUuid` TEXT NOT NULL, `requestId` TEXT, `resultJson` TEXT NOT NULL, `state` TEXT NOT NULL, PRIMARY KEY(`commandId`))"
    )
    private val indexSql = linkedMapOf(
        "index_count_trust_routes_ownerUuid_businessDate" to "CREATE INDEX `index_count_trust_routes_ownerUuid_businessDate` ON `count_trust_routes` (`ownerUuid`, `businessDate`)",
        "index_count_trust_routes_entityUuid" to "CREATE INDEX `index_count_trust_routes_entityUuid` ON `count_trust_routes` (`entityUuid`)",
        "index_count_trust_routes_requestId" to "CREATE INDEX `index_count_trust_routes_requestId` ON `count_trust_routes` (`requestId`)"
    )
    private const val STORAGE_ID_VALID = "typeof(storageId)='text' AND length(storageId)=36 AND " +
        "storageId=lower(storageId) AND storageId NOT GLOB '*[^0-9a-f-]*' AND " +
        "length(replace(storageId,'-',''))=32 AND substr(storageId,9,1)='-' AND " +
        "substr(storageId,14,1)='-' AND substr(storageId,19,1)='-' AND substr(storageId,24,1)='-'"
    private const val CONTROL_VALID = "typeof(id)='integer' AND id=1 AND $STORAGE_ID_VALID AND " +
        "typeof(generation)='integer' AND generation>=0 AND typeof(dirty)='integer' AND dirty IN (0,1)"

    val dirtyTriggers: Map<String, String> = buildMap {
        for (table in BUSINESS_TABLES) for (action in listOf("INSERT", "UPDATE", "DELETE")) {
            val name = "$DIRTY_PREFIX${table}_${action.lowercase(Locale.ROOT)}"
            put(name, "CREATE TRIGGER `$name` AFTER $action ON `$table` BEGIN " +
                "SELECT CASE WHEN (SELECT count(*) FROM (SELECT id FROM count_trust_control LIMIT 2))<>1 OR " +
                "(SELECT count(*) FROM count_trust_control WHERE $CONTROL_VALID AND generation<9223372036854775807)<>1 " +
                "THEN RAISE(ABORT,'COUNT_TRUST_CONTROL_INVALID') END; " +
                "UPDATE count_trust_control SET generation=generation+1,dirty=1 WHERE id=1; END")
        }
    }

    // Only this build's fixed declarations are retained. Unknown actual SQL is never cached.
    private val expectedSql: Map<String, String> =
        Collections.unmodifiableMap(tableSql + indexSql + dirtyTriggers)
    private val schemaWhitespace = Regex("\\s+")
    private val normalizedExpected: Map<String, String> =
        Collections.unmodifiableMap(expectedSql.mapValues { normalized(it.value) })

    /** Exact new non-fence objects for the reviewed v18 recovery compatibility view. */
    fun ownedDefinitions(): Map<String, Pair<String, String>> =
        tableSql.mapValues { "table" to it.value } + indexSql.mapValues { "index" to it.value } +
            dirtyTriggers.mapValues { "trigger" to it.value }

    /** Only the additive migration calls this; a name collision must fail, never overwrite. */
    fun createTables(db: SupportSQLiteDatabase) {
        tableSql.values.forEach(db::execSQL)
        indexSql.values.forEach(db::execSQL)
    }

    /** New Room database or trusted migration only. Does not sign or install any root. */
    fun install(db: SupportSQLiteDatabase, version: Int = VERSION) {
        for (table in LOCAL_TABLES) db.query("SELECT count(*) FROM `$table`").use {
            check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 0L && !it.moveToNext()) {
                "COUNT_TRUST_INSTALL_NOT_EMPTY"
            }
        }
        db.execSQL("INSERT INTO count_trust_control(id,storageId,generation,dirty) VALUES(1,?,0,1)",
            arrayOf(UUID.randomUUID().toString()))
        dirtyTriggers.values.forEach(db::execSQL)
        validate(db, version)
    }

    /** Called before ordinary Room onOpen can repair legacy sync objects. No repair/enrollment. */
    fun preOpen(db: SupportSQLiteDatabase) {
        if (db.version >= VERSION) validate(db)
    }

    /** Exact owned DDL and a strict, bounded control-row check; never reads all business data. */
    fun validate(db: SupportSQLiteDatabase, version: Int = db.version) {
        require(version <= CountCommandV20Schema.VERSION) { "COMMAND_UNKNOWN_DATABASE_VERSION" }
        val expected = if (version == CountCommandV20Schema.VERSION) expectedSql +
            ("count_trust_control" to CountCommandControlSchema.declaredTable) else expectedSql
        val placeholders = expected.keys.joinToString(",") { "?" }
        val args: Array<Any> = (expected.keys.toList() + listOf(DIRTY_PREFIX.length, DIRTY_PREFIX)).toTypedArray()
        db.query("SELECT type,CASE WHEN length(CAST(name AS BLOB))<=256 THEN name ELSE NULL END," +
            "CASE WHEN typeof(sql)='text' THEN CASE WHEN length(CAST(sql AS BLOB))<=16384 THEN sql ELSE NULL END ELSE NULL END " +
            "FROM sqlite_master WHERE name IN ($placeholders) OR " +
            "(type='trigger' AND substr(name,1,?)=?) LIMIT ${expected.size + 1}", args).use { cursor ->
            val seen = hashSetOf<String>()
            while (cursor.moveToNext()) {
                check((0..2).all { cursor.getType(it) == Cursor.FIELD_TYPE_STRING }) { "COUNT_TRUST_SCHEMA_INVALID" }
                val name = cursor.getString(1)
                val type = when {
                    name in tableSql -> "table"
                    name in indexSql -> "index"
                    else -> "trigger"
                }
                check(seen.add(name) && name in expected && cursor.getString(0) == type &&
                    (if (name == "count_trust_control" && version == CountCommandV20Schema.VERSION)
                        CountCommandControlSchema.matchesTable(cursor.getString(2)) else matchesFixedDefinition(name, cursor.getString(2)))) { "COUNT_TRUST_SCHEMA_INVALID" }
            }
            check(seen == expected.keys) { "COUNT_TRUST_SCHEMA_INVALID" }
        }
        requireControl(db)
    }

    fun requireControl(db: SupportSQLiteDatabase) {
        db.query("SELECT (SELECT count(*) FROM (SELECT id FROM count_trust_control LIMIT 2)), " +
            "(SELECT count(*) FROM count_trust_control WHERE $CONTROL_VALID)").use {
            check(it.moveToFirst() && (0..1).all { column -> it.getType(column) == Cursor.FIELD_TYPE_INTEGER && it.getLong(column) == 1L } &&
                !it.moveToNext()) { "COUNT_TRUST_CONTROL_INVALID" }
        }
    }

    /** Explicit account-clear or replica-replacement lifecycle, already inside maintenance and
     * one transaction. Root/command state is not portable; never infer permission from this reset.
     */
    fun resetLocalInTransaction(db: SupportSQLiteDatabase) {
        check(db.inTransaction()) { "COUNT_TRUST_TRANSACTION_REQUIRED" }
        for (table in LOCAL_TABLES.filterNot { it == "count_trust_control" }) db.execSQL("DELETE FROM `$table`")
        db.execSQL("DELETE FROM count_trust_control")
        db.execSQL("INSERT INTO count_trust_control(id,storageId,generation,dirty) VALUES(1,?,0,1)",
            arrayOf(UUID.randomUUID().toString()))
    }

    internal fun matchesControlDefinition(actualSql: String, version: Int = VERSION): Boolean =
        if (version == CountCommandV20Schema.VERSION) CountCommandControlSchema.matchesTable(actualSql)
        else matchesFixedDefinition("count_trust_control", actualSql)

    private fun matchesFixedDefinition(name: String, actualSql: String): Boolean =
        actualSql == expectedSql.getValue(name) || normalized(actualSql) == normalizedExpected.getValue(name)

    // Owned DDL has no user text or arbitrary string payloads. Normalize only its formatting and
    // Room's CREATE IF NOT EXISTS prefix; punctuation, expressions and literal case remain bound.
    private fun normalized(sql: String): String = sql.trim().replace("IF NOT EXISTS ", "")
        .replace("`", "").replace(schemaWhitespace, "")
}
