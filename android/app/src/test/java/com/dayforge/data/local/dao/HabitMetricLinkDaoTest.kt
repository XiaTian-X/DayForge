package com.dayforge.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HabitMetricLinkDaoTest {
    private lateinit var database: HabitDatabase
    private lateinit var habitDao: HabitDao
    private lateinit var metricDao: MetricDao
    private lateinit var linkDao: HabitMetricLinkDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            HabitDatabase::class.java
        ).allowMainThreadQueries().build()
        habitDao = database.habitDao()
        metricDao = database.metricDao()
        linkDao = database.habitMetricLinkDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun createTestHabit(): Long {
        val habit = HabitEntity(
            name = "Running",
            description = "Daily run",
            habitType = HabitType.TIMER,
            iconResId = 1,
            colorHex = "#4CAF50",
            schedule = HabitSchedule.Daily,
            targetValue = 30
        )
        return habitDao.insert(habit)
    }

    private suspend fun createTestMetric(): Long {
        val metric = MetricEntity(
            name = "Steps",
            description = "",
            unit = "steps",
            decimalPlaces = 0,
            iconResId = 0,
            colorHex = "#2196F3"
        )
        return metricDao.insert(metric)
    }

    @Test
    fun testHabitMetricLinkDaoExists() = runBlocking {
        assertNotNull("HabitMetricLinkDao should be accessible", linkDao)
    }

    @Test
    fun testInsertLink() = runBlocking {
        val habitId = createTestHabit()
        val metricId = createTestMetric()
        val habitUuid = habitDao.getHabitById(habitId)?.uuid ?: ""
        val metricUuid = metricDao.getMetricById(metricId)?.uuid ?: ""

        val link = HabitMetricLinkEntity(
            habitId = habitId,
            habitUuid = habitUuid,
            metricId = metricId,
            metricUuid = metricUuid
        )
        val id = linkDao.insert(link)
        assertTrue("Insert should return positive ID", id > 0)
    }

    @Test
    fun testGetLinksByHabit() = runBlocking {
        val habitId = createTestHabit()
        val metricId = createTestMetric()
        val habitUuid = habitDao.getHabitById(habitId)?.uuid ?: ""
        val metricUuid = metricDao.getMetricById(metricId)?.uuid ?: ""

        val link = HabitMetricLinkEntity(
            habitId = habitId,
            habitUuid = habitUuid,
            metricId = metricId,
            metricUuid = metricUuid
        )
        linkDao.insert(link)

        val links = linkDao.getLinksByHabit(habitId).first()
        assertTrue("Should return links for habit", links.isNotEmpty())
    }

    @Test
    fun testGetLinksByMetric() = runBlocking {
        val habitId = createTestHabit()
        val metricId = createTestMetric()
        val habitUuid = habitDao.getHabitById(habitId)?.uuid ?: ""
        val metricUuid = metricDao.getMetricById(metricId)?.uuid ?: ""

        val link = HabitMetricLinkEntity(
            habitId = habitId,
            habitUuid = habitUuid,
            metricId = metricId,
            metricUuid = metricUuid
        )
        linkDao.insert(link)

        val links = linkDao.getLinksByMetric(metricId).first()
        assertTrue("Should return links for metric", links.isNotEmpty())
    }

    @Test
    fun testUniqueConstraint() = runBlocking {
        // Stub: Defines expected unique constraint on (habitId, metricId)
        val habitId = createTestHabit()
        val metricId = createTestMetric()
        val habitUuid = habitDao.getHabitById(habitId)?.uuid ?: ""
        val metricUuid = metricDao.getMetricById(metricId)?.uuid ?: ""

        val link1 = HabitMetricLinkEntity(
            habitId = habitId,
            habitUuid = habitUuid,
            metricId = metricId,
            metricUuid = metricUuid
        )
        val id1 = linkDao.insert(link1)
        assertTrue("First insert should succeed", id1 > 0)

        // Second insert with same habitId + metricId should be ignored
        val link2 = HabitMetricLinkEntity(
            habitId = habitId,
            habitUuid = habitUuid,
            metricId = metricId,
            metricUuid = metricUuid
        )
        val id2 = linkDao.insertOrIgnore(link2)
        assertEquals("Duplicate insert should be ignored", -1L, id2)
    }

    @Test
    fun testCascadeDeleteFromHabit() = runBlocking {
        // Stub: Defines expected CASCADE from habit deletion
        val habitId = createTestHabit()
        val metricId = createTestMetric()
        val habitUuid = habitDao.getHabitById(habitId)?.uuid ?: ""
        val metricUuid = metricDao.getMetricById(metricId)?.uuid ?: ""

        val link = HabitMetricLinkEntity(
            habitId = habitId,
            habitUuid = habitUuid,
            metricId = metricId,
            metricUuid = metricUuid
        )
        linkDao.insert(link)

        // Delete habit
        val habit = habitDao.getHabitById(habitId)!!
        habitDao.delete(habit)

        // Link should be cascade deleted
        val links = linkDao.getAllLinksForHabit(habitId)
        assertTrue("Links should be deleted with habit", links.isEmpty())
    }

    @Test
    fun testCascadeDeleteFromMetric() = runBlocking {
        // Stub: Defines expected CASCADE from metric deletion
        val habitId = createTestHabit()
        val metricId = createTestMetric()
        val habitUuid = habitDao.getHabitById(habitId)?.uuid ?: ""
        val metricUuid = metricDao.getMetricById(metricId)?.uuid ?: ""

        val link = HabitMetricLinkEntity(
            habitId = habitId,
            habitUuid = habitUuid,
            metricId = metricId,
            metricUuid = metricUuid
        )
        linkDao.insert(link)

        // Delete metric
        val metric = metricDao.getMetricById(metricId)!!
        metricDao.delete(metric)

        // Link should be cascade deleted
        val links = linkDao.getAllLinksForMetric(metricId)
        assertTrue("Links should be deleted with metric", links.isEmpty())
    }
}