package com.dayforge.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.export.ConfigMapper
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.HabitTypeConverter
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.ProtocolNextDataRequiresUpgradeException
import com.dayforge.data.repository.SyncV2Mapper
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.service.ActivityRateCalculator
import com.dayforge.domain.service.ScheduleValidator
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnceScheduleStorageTest {
    @get:Rule val storage = PhysicalDatabaseRule()
    private val codec = HabitTypeConverter()
    private val role = ObjectAppearance(IconReference.Role("task.my_task"), "#123456", "object")
    private fun habit() = HabitEntity(name = "once", habitType = HabitType.CHECK_IN,
        iconResId = 0, colorHex = "#000000", schedule = HabitSchedule.Once(),
        failMode = FailMode.LOOSE, completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0, appearance = role)

    @Test fun exactDateHintAndUnknownRoleRemainStableAcrossRoomReopen() = runBlocking {
        for (day in listOf(null, "0001-01-01", "2028-02-29", "9999-12-31")) {
            val id = storage.database.habitDao().insert(habit().copy(name = "once-$day", schedule = HabitSchedule.Once(day)))
            storage.reopen()
            val loaded = storage.database.habitDao().getHabitById(id)!!
            assertEquals(HabitSchedule.Once(day), loaded.schedule)
            assertEquals(role, loaded.appearance)
            assertEquals(0, loaded.iconResId) // No resource fallback was persisted.
            assertEquals("#000000", loaded.colorHex) // Old presentation is not rewritten by new metadata.
        }
    }

    @Test fun invalidOrImpossibleDateHintsFailWithoutNormalization() {
        listOf("", "2027-02-29", "2026-9-01", "0000-01-01", "+10000-01-01", "2026-01-01T00:00:00Z", "2026-04-31").forEach {
            assertTrue(it, runCatching { HabitSchedule.Once(it) }.isFailure)
            assertTrue(it, runCatching { codec.toSchedule("{\"type\":\"once\",\"dueDate\":\"$it\"}") }.isFailure)
        }
        assertTrue(runCatching { codec.toSchedule("{\"type\":\"once\",\"due_date\":\"2028-02-29\"}") }.isFailure)
        assertEquals(HabitSchedule.Daily, codec.toSchedule("{\"type\":\"daily\",\"legacy\":true}"))
    }

    @Test fun invalidAppearanceCannotTurnIntoAnImplicitDefault() {
        assertNull(codec.toAppearance(null))
        assertNull(codec.fromAppearance(null))
        listOf("null", "{}", "[]", "{\"icon\":{\"kind\":\"role\",\"role\":\"task.my_task\"},\"accent_color\":\"bad\",\"icon_tint\":\"theme\"}",
            "{\"icon\":{\"kind\":\"asset\",\"asset_id\":\"not-a-uuid\"},\"accent_color\":\"#123456\",\"icon_tint\":\"theme\"}",
            codec.fromAppearance(role)!!.dropLast(1) + ",\"unknown\":true}").forEach {
            assertTrue(it, runCatching { codec.toAppearance(it) }.isFailure)
        }
        assertEquals(role, codec.toAppearance(codec.fromAppearance(role)))
    }

    @Test fun onceDoesNotAcquireDailyMissesOrRecurringStatisticsAfterDueDate() {
        val once = HabitSchedule.Once("2000-01-01")
        assertTrue(ScheduleValidator.isCheckInAllowedToday(once, 0))
        assertEquals(LocalDate.now(), ScheduleValidator.getNextCheckInDate(once, 0))
        for (day in listOf(LocalDate.of(1999, 12, 31), LocalDate.of(2000, 1, 1), LocalDate.of(2026, 9, 27)))
            assertFalse(ScheduleValidator.isCheckInAllowedOnDate(once, 0, day))
        assertEquals(0, ActivityRateCalculator.getWindowSize(once))
        assertEquals(0, ActivityRateCalculator.getDeductionPerMiss(once))
        assertTrue(ActivityRateCalculator.generateWindowCheckInDays(once, 0, System.currentTimeMillis()).isEmpty())
        assertEquals(100, ActivityRateCalculator.calculate(once, 0, emptyList()))
    }

    @Test fun legacySyncAndExportCannotSilentlyDropMetadataOrOncePolicy() {
        val original = habit()
        for (candidate in listOf(original, original.copy(appearance = null, completionPolicy = null),
            original.copy(schedule = HabitSchedule.Daily, completionPolicy = null),
            original.copy(schedule = HabitSchedule.Daily, appearance = null))) {
            assertTrue(runCatching { SyncV2Mapper.planNode(candidate) }.exceptionOrNull() is ProtocolNextDataRequiresUpgradeException)
            assertTrue(runCatching { ConfigMapper.habitEntityToDto(candidate) }.isFailure)
        }
        val metric = MetricEntity(name = "metric", unit = "kg", iconResId = 0, colorHex = "#000000", appearance = role)
        assertTrue(runCatching { SyncV2Mapper.metric(metric) }.exceptionOrNull() is ProtocolNextDataRequiresUpgradeException)
        assertTrue(runCatching { ConfigMapper.metricEntityToDto(metric) }.isFailure)
        val legacy = original.copy(schedule = HabitSchedule.Daily, appearance = null, completionPolicy = null,
            oneTimeConfirmedVersion = null)
        assertNotNull(SyncV2Mapper.planNode(legacy))
        assertNotNull(ConfigMapper.habitEntityToDto(legacy))
    }
}
