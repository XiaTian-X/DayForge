package com.dayforge.data.repository

import android.database.Cursor
import android.database.CursorWrapper
import android.os.CancellationSignal
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import java.util.Locale

/** Test-only observer below the real CountTrust connection gate. No proof/seed/effects are
 * fabricated. Late faults execute actual SQL and the original permanent + journal triggers.
 */
internal class ChallengeReuseNativeSpy(private val delegate: SupportSQLiteOpenHelper.Factory) : SupportSQLiteOpenHelper.Factory {
    enum class LateFault { CHALLENGE_NOOP, CONTROL_GENERATION, CONTROL_STORAGE, HANDOFF_CHALLENGE_NOOP }
    data class Sample(val audits: Map<String, Int>, val windows: Int)
    private val tables = listOf("next_challenge_state", "next_challenge_rounds", "next_challenge_births")
    private var active = false
    private var windows = 0
    private val audits = linkedMapOf<String, Int>()
    private var fault: LateFault? = null
    private var touched = false
    private var awaitingControl = false

    @Synchronized fun begin() { check(!active); active = true; windows = 0; audits.clear() }
    @Synchronized fun end(): Sample {
        check(active); active = false
        return Sample(tables.associateWith { audits[it] ?: 0 }, windows)
    }
    @Synchronized fun arm(value: LateFault) { check(fault == null); fault = value; touched = false; awaitingControl = false }
    @Synchronized fun wasTouched() = touched
    @Synchronized private fun observe(sql: String): Boolean {
        val normalized = sql.trim().replace("`", "").replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
        val window = normalized == "select count(*) from temp.count_trust_journal_events where seq>? and tablename in " +
            "('next_sync_state','next_challenge_state','next_challenge_rounds','next_challenge_births')"
        if (active) {
            if (window) windows++
            for (table in tables) if (normalized == "select * from $table order by rowid")
                audits[table] = (audits[table] ?: 0) + 1
        }
        return window
    }
    @Synchronized private fun afterWindowRead(sql: SupportSQLiteDatabase) {
        val selected = fault ?: return
        if (selected == LateFault.HANDOFF_CHALLENGE_NOOP) { awaitingControl = true; return }
        fault = null
        check(sql.inTransaction())
        when (selected) {
            LateFault.CHALLENGE_NOOP -> sql.execSQL("UPDATE main.next_challenge_state SET metadataHash=metadataHash WHERE id=1")
            LateFault.CONTROL_GENERATION -> sql.execSQL("UPDATE main.count_trust_control SET generation=generation+1 WHERE id=1")
            LateFault.CONTROL_STORAGE -> sql.execSQL("UPDATE main.count_trust_control SET storageId='00000000-0000-4000-8000-000000004230' WHERE id=1")
            LateFault.HANDOFF_CHALLENGE_NOOP -> error("Handoff fault waits for the original control read")
        }
        touched = true
    }
    @Synchronized private fun isHandoffControl(text: String): Boolean = awaitingControl &&
        text.trim().replace("`", "").replace(Regex("\\s+"), " ").lowercase(Locale.ROOT) ==
            "select storageid,generation,dirty from count_trust_control where id=1"
    @Synchronized private fun afterControlRead(sql: SupportSQLiteDatabase) {
        check(awaitingControl && fault == LateFault.HANDOFF_CHALLENGE_NOOP)
        awaitingControl = false; fault = null
        check(sql.inTransaction())
        // The caller has already materialized its old control row, so only the later
        // handoff boundary can discover these real trigger/generation changes.
        sql.execSQL("UPDATE main.next_challenge_state SET metadataHash=metadataHash WHERE id=1")
        touched = true
    }
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        var original: SupportSQLiteDatabase? = null
        var adapted: SupportSQLiteDatabase? = null
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase {
            if (original !== raw) {
                original = raw
                fun read(text: String, body: () -> Cursor): Cursor {
                    val window = observe(text)
                    val control = isHandoffControl(text)
                    val result = body()
                    return if (!window && !control) result else object : CursorWrapper(result) {
                        private var ended = false
                        override fun close() {
                            super.close()
                            if (!ended) {
                                ended = true
                                if (window) afterWindowRead(raw) else afterControlRead(raw)
                            }
                        }
                    }
                }
                adapted = object : SupportSQLiteDatabase by raw {
                    override fun query(query: String): Cursor = read(query) { raw.query(query) }
                    override fun query(query: String, bindArgs: Array<out Any?>): Cursor = read(query) { raw.query(query, bindArgs) }
                    override fun query(query: SupportSQLiteQuery): Cursor = read(query.sql) { raw.query(query) }
                    override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor =
                        read(query.sql) { raw.query(query, cancellationSignal) }
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
        val helper = delegate.create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase get() = wrap(helper.writableDatabase)
            override val readableDatabase get() = wrap(helper.readableDatabase)
        }
    }
}
