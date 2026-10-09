package com.dayforge.data.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.appearance.ConfigBundleOutput
import com.dayforge.data.appearance.ConfigFileFixture
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.model.PlanStructureMetadata
import com.dayforge.domain.model.ConfigSchedule
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import java.time.Instant
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NextConfigMapperTest {
    private val created = "2026-09-27T15:59:59.123456Z"
    private val time = Instant.parse(created).toEpochMilli()
    private fun uuid(id: Int) = "81000000-0000-4000-8000-" + id.toString().padStart(12, '0')
    private fun appearance(role: String) = ObjectAppearance(IconReference.Role(role), "#802196F3", "object")
    private fun habit(id: Int, type: HabitType = HabitType.COUNTING,
        schedule: HabitSchedule = HabitSchedule.Daily): HabitEntity {
        val goal = type == HabitType.GOAL
        val once = schedule is HabitSchedule.Once
        return HabitEntity(id = id.toLong(), uuid = uuid(id), name = "Node $id", description = "Description $id",
            habitType = type, iconResId = 0, colorHex = "#000000", schedule = schedule,
            targetValue = if (type == HabitType.CHECK_IN || goal) 1 else 2,
            targetCycles = if (once) null else 7, failMode = FailMode.LOOSE,
            bestTime = if (once || goal) null else 570L, createdAt = time, updatedAt = time, activityRateUpdatedAt = time,
            completionPolicy = if (goal) null else if (once) "one_and_done" else "recurring",
            oneTimeConfirmedVersion = if (once) 0 else null,
            appearance = appearance(if (goal) "goal.custom" else if (once) "task.custom" else "habit.custom"),
            planMetadata = PlanStructureMetadata(created, id.toLong(),
                if (once) null else "2026-01-15", if (goal) "2026-12-31" else null,
                if (goal) null else "Pacific/Kiritimati", if (type == HabitType.TIMER) "second" else null,
                if (once || goal) null else "09:30", null))
    }
    private fun metric(id: Int = 31, aggregation: String = "by_time") = MetricEntity(id = id.toLong(), uuid = uuid(id),
        name = "Metric $id", description = "Range target", unit = "kg", decimalPlaces = 3, aggregationType = aggregation,
        targetDirection = "range", targetValue = 70.5, targetValueUpper = 90.25, iconResId = 0, colorHex = "#000000",
        createdAt = time, updatedAt = time, appearance = appearance("metric.weight"))
    private fun link(habit: HabitEntity, metric: MetricEntity) = HabitMetricLinkEntity(id = 41, uuid = uuid(41),
        habitId = habit.id, habitUuid = habit.uuid, metricId = metric.id, metricUuid = metric.uuid,
        coefficient = 1.25, showInHabitDetail = false, promptOnComplete = true, isActive = false, createdAt = time, updatedAt = time)
    private fun bundle(habits: List<HabitEntity>, metrics: List<MetricEntity> = emptyList(),
        links: List<HabitMetricLinkEntity> = emptyList(), completed: Set<String> = emptySet()) = NextConfigMapper.bundle(habits, metrics, links, null,
        (habits.mapNotNull { it.appearance?.icon } + metrics.mapNotNull { it.appearance?.icon }).filterIsInstance<IconReference.Role>()
            .map { it.role }.distinct(), emptyList(), completed)

    @Test fun completeTypedGraphPreservesGoalWindowParentsAllModesMetricsAndInactiveLinks() = runBlocking {
        val goal = habit(1, HabitType.GOAL)
        val nodes = listOf(goal, habit(2, HabitType.CHECK_IN), habit(3), habit(4).copy(isCountdown = true),
            habit(5, HabitType.TIMER), habit(6, HabitType.TIMER).copy(isCountdown = true),
            habit(7, HabitType.CHECK_IN, HabitSchedule.Once("2028-02-29")))
            .map { if (it.habitType != HabitType.GOAL) it.copy(parentHabitId = goal.uuid) else it }
        val metric = metric()
        val mapped = bundle(nodes, listOf(metric), listOf(link(nodes[2], metric)))
        assertEquals(listOf("n0", "n1", "n2", "n3", "n4", "n5", "n6"), mapped.nodes.map { it.key })
        assertEquals(List(6) { "n0" }, mapped.nodes.drop(1).map { it.parentKey })
        assertEquals("2026-01-15", mapped.nodes.first().goal!!.startDate)
        assertEquals("2026-12-31", mapped.nodes.first().goal!!.dueDate)
        assertEquals(listOf("check", "count", "count", "duration", "duration", "check"), mapped.nodes.mapNotNull { it.activity?.trackingMode })
        assertEquals(listOf(1, 2, 2, 120, 120, 1), mapped.nodes.mapNotNull { it.activity?.targetValue })
        assertEquals(listOf(false, false, true, false, true, false), mapped.nodes.mapNotNull { it.activity?.isCountdown })
        assertEquals("one_and_done", mapped.nodes.last().activity!!.completionPolicy)
        assertEquals(ConfigSchedule.Once("2028-02-29"), mapped.nodes.last().activity!!.schedule)
        assertEquals(570, mapped.nodes[2].activity!!.preferredMinute)
        assertEquals("Pacific/Kiritimati", mapped.nodes[2].activity!!.timezone)
        val observation = mapped.metrics.single()
        assertEquals("by_time", observation.aggregationType); assertEquals(3, observation.decimalPlaces)
        assertEquals("range", observation.targetDirection); assertEquals(70.5, observation.targetValue!!, 0.0)
        assertEquals(90.25, observation.targetValueUpper!!, 0.0)
        val relation = mapped.links.single()
        assertEquals("n2", relation.activityKey); assertEquals("m0", relation.metricKey)
        assertEquals(1.25, relation.coefficient, 0.0); assertTrue(relation.promptOnComplete)
        assertFalse(relation.showInDetail); assertFalse(relation.isActive)
        assertEquals(mapped, ConfigBundleOutput.create(mapped) { error("No fixed assets") }.manifest)
        val text = Json.encodeToString(mapped)
        for (row in nodes) assertFalse(text.contains(row.uuid))
        assertFalse(text.contains(metric.uuid)); assertFalse(text.contains(created))
        for (field in listOf("goalSuccess", "manual_result", "completion_event", "confirmedVersion", "activityRate", "outbox", "recorded_local_date")) {
            assertFalse("history field $field", text.contains(field))
        }
    }

    @Test fun allSchedulesAndAggregationsRetainAnchorsRegardlessOfDeviceTimezone() {
        val schedules = listOf(HabitSchedule.Daily, HabitSchedule.Weekly(listOf(7, 1, 3)), HabitSchedule.Monthly(31), HabitSchedule.Custom(8))
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val rows = schedules.mapIndexed { index, value -> habit(index + 1, schedule = value) }
            val mapped = bundle(rows, listOf(metric(31, "average"), metric(32, "sum"), metric(33, "by_time")))
            assertEquals(listOf(ConfigSchedule.Daily("2026-01-15"), ConfigSchedule.Weekly("2026-01-15", listOf(1, 3, 7)),
                ConfigSchedule.Monthly("2026-01-15", 31), ConfigSchedule.Interval("2026-01-15", 8)), mapped.nodes.map { it.activity!!.schedule })
            assertEquals(listOf("average", "sum", "by_time"), mapped.metrics.map { it.aggregationType })
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals(mapped, bundle(rows, listOf(metric(31, "average"), metric(32, "sum"), metric(33, "by_time"))))
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun completionResultAndOnceHeadAreNotCarriedIntoANewTemplate() {
        val goal = habit(1, HabitType.GOAL).copy(goalSuccess = true, isActive = false)
        val once = habit(2, HabitType.CHECK_IN, HabitSchedule.Once(null)).copy(oneTimeConfirmedVersion = 3,
            oneTimeConfirmedHeadEventUuid = uuid(70), oneTimeConfirmedCompletionEventUuid = uuid(70))
        assertEquals("CONFIG_COMPLETED_ITEM_NOT_SELECTED", assertThrows(IllegalArgumentException::class.java) {
            bundle(listOf(goal, once))
        }.message)
        assertThrows(IllegalArgumentException::class.java) { bundle(listOf(goal, once), completed = setOf(goal.uuid)) }
        assertThrows(IllegalArgumentException::class.java) { bundle(listOf(goal, once), completed = setOf(uuid(99))) }
        val mapped = bundle(listOf(goal, once), completed = setOf(once.uuid))
        assertFalse(mapped.nodes.first().isActive)
        assertEquals("one_and_done", mapped.nodes.last().activity!!.completionPolicy)
        val tree = Json.encodeToJsonElement(mapped).jsonObject
        assertEquals(setOf("start_date", "due_date", "target_cycles", "fail_mode"), tree.getValue("nodes").jsonArray[0].jsonObject.getValue("goal").jsonObject.keys)
        assertFalse(tree.toString().contains(uuid(70))); assertFalse(tree.toString().contains("\"completion_event_uuid\""))
    }

    @Test fun legacyIncompleteAndUnrepresentablePrecisionAreExplicitFailuresNotSilentLoss() {
        val row = habit(1)
        for (bad in listOf(row.copy(appearance = null), row.copy(completionPolicy = null), row.copy(planMetadata = null),
            row.copy(planMetadata = row.planMetadata!!.copy(preferredLocalTime = "09:30:45.123456")),
            row.copy(planMetadata = row.planMetadata!!.copy(originAssignmentId = uuid(99))))) {
            assertTrue(runCatching { bundle(listOf(bad)) }.isFailure)
        }
        assertThrows(ArithmeticException::class.java) { bundle(listOf(habit(2, HabitType.TIMER).copy(targetValue = Int.MAX_VALUE))) }
        assertThrows(IllegalArgumentException::class.java) { bundle(emptyList(), listOf(metric().copy(appearance = null))) }
    }

    @Test fun completeCurrentOnceMapControlsTemplateGuardWithoutOverwritingConfirmedHeaders() {
        val confirmed = habit(1, HabitType.CHECK_IN, HabitSchedule.Once(null)).copy(oneTimeConfirmedVersion = 1,
            oneTimeConfirmedHeadEventUuid = uuid(70), oneTimeConfirmedCompletionEventUuid = uuid(70))
        fun mapped(states: Map<String, com.dayforge.domain.model.OneTimeState>) = NextConfigMapper.bundle(listOf(confirmed),
            emptyList(), emptyList(), null, listOf("task.custom"), emptyList(), currentOneTimeStates = states)
        assertEquals("CONFIG_COMPLETED_ITEM_NOT_SELECTED", assertThrows(IllegalArgumentException::class.java) {
            mapped(mapOf(confirmed.uuid to com.dayforge.domain.model.OneTimeState(1, uuid(70), uuid(70))))
        }.message)
        assertEquals("one_and_done", mapped(mapOf(confirmed.uuid to com.dayforge.domain.model.OneTimeState(2, uuid(71), null)))
            .nodes.single().activity!!.completionPolicy)
        assertNotNull(confirmed.oneTimeConfirmedCompletionEventUuid)
        for (states in listOf(emptyMap(), mapOf(uuid(99) to com.dayforge.domain.model.OneTimeState(0, null, null)))) {
            assertEquals("CONFIG_ONCE_SNAPSHOT_INCOMPLETE", assertThrows(IllegalArgumentException::class.java) { mapped(states) }.message)
        }
    }

    @Test fun badIdentitiesParentKindsLinkIdsAndDuplicateEndpointsFailInsteadOfDroppingRows() {
        val row = habit(1); val goal = habit(2, HabitType.GOAL); val metric = metric()
        for (rows in listOf(listOf(row, row), listOf(row.copy(id = 0)), listOf(row, goal.copy(uuid = row.uuid)),
            listOf(row, goal.copy(id = row.id)), listOf(row.copy(parentHabitId = uuid(99))),
            listOf(row, habit(3).copy(parentHabitId = row.uuid)), listOf(goal.copy(parentHabitId = row.uuid), row))) {
            assertTrue(runCatching { bundle(rows) }.isFailure)
        }
        val valid = link(row, metric)
        for (bad in listOf(valid.copy(id = 0), valid.copy(uuid = "invalid"), valid.copy(habitId = 999), valid.copy(metricId = 999), valid.copy(habitUuid = goal.uuid),
            valid.copy(metricUuid = uuid(98)), valid.copy(coefficient = Double.NaN))) {
            assertTrue(runCatching { bundle(listOf(row, goal), listOf(metric), listOf(bad)) }.isFailure)
        }
        assertThrows(IllegalArgumentException::class.java) { bundle(listOf(row), listOf(metric), listOf(valid, valid.copy(uuid = uuid(42)))) }
        assertThrows(IllegalArgumentException::class.java) { bundle(emptyList(), listOf(metric.copy(uuid = "invalid"))) }
    }

    @Test fun fixedReferencesRetainExactAssetAndRequireCompleteMetadataClosure() {
        val full = ConfigFileFixture.manifest()
        val asset = full.iconPack!!.assets.first()
        val row = habit(1).copy(appearance = ObjectAppearance(IconReference.Asset(asset.assetId), "#802196F3", "object"))
        assertThrows(IllegalArgumentException::class.java) { bundle(listOf(row)) }
        val mapped = NextConfigMapper.bundle(listOf(row), emptyList(), emptyList(), full.iconPack, emptyList(), full.themes)
        assertEquals(row.appearance, mapped.nodes.single().appearance)
        assertEquals(full.iconPack, mapped.iconPack); assertEquals(full.themes, mapped.themes)
        assertThrows(IllegalArgumentException::class.java) {
            NextConfigMapper.bundle(listOf(row.copy(appearance = row.appearance!!.copy(icon = IconReference.Asset(full.iconPack.assets.last().assetId)))),
                emptyList(), emptyList(), full.iconPack, emptyList(), emptyList())
        }
    }
}
