package com.dayforge.data.export

import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.model.parsePlanTime
import com.dayforge.data.repository.NextStructureMapper
import com.dayforge.domain.model.ConfigActivity
import com.dayforge.domain.model.ConfigBundle
import com.dayforge.domain.model.ConfigGoal
import com.dayforge.domain.model.ConfigLink
import com.dayforge.domain.model.ConfigMetric
import com.dayforge.domain.model.ConfigNode
import com.dayforge.domain.model.ConfigSchedule
import com.dayforge.domain.model.IconPack
import com.dayforge.domain.model.OneTimeState
import com.dayforge.domain.model.ThemeDefinition
import com.dayforge.domain.model.isContractUuid

/**
 * Maps an explicit v5 snapshot only. Does not infer old rows, load history or persist anything.
 * Local keys are generated independently of UUIDs; import must assign new account identities.
 * The repository caller owns account authorization, one consistent Room snapshot and asset closure,
 * including current pending once-state filtering. The optional complete projection map must come
 * from that validated snapshot, not guessed row headers. Standalone callers use confirmed state.
 */
internal object NextConfigMapper {
    fun bundle(habits: List<HabitEntity>, metrics: List<MetricEntity>, links: List<HabitMetricLinkEntity>,
        iconPack: IconPack?, unresolvedRoles: List<String>, themes: List<ThemeDefinition>,
        completedItemTemplateIds: Set<String> = emptySet(),
        currentOneTimeStates: Map<String, OneTimeState>? = null): ConfigBundle {
        require(habits.size <= 1000 && metrics.size <= 1000 && links.size <= 5000)
        require((habits.map { it.uuid } + metrics.map { it.uuid } + links.map { it.uuid }).all(::isContractUuid))
        require(completedItemTemplateIds.all { id -> habits.any { it.uuid == id && it.completionPolicy == "one_and_done" } })
        val onceIds = habits.filter { it.completionPolicy == "one_and_done" }.map { it.uuid }.toSet()
        require(currentOneTimeStates == null || currentOneTimeStates.keys == onceIds) { "CONFIG_ONCE_SNAPSHOT_INCOMPLETE" }
        require(habits.none { it.completionPolicy == "one_and_done" &&
            (if (currentOneTimeStates == null) it.oneTimeConfirmedCompletionEventUuid
                else currentOneTimeStates.getValue(it.uuid).completionEventUuid) != null &&
            it.uuid !in completedItemTemplateIds }) { "CONFIG_COMPLETED_ITEM_NOT_SELECTED" }
        require(habits.map { it.uuid }.distinct().size == habits.size &&
            metrics.map { it.uuid }.distinct().size == metrics.size && links.map { it.uuid }.distinct().size == links.size)
        require(habits.all { it.id > 0 } && habits.map { it.id }.distinct().size == habits.size &&
            metrics.all { it.id > 0 } && metrics.map { it.id }.distinct().size == metrics.size &&
            links.all { it.id > 0 } && links.map { it.id }.distinct().size == links.size)
        val habitKeys = habits.mapIndexed { index, row -> row.uuid to "n$index" }.toMap()
        val metricKeys = metrics.mapIndexed { index, row -> row.uuid to "m$index" }.toMap()
        val byHabit = habits.associateBy { it.uuid }
        val byMetric = metrics.associateBy { it.uuid }
        val nodes = habits.map { row ->
            // Preserve the established wire/domain checks, including minute-to-second overflow.
            NextStructureMapper.writePlan(row)
            val metadata = requireNotNull(row.planMetadata)
            val goal = row.habitType == HabitType.GOAL
            val parent = row.parentHabitId?.let { uuid ->
                require(byHabit[uuid]?.habitType == HabitType.GOAL) { "CONFIG_PARENT_INVALID" }
                habitKeys.getValue(uuid)
            }
            val activity = if (goal) null else {
                // The file contract has minute precision and no assignment identity. Reject a
                // richer source instead of truncating time or cloning a server assignment.
                require(metadata.originAssignmentId == null &&
                    metadata.preferredLocalTime?.let(::parsePlanTime)?.let { it.second == 0 && it.nano == 0 } != false
                ) { "CONFIG_PLAN_NOT_REPRESENTABLE" }
                val schedule = when (val value = row.schedule) {
                    HabitSchedule.Daily -> ConfigSchedule.Daily(metadata.startDate)
                    is HabitSchedule.Weekly -> ConfigSchedule.Weekly(metadata.startDate, value.daysOfWeek.sorted())
                    is HabitSchedule.Monthly -> ConfigSchedule.Monthly(metadata.startDate, value.dayOfMonth)
                    is HabitSchedule.Custom -> ConfigSchedule.Interval(requireNotNull(metadata.startDate), value.frequencyDays)
                    is HabitSchedule.Once -> ConfigSchedule.Once(value.dueDate)
                }
                ConfigActivity(when (row.habitType) {
                    HabitType.CHECK_IN -> "check"; HabitType.COUNTING -> "count"; HabitType.TIMER -> "duration"
                    HabitType.GOAL -> error("Goal is not an activity")
                }, requireNotNull(row.completionPolicy), row.isCountdown,
                    if (row.habitType == HabitType.TIMER) Math.multiplyExact(row.targetValue, 60) else row.targetValue,
                    row.targetCycles, failure(row.failMode), row.bestTime?.toInt(), requireNotNull(metadata.timezone), schedule)
            }
            ConfigNode(habitKeys.getValue(row.uuid), if (goal) "goal" else "activity", row.name, row.description,
                row.isActive, parent, requireNotNull(row.appearance), if (goal)
                    ConfigGoal(metadata.startDate, metadata.goalDueDate, row.targetCycles, failure(row.failMode)) else null, activity)
        }
        val metricRows = metrics.map { row ->
            NextStructureMapper.writeMetric(row)
            ConfigMetric(metricKeys.getValue(row.uuid), row.name, row.description, row.unit, row.isActive,
                row.decimalPlaces, row.aggregationType, row.targetDirection, row.targetValue, row.targetValueUpper,
                requireNotNull(row.appearance))
        }
        val linkRows = links.mapIndexed { index, row ->
            val activity = requireNotNull(byHabit[row.habitUuid]) { "CONFIG_LINK_ACTIVITY_MISSING" }
            val metric = requireNotNull(byMetric[row.metricUuid]) { "CONFIG_LINK_METRIC_MISSING" }
            require(activity.habitType != HabitType.GOAL && row.habitId == activity.id && row.metricId == metric.id)
            ConfigLink("l$index", habitKeys.getValue(row.habitUuid), metricKeys.getValue(row.metricUuid),
                row.coefficient, row.showInHabitDetail, row.promptOnComplete, row.isActive)
        }
        return ConfigBundle("dayforge.config", 2, nodes, metricRows, linkRows, iconPack, unresolvedRoles, themes)
    }

    private fun failure(mode: FailMode) = if (mode == FailMode.STRICT) "strict" else "loose"
}
