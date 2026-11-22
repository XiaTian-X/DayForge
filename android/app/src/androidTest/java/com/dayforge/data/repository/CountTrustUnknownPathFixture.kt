package com.dayforge.data.repository

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.dayforge.data.local.*

/** Real same named file and native SQLite operations; only path metadata is made unknown.
 * No SQL/body/return-value fault, fake permission, alternate file or database-copy shortcut. */
internal fun unknownPathDatabase(context: Context): HabitDatabase {
    check(context.packageName == "com.dayforge.testbed")
    val factory = CountTrustOpenHelperFactory(context, UnknownPathOpenHelperFactory())
    return Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
        .addMigrations(FactTimeMigration, OneTimePreparationMigration, CompletionFollowUpMigration, OneTimeTransmissionMigration,
            ObjectAppearanceMigration, PlanMetadataMigration, NextRecoveryMigration, NextRequestMigration, NextAcceptanceMigration,
            NextStructuralCausalMigration, NextSyncStateMigration, CountDayMigration, NextChallengeMigration, NextRestartMigration,
            NextConfigImportMigration, NextAbandonmentMigration, NextReplicaFenceMigration, CountTrustMigration, CountCommandMigration)
        .openHelperFactory(factory).addCallback(SyncSchemaCallback).build().also { CountTrustRuntime.attach(it, factory) }
}

private class UnknownPathOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        var original: SupportSQLiteDatabase? = null
        var observed: SupportSQLiteDatabase? = null
        fun wrap(database: SupportSQLiteDatabase): SupportSQLiteDatabase {
            if (original !== database) {
                original = database
                observed = object : SupportSQLiteDatabase by database { override val path: String get() = "" }
            }
            return requireNotNull(observed)
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
        val native = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
        return object : SupportSQLiteOpenHelper by native {
            override val writableDatabase get() = wrap(native.writableDatabase)
            override val readableDatabase get() = wrap(native.readableDatabase)
        }
    }
}
