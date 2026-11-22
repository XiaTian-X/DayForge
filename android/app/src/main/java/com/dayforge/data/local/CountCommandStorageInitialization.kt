package com.dayforge.data.local

import com.dayforge.domain.service.CountCommandCostTiming
import com.dayforge.domain.service.CountCommandCostTiming.Step

import android.database.Cursor
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.repository.CountCommandNativeRows
import com.dayforge.data.repository.CountCommandPendingRules
import com.dayforge.data.repository.CountCommandRootCodec
import com.dayforge.data.repository.CountCommandRootAnchor
import com.dayforge.data.repository.CountCommandSeal
import java.security.KeyStore
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Fixed storage initialization only; never an account or business execution authority.
 * Framework creation/upgrade or original whole-storage reset owns the real transaction and
 * permit. Existing v20 files call verifyMandatory only, which never creates keys or roots.
 */
internal object CountCommandStorageInitialization {
    fun initialize(sql: SupportSQLiteDatabase, permit: CountCommandInitializationPermit) {
        val source = permit.begin(sql)
        check(sql.inTransaction())
        CountTrustSchema.requireControl(sql)
        val storage = storage(sql)
        for (table in CountCommandV20Schema.tables) sql.query("SELECT 1 FROM `$table` LIMIT 1").use {
            require(!it.moveToFirst()) { "COMMAND_INITIALIZATION_NOT_EMPTY" }
        }
        val actual = schema(sql)
        val expected = permit.expectedSchema() ?: finalSchema(actual)
        if (source == CountCommandInitializationPermit.Source.AUTHORIZED_CLEAR_REPLACE) {
            require(actual.all { it in expected }) { "COMMAND_RESET_SCHEMA_CHANGED" }
            require((expected - actual.toSet()).all { row -> row[0] == "trigger" &&
                CountTrustOpenHelperFactory.isApprovedReplicaGuard(requireNotNull(row[1]), requireNotNull(row[3])) }) {
                "COMMAND_RESET_SCHEMA_CHANGED"
            }
        }
        val previous = CountCommandV20Schema.previousSchema(expected)
        permit.previousSchema()?.let { original ->
            require(previous == original) { "COMMAND_MIGRATION_CHANGED_OLD_SCHEMA" }
            require(CountCommandMigrationRows.fingerprint(sql, original, v20Control = true) == requireNotNull(permit.previousContents())) {
                "COMMAND_MIGRATION_CHANGED_OLD_ROWS"
            }
        }
        val legacy = CountCommandLegacyNative.fingerprint(sql)
        if (source == CountCommandInitializationPermit.Source.AUTHORIZED_CLEAR_REPLACE) require(legacy.first == 0L) { "COMMAND_RESET_LEGACY_REMAINS" }
        // Local dataset identity, not an anti-rollback counter. It changes only in this
        // trusted atomic initialization and survives all ordinary session/registration changes.
        val dataset = UUID.randomUUID().toString()
        val header = CountCommandRootCodec.Header(
            if (legacy.first == 0L) CountCommandRootCodec.Mode.EMPTY else CountCommandRootCodec.Mode.LEGACY_UNENROLLED,
            storage, dataset, CountCommandRootCodec.Source.valueOf(source.name), CountCommandPrimarySchema.hash(expected), null, null,
            CountCommandPendingRules.Frontier(null, null, 0), legacy.first.takeIf { it > 0 }, legacy.second.takeIf { legacy.first > 0 }, 1)
        val oldControl = CountCommandRootAnchor.read(sql)
        require(oldControl.hash == null) { "COMMAND_INITIALIZATION_ANCHOR_NOT_EMPTY" }
        val key = initializationKey()
        val signed = header.entity(Mac.getInstance("HmacSHA256").run { init(key); doFinal(CountCommandRootCodec.body(header)) })
        sql.execSQL("INSERT INTO count_command_recovery_roots(${CountCommandNativeRows.ROOT_COLUMNS.joinToString(",")}) " +
            "VALUES(${CountCommandNativeRows.ROOT_COLUMNS.joinToString(",") { "?" }})", CountCommandNativeRows.rootBindings(signed).toTypedArray())
        CountCommandRootAnchor.update(sql, oldControl, CountCommandRootAnchor.digest(signed))
        // Original clear/replace owns reinstatement of its exact guards and final full-schema
        // verification. Its private Tx permit remains pending; successful END is forbidden
        // until the factory's finishPrimaryReset verifies the resulting mandatory root.
        if (source == CountCommandInitializationPermit.Source.AUTHORIZED_CLEAR_REPLACE) return
        // Migration's private same-native framework transaction temporarily removed only
        // the exact old control UPDATE guard. Reinstall its precisely transformed original
        // bytes, then all new-table guards, before final old-row/schema/anchor verification.
        expected.single { it[1] == CountCommandControlSchema.GUARD }.let { guard ->
            if (actual.none { it[1] == CountCommandControlSchema.GUARD }) sql.execSQL(requireNotNull(guard[3]))
        }
        for ((name, definition) in CountCommandV20Schema.definitions) if (definition.type == "trigger" && actual.none { it[1] == name }) {
            sql.execSQL(requireNotNull(definition.declaredSql))
        }
        require(schema(sql) == expected) { "COMMAND_INITIALIZATION_FINAL_SCHEMA_CHANGED" }
        verifyMandatory(sql)
        permit.previousSchema()?.let { original ->
            require(CountCommandV20Schema.previousSchema(schema(sql)) == original) { "COMMAND_MIGRATION_CHANGED_OLD_SCHEMA" }
            require(CountCommandMigrationRows.fingerprint(sql, original, v20Control = true) == permit.previousContents()) {
                "COMMAND_MIGRATION_CHANGED_OLD_ROWS"
            }
        }
        permit.finish(sql, expected)
    }

