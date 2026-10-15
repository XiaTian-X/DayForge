package com.dayforge.data.repository

import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.local.HabitDatabase
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Work sharing inside ONE controlled SELECT-only phase of a Room transaction. Not a durable
 * proof, an authorization cache, or a transaction owner. Callers already hold the account lock.
 *
 * Only original raw-row audits and their exact absence are memoized. Every participating loader
 * remains read-only: no DML/DDL, transaction control, PRAGMA changes, external publication or child
 * coroutine work. The exit probes detect violations of that contract; they do not establish that
 * arbitrary code is read-only. Writer prepare and final audit MUST use separate empty sessions.
 */
internal class NextEvidenceReadSession private constructor(
    private val database: HabitDatabase,
    private val sql: SupportSQLiteDatabase
) : AbstractCoroutineContextElement(Key) {
    private data class RowKey(val table: String, val where: String, val args: List<Any>)
    private data class Schema(val sequence: Long, val name: String, val file: String, val version: Long)
    private data class ReadBoundary(val changes: Long, val mainVersion: Long, val tempVersion: Long,
        val schemas: List<Schema>)

    private var owner: Job? = null
    private var open = true
    private val rows = HashMap<RowKey, String?>()
    private val layouts = HashMap<String, String>()
    private var retainedBytes = 0L
    private var rowHits = 0
    private var rowLoads = 0
    private var layoutHits = 0

    private suspend fun requireOwner(actual: HabitDatabase) {
        val context = currentCoroutineContext()
        context.ensureActive()
        check(open && actual === database &&
            context[Job] === owner && database.inTransaction() && sql.inTransaction()) {
            "EVIDENCE_READ_SESSION_EXPIRED"
        }
    }

    private fun boundary(): ReadBoundary {
        // Initialize/check TEMP before database_list: opening TEMP itself may add its descriptor.
        val mainVersion = schemaVersion("main")
        val tempVersion = schemaVersion("temp")
        val schemas = sql.query("PRAGMA database_list").use { cursor -> buildList {
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                val version = when (name) { "main" -> mainVersion; "temp" -> tempVersion; else -> schemaVersion(name) }
                add(Schema(cursor.getLong(0), name, cursor.getString(2), version))
            }
        } }
        val changes = sql.query("SELECT total_changes()").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
        return ReadBoundary(changes, mainVersion, tempVersion, schemas)
    }

    private fun schemaVersion(name: String): Long =
        sql.query("PRAGMA \"${name.replace("\"", "\"\"")}\".schema_version").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun retain(bytes: Long, save: () -> Unit) {
        if (rows.size + layouts.size < MAX_ENTRIES && bytes <= MAX_BYTES - retainedBytes) {
            save()
            retainedBytes += bytes
        }
    }

    private suspend fun row(actual: HabitDatabase, table: String, where: String,
        args: Array<Any>, load: suspend () -> String?): String? {
        requireOwner(actual)
        // Only deterministic internal equality predicates and immutable, typed bind values.
        // Unknown expressions use the original audit, not a newly restricted business contract.
        if (!where.matches(EQUALITY_PREDICATE) || args.any { it !is String && it !is Long && it !is Int })
            return load()
        val key = RowKey(table, where, args.toList())
        if (rows.containsKey(key)) { rowHits++; return rows[key] }
        rowLoads++
        val result = load() // Exceptions/cancellation/partial audits are never recorded as absence.
        requireOwner(actual)
        val bytes = 128L + 2L * (table.length + where.length + (result?.length ?: 0)) +
            key.args.sumOf { if (it is String) 2L * it.length else 16L }
        retain(bytes) { rows[key] = result }
        return result
    }

    private suspend fun layout(actual: HabitDatabase, table: String, load: () -> String): String {
        requireOwner(actual)
        layouts[table]?.let { layoutHits++; return it }
        val value = load()
        retain(64L + 2L * (table.length + value.length)) { layouts[table] = value }
        return value
    }

    internal companion object Key : CoroutineContext.Key<NextEvidenceReadSession> {
        private const val MAX_ENTRIES = 256
        private const val MAX_BYTES = 2_097_152L
        private val EQUALITY_PREDICATE = Regex("(?:[A-Za-z_][A-Za-z0-9_]* *= *\\?|1=1)(?: AND [A-Za-z_][A-Za-z0-9_]* *= *\\?)*")

        suspend fun requireWritePhase() {
            check(currentCoroutineContext()[Key] == null) { "EVIDENCE_READ_WRITE_FORBIDDEN" }
        }

        /** Identity of this SELECT-only phase, not a transferable transaction/permission ticket. */
        suspend fun current(database: HabitDatabase): NextEvidenceReadSession {
            val session = checkNotNull(currentCoroutineContext()[Key]) { "EVIDENCE_READ_SESSION_REQUIRED" }
            session.requireOwner(database)
            return session
        }

        suspend fun <T> read(database: HabitDatabase, block: suspend () -> T): T {
            check(database.inTransaction()) { "EVIDENCE_READ_TRANSACTION_REQUIRED" }
            currentCoroutineContext()[Key]?.let { existing ->
                existing.requireOwner(database)
                return block() // Same owner's nested SELECT participant, not another transaction.
            }
            val sql = database.openHelper.writableDatabase
            val session = NextEvidenceReadSession(database, sql)
            return withContext(session) {
                val started = android.os.SystemClock.elapsedRealtime()
                var completed = false
                session.owner = requireNotNull(currentCoroutineContext()[Job])
                try {
                    session.requireOwner(database)
                    val before = session.boundary()
                    val result = block()
                    session.requireOwner(database)
                    check(session.boundary() == before) { "EVIDENCE_READ_SESSION_WROTE_DATA" }
                    completed = true
                    result
                } finally {
                    session.open = false
                    session.rows.clear()
                    session.layouts.clear()
                    session.owner = null
                    val elapsed = android.os.SystemClock.elapsedRealtime() - started
                    if (com.dayforge.BuildConfig.DEBUG && (session.rowHits > 0 || elapsed >= 100)) {
                        // Fixed numeric diagnostics only, never keys, arguments, owners or payloads.
                        android.util.Log.d("EvidenceReadTiming", "completed=$completed elapsed_ms=$elapsed " +
                            "row_loads=${session.rowLoads} row_hits=${session.rowHits} layout_hits=${session.layoutHits}")
                    }
                }
            }
        }

        /** Only an explicit Room owner can borrow a proof. The session supplies its captured handle:
         * query-observer helpers may return a NEW wrapper on each writableDatabase access.
         * A caller cannot pair an owner with an unrelated SQL handle or infer ownership from a path.
         */
        suspend fun rowHash(database: HabitDatabase, table: String, where: String,
            args: Array<Any>, length: (SupportSQLiteDatabase) -> String,
            audit: suspend (SupportSQLiteDatabase, String) -> String?): String? {
            val session = currentCoroutineContext()[Key]
            if (session == null) {
                val sql = database.openHelper.writableDatabase
                return audit(sql, length(sql))
            }
            // A cached null is meaningful, not a reason to repeat the original query.
            return session.row(database, table, where, args) {
                val expression = session.layout(database, table) { length(session.sql) }
                audit(session.sql, expression)
            }
        }
    }
}
