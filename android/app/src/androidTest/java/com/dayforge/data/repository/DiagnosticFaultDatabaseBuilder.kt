package com.dayforge.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.dayforge.data.local.*

/** Test-only production-equivalent builder; each caller supplies a private fixed native fault.
 * The real controlled factory remains outside the fault delegate. No production hook is added.
 */
internal fun buildDiagnosticFaultDatabase(
    context: Context,
    fixedFault: SupportSQLiteOpenHelper.Factory,
    queryCallback: RoomDatabase.QueryCallback? = null
): HabitDatabase {
    val factory = CountTrustOpenHelperFactory(context.applicationContext, fixedFault)
    return Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
        .addMigrations(FactTimeMigration, OneTimePreparationMigration, CompletionFollowUpMigration, OneTimeTransmissionMigration, ObjectAppearanceMigration, PlanMetadataMigration, NextRecoveryMigration, NextRequestMigration, NextAcceptanceMigration, NextStructuralCausalMigration, NextSyncStateMigration, CountDayMigration, NextChallengeMigration, NextRestartMigration, NextConfigImportMigration, NextAbandonmentMigration, NextReplicaFenceMigration, CountTrustMigration, CountCommandMigration)
        .openHelperFactory(factory)
        .addCallback(SyncSchemaCallback)
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