    /** Safe at normal opening as well as inside a protected transaction. No result object is
     * retained as an execution/presentation permission; primary operations read their own root.
     */
    fun verifyMandatory(sql: SupportSQLiteDatabase) {
        val authenticated = verifyRootAnchor(sql)
        when (authenticated.header.mode) {
            CountCommandRootCodec.Mode.EMPTY, CountCommandRootCodec.Mode.LEGACY_UNENROLLED -> {
                for (table in listOf("count_command_intents", "count_command_status", "count_command_nodes")) {
                    sql.query("SELECT 1 FROM `$table` LIMIT 1").use { require(!it.moveToFirst()) { "COMMAND_INITIAL_ANCHOR_CONTENT_CHANGED" } }
                }
                val legacy = CountCommandLegacyNative.fingerprint(sql)
                if (authenticated.header.mode == CountCommandRootCodec.Mode.EMPTY) require(legacy.first == 0L)
                else require(authenticated.header.legacyCount == legacy.first && authenticated.header.legacyHash == legacy.second)
            }
            CountCommandRootCodec.Mode.ACCOUNT_BOUND_RETAINED ->
                com.dayforge.data.repository.CountCommandRetainedPartition.verify(sql, authenticated) { check(sql.isOpen) }
            CountCommandRootCodec.Mode.ACCOUNT_BOUND -> Unit // Root only; all point rows are verified by the current operation.
        }
    }

    /** SELECT-only final envelope check; does not scan legacy business results or publish a capability. */
    fun verifyRootAnchor(sql: SupportSQLiteDatabase,
        costs: CountCommandCostTiming.Frame? = null): CountCommandSeal.Authenticated {
        val actual = CountCommandCostTiming.measure(costs, Step.NATIVE_SCHEMA) { schema(sql) }
        CountCommandCostTiming.measure(costs, Step.NATIVE_SCHEMA_SHAPE) { CountCommandV20Schema.previousSchema(actual) }
        val root = CountCommandCostTiming.measure(costs, Step.NATIVE_ROOT) { CountCommandNativeRows(sql) { check(sql.isOpen) }.root() }
        val originalStorage = storage(sql)
        val hash = CountCommandCostTiming.measure(costs, Step.NATIVE_SCHEMA_HASH) { CountCommandPrimarySchema.hash(actual) }
        return CountCommandCostTiming.measure(costs, Step.NATIVE_ROOT_AUTH) {
            CountCommandSeal().authenticate(root, originalStorage, hash) {
                CountCommandCostTiming.measure(costs, Step.NATIVE_ANCHOR) { CountCommandRootAnchor.read(sql).hash }
            }
        }
    }

    /** Fixed complete target, not an attacker-supplied checksum. Existing owned new objects
     * must already match; only the declared missing new fence guards can be appended here.
     */
    private fun finalSchema(actual: List<List<String?>>): List<List<String?>> {
        val extra = CountCommandV20Schema.definitions.filter { (name, def) -> def.type == "trigger" && actual.none { it[1] == name } }
            .map { (name, def) -> listOf("trigger", name, def.owner,
                requireNotNull(def.declaredSql).replaceFirst("CREATE TRIGGER IF NOT EXISTS ", "CREATE TRIGGER ")) }
        return (actual.map { it.toList() } + extra).sortedWith(compareBy({ it[0] }, { it[1] }))
    }
    private fun schema(sql: SupportSQLiteDatabase): List<List<String?>> {
        CountCommandPrimarySchema.requireStatisticsPreflight(sql)
        return sql.query("SELECT type,name,tbl_name,sql FROM main.sqlite_master ORDER BY type,name").use { c ->
            buildList { while (c.moveToNext()) add((0..3).map { if (c.isNull(it)) null else c.getString(it) }) }
        }
    }
    private fun storage(sql: SupportSQLiteDatabase): String = sql.query("SELECT storageId FROM count_trust_control WHERE id=1 LIMIT 2").use {
        require(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_STRING)
        it.getString(0).also { _ -> require(!it.moveToNext()) }
    }
    private fun initializationKey(): SecretKey = (KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        .getKey(CountCommandSeal.ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(CountCommandSeal.ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setDigests(KeyProperties.DIGEST_SHA256).build()); generateKey()
    }

}
