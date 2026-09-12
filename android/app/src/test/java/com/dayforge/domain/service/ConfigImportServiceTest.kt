package com.dayforge.domain.service

import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.export.dto.ConfigExportDto
import com.dayforge.data.export.ConfigMapper
import com.dayforge.data.export.dto.HabitMetricLinkConfigDto
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ConfigImportServiceTest {
    private lateinit var database: HabitDatabase
    private lateinit var service: ConfigImportService

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        HabitDatabaseProvider.clearInstanceForTesting()
        context.deleteDatabase("habit_database")
        database = HabitDatabaseProvider.getInstance(context)
        service = ConfigImportService(
            database.habitDao(),
            database.metricDao(),
            database.habitMetricLinkDao(),
            database
        )
    }

    @After
    fun teardown() {
        database.close()
        HabitDatabaseProvider.clearInstanceForTesting()
    }

    @Test
    fun `invalid link leaves configuration and generated outbox unchanged`() = runTest {
        database.habitDao().insert(
            HabitEntity(
                name = "Existing habit",
                description = "",
                habitType = HabitType.CHECK_IN,
                iconResId = 1,
                colorHex = "#2196F3",
                schedule = HabitSchedule.Daily
            )
        )
        database.metricDao().insert(
            MetricEntity(
                name = "Existing metric",
                unit = "kg",
                iconResId = 1,
                colorHex = "#2196F3"
            )
        )
        val outboxBefore = database.syncOutboxDao().getAll()
        val malformedImport = Json.encodeToString(
            ConfigExportDto(
                links = listOf(
                    HabitMetricLinkConfigDto(
                        uuid = "missing-link",
                        habitUuid = "missing-habit",
                        metricUuid = "missing-metric"
                    )
                )
            )
        )

        val result = service.importConfig(malformedImport)

        assertTrue(result.isFailure)
        assertNotNull(database.habitDao().getHabitByName("Existing habit"))
        assertNotNull(database.metricDao().getMetricByName("Existing metric"))
        assertEquals(outboxBefore, database.syncOutboxDao().getAll())
    }

    @Test
    fun `write failure after replacement starts rolls back rows and outbox`() = runTest {
        val original = HabitEntity(name = "Original", habitType = HabitType.CHECK_IN,
            iconResId = 1, colorHex = "#2196F3", schedule = HabitSchedule.Daily)
        database.habitDao().insert(original)
        val outbox = database.syncOutboxDao().getAll()
        val habit = ConfigMapper.habitEntityToDto(original.copy(uuid = java.util.UUID.randomUUID().toString(), name = "New"))
        val metric = ConfigMapper.metricEntityToDto(MetricEntity(name = "New metric", unit = "kg", iconResId = 1, colorHex = "#2196F3"))
        val config = ConfigExportDto(habits = listOf(habit), metrics = listOf(metric), links = listOf(
            HabitMetricLinkConfigDto(uuid = java.util.UUID.randomUUID().toString(), habitUuid = habit.uuid, metricUuid = metric.uuid)))
        val linkDao = mockk<com.dayforge.data.local.dao.HabitMetricLinkDao>()
        coEvery { linkDao.deleteAll() } coAnswers { database.habitMetricLinkDao().deleteAll() }
        coEvery { linkDao.insert(any()) } throws java.io.IOException("injected write failure")
        val importer = ConfigImportService(database.habitDao(), database.metricDao(), linkDao, database)

        assertTrue(importer.importConfig(Json.encodeToString(config)).isFailure)
        assertEquals(listOf("Original"), database.habitDao().getAllHabitsOnce().map { it.name })
        assertTrue(database.metricDao().getAllMetricsOnce().isEmpty())
        assertEquals(outbox, database.syncOutboxDao().getAll())
    }

    @Test
    fun `invalid configuration leaves business rows and outbox untouched`() = runTest {
        val original = HabitEntity(name = "Original", habitType = HabitType.CHECK_IN,
            iconResId = 1, colorHex = "#2196F3", schedule = HabitSchedule.Daily)
        database.habitDao().insert(original)
        val outbox = database.syncOutboxDao().getAll()
        val habit = ConfigMapper.habitEntityToDto(original.copy(name = "Imported"))
        val metric = ConfigMapper.metricEntityToDto(MetricEntity(name = "Weight", unit = "kg", iconResId = 1, colorHex = "#2196F3"))
        val valid = ConfigExportDto(habits = listOf(habit), metrics = listOf(metric))
        val invalid = listOf(
            valid.copy(habits = listOf(habit, habit.copy(name = "Duplicate ID"))),
            valid.copy(habits = listOf(habit, habit.copy(uuid = java.util.UUID.randomUUID().toString()))),
            valid.copy(habits = listOf(habit.copy(uuid = "not-a-uuid"))),
            valid.copy(habits = listOf(habit.copy(parentHabitUuid = habit.uuid))),
            valid.copy(habits = listOf(habit, habit.copy(uuid = java.util.UUID.randomUUID().toString(), name = "Child", parentHabitUuid = habit.uuid))),
            valid.copy(habits = listOf(habit.copy(parentHabitUuid = java.util.UUID.randomUUID().toString()))),
            valid.copy(habits = listOf(habit.copy(schedule = "{\"type\":\"custom\",\"frequencyDays\":0}"))),
            valid.copy(habits = listOf(habit.copy(schedule = "{\"type\":\"weekly\",\"daysOfWeek\":[8]}"))),
            valid.copy(habits = listOf(habit.copy(schedule = "{\"type\":\"monthly\",\"dayOfMonth\":0}"))),
            valid.copy(habits = listOf(habit.copy(bestTime = 1440))),
            valid.copy(habits = listOf(habit.copy(type = "TIMER", targetValue = 0))),
            valid.copy(habits = listOf(habit.copy(targetCycles = 0))),
            valid.copy(metrics = listOf(metric.copy(targetDirection = "range", targetValue = 2.0, targetValueUpper = 1.0))),
            valid.copy(metrics = listOf(metric.copy(targetDirection = "range"))),
            valid.copy(metrics = listOf(metric.copy(decimalPlaces = 100))),
            valid.copy(metrics = listOf(metric.copy(aggregationType = "unknown")))
        )
        invalid.forEachIndexed { index, config ->
            assertTrue("Invalid configuration $index", service.importConfig(Json.encodeToString(config)).isFailure)
            assertEquals(listOf("Original"), database.habitDao().getAllHabitsOnce().map { it.name })
            assertEquals(outbox, database.syncOutboxDao().getAll())
        }
    }

    @Test
    fun `valid configuration round trip preserves links and supported values`() = runTest {
        val habitId = database.habitDao().insert(HabitEntity(name = "Weekly", habitType = HabitType.COUNTING,
            iconResId = 1, colorHex = "#2196F3", schedule = HabitSchedule.Weekly(emptyList()), targetValue = 3))
        val metricId = database.metricDao().insert(MetricEntity(name = "Range", unit = "kg", iconResId = 1,
            colorHex = "#2196F3", targetDirection = "range", targetValue = -2.0, targetValueUpper = -2.0))
        val habit = requireNotNull(database.habitDao().getHabitById(habitId))
        val metric = requireNotNull(database.metricDao().getMetricById(metricId))
        val config = ConfigExportDto(habits = listOf(ConfigMapper.habitEntityToDto(habit)),
            metrics = listOf(ConfigMapper.metricEntityToDto(metric)), links = listOf(HabitMetricLinkConfigDto(
                uuid = java.util.UUID.randomUUID().toString(), habitUuid = habit.uuid, metricUuid = metric.uuid)))
        assertTrue(service.importConfig(Json.encodeToString(config)).isSuccess)
        val exported = ConfigExportService(database.habitDao(), database.metricDao(), database.habitMetricLinkDao())
            .exportConfigToJson().getOrThrow()
        assertEquals(config, Json.decodeFromString<ConfigExportDto>(exported))
    }

    @Test
    fun `import and export preserve coroutine cancellation`() = runTest {
        val guard = mockk<StructuralEditGuard>()
        coEvery { guard.requireAllowed() } throws CancellationException("cancelled")
        val importer = ConfigImportService(database.habitDao(), database.metricDao(), database.habitMetricLinkDao(), database, guard)
        val dao = mockk<com.dayforge.data.local.dao.HabitDao>()
        coEvery { dao.getAllHabitsOnce() } throws CancellationException("cancelled")
        val exporter = ConfigExportService(dao, database.metricDao(), database.habitMetricLinkDao())
        var cancelled = 0
        try { importer.importConfig("{}") } catch (_: CancellationException) { cancelled++ }
        try { exporter.exportConfigToJson() } catch (_: CancellationException) { cancelled++ }
        assertEquals(2, cancelled)
    }
}
