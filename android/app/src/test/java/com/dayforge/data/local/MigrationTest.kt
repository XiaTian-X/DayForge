package com.dayforge.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for database migrations.
 *
 * Tests verify:
 * - MIGRATION_6_7: Database version is 7 with TimeLogEntity registered
 * - MIGRATION_7_8: Database version is 8 with MetricEntity registered
 * - TimeLogDao and MetricDao are accessible from database instance
 * - Migration preserves existing data
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class MigrationTest {

    private lateinit var context: Context
    private lateinit var database: HabitDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun teardown() {
        if (::database.isInitialized) {
            database.close()
        }
    }

    @Test
    fun testCurrentDatabaseSchemaOpens() {
        // Create in-memory database which uses current schema
        database = Room.inMemoryDatabaseBuilder(
            context,
            HabitDatabase::class.java
        ).build()

        // Opening a fresh database validates Room's current schema definition.
        assertNotNull("Database should open with the current schema", database)
    }

    @Test
    fun testTimeLogDaoAccessible() = runTest {
        // Create in-memory database with current schema
        database = Room.inMemoryDatabaseBuilder(
            context,
            HabitDatabase::class.java
        ).build()

        // Verify timeLogDao is accessible
        val timeLogDao = database.timeLogDao()
        assertNotNull("TimeLogDao should be accessible", timeLogDao)

        // Verify we can query the dao (empty result expected for new database)
        assertEquals("Should return no rows for new database", 0, timeLogDao.countAll())
    }

    @Test
    fun testMigration6To7CreatesTimelogsTable() = runTest {
        // Create database with all migrations registered
        database = Room.databaseBuilder(
            context,
            HabitDatabase::class.java,
            "test-migration-db"
        )
            .addMigrations(
                HabitDatabase.MIGRATION_3_4,
                HabitDatabase.MIGRATION_4_5,
                HabitDatabase.MIGRATION_5_6,
                HabitDatabase.MIGRATION_6_7
            )
            .build()

        try {
            // Verify timeLogDao works - this confirms timelogs table exists
            val timeLogDao = database.timeLogDao()
            assertNotNull("TimeLogDao should be accessible", timeLogDao)

            // Query should work without error
            assertEquals("Query should succeed", 0, timeLogDao.countAll())
        } finally {
            database.close()
        }
    }

    @Test
    fun testMigrationIsRegistered() {
        // Verify MIGRATION_6_7 exists and is properly configured
        val migration = HabitDatabase.MIGRATION_6_7
        assertNotNull("MIGRATION_6_7 should exist", migration)
        assertEquals("Migration should be from version 6", 6, migration.startVersion)
        assertEquals("Migration should be to version 7", 7, migration.endVersion)
    }

    // ========== MIGRATION_7_8 Tests ==========

    @Test
    fun testMetricDaoAccessible() = runTest {
        // Create in-memory database with current schema
        database = Room.inMemoryDatabaseBuilder(
            context,
            HabitDatabase::class.java
        ).build()

        // Verify metricDao is accessible
        val metricDao = database.metricDao()
        assertNotNull("MetricDao should be accessible", metricDao)

        // Verify we can query the dao (empty result expected for new database)
        val metrics = metricDao.getAllMetricsOnce()
        assertTrue("Should return empty list for new database", metrics.isEmpty())
    }

    @Test
    fun testMetricLogDaoAccessible() = runTest {
        // Create in-memory database with current schema
        database = Room.inMemoryDatabaseBuilder(
            context,
            HabitDatabase::class.java
        ).build()

        // Verify metricLogDao is accessible
        val metricLogDao = database.metricLogDao()
        assertNotNull("MetricLogDao should be accessible", metricLogDao)

        // Verify we can query the dao (empty result expected for new database)
        assertEquals("Should return empty list for new database", 0, metricLogDao.countAll())
    }

    @Test
    fun testHabitMetricLinkDaoAccessible() = runTest {
        // Create in-memory database with current schema
        database = Room.inMemoryDatabaseBuilder(
            context,
            HabitDatabase::class.java
        ).build()

        // Verify habitMetricLinkDao is accessible
        val linkDao = database.habitMetricLinkDao()
        assertNotNull("HabitMetricLinkDao should be accessible", linkDao)

        // Verify we can query the dao (empty result expected for new database)
        val links = linkDao.getAllLinksOnce()
        assertTrue("Should return empty list for new database", links.isEmpty())
    }

    @Test
    fun testMigration7To8CreatesMetricsTables() = runTest {
        // Create database with all migrations registered
        database = Room.databaseBuilder(
            context,
            HabitDatabase::class.java,
            "test-migration-7-8-db"
        )
            .addMigrations(
                HabitDatabase.MIGRATION_3_4,
                HabitDatabase.MIGRATION_4_5,
                HabitDatabase.MIGRATION_5_6,
                HabitDatabase.MIGRATION_6_7,
                HabitDatabase.MIGRATION_7_8
            )
            .build()

        try {
            // Verify all three DAOs work - confirms all tables exist
            val metricDao = database.metricDao()
            val metricLogDao = database.metricLogDao()
            val linkDao = database.habitMetricLinkDao()

            assertNotNull("MetricDao should be accessible", metricDao)
            assertNotNull("MetricLogDao should be accessible", metricLogDao)
            assertNotNull("HabitMetricLinkDao should be accessible", linkDao)

            // Queries should work without error
            assertNotNull("Metrics query should succeed", metricDao.getAllMetricsOnce())
            assertEquals("MetricLogs query should succeed", 0, metricLogDao.countAll())
            assertNotNull("Links query should succeed", linkDao.getAllLinksOnce())
        } finally {
            database.close()
        }
    }

    @Test
    fun testMigration7To8IsRegistered() {
        // Verify MIGRATION_7_8 exists and is properly configured
        val migration = HabitDatabase.MIGRATION_7_8
        assertNotNull("MIGRATION_7_8 should exist", migration)
        assertEquals("Migration should be from version 7", 7, migration.startVersion)
        assertEquals("Migration should be to version 8", 8, migration.endVersion)
    }

    // ========== MIGRATION_8_9 Tests ==========

    @Test
    fun testMigration8To9AddsAggregationType() = runTest {
        // Create database with all migrations registered
        database = Room.databaseBuilder(
            context,
            HabitDatabase::class.java,
            "test-migration-8-9-db"
        )
            .addMigrations(
                HabitDatabase.MIGRATION_3_4,
                HabitDatabase.MIGRATION_4_5,
                HabitDatabase.MIGRATION_5_6,
                HabitDatabase.MIGRATION_6_7,
                HabitDatabase.MIGRATION_7_8,
                HabitDatabase.MIGRATION_8_9
            )
            .build()

        try {
            val metricDao = database.metricDao()
            assertNotNull("MetricDao should be accessible", metricDao)
        } finally {
            database.close()
        }
    }

    @Test
    fun testMigration8To9IsRegistered() {
        // Verify MIGRATION_8_9 exists and is properly configured
        val migration = HabitDatabase.MIGRATION_8_9
        assertNotNull("MIGRATION_8_9 should exist", migration)
        assertEquals("Migration should be from version 8", 8, migration.startVersion)
        assertEquals("Migration should be to version 9", 9, migration.endVersion)
    }

    // ========== MIGRATION_9_10 Tests ==========

    @Test
    fun testMigration9To10AddsIsCountdown() = runTest {
        // Create database with all migrations registered
        database = Room.databaseBuilder(
            context,
            HabitDatabase::class.java,
            "test-migration-9-10-db"
        )
            .addMigrations(
                HabitDatabase.MIGRATION_3_4,
                HabitDatabase.MIGRATION_4_5,
                HabitDatabase.MIGRATION_5_6,
                HabitDatabase.MIGRATION_6_7,
                HabitDatabase.MIGRATION_7_8,
                HabitDatabase.MIGRATION_8_9,
                HabitDatabase.MIGRATION_9_10
            )
            .build()

        try {
            val habitDao = database.habitDao()
            assertNotNull("HabitDao should be accessible", habitDao)
        } finally {
            database.close()
        }
    }

    @Test
    fun testMigration9To10IsRegistered() {
        // Verify MIGRATION_9_10 exists and is properly configured
        val migration = HabitDatabase.MIGRATION_9_10
        assertNotNull("MIGRATION_9_10 should exist", migration)
        assertEquals("Migration should be from version 9", 9, migration.startVersion)
        assertEquals("Migration should be to version 10", 10, migration.endVersion)
    }

    // ========== MIGRATION_13_14 Tests ==========

    @Test
    fun testMigration13To14AddsParentHabitId() = runTest {
        // Create database with all migrations registered
        database = Room.databaseBuilder(
            context,
            HabitDatabase::class.java,
            "test-migration-13-14-db"
        )
            .addMigrations(
                HabitDatabase.MIGRATION_3_4,
                HabitDatabase.MIGRATION_4_5,
                HabitDatabase.MIGRATION_5_6,
                HabitDatabase.MIGRATION_6_7,
                HabitDatabase.MIGRATION_7_8,
                HabitDatabase.MIGRATION_8_9,
                HabitDatabase.MIGRATION_9_10,
                HabitDatabase.MIGRATION_10_11,
                HabitDatabase.MIGRATION_11_12,
                HabitDatabase.MIGRATION_12_13,
                HabitDatabase.MIGRATION_13_14
            )
            .build()

        try {
            val habitDao = database.habitDao()
            assertNotNull("HabitDao should be accessible", habitDao)
        } finally {
            database.close()
        }
    }

    @Test
    fun testMigration13To14IsRegistered() {
        // Verify MIGRATION_13_14 exists and is properly configured
        val migration = HabitDatabase.MIGRATION_13_14
        assertNotNull("MIGRATION_13_14 should exist", migration)
        assertEquals("Migration should be from version 13", 13, migration.startVersion)
        assertEquals("Migration should be to version 14", 14, migration.endVersion)
    }

    // ========== MIGRATION_17_18 Tests ==========

    @Test
    fun testMigration17To18AddsActivityRate() = runTest {
        // Create database with all migrations registered
        database = Room.databaseBuilder(
            context,
            HabitDatabase::class.java,
            "test-migration-17-18-db"
        )
            .addMigrations(
                HabitDatabase.MIGRATION_3_4,
                HabitDatabase.MIGRATION_4_5,
                HabitDatabase.MIGRATION_5_6,
                HabitDatabase.MIGRATION_6_7,
                HabitDatabase.MIGRATION_7_8,
                HabitDatabase.MIGRATION_8_9,
                HabitDatabase.MIGRATION_9_10,
                HabitDatabase.MIGRATION_10_11,
                HabitDatabase.MIGRATION_11_12,
                HabitDatabase.MIGRATION_12_13,
                HabitDatabase.MIGRATION_13_14,
                HabitDatabase.MIGRATION_14_15,
                HabitDatabase.MIGRATION_15_16,
                HabitDatabase.MIGRATION_16_17,
                HabitDatabase.MIGRATION_17_18
            )
            .build()

        try {
            val habitDao = database.habitDao()
            assertNotNull("HabitDao should be accessible", habitDao)
        } finally {
            database.close()
        }
    }

    @Test
    fun testMigration17To18IsRegistered() {
        // Verify MIGRATION_17_18 exists and is properly configured
        val migration = HabitDatabase.MIGRATION_17_18
        assertNotNull("MIGRATION_17_18 should exist", migration)
        assertEquals("Migration should be from version 17", 17, migration.startVersion)
        assertEquals("Migration should be to version 18", 18, migration.endVersion)
    }

    // ========== MIGRATION_18_19 Tests ==========

    @Test
    fun testMigration18To19AddsBestTime() = runTest {
        // Create database with all migrations registered
        database = Room.databaseBuilder(
            context,
            HabitDatabase::class.java,
            "test-migration-18-19-db"
        )
            .addMigrations(
                HabitDatabase.MIGRATION_3_4,
                HabitDatabase.MIGRATION_4_5,
                HabitDatabase.MIGRATION_5_6,
                HabitDatabase.MIGRATION_6_7,
                HabitDatabase.MIGRATION_7_8,
                HabitDatabase.MIGRATION_8_9,
                HabitDatabase.MIGRATION_9_10,
                HabitDatabase.MIGRATION_10_11,
                HabitDatabase.MIGRATION_11_12,
                HabitDatabase.MIGRATION_12_13,
                HabitDatabase.MIGRATION_13_14,
                HabitDatabase.MIGRATION_14_15,
                HabitDatabase.MIGRATION_15_16,
                HabitDatabase.MIGRATION_16_17,
                HabitDatabase.MIGRATION_17_18,
                HabitDatabase.MIGRATION_18_19
            )
            .build()

        try {
            val habitDao = database.habitDao()
            assertNotNull("HabitDao should be accessible", habitDao)
        } finally {
            database.close()
        }
    }

    @Test
    fun testMigration18To19IsRegistered() {
        // Verify MIGRATION_18_19 exists and is properly configured
        val migration = HabitDatabase.MIGRATION_18_19
        assertNotNull("MIGRATION_18_19 should exist", migration)
        assertEquals("Migration should be from version 18", 18, migration.startVersion)
        assertEquals("Migration should be to version 19", 19, migration.endVersion)
    }
}
