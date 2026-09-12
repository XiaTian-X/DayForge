package com.dayforge.domain.service

import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.export.dto.ConfigExportDto
import com.dayforge.data.export.dto.HabitMetricLinkConfigDto
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import kotlinx.coroutines.test.runTest
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
    fun `invalid link rolls back cleared configuration and generated outbox`() = runTest {
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
}
