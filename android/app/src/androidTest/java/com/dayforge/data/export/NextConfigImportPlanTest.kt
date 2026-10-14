package com.dayforge.data.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.appearance.ConfigBundleOutput
import com.dayforge.data.appearance.ConfigFileFixture
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.ConfigBundle
import com.dayforge.domain.model.IconReference
import java.time.Instant
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NextConfigImportPlanTest {
    private val clock = Instant.parse("2026-10-09T15:01:02.123456Z")
    private fun uuid(n: Int) = "b1620000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private val target = ConfigImportTarget(uuid(800), uuid(801), uuid(802), uuid(803))
    private suspend fun source(value: ConfigBundle = ConfigFileFixture.manifest()) =
        ConfigBundleOutput.create(value, ConfigFileFixture::content)
    private suspend fun plan(value: ConfigBundle = ConfigFileFixture.manifest()): NextConfigImportPlan {
        val input = source(value); var next = 1
        return NextConfigImportPlan(input, NextConfigImportPlan.allocate(input, target, clock) { uuid(next++) })
    }

    @Test fun fullTemplateBuildsAllModesGoalWindowMetricsAndLinksWithoutHistory() = runBlocking<Unit> {
        val value = ConfigFileFixture.manifest(); val prepared = plan(value)
        assertEquals(value.nodes.size, prepared.habits.size)
        assertEquals(HabitType.GOAL, prepared.habits.first().habitType)
        val goal = prepared.habits.single { it.habitType == HabitType.GOAL }
        assertEquals("2026-09-01", goal.planMetadata!!.startDate)
        assertEquals("2026-12-31", goal.planMetadata!!.goalDueDate)
        assertNull(goal.goalSuccess)
        for (row in prepared.habits) {
            val original = value.nodes.single { it.name == row.name }
            assertEquals(original.description, row.description); assertEquals(original.isActive, row.isActive)
            assertEquals(original.parentKey?.let { prepared.identities.ids.getValue("node:$it") }, row.parentHabitId)
            assertEquals(clock.toEpochMilli(), row.createdAt); assertEquals(clock.toString(), row.planMetadata!!.creationTimestamp)
            assertEquals(0L, row.id); assertEquals(0, row.iconResId)
            assertNull(row.oneTimeConfirmedHeadEventUuid); assertNull(row.oneTimeConfirmedCompletionEventUuid)
            original.activity?.let { activity ->
                assertEquals(activity.targetValue, if (row.habitType == HabitType.TIMER) row.targetValue * 60 else row.targetValue)
                assertEquals(activity.isCountdown, row.isCountdown); assertEquals(activity.targetCycles, row.targetCycles)
                assertEquals(activity.preferredMinute?.toLong(), row.bestTime)
                assertEquals(activity.timezone, row.planMetadata!!.timezone)
            }
        }
        assertEquals(0, prepared.habits.single { it.completionPolicy == "one_and_done" }.oneTimeConfirmedVersion)
        val metric = prepared.metrics.single(); val originalMetric = value.metrics.single()
        assertEquals(originalMetric.aggregationType, metric.aggregationType); assertEquals(originalMetric.unit, metric.unit)
        assertEquals(originalMetric.decimalPlaces, metric.decimalPlaces); assertEquals(originalMetric.targetValue, metric.targetValue)
        assertEquals(originalMetric.targetValueUpper, metric.targetValueUpper); assertEquals(originalMetric.targetDirection, metric.targetDirection)
        val hIds = prepared.habits.mapIndexed { i, row -> row.uuid to (i + 40L) }.toMap()
        val mIds = prepared.metrics.mapIndexed { i, row -> row.uuid to (i + 70L) }.toMap()
        val link = prepared.links(hIds, mIds).single(); val originalLink = value.links.single()
        assertEquals(hIds.getValue(link.habitUuid), link.habitId); assertEquals(mIds.getValue(link.metricUuid), link.metricId)
        assertEquals(originalLink.coefficient, link.coefficient, 0.0); assertEquals(originalLink.isActive, link.isActive)
        assertEquals(originalLink.showInDetail, link.showInHabitDetail); assertEquals(originalLink.promptOnComplete, link.promptOnComplete)
        assertEquals(prepared.habits.size + prepared.metrics.size + value.links.size, prepared.creationOperationIds.size)
    }

    @Test fun schedulesAnchorsAndAllAggregationsDoNotUseCurrentDeviceTimezone() = runBlocking<Unit> {
        val full = ConfigFileFixture.manifest()
        val firstMetric = full.metrics.single()
        val value = full.copy(metrics = listOf("average", "sum", "by_time").mapIndexed { i, kind ->
            firstMetric.copy(key = "m$i", name = "Metric $i", aggregationType = kind)
        }, links = listOf(full.links.single().copy(metricKey = "m0")))
        val input = source(value); val saved = NextConfigImportPlan.allocate(input, target, clock)
        val old = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati")); val a = NextConfigImportPlan(input, saved)
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles")); val b = NextConfigImportPlan(input, saved)
            assertEquals(a.habits, b.habits); assertEquals(a.metrics, b.metrics)
            assertEquals(listOf("average", "sum", "by_time"), a.metrics.map { it.aggregationType })
            assertTrue(a.habits.any { it.schedule is HabitSchedule.Weekly }); assertTrue(a.habits.any { it.schedule is HabitSchedule.Monthly })
            val interval = a.habits.single { it.schedule is HabitSchedule.Custom }
            assertEquals(full.nodes.single { it.name == interval.name }.activity!!.schedule.let {
                (it as com.dayforge.domain.model.ConfigSchedule.Interval).startDate
            }, interval.planMetadata!!.startDate)
            assertTrue(a.habits.any { it.schedule is HabitSchedule.Once })
        } finally { TimeZone.setDefault(old) }
    }

    @Test fun savedAllocationRestoresEveryIdentityOriginalOperationAndCreationTimeButANewImportIsDistinct() = runBlocking<Unit> {
        val input = source(); val ids = NextConfigImportPlan.allocate(input, target, clock)
        val restored = ConfigImportIdentities.decode(ids.encode().toByteArray(Charsets.UTF_8))
        val a = NextConfigImportPlan(input, ids); val b = NextConfigImportPlan(input, restored)
        assertEquals(a.identities, b.identities); assertEquals(a.habits, b.habits); assertEquals(a.metrics, b.metrics)
        assertEquals(a.iconPack, b.iconPack); assertEquals(a.themes, b.themes); assertEquals(a.creationOperationIds, b.creationOperationIds)
        val next = NextConfigImportPlan(input, NextConfigImportPlan.allocate(input, target, clock.plusSeconds(1)))
        assertTrue(ids.ids.values.none { it in next.identities.ids.values })
    }

    @Test fun allForeignAssetPackThemeIdsAreRemappedButRolesColorsBytesAndPalettesArePreserved() = runBlocking<Unit> {
        val input = ConfigFileFixture.manifest(); val prepared = plan(input)
        val foreign = input.iconPack!!.assets.map { it.assetId } + input.iconPack!!.packId + input.themes.map { it.themeId }
        assertTrue(prepared.identities.ids.values.none { it in foreign })
        val pack = prepared.iconPack!!; assertEquals(1, pack.revision)
        for (asset in pack.assets) {
            val old = input.iconPack!!.assets.single { it.name == asset.name }
            assertEquals(old.light, asset.light); assertEquals(old.dark, asset.dark)
            assertEquals(old.colorMode, asset.colorMode); assertEquals(old.purpose, asset.purpose)
        }
        for ((role, old) in input.iconPack!!.roles) assertEquals(prepared.identities.ids.getValue("asset:$old"), pack.roles[role])
        for (row in prepared.habits) {
            val old = input.nodes.single { it.name == row.name }.appearance
            assertEquals(old.accentColor, row.appearance!!.accentColor); assertEquals(old.iconTint, row.appearance!!.iconTint)
            when (val icon = old.icon) {
                is IconReference.Role -> assertEquals(icon, row.appearance!!.icon)
                is IconReference.Asset -> assertEquals(IconReference.Asset(prepared.identities.ids.getValue("asset:${icon.assetId}")), row.appearance!!.icon)
            }
        }
        for ((old, theme) in input.themes.zip(prepared.themes)) {
            assertEquals(old.light, theme.light); assertEquals(old.dark, theme.dark); assertEquals(old.name, theme.name)
            assertEquals(old.generatorId, theme.generatorId); assertEquals(old.seed, theme.seed); assertEquals(1, theme.revision)
        }
    }

    @Test fun changedSourceMissingExtraDuplicateMalformedAndReusedForeignIdentitiesRejectBeforeWriting() = runBlocking<Unit> {
        val input = source(); val saved = NextConfigImportPlan.allocate(input, target, clock)
        val first = saved.ids.keys.first()
        val foreign = input.manifest.iconPack!!.assets.first().assetId
        for (bad in listOf(saved.copy(ids = saved.ids - first), saved.copy(ids = saved.ids + ("extra" to uuid(900))),
            saved.copy(ids = saved.ids.mapValues { uuid(900) }), saved.copy(ids = saved.ids + (first to "bad")),
            saved.copy(ids = saved.ids + (first to foreign)), saved.copy(archiveHash = "f".repeat(64)),
            saved.copy(creationTimestamp = "2026-10-09T23:01:02.123456+08:00"))) {
            assertTrue(runCatching { NextConfigImportPlan(input, bad) }.isFailure)
        }
        val full = input.manifest; val changed = source(full.copy(nodes = full.nodes.mapIndexed { i, row ->
            if (i == 0) row.copy(description = "Later changed input") else row
        }))
        assertEquals("CONFIG_IMPORT_SOURCE_CHANGED", assertThrows(IllegalArgumentException::class.java) {
            NextConfigImportPlan(changed, saved)
        }.message)
        for (text in listOf(saved.encode().replace("\"archiveHash\":", "\"extra\":null,\"archiveHash\":"),
            saved.encode().replace("\"ids\":", "\"ids\":{},\"ids\":"),
            saved.encode().replace("\"archiveHash\":\"${saved.archiveHash}\"", "\"archiveHash\":1"))) {
            assertTrue(runCatching { ConfigImportIdentities.decode(text.toByteArray()) }.isFailure)
        }
    }

    @Test fun copiedCollectionsAndActualLocalIdRequirementsCannotBeChangedOrGuessed() = runBlocking<Unit> {
        val input = source(); val allocated = NextConfigImportPlan.allocate(input, target, clock)
        val mutable = allocated.ids.toMutableMap(); val prepared = NextConfigImportPlan(input, allocated.copy(ids = mutable))
        mutable.clear(); assertEquals(allocated.ids, prepared.identities.ids)
        assertThrows(UnsupportedOperationException::class.java) { (prepared.identities.ids as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (prepared.habits as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) {
            ((prepared.habits.single { it.schedule is HabitSchedule.Weekly }.schedule as HabitSchedule.Weekly).daysOfWeek as MutableList).clear()
        }
        val h = prepared.habits.mapIndexed { i, row -> row.uuid to (i + 1L) }.toMap()
        val m = prepared.metrics.mapIndexed { i, row -> row.uuid to (i + 1L) }.toMap()
        for (bad in listOf(emptyMap(), h + (uuid(999) to 1L), h.mapValues { 0L }, h.mapValues { 1L }))
            assertThrows(IllegalArgumentException::class.java) { prepared.links(bad, m) }
        assertThrows(IllegalArgumentException::class.java) { prepared.links(h, emptyMap()) }
    }

    @Test fun emptyTemplateStillOwnsANewImportIdentityAndNoBusinessFactsOrAssets() = runBlocking<Unit> {
        val prepared = plan(ConfigFileFixture.empty())
        assertEquals(setOf("import"), prepared.identities.ids.keys)
        assertTrue(prepared.habits.isEmpty()); assertTrue(prepared.metrics.isEmpty()); assertTrue(prepared.links(emptyMap(), emptyMap()).isEmpty())
        assertNull(prepared.iconPack); assertTrue(prepared.themes.isEmpty()); assertTrue(prepared.creationOperationIds.isEmpty())
    }
}
