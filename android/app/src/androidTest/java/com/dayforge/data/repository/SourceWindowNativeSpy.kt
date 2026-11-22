package com.dayforge.data.repository

import android.content.Context
import android.database.Cursor
import android.database.CursorWrapper
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.dayforge.data.api.SYNC_REQUEST_LIMIT
import com.dayforge.data.local.*
import java.util.Locale
import kotlinx.coroutines.asContextElement
import kotlin.coroutines.CoroutineContext

/** Test-only native factory installed BELOW CountTrustOpenHelperFactory. It records only enum
 * categories/counters: no SQL text, bind values, payload, account or token escapes into records.
 * Delegation preserves the same SQLite/Room connection and does not implement an alternate DB.
 */
internal class SourceWindowNativeSpy(private val utf16: Boolean = false, private val unknownPath: Boolean = false) : SupportSQLiteOpenHelper.Factory {
    enum class Kind {
        JOURNAL_METADATA_SCALAR, JOURNAL_SOURCE_HEADERS_BOUNDED, JOURNAL_HEADERS_UNBOUNDED,
        JOURNAL_CELLS_SCALAR, JOURNAL_CELLS_FULL,
        SOURCE_ROW_SIZE_PREFLIGHT, SOURCE_ROW_FULL, SOURCE_IDS_BOUNDED, SCHEMA_SNAPSHOT, OTHER_QUERY
    }
    data class Snapshot(val events: List<Kind>, val overflow: Boolean) {
        fun count(kind: Kind) = events.count { it == kind }
        fun first(kind: Kind) = events.indexOf(kind)
    }
    private val events = arrayListOf<Kind>()
    private var active = false
    private var overflow = false
    @Volatile private var native: SupportSQLiteDatabase? = null
    /** Fixed faults used only by the closed COUNT-add gate. They run below the production
     * wrapper after the ACTUAL original origin INSERT, never from expected journal values. */
    enum class CountAddFault { HEADER_OPERATION, SOURCE_SIDE, SOURCE_PADDING, SOURCE_CELL_TYPE,
        SOURCE_CELLS_MISSING, HUGE_SOURCE_CELL, SEQUENCE_AFTER_METADATA, EXTRA_HABIT_NOOP, DEFAULT_ZONE_AFTER_RATE, END_AFTER_COMMIT }
    private var countAddFault: CountAddFault? = null
    private var countAddTouched = false
    private var countAddLateSequence = false
    private var countAddUnknownEnd = false
    @Synchronized fun armCountAddFault(fault: CountAddFault) {
        check(countAddFault == null && !active)
        countAddFault = fault; countAddTouched = false
    }
    @Synchronized fun countAddFaultTouched() = countAddTouched
    @Synchronized private fun afterCountAddOrigin(db: SupportSQLiteDatabase) {
        val fault = countAddFault ?: return
        countAddFault = null
        check(db.inTransaction())
        begin()
        when (fault) {
            CountAddFault.HEADER_OPERATION -> damageLatestSourceEvidence(Fault.HEADER_OPERATION)
            CountAddFault.SOURCE_SIDE -> damageLatestSourceEvidence(Fault.SOURCE_SIDE)
            CountAddFault.SOURCE_PADDING -> damageLatestSourceEvidence(Fault.SOURCE_PADDING)
            CountAddFault.SOURCE_CELL_TYPE -> damageLatestSourceEvidence(Fault.SOURCE_CELL_TYPE)
            CountAddFault.SOURCE_CELLS_MISSING -> damageLatestSourceEvidence(Fault.SOURCE_CELLS_MISSING)
            CountAddFault.HUGE_SOURCE_CELL -> {
                val column = CountTrustJournal.COLUMNS.getValue("sync_outbox").indexOf("payloadJson")
                check(column >= 0)
                db.execSQL("UPDATE temp.count_trust_journal_cells SET c$column=? WHERE side=1 AND seq=(" +
                    "SELECT max(seq) FROM temp.count_trust_journal_events WHERE tableName='sync_outbox')",
                    arrayOf("x".repeat(SYNC_REQUEST_LIMIT * 3)))
            }
            CountAddFault.SEQUENCE_AFTER_METADATA -> countAddLateSequence = true
            CountAddFault.EXTRA_HABIT_NOOP -> db.execSQL("UPDATE main.habits SET activityRate=activityRate WHERE id=(" +
                "SELECT habitId FROM main.completions ORDER BY id DESC LIMIT 1)")
            CountAddFault.DEFAULT_ZONE_AFTER_RATE -> {
                val current = java.time.ZoneId.systemDefault().id
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(if (current == "Etc/UTC") "Etc/GMT-1" else "Etc/UTC"))
            }
            CountAddFault.END_AFTER_COMMIT -> countAddUnknownEnd = true
        }
        if (fault != CountAddFault.SEQUENCE_AFTER_METADATA && fault != CountAddFault.END_AFTER_COMMIT)
            countAddTouched = true
    }

    private var preparationEndArmed = false
    private var preparationControlTouched = false
    private var preparationEndTouched = false
    @Volatile private var preparationEndThread: Thread? = null
    /** Fixed COUNT read/empty-selection fault: arm before A, or only after B to select C.
     * The first exact kernel control query selects this actual transaction's thread;
     * a different Room invalidation transaction cannot consume its END fault. */
    @Synchronized fun armCountAddPreparationEndFault() {
        check(!preparationEndArmed && preparationEndThread == null)
        preparationEndArmed = true; preparationControlTouched = false; preparationEndTouched = false
    }
    @Synchronized fun countAddPreparationControlTouched() = preparationControlTouched
    @Synchronized fun countAddPreparationEndFaultTouched() = preparationEndTouched

    /** Route observations only, not a substitute for the production complete journal.
     * Count only actual successful explicit native statements and outer native END events;
     * no SQL/bind/body/identity is retained and no production callback is proxied. */
    data class CountAddTransactionObservation(val id: Long, val controlReads: Int,
        val completionInserts: Int, val originInserts: Int,
        val explicitBusinessWrites: Int, val knownEnd: Boolean)
    private class CountAddTransactionFrame(val id: Long, val cohort: Long) {
        var controlReads = 0; var completionInserts = 0; var originInserts = 0; var writes = 0
    }
    private val countAddFrame = ThreadLocal<CountAddTransactionFrame?>()
    private val countAddCohort = ThreadLocal<Long?>()
    private val countAddTransactions = arrayListOf<CountAddTransactionObservation>()
    private val countAddOpenFrames = mutableSetOf<Long>()
    @Volatile private var countAddTransactionsArmed = false
    @Volatile private var countAddActiveCohort = 0L
    private var countAddNextId = 0L
    private var closedCountEffectReads = 0
    private var closedCountEffectHeaders = 0
    private val closedCountHeaderQuery = "select case when typeof(seq)='integer' then seq else null end,case when typeof(tablename)='text' and length(cast(tablename as blob))<=128 then tablename else null end,case when typeof(operation)='text' and length(cast(operation as blob))<=12 then operation else null end from temp.count_trust_journal_events where seq>? order by seq limit ?"
    /** Counts actual native query attempts, not successful verification or commits. */
    @Synchronized fun countAddClosedEffectReads(): Int = closedCountEffectReads
    /** One actual bounded-header query per entered 4/6-effect verification, distinct from
     * its individual OLD/NEW cell queries. Full domain/END assertions prove success. */
    @Synchronized fun countAddClosedEffectBarriers(): Int = closedCountEffectHeaders

    /** Fixed late faults AFTER the first full business verification's real control cursor
     * closes. No general SQL/handle escapes. In particular, TEMP evidence damage need not
     * advance the business event sequence, so a zero-tail hint alone must not certify it. */
    enum class CountAddVerifiedPrefixFault { BUSINESS_NOOP, JOURNAL_HEADER, JOURNAL_CELL }
    private var verifiedPrefixFault: CountAddVerifiedPrefixFault? = null
    private var verifiedPrefixStart = 0
    private var verifiedPrefixTouched = false
    @Synchronized fun armCountAddVerifiedPrefixFault(fault: CountAddVerifiedPrefixFault) {
        check(verifiedPrefixFault == null)
        verifiedPrefixFault = fault; verifiedPrefixStart = closedCountEffectHeaders; verifiedPrefixTouched = false
    }
    @Synchronized fun countAddVerifiedPrefixTouched(): Boolean = verifiedPrefixTouched
    @Synchronized private fun verifiedPrefixCursor(query: String, cursor: Cursor): Cursor {
        if (verifiedPrefixFault == null || closedCountEffectHeaders != verifiedPrefixStart + 1 ||
            query.trim().lowercase(Locale.ROOT) != "select storageid,generation,dirty from count_trust_control where id=1") return cursor
        val fault = requireNotNull(verifiedPrefixFault)
        verifiedPrefixFault = null
        return object : CursorWrapper(cursor) {
            private var fired = false
            override fun close() {
                super.close()
                if (fired) return
                fired = true
                val db = requireNotNull(native); check(db.inTransaction())
                when (fault) {
                    CountAddVerifiedPrefixFault.BUSINESS_NOOP -> db.execSQL(
                        "UPDATE main.habits SET activityRate=activityRate WHERE id=(SELECT habitId FROM main.completions ORDER BY id DESC LIMIT 1)")
                    CountAddVerifiedPrefixFault.JOURNAL_HEADER -> damageLatestSourceEvidence(Fault.HEADER_OPERATION)
                    CountAddVerifiedPrefixFault.JOURNAL_CELL -> damageLatestSourceEvidence(Fault.SOURCE_CELL_TYPE)
                }
                synchronized(this@SourceWindowNativeSpy) { verifiedPrefixTouched = true }
            }
        }
    }
    /** This pure observation marker follows only the test's real operation into Room.
     * Independently launched invalidation/background work cannot supply its A evidence. */
    @Synchronized fun startCountAddTransactionObservation(): CoroutineContext {
        check(!countAddTransactionsArmed && countAddOpenFrames.isEmpty())
        countAddTransactions.clear(); countAddActiveCohort++; countAddTransactionsArmed = true
        return countAddCohort.asContextElement(countAddActiveCohort)
    }
    @Synchronized fun finishCountAddTransactionObservation(): List<CountAddTransactionObservation> {
        check(countAddTransactionsArmed)
        countAddTransactionsArmed = false
        check(countAddOpenFrames.isEmpty()) { "COUNT_ADD_TEST_NATIVE_END_NOT_OBSERVED" }
        return java.util.Collections.unmodifiableList(countAddTransactions.toList())
    }
    private fun currentCountAddFrame(): CountAddTransactionFrame? = countAddFrame.get()?.takeIf {
        countAddTransactionsArmed && it.cohort == countAddActiveCohort && countAddCohort.get() == it.cohort
    }
    fun currentCountAddTransactionId(): Long? = currentCountAddFrame()?.id
    private fun beginCountAddTransaction(body: () -> Unit) {
        val outer = currentCountAddFrame() == null
        body()
        synchronized(this) {
            if (countAddTransactionsArmed && outer && countAddCohort.get() == countAddActiveCohort) {
                val frame = CountAddTransactionFrame(++countAddNextId, countAddActiveCohort)
                countAddFrame.set(frame); check(countAddOpenFrames.add(frame.id))
            }
        }
    }
    private fun endCountAddTransaction(known: Boolean) {
        val frame = currentCountAddFrame() ?: return
        synchronized(this) {
            countAddTransactions += CountAddTransactionObservation(frame.id, frame.controlReads,
                frame.completionInserts, frame.originInserts, frame.writes, known)
            check(countAddOpenFrames.remove(frame.id))
        }
        countAddFrame.remove()
    }
    private fun countAddBusinessDml(text: String): Pair<String, String>? {
        val normalized = text.replace("`", "").trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        val match = Regex("^(insert(?: or [a-z]+)? into|replace into|update(?: or [a-z]+)?|delete from) (?:main\\.)?([a-z_]+)(?:[ (]|$)").find(normalized)
            ?: return null
        return (match.groupValues[1] to match.groupValues[2]).takeIf { it.second in CountTrustSchema.BUSINESS_TABLES }
    }
    private fun countAddDml(text: String) {
        val frame = currentCountAddFrame() ?: return
        val (operation, table) = countAddBusinessDml(text) ?: return
        frame.writes++
        if (operation.startsWith("insert") && table == "completions") frame.completionInserts++
        if (operation.startsWith("insert") && table == "next_request_origins") frame.originInserts++
    }

    enum class LateSourceField { LAST_ERROR, PAYLOAD_JSON }
    data class RateFaultResult(val injected: Boolean, val queries: Snapshot)
    @Volatile private var lateField: LateSourceField? = null
    private var rateInjected = false
    private var regressSequence = false
    private var sequenceRegressionTouched = false

    enum class MetricSourceFault { MAIN_INDEX }
    private var metricSourceFault: MetricSourceFault? = null
    private var metricSourceTouched = false
    @Synchronized fun armMetricSourceBoundary(fault: MetricSourceFault) {
        check(metricSourceFault == null); metricSourceFault = fault; metricSourceTouched = false
    }
    @Synchronized fun metricSourceBoundaryTouched(): Boolean = metricSourceTouched

    /** Same current production builder, with only this test's native observer beneath the gate. */
    fun build(context: Context, queryCallback: RoomDatabase.QueryCallback? = null): HabitDatabase {
        if (utf16) {
            check(context.packageName == "com.dayforge.testbed")
            val file = context.getDatabasePath("habit_database")
            if (!file.exists()) {
                val directory = requireNotNull(file.parentFile)
                check(directory.isDirectory || directory.mkdirs())
                // Public SDK API: avoid Android's eager localized table until encoding is set.
                // This is a brand-new isolated fixture, never conversion of an existing file.
                SQLiteDatabase.openDatabase(file.absolutePath, null,
                    SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { empty ->
                    empty.execSQL("PRAGMA encoding='UTF-16le'")
                    empty.execSQL("CREATE TABLE android_metadata(locale TEXT)")
                    empty.query("android_metadata", arrayOf("locale"), null, null, null, null, null).use { check(!it.moveToFirst()) }
                    empty.rawQuery("PRAGMA encoding", null).use {
                        check(it.moveToFirst() && it.getString(0) == "UTF-16le" && !it.moveToNext())
                    }
                }
            }
        }
        val factory = CountTrustOpenHelperFactory(context.applicationContext, this)
        return Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
            .addMigrations(FactTimeMigration, OneTimePreparationMigration, CompletionFollowUpMigration, OneTimeTransmissionMigration, ObjectAppearanceMigration, PlanMetadataMigration, NextRecoveryMigration, NextRequestMigration, NextAcceptanceMigration, NextStructuralCausalMigration, NextSyncStateMigration, CountDayMigration, NextChallengeMigration, NextRestartMigration, NextConfigImportMigration, NextAbandonmentMigration, NextReplicaFenceMigration, CountTrustMigration, CountCommandMigration)
            .openHelperFactory(factory).addCallback(SyncSchemaCallback)
            .apply { if (queryCallback != null) setQueryCallback(object : RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                    factory.observeWithoutCapabilities {
                        queryCallback.onQuery(sqlQuery, java.util.Collections.unmodifiableList(bindArgs.map {
                            if (it is ByteArray) it.copyOf() else it
                        }))
                    }
                }
            }, java.util.concurrent.Executor { it.run() }) }
            .build().also { CountTrustRuntime.attach(it, factory) }
    }

    @Synchronized fun armAfterNextRateFault(field: LateSourceField) {
        check(!active && lateField == null); lateField = field; rateInjected = false
    }
    @Synchronized fun finishRateFault(): RateFaultResult {
        lateField = null
        val result = if (active) end() else Snapshot(emptyList(), false)
        return RateFaultResult(rateInjected, result)
    }
    private var legacyCountNoopArmed = false
    private var legacyCountNoopTouched = false
    @Synchronized fun armLegacyCountRateNoop() {
        check(!active && !legacyCountNoopArmed && lateField == null)
        legacyCountNoopArmed = true; legacyCountNoopTouched = false
    }
    @Synchronized fun legacyCountRateNoopTouched() = legacyCountNoopTouched

    @Synchronized private fun afterRate(db: SupportSQLiteDatabase, affected: Int) {
        if (legacyCountNoopArmed) {
            legacyCountNoopArmed = false
            check(affected == 1 && db.inTransaction()) { "COMMAND_WIDGET_NATIVE_RATE_NOT_TOUCHED" }
            val changed = db.compileStatement("UPDATE main.sync_outbox SET attemptCount=attemptCount WHERE id=(" +
                "SELECT max(id) FROM main.sync_outbox WHERE recordType='completion')").use { it.executeUpdateDelete() }
            check(changed == 1) { "COMMAND_WIDGET_NATIVE_SOURCE_NOT_TOUCHED" }
            legacyCountNoopTouched = true
        }

        val field = lateField ?: return
        lateField = null
        check(affected == 1 && db.inTransaction()) { "SOURCE_SPY_REAL_RATE_NOT_TOUCHED" }
        begin()
        val column = when (field) { LateSourceField.LAST_ERROR -> "lastError"; LateSourceField.PAYLOAD_JSON -> "payloadJson" }
        val changed = db.compileStatement("UPDATE main.sync_outbox SET $column=? WHERE id=(" +
            "SELECT max(id) FROM main.sync_outbox WHERE recordType='completion')").use { statement ->
            statement.bindString(1, "x".repeat(SYNC_REQUEST_LIMIT + 4097))
            statement.executeUpdateDelete()
        }
        check(changed == 1) { "SOURCE_SPY_LATE_SOURCE_NOT_TOUCHED" }
        rateInjected = true
    }
    @Synchronized fun armFinalWindowSequenceRegression() {
        check(!regressSequence); regressSequence = true; sequenceRegressionTouched = false
    }
    @Synchronized fun finalWindowSequenceRegressionTouched(): Boolean = sequenceRegressionTouched


    @Synchronized fun begin() { check(!active); events.clear(); overflow = false; active = true }
    @Synchronized fun end(): Snapshot {
        check(active); active = false
        return Snapshot(events.toList(), overflow)
    }
    @Synchronized private fun observe(query: String) {
        val sql = query.trim().replace('`', ' ').replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
        if (sql.startsWith("with checked as") && sql.contains("from temp.count_trust_journal_cells")) closedCountEffectReads++
        if (sql == closedCountHeaderQuery) closedCountEffectHeaders++
        if (sql == "select storageid,generation,dirty from count_trust_control where id=1") currentCountAddFrame()?.let { it.controlReads++ }

        if (preparationEndArmed && sql == "select storageid,generation,dirty from count_trust_control where id=1") {
            val db = requireNotNull(native); check(db.inTransaction())
            preparationEndArmed = false; preparationControlTouched = true
            preparationEndThread = Thread.currentThread()
        }
        if (metricSourceFault != null && sql == "pragma \"main\".schema_version") {
            val selected = requireNotNull(metricSourceFault); metricSourceFault = null
            val db = requireNotNull(native); check(db.inTransaction())
            when (selected) {
                MetricSourceFault.MAIN_INDEX -> db.execSQL("CREATE INDEX metric_source_handoff_test ON metrics(name)")
            }
            metricSourceTouched = true
        }
        if (!active) return
        if (countAddLateSequence && sql.startsWith("with checked as") && sql.contains("temp.count_trust_journal_cells")) {
            countAddLateSequence = false
            val db = requireNotNull(native); check(db.inTransaction())
            val changed = db.compileStatement("UPDATE temp.sqlite_sequence SET seq=seq-1 " +
                "WHERE name='count_trust_journal_events' AND seq>0").use { it.executeUpdateDelete() }
            check(changed == 1); countAddTouched = true
        }
        val kind = when {
            sql == "select type,name,tbl_name,sql from main.sqlite_master order by type,name" -> Kind.SCHEMA_SNAPSHOT
            sql.contains("from temp.count_trust_journal_cells") && sql.startsWith("select *") -> Kind.JOURNAL_CELLS_FULL
            sql.contains("from temp.count_trust_journal_cells") -> Kind.JOURNAL_CELLS_SCALAR
            sql.contains("from temp.count_trust_journal_events") && sql.startsWith("select seq,tablename,operation") &&
                sql.contains(" limit ") -> Kind.JOURNAL_SOURCE_HEADERS_BOUNDED
            sql.contains("from temp.count_trust_journal_events") && sql.startsWith("select seq,tablename,operation") -> Kind.JOURNAL_HEADERS_UNBOUNDED
            sql.contains("from temp.count_trust_journal_events") ||
                sql.contains("from temp.sqlite_sequence") -> Kind.JOURNAL_METADATA_SCALAR
            (sql.contains("from sync_outbox") || sql.contains("from main.sync_outbox")) &&
                sql.startsWith("select *") -> Kind.SOURCE_ROW_FULL
            (sql.contains("from sync_outbox") || sql.contains("from main.sync_outbox")) &&
                sql.contains("length(cast(") -> Kind.SOURCE_ROW_SIZE_PREFLIGHT
            (sql.contains("from sync_outbox") || sql.contains("from main.sync_outbox")) &&
                sql.startsWith("select id") && sql.contains(" limit ") -> Kind.SOURCE_IDS_BOUNDED
            else -> Kind.OTHER_QUERY
        }
        if (events.size < 16384) events += kind else overflow = true
        if (regressSequence && sql.startsWith("select case when") &&
            sql.contains("from temp.count_trust_journal_cells") && sql.endsWith("side=1")) {
            regressSequence = false
            val db = requireNotNull(native)
            check(db.inTransaction())
            val changed = db.compileStatement("UPDATE temp.sqlite_sequence SET seq=seq-1 " +
                "WHERE name='count_trust_journal_events' AND seq>0").use { it.executeUpdateDelete() }
            check(changed == 1); sequenceRegressionTouched = true
        }
    }

    /** Deliberately corrupt the REAL trigger-produced temporary evidence, after a real source.
     * No general raw handle/SQL API is exposed. Ordinary negative source writes use the gate.
     * Original complete journal/domain suites remain mandatory alongside these window gates.
     */
    enum class Fault { HEADER_OPERATION, HEADER_TABLE, SEQUENCE_GAP, SOURCE_SIDE, SOURCE_PADDING,
        SOURCE_CELL_TYPE, SOURCE_CELLS_MISSING, SOURCE_TABLE }
    fun damageLatestSourceEvidence(fault: Fault) {
        val db = requireNotNull(native)
        check(db.inTransaction())
        val sequence = db.query("SELECT seq FROM temp.count_trust_journal_events WHERE tableName='sync_outbox' ORDER BY seq DESC LIMIT 1").use {
            check(it.moveToFirst()); it.getLong(0).also { _ -> check(!it.moveToNext()) }
        }
        when (fault) {
            Fault.HEADER_OPERATION -> db.execSQL("UPDATE temp.count_trust_journal_events SET operation='INVALID' WHERE seq=?", arrayOf(sequence))
            Fault.HEADER_TABLE -> db.execSQL("UPDATE temp.count_trust_journal_events SET tableName='unknown_table' WHERE seq=?", arrayOf(sequence))
            Fault.SEQUENCE_GAP -> db.execSQL("DELETE FROM temp.count_trust_journal_events WHERE seq=?", arrayOf(sequence))
            Fault.SOURCE_SIDE -> db.execSQL("UPDATE temp.count_trust_journal_cells SET side=0 WHERE seq=? AND side=1", arrayOf(sequence))
            Fault.SOURCE_PADDING -> {
                val index = CountTrustJournal.COLUMNS.getValue("sync_outbox").size
                check(index < CountTrustJournal.COLUMNS.values.maxOf { it.size })
                db.execSQL("UPDATE temp.count_trust_journal_cells SET c$index=1 WHERE seq=? AND side=1", arrayOf(sequence))
            }
            Fault.SOURCE_CELL_TYPE -> db.execSQL("UPDATE temp.count_trust_journal_cells SET c0=CAST(c0 AS REAL) WHERE seq=? AND side=1", arrayOf(sequence))
            Fault.SOURCE_TABLE -> db.execSQL("UPDATE temp.count_trust_journal_cells SET sourceTable='completions' WHERE seq=?", arrayOf(sequence))
            Fault.SOURCE_CELLS_MISSING -> db.execSQL("DELETE FROM temp.count_trust_journal_cells WHERE seq=?", arrayOf(sequence))
        }
    }

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        var original: SupportSQLiteDatabase? = null
        var adapted: SupportSQLiteDatabase? = null
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase {
            if (original !== raw) {
                original = raw; native = raw
                adapted = object : SupportSQLiteDatabase by raw {
                    override val path: String? get() = if (unknownPath) "" else raw.path
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val statement = raw.compileStatement(sql)
                        val normalized = sql.replace("`", "").replace(Regex("\\s+"), "").lowercase(Locale.ROOT)
                        val rate = normalized == "updatehabitssetactivityrate=?,activityrateupdatedat=?whereid=?"
                        val origin = normalized.startsWith("insert") && normalized.contains("intonext_request_origins(")
                        // Wrap business statements even when compiled before observation, so cached
                        // Room statements cannot escape the later execution-time cohort ledger.
                        if (!rate && !origin && countAddBusinessDml(sql) == null) return statement
                        return object : SupportSQLiteStatement by statement {
                            // Current Room 2.8.5 SQLiteStatement.step delegates to execute().
                            override fun execute() {
                                statement.execute(); countAddDml(sql)
                                if (origin) afterCountAddOrigin(raw)
                                if (rate && (lateField != null || legacyCountNoopArmed)) {
                                    val affected = raw.query("SELECT changes()").use {
                                        check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER)
                                        it.getInt(0).also { _ -> check(!it.moveToNext()) }
                                    }
                                    afterRate(raw, affected)
                                }
                            }
                            override fun executeInsert(): Long = statement.executeInsert().also {
                                countAddDml(sql)
                                if (origin) afterCountAddOrigin(raw)
                            }
                            override fun executeUpdateDelete(): Int = statement.executeUpdateDelete().also {
                                countAddDml(sql)
                                if (rate) afterRate(raw, it)
                                if (origin) afterCountAddOrigin(raw)
                            }
                        }
                    }
                    override fun beginTransaction() = beginCountAddTransaction { raw.beginTransaction() }
                    override fun beginTransactionNonExclusive() = beginCountAddTransaction { raw.beginTransactionNonExclusive() }
                    override fun beginTransactionReadOnly() = beginCountAddTransaction { raw.beginTransactionReadOnly() }
                    override fun beginTransactionWithListener(transactionListener: android.database.sqlite.SQLiteTransactionListener) =
                        beginCountAddTransaction { raw.beginTransactionWithListener(transactionListener) }
                    override fun beginTransactionWithListenerNonExclusive(transactionListener: android.database.sqlite.SQLiteTransactionListener) =
                        beginCountAddTransaction { raw.beginTransactionWithListenerNonExclusive(transactionListener) }
                    override fun beginTransactionWithListenerReadOnly(transactionListener: android.database.sqlite.SQLiteTransactionListener) =
                        beginCountAddTransaction { raw.beginTransactionWithListenerReadOnly(transactionListener) }
                    override fun execSQL(sql: String) { raw.execSQL(sql); countAddDml(sql) }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) { raw.execSQL(sql, bindArgs); countAddDml(sql) }
                    override fun endTransaction() {
                        try { raw.endTransaction() } catch (error: Throwable) { endCountAddTransaction(false); throw error }
                        if (!raw.inTransaction()) endCountAddTransaction(true)
                        if (preparationEndThread === Thread.currentThread() && !raw.inTransaction()) {
                            preparationEndThread = null
                            synchronized(this@SourceWindowNativeSpy) { preparationEndTouched = true }
                            throw IllegalStateException("COUNT_ADD_TEST_PREPARATION_END_OUTCOME_LOST")
                        }
                        if (countAddUnknownEnd && !raw.inTransaction()) {
                            countAddUnknownEnd = false; countAddTouched = true
                            throw IllegalStateException("COUNT_ADD_TEST_END_OUTCOME_LOST")
                        }
                    }
                    override fun query(query: String): Cursor { observe(query); return verifiedPrefixCursor(query, raw.query(query)) }
                    override fun query(query: String, bindArgs: Array<out Any?>): Cursor {
                        observe(query); return verifiedPrefixCursor(query, raw.query(query, bindArgs))
                    }
                    override fun query(query: SupportSQLiteQuery): Cursor { observe(query.sql); return verifiedPrefixCursor(query.sql, raw.query(query)) }
                    override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor {
                        observe(query.sql); return verifiedPrefixCursor(query.sql, raw.query(query, cancellationSignal))
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
        val delegate = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
        return object : SupportSQLiteOpenHelper by delegate {
            override val writableDatabase get() = wrap(delegate.writableDatabase)
            override val readableDatabase get() = wrap(delegate.readableDatabase)
        }
    }
}
