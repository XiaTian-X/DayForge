package com.dayforge.domain.service

import com.dayforge.data.export.dto.ConfigExportDto
import com.dayforge.data.export.ConfigMapper
import com.dayforge.data.export.dto.HabitMetricLinkConfigDto
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class ConfigImportServiceTest {
    @get:org.junit.Rule val storage = com.dayforge.data.local.PhysicalDatabaseRule()
    private lateinit var database: HabitDatabase
    private lateinit var service: ConfigImportService

    @Before
    fun setup() {
        database = storage.database
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
    }

    @Test
    fun invalid_link_leaves_configuration_and_generated_outbox_unchanged() = runBlocking {
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
    fun write_failure_after_replacement_starts_rolls_back_rows_and_outbox() = runBlocking {
        val original = HabitEntity(name = "Original", habitType = HabitType.CHECK_IN,
            iconResId = 1, colorHex = "#2196F3", schedule = HabitSchedule.Daily)
        database.habitDao().insert(original)
        val outbox = database.syncOutboxDao().getAll()
        val habit = ConfigMapper.habitEntityToDto(original.copy(uuid = java.util.UUID.randomUUID().toString(), name = "New"))
        val metric = ConfigMapper.metricEntityToDto(MetricEntity(name = "New metric", unit = "kg", iconResId = 1, colorHex = "#2196F3"))
        val config = ConfigExportDto(habits = listOf(habit), metrics = listOf(metric), links = listOf(
            HabitMetricLinkConfigDto(uuid = java.util.UUID.randomUUID().toString(), habitUuid = habit.uuid, metricUuid = metric.uuid)))
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_test_import BEFORE INSERT ON habit_metric_links
            BEGIN SELECT RAISE(ABORT, 'test import link failure'); END
        """.trimIndent())
        val result = service.importConfig(Json.encodeToString(config))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.cause is android.database.sqlite.SQLiteConstraintException)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("test import link failure"))
        database = storage.reopen()
        assertEquals(listOf("Original"), database.habitDao().getAllHabitsOnce().map { it.name })
        assertTrue(database.metricDao().getAllMetricsOnce().isEmpty())
        assertEquals(outbox, database.syncOutboxDao().getAll())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_test_import")
        val retry = ConfigImportService(database.habitDao(), database.metricDao(), database.habitMetricLinkDao(), database)
        assertTrue(retry.importConfig(Json.encodeToString(config)).isSuccess)
        database = storage.reopen()
        assertEquals(listOf("New"), database.habitDao().getAllHabitsOnce().map { it.name })
        assertEquals(listOf("New metric"), database.metricDao().getAllMetricsOnce().map { it.name })
    }

    @Test
    fun invalid_configuration_leaves_business_rows_and_outbox_untouched() = runBlocking {
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
            valid.copy(habits = listOf(habit.copy(schedule = "{\"type\":\"custom\",\"frequencyDays\":3651}"))),
            valid.copy(habits = listOf(habit.copy(schedule = "{\"type\":\"weekly\",\"daysOfWeek\":[8]}"))),
            valid.copy(habits = listOf(habit.copy(schedule = "{\"type\":\"monthly\",\"dayOfMonth\":0}"))),
            valid.copy(habits = listOf(habit.copy(bestTime = 1440))),
            valid.copy(habits = listOf(habit.copy(type = "TIMER", targetValue = 0))),
            valid.copy(habits = listOf(habit.copy(type = "TIMER", targetValue = 35_791_395))),
            valid.copy(habits = listOf(habit.copy(isCountdown = true))),
            valid.copy(habits = listOf(habit.copy(name = "x".repeat(101)))),
            valid.copy(habits = listOf(habit.copy(description = "x".repeat(1001)))),
            valid.copy(habits = listOf(habit.copy(color = "invalid"))),
            valid.copy(habits = listOf(habit.copy(targetCycles = 0))),
            valid.copy(metrics = listOf(metric.copy(targetDirection = "range", targetValue = 2.0, targetValueUpper = 1.0))),
            valid.copy(metrics = listOf(metric.copy(targetDirection = "range"))),
            valid.copy(metrics = listOf(metric.copy(decimalPlaces = 100))),
            valid.copy(metrics = listOf(metric.copy(unit = "x".repeat(51)))),
            valid.copy(metrics = listOf(metric.copy(aggregationType = "unknown")))
        )
        invalid.forEachIndexed { index, config ->
            assertTrue("Invalid configuration $index", service.importConfig(Json.encodeToString(config)).isFailure)
            assertEquals(listOf("Original"), database.habitDao().getAllHabitsOnce().map { it.name })
            assertEquals(outbox, database.syncOutboxDao().getAll())
        }
        val validBoundary = valid.copy(habits = listOf(habit.copy(name = "😀".repeat(100),
            type = "TIMER", targetValue = 35_791_394, schedule = "{\"type\":\"custom\",\"frequencyDays\":3650}")))
        assertTrue(service.importConfig(Json.encodeToString(validBoundary)).isSuccess)
    }

    @Test
    fun valid_configuration_round_trip_preserves_links_and_supported_values() = runBlocking {
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
    fun literalPortableConfigurationPreservesMeaningOnDiskAndInExport() = runBlocking {
        val raw = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            .open("config/literal-portable.json").bufferedReader().use { it.readText() }
        assertTrue(service.importConfig(raw).isSuccess)
        database = storage.reopen()
        val child = requireNotNull(database.habitDao().getHabitByUuid("10000000-0000-4000-8000-000000000002"))
        assertEquals("Drink water", child.name)
        assertEquals("literal child", child.description)
        assertEquals(HabitType.COUNTING, child.habitType)
        assertEquals("10000000-0000-4000-8000-000000000001", child.parentHabitId)
        assertEquals(HabitSchedule.Weekly(listOf(1, 4)), child.schedule)
        assertEquals(3, child.targetValue)
        assertEquals(9, child.targetCycles)
        assertEquals(487L, child.bestTime)
        assertEquals(com.dayforge.data.model.FailMode.LOOSE, child.failMode)
        org.junit.Assert.assertFalse(child.isActive)
        val metric = requireNotNull(database.metricDao().getMetricByUuid("20000000-0000-4000-8000-000000000001"))
        assertEquals("Balance", metric.name)
        assertEquals("kg", metric.unit)
        assertEquals(2, metric.decimalPlaces)
        assertEquals("range", metric.targetDirection)
        assertEquals(-2.0, metric.targetValue)
        assertEquals(5.5, metric.targetValueUpper)
        assertEquals("by_time", metric.aggregationType)
        org.junit.Assert.assertFalse(metric.isActive)
        val link = requireNotNull(database.habitMetricLinkDao().getLink(child.id, metric.id))
        assertEquals("30000000-0000-4000-8000-000000000001", link.uuid)
        assertEquals(2.5, link.coefficient, 0.0)
        org.junit.Assert.assertFalse(link.showInHabitDetail)
        assertTrue(link.promptOnComplete)
        val exported = Json.parseToJsonElement(ConfigExportService(database.habitDao(), database.metricDao(),
            database.habitMetricLinkDao()).exportConfigToJson().getOrThrow()).jsonObject
        val habits = exported.getValue("habits").jsonArray
        assertEquals(listOf("Hydration", "Drink water"), habits.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        val outputChild = habits[1].jsonObject
        assertEquals("COUNTING", outputChild.getValue("type").jsonPrimitive.content)
        assertEquals("water", outputChild.getValue("icon").jsonPrimitive.content)
        assertEquals(487, outputChild.getValue("bestTime").jsonPrimitive.int)
        assertEquals(3, outputChild.getValue("targetValue").jsonPrimitive.int)
        assertEquals("#123456", outputChild.getValue("color").jsonPrimitive.content)
        val outputMetric = exported.getValue("metrics").jsonArray.single().jsonObject
        assertEquals("by_time", outputMetric.getValue("aggregationType").jsonPrimitive.content)
        assertEquals(5.5, outputMetric.getValue("targetValueUpper").jsonPrimitive.double, 0.0)
        val outputLink = exported.getValue("links").jsonArray.single().jsonObject
        assertEquals(2.5, outputLink.getValue("coefficient").jsonPrimitive.double, 0.0)
        assertEquals(false, outputLink.getValue("showInHabitDetail").jsonPrimitive.boolean)
        for (entity in habits + exported.getValue("metrics").jsonArray + exported.getValue("links").jsonArray) {
            org.junit.Assert.assertFalse(entity.jsonObject.keys.any { it in setOf("id", "createdAt", "updatedAt") })
        }
    }

    @Test
    fun import_and_export_preserve_coroutine_cancellation() = runBlocking {
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
