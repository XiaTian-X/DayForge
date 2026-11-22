package com.dayforge.data.repository

import android.content.Context
import android.database.Cursor
import android.os.CancellationSignal
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.*
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.dayforge.data.local.*

/** Native observer beneath the production SQL gate. Fixed counter only; no SQL/arguments saved. */
internal class CountDisplayNativeSpy : SupportSQLiteOpenHelper.Factory {
    private var active = false
    private var schemas = 0
    enum class HandoffFault { MAIN_INDEX, NOOP, CANCEL }
    private var fault: HandoffFault? = null
    private var cancelJob: kotlinx.coroutines.Job? = null
    private var authenticatedSchemaSeen = false
    private var touched = false
    @Volatile private var native: SupportSQLiteDatabase? = null
    @Synchronized fun armHandoff(value: HandoffFault, job: kotlinx.coroutines.Job? = null) {
        check(fault == null); fault = value; cancelJob = job; authenticatedSchemaSeen = false; touched = false
    }
    @Synchronized fun handoffTouched(): Boolean = touched
    @Synchronized fun begin() { check(!active); schemas = 0; active = true }
    @Synchronized fun end(): Int { check(active); active = false; return schemas }
    @Synchronized private fun observe(sql: String) {
        if (sql == "SELECT type,name,tbl_name,sql FROM main.sqlite_master ORDER BY type,name") {
            if (active) schemas++
            if (fault != null) authenticatedSchemaSeen = true
        }
        if (fault != null && authenticatedSchemaSeen && sql == "PRAGMA \"main\".schema_version") {
            val selected = requireNotNull(fault); fault = null
            val db = requireNotNull(native); check(db.inTransaction())
            when (selected) {
                HandoffFault.MAIN_INDEX -> db.execSQL("CREATE INDEX count_display_handoff_test ON metrics(name)")
                HandoffFault.NOOP -> db.execSQL("UPDATE metrics SET updatedAt=updatedAt")
                HandoffFault.CANCEL -> requireNotNull(cancelJob).cancel()
            }
            cancelJob = null; touched = true
        }
    }
    fun build(context: Context, queryCallback: RoomDatabase.QueryCallback? = null): HabitDatabase {
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

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        var original: SupportSQLiteDatabase? = null
        var adapted: SupportSQLiteDatabase? = null
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase {
            if (original !== raw) {
                original = raw; native = raw
                adapted = object : SupportSQLiteDatabase by raw {
                    override fun query(query: String): Cursor { observe(query); return raw.query(query) }
                    override fun query(query: String, bindArgs: Array<out Any?>): Cursor {
                        observe(query); return raw.query(query, bindArgs)
                    }
                    override fun query(query: SupportSQLiteQuery): Cursor { observe(query.sql); return raw.query(query) }
                    override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor {
                        observe(query.sql); return raw.query(query, cancellationSignal)
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
