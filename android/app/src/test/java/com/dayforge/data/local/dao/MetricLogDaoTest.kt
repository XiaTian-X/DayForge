package com.dayforge.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MetricLogDaoTest {
    private lateinit var database: HabitDatabase
    private lateinit var metricDao: MetricDao
    private lateinit var metricLogDao: MetricLogDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            HabitDatabase::class.java
        ).allowMainThreadQueries().build()
        metricDao = database.metricDao()
        metricLogDao = database.metricLogDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun createTestMetric(): Long {
        val metric = MetricEntity(
            name = "Weight",
            description = "",
            unit = "kg",
            decimalPlaces = 1,
            iconResId = 0,
            colorHex = "#4CAF50"
        )
        return metricDao.insert(metric)
    }

    @Test
    fun testMetricLogDaoExists() = runBlocking {
        assertNotNull("MetricLogDao should be accessible", metricLogDao)
    }

    @Test
    fun testInsertAndGetLog() = runBlocking {
        val metricId = createTestMetric()
        val log = MetricLogEntity(
            metricId = metricId,
            date = System.currentTimeMillis(),
            value = 75.5,
            unit = "kg"
        )
        val id = metricLogDao.insert(log)
        assertTrue("Insert should return positive ID", id > 0)

        val retrieved = metricLogDao.getById(id)
        assertNotNull("Should retrieve inserted log", retrieved)
        assertEquals(75.5, retrieved?.value!!, 0.01)
    }

    @Test
    fun testGetLogsByMetricFlow() = runBlocking {
        val metricId = createTestMetric()
        val log = MetricLogEntity(
            metricId = metricId,
            date = System.currentTimeMillis(),
            value = 75.0,
            unit = "kg"
        )
        metricLogDao.insert(log)

        val logs = metricLogDao.getLogsByMetric(metricId).first()
        assertTrue("Should return logs for metric", logs.isNotEmpty())
    }

    @Test
    fun testGetLatestLog() = runBlocking {
        val metricId = createTestMetric()
        val log1 = MetricLogEntity(
            metricId = metricId,
            date = System.currentTimeMillis() - 86400000, // Yesterday
            value = 75.0,
            unit = "kg"
        )
        val log2 = MetricLogEntity(
            metricId = metricId,
            date = System.currentTimeMillis(), // Today
            value = 74.5,
            unit = "kg"
        )
        metricLogDao.insert(log1)
        metricLogDao.insert(log2)

        val latest = metricLogDao.getLatestLog(metricId)
        assertNotNull("Should have latest log", latest)
        assertEquals(74.5, latest?.value!!, 0.01)
    }

    @Test
    fun testGetLogsInRange() = runBlocking {
        val metricId = createTestMetric()
        val now = System.currentTimeMillis()
        val log = MetricLogEntity(
            metricId = metricId,
            date = now,
            value = 75.0,
            unit = "kg"
        )
        metricLogDao.insert(log)

        val logs = metricLogDao.getLogsInRange(metricId, now - 86400000, now + 86400000)
        assertTrue("Should return logs in range", logs.isNotEmpty())
    }

    @Test
    fun testCascadeDelete() = runBlocking {
        // Stub: Defines expected CASCADE behavior
        val metricId = createTestMetric()
        val log = MetricLogEntity(
            metricId = metricId,
            date = System.currentTimeMillis(),
            value = 75.0,
            unit = "kg"
        )
        metricLogDao.insert(log)

        // Delete metric
        val metric = metricDao.getMetricById(metricId)!!
        metricDao.delete(metric)

        // Logs should be cascade deleted
        val logs = metricLogDao.getAllLogsForMetric(metricId)
        assertTrue("Logs should be deleted with metric", logs.isEmpty())
    }
}