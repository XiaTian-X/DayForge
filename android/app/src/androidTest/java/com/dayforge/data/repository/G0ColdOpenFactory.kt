package com.dayforge.data.repository

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.util.UUID

/** Test-only lifecycle capability. A fresh trusted-code reference supplies the exact schema;
 * the target database never supplies its own allow-list. This does not grant SQL client access.
 */
internal data class G0SchemaRow(val type: String, val name: String, val table: String, val sql: String?)

internal fun g0Schema(sql: SupportSQLiteDatabase): List<G0SchemaRow> = sql.query(
    "SELECT type,name,tbl_name,sql FROM main.sqlite_master ORDER BY type,name"
).use { c -> buildList {
    while (c.moveToNext()) add(G0SchemaRow(c.getString(0), c.getString(1), c.getString(2),
        if (c.isNull(3)) null else c.getString(3)))
} }

internal fun g0Number(sql: SupportSQLiteDatabase, query: String): Long = sql.query(query).use {
    check(it.moveToFirst()); it.getLong(0)
}

internal class G0ColdOpenFactory(
    private val expected: List<G0SchemaRow>,
    private val beforeRoomOpen: (SupportSQLiteDatabase) -> Unit = {},
    private val baseline: (SupportSQLiteDatabase) -> Unit = {},
    private val onFailure: () -> Unit = {},
    private val afterActualEnd: () -> Unit = {}
) : SupportSQLiteOpenHelper.Factory {
    var callbacks = 0
    var validated = false
    var committed = false
    val nonce = UUID.randomUUID().toString()
    private var connection: SupportSQLiteDatabase? = null
    private var openingThread: Thread? = null
    private var reserved = false
    private var terminalFailure: Throwable? = null
    private var corruptionSignal: (() -> Unit)? = null
    private var platformOpenComplete = false

    fun injectCorruptionSignalForTest() = checkNotNull(corruptionSignal).invoke()

    private fun sameBoundary(db: SupportSQLiteDatabase) {
        check(connection === db && openingThread === Thread.currentThread()) { "G0_OPEN_CONNECTION_CHANGED" }
        check(reserved && db.inTransaction()) { "G0_OPEN_RESERVATION_LOST" }
        db.query("SELECT value FROM temp.g0_open_nonce").use {
            check(it.moveToFirst() && it.getString(0) == nonce)
        }
    }

    private fun rollback(failure: Throwable): Nothing {
        terminalFailure = failure
        onFailure()
        if (reserved) {
            reserved = false
            try { checkNotNull(connection).endTransaction() }
            catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        }
        throw failure
    }

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val callback = configuration.callback
        val guarded = object : SupportSQLiteOpenHelper.Callback(callback.version) {
            override fun onCorruption(db: SupportSQLiteDatabase) {
                // The default Support callback deletes database files. A failed cold proof
                // must preserve evidence; the lifecycle owner, not this callback, drains/closes.
                val failure = IllegalStateException("G0_COLD_CORRUPTION")
                terminalFailure = failure
                onFailure()
                throw failure
            }

            override fun onConfigure(db: SupportSQLiteDatabase) {
                terminalFailure?.let { throw it }
                check(!reserved)
                connection = db; openingThread = Thread.currentThread()
                // Fixed connection-only setup before the reservation, never user-table SQL.
                // Room repeats these settings during onOpen; they must remain effective.
                db.setForeignKeyConstraintsEnabled(true)
                db.execSQL("PRAGMA temp_store = MEMORY")
                db.execSQL("PRAGMA recursive_triggers = 1")
                db.beginTransactionNonExclusive()
                reserved = true
                try {
                    check(g0Schema(db) == expected) { "G0_COLD_SCHEMA_REJECTED" }
                    check(db.version == callback.version) { "G0_COLD_VERSION_REJECTED" }
                    db.execSQL("CREATE TEMP TABLE g0_open_nonce(value TEXT NOT NULL)")
                    db.execSQL("INSERT INTO temp.g0_open_nonce VALUES(?)", arrayOf(nonce))
                    callback.onConfigure(db)
                    sameBoundary(db)
                } catch (failure: Throwable) { rollback(failure) }
            }

            override fun onCreate(db: SupportSQLiteDatabase): Unit = error("G0_COLD_CREATE_FORBIDDEN")
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int): Unit =
                error("G0_COLD_MIGRATION_NOT_PROVEN")

            override fun onOpen(db: SupportSQLiteDatabase) {
                try {
                    sameBoundary(db)
                    platformOpenComplete = true
                } catch (failure: Throwable) { rollback(failure) }
                // Keep the SAME reservation across the framework getter's return. The owner
                // below still withholds the handle, runs Room onOpen, validates and ends the
                // transaction. Moving these stages outside callback retry avoids its 500ms sleep
                // on cancellation; no callback failure is converted into successful opening.
            }
        }
        corruptionSignal = { guarded.onCorruption(checkNotNull(connection)) }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(guarded)
                .noBackupDirectory(configuration.useNoBackupDirectory).build()
        )
        return object : SupportSQLiteOpenHelper {
            private val lifecycle = Any()
            private var opening = false
            private var closed = false
            override val databaseName get() = helper.databaseName
            override val writableDatabase get() = open()
            // Cold validation needs a writer reservation; never use a read-only fallback.
            override val readableDatabase get() = open()

            private fun open(): SupportSQLiteDatabase = synchronized(lifecycle) {
                terminalFailure?.let { throw it }
                check(!closed && !opening) { "G0_COLD_OWNER_CLOSED_OR_REENTRANT" }
                if (committed) return@synchronized checkNotNull(connection)
                opening = true
                try {
                    val db = helper.writableDatabase
                    check(platformOpenComplete)
                    sameBoundary(db)
                    beforeRoomOpen(db)
                    callbacks++
                    callback.onOpen(db)
                    sameBoundary(db)
                    check(g0Schema(db) == expected) { "G0_COLD_SCHEMA_CHANGED" }
                    check(g0Number(db, "PRAGMA foreign_keys") == 1L)
                    check(g0Number(db, "PRAGMA recursive_triggers") == 1L)
                    baseline(db)
                    sameBoundary(db)
                    validated = true
                    db.setTransactionSuccessful()
                    // endTransaction failure is UNKNOWN, never evidence of a rollback.
                    reserved = false
                    db.endTransaction()
                    afterActualEnd()
                    committed = true
                    db
                } catch (failure: Throwable) {
                    terminalFailure = failure
                    try { onFailure() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    if (reserved) {
                        reserved = false
                        try { checkNotNull(connection).endTransaction() }
                        catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    }
                    // No getter has published a handle. Room may have cached the callback
                    // handle internally: close it before delivering failure, permanently poison
                    // this owner, and require a new Room instance for any subsequent attempt.
                    closed = true
                    try { helper.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    throw failure
                } finally { opening = false }
            }

            override fun setWriteAheadLoggingEnabled(enabled: Boolean) = synchronized(lifecycle) {
                check(!opening && !closed && !committed && terminalFailure == null)
                helper.setWriteAheadLoggingEnabled(enabled)
            }

            override fun close() = synchronized(lifecycle) {
                check(!opening) { "G0_COLD_CLOSE_DURING_OPEN" }
                closed = true
                helper.close()
            }
        }
    }
}

