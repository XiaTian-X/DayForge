package com.dayforge.data.local.dao

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.MetricEntity
import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MetricDaoTest {
    @get:org.junit.Rule val storage = com.dayforge.data.local.PhysicalDatabaseRule()
    private lateinit var database: HabitDatabase
    private lateinit var metricDao: MetricDao

    @Before
    fun setup() {
        database = storage.database
        metricDao = database.metricDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testMetricDaoExists() = runBlocking {
        assertNotNull("MetricDao should be accessible", metricDao)
        assertEquals(emptyList<MetricEntity>(), metricDao.getAllMetricsOnce())
        assertNull(metricDao.getMetricById(999))
    }

    @Test
    fun testInsertAndGetMetric() = runBlocking {
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

        reopen()
        val retrieved = metricDao.getMetricById(id)
        assertEquals(metric.copy(id = id), retrieved)
        assertNotNull("Should retrieve inserted metric", retrieved)
        assertEquals("Name should match", "Weight", retrieved?.name)
    }

    @Test
    fun testGetAllActiveMetricsFlow() = runBlocking {
        val metric = MetricEntity(
            name = "Steps",
            description = "Daily steps",
            unit = "steps",
            decimalPlaces = 0,
            iconResId = 0,
            colorHex = "#2196F3", createdAt = 100
        )
        val firstId = metricDao.insert(metric)
        val newestId = metricDao.insert(metric.copy(name = "New", uuid = "new", createdAt = 300))
        val hiddenId = metricDao.insert(metric.copy(name = "Hidden", uuid = "hidden", isActive = false, createdAt = 500))

        val metrics = metricDao.getAllActiveMetrics().first()
        assertTrue("Should return metrics", metrics.isNotEmpty())
        assertEquals(listOf(newestId, firstId), metrics.map { it.id })
        metricDao.getAllActiveMetrics().test {
            assertEquals(listOf(newestId, firstId), awaitItem().map { it.id })
            metricDao.update(requireNotNull(metricDao.getMetricById(hiddenId)).copy(isActive = true))
            assertEquals(listOf(hiddenId, newestId, firstId), awaitItem().map { it.id })
            metricDao.delete(requireNotNull(metricDao.getMetricById(newestId)))
            assertEquals(listOf(hiddenId, firstId), awaitItem().map { it.id })
        }
    }

    @Test
    fun testUpdateMetric() = runBlocking {
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
        reopen()

        val retrieved = metricDao.getMetricById(id)
        assertEquals("Name should be updated", "Body Weight", retrieved?.name)
        assertEquals(updated, retrieved)
    }

    @Test
    fun testDeleteMetric() = runBlocking {
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
        reopen()

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
            colorHex = "#4CAF50", createdAt = 100
        )
        val oldId = metricDao.insert(metric)
        val hiddenId = metricDao.insert(metric.copy(name = "Inactive", uuid = "inactive", isActive = false, createdAt = 200))
        reopen()

        val metrics = metricDao.getAllMetricsOnce()
        assertTrue("Should return stored metrics", metrics.isNotEmpty())
        assertEquals(listOf(hiddenId, oldId), metrics.map { it.id })
        assertFalse(metrics.first().isActive)
    }
    private fun reopen() {
        database = storage.reopen()
        metricDao = database.metricDao()
    }
}
