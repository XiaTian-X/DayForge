package com.dayforge.data.local.dao

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
    @get:org.junit.Rule val storage = com.dayforge.data.local.PhysicalDatabaseRule()
    private lateinit var database: HabitDatabase
    private lateinit var metricDao: MetricDao
    private lateinit var metricLogDao: MetricLogDao

    @Before
    fun setup() {
        database = storage.database
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
        assertEquals(0, metricLogDao.countAll())
        assertNull(metricLogDao.getLatestLog(999))
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

        reopen()
        val retrieved = metricLogDao.getById(id)
        assertEquals(log.copy(id = id), retrieved)
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
        val expectedId = metricLogDao.insert(log)
        val otherId = createTestMetric()
        metricLogDao.insert(log.copy(metricId = otherId, uuid = "unrelated"))

        val logs = metricLogDao.getLogsByMetric(metricId).first()
        assertTrue("Should return logs for metric", logs.isNotEmpty())
        assertEquals(listOf(log.copy(id = expectedId)), logs)
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
        val now = 1000L
        val log = MetricLogEntity(
            metricId = metricId,
            date = now,
            value = 75.0,
            unit = "kg"
        )
        val middle = metricLogDao.insert(log)
        val start = metricLogDao.insert(log.copy(uuid = "start", date = 500))
        val last = metricLogDao.insert(log.copy(uuid = "last", date = 1499))
        metricLogDao.insert(log.copy(uuid = "before", date = 499))
        metricLogDao.insert(log.copy(uuid = "end", date = 1500))
        metricLogDao.insert(log.copy(uuid = "other", metricId = createTestMetric()))
        reopen()
        val logs = metricLogDao.getLogsInRange(metricId, 500, 1500)
        assertEquals(listOf(start, middle, last), logs.map { it.id })
        assertEquals(logs, metricLogDao.getLogsInRangeSync(metricId, 500, 1500))
        assertEquals(emptyList<MetricLogEntity>(), metricLogDao.getLogsInRange(metricId, 500, 500))
        assertTrue("Should return logs in range", logs.isNotEmpty())
    }

    @Test
    fun testCascadeDelete() = runBlocking {
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
        reopen()

        // Logs should be cascade deleted
        val logs = metricLogDao.getAllLogsForMetric(metricId)
        assertTrue("Logs should be deleted with metric", logs.isEmpty())
    }

    @Test
    fun batchForeignKeyFailureRollsBackFactsAndOutboxAfterReopen() = runBlocking {
        val metricId = createTestMetric()
        val before = database.syncOutboxDao().getAll()
        val good = MetricLogEntity(metricId = metricId, date = 1000, value = 1.0, unit = "kg")
        val error = runCatching {
            metricLogDao.insertAll(listOf(good, good.copy(uuid = "missing-parent", metricId = 999999)))
        }.exceptionOrNull()
        assertTrue(error is android.database.sqlite.SQLiteConstraintException)
        reopen()
        assertEquals(0, metricLogDao.countAll())
        assertEquals(before, database.syncOutboxDao().getAll())
        metricLogDao.insertAll(listOf(good, good.copy(uuid = "valid-second")))
        reopen()
        assertEquals(2, metricLogDao.countAll())
        assertEquals(listOf(good.uuid, "valid-second"), database.syncOutboxDao().getAll()
            .filter { it.recordType == "metric_log" }.map { it.entityUuid })
    }

    private fun reopen() {
        database = storage.reopen()
        metricDao = database.metricDao()
        metricLogDao = database.metricLogDao()
    }
}
