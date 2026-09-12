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

    private fun build(context: Context): HabitDatabase =
        Room.databaseBuilder(context, HabitDatabase::class.java, DATABASE_NAME)
            // Unsupported development schemas/downgrades must fail, never erase production data.
            .addMigrations(FactTimeMigration)
            .addCallback(SyncSchemaCallback)
            .build()

    fun setInstanceForTesting(database: HabitDatabase) {
        instance = database
    }

    fun clearInstanceForTesting() {
        instance = null
    }
}
