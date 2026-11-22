package com.dayforge.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/** Two fixed test faults below the controlled factory; no arbitrary raw handle/callback.
 * AFTER_COMMITTED_REOPEN is after framework's real upgrade/reopen, not an intercepted END.
 */
internal class CountCommandMigrationNativeFault(private val fault: Fault) : SupportSQLiteOpenHelper.Factory {
    enum class Fault { OLD_ROW_AFTER_FIRST_NEW_TABLE, AFTER_COMMITTED_REOPEN, AFTER_COMMITTED_REOPEN_ONCE }
    var hits = 0; private set
    private var migrated = false
    private var originalFailure: Throwable? = null
    var retryRejections = 0; private set
    val observedOriginalFailure: Boolean get() = originalFailure != null
    fun containsOriginalFailure(error: Throwable?): Boolean = originalFailure?.let { original ->
        generateSequence(error) { it.cause }.any { it === original }
    } == true

    /** Framework2.6.2 retries a failed open after500ms. Keep this deliberate fault owner
     * failed using the SAME observed exception; only a new ordinary owner may recover.
     * The explicit transient mode independently preserves Framework's legitimate retry. */
    private fun refuseFaultOwnerRetry() {
        if (fault != Fault.AFTER_COMMITTED_REOPEN_ONCE) originalFailure?.let {
            retryRejections++
            throw it
        }
    }
    fun build(context: Context): HabitDatabase {
        check(context.packageName == "com.dayforge.testbed")
        val owner = CountTrustOpenHelperFactory(context, this)
        return Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
            .addMigrations(CountTrustMigration, CountCommandMigration).openHelperFactory(owner)
            .addCallback(SyncSchemaCallback).build().also { CountTrustRuntime.attach(it, owner) }
    }
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        var native: SupportSQLiteDatabase? = null; var retained: SupportSQLiteDatabase? = null
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase {
            if (native !== raw) {
                native = raw
                retained = object : SupportSQLiteDatabase by raw {
                    private fun executed(sql: String) {
                        val fixed = sql.lowercase(java.util.Locale.ROOT).replace("`", "").replace("\"", "")
                        if (fixed.startsWith("create table count_command_status ") || fixed.startsWith("create table count_command_status(")) {
                            migrated = true
                            if (fault == Fault.OLD_ROW_AFTER_FIRST_NEW_TABLE && hits == 0) {
                                check(raw.inTransaction())
                                raw.execSQL("UPDATE next_rejections SET resultJson='fixed migration fault' WHERE requestId='old-request'")
                                raw.query("SELECT changes()").use { check(it.moveToFirst() && it.getLong(0) == 1L && !it.moveToNext()) }
                                hits++
                            }
                        }
                    }
                    override fun execSQL(sql: String) { raw.execSQL(sql); executed(sql) }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
                        raw.execSQL(sql, bindArgs); executed(sql)
                    }
                }
            }
            return requireNotNull(retained)
        }
        val parent = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(parent.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                refuseFaultOwnerRetry()
                parent.onConfigure(wrap(db))
                if (fault in setOf(Fault.AFTER_COMMITTED_REOPEN, Fault.AFTER_COMMITTED_REOPEN_ONCE) &&
                    migrated && db.version == 20 && hits == 0) {
                    check(!db.inTransaction()); hits++
                    val failure = IllegalStateException("FIXED_AFTER_COMMITTED_MIGRATION_REOPEN")
                    check(originalFailure == null); originalFailure = failure
                    throw failure
                }
            }
            override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                refuseFaultOwnerRetry()
                try { parent.onUpgrade(wrap(db), oldVersion, newVersion) }
                catch (failure: Throwable) {
                    // This is the real initialization fingerprint rejection caused by our
                    // one real old-row mutation. Never synthesize it from hits or a flag.
                    if (fault == Fault.OLD_ROW_AFTER_FIRST_NEW_TABLE && hits == 1 &&
                        generateSequence(failure) { it.cause }.any { it.message == "COMMAND_MIGRATION_CHANGED_OLD_ROWS" }) {
                        check(originalFailure == null); originalFailure = failure
                    }
                    throw failure
                }
            }
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onDowngrade(wrap(db), oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = parent.onOpen(wrap(db))
            override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase get(): SupportSQLiteDatabase {
                refuseFaultOwnerRetry()
                return wrap(helper.writableDatabase)
            }
            override val readableDatabase get(): SupportSQLiteDatabase {
                refuseFaultOwnerRetry()
                return wrap(helper.readableDatabase)
            }
        }
    }
}
