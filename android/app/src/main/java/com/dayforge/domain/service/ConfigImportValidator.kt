package com.dayforge.domain.service

import com.dayforge.data.export.ConfigMapper
import com.dayforge.data.export.dto.ConfigExportDto
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import java.util.UUID

/** Reject malformed configuration before replacement can delete existing data. */
internal object ConfigImportValidator {
    fun validate(config: ConfigExportDto) {
        validateIds(config.habits.map { it.uuid })
        validateIds(config.metrics.map { it.uuid })
        validateIds(config.links.map { it.uuid })
        require(config.habits.map { it.name }.distinct().size == config.habits.size) { "Duplicate habit names" }
        require(config.metrics.map { it.name }.distinct().size == config.metrics.size) { "Duplicate metric names" }
        val habits = config.habits.associateBy { it.uuid }
        val metrics = config.metrics.associateBy { it.uuid }
        config.habits.forEach { dto ->
            val habit = ConfigMapper.dtoToHabitEntity(dto)
            require(habit.name.isNotBlank()) { "Habit name is required" }
            require(habit.targetCycles == null || habit.targetCycles > 0) { "Target cycles must be positive" }
            require(habit.targetValue >= 0) { "Target value must not be negative" }
            if (habit.habitType == HabitType.COUNTING || habit.habitType == HabitType.TIMER) {
                require(habit.targetValue > 0) { "Count and timer targets must be positive" }
            }
            require(habit.bestTime == null || habit.bestTime in 0..1439) { "Invalid preferred time" }
            if (habit.habitType == HabitType.GOAL) {
                require(dto.parentHabitUuid == null) { "Goals must be top-level" }
            }
            dto.parentHabitUuid?.let { parentId ->
                val parent = requireNotNull(habits[parentId]) { "Missing parent goal" }
                require(parent.type == HabitType.GOAL.name && parent.parentHabitUuid == null) { "Parent must be a top-level goal" }
            }
            when (val schedule = habit.schedule) {
                HabitSchedule.Daily -> Unit
                is HabitSchedule.Weekly -> require(schedule.daysOfWeek.all { it in 1..7 }) { "Invalid weekday" }
                is HabitSchedule.Monthly -> require(schedule.dayOfMonth in 1..31) { "Invalid monthly day" }
                is HabitSchedule.Custom -> require(schedule.frequencyDays > 0) { "Frequency must be positive" }
            }
            val visited = mutableSetOf(dto.uuid)
            var parentId = dto.parentHabitUuid
            while (parentId != null) {
                require(visited.add(parentId)) { "Cyclic habit hierarchy" }
                val parent = requireNotNull(habits[parentId]) { "Missing parent habit" }
                parentId = parent.parentHabitUuid
            }
        }
        config.metrics.forEach { metric ->
            require(metric.name.isNotBlank() && metric.unit.isNotBlank()) { "Metric name and unit are required" }
            require(metric.decimalPlaces in 0..6) { "Invalid decimal places" }
            require(metric.aggregationType in setOf("average", "sum", "by_time")) { "Invalid aggregation type" }
            require(metric.targetDirection == null || metric.targetDirection in setOf("increase", "decrease", "range")) { "Invalid target direction" }
            require(metric.targetValue == null || metric.targetValue.isFinite()) { "Invalid target value" }
            require(metric.targetValueUpper == null || metric.targetValueUpper.isFinite()) { "Invalid upper target" }
            if (metric.targetDirection == "range") {
                require(metric.targetValue != null && metric.targetValueUpper != null && metric.targetValueUpper >= metric.targetValue) { "Invalid target range" }
            }
        }
        require(config.links.map { it.habitUuid to it.metricUuid }.distinct().size == config.links.size) { "Duplicate metric links" }
        config.links.forEach { link ->
            require(link.habitUuid in habits && link.metricUuid in metrics) { "Missing linked entity" }
            require(habits.getValue(link.habitUuid).type != HabitType.GOAL.name) { "Metric links require a basic habit" }
            require(link.coefficient.isFinite()) { "Invalid link coefficient" }
        }
    }

    private fun validateIds(ids: List<String>) {
        val canonical = ids.map {
            val value = UUID.fromString(it).toString()
            require(value.equals(it, ignoreCase = true)) { "Invalid configuration UUID" }
            value
        }
        require(canonical.distinct().size == ids.size) { "Duplicate configuration UUIDs" }
    }
}
