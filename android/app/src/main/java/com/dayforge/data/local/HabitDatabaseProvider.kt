package com.dayforge.data.local

import android.content.Context
import androidx.room.Room

/** Owns construction and process-wide reuse of the Room database instance. */
object HabitDatabaseProvider {
    private const val DATABASE_NAME = "habit_database"

    @Volatile
    private var instance: HabitDatabase? = null

    fun getInstance(context: Context): HabitDatabase = instance ?: synchronized(this) {
        instance ?: build(context.applicationContext).also { instance = it }
    }

    internal fun build(context: Context, queryCallback: androidx.room.RoomDatabase.QueryCallback? = null): HabitDatabase =
        Room.databaseBuilder(context, HabitDatabase::class.java, DATABASE_NAME)
            // Unsupported development schemas/downgrades must fail, never erase production data.
            .addMigrations(FactTimeMigration, OneTimePreparationMigration, CompletionFollowUpMigration, OneTimeTransmissionMigration, ObjectAppearanceMigration, PlanMetadataMigration, NextRecoveryMigration, NextRequestMigration, NextAcceptanceMigration, NextStructuralCausalMigration, NextSyncStateMigration, CountDayMigration, NextChallengeMigration, NextRestartMigration, NextConfigImportMigration, NextAbandonmentMigration, NextReplicaFenceMigration)
            .addCallback(SyncSchemaCallback)
            .apply { if (queryCallback != null) setQueryCallback(queryCallback, java.util.concurrent.Executor { it.run() }) }
            .build()

    fun setInstanceForTesting(database: HabitDatabase) {
        instance = database
    }

    fun clearInstanceForTesting() {
        instance = null
    }
}
