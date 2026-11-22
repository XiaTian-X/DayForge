package com.dayforge.data.repository

import android.content.Context
import android.database.sqlite.SQLiteTransactionListener
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.dayforge.data.local.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray

/** Fixed count-T0/T1 and double-metric T0/T1/T2 native END checkpoints only. No arbitrary SQL callback/handle escapes.
 * The same native identity is retained below the actual controlled factory. Native trigger
 * execution is not inferred from these counters: recovery checks full facts/source/origins.
 */
internal class CountCommandProcessNativeSpy(private val protocol: CountCommandProcessProtocol,
    private val delegateFactory: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory()) : SupportSQLiteOpenHelper.Factory {
    private data class Tx(var intents: Int = 0, var facts: Int = 0, var outcomes: Int = 0,
        var metricFacts: Int = 0, var statusUpdates: Int = 0, var rootUpdates: Int = 0,
        val successful: MutableList<Boolean> = arrayListOf(false), var childRolledBack: Boolean = false)
    private val transaction = ThreadLocal<Tx?>()
    private var armed = false
    private var hits = 0
    private var metricParent: CountCommandReference? = null
    fun armMetrics(parent: CountCommandReference) {
        check(!armed && hits == 0 && protocol.phase == CountCommandProcessProtocol.Phase.PREPARE)
        check(protocol.case in METRIC_CASES)
        metricParent = parent; armed = true
    }
    fun armCount() {
        check(!armed && hits == 0 && protocol.phase == CountCommandProcessProtocol.Phase.PREPARE)
        check(protocol.case in COUNT_CASES); armed = true
    }
    fun armRetained() {
        check(!armed && hits == 0 && protocol.phase == CountCommandProcessProtocol.Phase.PREPARE)
        check(protocol.case in CountCommandProcessProtocol.RETAINED_CASES && protocol.case != CountCommandProcessProtocol.Case.POST_GENERATION_PERSIST)
        armed = true
    }
    fun build(context: Context): HabitDatabase {
        check(context.packageName == "com.dayforge.testbed")
        val owner = CountTrustOpenHelperFactory(context.applicationContext, this)
        return Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
            .addMigrations(FactTimeMigration, OneTimePreparationMigration, CompletionFollowUpMigration, OneTimeTransmissionMigration,
                ObjectAppearanceMigration, PlanMetadataMigration, NextRecoveryMigration, NextRequestMigration, NextAcceptanceMigration,
                NextStructuralCausalMigration, NextSyncStateMigration, CountDayMigration, NextChallengeMigration, NextRestartMigration,
                NextConfigImportMigration, NextAbandonmentMigration, NextReplicaFenceMigration, CountTrustMigration, CountCommandMigration)
            .openHelperFactory(owner).addCallback(SyncSchemaCallback).build().also { CountTrustRuntime.attach(it, owner) }
    }

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        var original: SupportSQLiteDatabase? = null
        var adapted: SupportSQLiteDatabase? = null
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase {
            if (original !== raw) {
                original = raw
                adapted = object : SupportSQLiteDatabase by raw {
                    private fun executed(sql: String) {
                        if (!armed) return
                        val tx = transaction.get() ?: return
                        val normalized = sql.trim().lowercase(java.util.Locale.ROOT).replace("`", "").replace("\"", "")
                        if (UPDATE.find(normalized)?.groupValues?.get(1) == "count_command_status") tx.statusUpdates++
                        if (UPDATE.find(normalized)?.groupValues?.get(1) == "count_command_recovery_roots") tx.rootUpdates++
                        val table = INSERT.find(normalized)?.groupValues?.get(1) ?: return
                        when (table) {
                            "count_command_intents" -> tx.intents++
                            "completions" -> tx.facts++
                            "count_command_results" -> tx.outcomes++
                            "metric_logs" -> tx.metricFacts++
                        }
                    }
                    override fun execSQL(sql: String) { raw.execSQL(sql); executed(sql) }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) { raw.execSQL(sql, bindArgs); executed(sql) }
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val statement = raw.compileStatement(sql)
                        return object : SupportSQLiteStatement by statement {
                            override fun execute() { statement.execute(); executed(sql) }
                            override fun executeInsert(): Long = statement.executeInsert().also { executed(sql) }
                            override fun executeUpdateDelete(): Int = statement.executeUpdateDelete().also { executed(sql) }
                        }
                    }
                    private fun begin(body: () -> Unit) {
                        body()
                        val tx = transaction.get()
                        if (tx == null) transaction.set(Tx()) else tx.successful.add(false)
                    }
                    override fun beginTransaction() = begin { raw.beginTransaction() }
                    override fun beginTransactionNonExclusive() = begin { raw.beginTransactionNonExclusive() }
                    override fun beginTransactionReadOnly() = begin { raw.beginTransactionReadOnly() }
                    override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) =
                        begin { raw.beginTransactionWithListener(transactionListener) }
                    override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) =
                        begin { raw.beginTransactionWithListenerNonExclusive(transactionListener) }
                    override fun beginTransactionWithListenerReadOnly(transactionListener: SQLiteTransactionListener) =
                        begin { raw.beginTransactionWithListenerReadOnly(transactionListener) }
                    override fun setTransactionSuccessful() {
                        raw.setTransactionSuccessful()
                        transaction.get()?.let { it.successful[it.successful.lastIndex] = true }
                    }
                    private fun reference(): CountCommandReference {
                        val intent = raw.query("SELECT commandId,inputHash,namespaceHash FROM count_command_intents ORDER BY rowid DESC LIMIT 1").use {
                            check(it.moveToFirst())
                            Triple(it.getString(0), it.getString(1), it.getString(2)).also { _ -> check(!it.moveToNext()) }
                        }
                        val dataset = raw.query("SELECT datasetId FROM count_command_recovery_roots WHERE id=1 LIMIT 2").use {
                            check(it.moveToFirst()); it.getString(0).also { _ -> check(!it.moveToNext()) }
                        }
                        return CountCommandReference(intent.first, intent.second, intent.third, dataset)
                    }
                    private fun metricReference(selected: CountCommandReference, delivered: Boolean) {
                        val parent = requireNotNull(metricParent)
                        check(selected.commandId != parent.commandId && selected.datasetId == parent.datasetId &&
                            selected.namespaceHash == parent.namespaceHash)
                        val input = raw.query("SELECT intentJson FROM count_command_intents WHERE commandId=? LIMIT 2", arrayOf(selected.commandId)).use {
                            check(it.moveToFirst())
                            val bytes = it.getString(0).toByteArray(Charsets.UTF_8)
                            check(!it.moveToNext()); CountCommandRecords.Intent.decode(bytes, bytes.size) {}
                        }
                        check(input.commandId == selected.commandId && input.inputHash == selected.inputHash &&
                            input.action == CountCommandRecords.Action.LINKED_METRICS)
                        val body = input.body().getValue("input").jsonObject
                        check(CountCommandRecords.uuid(body, "parent_command_id") == parent.commandId &&
                            body.getValue("metrics").jsonArray.size == 2)
                        raw.query("SELECT commandId,parentCommandId,childCommandId,execution,delivery FROM count_command_status WHERE commandId IN (?,?) ORDER BY commandId",
                            arrayOf(parent.commandId, selected.commandId)).use { cursor ->
                            var rows = 0
                            while (cursor.moveToNext()) {
                                rows++
                                if (cursor.getString(0) == parent.commandId) check(cursor.getString(2) == selected.commandId)
                                else check(cursor.getString(1) == parent.commandId)
                                if (delivered) check(cursor.getString(3) == "APPLIED" && cursor.getString(4) == "DELIVERED")
                            }
                            check(rows == 2)
                        }
                    }
                    override fun endTransaction() {
                        val tx = transaction.get()
                        if (tx == null) { raw.endTransaction(); return }
                        val outer = tx.successful.size == 1
                        val successful = tx.successful.last()
                        val stageMatches = armed && outer && successful && !tx.childRolledBack && when (protocol.case) {
                            CountCommandProcessProtocol.Case.PRE_T0_END, CountCommandProcessProtocol.Case.POST_T0_END ->
                                tx.intents == 1 && tx.facts == 0 && tx.outcomes == 0
                            CountCommandProcessProtocol.Case.PRE_T1_END, CountCommandProcessProtocol.Case.POST_T1_END ->
                                tx.intents == 0 && tx.facts == 1 && tx.outcomes == 1
                            CountCommandProcessProtocol.Case.POST_METRIC_T0_END ->
                                tx.intents == 1 && tx.facts == 0 && tx.metricFacts == 0 && tx.outcomes == 0
                            CountCommandProcessProtocol.Case.POST_METRIC_T1_END ->
                                tx.intents == 0 && tx.facts == 0 && tx.metricFacts == 2 && tx.outcomes == 1
                            CountCommandProcessProtocol.Case.POST_T2_END ->
                                tx.intents == 0 && tx.facts == 0 && tx.metricFacts == 0 && tx.outcomes == 0 && tx.statusUpdates >= 2
                            CountCommandProcessProtocol.Case.POST_GENERATION_PERSIST -> false
                            CountCommandProcessProtocol.Case.POST_RETAINED_ENROLL_END ->
                                tx.rootUpdates == 1 && tx.intents == 0 && tx.facts == 0 && tx.metricFacts == 0 && tx.outcomes == 0
                            CountCommandProcessProtocol.Case.POST_INDEPENDENT_T0_END ->
                                tx.intents == 1 && tx.facts == 0 && tx.metricFacts == 0 && tx.outcomes == 0
                            CountCommandProcessProtocol.Case.POST_INDEPENDENT_T1_END ->
                                tx.intents == 0 && tx.facts == 0 && tx.metricFacts == 1 && tx.outcomes == 1
                            CountCommandProcessProtocol.Case.POST_INDEPENDENT_T2_END ->
                                tx.intents == 0 && tx.facts == 0 && tx.metricFacts == 0 && tx.outcomes == 0 && tx.statusUpdates == 1
                        }
                        val before = protocol.case in setOf(CountCommandProcessProtocol.Case.PRE_T0_END,
                            CountCommandProcessProtocol.Case.PRE_T1_END)
                        val enrollment = stageMatches && protocol.case == CountCommandProcessProtocol.Case.POST_RETAINED_ENROLL_END
                        val enrolledBefore = if (enrollment) CountCommandNativeRows(raw) {}.root().also {
                            check(it.mode == "ACCOUNT_BOUND_RETAINED" && it.format == 3 && it.revision == 2L)
                            check(it.catalogCount == 0L && it.pendingCount == 0L && it.legacyCount == 1L)
                        } else null
                        val selected = if (stageMatches && !enrollment) reference().also {
                            if (protocol.case in METRIC_CASES) metricReference(it, protocol.case == CountCommandProcessProtocol.Case.POST_T2_END)
                        } else null
                        if (selected != null && before) {
                            check(raw.inTransaction()); armed = false; hits++
                            protocol.checkpoint(selected, hits, observedOuterCommitBoundary = successful)
                        }
                        try {
                            raw.endTransaction()
                            if (enrolledBefore != null) {
                                check(!raw.inTransaction())
                                val after = CountCommandNativeRows(raw) {}.root()
                                check(CountCommandNativeRows.sameRoot(enrolledBefore, after))
                                armed = false; hits++
                                protocol.checkpointEnrollment(after.storageId, after.datasetId, requireNotNull(after.legacyCount),
                                    requireNotNull(after.legacyHash), after.revision, hits, actualOuterEnd = successful)
                            }
                            if (selected != null) {
                                check(!raw.inTransaction() && reference() == selected)
                                if (protocol.case in METRIC_CASES)
                                    metricReference(selected, protocol.case == CountCommandProcessProtocol.Case.POST_T2_END)
                                if (protocol.case in setOf(CountCommandProcessProtocol.Case.POST_T1_END,
                                        CountCommandProcessProtocol.Case.POST_METRIC_T1_END, CountCommandProcessProtocol.Case.POST_T2_END,
                                        CountCommandProcessProtocol.Case.POST_INDEPENDENT_T1_END, CountCommandProcessProtocol.Case.POST_INDEPENDENT_T2_END)) {
                                    raw.query("SELECT commandId FROM count_command_results WHERE commandId=? LIMIT 2", arrayOf(selected.commandId)).use {
                                        check(it.moveToFirst() && it.getString(0) == selected.commandId && !it.moveToNext())
                                    }
                                }
                                armed = false; hits++
                                protocol.checkpoint(selected, hits, observedOuterCommitBoundary = successful)
                            }
                        } finally {
                            if (outer) transaction.remove()
                            else {
                                tx.successful.removeAt(tx.successful.lastIndex)
                                // An unsuccessful nested native transaction makes its outer
                                // transaction ineligible for the requested commit checkpoint.
                                if (!successful) tx.childRolledBack = true
                            }
                        }
                    }
                }
            }
            return requireNotNull(adapted)
        }
        val parent = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(parent.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
            override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(wrap(db), oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onDowngrade(wrap(db), oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = parent.onOpen(wrap(db))
            override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
        }
        val helper = delegateFactory.create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase get() = wrap(helper.writableDatabase)
            override val readableDatabase get() = wrap(helper.readableDatabase)
        }
    }
    companion object {
        val METRIC_CASES = setOf(CountCommandProcessProtocol.Case.POST_METRIC_T0_END,
            CountCommandProcessProtocol.Case.POST_METRIC_T1_END, CountCommandProcessProtocol.Case.POST_T2_END)
        val COUNT_CASES = setOf(CountCommandProcessProtocol.Case.PRE_T0_END, CountCommandProcessProtocol.Case.POST_T0_END,
            CountCommandProcessProtocol.Case.PRE_T1_END, CountCommandProcessProtocol.Case.POST_T1_END)
        private val UPDATE = Regex("^update\\s+(?:main\\.)?([a-z_]+)\\s+set\\s")
        private val INSERT = Regex("^insert(?:\\s+or\\s+\\w+)?\\s+into\\s+(?:main\\.)?([a-z_]+)(?:\\s|\\()")
    }
}
