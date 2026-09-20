package com.dayforge.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.ExternalResource

/** Uses the production on-disk builder, migrations and triggers, isolated to the test APK. */
class PhysicalDatabaseRule : ExternalResource() {
    private lateinit var context: Context
    lateinit var database: HabitDatabase
        private set

    override fun before() {
        context = ApplicationProvider.getApplicationContext()
        check(context.packageName == "com.dayforge.testbed")
        HabitDatabaseProvider.clearInstanceForTesting()
        context.deleteDatabase("habit_database")
        database = HabitDatabaseProvider.getInstance(context)
    }

    fun reopen(): HabitDatabase {
        database.close()
        HabitDatabaseProvider.clearInstanceForTesting()
        database = HabitDatabaseProvider.getInstance(context)
        return database
    }

    override fun after() {
        try {
            if (::database.isInitialized) database.close()
        } finally {
            if (::context.isInitialized && context.packageName == "com.dayforge.testbed") {
                HabitDatabaseProvider.clearInstanceForTesting()
                context.deleteDatabase("habit_database")
            }
        }
    }
}
