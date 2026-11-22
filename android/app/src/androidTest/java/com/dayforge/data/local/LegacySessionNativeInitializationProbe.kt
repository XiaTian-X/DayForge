package com.dayforge.data.local

import android.database.Cursor
import android.database.CursorWrapper
import android.database.sqlite.SQLiteConstraintException
import android.os.CancellationSignal
import androidx.sqlite.db.*
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Observes the original control SELECT and actual delegate END. Optional fixed fault actors
 * install only the declared fence-content cases or execute one genuinely failing native query. */
internal class LegacySessionNativeInitializationProbe : SupportSQLiteOpenHelper.Factory {
    val selected = AtomicBoolean()
    val actualEnds = AtomicInteger()
    private val selectedThread = AtomicReference<Thread?>()
    enum class DamagedFence { MODE, REVISION, NATIVE_TYPE }
    val initialDamagedFence = AtomicReference<DamagedFence?>()
    val successfulFenceFaults = AtomicInteger()
    val modeFaultActualEnds = AtomicInteger()
    data class ModeFaultEvidence(val rejectedInsert: SQLiteConstraintException,
        val schemaBefore: List<List<String?>>, val schemaAfter: List<List<String?>>,
        val schemaVersionBefore: Long, val schemaVersionAfter: Long)
    val modeFaultEvidence = AtomicReference<ModeFaultEvidence?>()
    private val faultFailure = AtomicReference<Throwable?>()
    val failFenceQuery = AtomicBoolean()
    val failedFenceQueries = AtomicInteger()
    var initialFenceImage: List<String?>? = null
        private set
    private fun fenceImage(raw: SupportSQLiteDatabase): List<String?> = raw.query(
        "SELECT typeof(id),quote(id),typeof(recoveryId),quote(recoveryId),typeof(accountId),quote(accountId)," +
            "typeof(serverInstanceId),quote(serverInstanceId),typeof(oldEpoch),quote(oldEpoch),typeof(newEpoch),quote(newEpoch)," +
            "typeof(authenticationGeneration),quote(authenticationGeneration),typeof(deviceId),quote(deviceId)," +
            "typeof(capabilityRevision),quote(capabilityRevision),typeof(sourceHash),quote(sourceHash),typeof(mode),quote(mode) " +
            "FROM next_replica_fence ORDER BY id").use {
        check(it.moveToFirst()); val value = (0 until it.columnCount).map(it::getString)
        check(!it.moveToNext()); value
    }
    fun currentFenceImage(raw: SupportSQLiteDatabase): List<String?> = fenceImage(raw)
    private fun schema(raw: SupportSQLiteDatabase): List<List<String?>> = raw.query(
        "SELECT type,name,tbl_name,sql FROM main.sqlite_master ORDER BY type,name").use { cursor ->
        buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map(cursor::getString)) }
    }
    private fun schemaVersion(raw: SupportSQLiteDatabase): Long = raw.query("PRAGMA main.schema_version").use {
        check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER)
        it.getLong(0).also { _ -> check(!it.moveToNext()) }
    }
    private fun requireNoJournals(raw: SupportSQLiteDatabase) {
        raw.query("SELECT name FROM temp.sqlite_master WHERE name GLOB 'count_trust_journal_*' " +
            "OR name GLOB 'count_command_journal_*'").use { check(!it.moveToFirst()) {
            "LEGACY_TEST_MODE_FAULT_AFTER_JOURNAL_INSTALL"
        } }
    }
    private fun insertFault(raw: SupportSQLiteDatabase, fault: DamagedFence) {
        raw.execSQL("INSERT INTO next_replica_fence (id,recoveryId,accountId,serverInstanceId,oldEpoch,newEpoch," +
            "authenticationGeneration,deviceId,capabilityRevision,sourceHash,mode) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(1L, legacyGenerationId(11), legacyGenerationId(1), legacyGenerationId(2),
                legacyGenerationId(3), legacyGenerationId(12), legacyGenerationId(13), legacyGenerationId(4),
                if (fault == DamagedFence.REVISION) 0L else 3L,
                if (fault == DamagedFence.NATIVE_TYPE) byteArrayOf(1, 2) else "a".repeat(64),
                if (fault == DamagedFence.MODE) "broken" else "frozen"))
    }
    private fun installModeFault(raw: SupportSQLiteDatabase) {
        // New-file onCreate and root initialization have finished. No original onOpen or
        // Factory transaction has run: an installed journal must never be given a new cookie.
        check(raw.version == 20 && !raw.inTransaction())
        requireNoJournals(raw)
        val before = schema(raw)
        val guard = before.single { it[1] == "replica_fence_control_insert" }
        check(guard.take(3) == listOf("trigger", "replica_fence_control_insert", "next_replica_fence"))
        val guardSql = checkNotNull(guard[3])
        check(guardSql.replace(Regex("\\s+"), " ").trim() ==
            "CREATE TRIGGER replica_fence_control_insert BEFORE INSERT ON next_replica_fence " +
            "WHEN EXISTS(SELECT 1 FROM next_replica_fence WHERE id IS NOT 1 OR mode IS NOT 'closed') " +
            "OR NEW.id IS NOT 1 OR NEW.mode IS NOT 'frozen' " +
            "BEGIN SELECT RAISE(ABORT,'SYNC_REPLICA_SWITCH_FENCED'); END")
        raw.query("SELECT id FROM next_replica_fence").use { check(!it.moveToFirst()) }
        val versionBefore = schemaVersion(raw)
        // Preserve why the old actor did not install a damaged row. This is a real native
        // refusal, not a synthetic exception or an assertion thrown from an opening callback.
        val rejected = try { insertFault(raw, DamagedFence.MODE); null }
            catch (failure: SQLiteConstraintException) { failure }
        checkNotNull(rejected) { "LEGACY_TEST_MODE_GUARD_DID_NOT_REJECT" }
        check(rejected.message?.contains("SYNC_REPLICA_SWITCH_FENCED") == true)
        raw.query("SELECT id FROM next_replica_fence").use { check(!it.moveToFirst()) }
        check(schema(raw) == before && schemaVersion(raw) == versionBefore)
        raw.beginTransaction()
        try {
            // Fixed test-only corruption actor. All other guards and dirty triggers still run.
            raw.execSQL("DROP TRIGGER replica_fence_control_insert")
            insertFault(raw, DamagedFence.MODE)
            raw.execSQL(guardSql)
            check(schema(raw) == before)
            raw.setTransactionSuccessful()
        } finally {
            raw.endTransaction() // Only this actor's transaction; actual END must return.
        }
        check(!raw.inTransaction())
        modeFaultActualEnds.incrementAndGet()
        val after = schema(raw)
        val versionAfter = schemaVersion(raw)
        check(after == before && versionAfter > versionBefore)
        requireNoJournals(raw)
        initialFenceImage = fenceImage(raw)
        modeFaultEvidence.set(ModeFaultEvidence(rejected, before, after, versionBefore, versionAfter))
        check(initialDamagedFence.compareAndSet(DamagedFence.MODE, null))
        successfulFenceFaults.incrementAndGet()
    }
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val opened = AtomicBoolean()
        val created = AtomicBoolean()
        val wrappers = java.util.IdentityHashMap<SupportSQLiteDatabase, SupportSQLiteDatabase>()
        fun cursor(sql: String, raw: SupportSQLiteDatabase, cursor: Cursor): Cursor {
            if (!opened.get() || !raw.inTransaction() ||
                !Regex("(?i)\\bFROM\\s+(?:main\\.)?count_trust_control\\b").containsMatchIn(sql)) return cursor
            return object : CursorWrapper(cursor) {
                private fun read(found: Boolean): Boolean {
                    if (found && selected.compareAndSet(false, true)) selectedThread.set(Thread.currentThread())
                    return found
                }
                override fun moveToFirst() = read(cursor.moveToFirst())
                override fun moveToNext() = read(cursor.moveToNext())
                override fun moveToPosition(position: Int) = read(cursor.moveToPosition(position))
            }
        }
        fun beforeQuery(sql: String, raw: SupportSQLiteDatabase) {
            if (opened.get() && raw.inTransaction() && sql == "SELECT * FROM next_replica_fence ORDER BY id" &&
                failFenceQuery.compareAndSet(true, false)) {
                failedFenceQueries.incrementAndGet()
                // Real SQLite compile/step failure, not a thrown readiness DTO or a replaced graph.
                raw.query("SELECT * FROM legacy_session_missing_table_579").use { it.moveToFirst() }
                error("LEGACY_TEST_NATIVE_QUERY_FAILURE_NOT_REACHED")
            }
        }
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(wrappers) {
            wrappers[raw] ?: object : SupportSQLiteDatabase by raw {
                override fun query(query: String): Cursor { beforeQuery(query, raw); return cursor(query, raw, raw.query(query)) }
                override fun query(query: String, bindArgs: Array<out Any?>): Cursor {
                    beforeQuery(query, raw); return cursor(query, raw, raw.query(query, bindArgs))
                }
                override fun query(query: SupportSQLiteQuery): Cursor {
                    beforeQuery(query.sql, raw); return cursor(query.sql, raw, raw.query(query))
                }
                override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor {
                    beforeQuery(query.sql, raw); return cursor(query.sql, raw, raw.query(query, cancellationSignal))
                }
                override fun endTransaction() {
                    raw.endTransaction()
                    if (!raw.inTransaction() && selectedThread.compareAndSet(Thread.currentThread(), null)) actualEnds.incrementAndGet()
                }
            }.also { wrappers[raw] = it }
        }
        val parent = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(parent.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
            override fun onCreate(db: SupportSQLiteDatabase) {
                parent.onCreate(wrap(db))
                created.set(true)
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(wrap(db), oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onDowngrade(wrap(db), oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) {
                // A failed actor stays failed across FrameworkSQLiteOpenHelper's retry. Its
                // armed token is consumed only after native END and all restored-schema checks.
                faultFailure.get()?.let { throw it }
                try {
                    if (initialDamagedFence.get() == DamagedFence.MODE) {
                        check(created.get() && !opened.get()) { "LEGACY_TEST_MODE_FAULT_REQUIRES_FRESH_CREATE" }
                        installModeFault(db)
                    }
                    parent.onOpen(wrap(db))
                    initialDamagedFence.get()?.let { fault ->
                        // REVISION/NATIVE_TYPE retain their original post-onOpen native DML.
                        // Existing dirty triggers run; schema/root are untouched.
                        check(fault != DamagedFence.MODE)
                        insertFault(db, fault)
                        initialFenceImage = fenceImage(db)
                        check(initialDamagedFence.compareAndSet(fault, null))
                        successfulFenceFaults.incrementAndGet()
                    }
                    opened.set(true)
                } catch (failure: Throwable) {
                    faultFailure.compareAndSet(null, failure)
                    throw failure
                }
            }
            override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
        }
        val delegate = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
        return object : SupportSQLiteOpenHelper by delegate {
            override val readableDatabase get() = wrap(delegate.readableDatabase)
            override val writableDatabase get() = wrap(delegate.writableDatabase)
        }
    }
}
