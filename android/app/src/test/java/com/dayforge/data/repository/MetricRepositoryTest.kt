package com.dayforge.data.repository

import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.service.StructuralEditGuard
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class MetricRepositoryTest {
    private lateinit var database: HabitDatabase
    private lateinit var repository: MetricRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        HabitDatabaseProvider.clearInstanceForTesting()
        context.deleteDatabase("habit_database")
        database = HabitDatabaseProvider.getInstance(context)
        repository = MetricRepository(
            database,
            database.metricDao(),
            database.metricLogDao(),
            database.habitDao(),
            database.habitMetricLinkDao()
        )
    }

    @After
    fun teardown() {
        database.close()
        HabitDatabaseProvider.clearInstanceForTesting()
    }

    @Test
    fun `create metric and links commits business rows with outbox rows`() = runTest {
        val habitId = database.habitDao().insert(testHabit())
        clearOutbox()

        val metricId = repository.createMetric(testMetric(), setOf(habitId))

        assertNotNull(database.metricDao().getMetricById(metricId))
        assertNotNull(database.habitMetricLinkDao().getLink(habitId, metricId))
        assertEquals(
            setOf("metric", "link"),
            database.syncOutboxDao().getAll().map { it.recordType }.toSet()
        )
    }

    @Test
    fun `structural permission denial leaves metric and outbox unchanged`() = runTest {
        val guard = mockk<StructuralEditGuard>()
        coEvery { guard.requireAllowed() } throws IllegalStateException("denied")
        val guardedRepository = MetricRepository(
            database,
            database.metricDao(),
            database.metricLogDao(),
            database.habitDao(),
            database.habitMetricLinkDao(),
            guard
        )

        val failure = runCatching { guardedRepository.createMetric(testMetric()) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertNull(database.metricDao().getMetricByName("Weight"))
        assertEquals(0, database.syncOutboxDao().count())
    }

    @Test
    fun `missing selected habit rolls back metric and generated outbox`() = runTest {
        val failure = runCatching {
            repository.createMetric(testMetric(), setOf(Long.MAX_VALUE))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertNull(database.metricDao().getMetricByName("Weight"))
        assertEquals(0, database.syncOutboxDao().count())
    }

    @Test
    fun `batch observation failure rolls back earlier observations and outbox`() = runTest {
        val metricId = database.metricDao().insert(testMetric())
        clearOutbox()

        val failure = runCatching {
            repository.recordValues(
                listOf(
                    MetricValueDraft(metricId, 68.5),
                    MetricValueDraft(Long.MAX_VALUE, 10.0)
                ),
                recordedAt = 1_786_000_000_000
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(database.metricLogDao().getAllLogsForMetric(metricId).isEmpty())
        assertEquals(0, database.syncOutboxDao().count())
    }

    @Test
    fun `link batch failure rolls back earlier links and outbox`() = runTest {
        val habitId = database.habitDao().insert(testHabit())
        val metricId = database.metricDao().insert(testMetric())
        val metric = database.metricDao().getMetricById(metricId)!!
        clearOutbox()

        val failure = runCatching {
            repository.linkHabits(metric, linkedSetOf(habitId, Long.MAX_VALUE))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertNull(database.habitMetricLinkDao().getLink(habitId, metricId))
        assertEquals(0, database.syncOutboxDao().count())
    }

    @Test
    fun `record values share one captured occurrence time`() = runTest {
        val firstId = database.metricDao().insert(testMetric())
        val secondId = database.metricDao().insert(testMetric().copy(name = "Sleep", unit = "hour"))
        clearOutbox()
        val recordedAt = 1_786_000_000_123

        repository.recordValues(
            listOf(
                MetricValueDraft(firstId, 68.5, "morning"),
                MetricValueDraft(secondId, 7.5)
            ),
            recordedAt
        )

        assertEquals(recordedAt, database.metricLogDao().getLatestLog(firstId)?.date)
        assertEquals(recordedAt, database.metricLogDao().getLatestLog(secondId)?.date)
        assertEquals(2, database.syncOutboxDao().getAll().count { it.recordType == "metric_log" })
    }

    @Test
    fun `deleting metric captures cascade tombstones in the same commit`() = runTest {
        val habitId = database.habitDao().insert(testHabit())
        val metricId = database.metricDao().insert(testMetric())
        val metric = database.metricDao().getMetricById(metricId)!!
        database.habitMetricLinkDao().insert(
            HabitMetricLinkEntity(
                habitId = habitId,
                habitUuid = database.habitDao().getHabitById(habitId)!!.uuid,
                metricId = metricId,
                metricUuid = metric.uuid
            )
        )
        database.metricLogDao().insert(
            MetricLogEntity(
                metricId = metricId,
                date = 1_786_000_000_000,
                value = 68.5,
                unit = metric.unit
            )
        )
        clearOutbox()

        repository.deleteMetric(metric)

        assertNull(database.metricDao().getMetricById(metricId))
        assertEquals(
            setOf("metric", "metric_log", "link"),
            database.syncOutboxDao().getAll()
                .filter { it.action == "delete" }
                .map { it.recordType }
                .toSet()
        )
    }

    private suspend fun clearOutbox() {
        database.syncOutboxDao().getAll().forEach { database.syncOutboxDao().deleteById(it.id) }
    }

    @Test
    fun `non finite observations roll back the entire batch and outbox`() = runTest {
        val metricId = repository.createMetric(testMetric())
        clearOutbox()
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val failure = runCatching {
                repository.recordValues(listOf(MetricValueDraft(metricId, 1.0), MetricValueDraft(metricId, invalid)))
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(database.metricLogDao().getAllLogsForMetric(metricId).isEmpty())
            assertEquals(0, database.syncOutboxDao().count())
        }
        repository.recordValues(listOf(MetricValueDraft(metricId, -1.5), MetricValueDraft(metricId, 0.0)))
        assertEquals(2, database.metricLogDao().getAllLogsForMetric(metricId).size)
        assertEquals(2, database.syncOutboxDao().count())
    }

    @Test
    fun `non finite targets cannot create or overwrite a metric`() = runTest {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val failure = runCatching { repository.createMetric(testMetric().copy(targetValue = invalid)) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertNull(database.metricDao().getMetricByName("Weight"))
            assertEquals(0, database.syncOutboxDao().count())
        }
        val metricId = repository.createMetric(testMetric())
        val metric = database.metricDao().getMetricById(metricId)!!
        clearOutbox()
        val failure = runCatching { repository.updateMetric(metric.copy(targetValueUpper = Double.POSITIVE_INFINITY)) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertNull(database.metricDao().getMetricById(metricId)!!.targetValueUpper)
        assertEquals(0, database.syncOutboxDao().count())
    }

    @Test
    fun `range target validation matches server contract and leaves no rejected mutations`() = runTest {
        for ((lower, upper) in listOf(null to 1.0, 1.0 to null, 2.0 to 1.0)) {
            val failure = runCatching {
                repository.createMetric(testMetric().copy(targetDirection = "range", targetValue = lower, targetValueUpper = upper))
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertNull(database.metricDao().getMetricByName("Weight"))
            assertEquals(0, database.syncOutboxDao().count())
        }
        val metricId = repository.createMetric(testMetric().copy(targetDirection = "range", targetValue = -1.0, targetValueUpper = -1.0))
        val metric = database.metricDao().getMetricById(metricId)!!
        clearOutbox()
        val failure = runCatching { repository.updateMetric(metric.copy(targetValueUpper = -2.0)) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(-1.0, database.metricDao().getMetricById(metricId)!!.targetValueUpper!!, 0.0)
        assertEquals(0, database.syncOutboxDao().count())
    }

    private fun testHabit() = HabitEntity(
        name = "Walk",
        description = "",
        habitType = HabitType.CHECK_IN,
        iconResId = 1,
        colorHex = "#2196F3",
        schedule = HabitSchedule.Daily
    )

    private fun testMetric() = MetricEntity(
        name = "Weight",
        unit = "kg",
        iconResId = 1,
        colorHex = "#2196F3"
    )
}
