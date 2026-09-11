package com.dayforge.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.MetricEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MetricDaoTest {
    private lateinit var database: HabitDatabase
    private lateinit var metricDao: MetricDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            HabitDatabase::class.java
        ).allowMainThreadQueries().build()
        metricDao = database.metricDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testMetricDaoExists() = runBlocking {
        // Stub: Will pass once MetricDao is implemented
        assertNotNull("MetricDao should be accessible", metricDao)
    }

    @Test
    fun testInsertAndGetMetric() = runBlocking {
        // Stub: Defines expected behavior for insert/query
        val metric = MetricEntity(
            name = "Weight",
            description = "Body weight",
            unit = "kg",
            decimalPlaces = 1,
            iconResId = 0,
            colorHex = "#4CAF50"
        )
        val id = metricDao.insert(metric)
        assertTrue("Insert should return positive ID", id > 0)

        val retrieved = metricDao.getMetricById(id)
        assertNotNull("Should retrieve inserted metric", retrieved)
        assertEquals("Name should match", "Weight", retrieved?.name)
    }

    @Test
    fun testGetAllActiveMetricsFlow() = runBlocking {
        // Stub: Defines expected behavior for Flow query
        val metric = MetricEntity(
            name = "Steps",
            description = "Daily steps",
            unit = "steps",
            decimalPlaces = 0,
            iconResId = 0,
            colorHex = "#2196F3"
        )
        metricDao.insert(metric)

        val metrics = metricDao.getAllActiveMetrics().first()
        assertTrue("Should return metrics", metrics.isNotEmpty())
    }

    @Test
    fun testUpdateMetric() = runBlocking {
        // Stub: Defines expected behavior for update
        val metric = MetricEntity(
            name = "Weight",
            description = "",
            unit = "kg",
            decimalPlaces = 1,
            iconResId = 0,
            colorHex = "#4CAF50"
        )
        val id = metricDao.insert(metric)

        val updated = metric.copy(id = id, name = "Body Weight")
        metricDao.update(updated)

        val retrieved = metricDao.getMetricById(id)
        assertEquals("Name should be updated", "Body Weight", retrieved?.name)
    }

    @Test
    fun testDeleteMetric() = runBlocking {
        // Stub: Defines expected behavior for delete
        val metric = MetricEntity(
            name = "Weight",
            description = "",
            unit = "kg",
            decimalPlaces = 1,
            iconResId = 0,
            colorHex = "#4CAF50"
        )
        val id = metricDao.insert(metric)

        val toDelete = metricDao.getMetricById(id)!!
        metricDao.delete(toDelete)

        val retrieved = metricDao.getMetricById(id)
        assertNull("Metric should be deleted", retrieved)
    }

    @Test
    fun testGetAllMetricsOnce() = runBlocking {
        val metric = MetricEntity(
            name = "Weight",
            description = "",
            unit = "kg",
            decimalPlaces = 1,
            iconResId = 0,
            colorHex = "#4CAF50",
        )
        metricDao.insert(metric)

        val metrics = metricDao.getAllMetricsOnce()
        assertTrue("Should return stored metrics", metrics.isNotEmpty())
    }
}
