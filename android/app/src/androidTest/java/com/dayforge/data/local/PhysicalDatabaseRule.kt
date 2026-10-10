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

    /** Test-only full SQLite image, never a live database/WAL copy or production backup API. */
    fun snapshotClosedDatabase(): ByteArray {
        check(context.packageName == "com.dayforge.testbed" && !database.inTransaction())
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use {
            check(it.moveToFirst() && it.getInt(0) == 0 && it.getInt(1) == it.getInt(2))
        }
        database.close()
        HabitDatabaseProvider.clearInstanceForTesting()
        return try {
            val file = context.getDatabasePath("habit_database")
            val wal = java.io.File(file.path + "-wal")
            check(!wal.exists() || wal.length() == 0L)
            check(file.isFile && file.length() in 1..67_108_864L)
            file.readBytes()
        } finally { database = HabitDatabaseProvider.getInstance(context) }
    }

    /** Replace only this isolated fixture's closed DB; credentials/account binding stay untouched. */
    fun restoreClosedDatabase(image: ByteArray) {
        check(context.packageName == "com.dayforge.testbed" && !database.inTransaction())
        require(image.isNotEmpty() && image.size <= 67_108_864)
        database.close()
        HabitDatabaseProvider.clearInstanceForTesting()
        try {
            val file = context.getDatabasePath("habit_database")
            check(context.deleteDatabase("habit_database") || !file.exists())
            check(file.parentFile!!.isDirectory)
            file.writeBytes(image)
        } finally { database = HabitDatabaseProvider.getInstance(context) }
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
